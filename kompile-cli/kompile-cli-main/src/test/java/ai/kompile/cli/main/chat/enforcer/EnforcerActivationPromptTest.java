package ai.kompile.cli.main.chat.enforcer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The activation prompt's decision logic: only an explicit yes enables enforcement;
 * everything else (empty, no, EOF, garbage) declines.
 */
class EnforcerActivationPromptTest {

    @Test
    void onlyExplicitYesActivates() {
        assertTrue(EnforcerActivationPrompt.interpretAnswer("y"));
        assertTrue(EnforcerActivationPrompt.interpretAnswer("Y"));
        assertTrue(EnforcerActivationPrompt.interpretAnswer("yes"));
        assertTrue(EnforcerActivationPrompt.interpretAnswer("  YES "));

        assertFalse(EnforcerActivationPrompt.interpretAnswer(""), "empty input defaults to No");
        assertFalse(EnforcerActivationPrompt.interpretAnswer("   "));
        assertFalse(EnforcerActivationPrompt.interpretAnswer("n"));
        assertFalse(EnforcerActivationPrompt.interpretAnswer("no"));
        assertFalse(EnforcerActivationPrompt.interpretAnswer(null), "EOF defaults to No");
        assertFalse(EnforcerActivationPrompt.interpretAnswer("yeah"), "only y/yes count");
        assertFalse(EnforcerActivationPrompt.interpretAnswer("true"));
    }

    @Test
    void describeConfigSummarizesModeAndRules() {
        EnforcerConfig keyword = new EnforcerConfig();
        keyword.setKeywordMode(true);
        keyword.setInlineRules("BAN_CMD: rm -rf\nSTOP_CMD: git push --force");
        keyword.setBannedTools(List.of("bash"));

        String description = EnforcerActivationPrompt.describeConfig(keyword);
        assertTrue(description.contains("keyword mode"));
        assertTrue(description.contains("3 rules"), "2 inline lines + 1 banned tool: " + description);

        EnforcerConfig judge = new EnforcerConfig();
        judge.setRuleFile("rules.txt");
        String judgeDescription = EnforcerActivationPrompt.describeConfig(judge);
        assertTrue(judgeDescription.contains("LLM-judge mode"));
        assertTrue(judgeDescription.contains("rules.txt"));

        assertEquals("enforcer config", EnforcerActivationPrompt.describeConfig(null));
    }
}
