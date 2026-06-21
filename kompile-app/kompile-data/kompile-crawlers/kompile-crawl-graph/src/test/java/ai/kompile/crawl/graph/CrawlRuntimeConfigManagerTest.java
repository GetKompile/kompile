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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        assertEquals(2, defaults.get("crawlGraphExtractionRemoteParallelism"));
        assertEquals(64, defaults.get("crawlGraphExtractionMaxItemsPerBatch"));

        // And they round-trip through an update (validated/clamped, persisted to the shared file).
        Map<String, Object> effective = mgr.updateCrawlRuntimeConfig(Map.of(
                "crawlGraphExtractionRemoteParallelism", 8,
                "crawlGraphExtractionMaxItemsPerBatch", 256));
        assertEquals(8, effective.get("crawlGraphExtractionRemoteParallelism"));
        assertEquals(256, effective.get("crawlGraphExtractionMaxItemsPerBatch"));
    }
}
