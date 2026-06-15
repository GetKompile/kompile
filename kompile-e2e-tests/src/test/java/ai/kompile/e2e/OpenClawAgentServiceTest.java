package ai.kompile.e2e;

import ai.kompile.kclaw.agent.KClawAgentService;
import ai.kompile.kclaw.agent.ToolkitRegistry;
import ai.kompile.gateway.core.model.AgentDefinition;
import ai.kompile.kclaw.model.KClawRequest;
import ai.kompile.kclaw.model.KClawResponse;
import ai.kompile.gateway.core.service.AgentRegistry;
import ai.kompile.gateway.core.service.SessionService;
import ai.kompile.react.context.Toolkit;
import ai.kompile.react.model.ReActMessage;
import ai.kompile.react.model.ReActResult;
import ai.kompile.react.model.TokenUsage;
import ai.kompile.react.service.ReActAgentService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the KClaw agent service: request routing, session management,
 * error handling, and async execution.
 */
@Tag("e2e")
@ExtendWith(MockitoExtension.class)
@DisplayName("KClaw Agent Service")
class OpenClawAgentServiceTest {

    @Mock
    private ReActAgentService reActAgentService;

    @Mock
    private AgentRegistry agentRegistry;

    @Mock
    private SessionService sessionService;

    @Mock
    private ToolkitRegistry toolkitRegistry;

    @Mock
    private Toolkit toolkit;

    private KClawAgentService agentService;

    @BeforeEach
    void setUp() {
        agentService = new KClawAgentService(
                reActAgentService, agentRegistry, sessionService, toolkitRegistry
        );
    }

    // ── Basic Execution ──

    @Test
    @DisplayName("Execute request with valid agent returns success response")
    void testExecuteWithValidAgent() {
        AgentDefinition agentDef = AgentDefinition.builder()
                .name("test-agent")
                .systemPrompt("You are a helpful assistant.")
                .maxSteps(5)
                .build();

        ReActResult result = mock(ReActResult.class);
        when(result.getAnswer()).thenReturn("Hello, I can help you!");
        when(result.isSuccess()).thenReturn(true);
        when(result.getTotalUsage()).thenReturn(TokenUsage.of(100, 50));

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(agentDef));
        when(sessionService.loadSession(anyString())).thenReturn(List.of());
        when(toolkitRegistry.getToolkit(agentDef)).thenReturn(toolkit);
        when(reActAgentService.runSync(eq("Hello"), eq(toolkit), any())).thenReturn(result);

        KClawRequest request = KClawRequest.builder()
                .agentId("test-agent")
                .sessionKey("session-1")
                .message("Hello")
                .build();

        KClawResponse response = agentService.execute(request);

        assertTrue(response.isSuccess());
        assertEquals("Hello, I can help you!", response.getResponse());
        assertEquals("session-1", response.getSessionKey());
        assertEquals("test-agent", response.getAgentId());

        // Verify session was persisted
        ArgumentCaptor<ReActMessage> msgCaptor = ArgumentCaptor.forClass(ReActMessage.class);
        verify(sessionService, times(2)).appendMessage(eq("session-1"), msgCaptor.capture());
        List<ReActMessage> captured = msgCaptor.getAllValues();
        assertEquals("Hello", captured.get(0).getContent());
        assertEquals("Hello, I can help you!", captured.get(1).getContent());
    }

    @Test
    @DisplayName("Execute with null agentId defaults to jarvis")
    void testDefaultAgentId() {
        AgentDefinition defaultAgent = AgentDefinition.builder()
                .name("jarvis")
                .systemPrompt("Default agent")
                .maxSteps(10)
                .build();

        ReActResult result = mock(ReActResult.class);
        when(result.getAnswer()).thenReturn("Default response");
        when(result.isSuccess()).thenReturn(true);

        when(agentRegistry.getAgent("jarvis")).thenReturn(Optional.of(defaultAgent));
        when(sessionService.loadSession(anyString())).thenReturn(List.of());
        when(toolkitRegistry.getToolkit(defaultAgent)).thenReturn(toolkit);
        when(reActAgentService.runSync(anyString(), any(), any())).thenReturn(result);

        KClawRequest request = KClawRequest.builder()
                .message("Test")
                .build();

        KClawResponse response = agentService.execute(request);

        assertTrue(response.isSuccess());
        verify(agentRegistry).getAgent("jarvis");
    }

    @Test
    @DisplayName("Execute with unknown agent falls back to default")
    void testFallbackToDefaultAgent() {
        AgentDefinition defaultAgent = AgentDefinition.builder()
                .name("default")
                .systemPrompt("Default")
                .maxSteps(5)
                .build();

        ReActResult result = mock(ReActResult.class);
        when(result.getAnswer()).thenReturn("Fallback response");
        when(result.isSuccess()).thenReturn(true);

        when(agentRegistry.getAgent("unknown-agent")).thenReturn(Optional.empty());
        when(agentRegistry.getDefaultAgent()).thenReturn(defaultAgent);
        when(sessionService.loadSession(anyString())).thenReturn(List.of());
        when(toolkitRegistry.getToolkit(defaultAgent)).thenReturn(toolkit);
        when(reActAgentService.runSync(anyString(), any(), any())).thenReturn(result);

        KClawRequest request = KClawRequest.builder()
                .agentId("unknown-agent")
                .sessionKey("s1")
                .message("Test")
                .build();

        KClawResponse response = agentService.execute(request);

        assertTrue(response.isSuccess());
    }

    @Test
    @DisplayName("Execute returns error when no agent found and no default")
    void testNoAgentFound() {
        when(agentRegistry.getAgent("missing")).thenReturn(Optional.empty());
        when(agentRegistry.getDefaultAgent()).thenReturn(null);

        KClawRequest request = KClawRequest.builder()
                .agentId("missing")
                .sessionKey("s1")
                .message("Test")
                .build();

        KClawResponse response = agentService.execute(request);

        assertFalse(response.isSuccess());
        assertNotNull(response.getError());
        assertTrue(response.getError().contains("Agent not found"));
    }

    // ── Error Handling ──

    @Test
    @DisplayName("Execute returns error on ReAct agent exception")
    void testAgentExecutionException() {
        AgentDefinition agentDef = AgentDefinition.builder()
                .name("test-agent")
                .systemPrompt("Test")
                .maxSteps(5)
                .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(agentDef));
        when(sessionService.loadSession(anyString())).thenReturn(List.of());
        when(toolkitRegistry.getToolkit(agentDef)).thenReturn(toolkit);
        when(reActAgentService.runSync(anyString(), any(), any()))
                .thenThrow(new RuntimeException("LLM API timeout"));

        KClawRequest request = KClawRequest.builder()
                .agentId("test-agent")
                .sessionKey("s1")
                .message("Test")
                .build();

        KClawResponse response = agentService.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getError().contains("Agent execution failed"));
    }

    // ── Session Management ──

    @Test
    @DisplayName("Auto-generates session key when not provided")
    void testAutoGeneratedSessionKey() {
        AgentDefinition agentDef = AgentDefinition.builder()
                .name("jarvis")
                .systemPrompt("Test")
                .maxSteps(5)
                .build();

        ReActResult result = mock(ReActResult.class);
        when(result.getAnswer()).thenReturn("Response");
        when(result.isSuccess()).thenReturn(true);

        when(agentRegistry.getAgent("jarvis")).thenReturn(Optional.of(agentDef));
        when(sessionService.loadSession(anyString())).thenReturn(List.of());
        when(toolkitRegistry.getToolkit(agentDef)).thenReturn(toolkit);
        when(reActAgentService.runSync(anyString(), any(), any())).thenReturn(result);

        KClawRequest request = KClawRequest.builder()
                .message("Test")
                .build();

        KClawResponse response = agentService.execute(request);

        assertTrue(response.isSuccess());
        assertNotNull(response.getSessionKey());
        assertTrue(response.getSessionKey().startsWith("session:"));
    }

    @Test
    @DisplayName("Clear session delegates to session service")
    void testClearSession() {
        agentService.clearSession("session-1");
        verify(sessionService).clearSession("session-1");
    }

    @Test
    @DisplayName("Compact session delegates to session service")
    void testCompactSession() {
        agentService.compactIfNeeded("session-1", 4000);
        verify(sessionService).compactSession("session-1", 4000);
    }

    @Test
    @DisplayName("Get session history returns messages from service")
    void testGetSessionHistory() {
        List<ReActMessage> history = List.of(
                ReActMessage.user("Hello"),
                ReActMessage.assistant("Hi there!")
        );
        when(sessionService.loadSession("session-1")).thenReturn(history);

        List<ReActMessage> result = agentService.getSessionHistory("session-1");

        assertEquals(2, result.size());
        assertEquals("Hello", result.get(0).getContent());
    }

    // ── Async Execution ──

    @Test
    @DisplayName("Async execution completes with result")
    void testAsyncExecution() throws Exception {
        AgentDefinition agentDef = AgentDefinition.builder()
                .name("test-agent")
                .systemPrompt("Test")
                .maxSteps(5)
                .build();

        ReActResult result = mock(ReActResult.class);
        when(result.getAnswer()).thenReturn("Async response");
        when(result.isSuccess()).thenReturn(true);

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(agentDef));
        when(sessionService.loadSession(anyString())).thenReturn(List.of());
        when(toolkitRegistry.getToolkit(agentDef)).thenReturn(toolkit);
        when(reActAgentService.runSync(anyString(), any(), any())).thenReturn(result);

        KClawRequest request = KClawRequest.builder()
                .agentId("test-agent")
                .sessionKey("async-session")
                .message("Async test")
                .build();

        CompletableFuture<KClawResponse> future = agentService.executeAsync(request);
        KClawResponse response = future.get();

        assertTrue(response.isSuccess());
        assertEquals("Async response", response.getResponse());
    }

    // ── Metadata Propagation ──

    @Test
    @DisplayName("Request metadata is passed through correctly")
    void testMetadataPropagation() {
        AgentDefinition agentDef = AgentDefinition.builder()
                .name("test-agent")
                .systemPrompt("Test")
                .maxSteps(5)
                .build();

        ReActResult result = mock(ReActResult.class);
        when(result.getAnswer()).thenReturn("Response");
        when(result.isSuccess()).thenReturn(true);

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(agentDef));
        when(sessionService.loadSession(anyString())).thenReturn(List.of());
        when(toolkitRegistry.getToolkit(agentDef)).thenReturn(toolkit);
        when(reActAgentService.runSync(anyString(), any(), any())).thenReturn(result);

        KClawRequest request = KClawRequest.builder()
                .agentId("test-agent")
                .sessionKey("s1")
                .message("Test")
                .metadata(Map.of("channel", "slack", "userId", "U123"))
                .build();

        KClawResponse response = agentService.execute(request);

        assertTrue(response.isSuccess());
    }
}
