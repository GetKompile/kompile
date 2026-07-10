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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Request for applying training-free abliteration/model editing to a SameDiff model.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AbliterationRequest {
    private String modelId;
    private String outputModelId;
    private String method;
    private String layerSelectionStrategy;
    private Integer topK;
    private Double ablationStrength;
    private Boolean winsorize;
    private Double winsorizePercentile;
    @Builder.Default
    private List<String> targetWeightPatterns = new ArrayList<>();
    @Builder.Default
    private List<Direction> directions = new ArrayList<>();
    @Builder.Default
    private Map<String, List<List<Double>>> harmfulActivations = new LinkedHashMap<>();
    @Builder.Default
    private Map<String, List<List<Double>>> harmlessActivations = new LinkedHashMap<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Direction {
        private String layerName;
        private String position;
        @Builder.Default
        private List<Double> values = new ArrayList<>();
        private Double score;
        private Integer layerIndex;
    }
}
