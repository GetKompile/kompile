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
package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.core.llm.ModelContextWindows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossAgentResumeCompactorTest {

    @TempDir
    Path tempDir;

    @Test
    void leavesTranscriptUnchangedWhenItAlreadyFitsTargetBudget() {
        List<ChatHistory.Turn> turns = List.of(
                turn("user", "Investigate the resume command."),
                turn("assistant", "The OpenCode path should use --session."),
                turn("user", "Run the focused tests."));
        CrossAgentResumeCompactor.TargetBudget budget =
                new CrossAgentResumeCompactor.TargetBudget("gpt-4o", 128_000, 16_384, 1_000);

        CrossAgentResumeCompactor.Result result = CrossAgentResumeCompactor.compactToFit(turns, budget);

        assertFalse(result.compacted());
        assertEquals(turns, result.turns());
        assertEquals(result.tokensBefore(), result.tokensAfter());
    }

    @Test
    void compactsOlderTurnsAndPreservesRecentTailWithinTargetBudget() {
        List<ChatHistory.Turn> turns = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            turns.add(turn(i % 2 == 0 ? "user" : "assistant",
                    "turn-" + i + " src/main/java/Example" + i + ".java "
                            + "diagnostic detail ".repeat(260)));
        }
        turns.add(turn("user", "recent user request: verify OpenCode resume flags"));
        turns.add(turn("assistant", "recent assistant answer: use --session for native resume"));
        CrossAgentResumeCompactor.TargetBudget budget =
                new CrossAgentResumeCompactor.TargetBudget("tiny-target", 4_096, 1_024, 1_000);

        CrossAgentResumeCompactor.Result result = CrossAgentResumeCompactor.compactToFit(turns, budget);

        assertTrue(result.compacted());
        assertTrue(CrossAgentResumeCompactor.estimateTokens(result.turns()) <= budget.exportTokenBudget());
        assertTrue(result.turns().get(0).content().contains("[Compacted cross-agent resume context]"));
        assertTrue(result.summarizedTurns() > 0);
        assertEquals("recent assistant answer: use --session for native resume",
                result.turns().get(result.turns().size() - 1).content());
    }

    @Test
    void repeatedlyCompactsSummaryUntilVerySmallTargetBudgetFits() {
        List<ChatHistory.Turn> turns = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            turns.add(turn(i % 2 == 0 ? "user" : "assistant",
                    "error failed fixed todo command file /tmp/session-" + i + " "
                            + "context payload ".repeat(320)));
        }
        turns.add(turn("user", "latest request"));
        turns.add(turn("assistant", "latest response"));
        CrossAgentResumeCompactor.TargetBudget budget =
                new CrossAgentResumeCompactor.TargetBudget("very-small-target", 2_048, 512, 260);

        CrossAgentResumeCompactor.Result result = CrossAgentResumeCompactor.compactToFit(turns, budget);

        assertTrue(result.compacted());
        assertTrue(result.stages() > 1, "summary should need more than one compaction stage");
        assertTrue(CrossAgentResumeCompactor.estimateTokens(result.turns()) <= budget.exportTokenBudget());
    }

    @Test
    void targetBudgetUsesConfiguredClaudeModelWhenProvided() {
        String previous = System.getProperty("kompile.claude.model");
        try {
            System.setProperty("kompile.claude.model", "claude-opus-4-7");

            CrossAgentResumeCompactor.TargetBudget budget =
                    CrossAgentResumeCompactor.targetBudget("claude", "opencode", tempDir);

            assertEquals("claude-opus-4-7", budget.modelId());
            // claude-opus-4-7 is a 1M-context model per the live CLI catalog
            // (providers: anthropic/302ai/auriko report ctx=1_000_000, out=128_000)
            assertEquals(1_000_000, budget.contextWindow());
            assertEquals(128_000, budget.maxOutputTokens());
        } finally {
            restoreProperty("kompile.claude.model", previous);
        }
    }

    @Test
    void targetBudgetUsesConfiguredCodexModelOverride() {
        String previous = System.getProperty("kompile.codex.model");
        try {
            System.setProperty("kompile.codex.model", "gpt-5-codex");

            CrossAgentResumeCompactor.TargetBudget budget =
                    CrossAgentResumeCompactor.targetBudget("codex", "claude", tempDir);

            assertEquals("gpt-5-codex", budget.modelId());
            assertEquals(400_000, budget.contextWindow());
            assertEquals(128_000, budget.maxOutputTokens());
        } finally {
            restoreProperty("kompile.codex.model", previous);
        }
    }

    @Test
    void targetBudgetUsesCodexConfigModelWhenPresent() throws Exception {
        Path config = tempDir.resolve("codex-config.toml");
        Files.writeString(config, """
                model = "gpt-5.5"
                model_reasoning_effort = "xhigh"
                """);

        String previousConfig = System.getProperty("kompile.codex.config");
        String previousModel = System.getProperty("kompile.codex.model");
        try {
            System.setProperty("kompile.codex.config", config.toString());
            System.clearProperty("kompile.codex.model");

            CrossAgentResumeCompactor.TargetBudget budget =
                    CrossAgentResumeCompactor.targetBudget("codex", "claude", tempDir);

            assertEquals("gpt-5.5", budget.modelId());
            assertEquals(1_050_000, budget.contextWindow());
            assertEquals(128_000, budget.maxOutputTokens());
        } finally {
            restoreProperty("kompile.codex.config", previousConfig);
            restoreProperty("kompile.codex.model", previousModel);
        }
    }

    @Test
    void modelWindowTableProvidesValidBudgetsForClaudeAndCodexVariants() {
        // Limits come from the dynamic-first CLI catalog and may change as providers update models.

        assertModel("claude-opus-4-7");
        assertModel("claude-sonnet-4-20250514");
        assertModel("claude-haiku-4-5");
        assertModel("anthropic/claude-sonnet-4.5");

        assertModel("gpt-5.5");
        assertModel("gpt-5-codex");
        assertModel("gpt-5.4-mini");
        assertModel("openai/gpt-5.2-codex");
        assertModel("gpt-4o");

        assertModel("deepseek-v4-flash-free");
        assertModel("deepseek-v4-flash");
        assertModel("opencode/deepseek-v4-pro");
    }

    private static ChatHistory.Turn turn(String role, String content) {
        return new ChatHistory.Turn(role, content, null);
    }

    private static void assertModel(String model) {
        int contextWindow = ModelContextWindows.getContextWindow(model);
        int maxOutputTokens = ModelContextWindows.getMaxOutputTokens(model);
        assertTrue(contextWindow > 0, model + " should resolve a positive context window");
        assertTrue(maxOutputTokens > 0, model + " should resolve a positive output limit");
        assertTrue(maxOutputTokens <= contextWindow, model + " output limit must fit its context window");
    }

    private static void restoreProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
