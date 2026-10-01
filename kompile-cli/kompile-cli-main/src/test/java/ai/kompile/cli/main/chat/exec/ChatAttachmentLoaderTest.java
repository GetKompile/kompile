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
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatAttachmentLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsTextAndImageAttachmentsIntoProviderNeutralInputs() throws Exception {
        Path text = tempDir.resolve("notes.md");
        Path image = tempDir.resolve("plot.png");
        Files.writeString(text, "# Evidence");
        Files.write(image, new byte[]{1, 2, 3});

        var loaded = ChatAttachmentLoader.load(List.of(text, image));

        assertEquals(2, loaded.size());
        assertFalse(loaded.get(0).isImage());
        assertEquals("# Evidence", loaded.get(0).textContent());
        assertNull(loaded.get(0).base64Data());
        assertTrue(loaded.get(1).isImage());
        assertEquals("AQID", loaded.get(1).base64Data());
        assertNull(loaded.get(1).textContent());
    }

    @Test
    void rejectsMissingAndOversizedAttachments() throws Exception {
        IOException missing = assertThrows(IOException.class,
                () -> ChatAttachmentLoader.load(List.of(tempDir.resolve("missing.txt"))));
        assertTrue(missing.getMessage().contains("not a regular file"));

        Path oversized = tempDir.resolve("oversized.txt");
        try (var out = Files.newOutputStream(oversized)) {
            out.write(new byte[(int) ChatAttachmentLoader.MAX_ATTACHMENT_BYTES + 1]);
        }
        IOException tooLarge = assertThrows(IOException.class,
                () -> ChatAttachmentLoader.load(List.of(oversized)));
        assertTrue(tooLarge.getMessage().contains("exceeds 5 MiB"));
    }

    @Test
    void attachesOnlyTheImageTypesTheVisionApisAccept() throws Exception {
        Path webp = tempDir.resolve("photo.WEBP");
        Files.write(webp, new byte[]{1, 2, 3});
        var image = ChatAttachmentLoader.load(List.of(webp)).get(0);
        assertTrue(image.isImage());
        assertEquals("image/webp", image.mimeType());

        // No vision API takes BMP, so it fails here with a clear message, not as a provider 400.
        Path bmp = tempDir.resolve("scan.bmp");
        Files.write(bmp, new byte[]{'B', 'M', 0, 0});
        IOException unsupported = assertThrows(IOException.class,
                () -> ChatAttachmentLoader.load(List.of(bmp)));
        assertTrue(unsupported.getMessage().contains("Unsupported image type image/bmp"),
                unsupported.getMessage());

        // SVG is XML: the model reads it as text.
        Path svg = tempDir.resolve("diagram.svg");
        Files.writeString(svg, "<svg xmlns=\"http://www.w3.org/2000/svg\"/>");
        var text = ChatAttachmentLoader.load(List.of(svg)).get(0);
        assertFalse(text.isImage());
        assertTrue(text.textContent().startsWith("<svg"));
    }

    @Test
    void binaryFilesArePassedByPathInsteadOfFailingTheTurn() throws Exception {
        // A PDF's header and binary marker are not UTF-8: reading it as a string used to
        // throw "Input length = 1" and abort the whole turn.
        Path pdf = tempDir.resolve("report.pdf");
        Files.write(pdf, new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '7', '\n', '%',
                (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3});
        // Valid UTF-8 can still be binary: text never holds NUL bytes.
        Path blob = tempDir.resolve("state.bin");
        Files.write(blob, new byte[]{'a', 0, 'b', 0});
        Path unicode = tempDir.resolve("notes.txt");
        Files.writeString(unicode, "naïve café ✓");

        var loaded = ChatAttachmentLoader.load(List.of(pdf, blob, unicode));

        assertFalse(loaded.get(0).isImage());
        assertEquals("[Binary file (14 bytes), not inlined: open it from the path above"
                + " with a tool if the task needs its contents.]", loaded.get(0).textContent());
        assertTrue(loaded.get(1).textContent().startsWith("[Binary file (4 bytes)"),
                loaded.get(1).textContent());
        assertEquals("naïve café ✓", loaded.get(2).textContent());
    }

    @Test
    void imageTypeComesFromTheExtensionTable() {
        assertEquals("image/jpeg", ChatAttachmentLoader.imageMimeType(Path.of("/a/b/shot.JPG")));
        assertEquals("image/png", ChatAttachmentLoader.imageMimeType(Path.of("plot.png")));
        assertNull(ChatAttachmentLoader.imageMimeType(Path.of("scan.bmp")));
        assertNull(ChatAttachmentLoader.imageMimeType(Path.of("diagram.svg")));
        assertNull(ChatAttachmentLoader.imageMimeType(Path.of("README")));
        assertNull(ChatAttachmentLoader.imageMimeType(Path.of("/")));
    }
}
