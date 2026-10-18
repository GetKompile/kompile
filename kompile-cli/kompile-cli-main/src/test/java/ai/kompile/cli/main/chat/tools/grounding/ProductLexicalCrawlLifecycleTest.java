package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ProductManagedWorkTracker;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Model-free fixtures and fake registry work only; no subprocess, model, service or network. */
class ProductLexicalCrawlLifecycleTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    private ToolContext context(ProductManagedWorkTracker tracker) {
        ToolContext context = new ToolContext("product", AgentConfig.builder("product")
                .enabledTools(Set.of("*")).build(), new PermissionService(), root, new ToolRegistry(mapper));
        context.linkProductManagedWork(tracker);
        return context;
    }

    private ObjectNode request() throws Exception {
        ObjectNode request = mapper.createObjectNode().put("corpusUpdate", "preserve-unselected-v1")
                .put("strictSteps", true).put("deriveOntology", false);
        request.putObject("knowledgeBase").put("name", "fixture");
        request.putObject("reasoningLearning").put("enabled", false);
        request.putObject("embeddingTraining").put("enabled", false);
        request.putArray("steps").add("LOADING").add("MARKDOWN_EXTRACTION").add("CHUNKING").add("LEXICAL_INDEX");
        ObjectNode selection = request.putArray("documents").addObject();
        selection.put("path", root.toRealPath().resolve("inputs").toString()).put("sourceType", "DIRECTORY")
                .put("pipelineId", "standard-text").putArray("includePatterns").add("a.txt");
        return request;
    }

    private void fixture() throws Exception {
        root = root.toRealPath();
        Files.writeString(root.resolve("kompile.project.json"),
                "{\"schemaVersion\":1,\"projectId\":\"fixture-project\",\"name\":\"Fixture\"}");
        Path sources = Files.createDirectories(root.resolve("inputs"));
        Path output = Files.createDirectories(root.resolve("data/crawls/fixture"));
        Path markdown = Files.createDirectories(root.resolve("data/markdown/fixture"));
        Files.writeString(sources.resolve("a.txt"), "selected replacement content\n");
        Files.writeString(sources.resolve("b.txt"), "retained source\n");
        StringBuilder docs = new StringBuilder();
        StringBuilder chunks = new StringBuilder();
        for (String id : new String[]{"a", "b"}) {
            ObjectNode row = mapper.createObjectNode().put("documentId", id)
                    .put("source", sources.resolve(id + ".txt").toString()).put("relativePath", "inputs/" + id + ".txt")
                    .put("markdownPath", "data/markdown/fixture/" + id + ".md").put("extractionStatus", "EXTRACTED")
                    .put("pipelineId", "standard-text").put("pipelineType", "STANDARD_TEXT");
            docs.append(mapper.writeValueAsString(row)).append('\n');
            chunks.append(mapper.writeValueAsString(mapper.createObjectNode().put("chunkId", id + "#0")
                    .put("documentId", id).put("index", 0).put("text", "old " + id))).append('\n');
            Files.writeString(markdown.resolve(id + ".md"), "old " + id + "\n");
        }
        Files.writeString(output.resolve("documents.jsonl"), docs);
        Files.writeString(output.resolve("chunks.jsonl"), chunks);
        Files.writeString(output.resolve("crawl-result.json"), "{\"documentCount\":2}");
        Files.writeString(output.resolve("analysis.json"), "{}");
    }

    private JsonNode awaitTerminal(String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            JsonNode state = mapper.readTree(LocalCrawlJobRegistry.status(id, mapper, root).getOutput());
            if (state.path("terminal").asBoolean()) return state;
            Thread.sleep(10);
        }
        throw new AssertionError("No terminal publication for " + id);
    }

    @Test void preservingLexicalExecutionSurvivesToolTurnAndRetainsOwnedResult() throws Exception {
        fixture();
        ProductManagedWorkTracker tracker = new ProductManagedWorkTracker();
        ToolContext turn = context(tracker);
        AtomicBoolean modelCalled = new AtomicBoolean();
        LocalProjectCrawlBackend backend = new LocalProjectCrawlBackend(mapper, (r, file, pipeline, text) -> {
            modelCalled.set(true); throw new AssertionError("No model execution in lexical publication");
        });
        String retained = Files.readString(root.resolve("data/markdown/fixture/b.md"));
        ToolResult accepted = backend.crawlDocuments(request(), turn);
        assertFalse(accepted.isError(), accepted.getOutput());
        String id = String.valueOf(accepted.getMetadata().get("jobId"));
        assertTrue(tracker.owns("crawl", id));
        turn.abort(); // completing/revoking the submitting turn must not revoke registered job work
        assertEquals("COMPLETED", awaitTerminal(id).path("status").asText());
        assertTrue(tracker.cancelAndAwait(Duration.ofSeconds(2)));
        ToolResult completed = backend.result(id, turn);
        assertFalse(completed.isError(), completed.getOutput());
        assertTrue(completed.getMetadata().containsKey("corpusReceipt"), completed.getMetadata().toString());
        JsonNode receipt = mapper.valueToTree(completed.getMetadata().get("corpusReceipt"));
        assertEquals("LEXICAL_ONLY", receipt.path("scope").asText());
        assertEquals(id, receipt.path("jobId").asText());
        assertEquals(retained, Files.readString(root.resolve("data/markdown/fixture/b.md")));
        assertTrue(Files.readString(root.resolve("data/crawls/fixture/chunks.jsonl")).contains("selected replacement"));
        assertFalse(modelCalled.get());
        assertTrue(LocalCrawlJobStore.storedResult(root, id, mapper).getMetadata().containsKey("corpusReceipt"));
        assertTrue(backend.result(id, context(new ProductManagedWorkTracker())).isError());
        for (String operation : new String[]{"status", "cancel", "transcript"}) {
            assertTrue(backend.control(mapper.createObjectNode().put("operation", operation).put("jobId", id),
                    context(new ProductManagedWorkTracker())).isError());
        }
        assertTrue(backend.crawlDocuments(request(), turn).isError(), "late admission must fail");
    }

    @Test void productValidationRejectsImplicitStepsModelsRoutesConnectorsAndRuntimeOptions() throws Exception {
        fixture();
        LocalProjectCrawlBackend backend = new LocalProjectCrawlBackend(mapper, (r, f, p, t) -> {
            throw new AssertionError("model invoked");
        });
        ToolContext context = context(new ProductManagedWorkTracker());
        ObjectNode missingSteps = request(); missingSteps.remove("steps");
        assertTrue(backend.crawlDocuments(missingSteps, context).isError());
        ObjectNode ordinary = request(); ordinary.remove("corpusUpdate");
        assertTrue(backend.crawlDocuments(ordinary, context).isError());
        ObjectNode extraStep = request(); extraStep.withArray("steps").add("VECTOR_INDEXING");
        assertTrue(backend.crawlDocuments(extraStep, context).isError());
        for (String field : new String[]{"models", "modelRuntime", "pipelines", "processingRoute", "routeRules",
                "pipelineRegistry", "runtimeConfig", "codeProjects", "environment", "config"}) {
            ObjectNode invalid = request(); invalid.putObject(field);
            assertTrue(backend.crawlDocuments(invalid, context).isError(), field);
        }
        ObjectNode connector = request(); ((ObjectNode) connector.path("documents").get(0)).put("sourceType", "GDRIVE");
        assertTrue(backend.crawlDocuments(connector, context).isError());
        ObjectNode network = request(); ((ObjectNode) network.path("documents").get(0)).put("url", "https://example.invalid");
        assertTrue(backend.crawlDocuments(network, context).isError());
        ObjectNode enabled = request(); enabled.withObject("/embeddingTraining").put("enabled", true);
        assertTrue(backend.crawlDocuments(enabled, context).isError());
        assertFalse(Files.exists(root.resolve(".kompile/state/crawl-jobs")), "denied before any job submission");
    }

    @Test void queuedCancelFencesEntryAndKeepsDurableTerminalIdentity() throws Exception {
        ProductManagedWorkTracker tracker = new ProductManagedWorkTracker();
        ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
        AtomicBoolean invoked = new AtomicBoolean();
        String id = LocalCrawlJobRegistry.newJobId();
        LocalCrawlJobRegistry.submit(id, "fixture", root, mapper.createObjectNode(), null, () -> {
            invoked.set(true); return ToolResult.success("crawl_documents", "must not run");
        }, tracker, queue::add);
        assertTrue(tracker.owns("crawl", id));
        assertTrue(tracker.cancelAndAwait(Duration.ofSeconds(2)));
        for (Runnable work : queue) work.run();
        assertFalse(invoked.get());
        assertEquals("CANCELLED", awaitTerminal(id).path("status").asText());
        assertTrue(tracker.owns("crawl", id));
        assertTrue(LocalCrawlJobStore.load(root, id).orElseThrow().path("terminal").asBoolean());
        String late = LocalCrawlJobRegistry.newJobId();
        assertThrows(IllegalStateException.class, () -> LocalCrawlJobRegistry.submit(
                late, "fixture", root, mapper.createObjectNode(), null,
                () -> ToolResult.success("crawl_documents", "late"), tracker, queue::add));
        assertFalse(Files.exists(root.resolve(".kompile/state/crawl-jobs").resolve(late)));
    }

    @Test void submissionRejectionPublishesTerminalCancellationWithoutCallingWork() throws Exception {
        ProductManagedWorkTracker tracker = new ProductManagedWorkTracker();
        String id = LocalCrawlJobRegistry.newJobId();
        assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> LocalCrawlJobRegistry.submit(
                id, "fixture", root, mapper.createObjectNode(), null,
                () -> { fail("work entered"); return null; }, tracker,
                task -> { throw new java.util.concurrent.RejectedExecutionException("fixture rejection"); }));
        assertTrue(tracker.cancelAndAwait(Duration.ofSeconds(2)));
        assertEquals("CANCELLED", awaitTerminal(id).path("status").asText());
        assertTrue(tracker.owns("crawl", id));
    }

    @Test void resistantWorkerCannotConfirmCleanupUntilFinallyAndDurablePublication() throws Exception {
        ProductManagedWorkTracker tracker = new ProductManagedWorkTracker();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        String id = LocalCrawlJobRegistry.newJobId();
        try {
            LocalCrawlJobRegistry.submit(id, "fixture", root, mapper.createObjectNode(), null, () -> {
                entered.countDown();
                boolean done = false;
                while (!done) {
                    try { done = release.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException expected) { interrupted.countDown(); }
                }
                return ToolResult.success("crawl_documents", "worker finished");
            }, tracker, executor);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertFalse(tracker.cancelAndAwait(Duration.ofMillis(50)));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertFalse(LocalCrawlJobStore.load(root, id).orElseThrow().path("terminal").asBoolean());
            release.countDown();
            assertTrue(tracker.cancelAndAwait(Duration.ofSeconds(2)));
            assertTrue(LocalCrawlJobStore.load(root, id).orElseThrow().path("terminal").asBoolean());
        } finally {
            release.countDown(); executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void workerExitWithoutDurableTerminalPublicationIsNotCleanupProof() throws Exception {
        ProductManagedWorkTracker tracker = new ProductManagedWorkTracker();
        String id = LocalCrawlJobRegistry.newJobId();
        Path directory = root.resolve(".kompile/state/crawl-jobs").resolve(id);
        Path trace = directory.resolve("trace.jsonl");
        Path backup = directory.resolve("trace.backup");
        CountDownLatch blocked = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LocalCrawlJobRegistry.submit(id, "fixture", root, mapper.createObjectNode(), null, () -> {
                Files.move(trace, backup); Files.createDirectory(trace);
                blocked.countDown();
                return ToolResult.success("crawl_documents", "complete", Map.of("status", "COMPLETED"));
            }, tracker, executor);
            assertTrue(blocked.await(2, TimeUnit.SECONDS));
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "worker has physically returned");
            assertFalse(tracker.cancelAndAwait(Duration.ofMillis(30)), "publication is still unproved");
            assertFalse(tracker.cleanupConfirmed());
        } finally {
            if (Files.isDirectory(trace)) Files.delete(trace);
            if (Files.exists(backup)) Files.move(backup, trace);
            executor.shutdownNow();
        }
        assertEquals("COMPLETED", awaitTerminal(id).path("status").asText());
        assertTrue(tracker.cancelAndAwait(Duration.ofSeconds(2)));
    }
}
