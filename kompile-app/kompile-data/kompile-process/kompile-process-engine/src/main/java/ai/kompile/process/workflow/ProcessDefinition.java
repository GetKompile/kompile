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

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A versioned, executable process definition.
 * This is the generalized equivalent of the 14-step FP&amp;A workflow.
 * Binds to an ontology version and defines steps, gates, controls, and agent assignments.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProcessDefinition {

    private String id;
    private String name;
    private int version;
    private String ontologySchemaId;
    /** Immutable snapshot of the ontology version bound at process approval time. */
    private int ontologyVersion;
    private ProcessStatus status;
    private String approvedBy;
    private Instant approvedAt;
    private List<ProcessPhase> phases;
    private List<ControlDefinitionRef> controls;
    /** References to AgentSpec IDs participating in this process. */
    private List<String> agentSpecs;
    private Map<String, Object> metadata;

    // ── Fact Sheet Binding ─────────────────────────────────────────────────

    /** The fact sheet this process was discovered from or belongs to. */
    private Long factSheetId;

    // ── Discovery Provenance ───────────────────────────────────────────────

    /** ID of the process suggestion that originated this definition. */
    private String sourceSuggestionId;
    /** Knowledge graph node IDs that were used as evidence during process discovery. */
    private List<String> sourceGraphNodeIds;
    /** Confidence score (0.0–1.0) assigned during automated process discovery. */
    private double discoveryConfidence;

    // ── Process Hierarchy ──────────────────────────────────────────────────

    /** ID of the parent process that this process is a sub-process of. */
    private String parentProcessId;

    /** IDs of child (sub) processes that are nested within this process. */
    private List<String> childProcessIds;

    /**
     * Lightweight reference to a {@link ai.kompile.process.controls.ControlDefinition}
     * used within a process definition to avoid circular dependencies.
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ControlDefinitionRef {
        private String controlId;
        private String triggerAfterStep;
    }
}
