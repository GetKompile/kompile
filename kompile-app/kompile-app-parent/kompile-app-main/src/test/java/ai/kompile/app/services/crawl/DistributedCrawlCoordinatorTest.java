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

import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest.DistributionConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest.PartitionStrategy;
import ai.kompile.core.crawl.graph.UnifiedCrawlSource;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import ai.kompile.app.services.scheduler.ExternalJobSchedulerDelegate;
import ai.kompile.app.services.scheduler.ExternalJobSchedulerDelegate.ExternalJobRef;
import ai.kompile.app.services.scheduler.JobResourceProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class DistributedCrawlCoordinatorTest {

    private DistributedCrawlCoordinator coordinator;
    private MockExternalDelegate mockDelegate;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockDelegate = new MockExternalDelegate();
        objectMapper = new ObjectMapper();
        coordinator = new DistributedCrawlCoordinator(List.of(mockDelegate), objectMapper);
    }

    @Test
    void testPartition_perSource() {
        List<UnifiedCrawlSource> sources = List.of(
                UnifiedCrawlSource.builder().label("S3").sourceType(SourceType.S3)
                        .pathOrUrl("bucket/a").build(),
                UnifiedCrawlSource.builder().label("SFTP").sourceType(SourceType.SFTP)
                        .pathOrUrl("/remote/b").build(),
                UnifiedCrawlSource.builder().label("Local").sourceType(SourceType.DIRECTORY)
                        .pathOrUrl("/data/c").build()
        );
        DistributionConfig config = DistributionConfig.builder()
                .partitionStrategy(PartitionStrategy.PER_SOURCE)
                .build();

        List<List<UnifiedCrawlSource>> partitions = coordinator.partitionSources(sources, config);
        assertEquals(3, partitions.size());
        assertEquals(1, partitions.get(0).size());
        assertEquals("S3", partitions.get(0).get(0).getLabel());
        assertEquals("SFTP", partitions.get(1).get(0).getLabel());
        assertEquals("Local", partitions.get(2).get(0).getLabel());
    }

    @Test
    void testPartition_roundRobin() {
        List<UnifiedCrawlSource> sources = List.of(
                UnifiedCrawlSource.builder().label("A").pathOrUrl("a").build(),
                UnifiedCrawlSource.builder().label("B").pathOrUrl("b").build(),
                UnifiedCrawlSource.builder().label("C").pathOrUrl("c").build(),
                UnifiedCrawlSource.builder().label("D").pathOrUrl("d").build(),
                UnifiedCrawlSource.builder().label("E").pathOrUrl("e").build()
        );
        DistributionConfig config = DistributionConfig.builder()
                .partitionStrategy(PartitionStrategy.ROUND_ROBIN)
                .workerCount(2)
                .build();

        List<List<UnifiedCrawlSource>> partitions = coordinator.partitionSources(sources, config);
        assertEquals(2, partitions.size());
        // Worker 0: A, C, E
        assertEquals(3, partitions.get(0).size());
        assertEquals("A", partitions.get(0).get(0).getLabel());
        assertEquals("C", partitions.get(0).get(1).getLabel());
        assertEquals("E", partitions.get(0).get(2).getLabel());
        // Worker 1: B, D
        assertEquals(2, partitions.get(1).size());
        assertEquals("B", partitions.get(1).get(0).getLabel());
        assertEquals("D", partitions.get(1).get(1).getLabel());
    }

    @Test
    void testPartition_hashShard() {
        List<UnifiedCrawlSource> sources = List.of(
                UnifiedCrawlSource.builder().label("A").pathOrUrl("a").build(),
                UnifiedCrawlSource.builder().label("B").pathOrUrl("b").build()
        );
        DistributionConfig config = DistributionConfig.builder()
                .partitionStrategy(PartitionStrategy.HASH_SHARD)
                .workerCount(3)
                .build();

        List<List<UnifiedCrawlSource>> partitions = coordinator.partitionSources(sources, config);
        assertEquals(3, partitions.size());
        // Each worker gets all sources
        for (List<UnifiedCrawlSource> partition : partitions) {
            assertEquals(2, partition.size());
        }
    }

    @Test
    void testStartDistributed() {
        UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                .name("Test distributed crawl")
                .sources(List.of(
                        UnifiedCrawlSource.builder().label("Source A")
                                .sourceType(SourceType.S3).pathOrUrl("bucket/a").build(),
                        UnifiedCrawlSource.builder().label("Source B")
                                .sourceType(SourceType.SFTP).pathOrUrl("/sftp/b").build()
                ))
                .distribution(DistributionConfig.builder()
                        .partitionStrategy(PartitionStrategy.PER_SOURCE)
                        .build())
                .build();

        DistributedCrawlSession session = coordinator.startDistributed(request);
        assertNotNull(session);
        assertNotNull(session.getSessionId());
        assertEquals(2, session.getTotalWorkers());
        assertEquals(DistributedCrawlSession.Status.RUNNING, session.getStatus());
        assertEquals(2, session.getWorkers().size());
        assertEquals(2, mockDelegate.submittedJobs.size());
    }

    @Test
    void testWorkerCallback() {
        UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                .name("Callback test")
                .sources(List.of(
                        UnifiedCrawlSource.builder().label("Source A")
                                .pathOrUrl("a").build()
                ))
                .distribution(DistributionConfig.builder()
                        .partitionStrategy(PartitionStrategy.PER_SOURCE)
                        .build())
                .build();

        DistributedCrawlSession session = coordinator.startDistributed(request);
        String sessionId = session.getSessionId();
        String workerId = session.getWorkers().keySet().iterator().next();

        // Simulate worker completion
        coordinator.handleWorkerCallback(sessionId, workerId, true,
                "Done", Map.of("documentsProcessed", 50));

        assertEquals(1, session.getCompletedWorkers().get());
        assertTrue(session.isAllWorkersFinished());
        assertEquals(DistributedCrawlSession.Status.COMPLETED, session.getStatus());
    }

    @Test
    void testWorkerCallback_partialFailure() {
        UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                .name("Partial failure test")
                .sources(List.of(
                        UnifiedCrawlSource.builder().label("A").pathOrUrl("a").build(),
                        UnifiedCrawlSource.builder().label("B").pathOrUrl("b").build()
                ))
                .distribution(DistributionConfig.builder()
                        .partitionStrategy(PartitionStrategy.PER_SOURCE)
                        .build())
                .build();

        DistributedCrawlSession session = coordinator.startDistributed(request);
        String sessionId = session.getSessionId();
        Iterator<String> workerIds = session.getWorkers().keySet().iterator();
        String worker0 = workerIds.next();
        String worker1 = workerIds.next();

        coordinator.handleWorkerCallback(sessionId, worker0, true, "OK", Map.of());
        coordinator.handleWorkerCallback(sessionId, worker1, false, "Connection refused", null);

        assertTrue(session.isAllWorkersFinished());
        assertEquals(DistributedCrawlSession.Status.PARTIALLY_COMPLETED, session.getStatus());
        assertEquals(1, session.getCompletedWorkers().get());
        assertEquals(1, session.getFailedWorkers().get());
    }

    @Test
    void testCancelSession() {
        UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                .name("Cancel test")
                .sources(List.of(
                        UnifiedCrawlSource.builder().label("A").pathOrUrl("a").build()
                ))
                .distribution(DistributionConfig.builder()
                        .partitionStrategy(PartitionStrategy.PER_SOURCE)
                        .build())
                .build();

        DistributedCrawlSession session = coordinator.startDistributed(request);
        assertTrue(coordinator.cancelSession(session.getSessionId()));
        assertEquals(DistributedCrawlSession.Status.CANCELLED, session.getStatus());
    }

    @Test
    void testSessionSnapshot() {
        UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                .name("Snapshot test")
                .sources(List.of(
                        UnifiedCrawlSource.builder().label("S3 docs")
                                .sourceType(SourceType.S3).pathOrUrl("bucket/docs").build()
                ))
                .distribution(DistributionConfig.builder()
                        .partitionStrategy(PartitionStrategy.PER_SOURCE)
                        .build())
                .build();

        DistributedCrawlSession session = coordinator.startDistributed(request);
        Map<String, Object> snapshot = session.toSnapshot();

        assertEquals(session.getSessionId(), snapshot.get("sessionId"));
        assertEquals("RUNNING", snapshot.get("status"));
        assertEquals(1, snapshot.get("totalWorkers"));
        assertEquals("Snapshot test", snapshot.get("name"));
        assertNotNull(snapshot.get("workers"));
    }

    @Test
    void testCleanupSessions() {
        UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                .name("Cleanup test")
                .sources(List.of(
                        UnifiedCrawlSource.builder().label("A").pathOrUrl("a").build()
                ))
                .distribution(DistributionConfig.builder()
                        .partitionStrategy(PartitionStrategy.PER_SOURCE)
                        .build())
                .build();

        DistributedCrawlSession session = coordinator.startDistributed(request);
        String sessionId = session.getSessionId();
        String workerId = session.getWorkers().keySet().iterator().next();

        // Complete the session
        coordinator.handleWorkerCallback(sessionId, workerId, true, "Done", Map.of());
        assertEquals(1, coordinator.getAllSessions().size());

        int removed = coordinator.cleanupSessions();
        assertEquals(1, removed);
        assertEquals(0, coordinator.getAllSessions().size());
    }

    @Test
    void testStartDistributed_noDistributionConfig() {
        UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                .name("No dist config")
                .sources(List.of(
                        UnifiedCrawlSource.builder().label("A").pathOrUrl("a").build()
                ))
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> coordinator.startDistributed(request));
    }

    @Test
    void testStartDistributed_noSources() {
        UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                .name("No sources")
                .sources(List.of())
                .distribution(DistributionConfig.builder().build())
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> coordinator.startDistributed(request));
    }

    /**
     * Mock implementation of ExternalJobSchedulerDelegate for testing.
     */
    static class MockExternalDelegate implements ExternalJobSchedulerDelegate {
        final List<Map<String, Object>> submittedJobs = Collections.synchronizedList(new ArrayList<>());

        @Override
        public String getMode() {
            return "mock";
        }

        @Override
        public CompletableFuture<ExternalJobRef> submitJob(
                String jobId, String jobType, String description,
                JobResourceProfile resourceProfile, Map<String, Object> metadata) {
            submittedJobs.add(Map.of(
                    "jobId", jobId,
                    "jobType", jobType,
                    "description", description
            ));
            return CompletableFuture.completedFuture(
                    new ExternalJobRef("ext-" + jobId, "PENDING", "submitted"));
        }

        @Override
        public CompletableFuture<Boolean> cancelJob(String jobId, String externalRef) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<ExternalJobStatus> getJobStatus(String jobId, String externalRef) {
            return CompletableFuture.completedFuture(
                    new ExternalJobStatus(externalRef, "RUNNING", "", Map.of()));
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }
}
