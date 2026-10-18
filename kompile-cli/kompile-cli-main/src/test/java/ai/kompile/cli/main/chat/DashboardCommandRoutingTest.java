package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DashboardCommandRoutingTest {
    @Test void bareDashboardUsesRegisteredInsightsWithoutModelOrControlRefresh() throws Exception {
        ChatRepl repl = mock(ChatRepl.class);
        AgenticChatLoop loop = mock(AgenticChatLoop.class);
        PermissionService permissions = mock(PermissionService.class);
        TerminalRenderer renderer = renderer();
        RecordingInsights source = new RecordingInsights();
        ChatCommandRouter router = router(repl, loop, permissions, renderer, source);

        assertTrue(router.handleSlashCommand("/dashboard"));

        assertEquals("overview", source.params.path("topic").asText());
        assertEquals("this session", source.params.path("question").asText());
        assertEquals("Chat-AbC", source.context.getSessionId());
        assertEquals(Path.of("."), source.context.getWorkingDirectory());
        verify(permissions).check(any(), eq("read"), eq("Read session insights"));
        verify(loop).getCurrentAgentConfig();
        verifyNoMoreInteractions(loop); // No model turn.
        verify(repl, never()).handleDashboardCommand(anyString());
        verify(renderer, never()).renderToolCallComplete(anyString(), anyString(), any());
    }

    @Test void reportPermissionDenialDoesNotReadSourcesOrInvokeModel() {
        ChatRepl repl = mock(ChatRepl.class);
        AgenticChatLoop loop = mock(AgenticChatLoop.class);
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.check(any(), eq("read"), anyString())).thenReturn(PermissionService.PermissionResult.DENIED);
        RecordingInsights source = new RecordingInsights();
        ChatCommandRouter router = router(repl, loop, permissions, renderer(), source);

        assertTrue(router.handleSlashCommand("/dashboard-judge"));
        assertNull(source.params, "the permission gate precedes source access");
        verify(repl, never()).onDirectToolComplete(anyString(), anyString(), any());
        verify(loop).getCurrentAgentConfig();
        verifyNoMoreInteractions(loop);
    }

    @Test void explicitControlsStayWithLifecycleOwnerWithoutExecutingInsights() {
        ChatRepl repl = mock(ChatRepl.class);
        AgenticChatLoop loop = mock(AgenticChatLoop.class);
        RecordingInsights source = new RecordingInsights();
        ChatCommandRouter router = router(repl, loop, mock(PermissionService.class), renderer(), source);
        assertTrue(router.handleSlashCommand("/dashboard reload"));
        verify(repl).handleDashboardCommand("reload");
        assertNull(source.params);
        verifyNoInteractions(loop);
    }

    @Test void timelineAndDashboardAliasShareTheReplLifecycleOwnerWithoutModelCalls() {
        ChatRepl repl = mock(ChatRepl.class);
        AgenticChatLoop loop = mock(AgenticChatLoop.class);
        RecordingInsights source = new RecordingInsights();
        ChatCommandRouter router = router(repl, loop, mock(PermissionService.class), renderer(), source);
        assertTrue(router.handleSlashCommand("/timeline"));
        assertTrue(router.handleSlashCommand("/timeline session:Chat-AbC run:Run-X limit:10"));
        assertTrue(router.handleSlashCommand("/dashboard-timeline next"));
        assertTrue(router.handleSlashCommand("/timeline inspect:Event-AbC"));
        assertTrue(router.handleSlashCommand("/dashboard-timeline follow"));
        assertTrue(router.handleSlashCommand("/timeline stop"));
        verify(repl).printDashboardTimeline("");
        verify(repl).printDashboardTimeline("session:Chat-AbC run:Run-X limit:10");
        verify(repl).printDashboardTimeline("next");
        verify(repl).printDashboardTimeline("inspect:Event-AbC");
        verify(repl).printDashboardTimeline("follow");
        verify(repl).printDashboardTimeline("stop");
        assertNull(source.params);
        verifyNoInteractions(loop);
        router.closeDashboardReports();
    }

    private static TerminalRenderer renderer() {
        TerminalRenderer renderer = mock(TerminalRenderer.class);
        when(renderer.startSpinner(anyString())).thenReturn(mock(TerminalRenderer.SpinnerHandle.class));
        return renderer;
    }

    private static ChatCommandRouter router(ChatRepl repl, AgenticChatLoop loop, PermissionService permissions,
                                            TerminalRenderer renderer, RecordingInsights source) {
        when(repl.getWorkingDirectory()).thenReturn(Path.of("."));
        when(loop.getCurrentAgentConfig()).thenReturn(mock(AgentConfig.class));
        var mapper = JsonUtils.standardMapper();
        ToolRegistry registry = new ToolRegistry(mapper);
        registry.register(source);
        return new ChatCommandRouter(repl, null, null, null, null, mapper, "Chat-AbC", true,
                null, null, renderer, null, null, null, null, registry, permissions, loop,
                null, null, null, List.of(), null);
    }

    private static final class RecordingInsights extends InsightsTool {
        JsonNode params;
        ToolContext context;
        @Override public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
            context.checkPermission(permissionKey(), "Read session insights");
            this.params = params;
            this.context = context;
            return ToolResult.success("Bounded overview");
        }
    }
}
