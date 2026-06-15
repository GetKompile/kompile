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

package ai.kompile.pipelines.steps.samediff.llm;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.ValueType;
import ai.kompile.pipelines.framework.core.config.GenericStepConfig;
import lombok.extern.slf4j.Slf4j;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.io.File;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Step runner for embedding tokens using SameDiff.
 */
@Slf4j
class SameDiffEmbedTokensStepRunner implements PipelineStepRunner {

    private SameDiff sameDiffModel;
    private List<String> outputNames;
    private long eosTokenId;
    private long padTokenId;
    private volatile boolean initialized = false;

    @Override
    public void init(StepConfig stepConfig, Context context) throws Exception {
        if (!(stepConfig instanceof GenericStepConfig)) {
            throw new IllegalArgumentException("Configuration must be an instance of GenericStepConfig");
        }
        GenericStepConfig config = (GenericStepConfig) stepConfig;
        Data params = config.getParameters();

        String modelUri = params.getString(SameDiffLLMConstants.PARAM_MODEL_URI);
        if (modelUri == null || modelUri.isEmpty()) {
            throw new IllegalArgumentException("Model URI must be specified");
        }

        File modelFile = new File(new URI(modelUri));
        if (!modelFile.exists() || !modelFile.isFile()) {
            throw new IllegalArgumentException("Model file not found at: " + modelFile.getAbsolutePath());
        }

        this.sameDiffModel = SameDiff.load(modelFile, true);
        log.info("Loaded SameDiff embed_tokens model from: {}", modelUri);

        this.outputNames = params.getList(SameDiffLLMConstants.PARAM_OUTPUT_NAMES, ValueType.STRING);
        if (this.outputNames == null || this.outputNames.isEmpty()) {
            this.outputNames = Collections.singletonList("hidden_states");
        }

        this.eosTokenId = params.getInt64(SameDiffLLMConstants.PARAM_EOS_TOKEN_ID, 2L);
        this.padTokenId = params.getInt64(SameDiffLLMConstants.PARAM_PAD_TOKEN_ID, 0L);

        this.initialized = true;
        log.info("SameDiffEmbedTokensStepRunner initialized successfully");
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public Data exec(Data input, Context context) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("SameDiffEmbedTokensStepRunner is not initialized");
        }

        INDArray inputIds = input.get(SameDiffLLMConstants.KEY_INPUT_IDS);
        if (inputIds == null) {
            throw new IllegalArgumentException("Input 'input_ids' is required");
        }

        Map<String, INDArray> placeholders = new HashMap<>();
        placeholders.put("input_ids", inputIds);

        INDArray attentionMask = input.get(SameDiffLLMConstants.KEY_ATTENTION_MASK);
        if (attentionMask != null) {
            placeholders.put("attention_mask", attentionMask);
        }

        Map<String, INDArray> outputs = sameDiffModel.output(placeholders, outputNames.toArray(new String[0]));

        Data result = Data.empty();
        for (Map.Entry<String, INDArray> entry : outputs.entrySet()) {
            result.put(entry.getKey(), entry.getValue());
        }

        log.debug("Embedded {} tokens to hidden states", inputIds.length());
        return result;
    }

    @Override
    public void close() throws Exception {
        if (sameDiffModel != null) {
            try {
                java.lang.reflect.Method closeMethod = sameDiffModel.getClass().getMethod("close");
                closeMethod.invoke(sameDiffModel);
            } catch (Exception e) {
                log.warn("Error closing SameDiff model", e);
            }
        }
    }
}
