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

package ai.kompile.app.diagnostics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Summary of allocation log analysis from ADR 53.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AllocationLogSummary {

    /**
     * Path to the allocation log file
     */
    private String logFilePath;

    /**
     * Whether the log file exists and is readable
     */
    private Boolean logFileExists;

    /**
     * Total number of allocations in the log
     */
    private Integer totalAllocations;

    /**
     * Number of NDArray allocations
     */
    private Integer ndarrayAllocations;

    /**
     * Number of OpContext allocations
     */
    private Integer opContextAllocations;

    /**
     * Total bytes allocated (NDArrays only)
     */
    private Long totalBytesAllocated;

    /**
     * Allocation counts by operation name
     */
    private Map<String, Integer> allocationsByOperation;

    /**
     * Top N largest allocations
     */
    private List<AllocationEntry> largestAllocations;

    /**
     * Top N most frequent allocation sites (by source location)
     */
    private Map<String, Integer> topAllocationSites;

    /**
     * Allocation rate statistics
     */
    private AllocationRateStats rateStats;

    /**
     * Error message if log parsing failed
     */
    private String error;
}
