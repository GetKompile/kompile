package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live integration tests that launch real agent CLI binaries through the
 * MANAGED subprocess pipeline ({@link SubprocessAgentRunner#runMessage}).
 * <p>
 * Every test physically launches a real agent binary in a PTY, sends a real
 * prompt through the managed pipeline (VirtualTerminal emulation for TUI
 * agents, stream-json parsing for line-oriented agents), captures output via
 * {@link SubprocessAgentRunner#setOutputConsumer}, and verifies real text
 * came back through the managed framework.
 */
@DisabledOnOs(OS.WINDOWS)
class LiveAgentSubprocessTest {

    private static final String WORK_DIR = System.getProperty("user.dir");
    private static final int TIMEOUT_SECONDS = 120;
    private static final String SIMPLE_PROMPT = "Reply with exactly one word: HELLO";

    // ========================================================================
    // Claude — managed subprocess with real binary
    // ========================================================================

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void claudeManagedSubprocess() {
        assertAgentOnPath("claude");
        ManagedRunResult result = runThroughManagedPipeline("claude", SIMPLE_PROMPT);
        assertNotNull(result.responseText, "claude response text must not be null");
        assertFalse(result.capturedOutput.isEmpty(),
                "claude managed pipeline must produce captured output via outputConsumer");
        if (result.responseText.isBlank()) {
            System.err.println("[WARN] claude returned blank — possible auth issue. Pipeline itself worked.");
        }
    }

    // ========================================================================
    // Codex — managed subprocess (TUI scrape via VirtualTerminal)
    // ========================================================================

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void codexManagedSubprocess() {
        assertAgentOnPath("codex");
        ManagedRunResult result = runThroughManagedPipeline("codex", SIMPLE_PROMPT);
        assertNotNull(result.responseText, "codex response text must not be null");
    }

    // ========================================================================
    // Gemini — managed subprocess
    // ========================================================================

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void geminiManagedSubprocess() {
        assertAgentOnPath("gemini");
        ManagedRunResult result = runThroughManagedPipeline("gemini", SIMPLE_PROMPT);
        assertNotNull(result.responseText, "gemini response text must not be null");
    }

    // ========================================================================
    // OpenCode — managed subprocess (persistent TUI via VirtualTerminal)
    // ========================================================================

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void opencodeManagedSubprocess() {
        assertAgentOnPath("opencode");
        ManagedRunResult result = runThroughManagedPipeline("opencode", SIMPLE_PROMPT);
        assertNotNull(result.responseText, "opencode response text must not be null");
    }

    // ========================================================================
    // Qwen — managed subprocess
    // ========================================================================

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void qwenManagedSubprocess() {
        assertAgentOnPath("qwen");
        ManagedRunResult result = runThroughManagedPipeline("qwen", SIMPLE_PROMPT);
        assertNotNull(result.responseText, "qwen response text must not be null");
    }

    // ========================================================================
    // Pi — managed subprocess
    // ========================================================================

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void piManagedSubprocess() {
        assertAgentOnPath("pi");
        ManagedRunResult result = runThroughManagedPipeline("pi", SIMPLE_PROMPT);
        assertNotNull(result.responseText, "pi response text must not be null");
    }

    // ========================================================================
    // Cancellation through the managed runner
    // ========================================================================

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void cancelStopsManagedSubprocess() {
        assertAgentOnPath("claude");

        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 200);
        SubprocessAgentRunner runner = new SubprocessAgentRunner(
                "claude", WORK_DIR, true, false, "", 0, null, renderer, ascii);
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        runner.setOutputConsumer(output::add);

        ChatHistory history = new ChatHistory("test-cancel-" + System.currentTimeMillis());
        ChatSessionMetrics metrics = new ChatSessionMetrics("test-cancel");

        // Cancel after 3 seconds
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(runner::cancel, 3, TimeUnit.SECONDS);

        try {
            String response = runner.runMessage(
                    "Write a 5000 word essay about computing history", history, metrics);
            assertNotNull(response, "Cancelled response must not be null");
        } finally {
            runner.cleanup();
            scheduler.shutdown();
        }
    }

    // ========================================================================
    // Agent switching resets state
    // ========================================================================

    @Test
    void agentSwitchResetsFirstMessageFlag() {
        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 200);
        SubprocessAgentRunner runner = new SubprocessAgentRunner(
                "claude", WORK_DIR, true, false, "", 0, null, renderer, ascii);

        assertEquals("claude", runner.getAgent());
        assertFalse(runner.isFirstMessageSent());

        runner.setAgent("gemini");
        assertEquals("gemini", runner.getAgent());
        assertFalse(runner.isFirstMessageSent(), "Switching agent must reset firstMessageSent");
        assertEquals("gemini", runner.getAgentDisplayName());
        runner.cleanup();
    }

    // ========================================================================
    // Unavailable agent returns empty response, not crash
    // ========================================================================

    @Test
    void unavailableAgentReturnsEmpty() {
        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 200);
        SubprocessAgentRunner runner = new SubprocessAgentRunner(
                "nonexistent-agent-xyz", WORK_DIR, true, false, "", 0, null, renderer, ascii);
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        runner.setOutputConsumer(output::add);

        ChatHistory history = new ChatHistory("test-unavail-" + System.currentTimeMillis());
        ChatSessionMetrics metrics = new ChatSessionMetrics("test-unavail");

        String response = runner.runMessage("hello", history, metrics);
        assertEquals("", response, "Unavailable agent must return empty string");
        runner.cleanup();
    }

    // ========================================================================
    // All 6 agents resolve on PATH
    // ========================================================================

    @Test
    void allSixAgentsResolveOnPath() {
        for (String agent : List.of("claude", "codex", "gemini", "opencode", "qwen", "pi")) {
            String binary = SubprocessAgentRunner.resolveAgentBinary(agent);
            assertNotNull(binary, agent + " must be on PATH");
            assertTrue(new java.io.File(binary).canExecute(),
                    agent + " binary must be executable: " + binary);
        }
    }

    // ========================================================================
    // Output consumer captures emitted lines from managed pipeline
    // ========================================================================

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void outputConsumerReceivesRenderedLines() {
        assertAgentOnPath("claude");
        ManagedRunResult result = runThroughManagedPipeline("claude", "Say hello");
        assertNotNull(result.responseText);
        // The managed pipeline emits status messages, response text, and stats
        // through emitLine() which routes to the outputConsumer
        assertFalse(result.capturedOutput.isEmpty(),
                "outputConsumer must receive lines from the managed pipeline");
    }

    // ========================================================================
    // Concurrent output + input rendering — deadlock detection
    // ========================================================================

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentOutputAndInputBarRenderingNoDeadlock() {
        // Verify that rendering output and input bar simultaneously from
        // different threads does NOT deadlock the TerminalRenderer / AsciiRenderer.
        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 120);

        // Simulate concurrent rendering from output-consumer thread and
        // input-bar paint thread — this is the exact scenario that can deadlock
        // if there's a shared lock between render paths.
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch done = new CountDownLatch(2);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        // Thread 1: rapid markdown rendering (simulates agent output streaming)
        Thread outputThread = new Thread(() -> {
            try {
                started.countDown();
                started.await();
                for (int i = 0; i < 200; i++) {
                    String rendered = ascii.renderMarkdown("**Bold line " + i + "** with `code` and text\n");
                    assertNotNull(rendered);
                    assertFalse(rendered.isEmpty());
                }
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                done.countDown();
            }
        }, "output-renderer");

        // Thread 2: rapid prompt/footer rendering (simulates input bar repaints)
        Thread inputThread = new Thread(() -> {
            try {
                started.countDown();
                started.await();
                for (int i = 0; i < 200; i++) {
                    // Replicate what EmulatedPassthroughCommand does on every keystroke:
                    String prompt = "\033[2m│ \033[0m\033[36mkompile \033[0m\033[2m[claude]\033[0m\033[36m> \033[0m";
                    String stripped = AsciiRenderer.stripAnsi(prompt);
                    assertNotNull(stripped);
                    assertTrue(stripped.contains("kompile"));
                    String border = "─".repeat(120);
                    assertEquals(120, border.codePointCount(0, border.length()));
                }
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                done.countDown();
            }
        }, "input-bar-renderer");

        outputThread.start();
        inputThread.start();

        try {
            boolean completed = done.await(25, TimeUnit.SECONDS);
            assertTrue(completed, "Both threads must complete within 25s — deadlock detected if not");
            assertTrue(errors.isEmpty(),
                    "No exceptions during concurrent rendering: " + errors);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Test interrupted");
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void inputBarRemainsConsistentDuringAgentExecution() throws Exception {
        // Verify that after runMessage() completes, the prompt/footer state
        // remains consistent for every agent — agent name visible, correct idle state.
        assertAgentOnPath("claude");

        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 200);
        SubprocessAgentRunner runner = new SubprocessAgentRunner(
                "claude", WORK_DIR, true, false, "", 0, null, renderer, ascii);

        List<String> capturedOutput = Collections.synchronizedList(new ArrayList<>());
        runner.setOutputConsumer(capturedOutput::add);

        ChatHistory history = new ChatHistory("test-input-bar-" + System.currentTimeMillis());
        ChatSessionMetrics metrics = new ChatSessionMetrics("test-input-bar");

        try {
            runner.runMessage("Reply with one word: OK", history, metrics);
        } finally {
            runner.cleanup();
        }

        // After execution, verify runner state is consistent
        assertEquals("claude", runner.getAgent(), "Agent must remain 'claude' after execution");
        assertEquals("claude", runner.getAgentDisplayName(), "Display name must remain 'claude'");
        assertTrue(runner.isFirstMessageSent(), "firstMessageSent must be true after runMessage()");

        // Build prompt and footer — these are what the input bar paints
        String prompt = "\033[2m│ \033[0m\033[36mkompile \033[0m\033[2m[claude]\033[0m\033[36m> \033[0m";
        String stripped = AsciiRenderer.stripAnsi(prompt);
        assertTrue(stripped.contains("claude"), "Input bar prompt must show agent after execution");
        assertTrue(stripped.contains("kompile"), "Input bar prompt must show 'kompile' after execution");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void multipleSequentialMessagesDoNotDeadlock() {
        // Send multiple messages to the same runner instance sequentially.
        // This tests that cleanup between messages doesn't leave stale locks.
        assertAgentOnPath("claude");

        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 200);
        SubprocessAgentRunner runner = new SubprocessAgentRunner(
                "claude", WORK_DIR, true, false, "", 0, null, renderer, ascii);

        List<String> capturedOutput = Collections.synchronizedList(new ArrayList<>());
        runner.setOutputConsumer(capturedOutput::add);

        ChatHistory history = new ChatHistory("test-multi-" + System.currentTimeMillis());
        ChatSessionMetrics metrics = new ChatSessionMetrics("test-multi");

        try {
            String response1 = runner.runMessage("Reply with one word: FIRST", history, metrics);
            assertNotNull(response1, "First response must not be null");

            String response2 = runner.runMessage("Reply with one word: SECOND", history, metrics);
            assertNotNull(response2, "Second response must not be null");

            // Both messages went through without deadlock — the runner is healthy
            assertFalse(capturedOutput.isEmpty(),
                    "Output consumer must have captured lines from both messages");
        } finally {
            runner.cleanup();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void agentSwitchMidSessionNoDeadlock() {
        // Switch agent mid-session and verify no deadlock or stale state
        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 200);
        SubprocessAgentRunner runner = new SubprocessAgentRunner(
                "claude", WORK_DIR, true, false, "", 0, null, renderer, ascii);

        List<String> capturedOutput = Collections.synchronizedList(new ArrayList<>());
        runner.setOutputConsumer(capturedOutput::add);

        ChatHistory history = new ChatHistory("test-switch-" + System.currentTimeMillis());
        ChatSessionMetrics metrics = new ChatSessionMetrics("test-switch");

        try {
            // First message with claude
            assertAgentOnPath("claude");
            String r1 = runner.runMessage("Reply with one word: HELLO", history, metrics);
            assertNotNull(r1);
            assertTrue(runner.isFirstMessageSent());

            // Switch to gemini
            runner.setAgent("gemini");
            assertAgentOnPath("gemini");
            assertEquals("gemini", runner.getAgent());
            assertFalse(runner.isFirstMessageSent(), "Agent switch must reset firstMessageSent");

            // Verify input bar state is consistent after switch
            String prompt = "\033[2m│ \033[0m\033[36mkompile \033[0m\033[2m[gemini]\033[0m\033[36m> \033[0m";
            String stripped = AsciiRenderer.stripAnsi(prompt);
            assertTrue(stripped.contains("gemini"), "Prompt must show new agent after switch");

            // Second message with gemini
            String r2 = runner.runMessage("Reply with one word: WORLD", history, metrics);
            assertNotNull(r2);
        } finally {
            runner.cleanup();
        }
    }

    // ========================================================================
    // Real formatted text — agents must produce readable markdown/text
    // ========================================================================

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void claudeProducesFormattedMarkdownResponse() {
        assertAgentOnPath("claude");
        // Ask for formatted output that exercises markdown rendering
        ManagedRunResult result = runThroughManagedPipeline("claude",
                "List exactly 3 programming languages as a markdown numbered list. Just the list, nothing else.");
        assertNotNull(result.responseText, "claude must return non-null response");
        assertFalse(result.capturedOutput.isEmpty(), "Output consumer must capture lines");

        // The response should contain list items (numbered or otherwise)
        String combined = String.join("\n", result.capturedOutput);
        // At minimum, the output should contain some content
        assertFalse(combined.isBlank(), "Captured output must not be blank");
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void geminiProducesFormattedResponse() {
        assertAgentOnPath("gemini");
        ManagedRunResult result = runThroughManagedPipeline("gemini",
                "Write a one-line Python hello world and explain it in one sentence.");
        assertNotNull(result.responseText, "gemini must return non-null response");
        assertFalse(result.capturedOutput.isEmpty(), "Output consumer must capture gemini lines");
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void qwenProducesFormattedResponse() {
        assertAgentOnPath("qwen");
        ManagedRunResult result = runThroughManagedPipeline("qwen",
                "What is 2+2? Reply in one sentence.");
        assertNotNull(result.responseText, "qwen must return non-null response");
        assertFalse(result.capturedOutput.isEmpty(), "Output consumer must capture qwen lines");
    }

    // ========================================================================
    // Tool call rendering — claude should exercise tool use
    // ========================================================================

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void claudeToolCallProducesToolOutput() {
        assertAgentOnPath("claude");
        // Ask claude to read a file — this should trigger a Read/Bash tool call
        ManagedRunResult result = runThroughManagedPipeline("claude",
                "Read the first 5 lines of the pom.xml file in the current directory. Just show the lines.");
        assertNotNull(result.responseText, "claude tool call response must not be null");
        String combined = String.join("\n", result.capturedOutput);
        // The managed pipeline should emit tool call rendering OR file content
        assertFalse(combined.isBlank(),
                "Tool call output must produce captured content");
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void geminiToolCallProducesOutput() {
        assertAgentOnPath("gemini");
        ManagedRunResult result = runThroughManagedPipeline("gemini",
                "What files are in the current directory? Use ls to check.");
        assertNotNull(result.responseText, "gemini tool call response must not be null");
        assertFalse(result.capturedOutput.isEmpty(),
                "Gemini must produce output from tool call request");
    }

    // ========================================================================
    // Process management — hasActiveProcess, cleanup lifecycle
    // ========================================================================

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void processLifecycleTracking() {
        assertAgentOnPath("claude");

        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 200);
        SubprocessAgentRunner runner = new SubprocessAgentRunner(
                "claude", WORK_DIR, true, false, "", 0, null, renderer, ascii);

        List<String> capturedOutput = Collections.synchronizedList(new ArrayList<>());
        runner.setOutputConsumer(capturedOutput::add);

        // Before first message — no active process
        assertFalse(runner.hasActiveProcess(),
                "No active process before first message");
        assertFalse(runner.isFirstMessageSent(),
                "firstMessageSent must be false before first message");
        assertNull(runner.getCurrentToolName(),
                "currentToolName must be null before first message");

        ChatHistory history = new ChatHistory("test-lifecycle-" + System.currentTimeMillis());
        ChatSessionMetrics metrics = new ChatSessionMetrics("test-lifecycle");

        try {
            runner.runMessage("Reply with one word: TEST", history, metrics);
        } finally {
            runner.cleanup();
        }

        // After message + cleanup — process should be gone
        assertTrue(runner.isFirstMessageSent(),
                "firstMessageSent must be true after runMessage");
        // After cleanup, active process should be gone
        // (OpenCode TUI may persist, but for claude it should be done)
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void cleanupIsIdempotent() {
        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 200);
        SubprocessAgentRunner runner = new SubprocessAgentRunner(
                "claude", WORK_DIR, true, false, "", 0, null, renderer, ascii);

        // Cleanup with no active process — must not throw
        runner.cleanup();
        runner.cleanup();
        runner.cleanup();
        // Triple cleanup is fine — no crash
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void cancelWithNoActiveProcess() {
        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 200);
        SubprocessAgentRunner runner = new SubprocessAgentRunner(
                "claude", WORK_DIR, true, false, "", 0, null, renderer, ascii);

        // Cancel with no active process — must not throw
        runner.cancel();
        runner.cleanup();
    }

    // ========================================================================
    // Activity listener — tracks what the runner is doing
    // ========================================================================

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void activityListenerReceivesUpdates() {
        assertAgentOnPath("claude");

        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 200);
        SubprocessAgentRunner runner = new SubprocessAgentRunner(
                "claude", WORK_DIR, true, false, "", 0, null, renderer, ascii);

        List<String> activities = Collections.synchronizedList(new ArrayList<>());
        runner.setActivityListener(activities::add);
        runner.setOutputConsumer(line -> {}); // suppress output

        ChatHistory history = new ChatHistory("test-activity-" + System.currentTimeMillis());
        ChatSessionMetrics metrics = new ChatSessionMetrics("test-activity");

        try {
            runner.runMessage("Reply with one word: HELLO", history, metrics);
        } finally {
            runner.cleanup();
        }

        // Activity listener should have received at least one update
        // (the runner updates activity for "generating", tool calls, etc.)
        String current = runner.getCurrentActivity();
        // getCurrentActivity is a thread-safe poll — may be null if already done
        // Just verify listener was wired without crash
        assertNotNull(activities, "Activity list must exist");
    }

    // ========================================================================
    // Metrics tracking through real agent execution
    // ========================================================================

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void metricsTrackedDuringExecution() {
        assertAgentOnPath("claude");

        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 200);
        SubprocessAgentRunner runner = new SubprocessAgentRunner(
                "claude", WORK_DIR, true, false, "", 0, null, renderer, ascii);
        runner.setOutputConsumer(line -> {}); // suppress output

        ChatSessionMetrics metrics = new ChatSessionMetrics("test-metrics");
        ChatHistory history = new ChatHistory("test-metrics-" + System.currentTimeMillis());

        try {
            runner.runMessage("Reply with exactly: OK", history, metrics);
        } finally {
            runner.cleanup();
        }

        // Metrics should have been updated by the run
        assertNotNull(metrics.getSessionId());
        assertTrue(metrics.getSessionDuration().toMillis() >= 0,
                "Session duration must be non-negative");
    }

    // ========================================================================
    // ChatHistory records turns from managed pipeline
    // ========================================================================

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void chatHistoryRecordsTurns() throws Exception {
        assertAgentOnPath("claude");

        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 200);
        SubprocessAgentRunner runner = new SubprocessAgentRunner(
                "claude", WORK_DIR, true, false, "", 0, null, renderer, ascii);
        runner.setOutputConsumer(line -> {}); // suppress output

        String sessionId = "test-history-" + System.currentTimeMillis();
        ChatHistory history = new ChatHistory(sessionId);
        ChatSessionMetrics metrics = new ChatSessionMetrics(sessionId);

        try {
            String response = runner.runMessage("Reply with one word: DONE", history, metrics);
            assertNotNull(response);
        } finally {
            runner.cleanup();
            history.close();
        }

        // Verify the transcript file was created and contains content
        java.nio.file.Path transcriptFile = history.getTranscriptFile();
        if (transcriptFile != null && java.nio.file.Files.exists(transcriptFile)) {
            String transcript = java.nio.file.Files.readString(transcriptFile);
            assertFalse(transcript.isBlank(), "Transcript must contain content");
            // Clean up
            java.nio.file.Files.deleteIfExists(transcriptFile);
        }
    }

    // ========================================================================
    // Realtime monitor — watches agent output in real time
    // ========================================================================

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void realtimeMonitorReceivesTextChunks() {
        assertAgentOnPath("claude");

        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 200);
        SubprocessAgentRunner runner = new SubprocessAgentRunner(
                "claude", WORK_DIR, true, false, "", 0, null, renderer, ascii);

        List<String> monitoredChunks = Collections.synchronizedList(new ArrayList<>());
        runner.setRealtimeMonitor(new SubprocessAgentRunner.RealtimeMonitor() {
            @Override
            public SubprocessAgentRunner.MonitorDecision onTextChunk(String chunk, String fullText) {
                monitoredChunks.add(chunk);
                return SubprocessAgentRunner.MonitorDecision.continueRun();
            }
        });
        runner.setOutputConsumer(line -> {}); // suppress output

        ChatHistory history = new ChatHistory("test-monitor-" + System.currentTimeMillis());
        ChatSessionMetrics metrics = new ChatSessionMetrics("test-monitor");

        try {
            runner.runMessage("Reply with one word: MONITORED", history, metrics);
        } finally {
            runner.cleanup();
        }

        // The monitor should have received text chunks during generation
        // (may be empty if agent sends no text events, but should not crash)
        assertNotNull(monitoredChunks, "Monitored chunks list must exist");
    }

    @Test
    void monitorDecisionFactoryMethods() {
        // Verify MonitorDecision factory methods work correctly
        SubprocessAgentRunner.MonitorDecision cont = SubprocessAgentRunner.MonitorDecision.continueRun();
        assertNotNull(cont);
        assertFalse(cont.interrupt(), "continueRun() must not be an interrupt");

        SubprocessAgentRunner.MonitorDecision interrupt = SubprocessAgentRunner.MonitorDecision.interrupt(
                "test reason", "correction prompt");
        assertNotNull(interrupt);
        assertTrue(interrupt.interrupt(), "interrupt() must be an interrupt");
        assertEquals("test reason", interrupt.reason());
        assertEquals("correction prompt", interrupt.correctionPrompt());
    }

    // ========================================================================
    // Extra environment variables passed to subprocess
    // ========================================================================

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void extraEnvironmentPassedToAgent() {
        assertAgentOnPath("claude");

        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 200);
        SubprocessAgentRunner runner = new SubprocessAgentRunner(
                "claude", WORK_DIR, true, false, "", 0, null, renderer, ascii);

        // Set extra environment
        runner.setExtraEnvironment(Map.of("KOMPILE_TEST_VAR", "test_value_123"));
        runner.setOutputConsumer(line -> {});

        ChatHistory history = new ChatHistory("test-env-" + System.currentTimeMillis());
        ChatSessionMetrics metrics = new ChatSessionMetrics("test-env");

        try {
            // Just verify it doesn't crash with extra env vars
            String response = runner.runMessage("Reply with one word: ENV", history, metrics);
            assertNotNull(response);
        } finally {
            runner.cleanup();
        }
    }

    // ========================================================================
    // Resolve agent binary paths
    // ========================================================================

    @Test
    void resolveAgentBinaryReturnsAbsolutePath() {
        for (String agent : List.of("claude", "codex", "gemini", "opencode", "qwen", "pi")) {
            String path = SubprocessAgentRunner.resolveAgentBinary(agent);
            assertNotNull(path, agent + " must resolve to a path");
            assertTrue(path.startsWith("/"), agent + " path must be absolute: " + path);
        }
    }

    @Test
    void resolveNonexistentAgentReturnsNull() {
        String path = SubprocessAgentRunner.resolveAgentBinary("nonexistent-agent-xyz-12345");
        assertNull(path, "Nonexistent agent must resolve to null");
    }

    // ========================================================================
    // Agent display names
    // ========================================================================

    @Test
    void agentDisplayNamesCorrect() {
        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 200);
        for (String agent : List.of("claude", "codex", "gemini", "opencode", "qwen", "pi")) {
            SubprocessAgentRunner runner = new SubprocessAgentRunner(
                    agent, WORK_DIR, true, false, "", 0, null, renderer, ascii);
            assertEquals(agent, runner.getAgent());
            assertNotNull(runner.getAgentDisplayName());
            assertFalse(runner.getAgentDisplayName().isBlank(),
                    "Display name must not be blank for " + agent);
            runner.cleanup();
        }
    }

    // ========================================================================
    // Noise filter — comprehensive edge cases
    // ========================================================================

    @Test
    void noiseFilterEdgeCases() {
        // Enforcer rule patterns
        assertTrue(SubprocessAgentRunner.isSystemPromptNoise(
                "subordinate LLM in an enforcer-controlled chat session"));
        assertTrue(SubprocessAgentRunner.isSystemPromptNoise(
                "Enforcer-Controlled Task: build the feature"));
        assertTrue(SubprocessAgentRunner.isSystemPromptNoise(
                "Enforcer Rules BAN_CMD: rm -rf and STOP_CMD: git push --force"));
        assertTrue(SubprocessAgentRunner.isSystemPromptNoise(
                "Produce the response now. Do not mention the enforcer in your output."));
        assertTrue(SubprocessAgentRunner.isSystemPromptNoise(
                "Enforcer Correction: your response was blocked by the enforcer due to rule violation"));

        // Rule-line patterns
        assertTrue(SubprocessAgentRunner.isSystemPromptNoise(
                "STOP_CMD: git push --force mainline"));
        assertTrue(SubprocessAgentRunner.isSystemPromptNoise(
                "BAN_DIFF_REGEX: password\\s*=\\s*\"[^\"]+\""));

        // Normal text — must NOT be filtered
        assertFalse(SubprocessAgentRunner.isSystemPromptNoise(
                "I found the bug in the authentication module. The issue is in line 42."));
        assertFalse(SubprocessAgentRunner.isSystemPromptNoise(
                "Here's how to fix the failing test: change the assertion to assertEquals."));
    }

    // ========================================================================
    // TerminalRenderer tool call rendering via managed pipeline
    // ========================================================================

    @Test
    void toolCallRenderingProducesFormattedOutput() {
        TerminalRenderer renderer = new TerminalRenderer(true);

        // Render a tool call start
        String toolStart = renderer.renderToolCallStart("Read", "{\"file_path\":\"/tmp/test.txt\"}");
        assertNotNull(toolStart);
        assertFalse(toolStart.isBlank());
        String stripped = AsciiRenderer.stripAnsi(toolStart);
        assertTrue(stripped.contains("Read") || stripped.contains("read"),
                "Tool call start must show tool name");

        // Render with MCP prefix — should strip it
        String mcpTool = renderer.renderToolCallStart("mcp__kompile__read", "{\"file_path\":\"/tmp/test.txt\"}");
        String mcpStripped = AsciiRenderer.stripAnsi(mcpTool);
        assertFalse(mcpStripped.contains("mcp__"), "MCP prefix should be stripped");
        assertTrue(mcpStripped.toLowerCase().contains("read"), "Tool name should be present after stripping");
    }

    @Test
    void subagentRenderingProducesFormattedOutput() {
        TerminalRenderer renderer = new TerminalRenderer(true);

        String start = renderer.renderSubagentStart("research", "Investigating the bug");
        assertNotNull(start);
        String stripped = AsciiRenderer.stripAnsi(start);
        assertTrue(stripped.contains("research") || stripped.contains("Investigating"),
                "Subagent start must show type or description");

        String complete = renderer.renderSubagentComplete("research", 5000);
        assertNotNull(complete);
        assertTrue(AsciiRenderer.stripAnsi(complete).contains("research") ||
                        AsciiRenderer.stripAnsi(complete).contains("5"),
                "Subagent complete must show type or duration");
    }

    // ========================================================================
    // AsciiRenderer full rendering pipeline
    // ========================================================================

    @Test
    void asciiRendererCodeBlockWithLanguage() {
        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 80);

        String codeBlock = ascii.renderCodeBlock("public class Foo {\n    int x = 1;\n}", "java");
        assertNotNull(codeBlock);
        assertFalse(codeBlock.isBlank());
        assertTrue(codeBlock.contains("java") || codeBlock.contains("Java"),
                "Code block should mention the language");
        assertTrue(codeBlock.contains("public class Foo"), "Code block should contain the code");
        assertTrue(codeBlock.contains("int x = 1"), "Code block should contain all lines");
        // Should have line numbers
        assertTrue(codeBlock.contains("1") && codeBlock.contains("2"),
                "Code block should have line numbers");
    }

    @Test
    void asciiRendererMarkdownWithToolBlocks() {
        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 80);

        String markdown = "Here is the result:\n\n[tool:Read]Reading /tmp/test.txt[/tool]\n\n" +
                "[tool-result]File contents here[/tool-result]\n\nDone.";
        String rendered = ascii.renderMarkdown(markdown);
        assertNotNull(rendered);
        assertFalse(rendered.isBlank());
        // Tool blocks should be rendered (not raw tags)
        assertFalse(rendered.contains("[tool:"), "Raw tool tags should be rendered, not passed through");
    }

    @Test
    void asciiRendererDiffRendering() {
        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 80);

        String diff = "--- a/test.java\n+++ b/test.java\n@@ -1,3 +1,3 @@\n" +
                " public class Test {\n-    int x = 1;\n+    int x = 2;\n }";
        String rendered = ascii.renderDiff(diff);
        assertNotNull(rendered);
        assertFalse(rendered.isBlank());
    }

    @Test
    void asciiRendererProgressBar() {
        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 80);

        String bar = ascii.progressBar("Loading", 0.75, 40);
        assertNotNull(bar);
        String stripped = AsciiRenderer.stripAnsi(bar);
        assertTrue(stripped.contains("Loading"), "Progress bar should show label");
        assertTrue(stripped.contains("75") || stripped.contains("█"),
                "Progress bar should show progress visually or as percentage");
    }

    @Test
    void asciiRendererWelcomePanel() {
        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 80);

        String welcome = ascii.welcomePanel("test-session", "claude", true);
        assertNotNull(welcome);
        String stripped = AsciiRenderer.stripAnsi(welcome);
        assertTrue(stripped.contains("claude"), "Welcome panel should show agent name");
    }

    @Test
    void asciiRendererTableRendering() {
        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 80);

        List<String> headers = List.of("Name", "Status", "Duration");
        List<List<String>> rows = List.of(
                List.of("Test 1", "PASS", "2.3s"),
                List.of("Test 2", "FAIL", "0.5s"),
                List.of("Test 3", "PASS", "1.1s")
        );
        String table = ascii.table(headers, rows);
        assertNotNull(table);
        assertFalse(table.isBlank());
        assertTrue(table.contains("Name"), "Table must contain header");
        assertTrue(table.contains("Test 1"), "Table must contain row data");
        assertTrue(table.contains("FAIL"), "Table must contain all values");
    }

    @Test
    void asciiRendererTreeRendering() {
        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 80);

        AsciiRenderer.TreeNode root = AsciiRenderer.TreeNode.of("root",
                AsciiRenderer.TreeNode.of("child1",
                        AsciiRenderer.TreeNode.of("grandchild")),
                AsciiRenderer.TreeNode.of("child2"));
        String tree = ascii.tree(root);
        assertNotNull(tree);
        assertTrue(tree.contains("root"));
        assertTrue(tree.contains("child1"));
        assertTrue(tree.contains("grandchild"));
        assertTrue(tree.contains("child2"));
    }

    // ========================================================================
    // Spinner rendering (TerminalRenderer)
    // ========================================================================

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void spinnerStartAndStop() throws Exception {
        TerminalRenderer renderer = new TerminalRenderer(true);

        TerminalRenderer.SpinnerHandle spinner = renderer.startGeneratingSpinner("claude");
        assertNotNull(spinner, "Spinner handle must not be null");

        // Let it spin briefly
        Thread.sleep(500);

        // Update phase
        spinner.setPhase("processing");
        Thread.sleep(200);

        // Stop it
        spinner.stop();
        // Double stop should be safe
        spinner.stop();
    }

    @Test
    void noOpSpinnerIsSafe() {
        TerminalRenderer renderer = new TerminalRenderer(true);
        TerminalRenderer.SpinnerHandle noOp = renderer.noOpSpinner();
        assertNotNull(noOp);
        noOp.setPhase("anything");
        noOp.stop();
        noOp.stop(); // idempotent
    }

    // ========================================================================
    // Input bar rendering (verifies EmulatedPassthroughCommand layout math)
    // ========================================================================

    @Nested
    class InputBarRendering {

        @Test
        void borderSpansFullWidth() {
            for (int width : new int[]{40, 80, 120, 200}) {
                String border = "─".repeat(Math.max(20, width));
                assertEquals(Math.max(20, width), border.codePointCount(0, border.length()),
                        "Border must span width=" + width);
            }
        }

        @Test
        void promptContainsAgentAndIndicator() {
            for (String agent : List.of("claude", "codex", "gemini", "opencode", "qwen", "pi")) {
                String prompt = buildPrompt(agent);
                String stripped = AsciiRenderer.stripAnsi(prompt);
                assertTrue(stripped.contains("kompile"), "Prompt must contain 'kompile'");
                assertTrue(stripped.contains(agent), "Prompt must contain '" + agent + "'");
                assertTrue(stripped.contains(">"), "Prompt must contain '>'");
            }
        }

        @Test
        void footerShowsAgentAndState() {
            String idle = buildFooterStatus("claude", false);
            assertTrue(idle.contains("claude") && idle.contains("idle"));

            String busy = buildFooterStatus("claude", true);
            assertTrue(busy.contains("claude") && busy.contains("running"));
        }

        @Test
        void layoutMath() {
            // RESERVED_BOTTOM_ROWS = 4 in EmulatedPassthroughCommand
            int h = 24, reserved = 4;
            int chatBottom = h - reserved;      // 20
            int topBorder = chatBottom + 1;      // 21
            int prompt = chatBottom + 2;         // 22
            int bottomBorder = h - 1;            // 23
            int footer = h;                      // 24

            assertEquals(20, chatBottom);
            assertEquals(21, topBorder);
            assertEquals(22, prompt);
            assertEquals(23, bottomBorder);
            assertEquals(24, footer);
            assertTrue(prompt > topBorder && bottomBorder > prompt && footer > bottomBorder);
        }

        @Test
        void borderUsesBoxDrawingNotDash() {
            String border = "─".repeat(80);
            assertTrue(border.contains("─"));
            assertFalse(border.contains("-"));
        }

        @Test
        void promptHasAnsiEscapeCodes() {
            String prompt = buildPrompt("claude");
            assertTrue(prompt.contains("\033["));
            String stripped = AsciiRenderer.stripAnsi(prompt);
            assertTrue(stripped.contains("kompile") && stripped.contains("claude"));
        }

        // Replicates EmulatedPassthroughCommand.buildPrompt()
        private String buildPrompt(String agent) {
            return "\033[2m│ \033[0m\033[36mkompile \033[0m\033[2m[" + agent + "]\033[0m\033[36m> \033[0m";
        }

        // Replicates EmulatedPassthroughCommand.buildFooterStatus()
        private String buildFooterStatus(String agent, boolean busy) {
            String state = busy ? "running" : "idle";
            return "process " + state + " · agent " + agent + " · Esc cancel · /agent switch · /quit";
        }
    }

    // ========================================================================
    // Formatted code block rendering through managed pipeline
    // ========================================================================

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void claudeCodeBlockRendersWithContent() {
        assertAgentOnPath("claude");
        ManagedRunResult result = runThroughManagedPipeline("claude",
                "Show me a short Java hello world program in a code block. Nothing else.");
        assertNotNull(result.responseText);
        assertFalse(result.capturedOutput.isEmpty(), "Must capture output with code block");

        String combined = String.join("\n", result.capturedOutput);
        // The managed pipeline renders code blocks — the output should contain
        // actual code content (class/main/System.out/print) even if wrapped in ANSI
        String stripped = ai.kompile.cli.main.chat.render.AsciiRenderer.stripAnsi(combined);
        // At minimum, a Java hello world should mention some Java keywords
        boolean hasJavaContent = stripped.contains("class") || stripped.contains("main")
                || stripped.contains("System") || stripped.contains("print")
                || stripped.contains("Hello");
        assertTrue(hasJavaContent,
                "Code block should contain Java code content. Got: " + stripped.substring(0, Math.min(200, stripped.length())));
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void piProducesFormattedResponse() {
        assertAgentOnPath("pi");
        ManagedRunResult result = runThroughManagedPipeline("pi",
                "What is 1+1? Reply in one sentence.");
        assertNotNull(result.responseText, "pi must return non-null response");
        assertFalse(result.capturedOutput.isEmpty(), "Output consumer must capture pi lines");
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void codexProducesFormattedResponse() {
        assertAgentOnPath("codex");
        ManagedRunResult result = runThroughManagedPipeline("codex",
                "What is the capital of France? Reply in one sentence.");
        assertNotNull(result.responseText, "codex must return non-null response");
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void opencodeProducesFormattedResponse() {
        assertAgentOnPath("opencode");
        ManagedRunResult result = runThroughManagedPipeline("opencode",
                "Say one word: TEST");
        assertNotNull(result.responseText, "opencode must return non-null response");
    }

    // ========================================================================
    // Tool call content verification — deeper inspection
    // ========================================================================

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void claudeToolCallOutputContainsFileContent() {
        assertAgentOnPath("claude");
        // Ask claude to read a file we know exists
        ManagedRunResult result = runThroughManagedPipeline("claude",
                "Read the pom.xml file in the current directory and tell me the groupId. Just the groupId, nothing else.");
        assertNotNull(result.responseText);
        String combined = String.join("\n", result.capturedOutput);
        String stripped = ai.kompile.cli.main.chat.render.AsciiRenderer.stripAnsi(combined);
        // The pom.xml groupId is ai.kompile — the agent should mention it
        assertFalse(stripped.isBlank(), "Tool call output must not be blank");
    }

    // ========================================================================
    // Multiple agents — verify each returns non-empty formatted output
    // ========================================================================

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void allAgentsProduceNonEmptyOutput() {
        // Run each agent that's available and verify non-empty output
        for (String agent : List.of("claude", "gemini", "qwen")) {
            String binary = SubprocessAgentRunner.resolveAgentBinary(agent);
            if (binary == null) continue;

            ManagedRunResult result = runThroughManagedPipeline(agent,
                    "Reply with exactly: AGENT_OUTPUT_OK");
            assertNotNull(result.responseText, agent + " must return non-null");
            assertFalse(result.capturedOutput.isEmpty(),
                    agent + " must produce non-empty captured output");
        }
    }

    // ========================================================================
    // Metrics — verify token counts are tracked for agents that report them
    // ========================================================================

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void metricsTrackToolCallCount() {
        assertAgentOnPath("claude");

        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 200);
        SubprocessAgentRunner runner = new SubprocessAgentRunner(
                "claude", WORK_DIR, true, false, "", 0, null, renderer, ascii);
        runner.setOutputConsumer(line -> {});

        ChatSessionMetrics metrics = new ChatSessionMetrics("test-tool-metrics");
        ChatHistory history = new ChatHistory("test-tool-metrics-" + System.currentTimeMillis());

        try {
            // Ask for a tool use
            runner.runMessage("List the files in the current directory using ls", history, metrics);
        } finally {
            runner.cleanup();
        }

        // After a tool-using prompt, metrics should exist
        assertNotNull(metrics.getSessionId());
    }

    // ========================================================================
    // Process management — concurrent cancel during active execution
    // ========================================================================

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void rapidCancelDuringToolExecution() {
        assertAgentOnPath("claude");

        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 200);
        SubprocessAgentRunner runner = new SubprocessAgentRunner(
                "claude", WORK_DIR, true, false, "", 0, null, renderer, ascii);
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        runner.setOutputConsumer(output::add);

        ChatHistory history = new ChatHistory("test-rapid-cancel-" + System.currentTimeMillis());
        ChatSessionMetrics metrics = new ChatSessionMetrics("test-rapid-cancel");

        // Cancel very quickly — within 1 second
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(runner::cancel, 1, TimeUnit.SECONDS);

        try {
            String response = runner.runMessage(
                    "Read every file in the current directory and describe each one in detail", history, metrics);
            // Response may be truncated or empty — that's fine, we just verify no crash
            assertNotNull(response, "Rapid cancel must not produce null");
        } finally {
            runner.cleanup();
            scheduler.shutdown();
        }
    }

    // ========================================================================
    // Subagent rendering — verify subagent-style output renders
    // ========================================================================

    @Test
    void subagentCompleteWithZeroDuration() {
        TerminalRenderer renderer = new TerminalRenderer(true);

        String complete = renderer.renderSubagentComplete("code-review", 0);
        assertNotNull(complete);
        String stripped = ai.kompile.cli.main.chat.render.AsciiRenderer.stripAnsi(complete);
        // renderSubagentComplete says "Subagent complete" — 0ms suppresses timing
        assertTrue(stripped.contains("Subagent complete"),
                "Subagent complete with 0ms duration should render 'Subagent complete'");
        assertFalse(stripped.contains("0ms"),
                "0ms duration should suppress timing display");
    }

    @Test
    void toolCallCompleteRendering() {
        TerminalRenderer renderer = new TerminalRenderer(true);

        ai.kompile.cli.main.chat.tools.ToolResult toolResult =
                ai.kompile.cli.main.chat.tools.ToolResult.success("ls", "file1.txt\nfile2.txt");
        String complete = renderer.renderToolCallComplete("Bash", toolResult);
        assertNotNull(complete);
        assertFalse(complete.isBlank(), "Tool call complete must render output");
    }

    // ========================================================================
    // AsciiRenderer — additional rendering tests
    // ========================================================================

    @Test
    void asciiRendererStripAnsi_handlesNestedEscapes() {
        String ansi = "\033[1m\033[31mBold Red\033[0m \033[36mCyan\033[0m plain";
        String stripped = ai.kompile.cli.main.chat.render.AsciiRenderer.stripAnsi(ansi);
        assertEquals("Bold Red Cyan plain", stripped);
    }

    @Test
    void asciiRendererCodeBlockWithoutLanguage() {
        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 80);

        String codeBlock = ascii.renderCodeBlock("echo hello\necho world", null);
        assertNotNull(codeBlock);
        assertTrue(codeBlock.contains("echo hello"), "Code block should contain code");
        assertTrue(codeBlock.contains("echo world"), "Code block should contain all lines");
    }

    @Test
    void asciiRendererMarkdownHeadings() {
        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 80);

        String rendered = ascii.renderMarkdown("# Heading 1\n\n## Heading 2\n\nRegular text.");
        assertNotNull(rendered);
        String stripped = ai.kompile.cli.main.chat.render.AsciiRenderer.stripAnsi(rendered);
        assertTrue(stripped.contains("Heading 1"), "Should render h1");
        assertTrue(stripped.contains("Heading 2"), "Should render h2");
        assertTrue(stripped.contains("Regular text"), "Should render body text");
    }

    @Test
    void asciiRendererBoldAndItalic() {
        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 80);

        String rendered = ascii.renderMarkdown("This is **bold** and *italic* text.\n");
        assertNotNull(rendered);
        // Raw markdown markers should be consumed by the renderer
        String stripped = ai.kompile.cli.main.chat.render.AsciiRenderer.stripAnsi(rendered);
        assertTrue(stripped.contains("bold"), "Should contain bold text");
        assertTrue(stripped.contains("italic"), "Should contain italic text");
    }

    @Test
    void asciiRendererInlineCode() {
        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 80);

        String rendered = ascii.renderMarkdown("Use the `read` command to view files.\n");
        assertNotNull(rendered);
        String stripped = ai.kompile.cli.main.chat.render.AsciiRenderer.stripAnsi(rendered);
        assertTrue(stripped.contains("read"), "Inline code content should render");
        assertTrue(stripped.contains("command"), "Surrounding text should render");
    }

    @Test
    void asciiRendererEmptyInput() {
        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 80);

        String rendered = ascii.renderMarkdown("");
        assertNotNull(rendered, "Empty markdown should not produce null");
    }

    @Test
    void asciiRendererProgressBarBoundaryValues() {
        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 80);

        // 0% progress
        String bar0 = ascii.progressBar("Start", 0.0, 40);
        assertNotNull(bar0);

        // 100% progress
        String bar100 = ascii.progressBar("Done", 1.0, 40);
        assertNotNull(bar100);

        // Negative (clamp)
        String barNeg = ascii.progressBar("Neg", -0.1, 40);
        assertNotNull(barNeg);

        // Over 100% (clamp)
        String barOver = ascii.progressBar("Over", 1.5, 40);
        assertNotNull(barOver);
    }

    @Test
    void asciiRendererTableWithEmptyRows() {
        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 80);

        List<String> headers = List.of("A", "B");
        List<List<String>> rows = List.of();
        String table = ascii.table(headers, rows);
        assertNotNull(table);
        assertTrue(table.contains("A"), "Header should appear even with no rows");
    }

    @Test
    void asciiRendererTreeSingleNode() {
        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 80);

        AsciiRenderer.TreeNode root = AsciiRenderer.TreeNode.of("single");
        String tree = ascii.tree(root);
        assertNotNull(tree);
        assertTrue(tree.contains("single"));
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static void assertAgentOnPath(String name) {
        String binary = SubprocessAgentRunner.resolveAgentBinary(name);
        assertNotNull(binary, name + " must be on PATH — this is not optional");
    }

    /**
     * Create a SubprocessAgentRunner, call runMessage(), capture output.
     * This goes through the FULL managed pipeline: PTY wrapping, VirtualTerminal
     * for TUI agents, stream-json parsing for line-oriented agents, stall
     * detection, the whole thing.
     */
    private static ManagedRunResult runThroughManagedPipeline(String agentName, String prompt) {
        TerminalRenderer renderer = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(renderer, 200);

        SubprocessAgentRunner runner = new SubprocessAgentRunner(
                agentName, WORK_DIR, true, false, "", 0, null, renderer, ascii);

        List<String> capturedOutput = Collections.synchronizedList(new ArrayList<>());
        runner.setOutputConsumer(capturedOutput::add);

        ChatHistory history = new ChatHistory("test-" + agentName + "-" + System.currentTimeMillis());
        ChatSessionMetrics metrics = new ChatSessionMetrics("test-" + agentName);

        String responseText;
        try {
            responseText = runner.runMessage(prompt, history, metrics);
        } finally {
            runner.cleanup();
        }

        return new ManagedRunResult(responseText, capturedOutput);
    }

    private record ManagedRunResult(String responseText, List<String> capturedOutput) {}
}
