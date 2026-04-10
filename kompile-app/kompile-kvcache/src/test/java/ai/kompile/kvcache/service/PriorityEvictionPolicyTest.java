package ai.kompile.kvcache.service;

import ai.kompile.kvcache.model.BlockPriority;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PriorityEvictionPolicy")
class PriorityEvictionPolicyTest {

    @Nested @DisplayName("BlockPriority")
    class BlockPriorityTests {
        @Test void tierConstants() {
            assertEquals(90, BlockPriority.SYSTEM_PROMPT);
            assertEquals(70, BlockPriority.CACHED_PREFIX);
            assertEquals(50, BlockPriority.USER_CONTEXT);
            assertEquals(10, BlockPriority.EVICTABLE);
        }

        @Test void ordering() {
            var low = new BlockPriority(10, 1000L);
            var high = new BlockPriority(90, 1000L);
            // Lower priority first for eviction
            assertTrue(low.compareTo(high) < 0);
        }

        @Test void samePriorityOlderFirst() {
            var older = new BlockPriority(50, 1000L);
            var newer = new BlockPriority(50, 2000L);
            assertTrue(older.compareTo(newer) < 0);
        }
    }

    @Nested @DisplayName("Policy initialization")
    class Init {
        @Test void startsEmpty() {
            var policy = new PriorityEvictionPolicy(BlockPriority.USER_CONTEXT);
            assertEquals(0, policy.size());
            assertTrue(policy.getEvictionOrder(5).isEmpty());
        }
    }

    @Nested @DisplayName("Set and get priority")
    class SetPriority {
        @Test void registerBlockAndSize() {
            var policy = new PriorityEvictionPolicy(BlockPriority.USER_CONTEXT);
            policy.registerBlock(1);
            assertEquals(1, policy.size());
        }

        @Test void setPriorityAndRetrieve() {
            var policy = new PriorityEvictionPolicy(BlockPriority.USER_CONTEXT);
            policy.registerBlock(1);
            policy.setPriority(1, BlockPriority.SYSTEM_PROMPT);

            assertEquals(1, policy.size());
            var bp = policy.getBlockPriority(1);
            assertEquals(BlockPriority.SYSTEM_PROMPT, bp.priority());
        }

        @Test void touchBlock() {
            var policy = new PriorityEvictionPolicy(BlockPriority.USER_CONTEXT);
            policy.registerBlock(1);
            long beforeTouch = policy.getBlockPriority(1).lastAccessedMs();

            try { Thread.sleep(5); } catch (InterruptedException ignored) {}
            policy.touchBlock(1);
            long afterTouch = policy.getBlockPriority(1).lastAccessedMs();
            assertTrue(afterTouch >= beforeTouch);
            assertEquals(1, policy.size());
        }

        @Test void touchNonExistentIsNoOp() {
            // touchBlock uses computeIfPresent, so non-existent is a no-op
            var policy = new PriorityEvictionPolicy(BlockPriority.USER_CONTEXT);
            policy.touchBlock(99);
            assertEquals(0, policy.size());
        }

        @Test void removeBlock() {
            var policy = new PriorityEvictionPolicy(BlockPriority.USER_CONTEXT);
            policy.registerBlock(1);
            policy.registerBlock(2);

            policy.removeBlock(1);
            assertEquals(1, policy.size());
        }
    }

    @Nested @DisplayName("Eviction order")
    class EvictionOrder {
        @Test void evictsLowestPriorityFirst() {
            var policy = new PriorityEvictionPolicy(BlockPriority.USER_CONTEXT);
            policy.setPriority(1, BlockPriority.SYSTEM_PROMPT);   // 90 - keep
            policy.setPriority(2, BlockPriority.EVICTABLE);        // 10 - evict first
            policy.setPriority(3, BlockPriority.CACHED_PREFIX);    // 70 - keep
            policy.setPriority(4, BlockPriority.USER_CONTEXT);     // 50 - evict second

            List<Integer> evictionOrder = policy.getEvictionOrder(2);
            assertEquals(2, evictionOrder.size());
            assertEquals(2, evictionOrder.get(0)); // EVICTABLE (priority 10)
            assertEquals(4, evictionOrder.get(1)); // USER_CONTEXT (priority 50)
        }

        @Test void requestMoreThanAvailable() {
            var policy = new PriorityEvictionPolicy(BlockPriority.USER_CONTEXT);
            policy.setPriority(1, BlockPriority.EVICTABLE);

            List<Integer> evictionOrder = policy.getEvictionOrder(5);
            assertEquals(1, evictionOrder.size());
        }

        @Test void requestZero() {
            var policy = new PriorityEvictionPolicy(BlockPriority.USER_CONTEXT);
            policy.setPriority(1, BlockPriority.EVICTABLE);

            assertTrue(policy.getEvictionOrder(0).isEmpty());
        }
    }

    @Nested @DisplayName("Tier counts")
    class TierCounts {
        @Test void countsCorrectly() {
            var policy = new PriorityEvictionPolicy(BlockPriority.USER_CONTEXT);
            policy.setPriority(1, BlockPriority.SYSTEM_PROMPT);
            policy.setPriority(2, BlockPriority.SYSTEM_PROMPT);
            policy.setPriority(3, BlockPriority.EVICTABLE);
            policy.setPriority(4, BlockPriority.USER_CONTEXT);

            Map<String, Long> counts = policy.getTierCounts();
            assertEquals(2L, counts.get("systemPrompt"));
            assertEquals(1L, counts.get("evictable"));
            assertEquals(1L, counts.get("userContext"));
        }
    }
}
