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
import java.util.Map;

/**
 * The full specification for a workflow agent.
 * Designed to survive model upgrades — the spec, not the model, is the deliverable.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentSpec {

    private String id;
    /** Human-readable agent name, e.g., "Triage Agent". */
    private String name;
    private int version;
    private String ontologySchemaId;
    /** Ontology version snapshot this agent was validated against. */
    private int ontologyVersion;
    /**
     * Prompt template with {@code {placeholder}} tokens for ontology-bound values.
     * Example: "You are a triage agent working with the {channel_taxonomy} entity."
     */
    private String promptTemplate;
    private List<AgentTool> tools;
    /** ID of the golden-cycle test suite used to validate this agent spec. */
    private String evalSuiteId;
    private EscalationPolicy escalation;
    private AuditTrailSchema auditSchema;
    private AgentPermissions permissions;
    /** Runtime configuration parameters (model name, temperature, etc.). */
    private Map<String, Object> parameters;
}
