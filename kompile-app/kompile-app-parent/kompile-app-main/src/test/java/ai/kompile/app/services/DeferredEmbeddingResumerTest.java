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

package ai.kompile.app.services;

import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeferredEmbeddingResumerTest {

    @Mock
    private UnifiedCrawlService crawlService;
    @Mock
    private ResourceGovernor governor;
    @InjectMocks
    private DeferredEmbeddingResumer resumer;

    private UnifiedCrawlJob job(String id, UnifiedCrawlJob.Status status, boolean hasChunks) {
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getStatus()).thenReturn(new AtomicReference<>(status));
        return job;
    }

    @Test
    void skipsWhenNoGpuHeadroom() {
        when(governor.hasGpuHeadroom("EMBEDDING")).thenReturn(false);
        assertEquals(0, resumer.resumeEligibleJobs());
        verify(crawlService, never()).resumeDeferredEmbedding(anyString());
    }

    @Test
    void skipsNonPendingJobs() {
        when(governor.hasGpuHeadroom("EMBEDDING")).thenReturn(true);
        UnifiedCrawlJob completed = mock(UnifiedCrawlJob.class);
        when(completed.getStatus()).thenReturn(new AtomicReference<>(UnifiedCrawlJob.Status.COMPLETED));
        when(crawlService.getAllJobs()).thenReturn(List.of(completed));

        assertEquals(0, resumer.resumeEligibleJobs());
        verify(crawlService, never()).resumeDeferredEmbedding(anyString());
    }

    @Test
    void skipsPendingJobsWithNoDeferredChunks() {
        when(governor.hasGpuHeadroom("EMBEDDING")).thenReturn(true);
        UnifiedCrawlJob pending = mock(UnifiedCrawlJob.class);
        when(pending.getStatus())
                .thenReturn(new AtomicReference<>(UnifiedCrawlJob.Status.COMPLETED_PENDING_EMBEDDING));
        when(pending.getDeferredEmbeddingChunks()).thenReturn(List.of());
        when(crawlService.getAllJobs()).thenReturn(List.of(pending));

        assertEquals(0, resumer.resumeEligibleJobs());
        verify(crawlService, never()).resumeDeferredEmbedding(anyString());
    }

    @Test
    void resumesEligiblePendingJob() {
        when(governor.hasGpuHeadroom("EMBEDDING")).thenReturn(true);
        UnifiedCrawlJob pending = mock(UnifiedCrawlJob.class);
        when(pending.getStatus())
                .thenReturn(new AtomicReference<>(UnifiedCrawlJob.Status.COMPLETED_PENDING_EMBEDDING));
        when(pending.getDeferredEmbeddingChunks()).thenReturn(List.of(mock(Document.class)));
        when(pending.getJobId()).thenReturn("job-1");
        when(crawlService.getAllJobs()).thenReturn(List.of(pending));
        when(crawlService.resumeDeferredEmbedding("job-1")).thenReturn(3);

        assertEquals(1, resumer.resumeEligibleJobs());
        verify(crawlService).resumeDeferredEmbedding("job-1");
    }
}
