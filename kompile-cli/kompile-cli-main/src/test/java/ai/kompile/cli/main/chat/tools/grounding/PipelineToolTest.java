package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
    void capabilitiesExposeOnlyManagedUnifiedRuntime() throws Exception {
        ToolResult result = tool.execute(mapper.createObjectNode().put("action", "capabilities"), context);
        assertFalse(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("UNIFIED_PIPELINE"));
        assertTrue(result.getOutput().contains("callerManagedProcesses"));
        assertFalse(result.getOutput().contains("documentModelExecutable"));
        assertFalse(result.getOutput().contains("vlm-test"));
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
