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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectCommandMarkdownTest {

    @TempDir
    Path tempDir;

    @Test
    void markdownListShowsProjectMarkdownFiles() throws Exception {
        Path projectRoot = tempDir.resolve("md-list-project");

        assertEquals(0, execute("project", "create",
                "--root", projectRoot.toString(),
                "--name", "md-list-project",
                "--backend", "local"));

        Files.writeString(
                projectRoot.resolve("data/markdown/intro.md"),
                "---\ntitle: Introduction\ntags:\n  - overview\n---\n\n# Introduction\n\nWelcome.\n",
                StandardCharsets.UTF_8);
        Files.createDirectories(projectRoot.resolve("data/markdown/sub"));
        Files.writeString(
                projectRoot.resolve("data/markdown/sub/details.md"),
                "# Details\n\nMore info here.\n",
                StandardCharsets.UTF_8);

        String output = executeCapture("project", "markdown-list",
                "--root", projectRoot.toString());

        assertTrue(output.contains("intro.md"), "Expected intro.md in output: " + output);
        assertTrue(output.contains("sub/details.md"), "Expected sub/details.md in output: " + output);
        assertTrue(output.contains("Introduction"), "Expected title Introduction in output: " + output);
    }

    @Test
    void markdownReadPrintsFileBody() throws Exception {
        Path projectRoot = tempDir.resolve("md-read-project");

        assertEquals(0, execute("project", "create",
                "--root", projectRoot.toString(),
                "--name", "md-read-project",
                "--backend", "local"));

        String markdownContent = "---\ntitle: My Note\n---\n\n# My Note\n\nBody text unique-read-marker.\n";
        Files.writeString(
                projectRoot.resolve("data/markdown/note.md"),
                markdownContent,
                StandardCharsets.UTF_8);

        String output = executeCapture("project", "markdown-read",
                "--root", projectRoot.toString(),
                "note.md");

        assertTrue(output.contains("unique-read-marker"), "Expected body content in output: " + output);
        assertTrue(output.contains("# My Note"), "Expected heading in output: " + output);
    }

    @Test
    void markdownSearchFindsMatchingFiles() throws Exception {
        Path projectRoot = tempDir.resolve("md-search-project");

        assertEquals(0, execute("project", "create",
                "--root", projectRoot.toString(),
                "--name", "md-search-project",
                "--backend", "local"));

        Files.writeString(
                projectRoot.resolve("data/markdown/vectors.md"),
                "# Vector Stores\n\nHNSW index for similarity search.\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                projectRoot.resolve("data/markdown/pipelines.md"),
                "# Pipeline Guide\n\nHow to build ML pipelines.\n",
                StandardCharsets.UTF_8);

        String output = executeCapture("project", "markdown-search",
                "--root", projectRoot.toString(),
                "vector");

        assertTrue(output.contains("vectors.md"), "Expected vectors.md in search output: " + output);
        assertTrue(output.contains("Vector Stores"), "Expected title in search output: " + output);
    }

    @Test
    void markdownSearchReturnsNoMatchMessage() throws Exception {
        Path projectRoot = tempDir.resolve("md-search-empty");

        assertEquals(0, execute("project", "create",
                "--root", projectRoot.toString(),
                "--name", "md-search-empty",
                "--backend", "local"));

        String output = executeCapture("project", "markdown-search",
                "--root", projectRoot.toString(),
                "nonexistent-term");

        assertTrue(output.contains("No markdown files matched"), "Expected no match message: " + output);
    }

    @Test
    void crawlProducesMarkdownThenListableAndSearchable() throws Exception {
        Path projectRoot = tempDir.resolve("crawl-md-project");
        Path htmlSource = tempDir.resolve("source.html");
        Files.writeString(htmlSource, """
                <!doctype html>
                <html>
                <head><title>Test Doc</title></head>
                <body>
                  <h1>Heading</h1>
                  <p>Unique crawl marker alpha-bravo-charlie.</p>
                </body>
                </html>
                """, StandardCharsets.UTF_8);

        assertEquals(0, execute("project", "create",
                "--root", projectRoot.toString(),
                "--name", "crawl-md-project",
                "--backend", "local"));
        assertEquals(0, execute("project", "crawl-add",
                "--root", projectRoot.toString(),
                "--id", "test-crawl",
                "--name", "Test Crawl",
                "--source", htmlSource.toString(),
                "--type", "file",
                "--include", "*.html",
                "--loader", "local-knowledge",
                "--chunker", "markdown-fixed",
                "--collection", "test-crawl"));
        assertEquals(0, execute("project", "crawl",
                "--root", projectRoot.toString(),
                "--id", "test-crawl"));

        // Verify the crawled markdown shows up in markdown-list
        String listOutput = executeCapture("project", "markdown-list",
                "--root", projectRoot.toString());
        assertTrue(listOutput.contains("test-crawl/"), "Expected test-crawl/ in list output: " + listOutput);

        // Verify searchable
        String searchOutput = executeCapture("project", "markdown-search",
                "--root", projectRoot.toString(),
                "alpha-bravo-charlie");
        assertTrue(searchOutput.contains("source"), "Expected source file in search output: " + searchOutput);

        // Catalog files should exist
        assertTrue(Files.isRegularFile(projectRoot.resolve("data/markdown/project-markdown.json")));
        assertTrue(Files.isRegularFile(projectRoot.resolve("data/crawls/project-crawls.json")));

        // Verify markdown catalog contains enriched fields from frontmatter
        String catalogJson = Files.readString(projectRoot.resolve("data/markdown/project-markdown.json"), StandardCharsets.UTF_8);
        assertTrue(catalogJson.contains("\"crawlProfile\""), "Expected crawlProfile in markdown catalog: " + catalogJson);
        assertTrue(catalogJson.contains("\"collection\""), "Expected collection in markdown catalog: " + catalogJson);
        assertTrue(catalogJson.contains("\"converter\""), "Expected converter in markdown catalog: " + catalogJson);
        assertTrue(catalogJson.contains("\"project\""), "Expected project in markdown catalog: " + catalogJson);

        // Verify crawl catalog contains result data
        String crawlCatalogJson = Files.readString(projectRoot.resolve("data/crawls/project-crawls.json"), StandardCharsets.UTF_8);
        assertTrue(crawlCatalogJson.contains("\"profileId\""), "Expected profileId in crawl catalog: " + crawlCatalogJson);
        assertTrue(crawlCatalogJson.contains("test-crawl"), "Expected test-crawl in crawl catalog: " + crawlCatalogJson);

        // Verify crawled markdown contains YAML frontmatter with project/crawl metadata
        Path mdDir = projectRoot.resolve("data/markdown/test-crawl");
        assertTrue(Files.isDirectory(mdDir), "Expected markdown directory: " + mdDir);
        try (var mdFiles = Files.list(mdDir)) {
            Path mdFile = mdFiles.filter(p -> p.toString().endsWith(".md")).findFirst()
                    .orElseThrow(() -> new AssertionError("No .md file found in " + mdDir));
            String mdContent = Files.readString(mdFile, StandardCharsets.UTF_8);
            assertTrue(mdContent.startsWith("---"), "Expected YAML frontmatter start: " + mdContent.substring(0, Math.min(50, mdContent.length())));
            assertTrue(mdContent.contains("crawl_profile: \"test-crawl\""), "Expected crawl_profile in frontmatter");
            assertTrue(mdContent.contains("project: \"crawl-md-project\""), "Expected project name in frontmatter");
            assertTrue(mdContent.contains("converter: kompile-project-crawl"), "Expected converter in frontmatter");
            assertTrue(mdContent.contains("collection: \"test-crawl\""), "Expected collection in frontmatter");
        }
    }

    @Test
    void crawlWithFactSheetNameIncludesItInFrontmatter() throws Exception {
        Path projectRoot = tempDir.resolve("crawl-fs-project");
        Path textSource = tempDir.resolve("note.txt");
        Files.writeString(textSource, "Knowledge base entry about quantum computing.", StandardCharsets.UTF_8);

        assertEquals(0, execute("project", "create",
                "--root", projectRoot.toString(),
                "--name", "fs-test",
                "--backend", "local"));
        assertEquals(0, execute("project", "crawl-add",
                "--root", projectRoot.toString(),
                "--id", "fs-crawl",
                "--name", "FS Crawl",
                "--source", textSource.toString(),
                "--type", "file",
                "--include", "*.txt",
                "--loader", "local-knowledge",
                "--chunker", "markdown-fixed",
                "--collection", "fs-collection",
                "--fact-sheet", "My Research Sheet"));
        assertEquals(0, execute("project", "crawl",
                "--root", projectRoot.toString(),
                "--id", "fs-crawl"));

        Path mdDir = projectRoot.resolve("data/markdown/fs-crawl");
        assertTrue(Files.isDirectory(mdDir), "Expected markdown directory: " + mdDir);
        try (var mdFiles = Files.list(mdDir)) {
            Path mdFile = mdFiles.filter(p -> p.toString().endsWith(".md")).findFirst()
                    .orElseThrow(() -> new AssertionError("No .md file found in " + mdDir));
            String mdContent = Files.readString(mdFile, StandardCharsets.UTF_8);
            assertTrue(mdContent.contains("fact_sheet: \"My Research Sheet\""),
                    "Expected fact_sheet in frontmatter: " + mdContent.substring(0, Math.min(200, mdContent.length())));
        }

        // Also verify crawl-result.json includes factSheetName
        Path crawlResult = projectRoot.resolve("data/crawls/fs-crawl/crawl-result.json");
        assertTrue(Files.isRegularFile(crawlResult), "Expected crawl-result.json");
        String resultJson = Files.readString(crawlResult, StandardCharsets.UTF_8);
        assertTrue(resultJson.contains("\"factSheetName\" : \"My Research Sheet\""),
                "Expected factSheetName in crawl-result.json: " + resultJson);
    }

    private static int execute(String... args) {
        CommandLine commandLine = new CommandLine(new MainCommand());
        commandLine.setOut(new PrintWriter(new StringWriter()));
        commandLine.setErr(new PrintWriter(new StringWriter()));
        return commandLine.execute(args);
    }

    private static String executeCapture(String... args) {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream captureStream = new PrintStream(baos, true, StandardCharsets.UTF_8);
        try {
            System.setOut(captureStream);
            CommandLine commandLine = new CommandLine(new MainCommand());
            commandLine.setOut(new PrintWriter(captureStream));
            commandLine.setErr(new PrintWriter(new StringWriter()));
            int exitCode = commandLine.execute(args);
            assertEquals(0, exitCode, "Command exited with non-zero code: " + exitCode);
            return baos.toString(StandardCharsets.UTF_8);
        } finally {
            System.setOut(originalOut);
        }
    }
}
