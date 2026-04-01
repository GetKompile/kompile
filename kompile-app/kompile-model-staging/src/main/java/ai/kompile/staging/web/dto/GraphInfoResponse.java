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
 * Response containing model graph information.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphInfoResponse {
    private String modelId;
    private int totalOps;
    private int totalVariables;
    private Map<String, Integer> opTypes; // op type name -> count
    private List<String> inputNames;
    private List<String> outputNames;
    private List<OpInfo> ops;
    private long modelSizeBytes;
    private GraphAnalysis analysis;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OpInfo {
        private String name;
        private String opType;
        private List<String> inputs;
        private List<String> outputs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GraphAnalysis {
        private int graphDepth;
        private long parameterCount;
        private Map<String, Long> parametersByDataType;
        private int constantCount;
        private List<LayerGroup> layerGroups;
        private int attentionHeads;
        private boolean hasAttentionFusion;
        private boolean hasLinearFusion;
        private int fusedOpCount;
        private long memoryEstimateBytes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LayerGroup {
        private String name;
        private int count;
        private int opsPerGroup;
        private List<String> opTypes;
    }
}
