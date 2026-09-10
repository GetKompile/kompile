package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.tui.SidePanelManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {

    private ToolRegistry registry;
    private ObjectMapper om;

    @BeforeEach
    void setUp() {
        om = new ObjectMapper();
        registry = new ToolRegistry(om);
    }

    @Test
    void baseToolsExposeAccurateMcpAnnotations() {
        assertEquals(McpToolAnnotations.READ_ONLY, new ReadTool().mcpAnnotations());
        assertEquals(McpToolAnnotations.READ_ONLY, new ReadBatchTool().mcpAnnotations());
        assertEquals(McpToolAnnotations.READ_ONLY, new FileContextTool().mcpAnnotations());
        assertTrue(new FileNoteTool().mcpAnnotations().destructiveHint());
        assertEquals(McpToolAnnotations.READ_ONLY, new GrepBatchTool().mcpAnnotations());
        assertEquals(McpToolAnnotations.READ_ONLY,
                new FetchResultBatchTool(new ToolResultReferenceCache()).mcpAnnotations());
        assertEquals(McpToolAnnotations.READ_ONLY, new GrepTool().mcpAnnotations());
        assertEquals(McpToolAnnotations.READ_ONLY, new GlobTool().mcpAnnotations());
        assertEquals(McpToolAnnotations.READ_ONLY, new ListTool().mcpAnnotations());
        assertEquals(McpToolAnnotations.NETWORK, new WebFetchTool().mcpAnnotations());
        assertEquals(McpToolAnnotations.NETWORK, new WebSearchTool().mcpAnnotations());
        assertEquals(McpToolAnnotations.DESTRUCTIVE, new BashTool().mcpAnnotations());
        assertEquals(McpToolAnnotations.WRITE, new WriteTool().mcpAnnotations());
        assertEquals(McpToolAnnotations.WRITE, new EditTool().mcpAnnotations());
        assertEquals(McpToolAnnotations.WRITE, new EditBatchTool().mcpAnnotations());
        assertEquals(McpToolAnnotations.WRITE, new EditPatchTool().mcpAnnotations());
        assertEquals(McpToolAnnotations.WRITE, new PatchTool().mcpAnnotations());
    }

    @Test
    void testRegisterAndGet() {
        TodoWriteTool tool = new TodoWriteTool();
        registry.register(tool);

        assertSame(tool, registry.get("todowrite"));
        assertTrue(registry.ids().contains("todowrite"));
    }

    @Test
    void testUnregister() {
        TodoWriteTool tool = new TodoWriteTool();
        registry.register(tool);
        assertNotNull(registry.get("todowrite"));

        registry.unregister("todowrite");
        assertNull(registry.get("todowrite"));
        assertFalse(registry.ids().contains("todowrite"));
    }

    @Test
    void testUnregisterNonexistent() {
        // Should not throw
        registry.unregister("nonexistent");
    }

    @Test
    void testGetToolsForAgentWithWildcard() {
        registry.register(new TodoWriteTool());
        registry.register(new TodoReadTool());
        registry.register(new ExitPlanModeTool());

        AgentConfig agent = AgentConfig.builder("coder")
                .enabledTools(Set.of("*"))
                .build();

        List<CliTool> tools = registry.getToolsForAgent(agent);
        assertEquals(3, tools.size());
    }

    @Test
    void testGetToolsForAgentFiltered() {
        registry.register(new TodoWriteTool());
        registry.register(new TodoReadTool());
        registry.register(new ExitPlanModeTool());

        AgentConfig agent = AgentConfig.builder("planner")
                .enabledTools(Set.of("todowrite", "todoread"))
                .build();

        List<CliTool> tools = registry.getToolsForAgent(agent);
        assertEquals(2, tools.size());
        assertTrue(tools.stream().anyMatch(t -> "todowrite".equals(t.id())));
        assertTrue(tools.stream().anyMatch(t -> "todoread".equals(t.id())));
        assertFalse(tools.stream().anyMatch(t -> "exit_plan_mode".equals(t.id())));
    }

    @Test
    void testBuildToolDefinitions() {
        registry.register(new ExitPlanModeTool());

        AgentConfig agent = AgentConfig.builder("test")
                .enabledTools(Set.of("*"))
                .build();

        ArrayNode defs = registry.buildToolDefinitions(agent);
        assertEquals(1, defs.size());

        var toolDef = defs.get(0);
        assertEquals("function", toolDef.path("type").asText());
        assertEquals("exit_plan_mode", toolDef.path("function").path("name").asText());
        assertFalse(toolDef.path("function").path("description").asText().isEmpty());
        assertTrue(toolDef.path("function").has("parameters"));
    }

    @Test
    void testBuildDirectToolDefinitionsUsesProviderNeutralShape() {
        registry.register(new ExitPlanModeTool());

        AgentConfig agent = AgentConfig.builder("test")
                .enabledTools(Set.of("*"))
                .build();

        ArrayNode defs = registry.buildDirectToolDefinitions(agent);
        assertEquals(1, defs.size());
        assertEquals("exit_plan_mode", defs.path(0).path("name").asText());
        assertFalse(defs.path(0).path("description").asText().isEmpty());
        assertTrue(defs.path(0).has("inputSchema"));
        assertFalse(defs.path(0).has("function"));
    }

    @Test
    void progressiveDefinitionsStartSmallAndExposeOnlyTheActivatedGroup() {
        registry.register(namedTool("read"));
        registry.register(namedTool("crawl_discover"));
        registry.register(namedTool("knowledge_graph"));
        registry.register(new ActivateToolsTool(registry.getDynamicToolManager()));
        AgentConfig agent = AgentConfig.builder("local-small")
                .enabledTools(Set.of("*"))
                .build();

        ArrayNode initial = registry.buildProgressiveDirectToolDefinitions(agent);
        assertEquals(List.of("read", "activate_tools"), directToolNames(initial));
        assertEquals(4, registry.buildDirectToolDefinitions(agent).size(),
                "the ordinary full-capability surface must remain unchanged");

        assertEquals(List.of("crawl_discover"),
                registry.getDynamicToolManager().activateGroup("crawl"));
        ArrayNode activated = registry.buildProgressiveDirectToolDefinitions(agent);
        assertEquals(List.of("read", "activate_tools", "crawl_discover"),
                directToolNames(activated));
        assertFalse(directToolNames(activated).contains("knowledge_graph"),
                "activating crawl must not leak the graph catalog");
    }

    @Test
    void codeGroupExposesGraphSearchAndSpecialistsReceiveIt() {
        registry.register(namedTool("read"));
        registry.register(namedTool("code_graph"));
        registry.register(namedTool("graph_search"));
        registry.register(namedTool("graph_reasoning_query"));
        AgentConfig architect = AgentConfig.builder("architect")
                .enabledTools(Set.of("read", "code_graph", "graph_search", "graph_reasoning_query"))
                .build();

        registry.prepareProgressiveTools(architect);
        List<String> names = directToolNames(registry.buildProgressiveDirectToolDefinitions(architect));

        assertTrue(names.contains("code_graph"));
        assertTrue(names.contains("graph_search"));
        assertTrue(names.contains("graph_reasoning_query"));
    }

    @Test
    void progressiveDefinitionsKeepNewUngroupedToolsBehindOtherGroup() {
        registry.register(namedTool("read"));
        registry.register(namedTool("future_special_tool"));
        registry.register(new ActivateToolsTool(registry.getDynamicToolManager()));
        AgentConfig agent = AgentConfig.builder("local-small")
                .enabledTools(Set.of("*"))
                .build();

        assertFalse(directToolNames(registry.buildProgressiveDirectToolDefinitions(agent))
                .contains("future_special_tool"));
        assertTrue(registry.getDynamicToolManager().availableGroupNames().contains("other"));

        assertEquals(List.of("future_special_tool"),
                registry.getDynamicToolManager().activateGroup("other"));
        assertTrue(directToolNames(registry.buildProgressiveDirectToolDefinitions(agent))
                .contains("future_special_tool"));
    }

    @Test
    void fileContextIsCoreAndFileNotesActivateWithFileTools() {
        registry.register(namedTool("read"));
        registry.register(namedTool("file_context"));
        registry.register(namedTool("file_note"));
        registry.register(new ActivateToolsTool(registry.getDynamicToolManager()));
        AgentConfig agent = AgentConfig.builder("local-small")
                .enabledTools(Set.of("*"))
                .build();

        assertTrue(directToolNames(registry.buildProgressiveDirectToolDefinitions(agent))
                .contains("file_context"));
        assertFalse(directToolNames(registry.buildProgressiveDirectToolDefinitions(agent))
                .contains("file_note"));
        assertTrue(registry.getDynamicToolManager().activateGroup("files")
                .contains("file_note"));
        assertTrue(directToolNames(registry.buildProgressiveDirectToolDefinitions(agent))
                .contains("file_note"));
    }

    @Test
    void activationToolSchemaTeachesTheSmallModelValidActionsAndGroups() {
        registry.register(namedTool("crawl_discover"));
        ActivateToolsTool activation = new ActivateToolsTool(registry.getDynamicToolManager());
        registry.register(activation);

        var schema = activation.parameterSchema();
        assertEquals(List.of("list", "describe", "activate"),
                values(schema.path("properties").path("action").path("enum")));
        assertTrue(values(schema.path("properties").path("group").path("enum"))
                .containsAll(List.of("crawl", "all")));
        assertTrue(activation.description().contains("group=crawl"));
        assertTrue(activation.description().contains("next step"));
    }

    @Test
    void rejectsBlankToolIdsBeforeTheyReachProviderRequests() {
        CliTool unnamed = new CliTool() {
            @Override public String id() { return " "; }
            @Override public String description() { return "invalid"; }
            @Override public com.fasterxml.jackson.databind.JsonNode parameterSchema() {
                return om.createObjectNode();
            }
            @Override public String permissionKey() { return "invalid"; }
            @Override public ToolResult execute(
                    com.fasterxml.jackson.databind.JsonNode params, ToolContext context) {
                return null;
            }
        };

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> registry.register(unnamed));
        assertTrue(error.getMessage().contains("must not be blank"));
    }

    @Test
    void testRegisterOverwritesExisting() {
        ExitPlanModeTool tool1 = new ExitPlanModeTool();
        ExitPlanModeTool tool2 = new ExitPlanModeTool();

        registry.register(tool1);
        assertSame(tool1, registry.get("exit_plan_mode"));

        registry.register(tool2);
        assertSame(tool2, registry.get("exit_plan_mode"));
    }

    @Test
    void testAllReturnsUnmodifiable() {
        registry.register(new TodoWriteTool());
        assertThrows(UnsupportedOperationException.class, () -> {
            registry.all().clear();
        });
    }

    @Test
    void sidePanelToolExposesVersionedManagerSnapshot() {
        SidePanelManager manager = new SidePanelManager();
        SidePanelTool tool = new SidePanelTool(manager);
        registry.register(tool);

        assertSame(manager, tool.getSidePanelManager());
        assertSame(tool, registry.get("side_panel"));

        long initialVersion = manager.snapshot().version();
        manager.show("Plan", "line one\nline two");
        SidePanelManager.Snapshot shown = manager.snapshot();
        assertTrue(shown.visible());
        assertEquals("Plan", shown.title());
        assertEquals("line one\nline two", shown.content());
        assertTrue(shown.version() > initialVersion);

        manager.hide();
        SidePanelManager.Snapshot hidden = manager.snapshot();
        assertFalse(hidden.visible());
        assertTrue(hidden.version() > shown.version());
    }

    private CliTool namedTool(String id) {
        return new CliTool() {
            @Override public String id() { return id; }
            @Override public String description() { return "Use " + id + " for its focused capability."; }
            @Override public com.fasterxml.jackson.databind.JsonNode parameterSchema() {
                return om.createObjectNode().put("type", "object");
            }
            @Override public String permissionKey() { return "read"; }
            @Override public ToolResult execute(
                    com.fasterxml.jackson.databind.JsonNode params, ToolContext context) {
                return ToolResult.success(id, "ok");
            }
        };
    }

    private static List<String> directToolNames(ArrayNode definitions) {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        definitions.forEach(definition -> names.add(definition.path("name").asText()));
        return names;
    }

    private static List<String> values(com.fasterxml.jackson.databind.JsonNode array) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }
}
