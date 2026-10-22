package app.phoneinput.desktop

import app.phoneinput.audio.AudioLevels
import app.phoneinput.desktop.audio.AudioPump
import app.phoneinput.desktop.audio.InstallState
import app.phoneinput.desktop.audio.VbCableInstaller
import app.phoneinput.desktop.audio.VirtualMic
import app.phoneinput.desktop.audio.VirtualMicStatus
import app.phoneinput.desktop.net.DiscoveryResponder
import app.phoneinput.desktop.net.HostInfo
import app.phoneinput.desktop.net.StreamServer
import app.phoneinput.desktop.net.AdbBridge
import app.phoneinput.desktop.net.BluetoothBridge
import app.phoneinput.desktop.net.BluetoothStatus
import app.phoneinput.desktop.net.SpeakerStreamer
import app.phoneinput.desktop.net.UsbStatus
import app.phoneinput.desktop.win.Autostart
import app.phoneinput.protocol.PairingInfo
import app.phoneinput.protocol.PairingUri
import app.phoneinput.protocol.HostOs
import app.phoneinput.protocol.PhoneInputProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import app.phoneinput.desktop.update.DesktopInstaller
import app.phoneinput.update.AppVersion
import app.phoneinput.update.UpdateState
import app.phoneinput.update.Updater
import java.awt.Desktop
import java.net.BindException
import java.net.URI
import java.util.prefs.Preferences
import kotlin.system.exitProcess

data class DesktopState(
    val hostName: String,
    val addresses: List<String>,
    /** Connected devices first (oldest connection first), then recently disconnected ones. */
    val devices: List<StreamServer.Device> = emptyList(),
    /** Input level (dBFS) per connected session id, refreshed ~10×/s for the device list. */
    val deviceLevels: Map<Int, Float> = emptyMap(),
    val levels: List<Float> = emptyList(),
    val rmsDb: Float = AudioLevels.FLOOR_DB,
    val peakDb: Float = AudioLevels.FLOOR_DB,
    val bufferMs: Int = 0,
    val lossPercent: Float = 0f,
    val muted: Boolean = false,
    val gainDb: Float = 0f,
    val monitor: Boolean = false,
    val virtualMic: VirtualMicStatus = VirtualMicStatus.Checking,
    /** PhoneInput can fetch and launch the driver setup itself (Windows / VB-CABLE). */
    val canInstallDriver: Boolean = false,
    val driverInstall: InstallState = InstallState.Idle,
    val outputError: Boolean = false,
    val serverError: String? = null,
    val usb: UsbStatus = UsbStatus(),
    val bluetooth: BluetoothStatus = BluetoothStatus(),
    val update: UpdateState = UpdateState.Idle,
    /** Look for updates on start and every few hours, and download them in the background. */
    val autoUpdate: Boolean = true,
    /** Installed Windows builds only: a sign-in entry starts PhoneInput hidden in the tray. */
    val startWithWindows: Boolean = false,
    /** The user chose "Later" for this version; the banner stays hidden until a newer one appears. */
    val dismissedUpdate: AppVersion? = null,
) {
    /** What the QR code encodes: a phoneinput:// link with this computer's LAN addresses. */
    val pairingUri: String? get() = if (addresses.isEmpty()) null else PairingUri.build(PairingInfo(hostName, addresses, PhoneInputProtocol.AUDIO_PORT, HostInfo.os))

    val active: StreamServer.Device? get() = devices.firstOrNull { it.active }
    val connected: List<StreamServer.Device> get() = devices.filter { it.connected }
}

class DesktopController : AutoCloseable {
    private val prefs = Preferences.userRoot().node("app/phoneinput")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hostName = HostInfo.hostName()

    private val _state = MutableStateFlow(
        DesktopState(
            hostName = hostName,
            addresses = HostInfo.lanAddresses(),
            gainDb = prefs.getFloat(KEY_GAIN, 0f),
            monitor = prefs.getBoolean(KEY_MONITOR, false),
            canInstallDriver = HostInfo.os == HostOs.Windows,
            autoUpdate = prefs.getBoolean(KEY_AUTO_UPDATE, true),
            startWithWindows = Autostart.isEnabled(),
        ),
    )
    val state: StateFlow<DesktopState> = _state.asStateFlow()

    private val server = StreamServer()
    private val discovery = DiscoveryResponder(hostName, HostInfo.os, audioPort = { server.boundPort })
    @Volatile
    private var virtualMic: VirtualMic? = null
    private var pump: AudioPump? = null
    private val adb = AdbBridge(PhoneInputProtocol.USB_PORT, HostInfo.os) { status -> _state.update { it.copy(usb = status) } }
    private val speaker = SpeakerStreamer(server, HostInfo.os).also { server.onSpeakerListeners = it::onListeners }
    private val updater = Updater(
        currentVersion = DesktopInstaller.currentVersion,
        downloadDir = DesktopInstaller.downloadDir,
        scope = scope,
        pickAsset = DesktopInstaller::pickAsset,
        install = { file, release ->
            DesktopInstaller.launch(file)
            Log.i("Quitting to install ${release.version}")
            close()
            exitProcess(0)
        },
        log = { message, error -> if (error == null) Log.i(message) else Log.w("$message: ${error.message}") },
    )
    private val bluetooth = BluetoothBridge(server, HostInfo.os, "PhoneInput on $hostName") { status -> _state.update { it.copy(bluetooth = status) } }

    /**
     * Claims both UDP ports exclusively. False means another program (usually another PhoneInput,
     * maybe hidden in the tray) holds them; nothing is left bound in that case.
     */
    fun bindPorts(): Boolean = try {
        server.bind()
        discovery.bind()
        true
    } catch (e: BindException) {
        Log.e("Ports ${PhoneInputProtocol.AUDIO_PORT}/${PhoneInputProtocol.DISCOVERY_PORT}/${PhoneInputProtocol.USB_PORT} are in use", e)
        server.close()
        discovery.close()
        false
    }

    fun start() {
        Log.i("PhoneInput starting on ${HostInfo.os} as $hostName, LAN ${_state.value.addresses}")
        server.start()
        discovery.start()
        adb.start()
        bluetooth.start()

        scope.launch { updater.state.collect { u -> _state.update { it.copy(update = u) } } }
        scope.launch {
            delay(5_000) // let startup settle first
            while (isActive) {
                if (_state.value.autoUpdate) updater.check(autoDownload = true)
                delay(Updater.CHECK_INTERVAL_MS)
            }
        }

        scope.launch {
            server.snapshot.collect { snap -> _state.update { it.copy(devices = snap.devices) } }
        }

        scope.launch {
            while (isActive) {
                delay(100)
                val levels = server.levels()
                if (levels.isNotEmpty() || _state.value.deviceLevels.isNotEmpty()) _state.update { it.copy(deviceLevels = levels) }
            }
        }

        scope.launch(Dispatchers.IO) {
            detectVirtualMic()
            // The pump retries opening the output every 2 s, so a mic that appears later is picked up.
            pump = AudioPump(
                server = server,
                primaryFactory = { virtualMic?.openOutput() },
                controls = { _state.value.let { AudioPump.Controls(it.muted, it.gainDb, it.monitor) } },
                onMeter = { m ->
                    _state.update {
                        it.copy(levels = m.levels, rmsDb = m.rmsDb, peakDb = m.peakDb, outputError = m.outputError && it.virtualMic.ready)
                    }
                },
            ).also { it.start() }
        }

        // Network health once a second: buffer depth, loss, and refreshed LAN addresses.
        scope.launch {
            var last = server.stats()
            var tick = 0
            while (isActive) {
                delay(1_000)
                val now = server.stats()
                val good = now.played - last.played
                val bad = (now.concealed - last.concealed) + (now.dropped - last.dropped)
                last = now
                val loss = if (good + bad > 0) 100f * bad / (good + bad) else 0f
                _state.update { it.copy(bufferMs = server.bufferedMs(), lossPercent = loss) }
                if (++tick % 10 == 0) _state.update { it.copy(addresses = HostInfo.lanAddresses()) }
            }
        }
    }

    private fun detectVirtualMic() {
        virtualMic?.close()
        val vm = VirtualMic.forHost(HostInfo.os)
        virtualMic = vm
        Log.i("Virtual microphone: ${vm.status.deviceName} (ready=${vm.status.ready})")
        _state.update { it.copy(virtualMic = vm.status) }
    }

    /** Re-checks for the virtual microphone, e.g. after the user installed the driver. */
    fun recheckVirtualMic() {
        scope.launch(Dispatchers.IO) {
            detectVirtualMic()
            if (_state.value.virtualMic.ready) _state.update { it.copy(driverInstall = InstallState.Idle) }
        }
    }

    /** Downloads VB-CABLE from vb-audio.com and opens its setup (Windows asks for permission). */
    fun installDriver() {
        val current = _state.value.driverInstall
        if (current is InstallState.Downloading || current is InstallState.WaitingForSetup) return
        scope.launch(Dispatchers.IO) {
            _state.update { it.copy(driverInstall = InstallState.Downloading(null)) }
            val next = try {
                val completed = VbCableInstaller().run { p ->
                    _state.update { s -> if (s.driverInstall is InstallState.Downloading) s.copy(driverInstall = InstallState.Downloading(p)) else s }
                    if (p != null && p >= 1f) _state.update { it.copy(driverInstall = InstallState.WaitingForSetup) }
                }
                if (!completed) {
                    InstallState.Failed("Setup was cancelled. Windows needs your permission to install the driver.")
                } else {
                    detectVirtualMic()
                    if (_state.value.virtualMic.ready) InstallState.Idle else InstallState.NeedsRestart
                }
            } catch (e: Exception) {
                Log.e("VB-CABLE install failed", e)
                InstallState.Failed(e.message ?: "Couldn't install VB-CABLE.")
            }
            _state.update { it.copy(driverInstall = next) }
        }
    }

    /** Downloads Google's platform-tools so USB connections work without the Android SDK. */
    fun installAdb() = adb.installPlatformTools()

    val appVersion: AppVersion? get() = updater.currentVersion

    fun checkForUpdates() {
        _state.update { it.copy(dismissedUpdate = null) }
        updater.check(autoDownload = _state.value.autoUpdate)
    }

    fun downloadUpdate() = updater.download()

    /** Quits PhoneInput, installs the downloaded version and starts it again. */
    fun installUpdate() = updater.install()

    fun dismissUpdate() {
        val version = when (val u = _state.value.update) {
            is UpdateState.Available -> u.release.version
            is UpdateState.Ready -> u.release.version
            else -> return
        }
        _state.update { it.copy(dismissedUpdate = version) }
    }

    fun setAutoUpdate(on: Boolean) {
        _state.update { it.copy(autoUpdate = on) }
        prefs.putBoolean(KEY_AUTO_UPDATE, on)
        if (on) updater.check(autoDownload = true)
    }

    fun setStartWithWindows(on: Boolean) {
        if (Autostart.setEnabled(on)) _state.update { it.copy(startWithWindows = on) }
    }

    fun setMuted(muted: Boolean) = _state.update { it.copy(muted = muted) }

    fun setGain(db: Float) {
        _state.update { it.copy(gainDb = db) }
        prefs.putFloat(KEY_GAIN, db)
    }

    fun setMonitor(on: Boolean) {
        _state.update { it.copy(monitor = on) }
        prefs.putBoolean(KEY_MONITOR, on)
    }

    /** Makes this device the one feeding the virtual microphone. */
    fun select(sessionId: Int) = server.select(sessionId)

    /** Selects the n-th connected device (0-based), e.g. from Ctrl+1…9. */
    fun selectIndex(index: Int) {
        _state.value.connected.getOrNull(index)?.let { select(it.sessionId) }
    }

    fun disconnect(sessionId: Int) = server.disconnect(sessionId)

    fun forgetRecent(sessionId: Int) = server.forgetRecent(sessionId)

    fun clearRecent() = server.clearRecent()

    fun openSoundSettings() {
        val cmd = when (HostInfo.os) {
            HostOs.Windows -> listOf("cmd", "/c", "start", "ms-settings:sound")
            HostOs.Linux -> listOf("pavucontrol")
            else -> return
        }
        runCatching { ProcessBuilder(cmd).start() }
    }

    fun openUrl(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }
    }

    override fun close() {
        Log.i("PhoneInput closing")
        pump?.close()
        adb.close()
        bluetooth.close()
        speaker.close()
        discovery.close()
        server.close()
        virtualMic?.close()
        scope.cancel()
    }

    private companion object {
        const val KEY_GAIN = "gainDb"
        const val KEY_MONITOR = "monitor"
        const val KEY_AUTO_UPDATE = "autoUpdate"
    }
}
