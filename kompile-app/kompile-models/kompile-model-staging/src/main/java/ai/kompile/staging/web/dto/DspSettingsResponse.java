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
 * Response DTO for DSP (Data-parallel Signal Processing) runtime flags.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DspSettingsResponse {
    // GEMM / matmul optimization
    private boolean batchedGemm;
    private boolean cublasTf32;
    private boolean cublasCaptureWorkspace;
    private boolean fp16Compute;
    private boolean matmulSegmentation;

    // Cast optimization
    private boolean castElimination;
    private boolean castSinkMatmul;

    // Batch-zero optimization
    private boolean batchZero;
    private boolean batchZeroKernel;

    // Symbolic shapes
    private boolean symbolicShapes;
    private int symbolicShapeWarmup;

    // Capture pool
    private boolean capturePoolEnabled;
    private long capturePoolMaxBytes;
}
