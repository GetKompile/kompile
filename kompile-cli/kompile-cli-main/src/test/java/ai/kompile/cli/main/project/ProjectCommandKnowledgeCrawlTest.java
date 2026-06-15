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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectCommandKnowledgeCrawlTest {

    @TempDir
    Path tempDir;

    @Test
    void localKnowledgeCrawlExtractsPdfAndHtmlToProjectMarkdown() throws Exception {
        Path projectRoot = tempDir.resolve("knowledge-project");
        Path pdf = tempDir.resolve("knowledge-source.pdf");
        Path html = tempDir.resolve("knowledge-source.html");
        writePdf(pdf, "Kompile knowledge PDF uniquealpha budget planning text.");
        Files.writeString(html, """
                <!doctype html>
                <html>
                <head>
                  <title>Knowledge HTML</title>
                  <style>.hidden { color: red; }</style>
                </head>
                <body>
                  <h1>Semantic Layer</h1>
                  <p>HTML uniqueomega margin bridge content.</p>
                </body>
                </html>
                """, StandardCharsets.UTF_8);

        assertEquals(0, execute("project", "create",
                "--root", projectRoot.toString(),
                "--name", "knowledge-project",
                "--backend", "local"));
        assertEquals(0, execute("project", "crawl-add",
                "--root", projectRoot.toString(),
                "--id", "knowledge-docs",
                "--name", "Knowledge Docs",
                "--source", pdf.toString(),
                "--source", html.toString(),
                "--type", "file",
                "--include", "*.pdf,*.html",
                "--loader", "local-knowledge",
                "--chunker", "markdown-fixed",
                "--collection", "knowledge-docs"));
        assertEquals(0, execute("project", "crawl",
                "--root", projectRoot.toString(),
                "--id", "knowledge-docs"));

        Path markdownDir = projectRoot.resolve("data/markdown/knowledge-docs");
        Path crawlDir = projectRoot.resolve("data/crawls/knowledge-docs");
        String pdfMarkdown = Files.readString(markdownDir.resolve("knowledge-source.pdf.md"));
        String htmlMarkdown = Files.readString(markdownDir.resolve("knowledge-source.html.md"));
        String documents = Files.readString(crawlDir.resolve("documents.jsonl"));
        String chunks = Files.readString(crawlDir.resolve("chunks.jsonl"));
        String analysis = Files.readString(crawlDir.resolve("analysis.json"));
        String summary = Files.readString(crawlDir.resolve("crawl-result.json"));

        assertTrue(pdfMarkdown.contains("uniquealpha budget planning"));
        assertTrue(htmlMarkdown.contains("# Knowledge HTML"));
        assertTrue(htmlMarkdown.contains("# Semantic Layer"));
        assertTrue(htmlMarkdown.contains("uniqueomega margin bridge"));
        assertFalse(htmlMarkdown.contains("<style>"));
        assertFalse(chunks.contains("<!doctype"));
        assertTrue(chunks.contains("uniquealpha"));
        assertTrue(chunks.contains("uniqueomega"));
        assertTrue(chunks.contains("knowledge-source.pdf#chunk-0"));
        assertTrue(chunks.contains("knowledge-source.html#chunk-0"));
        assertTrue(documents.contains("\"markdownPath\":\"data/markdown/knowledge-docs/knowledge-source.pdf.md\""));
        assertTrue(documents.contains("\"extractionStatus\":\"EXTRACTED\""));
        assertTrue(summary.contains("\"markdownCount\" : 2"));
        assertTrue(analysis.contains("\"topTerms\""));
    }

    private static int execute(String... args) {
        CommandLine commandLine = new CommandLine(new MainCommand());
        commandLine.setOut(new PrintWriter(new StringWriter()));
        commandLine.setErr(new PrintWriter(new StringWriter()));
        return commandLine.execute(args);
    }

    private static void writePdf(Path path, String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText(text);
                content.endText();
            }
            document.save(path.toFile());
        }
    }
}
