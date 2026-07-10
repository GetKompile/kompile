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

import ai.kompile.core.crawl.graph.ProcessingRouteConfig;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig.ProcessingBackend;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig.ProcessingBackendType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link CrawlLlmDispatcher#isCapableOf} correctly filters backends
 * by capability, and that the fallback-loop ordering respects priority.
 *
 * <p>Backend fixture used throughout:</p>
 * <ul>
 *   <li>{@code local-vlm} — LOCAL_MODEL, capabilities=[vlm] → must be skipped for LLM extraction</li>
 *   <li>{@code opencode-extraction} — CLI_AGENT, priority=1, capabilities=[llm], backupBackendId=local-serving → first for LLM</li>
 *   <li>{@code local-serving} — LOCAL_MODEL/serving, priority=2, capabilities=[llm] → backup / fallback</li>
 * </ul>
 */
class CrawlLlmDispatcherCapabilityFilterTest {

    // ── Backend fixtures ─────────────────────────────────────────────────────

    private static ProcessingBackend vlmOnlyBackend() {
        return ProcessingBackend.builder()
                .id("local-vlm")
                .type(ProcessingBackendType.LOCAL_MODEL)
                .priority(0)
                .maxConcurrent(1)
                .capabilities(List.of("vlm"))
                .build();
    }

    private static ProcessingBackend llmPrimaryBackend() {
        return ProcessingBackend.builder()
                .id("opencode-extraction")
                .type(ProcessingBackendType.CLI_AGENT)
                .agentName("opencode-cli")
                .priority(1)
                .capabilities(List.of("llm"))
                .backupBackendId("local-serving")
                .build();
    }

    private static ProcessingBackend llmServingBackend() {
        return ProcessingBackend.builder()
                .id("local-serving")
                .type(ProcessingBackendType.LOCAL_MODEL)
                .agentName("serving")
                .priority(2)
                .capabilities(List.of("llm"))
                .build();
    }

    private static ProcessingBackend noCapabilityBackend() {
        return ProcessingBackend.builder()
                .id("legacy-backend")
                .type(ProcessingBackendType.CLI_AGENT)
                .agentName("legacy-cli")
                .priority(3)
                // empty capabilities list — backward-compatible: eligible for all tasks
                .build();
    }

    // ── isCapableOf tests ─────────────────────────────────────────────────────

    @Test
    void vlmOnlyBackend_isNotCapableOfLlm() {
        CrawlLlmDispatcher d = new CrawlLlmDispatcher();
        assertFalse(d.isCapableOf(vlmOnlyBackend(), "llm"),
                "vlm-only backend must not be eligible for LLM extraction");
    }

    @Test
    void vlmOnlyBackend_isCapableOfVlm() {
        CrawlLlmDispatcher d = new CrawlLlmDispatcher();
        assertTrue(d.isCapableOf(vlmOnlyBackend(), "vlm"),
                "vlm-only backend must be eligible for VLM tasks");
    }

    @Test
    void llmPrimaryBackend_isCapableOfLlm() {
        CrawlLlmDispatcher d = new CrawlLlmDispatcher();
        assertTrue(d.isCapableOf(llmPrimaryBackend(), "llm"),
                "llm-capable backend must be eligible for LLM extraction");
    }

    @Test
    void llmPrimaryBackend_isNotCapableOfVlm() {
        CrawlLlmDispatcher d = new CrawlLlmDispatcher();
        assertFalse(d.isCapableOf(llmPrimaryBackend(), "vlm"),
                "llm-only backend must not be eligible for VLM tasks");
    }

    @Test
    void llmServingBackend_isCapableOfLlm() {
        CrawlLlmDispatcher d = new CrawlLlmDispatcher();
        assertTrue(d.isCapableOf(llmServingBackend(), "llm"),
                "serving (llm) backend must be eligible for LLM extraction");
    }

    @Test
    void noCapabilityBackend_isCapableOfAnything() {
        CrawlLlmDispatcher d = new CrawlLlmDispatcher();
        assertTrue(d.isCapableOf(noCapabilityBackend(), "llm"),
                "backend with empty capabilities is eligible for all tasks (backward compat)");
        assertTrue(d.isCapableOf(noCapabilityBackend(), "vlm"),
                "backend with empty capabilities is eligible for all tasks (backward compat)");
        assertTrue(d.isCapableOf(noCapabilityBackend(), "embedding"),
                "backend with empty capabilities is eligible for all tasks (backward compat)");
    }

    @Test
    void nullCapabilitiesTreatedAsEmptyAllowsAll() {
        ProcessingBackend nullCapBackend = ProcessingBackend.builder()
                .id("null-cap")
                .type(ProcessingBackendType.API_AGENT)
                .capabilities(null)
                .build();
        CrawlLlmDispatcher d = new CrawlLlmDispatcher();
        assertTrue(d.isCapableOf(nullCapBackend, "llm"),
                "null capabilities list must be treated as 'all tasks allowed'");
    }

    // ── Ordering + capability combination ────────────────────────────────────

    /**
     * Verify priority ordering: filtering the three backends for LLM capability and
     * sorting by priority yields [opencode-extraction(1), local-serving(2)] — the VLM
     * backend is absent.
     */
    @Test
    void llmEligibleBackendsAreOrderedByPriority() {
        CrawlLlmDispatcher d = new CrawlLlmDispatcher();
        List<ProcessingBackend> all = List.of(vlmOnlyBackend(), llmPrimaryBackend(), llmServingBackend());

        List<ProcessingBackend> llmBackends = all.stream()
                .filter(b -> d.isCapableOf(b, "llm"))
                .sorted(java.util.Comparator.comparingInt(ProcessingBackend::getPriority))
                .toList();

        assertEquals(2, llmBackends.size(), "exactly 2 LLM-capable backends");
        assertEquals("opencode-extraction", llmBackends.get(0).getId(),
                "priority-1 backend comes first");
        assertEquals("local-serving", llmBackends.get(1).getId(),
                "priority-2 backend (the backup) comes second");

        // Also verify local-vlm is not present
        assertTrue(llmBackends.stream().noneMatch(b -> "local-vlm".equals(b.getId())),
                "vlm-only backend must never appear in LLM backend list");
    }

    /**
     * Verify that backup linkage: llmPrimaryBackend references local-serving as its
     * backupBackendId, so when primary fails the explicit backup is tried before the
     * general fallback chain.
     */
    @Test
    void backupBackendIdPointsToServingBackend() {
        ProcessingBackend primary = llmPrimaryBackend();
        ProcessingBackend backup = llmServingBackend();

        assertEquals("local-serving", primary.getBackupBackendId(),
                "primary backend's backupBackendId must point to local-serving");
        assertEquals(primary.getBackupBackendId(), backup.getId(),
                "backupBackendId must match the serving backend's id");
    }

    @Test
    void usableLlmResponseRejectsNullBlankAndErrorPayloads() {
        assertFalse(CrawlLlmDispatcher.isUsableLlmResponse(null));
        assertFalse(CrawlLlmDispatcher.isUsableLlmResponse(""));
        assertFalse(CrawlLlmDispatcher.isUsableLlmResponse("   \n\t"));
        assertFalse(CrawlLlmDispatcher.isUsableLlmResponse("Error: provider returned no content"));
        assertFalse(CrawlLlmDispatcher.isUsableLlmResponse("  Error: model unavailable"));
    }

    @Test
    void usableLlmResponseAcceptsActualContent() {
        assertTrue(CrawlLlmDispatcher.isUsableLlmResponse("{\"entities\":[],\"relationships\":[]}"));
    }

    /**
     * Route config with all three backends: when the VLM-only backend is the one returned
     * by a capacity tracker, and the next eligible LLM backend is opencode-extraction,
     * verifies the capability-filter selects the right one.
     */
    @Test
    void routeConfigWithMixedBackends_firstLlmCapableIsSelected() {
        CrawlLlmDispatcher d = new CrawlLlmDispatcher();

        // Build route config with all three backends (vlm first in list to test filtering)
        ProcessingRouteConfig route = ProcessingRouteConfig.builder()
                .fallbackEnabled(true)
                .backends(List.of(vlmOnlyBackend(), llmPrimaryBackend(), llmServingBackend()))
                .build();

        // Simulate iterating the fallback loop: find first llm-capable, enabled, non-broken backend
        Optional<ProcessingBackend> firstLlm = route.getBackends().stream()
                .filter(ProcessingBackend::isEnabled)
                .filter(b -> d.isCapableOf(b, "llm"))
                .findFirst();

        assertTrue(firstLlm.isPresent(), "must find an LLM backend");
        assertEquals("opencode-extraction", firstLlm.get().getId(),
                "first LLM-capable backend in priority order must be opencode-extraction");
    }
}
