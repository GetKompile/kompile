/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.KnowledgeSearchCliTool;
import ai.kompile.cli.main.chat.tools.KnowledgeStatusCliTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalProjectCrawlBackendTest {
    @TempDir
    Path projectRoot;

    private ObjectMapper mapper;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        AgentConfig agent = AgentConfig.builder("offline-worker")
                .enabledTools(Set.of("*"))
                .build();
        PermissionService permissions = new PermissionService();
        for (String tool : Set.of(
                "crawl_documents",
                "crawl_discover",
                "crawl_source",
                "crawl_control",
                "knowledge_search",
                "knowledge_status",
                "ask_graph_assert",
                "ask_graph_verify",
                "external_directory")) {
            permissions.setUserOverride(tool, PermissionService.PermissionLevel.ALLOW);
        }
        context = new ToolContext("offline-crawl-test", agent, permissions, projectRoot,
                new ToolRegistry(mapper));
    }

    @Test
    void archiveLearningReportsNonArchivableTargetWithoutDisablingRun() throws Exception {
        Files.writeString(projectRoot.resolve("archive-alias.md"),
                "# Archive alias\nThe local archive warning must remain accurate.\n",
                StandardCharsets.UTF_8);

        ObjectNode request = mapper.createObjectNode()
                .put("dryRun", true)
                .put("async", false);
        request.putArray("documents").addObject().put("path", "archive-alias.md");
        request.putObject("knowledgeBase").put("id", 45);
        request.putArray("archivedSteps").add("LEARNING");

        ToolResult result = new CrawlDocumentsTool((String) null, mapper)
                .execute(request, context);

        assertFalse(result.isError(), result.getOutput());
        Object rawWarnings = result.getMetadata().get("warnings");
        assertTrue(rawWarnings instanceof List<?>);
        String warnings = rawWarnings.toString();
        assertTrue(warnings.contains("ENRICHMENT"), warnings);
        assertTrue(warnings.contains("not archivable locally"), warnings);
        assertFalse(warnings.contains("ignored distributed-only configuration"), warnings);
    }

    @Test
    void emptyRequestBootstrapsAndSearchesTheCurrentFolder() throws Exception {
        Files.writeString(projectRoot.resolve("folder-note.md"),
                "The folder bootstrap contains the silver osprey marker.\n",
                StandardCharsets.UTF_8);

        CrawlDocumentsTool crawl = new CrawlDocumentsTool((String) null, mapper);
        assertFalse(crawl.parameterSchema().has("anyOf"),
                "The local folder bootstrap must not require documents or codeProjects selectors.");
        JsonNode runtimeSchema = crawl.parameterSchema().path("properties")
                .path("modelRuntime").path("properties");
        assertFalse(runtimeSchema.has("autoBootstrap"));
        assertFalse(runtimeSchema.has("stagingExecutable"));
        assertFalse(runtimeSchema.has("stagingJar"));

        ToolResult bootstrapped = crawl.execute(mapper.createObjectNode().put("async", false), context);

        assertFalse(bootstrapped.isError(), bootstrapped.getOutput());
        assertEquals("project-local", bootstrapped.getMetadata().get("backend"));
        String knowledgeBase = (String) bootstrapped.getMetadata().get("knowledgeBase");
        assertFalse(knowledgeBase.isBlank());
        assertFalse(bootstrapped.getMetadata().containsKey("factSheetId"));
        JsonNode projectManifest = mapper.readTree(projectRoot.resolve("kompile.project.json").toFile());
        JsonNode directoryProject = projectManifest.path("codingProjects").get(0);
        assertEquals(projectRoot.toAbsolutePath().normalize().toString(),
                directoryProject.path("rootPath").asText());
        assertTrue(Files.isRegularFile(projectRoot.resolve(
                directoryProject.path("metadataPath").asText()).resolve("project.json")));
        assertTrue(Files.isRegularFile(projectRoot.resolve(
                directoryProject.path("metadataPath").asText()).resolve("index-plan.json")));
        assertTrue(knowledgeBase.startsWith(directoryProject.path("codeProjectId").asText()));
        assertTrue(Files.isRegularFile(projectRoot.resolve("data/crawls")
                .resolve(knowledgeBase).resolve(LocalProjectGraphBackend.GRAPH_FILE)));

        ObjectNode searchParams = mapper.createObjectNode().put("query", "silver osprey");
        searchParams.put("topic", "bootstrap behavior");
        ToolResult search = new KnowledgeSearchCliTool((String) null, mapper)
                .execute(searchParams, context);
        assertFalse(search.isError(), search.getOutput());
        assertTrue(search.getOutput().contains("folder-note.md"), search.getOutput());

        ToolResult status = new KnowledgeStatusCliTool((String) null, mapper)
                .execute(mapper.createObjectNode(), context);
        assertFalse(status.isError(), status.getOutput());
        assertEquals(1, ((Number) status.getMetadata().get("knowledgeBaseCount")).intValue());

        ObjectNode assertion = mapper.createObjectNode()
                .put("atom", "containsMarker(folderBootstrap, silverOsprey)")
                .put("value", 0.9);
        ToolResult asserted = new AskGraphAssertTool((String) null, mapper)
                .execute(assertion, context);
        assertFalse(asserted.isError(), asserted.getOutput());

        ObjectNode verification = mapper.createObjectNode()
                .put("atom", "containsMarker(folderBootstrap, silverOsprey)");
        ToolResult verified = new AskGraphVerifyTool((String) null, mapper)
                .execute(verification, context);
        assertFalse(verified.isError(), verified.getOutput());
    }

    @Test
    void folderBootstrapDoesNotReuseMixedSummaryMetadataAsExecutableComponents()
            throws Exception {
        Files.writeString(projectRoot.resolve("legacy-note.md"),
                "The legacy aggregate crawl contains the bronze petrel marker.\n",
                StandardCharsets.UTF_8);
        ToolResult initial = new CrawlDocumentsTool((String) null, mapper)
                .execute(mapper.createObjectNode().put("async", false), context);
        assertFalse(initial.isError(), initial.getOutput());

        String knowledgeBase = (String) initial.getMetadata().get("knowledgeBase");
        Path crawl = projectRoot.resolve("data/crawls").resolve(knowledgeBase);
        Files.delete(crawl.resolve(LocalProjectGraphBackend.GRAPH_FILE));
        Files.delete(crawl.resolve("mcp-request.json"));
        ObjectNode legacySummary = mapper.createObjectNode()
                .put("profileId", knowledgeBase)
                .put("name", "Legacy aggregate crawl")
                .put("status", "COMPLETED")
                .put("loader", "mixed")
                .put("chunker", "mixed");
        legacySummary.putArray("sources").add(projectRoot.toString());
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                crawl.resolve("crawl-result.json").toFile(), legacySummary);

        ToolResult bootstrapped = new LocalProjectCrawlBackend(mapper)
                .ensureFolderKnowledgeBase(context);

        assertFalse(bootstrapped.isError(), bootstrapped.getOutput());
        assertTrue(Files.isRegularFile(
                crawl.resolve(LocalProjectGraphBackend.GRAPH_FILE)));
    }

    @Test
    void knowledgeStatusSkipsUnsupportedSparseArtifactsDuringFolderBootstrap() throws Exception {
        Files.writeString(projectRoot.resolve("folder-note.md"),
                "The safe folder bootstrap contains the copper kestrel marker.\n",
                StandardCharsets.UTF_8);
        Path sparseBinary = projectRoot.resolve("cached-model.bin");
        try (SeekableByteChannel channel = Files.newByteChannel(sparseBinary,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.position((long) Integer.MAX_VALUE + 1024L);
            channel.write(ByteBuffer.wrap(new byte[]{0}));
        }

        ToolResult status = new KnowledgeStatusCliTool((String) null, mapper)
                .execute(mapper.createObjectNode(), context);

        assertFalse(status.isError(), status.getOutput());
        LocalProjectCrawlBackend backend = new LocalProjectCrawlBackend(mapper);
        String knowledgeBase = backend.defaultKnowledgeBaseId(projectRoot);
        String documents = Files.readString(projectRoot.resolve("data/crawls")
                .resolve(knowledgeBase).resolve("documents.jsonl"));
        assertTrue(documents.contains("folder-note.md"), documents);
        assertFalse(documents.contains("cached-model.bin"), documents);
    }

    @Test
    void knowledgeStatusInventoriesPersistedBasesWithoutRebootstrappingAnIncompleteDefault() throws Exception {
        Path crawl = projectRoot.resolve("data/crawls/kompile-knowledge");
        Files.createDirectories(crawl);
        Files.writeString(crawl.resolve("crawl-result.json"), """
                {
                  "profileId": "kompile-knowledge",
                  "name": "kompile knowledge",
                  "status": "COMPLETED",
                  "sources": ["stale-source.md"],
                  "documentCount": 1,
                  "chunkCount": 1
                }
                """, StandardCharsets.UTF_8);

        ToolResult status = new KnowledgeStatusCliTool((String) null, mapper)
                .execute(mapper.createObjectNode(), context);

        assertFalse(status.isError(), status.getOutput());
        assertEquals(1, ((Number) status.getMetadata().get("knowledgeBaseCount")).intValue());
        assertTrue(status.getOutput().contains("kompile-knowledge"), status.getOutput());
    }

    @Test
    void knowledgeStatusStreamsRecognizedTextLargerThanTheFormerLimit() throws Exception {
        Path large = projectRoot.resolve("large.json");
        try (SeekableByteChannel channel = Files.newByteChannel(large,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            ByteBuffer spaces = ByteBuffer.allocate(64 * 1024);
            while (spaces.hasRemaining()) spaces.put((byte) ' ');
            spaces.flip();
            for (int block = 0; block < 26 * 16; block++) {
                while (spaces.hasRemaining()) channel.write(spaces);
                spaces.rewind();
            }
            ByteBuffer marker = ByteBuffer.wrap("streamed-heron-marker".getBytes(StandardCharsets.UTF_8));
            while (marker.hasRemaining()) channel.write(marker);
        }

        ToolResult result = new KnowledgeStatusCliTool((String) null, mapper)
                .execute(mapper.createObjectNode(), context);

        assertFalse(result.isError(), result.getOutput());
        assertEquals(1, ((Number) result.getMetadata().get("knowledgeBaseCount")).intValue());
        LocalProjectCrawlBackend backend = new LocalProjectCrawlBackend(mapper);
        String knowledgeBase = backend.defaultKnowledgeBaseId(projectRoot);
        Path crawl = projectRoot.resolve("data/crawls").resolve(knowledgeBase);
        String documents = Files.readString(crawl.resolve("documents.jsonl"));
        String chunks = Files.readString(crawl.resolve("chunks.jsonl"));
        assertTrue(documents.contains("\"extractionStatus\":\"EXTRACTED\""), documents);
        assertTrue(documents.contains("large.json"), documents);
        assertTrue(chunks.contains("streamed-heron-marker"), chunks);
    }

    @Test
    void streamingChunksPreserveOverlapAcrossIoBatchBoundaries() throws Exception {
        String marker = "boundary-heron-marker";
        String content = "a".repeat(16 * 1024 - 10) + marker + "b".repeat(16 * 1024);
        Files.writeString(projectRoot.resolve("boundary.txt"), content, StandardCharsets.UTF_8);
        ObjectNode request = documentRequest("boundary.txt", "boundary-kb");
        ObjectNode document = (ObjectNode) request.withArray("documents").get(0);
        document.put("chunkSize", 256);
        document.put("chunkOverlap", 32);

        ToolResult result = new CrawlDocumentsTool((String) null, mapper).execute(request, context);

        assertFalse(result.isError(), result.getOutput());
        boolean foundMarker = false;
        boolean foundOverlap = false;
        long previousEnd = -1;
        try (BufferedReader chunks = Files.newBufferedReader(
                projectRoot.resolve("data/crawls/boundary-kb/chunks.jsonl"), StandardCharsets.UTF_8)) {
            String line;
            while ((line = chunks.readLine()) != null) {
                JsonNode chunk = mapper.readTree(line);
                String text = chunk.path("text").asText();
                assertTrue(text.length() <= 256, "physical chunks must stay bounded");
                long start = chunk.path("start").asLong();
                if (previousEnd >= 0 && start < previousEnd) foundOverlap = true;
                previousEnd = chunk.path("end").asLong();
                if (text.contains(marker)) foundMarker = true;
            }
        }
        assertTrue(foundOverlap, "neighboring chunks should retain configured overlap");
        assertTrue(foundMarker, "a marker split at the input batch edge must remain searchable");
    }

    @Test
    void addsDocumentsToAProjectKnowledgeBaseAndSearchesThemOffline() throws Exception {
        Files.writeString(projectRoot.resolve("alpha.md"),
                "# Alpha\n\nThe Orion protocol stores blue lantern facts.\n",
                StandardCharsets.UTF_8);

        CrawlDocumentsTool crawl = new CrawlDocumentsTool((String) null, mapper);
        ToolResult first = crawl.execute(documentRequest("alpha.md", "project-notes"), context);

        assertFalse(first.isError(), first.getOutput());
        assertEquals("project-local", first.getMetadata().get("backend"));
        assertEquals("COMPLETED", first.getMetadata().get("status"));
        assertEquals(1, ((Number) first.getMetadata().get("documentCount")).intValue());

        Path knowledgeBase = projectRoot.resolve("data/crawls/project-notes");
        assertTrue(Files.isRegularFile(knowledgeBase.resolve("crawl-result.json")));
        assertTrue(Files.isRegularFile(knowledgeBase.resolve("documents.jsonl")));
        assertTrue(Files.isRegularFile(knowledgeBase.resolve("chunks.jsonl")));
        assertTrue(Files.isRegularFile(knowledgeBase.resolve("mcp-request.json")));

        KnowledgeSearchCliTool search = new KnowledgeSearchCliTool((String) null, mapper);
        ObjectNode searchParams = mapper.createObjectNode();
        searchParams.put("query", "blue lantern");
        searchParams.put("knowledgeBase", "project-notes");
        ToolResult searchResult = search.execute(searchParams, context);

        assertFalse(searchResult.isError(), searchResult.getOutput());
        assertEquals("project-local", searchResult.getMetadata().get("backend"));
        assertTrue(searchResult.getOutput().contains("blue lantern"));
        assertTrue(searchResult.getOutput().contains("alpha.md"));

        Files.writeString(projectRoot.resolve("beta.md"),
                "# Beta\n\nThe Vega ledger records amber compass entries.\n",
                StandardCharsets.UTF_8);
        ToolResult second = crawl.execute(documentRequest("beta.md", "project-notes"), context);

        assertFalse(second.isError(), second.getOutput());
        assertEquals(2, ((Number) second.getMetadata().get("documentCount")).intValue());
        String documents = Files.readString(knowledgeBase.resolve("documents.jsonl"));
        assertTrue(documents.contains("alpha.md"));
        assertTrue(documents.contains("beta.md"));

        KnowledgeStatusCliTool status = new KnowledgeStatusCliTool((String) null, mapper);
        ObjectNode statusParams = mapper.createObjectNode();
        statusParams.put("knowledgeBase", "project-notes");
        ToolResult statusResult = status.execute(statusParams, context);

        assertFalse(statusResult.isError(), statusResult.getOutput());
        assertEquals("project-local", statusResult.getMetadata().get("backend"));
        assertTrue(statusResult.getOutput().contains("\"documentCount\" : 2"));
    }

    @Test
    void materializesLocatorFreeExternalSourcesWithoutPathOrUrl() throws Exception {
        // Locator-free identity must pass the exactly-one-of path/url gate with no path or
        // url at all. GDRIVE with fileIds metadata is a fully offline example: dry-run must
        // reach pipeline preview instead of the locator error.
        ObjectNode request = mapper.createObjectNode().put("async", false).put("dryRun", true);
        request.putObject("knowledgeBase").put("name", "locator-free");
        ObjectNode source = request.putArray("documents").addObject();
        source.put("sourceType", "GDRIVE");
        source.putObject("properties").put("accessToken", "test-token").put("fileIds", "f-1");

        ToolResult result = new CrawlDocumentsTool((String) null, mapper).execute(request, context);

        assertFalse(result.isError(), result.getOutput());
        assertFalse(result.getOutput().contains("exactly one of path or url"), result.getOutput());
        int jsonStart = result.getOutput().indexOf('{');
        assertTrue(jsonStart >= 0, result.getOutput());
        JsonNode preview = mapper.readTree(result.getOutput().substring(jsonStart));
        assertEquals("DRY_RUN", preview.path("status").asText(null));
    }

    @Test
    void materializesTypedConfluenceExportsLocallyWithoutPersistingCredentials() throws Exception {
        Path export = Files.createDirectories(projectRoot.resolve("confluence-export"));
        Files.writeString(export.resolve("roadmap.html"),
                "<html><head><title>Roadmap</title></head><body>local-confluence-puffin-marker</body></html>",
                StandardCharsets.UTF_8);
        ObjectNode request = mapper.createObjectNode().put("async", false);
        request.putObject("knowledgeBase").put("name", "external-local");
        ObjectNode source = request.putArray("documents").addObject();
        source.put("path", export.toAbsolutePath().toString());
        source.put("sourceType", "CONFLUENCE");
        source.putObject("properties")
                .put("apiToken", "must-not-persist")
                .put("spaceKey", "LOCAL");

        ToolResult result = new CrawlDocumentsTool((String) null, mapper).execute(request, context);

        assertFalse(result.isError(), result.getOutput());
        String knowledgeBase = (String) result.getMetadata().get("knowledgeBase");
        Path crawl = projectRoot.resolve("data/crawls").resolve(knowledgeBase);
        String persisted = Files.readString(crawl.resolve("mcp-request.json"));
        assertFalse(persisted.contains("must-not-persist"), persisted);
        assertTrue(persisted.contains("externalSourceType"), persisted);
        String documents = Files.readString(crawl.resolve("documents.jsonl"));
        assertTrue(documents.contains("confluence.exportPath"), documents);
        assertFalse(documents.contains("must-not-persist"), documents);
        ObjectNode searchParams = mapper.createObjectNode()
                .put("query", "local-confluence-puffin-marker")
                .put("knowledgeBase", knowledgeBase);
        ToolResult search = new KnowledgeSearchCliTool((String) null, mapper)
                .execute(searchParams, context);
        assertFalse(search.isError(), search.getOutput());
        assertTrue(search.getOutput().contains("local-confluence-puffin-marker"), search.getOutput());
    }

    @Test
    void indexesTheCurrentCodeProjectWithoutAProjectManifest() throws Exception {
        Path source = projectRoot.resolve("src/main/java/example/LocalAnswer.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package example;

                final class LocalAnswer {
                    static final String TOKEN = "quartz narwhal invariant";
                }
                """, StandardCharsets.UTF_8);

        ObjectNode request = mapper.createObjectNode();
        request.put("async", false);
        request.putArray("codeProjects").add("*");
        request.putObject("knowledgeBase").put("name", "source-code");

        ToolResult result = new CrawlDocumentsTool((String) null, mapper).execute(request, context);

        assertFalse(result.isError(), result.getOutput());
        assertEquals("project-local", result.getMetadata().get("backend"));
        assertTrue(((Number) result.getMetadata().get("documentCount")).intValue() >= 1);

        ObjectNode searchParams = mapper.createObjectNode();
        searchParams.put("query", "quartz narwhal");
        searchParams.put("knowledgeBase", "source-code");
        ToolResult search = new KnowledgeSearchCliTool((String) null, mapper)
                .execute(searchParams, context);

        assertFalse(search.isError(), search.getOutput());
        assertTrue(search.getOutput().contains("LocalAnswer.java"), search.getOutput());
    }

    @Test
    void explicitDataDocumentsRemainIncludedWithTheCodeProject() throws Exception {
        Path document = projectRoot.resolve("data/input_documents/operator-note.md");
        Files.createDirectories(document.getParent());
        Files.writeString(document,
                "The explicit project document contains the violet albatross procedure.\n",
                StandardCharsets.UTF_8);
        Path source = projectRoot.resolve("src/Worker.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "final class Worker {}\n", StandardCharsets.UTF_8);

        ObjectNode request = documentRequest("data/input_documents/operator-note.md", "mixed-kb");
        request.putArray("codeProjects").add("*");
        ToolResult crawl = new CrawlDocumentsTool((String) null, mapper).execute(request, context);

        assertFalse(crawl.isError(), crawl.getOutput());

        ObjectNode searchParams = mapper.createObjectNode();
        searchParams.put("query", "violet albatross");
        searchParams.put("knowledgeBase", "mixed-kb");
        ToolResult search = new KnowledgeSearchCliTool((String) null, mapper)
                .execute(searchParams, context);

        assertFalse(search.isError(), search.getOutput());
        assertTrue(search.getOutput().contains("operator-note.md"), search.getOutput());
    }

    @Test
    void indexesInlineKnowledgeThroughCrawlSource() throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("async", false);
        params.put("text", "The cobalt kestrel policy expires after seven rotations.");
        params.put("title", "Operational note");
        params.put("factSheetId", 23);

        ToolResult result = new CrawlSourceTool((String) null, mapper).execute(params, context);

        assertFalse(result.isError(), result.getOutput());
        assertEquals("project-local", result.getMetadata().get("backend"));
        assertTrue(Files.isRegularFile(projectRoot.resolve("data/crawls/kb-23/chunks.jsonl")));

        ObjectNode searchParams = mapper.createObjectNode();
        searchParams.put("query", "cobalt kestrel");
        searchParams.put("knowledgeBase", "23");
        ToolResult search = new KnowledgeSearchCliTool((String) null, mapper)
                .execute(searchParams, context);

        assertFalse(search.isError(), search.getOutput());
        assertTrue(search.getOutput().contains("cobalt kestrel"));
    }

    @Test
    void crawlControlReportsSynchronousLocalLifecycle() throws Exception {
        Files.writeString(projectRoot.resolve("lifecycle.md"),
                "Local lifecycle evidence uses a silver heron marker.\n",
                StandardCharsets.UTF_8);
        ToolResult crawl = new CrawlDocumentsTool((String) null, mapper)
                .execute(documentRequest("lifecycle.md", "lifecycle-kb"), context);
        assertFalse(crawl.isError(), crawl.getOutput());

        ObjectNode statusParams = mapper.createObjectNode();
        statusParams.put("operation", "status");
        statusParams.put("jobId", "lifecycle-kb");
        ToolResult status = new CrawlControlTool((String) null, mapper)
                .execute(statusParams, context);

        assertFalse(status.isError(), status.getOutput());
        assertEquals("project-local", status.getMetadata().get("backend"));
        assertTrue(status.getOutput().contains("\"status\" : \"COMPLETED\""));

        ObjectNode cancelParams = mapper.createObjectNode();
        cancelParams.put("operation", "cancel");
        cancelParams.put("jobId", "lifecycle-kb");
        ToolResult cancel = new CrawlControlTool((String) null, mapper)
                .execute(cancelParams, context);

        assertTrue(cancel.isError());
        assertTrue(cancel.getOutput().contains("synchronous project-local"));
        assertTrue(cancel.getOutput().contains("distributed"));
    }

    @Test
    void discoveryExplainsLocalCapabilitiesAndDistributedBoundaries() throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("section", "all");

        ToolResult result = new CrawlDiscoveryTool((String) null, mapper).execute(params, context);

        assertFalse(result.isError(), result.getOutput());
        assertEquals("project-local", result.getMetadata().get("backend"));
        assertTrue(result.getOutput().contains("\"CODE\""));
        assertTrue(result.getOutput().contains("\"VLM\""));
        assertTrue(result.getOutput().contains("\"OCR\""));
        assertTrue(result.getOutput().contains("\"TABLE_AWARE\""));
        assertTrue(result.getOutput().contains("\"KEYWORD_ONLY\""));
        assertFalse(result.getOutput().contains("documentModelWorker"));
        assertTrue(result.getOutput().contains("pooled model-runtime children"));
        assertTrue(result.getOutput().contains("pipelineTemplates"));
        assertTrue(result.getOutput().contains("\"id\" : \"ENRICHMENT\""), result.getOutput());
        assertTrue(result.getOutput().contains("UnifiedGraphReasoningLifecycle"), result.getOutput());
        assertFalse(result.getOutput().contains("ENRICHMENT requires the model-backed distributed crawl manager"),
                result.getOutput());
        assertTrue(result.getOutput().contains("recursive-character"));
        assertTrue(result.getOutput().contains("\"pdf\""));
        assertTrue(result.getOutput().contains("subprocess"));
        assertTrue(result.getOutput().contains("memory"));
        assertTrue(result.getOutput().contains("pipelineModelReadiness"));
        assertTrue(result.getOutput().contains("distributed"));
    }

    @Test
    void executesASelectedLoaderAndChunkerPipelineInTheLocalWorker() throws Exception {
        Files.writeString(projectRoot.resolve("configured.md"),
                "# Configured note\n\nThe copper osprey marker remains one whole document.\n",
                StandardCharsets.UTF_8);

        ObjectNode request = documentRequest("configured.md", "configured-kb");
        ((ObjectNode) request.withArray("documents").get(0)).put("pipelineId", "whole-note");
        request.putArray("pipelines").addObject()
                .put("pipelineId", "whole-note")
                .put("pipelineType", "STANDARD_TEXT")
                .put("loaderName", "markdown")
                .put("chunkerName", "no-op");

        ToolResult result = new CrawlDocumentsTool((String) null, mapper).execute(request, context);

        assertFalse(result.isError(), result.getOutput());
        assertEquals("mcp-host", result.getMetadata().get("executionMode"));
        Path knowledgeBase = projectRoot.resolve("data/crawls/configured-kb");
        String documents = Files.readString(knowledgeBase.resolve("documents.jsonl"));
        String chunks = Files.readString(knowledgeBase.resolve("chunks.jsonl"));
        assertTrue(documents.contains("\"pipelineId\":\"whole-note\""), documents);
        assertTrue(documents.contains("\"loader\":\"markdown\""), documents);
        assertTrue(documents.contains("\"chunker\":\"no-op\""), documents);
        assertTrue(chunks.contains("\"pipelineId\":\"whole-note\""), chunks);
        assertTrue(chunks.contains("\"pipelineType\":\"STANDARD_TEXT\""), chunks);
        assertTrue(chunks.contains("\"loader\":\"markdown\""), chunks);
        assertTrue(chunks.contains("\"chunker\":\"no-op\""), chunks);
        JsonNode summary = mapper.readTree(knowledgeBase.resolve("crawl-result.json").toFile());
        assertEquals("markdown", summary.path("loader").asText());
        assertEquals("no-op", summary.path("chunker").asText());
        assertEquals("effective-single-pipeline", summary.path("pipelineMetadataScope").asText());
    }

    @Test
    void projectRegisteredVlmOcrPipelineSupportsTheDogfoodPdfRequestAndConsistentMetadata()
            throws Exception {
        writeOcrProjectManifest();
        Files.writeString(projectRoot.resolve("wailingcaverns.pdf"),
                "The model executor owns PDF page rendering in this test.", StandardCharsets.UTF_8);
        LocalProjectCrawlBackend backend = new LocalProjectCrawlBackend(mapper,
                (root, file, pipeline, loadedText) -> """
                        # D&D WAILING CAVERNS

                        [Image]

                        Fight against the nightmare in this dungeon.
                        """);

        ToolResult result = backend.crawlDocuments(ocrDogfoodRequest(false), context);

        assertFalse(result.isError(), result.getOutput());
        Path crawl = projectRoot.resolve("data/crawls/dogfood-ocr-pdf");
        JsonNode summary = mapper.readTree(crawl.resolve("crawl-result.json").toFile());
        JsonNode document = mapper.readTree(
                Files.readAllLines(crawl.resolve("documents.jsonl"), StandardCharsets.UTF_8).get(0));
        JsonNode chunk = mapper.readTree(
                Files.readAllLines(crawl.resolve("chunks.jsonl"), StandardCharsets.UTF_8).get(0));
        assertEquals("pdf", summary.path("loader").asText());
        assertEquals("sentence", summary.path("chunker").asText());
        assertEquals("effective-single-pipeline", summary.path("pipelineMetadataScope").asText());
        for (String field : List.of("pipelineId", "pipelineType", "loader", "chunker")) {
            assertEquals(document.path(field).asText(), chunk.path(field).asText(), field);
        }
        assertEquals("vlm-ocr-pdf", document.path("pipelineId").asText());
        assertEquals("VLM", document.path("pipelineType").asText());
        assertTrue(chunk.path("text").asText().contains("WAILING CAVERNS"));
    }

    @Test
    void remoteChatVlmRunsThroughCrawlDocumentsAndPersistsSearchableOutput() throws Exception {
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            captured.set(mapper.readTree(exchange.getRequestBody()));
            String body = "data: {\"choices\":[{\"delta\":{\"content\":"
                    + mapper.writeValueAsString("# Remote vision\n\nremote-chat-ibis-marker")
                    + "},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream response = exchange.getResponseBody()) {
                response.write(bytes);
            }
        });
        server.start();
        try {
            new ChatConfig("custom", null, "remote-vision-model",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .saveProject(projectRoot);
            Path imagePath = projectRoot.resolve("remote-image.png");
            BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
            assertTrue(ImageIO.write(image, "png", imagePath.toFile()));
            image.flush();

            ObjectNode request = mapper.createObjectNode().put("async", false);
            request.putObject("knowledgeBase").put("name", "remote-chat-vlm");
            request.putArray("documents").addObject()
                    .put("path", imagePath.toString()).put("pipelineId", "remote-vlm");
            ObjectNode pipeline = request.putArray("pipelines").addObject()
                    .put("pipelineId", "remote-vlm").put("pipelineType", "VLM")
                    .put("loaderName", "auto").put("chunkerName", "no-op");
            pipeline.putObject("processor").put("type", "CHAT_MODEL");

            ToolResult result = new CrawlDocumentsTool((String) null, mapper)
                    .execute(request, context);

            assertFalse(result.isError(), result.getOutput());
            assertEquals("COMPLETED", result.getMetadata().get("status"));
            Path crawl = projectRoot.resolve("data/crawls/remote-chat-vlm");
            assertTrue(Files.readString(crawl.resolve("chunks.jsonl"))
                    .contains("remote-chat-ibis-marker"));
            JsonNode persistedDocument = mapper.readTree(
                    Files.readAllLines(crawl.resolve("documents.jsonl")).get(0));
            assertEquals("remote-vlm", persistedDocument.path("pipelineId").asText());
            assertEquals("VLM", persistedDocument.path("pipelineType").asText());

            JsonNode userContent = null;
            for (JsonNode message : captured.get().path("messages")) {
                if ("user".equals(message.path("role").asText())) {
                    userContent = message.path("content");
                    break;
                }
            }
            assertTrue(userContent != null && userContent.isArray(), String.valueOf(captured.get()));
            assertEquals("image_url", userContent.get(0).path("type").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void remoteChatDryRunResolvesProviderWithoutARequestOrLocalArtifact() throws Exception {
        new ChatConfig("custom", null, "remote-dry-run-model", "http://127.0.0.1:1/v1")
                .saveProject(projectRoot);
        Path imagePath = projectRoot.resolve("remote-dry-run.png");
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        assertTrue(ImageIO.write(image, "png", imagePath.toFile()));
        image.flush();

        ObjectNode request = mapper.createObjectNode().put("async", false).put("dryRun", true);
        request.putObject("knowledgeBase").put("name", "remote-chat-preview");
        request.putArray("documents").addObject()
                .put("path", imagePath.toString()).put("pipelineId", "remote-vlm");
        request.putArray("pipelines").addObject()
                .put("pipelineId", "remote-vlm")
                .put("pipelineType", "VLM")
                .putObject("processor").put("type", "CHAT_MODEL");

        ToolResult result = new CrawlDocumentsTool((String) null, mapper).execute(request, context);

        assertFalse(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("\"execution\" : \"CHAT_MODEL\""), result.getOutput());
        assertTrue(result.getOutput().contains("\"provider\" : \"custom\""), result.getOutput());
        assertTrue(result.getOutput().contains("\"credentialsPersistedInCrawl\" : false"),
                result.getOutput());
        assertFalse(result.getOutput().contains("modelPath"), result.getOutput());
        assertFalse(Files.exists(projectRoot.resolve("data/crawls/remote-chat-preview")));
    }

    @Test
    void asynchronousVlmOcrStatusReportsModelAndPersistenceStages() throws Exception {
        writeOcrProjectManifest();
        Files.writeString(projectRoot.resolve("wailingcaverns.pdf"),
                "The model executor owns PDF page rendering in this test.", StandardCharsets.UTF_8);
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        LocalProjectCrawlBackend backend = new LocalProjectCrawlBackend(mapper,
                (root, file, pipeline, loadedText) -> {
                    modelStarted.countDown();
                    if (!releaseModel.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release the model stub");
                    }
                    return "# staged OCR result";
                });

        ToolResult started = backend.crawlDocuments(ocrDogfoodRequest(true), context);
        String jobId = String.valueOf(started.getMetadata().get("jobId"));
        try {
            assertFalse(started.isError(), started.getOutput());
            assertTrue(modelStarted.await(5, TimeUnit.SECONDS), "model-backed worker did not start");
            ToolResult running = backend.control(
                    mapper.createObjectNode().put("operation", "status").put("jobId", jobId), context);
            assertFalse(running.isError(), running.getOutput());
            assertEquals("MODEL_INITIALIZATION", running.getMetadata().get("stage"));
            assertEquals(30, ((Number) running.getMetadata().get("progressPercent")).intValue());
            assertTrue(running.getOutput().contains("selected model-backed document pipeline"),
                    running.getOutput());
        } finally {
            releaseModel.countDown();
        }

        ToolResult terminal = null;
        for (int i = 0; i < 200; i++) {
            terminal = backend.control(
                    mapper.createObjectNode().put("operation", "status").put("jobId", jobId), context);
            if (Boolean.TRUE.equals(terminal.getMetadata().get("terminal"))) break;
            Thread.sleep(10);
        }
        assertFalse(terminal.isError(), terminal.getOutput());
        assertEquals(true, terminal.getMetadata().get("terminal"));
        assertEquals("COMPLETED", terminal.getMetadata().get("stage"));
        assertEquals(100, ((Number) terminal.getMetadata().get("progressPercent")).intValue());
    }

    @Test
    void asynchronousCustomModelPipelineReportsModelStageWithoutKnownTypeNames() throws Exception {
        Files.writeString(projectRoot.resolve("custom-model.txt"),
                "The custom executor owns model-backed extraction.", StandardCharsets.UTF_8);
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        LocalProjectCrawlBackend backend = new LocalProjectCrawlBackend(mapper,
                (root, file, pipeline, loadedText) -> {
                    modelStarted.countDown();
                    if (!releaseModel.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release the custom model");
                    }
                    return "# Custom model result";
                });
        ObjectNode request = documentRequest("custom-model.txt", "custom-model-stage");
        request.put("async", true);
        ((ObjectNode) request.withArray("documents").get(0))
                .put("pipelineId", "vendor.document-understanding");
        ObjectNode pipeline = request.putArray("pipelines").addObject()
                .put("pipelineId", "vendor.document-understanding")
                .put("pipelineType", "vendor.document-understanding")
                .put("loaderName", "text")
                .put("chunkerName", "no-op");
        ObjectNode definition = pipeline.putObject("processor")
                .put("type", "UNIFIED_PIPELINE")
                .putObject("pipelineDefinition")
                .put("pipelineId", "vendor.document-understanding")
                .put("kind", "GENERIC")
                .put("topology", "SEQUENCE");
        definition.putObject("pipelineSpec")
                .put("@class", "ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline")
                .put("id", "vendor.document-understanding")
                .putArray("steps");

        ToolResult started = backend.crawlDocuments(request, context);
        String jobId = String.valueOf(started.getMetadata().get("jobId"));
        try {
            assertFalse(started.isError(), started.getOutput());
            assertTrue(modelStarted.await(5, TimeUnit.SECONDS), "custom model worker did not start");
            ToolResult running = backend.control(
                    mapper.createObjectNode().put("operation", "status").put("jobId", jobId), context);
            assertFalse(running.isError(), running.getOutput());
            assertEquals("MODEL_INITIALIZATION", running.getMetadata().get("stage"));
            assertTrue(running.getOutput().contains("selected model-backed document pipeline"),
                    running.getOutput());
        } finally {
            releaseModel.countDown();
        }

        ToolResult terminal = null;
        for (int i = 0; i < 200; i++) {
            terminal = backend.control(
                    mapper.createObjectNode().put("operation", "status").put("jobId", jobId), context);
            if (Boolean.TRUE.equals(terminal.getMetadata().get("terminal"))) break;
            Thread.sleep(10);
        }
        assertFalse(terminal.isError(), terminal.getOutput());
        assertEquals("COMPLETED", terminal.getMetadata().get("stage"));
    }

    @Test
    void preservesDelimitedTablesWithTheTableAwarePipeline() throws Exception {
        Files.writeString(projectRoot.resolve("metrics.csv"),
                "region,total,comment\nwest,42,\"contains, comma\"\neast,17,steady\n",
                StandardCharsets.UTF_8);
        ObjectNode request = documentRequest("metrics.csv", "table-kb");
        ((ObjectNode) request.withArray("documents").get(0)).put("pipelineId", "table-aware");

        ToolResult result = new CrawlDocumentsTool((String) null, mapper).execute(request, context);

        assertFalse(result.isError(), result.getOutput());
        Path knowledgeBase = projectRoot.resolve("data/crawls/table-kb");
        String documents = Files.readString(knowledgeBase.resolve("documents.jsonl"));
        String chunks = Files.readString(knowledgeBase.resolve("chunks.jsonl"));
        assertTrue(documents.contains("\"pipelineType\":\"TABLE_AWARE\""), documents);
        assertTrue(documents.contains("\"loader\":\"table\""), documents);
        assertTrue(chunks.contains("| region | total | comment |"), chunks);
        assertTrue(chunks.contains("| west | 42 | contains, comma |"), chunks);
    }

    @Test
    @SuppressWarnings("unchecked")
    void explicitDryRunIsIsolatedAndReportsTheEffectivePlanWithoutWrites() throws Exception {
        Files.writeString(projectRoot.resolve("old.md"), "old source\n", StandardCharsets.UTF_8);
        Files.writeString(projectRoot.resolve("new.md"), "new source\n", StandardCharsets.UTF_8);
        CrawlDocumentsTool tool = new CrawlDocumentsTool((String) null, mapper);

        ToolResult seeded = tool.execute(documentRequest("old.md", "preview-kb"), context);
        assertFalse(seeded.isError(), seeded.getOutput());
        Path manifest = projectRoot.resolve("kompile.project.json");
        String manifestBefore = Files.readString(manifest);
        String crawlBefore = Files.readString(projectRoot.resolve(
                "data/crawls/preview-kb/crawl-result.json"));

        ObjectNode previewRequest = documentRequest("new.md", "preview-kb");
        previewRequest.put("dryRun", true);
        ToolResult preview = tool.execute(previewRequest, context);

        assertFalse(preview.isError(), preview.getOutput());
        Map<String, Object> plan = (Map<String, Object>) preview.getMetadata().get("preview");
        assertEquals(false, plan.get("persistentWrites"));
        assertEquals(true, plan.get("isolatedExplicitPreview"));
        List<String> sources = (List<String>) plan.get("sources");
        assertEquals(List.of(projectRoot.resolve("new.md").toString()), sources);
        List<Map<String, Object>> documents =
                (List<Map<String, Object>>) plan.get("effectiveDocuments");
        assertEquals(1, documents.size());
        assertEquals(projectRoot.resolve("new.md").toString(), documents.get(0).get("path"));
        assertTrue(plan.containsKey("pipelineResolution"));
        assertTrue(plan.containsKey("effectiveRequest"));
        JsonNode requestedConfiguration =
                (JsonNode) preview.getMetadata().get("requestedConfiguration");
        JsonNode effectiveConfiguration =
                (JsonNode) preview.getMetadata().get("effectiveConfiguration");
        assertTrue(requestedConfiguration.path("dryRun").asBoolean());
        assertEquals(projectRoot.resolve("new.md").toString(),
                effectiveConfiguration.path("documents").path(0).path("path").asText());
        List<Map<String, Object>> resolved =
                (List<Map<String, Object>>) plan.get("resolvedDocuments");
        assertEquals(1, resolved.size());
        assertEquals("RESOLVED", resolved.get(0).get("resolutionStatus"));
        assertEquals("standard-text", resolved.get(0).get("routeDecision"));
        assertEquals("markdown", resolved.get(0).get("loader"));
        assertEquals("recursive-character", resolved.get(0).get("chunker"));
        assertTrue(resolved.get(0).containsKey("modelResolution"));
        assertEquals(manifestBefore, Files.readString(manifest));
        assertEquals(crawlBefore, Files.readString(
                projectRoot.resolve("data/crawls/preview-kb/crawl-result.json")));
    }

    @Test
    void rejectsAnUnavailableLocalLoaderBeforeStartingTheWorker() throws Exception {
        Files.writeString(projectRoot.resolve("unknown-loader.md"), "loader validation\n",
                StandardCharsets.UTF_8);
        ObjectNode request = documentRequest("unknown-loader.md", "invalid-kb");
        ((ObjectNode) request.withArray("documents").get(0)).put("loaderName", "imaginary-loader");

        ToolResult result = new CrawlDocumentsTool((String) null, mapper).execute(request, context);

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("unknown project-local loader"), result.getOutput());
        assertFalse(Files.exists(projectRoot.resolve("data/crawls/invalid-kb/crawl-result.json")));
    }

    @Test
    void rejectsNonExecutableComposedPipelineBeforePersisting() throws Exception {
        Files.writeString(projectRoot.resolve("bad.md"),
                "This document selects a pipeline that has no executable definition.\n",
                StandardCharsets.UTF_8);

        ObjectNode request = mapper.createObjectNode();
        request.putObject("knowledgeBase").put("name", "non-executable-pipeline");
        request.put("dryRun", true);
        request.putArray("documents").addObject()
                .put("path", "bad.md").put("pipelineId", "broken");
        request.putArray("pipelines").addObject()
                .put("pipelineId", "broken")
                .put("pipelineType", "CUSTOM")
                .put("loaderName", "markdown")
                .put("chunkerName", "no-op")
                .put("pipelineDefinitionPath", "missing-pipeline.json");

        ToolResult result = new CrawlDocumentsTool((String) null, mapper)
                .execute(request, context);

        assertTrue(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("missing-pipeline.json"), result.getOutput());
        assertTrue(result.getOutput().contains("pipelineSpec"), result.getOutput());
        assertFalse(Files.exists(
                projectRoot.resolve("data/crawls/non-executable-pipeline/crawl-result.json")));
    }

    @Test
    void routesSelectablePdfThroughTheApplicationPdfLoader() throws Exception {
        Path pdf = projectRoot.resolve("text-source.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 720);
                stream.showText("Selectable text PDF for local crawl parsing and indexing.");
                stream.endText();
            }
            document.save(pdf.toFile());
        }

        ToolResult result = new CrawlDocumentsTool((String) null, mapper)
                .execute(documentRequest("text-source.pdf", "pdf-kb"), context);

        assertFalse(result.isError(), result.getOutput());
        Path crawl = projectRoot.resolve("data/crawls/pdf-kb");
        JsonNode persistedDocument = mapper.readTree(
                Files.readString(crawl.resolve("documents.jsonl")).lines().findFirst().orElseThrow());
        String chunks = Files.readString(crawl.resolve("chunks.jsonl"));
        assertEquals("pdf", persistedDocument.path("loader").asText());
        assertTrue(persistedDocument.path("loaderOutputs").isArray(), persistedDocument.toString());
        assertTrue(chunks.contains("Selectable text PDF"), chunks);
    }

    private ObjectNode documentRequest(String path, String knowledgeBase) {
        ObjectNode request = mapper.createObjectNode();
        request.putArray("documents").addObject().put("path", path);
        request.putObject("knowledgeBase").put("name", knowledgeBase);
        request.put("async", false);
        return request;
    }

    private void writeOcrProjectManifest() throws Exception {
        Files.writeString(projectRoot.resolve("kompile.project.json"), """
                {
                  "schemaVersion": 1,
                  "projectId": "ocr-dogfood",
                  "name": "OCR Dogfood",
                  "pipelines": [{
                    "id": "vlm-ocr-pdf",
                    "pipelineId": "vlm-ocr-pdf",
                    "name": "VLM OCR scanned PDF extraction",
                    "role": "VLM_OCR",
                    "active": true,
                    "required": true,
                    "metadata": {
                      "pipelineType": "VLM",
                      "loaderName": "pdf",
                      "chunkerName": "sentence"
                    }
                  }]
                }
                """, StandardCharsets.UTF_8);
    }

    private ObjectNode ocrDogfoodRequest(boolean async) {
        ObjectNode request = mapper.createObjectNode();
        request.put("name", "MCP OCR dogfood");
        request.put("async", async);
        request.putArray("documents").addObject()
                .put("path", "wailingcaverns.pdf")
                .put("sourceType", "FILE")
                .put("pipelineId", "vlm-ocr-pdf");
        ObjectNode pipeline = request.putArray("pipelines").addObject()
                .put("pipelineId", "vlm-ocr-pdf")
                .put("registeredPipelineId", "vlm-ocr-pdf")
                .put("modelId", "smoldocling-256m");
        pipeline.putObject("options")
                .put("outputFormat", "MARKDOWN")
                .put("maxPages", 1)
                .put("pageRange", "1")
                .put("pdfRenderDpi", 144)
                .put("pageBatchSize", 1)
                .put("temperature", 0.0)
                .put("doSample", false);
        request.put("defaultPipelineId", "vlm-ocr-pdf");
        request.putObject("knowledgeBase").put("name", "dogfood-ocr-pdf");
        request.putObject("modelRuntime")
                .put("autoBootstrap", false)
                .put("type", "vlm_pipeline")
                .put("timeoutMinutes", 30);
        request.putArray("steps")
                .add("LOADING").add("MARKDOWN_EXTRACTION").add("CHUNKING");
        request.put("strictSteps", true);
        request.put("deriveOntology", false);
        request.putObject("embeddingTraining").put("enabled", false);
        request.putObject("reasoningLearning").put("enabled", false);
        return request;
    }
}
