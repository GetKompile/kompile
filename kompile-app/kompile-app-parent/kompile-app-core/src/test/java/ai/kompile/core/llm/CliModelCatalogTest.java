/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.core.llm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the DYNAMIC model catalog reads real models.dev metadata from disk and that
 * {@link ModelContextWindows} consults it ahead of its static fallback table.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class CliModelCatalogTest {

    private static final String PROP = "kompile.cli.modelCatalogPaths";

    @TempDir Path tempDir;
    private String previousCatalogPaths;

    @BeforeEach
    void isolateCatalog() {
        previousCatalogPaths = System.getProperty(PROP);
        System.setProperty(PROP, tempDir.resolve("absent.json").toString());
        CliModelCatalog.invalidateCacheForTest();
    }

    @AfterEach
    void cleanup() {
        if (previousCatalogPaths == null) System.clearProperty(PROP);
        else System.setProperty(PROP, previousCatalogPaths);
        CliModelCatalog.invalidateCacheForTest();
    }

    private void useCatalog(String json) throws Exception {
        Path file = tempDir.resolve("models.json");
        Files.writeString(file, json);
        System.setProperty(PROP, file.toString());
        CliModelCatalog.invalidateCacheForTest();
    }

    private void useAdversarialCatalog(boolean includeOpenAi) throws Exception {
        String openai = includeOpenAi ? """
                , "openai": {"models": {
                  "gpt-6-astra": {"limit": {"context": 1050000, "output": 128000}},
                  "catalog-only-model": {"limit": {"context": 64000, "output": 4000}}
                }}
                """ : "";
        useCatalog("""
                {
                  "llmgateway": {"models": {
                    "gpt-6-astra": {"limit": {"context": 1050000, "output": 1050000}},
                    "catalog-only-model": {"limit": {"context": 900000, "output": 900000}}
                  }},
                  "nano-gpt": {"models": {
                    "openai/gpt-6-astra": {"limit": {"context": 900000, "output": 900000}}
                  }}
                  %s
                }
                """.formatted(openai));
    }

    @Test
    void qualifiedMissNeverFallsBackToBareOrSlashContainingAlias() throws Exception {
        useAdversarialCatalog(false);

        assertEquals("llmgateway", CliModelCatalog.lookup("gpt-6-astra").orElseThrow().providerId(),
                "unqualified callers retain their legacy first-catalog match");
        assertTrue(CliModelCatalog.lookup("openai", "gpt-6-astra").isEmpty());
        assertTrue(CliModelCatalog.lookup("openai/gpt-6-astra").isEmpty());
        assertTrue(CliModelCatalog.lookup("openai-codex/gpt-6-astra").isEmpty());
        assertTrue(CliModelCatalog.lookup("unknown/gpt-6-astra").isEmpty());
        assertTrue(CliModelCatalog.lookup(null, "gpt-6-astra").isEmpty());
        assertTrue(CliModelCatalog.lookup("openai", " ").isEmpty());
        assertEquals("nano-gpt", CliModelCatalog.lookup("nano-gpt", "openai/gpt-6-astra")
                .orElseThrow().providerId());
    }

    @Test
    void scopedKeysDisambiguateQualifiedIdsFromLiteralSlashes() throws Exception {
        useAdversarialCatalog(true);

        CliModelCatalog.ModelSpec openai = CliModelCatalog.lookup(" OpenAI ", " GPT-6-ASTRA ").orElseThrow();
        assertEquals("openai", openai.providerId());
        assertEquals("gpt-6-astra", openai.id());
        assertEquals(128_000, openai.maxOutputTokens());
        assertEquals(openai, CliModelCatalog.lookup("openai/gpt-6-astra").orElseThrow());

        CliModelCatalog.ModelSpec nano = CliModelCatalog.lookup("nano-gpt", "openai/gpt-6-astra").orElseThrow();
        assertEquals("nano-gpt", nano.providerId());
        assertEquals("openai/gpt-6-astra", nano.id());
        assertEquals(900_000, nano.maxOutputTokens());
        assertEquals(nano, CliModelCatalog.lookup("nano-gpt/openai/gpt-6-astra").orElseThrow());
        assertTrue(CliModelCatalog.lookup("openai", "nano-gpt/openai/gpt-6-astra").isEmpty());
    }

    @Test
    void providerAwareFallbackDoesNotReenterUnqualifiedCatalog() throws Exception {
        useAdversarialCatalog(false);

        for (String provider : new String[]{"openai", "openai-codex", "codex"}) {
            assertEquals(1_050_000, ModelContextWindows.getContextWindow(provider, "gpt-6-astra"));
            assertEquals(128_000, ModelContextWindows.getMaxOutputTokens(provider, "gpt-6-astra"));
            assertTrue(ModelContextWindows.isKnown(provider, "gpt-6-astra"));
            assertFalse(ModelContextWindows.isKnown(provider, "catalog-only-model"));
            assertEquals(ModelContextWindows.DEFAULT_CONTEXT_WINDOW,
                    ModelContextWindows.getContextWindow(provider, "catalog-only-model"));
            assertEquals(ModelContextWindows.DEFAULT_MAX_OUTPUT_TOKENS,
                    ModelContextWindows.getMaxOutputTokens(provider, "catalog-only-model"));
        }
        assertEquals(128_000, ModelContextWindows.getMaxOutputTokens("openai-codex", "gpt-5.6-sol"),
                "static longest-prefix/versioned fallback is still available");
        assertEquals(1_050_000, ModelContextWindows.getMaxOutputTokens("gpt-6-astra"),
                "bare-id dynamic lookup is preserved for legacy callers");
    }

    @Test
    void separateProviderAndModelKeysCannotCollideAtASlashBoundary() throws Exception {
        useCatalog("""
                {
                  "proxy": {"models": {
                    "vendor/model": {"limit": {"context": 64000, "output": 4000}}
                  }},
                  "proxy/vendor": {"models": {
                    "model": {"limit": {"context": 32000, "output": 2000}}
                  }}
                }
                """);

        assertEquals(4_000, CliModelCatalog.lookup("proxy", "vendor/model").orElseThrow().maxOutputTokens());
        assertEquals(2_000, CliModelCatalog.lookup("proxy/vendor", "model").orElseThrow().maxOutputTokens());
    }

    @Test
    void claudeAndGeminiAliasesUseOnlyTheirKnownUpstreams() throws Exception {
        useCatalog("""
                {
                  "gateway": {"models": {
                    "provider-only-model": {"limit": {"context": 900000, "output": 900000}}
                  }},
                  "anthropic": {"models": {
                    "provider-only-model": {"limit": {"context": 200000, "output": 8000}}
                  }},
                  "google": {"models": {
                    "provider-only-model": {"limit": {"context": 1000000, "output": 64000}}
                  }}
                }
                """);

        assertEquals(200_000, ModelContextWindows.getContextWindow(" Claude ", "provider-only-model"));
        assertEquals(8_000, ModelContextWindows.getMaxOutputTokens("claude", "provider-only-model"));
        assertEquals(1_000_000, ModelContextWindows.getContextWindow("GEMINI", "provider-only-model"));
        assertEquals(64_000, ModelContextWindows.getMaxOutputTokens("gemini", "provider-only-model"));
        assertFalse(ModelContextWindows.isKnown("custom-google", "provider-only-model"));
        assertFalse(ModelContextWindows.isKnown("openai-codex", null));
        assertEquals(ModelContextWindows.DEFAULT_CONTEXT_WINDOW,
                ModelContextWindows.getContextWindow("openai-codex", " "));
        assertEquals(ModelContextWindows.DEFAULT_MAX_OUTPUT_TOKENS,
                ModelContextWindows.getMaxOutputTokens("openai-codex", null));
    }

    @Test
    void nativeAliasesConsultOnlyActualProviderMetadata() throws Exception {
        useAdversarialCatalog(true);

        for (String provider : new String[]{"openai", "openai-codex", "codex"}) {
            assertEquals(128_000, ModelContextWindows.getMaxOutputTokens(provider, "gpt-6-astra"));
            assertTrue(ModelContextWindows.isKnown(provider, "catalog-only-model"));
            assertEquals(64_000, ModelContextWindows.getContextWindow(provider, "catalog-only-model"));
            assertEquals(4_000, ModelContextWindows.getMaxOutputTokens(provider, "catalog-only-model"));
        }
        assertFalse(ModelContextWindows.isKnown("custom-openai", "catalog-only-model"));
        assertEquals(ModelContextWindows.DEFAULT_MAX_OUTPUT_TOKENS,
                ModelContextWindows.getMaxOutputTokens("custom-openai", "catalog-only-model"));
    }

    @Test
    void freeAliasReconciliationCannotBorrowAnotherProvidersGeometry() throws Exception {
        useCatalog("""
                {
                  "llmgateway": {"models": {
                    "base": {"limit": {"context": 900000, "output": 900000}},
                    "base-free": {"limit": {"context": 900000, "output": 900000}},
                    "route/base": {"limit": {"context": 800000, "output": 800000}},
                    "route/base-free": {"limit": {"context": 800000, "output": 800000}}
                  }},
                  "route": {"models": {
                    "base": {"limit": {"context": 200000, "output": 40000}},
                    "base-free": {"limit": {"context": 40000, "output": 8000}}
                  }},
                  "without-base": {"models": {
                    "base-free": {"limit": {"context": 32000, "output": 4000}}
                  }}
                }
                """);

        CliModelCatalog.ModelSpec alias = CliModelCatalog.lookup("route", "base-free").orElseThrow();
        assertEquals("route", alias.providerId());
        assertEquals(200_000, alias.contextWindow());
        assertEquals(40_000, alias.maxOutputTokens());
        assertEquals(4_000, CliModelCatalog.lookup("without-base", "base-free").orElseThrow().maxOutputTokens());
        assertEquals("llmgateway", CliModelCatalog.lookup("base-free").orElseThrow().providerId());
        assertEquals(900_000, CliModelCatalog.lookup("base-free").orElseThrow().maxOutputTokens());
    }

    @Test
    void cacheInvalidationClearsScopedEntries() throws Exception {
        useAdversarialCatalog(true);
        assertTrue(CliModelCatalog.lookup("openai", "gpt-6-astra").isPresent());
        useAdversarialCatalog(false);
        assertTrue(CliModelCatalog.lookup("openai", "gpt-6-astra").isEmpty());
    }

    private Path writeCatalog(Path dir) throws Exception {
        // models.dev shape: { provider: { models: { id: { limit:{context,output}, cost, attachment, ... } } } }
        String json = """
            {
              "opencode": {
                "models": {
                  "deepseek-v4-flash": {
                    "limit": {"context": 999999, "output": 384000},
                    "cost": {"input": 0.14, "output": 0.28},
                    "attachment": false, "tool_call": true, "status": "active"
                  },
                  "deepseek-v4-flash-free": {
                    "limit": {"context": 200000, "output": 128000},
                    "cost": {"input": 0, "output": 0},
                    "attachment": false, "tool_call": true, "status": "active"
                  },
                  "kimi-k2.6": {
                    "limit": {"context": 262000, "output": 66000},
                    "cost": {"input": 0.5, "output": 1.5},
                    "attachment": true, "status": "active"
                  },
                  "no-limit-model": {
                    "cost": {"input": 0, "output": 0}, "attachment": false
                  }
                }
              },
              "anthropic": {
                "models": {
                  "claude-haiku-4-5": {
                    "limit": {"context": 200000, "output": 8192},
                    "cost": {"input": 1, "output": 5}, "attachment": true
                  }
                }
              }
            }
            """;
        Path f = dir.resolve("models.json");
        Files.writeString(f, json);
        return f;
    }

    @Test
    void readsRealMetadataFromCatalog(@TempDir Path dir) throws Exception {
        Path f = writeCatalog(dir);
        System.setProperty(PROP, f.toString());
        CliModelCatalog.invalidateCacheForTest();

        // Context window + max output come straight from limit.{context,output}.
        assertEquals(999999, CliModelCatalog.contextWindow("deepseek-v4-flash").orElseThrow());
        assertEquals(384000, CliModelCatalog.maxOutputTokens("deepseek-v4-flash").orElseThrow());

        // Provider-prefixed ids resolve to the same spec.
        assertEquals(999999, CliModelCatalog.contextWindow("opencode/deepseek-v4-flash").orElseThrow());

        // Free aliases keep free routing but inherit the canonical sibling's larger model limits.
        assertEquals(999999, CliModelCatalog.contextWindow("opencode/deepseek-v4-flash-free").orElseThrow());
        assertEquals(384000, CliModelCatalog.maxOutputTokens("opencode/deepseek-v4-flash-free").orElseThrow());
        assertTrue(CliModelCatalog.isFree("opencode/deepseek-v4-flash-free").orElseThrow());

        // cost 0/0 → free; non-zero cost → not free.
        assertFalse(CliModelCatalog.isFree("deepseek-v4-flash").orElseThrow());
        assertFalse(CliModelCatalog.isFree("kimi-k2.6").orElseThrow());

        // attachment → vision.
        assertTrue(CliModelCatalog.supportsVision("kimi-k2.6").orElseThrow());
        assertFalse(CliModelCatalog.supportsVision("deepseek-v4-flash").orElseThrow());

        // Models with no usable limit metadata are skipped (so the fallback can handle them).
        assertTrue(CliModelCatalog.lookup("no-limit-model").isEmpty());

        // Unknown models resolve to empty.
        assertTrue(CliModelCatalog.lookup("totally-made-up-model").isEmpty());

        // Provider enumeration returns the catalog's models (for dynamic chain building).
        assertTrue(CliModelCatalog.modelsForProvider("opencode").contains("deepseek-v4-flash"));
        assertTrue(CliModelCatalog.modelsForProvider("opencode").contains("kimi-k2.6"));
    }

    @Test
    void modelContextWindowsPrefersDynamicCatalogOverStaticTable(@TempDir Path dir) throws Exception {
        Path f = writeCatalog(dir);
        System.setProperty(PROP, f.toString());
        CliModelCatalog.invalidateCacheForTest();

        // The static fallback table would NOT have 999999 for deepseek-v4-flash (it hardcodes
        // 1_000_000). Getting 999999 proves the dynamic catalog wins.
        assertEquals(999999, ModelContextWindows.getContextWindow("deepseek-v4-flash"),
                "ModelContextWindows must consult the dynamic catalog before the static table");
        assertEquals(384000, ModelContextWindows.getMaxOutputTokens("deepseek-v4-flash"));
        assertEquals(999999, ModelContextWindows.getContextWindow("opencode/deepseek-v4-flash-free"));
        assertEquals(384000, ModelContextWindows.getMaxOutputTokens("opencode/deepseek-v4-flash-free"));
        assertTrue(ModelContextWindows.supportsVision("kimi-k2.6"));
    }

    @Test
    void fallsBackToStaticTableWhenNoCatalogPresent(@TempDir Path dir) {
        // Point at a non-existent catalog file → catalog empty → static fallback used.
        System.setProperty(PROP, dir.resolve("does-not-exist.json").toString());
        CliModelCatalog.invalidateCacheForTest();

        assertEquals(0, CliModelCatalog.size());
        // Static fallback still resolves a known model (claude-sonnet-4 → 200_000 in the table).
        assertEquals(200_000, ModelContextWindows.getContextWindow("claude-sonnet-4"));
        // Unknown model → default.
        assertEquals(ModelContextWindows.DEFAULT_CONTEXT_WINDOW,
                ModelContextWindows.getContextWindow("some-unknown-xyz-model"));
    }
}
