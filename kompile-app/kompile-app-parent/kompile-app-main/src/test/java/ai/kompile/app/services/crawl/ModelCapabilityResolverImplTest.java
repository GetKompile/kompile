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

import ai.kompile.app.services.agent.LocalStagingLlmService;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig;
import ai.kompile.core.llm.ModelCapability;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelCapabilityResolverImplTest {

    private static LocalStagingLlmService.LocalModelCandidate candidate(int contextWindow) {
        return new LocalStagingLlmService.LocalModelCandidate(
                "lfm2.5-1.2b-instruct", "local/lfm2.5-1.2b-instruct", null, contextWindow, 0L, null);
    }

    @Test
    void localResolutionClampsDiscoveryContextToExecutablePreloadCeiling() {
        LocalStagingLlmService staging = mock(LocalStagingLlmService.class);
        LocalStagingLlmService.LocalModelCandidate candidate = candidate(32_768);
        when(staging.resolveCandidate("lfm2.5-1.2b-instruct")).thenReturn(Optional.of(candidate));
        when(staging.currentModelId()).thenReturn(null);
        when(staging.executableContextWindow(candidate)).thenReturn(4_096);

        ModelCapabilityResolverImpl resolver = new ModelCapabilityResolverImpl(null, null, null, staging);
        ModelCapability cap = resolver.resolve(
                ProcessingRouteConfig.ProcessingBackendType.LOCAL_MODEL,
                null, "lfm2.5-1.2b-instruct", "local-staging").orElseThrow();

        assertTrue(cap.local());
        assertEquals(4_096, cap.contextTokens(),
                "pre-load planning must use the serving lane's executable ceiling, not the GGUF theoretical window");
    }

    @Test
    void liveServingContextWinsWhenThisModelIsLoaded() {
        LocalStagingLlmService staging = mock(LocalStagingLlmService.class);
        when(staging.resolveCandidate("lfm2.5-1.2b-instruct")).thenReturn(Optional.of(candidate(32_768)));
        when(staging.currentModelId()).thenReturn("local/lfm2.5-1.2b-instruct");
        when(staging.liveMaxContextLength()).thenReturn(Optional.of(8_192)); // post KV-bucketing truth

        ModelCapabilityResolverImpl resolver = new ModelCapabilityResolverImpl(null, null, null, staging);
        ModelCapability cap = resolver.resolve(
                ProcessingRouteConfig.ProcessingBackendType.LOCAL_MODEL,
                null, "lfm2.5-1.2b-instruct", "local-staging").orElseThrow();

        assertEquals(8_192, cap.contextTokens(),
                "the live serving window (post KV-bucketing) must override discovery metadata");
    }

    @Test
    void unknownLocalModelStillFallsBackToDefaultContext() {
        LocalStagingLlmService staging = mock(LocalStagingLlmService.class);
        when(staging.resolveCandidate("mystery-model")).thenReturn(Optional.empty());

        ModelCapabilityResolverImpl resolver = new ModelCapabilityResolverImpl(null, null, null, staging);
        ModelCapability cap = resolver.resolve(
                ProcessingRouteConfig.ProcessingBackendType.LOCAL_MODEL,
                null, "mystery-model", "local-staging").orElseThrow();

        assertEquals(2_048, cap.contextTokens());
    }

    @Test
    void localGenerationConcurrencyReadsServingReportAndDefaultsToOne() {
        assertEquals(1, new ModelCapabilityResolverImpl(null, null, null, null)
                .localGenerationConcurrency(), "no staging lane → serial generation");

        LocalStagingLlmService staging = mock(LocalStagingLlmService.class);
        when(staging.reportedGenerationConcurrency()).thenReturn(4);
        assertEquals(4, new ModelCapabilityResolverImpl(null, null, null, staging)
                .localGenerationConcurrency(), "serving-reported concurrency must pass through");
    }
}
