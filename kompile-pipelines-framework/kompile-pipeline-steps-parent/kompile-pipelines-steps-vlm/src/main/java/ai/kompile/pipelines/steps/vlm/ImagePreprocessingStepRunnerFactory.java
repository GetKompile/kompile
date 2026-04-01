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

public class ImagePreprocessingStepRunnerFactory implements PipelineStepRunnerFactory {
    public static final String RUNNER_FQCN = "ai.kompile.pipelines.steps.vlm.ImagePreprocessingStepRunner";

    @Override
    public String stepTypeName() { return VLMConstants.IMAGE_PREPROCESSING_TYPE; }

    @Override
    public String getRunnerType() { return RUNNER_FQCN; }

    @Override
    public PipelineStepRunner create() { return new ImagePreprocessingStepRunner(); }

    @Override
    public StepSchema getSchema() {
        return StepSchema.builder()
                .name(VLMConstants.IMAGE_PREPROCESSING_TYPE)
                .runnerClassName(RUNNER_FQCN)
                .description("Image preprocessing with tiling and normalization for VLM")
                .parameter(ParameterSchema.builder().name(VLMConstants.PARAM_TILE_SIZE)
                        .type(ValueType.INT64).required(false)
                        .description("Tile dimension in pixels (default: 364)").build())
                .parameter(ParameterSchema.builder().name(VLMConstants.PARAM_MAX_TILES)
                        .type(ValueType.INT64).required(false)
                        .description("Maximum number of tiles (default: 5)").build())
                .parameter(ParameterSchema.builder().name(VLMConstants.PARAM_RESCALE_FACTOR)
                        .type(ValueType.DOUBLE).required(false)
                        .description("Pixel rescale factor (default: 1/255)").build())
                .build();
    }
}
