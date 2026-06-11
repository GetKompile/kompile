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
import ai.kompile.app.web.dto.PassthroughSessionRequest;
import ai.kompile.chat.history.service.ChatHistoryService;
import ai.kompile.chat.history.service.FolderService;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.AgentType;
import ai.kompile.core.embeddings.NoOpVectorStoreImpl;
import ai.kompile.core.retrievers.NoOpDocumentRetrieverImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for PassthroughSessionManager: verifies that interactive sessions
 * can be started for all agent types (Claude, Codex, Gemini) and that
 * session lifecycle (start, send, list, end) works correctly.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PassthroughSessionManager - Start chat with all agents")
class PassthroughSessionManagerTest {

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

    @Mock
    private ChatHistoryService chatHistoryService;

    private AgentChatService agentChatService;
    private PassthroughSessionManager sessionManager;

    @BeforeEach
    void setUp() {
        agentChatService = new AgentChatService(
                agentRegistry,
                diagnosticService,
                streamParser,
                List.of(new NoOpDocumentRetrieverImpl()),
                List.of(new NoOpVectorStoreImpl()),
                null, // graphRagServices
                null, // toolDiscoveryService
                serverPortService,
                folderService,
                null, // apiAgentChatExecutor
                null  // queryProcessor
        );

        sessionManager = new PassthroughSessionManager(
                agentRegistry,
                agentChatService,
                streamParser,
                chatHistoryService
        );
    }

    @AfterEach
    void tearDown() {
        sessionManager.cleanup();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Create a CLI agent that uses 'sleep 30' as its command so the process stays alive
     * long enough for test assertions.
     */
    private AgentProvider createCliAgent(String name, String command, String skipFlag,
                                         String promptPattern) {
        return AgentProvider.builder()
                .name(name)
                .displayName(name)
                .command(command)
                .skipPermissions(true)
                .skipPermissionsFlag(skipFlag)
                .available(true)
                .isDefault(false)
                .agentType(AgentType.CLI)
                .interactivePromptPattern(promptPattern)
                .description("Test " + name + " agent")
                .build();
    }

    /**
     * Brief pause to let reader thread start and process output.
     */
    private void waitForSessionInit() {
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private AgentProvider createApiAgent(String name) {
        return AgentProvider.builder()
                .name(name)
                .displayName(name)
                .command(null)
                .available(true)
                .isDefault(false)
                .agentType(AgentType.API)
                .description("API agent")
                .build();
    }

    private PassthroughSessionRequest createSessionRequest(String agentName) {
        PassthroughSessionRequest request = new PassthroughSessionRequest();
        request.setAgentName(agentName);
        request.setSkipPermissions(true);
        request.setInjectMcpTools(false);
        return request;
    }


    // =========================================================================
    // Claude CLI
    // =========================================================================

    @Nested
    @DisplayName("Claude CLI agent")
    class ClaudeCli {

        private AgentProvider claude;

        @BeforeEach
        void setUp() {
            // Use 'cat' which stays alive reading stdin until it's closed
            claude = createCliAgent("claude-cli", "cat", null, null);
            lenient().when(agentRegistry.getAgent("claude-cli")).thenReturn(Optional.of(claude));
            lenient().when(streamParser.supportsStreamJson("claude-cli")).thenReturn(true);
        }

        @Test
        @DisplayName("should start an interactive session with Claude")
        void startsSession() {
            SseEmitter emitter = new SseEmitter(-1L);
            String sessionId = sessionManager.startSession(createSessionRequest("claude-cli"), emitter);

            assertNotNull(sessionId, "Session ID should not be null");
            waitForSessionInit();

            // Session should be listed
            List<Map<String, Object>> sessions = sessionManager.listSessions();
            assertEquals(1, sessions.size());
            assertEquals("claude-cli", sessions.get(0).get("agentName"));
            assertEquals(sessionId, sessions.get(0).get("sessionId"));
        }

        @Test
        @DisplayName("should report session status correctly")
        void sessionStatus() {
            SseEmitter emitter = new SseEmitter(-1L);
            String sessionId = sessionManager.startSession(createSessionRequest("claude-cli"), emitter);
            waitForSessionInit();

            Map<String, Object> status = sessionManager.getStatus(sessionId);
            assertNotNull(status);
            assertEquals("claude-cli", status.get("agentName"));
            assertEquals(0, status.get("messageCount"));
        }

        @Test
        @DisplayName("should end session cleanly")
        void endsSession() {
            SseEmitter emitter = new SseEmitter(-1L);
            String sessionId = sessionManager.startSession(createSessionRequest("claude-cli"), emitter);
            assertNotNull(sessionId);
            waitForSessionInit();

            sessionManager.endSession(sessionId);

            assertNull(sessionManager.getStatus(sessionId), "Session should be removed after ending");
            assertTrue(sessionManager.listSessions().isEmpty());
        }

        @Test
        @DisplayName("should use stream-json parsing for Claude")
        void usesStreamParser() {
            when(streamParser.supportsStreamJson("claude-cli")).thenReturn(true);

            SseEmitter emitter = new SseEmitter(-1L);
            String sessionId = sessionManager.startSession(createSessionRequest("claude-cli"), emitter);

            assertNotNull(sessionId);
            verify(streamParser).supportsStreamJson("claude-cli");
        }
    }

    // =========================================================================
    // Codex CLI
    // =========================================================================

    @Nested
    @DisplayName("Codex CLI agent")
    class CodexCli {

        private AgentProvider codex;

        @BeforeEach
        void setUp() {
            codex = createCliAgent("codex-cli", "cat", null, "^> $");
            lenient().when(agentRegistry.getAgent("codex-cli")).thenReturn(Optional.of(codex));
            lenient().when(streamParser.supportsStreamJson("codex-cli")).thenReturn(false);
        }

        @Test
        @DisplayName("should start an interactive session with Codex")
        void startsSession() {
            SseEmitter emitter = new SseEmitter(-1L);
            String sessionId = sessionManager.startSession(createSessionRequest("codex-cli"), emitter);

            assertNotNull(sessionId, "Session ID should not be null for Codex");
            waitForSessionInit();

            List<Map<String, Object>> sessions = sessionManager.listSessions();
            assertEquals(1, sessions.size());
            assertEquals("codex-cli", sessions.get(0).get("agentName"));
        }

        @Test
        @DisplayName("should use plain-text mode (not stream-json) for Codex")
        void usesPlainTextMode() {
            when(streamParser.supportsStreamJson("codex-cli")).thenReturn(false);

            SseEmitter emitter = new SseEmitter(-1L);
            String sessionId = sessionManager.startSession(createSessionRequest("codex-cli"), emitter);

            assertNotNull(sessionId);
            verify(streamParser).supportsStreamJson("codex-cli");
        }

        @Test
        @DisplayName("should have prompt pattern for turn detection")
        void hasPromptPattern() {
            assertNotNull(codex.getInteractivePromptPattern(),
                    "Codex should have an interactive prompt pattern");
            assertEquals("^> $", codex.getInteractivePromptPattern());
        }
    }

    // =========================================================================
    // Gemini CLI
    // =========================================================================

    @Nested
    @DisplayName("Gemini CLI agent")
    class GeminiCli {

        private AgentProvider gemini;

        @BeforeEach
        void setUp() {
            gemini = createCliAgent("gemini-cli", "cat", null, "^> $");
            lenient().when(agentRegistry.getAgent("gemini-cli")).thenReturn(Optional.of(gemini));
            lenient().when(streamParser.supportsStreamJson("gemini-cli")).thenReturn(false);
        }

        @Test
        @DisplayName("should start an interactive session with Gemini")
        void startsSession() {
            SseEmitter emitter = new SseEmitter(-1L);
            String sessionId = sessionManager.startSession(createSessionRequest("gemini-cli"), emitter);

            assertNotNull(sessionId, "Session ID should not be null for Gemini");
            waitForSessionInit();

            List<Map<String, Object>> sessions = sessionManager.listSessions();
            assertEquals(1, sessions.size());
            assertEquals("gemini-cli", sessions.get(0).get("agentName"));
        }

        @Test
        @DisplayName("should use plain-text mode (not stream-json) for Gemini")
        void usesPlainTextMode() {
            when(streamParser.supportsStreamJson("gemini-cli")).thenReturn(false);

            SseEmitter emitter = new SseEmitter(-1L);
            String sessionId = sessionManager.startSession(createSessionRequest("gemini-cli"), emitter);

            assertNotNull(sessionId);
            verify(streamParser).supportsStreamJson("gemini-cli");
        }

        @Test
        @DisplayName("should have prompt pattern for turn detection")
        void hasPromptPattern() {
            assertNotNull(gemini.getInteractivePromptPattern(),
                    "Gemini should have an interactive prompt pattern");
            assertEquals("^> $", gemini.getInteractivePromptPattern());
        }
    }

    // =========================================================================
    // Multi-agent sessions
    // =========================================================================

    @Nested
    @DisplayName("Multiple concurrent sessions")
    class MultiAgent {

        @BeforeEach
        void setUp() {
            AgentProvider claude = createCliAgent("claude-cli", "cat", null, null);
            AgentProvider codex = createCliAgent("codex-cli", "cat", null, "^> $");
            AgentProvider gemini = createCliAgent("gemini-cli", "cat", null, "^> $");

            lenient().when(agentRegistry.getAgent("claude-cli")).thenReturn(Optional.of(claude));
            lenient().when(agentRegistry.getAgent("codex-cli")).thenReturn(Optional.of(codex));
            lenient().when(agentRegistry.getAgent("gemini-cli")).thenReturn(Optional.of(gemini));
            lenient().when(streamParser.supportsStreamJson(anyString())).thenReturn(false);
            lenient().when(streamParser.supportsStreamJson("claude-cli")).thenReturn(true);
        }

        @Test
        @DisplayName("should start sessions with all three agents concurrently")
        void startAllAgents() {
            SseEmitter emitter1 = new SseEmitter(-1L);
            SseEmitter emitter2 = new SseEmitter(-1L);
            SseEmitter emitter3 = new SseEmitter(-1L);

            String claudeSession = sessionManager.startSession(createSessionRequest("claude-cli"), emitter1);
            String codexSession = sessionManager.startSession(createSessionRequest("codex-cli"), emitter2);
            String geminiSession = sessionManager.startSession(createSessionRequest("gemini-cli"), emitter3);

            assertNotNull(claudeSession, "Claude session should start");
            assertNotNull(codexSession, "Codex session should start");
            assertNotNull(geminiSession, "Gemini session should start");

            waitForSessionInit();

            // All three should be different sessions
            assertNotEquals(claudeSession, codexSession);
            assertNotEquals(codexSession, geminiSession);
            assertNotEquals(claudeSession, geminiSession);

            // All listed
            List<Map<String, Object>> sessions = sessionManager.listSessions();
            assertEquals(3, sessions.size());

            Set<String> agentNames = new HashSet<>();
            for (Map<String, Object> s : sessions) {
                agentNames.add((String) s.get("agentName"));
            }
            assertTrue(agentNames.contains("claude-cli"));
            assertTrue(agentNames.contains("codex-cli"));
            assertTrue(agentNames.contains("gemini-cli"));
        }

        @Test
        @DisplayName("should end individual sessions without affecting others")
        void endOneSession() {
            SseEmitter emitter1 = new SseEmitter(-1L);
            SseEmitter emitter2 = new SseEmitter(-1L);
            SseEmitter emitter3 = new SseEmitter(-1L);

            String claudeSession = sessionManager.startSession(createSessionRequest("claude-cli"), emitter1);
            String codexSession = sessionManager.startSession(createSessionRequest("codex-cli"), emitter2);
            String geminiSession = sessionManager.startSession(createSessionRequest("gemini-cli"), emitter3);

            waitForSessionInit();

            // End codex session
            sessionManager.endSession(codexSession);

            // Claude and Gemini should still be active
            assertNotNull(sessionManager.getStatus(claudeSession));
            assertNull(sessionManager.getStatus(codexSession), "Codex session should be gone");
            assertNotNull(sessionManager.getStatus(geminiSession));

            assertEquals(2, sessionManager.listSessions().size());
        }

        @Test
        @DisplayName("cleanup should end all sessions")
        void cleanupAll() {
            SseEmitter emitter1 = new SseEmitter(-1L);
            SseEmitter emitter2 = new SseEmitter(-1L);

            sessionManager.startSession(createSessionRequest("claude-cli"), emitter1);
            sessionManager.startSession(createSessionRequest("codex-cli"), emitter2);

            waitForSessionInit();

            assertEquals(2, sessionManager.listSessions().size());

            sessionManager.cleanup();

            assertEquals(0, sessionManager.listSessions().size());
        }
    }

    // =========================================================================
    // Error cases
    // =========================================================================

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("should return null when agent is not found")
        void agentNotFound() {
            when(agentRegistry.getAgent("nonexistent")).thenReturn(Optional.empty());

            SseEmitter emitter = new SseEmitter(-1L);
            String sessionId = sessionManager.startSession(createSessionRequest("nonexistent"), emitter);

            assertNull(sessionId, "Should return null for unknown agent");
        }

        @Test
        @DisplayName("should return null when agent is not available")
        void agentNotAvailable() {
            AgentProvider unavailable = AgentProvider.builder()
                    .name("unavailable")
                    .displayName("Unavailable Agent")
                    .command("nonexistent-binary")
                    .available(false)
                    .isDefault(false)
                    .agentType(AgentType.CLI)
                    .description("Unavailable")
                    .build();
            when(agentRegistry.getAgent("unavailable")).thenReturn(Optional.of(unavailable));

            SseEmitter emitter = new SseEmitter(-1L);
            String sessionId = sessionManager.startSession(createSessionRequest("unavailable"), emitter);

            assertNull(sessionId, "Should return null for unavailable agent");
        }

        @Test
        @DisplayName("should reject API agents (passthrough is CLI-only)")
        void rejectsApiAgent() {
            AgentProvider apiAgent = createApiAgent("openai-api");
            when(agentRegistry.getAgent("openai-api")).thenReturn(Optional.of(apiAgent));

            SseEmitter emitter = new SseEmitter(-1L);
            String sessionId = sessionManager.startSession(createSessionRequest("openai-api"), emitter);

            assertNull(sessionId, "Should return null for API agents");
        }

        @Test
        @DisplayName("sendMessage to non-existent session should return false")
        void sendToNonExistentSession() {
            boolean result = sessionManager.sendMessage("nonexistent-id", "hello");
            assertFalse(result);
        }

        @Test
        @DisplayName("getStatus for non-existent session should return null")
        void statusNonExistent() {
            assertNull(sessionManager.getStatus("nonexistent-id"));
        }

        @Test
        @DisplayName("endSession for non-existent session should not throw")
        void endNonExistent() {
            assertDoesNotThrow(() -> sessionManager.endSession("nonexistent-id"));
        }
    }

    // =========================================================================
    // Agent args pass-through
    // =========================================================================

    @Nested
    @DisplayName("Agent args pass-through in passthrough sessions")
    class AgentArgsPassthrough {

        @BeforeEach
        void setUp() {
            AgentProvider claude = createCliAgent("claude-cli", "cat", null, null);
            lenient().when(agentRegistry.getAgent("claude-cli")).thenReturn(Optional.of(claude));
            lenient().when(streamParser.supportsStreamJson("claude-cli")).thenReturn(true);
        }

        @Test
        @DisplayName("should start session with agentArgs in request")
        void startsSessionWithAgentArgs() {
            PassthroughSessionRequest request = createSessionRequest("claude-cli");
            request.setAgentArgs(List.of("--model", "opus", "--verbose"));

            SseEmitter emitter = new SseEmitter(-1L);
            String sessionId = sessionManager.startSession(request, emitter);

            // Session should be created successfully (non-null ID) even with agentArgs.
            // Note: the underlying 'cat' process may exit quickly with invalid args,
            // but the session creation itself should succeed.
            assertNotNull(sessionId, "Session should start even with agentArgs");
        }

        @Test
        @DisplayName("should start session with null agentArgs")
        void startsSessionWithNullAgentArgs() {
            PassthroughSessionRequest request = createSessionRequest("claude-cli");
            request.setAgentArgs(null);

            SseEmitter emitter = new SseEmitter(-1L);
            String sessionId = sessionManager.startSession(request, emitter);

            assertNotNull(sessionId, "Session should start with null agentArgs");
        }

        @Test
        @DisplayName("should start session with empty agentArgs")
        void startsSessionWithEmptyAgentArgs() {
            PassthroughSessionRequest request = createSessionRequest("claude-cli");
            request.setAgentArgs(List.of());

            SseEmitter emitter = new SseEmitter(-1L);
            String sessionId = sessionManager.startSession(request, emitter);

            assertNotNull(sessionId, "Session should start with empty agentArgs");
        }

        @Test
        @DisplayName("PassthroughSessionRequest agentArgs getter/setter")
        void getterSetter() {
            PassthroughSessionRequest request = new PassthroughSessionRequest();
            assertNull(request.getAgentArgs(), "Default agentArgs should be null");

            List<String> args = List.of("--model", "opus");
            request.setAgentArgs(args);
            assertEquals(args, request.getAgentArgs());
        }
    }

    // =========================================================================
    // Message sending
    // =========================================================================

    @Nested
    @DisplayName("Message sending")
    class MessageSending {

        @BeforeEach
        void setUp() {
            // Use 'cat' which reads stdin and echoes to stdout, stays alive
            AgentProvider claude = createCliAgent("claude-cli", "cat", null, null);
            lenient().when(agentRegistry.getAgent("claude-cli")).thenReturn(Optional.of(claude));
            lenient().when(streamParser.supportsStreamJson("claude-cli")).thenReturn(true);
        }

        @Test
        @DisplayName("should send a message to an active session")
        void sendMessage() {
            SseEmitter emitter = new SseEmitter(-1L);
            String sessionId = sessionManager.startSession(createSessionRequest("claude-cli"), emitter);
            assertNotNull(sessionId);
            waitForSessionInit();

            boolean sent = sessionManager.sendMessage(sessionId, "Hello Claude!");
            assertTrue(sent, "Message should be sent successfully");

            // Verify message count
            Map<String, Object> status = sessionManager.getStatus(sessionId);
            assertNotNull(status);
            assertEquals(1, status.get("messageCount"));
        }

        @Test
        @DisplayName("should increment message count for each message")
        void messageCountIncrements() {
            SseEmitter emitter = new SseEmitter(-1L);
            String sessionId = sessionManager.startSession(createSessionRequest("claude-cli"), emitter);
            assertNotNull(sessionId);
            waitForSessionInit();

            sessionManager.sendMessage(sessionId, "msg 1");
            sessionManager.sendMessage(sessionId, "msg 2");
            sessionManager.sendMessage(sessionId, "msg 3");

            Map<String, Object> status = sessionManager.getStatus(sessionId);
            assertEquals(3, status.get("messageCount"));
        }
    }
}
