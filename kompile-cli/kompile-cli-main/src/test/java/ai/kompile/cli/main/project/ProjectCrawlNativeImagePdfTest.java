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
import ai.kompile.utils.NativeImageInfo;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the two failure modes that made native-image PDF crawls lose the whole crawl instead of
 * failing just the one document:
 *
 * <ul>
 *   <li>PDFBox's {@code PDDocument} static initializer unconditionally touches AWT's
 *       {@code ColorModel}/{@code Raster} (PDFBox's own workaround for an unrelated JDK
 *       color-conversion race). Under GraalVM Native Image, {@code libawt.so} DOES load from
 *       beside the binary, but its own {@code JNI_OnLoad} fails inside
 *       {@code JNU_NewStringPlatform} because the image does not register the JNI metadata that
 *       call needs; Substrate reports that as a FATAL, non-catchable JNI error ("Could not
 *       allocate library name") that aborts the WHOLE process - no {@code try/catch} can stop it.
 *       The fix routes "pdf" straight to pdftotext under native image, before PDFBox is ever
 *       touched, mirroring the "table" loader's existing {@code streamPdfText} native-image guard,
 *       and feeds pdftotext's pages through the same per-document offset loop PDFBox uses so
 *       citations (pageNumber/bodyStart/bodyEnd) stay at parity between the two extractors.</li>
 *   <li>A model-pipeline executor (VLM/OCR) that throws {@link LinkageError} used to escape
 *       {@code writeLocalCrawlMarkdown} entirely instead of failing just that one artifact.</li>
 * </ul>
 */
class ProjectCrawlNativeImagePdfTest {

    // Mirrors ai.kompile.utils.NativeImageInfo.IMAGE_CODE_PROPERTY, which is package-private to a
    // different package. NativeImageInfo.isRunningInNativeImage() re-reads this property on every
    // call, so setting it simulates native-image mode from an ordinary JVM test.
    private static final String IMAGE_CODE_PROPERTY = "org.graalvm.nativeimage.imagecode";

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void nativeImagePdfCrawlUsesPdftotextAndNeverTouchesPdfbox() throws Exception {
        Assumptions.assumeTrue(pdftotextAvailable(), "pdftotext must be on PATH for this test");

        Path projectRoot = tempDir.resolve("native-pdf-project");
        Path pdf = tempDir.resolve("native.pdf");
        writePdf(pdf, "First page alpha.", "", "Third page omega.");

        assertEquals(0, execute("project", "create",
                "--root", projectRoot.toString(),
                "--name", "native-pdf-project",
                "--backend", "local"));
        assertEquals(0, execute("project", "crawl-add",
                "--root", projectRoot.toString(),
                "--id", "native-pdf",
                "--source", pdf.toString(),
                "--type", "file",
                "--loader", "local-knowledge",
                "--chunker", "markdown-fixed"));
        // Only the crawl needs simulated native mode. Keep the window that narrow and restore the
        // previous value: NativeImageInfo caches getExecutablePath() once it resolves in "runtime"
        // mode, so anything in the window that resolved it would leak native state into later tests.
        String previousImageCode = System.getProperty(IMAGE_CODE_PROPERTY);
        System.setProperty(IMAGE_CODE_PROPERTY, "runtime");
        try {
            assertEquals(0, execute("project", "crawl",
                    "--root", projectRoot.toString(),
                    "--id", "native-pdf"));
        } finally {
            if (previousImageCode == null) System.clearProperty(IMAGE_CODE_PROPERTY);
            else System.setProperty(IMAGE_CODE_PROPERTY, previousImageCode);
        }
        assertNull(NativeImageInfo.getExecutablePath(), "simulated native crawl left a cached executable path");

        Path markdownDir = projectRoot.resolve("data/markdown/native-pdf");
        Path crawlDir = projectRoot.resolve("data/crawls/native-pdf");
        String pdfMarkdown = Files.readString(markdownDir.resolve("native.pdf.md"));
        String documents = Files.readString(crawlDir.resolve("documents.jsonl"));
        JsonNode chunk = mapper.readTree(Files.readAllLines(crawlDir.resolve("chunks.jsonl")).get(0));

        assertTrue(pdfMarkdown.contains("First page alpha."), pdfMarkdown);
        assertTrue(pdfMarkdown.contains("Third page omega."), pdfMarkdown);
        assertTrue(documents.contains("\"extractionStatus\":\"EXTRACTED\""), documents);
        assertTrue(chunk.path("text").asText().contains("First page alpha."));
        assertTrue(chunk.path("text").asText().contains("Third page omega."));
        // The pdftotext path feeds pages through the same per-document bodyStart/bodyEnd loop
        // PDFBox uses, so citations stay at parity: page 2 is blank (skipped, like
        // PdfExtendedLoaderImpl.extractByPages) but numbering is not compacted around the gap.
        assertEquals(mapper.readTree("[1,3]"), chunk.path("pages"));
    }

    @Test
    void modelPipelineLinkageErrorFailsOnlyThatArtifact() throws Exception {
        Path projectRoot = tempDir.resolve("vlm-project");
        Path markdownDir = tempDir.resolve("vlm-project/data/markdown/vlm-docs");
        Files.createDirectories(markdownDir);
        Path image = tempDir.resolve("remote.png");
        Files.writeString(image, "resolution only");

        ObjectNode request = (ObjectNode) mapper.readTree("""
                {
                  "pipelines": [{
                    "pipelineId": "remote-vlm",
                    "pipelineType": "VLM",
                    "processor": {"type": "CHAT_MODEL"}
                  }],
                  "documents": [{"path": "%s", "pipelineId": "remote-vlm"}]
                }
                """.formatted(image.toString().replace("\\", "\\\\")));
        LocalCrawlCapabilities.ResolvedPipeline pipeline =
                LocalCrawlCapabilities.resolve(request, null, tempDir, image);
        assertTrue(LocalCrawlCapabilities.usesModelPipeline(pipeline));

        ProjectCrawlCommand.LocalCrawlDocument document = new ProjectCrawlCommand.LocalCrawlDocument(
                "remote-doc", image.toString(), "remote.png", Files.size(image),
                Instant.now().toString(), "image/png", null, null, "PENDING", null,
                null, null, null, null, 0, 0, List.of(), Map.of());
        ProjectCrawlCommand.ModelPipelineExecutor throwsLinkageError = (root, file, resolvedPipeline, loadedText) -> {
            throw new LinkageError("simulated missing native class");
        };

        ProjectCrawlCommand.LocalMarkdownArtifact artifact = ProjectCrawlCommand.writeLocalCrawlMarkdown(
                projectRoot, markdownDir, document, image, null, "vlm-project", pipeline, throwsLinkageError, request);

        assertEquals("FAILED", artifact.status());
        assertEquals("simulated missing native class", artifact.message());
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
