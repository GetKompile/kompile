package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.project.LocalProjectModelBootstrap;
import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    void capabilitiesExposeOnlyManagedUnifiedRuntime() throws Exception {
        ToolResult result = tool.execute(mapper.createObjectNode().put("action", "capabilities"), context);
        assertFalse(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("UNIFIED_PIPELINE"));
        assertTrue(result.getOutput().contains("callerManagedProcesses"));
        assertTrue(result.getOutput().contains("VLM_DOCUMENT"), result.getOutput());
        assertTrue(result.getOutput().contains("application/pdf"), result.getOutput());
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
        assertTrue(schema.path("properties").path("modelRuntime").path("description").asText()
                .contains("model_runtime"));
        assertTrue(schema.path("properties").path("modelRuntime").path("properties")
                .has("servingExecutable"));
        assertTrue(schema.path("properties").path("modelRuntime").path("properties")
                .has("localPath"));
        assertTrue(result.getOutput().contains("modelSpecificPipelineRequired"));
        assertFalse(result.getOutput().contains("documentModelExecutable"));
        assertFalse(result.getOutput().contains("vlm-test"));
        assertFalse(result.getOutput().toLowerCase().contains("smoldocling"),
                "Capabilities must not select a provider-specific model: " + result.getOutput());
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
