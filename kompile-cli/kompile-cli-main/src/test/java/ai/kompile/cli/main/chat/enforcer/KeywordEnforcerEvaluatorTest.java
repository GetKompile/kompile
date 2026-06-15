package ai.kompile.cli.main.chat.enforcer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KeywordEnforcerEvaluatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void passesWhenNoBannedKeywordsFound() {
        KeywordEnforcerEvaluator eval = new KeywordEnforcerEvaluator(
                List.of(new KeywordEnforcerEvaluator.KeywordRule(
                        "rm -rf", false, false, "Never use rm -rf", "error")),
                "Never use rm -rf");

        EnforcerDecision decision = eval.evaluate("list files", "Here are the files: a.txt, b.txt",
                EnforcerPolicy.from("Never use rm -rf", 2), 1);

        assertTrue(decision.isCompliant());
        assertFalse(decision.isStop());
    }

    @Test
    void failsWhenBannedKeywordDetected() {
        KeywordEnforcerEvaluator eval = new KeywordEnforcerEvaluator(
                List.of(new KeywordEnforcerEvaluator.KeywordRule(
                        "killall java", false, false, "Never kill all java processes", "error")),
                "Never kill all java processes");

        EnforcerDecision decision = eval.evaluate("clean up", "Let me run killall java to clean up",
                EnforcerPolicy.from("Never kill all java processes", 2), 1);

        assertFalse(decision.isCompliant());
        assertFalse(decision.isStop());
        assertEquals(1, decision.getViolations().size());
        assertTrue(decision.getViolations().get(0).contains("Never kill all java"));
        assertTrue(decision.getCorrectionPrompt().contains("STOP"));
        assertTrue(decision.getCorrectionPrompt().contains("How to Re-Comply"));
    }

    @Test
    void stopsOnCriticalSeverity() {
        KeywordEnforcerEvaluator eval = new KeywordEnforcerEvaluator(
                List.of(new KeywordEnforcerEvaluator.KeywordRule(
                        "DROP TABLE", false, false, "Never drop tables", "critical")),
                "Never drop tables");

        EnforcerDecision decision = eval.evaluate("cleanup", "Running DROP TABLE users;",
                EnforcerPolicy.from("Never drop tables", 2), 1);

        assertFalse(decision.isCompliant());
        assertTrue(decision.isStop());
    }

    @Test
    void caseInsensitiveByDefault() {
        KeywordEnforcerEvaluator eval = new KeywordEnforcerEvaluator(
                List.of(new KeywordEnforcerEvaluator.KeywordRule(
                        "sudo", false, false, "No sudo", "error")),
                "No sudo");

        EnforcerDecision decision = eval.evaluate("install", "Running SUDO apt install...",
                EnforcerPolicy.from("No sudo", 2), 1);

        assertFalse(decision.isCompliant());
    }

    @Test
    void caseSensitiveWhenConfigured() {
        KeywordEnforcerEvaluator eval = new KeywordEnforcerEvaluator(
                List.of(new KeywordEnforcerEvaluator.KeywordRule(
                        "SECRET", false, true, "No SECRET keyword", "error")),
                "No SECRET keyword");

        // lowercase should pass
        EnforcerDecision pass = eval.evaluate("x", "this is a secret message",
                EnforcerPolicy.from("No SECRET keyword", 2), 1);
        assertTrue(pass.isCompliant());

        // uppercase should fail
        EnforcerDecision fail = eval.evaluate("x", "this contains SECRET data",
                EnforcerPolicy.from("No SECRET keyword", 2), 1);
        assertFalse(fail.isCompliant());
    }

    @Test
    void regexPatternMatching() {
        KeywordEnforcerEvaluator eval = new KeywordEnforcerEvaluator(
                List.of(new KeywordEnforcerEvaluator.KeywordRule(
                        "git\\s+push\\s+--force", true, false, "No force push", "error")),
                "No force push");

        EnforcerDecision decision = eval.evaluate("deploy", "Running: git  push  --force origin main",
                EnforcerPolicy.from("No force push", 2), 1);

        assertFalse(decision.isCompliant());
    }

    @Test
    void parsesLineFormatBanPrefix() {
        EnforcerPolicy policy = new EnforcerPolicy(
                "BAN: killall java\nBAN: rm -rf /\nSTOP: DROP DATABASE\n# comment\n", 2, false);

        KeywordEnforcerEvaluator eval = KeywordEnforcerEvaluator.fromPolicy(policy, objectMapper);

        assertTrue(eval.isAvailable());
        assertEquals(3, eval.getRules().size());
        assertEquals("error", eval.getRules().get(0).getSeverity());
        assertEquals("critical", eval.getRules().get(2).getSeverity());
    }

    @Test
    void parsesLineFormatRegexPrefix() {
        EnforcerPolicy policy = new EnforcerPolicy("REGEX: git\\s+reset\\s+--hard\n", 2, false);

        KeywordEnforcerEvaluator eval = KeywordEnforcerEvaluator.fromPolicy(policy, objectMapper);

        assertTrue(eval.isAvailable());
        assertEquals(1, eval.getRules().size());
        assertTrue(eval.getRules().get(0).isRegex());
    }

    @Test
    void parsesJsonFormat() {
        String json = "[{\"keyword\":\"ollama\",\"description\":\"No external model servers\",\"severity\":\"error\"},"
                + "{\"keyword\":\"CUDA_VISIBLE\",\"description\":\"Never restrict GPU visibility\",\"severity\":\"critical\"}]";
        EnforcerPolicy policy = new EnforcerPolicy(json, 2, false);

        KeywordEnforcerEvaluator eval = KeywordEnforcerEvaluator.fromPolicy(policy, objectMapper);

        assertTrue(eval.isAvailable());
        assertEquals(2, eval.getRules().size());
        assertEquals("ollama", eval.getRules().get(0).getKeyword());
    }

    @Test
    void parsesJsonObjectWithRulesKey() {
        String json = "{\"rules\":[{\"keyword\":\"make\",\"isRegex\":false,\"description\":\"Use Maven not make\"}]}";
        EnforcerPolicy policy = new EnforcerPolicy(json, 2, false);

        KeywordEnforcerEvaluator eval = KeywordEnforcerEvaluator.fromPolicy(policy, objectMapper);

        assertTrue(eval.isAvailable());
        assertEquals(1, eval.getRules().size());
    }

    @Test
    void plainLinesAreTreatedAsBannedKeywords() {
        EnforcerPolicy policy = new EnforcerPolicy("ollama\nnpx\nCUDA_VISIBLE_DEVICES", 2, false);

        KeywordEnforcerEvaluator eval = KeywordEnforcerEvaluator.fromPolicy(policy, objectMapper);

        assertTrue(eval.isAvailable());
        assertEquals(3, eval.getRules().size());

        // Test detection
        EnforcerDecision decision = eval.evaluate("setup", "Let me use npx to run this",
                policy, 1);
        assertFalse(decision.isCompliant());
    }

    @Test
    void multipleViolationsReportedAtOnce() {
        KeywordEnforcerEvaluator eval = new KeywordEnforcerEvaluator(
                List.of(
                        new KeywordEnforcerEvaluator.KeywordRule("rm -rf", false, false, "No rm -rf", "error"),
                        new KeywordEnforcerEvaluator.KeywordRule("sudo", false, false, "No sudo", "error")
                ), "No rm -rf\nNo sudo");

        EnforcerDecision decision = eval.evaluate("cleanup", "Running: sudo rm -rf /tmp/*",
                EnforcerPolicy.from("rules", 2), 1);

        assertFalse(decision.isCompliant());
        assertEquals(2, decision.getViolations().size());
    }

    @Test
    void correctionMessageIncludesRulesAndRecomplianceSteps() {
        EnforcerPolicy policy = new EnforcerPolicy("BAN: ollama\nBAN: npx", 2, false);
        KeywordEnforcerEvaluator eval = KeywordEnforcerEvaluator.fromPolicy(policy, objectMapper);

        EnforcerDecision decision = eval.evaluate("setup", "Use ollama serve to start",
                policy, 1);

        assertFalse(decision.isCompliant());
        String correction = decision.getCorrectionPrompt();
        assertTrue(correction.contains("STOP"), "Should tell LLM to stop");
        assertTrue(correction.contains("BAN: ollama"), "Should paste the rules");
        assertTrue(correction.contains("Re-Comply"), "Should have re-compliance steps");
        assertTrue(correction.contains("STOP your current approach"), "Should have step 1");
    }

    @Test
    void emptyOutputPasses() {
        KeywordEnforcerEvaluator eval = new KeywordEnforcerEvaluator(
                List.of(new KeywordEnforcerEvaluator.KeywordRule("bad", false, false, "No bad", "error")),
                "No bad");

        EnforcerDecision decision = eval.evaluate("x", "",
                EnforcerPolicy.from("No bad", 2), 1);

        assertTrue(decision.isCompliant());
    }

    @Test
    void describeIncludesRuleCount() {
        KeywordEnforcerEvaluator eval = new KeywordEnforcerEvaluator(
                List.of(
                        new KeywordEnforcerEvaluator.KeywordRule("a", false, false, null, "error"),
                        new KeywordEnforcerEvaluator.KeywordRule("b", false, false, null, "error")
                ), "");

        assertEquals("keyword-evaluator (2 rules, 0 tool bans)", eval.describe());
    }

    @Test
    void notAvailableWithNoRules() {
        KeywordEnforcerEvaluator eval = new KeywordEnforcerEvaluator(List.of(), "");
        assertFalse(eval.isAvailable());
    }

    // ── Tool call evaluation tests ──────────────────────────────────────

    @Test
    void toolCallAllowedWhenNoToolRulesMatch() {
        KeywordEnforcerEvaluator eval = new KeywordEnforcerEvaluator(
                List.of(new KeywordEnforcerEvaluator.KeywordRule(
                        "rm -rf", false, false, "No rm -rf", "error", "command")),
                "No rm -rf");

        EnforcerToolCallDecision decision = eval.evaluateToolCall("bash",
                "{\"command\": \"ls -la\"}", EnforcerPolicy.from("No rm -rf", 2));

        assertTrue(decision.isAllowed());
    }

    @Test
    void toolCallBlockedWhenCommandBanMatches() {
        KeywordEnforcerEvaluator eval = new KeywordEnforcerEvaluator(
                List.of(new KeywordEnforcerEvaluator.KeywordRule(
                        "rm -rf", false, false, "No rm -rf", "error", "command")),
                "No rm -rf");

        EnforcerToolCallDecision decision = eval.evaluateToolCall("bash",
                "{\"command\": \"rm -rf /tmp/stuff\"}", EnforcerPolicy.from("No rm -rf", 2));

        assertFalse(decision.isAllowed());
        assertEquals(EnforcerToolCallDecision.Action.BLOCK, decision.getAction());
        assertTrue(decision.blockMessage().contains("rm -rf"));
    }

    @Test
    void toolCallBlockedByToolNameBan() {
        KeywordEnforcerEvaluator eval = new KeywordEnforcerEvaluator(
                List.of(new KeywordEnforcerEvaluator.KeywordRule(
                        "bash", false, false, "bash tool is banned", "error", "tool")),
                "bash tool is banned");

        EnforcerToolCallDecision decision = eval.evaluateToolCall("bash",
                "{\"command\": \"echo hello\"}", EnforcerPolicy.from("BAN_TOOL: bash", 2));

        assertFalse(decision.isAllowed());
        assertTrue(decision.blockMessage().contains("bash"));
    }

    @Test
    void toolCallAllowedForDifferentTool() {
        KeywordEnforcerEvaluator eval = new KeywordEnforcerEvaluator(
                List.of(new KeywordEnforcerEvaluator.KeywordRule(
                        "bash", false, false, "bash tool is banned", "error", "tool")),
                "bash tool is banned");

        EnforcerToolCallDecision decision = eval.evaluateToolCall("read",
                "{\"file\": \"test.txt\"}", EnforcerPolicy.from("BAN_TOOL: bash", 2));

        assertTrue(decision.isAllowed());
    }

    @Test
    void toolCallCorrectionIncludesRulesAndRecomplianceSteps() {
        EnforcerPolicy policy = new EnforcerPolicy("BAN_TOOL: bash\nBAN_CMD: rm -rf", 2, false);
        KeywordEnforcerEvaluator eval = KeywordEnforcerEvaluator.fromPolicy(policy, objectMapper);

        EnforcerToolCallDecision decision = eval.evaluateToolCall("bash",
                "{\"command\": \"rm -rf /\"}", policy);

        assertFalse(decision.isAllowed());
        String correction = decision.getCorrectionPrompt();
        assertTrue(correction.contains("STOP"), "Should tell LLM to stop");
        assertTrue(correction.contains("BLOCKED"), "Should indicate tool was blocked");
        assertTrue(correction.contains("BAN_TOOL: bash"), "Should paste the rules");
        assertTrue(correction.contains("Re-Comply"), "Should have re-compliance steps");
        assertTrue(correction.contains("ALTERNATIVE"), "Should tell to find alternative");
    }

    @Test
    void parsesLineBanToolPrefix() {
        EnforcerPolicy policy = new EnforcerPolicy(
                "BAN_TOOL: bash\nBAN_TOOL: write\nSTOP_TOOL: dangerous_tool\n", 2, false);

        KeywordEnforcerEvaluator eval = KeywordEnforcerEvaluator.fromPolicy(policy, objectMapper);

        assertTrue(eval.isAvailable());
        assertEquals(3, eval.getRules().size());
        assertEquals("tool", eval.getRules().get(0).getScope());
        assertEquals("tool", eval.getRules().get(1).getScope());
        assertEquals("critical", eval.getRules().get(2).getSeverity());
    }

    @Test
    void parsesLineBanCmdPrefix() {
        EnforcerPolicy policy = new EnforcerPolicy(
                "BAN_CMD: git push --force\nBAN_CMD_REGEX: kill(all)?\\s+java\n", 2, false);

        KeywordEnforcerEvaluator eval = KeywordEnforcerEvaluator.fromPolicy(policy, objectMapper);

        assertEquals(2, eval.getRules().size());
        assertEquals("command", eval.getRules().get(0).getScope());
        assertEquals("command", eval.getRules().get(1).getScope());
        assertTrue(eval.getRules().get(1).isRegex());
    }

    @Test
    void toolBanDoesNotAffectOutputEvaluation() {
        KeywordEnforcerEvaluator eval = new KeywordEnforcerEvaluator(
                List.of(new KeywordEnforcerEvaluator.KeywordRule(
                        "bash", false, false, "bash tool is banned", "error", "tool")),
                "bash tool is banned");

        // Output containing "bash" should pass since this is a tool-scoped rule
        EnforcerDecision decision = eval.evaluate("help", "You can use bash for scripting",
                EnforcerPolicy.from("BAN_TOOL: bash", 2), 1);

        assertTrue(decision.isCompliant());
    }

    @Test
    void allScopeChecksOutputAndToolCalls() {
        // Plain lines default to "all" scope
        EnforcerPolicy policy = new EnforcerPolicy("killall java", 2, false);
        KeywordEnforcerEvaluator eval = KeywordEnforcerEvaluator.fromPolicy(policy, objectMapper);

        // Should catch in output
        EnforcerDecision outputDecision = eval.evaluate("fix", "Let me run killall java",
                policy, 1);
        assertFalse(outputDecision.isCompliant());

        // Should also catch in tool args
        EnforcerToolCallDecision toolDecision = eval.evaluateToolCall("bash",
                "{\"command\": \"killall java\"}", policy);
        assertFalse(toolDecision.isAllowed());
    }

    @Test
    void stopToolCausesCriticalBlock() {
        EnforcerPolicy policy = new EnforcerPolicy("STOP_TOOL: bash", 2, false);
        KeywordEnforcerEvaluator eval = KeywordEnforcerEvaluator.fromPolicy(policy, objectMapper);

        EnforcerToolCallDecision decision = eval.evaluateToolCall("bash",
                "{\"command\": \"echo hi\"}", policy);

        assertFalse(decision.isAllowed());
        // The violation should be recorded
        assertFalse(decision.getViolations().isEmpty());
    }

    @Test
    void jsonFormatWithScope() {
        String json = "[{\"keyword\":\"bash\",\"scope\":\"tool\",\"description\":\"No bash\"},"
                + "{\"keyword\":\"rm -rf\",\"scope\":\"tool_arg\",\"description\":\"No rm -rf\"},"
                + "{\"keyword\":\"sudo\",\"scope\":\"all\",\"description\":\"No sudo\"}]";
        EnforcerPolicy policy = new EnforcerPolicy(json, 2, false);
        KeywordEnforcerEvaluator eval = KeywordEnforcerEvaluator.fromPolicy(policy, objectMapper);

        assertEquals(3, eval.getRules().size());
        assertEquals("tool", eval.getRules().get(0).getScope());
        assertEquals("tool_arg", eval.getRules().get(1).getScope());
        assertEquals("all", eval.getRules().get(2).getScope());
    }

    @Test
    void toolArgScopeOnlyChecksArgs() {
        KeywordEnforcerEvaluator eval = new KeywordEnforcerEvaluator(
                List.of(new KeywordEnforcerEvaluator.KeywordRule(
                        "secret.key", false, false, "No reading secret.key", "error", "tool_arg")),
                "");

        // Tool name doesn't match
        EnforcerToolCallDecision decision = eval.evaluateToolCall("read",
                "{\"file\": \"/etc/secret.key\"}", EnforcerPolicy.from("", 2));
        assertFalse(decision.isAllowed());

        // Different file is fine
        EnforcerToolCallDecision okDecision = eval.evaluateToolCall("read",
                "{\"file\": \"/tmp/test.txt\"}", EnforcerPolicy.from("", 2));
        assertTrue(okDecision.isAllowed());
    }

    @Test
    void describeShowsToolBanCount() {
        KeywordEnforcerEvaluator eval = new KeywordEnforcerEvaluator(
                List.of(
                        new KeywordEnforcerEvaluator.KeywordRule("a", false, false, null, "error", "output"),
                        new KeywordEnforcerEvaluator.KeywordRule("bash", false, false, null, "error", "tool"),
                        new KeywordEnforcerEvaluator.KeywordRule("rm", false, false, null, "error", "command")
                ), "");

        assertEquals("keyword-evaluator (3 rules, 2 tool bans)", eval.describe());
    }

    @Test
    void stopCmdPrefix() {
        EnforcerPolicy policy = new EnforcerPolicy("STOP_CMD: format c:", 2, false);
        KeywordEnforcerEvaluator eval = KeywordEnforcerEvaluator.fromPolicy(policy, objectMapper);

        assertEquals(1, eval.getRules().size());
        assertEquals("command", eval.getRules().get(0).getScope());
        assertEquals("critical", eval.getRules().get(0).getSeverity());
    }
}
