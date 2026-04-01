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

package ai.kompile.pipelines.steps.vlm;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.NDArray;
import ai.kompile.pipelines.framework.api.kvcache.KVCache;
import ai.kompile.pipelines.steps.vlm.cache.Nd4jKVCache;
import ai.kompile.pipelines.steps.vlm.util.VLMSameDiffUtils;
import org.eclipse.deeplearning4j.llm.generation.DecoderUtils;
import org.eclipse.deeplearning4j.llm.generation.ModelIOConfig;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;

/**
 * Executes a single decoder step of a VLM model with KV cache management.
 *
 * <p>Uses DL4J's {@link DecoderUtils} for auto-discovering KV cache names,
 * logits output names, building causal masks, and creating empty KV caches.</p>
 *
 * This runner handles both the initial step (using inputs_embeds) and subsequent
 * autoregressive steps (using single-token input_ids with cached KV pairs).
 *
 * Input Data keys:
 *   - inputs_embeds: NDArray [1, seq, hidden] (first step) OR input_ids: NDArray [1, 1] (subsequent)
 *   - attention_mask: NDArray [1, total_seq]
 *   - position_ids: NDArray [1, seq]
 *   - kv_cache: KVCache (empty for first step, updated for subsequent)
 *
 * Output Data keys:
 *   - logits: NDArray [1, seq, vocab_size]
 *   - kv_cache: KVCache (updated)
 *   - next_token_id: INT64 (argmax of last logit position)
 *   - is_eos: BOOLEAN
 *   - attention_mask: NDArray (extended by 1)
 *   - position_ids: NDArray (incremented)
 *
 * Config parameters:
 *   - modelUri: Path to decoder_model_merged.fb
 *   - outputNames: Model output names
 *   - numKvLayers: Number of KV cache layers (auto-detected if 0)
 *   - numHeads: Number of attention heads
 *   - headDim: Dimension of each attention head
 *   - eosTokenId: End-of-sequence token ID
 *   - maxCacheSeqLen: Maximum KV cache sequence length before truncation (0 = unlimited)
 */
public class VLMDecoderStepRunner implements PipelineStepRunner {

    private static final Logger log = LoggerFactory.getLogger(VLMDecoderStepRunner.class);

    private SameDiff sd;
    private List<String> outputNames;
    private int numKvLayers;
    private int numHeads;
    private int headDim;
    private int eosTokenId;
    private int maxCacheSeqLen;
    private boolean initialized = false;

    // Auto-discovered I/O config from ModelIOConfig
    private ModelIOConfig ioConfig;
    private DecoderUtils.KVCacheNames kvCacheNames;
    private String logitsOutputName;

    @Override
    public void init(StepConfig stepConfig, Context context) throws Exception {
        Data params = stepConfig.getParameters();
        String modelUri = params.getString(VLMConstants.PARAM_MODEL_URI);
        Objects.requireNonNull(modelUri, "modelUri is required for VLMDecoderStepRunner");

        this.outputNames = params.getList(VLMConstants.PARAM_OUTPUT_NAMES,
                ai.kompile.pipelines.framework.api.data.ValueType.STRING);

        this.numKvLayers = params.getInt32(VLMConstants.PARAM_NUM_KV_LAYERS, 0);
        this.numHeads = params.getInt32(VLMConstants.PARAM_NUM_HEADS, 0);
        this.headDim = params.getInt32(VLMConstants.PARAM_HEAD_DIM, 0);
        this.eosTokenId = params.getInt32(VLMConstants.PARAM_EOS_TOKEN_ID, 2);
        this.maxCacheSeqLen = params.getInt32(VLMConstants.PARAM_MAX_CACHE_SEQ_LEN, 0);

        File modelFile = new File(modelUri);
        if (!modelFile.exists()) {
            throw new IllegalArgumentException("Decoder model not found: " + modelUri);
        }
        this.sd = SameDiff.load(modelFile, true);

        // Auto-discover I/O configuration using ModelIOConfig
        this.ioConfig = ModelIOConfig.discover(sd);
        this.kvCacheNames = ioConfig.getKvCacheNames();
        this.logitsOutputName = ioConfig.getLogitsOutputName();

        if (numKvLayers == 0 && kvCacheNames != null
                && kvCacheNames.keyNames != null && !kvCacheNames.keyNames.isEmpty()) {
            numKvLayers = kvCacheNames.keyNames.size();
        }
        if (numKvLayers == 0) {
            numKvLayers = detectNumKvLayersFromInputs();
        }

        if (this.outputNames == null || this.outputNames.isEmpty()) {
            this.outputNames = sd.outputs();
        }

        this.initialized = true;
        log.info("VLMDecoderStepRunner initialized: numKvLayers={}, numHeads={}, headDim={}, " +
                        "logitsOutput={}, maxCacheSeqLen={}",
                numKvLayers, numHeads, headDim, logitsOutputName,
                maxCacheSeqLen > 0 ? maxCacheSeqLen : "unlimited");
    }

    /**
     * Fallback KV layer detection from input names when DecoderUtils doesn't find output names.
     */
    private int detectNumKvLayersFromInputs() {
        int maxLayer = -1;
        for (String input : sd.inputs()) {
            if (input.startsWith("past_key_values.") && input.endsWith(".key")) {
                String layerStr = input.replace("past_key_values.", "").replace(".key", "");
                try {
                    int layer = Integer.parseInt(layerStr);
                    maxLayer = Math.max(maxLayer, layer);
                } catch (NumberFormatException ignored) {}
            }
        }
        return maxLayer + 1;
    }

    @Override
    public Data exec(Data input, Context context) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("VLMDecoderStepRunner not initialized");
        }

        Map<String, INDArray> placeholders = new HashMap<>();
        Map<String, INDArray> outputMap = null;

        // Get or create KV cache
        KVCache kvCache = input.getKVCache(VLMConstants.KEY_KV_CACHE);
        boolean isFirstStep = (kvCache == null || kvCache.getCurrentSequenceLength() == 0);

        if (kvCache == null && numKvLayers > 0) {
            // Use DecoderUtils to create empty KV cache template (returns a single empty INDArray)
            INDArray emptyKv = DecoderUtils.createEmptyKvCache(
                    sd, "input_ids", 1, numHeads * headDim);
            if (emptyKv != null) {
                // Build layer-indexed cache from the template shape
                kvCache = Nd4jKVCache.empty(numKvLayers, numHeads, headDim);
                emptyKv.close();
            } else {
                kvCache = Nd4jKVCache.empty(numKvLayers, numHeads, headDim);
            }
        }

        // Truncate KV cache if it exceeds maximum sequence length
        if (maxCacheSeqLen > 0 && kvCache != null
                && kvCache.getCurrentSequenceLength() > maxCacheSeqLen) {
            log.debug("Truncating KV cache from {} to {} tokens",
                    kvCache.getCurrentSequenceLength(), maxCacheSeqLen);
            if (kvCache instanceof Nd4jKVCache nd4jCache) {
                nd4jCache.truncate(maxCacheSeqLen);
            }
        }

        try {
            // Set up inputs based on what's available, using ModelIOConfig for name matching
            for (String modelInput : sd.inputs()) {
                if (ioConfig.isInputEmbeddings(modelInput) && input.has(VLMConstants.KEY_INPUTS_EMBEDS)) {
                    placeholders.put(modelInput, VLMSameDiffUtils.toINDArray(
                            input.getNDArray(VLMConstants.KEY_INPUTS_EMBEDS)));
                } else if (ioConfig.isInputIds(modelInput) && input.has(VLMConstants.KEY_INPUT_IDS)) {
                    placeholders.put(modelInput, VLMSameDiffUtils.toINDArray(
                            input.getNDArray(VLMConstants.KEY_INPUT_IDS)));
                } else if (ioConfig.isAttentionMask(modelInput) && input.has(VLMConstants.KEY_ATTENTION_MASK)) {
                    placeholders.put(modelInput, VLMSameDiffUtils.toINDArray(
                            input.getNDArray(VLMConstants.KEY_ATTENTION_MASK)));
                } else if (ioConfig.isPositionIds(modelInput) && input.has(VLMConstants.KEY_POSITION_IDS)) {
                    placeholders.put(modelInput, VLMSameDiffUtils.toINDArray(
                            input.getNDArray(VLMConstants.KEY_POSITION_IDS)));
                } else if (modelInput.equals("use_cache_branch")) {
                    placeholders.put("use_cache_branch",
                            Nd4j.scalar(DataType.BOOL, !isFirstStep ? 1 : 0));
                } else if (ioConfig.isCausalMask(modelInput) && input.has(VLMConstants.KEY_ATTENTION_MASK)) {
                    // Build causal mask using DecoderUtils
                    INDArray attnMask = VLMSameDiffUtils.toINDArray(
                            input.getNDArray(VLMConstants.KEY_ATTENTION_MASK));
                    long currentSeqLen = isFirstStep ?
                            (input.has(VLMConstants.KEY_INPUTS_EMBEDS) ?
                                    VLMSameDiffUtils.toINDArray(input.getNDArray(VLMConstants.KEY_INPUTS_EMBEDS)).shape()[1] : 1) : 1;
                    long totalSeqLen = attnMask.shape()[1];
                    INDArray causalMask = DecoderUtils.buildCausalMask(currentSeqLen, totalSeqLen);
                    placeholders.put(modelInput, causalMask);
                } else if (ioConfig.isKvCacheInput(modelInput) && kvCache != null) {
                    // Parse layer index and key/value
                    String withoutPrefix = modelInput.replace("past_key_values.", "");
                    int dotIdx = withoutPrefix.indexOf('.');
                    if (dotIdx > 0) {
                        int layer = Integer.parseInt(withoutPrefix.substring(0, dotIdx));
                        String kvType = withoutPrefix.substring(dotIdx + 1);
                        Nd4jKVCache nd4jCache = (Nd4jKVCache) kvCache;
                        if ("key".equals(kvType)) {
                            INDArray arr = nd4jCache.getKeyINDArray(layer);
                            placeholders.put(modelInput, arr.dup());
                        } else if ("value".equals(kvType)) {
                            INDArray arr = nd4jCache.getValueINDArray(layer);
                            placeholders.put(modelInput, arr.dup());
                        }
                    }
                }
            }

            // Execute model
            outputMap = sd.output(placeholders, outputNames.toArray(new String[0]));

            // Build updated KV cache from discovered output names
            INDArray[] newKeys = new INDArray[numKvLayers];
            INDArray[] newValues = new INDArray[numKvLayers];
            if (kvCacheNames != null && kvCacheNames.keyNames != null) {
                for (int i = 0; i < Math.min(numKvLayers, kvCacheNames.keyNames.size()); i++) {
                    String keyName = kvCacheNames.keyNames.get(i);
                    String valueName = kvCacheNames.valueNames.get(i);
                    if (outputMap.containsKey(keyName)) {
                        newKeys[i] = outputMap.get(keyName).dup();
                    }
                    if (outputMap.containsKey(valueName)) {
                        newValues[i] = outputMap.get(valueName).dup();
                    }
                }
            } else {
                // Fallback to present.N.key/value pattern
                for (int i = 0; i < numKvLayers; i++) {
                    String keyName = "present." + i + ".key";
                    String valueName = "present." + i + ".value";
                    if (outputMap.containsKey(keyName)) {
                        newKeys[i] = outputMap.get(keyName).dup();
                    }
                    if (outputMap.containsKey(valueName)) {
                        newValues[i] = outputMap.get(valueName).dup();
                    }
                }
            }

            Nd4jKVCache updatedCache = Nd4jKVCache.wrap(numKvLayers, numHeads, headDim, newKeys, newValues);

            // Extract logits using auto-discovered name
            INDArray logits = null;
            if (logitsOutputName != null && outputMap.containsKey(logitsOutputName)) {
                logits = outputMap.get(logitsOutputName).dup();
            } else {
                // Fallback: look for "logits" key
                for (String outName : outputNames) {
                    if (outName.equals("logits") && outputMap.containsKey(outName)) {
                        logits = outputMap.get(outName).dup();
                        break;
                    }
                }
            }

            // Sample next token (greedy argmax of last position)
            long nextTokenId = -1;
            boolean isEos = false;
            if (logits != null) {
                long seqLen = logits.shape()[1];
                INDArray lastLogits = logits.get(
                        org.nd4j.linalg.indexing.NDArrayIndex.point(0),
                        org.nd4j.linalg.indexing.NDArrayIndex.point(seqLen - 1),
                        org.nd4j.linalg.indexing.NDArrayIndex.all());
                nextTokenId = lastLogits.argMax(0).getLong(0);
                lastLogits.close();
                isEos = (nextTokenId == eosTokenId);
            }

            // Build output
            Data result = Data.empty();
            if (logits != null) {
                result.put(VLMConstants.KEY_LOGITS, VLMSameDiffUtils.fromINDArray(logits, VLMConstants.KEY_LOGITS));
            }
            result.put(VLMConstants.KEY_KV_CACHE, updatedCache);
            result.put(VLMConstants.KEY_NEXT_TOKEN_ID, nextTokenId);
            result.put(VLMConstants.KEY_IS_EOS, isEos);

            // Update attention_mask (extend by 1) and position_ids for next step
            if (input.has(VLMConstants.KEY_ATTENTION_MASK)) {
                INDArray oldMask = VLMSameDiffUtils.toINDArray(input.getNDArray(VLMConstants.KEY_ATTENTION_MASK));
                INDArray one = Nd4j.ones(DataType.INT64, 1, 1);
                INDArray newMask = Nd4j.concat(1, oldMask, one);
                result.put(VLMConstants.KEY_ATTENTION_MASK,
                        VLMSameDiffUtils.fromINDArray(newMask, VLMConstants.KEY_ATTENTION_MASK));
                one.close();
            }

            if (input.has(VLMConstants.KEY_POSITION_IDS)) {
                INDArray oldPos = VLMSameDiffUtils.toINDArray(input.getNDArray(VLMConstants.KEY_POSITION_IDS));
                long maxPos = oldPos.maxNumber().longValue();
                INDArray newPos = Nd4j.create(new long[]{maxPos + 1}, new long[]{1, 1}, DataType.INT64);
                result.put(VLMConstants.KEY_POSITION_IDS,
                        VLMSameDiffUtils.fromINDArray(newPos, VLMConstants.KEY_POSITION_IDS));
            }

            // Provide input_ids for next iteration (the generated token)
            if (nextTokenId >= 0) {
                INDArray nextInputIds = Nd4j.create(new long[]{nextTokenId}, new long[]{1, 1}, DataType.INT64);
                result.put(VLMConstants.KEY_INPUT_IDS,
                        VLMSameDiffUtils.fromINDArray(nextInputIds, VLMConstants.KEY_INPUT_IDS));
            }

            // Close the old KV cache
            if (kvCache != null) {
                try { kvCache.close(); } catch (Exception ignored) {}
            }

            // Clear SameDiff placeholders to free memory
            sd.clearPlaceholders(false);

            return result;
        } finally {
            VLMSameDiffUtils.closeAll(placeholders);
            VLMSameDiffUtils.closeAll(outputMap);
        }
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void close() throws Exception {
        if (sd != null) {
            try {
                java.lang.reflect.Method closeMethod = sd.getClass().getMethod("close");
                closeMethod.invoke(sd);
            } catch (NoSuchMethodException ignored) {
            } catch (Exception ignored) {
            }
            sd = null;
        }
        initialized = false;
    }
}
