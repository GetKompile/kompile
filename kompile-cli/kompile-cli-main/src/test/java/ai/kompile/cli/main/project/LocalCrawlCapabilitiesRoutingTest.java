/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers two {@link LocalCrawlCapabilities} contracts not exercised by {@link LocalCrawlCapabilitiesTest}:
 * {@code routeRules[].sourceTypes} matching a document's effective source type rather than a hardcoded
 * {@code "FILE"}, and {@code automaticLoader}/{@code loaderSupports} correctly separating images, known
 * binary formats, and the new mail/office/rtf loaders from the generic text-family loaders.
 */
class LocalCrawlCapabilitiesRoutingTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void routingMatchesTheDocumentsEffectiveSourceTypeInsteadOfAHardcodedFile() throws Exception {
        Path plainFile = tempDir.resolve("plain.txt");
        Path urlFile = tempDir.resolve("crawled.txt");
        Path slackFile = tempDir.resolve("slack-export.txt");
        Files.writeString(plainFile, "an ordinary local file");
        Files.writeString(urlFile, "captured from a web crawl");
        Files.writeString(slackFile, "captured from a slack export");

        ObjectNode request = (ObjectNode) mapper.readTree("""
                {
                  "pipelines": [
                    {"pipelineId": "route-slack", "pipelineType": "STANDARD_TEXT", "loaderName": "text"},
                    {"pipelineId": "route-url", "pipelineType": "STANDARD_TEXT", "loaderName": "text"},
                    {"pipelineId": "route-file", "pipelineType": "STANDARD_TEXT", "loaderName": "text"}
                  ],
                  "routeRules": [
                    {"pipelineId": "route-slack", "sourceTypes": ["SLACK"], "priority": 10},
                    {"pipelineId": "route-url", "sourceTypes": ["URL"], "priority": 20},
                    {"pipelineId": "route-file", "sourceTypes": ["FILE"], "priority": 30}
                  ],
                  "documents": [
                    {"path": "%s"},
                    {"path": "%s", "properties": {"sourceUrl": "https://example.com/doc"}},
                    {"path": "%s", "properties": {"externalSourceType": "SLACK",
                                                   "sourceUrl": "https://example.com/doc"}}
                  ]
                }
                """.formatted(plainFile.toString().replace("\\", "\\\\"),
                urlFile.toString().replace("\\", "\\\\"),
                slackFile.toString().replace("\\", "\\\\")));
        assertNull(LocalCrawlCapabilities.validationError(request));

        LocalCrawlCapabilities.ResolvedPipeline plain =
                LocalCrawlCapabilities.resolve(request, null, tempDir, plainFile);
        LocalCrawlCapabilities.ResolvedPipeline url =
                LocalCrawlCapabilities.resolve(request, null, tempDir, urlFile);
        LocalCrawlCapabilities.ResolvedPipeline slack =
                LocalCrawlCapabilities.resolve(request, null, tempDir, slackFile);

        // A document with no properties at all is an ordinary filesystem document ("FILE").
        assertEquals("route-file", plain.pipelineId());
        // properties.sourceUrl with no externalSourceType routes as "URL" - a hardcoded "FILE"
        // match (the regression this guards) would have sent this document to route-file instead.
        assertEquals("route-url", url.pipelineId());
        // properties.externalSourceType wins even when properties.sourceUrl is ALSO present.
        assertEquals("route-slack", slack.pipelineId());
    }

    @Test
    void automaticLoaderMapsImagesToTheImageSentinelSoAModelPipelineIsNeverBlocked() throws Exception {
        Path image = tempDir.resolve("photo.png");
        Files.writeString(image, "not a real png, resolution only");

        LocalCrawlCapabilities.ResolvedPipeline resolved =
                LocalCrawlCapabilities.resolve(null, null, tempDir, image);

        assertEquals("image", resolved.loaderName());
        assertTrue(LocalCrawlCapabilities.loaderSupports("image", image),
                "the image sentinel must defer to ProjectCrawlCommand's model-aware gate, not reject here");
    }

    @Test
    void automaticLoaderMapsKnownBinaryFormatsToTheUnsupportedSentinelAndLoaderSupportsRejectsThem()
            throws Exception {
        Path archive = tempDir.resolve("bundle.zip");
        Path executable = tempDir.resolve("tool.exe");
        Path audio = tempDir.resolve("clip.mp3");
        for (Path binary : List.of(archive, executable, audio)) {
            Files.writeString(binary, "not really binary, resolution only");

            LocalCrawlCapabilities.ResolvedPipeline resolved =
                    LocalCrawlCapabilities.resolve(null, null, tempDir, binary);

            assertEquals("unsupported-binary", resolved.loaderName(), binary.toString());
            assertFalse(LocalCrawlCapabilities.loaderSupports("unsupported-binary", binary), binary.toString());
            assertFalse(LocalCrawlCapabilities.loaderSupports("text", binary), binary.toString());
        }
    }

    @Test
    void loaderSupportsRejectsDedicatedLoaderAndBinaryExtensionsForTextFamilyLoaders() {
        // loaderSupports is a pure extension-matching function - it never touches the filesystem,
        // so none of these paths need to actually exist.
        Path markdown = tempDir.resolve("notes.md");
        Path code = tempDir.resolve("Answer.java");
        Path table = tempDir.resolve("data.csv");
        Path mail = tempDir.resolve("message.eml");
        Path office = tempDir.resolve("report.docx");
        Path rtf = tempDir.resolve("memo.rtf");
        Path pdf = tempDir.resolve("scan.pdf");

        // Each dedicated format still supports its own loader...
        assertTrue(LocalCrawlCapabilities.loaderSupports("markdown", markdown));
        assertTrue(LocalCrawlCapabilities.loaderSupports("code", code));
        assertTrue(LocalCrawlCapabilities.loaderSupports("table", table));
        assertTrue(LocalCrawlCapabilities.loaderSupports("mail", mail));
        assertTrue(LocalCrawlCapabilities.loaderSupports("office", office));
        assertTrue(LocalCrawlCapabilities.loaderSupports("rtf", rtf));

        // ...but never the generic "text" loader: reading a docx/eml/rtf/pdf's raw bytes as UTF-8
        // either throws deep inside chunking or silently indexes garbage.
        assertFalse(LocalCrawlCapabilities.loaderSupports("text", mail));
        assertFalse(LocalCrawlCapabilities.loaderSupports("text", office));
        assertFalse(LocalCrawlCapabilities.loaderSupports("text", rtf));
        assertFalse(LocalCrawlCapabilities.loaderSupports("text", pdf));
        assertTrue(LocalCrawlCapabilities.loaderSupports("text", tempDir.resolve("plain.txt")));
    }

    @Test
    void externalMaterializedAttachmentsResolveThroughTheirRealExtensionWhileTheMessageBodyDoesNot()
            throws Exception {
        Path connectorDir = tempDir.resolve("connector");
        Path attachmentDir = connectorDir.resolve("attachments").resolve("msg-1");
        Files.createDirectories(attachmentDir);
        Path messageFile = connectorDir.resolve("msg-1.md");
        Path pdfAttachment = attachmentDir.resolve("report.pdf");
        Path zipAttachment = attachmentDir.resolve("payload.zip");
        Files.writeString(messageFile, "# Message\n\nbody");
        Files.writeString(pdfAttachment, "not a real pdf, resolution only");
        Files.writeString(zipAttachment, "not a real zip, resolution only");

        // One document entry spans the whole connector directory, exactly as a real materialized
        // connector crawl registers it (see LocalProjectCrawlBackend).
        ObjectNode request = (ObjectNode) mapper.readTree("""
                {"documents": [{"path": "%s", "loaderName": "external-materialized"}]}
                """.formatted(connectorDir.toString().replace("\\", "\\\\")));
        assertNull(LocalCrawlCapabilities.validationError(request));

        LocalCrawlCapabilities.ResolvedPipeline message =
                LocalCrawlCapabilities.resolve(request, null, tempDir, messageFile);
        LocalCrawlCapabilities.ResolvedPipeline pdf =
                LocalCrawlCapabilities.resolve(request, null, tempDir, pdfAttachment);

        // The message body is the literal Markdown a connector wrote: still "external-materialized"
        // so LocalDocumentLoaderRegistry.load reads its two-header format.
        assertEquals("external-materialized", message.loaderName());
        assertTrue(LocalCrawlCapabilities.loaderSupports("external-materialized", messageFile));
        // A non-.md attachment nested under attachments/<messageKey>/ is NOT that Markdown format;
        // it must be routed by its own real extension instead of failing as unsupported Markdown.
        assertEquals("pdf", pdf.loaderName());
        assertTrue(LocalCrawlCapabilities.loaderSupports("external-materialized", pdfAttachment));
        // An attachment whose own extension has no text-family loader is still correctly rejected,
        // rather than silently accepted just because it lives under an external-materialized tree.
        assertFalse(LocalCrawlCapabilities.loaderSupports("external-materialized", zipAttachment));
    }

    @Test
    void scannedPdfPipelineIdPrefersTheRegistryDefaultOverAnyCustomVlmPipelineOrTheBuiltinFallback()
            throws Exception {
        // Case 1: "vlm-ocr-pdf" is registered under pipelineRegistry.defaults, exactly the shape
        // LocalProjectCrawlBackend.registerProjectPipelines emits for project init's VLM preset -
        // never under request.pipelines[]. A second, differently-named custom VLM pipeline is also
        // present in pipelines[] to prove the registry default still wins.
        ObjectNode registryDefaultRequest = (ObjectNode) mapper.readTree("""
                {
                  "pipelineRegistry": {
                    "defaults": [
                      {"pipelineId": "vlm-ocr-pdf", "pipelineType": "VLM"}
                    ]
                  },
                  "pipelines": [
                    {"pipelineId": "custom-vlm", "pipelineType": "VLM", "loaderName": "pdf"}
                  ]
                }
                """);
        assertEquals("vlm-ocr-pdf", LocalCrawlCapabilities.scannedPdfPipelineId(registryDefaultRequest));

        // Case 2: only a custom VLM pipeline is defined, under request.pipelines[] - no registry
        // default and no "vlm-ocr-pdf" id anywhere - so that pipeline's own id is used.
        ObjectNode customOnlyRequest = (ObjectNode) mapper.readTree("""
                {
                  "pipelines": [
                    {"pipelineId": "custom-vlm", "pipelineType": "VLM", "loaderName": "pdf"}
                  ]
                }
                """);
        assertEquals("custom-vlm", LocalCrawlCapabilities.scannedPdfPipelineId(customOnlyRequest));

        // Case 3: nothing defined at all falls back to the built-in VLM_PIPELINE template.
        assertEquals(LocalCrawlCapabilities.VLM_PIPELINE, LocalCrawlCapabilities.scannedPdfPipelineId(null));
    }
}
