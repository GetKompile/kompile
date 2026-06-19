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

import ai.kompile.modelmanager.registry.ModelMetadata;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptimizationResult {
    private boolean success;
    private String modelId;
    private String message;
    private String error;
    private long optimizationTimeMs;
    private String backupFile;
    private List<String> appliedOptimizations;
    private ModelMetadata.OptimizationStats stats;

    public static OptimizationResult failure(String modelId, String error) {
        return OptimizationResult.builder()
                .success(false)
                .modelId(modelId)
                .error(error)
                .build();
    }
}
