/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.KnowledgeGraphTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.project.NativeChatModels;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlOntology;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlRlReasoner;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlRlResult;
import ai.kompile.graph.reasoning.mebn.type.owl.TableMemberOntologyBridge;
import ai.kompile.graph.reasoning.model.GraphRelation;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in, bounded native Codex PDF/OCR integration proof. The PDF has no text layer: two raster
 * pages provide the only source facts, while a graph-resident OWL transitive-property declaration
 * makes the cross-page containment conclusion an actual engine inference rather than an extracted edge.
 */
@Timeout(600)
class ChatModelPdfOwlTransitiveLiveIT {
    private static final String ENABLE_PROPERTY = "kompile.remote.chat.pdf.live";
    private static final String CONFIG_ROOT_PROPERTY = "kompile.remote.chat.configRoot";
    private static final String MODEL = "gpt-5.6-luna";
    private static final String THINKING = "xhigh";
    private static final String KNOWLEDGE_BASE = "codex-luna-pdf-owl-live-it";
    private static final String ARCHIVE = "Archive Vault";
    private static final String BUILDING = "Records Building";
    private static final String REGION = "North Region";
    private static final String RELATION = "PART_OF";
    private static final String PAGE_ONE_SENTENCE = "Archive Vault is part of Records Building.";
    private static final String PAGE_TWO_SENTENCE = "Records Building is part of North Region.";

    private static final String OCR_PROMPT = "Read each supplied PDF page image and return faithful OCR in Markdown. "
            + "Preserve the visible wording, punctuation, and page boundaries exactly. Do not infer, summarize, "
            + "normalize, or invent content.";

    private static final String GRAPH_PROMPT = "Extract only facts explicitly supported by the OCR text. Use only the "
            + "node and relationship types declared by the supplied schema. Do not infer transitive, implicit, or "
            + "world-knowledge facts. Do not emit a properties object; source/page provenance is supplied by the "
            + "crawl engine. Return structured facts only, with no explanatory prose.";

    private static final String DECLARED_ONTOLOGY = """
            @prefix ex: <urn:kompile:pdf-owl-live-it#> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            ex:PART_OF a owl:ObjectProperty , owl:TransitiveProperty .
            """;

    @TempDir
    Path projectRoot;

    @Test
    void nativeCodexPdfOcrFeedsTwoPageFactsIntoTransitiveOwlInference() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(ENABLE_PROPERTY),
                "Native Codex PDF crawl is opt-in; pass -D" + ENABLE_PROPERTY + "=true");
        Instant startedAt = Instant.now();

        ChatConfig configured = requireCodexConfiguration();
        configureRequestScopedCodex(configured);

        Path pdf = projectRoot.resolve("image-only-containment.pdf");
        createImageOnlyPdf(pdf);
        seedDeclaredOntology();

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ToolContext context = context(mapper);
        CrawlDocumentsTool crawlDocuments = new CrawlDocumentsTool((String) null, mapper);
        CrawlControlTool crawlControl = new CrawlControlTool((String) null, mapper);
        CrawlResultTool crawlResult = new CrawlResultTool((String) null, mapper);
        String jobId = null;

        try {
            ToolResult accepted = crawlDocuments.execute(crawlRequest(mapper, pdf), context);
            assertFalse(accepted.isError(), accepted.getOutput());
            assertEquals("QUEUED", accepted.getMetadata().get("status"), accepted.getOutput());
            jobId = String.valueOf(accepted.getMetadata().get("jobId"));
            assertFalse(jobId.isBlank(), accepted.getOutput());

            ToolResult terminal = awaitTerminal(crawlControl, context, mapper, jobId,
                    Duration.ofMinutes(9));
            assertFalse(terminal.isError(), terminal.getOutput());
            assertEquals(Boolean.TRUE, terminal.getMetadata().get("terminal"), terminal.getOutput());

            // Read the durable crawl result before asserting the happy-path status. A terminal
            // COMPLETED_WITH_ERRORS result carries the actual semantic extraction diagnostics in
            // crawl_result (including per-chunk retry errors), while crawl_control status only
            // exposes the lifecycle envelope. Keeping this read before the failure assertion
            // preserves the evidence in Surefire output before @TempDir cleanup.
            ToolResult result = crawlResult.execute(
                    mapper.createObjectNode().put("jobId", jobId), context);
            ToolResult transcript = null;
            if (!"COMPLETED".equals(result.getMetadata().get("status"))) {
                transcript = crawlControl.execute(
                        mapper.createObjectNode().put("operation", "transcript")
                                .put("jobId", jobId), context);
            }
            String failureEvidence = "crawl_control terminal:\n" + terminal.getOutput()
                    + "\ncrawl_result:\n" + result.getOutput()
                    + "\nmetadata:\n" + result.getMetadata()
                    + (transcript == null ? "" : "\ncrawl_transcript:\n" + transcript.getOutput());
            assertFalse(result.isError(), failureEvidence);
            assertEquals("COMPLETED", result.getMetadata().get("status"), failureEvidence);
            assertEquals("COMPLETED", terminal.getMetadata().get("status"), failureEvidence);
            assertEquals(1, ((Number) result.getMetadata().get("documentCount")).intValue());
            assertTrue(((Number) result.getMetadata().get("chunkCount")).intValue() > 0,
                    result.getMetadata().toString());
            assertEquals(Boolean.TRUE, result.getMetadata().get("reasoningLearningEnabled"),
                    result.getMetadata().toString());
            assertEquals(Boolean.TRUE, result.getMetadata().get("folPslLearned"),
                    result.getMetadata().toString());
            assertEquals(Boolean.TRUE, result.getMetadata().get("mebnLearned"),
                    result.getMetadata().toString());

            Path crawlDirectory = projectRoot.resolve("data/crawls").resolve(KNOWLEDGE_BASE);
            Path graphPath = crawlDirectory.resolve(LocalProjectGraphBackend.GRAPH_FILE);
            assertTrue(Files.isRegularFile(graphPath), graphPath.toString());
            String chunks = Files.readString(crawlDirectory.resolve("chunks.jsonl"), StandardCharsets.UTF_8);
            assertTrue(chunks.contains(ARCHIVE), chunks);
            assertTrue(chunks.contains(BUILDING), chunks);
            assertTrue(chunks.contains(REGION), chunks);
            assertTrue(chunks.contains(PAGE_ONE_SENTENCE), chunks);
            assertTrue(chunks.contains(PAGE_TWO_SENTENCE), chunks);
            assertTrue(chunks.contains("Pages 1"), chunks);
            assertTrue(chunks.contains("Pages 2"), chunks);

            String persistedRequest = Files.readString(
                    crawlDirectory.resolve("mcp-request.json"), StandardCharsets.UTF_8);
            assertTrue(persistedRequest.contains(MODEL), persistedRequest);
            assertTrue(persistedRequest.contains(THINKING), persistedRequest);
            assertTrue(persistedRequest.contains("pdfRenderDpi"), persistedRequest);
            assertTrue(persistedRequest.contains("pageBatchSize"), persistedRequest);
            assertFalse(persistedRequest.contains("apiKey"), persistedRequest);
            assertFalse(persistedRequest.contains("Authorization"), persistedRequest);

            UnifiedGraph graph = UnifiedGraph.load(graphPath);
            assertEquals(DECLARED_ONTOLOGY.trim(), graph.artifactText("ontology.ttl").trim());
            String archiveId = entityId(graph, ARCHIVE);
            String buildingId = entityId(graph, BUILDING);
            String regionId = entityId(graph, REGION);

            List<GraphRelation> directPartOf = graph.relations().stream()
                    .filter(relation -> RELATION.equalsIgnoreCase(relation.type()))
                    .toList();
            assertTrue(directPartOf.stream().anyMatch(relation ->
                    relation.sourceId().equals(archiveId) && relation.targetId().equals(buildingId)),
                    directPartOf.toString());
            assertTrue(directPartOf.stream().anyMatch(relation ->
                    relation.sourceId().equals(buildingId) && relation.targetId().equals(regionId)),
                    directPartOf.toString());
            assertFalse(directPartOf.stream().anyMatch(relation ->
                    relation.sourceId().equals(archiveId) && relation.targetId().equals(regionId)),
                    "the conclusion must not be directly extracted: " + directPartOf);
            assertTrue(directPartOf.stream().allMatch(relation ->
                    "unified-corpus-extraction".equals(relation.attributes().get("provenance"))),
                    directPartOf.toString());

            OwlOntology ontology = TableMemberOntologyBridge.declaredOntology(graph).orElseThrow();
            assertTrue(ontology.objectProperties().values().stream().anyMatch(property ->
                    RELATION.equals(property.localName()) && property.isTransitive()),
                    ontology.objectProperties().toString());
            OwlRlResult owl = new OwlRlReasoner().reason(graph, ontology);
            boolean owlInferredTransitiveEdge = owl.inferredRelations().stream().anyMatch(relation ->
                    RELATION.equalsIgnoreCase(relation.type())
                            && relation.sourceId().equals(archiveId)
                            && relation.targetId().equals(regionId));
            assertTrue(owlInferredTransitiveEdge,
                    "OWL transitive closure did not infer the two-hop conclusion: "
                            + owl.inferredRelations());

            KnowledgeGraphTool knowledgeGraph = new KnowledgeGraphTool(null, mapper);
            ToolResult owlSnapshot = knowledgeGraph.execute(
                    mapper.createObjectNode().put("action", "owl_reasoning")
                            .put("knowledgeBase", KNOWLEDGE_BASE), context);
            assertFalse(owlSnapshot.isError(), owlSnapshot.getOutput());
            JsonNode owlJson = mapper.readTree(owlSnapshot.getOutput());
            assertEquals("declared-artifact", owlJson.path("ontologySource").asText());
            assertTrue(owlJson.path("inferredRelationCount").asInt() >= 1, owlJson.toString());

            GraphReasoningQueryTool graphReasoning = new GraphReasoningQueryTool((String) null, mapper);
            ToolResult path = graphReasoning.execute(
                    mapper.createObjectNode().put("operation", "PATH")
                            .put("entityId", archiveId).put("targetId", regionId)
                            .put("knowledgeBase", KNOWLEDGE_BASE), context);
            assertFalse(path.isError(), path.getOutput());
            assertTrue(path.getOutput().contains(BUILDING), path.getOutput());
            assertTrue(path.getOutput().contains(ARCHIVE), path.getOutput());
            assertTrue(path.getOutput().contains(REGION), path.getOutput());

            long inferredTransitiveEdgeCount = owl.inferredRelations().stream()
                    .filter(relation -> RELATION.equalsIgnoreCase(relation.type())
                            && relation.sourceId().equals(archiveId)
                            && relation.targetId().equals(regionId))
                    .count();
            String pathEvidence = path.getOutput().replaceAll("\\s+", " ").trim();
            System.out.println("[pdf-owl-live] OCR_FACTS page=1 provenance=Pages 1 sentence=\""
                    + PAGE_ONE_SENTENCE + "\"; page=2 provenance=Pages 2 sentence=\""
                    + PAGE_TWO_SENTENCE + "\"");
            System.out.println("[pdf-owl-live] DIRECT_FACTS count=" + directPartOf.size()
                    + " provenance=unified-corpus-extraction facts=" + directPartOf);
            System.out.println("[pdf-owl-live] OWL_DERIVED edge=" + ARCHIVE + " -" + RELATION + "-> "
                    + REGION + " rule=declared-artifact-transitive-not-learned count="
                    + inferredTransitiveEdgeCount + " asserted=" + owlInferredTransitiveEdge);
            System.out.println("[pdf-owl-live] TOOL_PATH evidence=" + pathEvidence);
            System.out.println("[pdf-owl-live] SUMMARY documents="
                    + result.getMetadata().get("documentCount") + " chunks="
                    + result.getMetadata().get("chunkCount") + " durationMs="
                    + Duration.between(startedAt, Instant.now()).toMillis()
                    + " ontologySource=declared-artifact");
        } catch (Throwable failure) {
            try {
                cancelIfActive(crawlControl, context, mapper, jobId);
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            if (failure instanceof Exception exception) throw exception;
            if (failure instanceof Error error) throw error;
            throw new AssertionError(failure);
        }
        cancelIfActive(crawlControl, context, mapper, jobId);
    }

    private ChatConfig requireCodexConfiguration() {
        Path configRoot = Path.of(System.getProperty(
                CONFIG_ROOT_PROPERTY, System.getProperty("user.dir")))
                .toAbsolutePath().normalize();
        ChatConfig configured = ChatConfig.loadOrFromEnv(configRoot);
        assertNotNull(configured,
                "Native Codex configuration is missing; configure openai-codex and retry");
        assertEquals("openai-codex", NativeChatModels.normalizeProvider(configured.getProvider()));
        assertEquals("standard", configured.getChatMode());
        assertFalse(configured.isKompileLocalServing());
        assertFalse(configured.isKompileServer());
        assertTrue(configured.isValid(), "Native Codex authentication/configuration is unavailable");
        return configured;
    }

    private void configureRequestScopedCodex(ChatConfig configured) throws Exception {
        ChatConfig scoped = new ChatConfig(configured.getProvider(), null, MODEL, configured.getBaseUrl());
        scoped.setAuthenticationMethod(configured.getAuthenticationMethod());
        scoped.setThinking(THINKING);
        scoped.setChatMode("standard");
        scoped.setApiKey(null);
        scoped.saveProject(projectRoot);
        ChatConfig saved = ChatConfig.loadProject(projectRoot);
        assertNotNull(saved);
        assertEquals("openai-codex", NativeChatModels.normalizeProvider(saved.getProvider()));
        assertEquals(MODEL, saved.getModel());
        assertEquals(THINKING, saved.getThinking());
        String persisted = Files.readString(ChatConfig.projectConfigPath(projectRoot), StandardCharsets.UTF_8);
        assertFalse(persisted.contains("apiKey"), persisted);
    }

    private ObjectNode crawlRequest(ObjectMapper mapper, Path pdf) {
        ObjectNode request = mapper.createObjectNode();
        request.put("name", "Native Codex image-only PDF OWL closure");
        request.put("async", true);
        request.put("deriveOntology", true);
        request.put("maxValidationRetries", 0);
        request.putObject("knowledgeBase").put("name", KNOWLEDGE_BASE);
        request.putArray("documents").addObject().put("path", pdf.toString()).put("pipelineId", "luna-pdf");

        ObjectNode pipeline = request.putArray("pipelines").addObject()
                .put("pipelineId", "luna-pdf")
                .put("pipelineType", "VLM")
                .put("loaderName", "auto")
                .put("chunkerName", "no-op");
        pipeline.putObject("processor")
                .put("type", "CHAT_MODEL")
                .put("provider", "openai-codex")
                .put("modelId", MODEL)
                .put("thinking", THINKING);
        pipeline.putObject("options")
                .put("provider", "openai-codex")
                .put("modelId", MODEL)
                .put("thinking", THINKING)
                .put("prompt", OCR_PROMPT)
                .put("outputFormat", "MARKDOWN")
                .put("maxPages", 2)
                .put("pageBatchSize", 1)
                .put("pdfRenderDpi", 120)
                .put("maxResponseChars", 20_000);

        request.putArray("steps").add("GRAPH_EXTRACTION").add("ENTITY_RESOLUTION").add("ENRICHMENT");
        request.putObject("embeddingTraining").put("enabled", false);
        request.putObject("reasoningLearning")
                .put("enabled", true)
                .put("pslSteps", 1)
                .put("mebnEpochs", 1)
                .put("consensusRounds", 1)
                .put("consensusWeight", 0.35)
                .put("maxRelationTypes", 8);
        request.putObject("runtimeConfig")
                .put("runReasoningLearning", true)
                .put("graphExtractionParallelism", 1)
                .put("graphExtractionRemoteParallelism", 1)
                .put("llmCallTimeoutSeconds", 120)
                .put("graphExtractionBatchTimeoutSeconds", 300);

        ObjectNode extraction = request.putObject("graphExtraction")
                .put("llmProvider", "chat:openai-codex")
                .put("modelName", MODEL)
                .put("thinking", THINKING)
                .put("extractionMode", "SINGLE_PASS")
                .put("entityResolution", true)
                .put("minConfidence", 0.0)
                .put("schemaMode", "STRICT")
                .put("customPrompt", GRAPH_PROMPT);
        extraction.putArray("entityTypes").add("ARCHIVE").add("BUILDING").add("REGION");
        extraction.putArray("relationshipTypes").add(RELATION);
        ObjectNode schema = extraction.putObject("standardizedSchema");
        schema.putArray("nodeTypes").addObject().put("label", "ARCHIVE")
                .put("description", "A named archive or vault.").put("parentType", "LOCATION");
        schema.withArray("nodeTypes").addObject().put("label", "BUILDING")
                .put("description", "A named building that can contain records.").put("parentType", "LOCATION");
        schema.withArray("nodeTypes").addObject().put("label", "REGION")
                .put("description", "A named geographic region.").put("parentType", "LOCATION");
        schema.putArray("relationshipTypes").addObject()
                .put("type", RELATION)
                .put("description", "An explicit child-to-container relation.")
                .put("connectionFamily", "PART_WHOLE");
        schema.putArray("patterns")
                .add("(ARCHIVE)-[:PART_OF]->(BUILDING)")
                .add("(BUILDING)-[:PART_OF]->(REGION)");
        return request;
    }

    private void seedDeclaredOntology() throws Exception {
        Path graphPath = projectRoot.resolve("data/crawls").resolve(KNOWLEDGE_BASE)
                .resolve(LocalProjectGraphBackend.GRAPH_FILE);
        Files.createDirectories(graphPath.getParent());
        new UnifiedGraph().graphId("local:test:" + KNOWLEDGE_BASE)
                .putArtifactText("ontology.ttl", DECLARED_ONTOLOGY)
                .saveCompact(graphPath);
    }

    private void createImageOnlyPdf(Path target) throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            addRasterPage(pdf, 1, PAGE_ONE_SENTENCE);
            addRasterPage(pdf, 2, PAGE_TWO_SENTENCE);
            pdf.save(target.toFile());
        }
        try (PDDocument check = Loader.loadPDF(target.toFile())) {
            assertEquals(2, check.getNumberOfPages());
            assertTrue(new PDFTextStripper().getText(check).trim().isEmpty(),
                    "fixture must remain image-only with no extractable text layer");
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
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 44));
            graphics.drawString("CONTAINMENT MAP — PAGE " + pageNumber, 70, 160);
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

    private ToolContext context(ObjectMapper mapper) {
        PermissionService permissions = new PermissionService();
        for (String tool : Set.of("crawl_documents", "crawl_control", "crawl_result",
                "knowledge_search", "knowledge_graph", "graph_reasoning_query")) {
            permissions.setUserOverride(tool, PermissionService.PermissionLevel.ALLOW);
        }
        return new ToolContext(
                "codex-luna-pdf-owl-live-it",
                AgentConfig.builder("codex-luna-pdf-owl-live-it").enabledTools(Set.of("*")).build(),
                permissions,
                projectRoot,
                new ToolRegistry(mapper));
    }

    private static String entityId(UnifiedGraph graph, String label) {
        return graph.entities().stream()
                .filter(entity -> label.equalsIgnoreCase(entity.label()))
                .map(entity -> entity.id())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing OCR entity " + label
                        + ": " + graph.entities()));
    }

    private static ToolResult awaitTerminal(CrawlControlTool crawlControl,
                                            ToolContext context,
                                            ObjectMapper mapper,
                                            String jobId,
                                            Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        ToolResult latest = null;
        while (Instant.now().isBefore(deadline)) {
            latest = crawlControl.execute(
                    mapper.createObjectNode().put("operation", "status").put("jobId", jobId),
                    context);
            if (latest.isError() || Boolean.TRUE.equals(latest.getMetadata().get("terminal"))) return latest;
            Thread.sleep(Math.min(1_000L, Math.max(100L,
                    ((Number) latest.getMetadata().getOrDefault("pollAfterMs", 1_000L)).longValue())));
        }
        assertNotNull(latest, "crawl_control returned no status before timeout");
        throw new AssertionError("PDF crawl did not reach terminal state within " + timeout
                + ": " + latest.getOutput());
    }

    private static void cancelIfActive(CrawlControlTool crawlControl,
                                       ToolContext context,
                                       ObjectMapper mapper,
                                       String jobId) throws Exception {
        if (jobId == null || jobId.isBlank()) return;
        ToolResult status = crawlControl.execute(
                mapper.createObjectNode().put("operation", "status").put("jobId", jobId), context);
        if (status.isError() || Boolean.TRUE.equals(status.getMetadata().get("terminal"))) return;
        ToolResult cancelled = crawlControl.execute(
                mapper.createObjectNode().put("operation", "cancel").put("jobId", jobId), context);
        if (cancelled.isError()) throw new AssertionError("cleanup cancellation failed: " + cancelled.getOutput());
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            ToolResult finalStatus = crawlControl.execute(
                    mapper.createObjectNode().put("operation", "status").put("jobId", jobId), context);
            if (finalStatus.isError() || Boolean.TRUE.equals(finalStatus.getMetadata().get("terminal"))) return;
            Thread.sleep(250L);
        }
        throw new AssertionError("cleanup cancellation did not reach terminal state for job " + jobId);
    }
}
