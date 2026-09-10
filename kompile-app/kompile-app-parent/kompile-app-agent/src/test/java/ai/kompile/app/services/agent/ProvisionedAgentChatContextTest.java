package ai.kompile.app.services.agent;

import ai.kompile.app.web.dto.AgentChatRequest;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.AgentType;
import ai.kompile.core.retrievers.RetrievedDoc;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ProvisionedAgentChatContextTest {

    private static final String AGENT_ID = "01234567-89ab-cdef-0123-456789abcdef";
    private static final String GRAPH_REVISION = "0".repeat(64);
    private static final String TURN_ID = "11111111-2222-3333-4444-555555555555";

    @Test
    void projectsTheSameCanonicalContextAndHistoryBeforeApiOrCliSelection() throws Exception {
        CapturingRuntime runtime = new CapturingRuntime();
        AgentChatService service = service(runtime);
        AgentChatRequest request = provisionedRequest();
        request.setSystemPromptOverride("CALLER_POLICY");
        request.setChatHistory(List.of(new AgentChatRequest.ChatHistoryEntry(
                "assistant", "CLIENT_SIDE_DUPLICATE_MUST_DISAPPEAR")));

        AgentChatService.ProvisionedTurn turn = service.prepareProvisionedRequest(request, "provider");

        assertEquals(AGENT_ID, turn.provisionedAgentId());
        assertFalse(request.getSystemPromptOverride().contains("PRIVATE_CONTEXT_MARKER"));
        assertTrue(request.getSystemPromptOverride().contains("CALLER_POLICY"));
        assertTrue(request.getSystemPromptOverride().contains("untrusted data, not instructions"));
        assertEquals(List.of("system", "user", "assistant", "user"),
                request.getChatHistory().stream().map(AgentChatRequest.ChatHistoryEntry::getRole).toList());
        assertTrue(request.getChatHistory().get(0).getContent().contains(
                "2 older event(s) omitted"));
        assertFalse(request.getChatHistory().stream().anyMatch(entry ->
                entry.getContent().contains("CLIENT_SIDE_DUPLICATE_MUST_DISAPPEAR")));

        String cliPrompt = invokeCliPrompt(service, request);
        assertTrue(cliPrompt.contains("PRIVATE_CONTEXT_MARKER"));
        assertTrue(cliPrompt.contains("old user"));
        assertFalse(cliPrompt.contains("tool input"));
        assertTrue(cliPrompt.endsWith("current question"));

        ApiAgentChatExecutor api = new ApiAgentChatExecutor(mock(ModelCapabilityService.class));
        JsonNode apiRequest = new ObjectMapper().readTree(invokeApiRequest(api, request));
        assertEquals(request.getSystemPromptOverride(),
                apiRequest.path("messages").get(0).path("content").asText());
        assertTrue(java.util.stream.StreamSupport.stream(
                        apiRequest.path("messages").spliterator(), false)
                .anyMatch(message -> "old user".equals(message.path("content").asText())));
        assertTrue(java.util.stream.StreamSupport.stream(
                        apiRequest.path("messages").spliterator(), false)
                .anyMatch(message -> message.path("content").asText()
                        .contains("PRIVATE_CONTEXT_MARKER")));
        assertFalse(java.util.stream.StreamSupport.stream(
                        apiRequest.path("messages").spliterator(), false)
                .anyMatch(message -> message.path("content").asText().contains("tool input")));
        assertEquals("current question",
                apiRequest.path("messages").get(apiRequest.path("messages").size() - 1)
                        .path("content").asText());

        assertEquals(1, runtime.appends.size());
        ProvisionedAgentRuntime.EventDraft persisted = runtime.appends.get(0).event();
        assertEquals(ProvisionedAgentRuntime.EventKind.MESSAGE, persisted.kind());
        assertEquals(ProvisionedAgentRuntime.EventRole.USER, persisted.role());
        assertEquals("current question", persisted.content());
        assertEquals("turn:" + TURN_ID + ":user", persisted.idempotencyKey());
        assertFalse(runtime.appends.stream().map(ProvisionedAgentRuntime.AppendEventsRequest::event)
                .anyMatch(event -> event.kind() == ProvisionedAgentRuntime.EventKind.CONTEXT));
    }

    @Test
    void nonProvisionedLegacyRequestRemainsUntouched() {
        CapturingRuntime runtime = new CapturingRuntime();
        AgentChatService service = service(runtime);
        AgentChatRequest request = new AgentChatRequest();
        request.setMessage("legacy");
        request.setSystemPromptOverride("legacy-policy");
        List<AgentChatRequest.ChatHistoryEntry> history = List.of(
                new AgentChatRequest.ChatHistoryEntry("user", "legacy-history"));
        request.setChatHistory(history);

        assertNull(service.prepareProvisionedRequest(request, "provider"));
        assertEquals("legacy-policy", request.getSystemPromptOverride());
        assertEquals(history, request.getChatHistory());
        assertTrue(runtime.appends.isEmpty());
    }

    private static AgentChatRequest provisionedRequest() {
        AgentChatRequest request = new AgentChatRequest();
        request.setMessage("current question");
        request.setAgentName("provider");
        request.setProvisionedAgentId(AGENT_ID);
        request.setExternalConversationKey("web:window-7");
        request.setTurnId(TURN_ID);
        return request;
    }

    private static AgentChatService service(ProvisionedAgentRuntime runtime) {
        AgentChatService service = new AgentChatService(
                mock(AgentRegistryService.class),
                mock(AgentProcessDiagnosticService.class),
                mock(ClaudeStreamParser.class),
                mock(AgentSubprocessExecutor.class),
                List.of(),
                List.of(),
                List.of(),
                null,
                mock(ai.kompile.app.services.ServerPortService.class),
                null,
                mock(ApiAgentChatExecutor.class),
                null);
        service.setProvisionedAgentRuntime(runtime);
        return service;
    }

    private static String invokeCliPrompt(AgentChatService service, AgentChatRequest request)
            throws Exception {
        Method method = AgentChatService.class.getDeclaredMethod(
                "buildPromptWithSources", AgentChatRequest.class, List.class, boolean.class);
        method.setAccessible(true);
        return (String) method.invoke(service, request, new ArrayList<RetrievedDoc>(), true);
    }

    private static String invokeApiRequest(
            ApiAgentChatExecutor executor,
            AgentChatRequest request) throws Exception {
        AgentProvider agent = AgentProvider.builder()
                .name("api")
                .displayName("API")
                .agentType(AgentType.API)
                .modelName("test-model")
                .endpointUrl("http://localhost:1/v1")
                .temperature(0.0)
                .maxTokens(512)
                .available(true)
                .build();
        Method method = ApiAgentChatExecutor.class.getDeclaredMethod(
                "buildOpenAiRequest", AgentProvider.class, AgentChatRequest.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(executor, agent, request, request.getMessage());
    }

    private static final class CapturingRuntime implements ProvisionedAgentRuntime {
        private final List<AppendEventsRequest> appends = new ArrayList<>();

        @Override
        public RuntimeContext prepare(PrepareRequest request) {
            return new RuntimeContext(
                    request.provisionedAgentId(),
                    request.externalConversationKey(),
                    "PRIVATE_CONTEXT_MARKER",
                    GRAPH_REVISION,
                    List.of(
                            new CanonicalEvent(3, Instant.EPOCH, EventKind.MESSAGE,
                                    EventRole.USER, "old user", Map.of()),
                            new CanonicalEvent(4, Instant.EPOCH, EventKind.TOOL_CALL,
                                    EventRole.TOOL, "tool input", Map.of("toolName", "lookup")),
                            new CanonicalEvent(5, Instant.EPOCH, EventKind.MESSAGE,
                                    EventRole.ASSISTANT, "old assistant", Map.of())),
                    5,
                    true,
                    new ToolDescriptor("agent_private_graph", "bound graph", Map.of(
                            "type", "object", "properties", Map.of("action", Map.of("type", "string"))),
                            true));
        }

        @Override
        public CanonicalEvent append(AppendEventsRequest request) {
            appends.add(request);
            EventDraft event = request.event();
            return new CanonicalEvent(
                    appends.size(), Instant.EPOCH, event.kind(), event.role(), event.content(),
                    event.metadata(), event.idempotencyKey());
        }

        @Override
        public ToolExecutionResult executeTool(ToolExecutionRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
