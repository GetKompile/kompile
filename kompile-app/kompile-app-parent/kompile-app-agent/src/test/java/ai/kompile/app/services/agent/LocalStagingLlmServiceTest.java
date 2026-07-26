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

package ai.kompile.app.services.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalStagingLlmServiceTest {

    private static LocalStagingLlmService.LocalModelCandidate candidate(int contextWindow) {
        return new LocalStagingLlmService.LocalModelCandidate(
                "model", "local/model", null, contextWindow, 0L, null);
    }

    @Test
    void executableContextUsesLargestValidServingBucket() {
        assertEquals(4_096, LocalStagingLlmService.executableContextWindow(
                candidate(32_768), "256, 1024, invalid, 4096, -1"));
    }

    @Test
    void executableContextPreservesSmallerDeclaredWindow() {
        assertEquals(2_048, LocalStagingLlmService.executableContextWindow(
                candidate(2_048), "256,512,1024,2048,4096"));
    }

    @Test
    void unusableBucketConfigurationFallsBackToProductionDefault() {
        assertEquals(4_096, LocalStagingLlmService.executableContextWindow(
                candidate(32_768), "invalid,-1,0"));
    }

    @Test
    void missingModelMetadataUsesConservativeLocalDefault() {
        assertEquals(2_048, LocalStagingLlmService.executableContextWindow(
                candidate(0), "256,512,1024,2048,4096"));
    }
}
