/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.KnowledgeSearchCliTool;
import ai.kompile.cli.main.chat.tools.KnowledgeStatusCliTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

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
    void emptyRequestBootstrapsAndSearchesTheCurrentFolder() throws Exception {
        Files.writeString(projectRoot.resolve("folder-note.md"),
                "The folder bootstrap contains the silver osprey marker.\n",
                StandardCharsets.UTF_8);

        CrawlDocumentsTool crawl = new CrawlDocumentsTool((String) null, mapper);
        assertFalse(crawl.parameterSchema().has("anyOf"),
                "The local folder bootstrap must not require documents or codeProjects selectors.");

        ToolResult bootstrapped = crawl.execute(mapper.createObjectNode(), context);

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
        assertTrue(chunks.contains("\"chunker\":\"no-op\""), chunks);
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

    private ObjectNode documentRequest(String path, String knowledgeBase) {
        ObjectNode request = mapper.createObjectNode();
        request.putArray("documents").addObject().put("path", path);
        request.putObject("knowledgeBase").put("name", knowledgeBase);
        return request;
    }
}
