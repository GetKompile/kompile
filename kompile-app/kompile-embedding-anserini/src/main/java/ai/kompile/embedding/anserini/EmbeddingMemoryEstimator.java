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

package ai.kompile.embedding.anserini;

import ai.kompile.modelmanager.ModelConstants;

import java.util.Map;

/**
 * Estimates GPU memory requirements for transformer embedding models.
 * Used for pre-flight checks before launching embedding operations and
 * for generating actionable error messages when GPU memory is insufficient.
 *
 * <p>The estimation is intentionally conservative (overestimates) — it is
 * better to warn early than to let a subprocess OOM and crash.</p>
 */
public final class EmbeddingMemoryEstimator {

    private EmbeddingMemoryEstimator() {} // utility class

    // Safety factor: only flag as insufficient when estimated > 85% of free GPU
    private static final double SAFETY_FACTOR = 0.85;

    /**
     * Estimate peak GPU memory for a transformer encoder model.
     *
     * <p>The dominant cost is the attention matrices: each layer computes
     * Q·K^T producing a [batch, heads, seq, seq] tensor in float32.
     * This scales quadratically with sequence length.</p>
     *
     * @param numLayers      transformer layers
     * @param numHeads       attention heads
     * @param seqLength      padded sequence length (after bucket snapping)
     * @param batchSize      batch size
     * @param modelSizeBytes approximate model weight size on GPU (0 to skip)
     * @return estimated peak memory in bytes
     */
    public static long estimatePeakMemoryBytes(int numLayers, int numHeads,
                                                int seqLength, int batchSize,
                                                long modelSizeBytes) {
        // Attention score matrix: [batch, heads, seq, seq] * 4 bytes (float32)
        long attentionPerLayer = (long) batchSize * numHeads * seqLength * seqLength * 4L;
        // Each layer needs: Q·K^T scores + softmax output + V projection ≈ 3x
        long activationsPerLayer = attentionPerLayer * 3;
        // Sum across all layers
        long totalActivations = (long) numLayers * activationsPerLayer;
        // Model weights + workspace overhead (~30%)
        long overhead = (long) (modelSizeBytes * 0.3);
        return modelSizeBytes + totalActivations + overhead;
    }

    /**
     * Estimate peak GPU memory using model metadata from ModelConstants.
     * Falls back to conservative defaults if architecture metadata is unavailable.
     *
     * @param modelId   model identifier (e.g. "bge-m3")
     * @param batchSize batch size
     * @param seqLength padded sequence length
     * @return estimated peak memory in bytes, or -1 if model is unknown
     */
    public static long estimatePeakMemoryBytes(String modelId, int batchSize, int seqLength) {
        Map<String, Object> arch = ModelConstants.getArchitectureMetadata(modelId);
        if (arch.isEmpty()) return -1;

        int numLayers = getInt(arch, "num_layers", 12);
        int numHeads = getInt(arch, "num_heads", 12);
        long modelSizeBytes = getLong(arch, "model_size_bytes", 500_000_000L);

        return estimatePeakMemoryBytes(numLayers, numHeads, seqLength, batchSize, modelSizeBytes);
    }

    /**
     * Check if a model can run at the given sequence length with available GPU memory.
     *
     * @param modelId       model identifier
     * @param seqLength     max sequence length
     * @param gpuFreeBytes  currently free GPU memory
     * @param gpuTotalBytes total GPU memory
     * @return null if sufficient, or an actionable error message
     */
    public static String checkMemorySufficiency(String modelId, int seqLength,
                                                 long gpuFreeBytes, long gpuTotalBytes) {
        long estimatedPeak = estimatePeakMemoryBytes(modelId, 1, seqLength);
        if (estimatedPeak < 0) return null; // unknown model, skip check

        if (estimatedPeak > (long) (gpuFreeBytes * SAFETY_FACTOR)) {
            return formatOomError(modelId, seqLength, gpuFreeBytes, gpuTotalBytes, estimatedPeak);
        }
        return null;
    }

    /**
     * Format an actionable OOM error message with GPU context and suggestions.
     */
    public static String formatOomError(String modelId, int seqLength,
                                         long gpuFreeBytes, long gpuTotalBytes,
                                         long estimatedPeakBytes) {
        long gpuFreeMb = gpuFreeBytes / (1024 * 1024);
        long gpuTotalMb = gpuTotalBytes / (1024 * 1024);
        long estimatedMb = estimatedPeakBytes / (1024 * 1024);

        // Calculate a safe sequence length that would fit
        int safeSeqLength = suggestMaxSequenceLength(modelId, gpuFreeBytes);

        StringBuilder sb = new StringBuilder();
        sb.append("GPU memory insufficient for model '").append(modelId)
          .append("' at seq_len=").append(seqLength).append(". ");
        sb.append("Estimated peak: ").append(estimatedMb).append(" MB, ");
        sb.append("GPU free: ").append(gpuFreeMb).append(" MB / ")
          .append(gpuTotalMb).append(" MB total. ");
        if (safeSeqLength > 0 && safeSeqLength < seqLength) {
            sb.append("Suggested max seq_len: ").append(safeSeqLength).append(". ");
        }
        sb.append("Consider reducing max sequence length or using a lighter model (e.g. bge-base-en-v1.5).");
        return sb.toString();
    }

    /**
     * Suggest the maximum safe sequence length for a model given available GPU memory.
     * Returns the largest bucket size that fits, or -1 if even 64 tokens won't fit.
     */
    public static int suggestMaxSequenceLength(String modelId, long gpuFreeBytes) {
        int[] buckets = {4096, 2048, 1024, 512, 256, 128, 64};
        for (int bucket : buckets) {
            long est = estimatePeakMemoryBytes(modelId, 1, bucket);
            if (est > 0 && est < (long) (gpuFreeBytes * SAFETY_FACTOR)) {
                return bucket;
            }
        }
        return -1;
    }

    private static int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val instanceof Integer) return (Integer) val;
        if (val instanceof Long) return ((Long) val).intValue();
        if (val instanceof Number) return ((Number) val).intValue();
        return defaultValue;
    }

    private static long getLong(Map<String, Object> map, String key, long defaultValue) {
        Object val = map.get(key);
        if (val instanceof Long) return (Long) val;
        if (val instanceof Integer) return (Integer) val;
        if (val instanceof Number) return ((Number) val).longValue();
        return defaultValue;
    }
}
