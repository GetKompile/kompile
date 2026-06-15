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

package ai.kompile.staging.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Request to compile a staged model with specified compilation and execution modes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompilationRequest {
    private String modelId;
    private String compilationMode; // REDUCE_OVERHEAD, SPLIT_STITCH, MAX_AUTOTUNE
    private String executionMode; // AUTO, SLOT_BY_SLOT, CUDA_GRAPHS, NVRTC_JIT, PTX_JIT, TRITON
    private List<String> selectedPasses;
    private String profile;
    @Builder.Default
    private int maxIterations = 3;
    @Builder.Default
    private boolean createBackup = true;
    private String outputModelId;
    @Builder.Default
    private boolean enableCache = true;
    private List<String> targetOutputs;
    private Map<String, List<Long>> placeholderShapes;
}
