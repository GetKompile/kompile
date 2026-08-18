package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.modelmanager.vlm.registry.VlmPipelineRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VlmModelDefinitionToolTest {
    @TempDir
    Path tempDir;

    private ObjectMapper mapper;
    private ToolContext context;
    private VlmModelDefinitionTool tool;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        PermissionService permissions = new PermissionService();
        permissions.setUserOverride("vlm_model_definition",
                PermissionService.PermissionLevel.ALLOW);
        context = new ToolContext("vlm-model-agent",
                AgentConfig.builder("vlm-model-agent")
                        .enabledTools(Set.of("vlm_model_definition"))
                        .build(),
                permissions, tempDir, new ToolRegistry(mapper));
        tool = new VlmModelDefinitionTool(mapper, VlmPipelineRegistry.create(tempDir.resolve("config")));
    }

    @Test
    void createsUpdatesListsValidatesAndDeletesProviderNeutralDefinition() throws Exception {
        ObjectNode definition = definition("Provider A", "provider-a");
        ToolResult created = tool.execute(request("create", definition), context);

        assertFalse(created.isError(), created.getOutput());
        JsonNode createdJson = mapper.readTree(created.getOutput());
        assertTrue(createdJson.path("created").asBoolean());
        assertEquals("provider-a-vlm", createdJson.path("modelId").asText());
        assertEquals("https://api.example.test/provider-a",
                createdJson.path("definition").path("metadata").path("providerEndpoint").asText());
        assertEquals("sha256:vision", createdJson.path("definition").path("components").get(0)
                .path("checksum").asText());

        ObjectNode update = definition("Provider B", "provider-b");
        ToolResult updated = tool.execute(request("update", update).put("modelId", "provider-a-vlm"),
                context);

        assertFalse(updated.isError(), updated.getOutput());
        assertEquals("Provider B",
                mapper.readTree(updated.getOutput()).path("definition")
                        .path("displayName").asText());

        ToolResult listed = tool.execute(mapper.createObjectNode().put("action", "list"), context);
        assertFalse(listed.isError(), listed.getOutput());
        assertTrue(listed.getOutput().contains("provider-a-vlm"), listed.getOutput());

        ObjectNode validate = request("validate", definition("Provider B", "provider-b"));
        JsonNode validation = mapper.readTree(tool.execute(validate, context).getOutput());
        assertTrue(validation.path("valid").asBoolean());

        ToolResult deleted = tool.execute(mapper.createObjectNode()
                .put("action", "delete").put("modelId", "provider-a-vlm"), context);
        assertFalse(deleted.isError(), deleted.getOutput());
        assertTrue(tool.execute(mapper.createObjectNode().put("action", "get")
                .put("modelId", "provider-a-vlm"), context).isError());
    }

    @Test
    void exposesCompleteComponentAndFreeFormRuntimeSchema() {
        JsonNode schema = tool.parameterSchema();
        JsonNode componentFields = schema.at("/properties/definition/properties/components/items/properties");
        assertTrue(componentFields.has("checksum"));
        assertTrue(componentFields.has("inputShape"));
        assertTrue(schema.at("/properties/definition/properties/runtime/additionalProperties").asBoolean());
        assertTrue(schema.at("/properties/definition/properties/pipelineConfig/additionalProperties").asBoolean());
    }

    @Test
    void rejectsDefinitionsWithoutProviderLocator() throws Exception {
        ObjectNode definition = mapper.createObjectNode()
                .put("setId", "missing-locator")
                .put("displayName", "Missing locator");
        ToolResult result = tool.execute(request("create", definition), context);

        assertTrue(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("provider, repository, localPath"),
                result.getOutput());
    }

    private ObjectNode request(String action, JsonNode definition) {
        return mapper.createObjectNode().put("action", action).set("definition", definition);
    }

    private ObjectNode definition(String displayName, String provider) {
        ObjectNode definition = mapper.createObjectNode()
                .put("setId", "provider-a-vlm")
                .put("displayName", displayName)
                .put("description", "Configurable provider-neutral VLM")
                .put("provider", provider)
                .put("source", "CUSTOM_URL")
                .put("repository", "registry://" + provider + "/vlm")
                .put("revision", "stable")
                .put("format", "safetensors")
                .put("modelType", "document-vlm")
                .put("providerEndpoint", "https://api.example.test/" + provider);
        definition.putObject("runtime").put("type", "document-vlm")
                .put("autoBootstrap", false);
        definition.putObject("metadata").put("owner", "test");
        definition.putArray("components").addObject()
                .put("componentKey", "vision")
                .put("fileName", "vision.bin")
                .put("downloadUrl", "https://models.example.test/" + provider + "/vision.bin")
                .put("checksum", "sha256:vision")
                .put("pipelineStage", "VISION_ENCODING");
        return definition;
    }
}
