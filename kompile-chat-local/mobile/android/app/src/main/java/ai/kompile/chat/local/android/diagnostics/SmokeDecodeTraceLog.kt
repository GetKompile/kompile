package ai.kompile.chat.local.android.diagnostics

import android.app.Application
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Small, durable trace for the bounded model smoke decode.
 *
 * The main process and the app-private SDX runtime process both write this file.
 * Each event is one line so it can be pulled while a decode is still running.
 * The file is deliberately independent from Android logcat and from the import
 * diagnostic journal: a native hang or a killed worker must still leave the last
 * durable phase and heartbeat behind.
 */
internal class SmokeDecodeTraceLog(context: Context) {
    private val directory = File(context.applicationContext.filesDir, DIRECTORY)
    private val activeFile = File(directory, ACTIVE_FILE)
    private val lockFile = File(directory, LOCK_FILE)

    fun record(
        event: String,
        attemptId: String? = null,
        fields: Map<String, Any?> = emptyMap()
    ) {
        runCatching {
            val line = formatLine(event, attemptId, fields)
            directory.mkdirs()
            FileChannel.open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            ).use { channel ->
                channel.lock().use {
                    if (SmokeDecodeTracePolicy.shouldRotate(activeFile.length(), line.toByteArray(StandardCharsets.UTF_8).size.toLong())) {
                        rotateLocked()
                    }
                    FileOutputStream(activeFile, true).use { output ->
                        output.write(line.toByteArray(StandardCharsets.UTF_8))
                        output.fd.sync()
                    }
                }
            }
        }.onFailure {
            // Diagnostics must never change model execution behavior.
            Log.w(TAG, "Unable to write smoke decode trace", it)
        }
    }

    fun recordFailure(
        event: String,
        attemptId: String?,
        failure: Throwable,
        fields: Map<String, Any?> = emptyMap()
    ) {
        val details = ImportDiagnosticPolicy.failureDetails(failure)
        val chunks = SmokeDecodeTracePolicy.detailChunks(details)
        val merged = LinkedHashMap<String, Any?>(fields.size + 5)
        merged.putAll(fields)
        merged["failure_class"] = failure.javaClass.name
        merged["failure_message"] = failure.message ?: failure.toString()
        merged["failure_stack_chars"] = details.length
        merged["failure_stack_chunks"] = chunks.size
        merged["failure_stack"] = chunks.firstOrNull().orEmpty()
        record(event, attemptId, merged)
        chunks.forEachIndexed { index, chunk ->
            record(
                event + "_stack",
                attemptId,
                mapOf(
                    "chunk_index" to index,
                    "chunk_count" to chunks.size,
                    "failure_stack_chunk" to chunk
                )
            )
        }
    }

    fun startHeartbeat(
        attemptId: String?,
        phase: String,
        intervalMillis: Long = DEFAULT_HEARTBEAT_INTERVAL_MILLIS
    ): AutoCloseable {
        val startedAt = SystemClock.elapsedRealtime()
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "smoke-decode-trace").apply { isDaemon = true }
        }
        val safeInterval = intervalMillis.coerceAtLeast(1_000L)
        executor.scheduleAtFixedRate(
            {
                record(
                    "heartbeat",
                    attemptId,
                    mapOf(
                        "phase" to phase,
                        "elapsed_ms" to (SystemClock.elapsedRealtime() - startedAt),
                        "pid" to Process.myPid()
                    )
                )
            },
            safeInterval,
            safeInterval,
            TimeUnit.MILLISECONDS
        )
        return AutoCloseable {
            executor.shutdownNow()
        }
    }

    fun locationDescription(): String {
        return LOG_RELATIVE_PATH + " (rotating backups .1 through ." + SmokeDecodeTracePolicy.MAX_BACKUP_FILES + ")"
    }

    /**
     * Returns the complete bounded trace in chronological file order.
     *
     * The active file is intentionally read on demand instead of being cached in
     * the ViewModel: the runtime worker writes the same app-private directory from
     * another process, and the button must include the latest durable heartbeat.
     */
    fun readContents(): String {
        return runCatching {
            val files = (SmokeDecodeTracePolicy.MAX_BACKUP_FILES downTo 1)
                .map { index -> File(directory, ACTIVE_FILE + "." + index) } +
                activeFile
            val existing = files.filter { it.isFile && it.length() > 0L }
            if (existing.isEmpty()) {
                return@runCatching "Smoke-decode trace is empty.\nLocation: ${locationDescription()}"
            }

            buildString {
                appendLine("Smoke-decode trace")
                appendLine("Location: ${locationDescription()}")
                existing.forEach { file ->
                    appendLine()
                    appendLine("=== ${file.name} ===")
                    append(file.readText(StandardCharsets.UTF_8))
                    if (lastOrNull() != '\n') {
                        appendLine()
                    }
                }
            }
        }.getOrElse { failure ->
            "Smoke-decode trace could not be read.\n" +
                "Location: ${locationDescription()}\n" +
                "Read failure: ${SmokeDecodeTracePolicy.field(failure)}"
        }
    }

    private fun formatLine(
        event: String,
        attemptId: String?,
        fields: Map<String, Any?>
    ): String {
        val values = ArrayList<String>(fields.size + 7)
        values += "wall_ms=" + System.currentTimeMillis()
        values += "uptime_ms=" + SystemClock.elapsedRealtime()
        values += "pid=" + Process.myPid()
        values += "process=" + SmokeDecodeTracePolicy.field(Application.getProcessName())
        values += "thread=" + SmokeDecodeTracePolicy.field(Thread.currentThread().name)
        values += "event=" + SmokeDecodeTracePolicy.eventName(event)
        if (!attemptId.isNullOrBlank()) {
            values += "attempt_id=" + SmokeDecodeTracePolicy.field(attemptId)
        }
        fields.entries
            .sortedBy { it.key }
            .forEach { entry ->
                values += SmokeDecodeTracePolicy.field(entry.key) + "=" +
                    SmokeDecodeTracePolicy.field(entry.value)
            }
        var line = values.joinToString(separator = " ") + "\n"
        if (line.toByteArray(StandardCharsets.UTF_8).size > SmokeDecodeTracePolicy.MAX_LINE_BYTES) {
            line = line.take(SmokeDecodeTracePolicy.MAX_LINE_CHARS - 1) + "…\n"
        }
        return line
    }

    private fun rotateLocked() {
        for (index in SmokeDecodeTracePolicy.MAX_BACKUP_FILES downTo 1) {
            val source = if (index == 1) activeFile else File(directory, ACTIVE_FILE + "." + (index - 1))
            val target = File(directory, ACTIVE_FILE + "." + index)
            if (target.exists()) {
                target.delete()
            }
            if (source.exists()) {
                source.renameTo(target)
            }
        }
    }

    companion object {
        private const val TAG = "SmokeDecodeTrace"
        private const val DIRECTORY = "diagnostics/smoke-decode"
        private const val ACTIVE_FILE = "smoke-decode.log"
        private const val LOCK_FILE = "smoke-decode.lock"
        private const val DEFAULT_HEARTBEAT_INTERVAL_MILLIS = 10_000L

        internal const val LOG_RELATIVE_PATH = "files/diagnostics/smoke-decode/smoke-decode.log"
    }
}

internal object SmokeDecodeTracePolicy {
    const val MAX_ACTIVE_BYTES = 512 * 1024
    const val MAX_BACKUP_FILES = 3
    const val MAX_LINE_BYTES = 16 * 1024
    const val MAX_LINE_CHARS = 15 * 1024
    internal const val MAX_DETAIL_CHUNK_CHARS = 1_536
    private const val MAX_FIELD_CHARS = 2_048

    fun shouldRotate(currentBytes: Long, incomingBytes: Long): Boolean {
        return currentBytes > 0L && currentBytes + incomingBytes > MAX_ACTIVE_BYTES
    }

    fun eventName(value: String): String {
        val normalized = value.map { character ->
            if (character.isLetterOrDigit() || character == '_' || character == '-' || character == '.') {
                character
            } else {
                '_'
            }
        }.joinToString("").replace(Regex("_+"), "_").trim('_')
        return normalized.take(80).ifBlank { "unknown" }
    }

    /**
     * Redact and bound complete diagnostic details before splitting them across trace lines.
     *
     * Redacting first is important: splitting an untrusted stack before redaction could divide a
     * credential or private path across chunk boundaries and make it invisible to the policy.
     */
    fun detailChunks(value: Any?): List<String> {
        val sanitized = ImportDiagnosticPolicy.sanitizeDetails(value?.toString().orEmpty())
        if (sanitized.isBlank()) return emptyList()
        return sanitized.chunked(MAX_DETAIL_CHUNK_CHARS)
    }

    fun field(value: Any?): String {
        val sanitized = ImportDiagnosticPolicy.sanitizeDetails(
            value?.toString()?.replace(Regex("[\\r\\n\\t]+"), " ") ?: ""
        ).replace(Regex("\\s+"), " ").trim()
        return sanitized.take(MAX_FIELD_CHARS)
    }
}