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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Continuous batching scheduler for autoregressive decode operations.
 * Unlike dynamic batching (which groups prefill requests), continuous batching
 * manages active decode slots where each slot generates tokens independently.
 *
 * <p>New requests join the active set immediately without waiting for others
 * to complete (iteration-level scheduling). Each decode step processes all
 * active slots in a single forward pass.</p>
 */
@Service
public class ContinuousBatcher {

    private static final Logger log = LoggerFactory.getLogger(ContinuousBatcher.class);

    private final ModelSchedulerConfigService configService;

    // Active decode slots
    private final ConcurrentHashMap<String, DecodeSlot> activeSlots = new ConcurrentHashMap<>();

    // Waiting queue for requests when all slots are occupied
    private final LinkedBlockingQueue<DecodeSlot> waitingQueue = new LinkedBlockingQueue<>();

    // Decode step handler (set by the model/generation service)
    private volatile DecodeStepHandler decodeStepHandler;

    // Decode loop thread
    private volatile ScheduledExecutorService decodeExecutor;
    private volatile boolean running = false;

    // Statistics
    private final AtomicLong totalDecodeSteps = new AtomicLong();
    private final AtomicLong totalTokensGenerated = new AtomicLong();
    private final AtomicLong totalRequestsCompleted = new AtomicLong();
    private final AtomicLong totalRequestsSubmitted = new AtomicLong();

    public ContinuousBatcher(ModelSchedulerConfigService configService) {
        this.configService = configService;
        log.info("ContinuousBatcher initialized");
    }

    /**
     * Handler for a single decode step across all active slots.
     */
    @FunctionalInterface
    public interface DecodeStepHandler {
        /**
         * Run one decode iteration for all active slots.
         * Returns map of requestId -> generated token (or null if slot finished).
         */
        Map<String, DecodeResult> decodeStep(Collection<DecodeSlot> activeSlots);
    }

    public record DecodeResult(int tokenId, String tokenText, boolean finished) {}

    /**
     * A decode slot representing an active generation request.
     */
    public static class DecodeSlot {
        public final String requestId;
        public final int seqIdx;
        public final int[] promptTokenIds;
        public volatile int currentPos;
        public final int maxTokens;
        public final CompletableFuture<String> resultFuture;
        public final Instant submittedAt;
        public final StringBuilder generatedText = new StringBuilder();

        public DecodeSlot(String requestId, int seqIdx, int[] promptTokenIds, int maxTokens) {
            this.requestId = requestId;
            this.seqIdx = seqIdx;
            this.promptTokenIds = promptTokenIds;
            this.currentPos = promptTokenIds.length;
            this.maxTokens = maxTokens;
            this.resultFuture = new CompletableFuture<>();
            this.submittedAt = Instant.now();
        }
    }

    /**
     * Register the decode step handler.
     */
    public void setDecodeStepHandler(DecodeStepHandler handler) {
        this.decodeStepHandler = handler;
    }

    /**
     * Submit a decode request. Returns a future with the generated text.
     */
    public CompletableFuture<String> submitDecode(int[] promptTokenIds, int maxTokens) {
        ModelSchedulerConfig config = configService.getConfiguration();
        if (!config.isContinuousBatchingEnabled()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Continuous batching is not enabled"));
        }

        String requestId = UUID.randomUUID().toString();
        DecodeSlot slot;
        totalRequestsSubmitted.incrementAndGet();

        synchronized (activeSlots) {
            int seqIdx = activeSlots.size();
            slot = new DecodeSlot(requestId, seqIdx, promptTokenIds, maxTokens);
            if (activeSlots.size() < config.getMaxConcurrentDecodes()) {
                activeSlots.put(requestId, slot);
            } else {
                waitingQueue.offer(slot);
            }
        }

        ensureDecodeLoop();
        return slot.resultFuture;
    }

    /**
     * Retire a slot (called when generation is complete or cancelled).
     */
    public void retireSlot(String requestId) {
        DecodeSlot removed = activeSlots.remove(requestId);
        if (removed != null && !removed.resultFuture.isDone()) {
            removed.resultFuture.complete(removed.generatedText.toString());
            totalRequestsCompleted.incrementAndGet();
        }

        // Promote waiting requests
        ModelSchedulerConfig config = configService.getConfiguration();
        synchronized (activeSlots) {
            while (activeSlots.size() < config.getMaxConcurrentDecodes() && !waitingQueue.isEmpty()) {
                DecodeSlot next = waitingQueue.poll();
                if (next != null) {
                    activeSlots.put(next.requestId, next);
                }
            }
        }
    }

    /**
     * Run one decode iteration for all active slots.
     */
    public void decodeStep() {
        if (activeSlots.isEmpty() || decodeStepHandler == null) return;

        totalDecodeSteps.incrementAndGet();

        try {
            Map<String, DecodeResult> results = decodeStepHandler.decodeStep(activeSlots.values());

            List<String> toRetire = new ArrayList<>();
            for (var entry : results.entrySet()) {
                String requestId = entry.getKey();
                DecodeResult result = entry.getValue();
                DecodeSlot slot = activeSlots.get(requestId);

                if (slot == null) continue;

                if (result.tokenText() != null) {
                    slot.generatedText.append(result.tokenText());
                }
                slot.currentPos++;
                totalTokensGenerated.incrementAndGet();

                if (result.finished() || slot.currentPos >= slot.promptTokenIds.length + slot.maxTokens) {
                    toRetire.add(requestId);
                }
            }

            for (String id : toRetire) {
                retireSlot(id);
            }
        } catch (Exception e) {
            log.error("Decode step failed: {}", e.getMessage(), e);
        }
    }

    private void ensureDecodeLoop() {
        if (running) return;
        synchronized (this) {
            if (running) return;
            running = true;
            decodeExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "continuous-batcher-decode");
                t.setDaemon(true);
                return t;
            });
            decodeExecutor.scheduleWithFixedDelay(this::decodeStep, 1, 1, TimeUnit.MILLISECONDS);
            log.info("Started continuous batching decode loop");
        }
    }

    /**
     * Get status for monitoring.
     */
    public Map<String, Object> getStatus() {
        ModelSchedulerConfig config = configService.getConfiguration();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", config.isContinuousBatchingEnabled());
        status.put("maxConcurrentDecodes", config.getMaxConcurrentDecodes());
        status.put("activeSlots", activeSlots.size());
        status.put("waitingRequests", waitingQueue.size());
        status.put("totalDecodeSteps", totalDecodeSteps.get());
        status.put("totalTokensGenerated", totalTokensGenerated.get());
        status.put("totalRequestsSubmitted", totalRequestsSubmitted.get());
        status.put("totalRequestsCompleted", totalRequestsCompleted.get());
        status.put("running", running);

        List<Map<String, Object>> slots = new ArrayList<>();
        for (var slot : activeSlots.values()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("requestId", slot.requestId);
            s.put("seqIdx", slot.seqIdx);
            s.put("currentPos", slot.currentPos);
            s.put("maxTokens", slot.maxTokens);
            s.put("generatedLength", slot.generatedText.length());
            s.put("submittedAt", slot.submittedAt.toString());
            slots.add(s);
        }
        status.put("slots", slots);

        return status;
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (decodeExecutor != null) {
            decodeExecutor.shutdownNow();
        }
        // Cancel all active and waiting requests
        for (var slot : activeSlots.values()) {
            slot.resultFuture.cancel(false);
        }
        activeSlots.clear();
        for (var slot : waitingQueue) {
            slot.resultFuture.cancel(false);
        }
        waitingQueue.clear();
        log.info("ContinuousBatcher shut down");
    }
}
