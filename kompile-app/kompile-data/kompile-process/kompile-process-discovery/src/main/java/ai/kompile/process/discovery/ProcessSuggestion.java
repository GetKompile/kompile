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

package ai.kompile.process.discovery;

import ai.kompile.graph.reasoning.fol.grounding.GroundedElement;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A suggested process definition discovered from knowledge graph analysis.
 * Contains enough information to create a ProcessDefinition via the process engine.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessSuggestion {

    /** Unique identifier for this suggestion */
    private String id;

    /** The fact sheet this suggestion was discovered from. */
    private Long factSheetId;

    /** When this suggestion was discovered */
    private Instant discoveredAt;

    /** Suggested process name */
    private String name;

    /** Human-readable description of the discovered process */
    private String description;

    /** How the process was discovered: EMAIL_FLOW, EXCEL_COMPUTATION, DOCUMENT_PIPELINE, COMMUNITY */
    private String discoverySource;

    /** Confidence that this is a real, repeatable process (0.0 to 1.0) */
    private double confidence;

    /** Suggested phases with their steps */
    @Builder.Default
    private List<SuggestedPhase> phases = new ArrayList<>();

    /** KG node IDs that this process is derived from */
    @Builder.Default
    private List<String> sourceGraphNodeIds = new ArrayList<>();

    /** Evidence supporting this suggestion */
    @Builder.Default
    private List<String> evidence = new ArrayList<>();

    /**
     * Bayesian posterior scores from MEBN inference over the source graph nodes.
     * Maps variable name (e.g. "isRelevant(node_42)") → posterior P(TRUE | evidence).
     * Populated when process discovery is enhanced with probabilistic scoring.
     */
    @Builder.Default
    private Map<String, Double> bayesianPosteriors = new LinkedHashMap<>();

    /**
     * Bayesian prior scores (before evidence) for the same variables as posteriors.
     * Enables prior→posterior comparison visualization in the UI.
     */
    @Builder.Default
    private Map<String, Double> bayesianPriors = new LinkedHashMap<>();

    /**
     * Structured evidence with typed entries that can be rendered in the UI.
     * Each entry has a type (CAUSAL, TEMPORAL, STATISTICAL, BAYESIAN),
     * a description, and an optional numeric score.
     */
    @Builder.Default
    private List<StructuredEvidence> structuredEvidence = new ArrayList<>();

    /**
     * Child process suggestions that are sub-processes of this one.
     * For example, an "Email-driven Budget Review" process may have a
     * "Spreadsheet Computation" child process extracted from a referenced attachment.
     */
    @Builder.Default
    private List<ProcessSuggestion> childSuggestions = new ArrayList<>();

    /** ID of the parent suggestion if this is a sub-process. */
    private String parentSuggestionId;

    /** Whether this suggestion has been accepted and converted to a ProcessDefinition */
    private Boolean accepted;

    /** ID of the ProcessDefinition created from this suggestion */
    private String acceptedProcessDefinitionId;

    /**
     * KB-grounded steps produced by {@code ProcessTreeToSuggestion.convertGrounded(...)}.
     * Each element wraps a {@link SuggestedStep} with its KB verify result, calibrated
     * confidence, and StrengthBand. Populated only when grounding is active; null otherwise.
     */
    @Builder.Default
    private List<GroundedElement<SuggestedStep>> groundedSteps = new ArrayList<>();

    /**
     * Lineage reference for this process candidate, tracing it back to the graph facts,
     * mined rules, and derivation evidence that produced it.
     * Populated by {@code MiningProcessDiscoveryService.discoverForFactSheet}.
     */
    private ProcessLineage lineageRef;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SuggestedPhase {
        private String name;
        private String description;
        @Builder.Default
        private List<SuggestedStep> steps = new ArrayList<>();
        /** Earliest occurredAt across all steps in this phase */
        private LocalDateTime earliestOccurrence;
        /** Latest occurredAt across all steps in this phase */
        private LocalDateTime latestOccurrence;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SuggestedStep {
        /** Step name */
        private String name;
        /** Step type: AUTO, HUMAN, APPROVE, TOOL_CALL, EXCEL_COMPUTE, SCRIPT, HTTP_CALL */
        private String stepType;
        /** Description of what this step does */
        private String description;
        /** For TOOL_CALL: suggested tool name */
        private String toolName;
        /** For EXCEL_COMPUTE: the graph node IDs containing the spreadsheet */
        @Builder.Default
        private List<String> graphNodeIds = new ArrayList<>();
        /** Input mapping: runData keys this step reads */
        @Builder.Default
        private Map<String, String> inputMapping = Map.of();
        /** Suggested assignee (for HUMAN/APPROVE steps) */
        private String suggestedAssignee;
        /** When this step occurred in the real world */
        private LocalDateTime occurredAt;
        /**
         * Role binding derived from the knowledge graph via {@code RoleBindingExtractor}.
         * Contains the entity type (e.g. "PERSON", "DEPARTMENT") nearest to this step's
         * graph nodes, or null when no role could be derived.
         */
        private String roleBinding;
        /**
         * Per-step lineage: traces this step back to its basis facts and supporting rules.
         */
        private ProcessLineage lineageRef;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StructuredEvidence {
        /** Type of evidence: CAUSAL, TEMPORAL, STATISTICAL, BAYESIAN */
        private String type;
        /** Human-readable description */
        private String description;
        /** Optional numeric score (probability, correlation, frequency) */
        private Double score;
        /** KG node IDs that support this evidence */
        @Builder.Default
        private List<String> supportingNodeIds = new ArrayList<>();
    }

    /**
     * Lineage reference for a process or step, tracing its derivation back to basis facts and rules.
     * Populated by {@code discoverForFactSheet} so that every candidate has traceability.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessLineage {
        /** Graph node IDs that are the factual basis for this process/step. */
        @Builder.Default
        private List<String> basisNodeIds = new ArrayList<>();
        /** PSL rule texts (from MinedRuleLineage) that support this process/step. */
        @Builder.Default
        private List<String> supportingRuleTexts = new ArrayList<>();
        /** Activities from the causal arcs that back this process/step. */
        @Builder.Default
        private List<String> causalActivityPairs = new ArrayList<>();
        /** The derivation method: e.g. "INDUCTIVE_MINER", "BAYESIAN", "CAUSAL_ANALYSIS". */
        private String derivationMethod;
        /** Soft-truth value from the inferred fact store for the key activity, or null if not available. */
        private Double softTruthValue;
        /** The atom key that was queried in the KB for this step's activity. */
        private String atomKey;
    }
}
