package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalCrawlJobStoreTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path projectRoot;

    @Test
    void persistsRedactedRequestTerminalResultAndTranscriptByExactJobId() throws Exception {
        String jobId = LocalCrawlJobRegistry.newJobId();
        ObjectNode request = mapper.createObjectNode();
        request.putObject("processingRoute").put("apiKey", "secret-value");
        ObjectNode document = request.putArray("documents").addObject()
                .put("url", "https://example.test/source?access_token=url-secret");
        document.putObject("properties")
                .put("accessToken", "access-secret")
                .put("apiToken", "api-secret")
                .put("botToken", "bot-secret");
        LocalCrawlJobStore.initialize(projectRoot, jobId, "notes", request);

        ObjectNode state = LocalCrawlJobStore.load(projectRoot, jobId).orElseThrow();
        state.put("status", "COMPLETED");
        state.put("terminal", true);
        state.put("resultAvailable", true);
        state.put("stage", "COMPLETED");
        state.put("progressPercent", 100);
        state.put("finishedAt", "2026-08-21T00:00:00Z");
        ObjectNode result = state.putObject("result");
        result.put("title", "crawl_documents");
        result.put("output", "done");
        result.putObject("metadata")
                .put("status", "COMPLETED")
                .put("refreshToken", "result-secret");
        result.put("error", false);
        LocalCrawlJobStore.persist(projectRoot, state, "JOB_TERMINAL");

        ToolResult transcript = LocalCrawlJobStore.transcript(projectRoot, jobId, mapper);
        JsonNode transcriptJson = mapper.readTree(transcript.getOutput());
        assertFalse(transcript.isError(), transcript.getOutput());
        assertEquals(jobId, transcriptJson.path("jobId").asText());
        assertEquals("***REDACTED***",
                transcriptJson.path("request").path("processingRoute").path("apiKey").asText());
        JsonNode properties = transcriptJson.path("request").path("documents").get(0).path("properties");
        assertEquals("***REDACTED***", properties.path("accessToken").asText());
        assertEquals("***REDACTED***", properties.path("apiToken").asText());
        assertEquals("***REDACTED***", properties.path("botToken").asText());
        assertFalse(transcript.getOutput().contains("url-secret"), transcript.getOutput());
        assertFalse(transcript.getOutput().contains("result-secret"), transcript.getOutput());
        assertTrue(transcriptJson.path("events").size() >= 2);

        ToolResult storedResult = LocalCrawlJobStore.storedResult(projectRoot, jobId, mapper);
        assertFalse(storedResult.isError(), storedResult.getOutput());
        assertEquals("done", storedResult.getOutput());
        assertEquals(jobId, storedResult.getMetadata().get("jobId"));
    }

    @Test
    void traceFailureCannotPublishTerminalState() throws Exception {
        String jobId = LocalCrawlJobRegistry.newJobId();
        LocalCrawlJobStore.initialize(projectRoot, jobId, "notes", mapper.createObjectNode());
        ObjectNode state = LocalCrawlJobStore.load(projectRoot, jobId).orElseThrow();
        state.put("status", "COMPLETED").put("terminal", true);
        Path directory = projectRoot.resolve(".kompile/state/crawl-jobs").resolve(jobId);
        Path trace = directory.resolve("trace.jsonl");
        Path backup = directory.resolve("trace.backup");
        Files.move(trace, backup);
        Files.createDirectory(trace);
        try {
            assertThrows(IllegalStateException.class,
                    () -> LocalCrawlJobStore.persist(projectRoot, state, "JOB_TERMINAL"));
            assertFalse(LocalCrawlJobStore.load(projectRoot, jobId).orElseThrow().path("terminal").asBoolean());
        } finally {
            Files.delete(trace);
            Files.move(backup, trace);
        }
        LocalCrawlJobStore.persist(projectRoot, state, "JOB_TERMINAL");
        assertTrue(LocalCrawlJobStore.load(projectRoot, jobId).orElseThrow().path("terminal").asBoolean());
        assertTrue(LocalCrawlJobStore.transcript(projectRoot, jobId, mapper).getOutput().contains("JOB_TERMINAL"));
    }

    @Test
    void reconcilesNonTerminalJobWhoseOwningProcessExited() throws Exception {
        String jobId = LocalCrawlJobRegistry.newJobId();
        LocalCrawlJobStore.initialize(projectRoot, jobId, "notes", mapper.createObjectNode());
        ObjectNode state = LocalCrawlJobStore.load(projectRoot, jobId).orElseThrow();
        state.put("ownerPid", Long.MAX_VALUE);
        state.put("status", "RUNNING");
        state.put("terminal", false);
        LocalCrawlJobStore.persist(projectRoot, state, "JOB_STAGE");

        ToolResult status = LocalCrawlJobStore.storedStatus(projectRoot, jobId, mapper);
        JsonNode payload = mapper.readTree(status.getOutput());
        assertEquals("FAILED", payload.path("status").asText());
        assertEquals("INTERRUPTED", payload.path("stage").asText());
        assertTrue(payload.path("terminal").asBoolean());
        assertTrue(LocalCrawlJobStore.storedResult(projectRoot, jobId, mapper).isError());
    }

    @Test
    void durableRunningJobDoesNotAdvertiseCrossProcessCancellation() throws Exception {
        String jobId = LocalCrawlJobRegistry.newJobId();
        LocalCrawlJobStore.initialize(projectRoot, jobId, "notes", mapper.createObjectNode());
        ObjectNode state = LocalCrawlJobStore.load(projectRoot, jobId).orElseThrow();
        state.put("ownerPid", ProcessHandle.current().pid());
        state.put("status", "RUNNING");
        state.put("terminal", false);
        state.put("stage", "DOCUMENT_PROCESSING");
        LocalCrawlJobStore.persist(projectRoot, state, "JOB_STAGE");

        ToolResult status = LocalCrawlJobStore.storedStatus(projectRoot, jobId, mapper);
        JsonNode payload = mapper.readTree(status.getOutput());

        assertFalse(payload.path("cancellable").asBoolean());
        assertTrue(payload.path("cancellationReason").asText().contains("owns this running crawl"));
        assertFalse(payload.path("nextActions").findValuesAsText("name").contains("cancel"));
        assertTrue(payload.path("nextActions").findValuesAsText("name").contains("monitor"));
        assertEquals(false, status.getMetadata().get("cancellable"));
    }
}
