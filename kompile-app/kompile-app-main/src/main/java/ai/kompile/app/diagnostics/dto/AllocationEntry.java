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

/**
 * Represents a single allocation entry from the ADR 53 allocation log.
 * Can be either an NDArray allocation or an OpContext allocation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AllocationEntry {

    /**
     * Type of allocation: "NDARRAY" or "OPCONTEXT"
     */
    private String allocationType;

    /**
     * Sequential allocation number
     */
    private Long allocationNumber;

    /**
     * Pointer address of the allocated object
     */
    private String pointer;

    /**
     * Size in bytes (NDArray only)
     */
    private Long sizeBytes;

    /**
     * Data type (NDArray only)
     */
    private String dataType;

    /**
     * Shape array (NDArray only)
     */
    private List<Long> shape;

    /**
     * Whether this is a view (NDArray only)
     */
    private Boolean isView;

    /**
     * Operation name that triggered the allocation
     */
    private String operation;

    /**
     * Number of inputs (OpContext only)
     */
    private Integer numInputs;

    /**
     * Number of outputs (OpContext only)
     */
    private Integer numOutputs;

    /**
     * Timestamp (nanoseconds since epoch)
     */
    private Long timestamp;

    /**
     * Thread ID that performed the allocation
     */
    private String threadId;

    /**
     * C++ stack trace frames
     */
    private List<String> stackTrace;

    /**
     * Raw log entry text (for debugging)
     */
    private String rawEntry;
}
