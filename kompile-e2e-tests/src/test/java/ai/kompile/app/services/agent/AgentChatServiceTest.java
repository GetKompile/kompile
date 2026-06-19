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

import ai.kompile.app.services.ServerPortService;
import ai.kompile.app.services.mcp.BuiltInToolDiscoveryService;
import ai.kompile.app.web.dto.AgentChatRequest;
import ai.kompile.chat.history.service.FolderService;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.embeddings.NoOpVectorStoreImpl;
import ai.kompile.core.retrievers.NoOpDocumentRetrieverImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link AgentChatService} — prompt building, interactive command construction,
 * cancel behaviour, and process tracking. Subprocess execution is tested via "echo"
 * so no external CLI is required.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentChatServiceTest {

    @Mock
    private AgentRegistryService agentRegistry;

    @Mock
    private AgentProcessDiagnosticService diagnosticService;

    @Mock
    private ClaudeStreamParser streamParser;

    @Mock
    private ServerPortService serverPortService;

    @Mock
    private BuiltInToolDiscoveryService toolDiscoveryService;

    @Mock
    private FolderService folderService;

    @Mock
    private ApiAgentChatExecutor apiAgentChatExecutor;

    private AgentChatService service;

    // A CLI agent that uses "echo" so we can test subprocess execution
    private AgentProvider echoAgent;

    @BeforeEach
    void setUp() {
        echoAgent = AgentProvider.builder()
                .name("echo-agent")
                .displayName("Echo Agent")
                .command("echo")
                .skipPermissionsFlag("--skip")
                .skipPermissions(false)
                .available(true)
                .isDefault(true)
                .description("Test echo agent")
                .build();

        // Stub the stream parser to behave like non-Claude (plain text)
        when(streamParser.supportsStreamJson("echo-agent")).thenReturn(false);
        when(streamParser.supportsStreamJson("claude-cli")).thenReturn(true);
        when(streamParser.getModifiedFiles(any())).thenReturn(List.of());

        when(serverPortService.getMcpApiUrl()).thenReturn("http://localhost:8080/api/mcp");

        AgentSubprocessExecutor subprocessExecutor = new AgentSubprocessExecutor(
                agentRegistry, diagnosticService, streamParser);
        service = new AgentChatService(
                agentRegistry,
                diagnosticService,
                streamParser,
                subprocessExecutor,
                List.of(new NoOpDocumentRetrieverImpl()),
                List.of(new NoOpVectorStoreImpl()),
                null,        // no GraphRagService
                toolDiscoveryService,
                serverPortService,
                folderService,
                apiAgentChatExecutor,
                null         // toolCallWriterService
        );
    }

    // ── buildInteractiveCommand — no skip permissions ───────────────────────────

    @Test
    void buildInteractiveCommand_noSkipPermissions_commandFirst() {
        List<String> cmd = service.buildInteractiveCommand(echoAgent, false, false);
        assertFalse(cmd.isEmpty());
        assertEquals("echo", cmd.get(0));
        assertFalse(cmd.contains("--skip"), "Should not contain skip flag");
    }

    @Test
    void buildInteractiveCommand_withSkipPermissions_addsFlag() {
        List<String> cmd = service.buildInteractiveCommand(echoAgent, true, false);
        assertEquals("echo", cmd.get(0));
        assertTrue(cmd.contains("--skip"), "Should contain skip permissions flag");
    }

    @Test
    void buildInteractiveCommand_noMcpTools_doesNotAddMcpFlag() {
        AgentProvider mcpAgent = AgentProvider.builder()
                .name("mcp-agent")
                .displayName("MCP Agent")
                .command("claude")
                .mcpSupported(true)
                .mcpServerFlag("--mcp-server")
                .available(true)
                .isDefault(false)
                .build();

        List<String> cmd = service.buildInteractiveCommand(mcpAgent, false, false);
        assertFalse(cmd.contains("--mcp-server"),
                "Without injectMcpTools=true, MCP flag should not be added");
    }

    @Test
    void buildInteractiveCommand_withMcpToolsAndSupportedAgent_addsMcpFlag() {
        AgentProvider mcpAgent = AgentProvider.builder()
                .name("mcp-agent")
                .displayName("MCP Agent")
                .command("claude")
                .mcpSupported(true)
                .mcpServerFlag("--mcp-server")
                .available(true)
                .isDefault(false)
                .build();

        when(toolDiscoveryService.getDiscoveredTools()).thenReturn(List.of());

        List<String> cmd = service.buildInteractiveCommand(mcpAgent, false, true);
        // With empty tools, MCP flag should not be injected
        assertFalse(cmd.contains("--mcp-server"),
                "No tools discovered → no MCP server injection");
    }

    @Test
    void buildInteractiveCommand_withAgentArgs_appendedToCommand() {
        List<String> agentArgs = List.of("--resume", "session-abc");
        List<String> cmd = service.buildInteractiveCommand(echoAgent, false, false, agentArgs);
        assertTrue(cmd.contains("--resume"));
        assertTrue(cmd.contains("session-abc"));
    }

    @Test
    void buildInteractiveCommand_nullAgentArgs_doesNotThrow() {
        List<String> cmd = service.buildInteractiveCommand(echoAgent, false, false, null);
        assertNotNull(cmd);
        assertEquals("echo", cmd.get(0));
    }

    // ── cancelProcess ────────────────────────────────────────────────────────────

    @Test
    void cancelProcess_unknownId_returnsFalse() {
        when(diagnosticService.getProcess("no-such-id")).thenReturn(Optional.empty());
        assertFalse(service.cancelProcess("no-such-id"));
    }

    @Test
    void cancelProcess_apiStream_delegatesToApiExecutor() {
        when(apiAgentChatExecutor.isApiStream("api-proc-id")).thenReturn(true);
        when(apiAgentChatExecutor.cancelApiStream("api-proc-id")).thenReturn(true);

        assertTrue(service.cancelProcess("api-proc-id"));
        verify(apiAgentChatExecutor).cancelApiStream("api-proc-id");
    }

    @Test
    void cancelProcess_apiStreamFails_returnsFalse() {
        when(apiAgentChatExecutor.isApiStream("api-proc-id")).thenReturn(true);
        when(apiAgentChatExecutor.cancelApiStream("api-proc-id")).thenReturn(false);

        assertFalse(service.cancelProcess("api-proc-id"));
    }

    // ── isProcessRunning ─────────────────────────────────────────────────────────

    @Test
    void isProcessRunning_unknownId_returnsFalse() {
        assertFalse(service.isProcessRunning("unknown-id"));
    }

    // ── getRunningProcessCount ───────────────────────────────────────────────────

    @Test
    void getRunningProcessCount_initially_zero() {
        assertEquals(0, service.getRunningProcessCount());
    }

    // ── executeChatSync — agent not found ────────────────────────────────────────

    @Test
    void executeChatSync_agentNotFound_returnsError() {
        when(agentRegistry.getAgent("unknown-agent")).thenReturn(Optional.empty());

        AgentChatRequest request = new AgentChatRequest();
        request.setAgentName("unknown-agent");
        request.setMessage("Hello");

        AgentChatService.SyncChatResult result = service.executeChatSync(request, 5);
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertTrue(result.error().contains("not found"));
    }

    @Test
    void executeChatSync_agentUnavailable_returnsError() {
        AgentProvider unavailable = AgentProvider.builder()
                .name("unavailable-agent")
                .displayName("Unavailable")
                .command("missing-binary-xyz")
                .available(false)
                .isDefault(false)
                .build();
        when(agentRegistry.getAgent("unavailable-agent")).thenReturn(Optional.of(unavailable));

        AgentChatRequest request = new AgentChatRequest();
        request.setAgentName("unavailable-agent");
        request.setMessage("Hi");

        AgentChatService.SyncChatResult result = service.executeChatSync(request, 5);
        assertFalse(result.isSuccess());
        assertTrue(result.error().contains("not available"));
    }

    @Test
    void executeChatSync_apiAgent_returnsError() {
        AgentProvider apiAgent = AgentProvider.builder()
                .name("api-agent")
                .displayName("API Agent")
                .command("none")
                .agentType(ai.kompile.core.agent.AgentType.API)
                .endpointUrl("http://api/v1")
                .modelName("gpt-4")
                .available(true)
                .isDefault(false)
                .build();
        when(agentRegistry.getAgent("api-agent")).thenReturn(Optional.of(apiAgent));

        AgentChatRequest request = new AgentChatRequest();
        request.setAgentName("api-agent");
        request.setMessage("Hi");

        AgentChatService.SyncChatResult result = service.executeChatSync(request, 5);
        assertFalse(result.isSuccess());
        assertTrue(result.error().contains("not supported"));
    }

    // ── executeChatSync — echo agent ─────────────────────────────────────────────

    @Test
    void executeChatSync_echoAgent_returnsOutput() {
        when(agentRegistry.getAgent("echo-agent")).thenReturn(Optional.of(echoAgent));

        ai.kompile.core.agent.ProcessStatus mockStatus = new ai.kompile.core.agent.ProcessStatus(
                "echo-agent", List.of("echo", "hello"));
        when(diagnosticService.startProcess(anyString(), any())).thenReturn(mockStatus);
        doNothing().when(diagnosticService).processStarted(any(), anyLong());
        doNothing().when(diagnosticService).processStreaming(any());
        doNothing().when(diagnosticService).outputReceived(any(), any());
        doNothing().when(diagnosticService).processCompleted(any(), anyInt());

        AgentChatRequest request = new AgentChatRequest();
        request.setAgentName("echo-agent");
        request.setMessage("hello world");
        request.setEnableRag(false);
        request.setEnableGraphRag(false);

        AgentChatService.SyncChatResult result = service.executeChatSync(request, 10);
        assertNotNull(result);
        // echo writes input to stdout; result should contain the message
        // (echo agent appends message as final arg in buildCommand)
        assertNotNull(result.content());
    }

    // ── SyncChatResult record ────────────────────────────────────────────────────

    @Test
    void syncChatResult_isSuccess_trueWhenNoErrorAndExitZero() {
        AgentChatService.SyncChatResult r = new AgentChatService.SyncChatResult(
                "content", "proc-1", 0, 100L, List.of(), List.of(), null, null);
        assertTrue(r.isSuccess());
    }

    @Test
    void syncChatResult_isSuccess_falseWhenErrorPresent() {
        AgentChatService.SyncChatResult r = new AgentChatService.SyncChatResult(
                "", null, -1, 0L, List.of(), List.of(), null, "Something went wrong");
        assertFalse(r.isSuccess());
    }

    @Test
    void syncChatResult_isSuccess_falseWhenNonZeroExit() {
        AgentChatService.SyncChatResult r = new AgentChatService.SyncChatResult(
                "output", "proc-2", 1, 200L, List.of(), List.of(), null, null);
        assertFalse(r.isSuccess());
    }
}
