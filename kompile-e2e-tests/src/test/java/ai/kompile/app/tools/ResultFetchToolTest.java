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

package ai.kompile.app.tools;

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

@DisplayName("ResultFetchTool")
class ResultFetchToolTest {

    private ResultReferenceCache cache;
    private ResultFetchTool tool;

    @BeforeEach
    void setUp() {
        cache = new ResultReferenceCache(McpOptimizationConfigProvider.ofDefaults());
        cache.start();
        tool = new ResultFetchTool(cache);
    }

    @AfterEach
    void tearDown() { cache.stop(); }

    @Test
    void fetchesFullPayloadWhenNoSliceRequested() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("answer", 42);
        String id = cache.store(payload);

        Map<String, Object> response = tool.fetchResult(
                new ResultFetchTool.FetchResultInput(id, null, null, null));

        assertEquals(id, response.get("result_id"));
        assertEquals(payload, response.get("payload"));
    }

    @Test
    void slicesListByKeyAndOffset() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documents", List.of("d0", "d1", "d2", "d3"));
        String id = cache.store(payload);

        Map<String, Object> response = tool.fetchResult(
                new ResultFetchTool.FetchResultInput(id, "documents", 1, 2));

        assertEquals(id, response.get("result_id"));
        assertEquals("documents", response.get("key"));
        @SuppressWarnings("unchecked")
        Map<String, Object> sliced = (Map<String, Object>) response.get("payload");
        assertEquals(List.of("d1", "d2"), sliced.get("items"));
        assertEquals(4, sliced.get("total"));
    }

    @Test
    void missingIdReturnsErrorStructure() {
        Map<String, Object> response = tool.fetchResult(
                new ResultFetchTool.FetchResultInput("never-stored", null, null, null));
        assertNotNull(response.get("error"));
    }

    @Test
    void blankIdReturnsError() {
        Map<String, Object> response = tool.fetchResult(
                new ResultFetchTool.FetchResultInput("", null, null, null));
        assertNotNull(response.get("error"));
    }

    @Test
    void nullInputIsGracefullyRejected() {
        Map<String, Object> response = tool.fetchResult(null);
        assertNotNull(response.get("error"));
    }
}
