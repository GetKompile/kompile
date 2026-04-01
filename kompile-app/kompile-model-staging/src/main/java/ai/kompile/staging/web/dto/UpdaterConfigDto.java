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
 * Configuration for the optimizer/updater used during training.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdaterConfigDto {
    @Builder.Default
    private String type = "ADAM";
    @Builder.Default
    private double learningRate = 1e-4;
    @Builder.Default
    private double beta1 = 0.9;
    @Builder.Default
    private double beta2 = 0.999;
    @Builder.Default
    private double epsilon = 1e-8;
    @Builder.Default
    private double weightDecay = 0.0;
    @Builder.Default
    private double momentum = 0.9;
}
