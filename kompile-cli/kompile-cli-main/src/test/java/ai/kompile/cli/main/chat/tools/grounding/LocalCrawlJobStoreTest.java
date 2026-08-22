package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalCrawlJobStoreTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path projectRoot;

    @Test
    void persistsRedactedRequestTerminalResultAndTranscriptByExactJobId() throws Exception {
        String jobId = LocalCrawlJobRegistry.newJobId();
        ObjectNode request = mapper.createObjectNode();
        request.putObject("processingRoute").put("apiKey", "secret-value");
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
        result.putObject("metadata").put("status", "COMPLETED");
        result.put("error", false);
        LocalCrawlJobStore.persist(projectRoot, state, "JOB_TERMINAL");

        ToolResult transcript = LocalCrawlJobStore.transcript(projectRoot, jobId, mapper);
        JsonNode transcriptJson = mapper.readTree(transcript.getOutput());
        assertFalse(transcript.isError(), transcript.getOutput());
        assertEquals(jobId, transcriptJson.path("jobId").asText());
        assertEquals("***REDACTED***",
                transcriptJson.path("request").path("processingRoute").path("apiKey").asText());
        assertTrue(transcriptJson.path("events").size() >= 2);

        ToolResult storedResult = LocalCrawlJobStore.storedResult(projectRoot, jobId, mapper);
        assertFalse(storedResult.isError(), storedResult.getOutput());
        assertEquals("done", storedResult.getOutput());
        assertEquals(jobId, storedResult.getMetadata().get("jobId"));
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
}
