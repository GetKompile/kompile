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
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
                "manifest.json", "{\"format\":\"kompile-graph\",\"formatVersion\":2}",
                "entities.jsonl", "",
                "relations.jsonl", ""));

        IOException error = assertThrows(IOException.class, () -> UnifiedGraph.load(file));
        assertTrue(error.getMessage().contains("formatVersion"));
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
        String manifest = "{\"format\":\"kompile-graph\",\"formatVersion\":1,"
                + "\"counts\":{\"entities\":0,\"relations\":0,\"vectorLayers\":0},"
                + "\"embeddingDim\":0,"
                + "\"sections\":[\"entities.jsonl\",\"relations.jsonl\"],"
                + "\"vectorLayers\":[]}";
        Path truncated = dir.resolve("truncated.kgraph");
        writeRawGraph(truncated, Map.of("manifest.json", manifest, "entities.jsonl", ""));
        IOException missing = assertThrows(IOException.class, () -> UnifiedGraph.load(truncated));
        assertTrue(missing.getMessage().contains("inventory mismatch"));

        Path wrongCount = dir.resolve("wrong-count.kgraph");
        writeRawGraph(wrongCount, Map.of(
                "manifest.json", manifest.replace("\"entities\":0", "\"entities\":1"),
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
}
