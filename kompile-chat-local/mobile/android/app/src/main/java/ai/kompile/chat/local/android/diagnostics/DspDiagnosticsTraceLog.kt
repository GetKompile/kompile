package ai.kompile.chat.local.android.diagnostics

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption

/**
 * App-private capture point for SameDiff DynamicShapePlan diagnostics.
 *
 * The importer and runtime workers execute sequentially during model preparation. Each worker
 * rotates the previous report before initializing ND4J, so importer evidence remains available
 * when runtime generation writes the active report.
 */
internal class DspDiagnosticsTraceLog(context: Context) {
    private val directory = File(context.applicationContext.filesDir, DIRECTORY)
    private val activeFile = File(directory, ACTIVE_FILE)
    private val lockFile = File(directory, LOCK_FILE)

    fun prepareCapture(): File {
        require(directory.mkdirs() || directory.isDirectory) {
            "Android could not create the DSP diagnostics directory: ${directory.absolutePath}"
        }
        FileChannel.open(
            lockFile.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use {
                rotateLocked()
            }
        }
        return activeFile
    }

    fun locationDescription(): String =
        "$LOG_RELATIVE_PATH (rotating backups .1 through .$MAX_BACKUP_FILES)"

    fun readContents(): String = runCatching {
        val files = (MAX_BACKUP_FILES downTo 1)
            .map { index -> File(directory, "$ACTIVE_FILE.$index") } + activeFile
        val existing = files.filter { it.isFile && it.length() > 0L }
        if (existing.isEmpty()) {
            return@runCatching "DSP diagnostics trace is empty.\nLocation: ${locationDescription()}"
        }
        buildString {
            appendLine("DSP diagnostics trace")
            appendLine("Location: ${locationDescription()}")
            existing.forEach { file ->
                val capture = readBounded(file)
                appendLine()
                appendLine("=== ${file.name} (${file.length()} bytes) ===")
                if (capture.truncated) {
                    appendLine("[copy limited to the newest $MAX_COPY_BYTES_PER_FILE bytes]")
                }
                append(capture.text)
                if (lastOrNull() != '\n') appendLine()
            }
        }
    }.getOrElse { failure ->
        "DSP diagnostics trace could not be read.\n" +
            "Location: ${locationDescription()}\n" +
            "Read failure: ${ImportDiagnosticPolicy.sanitizeDetails(failure.toString())}"
    }

    private fun rotateLocked() {
        for (index in MAX_BACKUP_FILES downTo 1) {
            val source = if (index == 1) {
                activeFile
            } else {
                File(directory, "$ACTIVE_FILE.${index - 1}")
            }
            val target = File(directory, "$ACTIVE_FILE.$index")
            if (target.exists()) target.delete()
            if (source.exists()) source.renameTo(target)
        }
    }

    private fun readBounded(file: File): BoundedCapture {
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            val copyBytes = length.coerceAtMost(MAX_COPY_BYTES_PER_FILE.toLong()).toInt()
            input.seek(length - copyBytes)
            val bytes = ByteArray(copyBytes)
            input.readFully(bytes)
            return BoundedCapture(
                text = String(bytes, StandardCharsets.UTF_8),
                truncated = length > copyBytes,
            )
        }
    }

    private data class BoundedCapture(val text: String, val truncated: Boolean)

    companion object {
        private const val DIRECTORY = "diagnostics/dsp"
        private const val ACTIVE_FILE = "dsp-diagnostics.json"
        private const val LOCK_FILE = "dsp-diagnostics.lock"
        private const val MAX_BACKUP_FILES = 3
        private const val MAX_COPY_BYTES_PER_FILE = 2 * 1024 * 1024

        internal const val LOG_RELATIVE_PATH =
            "files/diagnostics/dsp/dsp-diagnostics.json"
    }
}
