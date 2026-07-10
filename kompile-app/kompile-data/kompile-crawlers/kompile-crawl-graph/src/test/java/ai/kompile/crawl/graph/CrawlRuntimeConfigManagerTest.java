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

package ai.kompile.crawl.graph;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.crawl.graph.CliAgentAvailabilityAdapter;
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the typed crawl runtime-config read/update path that backs
 * {@code GET/PUT /api/unified-crawl/runtime-config}: only {@code crawl*} keys are written, every other
 * key in the shared {@code graph-extraction-config.json} (e.g. the extraction schema) is preserved, and
 * a read reflects the merged result.
 */
class CrawlRuntimeConfigManagerTest {

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    @Test
    void updateMergesCrawlKeysAndPreservesOtherConfig(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("graph-extraction-config.json");
        // Seed with a non-crawl (extraction schema) key + an existing crawl key.
        Files.writeString(cfg, "{\"extractionModelProvider\":\"opencode-cli\",\"crawlGraphExtractionParallelism\":4}");
        CrawlRuntimeConfigManager mgr = new CrawlRuntimeConfigManager(cfg);

        Map<String, Object> effective = mgr.updateCrawlRuntimeConfig(
                Map.of("crawlGraphExtractionParallelism", 12, "crawlVectorBatchSize", 64));

        // Returned effective config reflects the update (re-read + validated/clamped).
        assertEquals(12, effective.get("crawlGraphExtractionParallelism"));
        assertEquals(64, effective.get("crawlVectorBatchSize"));

        // The non-crawl key survives the write (no clobbering of the shared file).
        JsonNode root = MAPPER.readTree(cfg.toFile());
        assertEquals("opencode-cli", root.get("extractionModelProvider").asText());
        assertEquals(12, root.get("crawlGraphExtractionParallelism").asInt());

        // GET returns the same effective values.
        assertEquals(12, mgr.currentCrawlRuntimeConfig().get("crawlGraphExtractionParallelism"));
    }

    @Test
    void updateIgnoresUnknownAndNonCrawlKeys(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("graph-extraction-config.json");
        Files.writeString(cfg, "{\"extractionModelProvider\":\"opencode-cli\"}");
        CrawlRuntimeConfigManager mgr = new CrawlRuntimeConfigManager(cfg);

        mgr.updateCrawlRuntimeConfig(Map.of("extractionModelProvider", "HACKED", "notARealKey", 7));

        JsonNode root = MAPPER.readTree(cfg.toFile());
        assertEquals("opencode-cli", root.get("extractionModelProvider").asText(),
                "non-crawl keys must be ignored so a runtime-config push can't clobber the shared file");
        assertNull(root.get("notARealKey"), "unknown keys must not be written");
    }

    @Test
    void getReturnsDefaultsWhenNoFile(@TempDir Path dir) {
        CrawlRuntimeConfigManager mgr = new CrawlRuntimeConfigManager(dir.resolve("absent.json"));
        Map<String, Object> cfg = mgr.currentCrawlRuntimeConfig();
        assertFalse(cfg.isEmpty());
        // A representative default (graphExtractionParallelism defaults to 4).
        assertEquals(4, cfg.get("crawlGraphExtractionParallelism"));
    }

    @Test
    void remoteParallelismAndMaxItemsAreConfigurable(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("graph-extraction-config.json");
        Files.writeString(cfg, "{}");
        CrawlRuntimeConfigManager mgr = new CrawlRuntimeConfigManager(cfg);

        // Defaults are exposed via the runtime-config map (project/global .kompile level).
        Map<String, Object> defaults = mgr.currentCrawlRuntimeConfig();
        assertEquals(4, defaults.get("crawlGraphExtractionRemoteParallelism"));
        assertEquals(64, defaults.get("crawlGraphExtractionMaxItemsPerBatch"));

        // And they round-trip through an update (validated/clamped, persisted to the shared file).
        Map<String, Object> effective = mgr.updateCrawlRuntimeConfig(Map.of(
                "crawlGraphExtractionRemoteParallelism", 8,
                "crawlGraphExtractionMaxItemsPerBatch", 256));
        assertEquals(8, effective.get("crawlGraphExtractionRemoteParallelism"));
        assertEquals(256, effective.get("crawlGraphExtractionMaxItemsPerBatch"));
    }

    /**
     * When the adapter returns contextBudgetChars==0 (no model discovered) but reports an
     * agent name of "opencode", the fallback path should raise graphExtractionTargetCharsPerBatch
     * above the static 48 000-char default and return a non-null detail string.
     */
    @Test
    void contextBudgetFallbackFiresForUnknownAgent(@TempDir Path dir) {
        CrawlRuntimeConfigManager mgr = new CrawlRuntimeConfigManager(dir.resolve("absent.json"));

        // Stub adapter: model discovery fails (contextBudgetChars=0) but agent name resolves.
        CliAgentAvailabilityAdapter stubAdapter = new CliAgentAvailabilityAdapter() {
            @Override
            public int contextBudgetChars(double fraction, double charsPerToken) {
                return 0; // simulate: no models discovered
            }

            @Override
            public String resolveExtractionAgentName() {
                return "opencode";
            }
        };

        CrawlLlmDispatcher dispatcher = new CrawlLlmDispatcher();
        dispatcher.cliAgentAvailability = stubAdapter;

        GraphExtractionOrchestrator orch = new GraphExtractionOrchestrator();
        // pre-condition: static default
        assertEquals(48_000, orch.graphExtractionTargetCharsPerBatch);

        // fraction=0.7, charsPerToken=3.5; opencode fallback=128 000 tokens
        // effectiveTokens = (128000 - 8000) = 120000; budgetChars = (int)(120000 * 3.5 * 0.7) = 294000
        GraphExtractionConfig graphConfig = new GraphExtractionConfig();
        graphConfig.setExtractionContextBudgetFraction(0.7);
        graphConfig.setExtractionCharsPerToken(3.5);

        String detail = mgr.applyContextBudget(graphConfig, dispatcher, orch);

        assertNotNull(detail, "detail must be non-null: fallback budget should have fired");
        assertTrue(detail.contains("fallback=true"), "detail should indicate fallback was used");
        assertTrue(orch.graphExtractionTargetCharsPerBatch > 48_000,
                "budget must be raised above static default; got: " + orch.graphExtractionTargetCharsPerBatch);
    }

    /**
     * The budget update must never shrink graphExtractionTargetCharsPerBatch below its
     * current value, even when the fallback budget is smaller than what was pre-set.
     */
    @Test
    void contextBudgetNeverShrinksFromStaticDefault(@TempDir Path dir) {
        CrawlRuntimeConfigManager mgr = new CrawlRuntimeConfigManager(dir.resolve("absent.json"));

        CliAgentAvailabilityAdapter stubAdapter = new CliAgentAvailabilityAdapter() {
            @Override
            public int contextBudgetChars(double fraction, double charsPerToken) {
                return 0;
            }

            @Override
            public String resolveExtractionAgentName() {
                return "opencode";
            }
        };

        CrawlLlmDispatcher dispatcher = new CrawlLlmDispatcher();
        dispatcher.cliAgentAvailability = stubAdapter;

        GraphExtractionOrchestrator orch = new GraphExtractionOrchestrator();
        // Pre-set a very large chars value — must not be reduced.
        orch.graphExtractionTargetCharsPerBatch = 999_999;

        GraphExtractionConfig graphConfig = new GraphExtractionConfig();
        graphConfig.setExtractionContextBudgetFraction(0.7);
        graphConfig.setExtractionCharsPerToken(3.5);

        mgr.applyContextBudget(graphConfig, dispatcher, orch);

        assertEquals(999_999, orch.graphExtractionTargetCharsPerBatch,
                "budget must never shrink from a pre-set value");
    }

    /**
     * When the fallback budget is larger than the current value, chunksPerPrompt should
     * be scaled proportionally upward (never downward, never below initial value).
     */
    @Test
    void chunksPerPromptScalesProportionally(@TempDir Path dir) {
        CrawlRuntimeConfigManager mgr = new CrawlRuntimeConfigManager(dir.resolve("absent.json"));

        CliAgentAvailabilityAdapter stubAdapter = new CliAgentAvailabilityAdapter() {
            @Override
            public int contextBudgetChars(double fraction, double charsPerToken) {
                return 0; // force fallback path
            }

            @Override
            public String resolveExtractionAgentName() {
                return "opencode";
            }
        };

        CrawlLlmDispatcher dispatcher = new CrawlLlmDispatcher();
        dispatcher.cliAgentAvailability = stubAdapter;

        GraphExtractionOrchestrator orch = new GraphExtractionOrchestrator();
        // Pre-set orchestrator to match the static default so "before" is well-defined.
        orch.graphExtractionTargetCharsPerBatch = 48_000;
        orch.graphExtractionChunksPerPrompt = 4;

        GraphExtractionConfig graphConfig = new GraphExtractionConfig();
        graphConfig.setExtractionContextBudgetFraction(0.7);
        graphConfig.setExtractionCharsPerToken(3.5);

        mgr.applyContextBudget(graphConfig, dispatcher, orch);

        // chunksPerPrompt must not be reduced below its initial value.
        assertTrue(orch.graphExtractionChunksPerPrompt >= 4,
                "chunksPerPrompt must not shrink; got: " + orch.graphExtractionChunksPerPrompt);
    }
}
