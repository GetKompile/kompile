/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.codeindex;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tools.grounding.LocalProjectGraphBackend;
import ai.kompile.cli.main.chat.tools.grounding.CrawlDocumentsTool;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.unified.Dtype;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.UnifiedGraphArchive;
import ai.kompile.graph.reasoning.unified.UnifiedGraphMutationJournal;
import ai.kompile.graph.reasoning.unified.VectorLayer;
import ai.kompile.project.KompileProjectStore;
import ai.kompile.project.KompileCodingProject;
import ai.kompile.project.KompileProjectInitRequest;
import ai.kompile.project.KompileProjectManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LocalCodeKGraphPublisherTest {

    @TempDir
    Path tempDir;

    @Test
    void unchangedIndexGenerationReusesPublishedGraph() throws Exception {
        Path root = tempDir.resolve("generation-dedup-code");
        Files.createDirectories(root);
        Files.writeString(root.resolve("Stable.java"), "final class Stable {}\n");
        String projectId = uniqueId("generation-dedup-code");
        Path indexDirectory = LocalCodeIndexer.getIndexDir(projectId);
        try {
            LocalCodeIndexer indexer = new LocalCodeIndexer();
            try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
                indexer.index(root, projectId, null, null, true, quiet);
            }

            LocalCodeKGraphPublisher.ProjectionResult first =
                    LocalCodeKGraphPublisher.publish(root, projectId, null, null);
            KompileProjectStore store = new KompileProjectStore();
            String firstProjectedAt = store.load(root).getCodingProjects().get(0)
                    .getMetadata().get("codeProjectedAt");
            assertNotNull(firstProjectedAt);

            LocalCodeKGraphPublisher.ProjectionResult second =
                    LocalCodeKGraphPublisher.publish(root, projectId, null, null);
            String secondProjectedAt = store.load(root).getCodingProjects().get(0)
                    .getMetadata().get("codeProjectedAt");

            assertEquals(firstProjectedAt, secondProjectedAt,
                    "an unchanged generation must not rewrite the graph or manifest");
            assertEquals(first.graphPath(), second.graphPath());
            assertEquals(first.graphEntities(), second.graphEntities());
            assertEquals(first.graphRelations(), second.graphRelations());
            assertEquals(first.codeEntities(), second.codeEntities());
        } finally {
            deleteTree(indexDirectory);
        }
    }

    @Test
    void replacedArchiveCannotReuseManifestOnlyProjectionReceipt() throws Exception {
        Path root = tempDir.resolve("replaced-archive");
        Files.createDirectories(root);
        Files.writeString(root.resolve("Stable.java"), "final class Stable {}\n");
        String projectId = uniqueId("replaced-archive");
        Path indexDirectory = LocalCodeIndexer.getIndexDir(projectId);
        try {
            try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
                new LocalCodeIndexer().index(root, projectId, null, null, true, quiet);
            }
            var projection = LocalCodeKGraphPublisher.publish(root, projectId, null, null);
            UnifiedGraph replacement = new UnifiedGraph().graphId("replacement");
            replacement.addEntity(GraphEntity.builder("manual").label("manual").type("NOTE").build());
            replacement.save(projection.graphPath());
            var repaired = LocalCodeKGraphPublisher.publish(root, projectId, null, null);
            UnifiedGraph graph = UnifiedGraph.load(repaired.graphPath());
            assertTrue(graph.entity("manual").isPresent());
            assertTrue(graph.entities().stream().anyMatch(entity -> "Stable".equals(entity.label())));
            assertEquals("COMPLETED", graph.meta().get("phase.codeProjection." + projectId));
        } finally {
            deleteTree(indexDirectory);
        }
    }

    @Test
    void sqliteGenerationWinsOverInterruptedSidecarPublication() throws Exception {
        Path root = tempDir.resolve("stale-sidecar");
        Files.createDirectories(root);
        Path source = root.resolve("Service.java");
        Files.writeString(source, "final class Before {}\n");
        String projectId = uniqueId("stale-sidecar");
        Path indexDirectory = LocalCodeIndexer.getIndexDir(projectId);
        try {
            LocalCodeIndexer indexer = new LocalCodeIndexer();
            try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
                indexer.index(root, projectId, null, null, true, quiet);
            }
            LocalCodeKGraphPublisher.publish(root, projectId, null, null);
            Path sidecar = indexDirectory.resolve("metadata.json");
            String oldMetadata = Files.readString(sidecar);
            Files.writeString(source, "final class After {}\n");
            try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
                indexer.index(root, projectId, null, null, true, quiet);
            }
            Files.writeString(sidecar, oldMetadata);
            var projection = LocalCodeKGraphPublisher.publish(root, projectId, null, null);
            UnifiedGraph graph = UnifiedGraph.load(projection.graphPath());
            assertTrue(graph.entities().stream().anyMatch(entity -> "After".equals(entity.label())));
            try (IndexDatabase database = IndexDatabase.openReadOnly(indexDirectory)) {
                assertEquals(database.getIndexGeneration(), graph.meta().get("codeIndexGeneration." + projectId));
            }
        } finally {
            deleteTree(indexDirectory);
        }
    }

    @Test
    void codeProjectionPreservesExistingCrawlOutcome() throws Exception {
        Path root = tempDir.resolve("crawl-outcome-code");
        Path source = root.resolve("Status.java");
        Files.createDirectories(root);
        Files.writeString(source, "final class Status {}\n");
        String projectId = uniqueId("crawl-outcome-code");
        Path indexDirectory = LocalCodeIndexer.getIndexDir(projectId);
        try {
            LocalCodeIndexer indexer = new LocalCodeIndexer();
            try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
                indexer.index(root, projectId, null, null, true, quiet);
            }
            LocalCodeKGraphPublisher.ProjectionResult first =
                    LocalCodeKGraphPublisher.publish(root, projectId, null, null);
            Path summaryPath = first.graphPath().getParent().resolve("crawl-result.json");
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode crawlSummary = (ObjectNode) mapper.readTree(summaryPath.toFile());
            crawlSummary.put("status", "COMPLETED_WITH_ERRORS");
            crawlSummary.put("finishedAt", "2026-09-03T22:13:23Z");
            crawlSummary.putArray("semanticExtractionErrors").add("model produced non-finite logits");
            mapper.writerWithDefaultPrettyPrinter().writeValue(summaryPath.toFile(), crawlSummary);

            Files.writeString(source, "final class Status { int code; }\n");
            try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
                indexer.index(root, projectId, null, null, true, quiet);
            }
            LocalCodeKGraphPublisher.publish(root, projectId, null, null);

            JsonNode refreshed = mapper.readTree(summaryPath.toFile());
            assertEquals("COMPLETED_WITH_ERRORS", refreshed.path("status").asText());
            assertEquals("2026-09-03T22:13:23Z", refreshed.path("finishedAt").asText());
            assertEquals("model produced non-finite logits",
                    refreshed.path("semanticExtractionErrors").get(0).asText());
            assertEquals("COMPLETED", refreshed.path("codeProjectionStatus").asText());
            assertFalse(refreshed.path("codeProjectionUpdatedAt").asText().isBlank());
        } finally {
            deleteTree(indexDirectory);
        }
    }

    @Test
    void explicitDocumentCrawlRestoresExistingCodeProjectionWithoutRetrainingIt() throws Exception {
        Path root = tempDir.resolve("crawl-preserves-code");
        Path source = root.resolve("src/main/java/example/LocalService.java");
        Path document = root.resolve("docs/architecture.md");
        Files.createDirectories(source.getParent());
        Files.createDirectories(document.getParent());
        Files.writeString(source, """
                package example;

                public final class LocalService {
                }
                """);
        Files.writeString(document, "The orchid harbor architecture remains searchable.\n");
        String projectId = uniqueId("crawl-preserves-code");
        Path indexDirectory = LocalCodeIndexer.getIndexDir(projectId);
        try {
            LocalCodeIndexer indexer = new LocalCodeIndexer();
            try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
                indexer.index(root, projectId, null, null, true, quiet);
            }
            LocalCodeKGraphPublisher.ProjectionResult projection =
                    LocalCodeKGraphPublisher.publish(root, projectId, null, null);
            assertTrue(UnifiedGraph.load(projection.graphPath()).entities().stream().anyMatch(entity ->
                    "LocalService".equals(entity.label()) && "CLASS".equals(entity.type())),
                    "the pre-crawl projection must contain the class being preserved");

            // Preservation must use the archive, not a newer/unavailable SQLite index.
            deleteTree(indexDirectory);
            ObjectMapper mapper = new ObjectMapper();
            AgentConfig agent = AgentConfig.builder("crawl-preserves-code")
                    .enabledTools(Set.of("*")).build();
            PermissionService permissions = new PermissionService();
            permissions.setUserOverride(
                    "crawl_documents", PermissionService.PermissionLevel.ALLOW);
            ToolContext context = new ToolContext("crawl-preserves-code", agent,
                    permissions, root, new ToolRegistry(mapper));
            ObjectNode request = mapper.createObjectNode();
            request.put("async", false);
            request.putArray("documents").addObject().put("path", document.toString());
            request.putArray("steps").add("LEXICAL_INDEX");
            request.put("strictSteps", true);
            request.put("deriveOntology", false);
            request.putObject("embeddingTraining").put("enabled", false);
            request.putObject("reasoningLearning").put("enabled", false);
            request.putObject("runtimeConfig").put("runReasoningLearning", false);

            ToolResult crawl = new CrawlDocumentsTool((String) null, mapper)
                    .execute(request, context);

            assertFalse(crawl.isError(), crawl.getOutput());
            assertEquals(projection.knowledgeBaseId(), crawl.getMetadata().get("knowledgeBase"));
            assertTrue(((Number) crawl.getMetadata().get("codeEntityCount")).intValue() > 0);
            UnifiedGraph graph = UnifiedGraph.load(projection.graphPath());
            assertTrue(graph.entities().stream().anyMatch(entity ->
                    "LocalService".equals(entity.label()) && "CLASS".equals(entity.type())));
            assertTrue(graph.entities().stream().anyMatch(entity ->
                    "DOCUMENT".equals(entity.type()) && entity.label().contains("architecture.md")));
            assertEquals("STALE", graph.meta().get("phase.codeLearning." + projectId));
            JsonNode summary = mapper.readTree(
                    projection.graphPath().getParent().resolve("crawl-result.json").toFile());
            assertEquals(LocalProjectGraphBackend.CODE_PROJECTION_VERSION,
                    summary.path("codeProjectionVersion").asInt());
            assertEquals("COMPLETED", summary.path("status").asText());
        } finally {
            deleteTree(indexDirectory);
        }
    }

    @Test
    void standaloneDirectoryIndexCreatesQueriesExportsAndIncrementallyUpdatesKGraph() throws Exception {
        Path root = tempDir.resolve("self-contained-code");
        Path source = root.resolve("src/main/java/example/LocalService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package example;
                class BaseService {}
                final class LocalService extends BaseService {
                    String answer() {
                        return helper();
                    }
                    String answer(int mode) {
                        return helper() + mode;
                    }
                    String helper() {
                        return "portable graph";
                    }
                }
                """, StandardCharsets.UTF_8);
        Path consumer = root.resolve("src/main/java/consumer/UsesService.java");
        Files.createDirectories(consumer.getParent());
        Files.writeString(consumer, """
                package consumer;
                import example.LocalService;
                final class UsesService {
                    private LocalService service;
                }
                """, StandardCharsets.UTF_8);
        String projectId = uniqueId("self-contained-code");
        Path indexDirectory = LocalCodeIndexer.getIndexDir(projectId);
        try {
            LocalCodeIndexer indexer = new LocalCodeIndexer();
            try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
                indexer.index(root, projectId, null, null, true, quiet);
            }
            LocalCodeKGraphPublisher.ProjectionResult first =
                    LocalCodeKGraphPublisher.publish(root, projectId, null, null);

            assertTrue(Files.isRegularFile(root.resolve(KompileProjectStore.MANIFEST_FILE)));
            assertTrue(Files.isRegularFile(first.graphPath()));
            UnifiedGraph graph = UnifiedGraph.load(first.graphPath());
            assertTrue(graph.entities().stream().anyMatch(entity ->
                    "LocalService".equals(entity.label())));
            assertTrue(graph.entities().stream().anyMatch(entity ->
                    "CODE_PROJECT".equals(entity.type())));
            assertEquals(2, graph.entities().stream()
                    .filter(entity -> "answer".equals(entity.label()))
                    .map(GraphEntity::id).distinct().count());
            assertEquals(2, graph.entities().stream()
                    .filter(entity -> "answer".equals(entity.label()))
                    .map(entity -> entity.attributes().get("declarationKey"))
                    .distinct().count());
            assertEquals(LocalProjectGraphBackend.CODE_PROJECTION_VERSION,
                    ((Number) graph.meta().get("codeProjectionVersion")).intValue());
            Set<String> answerIds = graph.entities().stream()
                    .filter(entity -> "answer".equals(entity.label()))
                    .map(GraphEntity::id)
                    .collect(java.util.stream.Collectors.toSet());
            List<GraphRelation> callRelations = graph.relations().stream()
                    .filter(relation -> "CALLS".equals(relation.type())).toList();
            assertFalse(callRelations.isEmpty());
            assertTrue(callRelations.stream().allMatch(
                            relation -> !answerIds.contains(relation.sourceId())),
                    "ambiguous overload links must use a neutral reference node");
            assertTrue(graph.relations().stream().anyMatch(relation ->
                    "EXTENDS".equals(relation.type())));
            GraphEntity localService = graph.entities().stream()
                    .filter(entity -> "LocalService".equals(entity.label())
                            && "CLASS".equals(entity.type()))
                    .findFirst().orElseThrow();
            assertTrue(String.valueOf(localService.attributes().get("metadataRef"))
                    .startsWith("code-index:" + projectId + ":entity:"));
            assertTrue(String.valueOf(localService.attributes().get("signature"))
                    .contains("LocalService"));
            assertTrue(String.valueOf(localService.attributes().get("filePath"))
                    .endsWith("src/main/java/example/LocalService.java"));
            assertTrue(((Number) localService.attributes().get("startLine")).intValue() > 0);
            assertTrue(graph.relations().stream().anyMatch(relation ->
                            "CONTAINS".equals(relation.type())
                                    && localService.id().equals(relation.sourceId())),
                    "real class declaration must own its containment edges even when imported elsewhere");
            assertEquals("STALE", graph.meta().get("phase.codeLearning." + projectId));
            GraphRelation extendsRelation = graph.relations().stream()
                    .filter(relation -> "EXTENDS".equals(relation.type()))
                    .findFirst().orElseThrow();
            assertTrue(extendsRelation.attributes().keySet().containsAll(
                    Set.of("codeProjectId", "_kompileProjectionOwner", "filePath", "evidenceKind")));
            assertTrue(callRelations.stream().allMatch(relation ->
                    "heuristic-source-pattern".equals(relation.attributes().get("evidenceKind"))
                            && relation.attributes().containsKey("line")));

            // Non-code topology must survive a code-only refresh.
            graph.addEntity(GraphEntity.builder("manual:retained")
                    .type("MANUAL_ASSERTION")
                    .label("Retained assertion")
                    .build());
            GraphEntity serviceDeclaration = graph.entities().stream()
                    .filter(entity -> "LocalService".equals(entity.label())
                            && "CLASS".equals(entity.type())
                            && projectId.equals(entity.attributes().get("codeProjectId")))
                    .findFirst().orElseThrow();
            String serviceId = serviceDeclaration.id();
            String serviceFqn = String.valueOf(serviceDeclaration.attributes().get("fullyQualifiedName"));
            String serviceDeclarationKey = String.valueOf(
                    serviceDeclaration.attributes().get("declarationKey"));
            String sourceFilePath = root.relativize(source).toString().replace('\\', '/');
            GraphEntity serviceEntity = graph.entity(serviceId).orElseThrow();
            graph.addEntity(GraphEntity.builder(serviceId)
                    .type(serviceEntity.type())
                    .label(serviceEntity.label())
                    .tags(serviceEntity.tags())
                    .attributes(serviceEntity.attributes())
                    .embedding(new double[]{0.4, 0.6})
                    .build());
            graph.addRelation(GraphRelation.builder("manual:assertion", serviceId, "manual:retained")
                    .type("ASSERTED_RELATED_TO")
                    // User/cross-domain metadata may legitimately mention a code project; only the
                    // reserved projection-owner marker authorizes replacement during refresh.
                    .attribute("codeProjectId", projectId)
                    .embedding(new double[]{0.3, 0.7})
                    .build());
            graph.putEntityOpinion(serviceId, new Opinion(0.7, 0.1, 0.2, 0.5));
            graph.putRelationOpinion("manual:assertion", new Opinion(0.6, 0.1, 0.3, 0.5));
            graph.putVectorLayer(new VectorLayer("kge", VectorLayer.Target.ENTITY, 2, Dtype.F64)
                    .put(serviceId, new double[]{0.25, 0.75}));
            graph.putWeightMap("pslWeights", Map.of("callsResponse", 0.8));
            graph.putWeightMap("mebnWeights", Map.of("LocalService.answer", 0.65));
            graph.putArtifactText("models/mebn-weights.json", "{\"answer\":0.65}");
            graph.putArtifactText("models/fol-psl.json", "{\"callsResponse\":0.8}");
            graph.addEntity(GraphEntity.builder("legacy:reference")
                    .type("CODE_SYMBOL_REFERENCE")
                    .label("removed.Legacy")
                    .attribute("fullyQualifiedName", "removed.Legacy")
                    .attribute("codeProjectId", projectId)
                    .attribute("resolved", false)
                    .build());
            graph.save(first.graphPath());
            UnifiedGraphMutationJournal.appendAssertion(
                    first.graphPath(), List.of(),
                    new UnifiedGraphArchive.Link(-1, "journal:retained", serviceId,
                            "manual:retained", "JOURNAL_RELATED_TO", 1.0, 0.9,
                            true, true, Set.of(), null, Map.of("source", "journal"), null),
                    null);

            ObjectMapper mapper = new ObjectMapper();
            AgentConfig agent = AgentConfig.builder("local-code-kgraph-test")
                    .enabledTools(Set.of("*"))
                    .build();
            ToolContext context = new ToolContext("local-code-kgraph-test", agent,
                    new PermissionService(), root, new ToolRegistry(mapper));
            LocalProjectGraphBackend backend = new LocalProjectGraphBackend(mapper);
            ObjectNode query = mapper.createObjectNode()
                    .put("operation", "SEARCH")
                    .put("knowledgeBase", first.knowledgeBaseId())
                    .put("question", "LocalService");
            JsonNode queryResult = backend.reasoningQuery(query, context);
            assertTrue(queryResult.toString().contains("LocalService"), queryResult.toPrettyString());

            Path exported = root.resolve("exported-code.kgraph");
            ObjectNode export = mapper.createObjectNode()
                    .put("path", exported.toString())
                    .put("format", "kgraph")
                    .put("knowledgeBase", first.knowledgeBaseId());
            assertFalse(backend.exportGraph(export, context).isError());
            assertTrue(Files.isRegularFile(exported));
            assertTrue(UnifiedGraph.load(exported).entities().stream().anyMatch(entity ->
                    "LocalService".equals(entity.label())));

            Files.writeString(source, """
                package example;
                class BaseService {}
                final class LocalService {
                    String answer() {
                        return helper();
                    }
                    String answer(int mode) {
                        return helper() + mode;
                    }
                    String helper() {
                        return "updated portable graph";
                    }
                    }
                    """, StandardCharsets.UTF_8);
            try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
                indexer.index(root, projectId, null, null, false, quiet);
            }
            LocalCodeKGraphPublisher.ProjectionResult second =
                    LocalCodeKGraphPublisher.publish(root, projectId, null, null);
            UnifiedGraph stableRefresh = UnifiedGraph.load(second.graphPath());
            assertTrue(stableRefresh.entity("manual:retained").isPresent());
            assertTrue(stableRefresh.relations().stream().anyMatch(relation ->
                    "manual:assertion".equals(relation.id())));
            assertTrue(stableRefresh.relation("journal:retained").isPresent());
            assertFalse(UnifiedGraphMutationJournal.exists(second.graphPath()));
            assertNotNull(stableRefresh.entityOpinion(serviceId));
            assertNotNull(stableRefresh.relationOpinion("manual:assertion"));
            assertArrayEquals(new double[]{0.25, 0.75},
                    stableRefresh.vectorLayers().get("kge").get(serviceId));
            assertArrayEquals(new double[]{0.4, 0.6},
                    stableRefresh.entity(serviceId).orElseThrow().embedding(), 1e-6);
            assertArrayEquals(new double[]{0.3, 0.7},
                    stableRefresh.relation("manual:assertion").orElseThrow().embedding(), 1e-6);
            assertEquals(0.8, stableRefresh.weightMap("pslWeights").get("callsResponse"));
            assertEquals(0.65, stableRefresh.weightMap("mebnWeights").get("LocalService.answer"));
            assertEquals("{\"answer\":0.65}",
                    stableRefresh.artifactText("models/mebn-weights.json"));
            assertEquals("{\"callsResponse\":0.8}",
                    stableRefresh.artifactText("models/fol-psl.json"));
            assertEquals("COMPLETED",
                    stableRefresh.meta().get("phase.codeProjection." + projectId));
            assertTrue(stableRefresh.entity("legacy:reference").isEmpty());
            assertFalse(stableRefresh.relations().stream().anyMatch(relation ->
                    "EXTENDS".equals(relation.type())));

            String beforeDeletionGeneration = String.valueOf(
                    stableRefresh.meta().get("codeIndexGeneration." + projectId));
            Files.delete(source);
            try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
                indexer.index(root, projectId, null, null, false, quiet);
            }
            String beforeThirdPublishGeneration;
            try (IndexDatabase database = IndexDatabase.openReadOnly(indexDirectory)) {
                beforeThirdPublishGeneration = database.getIndexGeneration();
            }
            LocalCodeKGraphPublisher.ProjectionResult third =
                    LocalCodeKGraphPublisher.publish(root, projectId, null, null);
            UnifiedGraph updated = UnifiedGraph.load(third.graphPath());
            String afterThirdPublishGeneration;
            try (IndexDatabase database = IndexDatabase.openReadOnly(indexDirectory)) {
                afterThirdPublishGeneration = database.getIndexGeneration();
            }
            assertTrue(updated.entity("manual:retained").isPresent());
            assertNotEquals(beforeDeletionGeneration, beforeThirdPublishGeneration,
                    "deleting LocalService.java must commit a new SQLite generation");
            assertEquals(beforeThirdPublishGeneration, afterThirdPublishGeneration,
                    "the publisher must use the generation it just indexed");

            assertTrue(updated.entity(serviceId).isEmpty(),
                    "deleted declaration id must not survive the archive rewrite");
            assertFalse(updated.entities().stream().anyMatch(entity ->
                            "CLASS".equals(entity.type())
                                    && "LocalService".equals(entity.label())),
                    "deleted LocalService declaration class remains: " + updated.entities());
            assertFalse(updated.entities().stream().anyMatch(entity ->
                            "CLASS".equals(entity.type())
                                    && serviceFqn.equals(entity.attributes().get("fullyQualifiedName"))),
                    "deleted declaration FQN remains: " + updated.entities());
            assertFalse(updated.entities().stream().anyMatch(entity ->
                            serviceDeclarationKey.equals(entity.attributes().get("declarationKey"))),
                    "deleted declaration key remains: " + updated.entities());
            assertFalse(updated.entities().stream().anyMatch(entity ->
                            "CLASS".equals(entity.type())
                                    && String.valueOf(entity.attributes().get("filePath"))
                                            .endsWith(sourceFilePath)),
                    "deleted declaration source remains: " + updated.entities());

            List<String> incidentDeletedRelations = updated.relations().stream()
                    .filter(relation -> serviceId.equals(relation.sourceId())
                            || serviceId.equals(relation.targetId()))
                    .map(GraphRelation::id)
                    .toList();
            assertTrue(incidentDeletedRelations.isEmpty(),
                    "archive relations incident to a deleted declaration must be removed: "
                            + incidentDeletedRelations);
            assertFalse(updated.entityOpinions().containsKey(serviceId),
                    "deleted declaration opinion must be removed");
            assertFalse(updated.vectorLayers().values().stream()
                            .filter(layer -> layer.target() == VectorLayer.Target.ENTITY)
                            .anyMatch(layer -> layer.contains(serviceId)),
                    "deleted declaration vector rows must be removed");
            assertTrue(updated.relation("manual:assertion").isEmpty(),
                    "archive links cannot retain a dangling manual relation");
            assertTrue(updated.relation("journal:retained").isEmpty(),
                    "archive links cannot retain a dangling journal relation");
            assertFalse(updated.relationOpinions().containsKey("manual:assertion"),
                    "removed manual relation opinion must not be orphaned");

            List<GraphEntity> unresolvedLocalServiceReferences = updated.entities().stream()
                    .filter(entity -> "CODE_SYMBOL_REFERENCE".equals(entity.type())
                            && "LocalService".equals(entity.label()))
                    .toList();
            assertEquals(1, unresolvedLocalServiceReferences.size(),
                    "the valid unresolved UsesService reference must be retained exactly once");
            GraphEntity unresolvedReference = unresolvedLocalServiceReferences.get(0);
            assertEquals(projectId, unresolvedReference.attributes().get("codeProjectId"));
            assertEquals("LocalService",
                    unresolvedReference.attributes().get("fullyQualifiedName"));
            assertEquals(Boolean.FALSE, unresolvedReference.attributes().get("resolved"));
        } finally {
            if (Files.exists(indexDirectory)) {
                try (var walk = Files.walk(indexDirectory)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                    });
                }
            }
        }
    }

    @Test
    void rejectsUnsafeManifestKnowledgeBaseId() throws Exception {
        Path root = tempDir.resolve("unsafe-kb-code");
        Files.createDirectories(root);
        Files.writeString(root.resolve("Unsafe.java"), "final class Unsafe {}\n");
        String projectId = uniqueId("unsafe-kb-code");
        Path indexDirectory = LocalCodeIndexer.getIndexDir(projectId);
        try {
            LocalCodeIndexer indexer = new LocalCodeIndexer();
            try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
                indexer.index(root, projectId, null, null, true, quiet);
            }
            LocalCodeKGraphPublisher.publish(root, projectId, null, null);
            KompileProjectStore store = new KompileProjectStore();
            KompileProjectManifest manifest = store.load(root);
            KompileCodingProject codingProject = manifest.getCodingProjects().get(0);
            codingProject.getMetadata().put("knowledgeBaseId", "../outside");
            store.registerCodingProject(root, codingProject);

            assertThrows(IllegalArgumentException.class,
                    () -> LocalCodeKGraphPublisher.publish(root, projectId, null, null));
            assertFalse(Files.exists(tempDir.resolve("outside/graph.kgraph")));
        } finally {
            deleteTree(indexDirectory);
        }
    }

    @Test
    void nestedCodeProjectPublishesIntoFolderKnowledgeBaseIdentity() throws Exception {
        Path owner = tempDir.resolve("folder-owner");
        Path service = owner.resolve("service");
        Files.createDirectories(service);
        Files.writeString(service.resolve("Service.java"), "final class Service {}\n");

        String folderProjectId = uniqueId("folder-code");
        String serviceProjectId = uniqueId("service-code");
        KompileCodingProject folder = new KompileCodingProject();
        folder.setId(folderProjectId);
        folder.setCodeProjectId(folderProjectId);
        folder.setName("Folder");
        folder.setRootPath(owner.toString());
        KompileCodingProject nested = new KompileCodingProject();
        nested.setId(serviceProjectId);
        nested.setCodeProjectId(serviceProjectId);
        nested.setName("Service");
        nested.setRootPath(service.toString());
        KompileProjectInitRequest request = new KompileProjectInitRequest();
        request.setName("folder-owner");
        request.setIncludeStandardComponents(false);
        request.setCodingProjects(List.of(folder, nested));
        new KompileProjectStore().init(owner, request);

        Path indexDirectory = LocalCodeIndexer.getIndexDir(serviceProjectId);
        try {
            LocalCodeIndexer indexer = new LocalCodeIndexer();
            try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
                indexer.index(service, serviceProjectId, null, null, true, quiet);
            }
            LocalCodeKGraphPublisher.ProjectionResult projection =
                    LocalCodeKGraphPublisher.publish(service, serviceProjectId, null, null);
            assertEquals(folderProjectId + "-knowledge", projection.knowledgeBaseId());
            UnifiedGraph graph = UnifiedGraph.load(projection.graphPath());
            assertEquals(folderProjectId, graph.meta().get("projectId"));
            assertTrue(graph.entities().stream().anyMatch(entity ->
                    serviceProjectId.equals(entity.attributes().get("codeProjectId"))));
        } finally {
            deleteTree(indexDirectory);
        }
    }

    /**
     * Phase 3 regression guard: the learning lifecycle's artifacts and metadata
     * (KGE model json, PSL weights, MEBN theory/strengths, reasoningLearning.*
     * meta) must survive a code-graph rebuild, which deletes-and-reprojects only
     * code-projection-owned rows. Learned state is corpus-level and keyed by
     * stable ids, so refresh must be additive for it.
     */
    @Test
    void learningArtifactsSurviveCodeProjectionRefresh() throws Exception {
        Path root = tempDir.resolve("learning-survival-code");
        Path source = root.resolve("src/main/java/example/Survivor.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package example;
                final class Survivor {
                    String ping() { return "pong"; }
                }
                """, StandardCharsets.UTF_8);
        String projectId = uniqueId("learning-survival-code");
        Path indexDirectory = LocalCodeIndexer.getIndexDir(projectId);
        try {
            LocalCodeIndexer indexer = new LocalCodeIndexer();
            try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
                indexer.index(root, projectId, null, null, true, quiet);
            }
            LocalCodeKGraphPublisher.ProjectionResult first =
                    LocalCodeKGraphPublisher.publish(root, projectId, null, null);

            // Stamp exactly what UnifiedGraphKgeLifecycle/UnifiedGraphReasoningLifecycle
            // persist after a learning pass (artifact names match their constants).
            UnifiedGraph learned = UnifiedGraph.load(first.graphPath());
            learned.putArtifactText("models/kge.json", "{\"algorithm\":\"ROTATE\",\"dim\":32}");
            learned.putArtifactText("reasoning/psl-weights.json", "{\"calls\":0.42}");
            learned.putArtifactText("reasoning/mebn-theory.v1.json", "{\"mfrags\":1}");
            learned.putArtifactText("reasoning/mebn-strengths.json", "{\"strengths\":{}}");
            learned.meta("reasoningLearning.status", "COMPLETED")
                    .meta("reasoningLearning.folPsl", true)
                    .meta("reasoningLearning.mebn", true)
                    .meta("reasoningLearning.pslRules", 12)
                    .meta("reasoningLearning.mebnFragments", 5)
                    .meta("reasoningLearning.modelsTrained", 2)
                    .meta("reasoningLearning.consensusRounds", 1)
                    .meta("reasoningLearning.phase", "CODE_GRAPH_BUILD")
                    .meta("learning.execution", "SUBPROCESS");
            learned.save(first.graphPath());

            // Change the committed index generation, then re-project: learned state
            // must survive an actual graph rewrite rather than a deduplicated no-op.
            Files.writeString(source, """
                    package example;
                    final class Survivor {
                        String ping() { return "pong"; }
                        String status() { return "updated"; }
                    }
                    """, StandardCharsets.UTF_8);
            try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
                indexer.index(root, projectId, null, null, false, quiet);
            }
            LocalCodeKGraphPublisher.ProjectionResult second =
                    LocalCodeKGraphPublisher.publish(root, projectId, null, null);
            UnifiedGraph refreshed = UnifiedGraph.load(second.graphPath());

            assertEquals("{\"algorithm\":\"ROTATE\",\"dim\":32}",
                    refreshed.artifactText("models/kge.json"));
            assertEquals("{\"calls\":0.42}", refreshed.artifactText("reasoning/psl-weights.json"));
            assertEquals("{\"mfrags\":1}", refreshed.artifactText("reasoning/mebn-theory.v1.json"));
            assertEquals("{\"strengths\":{}}", refreshed.artifactText("reasoning/mebn-strengths.json"));
            assertEquals("COMPLETED", refreshed.meta().get("reasoningLearning.status"));
            assertEquals(Boolean.TRUE, refreshed.meta().get("reasoningLearning.folPsl"));
            assertEquals(Boolean.TRUE, refreshed.meta().get("reasoningLearning.mebn"));
            assertEquals(12, ((Number) refreshed.meta().get("reasoningLearning.pslRules")).intValue());
            assertEquals(5, ((Number) refreshed.meta().get("reasoningLearning.mebnFragments")).intValue());
            assertEquals("CODE_GRAPH_BUILD", refreshed.meta().get("reasoningLearning.phase"));
            assertEquals("SUBPROCESS", refreshed.meta().get("learning.execution"));
            // The projection itself must still have refreshed (fresh timestamps/version).
            assertEquals("COMPLETED", refreshed.meta().get("phase.codeProjection." + projectId));
            assertTrue(refreshed.entities().stream().anyMatch(entity ->
                    "Survivor".equals(entity.label())));
            assertTrue(refreshed.entities().stream().anyMatch(entity ->
                    "status".equals(entity.label())),
                    "the second publish must contain the newly indexed method");
        } finally {
            deleteTree(indexDirectory);
        }
    }

    private static void deleteTree(Path directory) throws Exception {
        if (!Files.exists(directory)) return;
        try (var walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        }
    }

    private static String uniqueId(String prefix) {
        return prefix + "-" + Long.toUnsignedString(System.nanoTime());
    }
}
