/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.e2e;

import ai.kompile.app.services.agent.AgentChatService;
import ai.kompile.app.services.agent.AgentProcessDiagnosticService;
import ai.kompile.app.services.agent.AgentRegistryService;
import ai.kompile.app.services.agent.ClaudeStreamParser;
import ai.kompile.app.tools.AgentDelegationTool;
import ai.kompile.app.web.dto.AgentChatRequest;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.AgentType;
import ai.kompile.core.embeddings.NoOpVectorStoreImpl;
import ai.kompile.core.retrievers.NoOpDocumentRetrieverImpl;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AgentDelegationTool and AgentChatService.executeChatSync.
 *
 * Uses a stub AgentChatService that overrides executeChatSync to return
 * pre-configured results without spawning real subprocesses.
 * Uses reflection to inject test agents into AgentRegistryService's private map,
 * bypassing the availability check that would try to run the CLI command.
 */
@DisplayName("AgentDelegationTool Tests")
class AgentDelegationToolTest {

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Injects an agent directly into the registry's private agents map,
     * bypassing availability checks.
     */
    @SuppressWarnings("unchecked")
    private static void injectAgent(AgentRegistryService registry, AgentProvider agent) {
        try {
            Field agentsField = AgentRegistryService.class.getDeclaredField("agents");
            agentsField.setAccessible(true);
            Map<String, AgentProvider> agents = (Map<String, AgentProvider>) agentsField.get(registry);
            agents.put(agent.getName(), agent);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject test agent", e);
        }
    }

    // ========================================================================
    // Stub AgentChatService — overrides executeChatSync only
    // ========================================================================

    static class StubAgentChatService extends AgentChatService {

        private SyncChatResult resultToReturn;
        private final List<AgentChatRequest> receivedRequests = new ArrayList<>();
        private long simulatedDelayMs = 0;

        StubAgentChatService(AgentRegistryService registry) {
            super(registry,
                    new AgentProcessDiagnosticService(),
                    new ClaudeStreamParser(),
                    List.of(new NoOpDocumentRetrieverImpl()),
                    List.of(new NoOpVectorStoreImpl()),
                    null, null, null, null, null);
        }

        void setResultToReturn(SyncChatResult result) {
            this.resultToReturn = result;
        }

        void setSimulatedDelayMs(long delayMs) {
            this.simulatedDelayMs = delayMs;
        }

        List<AgentChatRequest> getReceivedRequests() {
            return receivedRequests;
        }

        @Override
        public SyncChatResult executeChatSync(AgentChatRequest request, int timeoutSeconds) {
            receivedRequests.add(request);
            if (simulatedDelayMs > 0) {
                try {
                    Thread.sleep(simulatedDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new SyncChatResult("", null, -1, 0,
                            List.of(), List.of(), null, "Interrupted");
                }
            }
            return resultToReturn;
        }
    }

    // ========================================================================
    // Fixtures
    // ========================================================================

    private AgentRegistryService registry;
    private StubAgentChatService chatService;
    private AgentDelegationTool tool;

    @BeforeEach
    void setUp() {
        registry = new AgentRegistryService();
        // Inject agents via reflection to bypass CLI availability checks
        injectAgent(registry, AgentProvider.builder()
                .name("claude-cli")
                .displayName("Claude CLI")
                .command("claude")
                .available(true)
                .isDefault(true)
                .skipPermissionsFlag("--dangerously-skip-permissions")
                .description("Claude Code CLI agent")
                .build());
        injectAgent(registry, AgentProvider.builder()
                .name("codex-cli")
                .displayName("Codex CLI")
                .command("codex")
                .available(true)
                .isDefault(false)
                .description("Codex CLI agent")
                .build());

        chatService = new StubAgentChatService(registry);
        tool = new AgentDelegationTool(chatService, registry);
    }

    // ========================================================================
    // delegate_task (synchronous)
    // ========================================================================

    @Nested
    @DisplayName("delegate_task")
    class DelegateTaskTests {

        @Test
        @DisplayName("Should delegate successfully and return response")
        void shouldDelegateSuccessfully() {
            chatService.setResultToReturn(new AgentChatService.SyncChatResult(
                    "Hello World program written.", "proc-123", 0, 1500,
                    List.of(), List.of("Main.java"), Map.of("durationMs", 1500), null));

            var result = tool.delegateTask(new AgentDelegationTool.DelegateTaskInput(
                    "claude-cli", "Write a hello world program", "/tmp",
                    false, false, true, true, 60, null, null));

            assertEquals("success", result.get("status"));
            assertEquals("claude-cli", result.get("agentName"));
            assertEquals("Hello World program written.", result.get("response"));
            assertEquals("proc-123", result.get("processId"));
            assertNotNull(result.get("modifiedFiles"));
        }

        @Test
        @DisplayName("Should forward request parameters correctly")
        void shouldForwardRequestParams() {
            chatService.setResultToReturn(new AgentChatService.SyncChatResult(
                    "Done", null, 0, 100, List.of(), List.of(), null, null));

            tool.delegateTask(new AgentDelegationTool.DelegateTaskInput(
                    "claude-cli", "Search for info", "/work",
                    true, true, false, true, 120, 10, 0.7));

            assertEquals(1, chatService.getReceivedRequests().size());
            AgentChatRequest req = chatService.getReceivedRequests().get(0);
            assertEquals("Search for info", req.getMessage());
            assertEquals("claude-cli", req.getAgentName());
            assertEquals("/work", req.getWorkingDirectory());
            assertTrue(req.isEnableRag());
            assertTrue(req.isEnableGraphRag());
            assertFalse(req.isInjectMcpTools());
            assertTrue(req.isSkipPermissions());
            assertEquals(120, req.getTimeoutSeconds());
            assertEquals(10, req.getRagMaxResults());
            assertEquals(0.7, req.getRagSimilarityThreshold());
        }

        @Test
        @DisplayName("Should use default agent when agentName is null")
        void shouldUseDefaultAgent() {
            chatService.setResultToReturn(new AgentChatService.SyncChatResult(
                    "Default agent response", null, 0, 100,
                    List.of(), List.of(), null, null));

            var result = tool.delegateTask(new AgentDelegationTool.DelegateTaskInput(
                    null, "Do something", null,
                    null, null, null, null, null, null, null));

            assertEquals("success", result.get("status"));
            assertEquals("claude-cli", result.get("agentName"));
        }

        @Test
        @DisplayName("Should return error for empty message")
        void shouldErrorOnEmptyMessage() {
            var result = tool.delegateTask(new AgentDelegationTool.DelegateTaskInput(
                    "claude-cli", "", null,
                    null, null, null, null, null, null, null));

            assertEquals("error", result.get("status"));
            assertTrue(result.get("error").toString().contains("empty"));
        }

        @Test
        @DisplayName("Should return error for null message")
        void shouldErrorOnNullMessage() {
            var result = tool.delegateTask(new AgentDelegationTool.DelegateTaskInput(
                    "claude-cli", null, null,
                    null, null, null, null, null, null, null));

            assertEquals("error", result.get("status"));
        }

        @Test
        @DisplayName("Should return error for unknown agent")
        void shouldErrorOnUnknownAgent() {
            var result = tool.delegateTask(new AgentDelegationTool.DelegateTaskInput(
                    "unknown-agent", "Do something", null,
                    null, null, null, null, null, null, null));

            assertEquals("error", result.get("status"));
            assertTrue(result.get("error").toString().contains("not found"));
        }

        @Test
        @DisplayName("Should return error for unavailable agent")
        void shouldErrorOnUnavailableAgent() {
            injectAgent(registry, AgentProvider.builder()
                    .name("offline-cli")
                    .displayName("Offline")
                    .command("offline")
                    .available(false)
                    .build());

            var result = tool.delegateTask(new AgentDelegationTool.DelegateTaskInput(
                    "offline-cli", "Do something", null,
                    null, null, null, null, null, null, null));

            assertEquals("error", result.get("status"));
            assertTrue(result.get("error").toString().contains("not available"));
        }

        @Test
        @DisplayName("Should return error for API agent")
        void shouldErrorOnApiAgent() {
            injectAgent(registry, AgentProvider.builder()
                    .name("openai-api")
                    .displayName("OpenAI API")
                    .command("openai")
                    .available(true)
                    .agentType(AgentType.API)
                    .build());

            var result = tool.delegateTask(new AgentDelegationTool.DelegateTaskInput(
                    "openai-api", "Do something", null,
                    null, null, null, null, null, null, null));

            assertEquals("error", result.get("status"));
            assertTrue(result.get("error").toString().contains("API agents"));
        }

        @Test
        @DisplayName("Should propagate agent error response")
        void shouldPropagateAgentError() {
            chatService.setResultToReturn(new AgentChatService.SyncChatResult(
                    "partial output", "proc-456", 1, 500,
                    List.of(), List.of(), null, "Process exited with code: 1"));

            var result = tool.delegateTask(new AgentDelegationTool.DelegateTaskInput(
                    "claude-cli", "Bad task", null,
                    null, null, null, null, null, null, null));

            assertEquals("error", result.get("status"));
            assertEquals("partial output", result.get("response"));
            assertEquals(1, result.get("exitCode"));
            assertNotNull(result.get("error"));
        }

        @Test
        @DisplayName("Should include sources count when RAG returns docs")
        void shouldIncludeSourcesCount() {
            chatService.setResultToReturn(new AgentChatService.SyncChatResult(
                    "Answer with sources", null, 0, 200,
                    List.of(
                            new RetrievedDoc("doc1", "content1", Map.of(), 0.9),
                            new RetrievedDoc("doc2", "content2", Map.of(), 0.8)
                    ),
                    List.of(), null, null));

            var result = tool.delegateTask(new AgentDelegationTool.DelegateTaskInput(
                    "claude-cli", "Search question", null,
                    true, null, null, null, null, null, null));

            assertEquals("success", result.get("status"));
            assertEquals(2, result.get("sourcesUsed"));
        }

        @Test
        @DisplayName("Should include stats when available")
        void shouldIncludeStats() {
            Map<String, Object> stats = Map.of("durationMs", 1000, "costUsd", 0.05);
            chatService.setResultToReturn(new AgentChatService.SyncChatResult(
                    "Response", null, 0, 1000,
                    List.of(), List.of(), stats, null));

            var result = tool.delegateTask(new AgentDelegationTool.DelegateTaskInput(
                    "claude-cli", "Task", null,
                    null, null, null, null, null, null, null));

            assertEquals("success", result.get("status"));
            assertNotNull(result.get("stats"));
        }
    }

    // ========================================================================
    // Null services
    // ========================================================================

    @Nested
    @DisplayName("Null services")
    class NullServicesTests {

        @Test
        @DisplayName("Should return error when AgentChatService is null")
        void shouldErrorWhenChatServiceNull() {
            var nullTool = new AgentDelegationTool(null, registry);

            var result = nullTool.delegateTask(new AgentDelegationTool.DelegateTaskInput(
                    "claude-cli", "Do something", null,
                    null, null, null, null, null, null, null));

            assertEquals("error", result.get("status"));
            assertTrue(result.get("error").toString().contains("not available"));
        }

        @Test
        @DisplayName("Should return error when AgentRegistryService is null")
        void shouldErrorWhenRegistryNull() {
            var nullTool = new AgentDelegationTool(chatService, null);

            var result = nullTool.delegateTask(new AgentDelegationTool.DelegateTaskInput(
                    "claude-cli", "Do something", null,
                    null, null, null, null, null, null, null));

            assertEquals("error", result.get("status"));
            assertTrue(result.get("error").toString().contains("not available"));
        }

        @Test
        @DisplayName("Should return error when no default agent and no name")
        void shouldErrorWhenNoDefaultNoName() {
            var emptyRegistry = new AgentRegistryService();
            var emptyTool = new AgentDelegationTool(chatService, emptyRegistry);

            var result = emptyTool.delegateTask(new AgentDelegationTool.DelegateTaskInput(
                    null, "Do something", null,
                    null, null, null, null, null, null, null));

            assertEquals("error", result.get("status"));
        }
    }

    // ========================================================================
    // delegate_task_async + get_delegation_result
    // ========================================================================

    @Nested
    @DisplayName("Async delegation")
    class AsyncDelegationTests {

        @Test
        @DisplayName("Should start async delegation and return delegationId")
        void shouldStartAsyncDelegation() {
            chatService.setResultToReturn(new AgentChatService.SyncChatResult(
                    "Async response", null, 0, 100, List.of(), List.of(), null, null));
            chatService.setSimulatedDelayMs(100);

            var result = tool.delegateTaskAsync(new AgentDelegationTool.DelegateTaskAsyncInput(
                    "claude-cli", "Async task", null,
                    null, null, null, null, null, null, null));

            assertEquals("success", result.get("status"));
            assertNotNull(result.get("delegationId"));
            assertTrue(result.get("delegationId").toString().startsWith("delegation-"));
        }

        @Test
        @DisplayName("Should get completed async result with blocking wait")
        void shouldGetAsyncResultBlocking() throws Exception {
            chatService.setResultToReturn(new AgentChatService.SyncChatResult(
                    "Completed async response", "proc-789", 0, 200,
                    List.of(), List.of(), null, null));
            chatService.setSimulatedDelayMs(50);

            var asyncResult = tool.delegateTaskAsync(new AgentDelegationTool.DelegateTaskAsyncInput(
                    "claude-cli", "Async task", null,
                    null, null, null, null, 60, null, null));
            String delegationId = asyncResult.get("delegationId").toString();

            // Give it time to complete
            Thread.sleep(300);

            var getResult = tool.getDelegationResult(
                    new AgentDelegationTool.GetDelegationResultInput(delegationId, true));

            assertEquals("success", getResult.get("status"));
            assertEquals("Completed async response", getResult.get("response"));
        }

        @Test
        @DisplayName("Should return pending for non-blocking poll of running task")
        void shouldReturnPendingForNonBlockingPoll() {
            chatService.setResultToReturn(new AgentChatService.SyncChatResult(
                    "Response", null, 0, 100, List.of(), List.of(), null, null));
            chatService.setSimulatedDelayMs(5000);

            var asyncResult = tool.delegateTaskAsync(new AgentDelegationTool.DelegateTaskAsyncInput(
                    "claude-cli", "Long task", null,
                    null, null, null, null, 60, null, null));
            String delegationId = asyncResult.get("delegationId").toString();

            var pollResult = tool.getDelegationResult(
                    new AgentDelegationTool.GetDelegationResultInput(delegationId, false));

            assertEquals("pending", pollResult.get("status"));
            assertNotNull(pollResult.get("elapsedMs"));
        }

        @Test
        @DisplayName("Should return error for unknown delegationId")
        void shouldErrorOnUnknownDelegationId() {
            var result = tool.getDelegationResult(
                    new AgentDelegationTool.GetDelegationResultInput("nonexistent-id", true));

            assertEquals("error", result.get("status"));
            assertTrue(result.get("error").toString().contains("not found"));
        }

        @Test
        @DisplayName("Should return error for empty delegationId")
        void shouldErrorOnEmptyDelegationId() {
            var result = tool.getDelegationResult(
                    new AgentDelegationTool.GetDelegationResultInput("", true));

            assertEquals("error", result.get("status"));
        }

        @Test
        @DisplayName("Should reject async delegation with empty message")
        void shouldRejectAsyncEmptyMessage() {
            var result = tool.delegateTaskAsync(new AgentDelegationTool.DelegateTaskAsyncInput(
                    "claude-cli", "", null,
                    null, null, null, null, null, null, null));

            assertEquals("error", result.get("status"));
        }
    }

    // ========================================================================
    // cancel_delegation
    // ========================================================================

    @Nested
    @DisplayName("cancel_delegation")
    class CancelDelegationTests {

        @Test
        @DisplayName("Should cancel active delegation")
        void shouldCancelActiveDelegation() {
            chatService.setResultToReturn(new AgentChatService.SyncChatResult(
                    "Response", null, 0, 100, List.of(), List.of(), null, null));
            chatService.setSimulatedDelayMs(10000);

            var asyncResult = tool.delegateTaskAsync(new AgentDelegationTool.DelegateTaskAsyncInput(
                    "claude-cli", "Long task to cancel", null,
                    null, null, null, null, 60, null, null));
            String delegationId = asyncResult.get("delegationId").toString();

            var cancelResult = tool.cancelDelegation(
                    new AgentDelegationTool.CancelDelegationInput(delegationId));

            assertEquals("success", cancelResult.get("status"));
            assertTrue(cancelResult.get("message").toString().contains("cancelled"));
        }

        @Test
        @DisplayName("Should return error when cancelling unknown delegation")
        void shouldErrorOnCancelUnknown() {
            var result = tool.cancelDelegation(
                    new AgentDelegationTool.CancelDelegationInput("nonexistent"));

            assertEquals("error", result.get("status"));
        }

        @Test
        @DisplayName("Should return error when cancelling with empty id")
        void shouldErrorOnCancelEmptyId() {
            var result = tool.cancelDelegation(
                    new AgentDelegationTool.CancelDelegationInput(""));

            assertEquals("error", result.get("status"));
        }
    }

    // ========================================================================
    // list_delegations
    // ========================================================================

    @Nested
    @DisplayName("list_delegations")
    class ListDelegationsTests {

        @Test
        @DisplayName("Should list empty delegations")
        void shouldListEmptyDelegations() {
            var result = tool.listDelegations(new AgentDelegationTool.ListDelegationsInput());

            assertEquals("success", result.get("status"));
            assertEquals(0, result.get("activeDelegations"));
            assertTrue(((List<?>) result.get("delegations")).isEmpty());
        }

        @Test
        @DisplayName("Should list active delegations")
        void shouldListActiveDelegations() {
            chatService.setResultToReturn(new AgentChatService.SyncChatResult(
                    "Response", null, 0, 100, List.of(), List.of(), null, null));
            chatService.setSimulatedDelayMs(10000);

            tool.delegateTaskAsync(new AgentDelegationTool.DelegateTaskAsyncInput(
                    "claude-cli", "Task one", null,
                    null, null, null, null, 60, null, null));
            tool.delegateTaskAsync(new AgentDelegationTool.DelegateTaskAsyncInput(
                    "codex-cli", "Task two", null,
                    null, null, null, null, 60, null, null));

            var result = tool.listDelegations(new AgentDelegationTool.ListDelegationsInput());

            assertEquals("success", result.get("status"));
            assertEquals(2, result.get("activeDelegations"));

            @SuppressWarnings("unchecked")
            var delegations = (List<Map<String, Object>>) result.get("delegations");
            assertEquals(2, delegations.size());

            for (var d : delegations) {
                assertNotNull(d.get("delegationId"));
                assertNotNull(d.get("agentName"));
                assertNotNull(d.get("taskPreview"));
                assertNotNull(d.get("elapsedMs"));
            }
        }

        @Test
        @DisplayName("Should truncate long task preview")
        void shouldTruncateLongTaskPreview() {
            chatService.setResultToReturn(new AgentChatService.SyncChatResult(
                    "Response", null, 0, 100, List.of(), List.of(), null, null));
            chatService.setSimulatedDelayMs(10000);

            tool.delegateTaskAsync(new AgentDelegationTool.DelegateTaskAsyncInput(
                    "claude-cli", "A".repeat(200), null,
                    null, null, null, null, 60, null, null));

            var result = tool.listDelegations(new AgentDelegationTool.ListDelegationsInput());

            @SuppressWarnings("unchecked")
            var delegations = (List<Map<String, Object>>) result.get("delegations");
            String preview = delegations.get(0).get("taskPreview").toString();
            assertTrue(preview.length() <= 104); // 100 + "..."
            assertTrue(preview.endsWith("..."));
        }
    }

    // ========================================================================
    // SyncChatResult record
    // ========================================================================

    @Nested
    @DisplayName("SyncChatResult")
    class SyncChatResultTests {

        @Test
        @DisplayName("isSuccess true for exitCode 0 and no error")
        void isSuccessTrue() {
            var r = new AgentChatService.SyncChatResult(
                    "ok", "p1", 0, 100, List.of(), List.of(), null, null);
            assertTrue(r.isSuccess());
        }

        @Test
        @DisplayName("isSuccess false for non-zero exitCode")
        void isSuccessFalseExitCode() {
            var r = new AgentChatService.SyncChatResult(
                    "ok", "p1", 1, 100, List.of(), List.of(), null, null);
            assertFalse(r.isSuccess());
        }

        @Test
        @DisplayName("isSuccess false when error present")
        void isSuccessFalseError() {
            var r = new AgentChatService.SyncChatResult(
                    "ok", "p1", 0, 100, List.of(), List.of(), null, "fail");
            assertFalse(r.isSuccess());
        }

        @Test
        @DisplayName("Record accessors work correctly")
        void recordAccessors() {
            var sources = List.of(new RetrievedDoc("id", "text", Map.of(), 0.9));
            var modified = List.of("file.java");
            var stats = Map.<String, Object>of("durationMs", 500);

            var r = new AgentChatService.SyncChatResult(
                    "response", "proc-1", 0, 1234, sources, modified, stats, null);

            assertEquals("response", r.content());
            assertEquals("proc-1", r.processId());
            assertEquals(0, r.exitCode());
            assertEquals(1234, r.durationMs());
            assertEquals(1, r.sources().size());
            assertEquals(1, r.modifiedFiles().size());
            assertEquals(500, r.stats().get("durationMs"));
            assertNull(r.error());
        }
    }
}
