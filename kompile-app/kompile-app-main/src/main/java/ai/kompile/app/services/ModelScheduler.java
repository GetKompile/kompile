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

package ai.kompile.app.services;

import ai.kompile.app.config.ModelSchedulerConfig;
import jakarta.annotation.PreDestroy;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Request scheduler with dynamic batching for model inference.
 * Groups individual inference requests into batches to maximize GPU throughput.
 *
 * <p>Per-model queues with priority ordering. Flush loops drain up to preferredBatchSize
 * requests, waiting up to maxQueueDelayMs for a full batch before flushing partial.</p>
 */
@Service
public class ModelScheduler {

    private static final Logger log = LoggerFactory.getLogger(ModelScheduler.class);

    private final ModelSchedulerConfigService configService;

    // Per-model request queues
    private final ConcurrentHashMap<String, PriorityBlockingQueue<InferenceRequest<?>>> modelQueues = new ConcurrentHashMap<>();

    // Per-model flush threads
    private final ConcurrentHashMap<String, ScheduledExecutorService> flushExecutors = new ConcurrentHashMap<>();

    // Statistics
    private final AtomicLong totalSubmitted = new AtomicLong();
    private final AtomicLong totalCompleted = new AtomicLong();
    private final AtomicLong totalBatches = new AtomicLong();
    private final AtomicLong totalTimeoutFlushes = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> perModelSubmitted = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> perModelCompleted = new ConcurrentHashMap<>();

    // Registered batch handlers: modelId -> handler that processes a batch of requests
    private final ConcurrentHashMap<String, BatchHandler<?>> batchHandlers = new ConcurrentHashMap<>();

    public ModelScheduler(ModelSchedulerConfigService configService) {
        this.configService = configService;
        log.info("ModelScheduler initialized");
    }

    /**
     * Functional interface for processing a batch of requests.
     */
    @FunctionalInterface
    public interface BatchHandler<T> {
        /**
         * Process a batch of inputs and return results in same order.
         */
        List<Object> processBatch(List<T> inputs) throws Exception;
    }

    /**
     * An inference request with priority ordering.
     */
    public static class InferenceRequest<T> implements Comparable<InferenceRequest<?>> {
        final String modelId;
        final T input;
        final int priority;
        final CompletableFuture<Object> resultFuture;
        final Instant submittedAt;

        public InferenceRequest(String modelId, T input, int priority) {
            this.modelId = modelId;
            this.input = input;
            this.priority = priority;
            this.resultFuture = new CompletableFuture<>();
            this.submittedAt = Instant.now();
        }

        @Override
        public int compareTo(InferenceRequest<?> other) {
            // Higher priority first, then earlier submission
            int cmp = Integer.compare(other.priority, this.priority);
            if (cmp != 0) return cmp;
            return this.submittedAt.compareTo(other.submittedAt);
        }
    }

    /**
     * Register a batch handler for a model. Must be called before submitting requests.
     */
    public <T> void registerModel(String modelId, BatchHandler<T> handler) {
        batchHandlers.put(modelId, handler);
        perModelSubmitted.putIfAbsent(modelId, new AtomicLong());
        perModelCompleted.putIfAbsent(modelId, new AtomicLong());
        log.info("Registered batch handler for model '{}'", modelId);
    }

    /**
     * Unregister a model and stop its flush loop.
     */
    public void unregisterModel(String modelId) {
        batchHandlers.remove(modelId);
        PriorityBlockingQueue<InferenceRequest<?>> queue = modelQueues.remove(modelId);
        if (queue != null) {
            // Cancel pending requests
            for (InferenceRequest<?> req : queue) {
                req.resultFuture.cancel(false);
            }
        }
        ScheduledExecutorService executor = flushExecutors.remove(modelId);
        if (executor != null) {
            executor.shutdownNow();
        }
        log.info("Unregistered model '{}' from scheduler", modelId);
    }

    /**
     * Submit an inference request. Returns a future that completes when the request is processed.
     */
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<Object> submit(String modelId, T input, int priority) {
        ModelSchedulerConfig config = configService.getConfiguration();
        if (!config.isEnabled()) {
            // When scheduler is disabled, execute immediately via handler
            BatchHandler<T> handler = (BatchHandler<T>) batchHandlers.get(modelId);
            if (handler == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("No batch handler registered for model: " + modelId));
            }
            CompletableFuture<Object> future = new CompletableFuture<>();
            try {
                List<Object> results = handler.processBatch(List.of(input));
                future.complete(results.isEmpty() ? null : results.get(0));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
            return future;
        }

        PriorityBlockingQueue<InferenceRequest<?>> queue = modelQueues.computeIfAbsent(modelId,
                k -> new PriorityBlockingQueue<>(config.getQueueCapacity()));

        if (queue.size() >= config.getQueueCapacity()) {
            return CompletableFuture.failedFuture(
                    new RejectedExecutionException("Queue full for model: " + modelId));
        }

        InferenceRequest<T> request = new InferenceRequest<>(modelId, input, priority);
        queue.offer(request);
        totalSubmitted.incrementAndGet();
        perModelSubmitted.computeIfAbsent(modelId, k -> new AtomicLong()).incrementAndGet();

        // Ensure flush loop is running for this model
        ensureFlushLoop(modelId, config);

        return request.resultFuture;
    }

    /**
     * Submit an embedding request (convenience method, priority 50).
     */
    public CompletableFuture<Object> submitEmbedding(String modelId, String text) {
        return submit(modelId, text, 50);
    }

    private void ensureFlushLoop(String modelId, ModelSchedulerConfig config) {
        flushExecutors.computeIfAbsent(modelId, k -> {
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "scheduler-flush-" + modelId);
                t.setDaemon(true);
                return t;
            });
            executor.scheduleWithFixedDelay(
                    () -> flushLoop(modelId),
                    config.getMaxQueueDelayMs(),
                    config.getMaxQueueDelayMs(),
                    TimeUnit.MILLISECONDS);
            log.debug("Started flush loop for model '{}' (delay={}ms)", modelId, config.getMaxQueueDelayMs());
            return executor;
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void flushLoop(String modelId) {
        PriorityBlockingQueue<InferenceRequest<?>> queue = modelQueues.get(modelId);
        if (queue == null || queue.isEmpty()) return;

        ModelSchedulerConfig config = configService.getConfiguration();
        BatchHandler handler = batchHandlers.get(modelId);
        if (handler == null) {
            log.warn("No batch handler for model '{}', skipping flush", modelId);
            return;
        }

        int batchSize = Math.min(config.getPreferredBatchSize(), queue.size());
        batchSize = Math.min(batchSize, config.getMaxBatchSize());
        if (batchSize == 0) return;

        List<InferenceRequest<?>> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);

        if (batch.isEmpty()) return;

        totalBatches.incrementAndGet();
        if (batch.size() < config.getPreferredBatchSize()) {
            totalTimeoutFlushes.incrementAndGet();
        }

        try {
            List<Object> inputs = new ArrayList<>(batch.size());
            for (InferenceRequest<?> req : batch) {
                inputs.add(req.input);
            }

            List<Object> results = handler.processBatch(inputs);

            for (int i = 0; i < batch.size(); i++) {
                Object result = i < results.size() ? results.get(i) : null;
                batch.get(i).resultFuture.complete(result);
                totalCompleted.incrementAndGet();
                perModelCompleted.computeIfAbsent(modelId, k -> new AtomicLong()).incrementAndGet();
            }
        } catch (Exception e) {
            log.error("Batch processing failed for model '{}': {}", modelId, e.getMessage(), e);
            for (InferenceRequest<?> req : batch) {
                req.resultFuture.completeExceptionally(e);
            }
        }
    }

    /**
     * Get scheduler status for monitoring.
     */
    public Map<String, Object> getStatus() {
        ModelSchedulerConfig config = configService.getConfiguration();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", config.isEnabled());
        status.put("preferredBatchSize", config.getPreferredBatchSize());
        status.put("maxBatchSize", config.getMaxBatchSize());
        status.put("maxQueueDelayMs", config.getMaxQueueDelayMs());
        status.put("queueCapacity", config.getQueueCapacity());
        status.put("continuousBatchingEnabled", config.isContinuousBatchingEnabled());
        status.put("totalSubmitted", totalSubmitted.get());
        status.put("totalCompleted", totalCompleted.get());
        status.put("totalBatches", totalBatches.get());
        status.put("totalTimeoutFlushes", totalTimeoutFlushes.get());
        status.put("registeredModels", new ArrayList<>(batchHandlers.keySet()));

        Map<String, Object> queueDepths = new LinkedHashMap<>();
        for (var entry : modelQueues.entrySet()) {
            queueDepths.put(entry.getKey(), entry.getValue().size());
        }
        status.put("queueDepths", queueDepths);

        Map<String, Object> perModelStats = new LinkedHashMap<>();
        for (String modelId : batchHandlers.keySet()) {
            Map<String, Object> ms = new LinkedHashMap<>();
            ms.put("submitted", perModelSubmitted.getOrDefault(modelId, new AtomicLong()).get());
            ms.put("completed", perModelCompleted.getOrDefault(modelId, new AtomicLong()).get());
            ms.put("queueDepth", modelQueues.containsKey(modelId) ? modelQueues.get(modelId).size() : 0);
            perModelStats.put(modelId, ms);
        }
        status.put("perModelStats", perModelStats);

        return status;
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down ModelScheduler ({} flush executors)", flushExecutors.size());
        for (var entry : flushExecutors.entrySet()) {
            entry.getValue().shutdownNow();
        }
        flushExecutors.clear();
        // Cancel all pending requests
        for (var entry : modelQueues.entrySet()) {
            for (InferenceRequest<?> req : entry.getValue()) {
                req.resultFuture.cancel(false);
            }
        }
        modelQueues.clear();
    }
}
