package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.SubagentResumeMetadata;
import ai.kompile.cli.main.chat.agent.SubagentResumeService;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.SubagentResumeTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.workflow.WorkflowTeamEnforcement;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class WebDurableSubagentsTest {
    private static final String ID = "subagent-saved-child";
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir Path temp;
    private Path project;
    private Path conversations;
    private SubagentResumeService service;
    private WebDurableSubagents browser;
    private ToolRegistry tools;
    private AgentRegistry agents;
    private ToolContext context;
    private ChatConfig config;
    private AtomicInteger calls;

    @BeforeEach void prepare() throws Exception {
        project = Files.createDirectory(temp.resolve("project"));
        conversations = Files.createDirectory(temp.resolve("conversations"));
        service = new SubagentResumeService(conversations, mapper);
        browser = new WebDurableSubagents(conversations, mapper);
        agents = new AgentRegistry();
        calls = new AtomicInteger();
        tools = new ToolRegistry(mapper);
        tools.register(new SubagentResumeTool(mapper, service, (metadata, request, principal) -> {
            calls.incrementAndGet();
            assertEquals(ID, metadata.sessionId());
            assertEquals("parent-browser", principal.getSessionId());
            assertEquals("resume", request.path("action").asText());
            assertFalse(request.path("background").asBoolean(true));
            return ToolResult.success("continued");
        }));
        context = principal(agents.get("coder"));
        config = new ChatConfig("custom", "fixture-key-not-for-output", "fixture-model", "http://127.0.0.1:1");
        config.setChatMode("standard");
        seed(ID, project);
    }

    private ToolContext principal(AgentConfig agent) {
        PermissionService permissions = new PermissionService();
        permissions.setPromptListener(ignored -> permissions.submitPromptResponse("deny"));
        return new ToolContext("parent-browser", agent, permissions, project, tools);
    }

    private void seed(String id, Path directory) throws Exception {
        SubagentResumeMetadata.create(id, "saved", "general", "original-parent", null, "fixture-model")
                .withRoute("custom", "api-key", "fixture-model", "private-account-reference", "high")
                .write(SubagentResumeMetadata.sidecarFor(conversations, id), mapper);
        Files.writeString(conversations.resolve(id + ".txt"), "Started: now\nAgent:   general\nCWD:     " + directory + "\n\n> old prompt\n\n< old answer\n");
    }

    private WebCommandResolver.Resolution intent() {
        return browser.resolve("resume " + mapper.createObjectNode().put("session_id", ID).put("prompt", "follow up"), project, context);
    }

    @Test void discoveryIsReadOnlyWorkspaceScopedAndSecretFree() throws Exception {
        seed("subagent-other", Files.createDirectory(temp.resolve("other")));
        Files.writeString(conversations.resolve("main-session.txt"), "CWD: " + project + "\n> main secret\n");
        var before = Files.list(conversations);
        Set<String> paths;
        try (before) { paths = before.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()); }
        var result = browser.resolve("saved", project, context);
        assertEquals(WebCommandResolver.Status.COMPLETED, result.status());
        assertEquals("durable-subagents", result.data().path("menu").asText());
        assertEquals("list", result.data().path("action").asText());
        assertEquals(1, result.data().path("children").size());
        var row = result.data().path("children").get(0);
        assertEquals(ID, row.path("sessionId").asText());
        assertEquals("original-parent", row.path("parentSession").asText());
        assertFalse(row.path("locked").asBoolean(true));
        assertFalse(result.data().toString().contains("private-account-reference"));
        assertFalse(result.data().toString().contains("fixture-key"));
        try (var after = Files.list(conversations)) {
            assertEquals(paths, after.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
        assertEquals(0, calls.get());
    }

    @Test void canonicalDirectoryAllowsAliasesButRejectsChangedSymlinkWorkspace() throws Exception {
        Path alias = temp.resolve("alias");
        Files.createSymbolicLink(alias, project);
        assertEquals(1, browser.resolve("saved", alias, context).data().path("children").size());
        seed("subagent-link", alias);
        Files.delete(alias);
        Files.createSymbolicLink(alias, Files.createDirectory(temp.resolve("elsewhere")));
        assertEquals(1, browser.resolve("saved", project, context).data().path("children").size());
        Files.delete(conversations.resolve(ID + ".txt"));
        Files.createSymbolicLink(conversations.resolve(ID + ".txt"), temp.resolve("outside.txt"));
        Files.writeString(temp.resolve("outside.txt"), "CWD: " + project + "\n> outside\n");
        assertEquals(0, browser.resolve("saved", project, context).data().path("children").size());
    }

    @Test void showIsPlainBoundedHistoryWithoutRouteOrLedgerBlocks() throws Exception {
        StringBuilder transcript = new StringBuilder("Started: now\nCWD: " + project + "\n\n");
        for (int i = 0; i < 40; i++) transcript.append("> ").append(i).append(":").append("x".repeat(3000)).append("\n\n");
        Files.writeString(conversations.resolve(ID + ".txt"), transcript);
        Files.writeString(conversations.resolve(ID + ".ledger.jsonl"), "route-config-secret-not-transcript");
        var result = browser.resolve("show " + ID, project, context);
        assertEquals(WebCommandResolver.Status.COMPLETED, result.status());
        assertEquals("show", result.data().path("action").asText());
        var history = result.data().path("history");
        assertTrue(history.size() <= 30);
        int chars = 0;
        for (var entry : history) {
            chars += entry.path("content").asText().length();
            assertEquals("user", entry.path("role").asText());
            assertEquals(2, entry.size());
        }
        assertTrue(chars <= 65_536);
        assertTrue(history.get(history.size() - 1).path("content").asText().startsWith("39:"));
        assertFalse(result.data().toString().contains("private-account-reference"));
        assertFalse(result.data().toString().contains("route-config-secret"));
    }

    @Test void exactIdsOnlyAndUnknownOrSecretFieldsNeverReachTool() {
        for (String id : new String[]{"saved", "subagent", "main-session", "subagent-../outside", "subagent-missing"}) {
            assertEquals(WebCommandResolver.Status.INVALID, browser.resolve("show " + id, project, context).status());
        }
        for (String extra : new String[]{"apiKey", "token", "agent", "provider", "role", "background", "unexpected"}) {
            var request = mapper.createObjectNode().put("session_id", ID).put("prompt", "follow up").put(extra, "TOP-SECRET");
            var result = browser.resolve("resume " + request, project, context);
            assertEquals(WebCommandResolver.Status.INVALID, result.status());
            assertFalse(result.text().contains("TOP-SECRET"));
        }
        assertEquals(WebCommandResolver.Status.INVALID, browser.resolve("resume {} {}", project, context).status());
        assertEquals(0, calls.get());
    }

    @Test void resumeIsDeferredThenCallsExistingToolExactlyOnceWithoutBackgrounding() {
        var intent = intent();
        assertTrue(WebDurableSubagents.isResume(intent));
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, intent.status());
        assertEquals(0, calls.get());
        var done = browser.execute(intent, context, config, agents);
        assertEquals(WebCommandResolver.Status.COMPLETED, done.status(), done.text());
        assertEquals("continued", done.text());
        assertEquals(1, calls.get());
        assertFalse(done.data().has("request"));
    }

    @Test void lockAndWorkspaceAreRecheckedAfterResolution() throws Exception {
        var intent = intent();
        assertNull(service.tryLock(ID));
        try {
            assertTrue(browser.resolve("saved", project, context).data().path("children").get(0).path("locked").asBoolean());
            assertEquals(WebCommandResolver.Status.INVALID, browser.execute(intent, context, config, agents).status());
            assertEquals(WebCommandResolver.Status.INVALID, browser.resolve("resume " + intent.data().path("request"), project, context).status());
        } finally { service.unlock(ID); }
        seed(ID, Files.createDirectory(temp.resolve("moved")));
        assertEquals(WebCommandResolver.Status.INVALID, browser.execute(intent, context, config, agents).status());
        assertEquals(0, calls.get());
    }

    @Test void childLedgerAliasCannotImportMainHistory() throws Exception {
        Path mainLedger = conversations.resolve("main-ledger.jsonl");
        Files.writeString(mainLedger, "main-history-secret");
        Files.createSymbolicLink(conversations.resolve(ID + ".ledger.jsonl"), mainLedger);
        assertEquals(0, browser.resolve("saved", project, context).data().path("children").size());
        assertEquals(WebCommandResolver.Status.INVALID, intent().status());
        assertEquals(0, calls.get());
    }

    @Test void readToolRoleAndTaskPermissionDenialsFailClosed() {
        var intent = intent();
        var noTool = agents.get("coder").toBuilder().enabledTools(Set.of("read")).build();
        assertEquals(WebCommandResolver.Status.INVALID, browser.execute(intent, principal(noTool), config, agents).status());
        var cannotDelegate = agents.get("coder").toBuilder().canSpawnSubagents(false).build();
        assertEquals(WebCommandResolver.Status.INVALID, browser.execute(intent, principal(cannotDelegate), config, agents).status());
        var noTask = agents.get("coder").toBuilder().permissionOverrides(Map.of("task", PermissionService.PermissionLevel.DENY)).build();
        assertEquals(WebCommandResolver.Status.INVALID, browser.execute(intent, principal(noTask), config, agents).status());
        var noRead = agents.get("coder").toBuilder().permissionOverrides(Map.of("read", PermissionService.PermissionLevel.DENY)).build();
        assertEquals(WebCommandResolver.Status.INVALID, browser.resolve("saved", project, principal(noRead)).status());
        assertEquals(0, calls.get());
    }

    @Test void workflowOpaqueRoutesAndCancellationFailClosed() throws Exception {
        var intent = intent();
        ToolContext workflow = principal(agents.get("coder"));
        var team = mock(WorkflowTeamEnforcement.class);
        org.mockito.Mockito.when(team.evaluateToolUse("read")).thenReturn(WorkflowTeamEnforcement.ToolDecision.allow());
        workflow.bindWorkflow(team);
        assertEquals(WebCommandResolver.Status.INVALID, browser.execute(intent, workflow, config, agents).status());
        ChatConfig opaque = config.copy();
        opaque.setChatMode("passthrough");
        assertEquals(WebCommandResolver.Status.INVALID, browser.execute(intent, context, opaque, agents).status());
        ChatConfig configuredWorkflow = config.copy();
        configuredWorkflow.setChatMode("workflow");
        assertFalse(WebDurableSubagents.nativeRoute(configuredWorkflow));
        ChatConfig serving = config.copy();
        serving.setProvider("kompile-local");
        assertFalse(WebDurableSubagents.nativeRoute(serving));
        service.metadataFor(ID).withRoute("opencode", "native").write(SubagentResumeMetadata.sidecarFor(conversations, ID), mapper);
        assertEquals(WebCommandResolver.Status.INVALID, browser.execute(intent, context, config, agents).status());
        context.abort();
        assertEquals(WebCommandResolver.Status.INVALID, browser.execute(intent, context, config, agents).status());
        assertEquals(0, calls.get());
    }

    @Test void structuredClaudeNativeRouteRemainsAvailableThroughTheSharedTool() throws Exception {
        ChatConfig nativeClaude = config.copy();
        nativeClaude.setProvider("anthropic");
        nativeClaude.setAuthenticationMethod("native");
        assertTrue(nativeClaude.isClaudeCliNative());
        assertTrue(WebDurableSubagents.nativeRoute(nativeClaude));
        service.metadataFor(ID).withRoute("anthropic", "native").write(SubagentResumeMetadata.sidecarFor(conversations, ID), mapper);
        var done = browser.execute(intent(), context, config, agents);
        assertEquals(WebCommandResolver.Status.COMPLETED, done.status(), done.text());
        assertEquals(1, calls.get());
    }

    @Test void toolFailureDetailsAreNotReflected() {
        tools.register(new SubagentResumeTool(mapper, service, (metadata, request, principal) -> {
            throw new IllegalStateException("credential=TOP-SECRET");
        }));
        var result = browser.execute(intent(), context, config, agents);
        assertEquals(WebCommandResolver.Status.INVALID, result.status());
        assertFalse(result.text().contains("TOP-SECRET"));
    }
}
