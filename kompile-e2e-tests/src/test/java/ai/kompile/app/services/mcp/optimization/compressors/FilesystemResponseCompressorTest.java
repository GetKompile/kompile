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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FilesystemResponseCompressor")
class FilesystemResponseCompressorTest {

    private ResultReferenceCache cache;
    private FilesystemResponseCompressor compressor;

    @BeforeEach
    void setUp() {
        cache = new ResultReferenceCache(McpOptimizationConfigProvider.ofDefaults());
        cache.start();
        compressor = new FilesystemResponseCompressor(cache);
    }

    @AfterEach
    void tearDown() { cache.stop(); }

    @Test
    void supportsWriteDeleteRead() {
        assertEquals(Set.of("write_file", "delete_file", "read_file"),
                compressor.supportedToolNames());
    }

    @Test
    void writeFileReplacesPreviousContentWithToken() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("path", "/tmp/a.txt");
        input.put("previousContent", "old body");
        Object out = compressor.compress("write_file", input, McpOptimizationConfig.defaults());
        Map<?, ?> map = (Map<?, ?>) out;
        assertFalse(map.containsKey("previousContent"));
        assertTrue(map.containsKey("undo_token"));
        assertEquals(8, map.get("previousContentLength"));

        String token = (String) map.get("undo_token");
        assertTrue(cache.getFilesystemUndo(token).isPresent());
    }

    @Test
    void writeFileWithoutPreviousContentPassesThrough() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("path", "/tmp/a.txt");
        Object out = compressor.compress("write_file", input, McpOptimizationConfig.defaults());
        Map<?, ?> map = (Map<?, ?>) out;
        assertFalse(map.containsKey("undo_token"));
    }

    @Test
    void readFileOversizedContentIsTruncatedAndCached() {
        McpOptimizationConfig cfg = McpOptimizationConfig.defaults();
        cfg.setCompressionThresholdChars(100);
        String body = "x".repeat(500);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("path", "/tmp/big.txt");
        input.put("content", body);

        Object out = compressor.compress("read_file", input, cfg);
        Map<?, ?> map = (Map<?, ?>) out;
        assertEquals(Boolean.TRUE, map.get("truncated"));
        assertEquals(500, map.get("contentLength"));
        assertTrue(map.containsKey("result_id"));
        assertTrue(((String) map.get("content")).contains("[truncated"));

        String id = (String) map.get("result_id");
        assertTrue(cache.get(id).isPresent());
    }

    @Test
    void readFileSmallContentLeftAsIs() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("path", "/tmp/small.txt");
        input.put("content", "small body");
        Object out = compressor.compress("read_file", input, McpOptimizationConfig.defaults());
        Map<?, ?> map = (Map<?, ?>) out;
        assertEquals("small body", map.get("content"));
        assertFalse(map.containsKey("result_id"));
    }

    @Test
    void nonMapInputReturnsAsIs() {
        Object input = "just a string";
        assertSame(input, compressor.compress("write_file", input, McpOptimizationConfig.defaults()));
    }

    @Test
    void preservesExistingUndoToken() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("undo_token", "pre-existing");
        input.put("previousContent", "old");
        Object out = compressor.compress("write_file", input, McpOptimizationConfig.defaults());
        Map<?, ?> map = (Map<?, ?>) out;
        assertEquals("pre-existing", map.get("undo_token"));
        assertEquals("old", map.get("previousContent"));
    }
}
