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

package ai.kompile.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GgufMetadataReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void readsArchScopedContextLengthPastSkippedValues() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU32(out, 0x46554747L);          // "GGUF"
        writeU32(out, 3);                    // version
        writeU64(out, 0);                    // tensor_count
        writeU64(out, 3);                    // kv_count

        // kv1: general.architecture = "lfm2" (string — must be skipped correctly)
        writeString(out, "general.architecture");
        writeU32(out, 8);
        writeString(out, "lfm2");

        // kv2: lfm2.tokens = ["a", "bb"] (array of strings — the hard skip case)
        writeString(out, "lfm2.tokens");
        writeU32(out, 9);
        writeU32(out, 8);                    // element type: string
        writeU64(out, 2);                    // count
        writeString(out, "a");
        writeString(out, "bb");

        // kv3: lfm2.context_length = 32768 (u32)
        writeString(out, "lfm2.context_length");
        writeU32(out, 4);
        writeU32(out, 32_768);

        Path file = tempDir.resolve("model.gguf");
        Files.write(file, out.toByteArray());

        assertEquals(Optional.of(32_768), GgufMetadataReader.readContextLength(file));
    }

    @Test
    void nonGgufAndTruncatedFilesReturnEmpty() throws IOException {
        Path notGguf = tempDir.resolve("weights.bin");
        Files.write(notGguf, "definitely not a gguf header".getBytes(StandardCharsets.UTF_8));
        assertTrue(GgufMetadataReader.readContextLength(notGguf).isEmpty());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU32(out, 0x46554747L);
        writeU32(out, 3);
        writeU64(out, 0);
        writeU64(out, 5);                    // claims 5 KVs, then the file ends
        Path truncated = tempDir.resolve("truncated.gguf");
        Files.write(truncated, out.toByteArray());
        assertTrue(GgufMetadataReader.readContextLength(truncated).isEmpty());
    }

    private static void writeU32(ByteArrayOutputStream out, long value) {
        for (int i = 0; i < 4; i++) {
            out.write((int) ((value >>> (8 * i)) & 0xFF));
        }
    }

    private static void writeU64(ByteArrayOutputStream out, long value) {
        for (int i = 0; i < 8; i++) {
            out.write((int) ((value >>> (8 * i)) & 0xFF));
        }
    }

    private static void writeString(ByteArrayOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeU64(out, bytes.length);
        out.write(bytes);
    }
}
