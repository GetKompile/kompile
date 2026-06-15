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
 * Response containing all Triton GPU compiler settings.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TritonConfigResponse {
    // Build settings
    @Builder.Default
    private int tritonBuildThreads = 1;
    @Builder.Default
    private boolean tritonCacheEnabled = true;

    // Kernel tuning
    @Builder.Default
    private int tritonNumWarps = 0; // 0 = auto
    @Builder.Default
    private int tritonNumStages = 0; // 0 = auto
    @Builder.Default
    private int tritonNumCTAs = 1;
    @Builder.Default
    private int tritonMaxNreg = 0; // 0 = unset
    @Builder.Default
    private boolean tritonEnableFpFusion = true;

    // Debug settings
    @Builder.Default
    private boolean tritonVerbose = false;
    @Builder.Default
    private boolean tritonAlwaysCompile = false;
    @Builder.Default
    private boolean tritonKernelDump = false;

    // Directory settings
    private String tritonCacheDir;
    private String tritonDumpDir;
    private String tritonOverrideDir;
    private String tritonOverrideArch;
}
