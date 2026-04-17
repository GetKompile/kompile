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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("KnowledgeGraphResponseCompressor")
class KnowledgeGraphResponseCompressorTest {

    private final KnowledgeGraphResponseCompressor compressor = new KnowledgeGraphResponseCompressor();

    @Test
    void supportsAllEightGraphTools() {
        assertEquals(8, compressor.supportedToolNames().size());
        assertTrue(compressor.supportedToolNames().contains("graph_search_by_entity"));
        assertTrue(compressor.supportedToolNames().contains("graph_get_overview"));
    }

    @Test
    void truncatesTopLevelDescription() {
        McpOptimizationConfig cfg = McpOptimizationConfig.defaults();
        cfg.setKnowledgeGraphTruncateChars(20);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("description", "a".repeat(300));
        input.put("name", "leave-me-alone");

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) compressor.compress("graph_get_source_context", input, cfg);
        assertEquals(20, ((String) out.get("description")).length());
        assertTrue(((String) out.get("description")).endsWith("..."));
        assertEquals("leave-me-alone", out.get("name"));
    }

    @Test
    void truncatesNestedListDescriptions() {
        McpOptimizationConfig cfg = McpOptimizationConfig.defaults();
        cfg.setKnowledgeGraphTruncateChars(15);

        Map<String, Object> first = new LinkedHashMap<>();
        first.put("description", "b".repeat(100));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("description", "short");

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("nodes", List.of(first, second));

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) compressor.compress("graph_search_by_entity", input, cfg);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) out.get("nodes");
        assertEquals(15, ((String) nodes.get(0).get("description")).length());
        assertEquals("short", nodes.get(1).get("description"));
    }

    @Test
    void handlesMultipleTruncatableFieldNames() {
        McpOptimizationConfig cfg = McpOptimizationConfig.defaults();
        cfg.setKnowledgeGraphTruncateChars(10);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("summary", "s".repeat(200));
        input.put("content", "c".repeat(200));
        input.put("text", "t".repeat(200));

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) compressor.compress("graph_get_overview", input, cfg);
        assertEquals(10, ((String) out.get("summary")).length());
        assertEquals(10, ((String) out.get("content")).length());
        assertEquals(10, ((String) out.get("text")).length());
    }

    @Test
    void zeroOrNegativeLimitDisablesTruncation() {
        McpOptimizationConfig cfg = McpOptimizationConfig.defaults();
        cfg.setKnowledgeGraphTruncateChars(0);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("description", "keep this text intact");
        Object out = compressor.compress("graph_search_nodes", input, cfg);
        assertSame(input, out);
    }

    @Test
    void nullInputReturnsNull() {
        assertNull(compressor.compress("graph_search_nodes", null, McpOptimizationConfig.defaults()));
    }
}
