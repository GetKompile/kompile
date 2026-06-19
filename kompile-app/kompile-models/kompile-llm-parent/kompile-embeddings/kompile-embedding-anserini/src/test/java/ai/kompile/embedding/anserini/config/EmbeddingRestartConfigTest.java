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

package ai.kompile.embedding.anserini.config;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Self-contained unit tests for the embedding restart-governor config contract: defaults,
 * null-safety, threshold clamping, and JSON round-trip / partial-file robustness.
 */
class EmbeddingRestartConfigTest {

    private final ObjectMapper mapper = JsonUtils.standardMapper();

    @Test
    void defaultsAreEnabledWithThresholdThree() {
        EmbeddingRestartConfig cfg = EmbeddingRestartConfig.defaults();
        assertTrue(cfg.isAutoRestartEnabledOrDefault());
        assertEquals(3, cfg.nativeCrashThresholdOrDefault());
    }

    @Test
    void nullFieldsFallBackToDefaults() {
        EmbeddingRestartConfig cfg = new EmbeddingRestartConfig(null, null);
        assertTrue(cfg.isAutoRestartEnabledOrDefault());
        assertEquals(3, cfg.nativeCrashThresholdOrDefault());
    }

    @Test
    void thresholdBelowOneIsClamped() {
        assertEquals(3, new EmbeddingRestartConfig(true, 0).nativeCrashThresholdOrDefault());
        assertEquals(3, new EmbeddingRestartConfig(true, -7).nativeCrashThresholdOrDefault());
        assertEquals(5, new EmbeddingRestartConfig(true, 5).nativeCrashThresholdOrDefault());
    }

    @Test
    void explicitDisableIsHonored() {
        EmbeddingRestartConfig cfg = EmbeddingRestartConfig.builder()
                .autoRestartEnabled(false)
                .nativeCrashThreshold(7)
                .build();
        assertFalse(cfg.isAutoRestartEnabledOrDefault());
        assertEquals(7, cfg.nativeCrashThresholdOrDefault());
    }

    @Test
    void jsonRoundTripPreservesValues() throws Exception {
        EmbeddingRestartConfig original = EmbeddingRestartConfig.builder()
                .autoRestartEnabled(false)
                .nativeCrashThreshold(4)
                .build();
        String json = mapper.writeValueAsString(original);
        EmbeddingRestartConfig restored = mapper.readValue(json, EmbeddingRestartConfig.class);
        assertEquals(original, restored);
        assertFalse(restored.isAutoRestartEnabledOrDefault());
        assertEquals(4, restored.nativeCrashThresholdOrDefault());
    }

    @Test
    void partialJsonMissingThresholdUsesDefaultThreshold() throws Exception {
        EmbeddingRestartConfig cfg =
                mapper.readValue("{\"autoRestartEnabled\": false}", EmbeddingRestartConfig.class);
        assertFalse(cfg.isAutoRestartEnabledOrDefault());
        assertEquals(3, cfg.nativeCrashThresholdOrDefault());
    }

    @Test
    void partialJsonMissingToggleStaysEnabled() throws Exception {
        // Critical: a config file that only sets the threshold must NOT silently disable restarts
        // (this is why the fields are boxed + read through *OrDefault rather than @Builder.Default).
        EmbeddingRestartConfig cfg =
                mapper.readValue("{\"nativeCrashThreshold\": 5}", EmbeddingRestartConfig.class);
        assertTrue(cfg.isAutoRestartEnabledOrDefault());
        assertEquals(5, cfg.nativeCrashThresholdOrDefault());
    }
}
