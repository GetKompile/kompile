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
import ai.kompile.pipelines.steps.vlm.util.VLMSameDiffUtils;
import org.eclipse.deeplearning4j.llm.generation.SamplingConfig;
import org.eclipse.deeplearning4j.llm.generation.Sampler;
import org.eclipse.deeplearning4j.llm.generation.SamplerUtils;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.util.List;
import java.util.Objects;

/**
 * Samples the next token from logits using DL4J's {@link SamplingConfig} and {@link Sampler}.
 *
 * <p>Supports greedy (temperature=0), temperature scaling, top-k, top-p, and
 * repetition penalty via DL4J's sampling framework.</p>
 *
 * Input Data keys:
 *   - logits: NDArray [1, seq, vocab_size]
 *   - generated_tokens: long[] (optional, for repetition penalty)
 *
 * Output Data keys:
 *   - next_token_id: INT64
 *   - is_eos: BOOLEAN
 *
 * Config parameters:
 *   - eosTokenId: End-of-sequence token ID (default: 2)
 *   - temperature: Sampling temperature (default: 0.0 = greedy)
 *   - topK: Top-K filtering (default: 0 = disabled)
 *   - topP: Top-P (nucleus) filtering (default: 1.0 = disabled)
 *   - repetitionPenalty: Penalty for repeated tokens (default: 1.0 = disabled)
 *   - doSample: Whether to sample or use greedy (default: false)
 */
public class TokenSamplingStepRunner implements PipelineStepRunner {

    private int eosTokenId = 2;
    private SamplingConfig samplingConfig;
    private Sampler sampler;
    private boolean initialized = false;

    @Override
    public void init(StepConfig stepConfig, Context context) throws Exception {
        Data params = stepConfig.getParameters();
        this.eosTokenId = params.getInt32(VLMConstants.PARAM_EOS_TOKEN_ID, 2);

        // Build SamplingConfig from step parameters
        double temperature = params.getDouble(VLMConstants.PARAM_TEMPERATURE, 0.0);
        int topK = params.getInt32(VLMConstants.PARAM_TOP_K, 0);
        double topP = params.getDouble(VLMConstants.PARAM_TOP_P, 1.0);
        double repetitionPenalty = params.getDouble(VLMConstants.PARAM_REPETITION_PENALTY, 1.0);
        boolean doSample = params.getBoolean(VLMConstants.PARAM_DO_SAMPLE, false);

        // Also support legacy samplingStrategy param for backwards compat
        String samplingStrategy = params.getString(VLMConstants.PARAM_SAMPLING_STRATEGY, null);
        if (samplingStrategy != null && temperature == 0.0 && !doSample) {
            switch (samplingStrategy) {
                case VLMConstants.SAMPLING_TOP_K:
                    topK = topK > 0 ? topK : 50;
                    doSample = true;
                    temperature = 1.0;
                    break;
                case VLMConstants.SAMPLING_TOP_P:
                    topP = topP < 1.0 ? topP : 0.9;
                    doSample = true;
                    temperature = 1.0;
                    break;
                case VLMConstants.SAMPLING_GREEDY:
                default:
                    break;
            }
        }

        this.samplingConfig = SamplingConfig.builder()
                .temperature(temperature)
                .topK(topK)
                .topP(topP)
                .repetitionPenalty(repetitionPenalty)
                .doSample(doSample)
                .build();

        // Create sampler from config; falls back to greedy when temperature is 0
        this.sampler = Sampler.fromConfig(samplingConfig);
        this.initialized = true;
    }

    @Override
    public Data exec(Data input, Context context) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("TokenSamplingStepRunner not initialized");
        }

        NDArray logitsNDArray = Objects.requireNonNull(input.getNDArray(VLMConstants.KEY_LOGITS),
                "logits is required");
        INDArray logits = VLMSameDiffUtils.toINDArray(logitsNDArray);

        long seqLen = logits.shape()[1];

        // Get last position logits
        INDArray lastLogits = logits.get(
                org.nd4j.linalg.indexing.NDArrayIndex.point(0),
                org.nd4j.linalg.indexing.NDArrayIndex.point(seqLen - 1),
                org.nd4j.linalg.indexing.NDArrayIndex.all());

        // Apply repetition penalty if previous tokens are available
        if (samplingConfig.getRepetitionPenalty() != 1.0 && input.has(VLMConstants.KEY_GENERATED_TOKENS)) {
            List<Long> previousTokensList = input.getList(VLMConstants.KEY_GENERATED_TOKENS,
                    ai.kompile.pipelines.framework.api.data.ValueType.INT64);
            if (previousTokensList != null && !previousTokensList.isEmpty()) {
                int[] previousTokens = new int[previousTokensList.size()];
                for (int i = 0; i < previousTokensList.size(); i++) {
                    previousTokens[i] = previousTokensList.get(i).intValue();
                }
                SamplerUtils.applyRepetitionPenalty(lastLogits, previousTokens,
                        samplingConfig.getRepetitionPenalty());
            }
        }

        // Sample using DL4J's Sampler
        long nextTokenId = sampler.sample(lastLogits);

        lastLogits.close();

        boolean isEos = (nextTokenId == eosTokenId);

        Data result = Data.empty();
        result.put(VLMConstants.KEY_NEXT_TOKEN_ID, nextTokenId);
        result.put(VLMConstants.KEY_IS_EOS, isEos);
        return result;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void close() throws Exception {
        initialized = false;
    }
}
