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
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.partition.PartitionKey;
import ai.kompile.core.graphrag.partition.staging.GraphCommitSink;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The commit end of a partition transaction: a merged graph goes in, the crawl's ordinary write
 * path is used unchanged, and the difference between what was offered and what the store took is
 * reported rather than swallowed.
 */
@DisplayName("Partition graph committer")
class PartitionGraphCommitterTest {

    private static final PartitionKey KEY = PartitionKey.forEntity("acme", "v1", null);

    private GraphPersistenceHelper persistence;
    private UnifiedCrawlJob job;
    private GraphExtractionConfig config;

    @BeforeEach
    void setUp() {
        persistence = mock(GraphPersistenceHelper.class);
        job = new UnifiedCrawlJob();
        config = new GraphExtractionConfig();
        wrote(0, 0);
    }

    /** What the store will claim it took. */
    private void wrote(int entities, int relationships) {
        when(persistence.persistConstructedGraphBatch(any(), any(), any(), any()))
                .thenReturn(new GraphPersistenceHelper.GraphPersistResult(entities,
                        relationships));
    }

    private PartitionGraphCommitter committer() {
        return new PartitionGraphCommitter(persistence);
    }

    private GraphCommitSink sink() {
        return committer().sinkFor(job, config, List.of());
    }

    private static Graph graphOf(int entities, int relationships) {
        List<Entity> people = new ArrayList<>();
        for (int i = 0; i < entities; i++) {
            Entity entity = new Entity();
            entity.setId("e" + i);
            entity.setTitle("Entity " + i);
            people.add(entity);
        }
        List<Relationship> links = new ArrayList<>();
        for (int i = 0; i < relationships; i++) {
            Relationship link = new Relationship();
            link.setSource("e0");
            link.setTarget("e" + (i + 1));
            link.setType("RELATED_TO");
            links.add(link);
        }
        return Graph.builder().name("acme").entities(people).relationships(links).build();
    }

    @Nested
    @DisplayName("Building a sink")
    class Building {

        @Test
        void aCommitterNeedsSomewhereToWrite() {
            assertThrows(NullPointerException.class, () -> new PartitionGraphCommitter(null));
        }

        @Test
        void aSinkNeedsTheJobItsWritesBelongTo() {
            // Without the job there is no fact sheet, no job id and no cancellation flag, so the
            // write would be unattributable rather than merely unlabelled.
            assertThrows(NullPointerException.class,
                    () -> committer().sinkFor(null, config, List.of()));
        }

        @Test
        void aSinkIsUsableWithoutSourceDocuments() {
            wrote(1, 0);
            GraphCommitSink built = committer().sinkFor(job, config, null);

            assertEquals(1, built.commit(KEY, graphOf(1, 0)).entities());
        }
    }

    @Nested
    @DisplayName("Writing")
    class Writing {

        @Test
        void theStagedGraphGoesThroughTheCrawlsOrdinaryWritePath() {
            wrote(2, 1);
            Graph staged = graphOf(2, 1);
            List<RetrievedDoc> documents = List.of();

            committer().sinkFor(job, config, documents).commit(KEY, staged);

            ArgumentCaptor<Graph> written = ArgumentCaptor.forClass(Graph.class);
            verify(persistence).persistConstructedGraphBatch(eq(job), written.capture(),
                    eq(documents), eq(config));
            assertSame(staged, written.getValue(),
                    "the merged graph should reach the writer as-is, not be rebuilt");
        }

        @Test
        void whatTheStoreTookIsWhatTheOutcomeReports() {
            wrote(3, 2);

            GraphCommitSink.CommitOutcome outcome = sink().commit(KEY, graphOf(3, 2));

            assertEquals(3, outcome.entities());
            assertEquals(2, outcome.relationships());
            assertTrue(outcome.isClean(), outcome.describe());
        }

        @Test
        void anEmptyStagedGraphIsWrittenRatherThanSkipped() {
            // A partition that read chunks and extracted nothing is a real answer; the write path
            // still owns document nodes and job bookkeeping for it.
            sink().commit(KEY, graphOf(0, 0));

            verify(persistence).persistConstructedGraphBatch(any(), any(), any(), any());
        }

        @Test
        void aNullGraphCommitsNothingAndNeverTouchesTheStore() {
            GraphCommitSink.CommitOutcome outcome = sink().commit(KEY, null);

            assertEquals(0, outcome.entities());
            assertEquals(0, outcome.relationships());
            assertTrue(outcome.isClean());
            verify(persistence, never()).persistConstructedGraphBatch(any(), any(), any(), any());
        }

        @Test
        void aGraphWithNoListsAtAllIsNotMistakenForAShortfall() {
            Graph hollow = Graph.builder().name("acme").build();

            GraphCommitSink.CommitOutcome outcome = sink().commit(KEY, hollow);

            assertTrue(outcome.isClean(), "nothing offered cannot be under-written");
        }

        @Test
        void oneSinkServesEveryPartitionInTheRun() {
            wrote(1, 0);
            GraphCommitSink shared = sink();

            shared.commit(KEY, graphOf(1, 0));
            shared.commit(PartitionKey.forEntity("bob", "v1", null), graphOf(1, 0));

            verify(persistence, times(2))
                    .persistConstructedGraphBatch(any(), any(), any(), any());
        }

        @Test
        void aStoreThatBlowsUpIsNotDisguisedAsAnEmptyCommit() {
            when(persistence.persistConstructedGraphBatch(any(), any(), any(), any()))
                    .thenThrow(new IllegalStateException("graph service is down"));

            assertThrows(IllegalStateException.class, () -> sink().commit(KEY, graphOf(1, 0)));
        }
    }

    @Nested
    @DisplayName("Reporting a shortfall")
    class Shortfalls {

        @Test
        void entitiesTheStoreDroppedAreNamedWithTheirCount() {
            wrote(2, 0);

            GraphCommitSink.CommitOutcome outcome = sink().commit(KEY, graphOf(4, 0));

            assertFalse(outcome.isClean());
            assertEquals(1, outcome.problems().size(), outcome.describe());
            assertTrue(outcome.problems().get(0).contains("2 of 4 entities"),
                    outcome.problems().get(0));
        }

        @Test
        void relationshipsTheStoreDroppedAreReportedSeparately() {
            wrote(3, 1);

            GraphCommitSink.CommitOutcome outcome = sink().commit(KEY, graphOf(3, 3));

            assertEquals(1, outcome.problems().size(), outcome.describe());
            assertTrue(outcome.problems().get(0).contains("2 of 3 relationships"),
                    outcome.problems().get(0));
        }

        @Test
        void bothShortfallsAreReportedTogether() {
            wrote(1, 0);

            GraphCommitSink.CommitOutcome outcome = sink().commit(KEY, graphOf(3, 2));

            assertEquals(2, outcome.problems().size(), outcome.describe());
        }

        @Test
        void aStoreThatTookMoreThanWasOfferedIsNotAShortfall() {
            // Deduplication upstream can leave the writer touching nodes this graph did not name;
            // that is not a loss, and reporting it as one would train people to ignore the field.
            wrote(5, 5);

            assertTrue(sink().commit(KEY, graphOf(2, 1)).isClean());
        }

        @Test
        void theProblemSaysWhyAnEntityMightHaveBeenDropped() {
            wrote(0, 0);

            String problem = sink().commit(KEY, graphOf(1, 0)).problems().get(0);

            assertTrue(problem.contains("confidence floor"), problem);
        }
    }

    @Nested
    @DisplayName("Knowing whether it can write at all")
    class Readiness {

        @Test
        void aDeploymentWithNoGraphServiceSaysSoUpFront() {
            persistence.knowledgeGraphService = null;
            assertFalse(committer().canWrite());
        }

        @Test
        void aDeploymentWithAGraphServiceIsReady() {
            persistence.knowledgeGraphService = mock(KnowledgeGraphService.class);
            assertTrue(committer().canWrite());
        }

        @Test
        void aSinkIsStillHandedOutWhenNothingCanBeWritten() {
            // Refusing to build the sink would turn a misconfiguration into a crash mid-run; the
            // honest shape is a sink that works and a canWrite() that says not to bother.
            persistence.knowledgeGraphService = null;
            assertNotNull(sink());
        }
    }
}
