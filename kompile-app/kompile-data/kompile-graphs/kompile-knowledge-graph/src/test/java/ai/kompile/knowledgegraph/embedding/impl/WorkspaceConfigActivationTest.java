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
package ai.kompile.knowledgegraph.embedding.impl;

import org.junit.jupiter.api.Test;
import org.nd4j.linalg.api.memory.MemoryWorkspace;
import org.nd4j.linalg.api.memory.conf.WorkspaceConfiguration;
import org.nd4j.linalg.factory.Nd4j;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Guards the per-batch {@code MemoryWorkspace} config in {@link RotatEModel}
 * against the ND4J {@code "For cyclic workspace overallocation should be positive integral value"}
 * crash that failed KGE training.
 *
 * <p>NOTE: {@link TransEModel} no longer uses a {@code MemoryWorkspace} — its hot training
 * inner loop was rewritten to pure-Java {@code float[]} arithmetic to eliminate the
 * {@code OpaqueNDArray*} C++ object churn that caused the 82 GB RSS OOM.  The workspace
 * config was the root of two problems: (a) it pre-allocated 665 MB at construction time
 * (initialSize=512MB × 1.3 overalloc), and (b) the ND4J op calls inside the workspace
 * still created {@code OpaqueNDArray} objects tracked by JavaCPP's
 * {@code Pointer.physicalBytes()} — up to 38,400 per batch — driving RSS to the cap.
 * Only {@link RotatEModel} retains the workspace pattern (its complex-number inner loop
 * still benefits from ND4J vectorised ops and the workspace bounds its transients).
 */
class WorkspaceConfigActivationTest {

    @Test
    void rotateBatchWorkspaceActivates() {
        assertActivates(RotatEModel.class, "ROTATE_BATCH_WS_CONFIG", "ROTATE_BATCH_TEST");
    }

    private void assertActivates(Class<?> owner, String fieldName, String wsName) {
        assertDoesNotThrow(() -> {
            Field f = owner.getDeclaredField(fieldName);
            f.setAccessible(true);
            WorkspaceConfiguration cfg = (WorkspaceConfiguration) f.get(null);
            try (MemoryWorkspace ws = Nd4j.getWorkspaceManager().getAndActivateWorkspace(cfg, wsName)) {
                Nd4j.create(64, 64).addi(1.0); // exercise an allocation inside the workspace
            }
            Nd4j.getWorkspaceManager().destroyWorkspace(
                    Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(wsName));
        });
    }
}
