package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ProductManagedWorkTracker;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.project.ChatModelPipelineRunner;
import ai.kompile.cli.main.project.NativeChatModels;
import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import ai.kompile.utils.NativeImageInfo;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Real lifecycle with fake host/loopback HTTP responses only: no external inference or artifact runtime. */
@TemporaryUserHome
class ProductPipelineLifecycleTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private ToolContext context(ProductManagedWorkTracker tracker) {
        ToolContext context = new ToolContext("product", AgentConfig.builder("product")
                .enabledTools(Set.of("pipeline")).build(), new PermissionService(), root, new ToolRegistry(mapper));
        context.setAutoApproveAll(true);
        context.linkProductManagedWork(tracker);
        return context;
    }

    private ObjectNode definition() {
        ObjectNode definition = mapper.createObjectNode().put("pipelineId", "host-chat")
                .put("kind", "LLM").put("topology", "SEQUENCE");
        definition.putObject("processor").put("type", "CHAT_MODEL").put("provider", "custom")
                .put("modelId", "fake-host-model").put("prompt", "Fixture only");
        return definition;
    }

    private ObjectNode run() {
        ObjectNode request = mapper.createObjectNode().put("action", "run").put("pipelineId", "host-chat");
        request.putObject("input").put("text", "fixture input");
        return request;
    }

    private JsonNode success(PipelineTool tool, ObjectNode params, ToolContext context) throws Exception {
        ToolResult result = tool.execute(params, context);
        assertFalse(result.isError(), result.getOutput());
        return mapper.readTree(result.getOutput());
    }

    private void create(PipelineTool tool, ToolContext context, ObjectNode definition) throws Exception {
        ObjectNode create = mapper.createObjectNode().put("action", "create");
        create.set("definition", definition);
        success(tool, create, context);
    }

    private void configureHttpOnly() throws Exception {
        // Configuration resolution only. The injected host boundary never sends this request.
        new ChatConfig("custom", null, "fake-host-model", "https://example.invalid/v1").saveProject(root);
    }

    @Test void fakeHostExecutionIsOwnedAndTerminalResultRemainsSessionScoped() throws Exception {
        configureHttpOnly();
        ProductManagedWorkTracker tracker = new ProductManagedWorkTracker();
        ToolContext context = context(tracker);
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            PipelineTool tool = new PipelineTool(mapper, executor, (r, d, input, progress, chunks) -> {
                assertEquals("fixture input", input.get("text"));
                chunks.accept("fake answer");
                return "fake answer";
            });
            create(tool, context, definition());
            JsonNode completed = success(tool, run().put("wait", true), context);
            String id = completed.path("runId").asText();
            assertEquals("COMPLETED", completed.path("status").asText());
            assertEquals("fake answer", completed.path("output").path("text").asText());
            assertTrue(tracker.owns("pipeline", id));
            assertTrue(tracker.cancelAndAwait(Duration.ofSeconds(2)));
            assertEquals("COMPLETED", success(tool, mapper.createObjectNode().put("action", "status")
                    .put("runId", id), context).path("status").asText());
            ToolContext foreign = context(new ProductManagedWorkTracker());
            assertTrue(tool.execute(mapper.createObjectNode().put("action", "status").put("runId", id), foreign).isError());
            assertTrue(tool.execute(mapper.createObjectNode().put("action", "cancel").put("runId", id), foreign).isError());
            assertTrue(tool.execute(run(), context).isError(), "late submission must be rejected");
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void composedChatUsesTheSameTrackedHostBoundary() throws Exception {
        configureHttpOnly();
        ProductManagedWorkTracker tracker = new ProductManagedWorkTracker();
        ToolContext context = context(tracker);
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            PipelineTool tool = new PipelineTool(mapper, executor, (r, d, i, p, c) -> {
                assertNull(d.getProcessor());
                assertNotNull(d.getPipelineSpec());
                return "fake composed answer";
            });
            ObjectNode composed = composedDefinition();
            create(tool, context, composed);
            assertEquals("COMPLETED", success(tool, run().put("wait", true), context).path("status").asText());
            assertTrue(tracker.cancelAndAwait(Duration.ofSeconds(2)));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void closeCannotConfirmUntilResistantHostWorkerFinallyExits() throws Exception {
        configureHttpOnly();
        ProductManagedWorkTracker tracker = new ProductManagedWorkTracker();
        ToolContext context = context(tracker);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            PipelineTool tool = new PipelineTool(mapper, executor, (r, d, input, progress, chunks) -> {
                entered.countDown();
                boolean done = false;
                while (!done) {
                    try { done = release.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException expected) { interrupted.countDown(); }
                }
                return "late answer must not be published";
            });
            create(tool, context, definition());
            String id = success(tool, run(), context).path("runId").asText();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertFalse(tracker.cancelAndAwait(Duration.ofMillis(50)));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            JsonNode cancelling = success(tool, mapper.createObjectNode().put("action", "status").put("runId", id), context);
            assertFalse(cancelling.path("terminationConfirmed").asBoolean());
            assertFalse(cancelling.path("terminal").asBoolean());
            assertFalse(tracker.cleanupConfirmed());
            release.countDown();
            assertTrue(tracker.cancelAndAwait(Duration.ofSeconds(2)));
            JsonNode cancelled = success(tool, mapper.createObjectNode().put("action", "status").put("runId", id), context);
            assertEquals("CANCELLED", cancelled.path("status").asText());
            assertFalse(cancelled.has("output"));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void queuedCancellationFencesHostEntryAndRejectsLateAdmission() throws Exception {
        ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
        ProductManagedWorkTracker tracker = new ProductManagedWorkTracker();
        ToolContext context = context(tracker);
        AtomicBoolean invoked = new AtomicBoolean();
        PipelineTool tool = new PipelineTool(mapper, queue::add, (r, d, input, progress, chunks) -> {
            invoked.set(true); return "must not execute";
        });
        create(tool, context, definition());
        String id = success(tool, run(), context).path("runId").asText();
        assertTrue(tracker.owns("pipeline", id), "registered before executor accepted the queued work");
        assertTrue(tracker.cancelAndAwait(Duration.ofSeconds(2)));
        for (Runnable queued : queue) queued.run();
        assertFalse(invoked.get());
        assertEquals("CANCELLED", success(tool, mapper.createObjectNode().put("action", "status")
                .put("runId", id), context).path("status").asText());
        assertTrue(tool.execute(run(), context).isError());
    }

    @Test void queuedInnerHostTaskCancellationDoesNotMistakeFutureDoneForWorkerEntry() throws Exception {
        ProductManagedWorkTracker tracker = new ProductManagedWorkTracker();
        ToolContext context = context(tracker);
        ConcurrentLinkedQueue<Runnable> innerQueue = new ConcurrentLinkedQueue<>();
        CountDownLatch innerSubmitted = new CountDownLatch(1);
        AtomicInteger submissions = new AtomicInteger();
        AtomicBoolean invoked = new AtomicBoolean();
        ExecutorService outer = Executors.newSingleThreadExecutor();
        try {
            Executor controlled = task -> {
                if (submissions.getAndIncrement() == 0) outer.execute(task);
                else { innerQueue.add(task); innerSubmitted.countDown(); }
            };
            PipelineTool tool = new PipelineTool(mapper, controlled, (r, d, i, p, c) -> {
                invoked.set(true); return "must not run";
            });
            create(tool, context, definition());
            String id = success(tool, run(), context).path("runId").asText();
            assertTrue(innerSubmitted.await(2, TimeUnit.SECONDS));
            assertTrue(tracker.cancelAndAwait(Duration.ofSeconds(2)));
            for (Runnable task : innerQueue) task.run();
            assertFalse(invoked.get());
            assertEquals("CANCELLED", success(tool, mapper.createObjectNode().put("action", "status")
                    .put("runId", id), context).path("status").asText());
        } finally {
            outer.shutdownNow();
            assertTrue(outer.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void submissionRejectionStillConfirmsNoHostWorkEntered() throws Exception {
        ProductManagedWorkTracker tracker = new ProductManagedWorkTracker();
        ToolContext context = context(tracker);
        PipelineTool tool = new PipelineTool(mapper, task -> {
            throw new java.util.concurrent.RejectedExecutionException("fixture rejection");
        }, (r, d, i, p, c) -> { fail("host invoked"); return null; });
        create(tool, context, definition());
        assertTrue(tool.execute(run(), context).isError());
        assertTrue(tracker.cancelAndAwait(Duration.ofSeconds(2)));
    }

    @Test void resolvedTensorDefinitionIsDeniedBeforeAnySubmission() throws Exception {
        ProductManagedWorkTracker tracker = new ProductManagedWorkTracker();
        ToolContext context = context(tracker);
        AtomicBoolean submitted = new AtomicBoolean();
        Executor forbidden = task -> submitted.set(true);
        PipelineTool tool = new PipelineTool(mapper, forbidden, (r, d, i, p, c) -> { fail("host invoked"); return null; });
        ObjectNode tensor = mapper.createObjectNode().put("pipelineId", "host-chat")
                .put("kind", "GENERIC").put("topology", "SEQUENCE");
        tensor.putObject("pipelineSpec").put("@class", "ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline")
                .put("id", "host-chat").putArray("steps");
        create(tool, context, tensor);
        ToolResult refused = tool.execute(run(), context);
        assertTrue(refused.isError());
        assertTrue(refused.getOutput().contains("only in-host CHAT_MODEL"), refused.getOutput());
        assertFalse(submitted.get());
        assertTrue(tracker.cancelAndAwait(Duration.ZERO));
    }

    @Test void unregisteredFileAndMediaInputsAreDeniedBeforeSubmission() throws Exception {
        ProductManagedWorkTracker tracker = new ProductManagedWorkTracker();
        ToolContext context = context(tracker);
        AtomicBoolean submitted = new AtomicBoolean();
        PipelineTool tool = new PipelineTool(mapper, task -> submitted.set(true), (r, d, i, p, c) -> {
            fail("host invoked"); return null;
        });
        create(tool, context, definition());
        for (String key : new String[]{"filePath", "path"}) {
            ObjectNode request = run();
            request.putObject("input").put(key, "source.pdf");
            ToolResult refused = tool.execute(request, context);
            assertTrue(refused.isError());
            assertTrue(refused.getOutput().contains("operation=pdf"), refused.getOutput());
        }
        assertFalse(submitted.get());
        assertTrue(tracker.cancelAndAwait(Duration.ZERO));
    }

    private ObjectNode composedDefinition() {
        ObjectNode composed = mapper.createObjectNode().put("pipelineId", "host-chat")
                .put("kind", "LLM").put("topology", "SEQUENCE");
        var steps = composed.putObject("pipelineSpec").put("@class",
                ai.kompile.pipeline.serving.definition.ChatPipelineComposition.SEQUENCE).putArray("steps");
        for (int i = 0; i < 2; i++) {
            ObjectNode step = steps.addObject().put("@class",
                    ai.kompile.pipeline.serving.definition.ChatPipelineComposition.STEP).put("runnerClassName", "CHAT_MODEL");
            step.putObject("parameters").putObject("processor").put("type", "CHAT_MODEL")
                    .put("provider", "custom").put("modelId", "fixture-stage-" + i);
        }
        return composed;
    }

    private ObjectNode pdfDefinition(String key) {
        ObjectNode definition = definition().put("pipelineId", "pdf-" + key);
        definition.withObject("/processor").put("operation", "pdf").put("pdfRenderDpi", 72);
        definition.putObject("inputs").putObject(key).put("type", "string").put("required", true);
        return definition;
    }

    private Path registeredPdf() throws Exception {
        return registeredPdf(1);
    }

    private Path registeredPdf(int pages) throws Exception {
        Path pdf = root.resolve("data/content/sources/fixture.pdf");
        Files.createDirectories(pdf.getParent());
        try (PDDocument document = new PDDocument()) {
            for (int page = 0; page < pages; page++) document.addPage(new PDPage());
            document.save(pdf.toFile());
        }
        return pdf;
    }

    private HttpServer fakeHttp(List<JsonNode> requests) throws IOException {
        return fakeHttp(requests, request -> "fixture answer");
    }

    private HttpServer fakeHttp(List<JsonNode> requests, Function<JsonNode, String> reply) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            requests.add(request);
            String text;
            try { text = reply.apply(request); }
            catch (RuntimeException unexpectedRequest) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return; // Exhausted fixtures fail promptly rather than leaving a 30-minute HTTP read.
            }
            byte[] response = ("data: " + mapper.writeValueAsString(Map.of("choices", List.of(
                    Map.of("delta", Map.of("content", text), "finish_reason", "stop"))))
                    + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        return server;
    }

    @Test void registeredPdfJarUsesRealPdfBoxAndHttpWithEitherFileSelector() throws Exception {
        assertFalse(NativeImageInfo.isRunningInNativeImage(), "JAR-only qualification fixture");
        Path pdf = registeredPdf();
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = fakeHttp(requests);
        ExecutorService executor = Executors.newCachedThreadPool();
        ProductManagedWorkTracker tracker = new ProductManagedWorkTracker();
        try {
            new ChatConfig("custom", null, "fixture-vision",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1").saveProject(root);
            PipelineTool tool = new PipelineTool(mapper, executor,
                    ChatModelPipelineRunner::executeDefinition, ChatModelPipelineRunner::executeProductDefinition);
            ToolContext context = context(tracker);
            for (String key : List.of("filePath", "path")) {
                ObjectNode definition = pdfDefinition(key);
                create(tool, context, definition);
                ObjectNode request = run().put("pipelineId", definition.path("pipelineId").asText()).put("wait", true);
                request.putObject("input").put(key, root.relativize(pdf).toString());
                JsonNode result = success(tool, request, context);
                assertEquals("COMPLETED", result.path("status").asText(), result.toString());
                assertEquals("fixture answer", result.path("output").path("text").asText());
                assertTrue(tracker.owns("pipeline", result.path("runId").asText()));
            }
            Path ordinaryFile = Files.copy(pdf, root.resolve("ordinary.pdf"));
            UnifiedPipelineDefinition ordinary = mapper.treeToValue(pdfDefinition("filePath"), UnifiedPipelineDefinition.class);
            assertEquals("fixture answer", ChatModelPipelineRunner.executeDefinition(root, ordinary,
                    Map.of("filePath", ordinaryFile.toString()), null, null));
            assertThrows(IOException.class, () -> ChatModelPipelineRunner.executeProductDefinition(root, ordinary,
                    Map.of("filePath", ordinaryFile.toString()), null, null));
            assertEquals(3, requests.size(), "ordinary PDF entry retains unrestricted source behavior");
            for (JsonNode request : requests) {
                JsonNode image = request.findValue("image_url");
                assertNotNull(image, "Real PDF pages, not substituted extracted text, must reach HTTP");
                assertTrue(image.path("url").asText().startsWith("data:image/png;base64,iVBOR"));
            }
            assertTrue(tracker.cancelAndAwait(Duration.ofSeconds(2)));
        } finally {
            tracker.cancelAndAwait(Duration.ofSeconds(2));
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            server.stop(0);
        }
    }

    private void configureHttp(HttpServer server) throws Exception {
        new ChatConfig("custom", null, "fixture-vision",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1").saveProject(root);
    }

    private UnifiedPipelineDefinition captureDefinition(String range, int batchSize, int maxChars) throws Exception {
        ObjectNode definition = pdfDefinition("filePath");
        definition.withObject("/processor").put("outputFormat", "json").put("pageRange", range)
                .put("pageBatchSize", batchSize).put("maxResponseChars", maxChars);
        return mapper.treeToValue(definition, UnifiedPipelineDefinition.class);
    }

    private String captureCandidate(int page, String title) throws Exception {
        // These source number lexemes must survive, not roundtrip through double/JsonNode coercions.
        return "{\"schemaVersion\":1,\"kind\":\"rule_text\",\"title\":" + mapper.writeValueAsString(title)
                + ",\"data\":{\"large\":1234567890123456789012345678901234567890,"
                + "\"decimal\":0.123456789012345678901234567890123456789,\"exponent\":1.2300e+123,"
                + "\"literal\":{\"unknown\":[null,false,0,\"-\",\"\"]}},\"evidence\":[{\"page1based\":" + page
                + ",\"region\":{\"x\":0.1,\"y\":0.2,\"width\":0.3,\"height\":0.4},\"quote\":\"Physical page " + page + "\"}]}";
    }

    private String captureEnvelope(String... candidates) {
        return "{\"schemaVersion\":1,\"candidates\":[" + String.join(",", candidates) + "]}";
    }

    /** Exact transport contract, without introducing an Elthoria dependency/POM change here. */
    private JsonNode strictCapture(String raw) throws Exception {
        ObjectMapper strict = new ObjectMapper(JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.USE_BIG_INTEGER_FOR_INTS, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        JsonNode envelope = strict.readTree(raw);
        assertTrue(envelope.isObject());
        assertEquals(2, envelope.size());
        assertTrue(envelope.path("schemaVersion").isIntegralNumber());
        assertEquals(1, envelope.path("schemaVersion").intValue());
        JsonNode candidates = envelope.path("candidates");
        assertTrue(candidates.isArray());
        assertTrue(candidates.size() >= 1 && candidates.size() <= 100);
        for (JsonNode candidate : candidates) {
            assertTrue(candidate.isObject());
            assertEquals(5, candidate.size());
            assertTrue(candidate.path("schemaVersion").isIntegralNumber());
            assertEquals(1, candidate.path("schemaVersion").intValue());
            assertEquals("rule_text", candidate.path("kind").textValue());
            assertTrue(candidate.path("title").isTextual());
            assertTrue(candidate.path("data").isObject());
            assertTrue(candidate.path("evidence").isArray());
            assertFalse(candidate.path("evidence").isEmpty());
        }
        assertFalse(raw.contains("## Pages"));
        return envelope;
    }

    @Test void productPdfCaptureSingleSelectedPhysicalPageIsOneStrictEnvelope() throws Exception {
        Path pdf = registeredPdf(2);
        String candidate = captureCandidate(2, "Second physical page");
        String reply = captureEnvelope(candidate);
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = fakeHttp(requests, request -> reply);
        ExecutorService executor = Executors.newCachedThreadPool();
        ProductManagedWorkTracker tracker = new ProductManagedWorkTracker();
        try {
            configureHttp(server);
            PipelineTool tool = new PipelineTool(mapper, executor,
                    ChatModelPipelineRunner::executeDefinition, ChatModelPipelineRunner::executeProductDefinition);
            ToolContext context = context(tracker);
            create(tool, context, mapper.valueToTree(captureDefinition("2", 1, 10_000)));
            ObjectNode request = run().put("pipelineId", "pdf-filePath").put("wait", true);
            request.putObject("input").put("filePath", pdf.toString());
            JsonNode result = success(tool, request, context);
            assertEquals("COMPLETED", result.path("status").asText(), result.toString());
            String raw = result.path("output").path("text").asText();
            assertEquals(reply, raw, "One selected page in a multi-page PDF is still one raw envelope");
            assertEquals(2, strictCapture(raw).at("/candidates/0/evidence/0/page1based").intValue());
            assertEquals(1, requests.size());
            assertTrue(requests.get(0).toString().contains("original PDF page(s) 2 of 2"));
            assertTrue(requests.get(0).findValue("image_url").path("url").asText().startsWith("data:image/png;base64,iVBOR"));
            assertTrue(tracker.cancelAndAwait(Duration.ofSeconds(2)));
        } finally {
            tracker.cancelAndAwait(Duration.ofSeconds(2));
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            server.stop(0);
        }
    }

    @Test void productPdfCaptureBatchesMergeVerbatimIncludingNumbersAndPhysicalEvidence() throws Exception {
        Path pdf = registeredPdf(2);
        String first = captureCandidate(1, "First"), second = captureCandidate(2, "Second");
        String expected = captureEnvelope(first, second);
        for (int batchSize : List.of(1, 2)) {
            var replies = new ConcurrentLinkedQueue<>(batchSize == 1
                    ? List.of(captureEnvelope(first), captureEnvelope(second)) : List.of(expected));
            List<JsonNode> requests = new CopyOnWriteArrayList<>();
            HttpServer server = fakeHttp(requests, request -> replies.remove());
            try {
                configureHttp(server);
                List<String> chunks = new CopyOnWriteArrayList<>();
                String raw = ChatModelPipelineRunner.executeProductDefinition(root, captureDefinition("1-2", batchSize, 10_000),
                        Map.of("filePath", pdf.toString()), null, chunks::add);
                assertEquals(expected, raw, "No candidate, nested literal, number lexeme or evidence may be rewritten");
                JsonNode capture = strictCapture(raw);
                assertEquals(2, capture.path("candidates").size());
                assertEquals(1, capture.at("/candidates/0/evidence/0/page1based").intValue());
                assertEquals(2, capture.at("/candidates/1/evidence/0/page1based").intValue());
                assertEquals(List.of(raw), chunks, "Only a complete validated envelope is observable as captured output");
                assertEquals(batchSize == 1 ? 2 : 1, requests.size());
                for (JsonNode request : requests) assertEquals(batchSize, request.findValues("image_url").size());
                assertTrue(replies.isEmpty());
            } finally { server.stop(0); }
        }
    }

    @Test void productPdfCaptureRejectsFencesFragmentsAndMalformedEnvelopesWithoutPartialOutput() throws Exception {
        Path pdf = registeredPdf(2);
        String valid = captureEnvelope(captureCandidate(2, "Valid"));
        List<String> invalid = List.of("```json\n" + valid + "\n```", valid + valid,
                valid.substring(0, valid.length() - 1), valid.replace("\"schemaVersion\":1,\"candidates\"", "\"schemaVersion\":\"1\",\"candidates\""),
                valid.replaceFirst("schemaVersion\":1", "schemaVersion\":1.0"),
                "{\"schemaVersion\":2,\"candidates\":[{}]}", "{\"candidates\":[{}]}",
                "{\"schemaVersion\":1,\"candidates\":{}}", "{\"schemaVersion\":1,\"candidates\":[null]}",
                "{\"schemaVersion\":1,\"candidates\":[42]}", "{\"schemaVersion\":1,\"candidates\":[]}",
                "{\"schemaVersion\":1,\"candidates\":[{}],\"extra\":1}",
                "{\"schemaVersion\":1,\"schemaVersion\":1,\"candidates\":[{}]}",
                "{\"schemaVersion\":1,\"candidates\":[{\"data\":{\"x\":1,\"x\":2}}]}");
        var replies = new ConcurrentLinkedQueue<>(invalid);
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = fakeHttp(requests, request -> replies.remove());
        try {
            configureHttp(server);
            for (String ignored : invalid) {
                List<String> chunks = new CopyOnWriteArrayList<>();
                assertThrows(IOException.class, () -> ChatModelPipelineRunner.executeProductDefinition(root,
                        captureDefinition("2", 1, 10_000), Map.of("filePath", pdf.toString()), null, chunks::add));
                assertTrue(chunks.isEmpty(), "Malformed capture must not leak as successful output");
            }
            assertEquals(invalid.size(), requests.size());
        } finally { server.stop(0); }
    }

    @Test void productPdfCaptureEnforcesGlobalCharacterAndCandidateBoundsWithoutTruncation() throws Exception {
        Path pdf = registeredPdf(2);
        String candidate = captureCandidate(1, "x".repeat(1200));
        String reply = captureEnvelope(candidate);
        int limit = reply.length();
        assertTrue(limit > 1000, "Exercise an approved response budget above the definition's 1000-character minimum");
        String many = captureEnvelope(java.util.Collections.nCopies(51, captureCandidate(2, "many")).toArray(String[]::new));
        var replies = new ConcurrentLinkedQueue<>(List.of(reply, reply, reply, reply, many, many));
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = fakeHttp(requests, request -> replies.remove());
        try {
            configureHttp(server);
            assertEquals(reply, ChatModelPipelineRunner.executeProductDefinition(root, captureDefinition("1", 1, limit),
                    Map.of("filePath", pdf.toString()), null, null));
            List<String> chunks = new CopyOnWriteArrayList<>();
            IOException oversizedBatch = assertThrows(IOException.class, () -> ChatModelPipelineRunner.executeProductDefinition(root,
                    captureDefinition("1", 1, limit - 1), Map.of("filePath", pdf.toString()), null, chunks::add));
            assertTrue(oversizedBatch.getMessage().contains("maxResponseChars"), oversizedBatch.toString());
            IOException tooLong = assertThrows(IOException.class, () -> ChatModelPipelineRunner.executeProductDefinition(root,
                    captureDefinition("1-2", 1, limit), Map.of("filePath", pdf.toString()), null, chunks::add));
            assertTrue(tooLong.getMessage().contains("maxResponseChars"), tooLong.toString());
            IOException tooMany = assertThrows(IOException.class, () -> ChatModelPipelineRunner.executeProductDefinition(root,
                    captureDefinition("1-2", 1, 100_000), Map.of("filePath", pdf.toString()), null, chunks::add));
            assertTrue(tooMany.getMessage().contains("1..100"), tooMany.toString());
            assertTrue(chunks.isEmpty());
            assertTrue(replies.isEmpty());
            assertEquals(6, requests.size(), "Failure must not retry or request any alternate model");
        } finally { server.stop(0); }
    }

    @Test void productPdfCaptureAcceptsExactly100CandidatesButRejects101InOneBatch() throws Exception {
        Path pdf = registeredPdf(2);
        String candidate = captureCandidate(2, "Repeated candidates must not be deduplicated");
        String hundred = captureEnvelope(java.util.Collections.nCopies(100, candidate).toArray(String[]::new));
        String hundredOne = captureEnvelope(java.util.Collections.nCopies(101, candidate).toArray(String[]::new));
        var replies = new ConcurrentLinkedQueue<>(List.of(hundred, hundredOne));
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = fakeHttp(requests, request -> replies.remove());
        try {
            configureHttp(server);
            String raw = ChatModelPipelineRunner.executeProductDefinition(root, captureDefinition("2", 1, 100_000),
                    Map.of("filePath", pdf.toString()), null, null);
            assertEquals(hundred, raw);
            assertEquals(100, strictCapture(raw).path("candidates").size());
            IOException tooMany = assertThrows(IOException.class, () -> ChatModelPipelineRunner.executeProductDefinition(root,
                    captureDefinition("2", 1, 100_000), Map.of("filePath", pdf.toString()), null, null));
            assertTrue(tooMany.getMessage().contains("1..100"), tooMany.toString());
            assertEquals(2, requests.size());
        } finally { server.stop(0); }
    }

    @Test void productPdfCaptureRejectsMalformedLaterBatchWithoutPublishingEarlierCandidates() throws Exception {
        Path pdf = registeredPdf(2);
        var replies = new ConcurrentLinkedQueue<>(List.of(captureEnvelope(captureCandidate(1, "First")),
                "```json\n" + captureEnvelope(captureCandidate(2, "Second")) + "\n```"));
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = fakeHttp(requests, request -> replies.remove());
        try {
            configureHttp(server);
            List<String> chunks = new CopyOnWriteArrayList<>();
            assertThrows(IOException.class, () -> ChatModelPipelineRunner.executeProductDefinition(root,
                    captureDefinition("1-2", 1, 10_000), Map.of("filePath", pdf.toString()), null, chunks::add));
            assertTrue(chunks.isEmpty(), "A successful first batch does not authorize partial capture output");
            assertEquals(2, requests.size());
        } finally { server.stop(0); }
    }

    @Test void ordinaryPdfAndProductTextFormattingRemainUnchangedEvenWithJsonOptionOutsideProduct() throws Exception {
        Path pdf = registeredPdf(2);
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = fakeHttp(requests, request -> "line one\nline two");
        try {
            configureHttp(server);
            UnifiedPipelineDefinition ordinary = captureDefinition("1-2", 1, 10_000);
            String expected = "## Pages 1\n\nline one\nline two\n\n## Pages 2\n\nline one\nline two";
            assertEquals(expected, ChatModelPipelineRunner.executeDefinition(root, ordinary,
                    Map.of("filePath", pdf.toString()), null, null));
            ordinary.getProcessor().put("outputFormat", "Markdown");
            assertEquals(expected, ChatModelPipelineRunner.executeProductDefinition(root, ordinary,
                    Map.of("filePath", pdf.toString()), null, null));
            assertEquals(4, requests.size());
        } finally { server.stop(0); }
    }

    @Test void productPdfSourceValidationRejectsWrongTypeOutsideSymlinksAndAmbiguousInputsBeforeSubmission() throws Exception {
        Path pdf = registeredPdf();
        Path outside = Files.copy(pdf, root.resolve("outside.pdf"));
        Path badSignature = pdf.resolveSibling("not-pdf.pdf");
        Files.writeString(badSignature, "not a PDF");
        Path wrongType = Files.copy(pdf, pdf.resolveSibling("image.png"));
        Path symlink = Files.createSymbolicLink(pdf.resolveSibling("linked.pdf"), pdf);
        Path directoryLink = Files.createSymbolicLink(root.resolve("data/content/sources/linked-dir"), pdf.getParent());
        Path folder = Files.createDirectory(pdf.resolveSibling("folder.pdf"));
        ProductManagedWorkTracker tracker = new ProductManagedWorkTracker();
        ToolContext context = context(tracker);
        AtomicBoolean submitted = new AtomicBoolean();
        PipelineTool tool = new PipelineTool(mapper, task -> submitted.set(true), (r, d, i, p, c) -> {
            fail("source validation must not invoke any model"); return null;
        });
        ObjectNode definition = pdfDefinition("filePath");
        create(tool, context, definition);
        for (Path invalid : List.of(outside, badSignature, wrongType, symlink, directoryLink.resolve("fixture.pdf"),
                folder, pdf.resolveSibling("missing.pdf"))) {
            ObjectNode request = run().put("pipelineId", "pdf-filePath");
            request.putObject("input").put("filePath", invalid.toString());
            assertTrue(tool.execute(request, context).isError(), invalid.toString());
        }
        for (ObjectNode invalid : List.of(mapper.createObjectNode().put("filePath", pdf.toString()).put("path", pdf.toString()),
                mapper.createObjectNode().put("filePath", pdf.toString()).put("text", "ambiguous"),
                mapper.createObjectNode().put("filePath", pdf.toString()).put("image", "unsupported"),
                mapper.createObjectNode().put("filePath", 42), mapper.createObjectNode().put("image", pdf.toString()))) {
            ObjectNode request = run().put("pipelineId", "pdf-filePath");
            request.set("input", invalid);
            assertTrue(tool.execute(request, context).isError(), invalid.toString());
        }
        assertFalse(submitted.get());
        assertTrue(tracker.cancelAndAwait(Duration.ZERO));
    }

    @Test void productPdfValidationIsModelFreeAndEnforcesTheExact64MiBLimit() throws Exception {
        Path pdf = registeredPdf();
        // Deliberately invalid chat configuration: validating source arguments must not resolve a model/auth route.
        Path config = ChatConfig.projectConfigPath(root);
        Files.createDirectories(config.getParent());
        Files.writeString(config, "not chat JSON");
        UnifiedPipelineDefinition definition = mapper.treeToValue(pdfDefinition("filePath"), UnifiedPipelineDefinition.class);
        try (var file = Files.newByteChannel(pdf, StandardOpenOption.WRITE)) {
            file.position(64L * 1024 * 1024 - 1).write(ByteBuffer.wrap(new byte[]{0}));
        }
        Map<String, Object> input = Map.of("filePath", pdf.toString());
        assertDoesNotThrow(() -> ChatModelPipelineRunner.requireProductInputs(root, definition, input));
        try (var file = Files.newByteChannel(pdf, StandardOpenOption.WRITE)) {
            file.position(64L * 1024 * 1024).write(ByteBuffer.wrap(new byte[]{0}));
        }
        IOException failure = assertThrows(IOException.class,
                () -> ChatModelPipelineRunner.requireProductInputs(root, definition, input));
        assertTrue(failure.getMessage().contains("64 MiB"));
    }

    @Test void productPdfRejectsSimulatedNativeImageBeforeSubmissionOrAnyHelper() throws Exception {
        Path pdf = registeredPdf();
        ProductManagedWorkTracker tracker = new ProductManagedWorkTracker();
        ToolContext context = context(tracker);
        AtomicBoolean submitted = new AtomicBoolean();
        PipelineTool tool = new PipelineTool(mapper, task -> submitted.set(true), (r, d, i, p, c) -> {
            fail("native PDF host/helper invoked"); return null;
        });
        create(tool, context, pdfDefinition("filePath"));
        ObjectNode request = run().put("pipelineId", "pdf-filePath");
        request.putObject("input").put("filePath", pdf.toString());
        String property = "org.graalvm.nativeimage.imagecode";
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, "runtime");
            assertTrue(NativeImageInfo.isRunningInNativeImage());
            ToolResult failure = tool.execute(request, context);
            assertTrue(failure.isError());
            assertTrue(failure.getOutput().contains("native-image PDF helpers are denied"), failure.getOutput());
            assertFalse(submitted.get());
            UnifiedPipelineDefinition definition = mapper.treeToValue(pdfDefinition("filePath"), UnifiedPipelineDefinition.class);
            IOException denied = assertThrows(IOException.class, () -> ChatModelPipelineRunner.executeProductDefinition(
                    root, definition, Map.of("filePath", pdf.toString()), null, null));
            assertTrue(denied.getMessage().contains("native-image PDF helpers are denied"));
        } finally {
            if (previous == null) System.clearProperty(property); else System.setProperty(property, previous);
        }
        assertTrue(tracker.cancelAndAwait(Duration.ZERO));
    }

    @Test void actualExecutionSelectionRejectsAuthSwitchAfterPipelineToolPreview() throws Exception {
        ChatConfig http = new ChatConfig("anthropic", null, "fixture", null);
        http.setAuthenticationMethod("api-key");
        http.saveProject(root);
        ExecutorService executor = Executors.newCachedThreadPool();
        ProductManagedWorkTracker tracker = new ProductManagedWorkTracker();
        AtomicBoolean guardedEntryReached = new AtomicBoolean();
        try {
            PipelineTool tool = new PipelineTool(mapper, executor, (r, d, i, p, c) -> {
                fail("ordinary executor used for product"); return null;
            }, (r, d, i, p, c) -> {
                // requireInHostChatRoutes has accepted HTTP. Reproduce the operator auth-switch gap.
                ChatConfig cli = new ChatConfig("anthropic", null, "fixture", null);
                cli.setAuthenticationMethod("native");
                cli.saveProject(root);
                guardedEntryReached.set(true);
                return ChatModelPipelineRunner.executeProductDefinition(r, d, i, p, c);
            });
            ToolContext context = context(tracker);
            ObjectNode definition = definition();
            definition.withObject("/processor").put("provider", "anthropic");
            create(tool, context, definition);
            JsonNode result = success(tool, run().put("wait", true), context);
            assertTrue(guardedEntryReached.get());
            assertEquals("FAILED", result.path("status").asText());
            assertTrue(result.path("error").asText().contains("CLAUDE_CLI"), result.toString());
            assertTrue(result.path("error").asText().contains("in-host HTTP"), result.toString());
            assertFalse(result.has("output"));
            assertTrue(tracker.cancelAndAwait(Duration.ofSeconds(2)));
        } finally {
            tracker.cancelAndAwait(Duration.ofSeconds(2));
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void composedTextUsesHttpAndRechecksTheActualSelectionAtEveryStage() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = fakeHttp(requests);
        try {
            ChatConfig http = new ChatConfig("custom", null, "fixture",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            http.saveProject(root);
            ObjectNode composed = composedDefinition();
            // Inherit the active provider so an operator auth switch can affect a later resolution.
            for (int i = 0; i < 2; i++) ((ObjectNode) composed.at("/pipelineSpec/steps/" + i + "/parameters/processor"))
                    .remove("provider");
            UnifiedPipelineDefinition definition = mapper.treeToValue(composed, UnifiedPipelineDefinition.class);
            assertEquals("fixture answer", ChatModelPipelineRunner.executeProductDefinition(root, definition,
                    Map.of("text", "fixture source"), null, null));
            assertEquals(2, requests.size());
            requests.clear();
            AtomicBoolean switched = new AtomicBoolean();
            IOException denied = assertThrows(IOException.class, () -> ChatModelPipelineRunner.executeProductDefinition(
                    root, definition, Map.of("text", "fixture source"), event -> {
                        if (Integer.valueOf(100).equals(event.get("progressPercent")) && switched.compareAndSet(false, true)) {
                            ChatConfig cli = new ChatConfig("anthropic", null, "fixture", null);
                            cli.setAuthenticationMethod("native");
                            try { cli.saveProject(root); }
                            catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                        }
                    }, null));
            assertTrue(switched.get());
            assertTrue(denied.getMessage().contains("in-host HTTP"), denied.getMessage());
            assertTrue(denied.getMessage().contains("CLAUDE_CLI"), denied.getMessage());
            assertEquals(1, requests.size(), "later stage must stop before any request or CLI generation");
            assertFalse(NativeChatModels.hasActiveRequest(Thread.currentThread()));
        } finally {
            server.stop(0);
        }
    }

    @Test void nullTrackerRetainsOrdinaryInlineTestExecution() throws Exception {
        ToolContext ordinary = context(null);
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            PipelineTool tool = new PipelineTool(mapper, executor, (r, d, i, p, c) -> "ordinary fake host");
            ObjectNode request = mapper.createObjectNode().put("action", "test");
            request.set("definition", definition());
            request.putObject("input").put("text", "ordinary source");
            assertEquals("COMPLETED", success(tool, request, ordinary).path("status").asText());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void anthropicNativeAuthCannotReachCliBackedHostRunner() throws Exception {
        Path config = ChatConfig.projectConfigPath(root);
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{\"provider\":\"anthropic\",\"authenticationMethod\":\"native\",\"model\":\"fixture\"}");
        ProductManagedWorkTracker tracker = new ProductManagedWorkTracker();
        ToolContext context = context(tracker);
        AtomicBoolean invoked = new AtomicBoolean();
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            PipelineTool tool = new PipelineTool(mapper, executor, (r, d, i, p, c) -> {
                invoked.set(true); return "must not execute";
            });
            ObjectNode definition = definition();
            definition.withObject("/processor").put("provider", "anthropic");
            create(tool, context, definition);
            JsonNode failed = success(tool, run().put("wait", true), context);
            assertEquals("FAILED", failed.path("status").asText());
            assertTrue(failed.path("error").asText().contains("CLAUDE_CLI"), failed.toString());
            assertFalse(invoked.get());
            assertTrue(tracker.cancelAndAwait(Duration.ofSeconds(2)));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
