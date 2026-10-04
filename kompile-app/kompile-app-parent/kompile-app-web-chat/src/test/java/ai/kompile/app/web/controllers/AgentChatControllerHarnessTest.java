package ai.kompile.app.web.controllers;

import ai.kompile.app.services.agent.AgentChatService;
import ai.kompile.app.services.agent.AgentRegistryService;
import ai.kompile.app.services.agent.ChatHarnessClient;
import ai.kompile.app.web.dto.AgentChatCompactRequest;
import ai.kompile.app.web.dto.AgentChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentChatControllerHarnessTest {

    private AgentChatService legacyChat;
    private ChatHarnessClient harness;
    private AgentChatController controller;

    @BeforeEach
    void setUp() {
        legacyChat = mock(AgentChatService.class);
        harness = mock(ChatHarnessClient.class);
        controller = new AgentChatController(
                legacyChat, mock(AgentRegistryService.class), null, harness);
    }

    @Test
    void ordinaryBrowserTurnUsesKompileCliHarness() {
        AgentChatRequest request = new AgentChatRequest();
        request.setMessage("crawl this project");
        request.setAgentName("crawler");
        request.setSessionId("browser-1");
        when(harness.executeChat(eq(request), any(SseEmitter.class)))
                .thenReturn("harness-run");

        SseEmitter emitter = controller.streamChat(request, loopbackRequest());

        verify(harness).executeChat(same(request), same(emitter));
        verify(legacyChat, never()).executeChat(any(), any());
    }

    @Test
    void cancellationAndCapabilitiesRouteToHarness() {
        when(harness.cancel("harness-123")).thenReturn(true);
        var capabilities = new ObjectMapper().createObjectNode()
                .put("engine", "kompile-cli-main")
                .put("available", true);
        when(harness.capabilities("/project", true)).thenReturn(capabilities);

        Map<String, Object> cancelled = controller.cancelChat(
                "harness-123", loopbackRequest()).getBody();
        var response = controller.capabilities("/project", true, loopbackRequest());

        assertEquals(Boolean.TRUE, cancelled.get("cancelled"));
        assertSame(capabilities, response.getBody());
        verify(legacyChat, never()).cancelProcess(any());
    }

    @Test
    void ordinaryLanHarnessUsesTheSameCapabilitiesTurnsAndControlsAsLocalhost() {
        AgentChatRequest request = new AgentChatRequest();
        request.setMessage("run a tool");
        request.setAgentName("coder");
        MockHttpServletRequest remote = new MockHttpServletRequest();
        remote.setRemoteAddr("203.0.113.10");
        remote.setServerName("chat.example.test");

        var capability = new ObjectMapper().createObjectNode().put("available", true);
        when(harness.capabilities(null, true)).thenReturn(capability);
        when(harness.cancel("harness-remote")).thenReturn(true);
        when(harness.contextBudget("coder", null)).thenReturn(Map.of("source", "kompile-cli-main"));

        var emitter = controller.streamChat(request, remote);
        assertEquals(true, controller.cancelChat("harness-remote", remote).getBody().get("cancelled"));
        assertSame(capability, controller.capabilities(null, true, remote).getBody());
        assertEquals("kompile-cli-main", controller.contextBudget("coder", null, remote).getBody().get("source"));
        verify(harness).executeChat(same(request), same(emitter));
        verify(legacyChat, never()).executeChat(any(), any());
    }

    @Test
    void provisionedAgentStillRequiresIntegrationCredentials() {
        var request = new AgentChatRequest();
        request.setProvisionedAgentId("managed-agent");
        request.setMessage("hello");
        var remote = new MockHttpServletRequest();
        remote.setRemoteAddr("192.168.1.10");
        remote.setServerName("chat.example.test");
        var denied = assertThrows(ResponseStatusException.class,
                () -> controller.streamChat(request, remote));
        assertEquals(HttpStatus.UNAUTHORIZED, denied.getStatusCode());
        verify(harness, never()).executeChat(any(), any());
        verify(legacyChat, never()).executeChat(any(), any());
    }

    @Test
    void contextAndCompactionAreHarnessOwned() {
        when(harness.contextBudget("coder", "/project")).thenReturn(Map.of(
                "agentName", "coder",
                "model", "test-model",
                "contextWindow", 8192,
                "maxOutputTokens", 1024,
                "inputBudgetTokens", 6144,
                "source", "kompile-cli-main",
                "compactTriggerRatio", 0.85));

        Map<String, Object> budget = controller.contextBudget(
                "coder", "/project", loopbackRequest()).getBody();
        Map<String, Object> compact = controller.compact(new AgentChatCompactRequest()).getBody();

        assertEquals("kompile-cli-main", budget.get("source"));
        assertEquals("kompile-cli-main", compact.get("managedBy"));
        verify(legacyChat, never()).compactHistory(any(), any(), any());
    }

    @Test
    void workflowGateApprovalAnswersWithTheHarnessOutcome() throws Exception {
        var mapper = new ObjectMapper();
        when(harness.approveWorkflowGate("browser-1", "/project", "review")).thenReturn(Map.of(
                "ok", true, "message", "Approved gate 'review' for workflow 'ship'.", "approved", List.of("review")));
        when(harness.approveWorkflowGate("browser-2", null, null)).thenReturn(Map.of(
                "ok", false, "message", "This session has no workflow team."));

        var approved = controller.approveWorkflowGate(mapper.readTree(
                "{\"sessionId\":\"browser-1\",\"workingDirectory\":\"/project\",\"gate\":\"review\"}"));
        var refused = controller.approveWorkflowGate(mapper.readTree("{\"sessionId\":\"browser-2\",\"gate\":null}"));

        assertEquals(HttpStatus.OK, approved.getStatusCode());
        assertEquals(List.of("review"), approved.getBody().get("approved"));
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode());
        assertEquals("This session has no workflow team.", refused.getBody().get("message"));
    }

    @Test
    void workflowGateApprovalRejectsMalformedRequestsAndNeedsTheHarness() throws Exception {
        var mapper = new ObjectMapper();
        for (String body : List.of("[]", "{\"sessionId\":7}", "{\"sessionId\":\"browser-1\",\"gate\":{}}")) {
            var rejected = controller.approveWorkflowGate(mapper.readTree(body));
            assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode(), body);
            assertEquals(false, rejected.getBody().get("ok"), body);
        }
        verify(harness, never()).approveWorkflowGate(any(), any(), any());

        when(harness.approveWorkflowGate("browser-1", null, "bad"))
                .thenThrow(new IllegalArgumentException("Invalid gate name"));
        var invalidGate = controller.approveWorkflowGate(
                mapper.readTree("{\"sessionId\":\"browser-1\",\"gate\":\"bad\"}"));
        assertEquals(HttpStatus.BAD_REQUEST, invalidGate.getStatusCode());
        assertEquals("Invalid gate name", invalidGate.getBody().get("message"));

        when(harness.approveWorkflowGate("browser-1", null, null))
                .thenThrow(new IllegalStateException("Workflow gate approval unavailable"));
        var unavailable = assertThrows(ResponseStatusException.class,
                () -> controller.approveWorkflowGate(mapper.readTree("{\"sessionId\":\"browser-1\"}")));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getStatusCode());
        var withoutHarness = new AgentChatController(legacyChat, mock(AgentRegistryService.class));
        var missing = assertThrows(ResponseStatusException.class,
                () -> withoutHarness.approveWorkflowGate(mapper.readTree("{\"sessionId\":\"browser-1\"}")));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, missing.getStatusCode());
    }

    @Test
    void sessionInsightsAreTheHarnessReadAndNeedAHarness() {
        var rows = new ObjectMapper().createObjectNode().put("menu", "insights").put("available", true);
        rows.putArray("lines").add("Judge: no verdicts yet");
        when(harness.insightsSnapshot("browser-1", "/project")).thenReturn(rows);

        var response = controller.sessionInsights("browser-1", "/project");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(rows, response.getBody());
        verify(legacyChat, never()).executeChat(any(), any());

        // A client without the read (the interface default) and no harness at all are both 503.
        when(harness.insightsSnapshot("browser-2", null)).thenCallRealMethod();
        var unsupported = assertThrows(ResponseStatusException.class,
                () -> controller.sessionInsights("browser-2", null));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unsupported.getStatusCode());
        assertEquals("Session insights unavailable", unsupported.getReason());
        var withoutHarness = new AgentChatController(legacyChat, mock(AgentRegistryService.class));
        var missing = assertThrows(ResponseStatusException.class,
                () -> withoutHarness.sessionInsights("browser-1", null));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, missing.getStatusCode());
    }

    @Test
    void harnessSseTimeoutOutlivesTheBoundedCliTurn() {
        assertEquals(TimeUnit.SECONDS.toMillis(330), AgentChatController.harnessSseTimeout(0));
        assertEquals(TimeUnit.SECONDS.toMillis(90), AgentChatController.harnessSseTimeout(60));
        assertEquals(TimeUnit.SECONDS.toMillis(1_830), AgentChatController.harnessSseTimeout(9_999));
    }

    private static MockHttpServletRequest loopbackRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.setServerName("localhost");
        return request;
    }
}
