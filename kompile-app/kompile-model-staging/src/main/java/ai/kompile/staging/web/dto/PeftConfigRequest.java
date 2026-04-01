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
 * Request for configuring PEFT (Parameter-Efficient Fine-Tuning).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PeftConfigRequest {
    private String peftType; // LORA, QLORA, ADALORA, DYLORA, DORA, IA3, PROMPT_TUNING, PREFIX_TUNING
    private String baseModelId;
    private LoraConfigDto loraConfig;
    private QloraConfigDto qloraConfig;
    private AdaLoraConfigDto adaLoraConfig;
    private DyLoraConfigDto dyLoraConfig;
    private DoraConfigDto doraConfig;
    private Ia3ConfigDto ia3Config;
    private PromptTuningConfigDto promptTuningConfig;
    private PrefixTuningConfigDto prefixTuningConfig;
}
