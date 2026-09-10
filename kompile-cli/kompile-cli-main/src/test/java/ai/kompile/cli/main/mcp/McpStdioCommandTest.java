/*
 *   Copyright 2025 Kompile Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package ai.kompile.cli.main.mcp;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.roles.BuiltInRoles;
import ai.kompile.cli.main.chat.tools.DynamicToolManager;
import ai.kompile.cli.main.chat.tools.McpToolAnnotations;
import ai.kompile.cli.main.chat.tools.ToolSchemaOptimizer;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.coordination.CoordinationStateManager;
import ai.kompile.cli.mcp.stdio.AsyncToolExecutor;
import ai.kompile.cli.mcp.stdio.McpStderrLogger;
import ai.kompile.cli.mcp.stdio.StdioTaskTool;
import ai.kompile.cli.mcp.stdio.StdioMultiTaskTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import picocli.CommandLine;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class McpStdioCommandTest {

    @Test
    void daemonBridgeIsExplicitOptInAndNoDaemonNeverEnablesIt() throws Exception {
        McpStdioCommand defaults = new McpStdioCommand();
        new CommandLine(defaults).parseArgs();
        assertFalse(daemonEnabled(defaults));

        McpStdioCommand optedIn = new McpStdioCommand();
        new CommandLine(optedIn).parseArgs("--daemon");
        assertTrue(daemonEnabled(optedIn));

        McpStdioCommand explicitlyDisabled = new McpStdioCommand();
        new CommandLine(explicitlyDisabled).parseArgs("--no-daemon");
        assertFalse(daemonEnabled(explicitlyDisabled));
    }

    @Test
    @ResourceLock("user.home")
    void mcpDiagnosticsUseTheExplicitTranscriptDirectory(@TempDir Path tempDir)
            throws Exception {
        String previousHome = System.getProperty("user.home");
        String transcriptId = UUID.randomUUID().toString();
        try {
            System.setProperty("user.home", tempDir.toString());
            assertEquals(transcriptId,
                    McpStdioCommand.resolveTranscriptId(transcriptId));
            Path logDir = McpStdioCommand.transcriptMcpLogDirectory(transcriptId);
            assertEquals(tempDir.resolve(".kompile/logs/transcripts")
                    .resolve(transcriptId).resolve("mcp"), logDir);
            assertTrue(java.nio.file.Files.isDirectory(logDir));
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    @Test
    @ResourceLock("user.home")
    void rejectsPathTraversalAndUnwritableMcpSink(@TempDir Path tempDir)
            throws Exception {
        String previousHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());
            Path root = tempDir.resolve(".kompile/logs/transcripts")
                    .toAbsolutePath().normalize();
            Path dotDot = ai.kompile.cli.common.logs.LogPaths
                    .transcriptDirectory("..").toPath().toAbsolutePath().normalize();
            assertTrue(dotDot.startsWith(root));
            assertNotEquals(root, dotDot);
            assertNotEquals(
                    ai.kompile.cli.common.logs.LogPaths.transcriptDirectory("a/b"),
                    ai.kompile.cli.common.logs.LogPaths.transcriptDirectory("a_b"));

            Path logDir = McpStdioCommand.transcriptMcpLogDirectory(
                    UUID.randomUUID().toString());
            Path blockedSink = logDir.resolve("mcp-stderr.log");
            java.nio.file.Files.createDirectory(blockedSink);
            assertThrows(java.io.IOException.class,
                    () -> McpStdioCommand.requireWritableLogFile(blockedSink));
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    @Test
    void standaloneMcpStillGetsACanonicalUuidLogIdentity() {
        String generated = McpStdioCommand.resolveTranscriptId(null);
        assertEquals(generated, UUID.fromString(generated).toString());
    }

    @Test
    void sessionScopedToolsUseTheTranscriptIdentity() {
        assertEquals("transcript-123",
                McpStdioCommand.toolContextSessionId(" transcript-123 "));
        assertEquals("mcp-stdio", McpStdioCommand.toolContextSessionId(null));
    }

    @Test
    void stderrSinkFlushesBoundedChunksWithoutWaitingForANewline(@TempDir Path tempDir)
            throws Exception {
        Path log = tempDir.resolve("bounded-stderr.log");
        McpStderrLogger logger = new McpStderrLogger(log);
        byte[] payload = new byte[20_000];
        java.util.Arrays.fill(payload, (byte) 'x');

        logger.getPrintStream().write(payload);
        logger.getPrintStream().close();

        assertTrue(java.nio.file.Files.size(log) > payload.length);
    }

    private boolean daemonEnabled(McpStdioCommand command) throws Exception {
        Field field = McpStdioCommand.class.getDeclaredField("daemon");
        field.setAccessible(true);
        return field.getBoolean(command);
    }

    @Test
    @SuppressWarnings("unchecked")
    void readOnlyProfilesDoNotExposeBash() throws Exception {
        Field field = McpStdioCommand.class.getDeclaredField("PROFILE_TOOLS");
        field.setAccessible(true);
        Map<String, Set<String>> profiles = (Map<String, Set<String>>) field.get(null);

        assertFalse(profiles.get("minimal").contains("bash"));
        assertFalse(profiles.get("explore").contains("bash"));
        assertTrue(profiles.get("core").contains("bash"));
        assertTrue(profiles.get("minimal").contains("file_context"));
        assertTrue(profiles.get("core").contains("file_note"));
        assertTrue(profiles.get("explore").contains("graph_search"));
        assertTrue(profiles.get("explore").contains("graph_reasoning_query"));
    }

    @Test
    void daemonUsesTheResolvedProfileAllowlist() {
        McpStdioCommand command = new McpStdioCommand();
        new CommandLine(command).parseArgs("--profile", "explore", "--daemon");

        assertEquals("explore", command.resolvedProfileName());
        Set<String> allowed = command.resolvedProfileToolIds(command.resolvedProfileName());
        assertNotNull(allowed);
        assertTrue(allowed.contains("file_context"));
        assertFalse(allowed.contains("file_note"));
    }

    @Test
    void toolsListExposesParallelDelegationAndCallableSubtasksAtEverySchemaLevel(@TempDir Path workDir)
            throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        StdioTaskTool task = new StdioTaskTool(null, null, mapper, null);
        StdioMultiTaskTool multi = new StdioMultiTaskTool(null, null, mapper, workDir, null);
        Map<String, McpStdioCommand.ToolDef> tools = new LinkedHashMap<>();
        McpStdioCommand.registerTaskTools(tools, task, multi);

        for (String level : List.of("default", "none", "moderate", "aggressive", "compact")) {
            McpStdioCommand command = new McpStdioCommand();
            new CommandLine(command).parseArgs("default".equals(level)
                    ? new String[0] : new String[]{"--schema-level", level});
            // Also exercise group activation: loading delegation must expose both choices.
            DynamicToolManager manager = new DynamicToolManager();
            tools.values().forEach(tool -> manager.register(tool.name(), tool.description(), tool.schema()));
            assertTrue(manager.activateGroup("delegation").containsAll(List.of("task", "multi_task")));
            Field dynamic = McpStdioCommand.class.getDeclaredField("dynamicToolManager");
            dynamic.setAccessible(true);
            dynamic.set(command, manager);
            AsyncToolExecutor executor = prepareToolDispatch(command);
            try {
                ObjectNode message = mapper.createObjectNode();
                message.put("jsonrpc", "2.0").put("id", 1).put("method", "tools/list");
                Method handle = McpStdioCommand.class.getDeclaredMethod(
                        "handleMessage", JsonNode.class, Map.class, ObjectMapper.class);
                handle.setAccessible(true);
                JsonNode response = (JsonNode) handle.invoke(command, message, tools, mapper);
                Map<String, JsonNode> listed = new LinkedHashMap<>();
                response.path("result").path("tools").forEach(tool -> listed.put(tool.path("name").asText(), tool));
                assertEquals(Set.of("task", "multi_task"), listed.keySet(), level);
                String taskDescription = listed.get("task").path("description").asText();
                assertTrue(taskDescription.contains("multi_task"), taskDescription);
                assertTrue(taskDescription.contains("parallel batch"), taskDescription);
                String multiDescription = listed.get("multi_task").path("description").asText();
                assertTrue(multiDescription.contains("2+ independent subtasks"), multiDescription);
                assertTrue(multiDescription.contains("serial task calls"), multiDescription);
                if (List.of("default", "compact", "aggressive").contains(level)) {
                    assertEquals(task.compactHint(), taskDescription);
                    assertEquals(multi.compactHint(), multiDescription);
                    assertTrue(taskDescription.length() <= 200);
                    assertTrue(multiDescription.length() <= 200);
                }
                assertEquals(McpToolAnnotations.DELEGATION.toJsonNode(), listed.get("multi_task").path("annotations"));
                JsonNode schema = listed.get("multi_task").path("inputSchema");
                assertEquals(mapper.valueToTree(List.of("description", "subtasks")), schema.path("required"));
                assertSubtaskContract(mapper, schema);
            } finally {
                executor.shutdown();
            }
        }
    }

    @Test
    void parallelDelegationExampleAndOpenAiCompactSchemaRemainUsable(@TempDir Path workDir)
            throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        StdioMultiTaskTool multi = new StdioMultiTaskTool(null, null, mapper, workDir, null);
        String example = multi.description().split("Example:\\n", 2)[1].split("\\n\\n", 2)[0];
        JsonNode request = mapper.readTree(example);
        assertFalse(request.path("description").asText().isBlank());
        assertEquals(2, request.path("subtasks").size());
        request.path("subtasks").forEach(subtask -> {
            assertFalse(subtask.path("name").asText().isBlank());
            assertFalse(subtask.path("prompt").asText().isBlank());
        });
        assertTrue(multi.description().contains("different roles do not require serial dispatch"));
        assertTrue(multi.description().contains("resource-conflicting builds/tests"));
        var definitions = mapper.createArrayNode();
        var function = definitions.addObject().put("type", "function").putObject("function");
        function.put("name", multi.id()).put("description", multi.description());
        function.set("parameters", multi.parameterSchema());
        var optimized = ToolSchemaOptimizer.optimize(definitions, ToolSchemaOptimizer.OptimizationLevel.COMPACT,
                Map.of(multi.id(), multi.compactHint()));
        JsonNode compactParameters = optimized.get(0).path("function").path("parameters");
        assertSubtaskContract(mapper, compactParameters);
        JsonNode compactAgent = compactParameters.path("properties").path("subtasks")
                .path("items").path("properties").path("agent");
        assertFalse(compactAgent.has("description"));
        assertFalse(compactAgent.has("default"));
        assertEquals(multi.parameterSchema(), definitions.get(0).path("function").path("parameters"),
                "compaction must not mutate the original schema");

        function.put("name", "other_tool");
        var other = ToolSchemaOptimizer.optimize(definitions, ToolSchemaOptimizer.OptimizationLevel.COMPACT);
        assertFalse(other.get(0).path("function").path("parameters").path("properties")
                .path("subtasks").path("items").has("properties"),
                "the nested schema exception must not expand other tools");
    }

    private static void assertSubtaskContract(ObjectMapper mapper, JsonNode schema) {
        JsonNode subtasks = schema.path("properties").path("subtasks");
        assertEquals("array", subtasks.path("type").asText());
        JsonNode items = subtasks.path("items");
        assertEquals(mapper.valueToTree(List.of("name", "prompt")), items.path("required"));
        JsonNode properties = items.path("properties");
        for (String field : List.of("name", "prompt", "agent", "role", "model", "thinking")) {
            assertEquals("string", properties.path(field).path("type").asText(), field);
        }
        assertEquals(mapper.valueToTree(List.of("codex", "claude", "opencode")),
                properties.path("agent").path("enum"));
        assertEquals("array", properties.path("agents").path("type").asText());
        assertEquals("integer", properties.path("agent_count").path("type").asText());
    }

    @Test
    void buildCallResult_preservesToolMetadataAsStructuredContent() {
        McpStdioCommand command = new McpStdioCommand();
        ToolResult result = ToolResult.success(
                "reasoning layers",
                "Fetched reasoning overlays",
                Map.of(
                        "factSheetId", 42,
                        "layers", List.of("ontology", "psl", "mebn"),
                        "scores", Map.of("hybrid", 0.87)));

        ObjectNode callResult = command.buildCallResult(result);

        assertFalse(callResult.get("isError").asBoolean());
        assertEquals("text", callResult.get("content").get(0).get("type").asText());
        assertTrue(callResult.has("structuredContent"));
        assertEquals("reasoning layers", callResult.get("structuredContent").get("title").asText());
        assertEquals("Fetched reasoning overlays", callResult.get("structuredContent").get("output").asText());
        ObjectNode metadata = (ObjectNode) callResult.get("structuredContent").get("metadata");
        assertEquals(42, metadata.get("factSheetId").asInt());
        assertEquals("ontology", metadata.get("layers").get(0).asText());
        assertEquals(0.87, metadata.get("scores").get("hybrid").asDouble(), 1e-9);
    }

    @Test
    void buildCallResult_omitsStructuredContentWhenMetadataIsEmpty() {
        McpStdioCommand command = new McpStdioCommand();

        ObjectNode callResult = command.buildCallResult(ToolResult.success("plain text"));

        assertFalse(callResult.has("structuredContent"));
    }

    @Test
    void delegationQuotaFailureIsReturnedToTheWaitingParentCall() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        McpStdioCommand command = new McpStdioCommand();
        AsyncToolExecutor backgroundExecutor = prepareToolDispatch(command);
        try {
            McpStdioCommand.ToolDef task = delegationTool(
                    mapper, ignored -> ToolResult.error("Subagent quota exhausted"));

            JsonNode response = invokeToolCall(command, mapper, task, Map.of());
            JsonNode callResult = response.path("result");

            assertTrue(callResult.path("isError").asBoolean(),
                    "terminal quota failure must be returned on the original task call");
            assertTrue(callResult.path("content").path(0).path("text").asText()
                    .contains("quota exhausted"));
        } finally {
            backgroundExecutor.shutdown();
        }
    }

    @Test
    void delegationProcessExitReturnsItsResultToTheWaitingParentCall() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        McpStdioCommand command = new McpStdioCommand();
        AsyncToolExecutor backgroundExecutor = prepareToolDispatch(command);
        try {
            McpStdioCommand.ToolDef task = delegationTool(
                    mapper, ignored -> ToolResult.success("child finished normally"));

            JsonNode response = invokeToolCall(command, mapper, task, Map.of());
            JsonNode callResult = response.path("result");

            assertFalse(callResult.path("isError").asBoolean());
            assertEquals("child finished normally",
                    callResult.path("content").path(0).path("text").asText());
        } finally {
            backgroundExecutor.shutdown();
        }
    }

    @Test
    void explicitBackgroundDelegationStillReturnsAPollHandle() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        McpStdioCommand command = new McpStdioCommand();
        AsyncToolExecutor backgroundExecutor = prepareToolDispatch(command);
        try {
            McpStdioCommand.ToolDef task = delegationTool(
                    mapper, ignored -> ToolResult.success("background child finished"));

            JsonNode response = invokeToolCall(
                    command, mapper, task, Map.of("_background", true));
            JsonNode callResult = response.path("result");
            String text = callResult.path("content").path(0).path("text").asText();

            assertFalse(callResult.path("isError").asBoolean());
            assertTrue(text.contains("running in background"));
            assertTrue(text.contains("Task ID"));
            assertTrue(text.contains("Poll"));
        } finally {
            backgroundExecutor.shutdown();
        }
    }

    @Test
    void startupNotificationNeverReportsAZeroCountDuringAsyncInitialization() {
        assertEquals(
                "Kompile MCP server initialized; tools are loading asynchronously",
                McpStdioCommand.startupToolMessage(false, 0));
        assertEquals(
                "Kompile MCP server started with 82 tools",
                McpStdioCommand.startupToolMessage(true, 82));
    }

    @Test
    void initializationHintsOnlyPublishAnAuthoritativeToolCount() {
        ObjectNode loading = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        McpStdioCommand.populateInitializationHints(loading, false, 0, "full");
        assertTrue(loading.get("eagerLoadTools").asBoolean());
        assertFalse(loading.get("toolsReady").asBoolean());
        assertFalse(loading.has("toolCount"));
        assertEquals("full", loading.get("profile").asText());

        ObjectNode ready = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        McpStdioCommand.populateInitializationHints(ready, true, 82, "core");
        assertTrue(ready.get("toolsReady").asBoolean());
        assertEquals(82, ready.get("toolCount").asInt());
        assertEquals("core", ready.get("profile").asText());
    }

    @Test
    void stdioSessionRegistersItselfWithProjectLocalCoordination(@TempDir Path workDir) {
        CoordinationStateManager manager = new CoordinationStateManager(
                workDir, "mcp-stdio-test", JsonUtils.standardMapper());
        try {
            McpStdioCommand.registerStdioCoordinationSession(manager, "transcript-test");

            var agents = manager.queryAgents();
            assertEquals(1, agents.size());
            assertEquals("mcp-stdio-test", agents.get(0).getSessionId());
            assertEquals("transcript-test", agents.get(0).getToolSessionId());
            assertEquals(ProcessHandle.current().pid(), agents.get(0).getPid());
            assertFalse(agents.get(0).getAgentName().isBlank());
            assertFalse(agents.get(0).getTask().isBlank());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void architectRoleLeavesModelAndThinkingToTheSelectedAgent() {
        assertNull(BuiltInRoles.ARCHITECT.getAgentDefaultsFor("codex"));
        assertTrue(BuiltInRoles.ARCHITECT.isCanSpawnSubagents());
        assertEquals(Set.of("*"), BuiltInRoles.ARCHITECT.getEnabledTools());
        assertTrue(BuiltInRoles.ARCHITECT.getPermissionOverrides().isEmpty());
    }

    @Test
    void registeredDelegationToolsReceiveTheInvocationContext(@TempDir Path workDir) {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ToolContext first = new ToolContext("first", null, null, workDir, null);
        ToolContext second = new ToolContext("second", null, null, workDir, null);
        var current = new java.util.concurrent.atomic.AtomicReference<>(first);
        var received = new java.util.ArrayList<ToolContext>();
        StdioTaskTool task = new StdioTaskTool(null, null, mapper, null) {
            @Override public ToolResult execute(Map<String, Object> args, ToolContext context) {
                received.add(context);
                return ToolResult.success("task");
            }
        };
        StdioMultiTaskTool multi = new StdioMultiTaskTool(null, null, mapper, workDir, null) {
            @Override public ToolResult execute(Map<String, Object> args, ToolContext context) {
                received.add(context);
                return ToolResult.success("multi");
            }
        };
        Map<String, McpStdioCommand.ToolDef> tools = new LinkedHashMap<>();
        McpStdioCommand.registerTaskTools(tools, task, multi, current::get);
        tools.get("task").executor().apply(Map.of());
        current.set(second);
        tools.get("multi_task").executor().apply(Map.of());
        assertEquals(List.of(first, second), received);
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancellationBeforeOrAfterContextBindingStaysRequestLocal(@TempDir Path workDir)
            throws Exception {
        McpStdioCommand command = new McpStdioCommand();
        ObjectMapper mapper = JsonUtils.standardMapper();
        Field callsField = McpStdioCommand.class.getDeclaredField("inFlightCalls");
        callsField.setAccessible(true);
        Map<String, McpStdioCommand.InFlightCall> calls =
                (Map<String, McpStdioCommand.InFlightCall>) callsField.get(command);
        Field currentField = McpStdioCommand.class.getDeclaredField("CURRENT_CALL");
        currentField.setAccessible(true);
        ThreadLocal<McpStdioCommand.InFlightCall> current =
                (ThreadLocal<McpStdioCommand.InFlightCall>) currentField.get(null);
        Field contextField = McpStdioCommand.class.getDeclaredField("CTX");
        contextField.setAccessible(true);
        ThreadLocal<ToolContext> contexts = (ThreadLocal<ToolContext>) contextField.get(null);
        Method contextMethod = McpStdioCommand.class.getDeclaredMethod("ctx", Path.class);
        contextMethod.setAccessible(true);
        Method handle = McpStdioCommand.class.getDeclaredMethod(
                "handleMessage", JsonNode.class, Map.class, ObjectMapper.class);
        handle.setAccessible(true);
        try {
            for (boolean cancelBeforeBinding : List.of(true, false)) {
                var target = new McpStdioCommand.InFlightCall();
                var sibling = new McpStdioCommand.InFlightCall();
                calls.put("target", target);
                calls.put("sibling", sibling);
                current.set(sibling);
                ToolContext siblingContext = (ToolContext) contextMethod.invoke(command, workDir);
                contexts.remove();
                current.set(target);
                ToolContext targetContext = cancelBeforeBinding ? null
                        : (ToolContext) contextMethod.invoke(command, workDir);
                ObjectNode notification = mapper.createObjectNode();
                notification.put("jsonrpc", "2.0");
                notification.put("method", "notifications/cancelled");
                notification.putObject("params").put("requestId", "target");
                assertNull(handle.invoke(command, notification, Map.of(), mapper));
                if (targetContext == null) {
                    targetContext = (ToolContext) contextMethod.invoke(command, workDir);
                }
                assertTrue(targetContext.isAborted());
                assertSame(targetContext, target.context);
                assertFalse(siblingContext.isAborted());
                assertFalse(sibling.cancelled.get());
                contexts.remove();
                current.remove();
                calls.clear();
            }
        } finally {
            contexts.remove();
            current.remove();
            calls.clear();
        }
    }

    private static AsyncToolExecutor prepareToolDispatch(McpStdioCommand command)
            throws Exception {
        Field ready = McpStdioCommand.class.getDeclaredField("toolsReady");
        ready.setAccessible(true);
        ((AtomicBoolean) ready.get(command)).set(true);

        AsyncToolExecutor executor = new AsyncToolExecutor(null);
        Field async = McpStdioCommand.class.getDeclaredField("asyncExecutor");
        async.setAccessible(true);
        async.set(command, executor);
        return executor;
    }

    private static McpStdioCommand.ToolDef delegationTool(
            ObjectMapper mapper,
            java.util.function.Function<Map<String, Object>, ToolResult> executor) {
        return new McpStdioCommand.ToolDef(
                "task", "delegation test", mapper.createObjectNode(),
                McpToolAnnotations.DELEGATION, executor);
    }

    private static JsonNode invokeToolCall(
            McpStdioCommand command,
            ObjectMapper mapper,
            McpStdioCommand.ToolDef task,
            Map<String, Object> arguments) throws Exception {
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", 1);
        message.put("method", "tools/call");
        ObjectNode params = message.putObject("params");
        params.put("name", "task");
        params.set("arguments", mapper.valueToTree(arguments));

        Method handle = McpStdioCommand.class.getDeclaredMethod(
                "handleMessage", JsonNode.class, Map.class, ObjectMapper.class);
        handle.setAccessible(true);
        return (JsonNode) handle.invoke(command, message, Map.of("task", task), mapper);
    }
}
