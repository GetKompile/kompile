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

package io.anserini.encoder.samediff;

import io.anserini.encoder.samediff.tokenizer.SamediffBertTokenizerPreProcessor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CosDprDistilSameDiffEncoder extends SameDiffEncoder<float[]> {
    private static final Logger LOG = LogManager.getLogger(CosDprDistilSameDiffEncoder.class);

    public static final boolean DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS = true;
    public static final int DEFAULT_MAX_SEQUENCE_LENGTH = 512;
    public static final boolean DEFAULT_ADD_SPECIAL_TOKENS = true;

    // ========== DYNAMIC BATCH SIZE CONFIGURATION ==========
    // Batch sizes are calculated dynamically based on sequence length.
    // Shorter sequences allow larger batches (attention is O(n²) in sequence length).
    // Sorting by sequence length groups similar-length items together to minimize padding waste.

    private static final int BASE_OPTIMAL_BATCH_SIZE = 4;
    private static final int BASE_MAX_BATCH_SIZE = 8;
    private static final int REFERENCE_SEQ_LENGTH = 512;
    private static final int ABSOLUTE_MIN_BATCH_SIZE = 1;
    private static final int ABSOLUTE_MAX_BATCH_SIZE = 64;
    private static final double MEMORY_SCALE_FACTOR;

    static {
        long maxHeapMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        if (maxHeapMB < 4096) {
            MEMORY_SCALE_FACTOR = 0.5;
        } else if (maxHeapMB < 8192) {
            MEMORY_SCALE_FACTOR = 0.75;
        } else if (maxHeapMB < 16384) {
            MEMORY_SCALE_FACTOR = 1.0;
        } else {
            MEMORY_SCALE_FACTOR = 1.5;
        }
    }

    private static int calculateOptimalBatchSize(int maxSeqLength) {
        if (maxSeqLength <= 0) maxSeqLength = REFERENCE_SEQ_LENGTH;
        double seqLengthRatio = (double) REFERENCE_SEQ_LENGTH / maxSeqLength;
        double scaleFactor = seqLengthRatio * seqLengthRatio * MEMORY_SCALE_FACTOR;
        int optimalBatch = (int) Math.round(BASE_OPTIMAL_BATCH_SIZE * scaleFactor);
        return Math.max(ABSOLUTE_MIN_BATCH_SIZE, Math.min(optimalBatch, ABSOLUTE_MAX_BATCH_SIZE));
    }

    /**
     * Helper record to track original index during sorting.
     */
    private record IndexedEncoding(
            int originalIndex,
            String text,
            SamediffBertTokenizerPreProcessor.BertEncoding encoding,
            int seqLength
    ) {}

    private volatile boolean batchInferenceSupported = true;

    public CosDprDistilSameDiffEncoder(@NotNull String modelIdentifier,
                                       boolean doLowerCaseAndStripAccents,
                                       int maxSequenceLength,
                                       boolean addSpecialTokens) throws IOException {
        super(modelIdentifier, doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens);
        LOG.info("[{}] CosDprDistilSameDiffEncoder initialized.", modelIdentifier);
    }

    // Simplified constructor using defaults
    public CosDprDistilSameDiffEncoder(@NotNull String modelIdentifier) throws IOException {
        this(modelIdentifier,
                DEFAULT_DO_LOWERCASE_AND_STRIP_ACCENTS,
                DEFAULT_MAX_SEQUENCE_LENGTH,
                DEFAULT_ADD_SPECIAL_TOKENS);
    }

    // Legacy constructor for backward compatibility
    @Deprecated
    public CosDprDistilSameDiffEncoder(@NotNull String modelIdentifier,
                                       @NotNull String kompileManagedModelPath,
                                       @NotNull String kompileManagedVocabPath,
                                       boolean doLowerCaseAndStripAccents,
                                       int maxSequenceLength,
                                       boolean addSpecialTokens) throws IOException {
        super(modelIdentifier, kompileManagedModelPath, kompileManagedVocabPath,
                null, null, // Will be dynamically determined
                doLowerCaseAndStripAccents, maxSequenceLength, addSpecialTokens);
        LOG.info("[{}] CosDprDistilSameDiffEncoder initialized (legacy).", modelIdentifier);
    }

    @Override
    public float[] encode(@NotNull String query) {
        // Check for interrupt before starting encoding
        if (Thread.currentThread().isInterrupted()) {
            LOG.info("[{}] Encoding interrupted before starting for query: '{}'",
                    modelIdentifier, query.substring(0, Math.min(50, query.length())));
            return null;
        }

        SamediffBertTokenizerPreProcessor.BertEncoding encoding = this.tokenizerPreProcessor.encode(query);

        // Check for interrupt after tokenization (before inference)
        if (Thread.currentThread().isInterrupted()) {
            LOG.info("[{}] Encoding interrupted after tokenization", modelIdentifier);
            return null;
        }

        // Delegate to encodeFromTokenized for actual inference
        return encodeFromTokenized(query, encoding);
    }

    /**
     * Override from SameDiffEncoder to use pre-tokenized encoding directly.
     * This avoids re-tokenization in the pipelined bulk encoding path.
     *
     * <p>CosDPR encoder has no instruction prefix, so the pipelined approach
     * in the base class will work fully - parallel tokenization feeds directly
     * into sequential inference without re-tokenization.</p>
     *
     * @param query The original text (for logging purposes)
     * @param encoding The pre-tokenized BertEncoding
     * @return The embedding as a float array, or null on error
     */
    @Override
    protected float[] encodeFromTokenized(String query, SamediffBertTokenizerPreProcessor.BertEncoding encoding) {
        if (encoding == null) {
            LOG.warn("[{}] Null encoding provided for query", modelIdentifier);
            return null;
        }

        // Track all arrays that need cleanup to prevent off-heap memory leaks
        Map<String, INDArray> placeholderMap = new HashMap<>();
        Map<String, INDArray> outputMap = null;
        INDArray embeddingTensor = null;

        try {
            // Get what the model actually expects
            List<String> modelInputs = this.inputTensorNamesForModel;

            LOG.debug("[{}] Model expects {} inputs: {}", this.modelIdentifier, modelInputs.size(), modelInputs);

            // Test for existence of each standard input in the model and provide if needed
            for (String inputName : modelInputs) {
                if (isInputIdsInput(inputName)) {
                    // CRITICAL: Create 2D array directly to avoid intermediate array leak from castTo()
                    INDArray inputIds = Nd4j.createFromArray(new long[][]{encoding.inputIds});
                    placeholderMap.put(inputName, inputIds);
                    LOG.debug("[{}] Providing input_ids as '{}'", this.modelIdentifier, inputName);
                } else if (isAttentionMaskInput(inputName)) {
                    INDArray attentionMask = Nd4j.createFromArray(new long[][]{encoding.attentionMask});
                    placeholderMap.put(inputName, attentionMask);
                    LOG.debug("[{}] Providing attention_mask as '{}'", this.modelIdentifier, inputName);
                } else if (isTokenTypeIdsInput(inputName)) {
                    INDArray tokenTypeIds = Nd4j.createFromArray(new long[][]{encoding.tokenTypeIds});
                    placeholderMap.put(inputName, tokenTypeIds);
                    LOG.debug("[{}] Providing token_type_ids as '{}'", this.modelIdentifier, inputName);
                } else {
                    LOG.warn("[{}] Unknown model input '{}' - cannot map to tokenizer output", this.modelIdentifier, inputName);
                }
            }
            String outputTensorName = this.outputTensorNamesFromModel.get(0);

            // Execute inference - this is a blocking native call that cannot be interrupted
            outputMap = this.sameDiffModel.output(placeholderMap, outputTensorName);

            // Check for interrupt immediately after inference completes
            if (Thread.currentThread().isInterrupted()) {
                LOG.info("[{}] Encoding interrupted after inference completed", modelIdentifier);
                return null;
            }

            embeddingTensor = outputMap.get(outputTensorName);

            if (embeddingTensor == null) {
                LOG.error("[{}] Output tensor '{}' not found. Available outputs: {}",
                        this.modelIdentifier, outputTensorName, outputMap.keySet());
                return null;
            }

            LOG.debug("[{}] Got output tensor '{}' with shape: {}",
                    this.modelIdentifier, outputTensorName, Arrays.toString(embeddingTensor.shape()));

            return processOutput(embeddingTensor);

        } catch (Exception e) {
            // Check if exception is due to interrupt
            if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                LOG.info("[{}] Encoding interrupted during processing", modelIdentifier);
                Thread.currentThread().interrupt(); // Restore interrupt flag
                return null;
            }
            LOG.error("[{}] Error during CosDPR encoding for query: '{}'", this.modelIdentifier, query, e);
            return null;
        } finally {
            // CRITICAL: Close all input arrays to prevent off-heap memory leaks
            // These are created fresh on every encode() call and must be released
            for (INDArray arr : placeholderMap.values()) {
                if (arr != null) {
                    try {
                        arr.close();
                    } catch (Exception e) {
                        LOG.debug("[{}] Error closing input array: {}", this.modelIdentifier, e.getMessage());
                    }
                }
            }
            // CRITICAL: Close ALL output arrays in the outputMap, not just the one we used
            // The outputMap may contain multiple tensors from intermediate computations
            if (outputMap != null) {
                for (Map.Entry<String, INDArray> entry : outputMap.entrySet()) {
                    INDArray arr = entry.getValue();
                    if (arr != null) {
                        try {
                            arr.close();
                        } catch (Exception e) {
                            LOG.debug("[{}] Error closing output array '{}': {}",
                                    this.modelIdentifier, entry.getKey(), e.getMessage());
                        }
                    }
                }
            }
            // Note: embeddingTensor is already closed above as part of outputMap iteration

            // CRITICAL: Trigger periodic workspace cleanup for single-encode path
            maybeCleanupWorkspaces();
        }
    }
    
    private boolean isInputIdsInput(String inputName) {
        String lower = inputName.toLowerCase();
        return lower.contains("input") && lower.contains("id") || 
               lower.equals("input_ids") || 
               lower.equals("inputids") ||
               lower.equals("input");
    }
    
    private boolean isAttentionMaskInput(String inputName) {
        String lower = inputName.toLowerCase();
        return (lower.contains("attention") && lower.contains("mask")) ||
               lower.equals("attention_mask") ||
               lower.equals("attentionmask") ||
               lower.equals("mask");
    }
    
    private boolean isTokenTypeIdsInput(String inputName) {
        String lower = inputName.toLowerCase();
        return (lower.contains("token") && lower.contains("type")) ||
               (lower.contains("type") && lower.contains("id")) ||
               lower.equals("token_type_ids") ||
               lower.equals("tokentypeids") ||
               lower.equals("segment_ids") ||
               lower.equals("segmentids");
    }
    
    private float[] processOutput(INDArray embeddingTensor) {
        // Validate input tensor
        if (embeddingTensor == null || embeddingTensor.isEmpty() || embeddingTensor.length() == 0) {
            LOG.error("[{}] Invalid embedding tensor - null or empty", this.modelIdentifier);
            return null;
        }

        // Additional validation: check if data buffer is valid
        if (embeddingTensor.data() == null) {
            LOG.error("[{}] Invalid embedding tensor - null data buffer", this.modelIdentifier);
            return null;
        }

        if (embeddingTensor.rank() == 2 && embeddingTensor.shape()[0] == 1) {
            // 2D tensor [batch, hidden] - already pooled
            return safeToFloatVector(embeddingTensor);
        } else if (embeddingTensor.rank() == 1) {
            // 1D tensor [hidden] - already a vector
            return safeToFloatVector(embeddingTensor);
        } else if (embeddingTensor.rank() == 3 && embeddingTensor.shape()[0] == 1) {
            // 3D tensor [batch, sequence, hidden] - flatten to vector
            LOG.warn("[{}] Got 3D tensor, flattening to vector for CosDPR. Shape: {}",
                    this.modelIdentifier, Arrays.toString(embeddingTensor.shape()));
            INDArray reshaped = null;
            try {
                reshaped = embeddingTensor.reshape(1, -1);
                if (reshaped == null || reshaped.data() == null) {
                    LOG.error("[{}] Invalid embedding after reshape - null or null data buffer", this.modelIdentifier);
                    return null;
                }
                return safeToFloatVector(reshaped);
            } finally {
                // CRITICAL: Close reshaped array to prevent native memory leak
                if (reshaped != null) {
                    try {
                        reshaped.close();
                    } catch (Exception e) {
                        LOG.debug("[{}] Error closing reshaped array: {}", this.modelIdentifier, e.getMessage());
                    }
                }
            }
        } else {
            LOG.warn("[{}] Unexpected CosDPR embedding tensor shape: {}. Attempting to flatten and use.",
                    this.modelIdentifier, Arrays.toString(embeddingTensor.shape()));
            INDArray reshaped = null;
            try {
                reshaped = embeddingTensor.reshape(1, -1);
                if (reshaped == null || reshaped.data() == null) {
                    LOG.error("[{}] Invalid embedding after reshape - null or null data buffer", this.modelIdentifier);
                    return null;
                }
                return safeToFloatVector(reshaped);
            } finally {
                // CRITICAL: Close reshaped array to prevent native memory leak
                if (reshaped != null) {
                    try {
                        reshaped.close();
                    } catch (Exception e) {
                        LOG.debug("[{}] Error closing reshaped array: {}", this.modelIdentifier, e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Safely converts an INDArray to a float[] vector, catching any native pointer exceptions.
     * This handles cases where the data buffer exists but the underlying native pointer is null.
     */
    private float[] safeToFloatVector(INDArray array) {
        if (array == null) {
            LOG.error("[{}] Cannot convert null array to float vector", this.modelIdentifier);
            return null;
        }
        try {
            return array.toFloatVector();
        } catch (NullPointerException e) {
            // This catches JavaCPP "Pointer address of argument X is NULL" errors
            LOG.error("[{}] Native pointer is null during toFloatVector - array may have been closed or corrupted: {}",
                    this.modelIdentifier, e.getMessage());
            return null;
        } catch (IllegalStateException e) {
            // This catches "DataBuffer was already released" errors
            LOG.error("[{}] DataBuffer was released during toFloatVector: {}",
                    this.modelIdentifier, e.getMessage());
            return null;
        } catch (Exception e) {
            LOG.error("[{}] Unexpected error during toFloatVector: {}",
                    this.modelIdentifier, e.getMessage(), e);
            return null;
        }
    }

    // ========== BATCH ENCODING WITH SORTING OPTIMIZATION ==========

    /**
     * TRUE BATCH ENCODING - Process multiple texts with dynamic sizing and sorting.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Tokenize all texts upfront</li>
     *   <li>Sort by sequence length to minimize padding waste</li>
     *   <li>Calculate optimal batch size for each sub-batch</li>
     *   <li>Process sub-batches and reorder results</li>
     * </ol>
     *
     * @param texts List of texts to encode in a single batch
     * @return List of embeddings, one per input text. Null entries for failed encodings.
     */
    @Override
    public List<float[]> encodeBatch(@NotNull List<String> texts) {
        if (texts.isEmpty()) {
            return new ArrayList<>();
        }

        if (!shouldProceedWithEncoding()) {
            LOG.debug("[{}] encodeBatch rejected - shutdown in progress", modelIdentifier);
            return null;
        }

        getEncoderLock().lock();
        try {
            if (!batchInferenceSupported) {
                return encodeSequential(texts);
            }

            return encodeBatchWithDynamicSizing(texts);
        } finally {
            getEncoderLock().unlock();
        }
    }

    private List<float[]> encodeSequential(List<String> texts) {
        List<float[]> results = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            if (Thread.currentThread().isInterrupted()) return null;
            results.add(encode(texts.get(i)));
        }
        return results;
    }

    private List<float[]> encodeBatchWithDynamicSizing(List<String> texts) {
        long totalStartTime = System.currentTimeMillis();
        int numTexts = texts.size();

        // Step 1: Tokenize all texts and track sequence lengths
        List<IndexedEncoding> indexedEncodings = new ArrayList<>(numTexts);
        int maxSeqLength = 0;
        int totalTokens = 0;
        int minSeqLength = Integer.MAX_VALUE;

        long tokenizeStart = System.currentTimeMillis();
        for (int i = 0; i < numTexts; i++) {
            if (Thread.currentThread().isInterrupted()) return null;

            String text = texts.get(i);
            SamediffBertTokenizerPreProcessor.BertEncoding enc = this.tokenizerPreProcessor.encode(text);
            int seqLen = enc.inputIds.length;
            indexedEncodings.add(new IndexedEncoding(i, text, enc, seqLen));

            totalTokens += seqLen;
            if (seqLen > maxSeqLength) maxSeqLength = seqLen;
            if (seqLen < minSeqLength) minSeqLength = seqLen;
        }
        long tokenizeTime = System.currentTimeMillis() - tokenizeStart;

        // Step 2: Sort by sequence length to minimize padding waste
        indexedEncodings.sort((a, b) -> Integer.compare(a.seqLength, b.seqLength));

        int unsortedPaddingCost = numTexts * maxSeqLength;
        int sortedPaddingCost = 0;

        // Step 3: Calculate optimal batch size based on average sequence length
        double avgSeqLength = numTexts > 0 ? (double) totalTokens / numTexts : 0;
        int avgBasedOptimal = calculateOptimalBatchSize((int) avgSeqLength);

        // Step 4: Check if single batch is sufficient
        if (numTexts <= avgBasedOptimal) {
            int actualMaxSeq = indexedEncodings.get(numTexts - 1).seqLength;

            List<String> sortedTexts = new ArrayList<>(numTexts);
            List<SamediffBertTokenizerPreProcessor.BertEncoding> sortedEncodings = new ArrayList<>(numTexts);
            for (IndexedEncoding ie : indexedEncodings) {
                sortedTexts.add(ie.text);
                sortedEncodings.add(ie.encoding);
            }

            List<float[]> sortedResults = encodeSingleInferenceBatchFromEncodings(sortedTexts, sortedEncodings, actualMaxSeq);

            if (sortedResults == null && batchInferenceSupported) {
                LOG.warn("[{}] Batch inference failed - falling back to sequential", modelIdentifier);
                batchInferenceSupported = false;
                sortedResults = encodeSequentialFromEncodings(sortedTexts, sortedEncodings);
            }

            if (sortedResults == null) return null;

            // Reorder results back to original order
            float[][] reorderedResults = new float[numTexts][];
            for (int i = 0; i < numTexts; i++) {
                reorderedResults[indexedEncodings.get(i).originalIndex] = sortedResults.get(i);
            }

            long totalTime = System.currentTimeMillis() - totalStartTime;
            LOG.debug("[{}] Single batch: {} texts in {}ms ({} ms/text)",
                    modelIdentifier, numTexts, totalTime, String.format("%.2f", (double)totalTime / numTexts));

            return Arrays.asList(reorderedResults);
        }

        // Step 5: Adaptive sub-batch processing with length-aware grouping
        float[][] reorderedResults = new float[numTexts][];
        long embeddingStartTime = System.currentTimeMillis();
        int processedCount = 0;
        int subBatchNum = 0;

        while (processedCount < numTexts) {
            if (Thread.currentThread().isInterrupted()) return null;

            int remaining = numTexts - processedCount;
            int sampleEnd = Math.min(processedCount + 32, numTexts);
            int sampleMaxSeq = indexedEncodings.get(sampleEnd - 1).seqLength;
            int optimalForSample = calculateOptimalBatchSize(sampleMaxSeq);

            int subBatchSize = Math.min(optimalForSample, remaining);
            int subBatchMaxSeq = indexedEncodings.get(processedCount + subBatchSize - 1).seqLength;
            int refinedOptimal = calculateOptimalBatchSize(subBatchMaxSeq);

            while (subBatchSize < remaining && subBatchSize < refinedOptimal) {
                int nextIdx = processedCount + subBatchSize;
                int nextSeqLen = indexedEncodings.get(nextIdx).seqLength;
                int newOptimal = calculateOptimalBatchSize(nextSeqLen);
                if (subBatchSize + 1 <= newOptimal) {
                    subBatchSize++;
                    subBatchMaxSeq = nextSeqLen;
                    refinedOptimal = newOptimal;
                } else {
                    break;
                }
            }

            sortedPaddingCost += subBatchSize * subBatchMaxSeq;
            subBatchNum++;

            List<String> subTexts = new ArrayList<>(subBatchSize);
            List<SamediffBertTokenizerPreProcessor.BertEncoding> subEncodings = new ArrayList<>(subBatchSize);
            List<Integer> subOriginalIndices = new ArrayList<>(subBatchSize);

            for (int i = 0; i < subBatchSize; i++) {
                IndexedEncoding ie = indexedEncodings.get(processedCount + i);
                subTexts.add(ie.text);
                subEncodings.add(ie.encoding);
                subOriginalIndices.add(ie.originalIndex);
            }

            List<float[]> subResults = encodeSingleInferenceBatchFromEncodings(subTexts, subEncodings, subBatchMaxSeq);

            if (subResults == null) {
                if (Thread.currentThread().isInterrupted()) return null;

                if (SameDiffEncoder.isFailFastOnError()) {
                    throw new SameDiffEncoder.EncodingException(modelIdentifier, "sub-batch encoding",
                            "Sub-batch " + subBatchNum + " failed", null);
                }

                subResults = encodeSequentialFromEncodings(subTexts, subEncodings);
                if (subResults == null) return null;
            }

            for (int i = 0; i < subBatchSize; i++) {
                reorderedResults[subOriginalIndices.get(i)] = subResults.get(i);
            }

            processedCount += subBatchSize;
        }

        long totalTime = System.currentTimeMillis() - totalStartTime;
        long embeddingTime = System.currentTimeMillis() - embeddingStartTime;
        double paddingSavings = unsortedPaddingCost > 0
                ? (1.0 - (double) sortedPaddingCost / unsortedPaddingCost) * 100.0 : 0.0;

        LOG.info("[{}] {} sub-batches: {} texts in {}ms (tokenize={}ms, embed={}ms, padding saved: {}%)",
                modelIdentifier, subBatchNum, numTexts, totalTime, tokenizeTime, embeddingTime, String.format("%.1f", paddingSavings));

        return Arrays.asList(reorderedResults);
    }

    private List<float[]> encodeSequentialFromEncodings(List<String> texts,
                                                         List<SamediffBertTokenizerPreProcessor.BertEncoding> encodings) {
        List<float[]> results = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            if (Thread.currentThread().isInterrupted()) return null;
            results.add(encodeFromTokenized(texts.get(i), encodings.get(i)));
        }
        return results;
    }

    private List<float[]> encodeSingleInferenceBatchFromEncodings(
            List<String> texts,
            List<SamediffBertTokenizerPreProcessor.BertEncoding> encodings,
            int maxSeqLength) {

        if (encodings.isEmpty()) return new ArrayList<>();

        int batchSize = encodings.size();

        long[][] batchInputIds = new long[batchSize][maxSeqLength];
        long[][] batchAttentionMask = new long[batchSize][maxSeqLength];
        long[][] batchTokenTypeIds = new long[batchSize][maxSeqLength];

        for (int i = 0; i < batchSize; i++) {
            SamediffBertTokenizerPreProcessor.BertEncoding enc = encodings.get(i);
            int seqLen = enc.inputIds.length;
            System.arraycopy(enc.inputIds, 0, batchInputIds[i], 0, seqLen);
            System.arraycopy(enc.attentionMask, 0, batchAttentionMask[i], 0, seqLen);
            if (enc.tokenTypeIds != null) {
                System.arraycopy(enc.tokenTypeIds, 0, batchTokenTypeIds[i], 0, seqLen);
            }
        }

        Map<String, INDArray> placeholderMap = new HashMap<>();
        Map<String, INDArray> outputMap = null;
        List<float[]> results = new ArrayList<>(batchSize);

        try {
            for (String inputName : this.inputTensorNamesForModel) {
                if (isInputIdsInput(inputName)) {
                    placeholderMap.put(inputName, Nd4j.createFromArray(batchInputIds));
                } else if (isAttentionMaskInput(inputName)) {
                    placeholderMap.put(inputName, Nd4j.createFromArray(batchAttentionMask));
                } else if (isTokenTypeIdsInput(inputName)) {
                    placeholderMap.put(inputName, Nd4j.createFromArray(batchTokenTypeIds));
                }
            }

            if (Thread.currentThread().isInterrupted()) return null;

            String outputTensorName = this.outputTensorNamesFromModel.get(0);
            outputMap = this.sameDiffModel.output(placeholderMap, outputTensorName);

            if (Thread.currentThread().isInterrupted()) return null;

            INDArray batchOutput = outputMap.get(outputTensorName);
            if (batchOutput == null) {
                LOG.error("[{}] Output tensor not found", modelIdentifier);
                return null;
            }

            for (int i = 0; i < batchSize; i++) {
                try {
                    results.add(extractSingleEmbedding(batchOutput, i));
                } catch (Exception e) {
                    LOG.warn("[{}] Failed to extract embedding {}: {}", modelIdentifier, i, e.getMessage());
                    results.add(null);
                }
            }

            return results;

        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) return null;
            LOG.error("[{}] Error in batch from encodings: {}", modelIdentifier, e.getMessage(), e);
            if (SameDiffEncoder.isFailFastOnError()) {
                throw new SameDiffEncoder.EncodingException(modelIdentifier, "batch from encodings", e.getMessage(), e);
            }
            return null;
        } finally {
            for (INDArray arr : placeholderMap.values()) {
                if (arr != null) try { arr.close(); } catch (Exception ignored) {}
            }
            if (outputMap != null) {
                for (INDArray arr : outputMap.values()) {
                    if (arr != null) try { arr.close(); } catch (Exception ignored) {}
                }
            }
        }
    }

    private float[] extractSingleEmbedding(INDArray batchOutput, int batchIndex) {
        INDArray singleOutput = null;
        INDArray reshaped = null;
        long[] shape = batchOutput.shape();

        try {
            if (shape.length == 2) {
                // [batch, hidden] - already pooled (typical for CosDPR)
                singleOutput = batchOutput.getRow(batchIndex);
                return safeToFloatVector(singleOutput);
            } else if (shape.length == 3) {
                // [batch, seq_len, hidden] - need to extract
                singleOutput = batchOutput.get(
                        NDArrayIndex.point(batchIndex),
                        NDArrayIndex.point(0), // Take first token (CLS)
                        NDArrayIndex.all());
                return safeToFloatVector(singleOutput);
            } else if (shape.length == 1) {
                // [hidden] - single vector
                return safeToFloatVector(batchOutput);
            } else {
                LOG.warn("[{}] Unexpected batch output shape: {}", modelIdentifier, Arrays.toString(shape));
                reshaped = batchOutput.reshape(1, -1).getRow(batchIndex);
                return safeToFloatVector(reshaped);
            }
        } finally {
            if (singleOutput != null && singleOutput != batchOutput) {
                try { singleOutput.close(); } catch (Exception ignored) {}
            }
            if (reshaped != null) {
                try { reshaped.close(); } catch (Exception ignored) {}
            }
        }
    }
}
