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

/**
 * Specifies a single escalation route: who to assign to, SLA, and fallback.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EscalationRoute {

    /** Primary assignee (person ID or role name). */
    private String assignTo;
    /** SLA in seconds for the primary assignee to respond. */
    private int slaSeconds;
    /** Fallback assignee if the primary does not respond within the SLA. */
    private String fallbackAssignTo;
    /** When true, always escalate via this route even if the agent could auto-fix. */
    private boolean alwaysEscalate;
}
