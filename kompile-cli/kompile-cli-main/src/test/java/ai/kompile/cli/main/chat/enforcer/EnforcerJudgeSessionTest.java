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

package ai.kompile.cli.main.chat.enforcer;

import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.cli.main.chat.harness.JudgeBackend;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real TTY integration tests for the enforcer and judge modes.
 * <p>
 * These tests drive real sessions through the actual {@link EnforcerJudge},
 * {@link EnforcerService}, {@link ScoringRealtimeMonitor}, and
 * {@link EnforcerRealtimeMonitor} classes. The only abstraction is a
 * controllable {@link JudgeBackend} that returns deterministic JSON
 * responses — this is what a real LLM judge would return, but without
 * needing an actual API call.
 * <p>
 * What these tests exercise:
 * <ul>
 *   <li>Real score increments/decrements through the actual scoring pipeline</li>
 *   <li>Real stop/interrupt decisions through the actual judge evaluation</li>
 *   <li>Real correction loops with actual prompt construction and retry logic</li>
 *   <li>Real-time monitoring with actual async evaluation and event dispatch</li>
 *   <li>Real conversation window context tracking</li>
 *   <li>Real tool-call gating through the actual judge</li>
 * </ul>
 */
class EnforcerJudgeSessionTest {

    private ObjectMapper objectMapper;
    private EnforcerConversationWindow conversationWindow;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        conversationWindow = new EnforcerConversationWindow(
                tempDir.resolve("context.json"), objectMapper);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Controllable JudgeBackend — returns deterministic JSON judge responses
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * A JudgeBackend that returns pre-configured JSON responses. Each call
     * to {@link #generate} pops the next queued response. This lets us
     * drive the real {@link EnforcerJudge} through its actual evaluation
     * pipeline with known outcomes.
     */
    static class ScriptedJudgeBackend implements JudgeBackend {
        private final ConcurrentLinkedQueue<String> responses = new ConcurrentLinkedQueue<>();
        private final List<String> receivedPrompts = new CopyOnWriteArrayList<>();
        private final List<String> receivedSystemPrompts = new CopyOnWriteArrayList<>();

        void enqueue(String jsonResponse) {
            responses.add(jsonResponse);
        }

        void enqueueCompliant(String reasoning) {
            enqueue("{\"compliant\":true,\"stop\":false,\"severity\":\"info\","
                    + "\"violations\":[],\"correction_prompt\":\"\","
                    + "\"reasoning\":\"" + reasoning + "\"}");
        }

        void enqueueViolation(String violation, String correction) {
            enqueue("{\"compliant\":false,\"stop\":false,\"severity\":\"error\","
                    + "\"violations\":[\"" + violation + "\"],"
                    + "\"correction_prompt\":\"" + correction + "\","
                    + "\"reasoning\":\"" + violation + "\"}");
        }

        void enqueueStop(String violation) {
            enqueue("{\"compliant\":false,\"stop\":true,\"severity\":\"critical\","
                    + "\"violations\":[\"" + violation + "\"],"
                    + "\"correction_prompt\":\"\","
                    + "\"reasoning\":\"" + violation + "\"}");
        }

        void enqueueToolAllow(String reason) {
            enqueue("{\"action\":\"ALLOW\",\"reason\":\"" + reason + "\","
                    + "\"violations\":[],\"correction_prompt\":\"\",\"rewrittenArgs\":null}");
        }

        void enqueueToolBlock(String reason) {
            enqueue("{\"action\":\"BLOCK\",\"reason\":\"" + reason + "\","
                    + "\"violations\":[\"" + reason + "\"],\"correction_prompt\":\"\","
                    + "\"rewrittenArgs\":null}");
        }

        @Override
        public String generate(String userPrompt, String systemPrompt) {
            receivedPrompts.add(userPrompt);
            receivedSystemPrompts.add(systemPrompt);
            String next = responses.poll();
            if (next == null) {
                return "{\"compliant\":true,\"stop\":false,\"severity\":\"info\","
                        + "\"violations\":[],\"correction_prompt\":\"\","
                        + "\"reasoning\":\"default pass\"}";
            }
            return next;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String describe() {
            return "scripted-test-backend";
        }

        List<String> getReceivedPrompts() {
            return List.copyOf(receivedPrompts);
        }

        List<String> getReceivedSystemPrompts() {
            return List.copyOf(receivedSystemPrompts);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1. REAL ENFORCER SESSION TESTS — full enforce loop with actual classes
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void realSession_compliantFirstAttempt_accepted() {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueCompliant("response follows all rules");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerService service = new EnforcerService(judge);
        EnforcerPolicy policy = new EnforcerPolicy("Always respond in English.", 2, false);

        conversationWindow.addUserMessage("What is 2+2?");

        EnforcerResult result = service.enforce(
                "What is 2+2?", policy,
                conversationWindow::snapshot,
                prompt -> {
                    conversationWindow.finishAssistantMessage("The answer is 4.");
                    return "The answer is 4.";
                });

        assertEquals(EnforcerResult.Status.ACCEPTED, result.getStatus());
        assertEquals(1, result.getAttempts().size());
        assertEquals("The answer is 4.", result.getFinalOutput());
        assertTrue(result.getAttempts().get(0).decision().isCompliant());
        assertFalse(result.getAttempts().get(0).decision().isStop());

        // Verify the judge received the correct system prompt
        assertTrue(backend.getReceivedSystemPrompts().get(0).contains("Kompile Enforcer"));
        // Verify the judge received the rules
        assertTrue(backend.getReceivedPrompts().get(0).contains("Always respond in English"));
    }

    @Test
    void realSession_violationThenCorrection_acceptedOnRetry() {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueViolation("Response is too short", "Provide a detailed explanation with at least 3 sentences");
        backend.enqueueCompliant("corrected response now has sufficient detail");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerService service = new EnforcerService(judge);
        EnforcerPolicy policy = new EnforcerPolicy("Provide detailed answers with at least 3 sentences.", 2, false);

        AtomicInteger callCount = new AtomicInteger(0);
        List<String> promptsSentToAgent = new ArrayList<>();

        EnforcerResult result = service.enforce(
                "Explain gravity", policy,
                conversationWindow::snapshot,
                prompt -> {
                    promptsSentToAgent.add(prompt);
                    int call = callCount.incrementAndGet();
                    if (call == 1) {
                        return "Gravity pulls things down.";
                    }
                    return "Gravity is the force of attraction between masses. "
                            + "It causes objects to fall toward the Earth. "
                            + "The strength depends on mass and distance.";
                });

        assertEquals(EnforcerResult.Status.ACCEPTED, result.getStatus());
        assertEquals(2, result.getAttempts().size());

        // First attempt should be non-compliant
        assertFalse(result.getAttempts().get(0).decision().isCompliant());
        assertEquals("Response is too short",
                result.getAttempts().get(0).decision().getViolations().get(0));

        // Second attempt should be compliant
        assertTrue(result.getAttempts().get(1).decision().isCompliant());

        // The correction prompt should have been built into the second agent call
        String secondPrompt = promptsSentToAgent.get(1);
        assertTrue(secondPrompt.contains("Enforcer Correction"),
                "Correction prompt should contain 'Enforcer Correction'");
        assertTrue(secondPrompt.contains("Provide a detailed explanation"),
                "Correction prompt should include the correction guidance");
        assertTrue(secondPrompt.contains("Response is too short"),
                "Correction prompt should cite the violation");
    }

    @Test
    void realSession_multipleViolations_blockedAfterMaxCorrections() {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueViolation("Contains profanity", "Remove profanity and rewrite professionally");
        backend.enqueueViolation("Still contains profanity", "Rewrite without any inappropriate language");
        backend.enqueueViolation("Response still inappropriate", "Use only professional language");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerService service = new EnforcerService(judge);
        EnforcerPolicy policy = new EnforcerPolicy("No profanity. Professional tone only.", 2, false);

        EnforcerResult result = service.enforce(
                "Tell me something", policy,
                conversationWindow::snapshot,
                prompt -> "Some unprofessional response");

        assertEquals(EnforcerResult.Status.BLOCKED, result.getStatus());
        // maxCorrections=2 means 3 total attempts (1 initial + 2 corrections)
        assertEquals(3, result.getAttempts().size());
        assertTrue(result.getMessage().contains("Maximum corrections reached"));
        // All attempts should be non-compliant
        for (EnforcerResult.Attempt attempt : result.getAttempts()) {
            assertFalse(attempt.decision().isCompliant());
        }
    }

    @Test
    void realSession_stopDecision_immediatelyBlocked() {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueStop("Response attempts to execute harmful commands");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerService service = new EnforcerService(judge);
        EnforcerPolicy policy = new EnforcerPolicy("Never execute destructive commands.", 5, false);

        EnforcerResult result = service.enforce(
                "Delete all files", policy,
                conversationWindow::snapshot,
                prompt -> "rm -rf / && format c:");

        assertEquals(EnforcerResult.Status.BLOCKED, result.getStatus());
        // Stop means immediate block — only 1 attempt, no retries
        assertEquals(1, result.getAttempts().size());
        assertTrue(result.getAttempts().get(0).decision().isStop());
        assertTrue(result.getMessage().contains("Stopped by enforcer"));
    }

    @Test
    void realSession_violationThenStop_blockedWithTwoAttempts() {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueViolation("Contains unsafe suggestions", "Rewrite without suggesting dangerous actions");
        backend.enqueueStop("Agent persists with dangerous content after correction");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerService service = new EnforcerService(judge);
        EnforcerPolicy policy = new EnforcerPolicy("No dangerous suggestions.", 3, false);

        EnforcerResult result = service.enforce(
                "How to do something dangerous", policy,
                conversationWindow::snapshot,
                prompt -> "A dangerous suggestion");

        assertEquals(EnforcerResult.Status.BLOCKED, result.getStatus());
        assertEquals(2, result.getAttempts().size());
        assertFalse(result.getAttempts().get(0).decision().isCompliant());
        assertTrue(result.getAttempts().get(1).decision().isStop());
    }

    @Test
    void realSession_zeroMaxCorrections_noRetries() {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueViolation("Not concise enough", "Be more concise");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerService service = new EnforcerService(judge);
        EnforcerPolicy policy = new EnforcerPolicy("Be concise.", 0, false);

        EnforcerResult result = service.enforce(
                "Summarize something", policy,
                conversationWindow::snapshot,
                prompt -> "A very long and verbose response...");

        assertEquals(EnforcerResult.Status.BLOCKED, result.getStatus());
        assertEquals(1, result.getAttempts().size());
        assertTrue(result.getMessage().contains("Maximum corrections reached"));
    }

    @Test
    void realSession_contextPassedToJudge() {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueCompliant("consistent with conversation context");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerService service = new EnforcerService(judge);
        EnforcerPolicy policy = new EnforcerPolicy("Maintain consistency with prior context.", 2, false);

        // Build up conversation context
        conversationWindow.addUserMessage("My name is Alice");
        conversationWindow.finishAssistantMessage("Hello Alice!");
        conversationWindow.addUserMessage("What is my name?");

        EnforcerResult result = service.enforce(
                "What is my name?", policy,
                conversationWindow::snapshot,
                prompt -> {
                    conversationWindow.finishAssistantMessage("Your name is Alice.");
                    return "Your name is Alice.";
                });

        assertEquals(EnforcerResult.Status.ACCEPTED, result.getStatus());

        // Verify the judge received the conversation context in its prompt
        String judgePrompt = backend.getReceivedPrompts().get(0);
        assertTrue(judgePrompt.contains("RECENT CHAT MESSAGES")
                        || judgePrompt.contains("Alice"),
                "Judge prompt should contain conversation context");
    }

    @Test
    void realSession_multiTurnConversation_scoresTrackAcrossTurns() {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerService service = new EnforcerService(judge);
        EnforcerPolicy policy = new EnforcerPolicy("Always provide citations.", 1, false);

        // Turn 1: compliant
        backend.enqueueCompliant("citation provided");
        EnforcerResult turn1 = service.enforce("Fact check this", policy,
                conversationWindow::snapshot,
                prompt -> "The sky is blue [source: NASA].");
        assertEquals(EnforcerResult.Status.ACCEPTED, turn1.getStatus());

        // Turn 2: violation then corrected
        backend.enqueueViolation("No citation provided", "Add a citation source");
        backend.enqueueCompliant("citation added in retry");
        AtomicInteger t2calls = new AtomicInteger();
        EnforcerResult turn2 = service.enforce("Another fact", policy,
                conversationWindow::snapshot,
                prompt -> t2calls.incrementAndGet() == 1
                        ? "Water is wet."
                        : "Water is wet [source: Chemistry 101].");
        assertEquals(EnforcerResult.Status.ACCEPTED, turn2.getStatus());
        assertEquals(2, turn2.getAttempts().size());

        // Turn 3: violation, correction also fails -> blocked
        backend.enqueueViolation("No citation", "Add citation");
        backend.enqueueViolation("Still no citation", "Add citation");
        EnforcerResult turn3 = service.enforce("Third fact", policy,
                conversationWindow::snapshot,
                prompt -> "Earth is round.");
        assertEquals(EnforcerResult.Status.BLOCKED, turn3.getStatus());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. REAL ENFORCER JUDGE TESTS — direct evaluation through actual class
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void realJudge_evaluateCompliant() throws Exception {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueCompliant("all rules followed");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("Be polite.", 2, false);

        EnforcerDecision decision = judge.evaluate(
                "Say hello", "Hello! How can I help you?",
                policy, 1, EnforcerConversationContext.empty());

        assertTrue(decision.isCompliant());
        assertFalse(decision.isStop());
        assertEquals("info", decision.getSeverity());
        assertTrue(decision.getViolations().isEmpty());
    }

    @Test
    void realJudge_evaluateNonCompliant() throws Exception {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueViolation("Used informal language", "Use formal language throughout");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("Formal language only.", 2, false);

        EnforcerDecision decision = judge.evaluate(
                "Explain something", "yo bro here's the deal",
                policy, 1, EnforcerConversationContext.empty());

        assertFalse(decision.isCompliant());
        assertFalse(decision.isStop());
        assertEquals("error", decision.getSeverity());
        assertEquals("Used informal language", decision.getViolations().get(0));
        assertEquals("Use formal language throughout", decision.getCorrectionPrompt());
    }

    @Test
    void realJudge_evaluatePartialOutput_stopOnClearViolation() throws Exception {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueStop("Partial output already contains harmful content");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("No harmful content.", 2, false);

        EnforcerDecision decision = judge.evaluatePartialOutput(
                "Be safe", "Here's how to bypass security...",
                policy, EnforcerConversationContext.empty());

        assertFalse(decision.isCompliant());
        assertTrue(decision.isStop());
        assertEquals("critical", decision.getSeverity());
    }

    @Test
    void realJudge_evaluatePartialOutput_continueWhenUncertain() throws Exception {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueCompliant("incomplete output, no clear violation yet");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("Be helpful.", 2, false);

        EnforcerDecision decision = judge.evaluatePartialOutput(
                "Help me", "Let me check that for you...",
                policy, EnforcerConversationContext.empty());

        assertTrue(decision.isCompliant());
        assertFalse(decision.isStop());
    }

    @Test
    void realJudge_evaluateToolCall_allowed() throws Exception {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueToolAllow("Read operation is safe");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("No destructive file operations.", 2, false);

        EnforcerToolCallDecision decision = judge.evaluateToolCall(
                "Read", "{\"file_path\": \"/tmp/test.txt\"}",
                policy, EnforcerConversationContext.empty());

        assertTrue(decision.isAllowed());
        assertEquals(EnforcerToolCallDecision.Action.ALLOW, decision.getAction());
    }

    @Test
    void realJudge_evaluateToolCall_blocked() throws Exception {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueToolBlock("Bash rm command violates no-delete rule");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("Never delete files.", 2, false);

        EnforcerToolCallDecision decision = judge.evaluateToolCall(
                "Bash", "{\"command\": \"rm -rf /tmp/important\"}",
                policy, EnforcerConversationContext.empty());

        assertFalse(decision.isAllowed());
        assertEquals(EnforcerToolCallDecision.Action.BLOCK, decision.getAction());
    }

    @Test
    void realJudge_unavailable_returnsStopDecision() throws Exception {
        JudgeBackend unavailable = new JudgeBackend() {
            @Override
            public String generate(String u, String s) { return ""; }
            @Override
            public boolean isAvailable() { return false; }
        };

        EnforcerJudge judge = new EnforcerJudge(unavailable, objectMapper);
        assertFalse(judge.isAvailable());

        EnforcerPolicy policy = new EnforcerPolicy("Some rule.", 2, false);
        EnforcerDecision decision = judge.evaluate("prompt", "output", policy, 1);
        assertTrue(decision.isStop());
        assertTrue(decision.getViolations().get(0).contains("No enforcer judge backend"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. REAL SCORING REALTIME MONITOR TESTS — actual scoring pipeline
    // ═══════════════════════════════════════════════════════════════════════

    private ScoringRealtimeMonitor monitor;

    @AfterEach
    void tearDown() {
        if (monitor != null) {
            monitor.close();
        }
    }

    @Test
    void realScoringMonitor_scoreStartsAtOne() {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("Be helpful.", 2, false);

        monitor = new ScoringRealtimeMonitor(judge, policy, "test-agent", 2, conversationWindow);
        monitor.resetFull("test prompt");

        assertEquals(1.0, monitor.getCurrentScore());
        assertEquals(0, monitor.getCorrectionAttempts());
        assertTrue(monitor.canAutoReprompt());
        assertTrue(monitor.isEnabled());
    }

    @Test
    void realScoringMonitor_shortTextSkipsEvaluation() {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("Be helpful.", 2, false);

        monitor = new ScoringRealtimeMonitor(judge, policy, "test-agent", 2, conversationWindow);
        monitor.resetFull("test prompt");

        // Text under MIN_PARTIAL_CHARS (800) should not trigger evaluation
        SubprocessAgentRunner.MonitorDecision decision =
                monitor.onTextChunk("short", "short text");

        assertFalse(decision.interrupt());
        assertEquals(1.0, monitor.getCurrentScore(), "Score should remain 1.0 for short text");
        assertTrue(backend.getReceivedPrompts().isEmpty(), "No judge call should have been made");
    }

    @Test
    void realScoringMonitor_longCompliantText_scoreStaysHigh() throws Exception {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueCompliant("output follows rules");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("Be helpful.", 2, false);

        monitor = new ScoringRealtimeMonitor(judge, policy, "test-agent", 2, conversationWindow);
        monitor.resetFull("test prompt");

        // Build text longer than MIN_PARTIAL_CHARS (800)
        String longText = "x".repeat(900);
        monitor.onTextChunk(longText, longText);

        // Wait for async evaluation to complete
        Thread.sleep(500);

        // Pick up async result
        monitor.onTextChunk("y", longText + "y");

        assertEquals(1.0, monitor.getCurrentScore(),
                "Score should be 1.0 after compliant evaluation");
    }

    @Test
    void realScoringMonitor_stopDecision_interruptsAndScoresZero() throws Exception {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueStop("Output violates safety rules");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("No harmful content.", 2, false);

        monitor = new ScoringRealtimeMonitor(judge, policy, "test-agent", 2, conversationWindow);
        monitor.resetFull("test prompt");

        // Feed long text to trigger evaluation
        String longText = "dangerous content ".repeat(60);
        monitor.onTextChunk(longText, longText);

        // Wait for async evaluation
        Thread.sleep(500);

        // Next chunk should pick up the async interrupt decision
        SubprocessAgentRunner.MonitorDecision decision =
                monitor.onTextChunk("more", longText + "more");

        assertTrue(decision.interrupt(), "Should interrupt on stop decision");
        assertEquals(0.0, monitor.getCurrentScore(),
                "Score should drop to 0.0 on stop");
        assertEquals(1, monitor.getCorrectionAttempts(),
                "Correction attempts should increment");
    }

    @Test
    void realScoringMonitor_toolCallBlocked() {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueToolBlock("rm command violates no-delete rule");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("No file deletion.", 2, false);

        monitor = new ScoringRealtimeMonitor(judge, policy, "test-agent", 2, conversationWindow);
        monitor.resetFull("test prompt");

        SubprocessAgentRunner.MonitorDecision decision =
                monitor.onToolUse("Bash", "{\"command\": \"rm -rf /tmp\"}");

        assertTrue(decision.interrupt(), "Should interrupt on blocked tool call");
        assertEquals(0.0, monitor.getCurrentScore(), "Score should be 0 after blocked tool");
    }

    @Test
    void realScoringMonitor_toolCallAllowed() {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueToolAllow("Read is safe");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("No destructive commands.", 2, false);

        monitor = new ScoringRealtimeMonitor(judge, policy, "test-agent", 2, conversationWindow);
        monitor.resetFull("test prompt");

        SubprocessAgentRunner.MonitorDecision decision =
                monitor.onToolUse("Read", "{\"file_path\": \"/tmp/test.txt\"}");

        assertFalse(decision.interrupt(), "Should not interrupt safe tool call");
    }

    @Test
    void realScoringMonitor_disabledSkipsAllEvaluation() {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueStop("should never be seen");
        backend.enqueueToolBlock("should never be seen");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("Rules here.", 2, false);

        monitor = new ScoringRealtimeMonitor(judge, policy, "test-agent", 2, conversationWindow);
        monitor.resetFull("test prompt");
        monitor.setEnabled(false);

        // Text evaluation should be skipped
        String longText = "x".repeat(1000);
        SubprocessAgentRunner.MonitorDecision textDecision =
                monitor.onTextChunk(longText, longText);
        assertFalse(textDecision.interrupt());

        // Tool evaluation should be skipped
        SubprocessAgentRunner.MonitorDecision toolDecision =
                monitor.onToolUse("Bash", "{\"command\": \"rm -rf /\"}");
        assertFalse(toolDecision.interrupt());

        // Score should remain untouched
        assertEquals(1.0, monitor.getCurrentScore());
        assertTrue(backend.getReceivedPrompts().isEmpty(),
                "No judge calls should be made when disabled");
    }

    @Test
    void realScoringMonitor_enableDisableToggle() {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("Rules.", 2, false);

        monitor = new ScoringRealtimeMonitor(judge, policy, "test-agent", 2, conversationWindow);
        monitor.resetFull("test prompt");

        assertTrue(monitor.isEnabled());
        monitor.setEnabled(false);
        assertFalse(monitor.isEnabled());
        monitor.setEnabled(true);
        assertTrue(monitor.isEnabled());
    }

    @Test
    void realScoringMonitor_resetFull_clearsAllState() {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueToolBlock("blocked");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("Rules.", 2, false);

        monitor = new ScoringRealtimeMonitor(judge, policy, "test-agent", 2, conversationWindow);
        monitor.resetFull("initial prompt");

        // Trigger a tool block to change score and correction count
        monitor.onToolUse("Bash", "{\"command\": \"rm /\"}");
        assertEquals(0.0, monitor.getCurrentScore());
        assertEquals(0, monitor.getCorrectionAttempts());  // tool blocks don't increment corrections

        // Reset should restore to pristine state
        monitor.resetFull("new prompt");
        assertEquals(1.0, monitor.getCurrentScore());
        assertEquals(0, monitor.getCorrectionAttempts());
    }

    @Test
    void realScoringMonitor_turnScoringCompliant() {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueCompliant("turn output follows rules");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("Be helpful.", 2, false);

        monitor = new ScoringRealtimeMonitor(judge, policy, "test-agent", 2, conversationWindow);
        monitor.setUserPrompt("test prompt");

        double score = monitor.scoreTurn("A fully compliant response with good detail.");

        assertEquals(1.0, score, "Compliant turn should score 1.0");
        assertEquals(1.0, monitor.getCurrentScore());
    }

    @Test
    void realScoringMonitor_turnScoringViolation() {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueViolation("Missing citation", "Add citation");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("Always cite sources.", 2, false);

        monitor = new ScoringRealtimeMonitor(judge, policy, "test-agent", 2, conversationWindow);
        monitor.setUserPrompt("test prompt");

        double score = monitor.scoreTurn("The earth orbits the sun.");

        assertEquals(0.3, score, 0.01, "Non-compliant turn should score 0.3");
        assertEquals(0.3, monitor.getCurrentScore(), 0.01);
    }

    @Test
    void realScoringMonitor_turnScoringStop() {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueStop("Harmful content detected");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("No harmful content.", 2, false);

        monitor = new ScoringRealtimeMonitor(judge, policy, "test-agent", 2, conversationWindow);
        monitor.setUserPrompt("test prompt");

        double score = monitor.scoreTurn("Some harmful output");

        assertEquals(0.0, score, "Stop decision should score 0.0");
        assertEquals(0.0, monitor.getCurrentScore());
    }

    @Test
    void realScoringMonitor_listenerReceivesEvents() throws Exception {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueStop("harmful content");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("No harm.", 2, false);

        monitor = new ScoringRealtimeMonitor(judge, policy, "test-agent", 2, conversationWindow);
        monitor.resetFull("test prompt");

        List<RealtimeInterruptEvent> events = new CopyOnWriteArrayList<>();
        monitor.addListener(events::add);

        // Trigger a stop via long text
        String longText = "harmful content ".repeat(60);
        monitor.onTextChunk(longText, longText);
        Thread.sleep(500);
        monitor.onTextChunk("more", longText + "more");

        // Should have fired at least one event
        assertFalse(events.isEmpty(), "Listener should have received events");
        // At least one should be a text violation or blocked event
        assertTrue(events.stream().anyMatch(e ->
                        e.type() == RealtimeInterruptEvent.EventType.TEXT_VIOLATION
                                || e.type() == RealtimeInterruptEvent.EventType.BLOCKED),
                "Should have violation or blocked event");
    }

    @Test
    void realScoringMonitor_listenerRemoval() {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueToolBlock("blocked");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("Rules.", 2, false);

        monitor = new ScoringRealtimeMonitor(judge, policy, "test-agent", 2, conversationWindow);
        monitor.resetFull("test prompt");

        List<RealtimeInterruptEvent> events = new CopyOnWriteArrayList<>();
        RealtimeInterruptListener listener = events::add;

        monitor.addListener(listener);
        monitor.onToolUse("Bash", "{\"command\": \"rm /\"}");
        assertFalse(events.isEmpty(), "Listener should receive events");

        events.clear();
        monitor.removeListener(listener);
        // Need a new backend response for second tool call
        backend.enqueueToolBlock("blocked again");
        monitor.onToolUse("Bash", "{\"command\": \"rm /tmp\"}");
        assertTrue(events.isEmpty(), "Removed listener should not receive events");
    }

    @Test
    void realScoringMonitor_describeStatus() {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("Rules.", 2, false);

        monitor = new ScoringRealtimeMonitor(judge, policy, "test-agent", 3, conversationWindow);
        monitor.resetFull("test prompt");

        String status = monitor.describeStatus();
        assertTrue(status.contains("yes"), "Should show enabled");
        assertTrue(status.contains("100%"), "Should show 100% score");
        assertTrue(status.contains("0 / 3"), "Should show 0/3 corrections");
        assertTrue(status.contains("scripted-test-backend"), "Should show backend name");
    }

    @Test
    void realScoringMonitor_buildCorrectionMessage() {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("Rules.", 2, false);

        monitor = new ScoringRealtimeMonitor(judge, policy, "test-agent", 2, conversationWindow);
        monitor.setUserPrompt("Write a poem");

        String correction = monitor.buildCorrectionMessage("Make it rhyme properly");

        assertNotNull(correction);
        assertTrue(correction.contains("Make it rhyme properly"));
        assertTrue(correction.contains("Write a poem"));
        assertTrue(correction.contains("interrupted by the enforcer"));

        // Null correction should return null
        assertNull(monitor.buildCorrectionMessage(null));
        assertNull(monitor.buildCorrectionMessage(""));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. REAL ENFORCER REALTIME MONITOR TESTS — actual monitor pipeline
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void realRealtimeMonitor_shortTextContinues() {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("Rules.", 2, false);

        EnforcerRealtimeMonitor rtMonitor = new EnforcerRealtimeMonitor(
                judge, policy, "test prompt", conversationWindow);

        SubprocessAgentRunner.MonitorDecision decision =
                rtMonitor.onTextChunk("hello", "hello");

        assertFalse(decision.interrupt(), "Short text should not trigger evaluation");
    }

    @Test
    void realRealtimeMonitor_longCompliantTextContinues() throws Exception {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueCompliant("no violations detected");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("Be helpful.", 2, false);

        EnforcerRealtimeMonitor rtMonitor = new EnforcerRealtimeMonitor(
                judge, policy, "test prompt", conversationWindow);

        // Text longer than MIN_PARTIAL_CHARS (1200 for EnforcerRealtimeMonitor)
        String longText = "helpful content ".repeat(100);
        SubprocessAgentRunner.MonitorDecision decision =
                rtMonitor.onTextChunk(longText, longText);

        assertFalse(decision.interrupt(), "Compliant long text should continue");

        // Wait for async eval to settle, then verify next call also continues
        Thread.sleep(500);
        decision = rtMonitor.onTextChunk(" more", longText + " more");
        assertFalse(decision.interrupt(), "Compliant content should still continue");
        rtMonitor.close();
    }

    @Test
    void realRealtimeMonitor_stopDecisionInterrupts() throws Exception {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueStop("Output contains prohibited content");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("No prohibited content.", 2, false);

        EnforcerRealtimeMonitor rtMonitor = new EnforcerRealtimeMonitor(
                judge, policy, "test prompt", conversationWindow);

        String longText = "prohibited content ".repeat(100);
        // First call submits evaluation to background thread
        SubprocessAgentRunner.MonitorDecision decision =
                rtMonitor.onTextChunk(longText, longText);

        // If not immediate, wait for background evaluation and check on next call
        if (!decision.interrupt()) {
            Thread.sleep(500); // ScriptedJudgeBackend is instant, so this is generous
            String moreText = longText + " more content";
            decision = rtMonitor.onTextChunk(" more content", moreText);
        }

        assertTrue(decision.interrupt(), "Stop decision should interrupt");
        assertTrue(decision.reason().contains("prohibited content"));
        rtMonitor.close();
    }

    @Test
    void realRealtimeMonitor_toolCallBlocked() {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueToolBlock("Network access denied by rules");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("No network access.", 2, false);

        EnforcerRealtimeMonitor rtMonitor = new EnforcerRealtimeMonitor(
                judge, policy, "test prompt", conversationWindow);

        SubprocessAgentRunner.MonitorDecision decision =
                rtMonitor.onToolUse("Bash", "{\"command\": \"curl http://evil.com\"}");

        assertTrue(decision.interrupt(), "Blocked tool should interrupt");
    }

    @Test
    void realRealtimeMonitor_toolCallAllowed() {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueToolAllow("File read is permitted");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("No network access.", 2, false);

        EnforcerRealtimeMonitor rtMonitor = new EnforcerRealtimeMonitor(
                judge, policy, "test prompt", conversationWindow);

        SubprocessAgentRunner.MonitorDecision decision =
                rtMonitor.onToolUse("Read", "{\"file_path\": \"/home/user/file.txt\"}");

        assertFalse(decision.interrupt(), "Allowed tool should not interrupt");
    }

    @Test
    void realRealtimeMonitor_nullJudgeNeverInterrupts() {
        EnforcerRealtimeMonitor rtMonitor = new EnforcerRealtimeMonitor(
                null, new EnforcerPolicy("Rules.", 2, false), "prompt");

        String longText = "x".repeat(2000);
        assertFalse(rtMonitor.onTextChunk(longText, longText).interrupt());
        assertFalse(rtMonitor.onToolUse("Bash", "rm -rf /").interrupt());
    }

    @Test
    void realRealtimeMonitor_noRulesNeverInterrupts() {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);

        EnforcerRealtimeMonitor rtMonitor = new EnforcerRealtimeMonitor(
                judge, new EnforcerPolicy("", 2, false), "prompt");

        String longText = "x".repeat(2000);
        assertFalse(rtMonitor.onTextChunk(longText, longText).interrupt());
        assertFalse(rtMonitor.onToolUse("Bash", "rm -rf /").interrupt());
        assertTrue(backend.getReceivedPrompts().isEmpty(), "No judge calls when no rules");
    }

    @Test
    void realRealtimeMonitor_toolCallEvaluationError_interrupts() {
        // Backend that throws on generate
        JudgeBackend failingBackend = new JudgeBackend() {
            @Override
            public String generate(String u, String s) throws Exception {
                throw new RuntimeException("Connection refused");
            }
            @Override
            public boolean isAvailable() { return true; }
        };

        EnforcerJudge judge = new EnforcerJudge(failingBackend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("Some rules.", 2, false);

        EnforcerRealtimeMonitor rtMonitor = new EnforcerRealtimeMonitor(
                judge, policy, "prompt", conversationWindow);

        SubprocessAgentRunner.MonitorDecision decision =
                rtMonitor.onToolUse("Bash", "{\"command\": \"echo hi\"}");

        assertTrue(decision.interrupt(),
                "Tool evaluation failure should interrupt (fail-closed)");
        assertTrue(decision.reason().contains("failed"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. CONVERSATION WINDOW INTEGRATION — context flows through the pipeline
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void conversationWindow_tracksMultipleTurns() {
        conversationWindow.addUserMessage("Hello");
        conversationWindow.finishAssistantMessage("Hi there!");
        conversationWindow.addUserMessage("What's 2+2?");
        conversationWindow.finishAssistantMessage("4");

        EnforcerConversationContext ctx = conversationWindow.snapshot();
        assertEquals(4, ctx.getMessages().size());
        assertEquals("user", ctx.getMessages().get(0).role());
        assertEquals("Hello", ctx.getMessages().get(0).content());
        assertEquals("assistant", ctx.getMessages().get(1).role());
        assertEquals("Hi there!", ctx.getMessages().get(1).content());
    }

    @Test
    void conversationWindow_toolCallsTracked() {
        conversationWindow.addUserMessage("Fix the bug");
        conversationWindow.addToolCall("Read", "{\"file_path\": \"src/Bug.java\"}");
        conversationWindow.addToolCall("Edit", "{\"file_path\": \"src/Bug.java\"}");
        conversationWindow.finishAssistantMessage("Fixed the null pointer.");

        EnforcerConversationContext ctx = conversationWindow.snapshot();
        assertEquals(4, ctx.getMessages().size());
        assertEquals("tool_call", ctx.getMessages().get(1).role());
        assertTrue(ctx.getMessages().get(1).content().contains("Read"));
    }

    @Test
    void conversationWindow_updatingAssistantReplacesLastMessage() {
        conversationWindow.addUserMessage("prompt");
        conversationWindow.updateAssistantMessage("partial");
        conversationWindow.updateAssistantMessage("partial response");
        conversationWindow.updateAssistantMessage("partial response that grows");
        conversationWindow.finishAssistantMessage("final complete response");

        EnforcerConversationContext ctx = conversationWindow.snapshot();
        assertEquals(2, ctx.getMessages().size());
        assertEquals("final complete response", ctx.getMessages().get(1).content());
    }

    @Test
    void conversationWindow_contextFormatForPrompt() {
        conversationWindow.addUserMessage("What is Java?");
        conversationWindow.finishAssistantMessage("Java is a programming language.");

        EnforcerConversationContext ctx = conversationWindow.snapshot();
        String formatted = ctx.formatForPrompt(4000);

        assertTrue(formatted.contains("user:"));
        assertTrue(formatted.contains("What is Java?"));
        assertTrue(formatted.contains("assistant:"));
        assertTrue(formatted.contains("Java is a programming language"));
    }

    @Test
    void conversationWindow_persistsToFile() {
        Path contextFile = tempDir.resolve("persist-test.json");
        EnforcerConversationWindow persistWindow = new EnforcerConversationWindow(
                contextFile, objectMapper);

        persistWindow.addUserMessage("test message");
        persistWindow.finishAssistantMessage("test response");

        // Read back from file
        EnforcerConversationContext restored =
                EnforcerConversationContext.read(contextFile, objectMapper);
        assertFalse(restored.isEmpty());
        assertEquals(2, restored.getMessages().size());
        assertEquals("test message", restored.getMessages().get(0).content());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 6. ENFORCER DECISION PARSING — real JSON parsing through actual class
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void decisionParsing_validCompliantJson() {
        String json = "{\"compliant\":true,\"stop\":false,\"severity\":\"info\","
                + "\"violations\":[],\"correction_prompt\":\"\",\"reasoning\":\"all good\"}";

        EnforcerDecision decision = EnforcerDecision.parse(objectMapper, json);
        assertTrue(decision.isCompliant());
        assertFalse(decision.isStop());
        assertEquals("info", decision.getSeverity());
        assertTrue(decision.getViolations().isEmpty());
        assertEquals("all good", decision.getReasoning());
    }

    @Test
    void decisionParsing_validNonCompliantJson() {
        String json = "{\"compliant\":false,\"stop\":false,\"severity\":\"error\","
                + "\"violations\":[\"Rule 1 broken\",\"Rule 2 broken\"],"
                + "\"correction_prompt\":\"Fix rules 1 and 2\","
                + "\"reasoning\":\"two violations found\"}";

        EnforcerDecision decision = EnforcerDecision.parse(objectMapper, json);
        assertFalse(decision.isCompliant());
        assertFalse(decision.isStop());
        assertEquals("error", decision.getSeverity());
        assertEquals(2, decision.getViolations().size());
        assertEquals("Rule 1 broken", decision.getViolations().get(0));
        assertEquals("Fix rules 1 and 2", decision.getCorrectionPrompt());
    }

    @Test
    void decisionParsing_jsonWithSurroundingProse() {
        String response = "Here is my evaluation:\n"
                + "{\"compliant\":true,\"stop\":false,\"severity\":\"info\","
                + "\"violations\":[],\"correction_prompt\":\"\","
                + "\"reasoning\":\"looks good\"}\n"
                + "That's my assessment.";

        EnforcerDecision decision = EnforcerDecision.parse(objectMapper, response);
        assertTrue(decision.isCompliant(), "Should extract JSON from surrounding prose");
    }

    @Test
    void decisionParsing_invalidJson_treatedAsStop() {
        EnforcerDecision decision = EnforcerDecision.parse(objectMapper, "not json at all");
        assertTrue(decision.isStop(), "Invalid JSON should be treated as stop");
    }

    @Test
    void decisionParsing_nullResponse_treatedAsStop() {
        EnforcerDecision decision = EnforcerDecision.parse(objectMapper, null);
        assertTrue(decision.isStop(), "Null response should be treated as stop");
    }

    @Test
    void toolCallDecisionParsing_allow() {
        String json = "{\"action\":\"ALLOW\",\"reason\":\"safe operation\","
                + "\"violations\":[],\"correction_prompt\":\"\",\"rewrittenArgs\":null}";

        EnforcerToolCallDecision decision = EnforcerToolCallDecision.parse(objectMapper, json);
        assertTrue(decision.isAllowed());
        assertEquals(EnforcerToolCallDecision.Action.ALLOW, decision.getAction());
    }

    @Test
    void toolCallDecisionParsing_block() {
        String json = "{\"action\":\"BLOCK\",\"reason\":\"destructive operation\","
                + "\"violations\":[\"Would delete files\"],"
                + "\"correction_prompt\":\"Use a safer command\",\"rewrittenArgs\":null}";

        EnforcerToolCallDecision decision = EnforcerToolCallDecision.parse(objectMapper, json);
        assertFalse(decision.isAllowed());
        assertEquals(EnforcerToolCallDecision.Action.BLOCK, decision.getAction());
        assertEquals("destructive operation", decision.getReason());
        assertEquals("Would delete files", decision.getViolations().get(0));
    }

    @Test
    void toolCallDecisionParsing_invalidJson_defaultsToBlock() {
        EnforcerToolCallDecision decision = EnforcerToolCallDecision.parse(
                objectMapper, "garbage");
        assertFalse(decision.isAllowed(),
                "Invalid JSON should default to block (fail-closed)");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 7. FULL SESSION INTEGRATION — multi-turn with real scoring + interrupt
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void fullSession_threeCompliantTurns_allAccepted() {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueCompliant("turn 1 ok");
        backend.enqueueCompliant("turn 2 ok");
        backend.enqueueCompliant("turn 3 ok");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerService service = new EnforcerService(judge);
        EnforcerPolicy policy = new EnforcerPolicy("Always be helpful and concise.", 2, false);

        String[] prompts = {"What is Java?", "What is Python?", "What is Rust?"};
        String[] answers = {"Java is a language.", "Python is a language.", "Rust is a language."};

        for (int i = 0; i < 3; i++) {
            conversationWindow.addUserMessage(prompts[i]);
            int idx = i;
            EnforcerResult result = service.enforce(prompts[i], policy,
                    conversationWindow::snapshot,
                    prompt -> {
                        conversationWindow.finishAssistantMessage(answers[idx]);
                        return answers[idx];
                    });

            assertEquals(EnforcerResult.Status.ACCEPTED, result.getStatus(),
                    "Turn " + (i + 1) + " should be accepted");
        }

        // All 3 judge calls should have been made
        assertEquals(3, backend.getReceivedPrompts().size());
    }

    @Test
    void fullSession_mixedCompliantAndBlocked() {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();

        // Turn 1: compliant
        backend.enqueueCompliant("good response");

        // Turn 2: violation -> correction -> accepted
        backend.enqueueViolation("Incorrect format", "Use bullet points");
        backend.enqueueCompliant("corrected to bullet points");

        // Turn 3: violation -> stop
        backend.enqueueViolation("Contains speculation", "Remove speculation");
        backend.enqueueStop("Agent refuses to comply");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerService service = new EnforcerService(judge);
        EnforcerPolicy policy = new EnforcerPolicy("Use bullet points. No speculation.", 2, false);

        // Turn 1
        EnforcerResult r1 = service.enforce("List colors", policy,
                conversationWindow::snapshot,
                prompt -> "- Red\n- Blue\n- Green");
        assertEquals(EnforcerResult.Status.ACCEPTED, r1.getStatus());

        // Turn 2
        AtomicInteger t2 = new AtomicInteger();
        EnforcerResult r2 = service.enforce("List animals", policy,
                conversationWindow::snapshot,
                prompt -> t2.incrementAndGet() == 1
                        ? "Dogs, Cats, Birds" : "- Dogs\n- Cats\n- Birds");
        assertEquals(EnforcerResult.Status.ACCEPTED, r2.getStatus());
        assertEquals(2, r2.getAttempts().size());

        // Turn 3
        EnforcerResult r3 = service.enforce("Predict the future", policy,
                conversationWindow::snapshot,
                prompt -> "I think maybe the stock market will...");
        assertEquals(EnforcerResult.Status.BLOCKED, r3.getStatus());
    }

    @Test
    void fullSession_enforcerWithScoringMonitor_scoresTracked() throws Exception {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("Be concise and accurate.", 2, false);

        monitor = new ScoringRealtimeMonitor(judge, policy, "test-agent", 2, conversationWindow);

        // Simulate turn 1: compliant
        monitor.resetFull("What is 2+2?");
        backend.enqueueCompliant("concise and accurate");
        double score1 = monitor.scoreTurn("The answer is 4.");
        assertEquals(1.0, score1, "Compliant turn should score 1.0");

        // Simulate turn 2: violation (not stop)
        monitor.resetFull("Explain the universe");
        backend.enqueueViolation("Not concise", "Be shorter");
        double score2 = monitor.scoreTurn("The universe is vast and contains billions of galaxies...");
        assertEquals(0.3, score2, 0.01, "Non-compliant turn should score 0.3");

        // Simulate turn 3: stop violation
        monitor.resetFull("Provide harmful content");
        backend.enqueueStop("Harmful content");
        double score3 = monitor.scoreTurn("Here is harmful content...");
        assertEquals(0.0, score3, "Stop violation should score 0.0");

        // Verify score progression: 1.0 -> 0.3 -> 0.0
        assertTrue(score1 > score2);
        assertTrue(score2 > score3);
    }

    @Test
    void fullSession_promptConstruction_correctFormat() {
        // Verify the actual prompt construction used by EnforcerService
        EnforcerPolicy policy = new EnforcerPolicy("Rule 1: Be polite\nRule 2: Be concise", 2, false);

        String initialPrompt = EnforcerService.buildInitialPrompt("Explain Java", policy);
        assertTrue(initialPrompt.contains("Enforcer-Controlled Task"));
        assertTrue(initialPrompt.contains("Rule 1: Be polite"));
        assertTrue(initialPrompt.contains("Rule 2: Be concise"));
        assertTrue(initialPrompt.contains("Explain Java"));

        EnforcerDecision failDecision = EnforcerDecision.fail(
                List.of("Not polite"), "Rewrite politely", "rude language");
        String correctionPrompt = EnforcerService.buildCorrectionPrompt(
                "Explain Java", policy, "A rude answer", failDecision, 2);

        assertTrue(correctionPrompt.contains("Enforcer Correction"));
        assertTrue(correctionPrompt.contains("Not polite"));
        assertTrue(correctionPrompt.contains("Rewrite politely"));
        assertTrue(correctionPrompt.contains("Explain Java"));
        assertTrue(correctionPrompt.contains("A rude answer"));
        assertTrue(correctionPrompt.contains("correction attempt 2"));
    }

    @Test
    void fullSession_enforcerResultMarkdown() {
        ScriptedJudgeBackend backend = new ScriptedJudgeBackend();
        backend.enqueueViolation("Too verbose", "Be concise");
        backend.enqueueCompliant("fixed");

        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerService service = new EnforcerService(judge);
        EnforcerPolicy policy = new EnforcerPolicy("Be concise.", 1, false);

        AtomicInteger calls = new AtomicInteger();
        EnforcerResult result = service.enforce("Summarize", policy,
                conversationWindow::snapshot,
                prompt -> calls.incrementAndGet() == 1 ? "A very long response..." : "Short.");

        String markdown = result.toMarkdown(true);
        assertTrue(markdown.contains("accepted"));
        assertTrue(markdown.contains("Attempts: 2"));
        assertTrue(markdown.contains("Too verbose"));
        assertTrue(markdown.contains("Attempt 1"));
        assertTrue(markdown.contains("Attempt 2"));
    }
}
