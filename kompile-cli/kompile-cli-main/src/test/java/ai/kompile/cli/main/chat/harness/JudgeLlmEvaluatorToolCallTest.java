package ai.kompile.cli.main.chat.harness;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.enforcer.EnforcerToolCallDecision;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JudgeLlmEvaluatorToolCallTest {

    @Test
    void reviewsProposedMcpCallWithMainTurnContext() throws Exception {
        AtomicReference<String> prompt = new AtomicReference<>();
        AtomicReference<String> system = new AtomicReference<>();
        JudgeBackend backend = new JudgeBackend() {
            @Override
            public String generate(String userPrompt, String systemPrompt) {
                prompt.set(userPrompt);
                system.set(systemPrompt);
                return "{\"action\":\"BLOCK\",\"reason\":\"unrelated write\","
                        + "\"violations\":[\"not requested\"],"
                        + "\"correction_prompt\":\"Use read instead\",\"rewrittenArgs\":null}";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };
        JudgeLlmEvaluator evaluator = new JudgeLlmEvaluator(
                backend, JsonUtils.standardMapper());

        EnforcerToolCallDecision decision = evaluator.evaluateToolCall(
                "Inspect the configuration", "I will inspect it", "write",
                "{\"file_path\":\"config.json\"}");

        assertEquals(EnforcerToolCallDecision.Action.BLOCK, decision.getAction());
        assertEquals("Use read instead", decision.getCorrectionPrompt());
        assertTrue(prompt.get().contains("Inspect the configuration"));
        assertTrue(prompt.get().contains("I will inspect it"));
        assertTrue(prompt.get().contains("name: write"));
        assertEquals(JudgeLlmEvaluator.TOOL_CALL_JUDGE_SYSTEM_PROMPT, system.get());
    }

    @Test
    void malformedQualityJudgeOutputIsAnEvaluatorFailureNotAPolicyBlock() {
        AtomicInteger calls = new AtomicInteger();
        JudgeBackend backend = new JudgeBackend() {
            @Override
            public String generate(String userPrompt, String systemPrompt) {
                calls.incrementAndGet();
                return "I think this looks fine";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };
        JudgeLlmEvaluator evaluator = new JudgeLlmEvaluator(
                backend, JsonUtils.standardMapper());

        assertThrows(IllegalStateException.class, () -> evaluator.evaluateToolCall(
                "Inspect", "", "read", "{\"file_path\":\"pom.xml\"}"));
        assertEquals(2, calls.get(), "malformed model output gets one bounded format repair");
    }

    @Test
    void providerErrorIsNotRetriedOrReportedAsMalformedJson() {
        AtomicInteger calls = new AtomicInteger();
        JudgeBackend backend = new JudgeBackend() {
            @Override
            public String generate(String userPrompt, String systemPrompt) {
                calls.incrementAndGet();
                return "[OpenAI Codex API error 429: usage limit reached]";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };
        JudgeLlmEvaluator evaluator = new JudgeLlmEvaluator(
                backend, JsonUtils.standardMapper());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> evaluator.evaluateToolCall(
                        "Inspect", "", "read", "{\"file_path\":\"pom.xml\"}"));

        assertTrue(failure.getMessage().contains("error response"));
        assertEquals(1, calls.get(), "transport failures must never enter format repair");
    }
}
