package ai.kompile.kvcache.service;

import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ContentHashPrefixIndex")
class ContentHashPrefixIndexTest {

    private ContentHashPrefixIndex createIndex(int blockSize, int maxEntries) {
        return new ContentHashPrefixIndex(blockSize, maxEntries);
    }

    @Nested @DisplayName("Initialization")
    class Initialization {
        @Test void startsEmpty() {
            var index = createIndex(4, 100);
            assertEquals(0, index.size());
            var stats = index.getStats();
            assertEquals(0, stats.get("indexSize"));
            assertEquals(100, stats.get("maxEntries"));
            assertEquals(0L, stats.get("totalLookups"));
        }
    }

    @Nested @DisplayName("Block indexing")
    class BlockIndexing {
        @Test void indexSingleBlock() {
            var index = createIndex(4, 100);
            index.onBlockFilled(1, new int[]{10, 20, 30, 40});
            assertEquals(1, index.size());
        }

        @Test void indexMultipleBlocksSameContent() {
            var index = createIndex(4, 100);
            int[] tokens = {10, 20, 30, 40};
            index.onBlockFilled(1, tokens);
            index.onBlockFilled(2, tokens);
            // Same hash, so still 1 entry in hashToBlocks but 2 blocks listed
            assertEquals(1, index.size());
        }

        @Test void indexDifferentBlocks() {
            var index = createIndex(4, 100);
            index.onBlockFilled(1, new int[]{10, 20, 30, 40});
            index.onBlockFilled(2, new int[]{50, 60, 70, 80});
            assertEquals(2, index.size());
        }
    }

    @Nested @DisplayName("Block free")
    class BlockFree {
        @Test void freeRemovesEntry() {
            var index = createIndex(4, 100);
            index.onBlockFilled(1, new int[]{10, 20, 30, 40});
            assertEquals(1, index.size());

            index.onBlockFreed(1);
            assertEquals(0, index.size());
        }

        @Test void freeNonexistentBlockNoOp() {
            var index = createIndex(4, 100);
            index.onBlockFreed(999); // no exception
            assertEquals(0, index.size());
        }

        @Test void freeOneOfDuplicates() {
            var index = createIndex(4, 100);
            int[] tokens = {10, 20, 30, 40};
            index.onBlockFilled(1, tokens);
            index.onBlockFilled(2, tokens);

            index.onBlockFreed(1);
            // Entry still exists because block 2 has same hash
            assertEquals(1, index.size());
        }
    }

    @Nested @DisplayName("Prefix matching")
    class PrefixMatching {
        @Test void matchSingleBlock() {
            var index = createIndex(4, 100);
            index.onBlockFilled(1, new int[]{10, 20, 30, 40});

            var result = index.findCachedPrefix(new int[]{10, 20, 30, 40, 50, 60});
            assertTrue(result.hasMatch());
            assertEquals(4, result.matchedTokens());
            assertEquals(1, result.matchedBlockIds().length);
            assertEquals(1, result.matchedBlockIds()[0]);
            assertArrayEquals(new int[]{50, 60}, result.remainingTokens());
        }

        @Test void matchMultipleContiguousBlocks() {
            var index = createIndex(4, 100);
            index.onBlockFilled(1, new int[]{10, 20, 30, 40});
            index.onBlockFilled(2, new int[]{50, 60, 70, 80});

            var result = index.findCachedPrefix(new int[]{10, 20, 30, 40, 50, 60, 70, 80, 90});
            assertTrue(result.hasMatch());
            assertEquals(8, result.matchedTokens());
            assertEquals(2, result.matchedBlockIds().length);
            assertArrayEquals(new int[]{90}, result.remainingTokens());
        }

        @Test void noMatchReturnsFalse() {
            var index = createIndex(4, 100);
            index.onBlockFilled(1, new int[]{10, 20, 30, 40});

            var result = index.findCachedPrefix(new int[]{99, 88, 77, 66});
            assertFalse(result.hasMatch());
            assertEquals(0, result.matchedTokens());
        }

        @Test void tooShortInputReturnsFalse() {
            var index = createIndex(4, 100);
            index.onBlockFilled(1, new int[]{10, 20, 30, 40});

            var result = index.findCachedPrefix(new int[]{10, 20});
            assertFalse(result.hasMatch());
        }

        @Test void nullInputReturnsFalse() {
            var index = createIndex(4, 100);
            var result = index.findCachedPrefix(null);
            assertFalse(result.hasMatch());
        }

        @Test void breakOnGap() {
            var index = createIndex(4, 100);
            index.onBlockFilled(1, new int[]{10, 20, 30, 40});
            // block 2 is missing (gap)
            index.onBlockFilled(3, new int[]{90, 100, 110, 120});

            // Input has block1 matching, then block2 doesn't match, so prefix stops at 4
            var result = index.findCachedPrefix(new int[]{10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120});
            assertEquals(4, result.matchedTokens()); // only first block matches
        }
    }

    @Nested @DisplayName("Eviction")
    class Eviction {
        @Test void evictsWhenMaxEntriesReached() {
            var index = createIndex(4, 2);
            index.onBlockFilled(1, new int[]{1, 2, 3, 4});
            index.onBlockFilled(2, new int[]{5, 6, 7, 8});
            assertEquals(2, index.size());

            // Adding a third should evict the first
            index.onBlockFilled(3, new int[]{9, 10, 11, 12});
            assertEquals(2, index.size());
        }
    }

    @Nested @DisplayName("Statistics")
    class Statistics {
        @Test void tracksLookupsAndHits() {
            var index = createIndex(4, 100);
            index.onBlockFilled(1, new int[]{10, 20, 30, 40});

            index.findCachedPrefix(new int[]{10, 20, 30, 40}); // hit
            index.findCachedPrefix(new int[]{99, 88, 77, 66}); // miss
            index.findCachedPrefix(new int[]{10, 20, 30, 40, 50}); // hit

            var stats = index.getStats();
            assertEquals(3L, stats.get("totalLookups"));
            assertEquals(2L, stats.get("totalHits"));
            assertEquals(2.0 / 3.0, (double) stats.get("hitRate"), 0.01);
        }

        @Test void tracksBlocksIndexed() {
            var index = createIndex(4, 100);
            index.onBlockFilled(1, new int[]{1, 2, 3, 4});
            index.onBlockFilled(2, new int[]{5, 6, 7, 8});

            var stats = index.getStats();
            assertEquals(2L, stats.get("totalBlocksIndexed"));
        }
    }
}
