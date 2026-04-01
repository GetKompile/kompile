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
public class EnvironmentResponse {

    // Backend info
    private boolean cpu;
    private int blasMajorVersion;
    private int blasMinorVersion;
    private int blasPatchVersion;

    // Memory
    private boolean enableBlas;
    private long maxPrimaryMemory;
    private long maxSpecialMemory;
    private long maxDeviceMemory;

    // Debug/Profiling
    private boolean verbose;
    private boolean debug;
    private boolean profiling;
    private boolean detectingLeaks;
    private boolean helpersAllowed;
    private boolean logNativeNDArrayCreation;
    private boolean checkOutputChange;
    private boolean checkInputChange;

    // Lifecycle tracking
    private boolean lifecycleTracking;
    private boolean trackViews;
    private boolean trackDeletions;
    private boolean ndArrayTracking;
    private boolean dataBufferTracking;
    private int stackDepth;
    private int reportInterval;
    private long maxDeletionHistory;

    // Performance
    private int tadThreshold;
    private int elementwiseThreshold;
    private int maxThreads;
    private int maxMasterThreads;

    // CUDA
    private boolean gpuAvailable;
    private CudaSettingsResponse cuda;

    // Triton
    private TritonSettingsResponse triton;

    // DSP
    private DspSettingsResponse dsp;
}
