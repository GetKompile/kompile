package ai.kompile.app.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class SessionMetricsServiceTest {

    @TempDir
    Path tempDir;

    private String originalUserHome;

    @BeforeEach
    void useTemporaryHome() throws Exception {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());

        Path conversations = tempDir.resolve(".kompile/conversations");
        Files.createDirectories(conversations.resolve("provider-usage"));
        Files.createDirectories(conversations.resolve("tool-calls"));
        Files.writeString(conversations.resolve("direct.metrics.json"), """
                {
                  "session": {
                    "sessionId": "direct",
                    "provider": "openai",
                    "model": "gpt-test",
                    "agent": "codex"
                  },
                  "turns": {"user": 1, "assistant": 1, "total": 2},
                  "tokens": {
                    "input": 70,
                    "output": 10,
                    "cacheRead": 20,
                    "cacheCreation": 5,
                    "total": 105
                  },
                  "tools": {"totalCalls": 0, "totalErrors": 0}
                }
                """);
        Files.writeString(conversations.resolve("provider-usage/imported.json"), """
                {
                  "sessionId": "imported",
                  "provider": "gemini",
                  "model": "gemini-test",
                  "projectDirectory": "/project/imported",
                  "inputTokens": 100,
                  "outputTokens": 10,
                  "totalTokens": 110,
                  "cacheReadTokens": 80,
                  "cacheCreationTokens": 5,
                  "apiCalls": 1
                }
                """);
        Files.writeString(conversations.resolve("tool-calls/all-tool-calls.jsonl"),
                "{\"sessionId\":\"direct\",\"projectDirectory\":\"/project/direct\"}\n");
    }

    @AfterEach
    void restoreUserHome() {
        if (originalUserHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void aggregationUsesEachMetricsSourceCacheAccountingDialect() {
        SessionMetricsService service = new SessionMetricsService();

        Map<String, Object> aggregated = service.getAggregatedStats();
        Map<String, Object> tokens = map(aggregated.get("tokens"));
        assertNumber(170, tokens.get("totalInput"));
        assertNumber(20, tokens.get("totalOutput"));
        assertNumber(100, tokens.get("cacheRead"));
        assertNumber(10, tokens.get("cacheCreation"));
        assertNumber(215, tokens.get("total"));

        Map<String, Object> byProvider = map(aggregated.get("tokensByProvider"));
        assertNumber(105, byProvider.get("openai"));
        assertNumber(110, byProvider.get("gemini"));
        Map<String, Object> byAgent = map(aggregated.get("tokensByAgent"));
        assertNumber(105, byAgent.get("codex"));

        Map<String, Object> providerUsage = service.getProviderUsageStats();
        assertNumber(110, providerUsage.get("totalTokens"));
        assertNumber(80, providerUsage.get("totalCacheReadTokens"));
        assertNumber(5, providerUsage.get("totalCacheCreationTokens"));

        Map<String, Object> projects = map(service.getByProject().get("projects"));
        Map<String, Object> directProject = map(projects.get("/project/direct"));
        Map<String, Object> projectTokens = map(directProject.get("tokens"));
        assertNumber(105, projectTokens.get("total"));
        assertNumber(20, projectTokens.get("cacheRead"));
        assertNumber(5, projectTokens.get("cacheCreation"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static void assertNumber(long expected, Object actual) {
        assertEquals(expected, ((Number) actual).longValue());
    }
}
