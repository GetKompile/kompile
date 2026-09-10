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

/**
 * Request DTO for loading an LLM model.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmLoadModelRequest {
    private String modelId;
    /**
     * KV cache type: STATIC, PAGED, QUANTIZED
     */
    @Builder.Default
    private String kvCacheType = "STATIC";
    /**
     * Optional explicit path assertion. The path must resolve to exactly the checksum-verified
     * file registered for {@code modelId}; it never permits loading an arbitrary model file.
     */
    private String modelPath;
}
