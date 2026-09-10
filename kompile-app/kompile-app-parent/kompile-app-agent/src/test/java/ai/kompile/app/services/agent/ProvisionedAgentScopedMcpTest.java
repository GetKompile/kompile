package ai.kompile.app.services.agent;

import ai.kompile.app.services.mcp.ScopedMcpCapabilityService;
import ai.kompile.app.web.dto.AgentChatRequest;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

class ProvisionedAgentScopedMcpTest {

    private static final String FIRST = "01234567-89ab-cdef-0123-456789abcdef";
    private static final String SECOND = "11111111-89ab-cdef-0123-456789abcdef";
    private static final String GRAPH_REVISION = "0".repeat(64);

    @Test
    void bindsEachConcurrentToolClosureToItsPreparedTurnWithoutAuthoritySelectors()
            throws Exception {
        CapturingRuntime runtime = new CapturingRuntime();
        AgentChatService service = service(runtime);
        AgentChatService.ProvisionedTurn first = service.prepareProvisionedRequest(
                request(FIRST, "aaaaaaaa-2222-3333-4444-555555555555"), "claude-cli");
        AgentChatService.ProvisionedTurn second = service.prepareProvisionedRequest(
                request(SECOND, "bbbbbbbb-2222-3333-4444-555555555555"), "claude-cli");

        ScopedMcpCapabilityService.ScopedTool firstTool = service.scopedPrivateGraphTool(first);
        ScopedMcpCapabilityService.ScopedTool secondTool = service.scopedPrivateGraphTool(second);
        @SuppressWarnings("unchecked")
        Set<String> fields = ((Map<String, Object>) firstTool.inputSchema().get("properties")).keySet();
        assertFalse(fields.contains("ownerId"));
        assertFalse(fields.contains("agentId"));
        assertFalse(fields.contains("provisionedAgentId"));
        assertFalse(fields.contains("path"));
        assertFalse(fields.contains("factSheetId"));
        assertFalse(fields.contains("knowledgeBase"));

        var executor = Executors.newFixedThreadPool(2);
        try {
            var firstResult = executor.submit(() -> firstTool.executor().apply(Map.of("action", "read")));
            var secondResult = executor.submit(() -> secondTool.executor().apply(Map.of("action", "read")));
            assertEquals(FIRST, firstResult.get());
            assertEquals(SECOND, secondResult.get());
        } finally {
            executor.shutdownNow();
        }

        assertEquals(Set.of(FIRST, SECOND), Set.copyOf(runtime.executedAgentIds));
    }

    private static AgentChatRequest request(String agentId, String turnId) {
        AgentChatRequest request = new AgentChatRequest();
        request.setProvisionedAgentId(agentId);
        request.setExternalConversationKey("web:scope");
        request.setTurnId(turnId);
        request.setMessage("question");
        request.setAgentName("claude-cli");
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

    private static final class CapturingRuntime implements ProvisionedAgentRuntime {
        private final AtomicLong sequence = new AtomicLong();
        private final List<String> executedAgentIds =
                java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public RuntimeContext prepare(PrepareRequest request) {
            return new RuntimeContext(
                    request.provisionedAgentId(),
                    request.externalConversationKey(),
                    "",
                    GRAPH_REVISION,
                    List.of(),
                    0,
                    false,
                    new ToolDescriptor(
                            "agent_private_graph",
                            "bound private graph",
                            Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "action", Map.of("type", "string"),
                                            "query", Map.of("type", "string")),
                                    "required", List.of("action"),
                                    "additionalProperties", false),
                            true));
        }

        @Override
        public CanonicalEvent append(AppendEventsRequest request) {
            EventDraft event = request.event();
            return new CanonicalEvent(
                    sequence.incrementAndGet(), Instant.EPOCH, event.kind(), event.role(),
                    event.content(), event.metadata(), event.idempotencyKey());
        }

        @Override
        public ToolExecutionResult executeTool(ToolExecutionRequest request) {
            executedAgentIds.add(request.provisionedAgentId());
            return new ToolExecutionResult(request.provisionedAgentId(), GRAPH_REVISION);
        }
    }
}
