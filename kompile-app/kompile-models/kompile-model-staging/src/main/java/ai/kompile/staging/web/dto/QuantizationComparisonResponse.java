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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Side-by-side quantization comparison result for already materialized model variants.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuantizationComparisonResponse {
    private boolean success;
    private String error;
    private String baseModelId;
    private VariantResult baseModel;
    private List<VariantResult> variants;
    private String smallestModelId;
    private String bestSizeReductionModelId;
    private String verdict;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VariantResult {
        private boolean success;
        private String modelId;
        private String quantizationType;
        private String error;
        private String modelFile;
        private long sizeBytes;
        private long sizeDeltaBytes;
        private double sizeReductionPercent;
        private int opsCount;
        private int varsCount;
        @Builder.Default
        private Map<String, String> opTypeCounts = new LinkedHashMap<>();
        private CompilerCompareResponse graphComparison;
    }
}
