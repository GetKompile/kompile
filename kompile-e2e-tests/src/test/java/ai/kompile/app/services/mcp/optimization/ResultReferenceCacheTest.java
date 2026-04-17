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
import ai.kompile.core.mcp.optimization.ResultReferenceCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ResultReferenceCache")
class ResultReferenceCacheTest {

    private ResultReferenceCache cache;

    @BeforeEach
    void setup() {
        cache = new ResultReferenceCache(McpOptimizationConfigProvider.ofDefaults());
        cache.start();
    }

    @AfterEach
    void teardown() {
        cache.stop();
    }

    @Nested @DisplayName("store and get")
    class StoreAndGet {
        @Test void roundTripReturnsOriginalPayload() {
            Map<String, Object> payload = Map.of("hello", "world");
            String id = cache.store(payload);

            assertNotNull(id);
            Optional<Object> fetched = cache.get(id);
            assertTrue(fetched.isPresent());
            assertEquals(payload, fetched.get());
        }

        @Test void unknownIdReturnsEmpty() {
            assertTrue(cache.get("nope").isEmpty());
            assertTrue(cache.get(null).isEmpty());
        }

        @Test void storedPayloadIsIsolatedFromCallerMap() {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("retained", true);
            String id = cache.store(source);
            // We don't deep-copy — the reference identity is preserved.
            // This test documents the current contract so callers know.
            assertSame(source, cache.get(id).orElseThrow());
        }
    }

    @Nested @DisplayName("getSlice")
    class GetSlice {
        @Test void sliceOverList() {
            String id = cache.store(List.of("a", "b", "c", "d", "e"));
            Object slice = cache.getSlice(id, null, 1, 2).orElseThrow();
            Map<?, ?> map = (Map<?, ?>) slice;
            assertEquals(1, map.get("offset"));
            assertEquals(2, map.get("limit"));
            assertEquals(5, map.get("total"));
            assertEquals(2, map.get("returned"));
            assertEquals(List.of("b", "c"), map.get("items"));
        }

        @Test void sliceByMapKeyThenList() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("documents", List.of("doc0", "doc1", "doc2"));
            String id = cache.store(payload);

            Object slice = cache.getSlice(id, "documents", 0, 2).orElseThrow();
            Map<?, ?> map = (Map<?, ?>) slice;
            assertEquals(List.of("doc0", "doc1"), map.get("items"));
        }

        @Test void sliceReturnsScalarWhenValueIsNotList() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "ok");
            String id = cache.store(payload);

            Object value = cache.getSlice(id, "status", 0, 0).orElseThrow();
            assertEquals("ok", value);
        }

        @Test void missingKeyReturnsEmpty() {
            String id = cache.store(Map.of("a", 1));
            assertTrue(cache.getSlice(id, "missing", 0, 0).isEmpty());
        }

        @Test void limitZeroReturnsAll() {
            String id = cache.store(List.of(1, 2, 3, 4));
            Map<?, ?> slice = (Map<?, ?>) cache.getSlice(id, null, 0, 0).orElseThrow();
            assertEquals(4, slice.get("returned"));
        }
    }

    @Nested @DisplayName("filesystem undo cache")
    class FilesystemUndo {
        @Test void storeAndRetrievePreviousContent() {
            String token = cache.storeFilesystemUndo("/tmp/foo.txt", "original body");
            Map<String, Object> payload = cache.getFilesystemUndo(token).orElseThrow();
            assertEquals("/tmp/foo.txt", payload.get("filePath"));
            assertEquals("original body", payload.get("previousContent"));
        }

        @Test void invalidationRemovesEntry() {
            String token = cache.storeFilesystemUndo("/tmp/foo.txt", "bytes");
            cache.invalidateFilesystemUndo(token);
            assertTrue(cache.getFilesystemUndo(token).isEmpty());
        }

        @Test void nullTokenIsSafe() {
            assertTrue(cache.getFilesystemUndo(null).isEmpty());
            cache.invalidateFilesystemUndo(null);
        }

        @Test void undoCacheIsSeparateFromResultCache() {
            String undo = cache.storeFilesystemUndo("/tmp/x", "y");
            assertTrue(cache.get(undo).isEmpty());
            String resultId = cache.store(Map.of("foo", "bar"));
            assertTrue(cache.getFilesystemUndo(resultId).isEmpty());
        }
    }

    @Nested @DisplayName("LRU eviction")
    class LruEviction {
        @Test void enforcesMaxEntries() {
            McpOptimizationConfig small = McpOptimizationConfig.defaults();
            small.setResultCacheMaxEntries(2);
            ResultReferenceCache bounded = new ResultReferenceCache(() -> small);
            bounded.start();
            try {
                String a = bounded.store("a");
                String b = bounded.store("b");
                String c = bounded.store("c");
                // At least one of the earlier entries must have been evicted.
                int live = (bounded.get(a).isPresent() ? 1 : 0)
                        + (bounded.get(b).isPresent() ? 1 : 0)
                        + (bounded.get(c).isPresent() ? 1 : 0);
                assertTrue(live <= 2, "eviction cap not honored: " + live);
                assertTrue(bounded.get(c).isPresent(), "most recent entry should survive");
            } finally {
                bounded.stop();
            }
        }
    }
}
