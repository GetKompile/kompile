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
