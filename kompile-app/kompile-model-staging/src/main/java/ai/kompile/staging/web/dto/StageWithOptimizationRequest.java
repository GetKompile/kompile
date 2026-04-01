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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request DTO for staging a model with optional optimization configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class StageWithOptimizationRequest {

    @JsonProperty("autoPromote")
    @Builder.Default
    private boolean autoPromote = false;

    @JsonProperty("optimizationConfig")
    private OptimizationConfigDto optimizationConfig;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OptimizationConfigDto {
        @JsonProperty("enabledPasses")
        private List<String> enabledPasses;

        @JsonProperty("preset")
        private String preset;

        @JsonProperty("quantizationType")
        private String quantizationType;

        @JsonProperty("quantizePerChannel")
        @Builder.Default
        private boolean quantizePerChannel = false;

        @JsonProperty("maxIterations")
        @Builder.Default
        private int maxIterations = 3;
    }
}
