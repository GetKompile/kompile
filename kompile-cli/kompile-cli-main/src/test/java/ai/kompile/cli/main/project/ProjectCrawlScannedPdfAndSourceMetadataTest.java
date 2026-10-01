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
import ai.kompile.project.KompileProjectCrawlProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers two 2026-09-28 CLI chat-crawler audit items:
 *
 * <ul>
 *   <li>I4: a PDF whose standard extraction yields no text (a scanned PDF) is skipped with an
 *       actionable reason unless the project can run a VLM pipeline for it, in which case the
 *       crawl retries the file through the VLM pipeline and notes that in the result.</li>
 *   <li>I5/C7: for documents loaded by "external-materialized" (materialized connector
 *       Markdown - see LocalDocumentLoaderRegistry), the primary loader output's scalar metadata
 *       is carried as "sourceMetadata" on both the document row in documents.jsonl and every
 *       chunk record in chunks.jsonl, capped at 32 entries with string values truncated to 256
 *       chars.</li>
 * </ul>
 *
 * <p>The I4 cases call the package-private writeLocalCrawlMarkdown directly with a fake
 * ModelPipelineExecutor - never a real model or GPU, per project rules - mirroring the pattern
 * already established in ProjectCrawlNativeImagePdfTest. I5 uses the full CLI flow for the
 * primary chunks.jsonl/documents.jsonl contract with a small, realistic fixture, plus one direct
 * call to writeLocalCrawlMarkdown to exercise the 32-entry/256-char cap that a small end-to-end
 * fixture would never reach.</p>
 */
class ProjectCrawlScannedPdfAndSourceMetadataTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void scannedPdfWithoutVlmConfiguredIsSkippedWithAnActionableReason() throws Exception {
        Assumptions.assumeTrue(pdftotextAvailable(), "pdftotext must be on PATH for this test");
        Path projectRoot = tempDir.resolve("scanned-no-vlm");
        Path markdownDir = projectRoot.resolve("data/markdown/scanned");
        Files.createDirectories(markdownDir);
        Path pdf = tempDir.resolve("blank-no-vlm.pdf");
        writeBlankPdf(pdf);

        LocalCrawlCapabilities.ResolvedPipeline pipeline =
                LocalCrawlCapabilities.resolve(null, null, tempDir, pdf);
        ProjectCrawlCommand.LocalCrawlDocument document = pendingDocument("scanned-doc", pdf);
        KompileProjectCrawlProfile profile = new KompileProjectCrawlProfile();
        ProjectCrawlCommand.ModelPipelineExecutor neverCalled = (root, file, resolvedPipeline, loadedText) -> {
            throw new AssertionError("no VLM is configured; the executor must not run");
        };

        ProjectCrawlCommand.LocalMarkdownArtifact artifact = ProjectCrawlCommand.writeLocalCrawlMarkdown(
                projectRoot, markdownDir, document, pdf, profile, "scanned-no-vlm", pipeline, neverCalled, null);

        assertEquals("SKIPPED", artifact.status());
        // request is null here (no project pipeline registry at all), so the hint must name the
        // always-resolvable built-in vlm-document fallback, not the conventional "vlm-ocr-pdf" id
        // that only exists once a project actually registers it - see the hint-naming test below.
        assertEquals("no extractable text (scanned PDF?): re-crawl with pipelineId="
                + LocalCrawlCapabilities.VLM_PIPELINE + " or configure a VLM model", artifact.message());
    }

    @Test
    void scannedPdfSkipHintNamesTheProjectsRegisteredVlmOcrPipelineWhenOneExists() throws Exception {
        Assumptions.assumeTrue(pdftotextAvailable(), "pdftotext must be on PATH for this test");
        Path projectRoot = tempDir.resolve("scanned-hint-registry-default");
        Path markdownDir = projectRoot.resolve("data/markdown/scanned");
        Files.createDirectories(markdownDir);
        Path pdf = tempDir.resolve("blank-hint-registry-default.pdf");
        writeBlankPdf(pdf);

        LocalCrawlCapabilities.ResolvedPipeline pipeline =
                LocalCrawlCapabilities.resolve(null, null, tempDir, pdf);
        ProjectCrawlCommand.LocalCrawlDocument document =
                pendingDocument("scanned-doc-hint-registry-default", pdf);
        // No VLM is configured for THIS crawl (profile is null, exactly like the "no VLM
        // configured" case above), but the project registers "vlm-ocr-pdf" under
        // pipelineRegistry.defaults - the skip hint must name that pipeline, not vlm-document.
        ObjectNode request = mapper.createObjectNode();
        ObjectNode defaultEntry = request.putObject("pipelineRegistry").putArray("defaults").addObject();
        defaultEntry.put("pipelineId", "vlm-ocr-pdf");
        defaultEntry.put("pipelineType", "VLM");
        defaultEntry.set("processor", mapper.valueToTree(LocalCrawlCapabilities.builtinModelProcessor("VLM")));
        ProjectCrawlCommand.ModelPipelineExecutor neverCalled = (root, file, resolvedPipeline, loadedText) -> {
            throw new AssertionError("no VLM is configured; the executor must not run");
        };

        ProjectCrawlCommand.LocalMarkdownArtifact artifact = ProjectCrawlCommand.writeLocalCrawlMarkdown(
                projectRoot, markdownDir, document, pdf, null, "scanned-hint-registry-default", pipeline,
                neverCalled, request);

        assertEquals("SKIPPED", artifact.status());
        assertEquals("no extractable text (scanned PDF?): re-crawl with pipelineId=vlm-ocr-pdf"
                + " or configure a VLM model", artifact.message());
    }

    @Test
    void scannedPdfWithMultimodalProfileFallsBackToTheVlmPipeline() throws Exception {
        Assumptions.assumeTrue(pdftotextAvailable(), "pdftotext must be on PATH for this test");
        Path projectRoot = tempDir.resolve("scanned-vlm");
        Path markdownDir = projectRoot.resolve("data/markdown/scanned");
        Files.createDirectories(markdownDir);
        Path pdf = tempDir.resolve("blank-vlm.pdf");
        writeBlankPdf(pdf);

        LocalCrawlCapabilities.ResolvedPipeline pipeline =
                LocalCrawlCapabilities.resolve(null, null, tempDir, pdf);
        ProjectCrawlCommand.LocalCrawlDocument document = pendingDocument("scanned-doc-vlm", pdf);
        KompileProjectCrawlProfile profile = new KompileProjectCrawlProfile();
        profile.setMultimodal(true);
        ProjectCrawlCommand.ModelPipelineExecutor fakeVlm = (root, file, resolvedPipeline, loadedText) -> {
            assertEquals(LocalCrawlCapabilities.VLM_PIPELINE, resolvedPipeline.pipelineId());
            assertTrue(LocalCrawlCapabilities.usesModelPipeline(resolvedPipeline));
            return "OCR extracted: scanned content here";
        };

        ProjectCrawlCommand.LocalMarkdownArtifact artifact = ProjectCrawlCommand.writeLocalCrawlMarkdown(
                projectRoot, markdownDir, document, pdf, profile, "scanned-vlm", pipeline, fakeVlm, null);

        assertEquals("EXTRACTED", artifact.status());
        assertEquals("scanned PDF: used " + LocalCrawlCapabilities.VLM_PIPELINE, artifact.message());
        String markdown = Files.readString(projectRoot.resolve(artifact.markdownPath()), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("OCR extracted: scanned content here"), markdown);
    }

    @Test
    void scannedPdfPrefersTheProjectsRegisteredVlmOcrPipelineOverTheBuiltinFallback() throws Exception {
        Assumptions.assumeTrue(pdftotextAvailable(), "pdftotext must be on PATH for this test");
        Path projectRoot = tempDir.resolve("scanned-registry-default");
        Path markdownDir = projectRoot.resolve("data/markdown/scanned");
        Files.createDirectories(markdownDir);
        Path pdf = tempDir.resolve("blank-registry-default.pdf");
        writeBlankPdf(pdf);

        LocalCrawlCapabilities.ResolvedPipeline pipeline =
                LocalCrawlCapabilities.resolve(null, null, tempDir, pdf);
        ProjectCrawlCommand.LocalCrawlDocument document = pendingDocument("scanned-doc-registry-default", pdf);
        KompileProjectCrawlProfile profile = new KompileProjectCrawlProfile();
        profile.setMultimodal(true);

        // "vlm-ocr-pdf" is defined ONLY under pipelineRegistry.defaults - never under
        // request.pipelines[] - exactly the shape LocalProjectCrawlBackend.registerProjectPipelines
        // emits when project init's VLM OCR preset registers its pipeline. The scanned-PDF
        // fallback must find it through LocalCrawlCapabilities.scannedPdfPipelineId's full
        // pipelineDefinitions() merge instead of falling back to the built-in VLM_PIPELINE.
        ObjectNode request = mapper.createObjectNode();
        ObjectNode defaultEntry = request.putObject("pipelineRegistry").putArray("defaults").addObject();
        defaultEntry.put("pipelineId", "vlm-ocr-pdf");
        defaultEntry.put("pipelineType", "VLM");
        defaultEntry.set("processor", mapper.valueToTree(LocalCrawlCapabilities.builtinModelProcessor("VLM")));

        ProjectCrawlCommand.ModelPipelineExecutor fakeVlm = (root, file, resolvedPipeline, loadedText) -> {
            assertEquals("vlm-ocr-pdf", resolvedPipeline.pipelineId());
            assertTrue(LocalCrawlCapabilities.usesModelPipeline(resolvedPipeline));
            return "OCR extracted: registry default pipeline content";
        };

        ProjectCrawlCommand.LocalMarkdownArtifact artifact = ProjectCrawlCommand.writeLocalCrawlMarkdown(
                projectRoot, markdownDir, document, pdf, profile, "scanned-registry-default", pipeline, fakeVlm,
                request);

        assertEquals("EXTRACTED", artifact.status());
        assertEquals("scanned PDF: used vlm-ocr-pdf", artifact.message());
        String markdown = Files.readString(projectRoot.resolve(artifact.markdownPath()), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("OCR extracted: registry default pipeline content"), markdown);
    }

    @Test
    void externalMaterializedSourceMetadataAppearsInChunksAndDocumentsJsonl() throws Exception {
        Path projectRoot = tempDir.resolve("materialized-project");
        Path connectorDir = tempDir.resolve("connector-source");
        Files.createDirectories(connectorDir);
        Path message = connectorDir.resolve("msg-1.md");
        String fixture = "<!-- kompile-source-type: SLACK -->\n"
                + "<!-- kompile-source-metadata: {\"channel\":\"general\",\"author\":\"adam\","
                + "\"messageId\":12345,\"pinned\":true} -->\n"
                + "\n"
                + "Hello from the materialized Slack message body.\n";
        Files.writeString(message, fixture, StandardCharsets.UTF_8);

        assertEquals(0, execute("project", "create",
                "--root", projectRoot.toString(),
                "--name", "materialized-project",
                "--backend", "local"));
        assertEquals(0, execute("project", "crawl-add",
                "--root", projectRoot.toString(),
                "--id", "materialized",
                "--source", message.toString(),
                "--type", "file",
                "--loader", "external-materialized",
                "--chunker", "no-op"));
        assertEquals(0, execute("project", "crawl",
                "--root", projectRoot.toString(),
                "--id", "materialized"));

        Path crawlDir = projectRoot.resolve("data/crawls/materialized");
        JsonNode documentRow = mapper.readTree(
                Files.readAllLines(crawlDir.resolve("documents.jsonl")).get(0));
        assertEquals("EXTRACTED", documentRow.path("extractionStatus").asText(), documentRow.toString());
        assertEquals("external-materialized", documentRow.path("loader").asText());
        JsonNode documentMetadata = documentRow.path("sourceMetadata");
        assertEquals("general", documentMetadata.path("channel").asText());
        assertEquals("adam", documentMetadata.path("author").asText());
        assertEquals(12345, documentMetadata.path("messageId").asInt());
        assertTrue(documentMetadata.path("pinned").asBoolean());

        JsonNode chunkRow = mapper.readTree(
                Files.readAllLines(crawlDir.resolve("chunks.jsonl")).get(0));
        assertTrue(chunkRow.path("text").asText().contains("Hello from the materialized Slack message body."),
                chunkRow.toString());
        JsonNode chunkMetadata = chunkRow.path("sourceMetadata");
        assertEquals("general", chunkMetadata.path("channel").asText());
        assertEquals("adam", chunkMetadata.path("author").asText());
        assertEquals(12345, chunkMetadata.path("messageId").asInt());
        assertTrue(chunkMetadata.path("pinned").asBoolean());
    }

    @Test
    void sourceMetadataIsCappedAtThirtyTwoEntriesAndValuesTruncatedAt256Chars() throws Exception {
        Path projectRoot = tempDir.resolve("materialized-cap-project");
        Path markdownDir = projectRoot.resolve("data/markdown/materialized-cap");
        Files.createDirectories(markdownDir);
        Path connectorDir = tempDir.resolve("connector-cap-source");
        Files.createDirectories(connectorDir);
        Path message = connectorDir.resolve("msg-cap.md");

        StringBuilder metadataJson = new StringBuilder("{");
        for (int i = 0; i < 40; i++) {
            if (i > 0) metadataJson.append(',');
            metadataJson.append("\"key").append(i).append("\":");
            metadataJson.append(i == 0 ? "\"" + "x".repeat(300) + "\"" : i);
        }
        metadataJson.append('}');
        String fixture = "<!-- kompile-source-type: SLACK -->\n"
                + "<!-- kompile-source-metadata: " + metadataJson + " -->\n"
                + "\n"
                + "Body text for the cap test.\n";
        Files.writeString(message, fixture, StandardCharsets.UTF_8);

        LocalCrawlCapabilities.ResolvedPipeline pipeline = new LocalCrawlCapabilities.ResolvedPipeline(
                "local-knowledge", "STANDARD_TEXT", "external-materialized", "no-op",
                4000, 0, Map.of(), Map.of());
        ProjectCrawlCommand.LocalCrawlDocument document = pendingDocument("materialized-cap-doc", message);
        ProjectCrawlCommand.ModelPipelineExecutor neverCalled = (root, file, resolvedPipeline, loadedText) -> {
            throw new AssertionError("external-materialized is not a model pipeline");
        };

        ProjectCrawlCommand.LocalMarkdownArtifact artifact = ProjectCrawlCommand.writeLocalCrawlMarkdown(
                projectRoot, markdownDir, document, message, null, "materialized-cap", pipeline, neverCalled, null);
        assertEquals("EXTRACTED", artifact.status());
        document = document.withMarkdown(artifact);

        Map<String, Object> sourceMetadata = document.sourceMetadata();
        assertEquals(32, sourceMetadata.size(), sourceMetadata.keySet().toString());
        Object key0 = sourceMetadata.get("key0");
        assertTrue(key0 instanceof String, "key0 should be a truncated string: " + key0);
        assertEquals(256, ((String) key0).length());
    }

    private static ProjectCrawlCommand.LocalCrawlDocument pendingDocument(String documentId, Path file)
            throws Exception {
        return new ProjectCrawlCommand.LocalCrawlDocument(documentId, file.toString(),
                file.getFileName().toString(), Files.size(file), Instant.now().toString(),
                "application/octet-stream", null, null, "PENDING", null,
                null, null, null, null, 0, 0, List.of(), Map.of());
    }

    private static boolean pdftotextAvailable() {
        try {
            Process process = new ProcessBuilder("pdftotext", "-v").redirectErrorStream(true).start();
            process.waitFor(5, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeBlankPdf(Path path) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText("");
                content.endText();
            }
            document.save(path.toFile());
        }
    }

    private static int execute(String... args) {
        CommandLine commandLine = new CommandLine(new MainCommand());
        commandLine.setOut(new PrintWriter(new StringWriter()));
        commandLine.setErr(new PrintWriter(new StringWriter()));
        return commandLine.execute(args);
    }
}
