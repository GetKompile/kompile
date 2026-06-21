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

package ai.kompile.app.services.scheduler;

import ai.kompile.app.config.ResourceSchedulerConfig;
import ai.kompile.app.services.ResourceGovernor;
import ai.kompile.app.services.cluster.CrawlWorkerRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RemotePeerJobSchedulerDelegateTest {

    @Mock
    private CrawlWorkerRegistry registry;
    @Mock
    private ResourceSchedulerConfigService configService;
    @Mock
    private ResourceGovernor governor;

    private RemotePeerJobSchedulerDelegate delegate;
    private ResourceSchedulerConfig cfg;

    @BeforeEach
    void setUp() {
        cfg = ResourceSchedulerConfig.defaults();
        lenient().when(configService.getConfiguration()).thenReturn(cfg);
        delegate = new RemotePeerJobSchedulerDelegate(registry, configService, new ObjectMapper());
    }

    @Test
    void getMode_isCluster() {
        assertEquals("cluster", delegate.getMode());
    }

    @Test
    void isAvailable_trueWhenOrchestratorWithWorkers() {
        cfg.setClusterRole("orchestrator");
        when(registry.size(anyLong())).thenReturn(2);
        assertTrue(delegate.isAvailable());
    }

    @Test
    void isAvailable_falseWhenNotOrchestrator() {
        cfg.setClusterRole("worker"); // a worker-only node does not orchestrate
        assertFalse(delegate.isAvailable());
    }

    @Test
    void isAvailable_falseWhenNoWorkers() {
        cfg.setClusterRole("both");
        when(registry.size(anyLong())).thenReturn(0);
        assertFalse(delegate.isAvailable());
    }

    @Test
    void isAvailable_failover_falseWhenLocalHasHeadroom() {
        cfg.setClusterRole("orchestrator");
        ReflectionTestUtils.setField(delegate, "governor", governor);
        when(registry.size(anyLong())).thenReturn(1);
        when(governor.isLocalSaturated()).thenReturn(false);
        assertFalse(delegate.isAvailable(), "failover mode keeps work local until the host is saturated");
    }

    @Test
    void isAvailable_failover_trueWhenLocalSaturated() {
        cfg.setClusterRole("orchestrator");
        ReflectionTestUtils.setField(delegate, "governor", governor);
        when(registry.size(anyLong())).thenReturn(1);
        when(governor.isLocalSaturated()).thenReturn(true);
        assertTrue(delegate.isAvailable(), "saturated host fails over to peers");
    }

    @Test
    void isAvailable_alwaysMode_ignoresSaturation() {
        cfg.setClusterRole("orchestrator");
        cfg.setClusterOffloadMode("always");
        ReflectionTestUtils.setField(delegate, "governor", governor);
        when(registry.size(anyLong())).thenReturn(1);
        // governor.isLocalSaturated() must not be consulted in 'always' mode.
        assertTrue(delegate.isAvailable());
    }

    @Test
    void submitJob_honorsPinnedWorker_bypassesSelection() throws Exception {
        // A coordinator-pinned target must be used directly — registry selection is never consulted.
        // The pinned host is unreachable so the HTTP submit fails, but that's irrelevant to this assertion.
        delegate.submitJob("j1", "crawl", "desc",
                JobResourceProfile.cpuOnly("crawl", "Crawl", 0L),
                Map.of("targetWorkerBaseUrl", "http://pinned.invalid:9")).get();
        verify(registry, never()).selectWorker(anyString(), anyBoolean(), anyLong());
    }

    @Test
    void submitJob_noCapableWorker_returnsFailed() throws Exception {
        when(registry.selectWorker(anyString(), anyBoolean(), anyLong())).thenReturn(Optional.empty());
        ExternalJobSchedulerDelegate.ExternalJobRef ref = delegate.submitJob(
                "j1", "crawl", "desc",
                JobResourceProfile.cpuOnly("crawl", "Crawl", 0L),
                Map.of()).get();
        assertEquals("FAILED", ref.status());
        assertTrue(ref.message().contains("no capable"));
    }
}
