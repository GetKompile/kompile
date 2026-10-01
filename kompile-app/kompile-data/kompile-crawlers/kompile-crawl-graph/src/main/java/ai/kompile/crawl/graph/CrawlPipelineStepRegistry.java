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

package ai.kompile.crawl.graph;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static, immutable catalog of unified-crawl pipeline steps and their dependency edges.
 *
 * <p>Pure (no Spring) so it is trivially unit-testable. Step IDs match the IDs tracked by
 * {@link PipelineStepTracker}. This drives three things: the step catalog surfaced to the UI/CLI (so a
 * caller can pick which steps to run / archive / skip), the dependency validation performed by
 * {@link CrawlStepPlan}, and the resolution of the phase a running crawl reports to the step it is
 * working on ({@link #workPhase}).</p>
 */
public final class CrawlPipelineStepRegistry {

    /**
     * One pipeline step.
     *
     * @param id                canonical step ID (matches PipelineStepTracker)
     * @param displayName       human label
     * @param stepType          coarse category (IO / CPU / LLM / GRAPH / EMBEDDING / ...)
     * @param hardDependsOn     steps that must RUN for this step to RUN or ARCHIVE
     * @param chunkConsumerOnly true if the step's only real input is chunked text (so it needs CHUNKING)
     * @param chunkProducer     true for CHUNKING (the pivot that produces the chunks others consume)
     * @param foundational      true if the step always runs and cannot be skipped or archived
     * @param archivable        true if the step's inputs can be archived to disk and the step run later
     */
    public record StepDescriptor(
            String id,
            String displayName,
            String stepType,
            Set<String> hardDependsOn,
            boolean chunkConsumerOnly,
            boolean chunkProducer,
            boolean foundational,
            boolean archivable) {}

    /** Canonical, ordered list of pipeline steps. */
    public static final List<StepDescriptor> ALL_STEPS = List.of(
            new StepDescriptor("LOADING", "Source Loading", "IO",
                    Set.of(), false, false, true, false),
            new StepDescriptor("DISCOVERING", "Source Discovery", "IO",
                    Set.of("LOADING"), false, false, true, false),
            new StepDescriptor("CONVERTING", "Text Conversion", "CPU",
                    Set.of("LOADING"), false, false, true, false),
            new StepDescriptor("PREPROCESSING", "Document Preprocessing", "CPU",
                    Set.of("CONVERTING"), false, false, false, false),
            new StepDescriptor("ROUTING", "Content Routing", "CPU",
                    Set.of("CONVERTING"), false, false, false, false),
            new StepDescriptor("GRAPH_PREP", "Rule Graph Prep", "GRAPH",
                    Set.of("ROUTING"), false, false, false, false),
            new StepDescriptor("CHUNKING", "Chunking", "CPU",
                    Set.of("ROUTING"), false, true, false, false),
            new StepDescriptor("GRAPH_EXTRACTION", "Graph Extraction", "LLM",
                    Set.of("CHUNKING"), true, false, false, true),
            new StepDescriptor("SURFACING", "Crawl Surface", "GRAPH",
                    Set.of("CHUNKING"), false, false, false, false),
            new StepDescriptor("ENTITY_RESOLUTION", "Entity Resolution", "GRAPH",
                    Set.of("GRAPH_EXTRACTION"), false, false, false, true),
            new StepDescriptor("EDGE_COMPUTATION", "Graph Edge Cleanup", "GRAPH",
                    Set.of("ENTITY_RESOLUTION"), false, false, false, true),
            new StepDescriptor("VECTOR_INDEXING", "Embedding & Vector Index", "EMBEDDING",
                    Set.of("CHUNKING"), true, false, false, true),
            // Runs late on purpose: grouping reads the entities the graph ended up with, and the
            // retrieval discovery channels search an index that only contains this run's chunks
            // once VECTOR_INDEXING has landed. Depends on GRAPH_EXTRACTION only — a run with entity
            // resolution off still has entities to partition, they are just less merged.
            new StepDescriptor("ENTITY_PARTITIONS", "Entity Partitions", "GRAPH",
                    Set.of("GRAPH_EXTRACTION"), false, false, false, false),
            new StepDescriptor("ENRICHMENT", "Post-Crawl Enrichment", "ENRICHMENT",
                    Set.of("ENTITY_RESOLUTION", "EDGE_COMPUTATION"), false, false, false, false)
    );

    /**
     * Phases a crawl reports that are crawl work but not pipeline steps: QUEUED (waiting for a crawl
     * slot) and LEARNING (KGE training on the finished graph).
     */
    public static final Set<String> NON_STEP_WORK_PHASES = Set.of("QUEUED", "LEARNING");

    /**
     * Phases that mean no crawl work is running: the end markers COMPLETED, FAILED, CANCELLED and
     * PARTITION_COMPLETE; PENDING_EMBEDDING, set when a crawl finishes with its embedding deferred (the
     * deferred embedding runs later, outside the crawl's run); SCHEMA_UNIFICATION_FAILED, set just before
     * that error fails the crawl; and GRAPH_EXTRACTION_PREVIEW, the phase of the single-source preview's
     * throwaway job, which never runs as a crawl.
     */
    public static final Set<String> NON_WORK_PHASES = Set.of(
            "COMPLETED", "FAILED", "CANCELLED", "PARTITION_COMPLETE", "PENDING_EMBEDDING",
            "SCHEMA_UNIFICATION_FAILED", "GRAPH_EXTRACTION_PREVIEW");

    /** Vector indexing reports its embedding and its index commit as these two phases. */
    private static final Set<String> VECTOR_INDEXING_ALIASES = Set.of("EMBEDDING", "INDEXING");

    /** A decomposed extraction pass reports its step's ID with the pass appended. */
    private static final List<String> PASS_SUFFIXES = List.of("_ENTITIES", "_RELATIONS");

    private static final Map<String, StepDescriptor> BY_ID = index();

    private static Map<String, StepDescriptor> index() {
        Map<String, StepDescriptor> m = new LinkedHashMap<>();
        for (StepDescriptor d : ALL_STEPS) {
            m.put(d.id(), d);
        }
        return m;
    }

    private CrawlPipelineStepRegistry() {
    }

    public static List<StepDescriptor> all() {
        return ALL_STEPS;
    }

    public static StepDescriptor get(String stepId) {
        return stepId == null ? null : BY_ID.get(stepId);
    }

    public static boolean isKnown(String stepId) {
        return stepId != null && BY_ID.containsKey(stepId);
    }

    /**
     * Step ID for a reported phase that is another name for a step: EMBEDDING and INDEXING are
     * VECTOR_INDEXING. Any other phase, and null, is returned unchanged.
     */
    public static String canonicalStepId(String phase) {
        return phase != null && VECTOR_INDEXING_ALIASES.contains(phase) ? "VECTOR_INDEXING" : phase;
    }

    /** True for every phase {@link #workPhase} resolves to: a pipeline step or a non-step work phase. */
    public static boolean isWorkPhase(String phase) {
        return isKnown(phase) || (phase != null && NON_STEP_WORK_PHASES.contains(phase));
    }

    /**
     * The work phase behind the phase a crawl reports — the one place a reported phase is mapped to what
     * the crawl is doing, so the scheduler's per-phase resource lookup sees only declared names. Step
     * aliases and decomposed passes resolve to their step (EMBEDDING to VECTOR_INDEXING,
     * GRAPH_EXTRACTION_RELATIONS to GRAPH_EXTRACTION); null and the {@link #NON_WORK_PHASES} resolve to
     * null; any other phase is returned unchanged.
     */
    public static String workPhase(String phase) {
        if (phase == null || NON_WORK_PHASES.contains(phase)) {
            return null;
        }
        for (String suffix : PASS_SUFFIXES) {
            if (phase.endsWith(suffix)) {
                String step = phase.substring(0, phase.length() - suffix.length());
                if (isKnown(step)) {
                    return step;
                }
            }
        }
        return canonicalStepId(phase);
    }
}
