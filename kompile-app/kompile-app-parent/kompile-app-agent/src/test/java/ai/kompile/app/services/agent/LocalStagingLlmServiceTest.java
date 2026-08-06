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
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalStagingLlmServiceTest {

    @TempDir
    Path tempDir;

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

    @Test
    void siblingGgufArchitectureContextSupersedesStaleRegistryThenServingBucketsClampIt()
            throws IOException {
        Path registryDir = tempDir.resolve("data/models");
        Path modelDir = registryDir.resolve("llm-ggmls/lfm-test");
        Files.createDirectories(modelDir);
        Files.write(modelDir.resolve("model.sdnb"), new byte[]{1});
        writeGguf(modelDir.resolve("LFM2.5-1.2B.gguf"), 131_072);
        Files.writeString(registryDir.resolve("registry.json"), """
                {"models":{"lfm-test":{
                  "type":"llm_ggml","status":"active","model_id":"lfm-test",
                  "path":"llm-ggmls/lfm-test","model_file":"model.sdnb",
                  "metadata":{"max_sequence_length":512}
                }}}
                """, StandardCharsets.UTF_8);

        String previous = System.getProperty("kompile.data.dir");
        try {
            System.setProperty("kompile.data.dir", tempDir.toString());
            LocalStagingLlmService service = new LocalStagingLlmService();
            LocalStagingLlmService.LocalModelCandidate resolved =
                    service.resolveCandidate("lfm-test").orElseThrow();

            assertEquals(131_072, resolved.contextWindow(),
                    "GGUF describes architecture; a stale registry value must not hide it");
            assertEquals(4_096, service.executableContextWindow(resolved),
                    "runtime sequence buckets remain the executable ceiling");
        } finally {
            if (previous == null) {
                System.clearProperty("kompile.data.dir");
            } else {
                System.setProperty("kompile.data.dir", previous);
            }
        }
    }

    private static void writeGguf(Path file, int contextLength) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU32(out, 0x46554747L);
        writeU32(out, 3);
        writeU64(out, 0);
        writeU64(out, 2);
        writeString(out, "general.architecture");
        writeU32(out, 8);
        writeString(out, "lfm2");
        writeString(out, "lfm2.context_length");
        writeU32(out, 4);
        writeU32(out, contextLength);
        Files.write(file, out.toByteArray());
    }

    private static void writeU32(ByteArrayOutputStream out, long value) {
        for (int index = 0; index < 4; index++) {
            out.write((int) ((value >>> (8 * index)) & 0xFF));
        }
    }

    private static void writeU64(ByteArrayOutputStream out, long value) {
        for (int index = 0; index < 8; index++) {
            out.write((int) ((value >>> (8 * index)) & 0xFF));
        }
    }

    private static void writeString(ByteArrayOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeU64(out, bytes.length);
        out.write(bytes);
    }
}
