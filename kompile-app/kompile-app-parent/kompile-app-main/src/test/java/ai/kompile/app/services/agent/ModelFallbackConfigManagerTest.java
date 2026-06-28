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

package ai.kompile.app.services.agent;

import ai.kompile.app.services.agent.ModelFallbackConfigManager.ModelFallbackConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the fallback-executor config is configurable PER-CRAWL/PER-PROJECT via the active
 * override (pushed from GraphExtractionConfig), layered on top of the global file default.
 */
class ModelFallbackConfigManagerTest {

    @Test
    void perJobOverrideLayersOverGlobalDefault(@TempDir Path dir) {
        // No config file → global defaults (paid fallback OFF).
        ModelFallbackConfigManager mgr = new ModelFallbackConfigManager(dir.resolve("absent.json"));
        ModelFallbackConfig base = mgr.getConfig();
        assertFalse(base.paidFallbackEnabled, "global default is paid-fallback OFF");
        int defaultTimeout = base.perCallTimeoutSeconds;

        // Push a per-job override (e.g. this project may use a little paid budget, tighter timeout).
        mgr.setActiveExtractionFallbackOverride(true, 7, 90);
        ModelFallbackConfig overridden = mgr.getConfig();
        assertTrue(overridden.paidFallbackEnabled, "per-job override enables paid tier");
        assertEquals(7, overridden.maxPaidCallsPerCrawl);
        assertEquals(90, overridden.perCallTimeoutSeconds);

        // A null field in the override inherits the global default (partial override).
        mgr.setActiveExtractionFallbackOverride(true, null, null);
        ModelFallbackConfig partial = mgr.getConfig();
        assertTrue(partial.paidFallbackEnabled);
        assertEquals(base.maxPaidCallsPerCrawl, partial.maxPaidCallsPerCrawl, "null inherits global");
        assertEquals(defaultTimeout, partial.perCallTimeoutSeconds, "null inherits global");

        // Clearing the override (all null) returns to the pure global default.
        mgr.setActiveExtractionFallbackOverride(null, null, null);
        assertFalse(mgr.getConfig().paidFallbackEnabled, "cleared override → global default again");
    }
}
