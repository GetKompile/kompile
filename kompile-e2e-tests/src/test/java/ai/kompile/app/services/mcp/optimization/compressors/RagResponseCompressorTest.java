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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RagResponseCompressor")
class RagResponseCompressorTest {

    private ResultReferenceCache cache;
    private RagResponseCompressor compressor;

    @BeforeEach
    void setUp() {
        cache = new ResultReferenceCache(McpOptimizationConfigProvider.ofDefaults());
        cache.start();
        compressor = new RagResponseCompressor(cache);
    }

    @AfterEach
    void tearDown() { cache.stop(); }

    @Test
    void supportsRagQueryOnly() {
        assertEquals(java.util.Set.of("rag_query"), compressor.supportedToolNames());
    }

    @Test
    void nonMapInputReturnedUnchanged() {
        Object input = "not-a-map";
        assertSame(input, compressor.compress("rag_query", input, McpOptimizationConfig.defaults()));
    }

    @Test
    void missingRetrievedDocumentsReturnsMapUnchanged() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("answer", "ok");
        Object out = compressor.compress("rag_query", input, McpOptimizationConfig.defaults());
        assertInstanceOf(Map.class, out);
        assertFalse(((Map<?, ?>) out).containsKey("result_id"));
    }

    @Test
    void preservesUntruncatedShortDocs() {
        McpOptimizationConfig cfg = McpOptimizationConfig.defaults();
        cfg.setRagMaxContentChars(1000);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("retrieved_documents", List.of("short1", "short2"));
        Object out = compressor.compress("rag_query", input, cfg);
        Map<?, ?> outMap = (Map<?, ?>) out;
        assertFalse(outMap.containsKey("result_id"));
        assertEquals(List.of("short1", "short2"), outMap.get("retrieved_documents"));
    }

    @Test
    void truncatesOversizedDocsAndAddsResultId() {
        McpOptimizationConfig cfg = McpOptimizationConfig.defaults();
        cfg.setRagMaxContentChars(50);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("retrieved_documents", List.of("a".repeat(500), "b".repeat(200)));
        Object out = compressor.compress("rag_query", input, cfg);
        Map<?, ?> outMap = (Map<?, ?>) out;

        assertTrue(outMap.containsKey("result_id"), "truncated response must include a result_id handle");
        assertEquals(Boolean.TRUE, outMap.get("truncated"));

        @SuppressWarnings("unchecked")
        List<Object> docs = (List<Object>) outMap.get("retrieved_documents");
        assertEquals(2, docs.size());
        assertTrue(((String) docs.get(0)).length() < 500);
        assertTrue(((String) docs.get(0)).contains("…"));
    }

    @Test
    void alreadyHasResultIdIsLeftAlone() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("result_id", "pre-existing");
        input.put("retrieved_documents", List.of("a".repeat(500)));
        Object out = compressor.compress("rag_query", input, McpOptimizationConfig.defaults());
        assertEquals("pre-existing", ((Map<?, ?>) out).get("result_id"));
    }

    @Test
    void resultIdResolvesToCachedPayload() {
        McpOptimizationConfig cfg = McpOptimizationConfig.defaults();
        cfg.setRagMaxContentChars(20);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("retrieved_documents", List.of("A".repeat(500)));
        Map<?, ?> out = (Map<?, ?>) compressor.compress("rag_query", input, cfg);

        String id = (String) out.get("result_id");
        assertNotNull(id);
        assertTrue(cache.get(id).isPresent());
    }
}
