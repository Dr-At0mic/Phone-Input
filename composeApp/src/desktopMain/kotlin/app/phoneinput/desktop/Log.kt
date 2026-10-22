package app.phoneinput.desktop

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Minimal file + console log. Windows: %LOCALAPPDATA%\PhoneInput\logs\phoneinput.log,
 * Linux: ~/.local/state/phoneinput/phoneinput.log. Rotated to phoneinput.log.1 above 1 MB.
 */
object Log {
    val file: File = run {
        val dir = System.getenv("LOCALAPPDATA")?.let { File(it, "PhoneInput/logs") }
            ?: File(System.getProperty("user.home"), ".local/state/phoneinput")
        dir.mkdirs()
        File(dir, "phoneinput.log")
    }
    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    /** Tests set -Dphoneinput.log=console so they don't write into the user's log. */
    private val toFile = System.getProperty("phoneinput.log") != "console"

    init {
        if (file.length() > 1_000_000) file.renameTo(File(file.path + ".1").also { it.delete() })
    }

    fun i(message: String) = write("INFO", message, null)

    fun w(message: String, error: Throwable? = null) = write("WARN", message, error)

    fun e(message: String, error: Throwable? = null) = write("ERROR", message, error)

    @Synchronized
    private fun write(level: String, message: String, error: Throwable?) {
        val line = buildString {
            append(LocalDateTime.now().format(time)).append(' ').append(level).append(" [")
            append(Thread.currentThread().name).append("] ").append(message)
            if (error != null) {
                append('\n')
                append(StringWriter().also { error.printStackTrace(PrintWriter(it)) })
            }
        }
        System.err.println(line)
        if (toFile) runCatching { file.appendText(line + "\n") }
    }
}
