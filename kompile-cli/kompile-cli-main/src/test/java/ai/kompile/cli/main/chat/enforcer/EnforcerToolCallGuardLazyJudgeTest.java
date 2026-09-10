package ai.kompile.cli.main.chat.enforcer;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ReminderManager;
import ai.kompile.cli.main.chat.harness.HarnessConfig;
import ai.kompile.cli.main.chat.harness.JudgeBackend;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The tool-call guard must NOT construct its LLM judge at build time. The eager judge
 * used to spawn a persistent judge agent inside every {@code kompile mcp-stdio} launch
 * carrying the enforcer environment, stalling the MCP initialize handshake.
 */
class EnforcerToolCallGuardLazyJudgeTest {

    private final ObjectMapper objectMapper = JsonUtils.standardMapper();

    private EnforcerToolCallGuard guardWithRules(Path wd, String rules) throws Exception {
        EnforcerPolicy policy = new EnforcerPolicy(rules, 1, false);
        HarnessConfig config = new HarnessConfig();
        config.setJudgeGlobalEnabled(true);
        EnforcerRuntimePolicy runtimePolicy = EnforcerRuntimePolicy.create(
                wd, policy, config, objectMapper);
        return new EnforcerToolCallGuard(objectMapper, runtimePolicy);
    }

    @Test
    void constructionDoesNotBuildJudge(@TempDir Path wd) throws Exception {
        try (EnforcerToolCallGuard guard = guardWithRules(wd, "BAN_TOOL: bash\nBAN_CMD: rm -rf")) {
            assertTrue(guard.isActive());
            assertTrue(guard.describe().contains("lazy"),
                    "judge must not exist before the first LLM-needing evaluation: " + guard.describe());
        }
    }

    @Test
    void keywordBlockNeverNeedsTheJudge(@TempDir Path wd) throws Exception {
        try (EnforcerToolCallGuard guard = guardWithRules(wd, "BAN_TOOL: bash\nBAN_CMD: rm -rf")) {
            EnforcerToolCallDecision banned = guard.evaluate("bash", Map.of("command", "ls"));
            assertFalse(banned.isAllowed(), "BAN_TOOL: bash must block the bash tool");

            EnforcerToolCallDecision bannedArgs = guard.evaluate("write", Map.of("content", "rm -rf /tmp/x"));
            assertFalse(bannedArgs.isAllowed(), "BAN_CMD: rm -rf must block matching args");

            // Both decisions came from the keyword fast-path — still no judge.
            assertTrue(guard.describe().contains("lazy"),
                    "keyword-decided calls must not construct the judge: " + guard.describe());
        }
    }

    @Test
    void closeIsSafeWithoutJudge(@TempDir Path wd) throws Exception {
        EnforcerToolCallGuard guard = guardWithRules(wd, "BAN_TOOL: bash");
        assertDoesNotThrow(guard::close);
    }

    @Test
    void judgeExceptionFailsOpenAfterKeywordCheck(@TempDir Path wd) throws Exception {
        EnforcerPolicy policy = new EnforcerPolicy("Only use tools relevant to the request.", 1, false);
        EnforcerRuntimePolicy runtimePolicy = EnforcerRuntimePolicy.create(
                wd, policy, HarnessConfig.load(objectMapper), objectMapper);
        JudgeBackend failingBackend = new JudgeBackend() {
            @Override
            public String generate(String userPrompt, String systemPrompt) {
                throw new IllegalStateException("judge timed out");
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };

        try (EnforcerToolCallGuard guard = new EnforcerToolCallGuard(
                objectMapper, runtimePolicy, new EnforcerJudge(failingBackend, objectMapper))) {
            EnforcerToolCallDecision decision = guard.evaluate("read", Map.of("file_path", "README.md"));

            assertTrue(decision.isAllowed(), "judge infrastructure errors must fail open");
        }
    }

    @Test
    void routineTodoUpdateSkipsLlmUnlessExplicitlyBanned(@TempDir Path wd) throws Exception {
        EnforcerPolicy policy = new EnforcerPolicy(
                "Use tools relevant to the request.\nBAN_TOOL: bash", 1, false);
        HarnessConfig config = new HarnessConfig();
        config.setJudgeGlobalEnabled(true);
        EnforcerRuntimePolicy runtimePolicy = EnforcerRuntimePolicy.create(
                wd, policy, config, objectMapper);
        AtomicInteger calls = new AtomicInteger();
        JudgeBackend backend = new JudgeBackend() {
            @Override public String generate(String userPrompt, String systemPrompt) {
                calls.incrementAndGet();
                return "{\"action\":\"BLOCK\",\"reason\":\"over-eager\"}";
            }
            @Override public boolean isAvailable() { return true; }
        };

        try (EnforcerToolCallGuard guard = new EnforcerToolCallGuard(
                objectMapper, runtimePolicy, new EnforcerJudge(backend, objectMapper))) {
            EnforcerToolCallDecision allowed = guard.evaluate(
                    "mcp__kompile__todowrite", Map.of("action", "update", "task_id", "x"));

            assertTrue(allowed.isAllowed());
            assertEquals(0, calls.get(), "routine bookkeeping must not invoke the LLM judge");
        }

        try (EnforcerToolCallGuard banned = guardWithRules(wd, "BAN_TOOL: todowrite")) {
            assertFalse(banned.evaluate("todowrite", Map.of("action", "set")).isAllowed(),
                    "an explicit deterministic ban must still win");
        }
    }

    @Test
    void liveRemindersReachNestedToolJudgeAndDisableRoutineBypass(@TempDir Path wd)
            throws Exception {
        ReminderManager reminders = ReminderManager.forStorage(objectMapper,
                wd.resolve("session-reminders.json"),
                wd.resolve("project/.kompile/chat-reminders.json"));
        reminders.add(ReminderManager.Scope.PROJECT, "Plan before changing files");
        EnforcerPolicy policy = new EnforcerPolicy(
                "Only use tools relevant to the request.", 1, false);
        HarnessConfig config = new HarnessConfig();
        config.setJudgeGlobalEnabled(true);
        EnforcerRuntimePolicy runtimePolicy = EnforcerRuntimePolicy.create(
                wd, policy, config, objectMapper, reminders);
        EnforcerConversationContext.of(java.util.List.of(
                new EnforcerConversationContext.Message("user", "change the parser")))
                .write(runtimePolicy.getContextFile(), objectMapper);

        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> prompt = new AtomicReference<>();
        JudgeBackend backend = new JudgeBackend() {
            @Override public String generate(String userPrompt, String systemPrompt) {
                calls.incrementAndGet();
                prompt.set(userPrompt);
                return "{\"action\":\"ALLOW\",\"reason\":\"plan is present\"}";
            }
            @Override public boolean isAvailable() { return true; }
        };

        try (EnforcerToolCallGuard guard = new EnforcerToolCallGuard(
                objectMapper, runtimePolicy, new EnforcerJudge(backend, objectMapper))) {
            EnforcerToolCallDecision allowed = guard.evaluate(
                    "todowrite", Map.of("action", "set"));

            assertTrue(allowed.isAllowed());
            assertEquals(1, calls.get(),
                    "an active reminder must disable the routine-tool judge bypass");
            assertTrue(prompt.get().contains("[ACTIVE REMINDER CONSTRAINTS]"));
            assertTrue(prompt.get().contains("Plan before changing files"));
            assertTrue(prompt.get().contains("user: change the parser"));

            reminders.handleCommand(ReminderManager.Scope.PROJECT, "interval off");
            assertTrue(guard.evaluate("todowrite", Map.of("action", "update")).isAllowed());
            assertEquals(1, calls.get(),
                    "turning reminders off must restore the deterministic routine fast path");
        }
    }
}
