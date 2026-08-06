package ai.kompile.chat.local.android.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun codecRoundTripsSanitizedEntriesAndMakesMalformedRecordsVisible() {
        val original = listOf(
            diagnostic(
                9,
                "Downloaded from https://models.example/model.gguf",
                "java.io.IOException: failed at /data/user/0/private/model.gguf\n  at example.Loader.open(Loader.kt:42)"
            ),
            diagnostic(8, "token=secret-value")
        )

        val encoded = ImportDiagnosticPolicy.encode(original)
        val decoded = ImportDiagnosticPolicy.decode("malformed\n$encoded")

        assertEquals(3, decoded.size)
        assertEquals(ImportDiagnosticSeverity.ERROR, decoded[0].severity)
        assertEquals("A stored diagnostic entry could not be decoded.", decoded[0].summary)
        assertTrue(decoded[0].technicalDetails.contains("IllegalArgumentException"))
        assertEquals(9L, decoded[1].timestampEpochMillis)
        assertEquals("[url]", decoded[1].summary.substringAfter("Downloaded from "))
        assertFalse(decoded[2].summary.contains("secret-value"))
        assertTrue(decoded[1].technicalDetails.contains("example.Loader.open"))
        assertFalse(decoded[1].technicalDetails.contains("/data/user"))
    }

    @Test
    fun failureDetailsAndClipboardTextAreBoundedRedactedAndActionable() {
        val failure = IllegalStateException(
            "load failed at https://huggingface.co/private?token=hf_1234567890",
            java.io.IOException("native model at /data/user/0/app/model.gguf was rejected")
        )
        val entry = ImportDiagnosticPolicy.create(
            timestampEpochMillis = 11,
            operation = "local chat",
            phase = "generation",
            severity = ImportDiagnosticSeverity.ERROR,
            summary = failure.message.orEmpty(),
            remediation = "Retry after checking the model.",
            technicalDetails = ImportDiagnosticPolicy.failureDetails(failure)
        )

        val copied = ImportDiagnosticPolicy.copyText(entry)
        assertTrue(copied.contains("ERROR · local chat · generation"))
        assertTrue(copied.contains("Next: Retry after checking the model."))
        assertTrue(copied.contains("Caused by: java.io.IOException"))
        assertFalse(copied.contains("huggingface.co"))
        assertFalse(copied.contains("hf_1234567890"))
        assertFalse(copied.contains("/data/user"))
        assertTrue(entry.technicalDetails.length <= ImportDiagnosticPolicy.MAX_DETAIL_CHARS)
    }

    @Test
    fun strictAppGeneratedNativeCrashArtifactPathSurvivesWhileArbitraryPathsRemainRedacted() {
        val artifact =
            "no_backup/native-crash-dumps/tombstone-1722741072345-4321-0123456789abcdef.pb"
        val details = ImportDiagnosticPolicy.sanitizeDetails(
            "Raw tombstone: $artifact\n" +
                "Model: /data/user/0/app/files/Qwen-private.gguf\n" +
                "Repository: private-owner/private-model"
        )

        assertTrue(details.contains(artifact))
        assertFalse(details.contains("Qwen-private.gguf"))
        assertFalse(details.contains("private-owner/private-model"))
        assertTrue(details.contains("[source]"))
    }

    @Test
    fun errorSelectionUsesTheVisiblePhaseAndCopiesTheMatchingTechnicalLog() {
        val unrelated = ImportDiagnosticPolicy.create(
            timestampEpochMillis = 13,
            operation = "hugging face model",
            phase = "connect",
            severity = ImportDiagnosticSeverity.ERROR,
            summary = "old connection failure",
            remediation = "Reconnect.",
            technicalDetails = "example.ConnectException: old failure"
        )
        val verification = ImportDiagnosticPolicy.create(
            timestampEpochMillis = 12,
            operation = "hugging face model",
            phase = "verify",
            severity = ImportDiagnosticSeverity.ERROR,
            summary = "hash mismatch",
            remediation = "Retry verification.",
            technicalDetails = "example.VerificationException: hash mismatch"
        )
        val chat = ImportDiagnosticPolicy.create(
            timestampEpochMillis = 11,
            operation = "local chat",
            phase = "generation",
            severity = ImportDiagnosticSeverity.ERROR,
            summary = "decode failed",
            remediation = "Retry generation.",
            technicalDetails = "example.DecodeException: bad token"
        )
        val entries = listOf(unrelated, verification, chat)

        assertEquals(verification, ImportDiagnosticPolicy.errorForPhase(entries, "Verify"))
        assertEquals(
            chat,
            ImportDiagnosticPolicy.errorForMessage(entries, "decode failed", "local chat")
        )
        val copied = ImportDiagnosticPolicy.copyTextForError(
            "decode failed",
            entries,
            "local chat"
        )
        assertTrue(copied.contains("example.DecodeException: bad token"))
        assertFalse(copied.contains("old connection failure"))
        assertNull(ImportDiagnosticPolicy.errorForPhase(entries, "download"))
        assertNull(
            ImportDiagnosticPolicy.errorForMessage(
                entries,
                "a different generation failure",
                "local chat"
            )
        )
        assertEquals(
            "a different generation failure",
            ImportDiagnosticPolicy.copyTextForError(
                "a different generation failure",
                entries,
                "local chat"
            )
        )
    }

    @Test
    fun decoderRetainsBackwardCompatibleSixFieldRecords() {
        val legacy = ImportDiagnosticPolicy.encode(listOf(diagnostic(1, "legacy")))
            .substringBeforeLast('|')
        val decoded = ImportDiagnosticPolicy.decode(legacy)

        assertEquals(1, decoded.size)
        assertEquals("legacy", decoded.single().summary)
        assertEquals("", decoded.single().technicalDetails)
    }

    private fun diagnostic(
        timestamp: Long,
        summary: String,
        details: String = ""
    ): ImportDiagnostic =
        ImportDiagnosticPolicy.create(
            timestampEpochMillis = timestamp,
            operation = "model",
            phase = "activation",
            severity = ImportDiagnosticSeverity.ERROR,
            summary = summary,
            remediation = "Try again.",
            technicalDetails = details
        )
}
