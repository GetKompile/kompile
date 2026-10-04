/*
 * Copyright 2025 Kompile Inc.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalCrawlJobRegistryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path projectRoot;

    @Test
    void startReturnsPollableNonTerminalHandleThenResult() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String jobId = LocalCrawlJobRegistry.newJobId();
        LocalCrawlJobRegistry.submit(jobId, "notes", projectRoot,
                mapper.createObjectNode().put("name", "durable-test"), null, () -> {
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
        assertTrue(status.path("cancellable").asBoolean());
        assertTrue(status.path("nextActions").findValuesAsText("name").contains("cancel"));

        release.countDown();
        JsonNode terminal = awaitTerminal(jobId);
        assertTrue(terminal.path("terminal").asBoolean());
        assertEquals("COMPLETED", terminal.path("status").asText());

        ToolResult result = LocalCrawlJobRegistry.result(jobId, mapper);
        assertEquals("done", result.getOutput());
        assertEquals(jobId, ((Map<?, ?>) result.getMetadata().get("crawlResult")).get("jobId"));
        ToolResult transcript = LocalCrawlJobRegistry.transcript(jobId, mapper, projectRoot);
        assertFalse(transcript.isError(), transcript.getOutput());
        assertTrue(transcript.getOutput().contains("durable-test"));
        assertTrue(transcript.getOutput().contains("JOB_TERMINAL"));
        JsonNode durable = LocalCrawlJobStore.load(projectRoot, jobId).orElseThrow();
        assertEquals("COMPLETED", durable.path("status").asText());
        assertTrue(durable.path("terminal").asBoolean());
        assertFalse(durable.path("cancellable").asBoolean());
        assertFalse(durable.path("finishedAt").asText().isBlank());
    }

    @Test
    void runtimeErrorsRemainRetrievableAsTerminalResults() throws Exception {
        String jobId = LocalCrawlJobRegistry.newJobId();
        LocalCrawlJobRegistry.submit(jobId, "notes", null, () -> {
            throw new AssertionError("linkage-style failure");
        });

        JsonNode terminal = awaitTerminal(jobId);
        assertEquals("FAILED", terminal.path("status").asText());
        assertTrue(terminal.path("resultAvailable").asBoolean());

        ToolResult result = LocalCrawlJobRegistry.result(jobId, mapper);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("java.lang.AssertionError: linkage-style failure"));
    }

    @Test
    void terminalResultAdoptsAndPersistsWorkerKnowledgeBase() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String jobId = LocalCrawlJobRegistry.newJobId();
        LocalCrawlJobRegistry.submit(jobId, null, projectRoot,
                mapper.createObjectNode().put("name", "selector-test"), null, () -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return ToolResult.success("crawl_documents", "done",
                    Map.of("status", "COMPLETED", "knowledgeBase", "project-knowledge"));
        });

        assertTrue(started.await(5, TimeUnit.SECONDS));
        JsonNode running = mapper.readTree(
                LocalCrawlJobRegistry.status(jobId, mapper).getOutput());
        assertFalse(running.path("crawlResult").has("knowledgeBase"));
        assertFalse(running.path("nextActions").toString().contains("inspectKnowledge"));

        release.countDown();
        JsonNode terminal = awaitTerminal(jobId);
        assertEquals("project-knowledge",
                terminal.path("crawlResult").path("knowledgeBase").asText());
        assertTrue(terminal.path("nextActions").toString().contains("inspectKnowledge"));

        ToolResult result = LocalCrawlJobRegistry.result(jobId, mapper);
        assertEquals("project-knowledge",
                ((Map<?, ?>) result.getMetadata().get("crawlResult")).get("knowledgeBase"));
        ToolResult stored = LocalCrawlJobStore.storedResult(projectRoot, jobId, mapper);
        assertEquals("project-knowledge",
                ((Map<?, ?>) stored.getMetadata().get("crawlResult")).get("knowledgeBase"));
        assertEquals("COMPLETED", stored.getMetadata().get("status"));
        assertEquals(true, stored.getMetadata().get("terminal"));
    }

    @Test
    void cancelRemainsNonTerminalUntilWorkerCleanupFinishes() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        String jobId = LocalCrawlJobRegistry.newJobId();
        LocalCrawlJobRegistry.submit(jobId, null, null, () -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
                throw new AssertionError("crawl worker was not interrupted");
            } catch (InterruptedException expected) {
                interrupted.countDown();
                releaseCleanup.await(5, TimeUnit.SECONDS);
                throw expected;
            }
        });

        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(LocalCrawlJobRegistry.cancel(jobId));
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        JsonNode cancelling = mapper.readTree(
                LocalCrawlJobRegistry.status(jobId, mapper).getOutput());
        assertEquals("CANCELLING", cancelling.path("status").asText());
        assertFalse(cancelling.path("terminal").asBoolean());

        releaseCleanup.countDown();
        JsonNode status = awaitTerminal(jobId);
        assertEquals("CANCELLED", status.path("status").asText());
        assertTrue(status.path("terminal").asBoolean());
        assertTrue(LocalCrawlJobRegistry.result(jobId, mapper).getOutput().contains("cancel"));
    }

    @Test
    void statusIncludesLatestStructuredPipelineProgress() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String jobId = LocalCrawlJobRegistry.newJobId();
        LocalCrawlJobRegistry.submit(jobId, "notes", null, () -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return ToolResult.success("crawl_documents", "done", Map.of("status", "COMPLETED"));
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));

        LocalCrawlJobRegistry.updatePipelineProgress(jobId, "VLM_EXTRACTION", "Page 2/3", 49,
                Map.of("phase", "VLM_EXTRACTION", "currentPage", 2, "totalPages", 3));
        JsonNode status = mapper.readTree(LocalCrawlJobRegistry.status(jobId, mapper).getOutput());
        assertEquals("VLM_EXTRACTION", status.path("stage").asText());
        assertEquals(49, status.path("progressPercent").asInt());
        assertEquals(2, status.path("pipelineProgress").path("currentPage").asInt());
        assertEquals(3, status.path("pipelineProgress").path("totalPages").asInt());
        release.countDown();
        awaitTerminal(jobId);
        LocalCrawlJobRegistry.updatePipelineProgress(jobId, "STALE", "late update", 1,
                Map.of("currentPage", 99));
        JsonNode unchanged = mapper.readTree(LocalCrawlJobRegistry.status(jobId, mapper).getOutput());
        assertEquals("COMPLETED", unchanged.path("stage").asText());
        assertEquals(2, unchanged.path("pipelineProgress").path("currentPage").asInt());
    }

    @Test
    void committedCorpusCannotBeCancelledWhileFinalizing() throws Exception {
        CountDownLatch committed = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        String jobId = LocalCrawlJobRegistry.newJobId();
        var request = mapper.createObjectNode().put("name", "frozen");
        LocalCrawlJobRegistry.submit(jobId, "notes", projectRoot, request, null, () -> {
            var job = LocalCrawlJobRegistry.get(jobId);
            assertEquals("frozen", job.submittedRequest(projectRoot).path("name").asText());
            job.commitCorpus(() -> receipt(jobId));
            committed.countDown();
            finish.await(5, TimeUnit.SECONDS);
            return ToolResult.success("crawl_documents", "done", Map.of("status", "COMPLETED"));
        });
        request.put("name", "caller mutation");
        assertTrue(committed.await(5, TimeUnit.SECONDS));
        assertFalse(LocalCrawlJobRegistry.cancel(jobId));
        assertFalse(mapper.readTree(LocalCrawlJobRegistry.status(jobId, mapper).getOutput())
                .path("cancellable").asBoolean());
        finish.countDown();
        assertEquals("COMPLETED", awaitTerminal(jobId).path("status").asText());
        assertTrue(LocalCrawlJobStore.load(projectRoot, jobId).orElseThrow()
                .path("corpusCommitted").asBoolean());
    }

    @Test
    void cancellationBeforeCommitNeverRunsPublicationAction() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean published = new AtomicBoolean();
        String jobId = LocalCrawlJobRegistry.newJobId();
        LocalCrawlJobRegistry.submit(jobId, "notes", projectRoot,
                mapper.createObjectNode(), null, () -> {
                    started.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException expected) {
                        Thread.interrupted();
                    }
                    assertThrows(InterruptedIOException.class, () ->
                            LocalCrawlJobRegistry.get(jobId).commitCorpus(() -> {
                                published.set(true);
                                return null;
                            }));
                    return ToolResult.success("crawl_documents", "not published", Map.of());
                });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(LocalCrawlJobRegistry.cancel(jobId));
        assertEquals("CANCELLED", awaitTerminal(jobId).path("status").asText());
        assertFalse(published.get());
    }

    @Test
    void callerCannotBorrowAnActiveJobPublicationCapability() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean published = new AtomicBoolean();
        String jobId = LocalCrawlJobRegistry.newJobId();
        LocalCrawlJobRegistry.submit(jobId, "notes", projectRoot,
                mapper.createObjectNode(), null, () -> {
                    started.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return ToolResult.success("crawl_documents", "done", Map.of());
                });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        try {
            var job = LocalCrawlJobRegistry.get(jobId);
            assertThrows(IOException.class, () -> job.submittedRequest(projectRoot));
            assertThrows(IOException.class, () -> job.commitCorpus(() -> {
                published.set(true);
                return receipt(jobId);
            }));
            assertFalse(published.get());
        } finally {
            release.countDown();
            awaitTerminal(jobId);
        }
    }

    @Test
    void resultFailureAfterCommitRetainsFrozenReceiptInStateAndTrace() throws Exception {
        String jobId = LocalCrawlJobRegistry.newJobId();
        var original = receipt(jobId);
        LocalCrawlJobRegistry.submit(jobId, "notes", projectRoot,
                mapper.createObjectNode(), null, () -> {
                    var returned = LocalCrawlJobRegistry.get(jobId).commitCorpus(() -> original);
                    original.put("knowledgeBaseId", "caller-mutation");
                    ((com.fasterxml.jackson.databind.node.ObjectNode) returned).put("jobId", "mutated");
                    throw new IOException("post-commit result failure");
                });
        assertEquals("FAILED", awaitTerminal(jobId).path("status").asText());
        JsonNode state = LocalCrawlJobStore.load(projectRoot, jobId).orElseThrow();
        assertTrue(state.path("corpusCommitted").asBoolean());
        assertEquals("notes", state.path("knowledgeBaseId").asText());
        assertEquals(jobId, state.path("result").path("metadata").path("corpusReceipt")
                .path("jobId").asText());
        JsonNode transcript = mapper.readTree(LocalCrawlJobStore.transcript(projectRoot, jobId, mapper).getOutput());
        assertTrue(transcript.path("events").toString().contains("post-commit result failure"));
        assertTrue(transcript.path("events").toString().contains("CORPUS_COMMITTED"));
    }

    @Test
    void terminalPersistenceFailureStaysNonTerminalUntilPollRetriesSuccessfully() throws Exception {
        String jobId = LocalCrawlJobRegistry.newJobId();
        Path directory = projectRoot.resolve(".kompile/state/crawl-jobs").resolve(jobId);
        Path trace = directory.resolve("trace.jsonl");
        Path backup = directory.resolve("trace.backup");
        CountDownLatch blocked = new CountDownLatch(1);
        LocalCrawlJobRegistry.submit(jobId, "notes", projectRoot,
                mapper.createObjectNode(), null, () -> {
                    Files.move(trace, backup);
                    Files.createDirectory(trace);
                    blocked.countDown();
                    return ToolResult.success("crawl_documents", "done", Map.of());
                });
        assertTrue(blocked.await(5, TimeUnit.SECONDS));
        try {
            JsonNode pending = null;
            for (int i = 0; i < 100; i++) {
                pending = mapper.readTree(LocalCrawlJobRegistry.status(jobId, mapper).getOutput());
                if ("FINALIZING".equals(pending.path("status").asText())) break;
                Thread.sleep(20);
            }
            assertEquals("FINALIZING", pending.path("status").asText());
            assertFalse(pending.path("terminal").asBoolean());
            assertFalse(LocalCrawlJobStore.load(projectRoot, jobId).orElseThrow().path("terminal").asBoolean());
        } finally {
            Files.delete(trace);
            Files.move(backup, trace);
        }
        assertEquals("COMPLETED", awaitTerminal(jobId).path("status").asText());
        assertTrue(LocalCrawlJobStore.load(projectRoot, jobId).orElseThrow().path("terminal").asBoolean());
        assertTrue(LocalCrawlJobStore.transcript(projectRoot, jobId, mapper).getOutput().contains("JOB_TERMINAL"));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode receipt(String jobId) {
        return mapper.createObjectNode().put("schema", "kompile-local-corpus-receipt/v1")
                .put("status", "CORPUS_COMMITTED").put("scope", "LEXICAL_ONLY")
                .put("jobId", jobId).put("projectRoot", projectRoot.toAbsolutePath().normalize().toString())
                .put("knowledgeBaseId", "notes");
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
