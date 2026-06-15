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
 * Configuration DTO for speculative decoding settings.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpeculativeDecodingConfig {
    @Builder.Default
    private boolean enabled = false;
    @Builder.Default
    private int ngramSize = 3;
    @Builder.Default
    private int maxSpeculativeTokens = 5;
    @Builder.Default
    private boolean useDraftModel = false;
    private String draftModelId;
}
