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

package ai.kompile.process.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Declares what the audit trail must capture for each agent action and escalation event.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AuditTrailSchema {

    /**
     * Fields to record on every agent action.
     * Example: ["before", "after", "pattern_id", "evidence_source"].
     */
    private List<String> perActionFields;
    /** Fields to record for every escalation event. */
    private List<String> perEscalationFields;
    /** When true, the SHA-256 hash of inputs is recorded on each audit entry. */
    private boolean includeInputHash;
    /** When true, the SHA-256 hash of outputs is recorded on each audit entry. */
    private boolean includeOutputHash;
    /** When true, a snapshot of the evidence relied upon is embedded in the audit entry. */
    private boolean includeEvidenceSnapshot;
}
