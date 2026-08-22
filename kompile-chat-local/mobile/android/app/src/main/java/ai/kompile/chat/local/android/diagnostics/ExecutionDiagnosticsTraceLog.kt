package ai.kompile.chat.local.android.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * Durable, app-owned snapshot of the complete debug execution record.
 *
 * The native DSP and smoke traces intentionally remain separate while they are being written:
 * they have different writers and formats, and keeping them independent makes a native crash
 * less likely to corrupt both. The copy-debug workflow snapshots their current contents here
 * so support only needs to pull one file.
 */
internal class ExecutionDiagnosticsTraceLog(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "diagnostics")
    private val activeFile = File(directory, FILE_NAME)
    private val temporaryFile = File(directory, "$FILE_NAME.tmp")

    @Synchronized
    fun writeSnapshot(contents: String) {
        runCatching {
            directory.mkdirs()
            FileOutputStream(temporaryFile).use { output ->
                output.write(contents.toByteArray(StandardCharsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            if (!temporaryFile.renameTo(activeFile)) {
                temporaryFile.copyTo(activeFile, overwrite = true)
                check(temporaryFile.delete() || !temporaryFile.exists()) {
                    "Unable to remove temporary execution diagnostics snapshot"
                }
            }
        }.onFailure { failure ->
            Log.w(TAG, "Unable to persist consolidated execution diagnostics", failure)
        }
    }

    fun readContents(): String = runCatching {
        if (!activeFile.isFile) return@runCatching ""
        activeFile.readText(StandardCharsets.UTF_8)
    }.getOrElse { failure ->
        Log.w(TAG, "Unable to read consolidated execution diagnostics", failure)
        ""
    }

    /** Returns the app-private file that is exposed through the share-only FileProvider. */
    fun snapshotFile(): File = activeFile

    companion object {
        private const val FILE_NAME = "execution.log"
        private const val TAG = "ExecutionDiagnostics"

        const val LOG_RELATIVE_PATH = "files/diagnostics/$FILE_NAME"
    }
}
