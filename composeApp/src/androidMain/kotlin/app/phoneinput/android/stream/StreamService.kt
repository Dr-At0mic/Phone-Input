package app.phoneinput.android.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.phoneinput.R
import app.phoneinput.android.AppSettings
import app.phoneinput.android.Host
import app.phoneinput.android.MainActivity
import app.phoneinput.android.Transport
import app.phoneinput.protocol.HostOs

/**
 * Foreground service (type microphone) that owns the [StreamEngine], so streaming continues
 * with the screen off or the app in the background.
 */
class StreamService : Service() {
    private var engine: StreamEngine? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val host = intent.toHost()
                if (host == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startInForeground(host)
                engine?.stop()
                val settings = AppSettings.get(this)
                settings.rememberHost(host)
                engine = StreamEngine(this, host, settings, deviceName()) { error ->
                    main.post { shutdown(error) }
                }.also { it.start() }
            }
            ACTION_STOP -> shutdown(error = null)
        }
        return START_NOT_STICKY
    }

    private fun shutdown(error: String?) {
        engine?.stop()
        engine = null
        StreamController.finished(error)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        if (StreamController.isStreaming) StreamController.finished(null)
        super.onDestroy()
    }

    private fun startInForeground(host: Host) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(host), type)
    }

    private fun buildNotification(host: Host): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Streaming", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while PhoneInput is using the microphone"
            },
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(this, 1, stopIntent(this), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Streaming to ${host.name}")
            .setContentText("PhoneInput is using your microphone")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    companion object {
        private const val ACTION_START = "app.phoneinput.START"
        private const val ACTION_STOP = "app.phoneinput.STOP"
        private const val CHANNEL_ID = "streaming"
        private const val NOTIFICATION_ID = 1

        fun startIntent(context: Context, host: Host): Intent =
            Intent(context, StreamService::class.java)
                .setAction(ACTION_START)
                .putExtra("name", host.name)
                .putExtra("address", host.address)
                .putExtra("port", host.port)
                .putExtra("os", host.os.code)
                .putExtra("transport", host.transport.name)

        fun stopIntent(context: Context): Intent =
            Intent(context, StreamService::class.java).setAction(ACTION_STOP)

        private fun Intent.toHost(): Host? {
            val address = getStringExtra("address") ?: return null
            return Host(
                name = getStringExtra("name") ?: address,
                address = address,
                port = getIntExtra("port", app.phoneinput.protocol.PhoneInputProtocol.AUDIO_PORT),
                os = HostOs.of(getIntExtra("os", 0)),
                transport = Transport.entries.firstOrNull { it.name == getStringExtra("transport") } ?: Transport.WiFi,
            )
        }
    }
}
