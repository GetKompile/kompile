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

package ai.kompile.loader.mail;

import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that legacy, non-UTF-8 charsets declared on a {@code text/plain} body are decoded
 * correctly. The actual decoding is delegated to mime4j's {@code TextBody.getReader()} (see
 * {@link MailLoaderImpl#readTextBody}); these tests exist to prove that guarantee empirically
 * for the charsets this loader is expected to see in the wild, rather than to add any new
 * decoding logic of our own.
 *
 * <p>Raw bytes are used (not {@link Files#writeString}) so each fixture's body bytes are exactly
 * the bytes a real mail client would have produced for that charset - for ISO-2022-JP in
 * particular, the bytes are produced by the JDK's own encoder for that charset, used here purely
 * as ground truth, not as new production logic.
 */
class MailLoaderImplCharsetTest {

    private final MailLoaderImpl loader = new MailLoaderImpl();

    private DocumentSourceDescriptor descriptorFor(Path file) {
        return DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(file.toAbsolutePath().toString())
                .build();
    }

    /** Builds a raw .eml file: ASCII headers followed by the exact given body bytes. */
    private Path writeEmlWithRawBody(Path tempDir, String name, String charsetName, byte[] bodyBytes)
            throws IOException {
        String headers = "From: Alice <alice@example.com>\r\n"
                + "To: Bob <bob@example.com>\r\n"
                + "Subject: Charset Test\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=" + charsetName + "\r\n"
                + "Content-Transfer-Encoding: 8bit\r\n"
                + "\r\n";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(bodyBytes);
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));

        Path emlFile = tempDir.resolve(name);
        Files.write(emlFile, out.toByteArray());
        return emlFile;
    }

    @Test
    void iso8859_1_decodesAccentedCharacterCorrectly(@TempDir Path tempDir) throws Exception {
        // "caf" + 0xE9, which is 'é' under ISO-8859-1.
        byte[] body = new byte[]{'c', 'a', 'f', (byte) 0xE9};
        Path emlFile = writeEmlWithRawBody(tempDir, "iso-8859-1.eml", "ISO-8859-1", body);

        List<Document> documents = loader.load(descriptorFor(emlFile));
        assertEquals(1, documents.size());
        String text = documents.get(0).getText();
        assertTrue(text.contains("café"), "expected 'café', got: " + text);
    }

    @Test
    void windows1252_decodesSmartQuoteDifferentlyFromIso8859_1(@TempDir Path tempDir) throws Exception {
        // 0x92 is the right single quotation mark (U+2019) under windows-1252, but an unassigned
        // C1 control character (U+0092) under true ISO-8859-1 - a good discriminator to prove the
        // declared charset name is actually honored rather than silently treated as Latin-1.
        byte[] body = new byte[]{'q', 'u', 'o', 't', 'e', (byte) 0x92, 'm', 'a', 'r', 'k'};
        Path emlFile = writeEmlWithRawBody(tempDir, "windows-1252.eml", "windows-1252", body);

        List<Document> documents = loader.load(descriptorFor(emlFile));
        assertEquals(1, documents.size());
        String text = documents.get(0).getText();
        assertTrue(text.contains("quote’mark"),
                "expected the windows-1252 smart quote (U+2019), got: " + text);
        assertFalse(text.contains("quote\u0092mark"),
                "must not have been mis-decoded as raw ISO-8859-1, got: " + text);
    }

    @Test
    void iso2022jp_decodesJapaneseTextCorrectly(@TempDir Path tempDir) throws Exception {
        // Use the JDK's own ISO-2022-JP encoder as ground truth for the escape-sequence-based
        // byte layout, rather than hand-crafting the shift sequences.
        String original = "こんにちは"; // "konnichiwa" (hello) in hiragana
        byte[] body = original.getBytes(Charset.forName("ISO-2022-JP"));
        Path emlFile = writeEmlWithRawBody(tempDir, "iso-2022-jp.eml", "ISO-2022-JP", body);

        List<Document> documents = loader.load(descriptorFor(emlFile));
        assertEquals(1, documents.size());
        String text = documents.get(0).getText();
        assertTrue(text.contains(original), "expected decoded Japanese greeting, got: " + text);
    }
}
