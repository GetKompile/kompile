package ai.kompile.cli.main.chat.enforcer;

import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KeywordRealtimeMonitorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void allowsTextWithNoBannedKeywords() {
        KeywordEnforcerEvaluator eval = new KeywordEnforcerEvaluator(
                List.of(new KeywordEnforcerEvaluator.KeywordRule(
                        "rm -rf", false, false, "No rm -rf", "critical", "all")),
                "");
        EnforcerPolicy policy = new EnforcerPolicy("BAN: rm -rf", 2, false);
        KeywordRealtimeMonitor monitor = new KeywordRealtimeMonitor(eval, policy);

        // Short text below threshold
        SubprocessAgentRunner.MonitorDecision d = monitor.onTextChunk("hello", "hello world");
        assertFalse(d.interrupt());
    }

    @Test
    void interruptsTextOnCriticalKeyword() {
        KeywordEnforcerEvaluator eval = new KeywordEnforcerEvaluator(
                List.of(new KeywordEnforcerEvaluator.KeywordRule(
                        "DROP TABLE", false, false, "No dropping tables", "critical", "all")),
                "");
        EnforcerPolicy policy = new EnforcerPolicy("STOP: DROP TABLE", 2, false);
        KeywordRealtimeMonitor monitor = new KeywordRealtimeMonitor(eval, policy);

        // Build up text past threshold
        String longText = "x".repeat(300) + " executing DROP TABLE users;";
        SubprocessAgentRunner.MonitorDecision d = monitor.onTextChunk(".", longText);
        assertTrue(d.interrupt());
        assertTrue(d.reason().contains("No dropping tables"));
    }

    @Test
    void blocksToolCallByName() {
        KeywordEnforcerEvaluator eval = new KeywordEnforcerEvaluator(
                List.of(new KeywordEnforcerEvaluator.KeywordRule(
                        "bash", false, false, "bash is banned", "error", "tool")),
                "");
        EnforcerPolicy policy = new EnforcerPolicy("BAN_TOOL: bash", 2, false);
        KeywordRealtimeMonitor monitor = new KeywordRealtimeMonitor(eval, policy);

        SubprocessAgentRunner.MonitorDecision d = monitor.onToolUse("bash", "{\"command\": \"echo hi\"}");
        assertTrue(d.interrupt());
        assertTrue(d.reason().contains("bash"));
        assertFalse(d.correctionPrompt().isEmpty());
    }

    @Test
    void allowsNonBannedTool() {
        KeywordEnforcerEvaluator eval = new KeywordEnforcerEvaluator(
                List.of(new KeywordEnforcerEvaluator.KeywordRule(
                        "bash", false, false, "bash is banned", "error", "tool")),
                "");
        EnforcerPolicy policy = new EnforcerPolicy("BAN_TOOL: bash", 2, false);
        KeywordRealtimeMonitor monitor = new KeywordRealtimeMonitor(eval, policy);

        SubprocessAgentRunner.MonitorDecision d = monitor.onToolUse("read", "{\"file\": \"test.txt\"}");
        assertFalse(d.interrupt());
    }

    @Test
    void blocksToolCallByArgContent() {
        KeywordEnforcerEvaluator eval = new KeywordEnforcerEvaluator(
                List.of(new KeywordEnforcerEvaluator.KeywordRule(
                        "git push --force", false, false, "No force push", "error", "command")),
                "");
        EnforcerPolicy policy = new EnforcerPolicy("BAN_CMD: git push --force", 2, false);
        KeywordRealtimeMonitor monitor = new KeywordRealtimeMonitor(eval, policy);

        SubprocessAgentRunner.MonitorDecision d = monitor.onToolUse("bash",
                "{\"command\": \"git push --force origin main\"}");
        assertTrue(d.interrupt());
        assertTrue(d.reason().contains("force push"));
    }

    @Test
    void correctionPromptContainsRulesAndRecomplianceInstructions() {
        EnforcerPolicy policy = new EnforcerPolicy("BAN_TOOL: bash\nBAN_CMD: killall", 2, false);
        KeywordEnforcerEvaluator eval = KeywordEnforcerEvaluator.fromPolicy(policy, objectMapper);
        KeywordRealtimeMonitor monitor = new KeywordRealtimeMonitor(eval, policy);

        SubprocessAgentRunner.MonitorDecision d = monitor.onToolUse("bash",
                "{\"command\": \"killall java\"}");
        assertTrue(d.interrupt());

        String correction = d.correctionPrompt();
        assertTrue(correction.contains("STOP"), "Tells LLM to stop");
        assertTrue(correction.contains("BLOCKED"), "Says tool was blocked");
        assertTrue(correction.contains("BAN_TOOL: bash"), "Pastes the rules");
        assertTrue(correction.contains("Re-Comply"), "Includes re-compliance steps");
    }

    @Test
    void doesNotInterruptBelowMinCharThreshold() {
        KeywordEnforcerEvaluator eval = new KeywordEnforcerEvaluator(
                List.of(new KeywordEnforcerEvaluator.KeywordRule(
                        "bad", false, false, "No bad", "critical", "all")),
                "");
        EnforcerPolicy policy = new EnforcerPolicy("STOP: bad", 2, false);
        KeywordRealtimeMonitor monitor = new KeywordRealtimeMonitor(eval, policy);

        // Text contains "bad" but is too short for evaluation
        SubprocessAgentRunner.MonitorDecision d = monitor.onTextChunk("bad", "this is bad");
        assertFalse(d.interrupt(), "Should not interrupt below min char threshold");
    }
}
