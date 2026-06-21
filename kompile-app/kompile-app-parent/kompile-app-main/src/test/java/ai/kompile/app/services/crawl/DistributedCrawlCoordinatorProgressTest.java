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

import ai.kompile.core.crawl.graph.CrawlProgressEvent;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest.DistributionConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest.PartitionStrategy;
import ai.kompile.core.crawl.graph.UnifiedCrawlSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DistributedCrawlCoordinatorProgressTest {

    private DistributedCrawlCoordinator coordinator;
    private ApplicationEventPublisher publisher;

    @BeforeEach
    void setUp() {
        coordinator = new DistributedCrawlCoordinator(
                List.of(new DistributedCrawlCoordinatorTest.MockExternalDelegate()), null, new ObjectMapper());
        publisher = mock(ApplicationEventPublisher.class);
        ReflectionTestUtils.setField(coordinator, "eventPublisher", publisher);
        ReflectionTestUtils.setField(coordinator, "aggregator", new DistributedCrawlAggregator());
    }

    private DistributedCrawlSession start1() {
        UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                .name("Progress test")
                .sources(List.of(UnifiedCrawlSource.builder().label("A").pathOrUrl("a").build()))
                .distribution(DistributionConfig.builder().partitionStrategy(PartitionStrategy.PER_SOURCE).build())
                .build();
        return coordinator.startDistributed(request);
    }

    @Test
    void handleWorkerProgressStoresSnapshotAndPublishes() {
        DistributedCrawlSession session = start1();
        String workerId = session.getWorkers().keySet().iterator().next();
        UnifiedCrawlJob.ProgressSnapshot snap = UnifiedCrawlJob.ProgressSnapshot.builder()
                .entitiesExtracted(7).progressPercent(40).build();

        coordinator.handleWorkerProgress(session.getSessionId(), workerId, snap);

        assertSame(snap, session.getWorkers().get(workerId).getLatestSnapshot());
        ArgumentCaptor<CrawlProgressEvent> cap = ArgumentCaptor.forClass(CrawlProgressEvent.class);
        verify(publisher, atLeastOnce()).publishEvent(cap.capture());
        CrawlProgressEvent ev = cap.getValue();
        assertEquals("distributed-" + session.getSessionId(), ev.getJobId());
        assertTrue(ev.getProgressSnapshot() instanceof UnifiedCrawlJob.ProgressSnapshot);
    }

    @Test
    void unknownSessionIsNoOp() {
        coordinator.handleWorkerProgress("nope", "w", UnifiedCrawlJob.ProgressSnapshot.builder().build());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void nullSnapshotIsNoOp() {
        DistributedCrawlSession session = start1();
        String workerId = session.getWorkers().keySet().iterator().next();
        coordinator.handleWorkerProgress(session.getSessionId(), workerId, null);
        assertNull(session.getWorkers().get(workerId).getLatestSnapshot());
        verify(publisher, never()).publishEvent(any());
    }
}
