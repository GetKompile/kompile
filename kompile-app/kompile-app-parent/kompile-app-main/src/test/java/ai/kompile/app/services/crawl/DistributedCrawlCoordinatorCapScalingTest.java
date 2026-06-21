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

import ai.kompile.app.config.ResourceSchedulerConfig;
import ai.kompile.app.services.scheduler.ExternalJobSchedulerDelegate;
import ai.kompile.app.services.scheduler.ResourceSchedulerConfigService;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig.ProcessingBackend;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DistributedCrawlCoordinatorCapScalingTest {

    private DistributedCrawlCoordinator coordinatorWith(ResourceSchedulerConfig cfg) {
        ExternalJobSchedulerDelegate delegate = mock(ExternalJobSchedulerDelegate.class);
        when(delegate.getMode()).thenReturn("mock");
        ResourceSchedulerConfigService cfgService = mock(ResourceSchedulerConfigService.class);
        when(cfgService.getConfiguration()).thenReturn(cfg);
        return new DistributedCrawlCoordinator(List.of(delegate), cfgService, new ObjectMapper());
    }

    private ResourceSchedulerConfig scalingConfig(boolean enabled, int defaultParallelism) {
        ResourceSchedulerConfig cfg = new ResourceSchedulerConfig();
        cfg.setClusterBackendCapScalingEnabled(enabled);
        cfg.setClusterDefaultRemoteParallelism(defaultParallelism);
        return cfg;
    }

    @Test
    void noScalingWhenDisabled() {
        DistributedCrawlCoordinator c = coordinatorWith(scalingConfig(false, 2));
        UnifiedCrawlRequest.RuntimeConfig base = UnifiedCrawlRequest.RuntimeConfig.builder()
                .graphExtractionRemoteParallelism(8).build();
        UnifiedCrawlRequest.RuntimeConfig out = c.scaleRuntimeConfigForWorker(base, 4);
        assertSame(base, out, "disabled → unchanged instance");
        assertEquals(8, out.getGraphExtractionRemoteParallelism());
    }

    @Test
    void scalesRemoteParallelismByWorkerCount() {
        DistributedCrawlCoordinator c = coordinatorWith(scalingConfig(true, 2));
        UnifiedCrawlRequest.RuntimeConfig base = UnifiedCrawlRequest.RuntimeConfig.builder()
                .graphExtractionRemoteParallelism(8).build();
        UnifiedCrawlRequest.RuntimeConfig out = c.scaleRuntimeConfigForWorker(base, 4);
        assertEquals(2, out.getGraphExtractionRemoteParallelism());
        assertEquals(8, base.getGraphExtractionRemoteParallelism(), "original is not mutated");
    }

    @Test
    void scaleFloorsAtOne() {
        DistributedCrawlCoordinator c = coordinatorWith(scalingConfig(true, 2));
        UnifiedCrawlRequest.RuntimeConfig base = UnifiedCrawlRequest.RuntimeConfig.builder()
                .graphExtractionRemoteParallelism(1).build();
        UnifiedCrawlRequest.RuntimeConfig out = c.scaleRuntimeConfigForWorker(base, 3);
        assertEquals(1, out.getGraphExtractionRemoteParallelism());
    }

    @Test
    void usesDefaultWhenRequestParallelismMissing() {
        DistributedCrawlCoordinator c = coordinatorWith(scalingConfig(true, 6));
        UnifiedCrawlRequest.RuntimeConfig out = c.scaleRuntimeConfigForWorker(null, 3);
        assertNotNull(out);
        assertEquals(2, out.getGraphExtractionRemoteParallelism()); // default 6 / 3 workers
    }

    @Test
    void singleWorkerUnchanged() {
        DistributedCrawlCoordinator c = coordinatorWith(scalingConfig(true, 8));
        UnifiedCrawlRequest.RuntimeConfig base = UnifiedCrawlRequest.RuntimeConfig.builder()
                .graphExtractionRemoteParallelism(8).build();
        assertSame(base, c.scaleRuntimeConfigForWorker(base, 1), "single worker → no division");
    }

    @Test
    void scalesProcessingBackendMaxConcurrent() {
        DistributedCrawlCoordinator c = coordinatorWith(scalingConfig(true, 2));
        ProcessingRouteConfig route = ProcessingRouteConfig.builder()
                .backends(new ArrayList<>(List.of(
                        ProcessingBackend.builder().id("api").maxConcurrent(6).build(),
                        ProcessingBackend.builder().id("unlimited").maxConcurrent(0).build())))
                .build();
        ProcessingRouteConfig out = c.scaleProcessingRouteForWorker(route, 3);
        assertEquals(2, out.getBackends().get(0).getMaxConcurrent(), "6 / 3 workers");
        assertEquals(0, out.getBackends().get(1).getMaxConcurrent(), "0 (unlimited) stays 0");
        assertEquals(6, route.getBackends().get(0).getMaxConcurrent(), "original not mutated");
    }
}
