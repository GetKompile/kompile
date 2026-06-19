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

package ai.kompile.staging.optimization;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

@Data
@Builder
@AllArgsConstructor
public class OptimizationConfig {
    @Builder.Default
    private Set<OptimizationService.OptimizationType> enabledOptimizations = EnumSet.of(
            OptimizationService.OptimizationType.UNUSED_FUNCTION,
            OptimizationService.OptimizationType.CONSTANT_FUNCTION,
            OptimizationService.OptimizationType.BROADCAST_ELIMINATION,
            OptimizationService.OptimizationType.REORDERING,
            OptimizationService.OptimizationType.ALGEBRAIC,
            OptimizationService.OptimizationType.PEEPHOLE,
            OptimizationService.OptimizationType.ARITHMETIC_CHAIN,
            OptimizationService.OptimizationType.STRENGTH_REDUCTION,
            OptimizationService.OptimizationType.IDENTITY_FUNCTION,
            OptimizationService.OptimizationType.CONCAT_SPLIT,
            OptimizationService.OptimizationType.SELECT_WHERE,
            OptimizationService.OptimizationType.REDUNDANCY_ELIMINATION,
            OptimizationService.OptimizationType.SHAPE_FUNCTION,
            OptimizationService.OptimizationType.COMMON_SUBEXPRESSION_ELIMINATION,
            OptimizationService.OptimizationType.ATTENTION_FUSION,
            OptimizationService.OptimizationType.HORIZONTAL_FUSION,
            OptimizationService.OptimizationType.MATMUL_CHAIN,
            OptimizationService.OptimizationType.ACTIVATION_FUSION,
            OptimizationService.OptimizationType.NORMALIZATION_FUSION,
            OptimizationService.OptimizationType.REMATERIALIZATION,
            OptimizationService.OptimizationType.LINEAR_FUSION
    );

    private OptimizationService.QuantizationType quantizationType;

    @Builder.Default
    private boolean quantizePerChannel = false;

    @Builder.Default
    private boolean createBackup = true;

    public OptimizationConfig() {
        // Default optimizations matching GraphOptimizer.defaultOptimizations()
        // (all passes except hardware-specific cuDNN and quantization)
        this.enabledOptimizations = EnumSet.of(
                OptimizationService.OptimizationType.UNUSED_FUNCTION,
                OptimizationService.OptimizationType.CONSTANT_FUNCTION,
                OptimizationService.OptimizationType.BROADCAST_ELIMINATION,
                OptimizationService.OptimizationType.REORDERING,
                OptimizationService.OptimizationType.ALGEBRAIC,
                OptimizationService.OptimizationType.PEEPHOLE,
                OptimizationService.OptimizationType.ARITHMETIC_CHAIN,
                OptimizationService.OptimizationType.STRENGTH_REDUCTION,
                OptimizationService.OptimizationType.IDENTITY_FUNCTION,
                OptimizationService.OptimizationType.CONCAT_SPLIT,
                OptimizationService.OptimizationType.SELECT_WHERE,
                OptimizationService.OptimizationType.REDUNDANCY_ELIMINATION,
                OptimizationService.OptimizationType.SHAPE_FUNCTION,
                OptimizationService.OptimizationType.COMMON_SUBEXPRESSION_ELIMINATION,
                OptimizationService.OptimizationType.ATTENTION_FUSION,
                OptimizationService.OptimizationType.HORIZONTAL_FUSION,
                OptimizationService.OptimizationType.MATMUL_CHAIN,
                OptimizationService.OptimizationType.ACTIVATION_FUSION,
                OptimizationService.OptimizationType.NORMALIZATION_FUSION,
                OptimizationService.OptimizationType.REMATERIALIZATION,
                OptimizationService.OptimizationType.LINEAR_FUSION
        );
        this.quantizePerChannel = false;
        this.createBackup = true;
    }

    public void enableOnly(OptimizationService.OptimizationType... types) {
        this.enabledOptimizations = EnumSet.noneOf(OptimizationService.OptimizationType.class);
        this.enabledOptimizations.addAll(Arrays.asList(types));
    }

    public void enableAll() {
        this.enabledOptimizations = EnumSet.allOf(OptimizationService.OptimizationType.class);
    }

    public void disableAll() {
        this.enabledOptimizations = EnumSet.noneOf(OptimizationService.OptimizationType.class);
    }
}
