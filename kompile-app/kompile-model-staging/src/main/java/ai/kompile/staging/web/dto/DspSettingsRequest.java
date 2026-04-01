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
 *  limitations under the License.
 */

package ai.kompile.staging.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for updating DSP (Data-parallel Signal Processing) runtime flags.
 * Nullable fields allow partial updates.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DspSettingsRequest {
    // GEMM / matmul optimization
    private Boolean batchedGemm;
    private Boolean cublasTf32;
    private Boolean cublasCaptureWorkspace;
    private Boolean fp16Compute;
    private Boolean matmulSegmentation;

    // Cast optimization
    private Boolean castElimination;
    private Boolean castSinkMatmul;

    // Batch-zero optimization
    private Boolean batchZero;
    private Boolean batchZeroKernel;

    // Symbolic shapes
    private Boolean symbolicShapes;
    private Integer symbolicShapeWarmup;

    // Capture pool
    private Boolean capturePoolEnabled;
    private Long capturePoolMaxBytes;
}
