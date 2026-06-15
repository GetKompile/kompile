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

package ai.kompile.modelmanager.registry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration for the tokenizer used with a model.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TokenizerConfig {

    @JsonProperty("do_lower_case")
    @Builder.Default
    private boolean doLowerCase = true;

    @JsonProperty("add_special_tokens")
    @Builder.Default
    private boolean addSpecialTokens = true;

    @JsonProperty("strip_accents")
    @Builder.Default
    private boolean stripAccents = true;

    @JsonProperty("max_length")
    @Builder.Default
    private int maxLength = 512;

    @JsonProperty("padding")
    @Builder.Default
    private String padding = "max_length";

    @JsonProperty("truncation")
    @Builder.Default
    private boolean truncation = true;

    public static TokenizerConfig defaultBertConfig() {
        return TokenizerConfig.builder()
                .doLowerCase(true)
                .addSpecialTokens(true)
                .stripAccents(true)
                .maxLength(512)
                .padding("max_length")
                .truncation(true)
                .build();
    }
}
