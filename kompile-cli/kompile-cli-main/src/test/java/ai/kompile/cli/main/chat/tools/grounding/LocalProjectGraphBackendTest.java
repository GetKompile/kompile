/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.GraphEmbeddingsTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.project.KompileCodingProject;
import ai.kompile.project.KompileProjectCrawlProfile;
import ai.kompile.project.KompileProjectInitRequest;
import ai.kompile.project.KompileProjectManifest;
import ai.kompile.project.KompileProjectStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalProjectGraphBackendTest {
    @TempDir
    Path projectRoot;

    private ObjectMapper mapper;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        AgentConfig agent = AgentConfig.builder("local-graph-worker")
                .enabledTools(Set.of("*"))
                .build();
        PermissionService permissions = new PermissionService();
        for (String tool : Set.of(
                "crawl_documents", "crawl_control", "graph_reasoning_query", "graph_reason",
                "graph_embeddings", "graph_export", "graph_import", "external_directory")) {
            permissions.setUserOverride(tool, PermissionService.PermissionLevel.ALLOW);
        }
        context = new ToolContext("local-graph-test", agent, permissions, projectRoot,
                new ToolRegistry(mapper));
    }

    @Test
    void crawlsReasonsTrainsExportsAndIncrementallyRemovesDocumentsLocally() throws Exception {
        Path docs = projectRoot.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("retained.md"),
                "# Retained\nThe aurora compass points toward the northern archive.\n",
                StandardCharsets.UTF_8);
        Files.writeString(docs.resolve("removed.md"),
                "# Removed\nThe obsolete scarlet beacon is scheduled for deletion.\n",
                StandardCharsets.UTF_8);

        ObjectNode request = mapper.createObjectNode();
        request.putArray("documents").addObject().put("path", "docs");
        request.putObject("knowledgeBase").put("id", 41);
        request.putObject("embeddingTraining")
                .put("enabled", true)
                .put("algorithm", "TRANSE")
                .put("embeddingDim", 8)
                .put("epochs", 2);

        CrawlDocumentsTool crawlTool = new CrawlDocumentsTool((String) null, mapper);
        ToolResult first = crawlTool.execute(request, context);
        assertFalse(first.isError(), first.getOutput());
        assertEquals("project-local", first.getMetadata().get("backend"));
        assertTrue(((Number) first.getMetadata().get("graphEntityCount")).intValue() >= 6);
        assertTrue(((Number) first.getMetadata().get("graphRelationCount")).intValue() >= 5);
        assertTrue(((Number) first.getMetadata().get("embeddingVectorCount")).intValue() > 0);

        Path graphPath = projectRoot.resolve("data/crawls/kb-41/graph.kgraph");
        assertTrue(Files.isRegularFile(graphPath));
        UnifiedGraph firstGraph = UnifiedGraph.load(graphPath);
        assertEquals(41L, firstGraph.factSheetId());
        assertTrue(firstGraph.entityCount() >= 6);
        assertTrue(firstGraph.relationCount() >= 5);
        assertNotNull(firstGraph.artifact(LocalProjectGraphBackend.MODEL_ARTIFACT));
        assertTrue(firstGraph.vectorLayers().containsKey(LocalProjectGraphBackend.ENTITY_LAYER));

        ObjectNode query = mapper.createObjectNode();
        query.put("operation", "SEARCH");
        query.put("queryText", "aurora compass");
        query.put("factSheetId", 41);
        ToolResult queryResult = new GraphReasoningQueryTool((String) null, mapper)
                .execute(query, context);
        assertFalse(queryResult.isError(), queryResult.getOutput());
        assertTrue(queryResult.getOutput().toLowerCase().contains("aurora"), queryResult.getOutput());

        ObjectNode reason = mapper.createObjectNode();
        reason.put("target", "retained.md");
        reason.put("factSheetId", 41);
        ToolResult reasonResult = new GraphReasonTool((String) null, mapper)
                .execute(reason, context);
        assertFalse(reasonResult.isError(), reasonResult.getOutput());
        assertEquals("project-local", reasonResult.getMetadata().get("backend"));

        ObjectNode jobs = mapper.createObjectNode();
        jobs.put("action", "jobs");
        jobs.put("fact_sheet_id", 41);
        ToolResult jobsResult = new GraphEmbeddingsTool(null, mapper).execute(jobs, context);
        assertFalse(jobsResult.isError(), jobsResult.getOutput());
        assertEquals("project-local", jobsResult.getMetadata().get("backend"));

        ObjectNode export = mapper.createObjectNode();
        export.put("path", "backup.kgraph");
        export.put("format", "kgraph");
        export.put("factSheetId", 41);
        ToolResult exportResult = new GraphExportTool((String) null, mapper)
                .execute(export, context);
        assertFalse(exportResult.isError(), exportResult.getOutput());
        assertTrue(Files.isRegularFile(projectRoot.resolve("backup.kgraph")));

        Files.delete(docs.resolve("removed.md"));
        ToolResult second = crawlTool.execute(request, context);
        assertFalse(second.isError(), second.getOutput());
        UnifiedGraph updated = UnifiedGraph.load(graphPath);
        assertTrue(updated.entities().stream().noneMatch(entity ->
                entity.label() != null && entity.label().contains("removed.md")));
        assertTrue(updated.entityCount() < firstGraph.entityCount());

        ObjectNode graphStats = mapper.createObjectNode();
        graphStats.put("operation", "graph_stats");
        graphStats.put("jobId", "kb-41");
        ToolResult stats = new CrawlControlTool((String) null, mapper)
                .execute(graphStats, context);
        assertFalse(stats.isError(), stats.getOutput());
        assertTrue(stats.getOutput().contains("\"available\" : true"), stats.getOutput());

        ObjectNode importRequest = mapper.createObjectNode();
        importRequest.put("path", "backup.kgraph");
        importRequest.put("factSheetId", 42);
        ToolResult importResult = new GraphImportTool((String) null, mapper)
                .execute(importRequest, context);
        assertFalse(importResult.isError(), importResult.getOutput());
        assertTrue(Files.isRegularFile(projectRoot.resolve("data/crawls/kb-42/graph.kgraph")));
    }

    @Test
    void projectsTheIncrementalCodeIndexIntoTheLocalKnowledgeGraph() throws Exception {
        Path source = projectRoot.resolve("src/main/java/example/LocalService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package example;
                final class LocalService {
                    String answer() { return "local graph"; }
                }
                """, StandardCharsets.UTF_8);

        ObjectNode request = mapper.createObjectNode();
        request.putArray("codeProjects").add("*");
        request.putObject("knowledgeBase").put("id", 73);
        request.putObject("embeddingTraining").put("enabled", false);

        ToolResult result = new CrawlDocumentsTool((String) null, mapper).execute(request, context);
        assertFalse(result.isError(), result.getOutput());
        assertTrue(((Number) result.getMetadata().get("codeEntityCount")).intValue() > 0);

        UnifiedGraph graph = UnifiedGraph.load(
                projectRoot.resolve("data/crawls/kb-73/graph.kgraph"));
        assertEquals(73L, graph.factSheetId());
        assertTrue(graph.entities().stream().anyMatch(entity -> "CODE_PROJECT".equals(entity.type())));
        assertTrue(graph.entities().stream().anyMatch(entity ->
                entity.label() != null && entity.label().contains("LocalService")));
        assertTrue(graph.relations().stream().anyMatch(relation ->
                "HAS_CODE_PROJECT".equals(relation.type())));
    }

    @Test
    void persistsCodeProjectFactSheetAndGraphProfileInKompileProjectManifest() throws Exception {
        Path source = projectRoot.resolve("src/main/java/example/BoundService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package example; final class BoundService {}\n",
                StandardCharsets.UTF_8);

        KompileCodingProject codingProject = new KompileCodingProject();
        codingProject.setId("app");
        codingProject.setCodeProjectId("app-code");
        codingProject.setName("Application");
        codingProject.setRootPath(projectRoot.toString());
        KompileProjectInitRequest init = new KompileProjectInitRequest();
        init.setName("bound-project");
        init.setIncludeStandardComponents(false);
        init.setCodingProjects(List.of(codingProject));
        KompileProjectStore store = new KompileProjectStore();
        store.init(projectRoot, init);

        ObjectNode request = mapper.createObjectNode();
        request.putArray("codeProjects").add("app");
        request.putObject("knowledgeBase").put("id", 91);
        request.putObject("embeddingTraining").put("enabled", false);

        ToolResult result = new CrawlDocumentsTool((String) null, mapper).execute(request, context);
        assertFalse(result.isError(), result.getOutput());

        KompileProjectManifest restored = store.load(projectRoot);
        KompileCodingProject restoredCodeProject = restored.getCodingProjects().stream()
                .filter(candidate -> "app".equals(candidate.getId()))
                .findFirst().orElseThrow();
        assertEquals(91L, restoredCodeProject.getFactSheetId());
        KompileProjectCrawlProfile graphProfile = restored.getCrawlProfiles().stream()
                .filter(candidate -> "kb-91".equals(candidate.getId()))
                .findFirst().orElseThrow();
        assertTrue(graphProfile.isGraphLocal());
        assertTrue(graphProfile.isGraphAutoStart());
        assertEquals("91", graphProfile.getMetadata().get("factSheetId"));
        assertEquals("data/crawls/kb-91/graph.kgraph",
                graphProfile.getMetadata().get("graphPath").replace('\\', '/'));
        assertTrue(Files.isRegularFile(projectRoot.resolve(graphProfile.getMetadata().get("graphPath"))));
    }
}
