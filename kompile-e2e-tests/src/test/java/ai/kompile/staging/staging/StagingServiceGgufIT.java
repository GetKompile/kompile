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

package ai.kompile.staging.staging;

import ai.kompile.modelmanager.registry.RegistryService;
import ai.kompile.staging.conversion.ConversionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end integration test for the GGUF staging pipeline.
 *
 * <p>Wires up a real {@link StagingService} backed by a real
 * {@link ConversionService} and {@link RegistryService} against a temporary
 * directory, then drives a synthetic GGUF file through
 * {@link StagingService#stageLocalModel(String, String, String, boolean)}.
 * Asserts the async pipeline routes through {@code CONVERTING} status and
 * terminates in {@code FAILED} with a GGML-layer error (not the generic
 * "Unsupported format" path), proving that the wiring from
 * {@code needsConversion} through {@code ConversionService.importGgml} to
 * {@code GGMLModelImport} is intact.
 *
 * <p>A full GGUF weight load is exercised by the staging server smoke test;
 * this test uses a header-only synthetic file so it stays fast and offline.</p>
 */
class StagingServiceGgufIT {

    @TempDir
    Path tempDir;

    @Test
    void stageLocalModel_ggufFile_routesThroughGgmlImporterAndFailsOnCorruptWeights() throws Exception {
        RegistryService registryService = new RegistryService(tempDir.resolve("models"));
        ConversionService conversionService = new ConversionService();
        StagingService stagingService = new StagingService(
                registryService,
                conversionService,
                Collections.emptyList(),
                null);

        Path ggufFile = writeCorruptedGgufHeader(tempDir.resolve("synthetic.gguf"));

        String modelId = "synthetic-gguf-test";
        StagingModelInfo initial = stagingService.stageLocalModel(
                modelId, ggufFile.toAbsolutePath().toString(), "gguf", false);

        assertNotNull(initial);
        assertEquals(modelId, initial.getModelId());

        StagingModelInfo terminal = waitForTerminal(stagingService, modelId, 30_000L);

        assertEquals(StagingStatus.FAILED, terminal.getStatus(),
                "Expected corrupted GGUF to fail staging. Final state: " + terminal);

        String error = terminal.getError();
        assertNotNull(error, "Failed staging should carry an error message");
        assertTrue(error.toLowerCase().contains("conversion failed"),
                "Expected error to come from the conversion phase, got: " + error);
        assertTrue(!error.contains("Unsupported format"),
                "Expected GGML-layer error, not generic 'Unsupported format'. Got: " + error);
    }

    private StagingModelInfo waitForTerminal(StagingService service, String modelId, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        StagingModelInfo info = null;
        while (System.currentTimeMillis() < deadline) {
            info = service.getStagingModel(modelId);
            if (info != null && info.getStatus() != null && info.getStatus().isTerminal()) {
                return info;
            }
            Thread.sleep(100L);
        }
        fail("Staging did not reach a terminal state within " + timeoutMs + "ms. Last state: " + info);
        return info;
    }

    private Path writeCorruptedGgufHeader(Path file) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(24);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x46554747); // "GGUF"
        buffer.putInt(3);          // version
        buffer.putLong(999_999L);  // absurd tensor count
        buffer.putLong(999_999L);  // absurd metadata KV count
        Files.write(file, buffer.array());
        return file;
    }
}
