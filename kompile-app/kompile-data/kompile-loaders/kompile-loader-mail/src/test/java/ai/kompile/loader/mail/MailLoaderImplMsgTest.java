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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MailLoaderImpl}'s Outlook {@code .msg} (MAPI/CFBF, via POI HSMF) support.
 *
 * <p>This is deliberately a defensive/structural test rather than a full parsed-content
 * round-trip: no real {@code .msg} fixture exists anywhere in this repository or in the local
 * Maven repository under {@code ~/.m2} (both were searched), and POI's HSMF reader
 * ({@link org.apache.poi.hsmf.MAPIMessage}) is read-only - there is no writer API with which to
 * synthesize a valid MAPI/CFBF container in-memory the way XWPF/XSLF documents can be built for
 * the Office loaders. The pure-logic body-conversion helpers that the {@code .msg} path shares
 * with the HTML/RTF fallback chain ({@code htmlToText}, {@code stripRtfControlWords}) are instead
 * covered directly and exhaustively by {@link MailLoaderImplTextConversionTest}.
 *
 * <p>What this test does verify against the real {@link MailLoaderImpl#load} entry point is the
 * failure-handling contract that {@code loadMsgFile} relies on: a {@code .msg} file that is not a
 * valid CFBF/OLE2 container must degrade to the loader's existing generic error document rather
 * than propagating an exception or crashing the whole crawl.
 */
class MailLoaderImplMsgTest {

    private final MailLoaderImpl loader = new MailLoaderImpl();

    private DocumentSourceDescriptor descriptorFor(Path file) {
        return DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(file.toAbsolutePath().toString())
                .build();
    }

    @Test
    void corruptMsgFile_degradesToErrorDocument_insteadOfThrowing(@TempDir Path tempDir) throws Exception {
        Path msgFile = tempDir.resolve("corrupt.msg");
        // Plain text, not a CFBF/OLE2 container - MAPIMessage's constructor must reject this.
        Files.writeString(msgFile, "This is not a valid Outlook .msg file.", StandardCharsets.UTF_8);

        List<Document> documents = loader.load(descriptorFor(msgFile));

        assertEquals(1, documents.size());
        Document document = documents.get(0);
        assertEquals(Boolean.TRUE, document.getMetadata().get("parseError"),
                "a non-CFBF .msg file must be reported through the parseError metadata path");
        assertTrue(document.getText().contains("Unable to parse mail file"),
                "got: " + document.getText());
        assertEquals("corrupt.msg", document.getMetadata().get("fileName"));
    }

    @Test
    void emptyMsgFile_degradesToErrorDocument_insteadOfThrowing(@TempDir Path tempDir) throws Exception {
        Path msgFile = tempDir.resolve("empty.msg");
        Files.write(msgFile, new byte[0]);

        List<Document> documents = loader.load(descriptorFor(msgFile));

        assertEquals(1, documents.size());
        assertEquals(Boolean.TRUE, documents.get(0).getMetadata().get("parseError"));
    }

    @Test
    void supports_recognizesMsgExtensionCaseInsensitively() {
        DocumentSourceDescriptor upper = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl("/tmp/SomeMessage.MSG")
                .build();
        assertTrue(loader.supports(upper));
    }
}
