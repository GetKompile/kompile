/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import ai.kompile.project.KompileProjectCrawlProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalCrawlCapabilitiesTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void discoveryComesFromExecutableLoadersChunkersAndTemplates() {
        ObjectNode catalog = LocalCrawlCapabilities.catalog(mapper, "subprocess");

        assertTrue(catalog.path("loaders").toString().contains("\"pdf\""));
        assertTrue(catalog.path("loaders").toString().contains("\"code\""));
        assertTrue(catalog.path("chunkers").toString().contains("recursive-character"));
        assertTrue(catalog.path("chunkers").toString().contains("sentence"));
        assertTrue(catalog.path("pipelineTemplates").toString().contains("standard-text"));
        assertTrue(catalog.path("pipelineTemplates").toString().contains("vlm-document"));
        assertTrue(catalog.path("pipelineTemplates").toString().contains("ocr-document"));
        assertTrue(catalog.path("pipelineTemplates").toString().contains("table-aware"));
        assertTrue(catalog.path("pipelineTemplates").toString().contains("keyword-only"));
        assertEquals("subprocess", catalog.path("executionMode").asText());
    }

    @Test
    void documentOverridesRoutesAndDefaultsResolveInPrecedenceOrder() throws Exception {
        Path markdown = tempDir.resolve("notes.md");
        Path code = tempDir.resolve("Answer.java");
        Files.writeString(markdown, "# Notes\n\nalpha beta gamma delta epsilon zeta");
        Files.writeString(code, "class Answer { String value() { return \"route-me\"; } }");

        ObjectNode request = (ObjectNode) mapper.readTree("""
                {
                  "documents": [
                    {"path": "%s"},
                    {"path": "%s", "pipelineId": "notes", "chunkerName": "no-op"}
                  ],
                  "pipelines": [
                    {"pipelineId": "notes", "pipelineType": "STANDARD_TEXT",
                     "loaderName": "markdown", "chunkerName": "sentence"},
                    {"pipelineId": "java", "pipelineType": "CODE",
                     "loaderName": "code", "chunkerName": "recursive-character",
                     "chunkSize": 40, "chunkOverlap": 5}
                  ],
                  "routeRules": [
                    {"pipelineId": "java", "fileExtensions": [".java"], "priority": 10}
                  ],
                  "defaultPipelineId": "notes"
                }
                """.formatted(tempDir.toString().replace("\\", "\\\\"),
                markdown.toString().replace("\\", "\\\\")));
        assertNull(LocalCrawlCapabilities.validationError(request));

        KompileProjectCrawlProfile profile = new KompileProjectCrawlProfile();
        profile.setLoader("auto");
        profile.setChunker("recursive-character");
        LocalCrawlCapabilities.ResolvedPipeline markdownPipeline =
                LocalCrawlCapabilities.resolve(request, profile, tempDir, markdown);
        LocalCrawlCapabilities.ResolvedPipeline codePipeline =
                LocalCrawlCapabilities.resolve(request, profile, tempDir, code);

        assertEquals("notes", markdownPipeline.pipelineId());
        assertEquals("markdown", markdownPipeline.loaderName());
        assertEquals("no-op", markdownPipeline.chunkerName());
        assertEquals("java", codePipeline.pipelineId());
        assertEquals("code", codePipeline.loaderName());
        assertEquals(40, codePipeline.chunkSize());
    }

    @Test
    void validationAcceptsEveryPipelineKindAndRejectsUnknownComponents() throws Exception {
        ObjectNode unknownLoader = (ObjectNode) mapper.readTree("""
                {"documents":[{"path":"notes.md","loaderName":"imaginary-loader"}]}
                """);
        ObjectNode allPipelines = (ObjectNode) mapper.readTree("""
                {"pipelines":[
                  {"pipelineId":"vision","pipelineType":"VLM","options":{"vlmModel":"model"}},
                  {"pipelineId":"ocr","pipelineType":"OCR"},
                  {"pipelineId":"table","pipelineType":"TABLE_AWARE"},
                  {"pipelineId":"keywords","pipelineType":"KEYWORD_ONLY"},
                  {"pipelineId":"custom","pipelineType":"CUSTOM"}
                ]}
                """);
        ObjectNode unknownPipeline = (ObjectNode) mapper.readTree("""
                {"pipelines":[{"pipelineId":"future","pipelineType":"IMAGINARY"}]}
                """);
        ObjectNode strictStep = (ObjectNode) mapper.readTree("""
                {"steps":["ENRICHMENT"],"strictSteps":true}
                """);

        assertTrue(LocalCrawlCapabilities.validationError(unknownLoader).contains("unknown"));
        assertNull(LocalCrawlCapabilities.validationError(allPipelines));
        assertTrue(LocalCrawlCapabilities.validationError(unknownPipeline).contains("Unknown pipeline type"));
        assertTrue(LocalCrawlCapabilities.validationError(strictStep).contains("unavailable"));
    }

    @Test
    void modelAndKeywordPipelineExecutionModesAreResolvedLocally() throws Exception {
        Path pdf = tempDir.resolve("scan.pdf");
        Files.writeString(pdf, "not executed in this resolution test");
        ObjectNode request = (ObjectNode) mapper.readTree("""
                {"documents":[{"path":"%s","pipelineId":"vision"}],
                 "pipelines":[
                   {"pipelineId":"vision","pipelineType":"VLM","options":{"vlmModel":"smol"}},
                   {"pipelineId":"keywords","pipelineType":"KEYWORD_ONLY"}
                 ]}
                """.formatted(pdf.toString().replace("\\", "\\\\")));

        LocalCrawlCapabilities.ResolvedPipeline vision =
                LocalCrawlCapabilities.resolve(request, null, tempDir, pdf);
        assertTrue(LocalCrawlCapabilities.usesProcessingSubprocess(vision));
        assertEquals("smol", vision.chunkerOptions().get("vlmModel"));

        ((ObjectNode) request.withArray("documents").get(0)).put("pipelineId", "keywords");
        LocalCrawlCapabilities.ResolvedPipeline keywords =
                LocalCrawlCapabilities.resolve(request, null, tempDir, pdf);
        assertFalse(LocalCrawlCapabilities.usesProcessingSubprocess(keywords));
    }

    @Test
    void registeredChunkersActuallyChangeExecution() {
        String text = "First sentence. Second sentence. Third sentence.";
        LocalCrawlCapabilities.ResolvedPipeline noOp = new LocalCrawlCapabilities.ResolvedPipeline(
                "whole", "CUSTOM", "text", "no-op", 0, 0, java.util.Map.of());
        LocalCrawlCapabilities.ResolvedPipeline recursive = new LocalCrawlCapabilities.ResolvedPipeline(
                "small", "CUSTOM", "text", "recursive-character", 18, 2, java.util.Map.of());

        assertEquals(1, LocalCrawlCapabilities.chunk("doc", text, noOp).size());
        assertTrue(LocalCrawlCapabilities.chunk("doc", text, recursive).size() > 1);
    }

    @Test
    void directProjectProfileRemainsTheDefaultWithoutAnMcpPipelineSelection() throws Exception {
        Path document = tempDir.resolve("profile.md");
        Files.writeString(document, "profile-selected loader and chunker");
        KompileProjectCrawlProfile profile = new KompileProjectCrawlProfile();
        profile.setLoader("text");
        profile.setChunker("no-op");

        LocalCrawlCapabilities.ResolvedPipeline resolved =
                LocalCrawlCapabilities.resolve(null, profile, document, document);

        assertEquals("text", resolved.loaderName());
        assertEquals("no-op", resolved.chunkerName());
    }
}
