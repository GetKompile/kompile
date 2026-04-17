/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.tools;

import ai.kompile.app.services.agent.AgentChatService;
import ai.kompile.app.services.agent.AgentRegistryService;
import ai.kompile.app.tools.AgentDelegationTool.DelegateTaskInput;
import ai.kompile.app.tools.AgentDelegationTool.DelegateTaskAsyncInput;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.AgentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ai.kompile.app.web.dto.AgentChatRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for agentArgs pass-through in AgentDelegationTool.
 * Verifies that extra CLI arguments flow from DelegateTaskInput through to AgentChatRequest.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentDelegationTool - agentArgs pass-through")
class AgentDelegationToolAgentArgsTest {

    @Mock
    private AgentChatService agentChatService;

    @Mock
    private AgentRegistryService agentRegistry;

    private AgentDelegationTool tool;

    @BeforeEach
    void setUp() {
        tool = new AgentDelegationTool(agentChatService, agentRegistry);
    }

    private AgentProvider createClaude() {
        return AgentProvider.builder()
                .name("claude-cli")
                .displayName("Claude CLI")
                .command("claude")
                .skipPermissions(true)
                .skipPermissionsFlag("--dangerously-skip-permissions")
                .available(true)
                .isDefault(true)
                .agentType(AgentType.CLI)
                .description("Claude CLI agent")
                .build();
    }

    // =========================================================================
    // DelegateTaskInput record
    // =========================================================================

    @Nested
    @DisplayName("DelegateTaskInput record")
    class DelegateTaskInputTests {

        @Test
        @DisplayName("should store agentArgs in the record")
        void recordStoresAgentArgs() {
            List<String> args = List.of("--model", "opus", "--max-turns", "5");
            DelegateTaskInput input = new DelegateTaskInput(
                    "claude-cli", "hello", "/tmp", false, false, true, true, 300, null, null, args);
            assertEquals(args, input.agentArgs());
        }

        @Test
        @DisplayName("should allow null agentArgs")
        void recordAllowsNullAgentArgs() {
            DelegateTaskInput input = new DelegateTaskInput(
                    "claude-cli", "hello", null, null, null, null, null, null, null, null, null);
            assertNull(input.agentArgs());
        }

        @Test
        @DisplayName("should allow empty agentArgs")
        void recordAllowsEmptyAgentArgs() {
            DelegateTaskInput input = new DelegateTaskInput(
                    "claude-cli", "hello", null, null, null, null, null, null, null, null, List.of());
            assertTrue(input.agentArgs().isEmpty());
        }
    }

    // =========================================================================
    // DelegateTaskAsyncInput record
    // =========================================================================

    @Nested
    @DisplayName("DelegateTaskAsyncInput record")
    class DelegateTaskAsyncInputTests {

        @Test
        @DisplayName("should store agentArgs in the async record")
        void asyncRecordStoresAgentArgs() {
            List<String> args = List.of("--verbose", "--model", "sonnet");
            DelegateTaskAsyncInput input = new DelegateTaskAsyncInput(
                    "claude-cli", "hello", null, null, null, null, null, null, null, null, args);
            assertEquals(args, input.agentArgs());
        }

        @Test
        @DisplayName("should allow null agentArgs in async record")
        void asyncRecordAllowsNull() {
            DelegateTaskAsyncInput input = new DelegateTaskAsyncInput(
                    "claude-cli", "hello", null, null, null, null, null, null, null, null, null);
            assertNull(input.agentArgs());
        }
    }

    // =========================================================================
    // delegateTask forwards agentArgs to AgentChatRequest
    // =========================================================================

    @Nested
    @DisplayName("delegateTask agentArgs forwarding")
    class DelegateTaskForwarding {

        @BeforeEach
        void setUp() {
            when(agentRegistry.getAgent("claude-cli")).thenReturn(Optional.of(createClaude()));
        }

        @Test
        @DisplayName("should forward agentArgs to AgentChatService")
        void forwardsAgentArgs() {
            List<String> args = List.of("--model", "opus");

            // Capture the AgentChatRequest passed to executeChatSync
            ArgumentCaptor<AgentChatRequest> requestCaptor = ArgumentCaptor.forClass(AgentChatRequest.class);
            when(agentChatService.executeChatSync(requestCaptor.capture(), anyInt()))
                    .thenReturn(new AgentChatService.SyncChatResult(
                            "response", "proc-1", 0, 100, List.of(), List.of(), null, null));

            DelegateTaskInput input = new DelegateTaskInput(
                    "claude-cli", "do something", null, null, null, null, null, null, null, null, args);

            Map<String, Object> result = tool.delegateTask(input);

            assertEquals("success", result.get("status"));

            AgentChatRequest capturedRequest = requestCaptor.getValue();
            assertNotNull(capturedRequest.getAgentArgs(), "agentArgs should be set on request");
            assertEquals(args, capturedRequest.getAgentArgs());
        }

        @Test
        @DisplayName("should forward null agentArgs without error")
        void forwardsNullAgentArgs() {
            when(agentChatService.executeChatSync(any(), anyInt()))
                    .thenReturn(new AgentChatService.SyncChatResult(
                            "response", "proc-1", 0, 100, List.of(), List.of(), null, null));

            DelegateTaskInput input = new DelegateTaskInput(
                    "claude-cli", "do something", null, null, null, null, null, null, null, null, null);

            Map<String, Object> result = tool.delegateTask(input);
            assertEquals("success", result.get("status"));
        }

        @Test
        @DisplayName("should not set agentArgs on request when input agentArgs is empty")
        void emptyAgentArgsNotSet() {
            ArgumentCaptor<AgentChatRequest> requestCaptor = ArgumentCaptor.forClass(AgentChatRequest.class);
            when(agentChatService.executeChatSync(requestCaptor.capture(), anyInt()))
                    .thenReturn(new AgentChatService.SyncChatResult(
                            "response", "proc-1", 0, 100, List.of(), List.of(), null, null));

            DelegateTaskInput input = new DelegateTaskInput(
                    "claude-cli", "do something", null, null, null, null, null, null, null, null, List.of());

            tool.delegateTask(input);

            AgentChatRequest capturedRequest = requestCaptor.getValue();
            // Empty list should not be set (buildRequest checks !isEmpty)
            assertNull(capturedRequest.getAgentArgs(),
                    "Empty agentArgs should not be set on request");
        }
    }

    // =========================================================================
    // delegateTaskAsync forwards agentArgs
    // =========================================================================

    @Nested
    @DisplayName("delegateTaskAsync agentArgs forwarding")
    class DelegateTaskAsyncForwarding {

        @BeforeEach
        void setUp() {
            when(agentRegistry.getAgent("claude-cli")).thenReturn(Optional.of(createClaude()));
        }

        @Test
        @DisplayName("should return delegation ID with agentArgs")
        void asyncReturnsId() {
            // executeChatSync will be called async - just stub it
            lenient().when(agentChatService.executeChatSync(any(), anyInt()))
                    .thenReturn(new AgentChatService.SyncChatResult(
                            "response", "proc-1", 0, 100, List.of(), List.of(), null, null));

            List<String> args = List.of("--model", "opus");
            DelegateTaskAsyncInput input = new DelegateTaskAsyncInput(
                    "claude-cli", "do something", null, null, null, null, null, null, null, null, args);

            Map<String, Object> result = tool.delegateTaskAsync(input);

            assertEquals("success", result.get("status"));
            assertNotNull(result.get("delegationId"));
            assertTrue(result.get("delegationId").toString().startsWith("delegation-"));
        }
    }
}
