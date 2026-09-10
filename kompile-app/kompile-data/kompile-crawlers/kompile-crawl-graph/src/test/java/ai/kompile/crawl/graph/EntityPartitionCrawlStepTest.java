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
 *  limitations under the License.
 */

package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.PartitionPassConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob.PipelineStepProgress;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob.PipelineStepStatus;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.graphrag.GraphConstructor.ExtractionTaskContext;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.partition.ChunkCandidate;
import ai.kompile.core.graphrag.partition.DiscoveryChannel;
import ai.kompile.core.graphrag.partition.DiscoveryPolicy;
import ai.kompile.core.graphrag.partition.EntityPartition;
import ai.kompile.core.graphrag.partition.EvidenceManifest;
import ai.kompile.core.graphrag.partition.MembershipState;
import ai.kompile.core.graphrag.partition.PartitionKey;
import ai.kompile.core.graphrag.partition.PartitionLifecycle;
import ai.kompile.core.graphrag.partition.PartitionMember;
import ai.kompile.core.graphrag.partition.PartitionStore;
import ai.kompile.core.graphrag.partition.grouping.GroupingPolicy;
import ai.kompile.core.graphrag.partition.reuse.ExtractionReuse;
import ai.kompile.core.graphrag.partition.staging.GraphCommitSink;
import ai.kompile.core.graphrag.partition.staging.PartitionGraphTransaction;
import ai.kompile.crawl.graph.EntityPartitionCrawlService.PartitionRequest;
import ai.kompile.crawl.graph.EntityPartitionCrawlService.StagedExtractor;
import ai.kompile.crawl.graph.EntityPartitionCrawlService.StagedRunResult;
import ai.kompile.crawl.graph.EntityPartitionCrawlStep.Outcome;
import ai.kompile.crawl.graph.EntityPartitionCrawlStep.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What the crawl's partition pass claims, and what it refuses to claim.
 *
 * <p>The pass is late, optional and non-fatal, which makes silence its worst failure mode: a
 * partition that ran with no graph behind it, or under a policy whose channels never ran, reports
 * coverage it does not have. Every gate here therefore checks two things — that the pass declined,
 * and that it said <em>why</em> on the step the operator is looking at.</p>
 */
@DisplayName("The crawl's entity-partition step")
class EntityPartitionCrawlStepTest {

    private static final long FACT_SHEET = 7L;

    private EntityPartitionCrawlService partitions;
    private PartitionGraphCommitter committer;
    private GraphExtractionOrchestrator extraction;
    private PipelineStepTracker steps;
    private CrawlDocumentTracker events;
    private PartitionStore store;
    private EntityPartitionCrawlStep step;

    private GraphExtractionConfig config;
    private List<Document> chunks;

    @BeforeEach
    void setUp() {
        partitions = mock(EntityPartitionCrawlService.class);
        committer = mock(PartitionGraphCommitter.class);
        extraction = mock(GraphExtractionOrchestrator.class);
        steps = new PipelineStepTracker();
        events = new CrawlDocumentTracker();
        store = PartitionStore.inMemory();

        // The default world: a graph to write into, an extractor to read with, and no vector index.
        when(committer.canWrite()).thenReturn(true);
        when(extraction.hasGraphConstructor()).thenReturn(true);
        when(committer.sinkFor(any(), any(), any()))
                .thenReturn((key, graph) -> GraphCommitSink.CommitOutcome.nothing());

        config = new GraphExtractionConfig();
        chunks = List.of(new Document("chunk-1", "Acme Corp reported revenue of $4.1M.", Map.of()),
                new Document("chunk-2", "Acme Corp acquired Beta Ltd in March.", Map.of()));

        step = new EntityPartitionCrawlStep(partitions, committer, store, extraction, steps, events);
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------

    private UnifiedCrawlJob job() {
        return job(FACT_SHEET);
    }

    private UnifiedCrawlJob job(Long factSheetId) {
        return UnifiedCrawlJob.builder()
                .jobId("partition-step-test")
                .request(UnifiedCrawlRequest.builder().factSheetId(factSheetId).build())
                .build();
    }

    private void expectGroups(String... subjects) {
        List<PartitionRequest> requests = Arrays.stream(subjects)
                .map(subject -> PartitionRequest.forEntity(subject, FACT_SHEET))
                .toList();
        when(partitions.groupedRequests(any(), any(), any())).thenReturn(requests);
    }

    /** A run that read one chunk, learned something and committed it. */
    private StagedRunResult ranClean(String subject) {
        PartitionKey key = PartitionKey.forEntity(subject, "policy-v1", String.valueOf(FACT_SHEET));
        EvidenceManifest manifest = new EvidenceManifest(key, 1,
                Map.of(MembershipState.PROCESSED, 1), Map.of(DiscoveryChannel.SEED, 1),
                List.of(), List.of(), List.of(), List.of(), true);
        PartitionLifecycle.Result run = new PartitionLifecycle.Result(EntityPartition.open(key),
                manifest, 1, 1, 0, true, Map.of(), List.of());
        PartitionGraphTransaction transaction = PartitionGraphTransaction.openOn(key, FACT_SHEET);
        return new StagedRunResult(run, transaction,
                transaction.commit((k, graph) -> GraphCommitSink.CommitOutcome.nothing()));
    }

    private void everyPartitionSucceeds() {
        when(partitions.runStaged(any(), any(), any(), any(), any()))
                .thenAnswer(call -> ranClean(call.getArgument(0, PartitionRequest.class).subject()));
    }

    private PipelineStepProgress partitionStep(UnifiedCrawlJob job) {
        return job.getPipelineSteps().stream()
                .filter(s -> EntityPartitionCrawlStep.STEP_ID.equals(s.getStepId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the pass recorded no step at all"));
    }

    private PipelineStepStatus stepStatus(UnifiedCrawlJob job) {
        return partitionStep(job).getStatus().get();
    }

    private String stepMessage(UnifiedCrawlJob job) {
        return partitionStep(job).getMessage().get();
    }

    private List<String> eventLevels(UnifiedCrawlJob job) {
        return job.getRecentEvents().stream()
                .filter(e -> EntityPartitionCrawlStep.STEP_ID.equals(e.getPhase()))
                .map(UnifiedCrawlJob.StageEvent::getLevel)
                .toList();
    }

    private PartitionMember member(String chunkId) {
        return PartitionMember.admit(
                ChunkCandidate.of(chunkId, DiscoveryChannel.SEED, 1.0).inDocument("doc-1"),
                MembershipState.DISCOVERED, 1);
    }

    // ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("What stops it, and how loudly")
    class Gates {

        @Test
        @DisplayName("a step plan that turned it off")
        void theStepPlanCanOptOut() {
            UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                    .factSheetId(FACT_SHEET)
                    .enabledSteps(List.of("GRAPH_EXTRACTION"))
                    .build();
            UnifiedCrawlJob job = UnifiedCrawlJob.builder().jobId("j").request(request).build();
            CrawlStepPlan plan = CrawlStepPlan.from(request);
            assertFalse(plan.isRun(EntityPartitionCrawlStep.STEP_ID),
                    "precondition: an explicit selection without it must not run it");

            Outcome outcome = step.run(job, plan, chunks, config);

            assertEquals(Status.SKIPPED, outcome.status());
            assertEquals(PipelineStepStatus.SKIPPED, stepStatus(job));
            assertTrue(stepMessage(job).contains("step plan"), stepMessage(job));
            verifyNoInteractions(partitions);
        }

        @Test
        @DisplayName("no graph to write the partitions into")
        void noKnowledgeGraphIsNotSilentlyTolerated() {
            when(committer.canWrite()).thenReturn(false);
            UnifiedCrawlJob job = job();

            Outcome outcome = step.run(job, null, chunks, config);

            assertEquals(Status.SKIPPED, outcome.status());
            assertEquals(PipelineStepStatus.SKIPPED, stepStatus(job));
            assertTrue(stepMessage(job).contains("knowledge graph"), stepMessage(job));
            // Without this gate the pass reads every chunk it can find, reports every partition
            // complete, and stores nothing — a coverage claim with no coverage behind it.
            verify(partitions, never()).runStaged(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("no graph constructor to re-read chunks with")
        void aDeploymentWithoutExtractionSkips() {
            when(extraction.hasGraphConstructor()).thenReturn(false);
            UnifiedCrawlJob job = job();

            Outcome outcome = step.run(job, null, chunks, config);

            assertEquals(Status.SKIPPED, outcome.status());
            assertTrue(stepMessage(job).contains("graph constructor"), stepMessage(job));
            verifyNoInteractions(partitions);
        }

        @Test
        @DisplayName("no fact sheet to scope coverage to")
        void aJobWithoutAFactSheetSkips() {
            UnifiedCrawlJob job = job(null);

            Outcome outcome = step.run(job, null, chunks, config);

            assertEquals(Status.SKIPPED, outcome.status());
            assertTrue(stepMessage(job).contains("fact sheet"), stepMessage(job));
            verifyNoInteractions(partitions);
        }

        @Test
        @DisplayName("no chunk text held by this run")
        void nothingToReadSkips() {
            UnifiedCrawlJob job = job();

            Outcome outcome = step.run(job, null, List.of(), config);

            assertEquals(Status.SKIPPED, outcome.status());
            assertTrue(stepMessage(job).contains("chunk text"), stepMessage(job));
            verifyNoInteractions(partitions);
        }

        @Test
        @DisplayName("every skip is reported on the step, not just returned")
        void aSkipIsAlwaysVisibleToTheOperator() {
            when(committer.canWrite()).thenReturn(false);
            UnifiedCrawlJob job = job();

            step.run(job, null, chunks, config);

            assertEquals(List.of("INFO"), eventLevels(job),
                    "a skip has to reach the event log; the step alone does not say why");
        }

        @Test
        @DisplayName("no job at all")
        void aNullJobIsNotAnError() {
            Outcome outcome = step.run(null, null, chunks, config);

            assertEquals(Status.SKIPPED, outcome.status());
            verifyNoInteractions(partitions);
        }
    }

    @Nested
    @DisplayName("What it runs over")
    class Runs {

        @Test
        @DisplayName("one partition per entity group, each against this run's chunks")
        void everyGroupIsPartitionedOnce() {
            expectGroups("acme-corp", "beta-ltd");
            everyPartitionSucceeds();
            UnifiedCrawlJob job = job();

            Outcome outcome = step.run(job, null, chunks, config);

            assertEquals(Status.RAN, outcome.status());
            assertNotNull(outcome.result());
            assertEquals(List.of("acme-corp", "beta-ltd"), List.copyOf(outcome.result().runs().keySet()));
            assertTrue(outcome.result().failed().isEmpty());

            ArgumentCaptor<PartitionRequest> ran = ArgumentCaptor.forClass(PartitionRequest.class);
            verify(partitions, times(2))
                    .runStaged(ran.capture(), same(store), any(), any(), any());
            for (PartitionRequest request : ran.getAllValues()) {
                assertTrue(request.chunks().containsKey("chunk-1"),
                        "a partition that cannot look up its chunk text defers every member");
                assertTrue(request.chunks().containsKey("chunk-2"));
            }
            verify(partitions).groupedRequests(eq(FACT_SHEET), any(), any());
        }

        @Test
        @DisplayName("the step ends COMPLETED with one item per partition")
        void theStepReportsWhatItCovered() {
            expectGroups("acme-corp", "beta-ltd");
            everyPartitionSucceeds();
            UnifiedCrawlJob job = job();

            step.run(job, null, chunks, config);

            PipelineStepProgress recorded = partitionStep(job);
            assertEquals(PipelineStepStatus.COMPLETED, recorded.getStatus().get());
            assertEquals(2, recorded.getTotalItems().get());
            assertEquals(2, recorded.getCompletedItems().get());
            assertEquals(0, recorded.getFailedItems().get());
            assertEquals(2, job.getRecentEvents().stream()
                    .filter(event -> event.getMessage().matches("Partition [0-9]+/[0-9]+ started"))
                    .count(), "every partition must emit a live start snapshot");
            assertEquals(2, job.getRecentEvents().stream()
                    .filter(event -> event.getMessage().matches("Partition [0-9]+/[0-9]+ complete"))
                    .count(), "every successful partition must emit a live completion snapshot");
        }

        @Test
        @DisplayName("the job's current phase names this pass while it runs")
        void theJobPhaseNamesThePass() {
            expectGroups("acme-corp");
            everyPartitionSucceeds();
            UnifiedCrawlJob job = job();
            job.getCurrentPhase().set("VECTOR_INDEXING");

            step.run(job, null, chunks, config);

            // Not the per-step tracker — the job-level phase every "what is it doing now" surface
            // reads (CLI progress line, UI phase chip, the controller's ingest-phase mapping). Left
            // unset, all of them would keep displaying the phase that ran before this one.
            assertEquals(EntityPartitionCrawlStep.STEP_ID, job.getCurrentPhase().get());
        }

        @Test
        @DisplayName("a pass that does no work does not claim the phase")
        void aSkippedPassLeavesThePhaseAlone() {
            UnifiedCrawlJob job = job();
            job.getCurrentPhase().set("VECTOR_INDEXING");
            when(committer.canWrite()).thenReturn(false);

            step.run(job, null, chunks, config);

            assertEquals("VECTOR_INDEXING", job.getCurrentPhase().get(),
                    "a skipped pass that grabs the phase would report itself as the live phase "
                            + "for the rest of the crawl");
        }

        @Test
        @DisplayName("one reuse ledger across every subject")
        void theExtractionLedgerIsSharedAcrossPartitions() {
            expectGroups("acme-corp", "beta-ltd", "gamma-inc");
            everyPartitionSucceeds();

            step.run(job(), null, chunks, config);

            ArgumentCaptor<ExtractionReuse> ledger = ArgumentCaptor.forClass(ExtractionReuse.class);
            verify(partitions, times(3)).runStaged(any(), any(), any(), any(), ledger.capture());
            ExtractionReuse first = ledger.getAllValues().get(0);
            assertNotNull(first);
            for (ExtractionReuse seen : ledger.getAllValues()) {
                // Overlap between partitions is the point of partitioning. A per-subject ledger
                // would extract a chunk naming two subjects twice and call that a cache hit rate.
                assertSame(first, seen, "every partition must share one reuse ledger");
            }
        }

        @Test
        @DisplayName("one commit sink across every subject")
        void theCommitSinkIsBuiltOncePerRun() {
            expectGroups("acme-corp", "beta-ltd");
            everyPartitionSucceeds();

            step.run(job(), null, chunks, config);

            verify(committer, times(1)).sinkFor(any(), same(config), any());
        }

        @Test
        @DisplayName("its extractor is the crawl's own extraction")
        void chunksAreReReadThroughTheCrawlsExtraction() {
            expectGroups("acme-corp");
            everyPartitionSucceeds();
            Graph produced = new Graph();
            when(extraction.extractChunkGraph(any(), any(), any(), any(), any()))
                    .thenReturn(produced);
            UnifiedCrawlJob job = job();

            step.run(job, null, chunks, config);

            ArgumentCaptor<PartitionRequest> request =
                    ArgumentCaptor.forClass(PartitionRequest.class);
            ArgumentCaptor<StagedExtractor> extractor =
                    ArgumentCaptor.forClass(StagedExtractor.class);
            verify(partitions).runStaged(request.capture(), any(), extractor.capture(), any(), any());

            PartitionKey key = PartitionKey.forEntity("acme-corp", "policy-v1", "7");
            Graph learned = extractor.getValue()
                    .extract(member("chunk-1"), EntityPartition.open(key));

            assertSame(produced, learned, "what a chunk taught is what the transaction stages");
            ArgumentCaptor<Graph> graphContext = ArgumentCaptor.forClass(Graph.class);
            ArgumentCaptor<ExtractionTaskContext> task =
                    ArgumentCaptor.forClass(ExtractionTaskContext.class);
            verify(extraction).extractChunkGraph(same(chunks.get(0)), same(config), same(job),
                    graphContext.capture(), task.capture());
            assertEquals(request.getValue().key().id(), task.getValue().partitionId());
            assertEquals(request.getValue().snapshotId(), task.getValue().corpusSnapshotId());
            assertEquals("chunk-1", task.getValue().chunkId());
            assertEquals("SEED", task.getValue().discoveryChannel());
            assertTrue(task.getValue().corpusSnapshotId().startsWith("partition-corpus-v1:"));
            verify(extraction).mergeIntoContext(same(produced), same(graphContext.getValue()),
                    same(config), same(job));
        }

        @Test
        @DisplayName("hydrates exact persisted chunks, overrides them with current text, and defers previews")
        void pooledCorpusUsesOnlyCompleteTextAndCarriesAStableSnapshot() {
            CrawlIndexTrackingCallback corpus = mock(CrawlIndexTrackingCallback.class);
            step.setCorpusAccess(corpus);
            when(corpus.loadCorpusSnapshot(FACT_SHEET)).thenReturn(Optional.of(
                    new CrawlIndexTrackingCallback.CrawlCorpusSnapshot("tracked-snapshot-9", List.of(
                            new CrawlIndexTrackingCallback.CrawlCorpusPassage(
                                    "persisted-full", 7, "Exact text retained from an earlier crawl.",
                                    "hash-full", Map.of("source", "prior"), true),
                            new CrawlIndexTrackingCallback.CrawlCorpusPassage(
                                    "legacy-preview", 8, "First 500 characters only...",
                                    "hash-longer-than-preview", Map.of(), false),
                            new CrawlIndexTrackingCallback.CrawlCorpusPassage(
                                    "chunk-1", 0, "STALE current-run copy",
                                    "hash-stale", Map.of(), true)))));
            expectGroups("acme-corp");
            everyPartitionSucceeds();

            step.run(job(), null, chunks, config);

            ArgumentCaptor<PartitionRequest> request =
                    ArgumentCaptor.forClass(PartitionRequest.class);
            verify(partitions).runStaged(request.capture(), any(), any(), any(), any());
            Map<String, Document> pooled = request.getValue().chunks();
            assertEquals("Exact text retained from an earlier crawl.",
                    pooled.get("persisted-full").getText());
            assertEquals(chunks.get(0).getText(), pooled.get("chunk-1").getText(),
                    "the current crawl is authoritative for a duplicate chunk id");
            assertFalse(pooled.containsKey("legacy-preview"),
                    "a truncated preview must never masquerade as source evidence");
            assertTrue(request.getValue().snapshotId().startsWith("partition-corpus-v1:"));
        }

        @Test
        @DisplayName("a fact sheet with no entities completes rather than fails")
        void nothingToPartitionIsAnOutcomeNotAnError() {
            when(partitions.groupedRequests(any(), any(), any())).thenReturn(List.of());
            UnifiedCrawlJob job = job();

            Outcome outcome = step.run(job, null, chunks, config);

            assertEquals(Status.NOTHING_TO_PARTITION, outcome.status());
            assertNull(outcome.result());
            assertEquals(PipelineStepStatus.COMPLETED, stepStatus(job));
            verify(partitions, never()).runStaged(any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("When a partition falls over")
    class Failures {

        @Test
        @DisplayName("one subject failing does not take the others down")
        void aFailedSubjectIsNamedAndTheRestStillRun() {
            expectGroups("acme-corp", "beta-ltd");
            when(partitions.runStaged(any(), any(), any(), any(), any())).thenAnswer(call -> {
                PartitionRequest request = call.getArgument(0, PartitionRequest.class);
                if ("acme-corp".equals(request.subject())) {
                    throw new IllegalStateException("the model went away");
                }
                return ranClean(request.subject());
            });
            UnifiedCrawlJob job = job();

            Outcome outcome = step.run(job, null, chunks, config);

            assertEquals(Status.RAN, outcome.status());
            assertEquals(List.of("beta-ltd"), List.copyOf(outcome.result().runs().keySet()));
            assertEquals(List.of("acme-corp"), outcome.result().failed());
            assertEquals(PipelineStepStatus.COMPLETED, stepStatus(job));
            assertTrue(eventLevels(job).contains("WARN"),
                    "a subject with no coverage cannot be reported at INFO alongside the rest");
        }

        @Test
        @DisplayName("every subject failing fails the step")
        void noCoverageAtAllIsAFailure() {
            expectGroups("acme-corp", "beta-ltd");
            when(partitions.runStaged(any(), any(), any(), any(), any()))
                    .thenThrow(new IllegalStateException("the graph store is down"));
            UnifiedCrawlJob job = job();

            Outcome outcome = step.run(job, null, chunks, config);

            assertEquals(Status.FAILED, outcome.status(),
                    "the outcome must not say RAN while the step it just wrote says FAILED");
            assertEquals(2, outcome.result().failed().size());
            assertEquals(PipelineStepStatus.FAILED, stepStatus(job));
            assertTrue(eventLevels(job).contains("ERROR"));
        }

        @Test
        @DisplayName("the pass itself falling over is reported, not thrown")
        void theCrawlIsNeverFailedByThisStep() {
            when(partitions.groupedRequests(any(), any(), any()))
                    .thenThrow(new IllegalStateException("grouping blew up"));
            UnifiedCrawlJob job = job();

            Outcome outcome = step.run(job, null, chunks, config);

            assertEquals(Status.FAILED, outcome.status());
            assertEquals("grouping blew up", outcome.detail());
            assertEquals(PipelineStepStatus.FAILED, stepStatus(job));
            assertTrue(eventLevels(job).contains("ERROR"));
        }
    }

    @Nested
    @DisplayName("When the crawl is cancelled")
    class Cancellation {

        @Test
        @DisplayName("it stops between subjects, keeping what committed")
        void cancellationIsHonouredMidPass() {
            expectGroups("acme-corp", "beta-ltd", "gamma-inc");
            UnifiedCrawlJob job = job();
            when(partitions.runStaged(any(), any(), any(), any(), any())).thenAnswer(call -> {
                job.getStatus().set(UnifiedCrawlJob.Status.CANCELLING);
                return ranClean(call.getArgument(0, PartitionRequest.class).subject());
            });

            Outcome outcome = step.run(job, null, chunks, config);

            assertEquals(Status.CANCELLED, outcome.status());
            assertEquals(1, outcome.result().runs().size(),
                    "the partition that finished before the cancel is still committed coverage");
            verify(partitions, times(1)).runStaged(any(), any(), any(), any(), any());
            assertEquals(PipelineStepStatus.CANCELLED, stepStatus(job));
        }

        @Test
        @DisplayName("a job cancelled before it starts runs nothing")
        void anAlreadyCancelledJobDoesNoWork() {
            expectGroups("acme-corp");
            UnifiedCrawlJob job = job();
            job.getStatus().set(UnifiedCrawlJob.Status.CANCELLED);

            Outcome outcome = step.run(job, null, chunks, config);

            assertEquals(Status.CANCELLED, outcome.status());
            assertTrue(outcome.result().runs().isEmpty());
            verify(partitions, never()).runStaged(any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("What it is honest about not having looked at")
    class CoverageHonesty {

        private DiscoveryPolicy policyUsed(UnifiedCrawlJob job) {
            expectGroups("acme-corp");
            everyPartitionSucceeds();
            step.run(job, null, chunks, config);

            ArgumentCaptor<DiscoveryPolicy> policy =
                    ArgumentCaptor.forClass(DiscoveryPolicy.class);
            verify(partitions).groupedRequests(any(), any(), policy.capture());
            return policy.getValue();
        }

        @Test
        @DisplayName("no vector store: the policy records that retrieval never ran")
        void withoutAnIndexTheClaimIsGraphOnly() {
            when(partitions.hasVectorStore()).thenReturn(false);

            DiscoveryPolicy used = policyUsed(job());

            assertNotNull(used, "the default policy would claim channels that never ran");
            assertEquals(EntityPartitionCrawlService.GRAPH_ONLY_POLICY_VERSION, used.version());
        }

        @Test
        @DisplayName("an index this run never wrote into is the same case")
        void aConfiguredStoreIsNotTheSameAsAnIndexedRun() {
            when(partitions.hasVectorStore()).thenReturn(true);
            UnifiedCrawlJob job = job();
            steps.skipPipelineStep(job, "VECTOR_INDEXING", "vector indexing disabled");

            DiscoveryPolicy used = policyUsed(job);

            assertNotNull(used, "retrieval would search a corpus that predates this crawl");
            assertEquals(EntityPartitionCrawlService.GRAPH_ONLY_POLICY_VERSION, used.version());
        }

        @Test
        @DisplayName("once indexing landed, each request keeps its own policy")
        void anIndexedRunGetsTheFullPolicy() {
            when(partitions.hasVectorStore()).thenReturn(true);
            UnifiedCrawlJob job = job();
            steps.completePipelineStep(job, "VECTOR_INDEXING", 2, "indexed");

            assertNull(policyUsed(job),
                    "null leaves each request on the default policy, which is the full one");
        }
    }

    @Nested
    @DisplayName("What the configured settings change, and what they cannot")
    class Configuration {

        private GraphExtractionConfig with(PartitionPassConfig.PartitionPassConfigBuilder pass) {
            return GraphExtractionConfig.builder().partition(pass.build()).build();
        }

        private void runWith(UnifiedCrawlJob job, GraphExtractionConfig using) {
            expectGroups("acme-corp");
            everyPartitionSucceeds();
            step.run(job, null, chunks, using);
        }

        private GroupingPolicy groupingUsed(GraphExtractionConfig using) {
            runWith(job(), using);
            ArgumentCaptor<GroupingPolicy> grouping = ArgumentCaptor.forClass(GroupingPolicy.class);
            verify(partitions).groupedRequests(eq(FACT_SHEET), grouping.capture(), any());
            return grouping.getValue();
        }

        private PartitionRequest requestRun(GraphExtractionConfig using) {
            runWith(job(), using);
            ArgumentCaptor<PartitionRequest> request =
                    ArgumentCaptor.forClass(PartitionRequest.class);
            verify(partitions).runStaged(request.capture(), any(), any(), any(), any());
            return request.getValue();
        }

        private DiscoveryPolicy discoveryUsed(UnifiedCrawlJob job, GraphExtractionConfig using) {
            runWith(job, using);
            ArgumentCaptor<DiscoveryPolicy> policy =
                    ArgumentCaptor.forClass(DiscoveryPolicy.class);
            verify(partitions).groupedRequests(any(), any(), policy.capture());
            return policy.getValue();
        }

        @Test
        @DisplayName("an untouched configuration groups exactly as it did before it was settable")
        void defaultsReproduceTheStockGrouping() {
            GroupingPolicy used = groupingUsed(new GraphExtractionConfig());

            assertEquals(GroupingPolicy.defaults().version(), used.version());
            assertEquals(GroupingPolicy.defaults().maxGroupSize(), used.maxGroupSize());
            assertFalse(used.version().contains(PartitionPassConfig.TUNED_MARKER),
                    "nothing moved, so nothing should be filed as a tuned partition");
        }

        @Test
        @DisplayName("no configuration at all is the same as an untouched one")
        void aMissingConfigIsNotAMissingPolicy() {
            GroupingPolicy used = groupingUsed(null);

            assertEquals(GroupingPolicy.defaults().version(), used.version());
        }

        @Test
        @DisplayName("a tuned group size reaches grouping, under a version that says it was tuned")
        void aTunedKnobTravelsWithItsOwnVersion() {
            GroupingPolicy used = groupingUsed(with(PartitionPassConfig.builder().maxGroupSize(3)));

            assertEquals(3, used.maxGroupSize());
            assertTrue(used.version().startsWith(
                            GroupingPolicy.defaults().version() + PartitionPassConfig.TUNED_MARKER),
                    "a differently-grouped claim filed under the stock version would invite the "
                            + "one comparison the version exists to prevent");
        }

        @Test
        @DisplayName("a named grouping version is used verbatim, not fingerprinted")
        void aNamedVersionSurvivesTheStep() {
            GroupingPolicy used = groupingUsed(with(PartitionPassConfig.builder()
                    .groupingVersion("fy26-desks").maxGroupSize(3)));

            assertEquals("fy26-desks", used.version());
            assertEquals(3, used.maxGroupSize());
        }

        @Test
        @DisplayName("the opening event names the grouping the claims are relative to")
        void theEventSaysWhatTheClaimsAreRelativeTo() {
            UnifiedCrawlJob job = job();
            runWith(job, with(PartitionPassConfig.builder().groupingVersion("fy26-desks")));

            String details = job.getRecentEvents().stream()
                    .filter(e -> EntityPartitionCrawlStep.STEP_ID.equals(e.getPhase()))
                    .map(UnifiedCrawlJob.StageEvent::getDetails)
                    .filter(d -> d != null && d.contains("grouping "))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the pass never said how it grouped"));
            assertTrue(details.contains("fy26-desks"), details);
        }

        @Test
        @DisplayName("configured batch caps reach the request that runs")
        void batchCapsReachTheRequest() {
            PartitionRequest ran = requestRun(with(PartitionPassConfig.builder()
                    .maxItemsPerBatch(4).targetCostPerBatch(900L)));

            assertEquals(4, ran.maxItemsPerBatch());
            assertEquals(900L, ran.targetCostPerBatch());
        }

        @Test
        @DisplayName("a cost cap on its own leaves the request's own batch size alone")
        void aCostCapDoesNotResizeBatches() {
            PartitionRequest ran = requestRun(
                    with(PartitionPassConfig.builder().targetCostPerBatch(900L)));

            assertEquals(EntityPartitionCrawlService.DEFAULT_MAX_ITEMS_PER_BATCH,
                    ran.maxItemsPerBatch());
            assertEquals(900L, ran.targetCostPerBatch());
        }

        @Test
        @DisplayName("no caps configured leaves batching untouched")
        void noCapsLeavesBatchingUntouched() {
            PartitionRequest ran = requestRun(new GraphExtractionConfig());

            assertEquals(EntityPartitionCrawlService.DEFAULT_MAX_ITEMS_PER_BATCH,
                    ran.maxItemsPerBatch());
            assertEquals(0L, ran.targetCostPerBatch());
        }

        @Test
        @DisplayName("batch caps are not a re-grouping: the version stays put")
        void batchCapsDoNotRedefineThePartition() {
            GroupingPolicy used = groupingUsed(with(PartitionPassConfig.builder()
                    .maxItemsPerBatch(4).targetCostPerBatch(900L)));

            assertEquals(GroupingPolicy.defaults().version(), used.version(),
                    "how the work was cut up does not change what was looked at");
        }

        @Test
        @DisplayName("configured channels reach discovery once the index is searchable")
        void configuredChannelsReachDiscovery() {
            when(partitions.hasVectorStore()).thenReturn(true);
            UnifiedCrawlJob job = job();
            steps.completePipelineStep(job, "VECTOR_INDEXING", 2, "indexed");

            DiscoveryPolicy used = discoveryUsed(job, with(PartitionPassConfig.builder()
                    .channels(List.of("seed", "semantic"))));

            assertNotNull(used, "a configured policy has to override the request's default");
            assertEquals(Set.of(DiscoveryChannel.SEED, DiscoveryChannel.SEMANTIC),
                    used.channels());
        }

        @Test
        @DisplayName("tuning the pass cannot make it claim an index nobody searched")
        void aConfiguredPolicyIsStillNarrowedWithoutAnIndex() {
            when(partitions.hasVectorStore()).thenReturn(false);

            DiscoveryPolicy used = discoveryUsed(job(), with(PartitionPassConfig.builder()
                    .channels(List.of("SEED", "SEMANTIC"))));

            assertNotNull(used);
            assertFalse(used.channels().contains(DiscoveryChannel.SEMANTIC),
                    "the channel needed an index this run never wrote into");
            assertTrue(used.version().endsWith(EntityPartitionCrawlService.GRAPH_ONLY_SUFFIX),
                    "the shortfall has to be in the version or the claim looks like a full one");
        }

        @Test
        @DisplayName("an unrecognised channel fails the pass rather than looking in fewer places")
        void anUnknownChannelIsFatalToThePass() {
            UnifiedCrawlJob job = job();

            Outcome outcome = step.run(job, null, chunks,
                    with(PartitionPassConfig.builder().channels(List.of("telepathy"))));

            assertEquals(Status.FAILED, outcome.status());
            assertTrue(outcome.detail().contains("telepathy"), outcome.detail());
            assertEquals(PipelineStepStatus.FAILED, stepStatus(job));
            verify(partitions, never()).runStaged(any(), any(), any(), any(), any());
        }
    }
}
