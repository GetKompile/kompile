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

import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.crawl.graph.VectorIndexConfig;
import ai.kompile.crawl.graph.CrawlStepPlan.Action;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void legacyDefaults_graphRunsVectorAndPreprocessingOptional() {
        CrawlStepPlan plan = CrawlStepPlan.from(UnifiedCrawlRequest.builder().build());

        assertEquals(Action.RUN, plan.forStep("LOADING"));
        assertEquals(Action.RUN, plan.forStep("CONVERTING"));
        assertEquals(Action.RUN, plan.forStep("ROUTING"));
        assertEquals(Action.RUN, plan.forStep("CHUNKING"));
        assertEquals(Action.RUN, plan.forStep("GRAPH_PREP"));
        assertEquals(Action.RUN, plan.forStep("GRAPH_EXTRACTION"));
        assertEquals(Action.RUN, plan.forStep("SURFACING"));
        assertEquals(Action.RUN, plan.forStep("ENTITY_RESOLUTION"));
        assertEquals(Action.RUN, plan.forStep("EDGE_COMPUTATION"));
        assertEquals(Action.RUN, plan.forStep("ENRICHMENT"));

        assertEquals(Action.SKIP, plan.forStep("PREPROCESSING"));
        assertEquals(Action.SKIP, plan.forStep("VECTOR_INDEXING"));

        assertDoesNotThrow(plan::validate);
    }

    @Test
    void explicitEnable_vectorIndexingAlsoRunsMandatoryGraphSteps() {
        CrawlStepPlan plan = CrawlStepPlan.from(
                UnifiedCrawlRequest.builder().enabledSteps(List.of("VECTOR_INDEXING")).build());

        assertEquals(Action.RUN, plan.forStep("LOADING"));
        assertEquals(Action.RUN, plan.forStep("CONVERTING"));
        assertEquals(Action.RUN, plan.forStep("ROUTING"));
        assertEquals(Action.RUN, plan.forStep("CHUNKING"));
        assertEquals(Action.RUN, plan.forStep("VECTOR_INDEXING"));
        assertEquals(Action.RUN, plan.forStep("GRAPH_PREP"));
        assertEquals(Action.RUN, plan.forStep("GRAPH_EXTRACTION"));
        assertEquals(Action.RUN, plan.forStep("SURFACING"));
        assertEquals(Action.RUN, plan.forStep("ENTITY_RESOLUTION"));
        assertEquals(Action.RUN, plan.forStep("EDGE_COMPUTATION"));
        assertEquals(Action.SKIP, plan.forStep("PREPROCESSING"));
        assertEquals(Action.SKIP, plan.forStep("ENRICHMENT"));

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
        assertEquals(Action.RUN, plan.forStep("EDGE_COMPUTATION"));
        assertEquals(Action.SKIP, plan.forStep("VECTOR_INDEXING"));

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
    void archiveGraphExtractionRequestIgnored_graphStillRuns() {
        CrawlStepPlan plan = CrawlStepPlan.from(
                UnifiedCrawlRequest.builder().archivedSteps(List.of("GRAPH_EXTRACTION")).build());

        assertEquals(Action.RUN, plan.forStep("GRAPH_EXTRACTION"));
        assertEquals(Action.RUN, plan.forStep("CHUNKING"));
        assertEquals(Action.RUN, plan.forStep("ENTITY_RESOLUTION"));
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
    void archiveRequestsForMandatoryGraphStepsAreIgnored() {
        CrawlStepPlan plan = CrawlStepPlan.from(UnifiedCrawlRequest.builder()
                .archivedSteps(List.of("GRAPH_EXTRACTION", "ENTITY_RESOLUTION", "EDGE_COMPUTATION"))
                .build());

        assertEquals(Action.RUN, plan.forStep("GRAPH_EXTRACTION"));
        assertEquals(Action.RUN, plan.forStep("ENTITY_RESOLUTION"));
        assertEquals(Action.RUN, plan.forStep("EDGE_COMPUTATION"));
        assertDoesNotThrow(plan::validate);
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
        assertEquals(Action.RUN, plan.forStep("GRAPH_EXTRACTION"));
    }

    // -----------------------------------------------------------------------
    // Test 1: Default plan with optional vector/preprocessing configured
    // -----------------------------------------------------------------------

    /**
     * When vectorIndex.enabled=true and preprocessing is non-null, legacy mode should plan every step as RUN.
     */
    @Test
    void fullyConfiguredLegacyMode_fullPipelineRuns() {
        CrawlStepPlan plan = CrawlStepPlan.from(UnifiedCrawlRequest.builder()
                .graphExtraction(GraphExtractionConfig.builder().build())
                .vectorIndex(VectorIndexConfig.builder().enabled(true).build())
                .preprocessing("configured")
                .build());

        for (CrawlPipelineStepRegistry.StepDescriptor d : CrawlPipelineStepRegistry.all()) {
            assertEquals(Action.RUN, plan.forStep(d.id()),
                    d.id() + " should RUN when all features are enabled");
        }
        assertDoesNotThrow(plan::validate);
    }

    // -----------------------------------------------------------------------
    // Test 2: Modular-step footgun — archivedSteps alone must NOT flip to
    //         explicit/whitelist mode and must NOT skip graph steps
    // -----------------------------------------------------------------------

    /**
     * THE FOOTGUN: the old code seeded archivedSteps into the selection set, which
     * flipped the plan to explicit/whitelist mode and silently SKIPped every step
     * not listed — including GRAPH_PREP, ENRICHMENT, and all downstream graph steps.
     * The crawl appeared to complete in seconds (only rule-based GRAPH_PREP ran).
     *
     * <p>Fixed: archivedSteps alone must NOT activate explicit mode. The graph pipeline
     * must keep running; only the archived step itself is ARCHIVE, not every other step.</p>
     */
    @Test
    void footgun_archivedStepsAlone_doesNotFlipToExplicitMode_graphStepsStillRun() {
        CrawlStepPlan plan = CrawlStepPlan.from(UnifiedCrawlRequest.builder()
                .graphExtraction(GraphExtractionConfig.builder().build())
                .vectorIndex(VectorIndexConfig.builder().enabled(true).build())
                .preprocessing("configured")
                // Only archive VECTOR_INDEXING — no enabledSteps set.
                // OLD bug: this seeded VECTOR_INDEXING into selected-set → explicit mode →
                //          GRAPH_EXTRACTION, ENRICHMENT etc. all became SKIP.
                .archivedSteps(List.of("VECTOR_INDEXING"))
                .build());

        // All graph steps must still run in legacy mode.
        assertEquals(Action.RUN, plan.forStep("ROUTING"),          "ROUTING must RUN");
        assertEquals(Action.RUN, plan.forStep("GRAPH_PREP"),       "GRAPH_PREP must RUN (old bug: was SKIP)");
        assertEquals(Action.RUN, plan.forStep("CHUNKING"),         "CHUNKING must RUN");
        assertEquals(Action.RUN, plan.forStep("GRAPH_EXTRACTION"), "GRAPH_EXTRACTION must RUN (old bug: was SKIP)");
        assertEquals(Action.RUN, plan.forStep("ENTITY_RESOLUTION"),"ENTITY_RESOLUTION must RUN (old bug: was SKIP)");
        assertEquals(Action.RUN, plan.forStep("EDGE_COMPUTATION"), "EDGE_COMPUTATION must RUN (old bug: was SKIP)");
        assertEquals(Action.RUN, plan.forStep("ENRICHMENT"),       "ENRICHMENT must RUN (old bug: was SKIP)");
        // The archived step itself: ARCHIVE, not SKIP and not RUN.
        assertEquals(Action.ARCHIVE, plan.forStep("VECTOR_INDEXING"),
                "VECTOR_INDEXING must be ARCHIVE (not SKIP, not RUN)");
        assertDoesNotThrow(plan::validate);
    }

    /**
     * Complement of the footgun test: explicit mode can still select vector indexing, but it cannot
     * produce a graphless crawl. Mandatory graph construction steps are included automatically.
     */
    @Test
    void footgun_explicitMode_graphConstructionStillRuns() {
        CrawlStepPlan plan = CrawlStepPlan.from(UnifiedCrawlRequest.builder()
                .enabledSteps(List.of("VECTOR_INDEXING"))
                .build());

        assertEquals(Action.RUN, plan.forStep("GRAPH_PREP"));
        assertEquals(Action.RUN, plan.forStep("GRAPH_EXTRACTION"));
        assertEquals(Action.RUN, plan.forStep("SURFACING"));
        assertEquals(Action.RUN, plan.forStep("ENTITY_RESOLUTION"));
        assertEquals(Action.RUN, plan.forStep("EDGE_COMPUTATION"));
        assertEquals(Action.SKIP, plan.forStep("ENRICHMENT"));
        assertEquals(Action.RUN, plan.forStep("VECTOR_INDEXING"));
        assertDoesNotThrow(plan::validate);
    }

    /**
     * Explicit mode always includes mandatory graph construction. Listing ENRICHMENT keeps the
     * post-crawl reasoning stage in an otherwise explicit step selection.
     */
    @Test
    void footgun_explicitMode_enrichmentListedWithMandatoryGraphSteps_allRun() {
        CrawlStepPlan plan = CrawlStepPlan.from(UnifiedCrawlRequest.builder()
                .enabledSteps(List.of(
                        "GRAPH_PREP", "GRAPH_EXTRACTION",
                        "ENTITY_RESOLUTION", "EDGE_COMPUTATION", "ENRICHMENT"))
                .archivedSteps(List.of("VECTOR_INDEXING"))
                .build());

        assertEquals(Action.RUN,     plan.forStep("GRAPH_PREP"));
        assertEquals(Action.RUN,     plan.forStep("GRAPH_EXTRACTION"));
        assertEquals(Action.RUN,     plan.forStep("ENTITY_RESOLUTION"));
        assertEquals(Action.RUN,     plan.forStep("EDGE_COMPUTATION"));
        assertEquals(Action.RUN,     plan.forStep("ENRICHMENT"));
        // VECTOR_INDEXING: not in enabledSteps, so explicit mode would SKIP it —
        // but it is in archivedSteps; archivable=true, so it becomes ARCHIVE.
        assertEquals(Action.ARCHIVE, plan.forStep("VECTOR_INDEXING"));
        assertDoesNotThrow(plan::validate);
    }

    // -----------------------------------------------------------------------
    // Test 3: Step status / resumability via plan semantics
    // -----------------------------------------------------------------------

    /**
     * ARCHIVED steps are not SKIP: they represent work deferred for a later run,
     * not work that will never run. Their archivable flag is true; foundational steps
     * cannot be archived (they must always run).
     */
    @Test
    void vectorIndexingCanBeArchived_graphConstructionCannotBeArchivedOut() {
        CrawlStepPlan plan = CrawlStepPlan.from(UnifiedCrawlRequest.builder()
                .archivedSteps(List.of("GRAPH_EXTRACTION", "ENTITY_RESOLUTION", "EDGE_COMPUTATION", "VECTOR_INDEXING"))
                .build());

        assertEquals(Action.ARCHIVE, plan.forStep("VECTOR_INDEXING"));
        assertEquals(Action.RUN, plan.forStep("GRAPH_EXTRACTION"));
        assertEquals(Action.RUN, plan.forStep("ENTITY_RESOLUTION"));
        assertEquals(Action.RUN, plan.forStep("EDGE_COMPUTATION"));

        for (CrawlPipelineStepRegistry.StepDescriptor d : CrawlPipelineStepRegistry.all()) {
            if (d.foundational()) {
                assertEquals(Action.RUN, plan.forStep(d.id()),
                        d.id() + " is foundational and must RUN even if archive was requested");
            }
        }
    }

    /**
     * A step marked ARCHIVE is distinct from SKIP: ARCHIVE means "run later" while
     * SKIP means "never run this crawl". The plan's isArchive/isSkip predicates must
     * be mutually exclusive.
     */
    @Test
    void archive_distinctFromSkip_isArchiveAndIsSkipMutuallyExclusive() {
        CrawlStepPlan plan = CrawlStepPlan.from(UnifiedCrawlRequest.builder()
                .archivedSteps(List.of("VECTOR_INDEXING"))
                .build());

        assertTrue(plan.isArchive("VECTOR_INDEXING"),  "VECTOR_INDEXING must be ARCHIVE");
        assertTrue(!plan.isSkip("VECTOR_INDEXING"),    "VECTOR_INDEXING must NOT be SKIP");
        assertTrue(!plan.isRun("VECTOR_INDEXING"),     "VECTOR_INDEXING must NOT be RUN");
    }

    /**
     * Archive requests for GRAPH_EXTRACTION are ignored because graph construction is mandatory.
     */
    @Test
    void archivedGraphExtractionRequestIgnored_graphSpineRuns() {
        CrawlStepPlan plan = CrawlStepPlan.from(UnifiedCrawlRequest.builder()
                .graphExtraction(GraphExtractionConfig.builder().build())
                .vectorIndex(VectorIndexConfig.builder().enabled(true).build())
                .preprocessing("configured")
                .archivedSteps(List.of("GRAPH_EXTRACTION"))
                .build());

        assertEquals(Action.RUN, plan.forStep("GRAPH_EXTRACTION"));
        assertEquals(Action.RUN, plan.forStep("ENTITY_RESOLUTION"));
        assertEquals(Action.RUN, plan.forStep("EDGE_COMPUTATION"));
        assertEquals(Action.RUN, plan.forStep("ENRICHMENT"));
        assertEquals(Action.RUN, plan.forStep("CHUNKING"));
        assertEquals(Action.RUN, plan.forStep("VECTOR_INDEXING"));
        assertEquals(Action.RUN, plan.forStep("GRAPH_PREP"));
        assertDoesNotThrow(plan::validate);
    }

    // ---- strictSteps: explicit selection WITHOUT the mandatory graph spine ----

    @Test
    void strictEnable_vectorIndexingOnly_skipsGraphSpine() {
        CrawlStepPlan plan = CrawlStepPlan.from(UnifiedCrawlRequest.builder()
                .enabledSteps(List.of("VECTOR_INDEXING"))
                .strictSteps(true)
                .build());

        // Dependency chain of the selection still runs...
        assertEquals(Action.RUN, plan.forStep("LOADING"));
        assertEquals(Action.RUN, plan.forStep("CONVERTING"));
        assertEquals(Action.RUN, plan.forStep("ROUTING"));
        assertEquals(Action.RUN, plan.forStep("CHUNKING"));
        assertEquals(Action.RUN, plan.forStep("VECTOR_INDEXING"));

        // ...but the graph spine is no longer force-seeded.
        assertEquals(Action.SKIP, plan.forStep("GRAPH_PREP"));
        assertEquals(Action.SKIP, plan.forStep("GRAPH_EXTRACTION"));
        assertEquals(Action.SKIP, plan.forStep("SURFACING"));
        assertEquals(Action.SKIP, plan.forStep("ENTITY_RESOLUTION"));
        assertEquals(Action.SKIP, plan.forStep("EDGE_COMPUTATION"));
        assertEquals(Action.SKIP, plan.forStep("ENRICHMENT"));

        assertDoesNotThrow(plan::validate);
    }

    @Test
    void strictEnable_edgeComputationPullsItsDependencyChainOnly() {
        CrawlStepPlan plan = CrawlStepPlan.from(UnifiedCrawlRequest.builder()
                .enabledSteps(List.of("EDGE_COMPUTATION"))
                .strictSteps(true)
                .build());

        assertEquals(Action.RUN, plan.forStep("CHUNKING"));
        assertEquals(Action.RUN, plan.forStep("GRAPH_EXTRACTION"));
        assertEquals(Action.RUN, plan.forStep("ENTITY_RESOLUTION"));
        assertEquals(Action.RUN, plan.forStep("EDGE_COMPUTATION"));

        assertEquals(Action.SKIP, plan.forStep("VECTOR_INDEXING"));
        assertEquals(Action.SKIP, plan.forStep("ENRICHMENT"));
        assertEquals(Action.SKIP, plan.forStep("SURFACING"));
        assertEquals(Action.SKIP, plan.forStep("GRAPH_PREP"));

        assertDoesNotThrow(plan::validate);
    }

    @Test
    void strictEnable_graphExtractionOnly_skipsDownstreamGraphSteps() {
        CrawlStepPlan plan = CrawlStepPlan.from(UnifiedCrawlRequest.builder()
                .enabledSteps(List.of("GRAPH_EXTRACTION"))
                .strictSteps(true)
                .build());

        assertEquals(Action.RUN, plan.forStep("GRAPH_EXTRACTION"));
        assertEquals(Action.SKIP, plan.forStep("ENTITY_RESOLUTION"));
        assertEquals(Action.SKIP, plan.forStep("EDGE_COMPUTATION"));
        assertEquals(Action.SKIP, plan.forStep("ENRICHMENT"));
        assertEquals(Action.SKIP, plan.forStep("VECTOR_INDEXING"));

        assertDoesNotThrow(plan::validate);
    }

    @Test
    void strictFlagWithoutSelection_fallsBackToLegacyDefaults() {
        CrawlStepPlan plan = CrawlStepPlan.from(UnifiedCrawlRequest.builder()
                .strictSteps(true)
                .build());

        // No explicit selection → strict flag is inert; legacy defaults apply unchanged.
        assertEquals(Action.RUN, plan.forStep("GRAPH_EXTRACTION"));
        assertEquals(Action.RUN, plan.forStep("ENRICHMENT"));
        assertEquals(Action.SKIP, plan.forStep("VECTOR_INDEXING"));
        assertEquals(Action.SKIP, plan.forStep("PREPROCESSING"));

        assertDoesNotThrow(plan::validate);
    }
}
