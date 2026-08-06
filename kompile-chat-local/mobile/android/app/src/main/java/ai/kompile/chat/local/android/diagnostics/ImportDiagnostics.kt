package ai.kompile.chat.local.android.diagnostics

import android.content.Context
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

enum class ImportDiagnosticSeverity {
    INFO,
    SUCCESS,
    ERROR
}

/**
 * A bounded, sanitized record of a user-visible import, activation, or execution step.
 *
 * Source URLs, repository references, credentials, and arbitrary exception objects never enter
 * this type. Sanitized technical details may include a bounded cause/stack trace so a device
 * failure can be copied and diagnosed without requiring logcat.
 */
data class ImportDiagnostic(
    val timestampEpochMillis: Long,
    val operation: String,
    val phase: String,
    val severity: ImportDiagnosticSeverity,
    val summary: String,
    val remediation: String,
    val technicalDetails: String = ""
)

/** Pure policy/codec kept Android-independent so redaction and bounds are unit-testable. */
object ImportDiagnosticPolicy {
    const val MAX_ENTRIES = 32
    const val MAX_TEXT_CHARS = 320
    const val MAX_DETAIL_CHARS = 96 * 1024

    private val uri = Regex(
        """(?i)\b(?:https?|file|content)://[^\s<>()]+"""
    )
    private val credentialAssignment = Regex(
        """(?i)\b(?:authorization|bearer|token|password|secret|credential|api[_-]?key)\b\s*(?:[:=]|\s)\s*[^\s,;]+"""
    )
    private val wellKnownToken = Regex(
        """(?i)\b(?:hf|ghp|github_pat|sk)[_-][A-Za-z0-9_-]{8,}\b"""
    )
    private val sourceReference = Regex(
        """(?i)(?<![A-Za-z0-9._-])(?:[A-Za-z0-9][A-Za-z0-9._-]{0,95}/[A-Za-z0-9][A-Za-z0-9._-]{0,95}|(?:[A-Za-z]:\\|/)[^\s<>()]+)"""
    )
    private val retainedNativeCrashArtifact = Regex(
        """\bno_backup/native-crash-dumps/tombstone-[0-9]{1,20}-[0-9]{1,10}-[a-f0-9]{16}(?:-truncated)?\.pb\b"""
    )
    private val controls = Regex("""[\u0000-\u001f\u007f]+""")
    private val whitespace = Regex("""\s+""")
    private val detailControls = Regex("""[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]+""")
    private val horizontalWhitespace = Regex("""[\t ]+""")
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun create(
        timestampEpochMillis: Long,
        operation: String,
        phase: String,
        severity: ImportDiagnosticSeverity,
        summary: String,
        remediation: String,
        technicalDetails: String = ""
    ): ImportDiagnostic = ImportDiagnostic(
        timestampEpochMillis = timestampEpochMillis.coerceAtLeast(0),
        operation = sanitize(operation),
        phase = sanitize(phase),
        severity = severity,
        summary = sanitize(summary),
        remediation = sanitize(remediation),
        technicalDetails = sanitizeDetails(technicalDetails)
    )

    fun prependBounded(
        existing: List<ImportDiagnostic>,
        entry: ImportDiagnostic
    ): List<ImportDiagnostic> =
        (listOf(sanitizeEntry(entry)) + existing.map(::sanitizeEntry))
            .take(MAX_ENTRIES)

    fun sanitize(value: String): String {
        var result = value
            .replace(uri, "[url]")
            .replace(credentialAssignment, "[credential]")
            .replace(wellKnownToken, "[credential]")
            .replace(sourceReference, "[source]")
            .replace(controls, " ")
            .replace(whitespace, " ")
            .trim()
        if (result.length > MAX_TEXT_CHARS) {
            result = result.take(MAX_TEXT_CHARS - 1).trimEnd() + "…"
        }
        return result
    }

    fun sanitizeDetails(value: String): String {
        val retainedArtifacts = mutableListOf<String>()
        val protectedValue = retainedNativeCrashArtifact.replace(value) { match ->
            val index = retainedArtifacts.size
            retainedArtifacts += match.value
            "KOMPILE_NATIVE_CRASH_ARTIFACT_${index}_REFERENCE"
        }
        var result = protectedValue
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(uri, "[url]")
            .replace(credentialAssignment, "[credential]")
            .replace(wellKnownToken, "[credential]")
            .replace(sourceReference, "[source]")
            .replace(detailControls, " ")
            .lineSequence()
            .joinToString("\n") { line ->
                line.replace(horizontalWhitespace, " ").trimEnd()
            }
            .trim()
        retainedArtifacts.forEachIndexed { index, artifact ->
            result = result.replace(
                "KOMPILE_NATIVE_CRASH_ARTIFACT_${index}_REFERENCE",
                artifact
            )
        }
        if (result.length > MAX_DETAIL_CHARS) {
            result = result.take(MAX_DETAIL_CHARS - 1).trimEnd() + "…"
        }
        return result
    }

    /** Complete redacted exception evidence, including causes and suppressed cleanup failures. */
    fun failureDetails(failure: Throwable): String =
        sanitizeDetails(failure.stackTraceToString())

    fun copyText(entry: ImportDiagnostic): String {
        val safe = sanitizeEntry(entry)
        return buildString {
            append(safe.severity.name)
            append(" · ").append(safe.operation)
            append(" · ").append(safe.phase)
            append("\n").append(safe.summary)
            if (safe.remediation.isNotBlank()) {
                append("\nNext: ").append(safe.remediation)
            }
            if (safe.technicalDetails.isNotBlank()) {
                append("\nDetails:\n").append(safe.technicalDetails)
            }
        }
    }

    fun copyText(entries: List<ImportDiagnostic>): String =
        entries.take(MAX_ENTRIES).joinToString("\n\n") { copyText(it) }

    fun errorForPhase(
        entries: List<ImportDiagnostic>,
        phase: String
    ): ImportDiagnostic? {
        val errors = entries.filter { it.severity == ImportDiagnosticSeverity.ERROR }
        return errors.firstOrNull { it.phase.equals(phase, ignoreCase = true) }
    }

    fun errorForMessage(
        entries: List<ImportDiagnostic>,
        message: String,
        operationPrefix: String? = null
    ): ImportDiagnostic? {
        val normalizedMessage = message.trim()
        val errors = entries.filter { entry ->
            entry.severity == ImportDiagnosticSeverity.ERROR &&
                (operationPrefix.isNullOrBlank() ||
                    entry.operation.startsWith(operationPrefix, ignoreCase = true))
        }
        return errors.firstOrNull {
            it.summary.trim().equals(normalizedMessage, ignoreCase = true)
        } ?: errors.firstOrNull {
            val summary = it.summary.trim()
            summary.isNotEmpty() && normalizedMessage.isNotEmpty() &&
                (normalizedMessage.contains(summary, ignoreCase = true) ||
                    summary.contains(normalizedMessage, ignoreCase = true))
        }
    }

    fun copyTextForError(
        message: String,
        entries: List<ImportDiagnostic>,
        operationPrefix: String? = null
    ): String = errorForMessage(entries, message, operationPrefix)
        ?.let(::copyText)
        ?: sanitizeDetails(message)

    fun encode(entries: List<ImportDiagnostic>): String =
        entries.take(MAX_ENTRIES).joinToString("\n") { entry ->
            val safe = sanitizeEntry(entry)
            listOf(
                safe.timestampEpochMillis.toString(),
                safe.severity.name,
                encodeField(safe.operation),
                encodeField(safe.phase),
                encodeField(safe.summary),
                encodeField(safe.remediation),
                encodeField(safe.technicalDetails)
            ).joinToString("|")
        }

    fun decode(value: String?): List<ImportDiagnostic> {
        if (value.isNullOrBlank()) {
            return emptyList()
        }
        return value.lineSequence()
            .take(MAX_ENTRIES)
            .mapIndexed { index, line ->
                try {
                    decodeLine(line)
                } catch (failure: RuntimeException) {
                    create(
                        timestampEpochMillis = System.currentTimeMillis(),
                        operation = "app diagnostics",
                        phase = "stored entry ${index + 1}",
                        severity = ImportDiagnosticSeverity.ERROR,
                        summary = "A stored diagnostic entry could not be decoded.",
                        remediation = "Copy this error and clear App Diagnostics after preserving any useful entries.",
                        technicalDetails = failure.stackTraceToString()
                    )
                }
            }
            .toList()
    }

    private fun sanitizeEntry(entry: ImportDiagnostic): ImportDiagnostic = create(
        timestampEpochMillis = entry.timestampEpochMillis,
        operation = entry.operation,
        phase = entry.phase,
        severity = entry.severity,
        summary = entry.summary,
        remediation = entry.remediation,
        technicalDetails = entry.technicalDetails
    )

    private fun encodeField(value: String): String =
        encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeField(value: String): String =
        String(decoder.decode(value), StandardCharsets.UTF_8)

    private fun decodeLine(line: String): ImportDiagnostic {
        val fields = line.split('|')
        require(fields.size == 6 || fields.size == 7)
        return create(
            timestampEpochMillis = fields[0].toLong(),
            severity = ImportDiagnosticSeverity.valueOf(fields[1].uppercase(Locale.ROOT)),
            operation = decodeField(fields[2]),
            phase = decodeField(fields[3]),
            summary = decodeField(fields[4]),
            remediation = decodeField(fields[5]),
            technicalDetails = if (fields.size == 7) decodeField(fields[6]) else ""
        )
    }
}

/** Durable storage intentionally separated from normal settings and bounded by policy. */
class ImportDiagnosticStore(context: Context) {
    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): List<ImportDiagnostic> =
        ImportDiagnosticPolicy.decode(preferences.getString(KEY_ENTRIES, null))

    @Synchronized
    fun append(entry: ImportDiagnostic): List<ImportDiagnostic> {
        val next = ImportDiagnosticPolicy.prependBounded(load(), entry)
        preferences.edit()
            .putString(KEY_ENTRIES, ImportDiagnosticPolicy.encode(next))
            .apply()
        return next
    }

    /**
     * Commit evidence synchronously when the next operation may terminate the process. Normal
     * progress logging keeps using [append] so transfer updates never pay a filesystem sync.
     */
    @Synchronized
    fun appendDurably(entry: ImportDiagnostic): List<ImportDiagnostic> {
        val next = ImportDiagnosticPolicy.prependBounded(load(), entry)
        check(
            preferences.edit()
                .putString(KEY_ENTRIES, ImportDiagnosticPolicy.encode(next))
                .commit()
        ) {
            "Android could not durably persist native process-exit diagnostics."
        }
        return next
    }

    @Synchronized
    fun clear() {
        preferences.edit().remove(KEY_ENTRIES).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "kompile_chat_import_diagnostics"
        const val KEY_ENTRIES = "entries"
    }
}
