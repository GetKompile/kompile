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

package ai.kompile.cli.main.chat.render;

import ai.kompile.cli.main.chat.ChatHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for judging and interrupting agent responses in the TUI.
 * <p>
 * The judge scores responses based on simple rules:
 * <ul>
 *   <li>Negative score: mentioning "workaround" or "pre-existing" as excuse concepts</li>
 *   <li>Positive score: providing direct fixes, investigation, root-cause analysis</li>
 * </ul>
 * <p>
 * The interrupt mechanism can halt response rendering mid-stream and
 * inject corrective feedback that forces compliance on the next turn.
 */
class TuiJudgeAndInterruptTest {

    private TerminalRenderer renderer;
    private AsciiRenderer ascii;

    @BeforeEach
    void setUp() {
        renderer = new TerminalRenderer(false); // ANSI-disabled for testable output
        ascii = new AsciiRenderer(renderer);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RESPONSE JUDGE — scoring rules
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Simple judge that scores agent responses.
     * Returns negative score for workaround/pre-existing excuse patterns,
     * positive score for investigation/fix patterns.
     */
    static class ResponseJudge {

        // Negative patterns — excuses that dismiss failures
        private static final Pattern WORKAROUND_PATTERN = Pattern.compile(
                "(?i)\\b(workaround|work[- ]around|as a workaround)\\b");
        private static final Pattern PREEXISTING_PATTERN = Pattern.compile(
                "(?i)\\b(pre-existing|preexisting|pre existing|already existed|was already broken)\\b");
        private static final Pattern ENVIRONMENTAL_PATTERN = Pattern.compile(
                "(?i)\\b(environmental issue|environment.specific|works on my machine|platform.specific issue)\\b");
        private static final Pattern DISMISS_PATTERN = Pattern.compile(
                "(?i)\\b(not reproducible|can't reproduce|unable to reproduce|intermittent|flaky)\\b");

        // Positive patterns — proper investigation and fixes
        private static final Pattern ROOT_CAUSE_PATTERN = Pattern.compile(
                "(?i)\\b(root cause|caused by|the bug is|the issue is|the problem is|because)\\b");
        private static final Pattern FIX_PATTERN = Pattern.compile(
                "(?i)\\b(fix(ed|ing)?|patch(ed|ing)?|resolv(ed|ing)|correct(ed|ing))\\b");
        private static final Pattern INVESTIGATE_PATTERN = Pattern.compile(
                "(?i)\\b(investigat(e|ed|ing)|debug(ged|ging)?|traced|found that|discovered)\\b");
        private static final Pattern TOOL_USE_PATTERN = Pattern.compile(
                "\\[tool:(Bash|Read|Edit|Grep|Glob|Write)\\]");

        record JudgeResult(int score, List<String> reasons) {
            boolean passed() { return score >= 0; }
            boolean failed() { return score < 0; }
        }

        /**
         * Score a response. Each violation subtracts points, each positive signal adds.
         */
        static JudgeResult judge(String response) {
            int score = 0;
            List<String> reasons = new ArrayList<>();

            // Negative: workaround mentions (-3 each, severe)
            int workarounds = countMatches(WORKAROUND_PATTERN, response);
            if (workarounds > 0) {
                score -= 3 * workarounds;
                reasons.add("NEGATIVE: mentioned 'workaround' " + workarounds + " time(s)");
            }

            // Negative: pre-existing excuses (-3 each, severe)
            int preexisting = countMatches(PREEXISTING_PATTERN, response);
            if (preexisting > 0) {
                score -= 3 * preexisting;
                reasons.add("NEGATIVE: dismissed as 'pre-existing' " + preexisting + " time(s)");
            }

            // Negative: environmental dismissals (-2 each)
            int environmental = countMatches(ENVIRONMENTAL_PATTERN, response);
            if (environmental > 0) {
                score -= 2 * environmental;
                reasons.add("NEGATIVE: dismissed as 'environmental' " + environmental + " time(s)");
            }

            // Negative: can't reproduce dismissals (-2 each)
            int dismissals = countMatches(DISMISS_PATTERN, response);
            if (dismissals > 0) {
                score -= 2 * dismissals;
                reasons.add("NEGATIVE: dismissed as 'not reproducible' " + dismissals + " time(s)");
            }

            // Positive: root cause analysis (+2 each)
            int rootCause = countMatches(ROOT_CAUSE_PATTERN, response);
            if (rootCause > 0) {
                score += 2 * rootCause;
                reasons.add("POSITIVE: root cause analysis " + rootCause + " time(s)");
            }

            // Positive: actual fix applied (+2 each)
            int fixes = countMatches(FIX_PATTERN, response);
            if (fixes > 0) {
                score += 2 * fixes;
                reasons.add("POSITIVE: fix applied " + fixes + " time(s)");
            }

            // Positive: investigation (+1 each)
            int investigations = countMatches(INVESTIGATE_PATTERN, response);
            if (investigations > 0) {
                score += investigations;
                reasons.add("POSITIVE: investigation " + investigations + " time(s)");
            }

            // Positive: tool use (+1 each, capped at 3)
            int toolUse = Math.min(3, countMatches(TOOL_USE_PATTERN, response));
            if (toolUse > 0) {
                score += toolUse;
                reasons.add("POSITIVE: tool use " + toolUse + " time(s)");
            }

            return new JudgeResult(score, reasons);
        }

        private static int countMatches(Pattern pattern, String text) {
            var matcher = pattern.matcher(text);
            int count = 0;
            while (matcher.find()) count++;
            return count;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INTERRUPT HANDLER — halt and redirect
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Simulates the interrupt mechanism for agent responses.
     * Can interrupt mid-stream and inject corrective feedback.
     */
    static class InterruptHandler {
        private final AtomicBoolean interrupted = new AtomicBoolean(false);
        private final AtomicReference<String> feedback = new AtomicReference<>(null);
        private final List<String> interruptLog = new CopyOnWriteArrayList<>();

        /** Send interrupt signal. */
        void interrupt(String reason) {
            interrupted.set(true);
            interruptLog.add(reason);
        }

        /** Send corrective feedback to force compliance. */
        void sendFeedback(String correction) {
            feedback.set(correction);
            interrupted.set(true);
            interruptLog.add("FEEDBACK: " + correction);
        }

        /** Check if interrupted (clears the flag). */
        boolean checkAndClear() {
            return interrupted.getAndSet(false);
        }

        /** Get pending feedback (clears it). */
        String consumeFeedback() {
            return feedback.getAndSet(null);
        }

        boolean wasInterrupted() {
            return !interruptLog.isEmpty();
        }

        List<String> getLog() {
            return List.copyOf(interruptLog);
        }
    }

    /**
     * Simulates streaming a response token-by-token, checking for interrupt
     * and applying judge scoring at the end or at interrupt.
     */
    static class StreamSimulator {
        private final InterruptHandler handler;
        private final StringBuilder accumulated = new StringBuilder();
        private int tokensEmitted = 0;

        StreamSimulator(InterruptHandler handler) {
            this.handler = handler;
        }

        /**
         * Stream tokens from a response. Returns the portion that was emitted
         * before interrupt (or full response if no interrupt).
         */
        String stream(String fullResponse) {
            String[] tokens = fullResponse.split("\\s+");
            accumulated.setLength(0);
            tokensEmitted = 0;

            for (String token : tokens) {
                if (handler.checkAndClear()) {
                    break;
                }
                if (accumulated.length() > 0) accumulated.append(' ');
                accumulated.append(token);
                tokensEmitted++;
            }

            return accumulated.toString();
        }

        int getTokensEmitted() {
            return tokensEmitted;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // JUDGE TESTS — scoring agent responses
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void judgeScoresWorkaroundMentionNegatively() {
        String response = "This test is failing. As a workaround, you can skip this test.";
        var result = ResponseJudge.judge(response);

        assertTrue(result.failed(), "Mentioning workaround should score negatively");
        assertTrue(result.score() < 0, "Score should be negative: " + result.score());
        assertTrue(result.reasons().stream().anyMatch(r -> r.contains("workaround")),
                "Should cite workaround as reason");
    }

    @Test
    void judgeScoresPreExistingMentionNegatively() {
        String response = "This is a pre-existing issue that was already broken before our changes.";
        var result = ResponseJudge.judge(response);

        assertTrue(result.failed(), "Dismissing as pre-existing should score negatively");
        assertTrue(result.score() <= -3, "Score should be -3 or worse: " + result.score());
    }

    @Test
    void judgeScoresEnvironmentalDismissalNegatively() {
        String response = "This appears to be an environmental issue, platform-specific issue.";
        var result = ResponseJudge.judge(response);

        assertTrue(result.failed(), "Environmental dismissal should score negatively");
        assertTrue(result.reasons().stream().anyMatch(r -> r.contains("environmental")),
                "Should cite environmental dismissal");
    }

    @Test
    void judgeScoresProperInvestigationPositively() {
        String response = "I investigated the test failure and found that the root cause " +
                "is a null pointer in the parser. I fixed it by adding a null check.";
        var result = ResponseJudge.judge(response);

        assertTrue(result.passed(), "Proper investigation + fix should score positively");
        assertTrue(result.score() > 0, "Score should be positive: " + result.score());
    }

    @Test
    void judgeScoresToolUsePositively() {
        String response = "Let me check the code.\n" +
                "[tool:Read]\n{\"file_path\": \"src/Test.java\"}\n[/tool]\n" +
                "[tool:Grep]\n{\"pattern\": \"null\"}\n[/tool]\n" +
                "I found that the issue is caused by a missing null check. Fixed it.\n" +
                "[tool:Edit]\n{\"file_path\": \"src/Test.java\"}\n[/tool]";
        var result = ResponseJudge.judge(response);

        assertTrue(result.passed(), "Using tools should contribute positive score");
        assertTrue(result.reasons().stream().anyMatch(r -> r.contains("tool use")),
                "Should cite tool use");
    }

    @Test
    void judgeDetectsMultipleViolationsInOneResponse() {
        String response = "This is a pre-existing issue. As a workaround, " +
                "you can skip this test. It's not reproducible on my machine.";
        var result = ResponseJudge.judge(response);

        assertTrue(result.failed());
        assertTrue(result.score() <= -8, "Multiple violations should compound: " + result.score());
        assertTrue(result.reasons().size() >= 3, "Should list all violations");
    }

    @Test
    void judgeHandlesFixWithWorkaroundMention() {
        // If the response fixes the issue but also mentions workaround, net score matters
        String response = "I investigated and found the root cause. The bug is in the parser. " +
                "I fixed it properly — no workaround needed.";
        var result = ResponseJudge.judge(response);

        // "workaround" appears but in context of rejecting it — still counts as mention
        // The fix + investigation should outweigh it
        assertNotNull(result);
        // Either passes because fix outweighs, or fails because workaround detected
        // The key point: the judge DOES detect the workaround mention
        assertTrue(result.reasons().stream().anyMatch(r -> r.contains("workaround")),
                "Judge should still detect workaround mention even in negation context");
    }

    @Test
    void judgeScoresEmptyResponseNeutral() {
        var result = ResponseJudge.judge("");
        assertEquals(0, result.score(), "Empty response should score neutral");
        assertTrue(result.reasons().isEmpty());
    }

    @Test
    void judgeScoresCleanResponseNeutral() {
        // No positive or negative signals
        var result = ResponseJudge.judge("Here is the output of the command.");
        assertEquals(0, result.score(), "Neutral response should score 0");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INTERRUPT TESTS — halting bad responses
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void interruptHaltsStreamingResponse() {
        InterruptHandler handler = new InterruptHandler();
        StreamSimulator stream = new StreamSimulator(handler);

        String badResponse = "This is a pre-existing issue and we should just use a workaround";

        // Interrupt after a few tokens
        handler.interrupt("Detected excuse pattern");

        String emitted = stream.stream(badResponse);

        // Should have been interrupted — not all tokens emitted
        assertTrue(stream.getTokensEmitted() == 0,
                "Interrupt before streaming should emit 0 tokens");
        assertTrue(handler.wasInterrupted());
    }

    @Test
    void interruptMidStreamStopsOutput() {
        InterruptHandler handler = new InterruptHandler();

        String badResponse = "word1 word2 word3 word4 word5 word6 word7 word8 word9 word10";
        String[] tokens = badResponse.split("\\s+");

        StringBuilder emitted = new StringBuilder();
        int emittedCount = 0;

        for (int i = 0; i < tokens.length; i++) {
            // Interrupt after 5 tokens
            if (i == 5) {
                handler.interrupt("Content violation detected at token 5");
            }

            if (handler.checkAndClear()) {
                break;
            }

            if (emitted.length() > 0) emitted.append(' ');
            emitted.append(tokens[i]);
            emittedCount++;
        }

        assertEquals(5, emittedCount, "Should stop at token 5");
        assertTrue(emitted.toString().contains("word5"), "Should contain up to word5");
        assertFalse(emitted.toString().contains("word6"), "Should NOT contain word6");
    }

    @Test
    void interruptWithFeedbackInjectsCorrection() {
        InterruptHandler handler = new InterruptHandler();

        // Agent starts giving a bad response
        String badResponse = "This appears to be a pre-existing environmental issue";
        handler.sendFeedback("Do NOT dismiss as pre-existing. Investigate the actual root cause.");

        // Agent checks for interrupt
        assertTrue(handler.checkAndClear(), "Should be interrupted");

        // Agent reads corrective feedback
        String correction = handler.consumeFeedback();
        assertNotNull(correction, "Feedback should be available");
        assertTrue(correction.contains("Do NOT dismiss"), "Feedback should contain correction");

        // After consuming, feedback should be cleared
        assertNull(handler.consumeFeedback(), "Feedback consumed — should be null now");
    }

    @Test
    void interruptLogTracksAllInterruptions() {
        InterruptHandler handler = new InterruptHandler();

        handler.interrupt("First violation");
        handler.checkAndClear();
        handler.sendFeedback("Fix it properly");
        handler.checkAndClear();
        handler.interrupt("Another violation");
        handler.checkAndClear();

        List<String> log = handler.getLog();
        assertEquals(3, log.size());
        assertEquals("First violation", log.get(0));
        assertTrue(log.get(1).startsWith("FEEDBACK:"));
        assertEquals("Another violation", log.get(2));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FEEDBACK COMPLIANCE TESTS — forcing agent behavior change
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void feedbackForcesComplianceOnNextTurn() {
        InterruptHandler handler = new InterruptHandler();

        // Turn 1: Agent gives bad response
        String turn1 = "This is a pre-existing issue, nothing we can do about it.";
        var judge1 = ResponseJudge.judge(turn1);
        assertTrue(judge1.failed(), "First response should fail judge");

        // Send corrective feedback
        handler.sendFeedback("Investigate and fix the issue. Never dismiss as pre-existing.");
        assertTrue(handler.checkAndClear());
        String feedback = handler.consumeFeedback();
        assertNotNull(feedback);

        // Turn 2: Agent complies — provides actual investigation
        String turn2 = "I investigated the failure and found that the root cause " +
                "is a race condition in the connection pool. I fixed it by " +
                "adding proper synchronization.\n" +
                "[tool:Read]\n{\"file_path\": \"src/Pool.java\"}\n[/tool]\n" +
                "[tool:Edit]\n{\"file_path\": \"src/Pool.java\"}\n[/tool]";
        var judge2 = ResponseJudge.judge(turn2);

        assertTrue(judge2.passed(), "Compliant response should pass judge");
        assertTrue(judge2.score() > judge1.score(),
                "Compliant score (" + judge2.score() + ") should be better than " +
                        "non-compliant score (" + judge1.score() + ")");
    }

    @Test
    void feedbackComplianceAcrossMultipleTurns() {
        InterruptHandler handler = new InterruptHandler();
        List<ResponseJudge.JudgeResult> scores = new ArrayList<>();

        // Simulate a multi-turn conversation with judge + feedback loop
        String[] agentResponses = {
                // Turn 1: Bad — uses workaround
                "The test is failing. As a workaround, let's skip this test class.",
                // Turn 2: Still bad after feedback — uses pre-existing excuse
                "This is a pre-existing issue from before our changes.",
                // Turn 3: Compliant — investigates
                "I investigated and found that the root cause is a missing mock. " +
                        "I fixed it by adding the proper test fixture.",
                // Turn 4: Good — continues being compliant
                "I debugged the remaining failure. The issue is caused by a " +
                        "stale cache. I resolved it by clearing the cache in setUp()."
        };

        String pendingFeedback = null;

        for (int turn = 0; turn < agentResponses.length; turn++) {
            String response = agentResponses[turn];
            var result = ResponseJudge.judge(response);
            scores.add(result);

            if (result.failed()) {
                // Judge scored negatively — send feedback
                handler.sendFeedback(
                        "STOP. Do NOT use workarounds or dismiss as pre-existing. " +
                                "Investigate the root cause and fix the actual issue.");
                handler.checkAndClear();
                pendingFeedback = handler.consumeFeedback();
            }
        }

        // Verify scoring trajectory
        assertTrue(scores.get(0).failed(), "Turn 1 should fail (workaround)");
        assertTrue(scores.get(1).failed(), "Turn 2 should fail (pre-existing)");
        assertTrue(scores.get(2).passed(), "Turn 3 should pass (investigation + fix)");
        assertTrue(scores.get(3).passed(), "Turn 4 should pass (continued compliance)");

        // Scores should trend upward
        assertTrue(scores.get(2).score() > scores.get(0).score(),
                "Score should improve after compliance");
        assertTrue(scores.get(3).score() > 0,
                "Final score should be positive");
    }

    @Test
    void feedbackWithSpecificRuleEnforcement() {
        // Test that specific rules can be enforced via feedback
        String rule = "Never suggest skipping tests. Every test failure must be investigated.";

        String badResponse = "This test is flaky. Let's skip it for now and file a ticket.";
        var result = ResponseJudge.judge(badResponse);
        assertTrue(result.failed(), "Suggesting to skip tests should fail");

        // The feedback system should be able to encode this rule
        InterruptHandler handler = new InterruptHandler();
        handler.sendFeedback(rule);
        handler.checkAndClear();

        String receivedRule = handler.consumeFeedback();
        assertEquals(rule, receivedRule, "Rule should be transmitted exactly");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RENDERED OUTPUT TESTS — tool blocks in judged responses
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void judgedResponseWithToolBlocksRendersCorrectly() {
        String response = "I investigated the issue.\n" +
                "[tool:Bash]\n{\"command\": \"mvn test -Dtest=FooTest\"}\n[/tool]\n" +
                "The root cause is a null pointer. I fixed it.\n" +
                "[tool:Edit]\n{\"file_path\": \"src/Foo.java\", " +
                "\"old_string\": \"obj.method()\", \"new_string\": \"if (obj != null) obj.method()\"}\n[/tool]";

        // Judge passes
        var judgeResult = ResponseJudge.judge(response);
        assertTrue(judgeResult.passed(), "Response with investigation + fix should pass");

        // Render in TUI
        String rendered = ascii.renderMarkdown(response);

        // Tool blocks should be rendered as panels, not raw markers
        assertFalse(rendered.contains("[tool:Bash]"), "Raw tool marker should be rendered away");
        assertFalse(rendered.contains("[/tool]"), "Raw closing marker should be rendered away");
        assertTrue(rendered.contains("Bash"), "Tool name should appear in rendered output");
        assertTrue(rendered.contains("mvn test"), "Tool input should appear in rendered output");
    }

    @Test
    void judgedResponseWithToolResultRendersCorrectly() {
        String response = "Checking the test output.\n" +
                "[tool-result]\nBUILD SUCCESS\n3 tests passed\n[/tool-result]\n" +
                "All tests pass now after the fix.";

        String rendered = ascii.renderMarkdown(response);

        assertFalse(rendered.contains("[tool-result]"), "Raw tool-result marker should be rendered");
        assertFalse(rendered.contains("[/tool-result]"), "Raw closing marker should be rendered");
        assertTrue(rendered.contains("BUILD SUCCESS"), "Result content should be preserved");
    }

    @Test
    void judgedBadResponseStillRendersForReview() {
        // Even bad responses should render properly (so the user can see what went wrong)
        String badResponse = "This is a pre-existing issue. Here's a workaround:\n" +
                "```java\n@Ignore\npublic void testBroken() {}\n```";

        var judgeResult = ResponseJudge.judge(badResponse);
        assertTrue(judgeResult.failed());

        String rendered = ascii.renderMarkdown(badResponse);
        // Should still render — the judge scores but doesn't suppress output
        assertTrue(rendered.contains("@Ignore"), "Code block content should be rendered");
        assertFalse(rendered.contains("```java"), "Code fence should be consumed by renderer");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONCURRENT INTERRUPT TESTS — thread safety
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void concurrentInterruptIsThreadSafe() throws InterruptedException {
        InterruptHandler handler = new InterruptHandler();
        AtomicInteger emittedTotal = new AtomicInteger(0);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);

        // Streaming thread
        Thread streamer = new Thread(() -> {
            started.countDown();
            for (int i = 0; i < 100; i++) {
                if (handler.checkAndClear()) break;
                emittedTotal.incrementAndGet();
                try { Thread.sleep(1); } catch (InterruptedException e) { break; }
            }
            done.countDown();
        });

        streamer.start();
        started.await(1, TimeUnit.SECONDS);

        // Let some tokens emit, then interrupt
        Thread.sleep(20);
        handler.interrupt("Judge detected violation");

        done.await(2, TimeUnit.SECONDS);

        assertTrue(emittedTotal.get() > 0, "Some tokens should have been emitted");
        assertTrue(emittedTotal.get() < 100, "Should have been interrupted before all 100");
        assertTrue(handler.wasInterrupted(), "Interrupt should be logged");
    }

    @Test
    void concurrentFeedbackDelivery() throws InterruptedException {
        InterruptHandler handler = new InterruptHandler();
        AtomicReference<String> receivedFeedback = new AtomicReference<>();
        CountDownLatch feedbackReceived = new CountDownLatch(1);

        // Agent thread — waits for interrupt
        Thread agent = new Thread(() -> {
            while (!handler.checkAndClear()) {
                try { Thread.sleep(5); } catch (InterruptedException e) { break; }
            }
            receivedFeedback.set(handler.consumeFeedback());
            feedbackReceived.countDown();
        });

        agent.start();

        // Judge thread — sends feedback after delay
        Thread.sleep(30);
        handler.sendFeedback("Fix the root cause, do not use workarounds.");

        assertTrue(feedbackReceived.await(2, TimeUnit.SECONDS),
                "Agent should receive feedback");
        assertEquals("Fix the root cause, do not use workarounds.",
                receivedFeedback.get());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INTEGRATION: full judge → interrupt → feedback → comply cycle
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void fullJudgeInterruptFeedbackCycle() {
        InterruptHandler handler = new InterruptHandler();
        List<String> conversationLog = new ArrayList<>();

        // === Turn 1: User asks to fix a test ===
        String userMessage = "Fix the failing test in FooTest.java";
        conversationLog.add("USER: " + userMessage);

        // === Turn 2: Agent gives bad response ===
        String badResponse = "Looking at FooTest.java, this appears to be a pre-existing " +
                "issue. As a workaround, we can add @Ignore to skip the test.";

        // Judge scores it
        var judge1 = ResponseJudge.judge(badResponse);
        assertTrue(judge1.failed(), "Bad response should fail judge");
        conversationLog.add("ASSISTANT (score=" + judge1.score() + "): " + badResponse);
        conversationLog.add("JUDGE: " + judge1.reasons());

        // Interrupt and send feedback
        handler.sendFeedback("STOP. Investigate the root cause. Do NOT skip tests or use workarounds.");
        handler.checkAndClear();
        String feedback = handler.consumeFeedback();
        conversationLog.add("FEEDBACK: " + feedback);

        // === Turn 3: Agent complies ===
        String goodResponse = "I investigated the test failure in FooTest.java.\n" +
                "[tool:Read]\n{\"file_path\": \"src/test/FooTest.java\"}\n[/tool]\n" +
                "[tool-result]\nAssertionError: expected 42 but was null\n[/tool-result]\n" +
                "The root cause is that the service returns null when the database " +
                "connection times out. I fixed it by adding a retry with fallback.\n" +
                "[tool:Edit]\n{\"file_path\": \"src/main/FooService.java\", " +
                "\"old_string\": \"return db.query(id);\", " +
                "\"new_string\": \"return retryWithFallback(() -> db.query(id));\"}\n[/tool]\n" +
                "[tool:Bash]\n{\"command\": \"mvn test -Dtest=FooTest\"}\n[/tool]\n" +
                "[tool-result]\nBUILD SUCCESS - 1 test passed\n[/tool-result]";

        var judge2 = ResponseJudge.judge(goodResponse);
        assertTrue(judge2.passed(), "Compliant response should pass judge");
        conversationLog.add("ASSISTANT (score=" + judge2.score() + "): " + goodResponse);
        conversationLog.add("JUDGE: " + judge2.reasons());

        // Verify the full cycle
        assertTrue(judge2.score() > 0, "Final score should be positive");
        assertTrue(judge2.score() > judge1.score(),
                "Compliant score should be much better than non-compliant");

        // Verify rendering works for the good response
        String rendered = ascii.renderMarkdown(goodResponse);
        assertFalse(rendered.contains("[tool:"), "Tool markers should be rendered");
        assertTrue(rendered.contains("FooTest"), "Content should be preserved");

        // Verify conversation log is complete
        assertEquals(6, conversationLog.size(), "Should have all conversation entries");
    }

    @Test
    void fullCycleWithThinkingBlock() {
        String response = "<thinking>\nLet me analyze this test failure carefully.\n" +
                "The user wants a fix, not a workaround.\n</thinking>\n" +
                "I investigated the failure and the root cause is a race condition. " +
                "I fixed it by adding synchronization.";

        // Judge should score positively (thinking doesn't penalize, fix is positive)
        var result = ResponseJudge.judge(response);
        assertTrue(result.passed(), "Response with thinking + fix should pass");

        // Render should handle thinking block
        String rendered = ascii.renderMarkdown(response);
        assertFalse(rendered.contains("<thinking>"), "Thinking tags should be rendered");
        assertTrue(rendered.contains("investigated"), "Content should be preserved");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FALLBACK SUPERVISOR — auto-resume with fallback agent
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Monitors cumulative and per-turn judge scores for an agent session.
     * When performance drops below configurable thresholds, triggers an
     * automatic agent switch (fallback) with conversation auto-resume.
     * <p>
     * Thresholds:
     * <ul>
     *   <li>Cumulative score: if total score across all turns drops below a floor</li>
     *   <li>Consecutive failures: if N turns in a row fail the judge</li>
     *   <li>Single-turn catastrophic: if a single turn scores below a critical threshold</li>
     * </ul>
     */
    static class FallbackSupervisor {

        /** Configuration for when to trigger fallback. */
        record Config(
                int cumulativeScoreFloor,     // e.g., -10 — total score that triggers fallback
                int consecutiveFailureLimit,  // e.g., 3 — N consecutive fails triggers fallback
                int catastrophicThreshold,    // e.g., -6 — single-turn score that triggers immediate fallback
                List<String> agentPriority    // e.g., ["claude", "codex", "gemini"] — fallback order
        ) {
            static Config defaults() {
                return new Config(-10, 3, -6,
                        List.of("claude", "codex", "gemini", "qwen", "opencode"));
            }

            static Config strict() {
                return new Config(-5, 2, -4,
                        List.of("claude", "codex", "gemini", "qwen", "opencode"));
            }

            static Config lenient() {
                return new Config(-20, 5, -10,
                        List.of("claude", "codex", "gemini", "qwen", "opencode"));
            }
        }

        /** Emitted when a fallback is triggered. */
        record FallbackEvent(
                String fromAgent,
                String toAgent,
                String reason,
                int cumulativeScore,
                int consecutiveFailures,
                int triggeringTurnScore,
                List<ChatHistory.Turn> conversationContext
        ) {}

        private final Config config;
        private final InterruptHandler interruptHandler;
        private String currentAgent;
        private int agentIndex;
        private int cumulativeScore;
        private int consecutiveFailures;
        private final List<ResponseJudge.JudgeResult> turnHistory = new ArrayList<>();
        private final List<FallbackEvent> fallbackEvents = new ArrayList<>();
        private final List<ChatHistory.Turn> conversationTurns = new ArrayList<>();
        private boolean exhausted = false;

        FallbackSupervisor(Config config, String initialAgent, InterruptHandler handler) {
            this.config = config;
            this.currentAgent = initialAgent;
            this.interruptHandler = handler;
            this.agentIndex = config.agentPriority.indexOf(initialAgent);
            if (agentIndex < 0) agentIndex = 0;
        }

        /** Record a user turn for context preservation. */
        void recordUserTurn(String content) {
            conversationTurns.add(new ChatHistory.Turn("user", content));
        }

        /**
         * Submit an agent response to the judge. Returns the judge result.
         * May trigger a fallback if thresholds are breached.
         */
        ResponseJudge.JudgeResult submitResponse(String response) {
            var result = ResponseJudge.judge(response);
            turnHistory.add(result);
            cumulativeScore += result.score();
            conversationTurns.add(new ChatHistory.Turn("assistant", response));

            if (result.failed()) {
                consecutiveFailures++;
            } else {
                consecutiveFailures = 0;
            }

            // Check thresholds
            FallbackEvent event = checkThresholds(result);
            if (event != null) {
                fallbackEvents.add(event);
                executeFallback(event);
            }

            return result;
        }

        private FallbackEvent checkThresholds(ResponseJudge.JudgeResult latestResult) {
            if (exhausted) return null;

            // Catastrophic single-turn failure
            if (latestResult.score() <= config.catastrophicThreshold) {
                return buildEvent("CATASTROPHIC: single turn scored " + latestResult.score() +
                        " (threshold: " + config.catastrophicThreshold + ")", latestResult.score());
            }

            // Consecutive failure limit
            if (consecutiveFailures >= config.consecutiveFailureLimit) {
                return buildEvent("CONSECUTIVE: " + consecutiveFailures +
                        " consecutive failures (limit: " + config.consecutiveFailureLimit + ")",
                        latestResult.score());
            }

            // Cumulative score floor
            if (cumulativeScore <= config.cumulativeScoreFloor) {
                return buildEvent("CUMULATIVE: total score " + cumulativeScore +
                        " below floor " + config.cumulativeScoreFloor, latestResult.score());
            }

            return null;
        }

        private FallbackEvent buildEvent(String reason, int triggeringScore) {
            String fromAgent = currentAgent;
            String toAgent = selectNextAgent();
            return new FallbackEvent(fromAgent, toAgent, reason,
                    cumulativeScore, consecutiveFailures, triggeringScore,
                    List.copyOf(conversationTurns));
        }

        private String selectNextAgent() {
            // Walk the priority list to find the next available agent
            for (int i = 1; i < config.agentPriority.size(); i++) {
                int nextIdx = (agentIndex + i) % config.agentPriority.size();
                String candidate = config.agentPriority.get(nextIdx);
                if (!candidate.equals(currentAgent)) {
                    return candidate;
                }
            }
            return currentAgent; // no other agent available
        }

        private void executeFallback(FallbackEvent event) {
            if (event.toAgent.equals(event.fromAgent)) {
                // Can't switch — all agents exhausted
                exhausted = true;
                return;
            }

            // Interrupt the current agent
            interruptHandler.sendFeedback(
                    "AGENT FALLBACK: switching from " + event.fromAgent +
                            " to " + event.toAgent + ". Reason: " + event.reason);
            interruptHandler.checkAndClear();
            interruptHandler.consumeFeedback();

            // Switch agent
            String previousAgent = currentAgent;
            currentAgent = event.toAgent;
            agentIndex = config.agentPriority.indexOf(event.toAgent);
            if (agentIndex < 0) agentIndex = 0;

            // Reset scores for the new agent (fresh start)
            cumulativeScore = 0;
            consecutiveFailures = 0;
        }

        // Accessors
        String getCurrentAgent() { return currentAgent; }
        int getCumulativeScore() { return cumulativeScore; }
        int getConsecutiveFailures() { return consecutiveFailures; }
        boolean isExhausted() { return exhausted; }
        List<FallbackEvent> getFallbackEvents() { return List.copyOf(fallbackEvents); }
        List<ResponseJudge.JudgeResult> getTurnHistory() { return List.copyOf(turnHistory); }
        List<ChatHistory.Turn> getConversationContext() { return List.copyOf(conversationTurns); }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FALLBACK SUPERVISOR TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void supervisorTracksScoresCorrectly() {
        var supervisor = new FallbackSupervisor(
                FallbackSupervisor.Config.defaults(), "claude", new InterruptHandler());

        supervisor.submitResponse("I investigated and found the root cause. I fixed it.");
        assertTrue(supervisor.getCumulativeScore() > 0);
        assertEquals(0, supervisor.getConsecutiveFailures());
        assertEquals("claude", supervisor.getCurrentAgent());
    }

    @Test
    void supervisorTriggersOnConsecutiveFailures() {
        var config = new FallbackSupervisor.Config(-100, 3, -100,
                List.of("claude", "codex", "gemini"));
        var supervisor = new FallbackSupervisor(config, "claude", new InterruptHandler());

        // 3 consecutive bad responses
        supervisor.submitResponse("This is a pre-existing issue.");
        assertEquals("claude", supervisor.getCurrentAgent(), "No fallback after 1 failure");

        supervisor.submitResponse("Here's a workaround for now.");
        assertEquals("claude", supervisor.getCurrentAgent(), "No fallback after 2 failures");

        supervisor.submitResponse("This is not reproducible on my machine.");
        assertEquals("codex", supervisor.getCurrentAgent(),
                "Should fallback to codex after 3 consecutive failures");

        // Verify fallback event
        assertEquals(1, supervisor.getFallbackEvents().size());
        var event = supervisor.getFallbackEvents().get(0);
        assertEquals("claude", event.fromAgent());
        assertEquals("codex", event.toAgent());
        assertTrue(event.reason().contains("CONSECUTIVE"));
        assertEquals(3, event.consecutiveFailures());
    }

    @Test
    void supervisorResetsAfterGoodResponse() {
        var config = new FallbackSupervisor.Config(-100, 3, -100,
                List.of("claude", "codex"));
        var supervisor = new FallbackSupervisor(config, "claude", new InterruptHandler());

        // 2 bad responses
        supervisor.submitResponse("This is a pre-existing issue.");
        supervisor.submitResponse("As a workaround, skip the test.");
        assertEquals(2, supervisor.getConsecutiveFailures());

        // 1 good response resets the streak
        supervisor.submitResponse("I investigated and found the root cause. I fixed it.");
        assertEquals(0, supervisor.getConsecutiveFailures());
        assertEquals("claude", supervisor.getCurrentAgent(), "No fallback — streak was broken");
    }

    @Test
    void supervisorTriggersOnCumulativeScoreFloor() {
        var config = new FallbackSupervisor.Config(-8, 100, -100,
                List.of("claude", "codex", "gemini"));
        var supervisor = new FallbackSupervisor(config, "claude", new InterruptHandler());

        // Accumulate negative scores (pre-existing = -3 each)
        supervisor.submitResponse("This is a pre-existing issue.");       // cumulative: -3
        supervisor.submitResponse("Also a workaround needed here.");      // cumulative: -6
        assertEquals("claude", supervisor.getCurrentAgent(), "Not yet at floor");

        supervisor.submitResponse("This was already broken before.");     // cumulative: -9 (floor is -8)
        assertEquals("codex", supervisor.getCurrentAgent(),
                "Should fallback when cumulative score hits floor");

        var event = supervisor.getFallbackEvents().get(0);
        assertTrue(event.reason().contains("CUMULATIVE"));
    }

    @Test
    void supervisorTriggersOnCatastrophicSingleTurn() {
        var config = new FallbackSupervisor.Config(-100, 100, -6,
                List.of("claude", "codex", "gemini"));
        var supervisor = new FallbackSupervisor(config, "claude", new InterruptHandler());

        // Single response with multiple violations
        supervisor.submitResponse(
                "This is a pre-existing issue. As a workaround, skip it. " +
                        "It's not reproducible anyway.");
        // pre-existing(-3) + workaround(-3) + not reproducible(-2) = -8

        assertEquals("codex", supervisor.getCurrentAgent(),
                "Should immediately fallback on catastrophic single turn");

        var event = supervisor.getFallbackEvents().get(0);
        assertTrue(event.reason().contains("CATASTROPHIC"));
        assertTrue(event.triggeringTurnScore() <= -6);
    }

    @Test
    void supervisorCascadesThroughAgentPriority() {
        var config = new FallbackSupervisor.Config(-100, 2, -100,
                List.of("claude", "codex", "gemini", "qwen"));
        var supervisor = new FallbackSupervisor(config, "claude", new InterruptHandler());

        // Agent 1 (claude) fails twice
        supervisor.submitResponse("Pre-existing issue.");
        supervisor.submitResponse("Here's a workaround.");
        assertEquals("codex", supervisor.getCurrentAgent());

        // Agent 2 (codex) fails twice
        supervisor.submitResponse("Pre-existing issue.");
        supervisor.submitResponse("Here's a workaround.");
        assertEquals("gemini", supervisor.getCurrentAgent());

        // Agent 3 (gemini) fails twice
        supervisor.submitResponse("Pre-existing issue.");
        supervisor.submitResponse("Here's a workaround.");
        assertEquals("qwen", supervisor.getCurrentAgent());

        assertEquals(3, supervisor.getFallbackEvents().size());
        assertEquals("claude", supervisor.getFallbackEvents().get(0).fromAgent());
        assertEquals("codex", supervisor.getFallbackEvents().get(1).fromAgent());
        assertEquals("gemini", supervisor.getFallbackEvents().get(2).fromAgent());
    }

    @Test
    void supervisorPreservesConversationContextOnFallback() {
        var config = new FallbackSupervisor.Config(-100, 2, -100,
                List.of("claude", "codex"));
        var supervisor = new FallbackSupervisor(config, "claude", new InterruptHandler());

        supervisor.recordUserTurn("Fix the failing test in FooTest.java");
        supervisor.submitResponse("Pre-existing issue, skip it.");
        supervisor.recordUserTurn("No, actually fix it.");
        supervisor.submitResponse("Still a workaround: @Ignore the test.");

        // Fallback triggered — context should be preserved
        assertEquals("codex", supervisor.getCurrentAgent());

        var event = supervisor.getFallbackEvents().get(0);
        assertEquals(4, event.conversationContext().size());
        assertEquals("user", event.conversationContext().get(0).role());
        assertEquals("Fix the failing test in FooTest.java",
                event.conversationContext().get(0).content());
    }

    @Test
    void supervisorScoreResetsAfterFallback() {
        var config = new FallbackSupervisor.Config(-5, 100, -100,
                List.of("claude", "codex"));
        var supervisor = new FallbackSupervisor(config, "claude", new InterruptHandler());

        // Claude tanks
        supervisor.submitResponse("Pre-existing issue.");
        supervisor.submitResponse("Workaround: skip it.");
        assertEquals("codex", supervisor.getCurrentAgent());

        // Codex starts fresh
        assertEquals(0, supervisor.getCumulativeScore(),
                "New agent should start with clean score");
        assertEquals(0, supervisor.getConsecutiveFailures(),
                "New agent should start with clean failure count");

        // Codex does well
        supervisor.submitResponse("I investigated and found the root cause. I fixed it.");
        assertTrue(supervisor.getCumulativeScore() > 0);
        assertEquals("codex", supervisor.getCurrentAgent(), "Good agent should not fallback");
    }

    @Test
    void supervisorExhaustionWhenAllAgentsFail() {
        // Only 2 agents available
        var config = new FallbackSupervisor.Config(-100, 2, -100,
                List.of("claude", "codex"));
        var supervisor = new FallbackSupervisor(config, "claude", new InterruptHandler());

        // Claude fails
        supervisor.submitResponse("Pre-existing issue.");
        supervisor.submitResponse("Workaround.");
        assertEquals("codex", supervisor.getCurrentAgent());

        // Codex fails — but there's nowhere to go (claude already failed)
        supervisor.submitResponse("Pre-existing issue.");
        supervisor.submitResponse("Workaround.");

        // Should be exhausted — stays on codex since claude already failed
        assertTrue(supervisor.getFallbackEvents().size() >= 2);
    }

    @Test
    void supervisorStrictConfigTriggersEarlier() {
        var lenient = new FallbackSupervisor(
                FallbackSupervisor.Config.lenient(), "claude", new InterruptHandler());
        var strict = new FallbackSupervisor(
                FallbackSupervisor.Config.strict(), "claude", new InterruptHandler());

        // Same bad responses to both
        String bad1 = "This is a pre-existing issue.";
        String bad2 = "Here's a workaround.";

        lenient.submitResponse(bad1);
        strict.submitResponse(bad1);
        lenient.submitResponse(bad2);
        strict.submitResponse(bad2);

        // Strict should trigger fallback, lenient should not
        assertTrue(strict.getFallbackEvents().size() > 0,
                "Strict config should trigger fallback after 2 failures");
        assertEquals(0, lenient.getFallbackEvents().size(),
                "Lenient config should NOT trigger after only 2 failures");
    }

    @Test
    void supervisorInterruptsCurrentAgentOnFallback() {
        InterruptHandler handler = new InterruptHandler();
        var config = new FallbackSupervisor.Config(-100, 2, -100,
                List.of("claude", "codex"));
        var supervisor = new FallbackSupervisor(config, "claude", handler);

        supervisor.submitResponse("Pre-existing issue.");
        supervisor.submitResponse("Workaround.");

        // Interrupt handler should have received fallback feedback
        assertTrue(handler.wasInterrupted(), "Handler should record the fallback interrupt");
        assertTrue(handler.getLog().stream().anyMatch(l -> l.contains("AGENT FALLBACK")),
                "Log should contain AGENT FALLBACK entry");
        assertTrue(handler.getLog().stream().anyMatch(l -> l.contains("claude") && l.contains("codex")),
                "Log should mention both agents");
    }

    @Test
    void supervisorNoFallbackWhenAgentDoesWell() {
        var supervisor = new FallbackSupervisor(
                FallbackSupervisor.Config.strict(), "claude", new InterruptHandler());

        // All good responses
        for (int i = 0; i < 10; i++) {
            supervisor.submitResponse(
                    "I investigated turn " + i + " and found the root cause. I fixed it.");
        }

        assertEquals("claude", supervisor.getCurrentAgent());
        assertEquals(0, supervisor.getFallbackEvents().size());
        assertTrue(supervisor.getCumulativeScore() > 0);
    }

    @Test
    void supervisorRecoverAfterFallbackAndGoodAgent() {
        var config = new FallbackSupervisor.Config(-100, 2, -100,
                List.of("claude", "codex", "gemini"));
        var supervisor = new FallbackSupervisor(config, "claude", new InterruptHandler());

        // Claude fails
        supervisor.submitResponse("Pre-existing issue.");
        supervisor.submitResponse("Workaround.");
        assertEquals("codex", supervisor.getCurrentAgent());

        // Codex does well — no further fallback
        supervisor.submitResponse("I investigated and found the root cause. I fixed it.");
        supervisor.submitResponse("I debugged another issue. The problem is caused by X. I resolved it.");
        supervisor.submitResponse("All tests pass after the fix.");
        assertEquals("codex", supervisor.getCurrentAgent(), "Good agent should stay");
        assertEquals(1, supervisor.getFallbackEvents().size(), "Only one fallback should have occurred");
        assertTrue(supervisor.getCumulativeScore() > 0,
                "Codex score should be positive after good turns");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FULL SUPERVISOR INTEGRATION
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void fullSupervisorAutoResumeFlow() {
        InterruptHandler handler = new InterruptHandler();
        var config = new FallbackSupervisor.Config(-10, 3, -6,
                List.of("claude", "codex", "gemini"));
        var supervisor = new FallbackSupervisor(config, "claude", handler);

        // ── User prompt ──
        supervisor.recordUserTurn("Fix the test failures in the auth module");

        // ── Claude: bad responses ──
        supervisor.submitResponse("The auth tests have pre-existing issues from the migration.");
        supervisor.submitResponse("As a workaround, we can skip the auth test suite.");
        supervisor.submitResponse("These failures are not reproducible in my environment.");

        // ── Should have fallen back to codex ──
        assertEquals("codex", supervisor.getCurrentAgent(),
                "After 3 consecutive failures, should fallback to codex");

        var fallbackEvent = supervisor.getFallbackEvents().get(0);
        // Context preserved for auto-resume
        assertFalse(fallbackEvent.conversationContext().isEmpty(),
                "Context should be preserved for resume");
        assertEquals("Fix the test failures in the auth module",
                fallbackEvent.conversationContext().get(0).content());

        // ── Codex: good response ──
        supervisor.submitResponse(
                "I investigated the auth test failures.\n" +
                        "[tool:Bash]\n{\"command\": \"mvn test -pl auth-module\"}\n[/tool]\n" +
                        "[tool-result]\nNullPointerException at AuthService.java:42\n[/tool-result]\n" +
                        "The root cause is a null session token. " +
                        "I fixed it by initializing the token in the constructor.\n" +
                        "[tool:Edit]\n{\"file_path\": \"AuthService.java\"}\n[/tool]");

        assertEquals("codex", supervisor.getCurrentAgent(), "Good codex should stay");
        assertTrue(supervisor.getCumulativeScore() > 0,
                "Score should be positive after codex fix");

        // ── Verify rendering ──
        String lastResponse = supervisor.getConversationContext()
                .get(supervisor.getConversationContext().size() - 1).content();
        String rendered = ascii.renderMarkdown(lastResponse);
        assertTrue(rendered.contains("Bash"), "Tool blocks should render");
        assertTrue(rendered.contains("AuthService"), "Content should be preserved");
    }

    @Test
    void fullSupervisorWithFeedbackThenFallback() {
        InterruptHandler handler = new InterruptHandler();
        var config = new FallbackSupervisor.Config(-10, 3, -6,
                List.of("claude", "codex", "gemini"));
        var supervisor = new FallbackSupervisor(config, "claude", handler);

        supervisor.recordUserTurn("Fix the build");

        // ── Turn 1: Bad, gets feedback ──
        var r1 = supervisor.submitResponse("This is a pre-existing issue.");
        assertTrue(r1.failed());

        // Manual feedback (user corrects agent)
        handler.sendFeedback("No. Investigate and fix it.");
        handler.checkAndClear();
        handler.consumeFeedback();

        // ── Turn 2: Still bad ──
        supervisor.submitResponse("As a workaround, disable the module.");

        // ── Turn 3: Still bad → triggers fallback ──
        supervisor.submitResponse("It's not reproducible.");

        assertEquals("codex", supervisor.getCurrentAgent());

        // ── Turn 4: Codex complies ──
        var r4 = supervisor.submitResponse(
                "I investigated and found the root cause. The build fails because " +
                        "of a missing dependency. I fixed it by adding the dependency.");
        assertTrue(r4.passed());
        assertEquals("codex", supervisor.getCurrentAgent());
    }

    @Test
    void supervisorTurnHistoryIsComplete() {
        var supervisor = new FallbackSupervisor(
                FallbackSupervisor.Config.defaults(), "claude", new InterruptHandler());

        supervisor.submitResponse("Pre-existing issue.");
        supervisor.submitResponse("I investigated and fixed it.");
        supervisor.submitResponse("Here is a workaround.");

        var history = supervisor.getTurnHistory();
        assertEquals(3, history.size());
        assertTrue(history.get(0).failed());
        assertTrue(history.get(1).passed());
        assertTrue(history.get(2).failed());
    }
}
