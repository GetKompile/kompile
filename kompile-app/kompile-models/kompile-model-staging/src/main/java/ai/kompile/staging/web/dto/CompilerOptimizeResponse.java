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

import java.util.List;

/**
 * Response from a compiler optimization run.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompilerOptimizeResponse {
    private String jobId;
    private String status; // PENDING, RUNNING, COMPLETED, FAILED
    private boolean success;
    private String modelId;
    private String message;
    private String error;
    private int opsRemoved;
    private int opsFused;
    private List<String> passesApplied;
    private int beforeOpsCount;
    private int afterOpsCount;
    private int beforeVarsCount;
    private int afterVarsCount;
    private long sizeBeforeBytes;
    private long sizeAfterBytes;
    private double reductionPercent;
    private long optimizationTimeMs;
    private String backupFile;
    private boolean dryRun;
}
