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
 * {@link PipelineStepTracker}. This drives two things: the step catalog surfaced to the UI/CLI (so a
 * caller can pick which steps to run / archive / skip), and the dependency validation performed by
 * {@link CrawlStepPlan}.</p>
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
            new StepDescriptor("ENRICHMENT", "Post-Crawl Enrichment", "ENRICHMENT",
                    Set.of(), false, false, false, false)
    );

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
}
