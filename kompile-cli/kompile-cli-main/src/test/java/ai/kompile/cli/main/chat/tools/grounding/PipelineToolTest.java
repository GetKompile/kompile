package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.project.LocalProjectModelBootstrap;
import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PipelineToolTest {
    @TempDir
    Path projectRoot;

    private ObjectMapper mapper;
    private ToolContext context;
    private PipelineTool tool;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        PermissionService permissions = new PermissionService();
        permissions.setUserOverride("pipeline", PermissionService.PermissionLevel.ALLOW);
        context = new ToolContext("pipeline-agent", AgentConfig.builder("pipeline-agent")
                .enabledTools(Set.of("pipeline")).build(), permissions, projectRoot,
                new ToolRegistry(mapper));
        tool = new PipelineTool(mapper);
    }

    @Test
    void agentCanCreateVersionPromoteAndRollback() throws Exception {
        ToolResult created = tool.execute(request("create", definition("first"))
                .put("expectedActiveVersion", 0), context);
        assertFalse(created.isError(), created.getOutput());
        assertEquals(1L, mapper.readTree(created.getOutput()).path("definitionVersion").asLong());

        ToolResult updated = tool.execute(request("update", definition("second"))
                .put("expectedActiveVersion", 1), context);
        assertFalse(updated.isError(), updated.getOutput());
        assertEquals(2L, mapper.readTree(updated.getOutput()).path("definitionVersion").asLong());

        ObjectNode promote = mapper.createObjectNode().put("action", "promote")
                .put("pipelineId", "agent-pipeline").put("version", 2)
                .put("expectedActiveVersion", 1);
        assertFalse(tool.execute(promote, context).isError());

        ObjectNode get = mapper.createObjectNode().put("action", "get")
                .put("pipelineId", "agent-pipeline");
        assertEquals("second", mapper.readTree(tool.execute(get, context).getOutput())
                .path("description").asText());

        ObjectNode rollback = mapper.createObjectNode().put("action", "rollback")
                .put("pipelineId", "agent-pipeline").put("version", 1)
                .put("expectedActiveVersion", 2);
        assertFalse(tool.execute(rollback, context).isError());
        assertEquals("first", mapper.readTree(tool.execute(get, context).getOutput())
                .path("description").asText());
    }

    @Test
    void lifecycleAndCrawlDiscoveryShareProjectManifestRegistrations() throws Exception {
        Files.writeString(projectRoot.resolve("registered-definition.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        definition("project registered")), StandardCharsets.UTF_8);
        Files.writeString(projectRoot.resolve("kompile.project.json"), """
                {
                  "schemaVersion": 1,
                  "projectId": "pipeline-project",
                  "name": "Pipeline project",
                  "pipelines": [{
                    "id": "discovered-pipeline",
                    "pipelineId": "discovered-pipeline",
                    "name": "Discovered pipeline",
                    "definitionPath": "registered-definition.json",
                    "active": true,
                    "metadata": {"pipelineType": "CUSTOM"}
                  }]
                }
                """, StandardCharsets.UTF_8);

        ToolResult listed = tool.execute(
                mapper.createObjectNode().put("action", "list"), context);
        ToolResult fetched = tool.execute(mapper.createObjectNode()
                .put("action", "get").put("pipelineId", "discovered-pipeline"), context);
        ToolResult discovered = new LocalProjectCrawlBackend(mapper)
                .discover("pipelines", projectRoot);

        assertFalse(listed.isError(), listed.getOutput());
        assertTrue(listed.getOutput().contains("discovered-pipeline"), listed.getOutput());
        assertTrue(listed.getOutput().contains("kompile.project.json"), listed.getOutput());
        assertFalse(fetched.isError(), fetched.getOutput());
        assertEquals("kompile.project.json",
                mapper.readTree(fetched.getOutput()).path("registrySource").asText());
        assertFalse(discovered.isError(), discovered.getOutput());
        assertTrue(discovered.getOutput().contains("discovered-pipeline"), discovered.getOutput());
    }

    @Test
    void projectModelRefsAndRequestBindingsReachThePreparedRuntimeDefinition() throws Exception {
        Path modelDirectory = Files.createDirectories(
                projectRoot.resolve("data/models/vlm-pipelines/project-vlm"));
        Files.writeString(modelDirectory.resolve("tokenizer.json"), "{}");

        UnifiedPipelineDefinition definition = UnifiedPipelineDefinition.builder()
                .pipelineId("project-vlm-pipeline")
                .kind(UnifiedPipelineDefinition.PipelineKind.VLM)
                .modelDefinitions(Map.of("project-vlm", Map.of(
                        "id", "project-vlm",
                        "modelId", "project-vlm",
                        "localPath", modelDirectory.toString(),
                        "type", "vlm_pipeline")))
                .build();
        ObjectNode registration = mapper.createObjectNode();
        registration.putObject("options").putArray("modelRefs").add("project-vlm");

        UnifiedPipelineDefinition fromProjectRef = tool.prepare(
                projectRoot, definition, Map.of(), Map.of(), registration, false);
        assertEquals("project-vlm", fromProjectRef.getModelBindings().get("default"));
        assertEquals(modelDirectory.toAbsolutePath().normalize().toString(),
                fromProjectRef.getResolvedModels().get("default").get("modelPath"));

        UnifiedPipelineDefinition fromRequestBinding = tool.prepare(
                projectRoot, definition,
                Map.of("modelBindings", Map.of("vision", "project-vlm")),
                Map.of(), registration, false);
        assertEquals("project-vlm", fromRequestBinding.getModelBindings().get("vision"));
        assertEquals(modelDirectory.toAbsolutePath().normalize().toString(),
                fromRequestBinding.getResolvedModels().get("vision").get("modelPath"));
    }

    @Test
    void inlineTestReportsStructuredReadOnlyModelFailureWithoutCreatingProjectMetadata() throws Exception {
        ObjectNode definition = (ObjectNode) definition("read-only test");
        definition.putObject("modelBindings").put("default", "missing-vlm");

        ToolResult result = tool.execute(request("test", definition), context);

        assertTrue(result.isError(), result.getOutput());
        JsonNode output = mapper.readTree(result.getOutput());
        assertEquals("test", output.path("action").asText());
        assertEquals("FAILED", output.path("status").asText());
        assertTrue(output.path("diagnostic").path("summary").asText()
                        .toLowerCase().contains("read-only"),
                result.getOutput());
        assertTrue(output.path("diagnostic").has("exceptionChain"), result.getOutput());
        assertEquals("agent-pipeline", output.path("diagnostic")
                .path("requestedDefinition").path("pipelineId").asText());
        assertFalse(Files.exists(projectRoot.resolve("kompile.project.json")));
    }

    @Test
    void modelInventorySeparatesArtifactPresenceFromUnprobedRuntimeHealth() throws Exception {
        Path modelDirectory = Files.createDirectories(projectRoot.resolve("data/models/tiny"));
        Files.write(modelDirectory.resolve("model.gguf"), new byte[]{1});
        Files.writeString(projectRoot.resolve("kompile.project.json"), """
                {
                  "schemaVersion": 1,
                  "projectId": "readiness-project",
                  "name": "Readiness project",
                  "models": [{
                    "id": "tiny",
                    "modelId": "tiny",
                    "role": "LLM",
                    "path": "data/models/tiny"
                  }]
                }
                """, StandardCharsets.UTF_8);

        List<Map<String, Object>> inventory =
                LocalProjectModelBootstrap.inventory(projectRoot);

        assertEquals(1, inventory.size());
        assertEquals(true, inventory.get(0).get("artifactReady"));
        assertEquals("AVAILABLE", inventory.get(0).get("artifactStatus"));
        assertEquals("NOT_PROBED", inventory.get(0).get("runtimeStatus"));
        assertTrue(String.valueOf(inventory.get(0).get("readyMeaning"))
                .contains("does not prove runtime initialization"));
    }

    @Test
    void capabilitiesExposeLocalCompositionAndStandaloneHostChat() throws Exception {
        ToolResult result = tool.execute(mapper.createObjectNode().put("action", "capabilities"), context);
        assertFalse(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("UNIFIED_PIPELINE"));
        assertTrue(result.getOutput().contains("callerManagedProcesses"));
        assertTrue(result.getOutput().contains("VLM_DOCUMENT"), result.getOutput());
        assertTrue(result.getOutput().contains("application/pdf"), result.getOutput());
        assertTrue(result.getOutput().contains("failFastOnPageError"), result.getOutput());
        assertTrue(tool.description().contains("terminal=true"));
        assertTrue(result.getOutput().contains("VISION_MULTIMODEL"), result.getOutput());
        assertTrue(result.getOutput().contains("vision_encoder.image_features"), result.getOutput());
        assertTrue(result.getOutput().contains("pipeline_input.input_ids"), result.getOutput());
        assertTrue(result.getOutput().contains("modelBindings.default"), result.getOutput());
        assertTrue(result.getOutput().contains("modelAcquisition"), result.getOutput());
        assertTrue(result.getOutput().contains("roleBindingWorkflow"), result.getOutput());
        assertTrue(result.getOutput().contains("graphWiring"), result.getOutput());
        assertTrue(result.getOutput().contains("inputDataBindings"), result.getOutput());
        assertTrue(result.getOutput().contains("multiple predecessors are merged"), result.getOutput());
        assertTrue(result.getOutput().contains("\"convert\""), result.getOutput());
        JsonNode schema = tool.parameterSchema();
        JsonNode pipelineSchema = schema.path("properties").path("definition").path("properties")
                .path("pipelineSpec");
        assertTrue(pipelineSchema.has("properties"));
        assertTrue(pipelineSchema.path("properties").path("inputNodeName").isObject());
        assertTrue(pipelineSchema.path("properties").path("nodes").path("items")
                .path("properties").path("stepConfig").path("properties")
                .path("parameters").path("properties").has("inputDataBindings"));
        assertTrue(schema.path("properties").path("input").path("properties")
                .has("filePath"));
        assertTrue(schema.path("properties").path("input").path("properties")
                .has("image"));
        JsonNode chatProcessor = schema.path("properties").path("definition").path("properties")
                .path("processor").path("properties");
        assertEquals("string", chatProcessor.path("pageRange").path("type").asText());
        assertTrue(schema.path("properties").path("modelRuntime").path("description").asText()
                .contains("model_runtime"));
        assertTrue(schema.path("properties").path("modelRuntime").path("properties")
                .has("servingExecutable"));
        assertTrue(schema.path("properties").path("modelRuntime").path("properties")
                .has("localPath"));
        assertTrue(schema.path("properties").path("modelRuntime").path("properties")
                .has("prefixCacheEnabled"));
        assertEquals(0, schema.path("properties").path("modelRuntime").path("properties")
                .path("prefixCacheMaxBytes").path("minimum").asInt());
        assertEquals(0, schema.path("properties").path("modelRuntime").path("properties")
                .path("prefixCacheBlockSize").path("minimum").asInt());
        assertFalse(schema.path("properties").path("modelRuntime").path("properties")
                .has("autoBootstrap"));
        assertFalse(schema.path("properties").path("modelRuntime").path("properties")
                .has("stagingExecutable"));
        assertFalse(schema.path("properties").path("modelRuntime").path("properties")
                .has("stagingJar"));
        assertTrue(result.getOutput().contains("modelSpecificPipelineRequired"));
        assertFalse(result.getOutput().contains("documentModelExecutable"));
        assertFalse(result.getOutput().contains("vlm-test"));
        assertFalse(result.getOutput().toLowerCase().contains("smoldocling"),
                "Capabilities must not select a provider-specific model: " + result.getOutput());
    }

    @Test
    void chatModelValidationAcceptsExplicitPageRangeAndMaxPagesAboveTwoHundred() throws Exception {
        ObjectNode definition = (ObjectNode) definition("bounded PDF selection");
        definition.put("kind", "LLM").remove("pipelineSpec");
        definition.putObject("processor")
                .put("type", "CHAT_MODEL")
                .put("pageRange", "7-9")
                .put("maxPages", 201);

        ToolResult result = tool.execute(request("validate", definition), context);

        assertFalse(result.isError(), result.getOutput());
        assertTrue(mapper.readTree(result.getOutput()).path("valid").asBoolean(), result.getOutput());
    }

    @Test
    void standaloneChatCanBeCreatedVersionedRunAndTestedWithInlineText() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = chatServer(requests, null, null);
        try {
            configureChat(server);
            ObjectNode definition = chatDefinition();
            ToolResult created = tool.execute(request("create", definition), context);
            assertFalse(created.isError(), created.getOutput());
            JsonNode first = mapper.readTree(created.getOutput());
            definition.withObject("/processor").put("prompt", "Updated extraction prompt");
            ToolResult updated = tool.execute(request("update", definition).put("expectedActiveVersion", 1), context);
            assertFalse(updated.isError(), updated.getOutput());
            assertNotEquals(first.path("contentDigest"), mapper.readTree(updated.getOutput()).path("contentDigest"));
            ToolResult fetched = tool.execute(mapper.createObjectNode().put("action", "get")
                    .put("pipelineId", "host-chat").put("version", 2), context);
            assertEquals("Updated extraction prompt", mapper.readTree(fetched.getOutput())
                    .path("processor").path("prompt").asText());
            assertFalse(mapper.readTree(fetched.getOutput()).has("pipelineSpec"));

            ObjectNode run = mapper.createObjectNode().put("action", "run").put("pipelineId", "host-chat")
                    .put("version", 2).put("wait", true);
            run.putObject("input").put("text", "Inline knowledge source");
            JsonNode executed = success(run);
            assertEquals("COMPLETED", executed.path("status").asText(), executed.toString());
            assertTrue(executed.path("terminal").asBoolean());
            assertEquals("host response", executed.path("output").path("text").asText());

            ObjectNode test = request("test", definition);
            test.putObject("input").put("text", "Another inline source");
            JsonNode tested = success(test);
            assertEquals("COMPLETED", tested.path("status").asText(), tested.toString());
            assertEquals("CHAT_MODEL", tested.path("output").path("execution").asText());
            assertFalse(tested.path("resolvedDefinition").has("resolvedModels"));
            assertFalse(tested.path("resolvedDefinition").has("pipelineSpec"));
            assertEquals(2, requests.size());
            assertTrue(requests.get(0).toString().contains("Inline knowledge source"));
            assertFalse(Files.exists(projectRoot.resolve("data/models")), "No local artifact resolution is needed");
            assertFalse(Files.exists(projectRoot.resolve("kompile.project.json")), "Host execution must not bootstrap a project");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void requestChatBindingsAndDefinitionsOverridePortableAndProjectDefaults() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = chatServer(requests, null, null);
        try {
            configureChat(server);
            ObjectNode definition = chatDefinition();
            definition.withObject("/processor").put("modelId", "legacy-model");
            definition.putObject("modelBindings").put("default", "portable");
            ObjectNode modelDefinitions = definition.putObject("modelDefinitions");
            modelDefinitions.putObject("portable").put("source", "chat").put("provider", "custom").put("modelId", "portable-model");
            modelDefinitions.putObject("requested").put("source", "chat").put("provider", "custom").put("modelId", "portable-default-for-requested");
            ObjectNode registration = mapper.createObjectNode();
            ObjectNode options = registration.putObject("options");
            options.putObject("modelBindings").put("default", "registered");
            options.putObject("modelDefinitions").putObject("requested").put("source", "chat")
                    .put("provider", "custom").put("modelId", "registered-default-for-requested");
            registration.putObject("processor").put("type", "CHAT_MODEL")
                    .putObject("modelBindings").put("default", "registered");
            Map<String, Object> input = Map.of("text", "bound source",
                    "modelBindings", Map.of("default", "requested"),
                    "modelDefinitions", Map.of("requested", Map.of("source", "chat", "provider", "custom", "modelId", "request-model")));
            UnifiedPipelineDefinition prepared = tool.prepare(projectRoot,
                    mapper.convertValue(definition, UnifiedPipelineDefinition.class), input, Map.of(), registration, false);
            assertEquals(Map.of("default", "requested"), prepared.getModelBindings());
            assertEquals("request-model", prepared.getModelDefinitions().get("requested").get("modelId"));
            assertNull(prepared.getResolvedModels());

            ObjectNode test = request("test", definition);
            test.set("input", mapper.valueToTree(input));
            JsonNode tested = success(test);
            assertEquals("requested", tested.path("modelBindings").path("default").asText());
            assertEquals("request-model", requests.get(0).path("model").asText());
            assertFalse(Files.exists(projectRoot.resolve("data/models")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void projectChatRegistrationUsesRemoteModelRefsAndRequestOverrides() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = chatServer(requests, null, null);
        try {
            configureChat(server);
            Files.writeString(projectRoot.resolve("kompile.project.json"), """
                    {
                      "schemaVersion": 1,
                      "projectId": "chat-project",
                      "name": "Chat project",
                      "models": [{"id": "project-chat", "modelId": "registered-model", "source": "chat",
                                  "role": "LLM", "metadata": {"provider": "custom"}}],
                      "pipelines": [{"id": "registered-chat", "pipelineId": "registered-chat", "active": true,
                                     "modelRefs": ["project-chat"],
                                     "metadata": {"pipelineType": "CHAT_MODEL", "provider": "custom"}}]
                    }
                    """);
            JsonNode fetched = success(mapper.createObjectNode().put("action", "get").put("pipelineId", "registered-chat"));
            assertEquals("CHAT_MODEL", fetched.path("processor").path("type").asText(), fetched.toString());
            ObjectNode run = mapper.createObjectNode().put("action", "run")
                    .put("pipelineId", "registered-chat").put("wait", true);
            run.putObject("input").put("text", "registered source");
            JsonNode first = success(run);
            assertEquals("COMPLETED", first.path("status").asText(), first.toString());
            assertEquals("registered-model", requests.get(0).path("model").asText());
            ObjectNode input = (ObjectNode) run.get("input");
            input.putObject("modelBindings").put("default", "request-chat");
            input.putObject("modelDefinitions").putObject("request-chat")
                    .put("source", "chat").put("provider", "custom").put("modelId", "requested-model");
            JsonNode second = success(run);
            assertEquals("COMPLETED", second.path("status").asText(), second.toString());
            assertEquals("requested-model", requests.get(1).path("model").asText());
            assertFalse(Files.exists(projectRoot.resolve("data/models")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatFileInputUsesHostDocumentRunnerWithoutTemporaryPipelineArtifacts() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = chatServer(requests, null, null);
        try {
            configureChat(server);
            Files.writeString(projectRoot.resolve("source.txt"), "file knowledge source");
            ObjectNode test = request("test", chatDefinition());
            test.putObject("input").put("filePath", "source.txt");
            JsonNode tested = success(test);
            assertEquals("host response", tested.path("output").path("text").asText());
            assertTrue(requests.get(0).toString().contains("file knowledge source"));
            assertFalse(Files.exists(projectRoot.resolve("data/pipelines")), "pipeline test is read-only");
            assertFalse(Files.exists(projectRoot.resolve("data/models")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mixedTensorChatAndSecretContractsFailBeforeAnyRuntimeLaunch() throws Exception {
        ObjectNode mixed = chatDefinition();
        mixed.set("pipelineSpec", definition("local").path("pipelineSpec"));
        ToolResult rejected = tool.execute(request("create", mixed), context);
        assertTrue(rejected.isError());
        assertTrue(rejected.getOutput().contains("one host stage"));
        assertFalse(Files.exists(projectRoot.resolve("data/pipelines")));

        ObjectNode secret = chatDefinition();
        secret.withObject("/processor").put("apiKey", "never-persist-or-return-this");
        ToolResult secretResult = tool.execute(request("create", secret), context);
        assertTrue(secretResult.isError());
        assertFalse(secretResult.getOutput().contains("never-persist-or-return-this"));

        ObjectNode tensor = request("test", chatDefinition());
        tensor.putObject("input").put("text", "source").putObject("input_ids");
        ToolResult invalidInput = tool.execute(tensor, context);
        assertTrue(invalidInput.isError(), invalidInput.getOutput());
        assertTrue(invalidInput.getOutput().contains("Unsupported CHAT_MODEL input field"));

        ObjectNode local = request("test", definition("local"));
        ObjectNode input = local.putObject("input");
        input.putObject("modelBindings").put("default", "chat-generator");
        input.putObject("modelDefinitions").putObject("chat-generator").put("source", "chat").put("modelId", "remote");
        ToolResult invalidLocal = tool.execute(local, context);
        assertTrue(invalidLocal.isError(), invalidLocal.getOutput());
        assertTrue(invalidLocal.getOutput().contains("Local tensor pipelines"), invalidLocal.getOutput());
        assertFalse(Files.exists(projectRoot.resolve("data/models")));
    }

    @Test
    void nativeModelCatalogExposesExactIdsWithoutChangingPipelineSelection() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> paths = new CopyOnWriteArrayList<>();
        server.createContext("/v1/models", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            byte[] body = "{\"data\":[{\"id\":\"model-one\"},{\"id\":\"model-two\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            configureChat(server);
            ObjectNode request = mapper.createObjectNode().put("action", "list_models").put("provider", "custom");
            JsonNode catalog = success(request);
            assertEquals("SUCCESS", catalog.path("catalogStatus").asText());
            assertEquals(List.of("model-one", "model-two"), catalog.path("models").findValuesAsText("id"));
            assertEquals("configured-model", catalog.path("configuredModel").asText());
            assertTrue(catalog.path("manualModelSelectionAllowed").asBoolean());
            assertEquals("UNKNOWN", catalog.path("readiness").asText());
            assertTrue(tool.parameterSchema().path("properties").path("action").path("enum").toString().contains("list_models"));
            assertTrue(tool.execute(request.deepCopy().put("provider", 42), context).isError());
            assertTrue(tool.execute(request.deepCopy().put("apiKey", "inline-secret"), context).isError());
            JsonNode selection = success(mapper.createObjectNode().put("action", "capabilities")
                    .put("provider", "custom").put("model", "manual-private-model").put("operation", "image"));
            assertEquals("manual-private-model", selection.path("chatModel").path("selection").path("model").asText());
            assertEquals(List.of("/v1/models"), paths);
            assertFalse(Files.exists(projectRoot.resolve("data/models")));
        } finally { server.stop(0); }
    }

    @Test
    void nativeChatCapabilitiesAreNoCallByDefaultAndLiveProbesAreBoundedOptIn() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = chatServer(requests, null, null);
        try {
            configureChat(server);
            JsonNode discovered = success(mapper.createObjectNode().put("action", "capabilities"));
            assertTrue(discovered.path("chatModel").path("readiness").asText().contains("UNKNOWN"));
            assertFalse(discovered.path("chatModel").path("probe").asBoolean());
            ObjectNode selection = mapper.createObjectNode().put("action", "capabilities")
                    .put("provider", "custom").put("model", "probe-model").put("operation", "text");
            success(selection);
            assertTrue(requests.isEmpty(), "Metadata selection must not send a provider request");
            ToolResult unsupported = tool.execute(selection.deepCopy().put("operation", "embedding"), context);
            assertTrue(unsupported.isError());
            assertTrue(requests.isEmpty());
            for (int seconds : List.of(0, 61)) {
                assertTrue(tool.execute(selection.deepCopy().put("probe", true)
                        .put("probeTimeoutSeconds", seconds), context).isError());
            }
            assertTrue(requests.isEmpty());
            // The synthetic probe may require a particular response shape. This fixture only
            // verifies explicit opt-in reaches the native transport; the helper owns its verdict.
            tool.execute(selection.put("probe", true).put("probeTimeoutSeconds", 5), context);
            assertEquals(1, requests.size(), "Live opt-in probes use a synthetic native chat call");
            assertFalse(Files.exists(projectRoot.resolve("data/models")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void hostWholePipelineTimeoutDoesNotReturnPhantomSuccess() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = chatServer(new CopyOnWriteArrayList<>(), null, release);
        try {
            configureChat(server);
            UnifiedPipelineDefinition definition = mapper.convertValue(chatDefinition(), UnifiedPipelineDefinition.class);
            Exception failure = assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                    assertThrows(Exception.class, () -> tool.test(projectRoot, definition,
                            Map.of("text", "bounded source"), Map.of(), Duration.ofMillis(300))));
            assertTrue(failure.getMessage().contains("timed out"), failure.toString());
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void cancellingHostRunInterruptsItsNativeOwnerAndNeverPublishesLateOutput() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = chatServer(new CopyOnWriteArrayList<>(), entered, release);
        try {
            configureChat(server);
            success(request("create", chatDefinition()));
            ObjectNode run = mapper.createObjectNode().put("action", "run").put("pipelineId", "host-chat");
            run.putObject("input").put("text", "cancel this source");
            String runId = success(run).path("runId").asText();
            assertTrue(entered.await(5, TimeUnit.SECONDS), "Native provider call must enter before cancellation");
            JsonNode cancelled = success(mapper.createObjectNode().put("action", "cancel").put("runId", runId));
            assertTrue(cancelled.path("cancelled").asBoolean(), cancelled.toString());
            JsonNode terminal = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (true) {
                    JsonNode status = success(mapper.createObjectNode().put("action", "status").put("runId", runId));
                    if (status.path("terminal").asBoolean()) return status;
                    Thread.sleep(10);
                }
            });
            assertEquals("CANCELLED", terminal.path("status").asText(), terminal.toString());
            assertTrue(terminal.path("terminationConfirmed").asBoolean(), terminal.toString());
            assertFalse(terminal.has("output"));
            release.countDown();
            Thread.sleep(50);
            JsonNode afterRelease = success(mapper.createObjectNode().put("action", "status").put("runId", runId));
            assertEquals("CANCELLED", afterRelease.path("status").asText());
            assertFalse(afterRelease.has("output"));
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void composedSequenceSelectsExactModelsAndPassesIntermediateText() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = chatServer(requests, null, null);
        try {
            configureChat(server);
            ObjectNode definition = chatSequence();
            definition.putObject("modelBindings").put("step0", "reader").put("step1", "writer");
            ObjectNode models = definition.putObject("modelDefinitions");
            models.putObject("reader").put("source", "chat").put("provider", "custom").put("modelId", "vision-reader-v2");
            models.putObject("writer").put("source", "chat").put("provider", "custom").put("modelId", "text-writer-v3");
            ObjectNode test = request("test", definition);
            ObjectNode input = test.putObject("input").put("text", "Original sequence source");
            input.putObject("modelBindings").put("step1", "unlisted-exact-model");
            JsonNode result = success(test);
            assertEquals("COMPLETED", result.path("status").asText(), result.toString());
            assertEquals("host response", result.path("output").path("text").asText());
            assertEquals(2, requests.size());
            assertEquals("vision-reader-v2", requests.get(0).path("model").asText());
            assertEquals("unlisted-exact-model", requests.get(1).path("model").asText());
            assertTrue(requests.get(0).toString().contains("Original sequence source"));
            assertTrue(requests.get(1).toString().contains("host response"));
            assertFalse(Files.exists(projectRoot.resolve("data/models")));
        } finally { server.stop(0); }
    }

    @Test
    void composedGraphOrdersDependenciesAndJoinsFanIn() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = chatServer(requests, null, null);
        try {
            configureChat(server);
            ObjectNode test = request("test", chatGraph());
            test.putObject("input").put("text", "Original DAG source");
            JsonNode result = success(test);
            assertEquals("COMPLETED", result.path("status").asText(), result.toString());
            assertEquals(List.of("left-model", "right-model", "join-model"),
                    requests.stream().map(r -> r.path("model").asText()).toList());
            assertTrue(requests.get(2).toString().contains("host response\\n\\nhost response"));
        } finally { server.stop(0); }
    }

    @Test
    void unsupportedLaterStageAndInvalidGraphsMakeNoProviderCalls() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = chatServer(requests, null, null);
        try {
            configureChat(server);
            ObjectNode definition = chatSequence();
            ((ObjectNode) definition.at("/pipelineSpec/steps/1/parameters/processor")).put("operation", "image");
            ObjectNode test = request("test", definition);
            test.putObject("input").put("text", "Text cannot satisfy image stage");
            ToolResult result = tool.execute(test, context);
            assertTrue(result.isError(), result.getOutput());
            assertTrue(requests.isEmpty(), "Preflight all stages before sending any input");

            ObjectNode mixed = chatSequence();
            ((ObjectNode) mixed.at("/pipelineSpec/steps/1")).put("runnerClassName", "local.tensor.Runner");
            assertFalse(success(request("validate", mixed)).path("valid").asBoolean());
            ObjectNode cycle = chatGraph();
            ((ObjectNode) cycle.at("/pipelineSpec/nodes/1")).putArray("inputs").add("join");
            assertFalse(success(request("validate", cycle)).path("valid").asBoolean());
            ObjectNode generatedPath = chatGraph();
            ((ObjectNode) generatedPath.at("/pipelineSpec/nodes/0/stepConfig/parameters"))
                    .putObject("inputDataBindings").put("filePath", "left.text");
            assertFalse(success(request("validate", generatedPath)).path("valid").asBoolean());
            assertTrue(requests.isEmpty());
        } finally { server.stop(0); }
    }

    @Test
    void crawlUnifiedHostSequenceUsesSameExactModelExecutor() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = chatServer(requests, null, null);
        try {
            configureChat(server);
            Path source = projectRoot.resolve("source.txt");
            Files.writeString(source, "Full crawl source");
            var pipeline = new ai.kompile.cli.main.project.LocalCrawlCapabilities.ResolvedPipeline(
                    "composed-crawl", "LLM", "auto", "no-op", 0, 0, Map.of(),
                    Map.of("type", "UNIFIED_PIPELINE", "pipelineDefinition", mapper.convertValue(chatSequence(), Map.class)));
            String text = ai.kompile.cli.main.project.LocalModelPipelineRunner.extract(projectRoot, source, pipeline, null);
            assertEquals("host response", text);
            assertEquals(List.of("first-model", "second-model"), requests.stream().map(r -> r.path("model").asText()).toList());
            assertTrue(requests.get(0).toString().contains("Full crawl source"));
            assertFalse(Files.exists(projectRoot.resolve("data/models")));
        } finally { server.stop(0); }
    }

    @Test
    void crawlCompositionUsesLoadedTextAndProjectExactModelAlias() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = chatServer(requests, null, null);
        try {
            configureChat(server);
            Files.writeString(projectRoot.resolve("kompile.project.json"), """
                    {"schemaVersion":1,"projectId":"chat-project","name":"Chat project",
                     "models":[{"id":"project-chat","modelId":"registered-exact-model","source":"chat",
                       "role":"LLM","metadata":{"provider":"custom"}}]}
                    """);
            Path source = projectRoot.resolve("source.txt");
            Files.writeString(source, "Do not replace explicitly loaded text with disk contents");
            ObjectNode definition = chatSequence();
            definition.putObject("inputs").putObject("text").put("type", "string").put("required", true);
            ((ObjectNode) definition.at("/pipelineSpec/steps/0/parameters/processor")).put("modelId", "project-chat");
            var pipeline = new ai.kompile.cli.main.project.LocalCrawlCapabilities.ResolvedPipeline(
                    "composed-crawl", "LLM", "auto", "no-op", 0, 0, Map.of(),
                    Map.of("type", "UNIFIED_PIPELINE", "pipelineDefinition", mapper.convertValue(definition, Map.class)));
            assertEquals("host response", ai.kompile.cli.main.project.LocalModelPipelineRunner.extract(
                    projectRoot, source, pipeline, "Authoritative loaded source"));
            assertEquals("registered-exact-model", requests.get(0).path("model").asText());
            assertTrue(requests.get(0).toString().contains("Authoritative loaded source"));
            assertFalse(requests.get(0).toString().contains("disk contents"));
            assertFalse(Files.exists(projectRoot.resolve("data/models")));
        } finally { server.stop(0); }
    }

    @Test
    void crawlTextContractRejectsUnloadedBinaryBeforeProviderCall() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = chatServer(requests, null, null);
        try {
            configureChat(server);
            Path source = projectRoot.resolve("source.pdf");
            Files.writeString(source, "Not parsed as text implicitly");
            ObjectNode definition = chatSequence();
            definition.putObject("inputs").putObject("text").put("type", "string").put("required", true);
            var pipeline = new ai.kompile.cli.main.project.LocalCrawlCapabilities.ResolvedPipeline(
                    "composed-crawl", "LLM", "auto", "no-op", 0, 0, Map.of(),
                    Map.of("type", "UNIFIED_PIPELINE", "pipelineDefinition", mapper.convertValue(definition, Map.class)));
            assertThrows(IOException.class, () -> ai.kompile.cli.main.project.LocalModelPipelineRunner.extract(
                    projectRoot, source, pipeline, null));
            assertTrue(requests.isEmpty());
        } finally { server.stop(0); }
    }

    @Test
    void projectArtifactAliasCannotBecomeRemoteModelImplicitly() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = chatServer(requests, null, null);
        try {
            configureChat(server);
            Files.writeString(projectRoot.resolve("kompile.project.json"), """
                    {"schemaVersion":1,"projectId":"chat-project","name":"Chat project",
                     "models":[{"id":"artifact-model","modelId":"local-model","source":"local","role":"LLM"}]}
                    """);
            ObjectNode definition = chatSequence();
            ((ObjectNode) definition.at("/pipelineSpec/steps/1/parameters/processor")).put("modelId", "artifact-model");
            ObjectNode test = request("test", definition);
            test.putObject("input").put("text", "Do not send before validating every model");
            ToolResult result = tool.execute(test, context);
            assertTrue(result.isError(), result.getOutput());
            assertTrue(requests.isEmpty());
        } finally { server.stop(0); }
    }

    private ObjectNode chatSequence() {
        ObjectNode definition = mapper.createObjectNode().put("pipelineId", "chat-sequence").put("kind", "LLM").put("topology", "SEQUENCE");
        definition.putObject("pipelineSpec").put("@class", ai.kompile.pipeline.serving.definition.ChatPipelineComposition.SEQUENCE)
                .putArray("steps").add(chatStep("first-model")).add(chatStep("second-model"));
        return definition;
    }

    private ObjectNode chatGraph() {
        ObjectNode definition = mapper.createObjectNode().put("pipelineId", "chat-graph").put("kind", "LLM").put("topology", "GRAPH");
        var nodes = definition.putObject("pipelineSpec").put("@class", ai.kompile.pipeline.serving.definition.ChatPipelineComposition.GRAPH)
                .put("inputNodeName", "pipeline_input").put("outputNodeName", "join").putArray("nodes");
        for (String name : List.of("join", "left", "right")) {
            ObjectNode node = nodes.addObject().put("@graphNodeType", "STANDARD").put("name", name);
            var inputs = node.putArray("inputs");
            if (name.equals("join")) inputs.add("left").add("right"); else inputs.add("pipeline_input");
            node.set("stepConfig", chatStep(name + "-model"));
        }
        return definition;
    }

    private ObjectNode chatStep(String model) {
        ObjectNode step = mapper.createObjectNode().put("@class", ai.kompile.pipeline.serving.definition.ChatPipelineComposition.STEP)
                .put("runnerClassName", "CHAT_MODEL");
        step.putObject("parameters").putObject("processor").put("type", "CHAT_MODEL").put("provider", "custom").put("modelId", model);
        return step;
    }

    private ObjectNode chatDefinition() {
        ObjectNode definition = mapper.createObjectNode().put("pipelineId", "host-chat")
                .put("kind", "LLM").put("topology", "SEQUENCE");
        definition.putObject("processor").put("type", "CHAT_MODEL").put("provider", "custom")
                .put("modelId", "configured-model").put("prompt", "Extract the source faithfully");
        return definition;
    }

    private JsonNode success(ObjectNode request) throws Exception {
        ToolResult result = tool.execute(request, context);
        assertFalse(result.isError(), result.getOutput());
        return mapper.readTree(result.getOutput());
    }

    private void configureChat(HttpServer server) throws IOException {
        new ChatConfig("custom", null, "configured-model",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1").saveProject(projectRoot);
    }

    private HttpServer chatServer(List<JsonNode> requests, CountDownLatch entered, CountDownLatch release) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(mapper.readTree(exchange.getRequestBody()));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            if (entered != null) entered.countDown();
            try (OutputStream response = exchange.getResponseBody()) {
                if (release != null && !release.await(10, TimeUnit.SECONDS)) return;
                String body = "data: {\"choices\":[{\"delta\":{\"content\":\"host response\"},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n";
                response.write(body.getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        return server;
    }

    private ObjectNode request(String action, JsonNode definition) {
        ObjectNode request = mapper.createObjectNode().put("action", action);
        request.set("definition", definition);
        return request;
    }

    private JsonNode definition(String description) throws Exception {
        return mapper.readTree("""
                {
                  "schemaVersion": 1,
                  "pipelineId": "agent-pipeline",
                  "displayName": "Agent pipeline",
                  "description": "%s",
                  "kind": "GENERIC",
                  "topology": "SEQUENCE",
                  "pipelineSpec": {
                    "@class": "ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline",
                    "id": "agent-pipeline",
                    "steps": []
                  }
                }
                """.formatted(description));
    }
}
