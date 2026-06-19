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

package ai.kompile.process.controls;

import ai.kompile.process.ontology.ProvenanceCitation;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A formal control assertion (SOX/J-SOX compatible).
 * Executable as part of the workflow — fires at defined points and produces
 * immutable {@link ControlAttestation} records.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ControlDefinition {

    /** Control identifier, e.g., "C-01". */
    private String id;
    /** Human-readable control name, e.g., "Trial Balance Ties". */
    private String name;
    private String description;
    /** HARD gates halt the workflow on failure; SOFT gates warn and continue. */
    private ControlGateType gateType;
    /**
     * Executable assertion expression, e.g., "TB.debit_total == TB.credit_total".
     * CEL/SpEL-compatible.
     */
    private String expression;
    /** Step ID after which this control fires. */
    private String triggerAfterStep;
    /** Data keys required to evaluate the expression. */
    private List<String> inputKeys;
    /** Regulatory reference, e.g., "ICFR C-04", "SOX 404". */
    private String regulatoryReference;
    private ControlSeverity severity;
    private ControlFailurePolicy onFailure;
    private List<ProvenanceCitation> provenance;
}
