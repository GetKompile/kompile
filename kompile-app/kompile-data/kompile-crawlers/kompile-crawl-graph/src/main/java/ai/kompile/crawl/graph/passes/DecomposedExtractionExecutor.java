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
import ai.kompile.core.crawl.graph.GraphExtractionValidationPolicy;
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
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            return Options.defaults();
        }
        Options defaults = Options.defaults();
        return new Options(
                positive(config.getDecomposedMaxPropositions(), defaults.maxPropositions()),
                positive(config.getDecomposedEntityCandidateLimit(), defaults.entityCandidateLimit()),
                positive(config.getDecomposedRelationCandidateLimit(), defaults.relationCandidateLimit()),
                positive(config.getDecomposedClaimCandidateLimit(), defaults.claimCandidateLimit()),
                config.isDecomposedRequireEvidenceSpans(),
                config.isDecomposedClaimMatching());
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
        if (chunkText == null || chunkText.isBlank()) {
            return new Result(null, null);
        }

        double minScore = config != null ? config.getDecomposedCandidateMinScore() : 0;
        GraphEntityCandidateProvider entities =
                new GraphEntityCandidateProvider(targetGraph, minScore);
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

        PassContext context = PassContext.forChunk(chunkId, documentId, chunkText)
                .withGraph(graphId(targetGraph), parentGraphId(targetGraph))
                .withSchema(GraphExtractionSchema.SCHEMA_VERSION,
                        config != null ? config.getModelName() : null);

        Outcome outcome = new DecomposedExtractionPipeline(
                entities, relations, claims, optionsFrom(config)).run(context, caller);

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

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }
}
