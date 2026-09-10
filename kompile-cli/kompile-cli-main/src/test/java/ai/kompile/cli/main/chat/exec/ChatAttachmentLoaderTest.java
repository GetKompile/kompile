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
}
