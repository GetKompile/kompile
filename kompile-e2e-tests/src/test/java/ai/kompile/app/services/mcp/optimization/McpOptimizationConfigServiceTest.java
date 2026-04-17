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

package ai.kompile.app.services.mcp.optimization;

import ai.kompile.core.mcp.optimization.McpOptimizationConfig;
import ai.kompile.core.mcp.optimization.McpOptimizationConfig.MetaToolMode;
import ai.kompile.core.mcp.optimization.McpOptimizationConfigChangedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("McpOptimizationConfigService")
class McpOptimizationConfigServiceTest {

    @TempDir Path tempDir;

    private McpOptimizationConfigService newService(ApplicationEventPublisher publisher) {
        McpOptimizationConfigService svc = new McpOptimizationConfigService(tempDir.toString(), publisher);
        svc.loadAndApplyConfig();
        return svc;
    }

    private McpOptimizationConfigService newService() {
        return newService(event -> {});
    }

    @Nested @DisplayName("Defaults")
    class Defaults {
        @Test void onFreshStartupAllDefaultsPopulated() {
            var cfg = newService().getConfiguration();
            assertTrue(cfg.getEnabled());
            assertEquals(2000, cfg.getRagMaxContentChars());
            assertEquals(3, cfg.getRagMaxDocs());
            assertEquals(200, cfg.getKnowledgeGraphTruncateChars());
            assertEquals(4000, cfg.getCompressionThresholdChars());
            assertEquals(1000, cfg.getResultCacheMaxEntries());
            assertEquals(MetaToolMode.HYBRID, cfg.getMetaToolMode());
            assertNotNull(cfg.getAlwaysExposedTools());
            assertNotNull(cfg.getToolOverrides());
        }

        @Test void firstLoadWritesDefaultsToDisk() {
            var svc = newService();
            assertTrue(Files.exists(Path.of(svc.getConfigFilePath())));
        }
    }

    @Nested @DisplayName("Update and persistence")
    class UpdateAndPersistence {
        @Test void updateAppliesOnlyNonNullFields() {
            var svc = newService();
            var partial = McpOptimizationConfig.builder()
                    .ragMaxContentChars(500)
                    .metaToolMode(MetaToolMode.DIRECT)
                    .build();
            var merged = svc.updateConfiguration(partial);
            assertEquals(500, merged.getRagMaxContentChars());
            assertEquals(MetaToolMode.DIRECT, merged.getMetaToolMode());
            // Untouched fields retain defaults.
            assertEquals(3, merged.getRagMaxDocs());
            assertTrue(merged.getEnabled());
        }

        @Test void reloadReadsPersistedValues() {
            var first = newService();
            first.updateConfiguration(McpOptimizationConfig.builder()
                    .ragMaxContentChars(777)
                    .alwaysExposedTools(List.of("rag_query"))
                    .build());

            var second = newService();
            assertEquals(777, second.getConfiguration().getRagMaxContentChars());
            assertEquals(List.of("rag_query"), second.getConfiguration().getAlwaysExposedTools());
        }

        @Test void resetReturnsToBuiltInDefaults() {
            var svc = newService();
            svc.updateConfiguration(McpOptimizationConfig.builder().ragMaxDocs(1).build());
            assertEquals(1, svc.getConfiguration().getRagMaxDocs());

            svc.resetConfiguration();
            assertEquals(3, svc.getConfiguration().getRagMaxDocs());
        }

        @Test void updateWithNullIsNoop() {
            var svc = newService();
            var before = svc.getConfiguration();
            var after = svc.updateConfiguration(null);
            assertSame(before, after);
        }
    }

    @Nested @DisplayName("Validation")
    class Validation {
        @Test void rejectsNegativeRagMaxContentChars() {
            var svc = newService();
            assertThrows(IllegalArgumentException.class, () ->
                    svc.updateConfiguration(McpOptimizationConfig.builder().ragMaxContentChars(-1).build()));
        }

        @Test void rejectsZeroRagMaxDocs() {
            var svc = newService();
            assertThrows(IllegalArgumentException.class, () ->
                    svc.updateConfiguration(McpOptimizationConfig.builder().ragMaxDocs(0).build()));
        }

        @Test void rejectsZeroResultCacheTtl() {
            var svc = newService();
            assertThrows(IllegalArgumentException.class, () ->
                    svc.updateConfiguration(McpOptimizationConfig.builder().resultCacheTtlSeconds(0L).build()));
        }
    }

    @Nested @DisplayName("Change events")
    class ChangeEvents {
        @Test void publishesEventOnUpdate() {
            AtomicInteger count = new AtomicInteger();
            ApplicationEventPublisher publisher = event -> {
                if (event instanceof McpOptimizationConfigChangedEvent) {
                    count.incrementAndGet();
                }
            };
            var svc = newService(publisher);
            // loadAndApplyConfig already published once; reset counter
            int baseline = count.get();
            svc.updateConfiguration(McpOptimizationConfig.builder().ragMaxDocs(5).build());
            assertTrue(count.get() > baseline);
        }
    }

    @Nested @DisplayName("Corrupted file recovery")
    class Corrupted {
        @Test void unparseableFileFallsBackToDefaults() throws Exception {
            var cfgDir = tempDir.resolve("config");
            Files.createDirectories(cfgDir);
            Files.writeString(cfgDir.resolve("mcp-optimization-config.json"), "{{{ not json");

            var svc = newService();
            assertEquals(2000, svc.getConfiguration().getRagMaxContentChars());
        }
    }
}
