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

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
    void unauthenticatedRemoteHarnessTurnAndCancellationAreForbidden() {
        AgentChatRequest request = new AgentChatRequest();
        request.setMessage("run a tool");
        request.setAgentName("coder");
        MockHttpServletRequest remote = new MockHttpServletRequest();
        remote.setRemoteAddr("203.0.113.10");
        remote.setServerName("chat.example.test");

        ResponseStatusException turn = assertThrows(ResponseStatusException.class,
                () -> controller.streamChat(request, remote));
        ResponseStatusException cancel = assertThrows(ResponseStatusException.class,
                () -> controller.cancelChat("harness-remote", remote));
        ResponseStatusException capabilities = assertThrows(ResponseStatusException.class,
                () -> controller.capabilities(null, true, remote));
        ResponseStatusException context = assertThrows(ResponseStatusException.class,
                () -> controller.contextBudget("coder", null, remote));

        assertEquals(HttpStatus.FORBIDDEN, turn.getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, cancel.getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, capabilities.getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, context.getStatusCode());
        verify(harness, never()).executeChat(any(), any());
        verify(harness, never()).cancel(any());
        verify(harness, never()).capabilities(any(), anyBoolean());
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
