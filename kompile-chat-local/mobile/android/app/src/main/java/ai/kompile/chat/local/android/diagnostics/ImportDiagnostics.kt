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
 * A bounded, sanitized record of a user-visible model, graph, or project import step.
 *
 * Source URLs, repository references, credentials, stack traces, and arbitrary exception
 * objects never enter this type. Callers provide only a short outcome and remediation.
 */
data class ImportDiagnostic(
    val timestampEpochMillis: Long,
    val operation: String,
    val phase: String,
    val severity: ImportDiagnosticSeverity,
    val summary: String,
    val remediation: String
)

/** Pure policy/codec kept Android-independent so redaction and bounds are unit-testable. */
object ImportDiagnosticPolicy {
    const val MAX_ENTRIES = 32
    const val MAX_TEXT_CHARS = 320

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
    private val controls = Regex("""[\u0000-\u001f\u007f]+""")
    private val whitespace = Regex("""\s+""")
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun create(
        timestampEpochMillis: Long,
        operation: String,
        phase: String,
        severity: ImportDiagnosticSeverity,
        summary: String,
        remediation: String
    ): ImportDiagnostic = ImportDiagnostic(
        timestampEpochMillis = timestampEpochMillis.coerceAtLeast(0),
        operation = sanitize(operation),
        phase = sanitize(phase),
        severity = severity,
        summary = sanitize(summary),
        remediation = sanitize(remediation)
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

    fun encode(entries: List<ImportDiagnostic>): String =
        entries.take(MAX_ENTRIES).joinToString("\n") { entry ->
            val safe = sanitizeEntry(entry)
            listOf(
                safe.timestampEpochMillis.toString(),
                safe.severity.name,
                encodeField(safe.operation),
                encodeField(safe.phase),
                encodeField(safe.summary),
                encodeField(safe.remediation)
            ).joinToString("|")
        }

    fun decode(value: String?): List<ImportDiagnostic> {
        if (value.isNullOrBlank()) {
            return emptyList()
        }
        return value.lineSequence()
            .take(MAX_ENTRIES)
            .mapNotNull(::decodeLine)
            .toList()
    }

    private fun sanitizeEntry(entry: ImportDiagnostic): ImportDiagnostic = create(
        timestampEpochMillis = entry.timestampEpochMillis,
        operation = entry.operation,
        phase = entry.phase,
        severity = entry.severity,
        summary = entry.summary,
        remediation = entry.remediation
    )

    private fun encodeField(value: String): String =
        encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeField(value: String): String =
        String(decoder.decode(value), StandardCharsets.UTF_8)

    private fun decodeLine(line: String): ImportDiagnostic? = runCatching {
        val fields = line.split('|', limit = 6)
        require(fields.size == 6)
        create(
            timestampEpochMillis = fields[0].toLong(),
            severity = ImportDiagnosticSeverity.valueOf(fields[1].uppercase(Locale.ROOT)),
            operation = decodeField(fields[2]),
            phase = decodeField(fields[3]),
            summary = decodeField(fields[4]),
            remediation = decodeField(fields[5])
        )
    }.getOrNull()
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

    @Synchronized
    fun clear() {
        preferences.edit().remove(KEY_ENTRIES).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "kompile_chat_import_diagnostics"
        const val KEY_ENTRIES = "entries"
    }
}
