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

package ai.kompile.app.services.mcp.optimization.compressors;

import ai.kompile.core.mcp.optimization.McpOptimizationConfig;
import ai.kompile.core.mcp.optimization.McpOptimizationConfigProvider;
import ai.kompile.core.mcp.optimization.ResultReferenceCache;
import ai.kompile.core.mcp.optimization.ToolResponseCompressor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DefaultResponseCompressor")
class DefaultResponseCompressorTest {

    private ResultReferenceCache cache;
    private DefaultResponseCompressor compressor;

    @BeforeEach
    void setUp() {
        cache = new ResultReferenceCache(McpOptimizationConfigProvider.ofDefaults());
        cache.start();
        compressor = new DefaultResponseCompressor(cache);
    }

    @AfterEach
    void tearDown() { cache.stop(); }

    @Test
    void declaresItselfAsDefault() {
        assertEquals(Set.of(ToolResponseCompressor.WILDCARD), compressor.supportedToolNames());
    }

    @Test
    void dropsNullFields() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("keep", "value");
        input.put("drop", null);

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) compressor.compress("any", input, McpOptimizationConfig.defaults());
        assertTrue(out.containsKey("keep"));
        assertFalse(out.containsKey("drop"));
    }

    @Test
    void truncatesOversizedStrings() {
        McpOptimizationConfig cfg = McpOptimizationConfig.defaults();
        cfg.setCompressionThresholdChars(10);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("body", "a".repeat(200));

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) compressor.compress("any", input, cfg);
        String body = (String) out.get("body");
        assertTrue(body.startsWith("aaaaaaaaaa"));
        assertTrue(body.contains("[truncated]"));
    }

    @Test
    void collapsesOversizedLists() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("i", i);
            entries.add(row);
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("rows", entries);

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) compressor.compress("any", input, McpOptimizationConfig.defaults());

        Map<?, ?> envelope = (Map<?, ?>) out.get("rows");
        assertEquals(20, envelope.get("returned"));
        assertEquals(50, envelope.get("total"));
        assertEquals(Boolean.TRUE, envelope.get("truncated"));
        assertTrue(out.containsKey("result_id"));
    }

    @Test
    void smallListIsNotCollapsed() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("rows", List.of(1, 2, 3));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) compressor.compress("any", input, McpOptimizationConfig.defaults());
        assertEquals(List.of(1, 2, 3), out.get("rows"));
        assertFalse(out.containsKey("result_id"));
    }

    @Test
    void nullInputReturnsNull() {
        assertNull(compressor.compress("any", null, McpOptimizationConfig.defaults()));
    }

    @Test
    void resultIdRoundTripsThroughCache() {
        List<Object> big = new ArrayList<>();
        for (int i = 0; i < 30; i++) big.add(i);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("rows", big);

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) compressor.compress("any", input, McpOptimizationConfig.defaults());
        String id = (String) out.get("result_id");
        assertNotNull(id);
        Object cached = cache.get(id).orElseThrow();
        // Cached payload is the *original* untruncated input map.
        assertSame(input, cached);
    }
}
