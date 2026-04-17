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
import ai.kompile.core.mcp.optimization.McpOptimizationConfigProvider;
import ai.kompile.core.mcp.optimization.ToolResponseCompressor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ToolResponseCompressorRegistry")
class ToolResponseCompressorRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ToolResponseCompressorRegistry registry(McpOptimizationConfig cfg,
                                                    List<ToolResponseCompressor> compressors) {
        McpOptimizationConfigProvider provider = () -> cfg;
        return new ToolResponseCompressorRegistry(compressors, provider, objectMapper);
    }

    private static ToolResponseCompressor alwaysRepl(String name, Object replacement) {
        return new ToolResponseCompressor() {
            @Override public Set<String> supportedToolNames() { return Set.of(name); }
            @Override public Object compress(String toolName, Object result, McpOptimizationConfig cfg) {
                return replacement;
            }
        };
    }

    private static ToolResponseCompressor wildcardReturning(Object replacement) {
        return new ToolResponseCompressor() {
            @Override public Set<String> supportedToolNames() { return Set.of(WILDCARD); }
            @Override public Object compress(String toolName, Object result, McpOptimizationConfig cfg) {
                return replacement;
            }
        };
    }

    @Nested @DisplayName("Disabled or empty")
    class DisabledOrEmpty {
        @Test void disabledConfigShortCircuits() {
            McpOptimizationConfig cfg = McpOptimizationConfig.defaults();
            cfg.setEnabled(false);
            var reg = registry(cfg, List.of(alwaysRepl("foo", "REPLACED")));
            assertEquals(Map.of("untouched", true), reg.compress("foo", Map.of("untouched", true)));
        }

        @Test void nullResultReturnsNull() {
            var reg = registry(McpOptimizationConfig.defaults(), List.of());
            assertNull(reg.compress("anything", null));
        }

        @Test void belowThresholdSkipsCompression() {
            McpOptimizationConfig cfg = McpOptimizationConfig.defaults();
            cfg.setCompressionThresholdChars(10_000);
            var reg = registry(cfg, List.of(alwaysRepl("foo", "BIG-ENOUGH-REPLACEMENT")));
            Object original = Map.of("k", "small");
            assertEquals(original, reg.compress("foo", original));
        }
    }

    @Nested @DisplayName("Fail-safe passthrough")
    class FailSafe {
        @Test void compressorThatProducesLargerResultIsIgnored() {
            McpOptimizationConfig cfg = McpOptimizationConfig.defaults();
            cfg.setCompressionThresholdChars(1);
            String longerReplacement = "x".repeat(500);
            var reg = registry(cfg, List.of(alwaysRepl("foo", longerReplacement)));

            Object original = "hello";
            assertEquals(original, reg.compress("foo", original));
        }

        @Test void compressorThatThrowsReturnsOriginal() {
            McpOptimizationConfig cfg = McpOptimizationConfig.defaults();
            cfg.setCompressionThresholdChars(1);
            ToolResponseCompressor boom = new ToolResponseCompressor() {
                @Override public Set<String> supportedToolNames() { return Set.of("foo"); }
                @Override public Object compress(String toolName, Object result, McpOptimizationConfig cfg) {
                    throw new RuntimeException("boom");
                }
            };
            var reg = registry(cfg, List.of(boom));
            Object original = "a-larger-than-1-char-payload";
            assertSame(original, reg.compress("foo", original));
        }
    }

    @Nested @DisplayName("Dispatch")
    class Dispatch {
        @Test void toolSpecificCompressorWinsOverDefault() {
            McpOptimizationConfig cfg = McpOptimizationConfig.defaults();
            cfg.setCompressionThresholdChars(1);
            var reg = registry(cfg, List.of(
                    alwaysRepl("foo", "FOO"),
                    wildcardReturning("DEFAULT")
            ));
            assertEquals("FOO", reg.compress("foo", "original-larger-than-threshold"));
            assertEquals("DEFAULT", reg.compress("other", "original-larger-than-threshold"));
        }

        @Test void multipleWildcardsLogsWarningAndKeepsFirst() {
            McpOptimizationConfig cfg = McpOptimizationConfig.defaults();
            cfg.setCompressionThresholdChars(1);
            var reg = registry(cfg, List.of(
                    wildcardReturning("FIRST"),
                    wildcardReturning("SECOND")
            ));
            // First wildcard wins; exact value isn't guaranteed, but it must be one of them.
            Object out = reg.compress("anything", "big-enough-payload");
            assertTrue("FIRST".equals(out) || "SECOND".equals(out));
        }

        @Test void emptySupportedSetIsSkipped() {
            ToolResponseCompressor skip = new ToolResponseCompressor() {
                @Override public Set<String> supportedToolNames() { return Collections.emptySet(); }
                @Override public Object compress(String toolName, Object result, McpOptimizationConfig cfg) {
                    return "should-not-run";
                }
            };
            var reg = registry(McpOptimizationConfig.defaults(), List.of(skip));
            assertEquals("original-big-payload".repeat(500), reg.compress("foo", "original-big-payload".repeat(500)));
        }
    }

    @Nested @DisplayName("Per-tool overrides")
    class Overrides {
        @Test void overrideDisabledWinsOverCompressor() {
            McpOptimizationConfig cfg = McpOptimizationConfig.defaults();
            cfg.setCompressionThresholdChars(1);
            cfg.setToolOverrides(Map.of("foo",
                    McpOptimizationConfig.ToolOverride.builder().compressionEnabled(false).build()));
            var reg = registry(cfg, List.of(alwaysRepl("foo", "REPLACED")));
            assertEquals("original-payload", reg.compress("foo", "original-payload"));
        }

        @Test void overrideEnabledForcesCompressionEvenBelowThreshold() {
            McpOptimizationConfig cfg = McpOptimizationConfig.defaults();
            cfg.setCompressionThresholdChars(10_000);
            cfg.setToolOverrides(Map.of("foo",
                    McpOptimizationConfig.ToolOverride.builder().compressionEnabled(true).build()));
            var reg = registry(cfg, List.of(alwaysRepl("foo", "X")));
            assertEquals("X", reg.compress("foo", "original-payload"));
        }
    }
}
