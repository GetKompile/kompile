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

import java.util.Map;

/**
 * Escalation routing table for an {@link AgentSpec}.
 * Maps variance pattern IDs to specific escalation routes, with fallback defaults.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EscalationPolicy {

    /** Pattern-ID to escalation route mapping. Key is the variance pattern identifier. */
    private Map<String, EscalationRoute> routes;
    /** Route used when no specific pattern route matches. */
    private EscalationRoute defaultRoute;
    /** Route used when the agent itself encounters an unrecoverable error. */
    private EscalationRoute onAgentFailure;
}
