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

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4: the dispatcher treats a backend as unavailable when the (advisory) cluster breaker reports it open,
 * even if this JVM's local breaker is closed — and falls back to local-only behavior when no cluster is wired.
 */
class CrawlLlmDispatcherClusterHealthTest {

    @Test
    void withoutClusterHealthAFreshBackendIsAvailable() {
        CrawlLlmDispatcher d = new CrawlLlmDispatcher();
        // No cluster wired → NOOP; a backend with no local failures is not open.
        assertFalse(d.isBackendOpen("openai"));
    }

    @Test
    void clusterOpenBackendIsUnavailableEvenWithClosedLocalBreaker() {
        CrawlLlmDispatcher d = new CrawlLlmDispatcher();
        Set<String> clusterOpen = Set.of("openai");
        d.setClusterBackendHealth(new ClusterBackendHealth() {
            @Override
            public boolean isOpen(String backendId) {
                return clusterOpen.contains(backendId);
            }

            @Override
            public void record(String backendId, Event event) {
                // not exercised here
            }
        });
        assertTrue(d.isBackendOpen("openai"), "cluster-open backend is unavailable despite a closed local breaker");
        assertFalse(d.isBackendOpen("anthropic"), "cluster-closed backend stays available");
    }
}
