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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TritonSettingsResponse {
    // Compiler settings
    private int buildThreads;
    private boolean cacheEnabled;
    private boolean verbose;
    private boolean alwaysCompile;
    private int numWarps;
    private int numStages;
    private int numCTAs;
    private boolean enableFpFusion;
    private String cacheDir;
    private String dumpDir;
    private String overrideArch;
    private boolean disableLineInfo;
    private int maxNreg;
    private int attentionBlockN;

    // Compilation mode
    private boolean compileAll;
    private String includeTypes;
    private String excludeOps;

    // Section fusion
    private boolean sectionFusion;
    private boolean fusionScoring;
    private float fusionMinScore;

    // Graph capture
    private boolean graphCapture;
    private boolean allowFallbackCapture;
    private boolean forceRecapture;
    private int captureMinExec;

    // Arg table
    private boolean consolidatedArgTable;
    private boolean argDirtyTracking;

    // Cooperative launch
    private boolean cooperativeLaunch;
    private int coopTargetBlocks;

    // Debug/verification
    private boolean skipKernels;
    private boolean verifyKernels;
    private boolean verifyFullSnapshot;
    private boolean dumpSections;
    private boolean dumpArgs;

    // Subsegment limits
    private int maxSubsegmentOps;
    private int maxSubsegmentSections;

    // Segment fusion optimization flags
    private boolean fuseIdentityShapes;
    private boolean fuseCastChains;
    private boolean specializePermuteSeq1;
    private boolean fusedMatmul;
    private boolean fuseAttentionNeighborhoods;
}
