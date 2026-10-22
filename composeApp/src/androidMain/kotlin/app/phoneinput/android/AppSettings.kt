package app.phoneinput.android

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaRecorder
import app.phoneinput.audio.NoiseSuppression
import app.phoneinput.protocol.HostOs
import app.phoneinput.protocol.PhoneInputProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class MicSource(val key: String, val label: String, val description: String, val androidSource: Int) {
    Default("mic", "Default mic", "The phone's main microphone", MediaRecorder.AudioSource.MIC),
    Voice("voice", "Voice", "Tuned for calls, with the phone's own processing", MediaRecorder.AudioSource.VOICE_COMMUNICATION),
    Camcorder("camcorder", "Camcorder mic", "Pointed the same way as the main camera", MediaRecorder.AudioSource.CAMCORDER),
    Unprocessed("raw", "Unprocessed", "Raw signal, no phone processing", MediaRecorder.AudioSource.UNPROCESSED);

    companion object {
        fun of(key: String?): MicSource = entries.firstOrNull { it.key == key } ?: Default
    }
}

/** How the phone reaches a computer. */
enum class Transport(val label: String) {
    WiFi("Wi-Fi"),

    /** TCP to 127.0.0.1 through `adb reverse` over the cable. */
    Usb("USB"),

    /** RFCOMM to a paired computer; [Host.address] is its Bluetooth MAC address. */
    Bluetooth("Bluetooth"),
}

/** A computer the phone can stream to. [customName] is true once the user renamed it. */
data class Host(
    val name: String,
    val address: String,
    val port: Int,
    val os: HostOs,
    val customName: Boolean = false,
    val transport: Transport = Transport.WiFi,
) {
    val usb: Boolean get() = transport == Transport.Usb

    val key: String get() = when (transport) {
        Transport.WiFi -> "$address:$port"
        Transport.Usb -> "usb"
        Transport.Bluetooth -> "bt:$address"
    }

    companion object {
        val Usb = Host("USB cable", "127.0.0.1", PhoneInputProtocol.USB_PORT, HostOs.Unknown, transport = Transport.Usb)

        fun bluetooth(name: String, address: String) = Host(name, address, 0, HostOs.Unknown, transport = Transport.Bluetooth)
    }
}

/** User preferences, persisted in SharedPreferences and observable as StateFlows. */
class AppSettings private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("phoneinput", Context.MODE_PRIVATE)

    inner class BoolPref(private val key: String, default: Boolean) {
        private val flow = MutableStateFlow(prefs.getBoolean(key, default))
        val state: StateFlow<Boolean> = flow.asStateFlow()
        val value: Boolean get() = flow.value

        fun set(v: Boolean) {
            flow.value = v
            prefs.edit().putBoolean(key, v).apply()
        }
    }

    private val _micSource = MutableStateFlow(MicSource.of(prefs.getString(KEY_MIC, null)))
    val micSource: StateFlow<MicSource> = _micSource.asStateFlow()

    private val _noiseSuppression = MutableStateFlow(NoiseSuppression.of(prefs.getString(KEY_NS, null)))
    val noiseSuppression: StateFlow<NoiseSuppression> = _noiseSuppression.asStateFlow()

    val echoCancellation = BoolPref("echoCancellation", false)
    val autoGain = BoolPref("autoGain", false)
    val autoReconnect = BoolPref("autoReconnect", true)

    /** Send Opus instead of raw PCM when the computer supports it. */
    val compression = BoolPref("compression", true)
    val keepScreenOn = BoolPref("keepScreenOn", false)

    /** Check for new releases when the app opens and download them on Wi-Fi. */
    val autoUpdate = BoolPref("autoUpdate", true)

    private val _savedHosts = MutableStateFlow(readSavedHosts())

    /** Computers the user connected to before, most recently used first. Editable and removable. */
    val savedHosts: StateFlow<List<Host>> = _savedHosts.asStateFlow()

    fun setMicSource(source: MicSource) {
        _micSource.value = source
        prefs.edit().putString(KEY_MIC, source.key).apply()
    }

    fun setNoiseSuppression(level: NoiseSuppression) {
        _noiseSuppression.value = level
        prefs.edit().putString(KEY_NS, level.key).apply()
    }

    /** Saves (or moves to the top) a host after connecting. A name the user gave it is kept. */
    fun rememberHost(host: Host) {
        // USB is always offered and Bluetooth lists the phone's paired computers; nothing to save.
        if (host.transport != Transport.WiFi) return
        val existing = _savedHosts.value.firstOrNull { it.key == host.key }
        val saved = if (existing != null && existing.customName) host.copy(name = existing.name, customName = true) else host
        writeSavedHosts(listOf(saved) + _savedHosts.value.filter { it.key != host.key })
    }

    /** Replaces [old] with [updated] (rename and/or new address), keeping its position. */
    fun updateHost(old: Host, updated: Host) {
        val list = _savedHosts.value.filter { it.key == old.key || it.key != updated.key }
        writeSavedHosts(list.map { if (it.key == old.key) updated else it })
    }

    fun removeHost(host: Host) = writeSavedHosts(_savedHosts.value.filter { it.key != host.key })

    private fun writeSavedHosts(list: List<Host>) {
        val trimmed = list.take(MAX_SAVED)
        _savedHosts.value = trimmed
        val json = JSONArray()
        trimmed.forEach {
            json.put(
                JSONObject()
                    .put("name", it.name)
                    .put("custom", it.customName)
                    .put("address", it.address)
                    .put("port", it.port)
                    .put("os", it.os.code),
            )
        }
        prefs.edit().putString(KEY_HOSTS, json.toString()).apply()
    }

    private fun readSavedHosts(): List<Host> {
        val raw = prefs.getString(KEY_HOSTS, null)
        if (raw != null) {
            return runCatching {
                val json = JSONArray(raw)
                List(json.length()) { i ->
                    val o = json.getJSONObject(i)
                    Host(
                        name = o.getString("name"),
                        address = o.getString("address"),
                        port = o.optInt("port", PhoneInputProtocol.AUDIO_PORT),
                        os = HostOs.of(o.optInt("os", 0)),
                        customName = o.optBoolean("custom", false),
                    )
                }
            }.getOrDefault(emptyList())
        }
        // Migrate the single "last host" stored by v0.1.
        val address = prefs.getString("lastHostAddress", null) ?: return emptyList()
        return listOf(
            Host(
                name = prefs.getString("lastHostName", null) ?: address,
                address = address,
                port = prefs.getInt("lastHostPort", PhoneInputProtocol.AUDIO_PORT),
                os = HostOs.of(prefs.getInt("lastHostOs", 0)),
            ),
        )
    }

    companion object {
        private const val KEY_MIC = "micSource"
        private const val KEY_NS = "noiseSuppressionLevel"
        private const val KEY_HOSTS = "savedHosts"
        private const val MAX_SAVED = 12

        @Volatile
        private var instance: AppSettings? = null

        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
    }
}
