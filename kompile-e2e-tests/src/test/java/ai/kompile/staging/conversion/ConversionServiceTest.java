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

package ai.kompile.staging.conversion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ConversionService}, focused on format routing.
 *
 * <p>Full end-to-end GGUF weight loading is exercised by the integration
 * test suite; these tests verify the dispatch and extension-sniffing
 * behavior so a misconfigured format string does not silently bypass
 * conversion.</p>
 */
class ConversionServiceTest {

    private ConversionService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new ConversionService();
    }

    @Test
    void supportsFormat_includesGgufAndGgml() {
        assertTrue(service.supportsFormat("gguf"));
        assertTrue(service.supportsFormat("ggml"));
        assertTrue(service.supportsFormat("GGUF"));
        assertTrue(service.supportsFormat("GGML"));
        assertTrue(service.supportsFormat("onnx"));
        assertTrue(service.supportsFormat("tensorflow"));
        assertFalse(service.supportsFormat(null));
        assertFalse(service.supportsFormat("unknown"));
    }

    @Test
    void convert_missingInputFileFails() {
        Path input = tempDir.resolve("missing.gguf");
        Path output = tempDir.resolve("out.sdz");

        ConversionResult result = service.convert(input, output, "gguf");

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("does not exist"),
                "Expected 'does not exist' message, got: " + result.getErrorMessage());
    }

    /**
     * A .gguf extension should route through the GGML importer even when the
     * caller provides no format or a wrong one. We prove this by pointing
     * the service at an unreadable GGUF file and verifying the error comes
     * from the GGML layer (not from the "Unsupported format" default).
     */
    @Test
    void convert_ggufExtensionRoutesThroughGgmlImporter() throws IOException {
        Path input = writeCorruptedGgufHeader(tempDir.resolve("bogus.gguf"));
        Path output = tempDir.resolve("out.sdz");

        ConversionResult result = service.convert(input, output, null);

        assertFalse(result.isSuccess(), "Expected conversion to fail on corrupted GGUF");
        assertNotNull(result.getErrorMessage());
        assertFalse(result.getErrorMessage().contains("Unsupported format"),
                "Expected GGML-layer error, got: " + result.getErrorMessage());
    }

    @Test
    void convert_ggmlExtensionRoutesThroughGgmlImporter() throws IOException {
        Path input = writeCorruptedGgmlHeader(tempDir.resolve("bogus.ggml"));
        Path output = tempDir.resolve("out.sdz");

        ConversionResult result = service.convert(input, output, "ggml");

        assertFalse(result.isSuccess(), "Expected conversion to fail on corrupted GGML");
        assertNotNull(result.getErrorMessage());
        assertFalse(result.getErrorMessage().contains("Unsupported format"),
                "Expected GGML-layer error, got: " + result.getErrorMessage());
    }

    @Test
    void convert_unknownFormatAndExtensionFails() throws IOException {
        Path input = Files.createFile(tempDir.resolve("blob.bin"));
        Path output = tempDir.resolve("out.sdz");

        ConversionResult result = service.convert(input, output, "weird");

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
    }

    private Path writeCorruptedGgufHeader(Path file) throws IOException {
        // Valid GGUF magic + bogus trailing bytes — enough to pass the
        // extension check but fail the tensor parse.
        ByteBuffer buffer = ByteBuffer.allocate(24);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x46554747); // "GGUF"
        buffer.putInt(3);          // version
        buffer.putLong(999_999L);  // absurd tensor count
        buffer.putLong(999_999L);  // absurd metadata KV count
        Files.write(file, buffer.array());
        return file;
    }

    private Path writeCorruptedGgmlHeader(Path file) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x67676D6C); // "ggml"
        buffer.putInt(1);          // version
        Files.write(file, buffer.array());
        return file;
    }
}
