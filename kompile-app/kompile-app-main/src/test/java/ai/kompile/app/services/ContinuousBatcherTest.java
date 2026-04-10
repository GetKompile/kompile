package ai.kompile.app.services;

import ai.kompile.app.config.ModelSchedulerConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ContinuousBatcher")
class ContinuousBatcherTest {

    @TempDir Path tempDir;

    private ModelSchedulerConfigService createConfigService() {
        return new ModelSchedulerConfigService(tempDir.toString());
    }

    private ContinuousBatcher createBatcher() {
        return new ContinuousBatcher(createConfigService());
    }

    private ContinuousBatcher createBatcher(ModelSchedulerConfigService configService) {
        return new ContinuousBatcher(configService);
    }

    @Nested @DisplayName("Initialization")
    class Initialization {
        @Test void startsWithDefaults() {
            var batcher = createBatcher();
            var status = batcher.getStatus();
            assertNotNull(status);
            assertTrue((Boolean) status.get("enabled"));
            assertEquals(0, status.get("activeSlots"));
            assertEquals(0, status.get("waitingRequests"));
            assertFalse((Boolean) status.get("running"));
        }
    }

    @Nested @DisplayName("Submit and decode")
    class SubmitAndDecode {
        @Test void submitWhenDisabledFails() throws Exception {
            var configService = createConfigService();
            configService.saveConfiguration(ModelSchedulerConfig.builder()
                    .continuousBatchingEnabled(false).build());

            var batcher = createBatcher(configService);
            var future = batcher.submitDecode(new int[]{1, 2, 3}, 10);
            assertTrue(future.isCompletedExceptionally());
        }

        @Test void submitCreatesSlot() throws Exception {
            var batcher = createBatcher();
            batcher.setDecodeStepHandler(slots -> {
                Map<String, ContinuousBatcher.DecodeResult> results = new HashMap<>();
                for (var slot : slots) {
                    results.put(slot.requestId, new ContinuousBatcher.DecodeResult(42, "x", true));
                }
                return results;
            });

            var future = batcher.submitDecode(new int[]{1, 2, 3}, 5);
            String result = future.get(5, TimeUnit.SECONDS);
            assertEquals("x", result);

            var status = batcher.getStatus();
            assertEquals(1L, status.get("totalRequestsCompleted"));

            batcher.shutdown();
        }

        @Test void multipleSlots() throws Exception {
            var batcher = createBatcher();
            int[] counter = {0};

            batcher.setDecodeStepHandler(slots -> {
                Map<String, ContinuousBatcher.DecodeResult> results = new HashMap<>();
                for (var slot : slots) {
                    counter[0]++;
                    // Finish after 2 steps
                    boolean done = slot.currentPos >= slot.promptTokenIds.length + 2;
                    results.put(slot.requestId,
                            new ContinuousBatcher.DecodeResult(42, "t", done));
                }
                return results;
            });

            var f1 = batcher.submitDecode(new int[]{1}, 3);
            var f2 = batcher.submitDecode(new int[]{2}, 3);

            String r1 = f1.get(5, TimeUnit.SECONDS);
            String r2 = f2.get(5, TimeUnit.SECONDS);

            assertNotNull(r1);
            assertNotNull(r2);
            assertTrue(r1.contains("t"));
            assertTrue(r2.contains("t"));

            batcher.shutdown();
        }
    }

    @Nested @DisplayName("Retire slot")
    class RetireSlot {
        @Test void retireCompletesTheFuture() {
            var batcher = createBatcher();
            // Set no-op handler so decode loop doesn't auto-complete
            batcher.setDecodeStepHandler(slots -> new HashMap<>());

            var future = batcher.submitDecode(new int[]{1}, 10);
            var status = batcher.getStatus();
            assertEquals(1, status.get("activeSlots"));

            // Manually find the requestId from status
            @SuppressWarnings("unchecked")
            var slots = (List<Map<String, Object>>) status.get("slots");
            String requestId = (String) slots.get(0).get("requestId");

            batcher.retireSlot(requestId);
            assertTrue(future.isDone());

            batcher.shutdown();
        }
    }

    @Nested @DisplayName("Capacity limit")
    class CapacityLimit {
        @Test void excessSlotsGoToWaitingQueue() throws Exception {
            var configService = createConfigService();
            configService.saveConfiguration(ModelSchedulerConfig.builder()
                    .continuousBatchingEnabled(true)
                    .maxConcurrentDecodes(2)
                    .build());

            var batcher = createBatcher(configService);
            batcher.setDecodeStepHandler(slots -> new HashMap<>());

            batcher.submitDecode(new int[]{1}, 10);
            batcher.submitDecode(new int[]{2}, 10);
            batcher.submitDecode(new int[]{3}, 10); // should go to waiting

            var status = batcher.getStatus();
            assertEquals(2, status.get("activeSlots"));
            assertEquals(1, status.get("waitingRequests"));

            batcher.shutdown();
        }
    }

    @Nested @DisplayName("Shutdown")
    class Shutdown {
        @Test void shutdownCancelsEverything() {
            var batcher = createBatcher();
            batcher.setDecodeStepHandler(slots -> new HashMap<>());

            var f1 = batcher.submitDecode(new int[]{1}, 10);
            var f2 = batcher.submitDecode(new int[]{2}, 10);

            batcher.shutdown();
            assertTrue(f1.isDone() || f1.isCancelled());
            assertTrue(f2.isDone() || f2.isCancelled());
        }
    }
}
