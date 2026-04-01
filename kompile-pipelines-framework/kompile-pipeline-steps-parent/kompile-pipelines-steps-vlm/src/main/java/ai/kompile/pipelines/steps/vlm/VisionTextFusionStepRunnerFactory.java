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

public class VisionTextFusionStepRunnerFactory implements PipelineStepRunnerFactory {
    public static final String RUNNER_FQCN = "ai.kompile.pipelines.steps.vlm.VisionTextFusionStepRunner";

    @Override
    public String stepTypeName() { return VLMConstants.VISION_TEXT_FUSION_TYPE; }

    @Override
    public String getRunnerType() { return RUNNER_FQCN; }

    @Override
    public PipelineStepRunner create() { return new VisionTextFusionStepRunner(); }

    @Override
    public StepSchema getSchema() {
        return StepSchema.builder()
                .name(VLMConstants.VISION_TEXT_FUSION_TYPE)
                .runnerClassName(RUNNER_FQCN)
                .description("Fuses vision and text embeddings by replacing image token positions")
                .parameter(ParameterSchema.builder().name(VLMConstants.PARAM_IMAGE_TOKEN_ID)
                        .type(ValueType.INT64).required(true)
                        .description("Token ID for the <image> placeholder token").build())
                .build();
    }
}
