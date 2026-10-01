/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.project;

import ai.kompile.cli.main.MainCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers two 2026-09-28 CLI chat-crawler audit items:
 *
 * <ul>
 *   <li>I1: the project-local folder walk includes mail (.eml/.mbox/.msg), office
 *       (.doc/.docx/.ppt/.pptx), rtf, and image extensions; images are only ever processed when
 *       the resolved pipeline is a model (VLM) pipeline, otherwise they are skipped with an
 *       actionable reason.</li>
 *   <li>I2: a file is sniffed for binary content (a NUL byte, or a malformed/unmappable UTF-8
 *       byte sequence) before any UTF-8 text read, so a non-text file is skipped instead of
 *       emitting garbage. A valid UTF-8 multibyte character truncated exactly at the sniff
 *       boundary must NOT be misdetected as binary.</li>
 * </ul>
 *
 * <p>Both go through the full "project create" / "crawl-add" / "crawl" CLI flow and assert on
 * documents.jsonl rather than calling the private per-extension gates directly: those gates
 * (isKnowledgeSource and friends) are private, and the mail/office/rtf loaders themselves are
 * concurrently being implemented by another workstream, so a documents.jsonl row - present
 * regardless of whether that specific loader is finished yet - is what proves the folder walk
 * picked the file up at all.</p>
 */
class ProjectCrawlLocalDiscoveryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void folderWalkDiscoversMailOfficeRtfAndImageFiles() throws Exception {
        Path projectRoot = tempDir.resolve("discovery-project");
        Path sourceDir = tempDir.resolve("discovery-source");
        Files.createDirectories(sourceDir);
        List<String> fileNames = List.of("message.eml", "archive.mbox", "memo.msg",
                "letter.doc", "report.docx", "slides.ppt", "deck.pptx", "note.rtf", "photo.png");
        for (String fileName : fileNames) {
            Files.writeString(sourceDir.resolve(fileName), "placeholder content for " + fileName,
                    StandardCharsets.UTF_8);
        }

        assertEquals(0, execute("project", "create",
                "--root", projectRoot.toString(),
                "--name", "discovery-project",
                "--backend", "local"));
        assertEquals(0, execute("project", "crawl-add",
                "--root", projectRoot.toString(),
                "--id", "discovery",
                "--source", sourceDir.toString(),
                "--type", "directory"));
        assertEquals(0, execute("project", "crawl",
                "--root", projectRoot.toString(),
                "--id", "discovery"));

        Path crawlDir = projectRoot.resolve("data/crawls/discovery");
        List<String> lines = Files.readAllLines(crawlDir.resolve("documents.jsonl"));
        assertEquals(fileNames.size(), lines.size(),
                "expected one documents.jsonl row per discovered file: " + lines);

        JsonNode imageRow = null;
        for (String fileName : fileNames) {
            JsonNode row = rowFor(lines, fileName);
            if (fileName.equals("photo.png")) imageRow = row;
        }

        assertNotNull(imageRow, "photo.png missing from documents.jsonl");
        assertEquals("SKIPPED", imageRow.path("extractionStatus").asText());
        String message = imageRow.path("extractionMessage").asText();
        assertTrue(message.contains("image needs a vision pipeline"), message);
        assertTrue(message.contains(LocalCrawlCapabilities.VLM_PIPELINE), message);
    }

    @Test
    void binarySniffSkipsNulAndMalformedUtf8ButAllowsASplitMultibyteCharacterAtTheSampleBoundary()
            throws Exception {
        Path projectRoot = tempDir.resolve("binary-project");
        Path sourceDir = tempDir.resolve("binary-source");
        Files.createDirectories(sourceDir);

        Files.write(sourceDir.resolve("nul.txt"), new byte[] {'h', 'i', 0, 'x'});
        // 0xFF/0xFE are never valid UTF-8 leading bytes in any position; no NUL byte here, so this
        // isolates the "malformed UTF-8" branch from the NUL-byte branch above.
        Files.write(sourceDir.resolve("malformed.txt"),
                new byte[] {(byte) 0xFF, (byte) 0xFE, (byte) 0xFD, (byte) 0xFC});
        // 8191 ASCII bytes, then the 2-byte UTF-8 encoding of U+00E9 (e-acute) straddling the
        // 8192-byte sniff window: its first byte lands exactly at sample index 8191 (the last byte
        // read), and its second byte falls just outside the sample. Decoding with endOfInput=false
        // must treat that as underflow, not a malformed-input error.
        String boundaryContent = "a".repeat(8191) + "é" + "tail marker XYZ123";
        Files.writeString(sourceDir.resolve("boundary.txt"), boundaryContent, StandardCharsets.UTF_8);

        assertEquals(0, execute("project", "create",
                "--root", projectRoot.toString(),
                "--name", "binary-project",
                "--backend", "local"));
        assertEquals(0, execute("project", "crawl-add",
                "--root", projectRoot.toString(),
                "--id", "binary",
                "--source", sourceDir.toString(),
                "--type", "directory"));
        assertEquals(0, execute("project", "crawl",
                "--root", projectRoot.toString(),
                "--id", "binary"));

        Path crawlDir = projectRoot.resolve("data/crawls/binary");
        List<String> lines = Files.readAllLines(crawlDir.resolve("documents.jsonl"));
        JsonNode nul = rowFor(lines, "nul.txt");
        JsonNode malformed = rowFor(lines, "malformed.txt");
        JsonNode boundary = rowFor(lines, "boundary.txt");

        assertEquals("SKIPPED", nul.path("extractionStatus").asText());
        assertEquals("binary content; no text loader for .txt", nul.path("extractionMessage").asText());
        assertEquals("SKIPPED", malformed.path("extractionStatus").asText());
        assertEquals("binary content; no text loader for .txt", malformed.path("extractionMessage").asText());

        assertEquals("EXTRACTED", boundary.path("extractionStatus").asText(), boundary.toString());
        Path markdownPath = projectRoot.resolve(boundary.path("markdownPath").asText());
        String markdown = Files.readString(markdownPath, StandardCharsets.UTF_8);
        assertTrue(markdown.contains("tail marker XYZ123"), markdown);
        assertTrue(markdown.contains("é"), markdown);
    }

    private JsonNode rowFor(List<String> lines, String relativePath) throws Exception {
        for (String line : lines) {
            JsonNode row = mapper.readTree(line);
            if (relativePath.equals(row.path("relativePath").asText())) return row;
        }
        throw new AssertionError(relativePath + " missing from documents.jsonl: " + lines);
    }

    private static int execute(String... args) {
        CommandLine commandLine = new CommandLine(new MainCommand());
        commandLine.setOut(new PrintWriter(new StringWriter()));
        commandLine.setErr(new PrintWriter(new StringWriter()));
        return commandLine.execute(args);
    }
}
