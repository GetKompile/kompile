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
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CompressingToolCallbackProvider")
class CompressingToolCallbackProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static ToolCallback stub(String name, String responseJson) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder()
                        .name(name)
                        .description("test")
                        .inputSchema("{}")
                        .build();
            }
            @Override public String call(String input) { return responseJson; }
            @Override public String call(String input, ToolContext context) { return responseJson; }
        };
    }

    private static ToolCallbackProvider providerOf(ToolCallback... callbacks) {
        return () -> callbacks;
    }

    @Nested @DisplayName("Compression interception")
    class CompressionInterception {
        @Test void runsResultThroughRegistry() {
            McpOptimizationConfig cfg = McpOptimizationConfig.defaults();
            cfg.setCompressionThresholdChars(1);
            ToolResponseCompressor replacer = new ToolResponseCompressor() {
                @Override public Set<String> supportedToolNames() { return Set.of("foo_tool"); }
                @Override public Object compress(String name, Object r, McpOptimizationConfig c) {
                    return java.util.Map.of("compressed", true);
                }
            };
            var registry = new ToolResponseCompressorRegistry(
                    java.util.List.of(replacer), () -> cfg, objectMapper);

            String original = "{\"original\":\"" + "x".repeat(200) + "\"}";
            var provider = new CompressingToolCallbackProvider(
                    providerOf(stub("foo_tool", original)), registry, objectMapper);

            ToolCallback[] wrapped = provider.getToolCallbacks();
            assertEquals(1, wrapped.length);
            String result = wrapped[0].call("{}");
            assertTrue(result.contains("compressed"), "expected compressed key in " + result);
            assertFalse(result.contains("original"));
        }

        @Test void nonJsonResponsesPassThroughUnchanged() {
            ToolResponseCompressor breaker = new ToolResponseCompressor() {
                @Override public Set<String> supportedToolNames() { return Set.of("txt_tool"); }
                @Override public Object compress(String name, Object r, McpOptimizationConfig c) {
                    fail("should not run for non-JSON output");
                    return null;
                }
            };
            var registry = new ToolResponseCompressorRegistry(
                    java.util.List.of(breaker),
                    McpOptimizationConfigProvider.ofDefaults(),
                    objectMapper);

            var provider = new CompressingToolCallbackProvider(
                    providerOf(stub("txt_tool", "just plain text, not json")),
                    registry, objectMapper);

            assertEquals("just plain text, not json", provider.getToolCallbacks()[0].call("{}"));
        }

        @Test void emptyDelegateReturnsEmpty() {
            var registry = new ToolResponseCompressorRegistry(
                    java.util.List.of(),
                    McpOptimizationConfigProvider.ofDefaults(), objectMapper);
            var provider = new CompressingToolCallbackProvider(providerOf(), registry, objectMapper);
            assertEquals(0, provider.getToolCallbacks().length);
        }
    }

    @Nested @DisplayName("Allow-set filter")
    class AllowSet {
        @Test void filtersOutDisallowedTools() {
            var registry = new ToolResponseCompressorRegistry(
                    java.util.List.of(),
                    McpOptimizationConfigProvider.ofDefaults(), objectMapper);

            var provider = new CompressingToolCallbackProvider(
                    providerOf(stub("keep_me", "{}"), stub("drop_me", "{}")),
                    registry, objectMapper,
                    Set.of("keep_me"));

            ToolCallback[] callbacks = provider.getToolCallbacks();
            assertEquals(1, callbacks.length);
            assertEquals("keep_me", callbacks[0].getToolDefinition().name());
        }

        @Test void nullAllowSetMeansNoFilter() {
            var registry = new ToolResponseCompressorRegistry(
                    java.util.List.of(),
                    McpOptimizationConfigProvider.ofDefaults(), objectMapper);
            var provider = new CompressingToolCallbackProvider(
                    providerOf(stub("a", "{}"), stub("b", "{}")),
                    registry, objectMapper, null);

            assertEquals(2, provider.getToolCallbacks().length);
        }

        @Test void emptyAllowSetDropsEverything() {
            var registry = new ToolResponseCompressorRegistry(
                    java.util.List.of(),
                    McpOptimizationConfigProvider.ofDefaults(), objectMapper);
            var provider = new CompressingToolCallbackProvider(
                    providerOf(stub("a", "{}"), stub("b", "{}")),
                    registry, objectMapper, Set.of());

            assertEquals(0, provider.getToolCallbacks().length);
        }
    }

    @Nested @DisplayName("Delegation")
    class Delegation {
        @Test void delegateReturningNullIsForwarded() {
            var registry = new ToolResponseCompressorRegistry(
                    java.util.List.of(),
                    McpOptimizationConfigProvider.ofDefaults(), objectMapper);
            ToolCallbackProvider nullProvider = () -> null;
            var provider = new CompressingToolCallbackProvider(nullProvider, registry, objectMapper);
            assertNull(provider.getToolCallbacks());
        }

        @Test void multipleCallbacksWrappedAcrossIterations() {
            var registry = new ToolResponseCompressorRegistry(
                    java.util.List.of(),
                    McpOptimizationConfigProvider.ofDefaults(), objectMapper);
            var provider = new CompressingToolCallbackProvider(
                    providerOf(stub("a", "{}"), stub("b", "{}")),
                    registry, objectMapper);

            ToolCallback[] first = provider.getToolCallbacks();
            ToolCallback[] second = provider.getToolCallbacks();
            assertEquals(2, first.length);
            assertEquals(2, second.length);
            // Names are stable across calls.
            assertEquals(Arrays.stream(first).map(cb -> cb.getToolDefinition().name()).toList(),
                    Arrays.stream(second).map(cb -> cb.getToolDefinition().name()).toList());
        }
    }
}
