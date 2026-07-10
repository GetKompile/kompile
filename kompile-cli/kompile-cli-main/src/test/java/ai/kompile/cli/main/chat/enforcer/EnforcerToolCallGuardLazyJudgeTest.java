package ai.kompile.cli.main.chat.enforcer;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.harness.HarnessConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

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
        EnforcerRuntimePolicy runtimePolicy = EnforcerRuntimePolicy.create(
                wd, policy, HarnessConfig.load(objectMapper), objectMapper);
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
}
