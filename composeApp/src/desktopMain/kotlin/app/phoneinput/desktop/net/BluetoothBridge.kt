package app.phoneinput.desktop.net

import app.phoneinput.desktop.Log
import app.phoneinput.desktop.net.bluetooth.BluetoothUnavailableException
import app.phoneinput.desktop.net.bluetooth.Rfcomm
import app.phoneinput.desktop.net.bluetooth.RfcommServer
import app.phoneinput.protocol.HostOs
import java.io.IOException
import kotlin.concurrent.thread

data class BluetoothStatus(
    val supported: Boolean = true,
    /** RFCOMM channel phones connect to; null while not listening. */
    val channel: Int? = null,
    val error: String? = null,
) {
    val listening: Boolean get() = channel != null
}

/**
 * Bluetooth support: keeps an RFCOMM server open for phones paired with this computer and hands
 * each connection to [StreamServer.serve]. While Bluetooth is off (or the adapter is missing) it
 * retries every few seconds, so turning Bluetooth on later just works.
 */
class BluetoothBridge(
    private val server: StreamServer,
    private val os: HostOs,
    private val serviceName: String,
    private val onStatus: (BluetoothStatus) -> Unit,
) : AutoCloseable {
    @Volatile private var running = false
    @Volatile private var rfcomm: RfcommServer? = null

    fun start() {
        if (!Rfcomm.supported(os)) {
            onStatus(BluetoothStatus(supported = false))
            return
        }
        running = true
        thread(name = "pm-bluetooth", isDaemon = true) {
            var lastError: String? = null
            while (running) {
                try {
                    val listener = Rfcomm.listen(os, serviceName)
                    rfcomm = listener
                    lastError = null
                    onStatus(BluetoothStatus(channel = listener.channel))
                    acceptLoop(listener)
                } catch (e: BluetoothUnavailableException) {
                    if (e.message != lastError) Log.i("Bluetooth unavailable: ${e.message}")
                    lastError = e.message
                    onStatus(BluetoothStatus(error = e.message))
                } catch (e: Throwable) {
                    // UnsatisfiedLinkError etc. land here too: report and keep retrying.
                    if (e.message != lastError) Log.w("Bluetooth server failed: ${e.message}")
                    lastError = e.message
                    onStatus(BluetoothStatus(error = "Bluetooth isn't available: ${e.message}"))
                } finally {
                    rfcomm?.let { runCatching { it.close() } }
                    rfcomm = null
                }
                if (running) Thread.sleep(RETRY_MS)
            }
        }
    }

    /** Returns when the listener fails (e.g. Bluetooth turned off) or is closed. */
    private fun acceptLoop(listener: RfcommServer) {
        while (running) {
            val connection = try {
                listener.accept()
            } catch (e: IOException) {
                if (running) Log.w("Bluetooth listener stopped: ${e.message}")
                return
            }
            server.serve(StreamPeer(Transport.Bluetooth, connection.input, connection.output, connection))
        }
    }

    override fun close() {
        running = false
        rfcomm?.let { runCatching { it.close() } }
    }

    private companion object {
        const val RETRY_MS = 5_000L
    }
}
