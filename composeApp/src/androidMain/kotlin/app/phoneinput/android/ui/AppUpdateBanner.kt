package app.phoneinput.android.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.phoneinput.android.update.AppUpdates
import app.phoneinput.ui.components.UpdateBanner
import app.phoneinput.update.UpdateState

/** The shared update card wired to [AppUpdates]; "Later" hides it until a newer version shows up. */
@Composable
fun AppUpdateBanner() {
    val context = LocalContext.current
    val state by AppUpdates.state.collectAsState()
    var dismissed by rememberSaveable { mutableStateOf<String?>(null) }
    val version = when (val s = state) {
        is UpdateState.Available -> s.release.version.toString()
        is UpdateState.Ready -> s.release.version.toString()
        else -> null
    }
    if (version != null && version == dismissed) return
    UpdateBanner(
        state = state,
        installLabel = "Install",
        onDownload = { AppUpdates.download() },
        onInstall = { AppUpdates.install(context) },
        onOpenPage = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
        onDismiss = { dismissed = version },
    )
}
