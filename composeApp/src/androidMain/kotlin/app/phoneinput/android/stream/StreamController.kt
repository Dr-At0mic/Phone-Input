package app.phoneinput.android.stream

import android.content.Context
import androidx.core.content.ContextCompat
import app.phoneinput.android.Host
import app.phoneinput.audio.AudioLevels
import app.phoneinput.protocol.Codec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Standby: connected, but the computer is using another device as its microphone. */
enum class LinkState { Idle, Connecting, Live, Standby, Reconnecting }

data class StreamUiState(
    val link: LinkState = LinkState.Idle,
    val host: Host? = null,
    val muted: Boolean = false,
    val rttMs: Int? = null,
    /** Audio encoding agreed with the computer; null until it answers. */
    val codec: Codec? = null,
    /** The user wants the computer's audio played on this phone. */
    val speaker: Boolean = false,
    /** The computer confirmed it's sending its audio. */
    val speakerOn: Boolean = false,
    /** Why computer audio stopped (shown on the Live screen). */
    val speakerMessage: String? = null,
    val levels: List<Float> = emptyList(),
    val levelDb: Float = AudioLevels.FLOOR_DB,
    val startedAtMs: Long = 0L,
    /** Why the last session ended, shown on the Connect screen. */
    val lastError: String? = null,
)

/** Process-wide stream state shared by the UI and [StreamService]. */
object StreamController {
    private val _state = MutableStateFlow(StreamUiState())
    val state: StateFlow<StreamUiState> = _state.asStateFlow()

    val isStreaming: Boolean get() = _state.value.link != LinkState.Idle

    fun start(context: Context, host: Host) {
        _state.value = StreamUiState(link = LinkState.Connecting, host = host, startedAtMs = System.currentTimeMillis())
        ContextCompat.startForegroundService(context, StreamService.startIntent(context, host))
    }

    fun stop(context: Context) {
        context.startService(StreamService.stopIntent(context))
    }

    fun setMuted(muted: Boolean) = _state.update { it.copy(muted = muted) }

    fun setSpeaker(on: Boolean) = _state.update { it.copy(speaker = on, speakerOn = on && it.speakerOn, speakerMessage = null) }

    fun clearError() = _state.update { it.copy(lastError = null) }

    /** Shows a message on the Connect screen (e.g. a QR problem). */
    fun reportError(message: String) = _state.update { it.copy(lastError = message) }

    internal fun update(transform: (StreamUiState) -> StreamUiState) = _state.update(transform)

    internal fun finished(error: String?) {
        _state.value = StreamUiState(lastError = error)
    }
}
