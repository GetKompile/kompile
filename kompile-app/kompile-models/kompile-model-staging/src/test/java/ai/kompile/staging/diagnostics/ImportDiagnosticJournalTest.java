package ai.kompile.staging.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportDiagnosticJournalTest {

    @Test
    void boundsHistoryAndReturnsNewestEventsFirst() {
        ImportDiagnosticJournal journal = new ImportDiagnosticJournal(3);
        String attempt = journal.start("chat", "huggingface:owner/repo", Map.of());

        for (int i = 0; i < 4; i++) {
            journal.record(
                    attempt,
                    "chat",
                    "huggingface:owner/repo",
                    ImportPhase.DOWNLOAD,
                    ImportDiagnosticCode.DOWNLOAD_COMPLETE,
                    ImportDiagnosticSeverity.INFO,
                    "download " + i,
                    "",
                    Map.of("index", i));
        }

        List<ImportDiagnosticEvent> recent = journal.recent(20);
        assertEquals(3, recent.size());
        assertEquals("download 3", recent.get(0).summary());
        assertEquals("download 1", recent.get(2).summary());
        assertEquals(3, journal.attempt(attempt).size());
    }

    @Test
    void classifiesMissingAmbiguousBundleDownloadAndCompileFailures() {
        ImportDiagnosticJournal journal = new ImportDiagnosticJournal(20);
        String attempt = journal.start("chat", "huggingface:owner/repo", Map.of());

        assertEquals(
                ImportDiagnosticCode.MODEL_NOT_FOUND,
                journal.failure(
                        attempt,
                        "chat",
                        "huggingface:owner/repo",
                        ImportPhase.DISCOVER,
                        new IllegalArgumentException("Model file not found")).code());
        assertEquals(
                ImportDiagnosticCode.MODEL_SELECTION_REQUIRED,
                journal.failure(
                        attempt,
                        "chat",
                        "huggingface:owner/repo",
                        ImportPhase.DISCOVER,
                        new IllegalArgumentException("Multiple GGUF candidates; select one")).code());
        ImportDiagnosticEvent incomplete = journal.failure(
                attempt,
                "chat",
                "https-components:component-bundle",
                ImportPhase.SELECT,
                new IllegalArgumentException(
                        "Missing tokenizer_config.json or chat_template.jinja"));
        assertEquals(ImportDiagnosticCode.ASSET_BUNDLE_INCOMPLETE, incomplete.code());
        assertTrue(incomplete.remediation().contains("tokenizer.json"));
        assertEquals(
                ImportDiagnosticCode.DOWNLOAD_FAILED,
                journal.failure(
                        attempt,
                        "chat",
                        "https-components:component-bundle",
                        ImportPhase.DOWNLOAD,
                        new IllegalStateException("Connection reset")).code());
        assertEquals(
                ImportDiagnosticCode.COMPILE_FAILED,
                journal.failure(
                        attempt,
                        "chat",
                        "https-components:component-bundle",
                        ImportPhase.COMPILE,
                        new IllegalStateException("Target compiler rejected the graph")).code());
    }

    @Test
    void scrubsCredentialsQueriesFragmentsAndUnboundedFailureData() {
        ImportDiagnosticJournal journal = new ImportDiagnosticJournal(4);
        String attempt = journal.start(
                "chat",
                "https://user:secret@example.test/model.gguf?token=hf_source_secret#fragment",
                Map.of("authToken", "hf_top_secret"));

        ImportDiagnosticEvent event = journal.record(
                attempt,
                "chat",
                "https-components:component-bundle",
                ImportPhase.DOWNLOAD,
                ImportDiagnosticCode.DOWNLOAD_FAILED,
                ImportDiagnosticSeverity.ERROR,
                "GET https://user:secret@example.test/model.gguf?token=hf_summary_secret#part failed; "
                        + "Authorization=Bearer abc.def",
                "Retry with token=hf_remediation_secret",
                Map.of(
                        "asset.model",
                        "https://user:secret@example.test/model.gguf?token=hf_detail_secret#part",
                        "credential", "password-value",
                        "message", "Bearer live-token",
                        "oversized", "x".repeat(5000)));

        String serializedView = event.toString() + journal.recent(4);
        assertFalse(serializedView.contains("secret"));
        assertFalse(serializedView.contains("abc.def"));
        assertFalse(serializedView.contains("live-token"));
        assertFalse(serializedView.contains("?token="));
        assertFalse(serializedView.contains("#fragment"));
        assertEquals("https://example.test/model.gguf", event.details().get("asset.model"));
        assertEquals("[redacted]", event.details().get("credential"));
        assertTrue(event.details().get("oversized").length() < 900);
        assertFalse(event.details().containsKey("stackTrace"));
    }

    @Test
    void sanitizesStructuredAndEmbeddedSecretsAcrossTextContexts() {
        List<TextCase> urlCases = List.of(
                new TextCase(
                        "[https://user:pw@example.test/model.gguf?token=hf_bracket_secret#part]",
                        "[https://example.test/model.gguf]"),
                new TextCase(
                        "\"https://user:pw@example.test/model.gguf?X-Amz-Signature=signed-secret\"",
                        "\"https://example.test/model.gguf\""),
                new TextCase(
                        "(https://user:pw@example.test/model.gguf?sig=signed-secret#part).",
                        "(https://example.test/model.gguf)."),
                new TextCase(
                        "'https://user:pw@example.test/model.gguf?access_token=hf_quote_secret'",
                        "'https://example.test/model.gguf'"));
        for (TextCase testCase : urlCases) {
            assertEquals(testCase.expected(), ImportDiagnosticJournal.safeText(testCase.input()));
        }

        assertEquals(
                "Authorization: [redacted]",
                ImportDiagnosticJournal.safeText("Authorization: Bearer abc.def-123"));
        assertEquals(
                "Bearer [redacted]",
                ImportDiagnosticJournal.safeText("Bearer abc.def-123"));
        assertEquals(
                "token=[redacted]",
                ImportDiagnosticJournal.safeText("token=hf_abcdefghijklmnopqrstuvwxyz"));
        assertEquals(
                "https://example.test/models/_encoded_/model.gguf",
                ImportDiagnosticJournal.safeText(
                        "https://example.test/models/%68%66%5Fsecret/model.gguf"));
        String multiline = ImportDiagnosticJournal.safeText(
                "first cause\nsecond cause: hf_abcdefghijklmnopqrstuvwxyz");
        assertFalse(multiline.contains("\n"));
        assertFalse(multiline.contains("abcdefghijklmnopqrstuvwxyz"));
        assertTrue(multiline.contains("hf_[redacted]"));

        String bounded = ImportDiagnosticJournal.safeText("x".repeat(5000));
        assertTrue(bounded.length() <= 769);
        assertTrue(bounded.endsWith("…"));
    }

    private record TextCase(String input, String expected) {
    }
}
