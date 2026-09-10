/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.cli.main.chat.mcp;

import ai.kompile.cli.main.MainCommand;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.tools.ResourcePolicy;
import ai.kompile.cli.main.chat.tools.grounding.LocalProjectGraphBackend;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One opt-in, bounded proof through the actual MCP stdio surface: Codex Luna/xhigh reads a
 * temporary two-page image-only PDF, the crawl persists OCR and graph artifacts in an isolated
 * temporary project, and all graph engines are called against that same named graph.
 */
class ChatModelPdfOwlTransitiveMcpLiveIT {
    private static final String ENABLE_PROPERTY = "kompile.remote.chat.pdf.live";
    private static final String MODEL = "gpt-5.6-luna";
    private static final String THINKING = "xhigh";
    private static final String KNOWLEDGE_BASE = "codex-luna-pdf-owl-transitive-acceptance";
    private static final String ARCHIVE = "Archive Vault";
    private static final String BUILDING = "Records Building";
    private static final String REGION = "North Region";
    private static final String PART_OF = "PART_OF";
    private static final String PAGE_ONE = "Archive Vault is part of Records Building.";
    private static final String PAGE_TWO = "Records Building is part of North Region.";
    private static final String ONTOLOGY = """
            @prefix ex: <urn:kompile:pdf-owl-mcp-live-it#> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            ex:PART_OF a owl:ObjectProperty , owl:TransitiveProperty .
            """;
    private static final String OCR_PROMPT = "Read each supplied PDF page image and return faithful OCR in Markdown. "
            + "Preserve visible wording, punctuation, and page boundaries exactly. Do not infer, summarize, "
            + "normalize, or invent content.";
    private static final String EXTRACTION_PROMPT = "Extract only facts explicitly supported by the OCR text and the "
            + "declared schema. Use only the declared node and relationship types. Do not emit a properties object; "
            + "source/page provenance is supplied by the crawl engine. Return structured facts only, with no prose. "
            + "OWL transitivity is an explicit declared-artifact rule and is not an OCR extraction.";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @TempDir
    Path project;

    private Path pdf;

    @BeforeEach
    void createFixtures() throws Exception {
        pdf = project.resolve("two-image-only-containment.pdf");
        Files.writeString(project.resolve("facts.txt"),
                "Unrelated fixture facts must not enter generic OCR output.\n", StandardCharsets.UTF_8);
        createImageOnlyPdf(pdf);
        configureFixtureResourcePolicy(project);
        seedDeclaredOntology(project);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void actualMcpCrawlsTemporaryImagePdfAndExercisesNamedGraphEngines() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(ENABLE_PROPERTY),
                "Native Codex PDF crawl is opt-in; pass -D" + ENABLE_PROPERTY + "=true");
        Instant started = Instant.now();
        configureRequestScopedCodex(project);
        JsonNode defaultBefore;
        JsonNode defaultAfter;
        String transcript = "pdf-mcp-live-" + UUID.randomUUID();
        try (McpStdioClient client = startClient(project, transcript)) {
            client.initialize();
            List<McpBundleToolLoader.RemoteTool> tools = client.listTools();
            for (String name : List.of("crawl_documents", "crawl_control", "crawl_result", "graph_search",
                    "ask_graph_mebn", "graph_bayes", "graph_embeddings", "ask_graph_verify",
                    "ask_graph_explain", "knowledge_graph", "graph_reasoning_query")) {
                assertNotNull(tool(tools, name), name + " missing from actual tools/list");
            }
            for (String name : List.of("ask_graph_mebn", "graph_bayes", "graph_embeddings")) {
                assertTrue(tool(tools, name).inputSchema().path("properties").has("knowledgeBase"),
                        "fresh tools/list omitted knowledgeBase for " + name);
            }

            defaultBefore = call(client, "knowledge_graph", object("action", "overview")
                    .put("knowledgeBase", KNOWLEDGE_BASE));
            JsonNode crawl = callTool(client, "crawl_documents", crawlRequest(pdf));
            assertToolSuccess(crawl);
            JsonNode metadata = crawl.path("structuredContent").path("metadata");
            String jobId = metadata.path("jobId").asText();
            assertFalse(jobId.isBlank(), crawl.toPrettyString());
            JsonNode status = awaitTerminal(client, jobId, Duration.ofMinutes(9));
            assertToolSuccess(status);
            assertEquals("COMPLETED", status.path("structuredContent").path("metadata").path("status").asText(),
                    status.toPrettyString());

            JsonNode result = callTool(client, "crawl_result", object("jobId", jobId));
            assertToolSuccess(result);
            JsonNode resultMeta = result.path("structuredContent").path("metadata");
            assertEquals(KNOWLEDGE_BASE, resultMeta.path("knowledgeBase").asText(), result.toPrettyString());
            assertEquals(1, resultMeta.path("documentCount").asInt(), result.toPrettyString());
            assertTrue(resultMeta.path("chunkCount").asInt() > 0, result.toPrettyString());
            assertTrue(resultMeta.path("folPslLearned").asBoolean(), result.toPrettyString());
            assertTrue(resultMeta.path("mebnLearned").asBoolean(), result.toPrettyString());

            Path crawlDir = project.resolve("data/crawls").resolve(KNOWLEDGE_BASE);
            Path chunks = crawlDir.resolve("chunks.jsonl");
            Path graph = crawlDir.resolve(LocalProjectGraphBackend.GRAPH_FILE);
            assertTrue(Files.isRegularFile(pdf), pdf.toString());
            assertTrue(Files.isRegularFile(chunks), chunks.toString());
            assertTrue(Files.isRegularFile(graph), graph.toString());
            String ocr = Files.readString(chunks, StandardCharsets.UTF_8);
            assertTrue(ocr.contains(PAGE_ONE), ocr);
            assertTrue(ocr.contains(PAGE_TWO), ocr);
            assertTrue(ocr.contains("Pages 1"), ocr);
            assertTrue(ocr.contains("Pages 2"), ocr);
            assertFalse(ocr.contains("facts.txt"), ocr);

            JsonNode search = call(client, "graph_search", object("query", ARCHIVE + " " + BUILDING + " " + REGION)
                    .put("search_type", "hybrid").put("max_results", 20).put("knowledgeBase", KNOWLEDGE_BASE));
            assertTrue(toolText(search).contains(ARCHIVE), search.toPrettyString());
            String archiveId = entityId(search, ARCHIVE);
            String buildingId = entityId(search, BUILDING);
            String regionId = entityId(search, REGION);

            JsonNode path = call(client, "graph_reasoning_query", object("operation", "PATH")
                    .put("entityId", archiveId).put("targetId", regionId).put("knowledgeBase", KNOWLEDGE_BASE));
            assertTrue(toolText(path).contains(BUILDING), path.toPrettyString());
            JsonNode verify = call(client, "ask_graph_verify", object("atom", PART_OF + "(" + ARCHIVE + ", " + BUILDING + ")")
                    .put("knowledgeBase", KNOWLEDGE_BASE));
            assertEquals("SUPPORTED", structured(verify).path("verdict").asText(), verify.toPrettyString());
            JsonNode transitiveVerify = call(client, "ask_graph_verify",
                    object("atom", PART_OF + "(" + ARCHIVE + ", " + REGION + ")")
                            .put("knowledgeBase", KNOWLEDGE_BASE));
            JsonNode transitiveData = structured(transitiveVerify);
            assertEquals("SUPPORTED", transitiveData.path("verdict").asText(), transitiveVerify.toPrettyString());
            assertTrue(transitiveData.path("supportingFactKeys").isArray()
                            && transitiveData.path("supportingFactKeys").size() >= 2,
                    "OWL transitive verification omitted its two premise facts: " + transitiveVerify.toPrettyString());
            boolean hasTransitiveOwlRule = false;
            for (JsonNode rule : transitiveData.path("activatedRules")) {
                hasTransitiveOwlRule |= rule.asText().contains("prp-trp");
            }
            assertTrue(hasTransitiveOwlRule,
                    "OWL transitive verification omitted the activated OWL rule: " + transitiveVerify.toPrettyString());
            JsonNode reverseVerify = call(client, "ask_graph_verify",
                    object("atom", PART_OF + "(" + REGION + ", " + ARCHIVE + ")")
                            .put("knowledgeBase", KNOWLEDGE_BASE));
            assertEquals("UNKNOWN", structured(reverseVerify).path("verdict").asText(), reverseVerify.toPrettyString());
            JsonNode explain = call(client, "ask_graph_explain", object("atom", PART_OF + "(" + ARCHIVE + ", " + BUILDING + ")")
                    .put("knowledgeBase", KNOWLEDGE_BASE));
            assertToolSuccess(explain);

            JsonNode owl = call(client, "knowledge_graph", object("action", "owl_reasoning")
                    .put("knowledgeBase", KNOWLEDGE_BASE));
            assertToolSuccess(owl);
            JsonNode owlData = structured(owl);
            assertEquals("declared-artifact", owlData.path("ontologySource").asText(), owl.toPrettyString());
            assertTrue(owlData.path("inferredRelationCount").asInt() >= 1, owl.toPrettyString());

            JsonNode mebn = call(client, "ask_graph_mebn", object("nodeId", archiveId)
                    .put("maxDepth", 3).put("maxNodes", 32).put("knowledgeBase", KNOWLEDGE_BASE));
            assertToolSuccess(mebn);
            JsonNode mebnData = structured(mebn);
            assertTrue(mebnData.path("posteriors").isObject() && mebnData.path("posteriors").size() > 0,
                    "MEBN returned metadata without posterior values: " + mebn.toPrettyString());

            JsonNode bayes = call(client, "graph_bayes", object("action", "query")
                    .put("node_id", archiveId).put("max_depth", 3).put("max_nodes", 32)
                    .put("knowledgeBase", KNOWLEDGE_BASE));
            assertToolSuccess(bayes);
            JsonNode bayesData = structured(bayes);
            String bayesMethod = bayesData.path("method").asText("");
            assertTrue(bayesMethod.contains("VariableElimination"),
                    "graph_bayes did not report VariableElimination: " + bayes.toPrettyString());
            assertEquals("VariableElimination", bayesData.path("inferenceAlgorithm").asText(),
                    "graph_bayes reported the wrong inference algorithm: " + bayes.toPrettyString());
            assertTrue(bayesData.path("posterior").isNumber(),
                    "graph_bayes omitted a numeric posterior: " + bayes.toPrettyString());
            double bayesPosterior = bayesData.path("posterior").asDouble(Double.NaN);
            assertTrue(Double.isFinite(bayesPosterior) && bayesPosterior >= 0.0 && bayesPosterior <= 1.0,
                    "graph_bayes returned an invalid posterior: " + bayes.toPrettyString());

            JsonNode trained = call(client, "graph_embeddings", object("action", "train")
                    .put("algorithm", "TRANSE").put("embedding_dim", 8).put("epochs", 3)
                    .put("learning_rate", 0.05).put("knowledgeBase", KNOWLEDGE_BASE));
            assertToolSuccess(trained);
            assertTrue(toolText(trained).contains("TRANSE"), trained.toPrettyString());
            for (ObjectNode request : List.of(
                    object("action", "score").put("head", ARCHIVE).put("relation", PART_OF).put("tail", BUILDING),
                    object("action", "predict_tails").put("head", ARCHIVE).put("relation", PART_OF),
                    object("action", "predict_heads").put("relation", PART_OF).put("tail", BUILDING),
                    object("action", "predict_relations").put("head", ARCHIVE).put("tail", BUILDING),
                    object("action", "similar").put("entity_name", ARCHIVE))) {
                request.put("top_k", 3).put("knowledgeBase", KNOWLEDGE_BASE);
                JsonNode response = callTool(client, "graph_embeddings", request);
                assertToolSuccess(response);
            }
            JsonNode postKgeOverview = call(client, "knowledge_graph", object("action", "overview")
                    .put("knowledgeBase", KNOWLEDGE_BASE));
            assertTrue(structured(postKgeOverview).path("embeddingVectors").asInt() >= 3,
                    "KGE did not publish entity vectors: " + postKgeOverview.toPrettyString());

            JsonNode bayesianRank = call(client, "graph_reasoning_query", object("operation", "RANK")
                    .put("structural", "BAYESIAN").put("queryText", ARCHIVE)
                    .put("knowledgeBase", KNOWLEDGE_BASE).put("topK", 5));
            String rankText = toolText(bayesianRank);
            assertTrue(rankText.contains("using BAYESIAN structural reasoning"),
                    "RANK did not report the requested BAYESIAN engine: " + bayesianRank.toPrettyString());
            assertFalse(rankText.contains("PageRank"),
                    "RANK unexpectedly reported PageRank for structural=BAYESIAN: " + bayesianRank.toPrettyString());

            JsonNode wrongKb = callTool(client, "graph_search", object("query", ARCHIVE)
                    .put("knowledgeBase", KNOWLEDGE_BASE + "-does-not-exist"));
            assertTrue(wrongKb.path("isError").asBoolean(),
                    "unknown knowledgeBase unexpectedly fell back: " + wrongKb.toPrettyString());
            defaultAfter = call(client, "knowledge_graph", object("action", "overview")
                    .put("knowledgeBase", KNOWLEDGE_BASE));

            System.out.println("[pdf-mcp-live] PDF=" + pdf);
            System.out.println("[pdf-mcp-live] OCR=" + chunks);
            System.out.println("[pdf-mcp-live] GRAPH=" + graph);
            System.out.println("[pdf-mcp-live] GRAPH_ID=local:kompile:" + KNOWLEDGE_BASE);
            System.out.println("[pdf-mcp-live] MEBN_POSTERIORS=" + mebnData.path("posteriors").size());
            System.out.println("[pdf-mcp-live] BAYES_METHOD=" + bayesMethod);
            System.out.println("[pdf-mcp-live] OWL_INFERRED_RELATIONS="
                    + owlData.path("inferredRelationCount").asInt()
                    + " verifyTransitive=" + structured(transitiveVerify).path("verdict").asText("UNKNOWN")
                    + " reverseSupported=" + toolText(reverseVerify).contains("SUPPORTED"));
            System.out.println("[pdf-mcp-live] KGE_VECTOR_COUNT="
                    + structured(postKgeOverview).path("embeddingVectors").asInt());
            System.out.println("[pdf-mcp-live] DURATION_MS=" + Duration.between(started, Instant.now()).toMillis());
            assertEquals(defaultBefore.path("graphPath").asText(), defaultAfter.path("graphPath").asText(),
                    "explicit named crawl changed the default graph selector");
        }
    }

    private JsonNode awaitTerminal(McpStdioClient client, String jobId, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        JsonNode latest = null;
        while (Instant.now().isBefore(deadline)) {
            latest = callTool(client, "crawl_control", object("operation", "status").put("jobId", jobId));
            assertToolSuccess(latest);
            JsonNode metadata = latest.path("structuredContent").path("metadata");
            if (metadata.path("terminal").asBoolean()) return latest;
            Thread.sleep(Math.min(1_000L, Math.max(100L, metadata.path("pollAfterMs").asLong(500L))));
        }
        throw new AssertionError("PDF crawl did not reach terminal state: " + latest);
    }

    private ObjectNode crawlRequest(Path pdf) {
        ObjectNode request = object("name", "Native Codex image-only PDF OWL closure");
        request.put("async", true).put("deriveOntology", true).put("maxValidationRetries", 0);
        request.putObject("knowledgeBase").put("name", KNOWLEDGE_BASE);
        request.putArray("documents").addObject().put("path", pdf.toString()).put("pipelineId", "luna-pdf");
        ObjectNode pipeline = request.putArray("pipelines").addObject()
                .put("pipelineId", "luna-pdf").put("pipelineType", "VLM")
                .put("loaderName", "auto").put("chunkerName", "no-op");
        pipeline.putObject("processor").put("type", "CHAT_MODEL").put("provider", "openai-codex")
                .put("modelId", MODEL).put("thinking", THINKING);
        pipeline.putObject("options").put("provider", "openai-codex").put("modelId", MODEL)
                .put("thinking", THINKING).put("prompt", OCR_PROMPT).put("outputFormat", "MARKDOWN")
                .put("maxPages", 2).put("pageBatchSize", 1).put("pdfRenderDpi", 120)
                .put("maxResponseChars", 20_000);
        request.putArray("steps").add("GRAPH_EXTRACTION").add("ENTITY_RESOLUTION").add("ENRICHMENT");
        request.putObject("embeddingTraining").put("enabled", false);
        request.putObject("reasoningLearning").put("enabled", true).put("pslSteps", 1)
                .put("mebnEpochs", 1).put("consensusRounds", 1).put("consensusWeight", 0.35)
                .put("maxRelationTypes", 8);
        request.putObject("runtimeConfig").put("runReasoningLearning", true)
                .put("graphExtractionParallelism", 1).put("graphExtractionRemoteParallelism", 1)
                .put("llmCallTimeoutSeconds", 120).put("graphExtractionBatchTimeoutSeconds", 300);
        ObjectNode extraction = request.putObject("graphExtraction")
                .put("modelName", MODEL).put("thinking", THINKING).put("extractionMode", "SINGLE_PASS")
                .put("entityResolution", true).put("minConfidence", 0.0).put("schemaMode", "STRICT")
                .put("customPrompt", EXTRACTION_PROMPT);
        extraction.putArray("entityTypes").add("ARCHIVE").add("BUILDING").add("REGION");
        extraction.putArray("relationshipTypes").add(PART_OF);
        request.putObject("processingRoute").put("fallbackEnabled", false)
                .putArray("backends").addObject()
                .put("id", "native-codex-luna-pdf").put("type", "CHAT_MODEL")
                .put("provider", "openai-codex").put("modelName", MODEL)
                .put("thinking", THINKING).put("priority", 1);
        ObjectNode schema = extraction.putObject("standardizedSchema");
        schema.putArray("nodeTypes").addObject().put("label", "ARCHIVE").put("description", "A named archive.")
                .put("parentType", "LOCATION");
        schema.withArray("nodeTypes").addObject().put("label", "BUILDING").put("description", "A named building.")
                .put("parentType", "LOCATION");
        schema.withArray("nodeTypes").addObject().put("label", "REGION").put("description", "A named region.")
                .put("parentType", "LOCATION");
        schema.putArray("relationshipTypes").addObject().put("type", PART_OF)
                .put("description", "An explicit child-to-container relation.").put("connectionFamily", "PART_WHOLE");
        return request;
    }

    private McpStdioClient startClient(Path project, String transcript) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> args = List.of("-Xmx512m", "-XX:ActiveProcessorCount=2",
                "-Dkompile.memory.dense.enabled=false",
                "-Dkompile.coordination.systemRoot=" + project.resolve(".coordination"),
                "-cp", classpath, MainCommand.class.getName(), "mcp-stdio", "--no-daemon",
                "--profile", "full", "--work-dir", project.toString(), "--transcript-id", transcript);
        return new McpStdioClient(mapper, java, args,
                Map.of("OMP_NUM_THREADS", "2", "OPENBLAS_NUM_THREADS", "2", "MKL_NUM_THREADS", "2"),
                project, 600, "pdf-mcp-live", transcript);
    }

    private void configureRequestScopedCodex(Path project) throws Exception {
        ChatConfig configured = ChatConfig.loadOrFromEnv(project);
        assertNotNull(configured, "Native Codex configuration is missing");
        ChatConfig scoped = new ChatConfig(configured.getProvider(), null, MODEL, configured.getBaseUrl());
        scoped.setAuthenticationMethod(configured.getAuthenticationMethod());
        scoped.setThinking(THINKING);
        scoped.setChatMode("standard");
        scoped.setApiKey(null);
        scoped.saveProject(project);
        String persisted = Files.readString(ChatConfig.projectConfigPath(project), StandardCharsets.UTF_8);
        assertTrue(persisted.contains(MODEL) && persisted.contains(THINKING), persisted);
        assertFalse(persisted.contains("apiKey"), persisted);
    }

    private void configureFixtureResourcePolicy(Path project) throws Exception {
        ObjectNode policy = ResourcePolicy.defaults();
        var rules = (com.fasterxml.jackson.databind.node.ArrayNode) policy.path("rules");
        ObjectNode crawlRule = rules.addObject().put("id", "native-codex-luna-pdf-crawl")
                .put("tool", "crawl_documents").put("class", "low");
        var crawlConditions = crawlRule.putArray("all");
        crawlConditions.addObject().put("path", "/arguments/knowledgeBase/name")
                .put("op", "eq").put("value", KNOWLEDGE_BASE);
        crawlConditions.addObject().put("path", "/arguments/documents/0/path")
                .put("op", "eq").put("value", pdf.toAbsolutePath().normalize().toString());
        crawlConditions.addObject().put("path", "/arguments/processingRoute/backends/0/modelName")
                .put("op", "eq").put("value", MODEL);

        ObjectNode trainRule = rules.addObject().put("id", "native-codex-luna-pdf-kge-train")
                .put("tool", "graph_embeddings").put("class", "low");
        var trainConditions = trainRule.putArray("all");
        trainConditions.addObject().put("path", "/arguments/action").put("op", "eq").put("value", "train");
        trainConditions.addObject().put("path", "/arguments/knowledgeBase")
                .put("op", "eq").put("value", KNOWLEDGE_BASE);
        trainConditions.addObject().put("path", "/arguments/algorithm")
                .put("op", "eq").put("value", "TRANSE");
        trainConditions.addObject().put("path", "/arguments/embedding_dim")
                .put("op", "eq").put("value", 8);
        trainConditions.addObject().put("path", "/arguments/epochs")
                .put("op", "eq").put("value", 3);

        ResourcePolicy.validate(policy);
        Path policyPath = project.resolve(".kompile/resource-policy.json");
        Files.createDirectories(policyPath.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(policyPath.toFile(), policy);
        ResourcePolicy.Decision crawlDecision = ResourcePolicy.classify(project, "crawl_documents", crawlRequest(pdf));
        assertEquals("low", crawlDecision.resourceClass(), crawlDecision.reason());
        ResourcePolicy.Decision kgeDecision = ResourcePolicy.classify(project, "graph_embeddings", object("action", "train")
                .put("algorithm", "TRANSE").put("embedding_dim", 8).put("epochs", 3)
                .put("learning_rate", 0.05).put("knowledgeBase", KNOWLEDGE_BASE));
        assertEquals("low", kgeDecision.resourceClass(), kgeDecision.reason());
    }

    private void seedDeclaredOntology(Path project) throws Exception {
        Path graph = project.resolve("data/crawls").resolve(KNOWLEDGE_BASE).resolve(LocalProjectGraphBackend.GRAPH_FILE);
        Files.createDirectories(graph.getParent());
        new UnifiedGraph().graphId("local:kompile:" + KNOWLEDGE_BASE)
                .putArtifactText("ontology.ttl", ONTOLOGY).saveCompact(graph);
    }

    private void createImageOnlyPdf(Path target) throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            addRasterPage(pdf, 1, PAGE_ONE);
            addRasterPage(pdf, 2, PAGE_TWO);
            pdf.save(target.toFile());
        }
        try (PDDocument check = Loader.loadPDF(target.toFile())) {
            assertEquals(2, check.getNumberOfPages());
            assertTrue(new PDFTextStripper().getText(check).trim().isEmpty());
        }
    }

    private static void addRasterPage(PDDocument pdf, int pageNumber, String sentence) throws Exception {
        PDPage page = new PDPage(PDRectangle.LETTER);
        pdf.addPage(page);
        BufferedImage raster = new BufferedImage(1000, 1300, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = raster.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, raster.getWidth(), raster.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 44));
            graphics.drawString("CONTAINMENT MAP - PAGE " + pageNumber, 70, 160);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 38));
            graphics.drawString(sentence, 70, 330);
        } finally {
            graphics.dispose();
        }
        PDImageXObject image = LosslessFactory.createFromImage(pdf, raster);
        try (PDPageContentStream stream = new PDPageContentStream(pdf, page)) {
            stream.drawImage(image, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
        }
        raster.flush();
    }

    private static ObjectNode object(String key, String value) {
        return new ObjectMapper().createObjectNode().put(key, value);
    }

    private JsonNode call(McpStdioClient client, String name, ObjectNode request) throws Exception {
        JsonNode response = callTool(client, name, request);
        assertToolSuccess(response);
        return response;
    }

    private JsonNode callTool(McpStdioClient client, String name, ObjectNode request) throws Exception {
        return client.callTool(name, request);
    }

    private JsonNode structured(JsonNode response) throws Exception {
        JsonNode structured = response.path("structuredContent");
        if (structured.path("output").isTextual()) {
            String output = structured.path("output").asText().trim();
            if (output.startsWith("{") || output.startsWith("[")) {
                try {
                    return mapper.readTree(output);
                } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                    // Some MCP tools intentionally return Markdown/text evidence in output.
                }
            }
        }
        return structured.isObject() ? structured : response;
    }

    private String entityId(JsonNode response, String label) throws Exception {
        for (JsonNode entity : structured(response).path("entities")) {
            if (label.equalsIgnoreCase(entity.path("label").asText())) return entity.path("id").asText();
        }
        String text = response.toPrettyString();
        String marker = "**" + label + "**";
        for (String line : toolText(response).split("\\R")) {
            int markerIndex = line.indexOf(marker);
            int idIndex = line.indexOf("id: `", Math.max(0, markerIndex));
            if (markerIndex >= 0 && idIndex >= 0) {
                int end = line.indexOf('`', idIndex + 5);
                if (end > idIndex) return line.substring(idIndex + 5, end);
            }
        }
        int index = text.indexOf("id: `");
        if (index >= 0) {
            int end = text.indexOf('`', index + 5);
            if (end > index) return text.substring(index + 5, end);
        }
        throw new AssertionError("Missing graph_search entity " + label + ": " + response);
    }

    private static McpBundleToolLoader.RemoteTool tool(List<McpBundleToolLoader.RemoteTool> tools, String name) {
        return tools.stream().filter(candidate -> name.equals(candidate.name())).findFirst().orElse(null);
    }

    private static String toolText(JsonNode result) {
        StringBuilder text = new StringBuilder();
        for (JsonNode content : result.path("content")) {
            if ("text".equals(content.path("type").asText())) {
                if (!text.isEmpty()) text.append('\n');
                text.append(content.path("text").asText());
            }
        }
        return text.toString();
    }

    private static void assertToolSuccess(JsonNode result) {
        assertFalse(result.path("isError").asBoolean(), result.toPrettyString());
    }
}
