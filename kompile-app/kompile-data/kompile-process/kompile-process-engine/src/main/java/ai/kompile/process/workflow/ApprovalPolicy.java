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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Defines who can approve, how (full/partial), timeout behaviour, and delegation.
 * This is the HITL protocol contract embedded in an APPROVE-type {@link ProcessStep}.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ApprovalPolicy {

    /** Ordered list of approver identifiers (person IDs or role names) by priority. */
    private List<String> approverPool;
    private ApprovalMode mode;
    /** When true, the approver may accept some items and reject others in the same batch. */
    private boolean allowPartialApproval;
    /** Items whose absolute value is below this threshold are auto-approved (in dollars or base units). */
    private int dollarThreshold;
    /** Maximum number of auto-corrections the agent may apply per batch without human review. */
    private int maxAutoCorrections;
    /** Pattern-specific escalation overrides, e.g., "channel mismatch → always M.Chen". */
    private List<EscalationOverride> escalationOverrides;
    private DelegationPolicy delegation;
}
