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

package ai.kompile.crawl.graph.passes;

import ai.kompile.core.crawl.graph.ExtractionMode;
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.GraphExtractionConfig.DecomposedPromptTier;
import ai.kompile.core.crawl.graph.GraphExtractionValidationPolicy;
import ai.kompile.core.crawl.graph.PropositionAtomizationConfig;
import ai.kompile.core.evaluation.graph.GraphDecisionTraceSink;
import ai.kompile.core.graphrag.GraphConstructor.ExtractionTaskContext;
import ai.kompile.core.graphrag.format.GraphExtractionSchema;
import ai.kompile.core.graphrag.format.GraphExtractionValidator;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.passes.ClaimCandidateProvider;
import ai.kompile.core.graphrag.passes.DecomposedExtractionPipeline;
import ai.kompile.core.graphrag.passes.DecomposedExtractionPipeline.LlmCaller;
import ai.kompile.core.graphrag.passes.DecomposedExtractionPipeline.Options;
import ai.kompile.core.graphrag.passes.DecomposedExtractionPipeline.Outcome;
import ai.kompile.core.graphrag.passes.DecomposedExtractionPipeline.PassStats;
import ai.kompile.core.graphrag.passes.PassContext;
import ai.kompile.core.llm.ModelCapability;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.StringJoiner;

/**
 * Runs the decomposed extraction passes for one crawl chunk and hands back the same
 * schema-shaped JSON a single monolithic prompt would have produced.
 *
 * <p>That shape is the whole point of the seam: the orchestrator substitutes this for its one LLM
 * call and everything after it — JSON extraction, validation, {@code toGraph}, merge, persistence,
 * telemetry — runs untouched. What changes is upstream of the JSON: instead of one prompt asking a
 * model to segment, resolve identity, judge assertability, pick relation types and mint ids in a
 * single breath, each of those is a bounded decision over candidates the engine retrieved, checked
 * against the chunk text before anything is projected.</p>
 */
public final class DecomposedExtractionExecutor {

    private static final Logger log = LoggerFactory.getLogger(DecomposedExtractionExecutor.class);
    private static final ModelCapability DEFAULT_EXECUTION_CAPABILITY =
            new ModelCapability(null, 4_096, 1_024, true);
    private static final double DEFAULT_CHARS_PER_TOKEN = 3.5d;

    /** Immutable context-derived prompt decision recorded into every pass context. */
    public record PromptProfile(DecomposedPromptTier tier,
                                int budgetTokens,
                                int executableContextTokens,
                                int outputReserveTokens,
                                double charsPerToken,
                                String source) {

        public int maxPromptChars() {
            return Math.max(1, (int) Math.floor(budgetTokens * charsPerToken));
        }
    }

    private record TierLimits(int entities, int relations, int claims,
                              int eventChars, int referenceChars) {
    }

    /** Result of one chunk's decomposed extraction. */
    public record Result(String json, Outcome outcome) {

        /** True when the passes produced projectable JSON. */
        public boolean usable() {
            return json != null && !json.isBlank();
        }

        /** Compact per-pass accounting for the crawl log. */
        public String summary() {
            if (outcome == null) {
                return "no outcome";
            }
            StringJoiner joiner = new StringJoiner(" ");
            for (PassStats stats : outcome.stats()) {
                joiner.add(stats.passId() + "=" + stats.accepted() + "/" + stats.proposed()
                        + (stats.abstained() > 0 ? " abstain:" + stats.abstained() : "")
                        + (stats.spanRejected() > 0 ? " span:" + stats.spanRejected() : "")
                        + (stats.outOfVocabulary() > 0 ? " oov:" + stats.outOfVocabulary() : "")
                        + (stats.failures() > 0 ? " fail:" + stats.failures() : ""));
            }
            joiner.add("projected=" + outcome.projectedItems());
            if (!outcome.notes().isEmpty()) {
                joiner.add("notes=" + outcome.notes().size());
            }
            return joiner.toString();
        }
    }

    /** True when this config asks for decomposed extraction. */
    public static boolean isEnabled(GraphExtractionConfig config) {
        return config != null && config.getExtractionMode() == ExtractionMode.DECOMPOSED;
    }

    /** Translates the crawl config's knobs into pipeline options. */
    public static Options optionsFrom(GraphExtractionConfig config) {
        if (config == null) {
            PropositionAtomizationConfig atomization = PropositionAtomizationConfig.defaults();
            return Options.defaults()
                    .withSplitMentionsByEndpoint(true)
                    .withSplitRelationDecision(true)
                    .withSplitClaimsByCandidate(true)
                    .withPropositionAtomization(atomization.effectiveMode(),
                            atomization.effectiveMaxEventChars(),
                            atomization.effectiveReferenceContextChars());
        }
        Options defaults = Options.defaults();
        PropositionAtomizationConfig atomization = config.getDecomposedAtomization();
        return new Options(
                propositionLimit(config.getDecomposedMaxPropositions()),
                positive(config.getDecomposedEntityCandidateLimit(), defaults.entityCandidateLimit()),
                positive(config.getDecomposedRelationCandidateLimit(), defaults.relationCandidateLimit()),
                positive(config.getDecomposedClaimCandidateLimit(), defaults.claimCandidateLimit()),
                config.isDecomposedRequireEvidenceSpans(),
                config.isDecomposedClaimMatching(),
                config.isDecomposedSplitMentionsByEndpoint(),
                defaults.inferFocusedMentionOperation(),
                true,
                true,
                atomization.effectiveMode()
                        != PropositionAtomizationConfig.Mode.WHOLE_CHUNK,
                atomization.effectiveMode(),
                atomization.effectiveMaxEventChars(),
                atomization.effectiveReferenceContextChars());
    }

    /** Applies the resolved context tier to bounded presentation, never to parsing or validation. */
    public static Options optionsFrom(GraphExtractionConfig config, PromptProfile profile) {
        Options configured = optionsFrom(config);
        TierLimits limits = limitsFor(profile == null ? DecomposedPromptTier.STANDARD : profile.tier());
        return new Options(
                configured.maxPropositions(),
                Math.min(configured.entityCandidateLimit(), limits.entities()),
                Math.min(configured.relationCandidateLimit(), limits.relations()),
                Math.min(configured.claimCandidateLimit(), limits.claims()),
                configured.requireEvidenceSpans(),
                configured.claimMatchingEnabled(),
                configured.splitMentionsByEndpoint(),
                configured.inferFocusedMentionOperation(),
                configured.splitRelationDecision(),
                configured.splitClaimsByCandidate(),
                configured.splitPropositionsBySourceEvent(),
                configured.propositionAtomizationMode(),
                Math.min(configured.propositionMaxEventChars(), limits.eventChars()),
                Math.min(configured.propositionReferenceContextChars(), limits.referenceChars()));
    }

    /**
     * Resolves project overrides against the actual executable model context. Architecture metadata
     * may be larger, but it cannot authorize a prompt the loaded serving profile cannot execute.
     */
    public static PromptProfile promptProfileFrom(GraphExtractionConfig config,
                                                  ModelCapability modelCapability) {
        ModelCapability capability = modelCapability == null
                ? DEFAULT_EXECUTION_CAPABILITY : modelCapability;
        int availableTokens = Math.max(1,
                capability.maxInputChars() / ModelCapability.CHARS_PER_TOKEN);
        Integer budgetOverride = config == null ? null : config.getDecomposedPromptBudgetTokens();
        if (budgetOverride != null && budgetOverride <= 0) {
            throw new IllegalArgumentException(
                    "decomposedPromptBudgetTokens must be positive when specified");
        }
        if (budgetOverride != null && budgetOverride > availableTokens) {
            throw new IllegalArgumentException("decomposedPromptBudgetTokens=" + budgetOverride
                    + " exceeds executable prompt budget " + availableTokens
                    + " for model context=" + capability.contextTokens()
                    + " and output reserve=" + capability.maxOutputTokens());
        }
        int budgetTokens = budgetOverride != null ? budgetOverride : availableTokens;
        DecomposedPromptTier maximum = richestCompatibleTier(
                capability.contextTokens(), budgetTokens);
        if (maximum == null) {
            throw new IllegalArgumentException("Executable model context=" + capability.contextTokens()
                    + " leaves only " + budgetTokens + " prompt tokens; decomposed extraction requires "
                    + "at least a 1024-token context and a 512-token prompt budget");
        }

        DecomposedPromptTier requested = config == null
                ? DecomposedPromptTier.AUTO : config.getDecomposedPromptTier();
        DecomposedPromptTier selected = requested == DecomposedPromptTier.AUTO ? maximum : requested;
        if (tierRank(selected) > tierRank(maximum)) {
            throw new IllegalArgumentException("decomposedPromptTier=" + selected
                    + " does not fit executable context=" + capability.contextTokens()
                    + " and prompt budget=" + budgetTokens + "; maximum compatible tier is " + maximum);
        }
        double charsPerToken = config != null && config.getExtractionCharsPerToken() != null
                && config.getExtractionCharsPerToken() > 0
                ? config.getExtractionCharsPerToken() : DEFAULT_CHARS_PER_TOKEN;
        String source = budgetOverride != null && requested != DecomposedPromptTier.AUTO
                ? "PROJECT_TIER_AND_BUDGET"
                : budgetOverride != null ? "PROJECT_BUDGET"
                : requested != DecomposedPromptTier.AUTO ? "PROJECT_TIER"
                : "MODEL_CAPABILITY";
        return new PromptProfile(selected, budgetTokens, capability.contextTokens(),
                capability.maxOutputTokens(), charsPerToken, source);
    }

    /**
     * Runs every pass over {@code chunkText}.
     *
     * <p>Never throws: a pass that fails is already contained by the pipeline, and a failure to
     * serialize is reported as an unusable result so the caller can treat it exactly like a bad
     * model response and take its normal retry path.</p>
     *
     * @param chunkText  the chunk as it would have been sent to the monolithic prompt
     * @param chunkId    id of the chunk being extracted
     * @param documentId id of the document the chunk came from
     * @param config     crawl graph-extraction config
     * @param schema     resolved project schema, used for the admissible relation vocabulary
     * @param policy     validation policy, whose relation signatures constrain that vocabulary
     * @param targetGraph in-run graph that supplies entity and claim candidates
     * @param caller     dispatch seam, one call per pass
     */
    public Result extract(String chunkText,
                          String chunkId,
                          String documentId,
                          GraphExtractionConfig config,
                          GraphSchema schema,
                          GraphExtractionValidationPolicy policy,
                          Graph targetGraph,
                          LlmCaller caller) {
        return extract(chunkText, chunkId, documentId, config, schema, policy, targetGraph, caller,
                null, null);
    }

    /** Context-aware form used by entity-partition extraction. */
    public Result extract(String chunkText,
                          String chunkId,
                          String documentId,
                          GraphExtractionConfig config,
                          GraphSchema schema,
                          GraphExtractionValidationPolicy policy,
                          Graph targetGraph,
                          LlmCaller caller,
                          ExtractionTaskContext taskContext) {
        return extract(chunkText, chunkId, documentId, config, schema, policy, targetGraph, caller,
                taskContext, null);
    }

    /** Context-aware form with the selected serving model's effective capability. */
    public Result extract(String chunkText,
                          String chunkId,
                          String documentId,
                          GraphExtractionConfig config,
                          GraphSchema schema,
                          GraphExtractionValidationPolicy policy,
                          Graph targetGraph,
                          LlmCaller caller,
                          ExtractionTaskContext taskContext,
                          ModelCapability modelCapability) {
        return extract(chunkText, chunkId, documentId, config, schema, policy, targetGraph, caller,
                taskContext, modelCapability, GraphDecisionTraceSink.noop());
    }

    /** Full production form with optional graph-decision diagnostics. */
    public Result extract(String chunkText,
                          String chunkId,
                          String documentId,
                          GraphExtractionConfig config,
                          GraphSchema schema,
                          GraphExtractionValidationPolicy policy,
                          Graph targetGraph,
                          LlmCaller caller,
                          ExtractionTaskContext taskContext,
                          ModelCapability modelCapability,
                          GraphDecisionTraceSink traceSink) {
        if (chunkText == null || chunkText.isBlank()) {
            return new Result(null, null);
        }

        PromptProfile promptProfile = promptProfileFrom(config, modelCapability);

        double minScore = config != null ? config.getEffectiveCandidateMinScore() : 0;
        GraphEntityCandidateProvider entities =
                new GraphEntityCandidateProvider(targetGraph, minScore, traceSink);
        SchemaRelationCandidateProvider relations =
                SchemaRelationCandidateProvider.from(config, schema, policy);
        ClaimCandidateProvider claims = config == null || config.isDecomposedClaimMatching()
                ? new GraphClaimCandidateProvider(targetGraph)
                : ClaimCandidateProvider.none();

        if (relations.isEmpty()) {
            // With no admissible type list the relation pass has nothing to choose from, and an
            // open vocabulary is exactly what decomposition is meant to remove. Say so once — the
            // chunk still yields entities and propositions.
            log.debug("Decomposed extraction for chunk {} has no configured relation vocabulary; "
                    + "relation proposals will be schema gaps", chunkId);
        }

        String taskGraphRevision = taskContext == null ? null : taskContext.graphRevision();
        String graphRevision = taskGraphRevision != null && !taskGraphRevision.isBlank()
                ? taskGraphRevision : graphId(targetGraph);
        PassContext context = PassContext.forChunk(chunkId, documentId, chunkText)
                .withGraph(graphRevision, parentGraphId(targetGraph))
                .withSchema(GraphExtractionSchema.SCHEMA_VERSION,
                        config != null ? config.getModelName() : null)
                .withSchemaEntityTypes(schemaEntityTypes(schema, policy))
                .withPin("promptTier", promptProfile.tier().name())
                .withPin("promptBudgetTokens", String.valueOf(promptProfile.budgetTokens()))
                .withPin("promptBudgetSource", promptProfile.source())
                .withPin("executableContextTokens",
                        String.valueOf(promptProfile.executableContextTokens()));
        if (taskContext != null) {
            context = context.withPartition(taskContext.partitionId())
                    .withPin("corpusSnapshot", taskContext.corpusSnapshotId())
                    .withPin("subjects", String.join(" | ", taskContext.subjects()))
                    .withPin("discoveryChannel", taskContext.discoveryChannel())
                    .withPin("evidenceReason", taskContext.evidenceReason())
                    .withPin("evidenceConfidence", taskContext.evidenceConfidence() == null
                            ? null : String.valueOf(taskContext.evidenceConfidence()))
                    .withGraphContext(taskContext.graphContext())
                    .withConceptHints(taskContext.conceptHints())
                    .withSourceSpans(taskContext.sourceSpans());
        }

        Outcome outcome = new DecomposedExtractionPipeline(
                entities, relations, claims, optionsFrom(config, promptProfile))
                .run(context, budgetedCaller(caller, promptProfile));

        if (outcome.projectedItems() == 0 && hasFailures(outcome)) {
            // Nothing projected *and* a pass failed outright is a dispatch problem, not a chunk
            // without facts. Report it unusable so the caller takes its normal bad-response path
            // instead of recording an empty extraction as a successful one.
            log.debug("Decomposed extraction for chunk {} projected nothing after pass failures", chunkId);
            return new Result(null, outcome);
        }

        try {
            return new Result(GraphExtractionValidator.toJson(outcome.result()), outcome);
        } catch (JsonProcessingException e) {
            log.warn("Decomposed extraction produced an unserializable result for chunk {}: {}",
                    chunkId, e.getOriginalMessage());
            return new Result(null, outcome);
        }
    }

    private static boolean hasFailures(Outcome outcome) {
        return outcome.stats().stream().anyMatch(stats -> stats.failures() > 0);
    }

    private static String graphId(Graph graph) {
        return graph != null ? graph.getId() : null;
    }

    private static String parentGraphId(Graph graph) {
        return graph != null ? graph.getParentGraphId() : null;
    }

    /**
     * Collects the entity vocabulary already declared by the active schema. Pattern endpoint labels
     * are declarations too, including in pattern-only projects.
     */
    private static List<String> schemaEntityTypes(GraphSchema schema,
                                                  GraphExtractionValidationPolicy policy) {
        LinkedHashSet<String> types = new LinkedHashSet<>();
        if (schema != null) {
            types.addAll(schema.getAllNodeLabels());
        }
        LinkedHashSet<String> patterns = new LinkedHashSet<>();
        if (schema != null && schema.getPatterns() != null) {
            patterns.addAll(schema.getPatterns());
        }
        if (policy != null) {
            patterns.addAll(policy.effectiveRelationPatterns());
        }
        for (String pattern : patterns) {
            GraphExtractionValidator.parseRelationPattern(pattern).ifPresent(signature -> {
                types.add(signature.sourceType());
                types.add(signature.targetType());
            });
        }
        return List.copyOf(types);
    }

    private static int propositionLimit(int value) {
        return Math.max(0, value);
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static LlmCaller budgetedCaller(LlmCaller delegate, PromptProfile profile) {
        return (passId, prompt) -> {
            String rendered = prompt == null ? "" : prompt;
            int estimatedTokens = (int) Math.ceil(rendered.length() / profile.charsPerToken());
            if (estimatedTokens > profile.budgetTokens()) {
                throw new IllegalArgumentException("Decomposed " + passId + " prompt estimated at "
                        + estimatedTokens + " tokens exceeds " + profile.budgetTokens()
                        + "-token " + profile.tier() + " budget; lower bounded context/candidates or "
                        + "select a larger executable context profile");
            }
            return delegate.call(passId, prompt);
        };
    }

    private static DecomposedPromptTier richestCompatibleTier(int contextTokens, int promptTokens) {
        if (contextTokens >= 16_384 && promptTokens >= 4_096) {
            return DecomposedPromptTier.EXPANDED;
        }
        if (contextTokens >= 8_192 && promptTokens >= 2_048) {
            return DecomposedPromptTier.RICH;
        }
        if (contextTokens >= 4_096 && promptTokens >= 1_024) {
            return DecomposedPromptTier.STANDARD;
        }
        if (contextTokens >= 1_024 && promptTokens >= 512) {
            return DecomposedPromptTier.COMPACT;
        }
        return null;
    }

    private static int tierRank(DecomposedPromptTier tier) {
        return switch (tier) {
            case AUTO -> 0;
            case COMPACT -> 1;
            case STANDARD -> 2;
            case RICH -> 3;
            case EXPANDED -> 4;
        };
    }

    private static TierLimits limitsFor(DecomposedPromptTier tier) {
        return switch (tier) {
            case AUTO, STANDARD -> new TierLimits(6, 8, 3, 800, 640);
            case COMPACT -> new TierLimits(4, 6, 2, 480, 320);
            case RICH -> new TierLimits(8, 12, 5, 1_200, 1_200);
            case EXPANDED -> new TierLimits(Integer.MAX_VALUE, Integer.MAX_VALUE,
                    Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        };
    }
}
