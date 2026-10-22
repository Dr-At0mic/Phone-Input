package app.phoneinput.android.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import app.phoneinput.R
import app.phoneinput.android.MainActivity
import app.phoneinput.update.AppVersion
import app.phoneinput.update.Release
import app.phoneinput.update.UpdateState
import app.phoneinput.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Updates from the GitHub releases: checks when the app opens (at most every few hours),
 * downloads the APK in the background on unmetered networks, and installs it with
 * [PackageInstaller]. Android shows its own confirmation the first time; once PhoneInput has
 * installed itself, Android 12+ lets later updates go through without asking.
 */
object AppUpdates {
    private const val TAG = "PhoneInput"

    private var updater: Updater? = null
    private val disabled = MutableStateFlow<UpdateState>(UpdateState.Disabled)
    private var lastCheckMs = 0L

    val state: StateFlow<UpdateState> get() = updater?.state ?: disabled
    val currentVersion: AppVersion? get() = updater?.currentVersion

    fun init(context: Context) {
        if (updater != null) return
        val app = context.applicationContext
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        // Debug builds are signed with a different key, so a release APK couldn't replace them.
        val debuggable = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        updater = Updater(
            currentVersion = if (debuggable) null else AppVersion.parse(info.versionName),
            downloadDir = File(app.cacheDir, "updates"),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            pickAsset = { release -> release.asset("PhoneInput-${release.version}.apk") },
            install = { file, release -> installApk(app, file, release) },
            log = { message, error -> if (error == null) Log.i(TAG, message) else Log.w(TAG, message, error) },
        )
    }

    /** Called when the app comes to the front. Downloads only on Wi-Fi (or another unmetered network). */
    fun checkIfDue(context: Context, auto: Boolean) {
        if (!auto || System.currentTimeMillis() - lastCheckMs < Updater.CHECK_INTERVAL_MS) return
        check(context)
    }

    fun check(context: Context) {
        lastCheckMs = System.currentTimeMillis()
        val cm = context.getSystemService(ConnectivityManager::class.java)
        updater?.check(autoDownload = cm != null && !cm.isActiveNetworkMetered)
    }

    fun download() = updater?.download()

    fun install(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(context, "Allow PhoneInput to install updates, then tap Install again.", Toast.LENGTH_LONG).show()
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        updater?.install()
    }

    internal fun installFailed(message: String?) = updater?.installFailed(message)

    private fun installApk(context: Context, file: File, release: Release) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("PhoneInput-${release.version}.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(context, id, Intent(context, UpdateInstallReceiver::class.java), flags)
            session.commit(callback.intentSender)
        }
    }
}

/** Results from [PackageInstaller]: shows Android's confirmation screen or reports a failure. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let {
                    context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // Android replaces and restarts the app
            PackageInstaller.STATUS_FAILURE_ABORTED -> AppUpdates.installFailed(null)
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> AppUpdates.installFailed(
                "This update can't replace the installed app (different signature). Uninstall PhoneInput, then install the new version.",
            )
            PackageInstaller.STATUS_FAILURE_STORAGE -> AppUpdates.installFailed("Not enough storage to install the update.")
            else -> AppUpdates.installFailed(
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { "Update failed: $it" } ?: "Update failed.",
            )
        }
    }
}

/**
 * After an update Android stops the old app and doesn't reopen it (apps may not start activities
 * from the background), so tell the user it's done and let them open the new version.
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Shown when PhoneInput has been updated"
            },
        )
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("PhoneInput updated to $version")
            .setContentText("Tap to open it.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) } // no-op without notification permission
    }

    private companion object {
        const val CHANNEL_ID = "updates"
        const val NOTIFICATION_ID = 2
    }
}
