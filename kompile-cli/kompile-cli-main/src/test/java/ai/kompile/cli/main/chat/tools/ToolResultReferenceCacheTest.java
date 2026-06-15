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

package ai.kompile.cli.main.chat.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ToolResultReferenceCache}.
 * <p>
 * Tests store/get, shouldCache, getSlice, TTL expiry, storeAndSummarize,
 * size, eviction, and edge cases (null, expired, past-end offset).
 */
class ToolResultReferenceCacheTest {

    private ToolResultReferenceCache cache;

    @BeforeEach
    void setUp() {
        // Use default threshold (8000) and TTL (15 minutes)
        cache = new ToolResultReferenceCache();
    }

    // ===================================================================
    // store() and get()
    // ===================================================================

    @Nested
    class StoreAndGet {

        @Test
        void storeAndRetrieve() {
            String id = cache.store("Bash", "Hello World output", Map.of("cmd", "echo hello"));

            assertNotNull(id);
            assertFalse(id.isEmpty());
            assertEquals(8, id.length(), "Handle should be 8-char UUID prefix");

            Optional<ToolResultReferenceCache.CacheEntry> entry = cache.get(id);
            assertTrue(entry.isPresent());
            assertEquals("Bash", entry.get().toolName());
            assertEquals("Hello World output", entry.get().output());
            assertEquals("echo hello", entry.get().metadata().get("cmd"));
        }

        @Test
        void multipleStores_differentIds() {
            String id1 = cache.store("Read", "output1", Map.of());
            String id2 = cache.store("Read", "output2", Map.of());

            assertNotEquals(id1, id2);
        }

        @Test
        void getNonexistentId_returnsEmpty() {
            Optional<ToolResultReferenceCache.CacheEntry> entry = cache.get("nonexist");
            assertTrue(entry.isEmpty());
        }

        @Test
        void getNull_returnsEmpty() {
            assertTrue(cache.get(null).isEmpty());
        }
    }

    // ===================================================================
    // shouldCache()
    // ===================================================================

    @Nested
    class ShouldCache {

        @Test
        void largeOutput_shouldCache() {
            String large = "X".repeat(8000);
            assertTrue(cache.shouldCache(large));
        }

        @Test
        void smallOutput_shouldNotCache() {
            assertFalse(cache.shouldCache("small output"));
        }

        @Test
        void exactlyAtThreshold_shouldCache() {
            String exact = "X".repeat(ToolResultReferenceCache.DEFAULT_CACHE_THRESHOLD_CHARS);
            assertTrue(cache.shouldCache(exact));
        }

        @Test
        void belowThreshold_shouldNotCache() {
            String below = "X".repeat(ToolResultReferenceCache.DEFAULT_CACHE_THRESHOLD_CHARS - 1);
            assertFalse(cache.shouldCache(below));
        }

        @Test
        void nullOutput_shouldNotCache() {
            assertFalse(cache.shouldCache(null));
        }

        @Test
        void customThreshold_respected() {
            ToolResultReferenceCache custom = new ToolResultReferenceCache(100, 60_000);
            assertTrue(custom.shouldCache("X".repeat(100)));
            assertFalse(custom.shouldCache("X".repeat(99)));
        }
    }

    // ===================================================================
    // getSlice()
    // ===================================================================

    @Nested
    class GetSlice {

        @Test
        void sliceFromBeginning() {
            String content = "line0\nline1\nline2\nline3\nline4\n";
            String id = cache.store("Read", content, Map.of());

            ToolResult result = cache.getSlice(id, 0, 3);

            assertFalse(result.isError());
            assertTrue(result.getOutput().contains("line0"));
            assertTrue(result.getOutput().contains("line1"));
            assertTrue(result.getOutput().contains("line2"));
            assertTrue(result.getMetadata().containsKey("totalLines"));
            assertTrue((boolean) result.getMetadata().get("truncated"));
        }

        @Test
        void sliceFromMiddle() {
            String content = "line0\nline1\nline2\nline3\nline4";
            String id = cache.store("Read", content, Map.of());

            ToolResult result = cache.getSlice(id, 2, 2);

            assertFalse(result.isError());
            assertTrue(result.getOutput().contains("line2"));
            assertTrue(result.getOutput().contains("line3"));
        }

        @Test
        void slicePastEnd_returnsMessage() {
            String content = "line0\nline1";
            String id = cache.store("Read", content, Map.of());

            ToolResult result = cache.getSlice(id, 100, 10);

            assertFalse(result.isError());
            assertTrue(result.getOutput().contains("past end"));
        }

        @Test
        void sliceNonexistentId_returnsError() {
            ToolResult result = cache.getSlice("no-such-id", 0, 10);
            assertTrue(result.isError());
            assertTrue(result.getOutput().contains("not found"));
        }

        @Test
        void sliceNegativeOffset_treatedAsZero() {
            String content = "line0\nline1\nline2";
            String id = cache.store("Bash", content, Map.of());

            ToolResult result = cache.getSlice(id, -5, 2);

            assertFalse(result.isError());
            assertTrue(result.getOutput().contains("line0"));
        }

        @Test
        void sliceZeroLimit_defaultsTo200() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 300; i++) {
                sb.append("line_").append(i).append("\n");
            }
            String id = cache.store("Bash", sb.toString(), Map.of());

            ToolResult result = cache.getSlice(id, 0, 0);

            assertFalse(result.isError());
            int returnedLines = (int) result.getMetadata().get("linesReturned");
            assertEquals(200, returnedLines);
        }

        @Test
        void sliceMetadata_includesToolName() {
            String id = cache.store("Grep", "match1\nmatch2", Map.of());
            ToolResult result = cache.getSlice(id, 0, 10);

            assertEquals("Grep", result.getMetadata().get("toolName"));
        }

        @Test
        void sliceNotTruncated_whenAllLinesReturned() {
            String content = "a\nb\nc";
            String id = cache.store("Read", content, Map.of());

            ToolResult result = cache.getSlice(id, 0, 100);

            assertFalse((boolean) result.getMetadata().get("truncated"));
        }
    }

    // ===================================================================
    // storeAndSummarize()
    // ===================================================================

    @Nested
    class StoreAndSummarize {

        @Test
        void summarizeIncludesHandle() {
            String output = "X".repeat(10000);
            ToolResult result = cache.storeAndSummarize("Bash", "command output", output, Map.of());

            assertFalse(result.isError());
            assertTrue(result.getOutput().contains("ref:"));
            assertTrue(result.getOutput().contains("fetch_result"));
            assertTrue(result.getMetadata().containsKey("result_id"));
            assertTrue((boolean) result.getMetadata().get("cached"));
        }

        @Test
        void summarizeIncludesPreview() {
            String output = "First line of content\n" + "X".repeat(9000);
            ToolResult result = cache.storeAndSummarize("Read", "file contents", output, Map.of());

            assertTrue(result.getOutput().contains("Preview:"));
            assertTrue(result.getOutput().contains("First line"));
        }

        @Test
        void summarizeIncludesCharAndLineCount() {
            String output = "line1\nline2\nline3\n";
            ToolResult result = cache.storeAndSummarize("Read", "file", output, Map.of());

            assertTrue(result.getOutput().contains("chars"));
            assertTrue(result.getOutput().contains("lines"));
            assertEquals(output.length(), result.getMetadata().get("totalChars"));
        }

        @Test
        void summarizedContent_canBeRetrieved() {
            String output = "full content here";
            ToolResult result = cache.storeAndSummarize("Bash", "title", output, Map.of());

            String handle = (String) result.getMetadata().get("result_id");
            Optional<ToolResultReferenceCache.CacheEntry> entry = cache.get(handle);

            assertTrue(entry.isPresent());
            assertEquals("full content here", entry.get().output());
        }

        @Test
        void summarize_withExistingMetadata() {
            ToolResult result = cache.storeAndSummarize("Read", "file",
                    "content", Map.of("original_key", "value"));

            assertEquals("value", result.getMetadata().get("original_key"));
            assertTrue(result.getMetadata().containsKey("result_id"));
        }
    }

    // ===================================================================
    // size()
    // ===================================================================

    @Nested
    class Size {

        @Test
        void emptyCache_sizeZero() {
            assertEquals(0, cache.size());
        }

        @Test
        void afterStores_sizeIncrements() {
            cache.store("A", "output1", Map.of());
            cache.store("B", "output2", Map.of());
            cache.store("C", "output3", Map.of());

            assertEquals(3, cache.size());
        }
    }

    // ===================================================================
    // TTL expiry
    // ===================================================================

    @Nested
    class TtlExpiry {

        @Test
        void expiredEntry_returnsEmpty() {
            // Create a cache with 1ms TTL — entry expires almost immediately
            ToolResultReferenceCache shortTtl = new ToolResultReferenceCache(100, 1);
            String id = shortTtl.store("Bash", "output", Map.of());

            // Wait a tiny bit for expiry
            try { Thread.sleep(10); } catch (InterruptedException ignored) {}

            Optional<ToolResultReferenceCache.CacheEntry> entry = shortTtl.get(id);
            assertTrue(entry.isEmpty(), "Expired entry should not be returned");
        }

        @Test
        void expiredEntry_sliceReturnsError() {
            ToolResultReferenceCache shortTtl = new ToolResultReferenceCache(100, 1);
            String id = shortTtl.store("Bash", "output", Map.of());

            try { Thread.sleep(10); } catch (InterruptedException ignored) {}

            ToolResult result = shortTtl.getSlice(id, 0, 10);
            assertTrue(result.isError());
        }
    }

    // ===================================================================
    // Constants
    // ===================================================================

    @Nested
    class Constants {

        @Test
        void defaultThreshold_is8000() {
            assertEquals(8000, ToolResultReferenceCache.DEFAULT_CACHE_THRESHOLD_CHARS);
        }
    }

    // ===================================================================
    // CacheEntry record
    // ===================================================================

    @Nested
    class CacheEntryRecord {

        @Test
        void allFieldsAccessible() {
            ToolResultReferenceCache.CacheEntry entry =
                    new ToolResultReferenceCache.CacheEntry(
                            "Read", "file content", Map.of("path", "/tmp/f"), 999999L);

            assertEquals("Read", entry.toolName());
            assertEquals("file content", entry.output());
            assertEquals("/tmp/f", entry.metadata().get("path"));
            assertEquals(999999L, entry.expiresAt());
        }
    }
}
