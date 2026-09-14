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
        writePdf(pdf, "Kompile knowledge PDF uniquealpha budget planning text. uniquealpha uniquealpha.");
        Files.writeString(html, """
                <!doctype html>
                <html>
                <head>
                  <title>Knowledge HTML</title>
                  <style>.hidden { color: red; }</style>
                </head>
                <body>
                  <h1>Semantic Layer</h1>
                  <p>HTML uniqueomega margin bridge content. uniqueomega uniqueomega.</p>
                  <p>galaxía galaxía 𐐨𐐻𐐯 𐐨𐐻𐐯</p>
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
                "--source", tempDir.toString(),
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
        assertFalse(chunks.contains("converter: kompile-project-crawl"));
        assertFalse(chunks.contains("crawl_profile:"));
        assertTrue(chunks.contains("uniquealpha"));
        assertTrue(chunks.contains("uniqueomega"));
        assertTrue(chunks.contains("knowledge-source.pdf#chunk-0"));
        assertTrue(chunks.contains("knowledge-source.html#chunk-0"));
        assertTrue(documents.contains("\"markdownPath\":\"data/markdown/knowledge-docs/knowledge-source.pdf.md\""));
        assertTrue(documents.contains("\"extractionStatus\":\"EXTRACTED\""));
        assertTrue(summary.contains("\"markdownCount\" : 2"));
        assertTrue(analysis.contains("\"documentCount\" : 2"), analysis);
        assertTrue(analysis.contains("\"topTerms\""));
        assertTrue(analysis.contains("\"term\":\"uniquealpha\",\"count\":3"), analysis);
        assertTrue(analysis.contains("\"term\":\"uniqueomega\",\"count\":3"), analysis);
        assertTrue(analysis.contains("\"term\":\"galaxía\",\"count\":2"), analysis);
        assertTrue(ProjectCrawlCommand.localKnowledgeTerms("galaxía 𐐨𐐻𐐯").contains("𐐨𐐻𐐯"));
        assertEquals(3, ProjectCrawlCommand.countWords("cafe\u0301 𐐨𐐻𐐯 galaxy"));
        assertEquals("line\\n---\\r\\u2028end", ProjectCrawlCommand.escapeYaml("line\n---\r\u2028end"));
        assertFalse(analysis.contains("\"term\":\"converter\""), analysis);
        assertFalse(analysis.contains("\"term\":\"collection\""), analysis);
        assertFalse(analysis.contains("\"term\":\"profile\""), analysis);
    }

    @Test
    void pageCitationsSurviveNormalizationBlankPagesAndChunking() throws Exception {
        Path pdf = tempDir.resolve("pages.pdf");
        writePdf(pdf, "  First   page alpha.  ", "", "Third page omega.");
        var loaded = LocalDocumentLoaderRegistry.load(pdf, "pdf", java.util.Map.of());
        assertEquals("First page alpha.\n\nThird page omega.", loaded.text());
        assertEquals(1, loaded.outputs().get(0).metadata().get("pageNumber"));
        assertEquals(3, loaded.outputs().get(1).metadata().get("pageNumber"));
        for (var section : loaded.outputs()) {
            int start = ((Number) section.metadata().get("bodyStart")).intValue();
            int end = ((Number) section.metadata().get("bodyEnd")).intValue();
            assertTrue(loaded.text().substring(start, end).endsWith("."));
        }
        Path project = tempDir.resolve("page-project");
        assertEquals(0, execute("project", "create", "--root", project.toString(),
                "--name", "page-project", "--backend", "local"));
        assertEquals(0, execute("project", "crawl-add", "--root", project.toString(),
                "--id", "pages", "--source", pdf.toString(), "--type", "file",
                "--loader", "local-knowledge", "--chunker", "markdown-fixed"));
        assertEquals(0, execute("project", "crawl", "--root", project.toString(), "--id", "pages"));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var chunk = mapper.readTree(Files.readAllLines(project.resolve("data/crawls/pages/chunks.jsonl")).get(0));
        assertEquals(mapper.readTree("[1,3]"), chunk.path("pages"));
        assertEquals("# pages.pdf\n\n" + loaded.text(), chunk.path("text").asText());
    }

    private static int execute(String... args) {
        CommandLine commandLine = new CommandLine(new MainCommand());
        commandLine.setOut(new PrintWriter(new StringWriter()));
        commandLine.setErr(new PrintWriter(new StringWriter()));
        return commandLine.execute(args);
    }

    private static void writePdf(Path path, String... texts) throws Exception {
        try (PDDocument document = new PDDocument()) {
          for (String text : texts) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText(text);
                content.endText();
            }
          }
            document.save(path.toFile());
        }
    }
}
