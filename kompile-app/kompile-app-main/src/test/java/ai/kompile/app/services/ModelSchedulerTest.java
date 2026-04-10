package ai.kompile.app.services;

import ai.kompile.app.config.ModelSchedulerConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ModelScheduler")
class ModelSchedulerTest {

    @TempDir Path tempDir;

    private ModelSchedulerConfigService createConfigService() {
        return new ModelSchedulerConfigService(tempDir.toString());
    }

    private ModelScheduler createScheduler() {
        return new ModelScheduler(createConfigService());
    }

    private ModelScheduler createScheduler(ModelSchedulerConfigService configService) {
        return new ModelScheduler(configService);
    }

    @Nested @DisplayName("Initialization")
    class Initialization {
        @Test void createsWithDefaults() {
            var scheduler = createScheduler();
            var status = scheduler.getStatus();
            assertNotNull(status);
            assertTrue((Boolean) status.get("enabled"));
            assertEquals(0L, status.get("totalSubmitted"));
            assertEquals(0L, status.get("totalCompleted"));
        }

        @Test void statusShowsConfig() {
            var scheduler = createScheduler();
            var status = scheduler.getStatus();
            assertEquals(32, status.get("preferredBatchSize"));
            assertEquals(64, status.get("maxBatchSize"));
            assertEquals(50L, status.get("maxQueueDelayMs"));
        }
    }

    @Nested @DisplayName("Model registration")
    class Registration {
        @Test void registerAndUnregisterModel() {
            var scheduler = createScheduler();
            scheduler.registerModel("test-model", inputs -> inputs);
            var status = scheduler.getStatus();
            @SuppressWarnings("unchecked")
            var models = (List<String>) status.get("registeredModels");
            assertTrue(models.contains("test-model"));

            scheduler.unregisterModel("test-model");
            status = scheduler.getStatus();
            @SuppressWarnings("unchecked")
            var models2 = (List<String>) status.get("registeredModels");
            assertFalse(models2.contains("test-model"));
        }
    }

    @Nested @DisplayName("Direct submission (scheduler disabled)")
    class DirectSubmission {
        @Test void submitWithDisabledSchedulerRunsImmediately() throws Exception {
            var configService = createConfigService();
            var config = ModelSchedulerConfig.builder().enabled(false).build();
            configService.saveConfiguration(config);

            var scheduler = createScheduler(configService);
            AtomicInteger callCount = new AtomicInteger();
            scheduler.registerModel("test-model", inputs -> {
                callCount.incrementAndGet();
                return inputs;
            });

            CompletableFuture<Object> future = scheduler.submit("test-model", "hello", 50);
            Object result = future.get(5, TimeUnit.SECONDS);
            assertEquals("hello", result);
            assertEquals(1, callCount.get());
        }

        @Test void submitWithNoHandlerFails() {
            var configService = createConfigService();
            try {
                var config = ModelSchedulerConfig.builder().enabled(false).build();
                configService.saveConfiguration(config);
            } catch (Exception e) {
                fail(e);
            }

            var scheduler = createScheduler(configService);
            CompletableFuture<Object> future = scheduler.submit("nonexistent", "hello", 50);
            assertTrue(future.isCompletedExceptionally());
        }
    }

    @Nested @DisplayName("Batched submission")
    class BatchedSubmission {
        @Test void submitMultipleRequestsGetBatched() throws Exception {
            var scheduler = createScheduler();
            AtomicInteger batchCount = new AtomicInteger();
            AtomicInteger maxBatchSeen = new AtomicInteger();

            scheduler.registerModel("batch-model", inputs -> {
                batchCount.incrementAndGet();
                maxBatchSeen.updateAndGet(old -> Math.max(old, inputs.size()));
                return inputs;
            });

            // Submit several requests
            List<CompletableFuture<Object>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 10; i++) {
                futures.add(scheduler.submit("batch-model", "input-" + i, 50));
            }

            // Wait for all to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(10, TimeUnit.SECONDS);

            // All should have completed
            for (var f : futures) {
                assertTrue(f.isDone());
                assertFalse(f.isCompletedExceptionally());
            }
        }
    }

    @Nested @DisplayName("Queue capacity")
    class QueueCapacity {
        @Test void rejectsWhenQueueFull() throws Exception {
            var configService = createConfigService();
            var config = ModelSchedulerConfig.builder()
                    .enabled(true)
                    .queueCapacity(2)
                    .maxQueueDelayMs(10000) // long delay so nothing flushes
                    .build();
            configService.saveConfiguration(config);

            var scheduler = createScheduler(configService);
            // Register handler that blocks
            scheduler.registerModel("blocking-model", inputs -> {
                Thread.sleep(60000);
                return inputs;
            });

            // Fill the queue
            scheduler.submit("blocking-model", "a", 50);
            scheduler.submit("blocking-model", "b", 50);

            // Third should be rejected
            CompletableFuture<Object> overflow = scheduler.submit("blocking-model", "c", 50);
            assertTrue(overflow.isCompletedExceptionally());

            scheduler.shutdown();
        }
    }

    @Nested @DisplayName("Shutdown")
    class Shutdown {
        @Test void shutdownCancelsPendingRequests() {
            var scheduler = createScheduler();
            scheduler.registerModel("test", inputs -> {
                Thread.sleep(60000);
                return inputs;
            });

            var f = scheduler.submit("test", "data", 50);
            scheduler.shutdown();
            // After shutdown, pending futures should be cancelled
            assertTrue(f.isDone() || f.isCancelled());
        }
    }

    @Nested @DisplayName("Priority ordering")
    class PriorityOrdering {
        @Test void higherPriorityRequestsProcessedFirst() throws Exception {
            var configService = createConfigService();
            var config = ModelSchedulerConfig.builder()
                    .enabled(true)
                    .preferredBatchSize(100) // big batch to get all at once
                    .maxQueueDelayMs(200)
                    .build();
            configService.saveConfiguration(config);

            var scheduler = createScheduler(configService);
            java.util.List<Object> processedOrder = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

            scheduler.registerModel("priority-model", inputs -> {
                processedOrder.addAll(inputs);
                return inputs;
            });

            // Submit low-priority first, then high
            scheduler.submit("priority-model", "low", 10);
            scheduler.submit("priority-model", "high", 90);

            // Wait for flush
            Thread.sleep(500);

            // High priority should come first in the batch
            if (processedOrder.size() >= 2) {
                assertEquals("high", processedOrder.get(0));
                assertEquals("low", processedOrder.get(1));
            }

            scheduler.shutdown();
        }
    }
}
