/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.e2e;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolRegistryFactory;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tools.grounding.CrawlDocumentsTool;
import ai.kompile.cli.main.chat.tools.grounding.LocalProjectCrawlBackend;
import ai.kompile.modelmanager.KompileModelManager;
import ai.kompile.modelmanager.registry.RegistryService;
import ai.kompile.ocr.OcrPipelineConfig;
import ai.kompile.ocr.VlmOutputFormat;
import ai.kompile.ocr.document.ParsedDocument;
import ai.kompile.ocr.models.pipeline.VlmDocumentPipeline;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real local VLM integration gates for the JVM runtime and MCP tool boundary.
 *
 * <p>The PDF is an external, property-provided test fixture. It is read in place, never copied into
 * the repository, and never sent to an HTTP service. The JVM gate loads the production VLM pipeline
 * directly, while the MCP gate exercises discovery, model-runtime bootstrap, and
 * {@code crawl_documents} through the installed document-model worker.</p>
 */
@Tag("integration")
class LocalMcpVlmPdfIT {
    static final String PDF_PROPERTY = "kompile.vlm.pdf.it.path";
    static final String PROJECT_ROOT_PROPERTY = "kompile.vlm.pdf.it.projectRoot";
    static final String WORKER_PROPERTY = "kompile.vlm.pdf.it.worker";
    static final String MODEL_PROPERTY = "kompile.vlm.pdf.it.modelId";
    static final String MODEL_DIRECTORY_PROPERTY = "kompile.vlm.pdf.it.modelDirectory";
    static final String MAX_PAGES_PROPERTY = "kompile.vlm.pdf.it.maxPages";
    static final String PAGE_RANGE_PROPERTY = "kompile.vlm.pdf.it.pageRange";
    static final String MAX_TOKENS_PROPERTY = "kompile.vlm.pdf.it.maxNewTokens";
    static final String PDF_DPI_PROPERTY = "kompile.vlm.pdf.it.pdfRenderDpi";
    static final String TIMEOUT_PROPERTY = "kompile.vlm.pdf.it.timeoutMinutes";

    private static final String KNOWLEDGE_BASE = "real-image-pdf-vlm-it";
    private static final String PIPELINE_ID = "real-image-pdf-vlm";
    private static final int MIN_FIXTURE_SOURCE_WORDS = 100;

    @TempDir
    Path projectRoot;

    private String previousLocalCrawlExecution;

    @AfterEach
    void restoreExecutionMode() {
        if (previousLocalCrawlExecution == null) {
            System.clearProperty("kompile.local.crawl.execution");
        } else {
            System.setProperty("kompile.local.crawl.execution", previousLocalCrawlExecution);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void jvmVlmRuntimeLoadsAndProcessesPropertyProvidedImagePdf() throws Exception {
        Path pdf = requiredExternalPdf();
        String modelId = System.getProperty(MODEL_PROPERTY, "smoldocling-256m");
        Path modelDirectory = requiredDirectory(MODEL_DIRECTORY_PROPERTY);
        int maxPages = positiveInt(MAX_PAGES_PROPERTY, 1);
        String pageRange = resolveFixturePageRange(pdf, optionalProperty(PAGE_RANGE_PROPERTY));
        int maxNewTokens = positiveInt(MAX_TOKENS_PROPERTY, 768);
        int pdfRenderDpi = positiveInt(PDF_DPI_PROPERTY, 144);
        assertImageBackedFixture(pdf, maxPages, pageRange);

        previousLocalCrawlExecution = System.getProperty("kompile.local.crawl.execution");
        System.setProperty("kompile.local.crawl.execution", "inline");

        VlmDocumentPipeline pipeline = new VlmDocumentPipeline(
                new KompileModelManager(), new RegistryService(), null);
        AtomicReference<String> generatedText = new AtomicReference<>();
        try {
            pipeline.loadModelsFromDirectory(modelId, modelDirectory.toFile());

            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            LocalProjectCrawlBackend localBackend = new LocalProjectCrawlBackend(
                    mapper,
                    (crawlRoot, sourceFile, resolvedPipeline, loadedText) -> {
                        assertEquals(PIPELINE_ID, resolvedPipeline.pipelineId());
                        assertEquals("VLM", resolvedPipeline.pipelineType());
                        assertEquals("pdf", resolvedPipeline.loaderName());
                        assertEquals("sentence", resolvedPipeline.chunkerName());
                        assertEquals(modelId,
                                String.valueOf(resolvedPipeline.chunkerOptions().get("modelId")));
                        assertEquals(maxPages,
                                ((Number) resolvedPipeline.chunkerOptions().get("maxPages")).intValue());
                        assertEquals(pageRange, resolvedPipeline.chunkerOptions().get("pageRange"));
                        assertEquals(maxNewTokens,
                                ((Number) resolvedPipeline.chunkerOptions().get("maxNewTokens")).intValue());
                        assertEquals(pdfRenderDpi,
                                ((Number) resolvedPipeline.chunkerOptions().get("pdfRenderDpi")).intValue());

                        OcrPipelineConfig config = OcrPipelineConfig.builder()
                                .useVlm(true)
                                .vlmModelId(modelId)
                                .vlmOutputFormat(VlmOutputFormat.MARKDOWN)
                                .maxNewTokens(maxNewTokens)
                                .temperature(0.0d)
                                .topP(1.0d)
                                .beamSize(1)
                                .doSample(false)
                                .pdfRenderDpi(pdfRenderDpi)
                                .pageBatchSize(1)
                                .maxPages(maxPages)
                                .pageRange(pageRange)
                                .sourceId(sourceFile.toString())
                                .includeAuditTrail(true)
                                .build();

                        List<ParsedDocument> results =
                                pipeline.processPdf(sourceFile.toFile(), config, null);
                        assertFalse(results.isEmpty(), "The crawl VLM pipeline returned no pages");
                        assertTrue(results.stream().allMatch(ParsedDocument::isSuccess),
                                () -> "The crawl VLM pipeline returned failed pages: "
                                        + results.stream()
                                        .filter(result -> !result.isSuccess())
                                        .map(ParsedDocument::getErrorMessage)
                                        .toList());
                        String generated = results.stream()
                                .map(ParsedDocument::getText)
                                .filter(text -> text != null && !text.isBlank())
                                .reduce("", (left, right) ->
                                        left.isBlank() ? right : left + "\n\n" + right);
                        String rawGenerated = results.stream()
                                .map(ParsedDocument::getMetadata)
                                .filter(metadata -> metadata != null)
                                .map(metadata -> metadata.get("rawOutput"))
                                .filter(value -> value != null)
                                .map(String::valueOf)
                                .reduce("", (left, right) ->
                                        left.isBlank() ? right : left + "\n\n" + right);
                        System.out.println("VLM raw output: words=" + wordTokens(rawGenerated).size()
                                + ", preview=\"" + preview(rawGenerated, 240) + "\"");
                        assertRealWords(generated);
                        generatedText.set(generated);
                        return generated;
                    });

            PermissionService permissions = new PermissionService();
            ToolRegistry registry = ToolRegistryFactory.create(
                    mapper,
                    null,
                    new AgentRegistry(),
                    permissions,
                    null,
                    null,
                    null,
                    null);
            registry.register(new CrawlDocumentsTool(null, mapper, localBackend));
            permissions.setUserOverride(
                    "crawl_documents", PermissionService.PermissionLevel.ALLOW);
            permissions.setUserOverride(
                    "external_directory", PermissionService.PermissionLevel.ALLOW);
            ToolContext context = new ToolContext(
                    "real-image-pdf-vlm-jvm-it",
                    AgentConfig.builder("real-image-pdf-vlm-jvm-it")
                            .enabledTools(Set.of("crawl_documents"))
                            .build(),
                    permissions,
                    projectRoot,
                    registry);

            ObjectNode request = vlmCrawlRequest(
                    mapper, pdf, modelId, maxPages, pageRange, maxNewTokens, pdfRenderDpi,
                    positiveInt(TIMEOUT_PROPERTY, 30));
            ToolResult crawl = registry.get("crawl_documents").execute(request, context);

            assertFalse(crawl.isError(), () -> "crawl_documents failed: " + crawl.getOutput());
            assertEquals("COMPLETED", crawl.getMetadata().get("status"), crawl.getOutput());
            assertEquals(0, number(crawl, "failedDocumentCount"), crawl.getOutput());
            assertEquals(1, number(crawl, "documentCount"), crawl.getOutput());
            assertTrue(number(crawl, "chunkCount") > 0,
                    "The VLM output did not reach production chunking: " + crawl.getOutput());

            String generated = generatedText.get();
            assertTrue(generated != null, "crawl_documents never invoked the VLM pipeline executor");
            Path crawlDirectory = projectRoot.resolve("data/crawls").resolve(KNOWLEDGE_BASE);
            assertPersistedVlmWords(mapper, projectRoot, crawlDirectory, generated);
            System.out.println("crawl_documents consumed real VLM output: words="
                    + wordTokens(generated).size() + ", preview=\""
                    + preview(generated, 240) + "\"");
        } finally {
            pipeline.unloadModels();
        }
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.MINUTES)
    void crawlDocumentsRunsRealLocalVlmAgainstPropertyProvidedImagePdf() throws Exception {
        Path pdf = requiredExternalPdf();
        Path testProjectRoot = configuredProjectRoot();
        Path worker = requiredExecutable(
                WORKER_PROPERTY,
                Path.of(System.getProperty("user.home"), ".kompile", "bin", "kompile-vlm-test"));
        int maxPages = positiveInt(MAX_PAGES_PROPERTY, 1);
        String pageRange = resolveFixturePageRange(pdf, optionalProperty(PAGE_RANGE_PROPERTY));
        int timeoutMinutes = positiveInt(TIMEOUT_PROPERTY, 30);
        String modelId = System.getProperty(MODEL_PROPERTY, "smoldocling-256m");
        assertImageBackedFixture(pdf, maxPages, pageRange);

        previousLocalCrawlExecution = System.getProperty("kompile.local.crawl.execution");
        System.setProperty("kompile.local.crawl.execution", "inline");

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PermissionService permissions = new PermissionService();
        ToolRegistry registry = ToolRegistryFactory.create(
                mapper,
                null,
                new AgentRegistry(),
                permissions,
                null,
                null,
                null,
                null);
        permissions.setUserOverride("crawl_documents", PermissionService.PermissionLevel.ALLOW);
        permissions.setUserOverride("crawl_discover", PermissionService.PermissionLevel.ALLOW);
        permissions.setUserOverride("model_runtime", PermissionService.PermissionLevel.ALLOW);
        permissions.setUserOverride("external_directory", PermissionService.PermissionLevel.ALLOW);
        ToolContext context = new ToolContext(
                "real-image-pdf-vlm-it",
                AgentConfig.builder("real-image-pdf-vlm-it")
                        .enabledTools(Set.of("crawl_discover", "model_runtime", "crawl_documents"))
                        .build(),
                permissions,
                testProjectRoot,
                registry);

        CliTool crawlDiscover = registry.get("crawl_discover");
        CliTool modelRuntime = registry.get("model_runtime");
        CliTool crawlDocuments = registry.get("crawl_documents");
        assertTrue(crawlDiscover != null, "Production MCP registry has no crawl_discover tool");
        assertTrue(modelRuntime != null, "Production MCP registry has no model_runtime tool");
        assertTrue(crawlDocuments != null, "Production MCP registry has no crawl_documents tool");

        ToolResult discovery = crawlDiscover.execute(
                mapper.createObjectNode().put("section", "all"), context);
        assertFalse(discovery.isError(), () -> "crawl_discover failed: " + discovery.getOutput());
        JsonNode catalog = jsonOutput(mapper, discovery, "crawl_discover");
        JsonNode vlmTemplate = findObject(catalog.path("pipelineTemplates"), "pipelineType", "VLM");
        assertTrue(vlmTemplate.isObject(),
                () -> "crawl_discover did not advertise a VLM pipeline: " + discovery.getOutput());
        assertTrue(vlmTemplate.path("available").asBoolean(false),
                () -> "crawl_discover advertised VLM without an available worker: " + discovery.getOutput());
        assertTrue(vlmTemplate.path("configuration").path("pipelineOptionFields").toString()
                        .contains("pdfRenderDpi")
                        && vlmTemplate.path("configuration").path("pipelineOptionFields").toString()
                        .contains("pageRange"),
                () -> "crawl_discover omitted request-scoped VLM options: " + discovery.getOutput());
        assertTrue(vlmTemplate.path("configuration").path("runtimeConfigFields").toString()
                        .contains("documentModelExecutable"),
                () -> "crawl_discover omitted the VLM worker override: " + discovery.getOutput());
        assertEquals("model_runtime", catalog.path("modelRuntime").path("tool").asText(),
                discovery.getOutput());

        ToolResult runtimeStatus = modelRuntime.execute(
                mapper.createObjectNode().put("action", "status"), context);
        assertFalse(runtimeStatus.isError(),
                () -> "model_runtime status failed: " + runtimeStatus.getOutput());
        JsonNode runtime = jsonOutput(mapper, runtimeStatus, "model_runtime status");
        assertEquals(testProjectRoot.resolve("data/models").toString(), runtime.path("storage").asText(),
                runtimeStatus.getOutput());
        assertFalse(runtime.path("runtimeContract").path("centralizedService").asBoolean(true),
                runtimeStatus.getOutput());

        ObjectNode bootstrapRequest = mapper.createObjectNode();
        bootstrapRequest.put("action", "bootstrap");
        bootstrapRequest.put("modelId", modelId);
        bootstrapRequest.put("source", "huggingface");
        bootstrapRequest.put("repository", "ds4sd/SmolDocling-256M-preview");
        bootstrapRequest.put("format", "vlm");
        bootstrapRequest.put("type", "vlm_pipeline");
        bootstrapRequest.put("timeoutMinutes", timeoutMinutes);
        ToolResult bootstrap = modelRuntime.execute(bootstrapRequest, context);
        assertFalse(bootstrap.isError(),
                () -> "model_runtime bootstrap failed: " + bootstrap.getOutput());
        JsonNode bootstrapped = jsonOutput(mapper, bootstrap, "model_runtime bootstrap");
        Path stagedModel = Path.of(bootstrapped.path("model").path("modelPath").asText())
                .toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(stagedModel),
                () -> "model_runtime did not produce a local VLM artifact: " + bootstrap.getOutput());
        assertTrue(stagedModel.getFileName().toString().endsWith(".onnx")
                        || stagedModel.getFileName().toString().equals("pipeline.json"),
                () -> "model_runtime returned no runnable VLM pipeline artifact: " + bootstrap.getOutput());
        assertEquals(modelId, stagedModel.getParent().getFileName().toString(), bootstrap.getOutput());
        JsonNode stagedInventory = findObject(bootstrapped.path("models"), "id", modelId);
        assertEquals("VLM", stagedInventory.path("role").asText(), bootstrap.getOutput());
        assertTrue(stagedInventory.path("ready").asBoolean(false), bootstrap.getOutput());

        ObjectNode request = mapper.createObjectNode();
        request.put("name", "Real local image-PDF VLM MCP integration");
        request.putObject("knowledgeBase").put("name", KNOWLEDGE_BASE);
        request.putArray("documents").addObject()
                .put("path", pdf.toString())
                .put("sourceType", "FILE")
                .put("pipelineId", PIPELINE_ID);

        ObjectNode pipeline = request.putArray("pipelines").addObject();
        pipeline.put("pipelineId", PIPELINE_ID);
        pipeline.put("pipelineType", "VLM");
        pipeline.put("loaderName", "pdf");
        pipeline.put("chunkerName", "sentence");
        pipeline.putObject("options")
                .put("vlmModel", modelId)
                .put("modelId", modelId)
                .put("outputFormat", "MARKDOWN")
                .put("maxPages", maxPages)
                .put("maxNewTokens", positiveInt(MAX_TOKENS_PROPERTY, 768))
                .put("pdfRenderDpi", positiveInt(PDF_DPI_PROPERTY, 144))
                .put("pageBatchSize", 1)
                .put("temperature", 0.0d)
                .put("doSample", false)
                .put("timeoutMinutes", timeoutMinutes);
        if (pageRange != null) {
            ((ObjectNode) pipeline.path("options")).put("pageRange", pageRange);
        }

        request.putObject("modelRuntime")
                .put("autoBootstrap", false)
                .put("type", "vlm_pipeline")
                .put("timeoutMinutes", timeoutMinutes);
        request.putObject("runtimeConfig")
                .put("documentModelExecutable", worker.toString())
                .put("documentModelExecutableMode", "DEDICATED");
        request.putArray("steps")
                .add("LOADING")
                .add("MARKDOWN_EXTRACTION")
                .add("CHUNKING");
        request.put("strictSteps", true);
        request.put("deriveOntology", false);
        request.putObject("embeddingTraining").put("enabled", false);
        request.putObject("reasoningLearning").put("enabled", false);

        ToolResult crawl = crawlDocuments.execute(request, context);

        assertFalse(crawl.isError(), () -> "crawl_documents failed: " + crawl.getOutput());
        assertEquals("COMPLETED", crawl.getMetadata().get("status"), crawl.getOutput());
        assertEquals(0, number(crawl, "failedDocumentCount"), crawl.getOutput());
        assertEquals(1, number(crawl, "documentCount"), crawl.getOutput());
        assertTrue(number(crawl, "chunkCount") > 0,
                "The VLM output did not reach the production chunking stage: " + crawl.getOutput());

        Path crawlDirectory = testProjectRoot.resolve("data/crawls").resolve(KNOWLEDGE_BASE);
        Path documents = crawlDirectory.resolve("documents.jsonl");
        Path chunks = crawlDirectory.resolve("chunks.jsonl");
        assertTrue(Files.isRegularFile(documents), "crawl_documents did not persist documents.jsonl");
        assertTrue(Files.isRegularFile(chunks), "crawl_documents did not persist chunks.jsonl");
        String extractedChunks = Files.readString(chunks);
        assertTrue(extractedChunks.length() > 200,
                "The real VLM produced no substantive persisted markdown: " + extractedChunks);
        assertFalse(Files.exists(testProjectRoot.resolve(pdf.getFileName())),
                "The external PDF fixture was copied into the test project");
    }

    private static ObjectNode vlmCrawlRequest(
            ObjectMapper mapper,
            Path pdf,
            String modelId,
            int maxPages,
            String pageRange,
            int maxNewTokens,
            int pdfRenderDpi,
            int timeoutMinutes) {
        ObjectNode request = mapper.createObjectNode();
        request.put("name", "Real JVM image-PDF VLM crawl integration");
        request.putObject("knowledgeBase").put("name", KNOWLEDGE_BASE);
        request.putArray("documents").addObject()
                .put("path", pdf.toString())
                .put("sourceType", "FILE")
                .put("pipelineId", PIPELINE_ID);

        ObjectNode pipeline = request.putArray("pipelines").addObject();
        pipeline.put("pipelineId", PIPELINE_ID);
        pipeline.put("pipelineType", "VLM");
        pipeline.put("loaderName", "pdf");
        pipeline.put("chunkerName", "sentence");
        pipeline.putObject("options")
                .put("vlmModel", modelId)
                .put("modelId", modelId)
                .put("outputFormat", "MARKDOWN")
                .put("maxPages", maxPages)
                .put("maxNewTokens", maxNewTokens)
                .put("pdfRenderDpi", pdfRenderDpi)
                .put("pageBatchSize", 1)
                .put("temperature", 0.0d)
                .put("doSample", false)
                .put("timeoutMinutes", timeoutMinutes);
        if (pageRange != null) {
            ((ObjectNode) pipeline.path("options")).put("pageRange", pageRange);
        }

        request.putArray("steps")
                .add("LOADING")
                .add("MARKDOWN_EXTRACTION")
                .add("CHUNKING");
        request.put("strictSteps", true);
        request.put("deriveOntology", false);
        request.putObject("embeddingTraining").put("enabled", false);
        request.putObject("reasoningLearning").put("enabled", false);
        return request;
    }

    private static void assertPersistedVlmWords(
            ObjectMapper mapper, Path projectRoot, Path crawlDirectory, String generated)
            throws Exception {
        Path documents = crawlDirectory.resolve("documents.jsonl");
        Path chunks = crawlDirectory.resolve("chunks.jsonl");
        assertTrue(Files.isRegularFile(documents),
                "crawl_documents did not persist documents.jsonl");
        assertTrue(Files.isRegularFile(chunks),
                "crawl_documents did not persist chunks.jsonl");

        JsonNode document;
        try (Stream<String> lines = Files.lines(documents)) {
            document = mapper.readTree(lines.findFirst().orElseThrow(
                    () -> new AssertionError("documents.jsonl is empty")));
        }
        assertEquals("EXTRACTED", document.path("extractionStatus").asText(), document.toString());
        assertEquals(PIPELINE_ID, document.path("pipelineId").asText(), document.toString());
        assertEquals("VLM", document.path("pipelineType").asText(), document.toString());
        assertTrue(document.path("wordCount").asLong() > 0, document.toString());

        Path markdown = projectRoot.resolve(document.path("markdownPath").asText()).normalize();
        assertTrue(Files.isRegularFile(markdown),
                () -> "The extracted markdown artifact is missing: " + markdown);
        String persistedMarkdown = Files.readString(markdown);
        assertTrue(normalizedText(persistedMarkdown).contains(normalizedText(generated)),
                "The markdown artifact did not preserve the VLM-generated text");

        StringBuilder chunkText = new StringBuilder();
        try (Stream<String> lines = Files.lines(chunks)) {
            var iterator = lines.iterator();
            while (iterator.hasNext()) {
                JsonNode chunk = mapper.readTree(iterator.next());
                String text = chunk.path("text").asText();
                assertFalse(text.isBlank(), () -> "Persisted an empty chunk: " + chunk);
                chunkText.append(' ').append(text);
            }
        }
        List<String> sampleWords = wordTokens(generated).stream().distinct().limit(12).toList();
        Set<String> persistedWords = Set.copyOf(wordTokens(chunkText.toString()));
        assertTrue(persistedWords.containsAll(sampleWords),
                () -> "The chunk corpus cannot consume the generated VLM words. Missing: "
                        + sampleWords.stream()
                        .filter(word -> !persistedWords.contains(word))
                        .toList());
    }

    private static void assertRealWords(String generated) {
        assertFalse(generated == null || generated.isBlank(),
                "The real JVM VLM produced no document text");
        List<String> words = wordTokens(generated);
        assertTrue(words.size() >= 3,
                () -> "The real JVM VLM did not produce enough readable words: "
                        + preview(generated, 240));
        assertTrue(words.stream().distinct().count() >= 3,
                () -> "The real JVM VLM output is degenerate rather than readable text: "
                        + preview(generated, 240));
    }

    private static List<String> wordTokens(String text) {
        if (text == null || text.isBlank()) return List.of();
        return java.util.Arrays.stream(text.toLowerCase(Locale.ROOT)
                        .split("[^\\p{L}\\p{N}]+"))
                .filter(token -> token.length() > 1)
                .filter(token -> token.codePoints().anyMatch(Character::isLetter))
                .toList();
    }

    private static String normalizedText(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    private static String preview(String text, int maxCharacters) {
        String normalized = normalizedText(text);
        return normalized.length() <= maxCharacters
                ? normalized : normalized.substring(0, maxCharacters) + "…";
    }

    private Path configuredProjectRoot() throws Exception {
        String configured = System.getProperty(PROJECT_ROOT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return projectRoot;
        }
        Path root = Path.of(configured.trim()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        assertTrue(Files.isDirectory(root),
                () -> "Configured VLM integration project root is not a directory: " + root);
        return root;
    }

    private static Path requiredExternalPdf() {
        String configured = System.getProperty(PDF_PROPERTY);
        assertTrue(configured != null && !configured.isBlank(),
                () -> "Supply a local image-based PDF with -D" + PDF_PROPERTY + "=<absolute-path>");
        Path pdf = Path.of(configured.trim()).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(pdf),
                () -> "Property-provided PDF does not exist: " + pdf);
        assertTrue(pdf.getFileName().toString().toLowerCase().endsWith(".pdf"),
                () -> "Property-provided fixture is not a PDF: " + pdf);
        return pdf;
    }

    private static Path requiredDirectory(String property) {
        String configured = System.getProperty(property);
        assertTrue(configured != null && !configured.isBlank(),
                () -> "Supply a local VLM model directory with -D" + property + "=<absolute-path>");
        Path directory = Path.of(configured.trim()).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(directory),
                () -> "Property-provided VLM model directory does not exist: " + directory);
        assertTrue(Files.isRegularFile(directory.resolve("vision_encoder.onnx"))
                        || Files.isRegularFile(directory.resolve("vision_encoder.sdz")),
                () -> "VLM model directory has no vision encoder component: " + directory);
        assertTrue(Files.isRegularFile(directory.resolve("decoder_model_merged.onnx"))
                        || Files.isRegularFile(directory.resolve("decoder_model_merged.sdz"))
                        || Files.isRegularFile(directory.resolve("decoder.sdz")),
                () -> "VLM model directory has no decoder component: " + directory);
        return directory;
    }

    private static Path requiredExecutable(String property, Path defaultPath) {
        String configured = System.getProperty(property);
        Path executable = (configured == null || configured.isBlank())
                ? defaultPath : Path.of(configured.trim());
        Path normalized = executable.toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(normalized),
                () -> "Required native VLM worker is missing: " + normalized
                        + " (override with -D" + property + "=<path>)");
        assertTrue(Files.isExecutable(normalized),
                () -> "Configured VLM worker is not executable: " + normalized);
        return normalized;
    }

    private static String resolveFixturePageRange(Path pdf, String requestedPageRange)
            throws Exception {
        if (requestedPageRange != null) {
            return requestedPageRange;
        }

        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                int rasterImages = 0;
                for (COSName name : document.getPage(pageIndex).getResources().getXObjectNames()) {
                    PDXObject object = document.getPage(pageIndex).getResources().getXObject(name);
                    if (object instanceof PDImageXObject) {
                        rasterImages++;
                    }
                }
                if (rasterImages == 0) {
                    continue;
                }

                int pageNumber = pageIndex + 1;
                stripper.setStartPage(pageNumber);
                stripper.setEndPage(pageNumber);
                int sourceWords = wordTokens(stripper.getText(document)).size();
                if (sourceWords >= MIN_FIXTURE_SOURCE_WORDS) {
                    System.out.println("Selected image-bearing VLM fixture page " + pageNumber
                            + " (rasterImages=" + rasterImages
                            + ", sourceTextWords=" + sourceWords + ")");
                    return String.valueOf(pageNumber);
                }
            }
        }
        throw new AssertionError("No image-bearing page with readable source text was found in "
                + pdf + "; set -D" + PAGE_RANGE_PROPERTY + "=<page> explicitly");
    }

    private static void assertImageBackedFixture(Path pdf, int maxPages, String pageRange)
            throws Exception {
        int rasterImages = 0;
        List<Integer> selectedPages;
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            int totalPages = document.getNumberOfPages();
            assertTrue(totalPages > 0, "PDF has no pages: " + pdf);

            selectedPages = OcrPipelineConfig.builder()
                    .pageRange(pageRange)
                    .build()
                    .parsePageRange(totalPages);
            if (selectedPages == null) {
                selectedPages = new java.util.ArrayList<>();
                for (int page = 1; page <= totalPages; page++) {
                    selectedPages.add(page);
                }
            }
            if (selectedPages.size() > maxPages) {
                selectedPages = selectedPages.subList(0, maxPages);
            }
            assertFalse(selectedPages.isEmpty(),
                    () -> "Configured page range selects no pages: " + pageRange + " in " + pdf);

            for (int pageNumber : selectedPages) {
                assertTrue(pageNumber >= 1 && pageNumber <= totalPages,
                        () -> "Configured page is outside the PDF: " + pageNumber + " in " + pdf);
                int pageIndex = pageNumber - 1;
                int pageRasterImages = 0;
                for (COSName name : document.getPage(pageIndex).getResources().getXObjectNames()) {
                    PDXObject object = document.getPage(pageIndex).getResources().getXObject(name);
                    if (object instanceof PDImageXObject) {
                        pageRasterImages++;
                    }
                }
                rasterImages += pageRasterImages;
            }
        }

        List<Integer> inspectedPages = List.copyOf(selectedPages);
        assertTrue(rasterImages > 0,
                () -> "The configured PDF has no raster images on selected pages "
                        + inspectedPages + ": " + pdf);
    }

    private static String optionalProperty(String property) {
        String value = System.getProperty(property);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int positiveInt(String property, int defaultValue) {
        int value = Integer.getInteger(property, defaultValue);
        assertTrue(value > 0, () -> property + " must be greater than zero");
        return value;
    }

    private static int number(ToolResult result, String key) {
        Object value = result.getMetadata().get(key);
        assertTrue(value instanceof Number,
                () -> "Missing numeric metadata " + key + " in " + result.getMetadata());
        return ((Number) value).intValue();
    }

    private static JsonNode jsonOutput(ObjectMapper mapper, ToolResult result, String operation)
            throws Exception {
        JsonNode output = mapper.readTree(result.getOutput());
        assertTrue(output != null && output.isObject(),
                () -> operation + " did not return a JSON object: " + result.getOutput());
        return output;
    }

    private static JsonNode findObject(JsonNode values, String field, String expected) {
        if (values != null && values.isArray()) {
            for (JsonNode value : values) {
                if (expected.equals(value.path(field).asText())) {
                    return value;
                }
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
}
