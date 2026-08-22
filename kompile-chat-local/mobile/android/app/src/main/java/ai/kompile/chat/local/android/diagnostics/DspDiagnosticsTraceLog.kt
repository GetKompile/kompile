package ai.kompile.chat.local.android.diagnostics

import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption
import java.util.UUID

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

    fun prepareCapture(): File {
        require(directory.mkdirs() || directory.isDirectory) {
            "Android could not create the DSP diagnostics directory: ${directory.absolutePath}"
        }
        DspDiagnosticsExportPolicy.withCaptureLock(directory) {
            rotateLocked()
        }
        return activeFile
    }

    /** Starts a new user-chat capture without retaining reports from earlier turns. */
    fun resetForChat() {
        DspDiagnosticsExportPolicy.resetForChat(directory)
    }

    fun locationDescription(): String =
        "$LOG_RELATIVE_PATH (rotating backups .1 through .$MAX_BACKUP_FILES)"

    /**
     * Creates one immutable, share-only text file without materializing a native trace in heap.
     * The export contains the active capture followed by its rotating backups.
     */
    fun writeShareSnapshot(checkCancelled: () -> Unit = {}): File =
        DspDiagnosticsExportPolicy.writeShareSnapshot(directory, checkCancelled)

    /** Small metadata-only summary suitable for clipboard-oriented debug transcripts. */
    fun contentsDescription(): String = DspDiagnosticsExportPolicy.contentsDescription(directory)

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

    companion object {
        private const val DIRECTORY = DspDiagnosticsExportPolicy.DIRECTORY
        private const val ACTIVE_FILE = DspDiagnosticsExportPolicy.ACTIVE_FILE
        private const val MAX_BACKUP_FILES = DspDiagnosticsExportPolicy.MAX_BACKUP_FILES

        internal const val LOG_RELATIVE_PATH =
            "files/diagnostics/dsp/dsp-diagnostics.json"
    }
}

/** Heap-bounded, JVM-testable file export policy for native DSP diagnostics. */
internal object DspDiagnosticsExportPolicy {
    const val DIRECTORY = "diagnostics/dsp"
    const val ACTIVE_FILE = "dsp-diagnostics.json"
    const val LOCK_FILE = "dsp-diagnostics.lock"
    const val MAX_BACKUP_FILES = 3
    const val SHARE_FILE_PREFIX = "dsp-diagnostics-export-"
    const val SHARE_FILE_SUFFIX = ".log"
    const val MAX_SHARE_FILES = 2
    private const val COPY_BUFFER_BYTES = 64 * 1024
    private const val MIN_FREE_BYTES_AFTER_EXPORT = 64L * 1024L * 1024L
    private const val EXPORT_METADATA_ALLOWANCE_BYTES = 1024L * 1024L

    private val processCaptureLock = Any()
    private val processExportLock = Any()

    private data class SourceDescription(val name: String, val length: Long)

    private data class OpenSource(
        val name: String,
        val length: Long,
        val input: FileInputStream,
    ) : Closeable {
        override fun close() = input.close()
    }

    fun resetForChat(directory: File) {
        withCaptureLock(directory) {
            (1..MAX_BACKUP_FILES).forEach { index ->
                val backup = File(directory, "$ACTIVE_FILE.$index")
                check(!backup.exists() || backup.delete()) {
                    "Unable to remove stale DSP diagnostics backup: ${backup.absolutePath}"
                }
            }
            FileOutputStream(File(directory, ACTIVE_FILE), false).use { output ->
                output.flush()
                output.fd.sync()
            }
        }
    }

    fun contentsDescription(directory: File): String {
        val sources = sourceFiles(directory)
            .filter { file -> file.isFile && file.length() > 0L }
            .map { file -> SourceDescription(file.name, file.length()) }
        if (sources.isEmpty()) {
            return "DSP diagnostics trace is empty.\n" +
                "Location: files/$DIRECTORY/$ACTIVE_FILE"
        }
        return buildString {
            appendLine("DSP diagnostics are retained as files and omitted from clipboard text.")
            appendLine("Use Share DSP diagnostics file in Settings to export the complete trace.")
            sources.forEach { source ->
                appendLine("${source.name}: ${source.length} bytes")
            }
        }.trimEnd()
    }

    fun writeShareSnapshot(
        directory: File,
        checkCancelled: () -> Unit = {},
    ): File = synchronized(processExportLock) {
        require(directory.mkdirs() || directory.isDirectory) {
            "Android could not create the DSP diagnostics directory: ${directory.absolutePath}"
        }
        cleanupOldExports(directory, MAX_SHARE_FILES - 1)
        val sources = openSourceSnapshots(directory)
        try {
            checkFreeSpace(directory, sources)
            checkCancelled()
            val target = nextExportFile(directory)
            val temporary = File(directory, "${target.name}.tmp")
            try {
                FileOutputStream(temporary).use { output ->
                    writeUtf8(output, "DSP diagnostics trace export\n")
                    writeUtf8(output, "Location: files/$DIRECTORY/$ACTIVE_FILE\n")
                    writeUtf8(output, "Captured source files: ${sources.size}\n")
                    if (sources.isEmpty()) {
                        writeUtf8(output, "DSP diagnostics trace is empty.\n")
                    }
                    sources.forEach { source ->
                        checkCancelled()
                        writeUtf8(
                            output,
                            "\n=== ${source.name} (${source.length} bytes) ===\n"
                        )
                        copyPrefix(source, output, checkCancelled)
                        writeUtf8(output, "\n")
                    }
                    output.flush()
                    output.fd.sync()
                }
                checkCancelled()
                check(temporary.renameTo(target)) {
                    "Unable to publish the DSP diagnostics export atomically"
                }
                target.setLastModified(System.currentTimeMillis())
                cleanupOldExports(directory, MAX_SHARE_FILES, target)
                check(target.isFile) { "DSP diagnostics export was not created" }
                target
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        } finally {
            sources.forEach { source -> runCatching { source.close() } }
        }
    }

    internal fun <T> withCaptureLock(directory: File, action: () -> T): T =
        synchronized(processCaptureLock) {
            require(directory.mkdirs() || directory.isDirectory) {
                "Android could not create the DSP diagnostics directory: ${directory.absolutePath}"
            }
            FileChannel.open(
                File(directory, LOCK_FILE).toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            ).use { channel ->
                channel.lock().use { action() }
            }
        }

    private fun openSourceSnapshots(directory: File): List<OpenSource> =
        withCaptureLock(directory) {
            val opened = ArrayList<OpenSource>(MAX_BACKUP_FILES + 1)
            try {
                sourceFiles(directory).forEach { file ->
                    if (!file.isFile) return@forEach
                    val input = FileInputStream(file)
                    val length = input.channel.size()
                    if (length > 0L) {
                        opened += OpenSource(file.name, length, input)
                    } else {
                        input.close()
                    }
                }
                opened
            } catch (failure: Throwable) {
                opened.forEach { source -> runCatching { source.close() } }
                throw failure
            }
        }

    private fun sourceFiles(directory: File): List<File> =
        listOf(File(directory, ACTIVE_FILE)) +
            (1..MAX_BACKUP_FILES).map { index -> File(directory, "$ACTIVE_FILE.$index") }

    private fun copyPrefix(
        source: OpenSource,
        output: OutputStream,
        checkCancelled: () -> Unit,
    ) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var remaining = source.length
        while (remaining > 0L) {
            checkCancelled()
            val requested = minOf(buffer.size.toLong(), remaining).toInt()
            val count = source.input.read(buffer, 0, requested)
            if (count < 0) break
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun checkFreeSpace(directory: File, sources: List<OpenSource>) {
        val sourceBytes = sources.fold(0L) { total, source -> saturatedAdd(total, source.length) }
        val required = saturatedAdd(
            saturatedAdd(sourceBytes, MIN_FREE_BYTES_AFTER_EXPORT),
            EXPORT_METADATA_ALLOWANCE_BYTES,
        )
        check(directory.usableSpace >= required) {
            "Not enough storage to export DSP diagnostics while preserving 64 MiB of free space"
        }
    }

    private fun nextExportFile(directory: File): File {
        while (true) {
            val name = SHARE_FILE_PREFIX + System.currentTimeMillis() + "-" +
                UUID.randomUUID() + SHARE_FILE_SUFFIX
            val candidate = File(directory, name)
            if (!candidate.exists()) return candidate
        }
    }

    private fun cleanupOldExports(
        directory: File,
        keep: Int,
        protected: File? = null,
    ) {
        val older = directory.listFiles { file ->
            file.isFile && file.name.startsWith(SHARE_FILE_PREFIX) &&
                file.name.endsWith(SHARE_FILE_SUFFIX) && file != protected
        }.orEmpty().sortedWith(
            compareByDescending<File> { it.lastModified() }.thenByDescending { it.name }
        )
        val otherFilesToKeep = (keep - if (protected == null) 0 else 1).coerceAtLeast(0)
        older.drop(otherFilesToKeep).forEach { file -> file.delete() }
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun writeUtf8(output: OutputStream, value: String) {
        output.write(value.toByteArray(StandardCharsets.UTF_8))
    }
}
