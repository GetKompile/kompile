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

package ai.kompile.kvcache.bridge;

import ai.kompile.kvcache.service.KVCacheStatisticsCollector;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.deeplearning4j.llm.generation.*;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.util.List;
import java.util.Map;

/**
 * Adapter that bridges DL4J's {@link KvCacheManager} interface with kompile's
 * KV cache management infrastructure (statistics, monitoring, REST API visibility).
 *
 * <p>This adapter wraps any {@link KvCacheManager} implementation (Static, Quantized, etc.)
 * and adds kompile-side tracking so the cache appears in the KV cache browser UI
 * with live statistics.</p>
 *
 * <h3>Design</h3>
 * <p>The adapter uses composition rather than inheritance:</p>
 * <ul>
 *   <li>Delegates all KV cache tensor operations to an inner {@link KvCacheManager}</li>
 *   <li>Records statistics (appends, cache position, memory) into kompile's
 *       {@link KVCacheStatisticsCollector}</li>
 *   <li>The kompile {@link ai.kompile.kvcache.service.KVCacheManager} tracks this
 *       cache by name, making it visible in the REST API and UI</li>
 * </ul>
 *
 * <h3>Lifecycle</h3>
 * <p>The adapter is created by {@link VlmKvCacheIntegrationService} when a VLM model
 * loads. It persists across inference calls so that KV cache state is reused for
 * repeated document processing. The cache is destroyed when the model is unloaded
 * or the application shuts down.</p>
 *
 * @see KvCacheManager
 * @see StaticKvCacheManager
 * @see VlmKvCacheIntegrationService
 */
@Slf4j
public class KompileKvCacheAdapter implements KvCacheManager {

    /** The name this cache is registered under in kompile's KVCacheManager. */
    @Getter
    private final String cacheName;

    /** The model ID this cache is associated with. */
    @Getter
    private final String modelId;

    /** The inner DL4J KvCacheManager that handles actual tensor operations. */
    private final KvCacheManager delegate;

    /** Kompile statistics collector for recording cache events. */
    private final KVCacheStatisticsCollector statsCollector;

    /** Total number of inference runs that have used this cache. */
    private long inferenceCount = 0;

    /**
     * Creates a new adapter wrapping any KvCacheManager with kompile tracking.
     *
     * @param cacheName      name for registration in kompile's cache manager
     * @param modelId        VLM model ID (e.g., "smoldocling-256m")
     * @param delegate       the actual KvCacheManager to delegate to (Static, Quantized, etc.)
     * @param statsCollector kompile statistics collector for event recording
     */
    public KompileKvCacheAdapter(String cacheName, String modelId,
                                  KvCacheManager delegate,
                                  KVCacheStatisticsCollector statsCollector) {
        this.cacheName = cacheName;
        this.modelId = modelId;
        this.delegate = delegate;
        this.statsCollector = statsCollector;

        // Register with stats collector for memory/sequence tracking
        statsCollector.registerCache(cacheName, this::estimateMemoryUsageBytes, this::getActiveSequenceCount);
        log.info("KompileKvCacheAdapter created: name='{}', modelId='{}'", cacheName, modelId);
    }

    @Override
    public KvCacheStrategy getStrategy() {
        return delegate.getStrategy();
    }

    @Override
    public void initializeFromPrefill(Map<String, INDArray> prefillOutputs,
                                       DecoderUtils.KVCacheNames kvNames,
                                       int maxNewTokens, long prefillSeqLen) {
        delegate.initializeFromPrefill(prefillOutputs, kvNames, maxNewTokens, prefillSeqLen);
        inferenceCount++;
        log.info("KompileKvCacheAdapter '{}': initialized from prefill (prefillLen={}, maxKvLen={}, inference #{})",
                cacheName, prefillSeqLen, delegate.getMaxKvLen(), inferenceCount);
    }

    @Override
    public void initializeEmpty(SameDiff decoder, List<String> decoderInputNames,
                                 int maxKvLen, long hiddenSize) {
        delegate.initializeEmpty(decoder, decoderInputNames, maxKvLen, hiddenSize);
        inferenceCount++;
        log.info("KompileKvCacheAdapter '{}': initialized empty (maxKvLen={}, inference #{})",
                cacheName, maxKvLen, inferenceCount);
    }

    @Override
    public void prepareInputs(Map<String, INDArray> decoderInputMap, SameDiff decoder,
                               long hiddenSize, boolean dspActive) {
        delegate.prepareInputs(decoderInputMap, decoder, hiddenSize, dspActive);
    }

    @Override
    public void scatterNewEntries(Map<String, INDArray> decoderOutputs,
                                   DecoderUtils.KVCacheNames kvNames) {
        delegate.scatterNewEntries(decoderOutputs, kvNames);
        statsCollector.recordAppend(cacheName);
    }

    @Override
    public void scatterMultipleEntries(Map<String, INDArray> decoderOutputs,
                                        DecoderUtils.KVCacheNames kvNames,
                                        int numEntries) {
        delegate.scatterMultipleEntries(decoderOutputs, kvNames, numEntries);
        // Record each entry as an append
        for (int i = 0; i < numEntries; i++) {
            statsCollector.recordAppend(cacheName);
        }
    }

    @Override
    public long getCachePosition() {
        return delegate.getCachePosition();
    }

    @Override
    public void setCachePosition(long position) {
        delegate.setCachePosition(position);
    }

    @Override
    public long getMaxKvLen() {
        return delegate.getMaxKvLen();
    }

    @Override
    public boolean isInitialized() {
        return delegate.isInitialized();
    }

    @Override
    public boolean supportsCudaGraphReplay() {
        return delegate.supportsCudaGraphReplay();
    }

    @Override
    public Map<String, INDArray> getStaticKvBuffers() {
        return delegate.getStaticKvBuffers();
    }

    @Override
    public void close() {
        log.info("KompileKvCacheAdapter '{}': closing (total inferences={})", cacheName, inferenceCount);
        statsCollector.unregisterCache(cacheName);
        delegate.close();
    }

    /**
     * Returns the number of inference runs that have used this cache.
     */
    public long getInferenceCount() {
        return inferenceCount;
    }

    /**
     * Estimates memory usage in bytes based on the delegate's static KV buffers.
     */
    private long estimateMemoryUsageBytes() {
        Map<String, INDArray> buffers = delegate.getStaticKvBuffers();
        if (buffers == null || buffers.isEmpty()) return 0;
        long total = 0;
        for (INDArray buf : buffers.values()) {
            if (buf != null && !buf.wasClosed()) {
                total += buf.length() * buf.dataType().width();
            }
        }
        return total;
    }

    /**
     * Returns the number of active sequences (always 1 for VLM single-sequence decode).
     */
    private int getActiveSequenceCount() {
        return delegate.isInitialized() ? 1 : 0;
    }
}
