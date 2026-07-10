package ai.kompile.cli.main.chat.config;

import ai.kompile.core.llm.CliModelCatalog;
import ai.kompile.core.llm.ModelContextWindows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class ModelContextWindowsTest {

    private static final String CATALOG_PATHS_PROPERTY = "kompile.cli.modelCatalogPaths";

    private String previousCatalogPaths;

    @BeforeEach
    void isolateStaticFallbackTable() throws Exception {
        previousCatalogPaths = System.getProperty(CATALOG_PATHS_PROPERTY);
        System.setProperty(CATALOG_PATHS_PROPERTY,
                System.getProperty("java.io.tmpdir") + "/kompile-no-model-catalog-for-static-test.json");
        invalidateCatalogCache();
    }

    @AfterEach
    void restoreCatalogPath() throws Exception {
        if (previousCatalogPaths == null) {
            System.clearProperty(CATALOG_PATHS_PROPERTY);
        } else {
            System.setProperty(CATALOG_PATHS_PROPERTY, previousCatalogPaths);
        }
        invalidateCatalogCache();
    }

    private void invalidateCatalogCache() throws Exception {
        Method method = CliModelCatalog.class.getDeclaredMethod("invalidateCacheForTest");
        method.setAccessible(true);
        method.invoke(null);
    }

    // --- supportsVision: vision-capable models ---

    @Test
    void claudeSonnet4SupportsVision() {
        assertTrue(ModelContextWindows.supportsVision("claude-sonnet-4"));
    }

    @Test
    void claudeOpus4SupportsVision() {
        assertTrue(ModelContextWindows.supportsVision("claude-opus-4"));
    }

    @Test
    void gpt4oSupportsVision() {
        assertTrue(ModelContextWindows.supportsVision("gpt-4o"));
    }

    @Test
    void gpt41SupportsVision() {
        assertTrue(ModelContextWindows.supportsVision("gpt-4.1"));
    }

    @Test
    void gpt4TurboSupportsVision() {
        assertTrue(ModelContextWindows.supportsVision("gpt-4-turbo"));
    }

    @Test
    void o4MiniSupportsVision() {
        assertTrue(ModelContextWindows.supportsVision("o4-mini"));
    }

    @Test
    void gemini25ProSupportsVision() {
        assertTrue(ModelContextWindows.supportsVision("gemini-2.5-pro"));
    }

    @Test
    void gemini20FlashSupportsVision() {
        assertTrue(ModelContextWindows.supportsVision("gemini-2.0-flash"));
    }

    // --- supportsVision: text-only models ---

    @Test
    void gpt4DoesNotSupportVision() {
        assertFalse(ModelContextWindows.supportsVision("gpt-4"));
    }

    @Test
    void gpt35TurboDoesNotSupportVision() {
        assertFalse(ModelContextWindows.supportsVision("gpt-3.5-turbo"));
    }

    @Test
    void deepseekChatDoesNotSupportVision() {
        assertFalse(ModelContextWindows.supportsVision("deepseek-chat"));
    }

    @Test
    void deepseekCoderDoesNotSupportVision() {
        assertFalse(ModelContextWindows.supportsVision("deepseek-coder"));
    }

    // --- supportsVision: prefix matching for versioned IDs ---

    @Test
    void versionedClaudeIdMatchesByPrefix() {
        assertTrue(ModelContextWindows.supportsVision("claude-sonnet-4-20250514"));
    }

    @Test
    void versionedGpt4oIdMatchesByPrefix() {
        assertTrue(ModelContextWindows.supportsVision("gpt-4o-2024-11-20"));
    }

    // --- supportsVision: OpenRouter-style provider prefix stripping ---

    @Test
    void openRouterAnthropicPrefixStripped() {
        assertTrue(ModelContextWindows.supportsVision("anthropic/claude-sonnet-4"));
    }

    @Test
    void openRouterGooglePrefixStripped() {
        assertTrue(ModelContextWindows.supportsVision("google/gemini-2.5-pro"));
    }

    @Test
    void openRouterDeepseekStillTextOnly() {
        assertFalse(ModelContextWindows.supportsVision("deepseek/deepseek-chat"));
    }

    // --- supportsVision: case insensitivity ---

    @Test
    void caseInsensitiveLookup() {
        assertTrue(ModelContextWindows.supportsVision("Claude-Sonnet-4"));
        assertTrue(ModelContextWindows.supportsVision("GPT-4O"));
    }

    // --- supportsVision: edge cases ---

    @Test
    void nullModelReturnsFalse() {
        assertFalse(ModelContextWindows.supportsVision(null));
    }

    @Test
    void emptyModelReturnsFalse() {
        assertFalse(ModelContextWindows.supportsVision(""));
    }

    @Test
    void blankModelReturnsFalse() {
        assertFalse(ModelContextWindows.supportsVision("   "));
    }

    @Test
    void unknownModelReturnsFalse() {
        assertFalse(ModelContextWindows.supportsVision("some-random-model"));
    }

    // --- getContextWindow ---

    @Test
    void knownModelReturnsCorrectContextWindow() {
        assertEquals(200_000, ModelContextWindows.getContextWindow("claude-sonnet-4"));
    }

    @Test
    void deepseekV4FlashFreeUsesMillionTokenContext() {
        assertEquals(1_000_000, ModelContextWindows.getContextWindow("opencode/deepseek-v4-flash-free"));
        assertEquals(384_000, ModelContextWindows.getMaxOutputTokens("opencode/deepseek-v4-flash-free"));
    }

    @Test
    void unknownModelReturnsDefaultContextWindow() {
        assertEquals(ModelContextWindows.DEFAULT_CONTEXT_WINDOW,
                ModelContextWindows.getContextWindow("unknown-model"));
    }

    // --- getMaxOutputTokens ---

    @Test
    void knownModelReturnsCorrectMaxOutput() {
        assertEquals(16_000, ModelContextWindows.getMaxOutputTokens("claude-sonnet-4"));
    }

    @Test
    void unknownModelReturnsDefaultMaxOutput() {
        assertEquals(ModelContextWindows.DEFAULT_MAX_OUTPUT_TOKENS,
                ModelContextWindows.getMaxOutputTokens("unknown-model"));
    }
}
