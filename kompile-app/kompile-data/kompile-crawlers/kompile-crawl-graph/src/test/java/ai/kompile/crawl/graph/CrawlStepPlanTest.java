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

import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.crawl.graph.CrawlStepPlan.Action;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link CrawlStepPlan} — no Spring context. Verifies selection (legacy coarse
 * toggles vs explicit enable/archive), dependency closure, the SKIP/ARCHIVE cascade, and validation.
 */
class CrawlStepPlanTest {

    @Test
    void nullRequest_everyStepRuns() {
        CrawlStepPlan plan = CrawlStepPlan.from(null);
        for (CrawlPipelineStepRegistry.StepDescriptor d : CrawlPipelineStepRegistry.all()) {
            assertEquals(Action.RUN, plan.forStep(d.id()), d.id() + " should RUN for a null request");
        }
        assertDoesNotThrow(plan::validate);
    }

    @Test
    void legacyDefaults_unconfiguredStepsSkipped() {
        // No graph/vector/preprocessing config => those steps (and the graph steps that depend on
        // extraction) are skipped, exactly like the pre-existing pipeline behavior.
        CrawlStepPlan plan = CrawlStepPlan.from(UnifiedCrawlRequest.builder().build());

        assertEquals(Action.RUN, plan.forStep("LOADING"));
        assertEquals(Action.RUN, plan.forStep("CONVERTING"));
        assertEquals(Action.RUN, plan.forStep("ROUTING"));
        assertEquals(Action.RUN, plan.forStep("CHUNKING"));
        assertEquals(Action.RUN, plan.forStep("GRAPH_PREP"));

        assertEquals(Action.SKIP, plan.forStep("PREPROCESSING"));
        assertEquals(Action.SKIP, plan.forStep("GRAPH_EXTRACTION"));
        assertEquals(Action.SKIP, plan.forStep("VECTOR_INDEXING"));
        // Cascaded: these depend (transitively) on graph extraction.
        assertEquals(Action.SKIP, plan.forStep("ENTITY_RESOLUTION"));
        assertEquals(Action.SKIP, plan.forStep("EDGE_COMPUTATION"));

        assertDoesNotThrow(plan::validate);
    }

    @Test
    void explicitEnable_chunkAndEmbedOnly() {
        CrawlStepPlan plan = CrawlStepPlan.from(
                UnifiedCrawlRequest.builder().enabledSteps(List.of("VECTOR_INDEXING")).build());

        // VECTOR_INDEXING + its transitive deps + foundational steps run.
        assertEquals(Action.RUN, plan.forStep("LOADING"));
        assertEquals(Action.RUN, plan.forStep("CONVERTING"));
        assertEquals(Action.RUN, plan.forStep("ROUTING"));
        assertEquals(Action.RUN, plan.forStep("CHUNKING"));
        assertEquals(Action.RUN, plan.forStep("VECTOR_INDEXING"));
        // Everything else is skipped.
        assertEquals(Action.SKIP, plan.forStep("PREPROCESSING"));
        assertEquals(Action.SKIP, plan.forStep("GRAPH_PREP"));
        assertEquals(Action.SKIP, plan.forStep("GRAPH_EXTRACTION"));
        assertEquals(Action.SKIP, plan.forStep("ENTITY_RESOLUTION"));
        assertEquals(Action.SKIP, plan.forStep("EDGE_COMPUTATION"));

        assertDoesNotThrow(plan::validate);
    }

    @Test
    void explicitEnable_entityResolutionPullsGraphExtraction() {
        CrawlStepPlan plan = CrawlStepPlan.from(
                UnifiedCrawlRequest.builder().enabledSteps(List.of("ENTITY_RESOLUTION")).build());

        // Closure must pull in CHUNKING + GRAPH_EXTRACTION (entity resolution's transitive deps).
        assertEquals(Action.RUN, plan.forStep("CHUNKING"));
        assertEquals(Action.RUN, plan.forStep("GRAPH_EXTRACTION"));
        assertEquals(Action.RUN, plan.forStep("ENTITY_RESOLUTION"));
        assertEquals(Action.SKIP, plan.forStep("VECTOR_INDEXING"));
        assertEquals(Action.SKIP, plan.forStep("EDGE_COMPUTATION"));

        assertDoesNotThrow(plan::validate);
    }

    @Test
    void archiveVectorIndexing_chunkNowEmbedLater() {
        CrawlStepPlan plan = CrawlStepPlan.from(
                UnifiedCrawlRequest.builder().archivedSteps(List.of("VECTOR_INDEXING")).build());

        assertTrue(plan.isArchive("VECTOR_INDEXING"), "VECTOR_INDEXING should be ARCHIVE");
        assertEquals(Action.RUN, plan.forStep("CHUNKING"), "chunks must still be produced to archive them");
        assertEquals(Action.RUN, plan.forStep("LOADING"));
        assertDoesNotThrow(plan::validate);
    }

    @Test
    void archiveGraphExtraction_cascadesEntityResolutionToSkip() {
        CrawlStepPlan plan = CrawlStepPlan.from(
                UnifiedCrawlRequest.builder().archivedSteps(List.of("GRAPH_EXTRACTION")).build());

        assertTrue(plan.isArchive("GRAPH_EXTRACTION"));
        assertEquals(Action.RUN, plan.forStep("CHUNKING"));
        // Entity resolution can't run now because extraction was archived for later.
        assertEquals(Action.SKIP, plan.forStep("ENTITY_RESOLUTION"));
        assertDoesNotThrow(plan::validate);
    }

    @Test
    void nonArchivableStep_archiveRequestIgnored() {
        // CHUNKING is the pivot — not archivable; the request to archive it is ignored and it runs.
        CrawlStepPlan plan = CrawlStepPlan.from(
                UnifiedCrawlRequest.builder().archivedSteps(List.of("CHUNKING")).build());

        assertEquals(Action.RUN, plan.forStep("CHUNKING"));
        assertDoesNotThrow(plan::validate);
    }

    @Test
    void validate_throwsWhenArchivedStepDependsOnAnotherArchivedStep() {
        // Archiving both extraction and entity-resolution is contradictory: entity-resolution's input
        // (extracted entities) won't exist until extraction is resumed first.
        CrawlStepPlan plan = CrawlStepPlan.from(UnifiedCrawlRequest.builder()
                .archivedSteps(List.of("GRAPH_EXTRACTION", "ENTITY_RESOLUTION"))
                .build());

        assertTrue(plan.isArchive("GRAPH_EXTRACTION"));
        assertTrue(plan.isArchive("ENTITY_RESOLUTION"));
        assertThrows(IllegalArgumentException.class, plan::validate);
    }

    @Test
    void forStep_unknownStepDefaultsToRun() {
        CrawlStepPlan plan = CrawlStepPlan.from(UnifiedCrawlRequest.builder().build());
        assertEquals(Action.RUN, plan.forStep("NO_SUCH_STEP"));
    }

    @Test
    void unknownEnabledStep_fallsBackToLegacyMode() {
        // An unknown id contributes nothing to the selection, so the plan stays in legacy mode.
        CrawlStepPlan plan = CrawlStepPlan.from(
                UnifiedCrawlRequest.builder().enabledSteps(List.of("BOGUS_STEP")).build());
        assertEquals(Action.RUN, plan.forStep("CHUNKING"));
        assertEquals(Action.SKIP, plan.forStep("GRAPH_EXTRACTION"));
    }
}
