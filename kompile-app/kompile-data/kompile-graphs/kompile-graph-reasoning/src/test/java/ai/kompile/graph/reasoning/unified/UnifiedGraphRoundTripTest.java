/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.graph.reasoning.unified;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.model.SimpleGraphRelation;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedGraphRoundTripTest {

    private static final Instant TS = Instant.parse("2026-07-06T12:00:00Z");

    /** Build a graph that exercises every aspect a UnifiedGraph is meant to carry. */
    private UnifiedGraph buildRichGraph() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("k", 1L);
        nested.put("list", List.of(1L, 2L));

        Map<String, Object> n1Attrs = new LinkedHashMap<>();
        n1Attrs.put("nodeLevel", "ENTITY");
        n1Attrs.put("externalId", "src-1");
        n1Attrs.put("_source", "crawl");
        n1Attrs.put("count", 3L);
        n1Attrs.put("score", 0.75);
        n1Attrs.put("active", true);
        n1Attrs.put("validFrom", "2026-01-01T00:00:00Z");
        n1Attrs.put("validUntil", "2026-12-31T00:00:00Z");
        n1Attrs.put("additionalTypes", List.of("Employee", "Manager"));
        n1Attrs.put("nested", nested);

        SimpleGraphEntity n1 = new SimpleGraphEntity(
                "n1", "PERSON", "Alice", 0.9, 0.8,
                Set.of("vip", "internal"),
                new double[] {0.1, 0.2, 0.3, 0.4},
                TS, n1Attrs);
        SimpleGraphEntity n2 = SimpleGraphEntity.of("n2"); // minimal, no embedding
        SimpleGraphEntity n3 = new SimpleGraphEntity(
                "n3", "ORG", "Acme", 1.0, 1.0, Set.of(),
                new double[] {-0.5, 0.5, 0.0, 0.25}, null, Map.of());

        Map<String, Object> r1Attrs = new LinkedHashMap<>();
        r1Attrs.put("_provenanceType", "EXTRACTED");
        r1Attrs.put("mentions", 5L);
        SimpleGraphRelation r1 = new SimpleGraphRelation(
                "r1", "n1", "n2", "WORKS_AT", 0.7, 0.9, true,
                Set.of("strong"), new double[] {0.11, 0.22}, TS, r1Attrs);
        SimpleGraphRelation r2 = SimpleGraphRelation.undirected("r2", "n2", "n3", "RELATED", 0.5);

        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(n1).addEntity(n2).addEntity(n3);
        graph.addRelation(r1).addRelation(r2);

        // Subjective-logic opinions.
        graph.putEntityOpinion("n1", Opinion.fromBetaEvidence(8, 2));
        graph.putRelationOpinion("r1", Opinion.fromSoftTruth(0.7, 4));

        // Additional weight-vector layers: a KGE entity layer + a GLOBAL relation-type layer.
        VectorLayer kge = new VectorLayer("kge", VectorLayer.Target.ENTITY, 3, Dtype.F64);
        kge.put("n1", new double[] {1.0, 2.0, 3.0});
        kge.put("n3", new double[] {4.0, 5.0, 6.0});
        graph.putVectorLayer(kge);

        VectorLayer kgeRel = new VectorLayer("kgeRelation", VectorLayer.Target.GLOBAL, 2, Dtype.F64);
        kgeRel.put("WORKS_AT", new double[] {0.7, 0.8});
        kgeRel.put("RELATED", new double[] {0.9, 1.0});
        graph.putVectorLayer(kgeRel);

        // Named learned weight map.
        Map<String, Double> psl = new LinkedHashMap<>();
        psl.put("Precedes", 1.5);
        psl.put("Occurs", 0.8);
        graph.putWeightMap("pslWeights", psl);

        // Bundled model artifacts — any serialized model rides along in the one file.
        graph.putArtifactText("typeRegistry.json", "{\"types\":[{\"name\":\"PERSON\"}]}");
        graph.putArtifact("kge-model.bin", new byte[] {1, 2, 3, 4, 5});

        // Graph-level meta.
        graph.graphId("factsheet_42").factSheetId(42L).meta("note", "dogfood");
        return graph;
    }

    @Test
    void isUsableAsAReasoningGraphEverywhere() {
        // The whole point: one structure you pass around AS a graph.
        ReasoningGraph asGraph = buildRichGraph();
        assertEquals(3, asGraph.entityCount());
        assertEquals(2, asGraph.relationCount());
        assertTrue(asGraph.containsEntity("n3"));
        assertEquals(1, asGraph.outgoing("n1").size(), "n1 -> n2");
        assertEquals(1, asGraph.incoming("n2").size(), "r1 targets n2");
        assertEquals(2, asGraph.relationsOf("n2").size(), "r1 (in) + r2 (out)");
        assertArrayEquals(new double[] {0.1, 0.2, 0.3, 0.4},
                asGraph.entity("n1").orElseThrow().embedding(), 0.0);
    }

    @Test
    void saveLoadPreservesEveryAspect(@TempDir Path dir) throws IOException {
        UnifiedGraph original = buildRichGraph();
        Path file = dir.resolve("graph.kgraph");
        original.save(file, Dtype.F64); // F64 => primary embeddings survive exactly

        UnifiedGraph back = UnifiedGraph.load(file);

        // ── Topology (as a graph) ──
        assertEquals(3, back.entityCount());
        assertEquals(2, back.relationCount());
        assertEquals(1, back.outgoing("n1").size());
        assertEquals(2, back.relationsOf("n2").size());

        // ── Entity n1: all scalar/temporal/tag/attribute/embedding aspects ──
        GraphEntity n1 = back.entity("n1").orElseThrow();
        assertEquals("PERSON", n1.type());
        assertEquals("Alice", n1.label());
        assertEquals(0.9, n1.weight(), 0.0);
        assertEquals(0.8, n1.confidence(), 0.0);
        assertEquals(Set.of("vip", "internal"), n1.tags());
        assertEquals(TS, n1.timestamp());
        assertArrayEquals(new double[] {0.1, 0.2, 0.3, 0.4}, n1.embedding(), 0.0);

        Map<String, Object> expectedAttrs = buildRichGraph().entity("n1").orElseThrow().attributes();
        assertEquals(expectedAttrs, n1.attributes());

        // Derived aspects reconstructed purely from attributes:
        assertNotNull(n1.validTime(), "validFrom/validUntil should reconstruct a valid-time interval");
        assertTrue(n1.hasTypeMembership("Employee"), "additionalTypes should feed typeMemberships");
        assertTrue(n1.hasTypeMembership("PERSON"));

        // ── Entity n2: minimal node, sparse coverage (no embedding) ──
        GraphEntity n2 = back.entity("n2").orElseThrow();
        assertEquals("", n2.type());
        assertEquals(1.0, n2.weight(), 0.0);
        assertNull(n2.embedding(), "n2 had no embedding; the layer is sparse");
        assertTrue(n2.tags().isEmpty());
        assertTrue(n2.attributes().isEmpty());

        // ── Relations ──
        GraphRelation r1 = findRelation(back, "r1");
        assertEquals("n1", r1.sourceId());
        assertEquals("n2", r1.targetId());
        assertEquals("WORKS_AT", r1.type());
        assertEquals(0.7, r1.weight(), 0.0);
        assertEquals(0.9, r1.confidence(), 0.0);
        assertTrue(r1.directed());
        assertEquals(Set.of("strong"), r1.tags());
        assertEquals(TS, r1.timestamp());
        assertArrayEquals(new double[] {0.11, 0.22}, r1.embedding(), 0.0);
        assertEquals("EXTRACTED", r1.attributes().get("_provenanceType"));
        assertEquals(5L, r1.attributes().get("mentions"));

        GraphRelation r2 = findRelation(back, "r2");
        assertTrue(!r2.directed());
        assertEquals(0.5, r2.weight(), 0.0);

        // ── Opinions ──
        assertEquals(Opinion.fromBetaEvidence(8, 2), back.entityOpinion("n1"));
        assertEquals(Opinion.fromSoftTruth(0.7, 4), back.relationOpinion("r1"));

        // ── Additional vector layers ──
        VectorLayer kge = back.vectorLayer("kge");
        assertNotNull(kge);
        assertEquals(3, kge.dim());
        assertArrayEquals(new double[] {1.0, 2.0, 3.0}, kge.get("n1"), 0.0);
        assertArrayEquals(new double[] {4.0, 5.0, 6.0}, kge.get("n3"), 0.0);

        VectorLayer kgeRel = back.vectorLayer("kgeRelation");
        assertNotNull(kgeRel);
        assertEquals(VectorLayer.Target.GLOBAL, kgeRel.target());
        assertArrayEquals(new double[] {0.7, 0.8}, kgeRel.get("WORKS_AT"), 0.0);

        // ── Named weight map ──
        assertEquals(1.5, back.weightMap("pslWeights").get("Precedes"), 0.0);
        assertEquals(0.8, back.weightMap("pslWeights").get("Occurs"), 0.0);

        // ── Bundled model artifacts ──
        assertEquals("{\"types\":[{\"name\":\"PERSON\"}]}", back.artifactText("typeRegistry.json"));
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, back.artifact("kge-model.bin"));

        // ── Graph-level meta ──
        assertEquals("factsheet_42", back.graphId());
        assertEquals(42L, back.factSheetId());
        assertEquals("dogfood", back.meta().get("note"));
    }

    @Test
    void structuredProjectedEntityIdsSurvivePortableAndCompactMetadataSerialization(
            @TempDir Path dir) throws IOException {
        List<String> projectedIds = List.of("plain", "entity,with,comma", "id with spaces");
        UnifiedGraph graph = new UnifiedGraph()
                .addEntity("plain", "THING", "Plain")
                .addEntity("entity,with,comma", "THING", "Comma")
                .addEntity("id with spaces", "THING", "Spaced")
                .addRelation("r1", "plain", "entity,with,comma", "LINKS", 1.0)
                .meta("reasoningLearning.projectedEntityIds", projectedIds);

        Path portable = dir.resolve("projected-ids.kgraph");
        graph.save(portable);
        UnifiedGraph portableLoaded = UnifiedGraph.load(portable);
        assertTrue(portableLoaded.meta().get("reasoningLearning.projectedEntityIds")
                instanceof List<?>);
        assertEquals(projectedIds,
                portableLoaded.meta().get("reasoningLearning.projectedEntityIds"));

        Path compact = dir.resolve("projected-ids-compact.kgraph");
        graph.saveCompact(compact);
        UnifiedGraph compactLoaded = UnifiedGraph.load(compact);
        assertTrue(compactLoaded.meta().get("reasoningLearning.projectedEntityIds")
                instanceof List<?>);
        assertEquals(projectedIds,
                compactLoaded.meta().get("reasoningLearning.projectedEntityIds"));
    }

    @Test
    void stableReadReferenceUsesTheSourceOwnedFilesystem(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("graph.kgraph");
        buildRichGraph().save(file);

        try (UnifiedGraphReader.StableArchiveReference stable =
                     UnifiedGraphReader.stableArchiveReference(
                             file, UnifiedGraphReader.Limits.systemDefaults())) {
            assertEquals(file.getParent().toRealPath(),
                    stable.path().getParent().getParent().toRealPath());
            assertTrue(Files.isRegularFile(stable.path()));
        }

        try (var remaining = Files.list(dir)) {
            assertEquals(List.of("graph.kgraph"),
                    remaining.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void compactV3CursorAndMaterializedLoadPreserveRichLinks(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("compact-v3.kgraph");
        buildRichGraph().saveCompact(file, Dtype.F64);

        try (ZipFile zip = new ZipFile(file.toFile())) {
            assertNull(zip.getEntry(UnifiedGraphFormat.ENTRY_RELATIONS));
            assertEquals(ZipEntry.STORED,
                    zip.getEntry(UnifiedGraphFormat.ENTRY_COMPACT_LINKS).getMethod());
            assertEquals(ZipEntry.STORED,
                    zip.getEntry(UnifiedGraphFormat.ENTRY_COMPACT_ADJACENCY).getMethod());
        }

        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(file);
             UnifiedGraphArchive.LinkCursor links = archive.openLinks()) {
            assertEquals(3, archive.formatVersion());
            assertEquals(3, archive.entityCount());
            assertEquals(2, archive.linkCount());
            UnifiedGraphArchive.Link first = links.next();
            assertEquals("r1", first.id());
            assertEquals("n1", first.sourceId());
            assertEquals("n2", first.targetId());
            assertEquals("WORKS_AT", first.type());
            assertEquals(0.7, first.weight(), 0.0);
            assertEquals(0.9, first.confidence(), 0.0);
            assertTrue(first.directed());
            assertTrue(first.hasProperties());
            assertEquals(Set.of("strong"), first.tags());
            assertEquals(TS, first.timestamp());
            assertEquals(5L, first.attributes().get("mentions"));
            assertEquals(Opinion.fromSoftTruth(0.7, 4), first.opinion());
            UnifiedGraphArchive.Link second = links.next();
            assertEquals("r2", second.id());
            assertFalse(second.directed());
            assertFalse(second.hasProperties());
            assertNull(links.next());
            assertTrue(archive.hasAdjacencyIndex());
            archive.validateAdjacencyIndex();
        }

        UnifiedGraph loaded = UnifiedGraph.load(file);
        GraphRelation r1 = loaded.relation("r1").orElseThrow();
        assertEquals(Set.of("strong"), r1.tags());
        assertEquals(TS, r1.timestamp());
        assertEquals(5L, r1.attributes().get("mentions"));
        assertEquals(Opinion.fromSoftTruth(0.7, 4), loaded.relationOpinion("r1"));
        assertArrayEquals(new double[] {0.11, 0.22}, r1.embedding(), 0.0);
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, loaded.artifact("kge-model.bin"));
    }

    @Test
    void compactNeighborhoodRetainsOnlyBoundedExactIdTopology(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("compact-neighborhood.kgraph");
        buildRichGraph().saveCompact(file, Dtype.F64);

        UnifiedGraph neighborhood;
        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(file)) {
            neighborhood = archive.materializeNeighborhood(
                    List.of("n1"), List.of("n1"), GraphQueryEngine.Direction.OUTGOING,
                    1, 10, 10);
        }

        assertEquals(Set.of("n1", "n2"), neighborhood.entities().stream()
                .map(GraphEntity::id).collect(java.util.stream.Collectors.toSet()));
        assertEquals(List.of("r1"), neighborhood.relations().stream()
                .map(GraphRelation::id).toList());
        assertEquals(5L, neighborhood.relation("r1").orElseThrow().attributes().get("mentions"));
        assertEquals(Opinion.fromSoftTruth(0.7, 4), neighborhood.relationOpinion("r1"));
        assertEquals(false, neighborhood.meta().get("truncated"));
        assertEquals("mmap-csr", neighborhood.meta().get("adjacencyIndex"));
    }

    @Test
    void compactEndpointDictionaryExcludesIsolatedEntitiesAndAllowsEmptyRelationType(
            @TempDir Path dir) throws IOException {
        UnifiedGraph graph = new UnifiedGraph()
                .addEntity(SimpleGraphEntity.of("source"))
                .addEntity(SimpleGraphEntity.of("target"))
                .addEntity(SimpleGraphEntity.of("isolated"))
                .addRelation(new SimpleGraphRelation(
                        "untyped", "source", "target", null, 1.0, 1.0,
                        true, Set.of(), null, null, Map.of()));
        Path file = dir.resolve("compact-endpoints.kgraph");
        graph.saveCompact(file);

        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(file);
             UnifiedGraphArchive.LinkCursor cursor = archive.openLinks()) {
            Map<?, ?> topology = (Map<?, ?>) archive.manifest().get("topology");
            assertEquals(2L, topology.get("nodeCount"));
            assertEquals("", cursor.next().type());
            assertNull(cursor.next());
        }
        assertTrue(UnifiedGraph.load(file).containsEntity("isolated"));
    }

    @Test
    void orphanOpinionsRoundTrip(@TempDir Path dir) throws IOException {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity("n1", "PERSON", "Alice");
        graph.addEntity("n2", "DOCUMENT", "Doc");
        graph.addRelation("r1", "n1", "n2", "MENTIONS", 0.7);
        graph.putEntityOpinion("missing-entity", Opinion.fromBetaEvidence(3, 1));
        graph.putRelationOpinion("missing-relation", Opinion.fromSoftTruth(0.6, 2));

        Path file = dir.resolve("orphan-opinions.kgraph");
        graph.save(file, Dtype.F64);

        UnifiedGraph back = UnifiedGraph.load(file);
        assertEquals(Opinion.fromBetaEvidence(3, 1), back.entityOpinion("missing-entity"));
        assertEquals(Opinion.fromSoftTruth(0.6, 2), back.relationOpinion("missing-relation"));
    }

    @Test
    void streamSaveLoad() throws IOException {
        UnifiedGraph original = buildRichGraph();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        original.save(bos, Dtype.F64);

        UnifiedGraph back = UnifiedGraph.load(new ByteArrayInputStream(bos.toByteArray()));
        assertEquals(3, back.entityCount());
        assertEquals(2, back.relationCount());
        assertArrayEquals(new double[] {0.1, 0.2, 0.3, 0.4},
                back.entity("n1").orElseThrow().embedding(), 0.0);
        assertEquals(42L, back.factSheetId());
    }

    @Test
    void convenienceVectorAddersCreateLayers(@TempDir Path dir) throws IOException {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity("n1", "T", "one").addEntity("n2", "T", "two");
        graph.putEntityVector("kge", "n1", new double[] {1.0, 2.0});   // integer values => F32-exact
        graph.putEntityVector("kge", "n2", new double[] {3.0, 4.0});
        graph.putGlobalVector("rel", "KNOWS", new double[] {5.0, 6.0});

        assertEquals(2, graph.vectorLayer("kge").size());
        assertEquals(VectorLayer.Target.GLOBAL, graph.vectorLayer("rel").target());

        Path file = dir.resolve("conv.kgraph");
        graph.save(file);
        UnifiedGraph back = UnifiedGraph.load(file);
        assertArrayEquals(new double[] {1.0, 2.0}, back.vectorLayer("kge").get("n1"), 0.0);
        assertArrayEquals(new double[] {5.0, 6.0}, back.vectorLayer("rel").get("KNOWS"), 0.0);
    }

    @Test
    void defaultF32PrimaryEmbeddingIsWithinTolerance(@TempDir Path dir) throws IOException {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(new SimpleGraphEntity(
                "e", "T", "L", 1.0, 1.0, Set.of(),
                new double[] {0.1234567, -0.7654321, 0.999999}, null, Map.of()));
        Path file = dir.resolve("f32.kgraph");
        graph.save(file); // default F32

        UnifiedGraph back = UnifiedGraph.load(file);
        assertArrayEquals(new double[] {0.1234567, -0.7654321, 0.999999},
                back.entity("e").orElseThrow().embedding(), 1e-6);
    }

    @Test
    void emptyGraphRoundTrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("empty.kgraph");
        new UnifiedGraph().graphId("empty").save(file);

        UnifiedGraph back = UnifiedGraph.load(file);
        assertEquals(0, back.entityCount());
        assertEquals(0, back.relationCount());
        assertTrue(back.vectorLayers().isEmpty());
        assertEquals("empty", back.graphId());
    }

    @Test
    void rejectsNewerGraphFormat(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("future.kgraph");
        writeRawGraph(file, Map.of(
                "manifest.json", "{\"format\":\"kompile-graph\",\"formatVersion\":4}",
                "entities.jsonl", "",
                "relations.jsonl", ""));

        IOException error = assertThrows(IOException.class, () -> UnifiedGraph.load(file));
        assertTrue(error.getMessage().contains("formatVersion"));
    }

    @Test
    void readsV1WithoutSchemaIndex(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("legacy-v1.kgraph");
        writeRawGraph(file, Map.of(
                "manifest.json", "{\"format\":\"kompile-graph\",\"formatVersion\":1,"
                        + "\"counts\":{\"entities\":1,\"relations\":0,\"vectorLayers\":0},"
                        + "\"embeddingDim\":0,\"sections\":[\"entities.jsonl\",\"relations.jsonl\"],"
                        + "\"vectorLayers\":[]}",
                "entities.jsonl", "{\"id\":\"legacy\",\"type\":\"PERSON\",\"label\":\"Alice\"}\n",
                "relations.jsonl", ""));

        UnifiedGraph graph = UnifiedGraph.load(file);

        assertEquals(1, graph.entityCount());
        assertEquals("Alice", graph.entity("legacy").orElseThrow().label());
    }

    @Test
    void explicitTypeMembershipsSurviveCanonicalLoadAndResave(@TempDir Path dir) throws IOException {
        String manifest = "{\"format\":\"kompile-graph\",\"formatVersion\":2,"
                + "\"counts\":{\"entities\":1,\"relations\":0,\"vectorLayers\":0},"
                + "\"embeddingDim\":0,"
                + "\"sections\":[\"schemas/index.json\",\"entities.jsonl\",\"relations.jsonl\"],"
                + "\"vectorLayers\":[]}";
        String schema = "{\"format\":\"kompile-unified-schema\",\"version\":1,"
                + "\"entityCount\":1,\"relationCount\":0,\"entityTypes\":[\"PERSON\"],"
                + "\"relationTypes\":[],\"entityAttributeKeys\":[],\"relationAttributeKeys\":[],"
                + "\"declaredSchemaArtifacts\":[]}";
        Path source = dir.resolve("explicit-memberships.kgraph");
        writeRawGraph(source, Map.of(
                "manifest.json", manifest,
                "schemas/index.json", schema,
                "entities.jsonl", "{\"id\":\"alice\",\"type\":\"PERSON\","
                        + "\"typeMemberships\":[\"PERSON\",\"Employee\"],\"label\":\"Alice\"}\n",
                "relations.jsonl", ""));

        UnifiedGraph loaded = UnifiedGraph.load(source);
        assertTrue(loaded.entity("alice").orElseThrow().hasTypeMembership("Employee"));

        Path migrated = dir.resolve("migrated.kgraph");
        loaded.save(migrated);
        UnifiedGraph reloaded = UnifiedGraph.load(migrated);
        assertTrue(reloaded.entity("alice").orElseThrow().hasTypeMembership("Employee"));
    }

    @Test
    void rejectsInvalidV2SchemaIndex(@TempDir Path dir) throws IOException {
        String manifest = "{\"format\":\"kompile-graph\",\"formatVersion\":2,"
                + "\"counts\":{\"entities\":0,\"relations\":0,\"vectorLayers\":0},"
                + "\"embeddingDim\":0,"
                + "\"sections\":[\"schemas/index.json\",\"entities.jsonl\",\"relations.jsonl\"],"
                + "\"vectorLayers\":[]}";
        Path file = dir.resolve("bad-schema.kgraph");
        writeRawGraph(file, Map.of(
                "manifest.json", manifest,
                "schemas/index.json", "{}",
                "entities.jsonl", "",
                "relations.jsonl", ""));

        IOException error = assertThrows(IOException.class, () -> UnifiedGraph.load(file));
        assertTrue(error.getMessage().contains("schema index"));
    }

    @Test
    void rejectsUnsafeAndCaseCollidingEntries(@TempDir Path dir) throws IOException {
        Path unsafe = dir.resolve("unsafe.kgraph");
        writeRawGraph(unsafe, Map.of(
                "manifest.json", "{\"format\":\"kompile-graph\",\"formatVersion\":1}",
                "../escape", ""));
        assertThrows(IOException.class, () -> UnifiedGraph.load(unsafe));

        Path collision = dir.resolve("collision.kgraph");
        writeRawGraph(collision, Map.of(
                "manifest.json", "{\"format\":\"kompile-graph\",\"formatVersion\":1}",
                "MANIFEST.JSON", ""));
        assertThrows(IOException.class, () -> UnifiedGraph.load(collision));
    }

    @Test
    void rejectsTruncatedOrCountMismatchedManifestInventory(@TempDir Path dir) throws IOException {
        String manifest = "{\"format\":\"kompile-graph\",\"formatVersion\":2,"
                + "\"counts\":{\"entities\":0,\"relations\":0,\"vectorLayers\":0},"
                + "\"embeddingDim\":0,"
                + "\"sections\":[\"schemas/index.json\",\"entities.jsonl\",\"relations.jsonl\"],"
                + "\"vectorLayers\":[]}";
        Path truncated = dir.resolve("truncated.kgraph");
        writeRawGraph(truncated, Map.of("manifest.json", manifest, "entities.jsonl", ""));
        IOException missing = assertThrows(IOException.class, () -> UnifiedGraph.load(truncated));
        assertTrue(missing.getMessage().contains("inventory mismatch"));

        Path wrongCount = dir.resolve("wrong-count.kgraph");
        String wrongCountSchema = "{\"format\":\"kompile-unified-schema\",\"version\":1,"
                + "\"entityCount\":1,\"relationCount\":0,\"entityTypes\":[],\"relationTypes\":[],"
                + "\"entityAttributeKeys\":[],\"relationAttributeKeys\":[],"
                + "\"declaredSchemaArtifacts\":[]}";
        writeRawGraph(wrongCount, Map.of(
                "manifest.json", manifest.replace("\"entities\":0", "\"entities\":1"),
                "schemas/index.json", wrongCountSchema,
                "entities.jsonl", "", "relations.jsonl", ""));
        IOException count = assertThrows(IOException.class, () -> UnifiedGraph.load(wrongCount));
        assertTrue(count.getMessage().contains("entity count mismatch"));
    }

    @Test
    void fileSaveSafelyReplacesAnExistingDestination(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("replace.kgraph");
        Files.writeString(file, "not a graph", StandardCharsets.UTF_8);

        new UnifiedGraph().graphId("replacement").save(file);

        assertEquals("replacement", UnifiedGraph.load(file).graphId());
        try (var files = Files.list(dir)) {
            assertEquals(List.of("replace.kgraph"),
                    files.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void reservedVectorLayerNamesAreRejected() {
        UnifiedGraph graph = new UnifiedGraph();
        assertThrows(IllegalArgumentException.class, () ->
                graph.putVectorLayer(new VectorLayer("embedding", VectorLayer.Target.ENTITY, 2, Dtype.F32)));
        assertThrows(IllegalArgumentException.class, () ->
                graph.putEntityVector("relationEmbedding", "n1", new double[] {1.0}));
    }

    @Test
    void ofExistingGraphAdoptsTopology(@TempDir Path dir) throws IOException {
        UnifiedGraph original = buildRichGraph();
        UnifiedGraph wrapped = UnifiedGraph.of(original);
        Path file = dir.resolve("of.kgraph");
        wrapped.save(file, Dtype.F64);

        UnifiedGraph back = UnifiedGraph.load(file);
        assertEquals(3, back.entityCount());
        assertArrayEquals(new double[] {0.1, 0.2, 0.3, 0.4},
                back.entity("n1").orElseThrow().embedding(), 0.0);
    }

    @Test
    void streamsStructuralEntriesBeyondBufferedEntryLimit(@TempDir Path dir) throws IOException {
        UnifiedGraph graph = verboseRelationGraph(24, 256);
        Path file = dir.resolve("streamed-structure.kgraph");
        graph.save(file);
        UnifiedGraphReader.Limits limits = testLimits(1_024, 1024 * 1024, 4_096);

        UnifiedGraph fromPath = UnifiedGraphReader.read(file, limits);
        assertEquals(24, fromPath.relationCount());

        try (var in = Files.newInputStream(file)) {
            UnifiedGraph fromStream = UnifiedGraphReader.read(in, limits);
            assertEquals(24, fromStream.relationCount());
        }
    }

    @Test
    void rejectsOversizedJsonlRowWhileStreaming(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("oversized-row.kgraph");
        verboseRelationGraph(1, 2_048).save(file);

        IOException error = assertThrows(IOException.class,
                () -> UnifiedGraphReader.read(file, testLimits(8_192, 1024 * 1024, 512)));
        assertTrue(error.getMessage().contains("relations.jsonl"));
        assertTrue(error.getMessage().contains("row exceeds character limit"));
    }

    @Test
    void bufferedArtifactsStillHonorPerEntryLimit(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("oversized-artifact.kgraph");
        UnifiedGraph graph = verboseRelationGraph(1, 32);
        graph.putArtifact("large.bin", new byte[2_048]);
        graph.save(file);

        IOException error = assertThrows(IOException.class,
                () -> UnifiedGraphReader.read(file, testLimits(1_024, 1024 * 1024, 4_096)));
        assertTrue(error.getMessage().contains("models/large.bin"));
        assertTrue(error.getMessage().contains("entry exceeds size limit"));
    }

    @Test
    void writerOmitsCanonicalDefaultsFromTopologyRows(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("compact-defaults.kgraph");
        UnifiedGraph graph = new UnifiedGraph()
                .addEntity(SimpleGraphEntity.of("source"))
                .addEntity(SimpleGraphEntity.of("target"))
                .addRelation(SimpleGraphRelation.directed(
                        "r", "source", "target", "CALLS", 1.0));
        graph.save(file);

        try (ZipFile zip = new ZipFile(file.toFile())) {
            String entities = new String(
                    zip.getInputStream(zip.getEntry("entities.jsonl")).readAllBytes(), StandardCharsets.UTF_8);
            String relations = new String(
                    zip.getInputStream(zip.getEntry("relations.jsonl")).readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(entities.contains("\"weight\""));
            assertFalse(entities.contains("\"confidence\""));
            assertFalse(entities.contains("\"type\""));
            assertFalse(entities.contains("\"label\""));
            assertFalse(relations.contains("\"weight\""));
            assertFalse(relations.contains("\"confidence\""));
            assertFalse(relations.contains("\"directed\""));
        }

        UnifiedGraph loaded = UnifiedGraph.load(file);
        GraphRelation relation = loaded.relation("r").orElseThrow();
        assertEquals(1.0, relation.weight());
        assertEquals(1.0, relation.confidence());
        assertTrue(relation.directed());
        assertEquals("", loaded.entity("source").orElseThrow().type());
    }

    @Test
    void rejectsEntryCountBeforeZipFileIndexing(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("too-many-entries.kgraph");
        writeRawGraph(file, Map.of(
                "manifest.json", "{}",
                "entities.jsonl", "",
                "relations.jsonl", "",
                "extra.json", "{}"));

        UnifiedGraphReader.Limits base = testLimits(1_024, 1024 * 1024, 4_096);
        UnifiedGraphReader.Limits limits = new UnifiedGraphReader.Limits(
                3, base.maxEntryBytes(), base.maxTotalBytes(), base.maxManifestBytes(),
                base.maxEntryNameLength(), base.maxCentralDirectoryBytes(),
                base.maxTotalDecodedVectorValues(), base.maxArchiveBytes(),
                base.maxJsonlRowChars(), base.maxEntityCount(), base.maxRelationCount());
        IOException error = assertThrows(IOException.class, () -> UnifiedGraphReader.read(file, limits));
        assertTrue(error.getMessage().contains("too many entries"));
    }

    @Test
    void rejectsForgedEocdEntryCountBeforeZipFileIndexing(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("forged-entry-count.kgraph");
        writeRawGraph(file, Map.of(
                "manifest.json", "{}",
                "entities.jsonl", "",
                "relations.jsonl", "",
                "extra.json", "{}"));
        forgeEocdEntryCount(file, 1);

        UnifiedGraphReader.Limits base = testLimits(1_024, 1024 * 1024, 4_096);
        UnifiedGraphReader.Limits limits = new UnifiedGraphReader.Limits(
                3, base.maxEntryBytes(), base.maxTotalBytes(), base.maxManifestBytes(),
                base.maxEntryNameLength(), base.maxCentralDirectoryBytes(),
                base.maxTotalDecodedVectorValues(), base.maxArchiveBytes(),
                base.maxJsonlRowChars(), base.maxEntityCount(), base.maxRelationCount());
        IOException error = assertThrows(IOException.class, () -> UnifiedGraphReader.read(file, limits));
        assertTrue(error.getMessage().contains("too many entries")
                || error.getMessage().contains("entry count mismatch"));
    }

    @Test
    void rejectsStructuralRowsBeyondManifestCountImmediately(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("extra-structural-row.kgraph");
        writeRawGraph(file, Map.of(
                "manifest.json", "{\"format\":\"kompile-graph\",\"formatVersion\":1,"
                        + "\"counts\":{\"entities\":0,\"relations\":0,\"vectorLayers\":0},"
                        + "\"embeddingDim\":0,\"sections\":[\"entities.jsonl\",\"relations.jsonl\"],"
                        + "\"vectorLayers\":[]}",
                "entities.jsonl", "",
                "relations.jsonl", "{\"id\":\"r\",\"sourceId\":\"a\",\"targetId\":\"b\","
                        + "\"type\":\"CALLS\"}\n"));

        IOException error = assertThrows(IOException.class,
                () -> UnifiedGraphReader.read(file, testLimits(2_048, 1024 * 1024, 4_096)));
        assertTrue(error.getMessage().contains("more rows than declared"));
    }

    @Test
    void structuralStreamingHonorsCombinedExpansionBudget(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("expanded-budget.kgraph");
        verboseRelationGraph(20, 256).save(file);

        IOException error = assertThrows(IOException.class,
                () -> UnifiedGraphReader.read(file, testLimits(1_024, 4_096, 4_096)));
        assertTrue(error.getMessage().contains("total size limit"));
    }

    @Test
    void rejectsNonStringRelationTypeInsteadOfBypassingSchemaParity(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("numeric-relation-type.kgraph");
        writeRawGraph(file, Map.of(
                "manifest.json", "{\"format\":\"kompile-graph\",\"formatVersion\":1,"
                        + "\"counts\":{\"entities\":0,\"relations\":1,\"vectorLayers\":0},"
                        + "\"embeddingDim\":0,\"sections\":[\"entities.jsonl\",\"relations.jsonl\"],"
                        + "\"vectorLayers\":[]}",
                "entities.jsonl", "",
                "relations.jsonl", "{\"id\":\"r\",\"sourceId\":\"a\",\"targetId\":\"b\","
                        + "\"type\":42}\n"));

        IOException error = assertThrows(IOException.class,
                () -> UnifiedGraphReader.read(file, testLimits(2_048, 1024 * 1024, 4_096)));
        assertTrue(error.getMessage().contains("type must be a string"));
    }

    @Test
    void rejectsAggregateVectorBudgetBeforeDecodingNextLayer(@TempDir Path dir) throws IOException {
        UnifiedGraph graph = new UnifiedGraph()
                .addEntity(GraphEntity.builder("e").type("ENTITY").label("e").build());
        VectorLayer first = new VectorLayer("first", VectorLayer.Target.ENTITY, 2, Dtype.F32);
        first.put("e", new double[]{1.0, 2.0});
        VectorLayer second = new VectorLayer("second", VectorLayer.Target.ENTITY, 2, Dtype.F32);
        second.put("e", new double[]{3.0, 4.0});
        graph.putVectorLayer(first).putVectorLayer(second);
        Path file = dir.resolve("aggregate-vector-budget.kgraph");
        graph.save(file);

        UnifiedGraphReader.Limits base = testLimits(8_192, 1024 * 1024, 4_096);
        UnifiedGraphReader.Limits limits = new UnifiedGraphReader.Limits(
                base.maxEntryCount(), base.maxEntryBytes(), base.maxTotalBytes(),
                base.maxManifestBytes(), base.maxEntryNameLength(), base.maxCentralDirectoryBytes(),
                3, base.maxArchiveBytes(), base.maxJsonlRowChars(),
                base.maxEntityCount(), base.maxRelationCount());
        IOException error = assertThrows(IOException.class, () -> UnifiedGraphReader.read(file, limits));
        assertTrue(error.getMessage().contains("decoded vector value limit"));
    }

    private static UnifiedGraph verboseRelationGraph(int relationCount, int payloadChars) {
        UnifiedGraph graph = new UnifiedGraph()
                .addEntity(GraphEntity.builder("source").type("CODE_SYMBOL").label("source").build())
                .addEntity(GraphEntity.builder("target").type("CODE_SYMBOL").label("target").build());
        String payload = "x".repeat(payloadChars);
        for (int i = 0; i < relationCount; i++) {
            graph.addRelation(GraphRelation.builder("relation-" + i, "source", "target")
                    .type("CALLS")
                    .attribute("payload", payload)
                    .build());
        }
        return graph;
    }

    private static UnifiedGraphReader.Limits testLimits(
            long maxEntryBytes, long maxTotalBytes, int maxJsonlRowChars) {
        return new UnifiedGraphReader.Limits(
                100,
                maxEntryBytes,
                maxTotalBytes,
                maxEntryBytes,
                4_096,
                64 * 1024,
                1_000_000,
                maxTotalBytes,
                maxJsonlRowChars,
                10_000,
                10_000);
    }

    private static GraphRelation findRelation(ReasoningGraph graph, String id) {
        return graph.relations().stream()
                .filter(r -> r.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static void writeRawGraph(Path file, Map<String, String> entries) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private static void forgeEocdEntryCount(Path file, int count) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int signature = 0x06054b50;
        for (int offset = bytes.length - 22; offset >= 0; offset--) {
            int value = (bytes[offset] & 0xff)
                    | (bytes[offset + 1] & 0xff) << 8
                    | (bytes[offset + 2] & 0xff) << 16
                    | (bytes[offset + 3] & 0xff) << 24;
            if (value != signature) continue;
            bytes[offset + 8] = (byte) count;
            bytes[offset + 9] = (byte) (count >>> 8);
            bytes[offset + 10] = (byte) count;
            bytes[offset + 11] = (byte) (count >>> 8);
            Files.write(file, bytes);
            return;
        }
        throw new IOException("EOCD not found in test archive");
    }
}
