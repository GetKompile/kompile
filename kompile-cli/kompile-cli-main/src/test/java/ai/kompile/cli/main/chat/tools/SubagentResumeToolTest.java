package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.agent.*;
import ai.kompile.cli.main.chat.permission.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SubagentResumeToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir Path directory;

    private SubagentResumeService seed(String id, String name) throws Exception {
        var service = new SubagentResumeService(directory, MAPPER);
        service.writeMetadata(SubagentResumeMetadata.create(id, name, "removed-original-agent", "old-parent", null, "old-model")
                .withRoute("old-provider", "api-key"));
        Files.writeString(directory.resolve(id + ".ledger.jsonl"), "{\"v\":1}\n"
                + "{\"type\":\"USER\",\"role\":\"user\",\"content\":\"original task\"}\n"
                + "{\"type\":\"ASSISTANT\",\"role\":\"assistant\",\"content\":\"original answer\"}\n");
        return service;
    }

    private ToolContext context() {
        PermissionService permissions = new PermissionService();
        permissions.allowAll();
        return new ToolContext("new-parent", AgentConfig.builder("caller").canSpawnSubagents(true).build(),
                permissions, directory, new ToolRegistry(MAPPER));
    }

    private ObjectNode resume(String target) {
        return MAPPER.createObjectNode().put("action", "resume").put("session_id", target)
                .put("prompt", "follow up").put("background", false);
    }

    @Test void discoverAndInspectWithoutResuming() throws Exception {
        var service = seed("subagent-first", "saved-run");
        var tool = new SubagentResumeTool(MAPPER, service, (metadata, params, context) -> { fail("not a resume"); return null; });
        assertTrue(tool.execute(MAPPER.createObjectNode(), context()).getOutput().contains("saved-run"));
        var shown = tool.execute(MAPPER.createObjectNode().put("action", "show").put("session_id", "saved"), context());
        assertFalse(shown.isError());
        assertTrue(shown.getOutput().contains("original answer"));
        assertEquals("task", tool.permissionKey());
        assertEquals(McpToolAnnotations.DELEGATION, tool.mcpAnnotations());
        assertTrue(tool.isBackgroundable());
        assertTrue(tool.parameterSchema().path("properties").has("agent"));
        assertTrue(SubagentDelegationGuard.DELEGATION_TOOLS.contains(tool.id()));
        var discovery = new DynamicToolManager();
        discovery.register(tool.id(), tool.description(), tool.parameterSchema());
        discovery.setDynamicMode(true);
        assertTrue(discovery.activateGroup("delegation").contains(tool.id()));
    }

    @Test void differentNativeAgentAndRouteReachResumeNotFreshTask() throws Exception {
        var service = seed("subagent-native", "native-run");
        AtomicReference<AgentConfig> selected = new AtomicReference<>();
        AtomicReference<String> resumedId = new AtomicReference<>();
        SubagentRunner runner = new SubagentRunner() {
            public String runSubagent(AgentConfig agent, String prompt, ToolContext parent) { fail("must resume"); return null; }
            public String resumeSubagent(AgentConfig agent, String id, String prompt, ToolContext parent) {
                selected.set(agent); resumedId.set(id);
                assertEquals("new-parent", parent.getSessionId());
                return "continued";
            }
        };
        var tool = new SubagentResumeTool(MAPPER, service, SubagentResumeTool.chatExecutor(new AgentRegistry(), runner, null));
        var params = resume("native-run").put("agent_type", "general").put("provider", "new-provider")
                .put("model", "new-model").put("authentication_method", "native");
        var result = tool.execute(params, context());
        assertFalse(result.isError(), result.getOutput());
        assertEquals("subagent-native", resumedId.get());
        assertEquals("new-provider", selected.get().getProviderOverride());
        assertEquals("new-model", selected.get().getModelOverride());
        assertEquals("native", selected.get().getAuthenticationMethodOverride());
        assertFalse(selected.get().canSpawnSubagents());
        assertEquals("subagent-native", result.getMetadata().get("sessionId"));
    }

    @Test void deletedOriginalAgentDoesNotPreventChoosingNewOne() throws Exception {
        var service = seed("subagent-deleted", "deleted");
        AtomicReference<AgentConfig> selected = new AtomicReference<>();
        SubagentRunner runner = new SubagentRunner() {
            public String runSubagent(AgentConfig agent, String prompt, ToolContext context) { throw new AssertionError(); }
            public String resumeSubagent(AgentConfig agent, String id, String prompt, ToolContext parent) { selected.set(agent); return "ok"; }
        };
        var tool = new SubagentResumeTool(MAPPER, service, SubagentResumeTool.chatExecutor(new AgentRegistry(), runner, null));
        assertFalse(tool.execute(resume("deleted"), context()).isError());
        assertEquals("general", selected.get().getName());
    }

    @Test void supervisedChildrenCannotUseResumeAsRecursiveDelegation() throws Exception {
        var tool = new SubagentResumeTool(MAPPER, seed("subagent-child", "child"), null);
        var child = context();
        child.markSupervisedChild();
        assertThrows(ToolExecutionException.class, () -> tool.execute(resume("child"), child));
        assertTrue(SubagentDelegationGuard.blocksWithDepth("subagent_resume", "1"));
        assertTrue(SubagentDelegationGuard.CLAUDE_DISALLOWED.contains("mcp__kompile__subagent_resume"));
    }

    @Test void lockedUnknownAmbiguousAndInvalidRequestsNeverExecute() throws Exception {
        var service = seed("subagent-locked", "same-one");
        seed("subagent-other", "same-two");
        var tool = new SubagentResumeTool(MAPPER, service, (metadata, params, context) -> { fail("must not execute"); return null; });
        assertNull(service.tryLock("subagent-locked"));
        try { assertTrue(tool.execute(resume("same-one"), context()).getOutput().contains("live")); }
        finally { service.unlock("subagent-locked"); }
        assertTrue(tool.execute(resume("unknown"), context()).isError());
        assertTrue(tool.execute(resume("same"), context()).getOutput().contains("Ambiguous"));
        assertTrue(tool.execute(resume("../main"), context()).isError());
        assertTrue(tool.execute(resume("same-one").put("prompt", " "), context()).isError());
        assertTrue(tool.execute(resume("same-one").put("agent", 2), context()).isError());
        assertTrue(tool.execute(resume("same-one").put("action", "delete"), context()).isError());
    }

    @Test void cliHandoffCannotEscalateAFilteredNativeCaller() {
        var caller = context();
        var restricted = new ToolContext("read-only", AgentConfig.builder("reader")
                .enabledTools(java.util.Set.of("read", "grep", "task")).build(), caller.getPermissionService(), directory, null);
        assertThrows(ToolExecutionException.class, () -> SubagentResumeTool.checkCliPermissionCeiling(restricted));
    }

    @Test void serviceLockCoversEntirePortableContinuationAndErrorsDoNotAdvance() throws Exception {
        var service = seed("subagent-lock", "lease");
        var other = new SubagentResumeService(directory, MAPPER);
        assertThrows(IllegalStateException.class, () -> service.continueWith("subagent-lock", "failed", "parent", (history, prompt) -> {
            assertTrue(other.isLocked("subagent-lock"));
            assertNotNull(other.tryLock("subagent-lock"));
            throw new IllegalStateException("failed target");
        }));
        assertEquals(2, service.ledger("subagent-lock").size());
        assertFalse(service.isLocked("subagent-lock"));
        assertEquals("next answer", other.continueWith("subagent-lock", "next task", "adopter", (history, prompt) -> "next answer"));
        assertEquals(4, service.ledger("subagent-lock").size());
        assertEquals("adopter", service.metadataFor("subagent-lock").parentSession());
    }

    @Test void completedCliTranscriptBecomesPortableWithoutItsOriginalCli() throws Exception {
        String id = "subagent-codex-old";
        Files.writeString(directory.resolve(id + ".txt"), "──── Conversation: " + id + " ────\n"
                + "Started: now\nAgent: codex\nCWD: /project\n\n> original task\n< original answer\n");
        var service = new SubagentResumeService(directory, MAPPER);
        service.registerTranscript(id, "codex", "old-model", "parent");
        assertTrue(service.resolve(id).ok());
        assertEquals(2, service.ledger(id).size());
        service.registerTranscript(id, "codex", "old-model", "parent");
        assertEquals(2, service.ledger(id).size(), "registration is idempotent");
    }
}
