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

import java.util.Map;

/**
 * Response from comparing two models (before/after optimization).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompilerCompareResponse {
    private boolean success;
    private String error;
    private ModelInfo model1Info;
    private ModelInfo model2Info;
    private int opsAdded;
    private int opsRemoved;
    private int opsChanged;
    private long sizeChange;
    private double maxAbsoluteDifference;
    private double meanAbsoluteDifference;
    private boolean outputsMatch;
    private double speedupFactor;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelInfo {
        private String modelId;
        private int opsCount;
        private int varsCount;
        private long sizeBytes;
        private long inferenceTimeMs;
        private Map<String, String> opTypeCounts;
    }
}
