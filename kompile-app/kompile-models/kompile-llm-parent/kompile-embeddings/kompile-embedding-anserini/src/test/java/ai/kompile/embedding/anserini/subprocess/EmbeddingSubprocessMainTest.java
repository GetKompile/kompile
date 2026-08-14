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

package ai.kompile.embedding.anserini.subprocess;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingSubprocessMainTest {

    @Test
    void classifiesEmbeddingValidationFailuresAsRecoverable() {
        assertTrue(EmbeddingSubprocessMain.isRecoverableEmbeddingValidationFailure(
                new IllegalStateException("Encoder returned non-finite embedding value for batch request abc row 6")));
        assertTrue(EmbeddingSubprocessMain.isRecoverableEmbeddingValidationFailure(
                new IllegalStateException("Encoder returned zero-magnitude embedding for request abc")));
        assertTrue(EmbeddingSubprocessMain.isRecoverableEmbeddingValidationFailure(
                new RuntimeException("wrapper", new IllegalStateException("Encoder returned 3 embedding(s) for 4 text(s)"))));
    }

    @Test
    void doesNotClassifyDeviceFailuresAsRecoverableValidation() {
        assertFalse(EmbeddingSubprocessMain.isRecoverableEmbeddingValidationFailure(
                new IllegalStateException("cudaStreamSynchronize error code [700]: illegal memory access")));
        assertFalse(EmbeddingSubprocessMain.isRecoverableEmbeddingValidationFailure(new RuntimeException("boom")));
    }

    @Test
    void rejectsNativeLoaderEnvironmentOverrides() {
        EmbeddingSubprocessLauncher.DebugConfig config = new EmbeddingSubprocessLauncher.DebugConfig();
        config.setSystemEnvironmentVariables(Map.of(
                "LD_PRELOAD", "/tmp/forbidden.so",
                "SAFE_OPTION", "preserved"));

        assertFalse(config.getSystemEnvironmentVariables().containsKey("LD_PRELOAD"));
        assertFalse(config.buildEnvironmentVariables().containsKey("LD_PRELOAD"));
        assertEquals("preserved", config.buildEnvironmentVariables().get("SAFE_OPTION"));
    }
}
