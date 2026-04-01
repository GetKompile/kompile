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
 * Status of an asynchronous compilation job.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompilationJobStatus {
    private String jobId;
    private String modelId;
    private String status; // QUEUED, COMPILING, COMPLETED, FAILED, CANCELLED
    private String compilationMode; // REDUCE_OVERHEAD, SPLIT_STITCH, MAX_AUTOTUNE
    private String executionMode; // AUTO, SLOT_BY_SLOT, CUDA_GRAPHS, NVRTC_JIT, PTX_JIT, TRITON
    private int progressPercent;
    private String currentPhase;
    private String message;
    private String error;
    private String startedAt;
    private String completedAt;
    private long elapsedMs;
    private CompilerOptimizeResponse result;
}
