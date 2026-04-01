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

/**
 * Request to run compiler optimization on a model.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompilerOptimizeRequest {
    private String modelId;
    private List<String> selectedPasses;
    @Builder.Default
    private int maxIterations = 3;
    private String profile; // FULL, BASIC, TRANSFORMER, GPU, NONE
    private String outputModelId;
    private String quantizationType;
    @Builder.Default
    private boolean force = false;
    @Builder.Default
    private boolean createBackup = true;
    @Builder.Default
    private boolean dryRun = false;
}
