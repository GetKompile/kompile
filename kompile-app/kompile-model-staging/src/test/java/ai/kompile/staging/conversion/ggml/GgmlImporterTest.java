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

package ai.kompile.staging.conversion.ggml;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the GgmlImporter class.
 */
class GgmlImporterTest {

    private GgmlImporter importer;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        importer = new GgmlImporter();
    }

    @Test
    void testParseNonExistentFile() {
        Path nonExistent = tempDir.resolve("nonexistent.gguf");
        GgmlModelInfo info = importer.parseFile(nonExistent);

        assertFalse(info.isValid());
        assertNotNull(info.getErrorMessage());
        assertTrue(info.getErrorMessage().contains("does not exist"));
    }

    @Test
    void testParseTooSmallFile() throws IOException {
        Path smallFile = tempDir.resolve("small.gguf");
        Files.write(smallFile, new byte[10]); // Too small to be a valid GGUF

        GgmlModelInfo info = importer.parseFile(smallFile);

        assertFalse(info.isValid());
        assertNotNull(info.getErrorMessage());
    }

    @Test
    void testParseInvalidMagic() throws IOException {
        Path invalidFile = tempDir.resolve("invalid.gguf");
        ByteBuffer buffer = ByteBuffer.allocate(100);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x12345678); // Invalid magic
        buffer.putInt(3); // Version
        buffer.putLong(0); // Tensor count
        buffer.putLong(0); // Metadata count
        Files.write(invalidFile, buffer.array());

        GgmlModelInfo info = importer.parseFile(invalidFile);

        assertFalse(info.isValid());
        assertNotNull(info.getErrorMessage());
        assertTrue(info.getErrorMessage().contains("Unknown file format"));
    }

    @Test
    void testParseValidGgufHeader() throws IOException {
        Path ggufFile = tempDir.resolve("test.gguf");
        ByteBuffer buffer = ByteBuffer.allocate(100);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // GGUF magic ("GGUF" in little-endian)
        buffer.put((byte) 'G');
        buffer.put((byte) 'G');
        buffer.put((byte) 'U');
        buffer.put((byte) 'F');

        // Version 3
        buffer.putInt(3);

        // Tensor count
        buffer.putLong(100);

        // Metadata count (0 for simplicity)
        buffer.putLong(0);

        Files.write(ggufFile, buffer.array());

        GgmlModelInfo info = importer.parseFile(ggufFile);

        assertTrue(info.isValid());
        assertEquals("gguf", info.getFormat());
        assertEquals(3, info.getVersion());
        assertEquals(100, info.getTensorCount());
    }

    @Test
    void testIsGgmlFile_ValidGguf() throws IOException {
        Path ggufFile = tempDir.resolve("test.gguf");
        ByteBuffer buffer = ByteBuffer.allocate(24);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // GGUF magic
        buffer.put((byte) 'G');
        buffer.put((byte) 'G');
        buffer.put((byte) 'U');
        buffer.put((byte) 'F');
        buffer.putInt(3); // Version
        buffer.putLong(0); // Tensor count
        buffer.putLong(0); // Metadata count

        Files.write(ggufFile, buffer.array());

        assertTrue(importer.isGgmlFile(ggufFile));
    }

    @Test
    void testIsGgmlFile_InvalidFile() throws IOException {
        Path textFile = tempDir.resolve("test.txt");
        Files.writeString(textFile, "This is not a GGML file");

        assertFalse(importer.isGgmlFile(textFile));
    }

    @Test
    void testIsGgmlFile_NonExistent() {
        Path nonExistent = tempDir.resolve("nonexistent.gguf");
        assertFalse(importer.isGgmlFile(nonExistent));
    }

    @Test
    void testIsGgmlFile_Directory() {
        assertFalse(importer.isGgmlFile(tempDir));
    }

    @Test
    void testGgmlModelInfoSummary() {
        GgmlModelInfo info = GgmlModelInfo.builder()
                .valid(true)
                .format("gguf")
                .version(3)
                .architecture("llama")
                .modelName("LLaMA 2 7B")
                .quantizationType("Q4_K_M")
                .parameterCount(7_000_000_000L)
                .contextLength(4096)
                .build();

        String summary = info.getSummary();

        assertTrue(summary.contains("GGUF"));
        assertTrue(summary.contains("v3"));
        assertTrue(summary.contains("llama"));
        assertTrue(summary.contains("LLaMA 2 7B"));
        assertTrue(summary.contains("Q4_K_M"));
        assertTrue(summary.contains("7.0B params"));
    }

    @Test
    void testGgmlModelInfoInvalid() {
        GgmlModelInfo info = GgmlModelInfo.invalid("Test error message");

        assertFalse(info.isValid());
        assertEquals("Test error message", info.getErrorMessage());
        assertTrue(info.getSummary().contains("Invalid"));
    }

    @Test
    void testParseValidGgufWithMetadata() throws IOException {
        Path ggufFile = tempDir.resolve("test_meta.gguf");
        ByteBuffer buffer = ByteBuffer.allocate(200);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // GGUF magic
        buffer.put((byte) 'G');
        buffer.put((byte) 'G');
        buffer.put((byte) 'U');
        buffer.put((byte) 'F');

        // Version 3
        buffer.putInt(3);

        // Tensor count
        buffer.putLong(50);

        // Metadata count (1 entry)
        buffer.putLong(1);

        // Metadata entry: "general.architecture" = "llama"
        String key = "general.architecture";
        buffer.putLong(key.length()); // Key length (GGUF v2/v3 uses uint64)
        for (char c : key.toCharArray()) {
            buffer.put((byte) c);
        }
        buffer.putInt(8); // Type: STRING
        String value = "llama";
        buffer.putLong(value.length()); // Value length
        for (char c : value.toCharArray()) {
            buffer.put((byte) c);
        }

        byte[] data = new byte[buffer.position()];
        buffer.flip();
        buffer.get(data);
        Files.write(ggufFile, data);

        GgmlModelInfo info = importer.parseFile(ggufFile);

        assertTrue(info.isValid());
        assertEquals("gguf", info.getFormat());
        assertEquals(3, info.getVersion());
        assertEquals(50, info.getTensorCount());
        assertEquals("llama", info.getArchitecture());
    }
}
