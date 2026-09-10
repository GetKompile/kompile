/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.unified;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.model.SimpleGraphRelation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedGraphArchiveEditorTest {

    @TempDir
    Path directory;

    @Test
    void replacesOwnedProjectionWithoutMaterializingSourceRelations() throws Exception {
        Path path = directory.resolve("editable.kgraph");
        UnifiedGraph source = new UnifiedGraph().graphId("editable");
        source.addEntity(entity("root", "ROOT", Map.of(), new double[]{1.0, 0.0}));
        source.addEntity(entity("document", "DOCUMENT", Map.of(), new double[]{0.8, 0.2}));
        source.addEntity(entity("old-code", "CODE", owned(), new double[]{0.0, 1.0}));
        source.addRelation(new SimpleGraphRelation("document-link", "root", "document", "HAS_DOCUMENT",
                1.0, 1.0, true, Set.of(), new double[]{0.7, 0.3}, null, Map.of()));
        source.addRelation(new SimpleGraphRelation("old-code-link", "root", "old-code", "HAS_CODE",
                1.0, 1.0, true, Set.of(), new double[]{0.1, 0.9}, null, owned()));
        source.putEntityVector("analysis", "root", new double[]{1.0, 0.0});
        source.putEntityVector("analysis", "old-code", new double[]{0.0, 1.0});
        source.putRelationVector("relation-analysis", "document-link", new double[]{0.7, 0.3});
        source.putRelationVector("relation-analysis", "old-code-link", new double[]{0.1, 0.9});
        source.putEntityOpinion("root", new Opinion(0.8, 0.1, 0.1, 0.5));
        source.putWeightMap("rules", Map.of("rule-a", 0.75));
        source.putArtifactText("model.txt", "preserved");
        source.saveCompact(path);

        UnifiedGraph additions = new UnifiedGraph();
        additions.addEntity(entity("new-code", "CODE", owned(), null));
        additions.addRelation(new SimpleGraphRelation("new-code-link", "root", "new-code", "HAS_CODE",
                1.0, 1.0, true, Set.of(), null, null, owned()));

        UnifiedGraphArchiveEditor.Result result = UnifiedGraphArchiveEditor.rewrite(
                path, path, additions,
                record -> !owned(record.entity().attributes()),
                link -> !owned(link.attributes()),
                Map.of("projectionGeneration", "next"));
        assertEquals(3, result.entities());
        assertEquals(2, result.relations());
        assertEquals(1, result.removedEntities());
        assertEquals(1, result.removedRelations());

        UnifiedGraph updated = UnifiedGraph.load(path);
        assertTrue(updated.containsEntity("root"));
        assertTrue(updated.containsEntity("document"));
        assertTrue(updated.containsEntity("new-code"));
        assertFalse(updated.containsEntity("old-code"));
        assertNotNull(updated.relation("document-link").orElse(null));
        assertNotNull(updated.relation("new-code-link").orElse(null));
        assertTrue(updated.relation("old-code-link").isEmpty());
        assertArrayEquals(new double[]{1.0, 0.0}, updated.entity("root").orElseThrow().embedding(), 1.0e-6);
        assertTrue(updated.vectorLayer("analysis").contains("root"));
        assertFalse(updated.vectorLayer("analysis").contains("old-code"));
        assertTrue(updated.vectorLayer("relation-analysis").contains("document-link"));
        assertFalse(updated.vectorLayer("relation-analysis").contains("old-code-link"));
        assertNotNull(updated.entityOpinion("root"));
        assertEquals(0.75, updated.weightMap("rules").get("rule-a"));
        assertEquals("preserved", updated.artifactText("model.txt"));
        assertEquals("next", updated.meta().get("projectionGeneration"));
        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(path)) {
            assertTrue(archive.hasAdjacencyIndex());
            archive.validateAdjacencyIndex();
        }
    }

    @Test
    void replacesAnExactRelationWhileKeepingItsVectorRow() throws Exception {
        Path path = directory.resolve("replace-relation.kgraph");
        UnifiedGraph source = new UnifiedGraph();
        source.addEntity(SimpleGraphEntity.of("a"));
        source.addEntity(SimpleGraphEntity.of("b"));
        source.addRelation(new SimpleGraphRelation("claim", "a", "b", "OLD",
                0.5, 0.5, true, Set.of(), new double[]{0.25, 0.75}, null, Map.of()));
        source.putEntityOpinion("a", new Opinion(0.7, 0.1, 0.2, 0.5));
        source.putRelationOpinion("claim", new Opinion(0.6, 0.2, 0.2, 0.5));
        source.saveCompact(path);

        UnifiedGraph additions = new UnifiedGraph();
        additions.addEntity(SimpleGraphEntity.of("a", "UPDATED", "A"));
        additions.addRelation(new SimpleGraphRelation("claim", "a", "b", "NEW",
                1.0, 0.9, true, Set.of(), null, null, Map.of("asserted", true)));
        UnifiedGraphArchiveEditor.rewrite(path, path, additions, null, null, Map.of());

        UnifiedGraph updated = UnifiedGraph.load(path);
        assertEquals("NEW", updated.relation("claim").orElseThrow().type());
        assertArrayEquals(new double[]{0.25, 0.75},
                updated.relation("claim").orElseThrow().embedding(), 1.0e-6);
        assertNotNull(updated.entityOpinion("a"));
        assertNotNull(updated.relationOpinion("claim"));
    }

    private static GraphEntity entity(
            String id, String type, Map<String, Object> attributes, double[] embedding) {
        return new SimpleGraphEntity(id, type, id, 1.0, 1.0, Set.of(), embedding, null, attributes);
    }

    private static Map<String, Object> owned() {
        return Map.of("projectionOwner", "local-code-index", "codeProjectId", "app");
    }

    private static boolean owned(Map<String, Object> attributes) {
        return "local-code-index".equals(attributes.get("projectionOwner"));
    }
}
