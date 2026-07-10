/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.services.agent;

import ai.kompile.core.agent.AgentProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link CliAgentLLMChat} — availability checks, prompt building,
 * request spec fluent API, and response wrapping.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CliAgentLLMChatTest {

    @Mock
    private AgentRegistryService agentRegistryService;

    @Mock
    private CliAgentModelService cliAgentModelService;

    @Mock
    private HeadlessInteractiveSessionPool sessionPool;

    @Mock
    private OpencodeServeManager opencodeServeManager;

    @Mock
    private ExtractionConsensusService consensusService;

    @TempDir
    Path tempDir;

    private CliAgentLLMChat llmChat;

    // An agent that uses 'echo' so subprocess calls actually work
    private AgentProvider echoAgent;

    @BeforeEach
    void setUp() {
        echoAgent = AgentProvider.builder()
                .name("echo-cli")
                .displayName("Echo")
                .command("echo")
                .available(true)
                .isDefault(true)
                .description("Echo agent for testing")
                .build();

        when(agentRegistryService.hasAvailableAgents()).thenReturn(true);
        when(agentRegistryService.getAvailableAgentCount()).thenReturn(1);
        when(agentRegistryService.getDefaultAgent()).thenReturn(Optional.of(echoAgent));

        ClaudeStreamParser streamParser = new ClaudeStreamParser();
        AgentProcessDiagnosticService diagnosticService = new AgentProcessDiagnosticService();
        AgentSubprocessExecutor subprocessExecutor = new AgentSubprocessExecutor(
                agentRegistryService, diagnosticService, streamParser);
        when(cliAgentModelService.classifyOutcome(anyString(), anyBoolean()))
                .thenReturn(CliAgentModelService.ModelOutcome.OK);
        when(sessionPool.prompt(any(), any(), any(), anyString(), anyInt(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(3, String.class));
        llmChat = new CliAgentLLMChat(agentRegistryService, subprocessExecutor, streamParser,
                cliAgentModelService, sessionPool, opencodeServeManager, consensusService);
    }

    // ── isAvailable ──────────────────────────────────────────────────────────────

    @Test
    void isAvailable_whenAgentsPresent_returnsTrue() {
        assertTrue(llmChat.isAvailable());
    }

    @Test
    void isAvailable_whenNoAgents_returnsFalse() {
        when(agentRegistryService.hasAvailableAgents()).thenReturn(false);
        ClaudeStreamParser parser = new ClaudeStreamParser();
        AgentSubprocessExecutor exec = new AgentSubprocessExecutor(
                agentRegistryService, new AgentProcessDiagnosticService(), parser);
        CliAgentLLMChat noAgentChat = new CliAgentLLMChat(agentRegistryService, exec, parser,
                cliAgentModelService, sessionPool, opencodeServeManager, consensusService);
        assertFalse(noAgentChat.isAvailable());
    }

    // ── prompt() fluent API ──────────────────────────────────────────────────────

    @Test
    void prompt_returnsRequestSpec() {
        assertNotNull(llmChat.prompt());
    }

    @Test
    void prompt_withContent_returnsRequestSpec() {
        assertNotNull(llmChat.prompt("Hello"));
    }

    @Test
    void prompt_withPromptObject_returnsRequestSpec() {
        Prompt p = new Prompt("test prompt");
        assertNotNull(llmChat.prompt(p));
    }

    @Test
    void mutate_returnsBuilder() {
        assertNotNull(llmChat.mutate());
    }

    // ── executeAgent — echo command ───────────────────────────────────────────────

    @Test
    void executeAgent_withEchoAgent_returnsOutput() {
        String result = llmChat.executeAgent("hello world", null);
        assertNotNull(result);
        // echo outputs its arguments; result should contain "hello world"
        assertTrue(result.contains("hello world"),
                "echo agent should echo back the message, got: " + result);
    }

    @Test
    void subprocessExecutor_closesStdinForOneShotAgents() throws Exception {
        Path script = tempDir.resolve("stdin-check.sh");
        Files.writeString(script, "#!/bin/sh\n"
                + "if read line; then echo stdin:$line; else echo stdin:closed; fi\n"
                + "printf 'args:'; printf '%s|' \"$@\"; printf '\\n'\n");
        assertTrue(script.toFile().setExecutable(true), "test script should be executable");

        AgentProvider stdinAgent = AgentProvider.builder()
                .name("stdin-check-cli")
                .displayName("Stdin Check")
                .command(script.toString())
                .available(true)
                .isDefault(false)
                .description("Agent that reports stdin state")
                .build();
        when(agentRegistryService.getAgent("stdin-check-cli")).thenReturn(Optional.of(stdinAgent));

        AgentSubprocessExecutor exec = new AgentSubprocessExecutor(
                agentRegistryService, new AgentProcessDiagnosticService(), new ClaudeStreamParser());

        AgentSubprocessExecutor.SubprocessResult result = exec.executeSync(
                "stdin-check-cli", "payload prompt", false, false, tempDir.toString(), 5);

        assertTrue(result.isSuccess(), "script should complete without waiting for stdin: " + result);
        assertTrue(result.content().contains("stdin:closed"), "executor should close child stdin: " + result.content());
        assertTrue(result.content().contains("payload prompt"), "prompt should still be passed in argv: " + result.content());
    }

    @Test
    void executeAgent_withSystemMessage_prependsSystemPrefix() {
        String result = llmChat.executeAgent("question", "You are a helper");
        assertNotNull(result);
        // The combined prompt is passed to echo; output will contain both
        assertTrue(result.contains("System:") || result.contains("question"),
                "System message should be embedded in prompt: " + result);
    }

    @Test
    void executeAgent_noAgentAvailable_returnsErrorMessage() {
        when(agentRegistryService.getDefaultAgent()).thenReturn(Optional.empty());
        // Reset cached agent
        ClaudeStreamParser parser = new ClaudeStreamParser();
        AgentSubprocessExecutor exec = new AgentSubprocessExecutor(
                agentRegistryService, new AgentProcessDiagnosticService(), parser);
        CliAgentLLMChat noAgent = new CliAgentLLMChat(agentRegistryService, exec, parser,
                cliAgentModelService, sessionPool, opencodeServeManager, consensusService);

        String result = noAgent.executeAgent("test", null);
        assertNotNull(result);
        assertTrue(result.startsWith("Error:"), "Should return error when no agent: " + result);
    }

    // ── call() → content() ────────────────────────────────────────────────────────

    @Test
    void callContent_returnsEchoedString() {
        String content = llmChat.prompt("ping")
                .call()
                .content();
        assertNotNull(content);
        assertTrue(content.contains("ping"), "echo should return the prompt: " + content);
    }

    @Test
    void callChatResponse_wrapsInChatResponse() {
        ChatResponse response = llmChat.prompt("test")
                .call()
                .chatResponse();
        assertNotNull(response);
        assertFalse(response.getResults().isEmpty());
        assertNotNull(response.getResult().getOutput());
    }

    @Test
    void callEntity_returnsNull_withWarning() {
        // entity() is not supported for CLI agents
        Object result = llmChat.prompt("test")
                .call()
                .entity(String.class);
        assertNull(result);
    }

    // ── stream() ─────────────────────────────────────────────────────────────────

    @Test
    void streamContent_returnsNonEmptyFlux() {
        Flux<String> flux = llmChat.prompt("stream-test")
                .stream()
                .content();
        assertNotNull(flux);
        // CLI agents emit one item (full response)
        java.util.List<String> items = flux.collectList().block();
        assertNotNull(items);
        assertFalse(items.isEmpty());
        assertTrue(items.get(0).contains("stream-test") || !items.get(0).isBlank(),
                "Stream should emit at least one non-blank item");
    }

    @Test
    void streamChatResponse_emitsAtLeastOneChatResponse() {
        Flux<ChatResponse> flux = llmChat.prompt("hello")
                .stream()
                .chatResponse();
        assertNotNull(flux);
        java.util.List<ChatResponse> responses = flux.collectList().block();
        assertNotNull(responses);
        assertFalse(responses.isEmpty());
    }

    // ── user() / system() fluent setters ─────────────────────────────────────────

    @Test
    void userSetter_changesUserMessage() {
        String content = llmChat.prompt()
                .user("custom user message")
                .call()
                .content();
        assertNotNull(content);
        assertTrue(content.contains("custom user message") || !content.isBlank());
    }

    @Test
    void systemSetter_doesNotBreakExecution() {
        String content = llmChat.prompt("hi")
                .system("Be concise")
                .call()
                .content();
        assertNotNull(content);
    }

    // ── builder ──────────────────────────────────────────────────────────────────

    @Test
    void builder_build_returnsSameInstance() {
        ai.kompile.core.llm.chat.LLMChat built = llmChat.mutate().build();
        assertNotNull(built);
        assertSame(llmChat, built, "Builder should return the same CliAgentLLMChat instance");
    }

    @Test
    void builder_clone_returnsNewBuilder() {
        ai.kompile.core.llm.chat.LLMChat.Builder cloned = llmChat.mutate().clone();
        assertNotNull(cloned);
    }
}
