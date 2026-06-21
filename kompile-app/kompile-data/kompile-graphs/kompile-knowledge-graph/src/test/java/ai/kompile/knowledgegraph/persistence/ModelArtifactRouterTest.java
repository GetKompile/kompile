/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Plain JUnit 5 + Mockito tests for {@link ModelArtifactRouter}.
 */
class ModelArtifactRouterTest {

    @TempDir
    Path tempDir;

    /**
     * When two backends both support the same type, the one with higher priority must be
     * invoked for store and retrieve.
     */
    @Test
    void routesToHighestPriorityBackend() throws Exception {
        ModelArtifactBackend lowPriority  = Mockito.mock(ModelArtifactBackend.class);
        ModelArtifactBackend highPriority = Mockito.mock(ModelArtifactBackend.class);

        when(lowPriority.supports(any()))  .thenReturn(true);
        when(lowPriority.priority())        .thenReturn(0);
        when(highPriority.supports(any())) .thenReturn(true);
        when(highPriority.priority())       .thenReturn(10);

        Path dummyFile = Files.createTempFile(tempDir, "artifact", ".bin");

        ModelArtifactRouter router = new ModelArtifactRouter(List.of(lowPriority, highPriority));
        ModelArtifactRef ref = new ModelArtifactRef("fs-1", ModelArtifactType.PSL_WEIGHTS, "prog-1", null);

        router.store(ref, dummyFile);
        verify(highPriority, times(1)).store(ref, dummyFile);
        verify(lowPriority,  never()).store(any(), any());

        router.retrieve(ref, dummyFile);
        verify(highPriority, times(1)).retrieve(ref, dummyFile);
        verify(lowPriority,  never()).retrieve(any(), any());
    }

    /**
     * {@link FileModelArtifactBackend#supports} must return {@code true} for every
     * {@link ModelArtifactType}.
     */
    @Test
    void fileBackendHandlesAllTypes() {
        FileModelArtifactBackend backend = new FileModelArtifactBackend();
        for (ModelArtifactType type : ModelArtifactType.values()) {
            assertTrue(backend.supports(type),
                    "FileModelArtifactBackend must support type: " + type);
        }
    }

    /**
     * {@link StagingModelArtifactBackend} must only support {@link ModelArtifactType#SAMEDIFF_CHECKPOINT}
     * and only when {@code stagingUrl} is configured; all other types must return false.
     */
    @Test
    void stagingBackendOnlySupportsSameDiff() throws Exception {
        StagingModelArtifactBackend backend = new StagingModelArtifactBackend();

        // With blank stagingUrl: supports nothing
        setStagingUrl(backend, "");
        for (ModelArtifactType type : ModelArtifactType.values()) {
            assertFalse(backend.supports(type),
                    "With blank stagingUrl, staging backend must not support type: " + type);
        }

        // With a real stagingUrl: only SAMEDIFF_CHECKPOINT is supported
        setStagingUrl(backend, "http://staging.example.com");
        for (ModelArtifactType type : ModelArtifactType.values()) {
            if (type == ModelArtifactType.SAMEDIFF_CHECKPOINT) {
                assertTrue(backend.supports(type),
                        "Staging backend must support SAMEDIFF_CHECKPOINT when stagingUrl is set");
            } else {
                assertFalse(backend.supports(type),
                        "Staging backend must NOT support type " + type + " even when stagingUrl is set");
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void setStagingUrl(StagingModelArtifactBackend backend, String url) throws Exception {
        Field f = StagingModelArtifactBackend.class.getDeclaredField("stagingUrl");
        f.setAccessible(true);
        f.set(backend, url);
    }
}
