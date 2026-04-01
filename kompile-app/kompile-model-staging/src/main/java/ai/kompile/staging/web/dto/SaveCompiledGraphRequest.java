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
 * Request to compile and save a SameDiff graph as a separate model.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaveCompiledGraphRequest {
    private String sourceModelId;
    private String outputModelId;
    private List<String> targetOutputs;
    private List<String> selectedPasses;
    private String profile;
    @Builder.Default
    private int maxIterations = 3;
    @Builder.Default
    private String saveFormat = "fb";
    private String description;
}
