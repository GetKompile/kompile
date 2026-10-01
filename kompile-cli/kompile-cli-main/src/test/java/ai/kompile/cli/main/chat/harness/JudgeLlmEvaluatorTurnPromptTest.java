package ai.kompile.cli.main.chat.harness;

import ai.kompile.cli.common.util.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The judge weighs a turn against what the turn did: its prompt reports the turn's
 * steps and tool calls, by tool. Without them a turn that launched an agent reads as
 * one that only claimed to.
 */
class JudgeLlmEvaluatorTurnPromptTest {

    private static final String VERDICT = "{\"correctness\":5,\"completeness\":5,"
            + "\"design_quality\":null,\"thinking_coherence\":null,"
            + "\"reasoning\":\"The agent was launched.\"}";

    @Test
    void thePromptReportsTheTurnsStepsAndToolCallsByTool() {
        AtomicReference<String> prompt = new AtomicReference<>();
        JudgeLlmEvaluator evaluator = new JudgeLlmEvaluator(
                capturing(prompt), JsonUtils.standardMapper());

        JudgeDimensions verdict = evaluator.evaluate(TurnMetrics.builder()
                .taskPrompt("Launch a background agent to survey the repository")
                .agentOutput("agent launched")
                .agenticSteps(3)
                .toolCallsTotal(1)
                .toolCallErrors(0)
                .toolCallBreakdown(Map.of("Agent", 1))
                .build(), "general", "coder");

        assertFalse(verdict.isError(), verdict.getErrorDetail());
        String text = prompt.get();
        assertTrue(text.contains("Steps taken: 3\n"), text);
        assertTrue(text.contains("Tool calls: 1 (0 errors)\n"), text);
        assertTrue(text.contains("Tools used: Agent 1\n"), text);
    }

    @Test
    void aTurnWithoutToolCallsListsNoTools() {
        AtomicReference<String> prompt = new AtomicReference<>();
        JudgeLlmEvaluator evaluator = new JudgeLlmEvaluator(
                capturing(prompt), JsonUtils.standardMapper());

        JudgeDimensions verdict = evaluator.evaluate(TurnMetrics.builder()
                .taskPrompt("Answer two plus two")
                .agentOutput("Four.")
                .agenticSteps(1)
                .build(), "general", "coder");

        assertFalse(verdict.isError(), verdict.getErrorDetail());
        String text = prompt.get();
        assertTrue(text.contains("Steps taken: 1\n"), text);
        assertTrue(text.contains("Tool calls: 0 (0 errors)\n"), text);
        assertFalse(text.contains("Tools used:"), text);
    }

    private static JudgeBackend capturing(AtomicReference<String> prompt) {
        return new JudgeBackend() {
            @Override
            public String generate(String userPrompt, String systemPrompt) {
                prompt.set(userPrompt);
                return VERDICT;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };
    }
}
