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
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.configschema.ParameterSchema;
import ai.kompile.pipelines.framework.api.configschema.StepSchema;
import ai.kompile.pipelines.framework.api.configschema.StepSchemaProvider;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.ValueType;
import ai.kompile.pipelines.framework.core.config.GenericStepConfig;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.linalg.api.ndarray.INDArray;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.net.URI;
import java.util.*;

/**
 * Factory for creating SameDiff embed tokens step runners.
 * This step converts token IDs to hidden state embeddings using the model's embedding layer.
 */
@Slf4j
public class SameDiffEmbedTokensStepRunnerFactory implements PipelineStepRunnerFactory, StepSchemaProvider {

    public static final String STEP_TYPE_NAME = "SAMEDIFF_EMBED_TOKENS";
    public static final String RUNNER_FQCN = "ai.kompile.pipelines.steps.samediff.llm.SameDiffEmbedTokensStepRunner";

    @Override
    public String stepTypeName() {
        return STEP_TYPE_NAME;
    }

    @Override
    public String getRunnerType() {
        return RUNNER_FQCN;
    }

    @Override
    public PipelineStepRunner create() {
        return new SameDiffEmbedTokensStepRunner();
    }

    @Override
    public StepSchema getSchema() {
        return StepSchema.builder()
                .name(stepTypeName())
                .runnerClassName(RUNNER_FQCN)
                .description("Converts token IDs to hidden state embeddings using SameDiff model's embedding layer")
                .configClass(GenericStepConfig.class.getName())
                .parameters(Arrays.asList(
                        ParameterSchema.builder().name(SameDiffLLMConstants.PARAM_MODEL_URI)
                                .type(ValueType.STRING)
                                .description("URI to the SameDiff embed_tokens model file")
                                .required(true).build(),
                        ParameterSchema.builder().name(SameDiffLLMConstants.PARAM_OUTPUT_NAMES)
                                .type(ValueType.LIST)
                                .listElementType(ValueType.STRING)
                                .description("Output names to fetch from the model")
                                .required(false).build(),
                        ParameterSchema.builder().name(SameDiffLLMConstants.PARAM_EOS_TOKEN_ID)
                                .type(ValueType.INT64)
                                .description("EOS token ID")
                                .defaultValue(2L)
                                .required(false).build(),
                        ParameterSchema.builder().name(SameDiffLLMConstants.PARAM_PAD_TOKEN_ID)
                                .type(ValueType.INT64)
                                .description("PAD token ID")
                                .defaultValue(0L)
                                .required(false).build()
                ))
                .inputs(Arrays.asList(
                        ParameterSchema.builder().name(SameDiffLLMConstants.KEY_INPUT_IDS)
                                .type(ValueType.NDARRAY)
                                .description("Input token IDs to embed")
                                .required(true).build(),
                        ParameterSchema.builder().name(SameDiffLLMConstants.KEY_ATTENTION_MASK)
                                .type(ValueType.NDARRAY)
                                .description("Attention mask (optional)")
                                .required(false).build()
                ))
                .outputs(Collections.singletonList(
                        ParameterSchema.builder().name("hidden_states")
                                .type(ValueType.NDARRAY)
                                .description("Token embeddings/hidden states")
                                .required(true).build()
                ))
                .build();
    }

    @Override
    public Optional<StepSchema> getSchema(String runnerClassName) {
        return Optional.empty();
    }
}

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
