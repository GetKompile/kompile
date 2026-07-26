package ai.kompile.chat.local.android.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportDiagnosticsTest {

    @Test
    fun sanitizesUrlsCredentialsControlCharactersAndLength() {
        val longTail = "x".repeat(ImportDiagnosticPolicy.MAX_TEXT_CHARS + 100)
        val sanitized = ImportDiagnosticPolicy.sanitize(
            "failed https://huggingface.co/acme/model?token=hf_secret " +
                "Authorization: Bearer hf_1234567890 source acme/private-model " +
                "/storage/emulated/0/Download/model.sdz\n$longTail"
        )

        assertFalse(sanitized.contains("huggingface.co"))
        assertFalse(sanitized.contains("hf_secret"))
        assertFalse(sanitized.contains("hf_1234567890"))
        assertFalse(sanitized.contains("acme/private-model"))
        assertFalse(sanitized.contains("/storage/emulated"))
        assertFalse(sanitized.contains('\n'))
        assertTrue(sanitized.contains("[url]"))
        assertTrue(sanitized.contains("[source]"))
        assertTrue(sanitized.length <= ImportDiagnosticPolicy.MAX_TEXT_CHARS)
    }

    @Test
    fun prependIsNewestFirstAndStrictlyBounded() {
        var entries = emptyList<ImportDiagnostic>()
        repeat(ImportDiagnosticPolicy.MAX_ENTRIES + 7) { index ->
            entries = ImportDiagnosticPolicy.prependBounded(
                entries,
                diagnostic(index.toLong(), "entry-" + index)
            )
        }

        assertEquals(ImportDiagnosticPolicy.MAX_ENTRIES, entries.size)
        assertEquals(
            "entry-" + (ImportDiagnosticPolicy.MAX_ENTRIES + 6),
            entries.first().summary
        )
        assertEquals(7L, entries.last().timestampEpochMillis)
    }

    @Test
    fun codecRoundTripsSanitizedEntriesAndSkipsMalformedRecords() {
        val original = listOf(
            diagnostic(9, "Downloaded from https://models.example/model.gguf"),
            diagnostic(8, "token=secret-value")
        )

        val encoded = ImportDiagnosticPolicy.encode(original)
        val decoded = ImportDiagnosticPolicy.decode("malformed\n$encoded")

        assertEquals(2, decoded.size)
        assertEquals(9L, decoded[0].timestampEpochMillis)
        assertEquals("[url]", decoded[0].summary.substringAfter("Downloaded from "))
        assertFalse(decoded[1].summary.contains("secret-value"))
    }

    private fun diagnostic(timestamp: Long, summary: String): ImportDiagnostic =
        ImportDiagnosticPolicy.create(
            timestampEpochMillis = timestamp,
            operation = "model",
            phase = "activation",
            severity = ImportDiagnosticSeverity.ERROR,
            summary = summary,
            remediation = "Try again."
        )
}
