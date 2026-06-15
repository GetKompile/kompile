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

package ai.kompile.process.workflow;

import ai.kompile.process.ontology.ProvenanceCitation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Overrides the default escalation routing for a specific variance pattern.
 * Example: "channel mismatch → always route to M.Chen regardless of auto-fix capability".
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EscalationOverride {

    /** ID of the variance pattern that triggers this override. */
    private String patternId;
    /** Person or role identifier to route to when the pattern fires. */
    private String escalateTo;
    /** When true, always escalate even if the agent can auto-fix. */
    private boolean alwaysEscalate;
    private String reason;
    /** Where this rule was discovered, e.g., "M.Chen interview t.11". */
    private ProvenanceCitation source;
}
