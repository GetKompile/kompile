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

import java.io.Serializable;
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
public class ProcessSuggestion implements Serializable {

    private static final long serialVersionUID = 1L;

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

    /**
     * Raw (uncalibrated) conformance signal — fitness × precision — kept alongside the fused
     * confidence so accept/dismiss outcomes can (re)fit the Platt calibrator against the signal
     * that actually produced the suggestion.
     */
    private Double rawConformanceScore;

    /**
     * The business-process description a human reads: a coherent narrative of the flow, its
     * cases, roles, entailed orderings, and caveats. Always populated — deterministically by
     * {@code ProcessNarrator} at mining time, upgraded to LLM prose by the app-side narration
     * service when a chat model is configured. The LLM only NARRATES the mined structure; it is
     * never load-bearing, and un-grounded LLM output falls back to the template.
     */
    private String narrative;

    /** Provenance of {@link #narrative}: {@code "TEMPLATE"} or the LLM model identifier. */
    private String narrativeSource;

    /**
     * Learned acceptance likelihood in (0,1) from the logistic re-ranker fitted to accept/dismiss
     * history over the suggestion's signal features. Null until enough labeled outcomes exist —
     * the fused {@link #confidence} then stands alone. Never replaces confidence; it RANKS.
     */
    private Double learnedScore;

    /**
     * The FULLY DESCRIBED business process: agent-synthesized markdown produced by an MCP-tooled
     * agent that explored the fact sheet's graph (node titles, relation metadata, ontology, KB
     * facts) around the mined skeleton. Grounding-gated like the narrative — every mined step must
     * appear, nothing invented — and null until synthesis is requested.
     */
    private String processDocument;

    /** Which agent produced {@link #processDocument} (registry agent name). */
    private String processDocumentSource;

    /** Suggested phases with their steps */
    @Builder.Default
    private List<SuggestedPhase> phases = new ArrayList<>();

    /** KG node IDs that this process is derived from */
    @Builder.Default
    private List<String> sourceGraphNodeIds = new ArrayList<>();

    /** KG relation IDs that directly support this process candidate. */
    @Builder.Default
    private List<String> sourceGraphRelationIds = new ArrayList<>();

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
     * Stable identity of the BUSINESS PROCESS across mining generations: a re-mine whose activity
     * set matches a predecessor (Jaccard through alias unification) carries its key forward, so
     * "the same process at different times" is one lineage instead of disconnected snapshots.
     * Minted from the first sighting's suggestion id.
     */
    private String processKey;

    /** The predecessor generation this suggestion was matched to (drift is diffed against it). */
    private String previousSuggestionId;

    /**
     * Set on a PENDING mined suggestion when a newer generation replaces it (mark, never delete —
     * the lineage stays walkable). Null successor = the process stopped being discovered.
     */
    private String supersededBySuggestionId;

    /** When this suggestion stopped being the head of its lineage; null = current head. */
    private Instant supersededAt;

    /**
     * When the matched predecessor was ACCEPTED: the live ProcessDefinition this suggestion
     * proposes to revise — the accept path then bumps that definition's version instead of
     * creating an unrelated one.
     */
    private String revisesProcessDefinitionId;

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

    /** Stable id of the persisted {@code ReasoningTrace} explaining this mined suggestion. */
    private String reasoningTraceId;

    /** Artifact/model name used when this suggestion's trace is bundled into a {@code .kgraph}. */
    private String reasoningTraceArtifactName;

    /** Rank among candidates generated from the same graph snapshot (1 is strongest). */
    private Integer reasoningRank;

    /** Graph-to-event projection used to construct this candidate. */
    private String reasoningProjection;

    /** Generic relation family or extraction path that produced this candidate. */
    private String reasoningFamily;

    /** Mean HybridReasoner activation across candidate activities. */
    private Double hybridScore;

    /**
     * Detailed HybridReasoner interpretation behind {@link #hybridScore}. The scalar remains for
     * compatibility; this object preserves engine, structural, semantic, and per-activity scores.
     */
    private HybridReasoningDetails hybridReasoning;

    /** Fused entailment expectation for candidate precedence relations. */
    private Double entailmentScore;

    /** Compact process-mining statistics retained for clients and accepted definitions. */
    private Integer processCaseCount;
    private Integer processActivityCount;
    private Integer directlyFollowsCount;
    private Integer acceptedPrecedenceCount;
    private Integer entailedOnlyPrecedenceCount;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HybridReasoningDetails implements Serializable {
        private static final long serialVersionUID = 1L;

        private String interpretation;
        private double score;
        private double pslScore;
        private double bayesianScore;
        private double pslStructuralScore;
        private double bayesianStructuralScore;
        private double semanticScore;
        private String semanticMode;
        private String embeddingSource;
        private String embeddingModel;
        private int contextualizedActivityCount;
        private int directlyEmbeddedActivityCount;
        private int inferredEmbeddingActivityCount;
        private int embeddedActivityCount;
        private int activityCount;
        private double structuralWeight;
        private double semanticWeight;
        private boolean pslAvailable;
        private boolean bayesianAvailable;
        @Builder.Default
        private List<HybridActivityReasoning> activities = new ArrayList<>();
        @Builder.Default
        private List<String> warnings = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HybridActivityReasoning implements Serializable {
        private static final long serialVersionUID = 1L;

        private String activity;
        private double score;
        private double pslScore;
        private double bayesianScore;
        private double pslStructuralScore;
        private double bayesianStructuralScore;
        private double semanticScore;
        private boolean embedded;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SuggestedPhase implements Serializable {
        private static final long serialVersionUID = 1L;

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
    public static class SuggestedStep implements Serializable {
        private static final long serialVersionUID = 1L;

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
        /** Human-readable titles corresponding 1:1 with {@code graphNodeIds} (empty when unresolved). */
        @Builder.Default
        private List<String> graphNodeTitles = new ArrayList<>();
        /** Input mapping: runData keys this step reads */
        @Builder.Default
        private Map<String, String> inputMapping = Map.of();
        /** Suggested assignee (for HUMAN/APPROVE steps) */
        private String suggestedAssignee;
        /** When this step occurred in the real world */
        private LocalDateTime occurredAt;
        /**
         * Role binding derived via {@code RoleBindingExtractor}: the observed majority actor
         * ("bob", "Procurement Approver"), a KB query answer, or a name-keyword role
         * ("APPROVER"), or "UNASSIGNED" when no evidence exists.
         */
        private String roleBinding;
        /**
         * Where {@code roleBinding} came from: OBSERVED (majority actor tallied from the crawl's
         * actor relations), KB (knowledge-base query), HEURISTIC (activity-name keywords), or
         * null for UNASSIGNED.
         */
        private String roleSource;
        /**
         * Per-step lineage: traces this step back to its basis facts and supporting rules.
         */
        private ProcessLineage lineageRef;
        /**
         * Names of steps this step depends on — the mined/entailed control flow. Populated from the
         * process tree's sequence semantics and from high-confidence entailed precedence pairs; the
         * accept path translates these names to step ids on the engine's
         * {@code ProcessStep.dependsOn}, which the executor enforces.
         */
        @Builder.Default
        private List<String> dependsOn = new ArrayList<>();
        /**
         * SpEL routing stub for steps inside a mined XOR (choice) branch, e.g.
         * {@code #take_approval != false} — default-TRUE (a missing runData variable is null, so
         * the branch runs unless an operator sets the flag false). Copied onto the engine's
         * {@code ProcessStep.conditionExpression} on accept.
         */
        private String conditionExpression;
        /**
         * Human-readable provenance of {@code conditionExpression}: which choice branch this step
         * belongs to and its observed case share, e.g. "Choice: Approval branch — 5 of 8 cases".
         */
        private String conditionLabel;
        /** Control IDs observed on the graph events backing this step. */
        @Builder.Default
        private List<String> controlIds = new ArrayList<>();
        /** Roles inferred from event/relation/entity attributes that may execute or approve this step. */
        @Builder.Default
        private List<String> requiredRoles = new ArrayList<>();
        /** Permissions inferred from event/relation/entity attributes. */
        @Builder.Default
        private List<String> requiredPermissions = new ArrayList<>();
        /** Graph-derived policy attributes such as thresholds, routing policies, and action labels. */
        @Builder.Default
        private Map<String, Object> metadata = new LinkedHashMap<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StructuredEvidence implements Serializable {
        private static final long serialVersionUID = 1L;

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
    public static class ProcessLineage implements Serializable {
        private static final long serialVersionUID = 1L;

        /** Graph node IDs that are the factual basis for this process/step. */
        @Builder.Default
        private List<String> basisNodeIds = new ArrayList<>();
        /** Human-readable titles corresponding 1:1 with {@code basisNodeIds} (empty when unresolved). */
        @Builder.Default
        private List<String> basisNodeTitles = new ArrayList<>();
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
