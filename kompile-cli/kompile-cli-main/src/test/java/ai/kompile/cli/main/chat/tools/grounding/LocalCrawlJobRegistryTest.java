/*
 * Copyright 2025 Kompile Inc.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalCrawlJobRegistryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void startReturnsPollableNonTerminalHandleThenResult() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String jobId = LocalCrawlJobRegistry.newJobId();
        LocalCrawlJobRegistry.submit(jobId, "notes", null, () -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return ToolResult.success("crawl_documents", "done",
                    Map.of("status", "COMPLETED"));
        });

        assertTrue(started.await(5, TimeUnit.SECONDS));
        JsonNode status = mapper.readTree(
                LocalCrawlJobRegistry.status(jobId, mapper).getOutput());
        assertEquals(jobId, status.path("jobId").asText());
        assertFalse(status.path("terminal").asBoolean());
        assertEquals("RUNNING", status.path("status").asText());
        assertEquals(1_000, status.path("pollAfterMs").asInt());

        release.countDown();
        JsonNode terminal = awaitTerminal(jobId);
        assertTrue(terminal.path("terminal").asBoolean());
        assertEquals("COMPLETED", terminal.path("status").asText());

        ToolResult result = LocalCrawlJobRegistry.result(jobId, mapper);
        assertEquals("done", result.getOutput());
        assertEquals(jobId, ((Map<?, ?>) result.getMetadata().get("crawlResult")).get("jobId"));
    }

    @Test
    void cancelIsTerminalAndDoesNotRequireStartingAgain() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String jobId = LocalCrawlJobRegistry.newJobId();
        LocalCrawlJobRegistry.submit(jobId, null, release::countDown, () -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return ToolResult.success("crawl_documents", "unexpected",
                    Map.of("status", "COMPLETED"));
        });

        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(LocalCrawlJobRegistry.cancel(jobId));
        JsonNode status = mapper.readTree(
                LocalCrawlJobRegistry.status(jobId, mapper).getOutput());
        assertEquals("CANCELLED", status.path("status").asText());
        assertTrue(status.path("terminal").asBoolean());
        assertTrue(LocalCrawlJobRegistry.result(jobId, mapper).getOutput().contains("cancel"));
    }

    private JsonNode awaitTerminal(String jobId) throws Exception {
        for (int i = 0; i < 50; i++) {
            JsonNode status = mapper.readTree(
                    LocalCrawlJobRegistry.status(jobId, mapper).getOutput());
            if (status.path("terminal").asBoolean()) {
                return status;
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("job did not reach terminal state: " + jobId);
    }
}
