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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the DYNAMIC model catalog reads real models.dev metadata from disk and that
 * {@link ModelContextWindows} consults it ahead of its static fallback table.
 */
class CliModelCatalogTest {

    private static final String PROP = "kompile.cli.modelCatalogPaths";

    @AfterEach
    void cleanup() {
        System.clearProperty(PROP);
        CliModelCatalog.invalidateCacheForTest();
    }

    private Path writeCatalog(Path dir) throws Exception {
        // models.dev shape: { provider: { models: { id: { limit:{context,output}, cost, attachment, ... } } } }
        String json = """
            {
              "opencode": {
                "models": {
                  "deepseek-v4-flash": {
                    "limit": {"context": 999999, "output": 384000},
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

        // cost 0/0 → free; non-zero cost → not free.
        assertTrue(CliModelCatalog.isFree("deepseek-v4-flash").orElseThrow());
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
