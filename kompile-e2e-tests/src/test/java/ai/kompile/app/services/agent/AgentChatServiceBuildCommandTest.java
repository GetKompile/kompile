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
package ai.kompile.app.services.agent;

import ai.kompile.app.services.ServerPortService;
import ai.kompile.app.services.mcp.BuiltInToolDiscoveryService;
import ai.kompile.app.web.dto.AgentChatRequest;
import ai.kompile.chat.history.service.FolderService;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.embeddings.NoOpVectorStoreImpl;
import ai.kompile.core.retrievers.NoOpDocumentRetrieverImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AgentChatService.buildCommand() to verify that the skipPermissions
 * flag flows correctly through to the CLI command for each agent type.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentChatService - buildCommand skipPermissions")
class AgentChatServiceBuildCommandTest {

    @Mock
    private AgentRegistryService agentRegistry;

    @Mock
    private AgentProcessDiagnosticService diagnosticService;

    @Mock
    private ClaudeStreamParser streamParser;

    @Mock
    private ServerPortService serverPortService;

    @Mock
    private FolderService folderService;

    private AgentChatService service;
    private Method buildCommandMethod;

    @BeforeEach
    void setUp() throws Exception {
        AgentSubprocessExecutor subprocessExecutor = new AgentSubprocessExecutor(
                agentRegistry, diagnosticService, streamParser);
        service = new AgentChatService(
                agentRegistry,
                diagnosticService,
                streamParser,
                subprocessExecutor,
                List.of(new NoOpDocumentRetrieverImpl()),
                List.of(new NoOpVectorStoreImpl()),
                null, // graphRagServices
                null, // toolDiscoveryService
                serverPortService,
                folderService,
                null, // apiAgentChatExecutor
                null  // queryProcessor
        );
        buildCommandMethod = AgentChatService.class.getDeclaredMethod(
                "buildCommand", AgentProvider.class, AgentChatRequest.class, String.class);
        buildCommandMethod.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private List<String> invokeBuildCommand(AgentProvider agent, AgentChatRequest request, String prompt) throws Exception {
        return (List<String>) buildCommandMethod.invoke(service, agent, request, prompt);
    }

    private AgentProvider createAgent(String name, String command, String skipFlag) {
        return AgentProvider.builder()
                .name(name)
                .displayName(name)
                .command(command)
                .skipPermissions(true)
                .skipPermissionsFlag(skipFlag)
                .available(true)
                .isDefault(false)
                .description("Test agent")
                .build();
    }

    private AgentChatRequest createRequest(boolean skipPermissions) {
        AgentChatRequest request = new AgentChatRequest();
        request.setMessage("test message");
        request.setSkipPermissions(skipPermissions);
        request.setInjectMcpTools(false);
        return request;
    }

    // =========================================================================
    // Claude CLI
    // =========================================================================

    @Nested
    @DisplayName("Claude CLI agent")
    class ClaudeCliAgent {

        private AgentProvider claude;

        @BeforeEach
        void setUp() {
            claude = createAgent("claude-cli", "claude", "--dangerously-skip-permissions");
        }

        @Test
        @DisplayName("should include --dangerously-skip-permissions when skipPermissions=true")
        void includesSkipFlag() throws Exception {
            List<String> command = invokeBuildCommand(claude, createRequest(true), "hello");
            assertTrue(command.contains("--dangerously-skip-permissions"),
                    "Command should include skip flag: " + command);
        }

        @Test
        @DisplayName("should NOT include --dangerously-skip-permissions when skipPermissions=false")
        void excludesSkipFlag() throws Exception {
            List<String> command = invokeBuildCommand(claude, createRequest(false), "hello");
            assertFalse(command.contains("--dangerously-skip-permissions"),
                    "Command should NOT include skip flag: " + command);
        }

        @Test
        @DisplayName("should always include prompt args")
        void includesPromptArgs() throws Exception {
            List<String> command = invokeBuildCommand(claude, createRequest(true), "my prompt");
            assertTrue(command.contains("-p"), "Command should contain -p flag");
            assertTrue(command.contains("my prompt"), "Command should contain prompt text");
        }
    }

    // =========================================================================
    // Codex CLI
    // =========================================================================

    @Nested
    @DisplayName("Codex CLI agent")
    class CodexCliAgent {

        @Test
        @DisplayName("should include --full-auto when skipPermissions=true")
        void includesSkipFlag() throws Exception {
            AgentProvider codex = createAgent("codex-cli", "codex", "--full-auto");
            List<String> command = invokeBuildCommand(codex, createRequest(true), "hello");
            assertTrue(command.contains("--full-auto"),
                    "Command should include --full-auto: " + command);
        }

        @Test
        @DisplayName("should NOT include --full-auto when skipPermissions=false")
        void excludesSkipFlag() throws Exception {
            AgentProvider codex = createAgent("codex-cli", "codex", "--full-auto");
            List<String> command = invokeBuildCommand(codex, createRequest(false), "hello");
            assertFalse(command.contains("--full-auto"),
                    "Command should NOT include --full-auto: " + command);
        }
    }

    // =========================================================================
    // Gemini CLI
    // =========================================================================

    @Nested
    @DisplayName("Gemini CLI agent")
    class GeminiCliAgent {

        @Test
        @DisplayName("should include --yolo when skipPermissions=true")
        void includesSkipFlag() throws Exception {
            AgentProvider gemini = createAgent("gemini-cli", "gemini", "--yolo");
            AgentChatRequest request = createRequest(true);
            request.setWorkingDirectory(System.getProperty("java.io.tmpdir"));
            List<String> command = invokeBuildCommand(gemini, request, "hello");
            assertTrue(command.contains("--yolo"),
                    "Command should include --yolo: " + command);
        }

        @Test
        @DisplayName("should NOT include --yolo when skipPermissions=false")
        void excludesSkipFlag() throws Exception {
            AgentProvider gemini = createAgent("gemini-cli", "gemini", "--yolo");
            AgentChatRequest request = createRequest(false);
            request.setWorkingDirectory(System.getProperty("java.io.tmpdir"));
            List<String> command = invokeBuildCommand(gemini, request, "hello");
            assertFalse(command.contains("--yolo"),
                    "Command should NOT include --yolo: " + command);
        }
    }

    // =========================================================================
    // Agent with null skipPermissionsFlag
    // =========================================================================

    @Nested
    @DisplayName("Agent with null skipPermissionsFlag")
    class NullSkipFlagAgent {

        @Test
        @DisplayName("should not add any skip flag when skipPermissionsFlag is null")
        void noFlagAdded() throws Exception {
            AgentProvider agent = AgentProvider.builder()
                    .name("custom-agent")
                    .displayName("Custom Agent")
                    .command("custom")
                    .skipPermissions(true)
                    .skipPermissionsFlag(null)
                    .available(true)
                    .isDefault(false)
                    .description("Agent without skip flag")
                    .build();

            List<String> command = invokeBuildCommand(agent, createRequest(true), "hello");
            assertEquals("custom", command.get(0));
            assertFalse(command.stream().anyMatch(c ->
                    c.contains("skip") || c.contains("yolo") || c.contains("full-auto")),
                    "Command should not have any permission flag: " + command);
        }
    }

    // =========================================================================
    // Agent args pass-through
    // =========================================================================

    @Nested
    @DisplayName("agentArgs pass-through")
    class AgentArgsPassthrough {

        private AgentProvider claude;

        @BeforeEach
        void setUp() {
            claude = createAgent("claude-cli", "claude", "--dangerously-skip-permissions");
        }

        @Test
        @DisplayName("should append agentArgs after agent default args")
        void appendsAgentArgs() throws Exception {
            AgentChatRequest request = createRequest(true);
            request.setAgentArgs(List.of("--model", "opus", "--max-turns", "5"));
            List<String> command = invokeBuildCommand(claude, request, "hello");

            // agentArgs should be present in the command
            assertTrue(command.contains("--model"), "Command should contain --model: " + command);
            assertTrue(command.contains("opus"), "Command should contain opus: " + command);
            assertTrue(command.contains("--max-turns"), "Command should contain --max-turns: " + command);
            assertTrue(command.contains("5"), "Command should contain 5: " + command);

            // agentArgs should appear before -p (prompt flag)
            int modelIdx = command.indexOf("--model");
            int promptIdx = command.indexOf("-p");
            assertTrue(modelIdx < promptIdx,
                    "agentArgs (--model at " + modelIdx + ") should come before -p at " + promptIdx + ": " + command);
        }

        @Test
        @DisplayName("should work correctly with null agentArgs")
        void nullAgentArgs() throws Exception {
            AgentChatRequest request = createRequest(true);
            request.setAgentArgs(null);
            List<String> command = invokeBuildCommand(claude, request, "hello");

            // Should still produce a valid command
            assertTrue(command.contains("claude"), "Command should start with claude");
            assertTrue(command.contains("-p"), "Command should contain -p flag");
            assertTrue(command.contains("hello"), "Command should contain prompt");
        }

        @Test
        @DisplayName("should work correctly with empty agentArgs")
        void emptyAgentArgs() throws Exception {
            AgentChatRequest request = createRequest(true);
            request.setAgentArgs(List.of());
            List<String> command = invokeBuildCommand(claude, request, "hello");

            // Same as no extra args
            assertTrue(command.contains("claude"));
            assertTrue(command.contains("-p"));
            assertTrue(command.contains("hello"));
        }

        @Test
        @DisplayName("agentArgs should appear after skip-permissions flag")
        void agentArgsAfterSkipPermissions() throws Exception {
            AgentChatRequest request = createRequest(true);
            request.setAgentArgs(List.of("--verbose"));
            List<String> command = invokeBuildCommand(claude, request, "hello");

            int skipIdx = command.indexOf("--dangerously-skip-permissions");
            int verboseIdx = command.indexOf("--verbose");
            assertTrue(skipIdx < verboseIdx,
                    "Skip permissions flag should come before agentArgs: " + command);
        }

        @Test
        @DisplayName("should preserve agentArgs ordering")
        void preservesOrdering() throws Exception {
            AgentChatRequest request = createRequest(true);
            request.setAgentArgs(List.of("--first", "a", "--second", "b"));
            List<String> command = invokeBuildCommand(claude, request, "hello");

            int firstIdx = command.indexOf("--first");
            int aIdx = command.indexOf("a");
            int secondIdx = command.indexOf("--second");
            int bIdx = command.indexOf("b");

            assertTrue(firstIdx < aIdx && aIdx < secondIdx && secondIdx < bIdx,
                    "agentArgs should preserve ordering: " + command);
        }

        @Test
        @DisplayName("should work with Codex agent and agentArgs")
        void codexWithAgentArgs() throws Exception {
            AgentProvider codex = createAgent("codex-cli", "codex", "--full-auto");
            AgentChatRequest request = createRequest(true);
            request.setAgentArgs(List.of("--model", "o4-mini"));
            List<String> command = invokeBuildCommand(codex, request, "hello");

            assertTrue(command.contains("--full-auto"), "Should contain skip flag");
            assertTrue(command.contains("--model"), "Should contain agentArgs --model");
            assertTrue(command.contains("o4-mini"), "Should contain agentArgs value");
            assertTrue(command.contains("-p"), "Should contain prompt flag");
        }

        @Test
        @DisplayName("should work with Gemini agent and agentArgs")
        void geminiWithAgentArgs() throws Exception {
            AgentProvider gemini = createAgent("gemini-cli", "gemini", "--yolo");
            AgentChatRequest request = createRequest(true);
            request.setWorkingDirectory(System.getProperty("java.io.tmpdir"));
            request.setAgentArgs(List.of("--sandbox"));
            List<String> command = invokeBuildCommand(gemini, request, "hello");

            assertTrue(command.contains("--yolo"), "Should contain skip flag");
            assertTrue(command.contains("--sandbox"), "Should contain agentArgs --sandbox");
        }
    }

    // =========================================================================
    // buildInteractiveCommand agentArgs (used by passthrough)
    // =========================================================================

    @Nested
    @DisplayName("buildInteractiveCommand agentArgs")
    class InteractiveCommandAgentArgs {

        @Test
        @DisplayName("should include agentArgs in interactive command")
        void includesAgentArgs() {
            AgentProvider claude = createAgent("claude-cli", "claude", "--dangerously-skip-permissions");
            List<String> command = service.buildInteractiveCommand(
                    claude, true, false, List.of("--model", "sonnet"));

            assertTrue(command.contains("--model"), "Should contain --model: " + command);
            assertTrue(command.contains("sonnet"), "Should contain sonnet: " + command);
            assertFalse(command.contains("-p"), "Interactive command should not contain -p");
        }

        @Test
        @DisplayName("should work with null agentArgs in interactive command")
        void nullAgentArgs() {
            AgentProvider claude = createAgent("claude-cli", "claude", "--dangerously-skip-permissions");
            List<String> command = service.buildInteractiveCommand(claude, true, false, null);

            assertTrue(command.contains("claude"));
            assertTrue(command.contains("--dangerously-skip-permissions"));
            assertFalse(command.contains("-p"));
        }

        @Test
        @DisplayName("3-arg overload should behave same as 4-arg with null")
        void threeArgOverload() {
            AgentProvider claude = createAgent("claude-cli", "claude", "--dangerously-skip-permissions");
            List<String> with3 = service.buildInteractiveCommand(claude, true, false);
            List<String> with4 = service.buildInteractiveCommand(claude, true, false, null);

            assertEquals(with3, with4, "3-arg and 4-arg with null should produce same command");
        }
    }

    // =========================================================================
    // Default AgentChatRequest
    // =========================================================================

    @Nested
    @DisplayName("AgentChatRequest defaults")
    class RequestDefaults {

        @Test
        @DisplayName("skipPermissions should default to true")
        void defaultSkipPermissionsIsTrue() {
            AgentChatRequest request = new AgentChatRequest();
            assertTrue(request.isSkipPermissions(),
                    "Default skipPermissions should be true for backward compatibility");
        }

        @Test
        @DisplayName("agentArgs should default to null")
        void defaultAgentArgsIsNull() {
            AgentChatRequest request = new AgentChatRequest();
            assertNull(request.getAgentArgs(),
                    "Default agentArgs should be null");
        }
    }
}
