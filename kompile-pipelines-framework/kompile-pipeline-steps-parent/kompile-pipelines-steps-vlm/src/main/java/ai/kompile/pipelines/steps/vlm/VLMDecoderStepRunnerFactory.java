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
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory;
import ai.kompile.pipelines.framework.api.configschema.ParameterSchema;
import ai.kompile.pipelines.framework.api.configschema.StepSchema;
import ai.kompile.pipelines.framework.api.data.ValueType;

public class VLMDecoderStepRunnerFactory implements PipelineStepRunnerFactory {
    public static final String RUNNER_FQCN = "ai.kompile.pipelines.steps.vlm.VLMDecoderStepRunner";

    @Override
    public String stepTypeName() { return VLMConstants.VLM_DECODER_TYPE; }

    @Override
    public String getRunnerType() { return RUNNER_FQCN; }

    @Override
    public PipelineStepRunner create() { return new VLMDecoderStepRunner(); }

    @Override
    public StepSchema getSchema() {
        return StepSchema.builder()
                .name(VLMConstants.VLM_DECODER_TYPE)
                .runnerClassName(RUNNER_FQCN)
                .description("VLM decoder with KV cache management for autoregressive generation")
                .parameter(ParameterSchema.builder().name(VLMConstants.PARAM_MODEL_URI)
                        .type(ValueType.STRING).required(true)
                        .description("Path to the decoder_model_merged SameDiff model").build())
                .parameter(ParameterSchema.builder().name(VLMConstants.PARAM_OUTPUT_NAMES)
                        .type(ValueType.LIST).listElementType(ValueType.STRING).required(false)
                        .description("Model output names (auto-detected if not specified)").build())
                .parameter(ParameterSchema.builder().name(VLMConstants.PARAM_NUM_KV_LAYERS)
                        .type(ValueType.INT64).required(false)
                        .description("Number of KV cache layers (auto-detected if 0)").build())
                .parameter(ParameterSchema.builder().name(VLMConstants.PARAM_NUM_HEADS)
                        .type(ValueType.INT64).required(true)
                        .description("Number of attention heads").build())
                .parameter(ParameterSchema.builder().name(VLMConstants.PARAM_HEAD_DIM)
                        .type(ValueType.INT64).required(true)
                        .description("Dimension of each attention head").build())
                .parameter(ParameterSchema.builder().name(VLMConstants.PARAM_EOS_TOKEN_ID)
                        .type(ValueType.INT64).required(false)
                        .description("End-of-sequence token ID (default: 2)").build())
                .build();
    }
}
