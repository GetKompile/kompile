/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import ai.kompile.project.KompileProjectCrawlProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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
        assertTrue(catalog.path("modelProcessing").path("semanticServing").asText()
                .contains("LOCAL_MODEL/serving"));
        assertEquals("children stop before the MCP command returns",
                catalog.path("modelProcessing").path("lifecycle").asText());
    }

    @Test
    void discoveryDoesNotAdvertiseModelTemplatesWithoutAWorker() {
        ObjectNode catalog = LocalCrawlCapabilities.catalog(mapper, "in-process-native", false);

        assertFalse(template(catalog, LocalCrawlCapabilities.VLM_PIPELINE).path("available").asBoolean());
        assertFalse(template(catalog, LocalCrawlCapabilities.OCR_PIPELINE).path("available").asBoolean());
        assertFalse(catalog.path("modelProcessing").path("builtinDocumentProcessor").asBoolean());
        assertTrue(catalog.path("modelProcessing").path("callerDefinedUnifiedPipelines").asBoolean());
        assertTrue(catalog.path("pipelineRegistry").path("arbitraryPipelineTypes").asBoolean());
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
        ObjectNode futurePipeline = (ObjectNode) mapper.readTree("""
                {"pipelines":[{"pipelineId":"future","pipelineType":"AUDIO_TRANSCRIPTION"}]}
                """);
        ObjectNode invalidPipeline = (ObjectNode) mapper.readTree("""
                {"pipelines":[{"pipelineId":"invalid","pipelineType":"not a portable type"}]}
                """);
        ObjectNode strictStep = (ObjectNode) mapper.readTree("""
                {"steps":["ENRICHMENT"],"strictSteps":true}
                """);

        assertTrue(LocalCrawlCapabilities.validationError(unknownLoader).contains("unknown"));
        assertNull(LocalCrawlCapabilities.validationError(allPipelines));
        assertNull(LocalCrawlCapabilities.validationError(futurePipeline));
        assertTrue(LocalCrawlCapabilities.validationError(invalidPipeline).contains("portable identifier"));
        assertNull(LocalCrawlCapabilities.validationError(strictStep));
        assertTrue(LocalCrawlCapabilities.supportedSteps().contains("ENRICHMENT"));
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
    void requestScopedRuntimeAndPipelineOptionsCreateTheFolderPipeline() throws Exception {
        Path pdf = tempDir.resolve("created.pdf");
        Files.writeString(pdf, "resolution only");
        ObjectNode request = (ObjectNode) mapper.readTree("""
                {
                  "runtimeConfig": {
                    "documentModelExecutable": "workers/vlm",
                    "documentModelExecutableMode": "UNIFIED"
                  },
                  "modelRuntime": {
                    "autoBootstrap": true,
                    "source": "huggingface",
                    "repository": "ds4sd/SmolDocling-256M-preview",
                    "format": "vlm",
                    "type": "vlm_pipeline"
                  },
                  "documents": [{"path": "%s", "pipelineId": "created-vlm"}],
                  "pipelines": [{
                    "pipelineId": "created-vlm",
                    "pipelineType": "VLM",
                    "loaderName": "pdf",
                    "chunkerName": "sentence",
                    "options": {
                      "modelId": "folder-model",
                      "maxPages": 7,
                      "pdfRenderDpi": 180,
                      "temperature": 0.2
                    }
                  }]
                }
                """.formatted(pdf.toString().replace("\\", "\\\\")));

        assertNull(LocalCrawlCapabilities.validationError(request));
        LocalCrawlCapabilities.ResolvedPipeline resolved =
                LocalCrawlCapabilities.resolve(request, null, tempDir, pdf);

        assertEquals("folder-model", resolved.chunkerOptions().get("modelId"));
        assertEquals(7, resolved.chunkerOptions().get("maxPages"));
        assertEquals(180, resolved.chunkerOptions().get("pdfRenderDpi"));
        assertEquals("workers/vlm", resolved.processor().get("documentModelExecutable"));
        assertEquals("UNIFIED", resolved.processor().get("documentModelExecutableMode"));
        Map<?, ?> modelRuntime = (Map<?, ?>) resolved.processor().get("modelRuntime");
        assertEquals(true, modelRuntime.get("autoBootstrap"));
        assertEquals("ds4sd/SmolDocling-256M-preview", modelRuntime.get("repository"));
        assertEquals("vlm_pipeline", modelRuntime.get("type"));
    }

    @Test
    void requestScopedModelsAreBoundToPipelineRoles() throws Exception {
        Path document = tempDir.resolve("bound-model.txt");
        Files.writeString(document, "resolution only");
        ObjectNode request = (ObjectNode) mapper.readTree("""
                {
                  "pipelineRegistry": {
                    "models": [
                      {"id":"generator-config","modelId":"generator-model","role":"generator",
                       "source":"catalog","runtime":{"autoBootstrap":false}},
                      {"id":"embedding-config","modelId":"embedding-model","role":"embedding",
                       "source":"catalog","runtime":{"autoBootstrap":false}}
                    ],
                    "defaults": [{
                      "pipelineId":"model-bound-default",
                      "pipelineType":"CUSTOM",
                      "loaderName":"text",
                      "chunkerName":"no-op",
                      "modelBindings": {
                        "generator":"generator-config",
                        "embedding":"embedding-config"
                      }
                    }]
                  },
                  "pipelines": [{
                    "pipelineId":"model-bound",
                    "registeredPipelineId":"model-bound-default"
                  }],
                  "documents": [{"path":"%s","pipelineId":"model-bound"}]
                }
                """.formatted(document.toString().replace("\\", "\\\\")));

        assertNull(LocalCrawlCapabilities.validationError(request));
        LocalCrawlCapabilities.ResolvedPipeline resolved =
                LocalCrawlCapabilities.resolve(request, null, tempDir, document);

        Map<?, ?> bindings = (Map<?, ?>) resolved.chunkerOptions().get("modelBindings");
        assertEquals("generator-config", bindings.get("generator"));
        assertEquals("embedding-config", bindings.get("embedding"));
        Map<?, ?> definitions = (Map<?, ?>) resolved.processor().get("registeredModelDefinitions");
        assertTrue(definitions.containsKey("generator-config"));
        assertTrue(definitions.containsKey("embedding-config"));
    }

    @Test
    void malformedModelRegistryAndBindingsFailValidation() throws Exception {
        JsonNode duplicateModels = mapper.readTree("""
                {"pipelineRegistry":{"models":[{"id":"same"},{"id":"same"}]}}
                """);
        assertTrue(LocalCrawlCapabilities.validationError(duplicateModels)
                .contains("Duplicate registered model id"));

        JsonNode invalidBinding = mapper.readTree("""
                {"pipelines":[{"pipelineId":"bad","pipelineType":"CUSTOM",
                  "modelBindings":{"generator":""}}]}
                """);
        assertTrue(LocalCrawlCapabilities.validationError(invalidBinding)
                .contains("must reference a non-empty model id"));
    }

    @Test
    void arbitraryPipelinesInheritRegisteredDefaultsAndExecutors() throws Exception {
        Path audio = tempDir.resolve("meeting.txt");
        Files.writeString(audio, "audio placeholder");
        ObjectNode request = (ObjectNode) mapper.readTree("""
                {
                  "pipelineRegistry": {
                    "executors": [{
                      "executorId": "transcriber",
                      "type": "KOMPILE_SUBPROCESS",
                      "componentId": "kompile-audio",
                      "subprocessMode": "audio-transcription"
                    }],
                    "defaults": [{
                      "pipelineId": "audio-default",
                      "pipelineType": "AUDIO_TRANSCRIPTION",
                      "loaderName": "text",
                      "chunkerName": "no-op",
                      "executorId": "transcriber"
                    }]
                  },
                  "pipelines": [{
                    "pipelineId": "meeting-audio",
                    "registeredPipelineId": "audio-default"
                  }],
                  "documents": [{"path": "%s", "pipelineId": "meeting-audio"}]
                }
                """.formatted(audio.toString().replace("\\", "\\\\")));

        assertNull(LocalCrawlCapabilities.validationError(request));
        LocalCrawlCapabilities.ResolvedPipeline resolved =
                LocalCrawlCapabilities.resolve(request, null, tempDir, audio);

        assertEquals("AUDIO_TRANSCRIPTION", resolved.pipelineType());
        assertEquals("transcriber", resolved.processor().get("executorId"));
        assertEquals("audio-transcription", resolved.processor().get("subprocessMode"));
        assertTrue(LocalCrawlCapabilities.usesProcessingSubprocess(resolved));
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

    private JsonNode template(ObjectNode catalog, String pipelineId) {
        for (JsonNode template : catalog.path("pipelineTemplates")) {
            if (pipelineId.equals(template.path("pipelineId").asText())) return template;
        }
        throw new AssertionError("Missing pipeline template " + pipelineId);
    }
}
