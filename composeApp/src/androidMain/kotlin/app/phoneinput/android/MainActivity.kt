package app.phoneinput.android

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import android.content.Intent
import androidx.compose.runtime.rememberCoroutineScope
import app.phoneinput.protocol.PairingUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.phoneinput.android.stream.LinkState
import app.phoneinput.android.stream.StreamController
import app.phoneinput.android.ui.ConnectScreen
import app.phoneinput.android.ui.LiveScreen
import app.phoneinput.android.ui.SettingsScreen
import app.phoneinput.ui.theme.PhoneInputTheme
import app.phoneinput.ui.theme.PmColors

class MainActivity : ComponentActivity() {
    /** A phoneinput://connect link this activity was opened with (e.g. from the camera app). */
    private val pendingLink = MutableStateFlow<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingLink.value = intent.dataString
    }

    /** The navigation bar is hidden by default; a swipe from the edge shows it briefly. */
    private fun hideNavigationBar() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideNavigationBar()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        hideNavigationBar()
        val settings = AppSettings.get(this)
        pendingLink.value = intent?.dataString
        setContent {
            PhoneInputTheme {
                AndroidRoot(settings, pendingLink)
            }
        }
    }
}

private enum class Screen { Connect, Live, Settings }

@Composable
private fun AndroidRoot(settings: AppSettings, pendingLink: MutableStateFlow<String?>) {
    val context = LocalContext.current
    val stream by StreamController.state.collectAsState()
    var screen by rememberSaveable { mutableStateOf(if (StreamController.isStreaming) Screen.Live else Screen.Connect) }
    var settingsReturn by rememberSaveable { mutableStateOf(Screen.Connect) }
    var pendingHost by remember { mutableStateOf<Host?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }

    val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val host = pendingHost
        pendingHost = null
        if (result[Manifest.permission.RECORD_AUDIO] == true && host != null) {
            permissionDenied = false
            StreamController.start(context, host)
            screen = Screen.Live
        } else {
            permissionDenied = true
        }
    }

    fun connect(host: Host) {
        StreamController.clearError()
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            StreamController.start(context, host)
            screen = Screen.Live
        } else {
            pendingHost = host
            launcher.launch(permissions)
        }
    }

    val scope = rememberCoroutineScope()
    var qrBusy by remember { mutableStateOf(false) }

    /** QR code or phoneinput:// link → find the reachable address → save and connect. */
    fun connectFromLink(raw: String) {
        val info = PairingUri.parse(raw)
        if (info == null) {
            StreamController.reportError("That QR code isn't from PhoneInput.")
            return
        }
        if (!StreamController.isStreaming) {
            qrBusy = true
            scope.launch {
                val host = QrPairing.resolve(info)
                qrBusy = false
                settings.rememberHost(host)
                connect(host)
            }
        }
    }

    val link by pendingLink.collectAsState()
    LaunchedEffect(link) {
        link?.let {
            pendingLink.value = null
            connectFromLink(it)
        }
    }

    // When a session ends (by the user, the computer, or an error) go back to the device list.
    LaunchedEffect(stream.link) {
        if (stream.link == LinkState.Idle && screen == Screen.Live) screen = Screen.Connect
    }

    BackHandler(enabled = screen != Screen.Connect) {
        screen = if (screen == Screen.Settings) settingsReturn else Screen.Connect
    }

    Box(Modifier.fillMaxSize().background(PmColors.Bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        when (screen) {
            Screen.Connect -> ConnectScreen(
                stream = stream,
                settings = settings,
                permissionDenied = permissionDenied,
                onConnect = ::connect,
                onScanQr = {
                    StreamController.clearError()
                    QrPairing.scan(context, onResult = ::connectFromLink, onError = StreamController::reportError)
                },
                qrBusy = qrBusy,
                onOpenLive = { screen = Screen.Live },
                onSettings = {
                    settingsReturn = Screen.Connect
                    screen = Screen.Settings
                },
            )
            Screen.Live -> LiveScreen(
                stream = stream,
                settings = settings,
                onBack = { screen = Screen.Connect },
                onSettings = {
                    settingsReturn = Screen.Live
                    screen = Screen.Settings
                },
                onStop = { StreamController.stop(context) },
            )
            Screen.Settings -> SettingsScreen(settings = settings, onBack = { screen = settingsReturn })
        }
    }
}
