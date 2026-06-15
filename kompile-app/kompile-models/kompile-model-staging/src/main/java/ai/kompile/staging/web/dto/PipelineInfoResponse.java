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
 * Response DTO with detailed pipeline and decoder information
 * for the currently loaded model.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PipelineInfoResponse {
    private boolean loaded;
    private String modelId;
    private String decoderPath;
    private String kvCacheStrategy;
    private long memoryUsageMb;

    // Decoder info
    private DecoderConfigRequest decoderConfig;

    // Model I/O info
    private List<String> inputNames;
    private List<String> outputNames;
    private String logitsOutputName;
    private List<String> kvCacheKeyNames;
    private List<String> kvCacheValueNames;

    // Sampling config snapshot
    private Map<String, Object> currentSamplingConfig;

    // Speculative decoding
    private SpeculativeDecodingConfig speculativeConfig;

    private String message;
}
