package ai.kompile.cli.main.chat.enforcer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnforcerServiceTest {

    @Test
    void acceptsFirstCompliantResponse() {
        EnforcerService service = new EnforcerService(new FixedEvaluator(
                List.of(EnforcerDecision.pass("rules satisfied"))));

        EnforcerResult result = service.enforce(
                "answer briefly",
                new EnforcerPolicy("Use one sentence.", 2, false),
                prompt -> "Done.");

        assertEquals(EnforcerResult.Status.ACCEPTED, result.getStatus());
        assertEquals(1, result.getAttempts().size());
        assertEquals("Done.", result.getFinalOutput());
    }

    @Test
    void retriesWithCorrectionPromptUntilAccepted() {
        List<String> prompts = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        EnforcerService service = new EnforcerService(new FixedEvaluator(List.of(
                EnforcerDecision.fail(List.of("Too verbose"), "Rewrite in one sentence.", "violated length rule"),
                EnforcerDecision.pass("fixed")
        )));

        EnforcerResult result = service.enforce(
                "summarize",
                new EnforcerPolicy("Use exactly one sentence.", 2, false),
                prompt -> {
                    prompts.add(prompt);
                    return calls.incrementAndGet() == 1 ? "Long answer.\nMore text." : "Short answer.";
                });

        assertEquals(EnforcerResult.Status.ACCEPTED, result.getStatus());
        assertEquals(2, result.getAttempts().size());
        assertTrue(prompts.get(1).contains("Rewrite in one sentence."));
        assertEquals("Short answer.", result.getFinalOutput());
    }

    @Test
    void blocksWhenCorrectionLimitIsReached() {
        EnforcerService service = new EnforcerService(new FixedEvaluator(List.of(
                EnforcerDecision.fail(List.of("Missing citation"), "Add a citation.", "missing required citation"),
                EnforcerDecision.fail(List.of("Still missing citation"), "Add a citation.", "still missing")
        )));

        EnforcerResult result = service.enforce(
                "state a fact",
                new EnforcerPolicy("Every answer must cite a source.", 1, false),
                prompt -> "No citation.");

        assertEquals(EnforcerResult.Status.BLOCKED, result.getStatus());
        assertEquals(2, result.getAttempts().size());
        assertTrue(result.getMessage().contains("Maximum corrections reached"));
    }

    @Test
    void runsAgentUnjudgedWhenJudgeReadinessFailsByDefault() {
        AtomicInteger executorCalls = new AtomicInteger();
        EnforcerEvaluator unavailable = new FixedEvaluator(
                List.of(EnforcerDecision.pass("should not be reached"))) {
            @Override
            public boolean awaitReady(long timeoutMs) {
                return false;
            }

            @Override
            public String describe() {
                return "stale-cli";
            }
        };

        EnforcerResult result = new EnforcerService(unavailable).enforce(
                "answer",
                new EnforcerPolicy("Use one sentence.", 0, false),
                prompt -> {
                    executorCalls.incrementAndGet();
                    return "unjudged output";
                });

        assertEquals(EnforcerResult.Status.ACCEPTED, result.getStatus());
        assertEquals(1, executorCalls.get(), "judge unavailability must fail open");
        assertEquals("unjudged output", result.getFinalOutput());
    }

    @Test
    void acceptsAgentOutputWhenJudgeThrows() {
        EnforcerEvaluator failingJudge = new FixedEvaluator(
                List.of(EnforcerDecision.pass("unused"))) {
            @Override
            public EnforcerDecision evaluate(String userPrompt, String agentOutput,
                                             EnforcerPolicy policy, int attempt) {
                throw new IllegalStateException("judge timed out");
            }
        };

        EnforcerResult result = new EnforcerService(failingJudge).enforce(
                "answer",
                new EnforcerPolicy("Use one sentence.", 0, false),
                prompt -> "agent output");

        assertEquals(EnforcerResult.Status.ACCEPTED, result.getStatus());
        assertEquals("agent output", result.getFinalOutput());
        assertTrue(result.getJudgeBackend().contains("fail-open"));
    }

    @Test
    void doesNotMistakeAgentFailureForJudgeFailure() {
        EnforcerService service = new EnforcerService(new FixedEvaluator(
                List.of(EnforcerDecision.pass("unused"))));

        EnforcerResult result = service.enforce(
                "answer",
                new EnforcerPolicy("Use one sentence.", 0, false),
                prompt -> { throw new IllegalStateException("agent crashed"); });

        assertEquals(EnforcerResult.Status.ERROR, result.getStatus());
        assertTrue(result.getMessage().contains("Agent run failed"));
    }

    @Test
    void passesRecentContextToEvaluator() {
        List<EnforcerConversationContext> seen = new ArrayList<>();
        EnforcerService service = new EnforcerService(new FixedEvaluator(
                List.of(EnforcerDecision.pass("rules satisfied"))) {
            @Override
            public EnforcerDecision evaluate(String userPrompt, String agentOutput,
                                             EnforcerPolicy policy, int attempt,
                                             EnforcerConversationContext context) {
                seen.add(context);
                return super.evaluate(userPrompt, agentOutput, policy, attempt);
            }
        });

        EnforcerResult result = service.enforce(
                "answer",
                new EnforcerPolicy("Use context.", 0, false),
                () -> EnforcerConversationContext.of(List.of(
                        new EnforcerConversationContext.Message("user", "answer"))),
                prompt -> "Done.");

        assertEquals(EnforcerResult.Status.ACCEPTED, result.getStatus());
        assertEquals("answer", seen.get(0).getMessages().get(0).content());
    }

    private static class FixedEvaluator implements EnforcerEvaluator {
        private final List<EnforcerDecision> decisions;
        private int index;

        private FixedEvaluator(List<EnforcerDecision> decisions) {
            this.decisions = decisions;
        }

        @Override
        public EnforcerDecision evaluate(String userPrompt, String agentOutput,
                                         EnforcerPolicy policy, int attempt) {
            int decisionIndex = Math.min(index, decisions.size() - 1);
            index++;
            return decisions.get(decisionIndex);
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String describe() {
            return "fixed";
        }
    }
}
