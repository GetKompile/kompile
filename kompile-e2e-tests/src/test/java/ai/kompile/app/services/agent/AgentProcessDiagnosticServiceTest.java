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

import ai.kompile.core.agent.ProcessState;
import ai.kompile.core.agent.ProcessStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AgentProcessDiagnosticService} — process tracking, state transitions,
 * history management, and diagnostic summaries.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentProcessDiagnosticServiceTest {

    private AgentProcessDiagnosticService service;

    @BeforeEach
    void setUp() {
        service = new AgentProcessDiagnosticService();
    }

    // ── startProcess ────────────────────────────────────────────────────────────

    @Test
    void startProcess_returnsStatusWithStartingState() {
        ProcessStatus status = service.startProcess("claude-cli", List.of("claude", "--help"));
        assertNotNull(status);
        assertNotNull(status.getId());
        assertEquals("claude-cli", status.getAgentName());
        assertEquals(ProcessState.STARTING, status.getState());
    }

    @Test
    void startProcess_multipleProcesses_trackedIndependently() {
        ProcessStatus s1 = service.startProcess("agent-a", List.of("a"));
        ProcessStatus s2 = service.startProcess("agent-b", List.of("b"));
        assertNotEquals(s1.getId(), s2.getId());
        assertTrue(service.hasActiveProcess());
    }

    // ── processStarted ──────────────────────────────────────────────────────────

    @Test
    void processStarted_setsRunningStateAndPid() {
        ProcessStatus status = service.startProcess("claude-cli", List.of("claude"));
        String id = status.getId();

        service.processStarted(id, 12345L);

        Optional<ProcessStatus> found = service.getProcess(id);
        assertTrue(found.isPresent());
        assertEquals(ProcessState.RUNNING, found.get().getState());
        assertEquals(12345L, found.get().getPid());
    }

    @Test
    void processStarted_unknownId_doesNotThrow() {
        assertDoesNotThrow(() -> service.processStarted("unknown-id", 999L));
    }

    // ── processStreaming ────────────────────────────────────────────────────────

    @Test
    void processStreaming_changesRunningToStreaming() {
        ProcessStatus status = service.startProcess("claude-cli", List.of("claude"));
        String id = status.getId();
        service.processStarted(id, 100L);

        service.processStreaming(id);

        assertEquals(ProcessState.STREAMING, service.getProcess(id).get().getState());
    }

    @Test
    void processStreaming_whenAlreadyStreaming_remainsStreaming() {
        ProcessStatus status = service.startProcess("claude-cli", List.of("claude"));
        String id = status.getId();
        service.processStarted(id, 100L);
        service.processStreaming(id);
        service.processStreaming(id); // second call

        assertEquals(ProcessState.STREAMING, service.getProcess(id).get().getState());
    }

    // ── outputReceived ──────────────────────────────────────────────────────────

    @Test
    void outputReceived_incrementsLineCount() {
        ProcessStatus status = service.startProcess("claude-cli", List.of("claude"));
        String id = status.getId();
        service.processStarted(id, 100L);

        service.outputReceived(id, "line 1");
        service.outputReceived(id, "line 2");
        service.outputReceived(id, "line 3");

        assertEquals(3, service.getProcess(id).get().getLinesReceived());
    }

    @Test
    void outputReceived_transitionsRunningToStreaming() {
        ProcessStatus status = service.startProcess("agent", List.of("agent"));
        String id = status.getId();
        service.processStarted(id, 200L);

        assertEquals(ProcessState.RUNNING, service.getProcess(id).get().getState());
        service.outputReceived(id, "some output");
        assertEquals(ProcessState.STREAMING, service.getProcess(id).get().getState());
    }

    // ── processCompleted ────────────────────────────────────────────────────────

    @Test
    void processCompleted_exitCodeZero_completedState() {
        ProcessStatus status = service.startProcess("claude-cli", List.of("claude"));
        String id = status.getId();
        service.processStarted(id, 300L);

        service.processCompleted(id, 0);

        // After completion, moves to history
        Optional<ProcessStatus> found = service.getProcess(id);
        assertTrue(found.isPresent());
        assertEquals(ProcessState.COMPLETED, found.get().getState());
        assertNotNull(found.get().getEndTime());
    }

    @Test
    void processCompleted_nonZeroExit_failedState() {
        ProcessStatus status = service.startProcess("claude-cli", List.of("claude"));
        String id = status.getId();
        service.processStarted(id, 300L);

        service.processCompleted(id, 1);

        Optional<ProcessStatus> found = service.getProcess(id);
        assertTrue(found.isPresent());
        assertEquals(ProcessState.FAILED, found.get().getState());
    }

    @Test
    void processCompleted_removesFromActive_addsToHistory() {
        ProcessStatus status = service.startProcess("agent", List.of("a"));
        String id = status.getId();
        service.processStarted(id, 100L);
        service.processCompleted(id, 0);

        // hasActiveProcess should decrease
        assertFalse(service.hasActiveProcess());

        // Should appear in history
        List<ProcessStatus> history = service.getHistory();
        assertTrue(history.stream().anyMatch(p -> p.getId().equals(id)));
    }

    // ── processFailed ───────────────────────────────────────────────────────────

    @Test
    void processFailed_setsFailedStateAndError() {
        ProcessStatus status = service.startProcess("agent", List.of("a"));
        String id = status.getId();
        service.processStarted(id, 100L);

        service.processFailed(id, "Out of memory");

        Optional<ProcessStatus> found = service.getProcess(id);
        assertTrue(found.isPresent());
        assertEquals(ProcessState.FAILED, found.get().getState());
        assertEquals("Out of memory", found.get().getErrorMessage());
    }

    // ── processTimedOut ─────────────────────────────────────────────────────────

    @Test
    void processTimedOut_setsTimeoutState() {
        ProcessStatus status = service.startProcess("agent", List.of("a"));
        String id = status.getId();
        service.processStarted(id, 100L);

        service.processTimedOut(id);

        Optional<ProcessStatus> found = service.getProcess(id);
        assertTrue(found.isPresent());
        assertEquals(ProcessState.TIMEOUT, found.get().getState());
        assertEquals("Process timed out", found.get().getErrorMessage());
    }

    // ── processCancelled ────────────────────────────────────────────────────────

    @Test
    void processCancelled_setsCancelledState() {
        ProcessStatus status = service.startProcess("agent", List.of("a"));
        String id = status.getId();
        service.processStarted(id, 100L);

        service.processCancelled(id);

        Optional<ProcessStatus> found = service.getProcess(id);
        assertTrue(found.isPresent());
        assertEquals(ProcessState.CANCELLED, found.get().getState());
    }

    // ── getProcess ──────────────────────────────────────────────────────────────

    @Test
    void getProcess_unknownId_returnsEmpty() {
        Optional<ProcessStatus> found = service.getProcess("no-such-id");
        assertFalse(found.isPresent());
    }

    @Test
    void getProcess_findsActiveProcess() {
        ProcessStatus status = service.startProcess("agent", List.of("a"));
        String id = status.getId();

        Optional<ProcessStatus> found = service.getProcess(id);
        assertTrue(found.isPresent());
        assertEquals(id, found.get().getId());
    }

    // ── getCurrentProcess ───────────────────────────────────────────────────────

    @Test
    void getCurrentProcess_withNoActive_returnsEmpty() {
        assertTrue(service.getCurrentProcess().isEmpty());
    }

    @Test
    void getCurrentProcess_withOneActive_returnsIt() {
        service.startProcess("agent", List.of("cmd"));
        assertTrue(service.getCurrentProcess().isPresent());
    }

    // ── hasActiveProcess ────────────────────────────────────────────────────────

    @Test
    void hasActiveProcess_initially_false() {
        assertFalse(service.hasActiveProcess());
    }

    @Test
    void hasActiveProcess_afterStart_true() {
        service.startProcess("a", List.of("a"));
        assertTrue(service.hasActiveProcess());
    }

    @Test
    void hasActiveProcess_afterCompletion_false() {
        ProcessStatus s = service.startProcess("a", List.of("a"));
        service.processStarted(s.getId(), 1L);
        service.processCompleted(s.getId(), 0);
        assertFalse(service.hasActiveProcess());
    }

    // ── getHistory / clearHistory ───────────────────────────────────────────────

    @Test
    void getHistory_initiallyEmpty() {
        assertTrue(service.getHistory().isEmpty());
    }

    @Test
    void getHistory_afterCompletion_containsProcess() {
        ProcessStatus s = service.startProcess("a", List.of("a"));
        service.processStarted(s.getId(), 1L);
        service.processCompleted(s.getId(), 0);

        List<ProcessStatus> history = service.getHistory();
        assertEquals(1, history.size());
        assertEquals(s.getId(), history.get(0).getId());
    }

    @Test
    void clearHistory_removesAllHistory() {
        ProcessStatus s = service.startProcess("a", List.of("a"));
        service.processStarted(s.getId(), 1L);
        service.processCompleted(s.getId(), 0);

        service.clearHistory();
        assertTrue(service.getHistory().isEmpty());
    }

    @Test
    void history_cappedAtMaxHistory() {
        // Create 25 processes (MAX_HISTORY is 20)
        for (int i = 0; i < 25; i++) {
            ProcessStatus s = service.startProcess("agent-" + i, List.of("cmd"));
            service.processStarted(s.getId(), (long) i);
            service.processCompleted(s.getId(), 0);
        }
        List<ProcessStatus> history = service.getHistory();
        assertTrue(history.size() <= 20, "History should be capped at 20, got: " + history.size());
    }

    // ── getSummary ──────────────────────────────────────────────────────────────

    @Test
    void getSummary_noProcesses_summaryIsEmpty() {
        DiagnosticSummary summary = service.getSummary();
        assertNotNull(summary);
        assertFalse(summary.isHasActiveProcess());
        assertEquals(0, summary.getRecentProcessCount());
    }

    @Test
    void getSummary_withActiveProcess_reflectsIt() {
        ProcessStatus s = service.startProcess("claude-cli", List.of("claude"));
        service.processStarted(s.getId(), 999L);

        DiagnosticSummary summary = service.getSummary();
        assertTrue(summary.isHasActiveProcess());
        assertEquals(s.getId(), summary.getActiveProcessId());
        assertEquals("claude-cli", summary.getActiveAgentName());
    }

    @Test
    void getSummary_countsFailedProcesses() {
        ProcessStatus s1 = service.startProcess("a", List.of("a"));
        service.processStarted(s1.getId(), 1L);
        service.processFailed(s1.getId(), "err");

        ProcessStatus s2 = service.startProcess("b", List.of("b"));
        service.processStarted(s2.getId(), 2L);
        service.processCompleted(s2.getId(), 0);

        DiagnosticSummary summary = service.getSummary();
        assertEquals(1, summary.getFailedProcessCount());
        assertEquals(2, summary.getRecentProcessCount());
    }

    // ── getFullReport ────────────────────────────────────────────────────────────

    @Test
    void getFullReport_containsSummaryAndHistory() {
        ProcessStatus s = service.startProcess("agent", List.of("a"));
        service.processStarted(s.getId(), 1L);
        service.processCompleted(s.getId(), 0);

        FullDiagnosticReport report = service.getFullReport();
        assertNotNull(report);
        assertNotNull(report.getSummary());
        assertNotNull(report.getRecentProcesses());
        assertFalse(report.getRecentProcesses().isEmpty());
    }

    // ── chunkStreamed / fileModified ─────────────────────────────────────────────

    @Test
    void chunkStreamed_incrementsCounter() {
        ProcessStatus s = service.startProcess("agent", List.of("a"));
        service.processStarted(s.getId(), 1L);

        service.chunkStreamed(s.getId());
        service.chunkStreamed(s.getId());

        assertEquals(2, service.getProcess(s.getId()).get().getChunksStreamed());
    }

    @Test
    void fileModified_addsToModifiedFiles() {
        ProcessStatus s = service.startProcess("agent", List.of("a"));
        service.processStarted(s.getId(), 1L);

        service.fileModified(s.getId(), "/src/Foo.java");
        service.fileModified(s.getId(), "/src/Bar.java");

        List<String> files = service.getProcess(s.getId()).get().getModifiedFiles();
        assertTrue(files.contains("/src/Foo.java"));
        assertTrue(files.contains("/src/Bar.java"));
    }

    @Test
    void fileModified_noDuplicates() {
        ProcessStatus s = service.startProcess("agent", List.of("a"));
        service.processStarted(s.getId(), 1L);

        service.fileModified(s.getId(), "/src/Foo.java");
        service.fileModified(s.getId(), "/src/Foo.java"); // duplicate

        List<String> files = service.getProcess(s.getId()).get().getModifiedFiles();
        assertEquals(1, files.stream().filter(f -> f.equals("/src/Foo.java")).count());
    }
}
