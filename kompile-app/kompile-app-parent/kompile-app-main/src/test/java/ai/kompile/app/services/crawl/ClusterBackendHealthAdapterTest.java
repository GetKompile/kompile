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
import ai.kompile.app.services.scheduler.ResourceSchedulerConfigService;
import ai.kompile.crawl.graph.ClusterBackendHealth;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 4: the orchestrator-authoritative cluster backend breaker (opens on aggregate failures) and the role-aware
 * {@link ClusterBackendHealthAdapter#isOpen}/{@link ClusterBackendHealthAdapter#record} behavior, all gated by
 * {@code clusterSharedBackendBreakerEnabled} (fail-open when off).
 */
class ClusterBackendHealthAdapterTest {

    private ClusterBackendHealthAdapter adapter(boolean enabled, boolean orchestrator, int threshold, int cooldown) {
        ResourceSchedulerConfig c = new ResourceSchedulerConfig();
        c.setClusterSharedBackendBreakerEnabled(enabled);
        c.setClusterRole(orchestrator ? "orchestrator" : "worker");
        c.setClusterBackendFailureThreshold(threshold);
        c.setClusterBackendCooldownSeconds(cooldown);
        ResourceSchedulerConfigService svc = mock(ResourceSchedulerConfigService.class);
        when(svc.getConfiguration()).thenReturn(c);
        return new ClusterBackendHealthAdapter(svc, new ObjectMapper());
    }

    @Test
    void orchestratorBreakerOpensAfterAggregateThreshold() {
        ClusterBackendHealthAdapter a = adapter(true, true, 3, 60);
        a.record("openai", ClusterBackendHealth.Event.FAILURE);
        a.record("openai", ClusterBackendHealth.Event.RATE_LIMITED);
        assertFalse(a.isOpen("openai"), "still under threshold");
        a.record("openai", ClusterBackendHealth.Event.FAILURE);
        assertTrue(a.isOpen("openai"), "tripped at threshold");
        assertTrue(a.openBackends().contains("openai"));
        assertFalse(a.isOpen("anthropic"), "other backends unaffected");
    }

    @Test
    void featureDisabledIsAlwaysClosed() {
        ClusterBackendHealthAdapter a = adapter(false, true, 1, 60);
        for (int i = 0; i < 10; i++) {
            a.record("openai", ClusterBackendHealth.Event.FAILURE);
        }
        assertFalse(a.isOpen("openai"));
        assertTrue(a.openBackends().isEmpty());
    }

    @Test
    void applyRemoteEventTripsAndReturnsOpenSet() {
        ClusterBackendHealthAdapter a = adapter(true, true, 2, 60);
        assertTrue(a.applyRemoteEvent("openai", ClusterBackendHealth.Event.FAILURE).isEmpty());
        Set<String> open = a.applyRemoteEvent("openai", ClusterBackendHealth.Event.FAILURE);
        assertTrue(open.contains("openai"), "two reported failures (threshold 2) open the cluster breaker");
    }

    @Test
    void workerReadsCachedOpenSet() {
        ClusterBackendHealthAdapter a = adapter(true, false, 3, 60); // worker role
        // A worker's isOpen reads the open-set cached from orchestrator report responses (no local breaker).
        ReflectionTestUtils.setField(a, "cachedOpen", Set.of("openai"));
        assertTrue(a.isOpen("openai"));
        assertFalse(a.isOpen("anthropic"));
    }

    @Test
    void nullAndUnknownBackendsAreSafeAndClosed() {
        ClusterBackendHealthAdapter a = adapter(true, true, 1, 60);
        assertDoesNotThrow(() -> a.record(null, ClusterBackendHealth.Event.FAILURE));
        assertFalse(a.isOpen(null));
        assertFalse(a.isOpen("never-seen"));
    }

    @Test
    void breakerHalfOpensAfterCooldown() {
        // cooldown is clamped to >= 1s; recordFailure opens, isOpen resets once elapsed.
        ClusterBackendHealthAdapter.ClusterBreaker b = new ClusterBackendHealthAdapter.ClusterBreaker(1, 1);
        b.recordFailure();
        assertTrue(b.isOpen());
        ReflectionTestUtils.setField(b, "openedAtMs", System.currentTimeMillis() - 2_000L); // simulate elapsed cooldown
        assertFalse(b.isOpen(), "breaker half-opens (resets) after the cooldown window");
    }
}
