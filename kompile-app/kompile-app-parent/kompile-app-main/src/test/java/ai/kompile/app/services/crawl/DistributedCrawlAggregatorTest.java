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

package ai.kompile.app.services.crawl;

import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob.LlmCallRecord;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob.PipelineStepSnapshot;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob.ProgressSnapshot;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob.TuningDecision;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DistributedCrawlAggregatorTest {

    private final DistributedCrawlAggregator aggregator = new DistributedCrawlAggregator();

    private DistributedCrawlSession sessionWith(DistributedCrawlSession.Status status, int totalWorkers) {
        return DistributedCrawlSession.builder()
                .sessionId("s1")
                .status(status)
                .totalWorkers(totalWorkers)
                .startedAt(Instant.now())
                .build();
    }

    @Test
    void workerIndexParsing() {
        assertEquals(0, DistributedCrawlAggregator.workerIndex("s1-worker-0"));
        assertEquals(3, DistributedCrawlAggregator.workerIndex("s1-worker-3"));
        assertEquals(3, DistributedCrawlAggregator.workerIndex("s1-worker-3-r1")); // reassigned id
        assertEquals(0, DistributedCrawlAggregator.workerIndex("no-index"));
        assertEquals(0, DistributedCrawlAggregator.workerIndex(null));
    }

    @Test
    void aggregateNoSnapshotsYieldsZero() {
        DistributedCrawlSession s = sessionWith(DistributedCrawlSession.Status.RUNNING, 2);
        s.addWorker("s1-worker-0", List.of());
        s.addWorker("s1-worker-1", List.of());
        ProgressSnapshot agg = aggregator.aggregate(s);
        assertEquals(0, agg.getEntitiesExtracted());
        assertEquals(0, agg.getProgressPercent());
        assertNull(agg.getPipelineSteps());
        assertEquals("distributed-s1", agg.getJobId());
    }

    @Test
    void aggregateSumsCounters() {
        DistributedCrawlSession s = sessionWith(DistributedCrawlSession.Status.RUNNING, 2);
        s.addWorker("s1-worker-0", List.of());
        s.addWorker("s1-worker-1", List.of());
        s.updateWorkerSnapshot("s1-worker-0", ProgressSnapshot.builder()
                .entitiesExtracted(50).relationshipsExtracted(10).chunksCreated(100).build());
        s.updateWorkerSnapshot("s1-worker-1", ProgressSnapshot.builder()
                .entitiesExtracted(50).relationshipsExtracted(5).chunksCreated(40).build());
        ProgressSnapshot agg = aggregator.aggregate(s);
        assertEquals(100, agg.getEntitiesExtracted());
        assertEquals(15, agg.getRelationshipsExtracted());
        assertEquals(140, agg.getChunksCreated());
    }

    @Test
    void aggregateTagsStepsByWorker() {
        DistributedCrawlSession s = sessionWith(DistributedCrawlSession.Status.RUNNING, 2);
        s.addWorker("s1-worker-0", List.of());
        s.addWorker("s1-worker-1", List.of());
        s.updateWorkerSnapshot("s1-worker-0", ProgressSnapshot.builder()
                .pipelineSteps(List.of(PipelineStepSnapshot.builder()
                        .stepId("GRAPH_EXTRACTION").displayName("Graph Extraction").build())).build());
        s.updateWorkerSnapshot("s1-worker-1", ProgressSnapshot.builder()
                .pipelineSteps(List.of(PipelineStepSnapshot.builder()
                        .stepId("GRAPH_EXTRACTION").displayName("Graph Extraction").build())).build());
        ProgressSnapshot agg = aggregator.aggregate(s);
        assertNotNull(agg.getPipelineSteps());
        assertEquals(2, agg.getPipelineSteps().size());
        assertEquals("w0:GRAPH_EXTRACTION", agg.getPipelineSteps().get(0).getStepId());
        assertEquals("w1:GRAPH_EXTRACTION", agg.getPipelineSteps().get(1).getStepId());
        assertTrue(agg.getPipelineSteps().get(0).getDisplayName().startsWith("[W0]"));
    }

    @Test
    void aggregateMergesAndOrdersTuningDecisions() {
        DistributedCrawlSession s = sessionWith(DistributedCrawlSession.Status.RUNNING, 2);
        s.addWorker("s1-worker-0", List.of());
        s.addWorker("s1-worker-1", List.of());
        Instant t0 = Instant.parse("2026-06-21T10:00:00Z");
        Instant t1 = Instant.parse("2026-06-21T10:00:05Z");
        s.updateWorkerSnapshot("s1-worker-0", ProgressSnapshot.builder()
                .recentTuningDecisions(List.of(TuningDecision.builder()
                        .timestamp(t0).stage("GRAPH_EXTRACTION").direction("UP").build())).build());
        s.updateWorkerSnapshot("s1-worker-1", ProgressSnapshot.builder()
                .recentTuningDecisions(List.of(TuningDecision.builder()
                        .timestamp(t1).stage("GRAPH_EXTRACTION").direction("DOWN").build())).build());
        ProgressSnapshot agg = aggregator.aggregate(s);
        assertEquals(2, agg.getRecentTuningDecisions().size());
        // ascending by timestamp → t0 first, t1 last (monitor shows the tail)
        assertEquals(t0, agg.getRecentTuningDecisions().get(0).getTimestamp());
        assertEquals(t1, agg.getRecentTuningDecisions().get(1).getTimestamp());
        // Phase F: stages are worker-tagged so the monitor attributes them per worker.
        assertEquals("w0:GRAPH_EXTRACTION", agg.getRecentTuningDecisions().get(0).getStage());
        assertEquals("w1:GRAPH_EXTRACTION", agg.getRecentTuningDecisions().get(1).getStage());
    }

    @Test
    void aggregateTagsDecomposedLlmCallsAndPreservesTheirScope() {
        DistributedCrawlSession s = sessionWith(DistributedCrawlSession.Status.RUNNING, 1);
        s.addWorker("s1-worker-3", List.of());
        LlmCallRecord call = LlmCallRecord.builder()
                .timestamp(Instant.parse("2026-06-21T10:00:00Z"))
                .backendId("local-serving")
                .taskType("llm")
                .phase("ENTITY_PARTITIONS")
                .passId("mentions")
                .passInvocation(4)
                .taskId("task-4")
                .partitionId("partition-acme")
                .chunkId("chunk-7")
                .corpusSnapshotId("corpus-v1:abc")
                .graphRevision("graph-12")
                .graphEntities(14)
                .graphRelationships(9)
                .success(true)
                .build();
        s.updateWorkerSnapshot("s1-worker-3", ProgressSnapshot.builder()
                .recentLlmCalls(List.of(call)).build());

        LlmCallRecord aggregated = aggregator.aggregate(s).getRecentLlmCalls().get(0);

        assertEquals("w3:ENTITY_PARTITIONS", aggregated.getPhase());
        assertEquals("mentions", aggregated.getPassId());
        assertEquals(4, aggregated.getPassInvocation());
        assertEquals("partition-acme", aggregated.getPartitionId());
        assertEquals("chunk-7", aggregated.getChunkId());
        assertEquals("corpus-v1:abc", aggregated.getCorpusSnapshotId());
        assertEquals("graph-12", aggregated.getGraphRevision());
        assertEquals(14, aggregated.getGraphEntities());
        assertEquals(9, aggregated.getGraphRelationships());
    }

    @Test
    void aggregateAveragesProgress() {
        DistributedCrawlSession s = sessionWith(DistributedCrawlSession.Status.RUNNING, 2);
        s.addWorker("s1-worker-0", List.of());
        s.addWorker("s1-worker-1", List.of());
        s.updateWorkerSnapshot("s1-worker-0", ProgressSnapshot.builder().progressPercent(100).build());
        s.updateWorkerSnapshot("s1-worker-1", ProgressSnapshot.builder().progressPercent(50).build());
        assertEquals(75, aggregator.aggregate(s).getProgressPercent());
    }

    @Test
    void aggregateMapsPartialCompletedToCompleted() {
        DistributedCrawlSession s = sessionWith(DistributedCrawlSession.Status.PARTIALLY_COMPLETED, 1);
        s.addWorker("s1-worker-0", List.of());
        s.updateWorkerSnapshot("s1-worker-0", ProgressSnapshot.builder().build());
        assertEquals(UnifiedCrawlJob.Status.COMPLETED, aggregator.aggregate(s).getStatus());
    }

    @Test
    void aggregateUsesMaxMemoryAcrossWorkers() {
        DistributedCrawlSession s = sessionWith(DistributedCrawlSession.Status.RUNNING, 2);
        s.addWorker("s1-worker-0", List.of());
        s.addWorker("s1-worker-1", List.of());
        s.updateWorkerSnapshot("s1-worker-0", ProgressSnapshot.builder().memoryUsagePercent(40).build());
        s.updateWorkerSnapshot("s1-worker-1", ProgressSnapshot.builder().memoryUsagePercent(85).build());
        assertEquals(85, aggregator.aggregate(s).getMemoryUsagePercent());
    }
}
