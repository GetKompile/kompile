package ai.kompile.e2e;

import ai.kompile.kclaw.agent.ToolkitRegistry;
import ai.kompile.kclaw.model.AgentDefinition;
import ai.kompile.kclaw.service.PermissionService;
import ai.kompile.kclaw.tool.MemoryTool;
import ai.kompile.kclaw.tool.ShellExecutionTool;
import ai.kompile.react.context.Toolkit;
import ai.kompile.react.model.ToolDefinition;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the ToolkitRegistry: built-in tool registration, Spring AI tool
 * auto-discovery bridge, tool metadata retrieval, and agent toolkit assembly.
 */
@Tag("e2e")
@ExtendWith(MockitoExtension.class)
@DisplayName("Toolkit Registry")
class ToolkitRegistryTest {

    @Mock
    private PermissionService permissionService;

    private ToolkitRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        ShellExecutionTool shellTool = new ShellExecutionTool(permissionService);
        MemoryTool memoryTool = new MemoryTool("/tmp/kclaw-test", permissionService);
        registry = new ToolkitRegistry(shellTool, memoryTool);
    }

    // ── Built-in Registration ──

    @Test
    @DisplayName("Registry initializes with three built-in tools")
    void testBuiltInTools() {
        List<String> names = registry.getToolNames();

        assertEquals(3, names.size());
        assertTrue(names.contains("run_command"));
        assertTrue(names.contains("save_memory"));
        assertTrue(names.contains("search_memory"));
    }

    @Test
    @DisplayName("All built-in tools have non-null definitions")
    void testBuiltInToolDefinitions() {
        List<ToolDefinition> tools = registry.getAllTools();

        assertEquals(3, tools.size());
        for (ToolDefinition tool : tools) {
            assertNotNull(tool.getName());
            assertNotNull(tool.getDescription());
            assertNotNull(tool.getExecutor());
        }
    }

    // ── Custom Tool Registration ──

    @Test
    @DisplayName("Register custom tool adds it to registry")
    void testRegisterCustomTool() {
        ToolDefinition custom = ToolDefinition.builder()
                .name("web_search")
                .description("Search the web for information")
                .parameters(Map.of("type", "object"))
                .executor(args -> "search result")
                .build();

        registry.registerTool(custom);

        assertEquals(4, registry.getToolNames().size());
        assertTrue(registry.getToolNames().contains("web_search"));
    }

    @Test
    @DisplayName("Register tool with same name replaces existing")
    void testRegisterOverwrite() {
        ToolDefinition v1 = ToolDefinition.builder()
                .name("custom_tool")
                .description("Version 1")
                .executor(args -> "v1")
                .build();

        ToolDefinition v2 = ToolDefinition.builder()
                .name("custom_tool")
                .description("Version 2")
                .executor(args -> "v2")
                .build();

        registry.registerTool(v1);
        registry.registerTool(v2);

        List<ToolDefinition> all = registry.getAllTools();
        ToolDefinition found = all.stream()
                .filter(t -> "custom_tool".equals(t.getName()))
                .findFirst()
                .orElse(null);

        assertNotNull(found);
        assertEquals("Version 2", found.getDescription());
    }

    // ── Tool Metadata ──

    @Test
    @DisplayName("getToolMetadata returns name and description for each tool")
    void testGetToolMetadata() {
        List<Map<String, String>> metadata = registry.getToolMetadata();

        assertEquals(3, metadata.size());

        for (Map<String, String> entry : metadata) {
            assertNotNull(entry.get("name"));
            assertNotNull(entry.get("description"));
            assertFalse(entry.get("name").isEmpty());
        }
    }

    @Test
    @DisplayName("getToolMetadata includes custom tools after registration")
    void testGetToolMetadataWithCustom() {
        registry.registerTool(ToolDefinition.builder()
                .name("rag_query")
                .description("Query the RAG index")
                .executor(args -> "result")
                .build());

        List<Map<String, String>> metadata = registry.getToolMetadata();

        assertEquals(4, metadata.size());
        boolean found = metadata.stream()
                .anyMatch(m -> "rag_query".equals(m.get("name")));
        assertTrue(found);
    }

    // ── Agent Toolkit Assembly ──

    @Test
    @DisplayName("getToolkit returns only requested tools for agent")
    void testGetToolkitFiltersTools() {
        AgentDefinition agent = AgentDefinition.builder()
                .name("coder")
                .systemPrompt("Code assistant")
                .tools(List.of("run_command", "save_memory"))
                .maxSteps(10)
                .build();

        Toolkit toolkit = registry.getToolkit(agent);

        assertNotNull(toolkit);
        List<ToolDefinition> agentTools = toolkit.getTools();
        assertEquals(2, agentTools.size());

        List<String> toolNames = agentTools.stream()
                .map(ToolDefinition::getName)
                .toList();
        assertTrue(toolNames.contains("run_command"));
        assertTrue(toolNames.contains("save_memory"));
        assertFalse(toolNames.contains("search_memory"));
    }

    @Test
    @DisplayName("getToolkit skips unknown tool names gracefully")
    void testGetToolkitSkipsUnknown() {
        AgentDefinition agent = AgentDefinition.builder()
                .name("test")
                .systemPrompt("Test")
                .tools(List.of("run_command", "nonexistent_tool"))
                .maxSteps(5)
                .build();

        Toolkit toolkit = registry.getToolkit(agent);

        List<ToolDefinition> agentTools = toolkit.getTools();
        assertEquals(1, agentTools.size());
        assertEquals("run_command", agentTools.get(0).getName());
    }

    @Test
    @DisplayName("getToolkit returns empty toolkit for agent with no tools")
    void testGetToolkitEmpty() {
        AgentDefinition agent = AgentDefinition.builder()
                .name("reader")
                .systemPrompt("Read-only agent")
                .tools(List.of())
                .maxSteps(5)
                .build();

        Toolkit toolkit = registry.getToolkit(agent);

        assertNotNull(toolkit);
        assertTrue(toolkit.getTools().isEmpty());
    }

    // ── Spring AI Bridge ──

    @Test
    @DisplayName("registerSpringAiTools handles null bean gracefully")
    void testRegisterSpringAiToolsNullSafe() {
        // Spring AI ToolCallbacks class won't be found for a plain Object
        assertDoesNotThrow(() -> registry.registerSpringAiTools(new Object()));
        // Built-in tools should be unaffected
        assertEquals(3, registry.getToolNames().size());
    }

    @Test
    @DisplayName("registerSpringAiTools does not crash when Spring AI is not on classpath")
    void testRegisterSpringAiToolsNoSpringAi() {
        // A regular POJO without @Tool annotations
        Object plainBean = new Object() {
            public String someMethod() { return "not a tool"; }
        };

        assertDoesNotThrow(() -> registry.registerSpringAiTools(plainBean));
        assertEquals(3, registry.getToolNames().size());
    }

    // ── Tool Execution ──

    @Test
    @DisplayName("Built-in run_command tool returns permission denied for blocked commands")
    void testRunCommandPermissionDenied() {
        when(permissionService.checkCommandSafety("rm -rf /")).thenReturn("denied");

        ToolDefinition runCommand = registry.getAllTools().stream()
                .filter(t -> "run_command".equals(t.getName()))
                .findFirst()
                .orElseThrow();

        String result = runCommand.getExecutor().apply(Map.of("command", "rm -rf /"));

        assertTrue(result.contains("Permission denied"));
    }

    @Test
    @DisplayName("Built-in run_command tool returns error for empty command")
    void testRunCommandEmptyCommand() {
        ToolDefinition runCommand = registry.getAllTools().stream()
                .filter(t -> "run_command".equals(t.getName()))
                .findFirst()
                .orElseThrow();

        String result = runCommand.getExecutor().apply(Map.of("command", ""));

        assertTrue(result.contains("Error"));
    }
}
