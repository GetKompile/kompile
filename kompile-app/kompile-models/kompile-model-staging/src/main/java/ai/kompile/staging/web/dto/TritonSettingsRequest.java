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
 * Request DTO for updating Triton compiler settings.
 * Nullable fields allow partial updates.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TritonSettingsRequest {
    // Compiler settings
    private Integer buildThreads;
    private Boolean cacheEnabled;
    private Boolean verbose;
    private Boolean alwaysCompile;
    private Integer numWarps;
    private Integer numStages;
    private Integer numCTAs;
    private Boolean enableFpFusion;
    private String cacheDir;
    private String dumpDir;
    private String overrideArch;
    private Boolean disableLineInfo;
    private Integer maxNreg;
    private Integer attentionBlockN;

    // Compilation mode
    private Boolean compileAll;
    private String includeTypes;
    private String excludeOps;

    // Section fusion
    private Boolean sectionFusion;
    private Boolean fusionScoring;
    private Float fusionMinScore;

    // Graph capture
    private Boolean graphCapture;
    private Boolean allowFallbackCapture;
    private Boolean forceRecapture;
    private Integer captureMinExec;

    // Arg table
    private Boolean consolidatedArgTable;
    private Boolean argDirtyTracking;

    // Cooperative launch
    private Boolean cooperativeLaunch;
    private Integer coopTargetBlocks;

    // Debug/verification
    private Boolean skipKernels;
    private Boolean verifyKernels;
    private Boolean verifyFullSnapshot;
    private Boolean dumpSections;
    private Boolean dumpArgs;

    // Subsegment limits
    private Integer maxSubsegmentOps;
    private Integer maxSubsegmentSections;

    // Segment fusion optimization flags
    private Boolean fuseIdentityShapes;
    private Boolean fuseCastChains;
    private Boolean specializePermuteSeq1;
    private Boolean fusedMatmul;
    private Boolean fuseAttentionNeighborhoods;
}
