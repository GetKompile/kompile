/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.graph.reasoning.lifecycle;

import ai.kompile.graph.reasoning.psl.GraphPslProgramBuilder;
import ai.kompile.graph.reasoning.unified.KGraphCompatibilityPolicy;
import ai.kompile.graph.reasoning.unified.MiniJson;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.UnifiedGraphFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class ConsensusTargetsArtifactCodecTest {
    @TempDir Path directory;

    @Test
    void ambiguousConsensusIdentifiersFailBeforeLearningOrPublishingArtifacts() {
        for (String id : List.of("a,b", "a(b)", " a", "a ", "a\n", "")) {
            UnifiedGraph graph = new UnifiedGraph().addEntity(id, "ENTITY", "A")
                    .addEntity("c", "ENTITY", "C").addRelation("r", id, "c", "REL", 0.8);
            assertThrows(IllegalArgumentException.class, () -> UnifiedGraphReasoningLifecycle.learn(graph, null));
            assertNull(graph.artifact(ConsensusTargetsArtifactCodec.ARTIFACT_NAME));
            assertTrue(graph.entityOpinions().isEmpty());
            assertTrue(graph.relationOpinions().isEmpty());
            assertThrows(IllegalArgumentException.class,
                    () -> UnifiedGraphReasoningLifecycle.relationTargetKey("REL", id, "c"));
        }
        UnifiedGraph collision = new UnifiedGraph().addEntity("a", "ENTITY", "A")
                .addEntity("b", "ENTITY", "B").addRelation("r1", "a", "b", "WORKS-AT", 0.8)
                .addRelation("r2", "a", "b", "WORKS_AT", 0.6);
        assertThrows(IllegalArgumentException.class, () -> UnifiedGraphReasoningLifecycle.learn(collision, null));
        assertNull(collision.artifact(ConsensusTargetsArtifactCodec.ARTIFACT_NAME));
        assertEquals("REL(a,b)", UnifiedGraphReasoningLifecycle.relationTargetKey("REL", "a", "b"));
    }

    @Test
    void roundTripsLiteralKeysNumbersAndAliasesWithoutAtomParsing() {
        String opaqueId = " id,α(\"quoted\")\\path\n🚀 ";
        // Historical literal keys remain inspectable; new learning rejects ambiguous IDs.
        String relationKey = "WORKS_AT(" + opaqueId + ",b),c)";
        Map<String, Double> targets = new LinkedHashMap<>();
        targets.put("State(n0)", 0.12345678901234568);
        targets.put(relationKey, 0.75);
        // Not an atom at all: the snapshot is a string-keyed training map, not a PSL parser.
        targets.put(" literal ,(key) \t", 0.0);
        targets.put("one", 1.0);
        targets.put("tiny", Double.MIN_VALUE);
        Map<String, String> aliases = Map.of("n0", opaqueId, "n1", "b),c");

        String json = ConsensusTargetsArtifactCodec.encode(targets, aliases);
        var decoded = ConsensusTargetsArtifactCodec.decode(json);
        assertEquals(targets, decoded.targets());
        assertEquals(aliases, decoded.entityAliases());
        Map<String, Object> root = MiniJson.parseObject(json);
        assertEquals(ConsensusTargetsArtifactCodec.FORMAT, root.get("format"));
        assertEquals(1L, root.get("version"));
        assertEquals("training-targets", root.get("semantics"));
        assertInstanceOf(List.class, root.get("targets"));
        assertEquals(aliases, root.get("entityAliases"));
    }

    @Test
    void snapshotIsDetachedAndImmutable() {
        Map<String, Double> targets = new LinkedHashMap<>(Map.of("State(n0)", 0.5));
        Map<String, String> aliases = new LinkedHashMap<>(Map.of("n0", "original"));
        var snapshot = new ConsensusTargetsArtifactCodec.Snapshot(targets, aliases);
        targets.put("State(n0)", 1.0);
        aliases.put("n0", "changed");
        assertEquals(0.5, snapshot.targets().get("State(n0)"));
        assertEquals("original", snapshot.entityAliases().get("n0"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.targets().put("x", 0.2));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.entityAliases().put("n1", "x"));
    }

    @Test
    void emptySnapshotAndStandardJsonWhitespaceEscapesAndExponentsAreAccepted() {
        var empty = ConsensusTargetsArtifactCodec.decode(ConsensusTargetsArtifactCodec.encode(Map.of(), Map.of()));
        assertTrue(empty.targets().isEmpty());
        assertTrue(empty.entityAliases().isEmpty());
        String json = " { \"entityAliases\":{\"n0\":\"a\\/b\"},\"targets\":["
                + "{\"value\":2.5e-1,\"key\":\"State(\\u006e0)\"}],\"semantics\":\"training-targets\","
                + "\"version\":1,\"format\":\"kompile-consensus-targets\" } ";
        var decoded = ConsensusTargetsArtifactCodec.decode(json);
        assertEquals(Map.of("State(n0)", 0.25), decoded.targets());
        assertEquals(Map.of("n0", "a/b"), decoded.entityAliases());
    }

    @Test
    void portableV2SaveReloadPreservesSnapshotAndLegacyBinaryWithoutTraining() throws Exception {
        String opaqueId = " id,α(\"quoted\")\\path\n🚀 ";
        UnifiedGraph graph = new UnifiedGraph().graphId("consensus-snapshot")
                .addEntity(opaqueId, "PERSON", "Opaque")
                .addEntity("b),c", "COMPANY", "Other")
                .addRelation("r", opaqueId, "b),c", "WORKS_AT", 0.75);
        GraphPslProgramBuilder builder = new GraphPslProgramBuilder();
        builder.build(graph); // Grounding only: no learning, inference, or ND4J execution.
        String stateKey = "State(" + builder.entityIdToConstant().get(opaqueId) + ")";
        // Historical literal keys remain inspectable; new learning rejects ambiguous IDs.
        String relationKey = "WORKS_AT(" + opaqueId + ",b),c)";
        Map<String, Double> targets = new LinkedHashMap<>();
        targets.put(stateKey, 0.12345678901234568);
        targets.put(relationKey, 0.75);
        String json = ConsensusTargetsArtifactCodec.encode(targets, builder.constantToEntityId());
        graph.putArtifactText(UnifiedGraphReasoningLifecycle.CONSENSUS_TARGETS_JSON_ARTIFACT, json);
        graph.putModel(UnifiedGraphReasoningLifecycle.CONSENSUS_TARGETS_ARTIFACT, new LinkedHashMap<>(targets));
        byte[] legacy = graph.artifact(UnifiedGraphReasoningLifecycle.CONSENSUS_TARGETS_ARTIFACT).clone();
        Path archive = directory.resolve("targets.kgraph");
        graph.save(archive, KGraphCompatibilityPolicy.PORTABLE_V2);

        try (ZipFile zip = new ZipFile(archive.toFile())) {
            String manifest = new String(zip.getInputStream(zip.getEntry(UnifiedGraphFormat.ENTRY_MANIFEST))
                    .readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(2L, MiniJson.parseObject(manifest).get("formatVersion"));
            assertNotNull(zip.getEntry(UnifiedGraphFormat.ENTRY_SCHEMA_INDEX));
            assertNotNull(zip.getEntry(UnifiedGraphFormat.modelEntry(ConsensusTargetsArtifactCodec.ARTIFACT_NAME)));
        }
        UnifiedGraph reloaded = UnifiedGraph.load(archive);
        var snapshot = ConsensusTargetsArtifactCodec.decode(
                reloaded.artifactText(UnifiedGraphReasoningLifecycle.CONSENSUS_TARGETS_JSON_ARTIFACT));
        assertEquals(json, reloaded.artifactText(ConsensusTargetsArtifactCodec.ARTIFACT_NAME));
        assertEquals(targets, snapshot.targets());
        assertEquals(builder.constantToEntityId(), snapshot.entityAliases());
        assertTrue(reloaded.entity(opaqueId).isPresent());
        assertArrayEquals(legacy, reloaded.artifact(UnifiedGraphReasoningLifecycle.CONSENSUS_TARGETS_ARTIFACT));
        Map<String, Double> binaryTargets = reloaded.model(UnifiedGraphReasoningLifecycle.CONSENSUS_TARGETS_ARTIFACT);
        assertEquals(targets, binaryTargets);
        assertTrue(reloaded.entityOpinions().isEmpty());
        assertTrue(reloaded.relationOpinions().isEmpty());

        // Adding an earlier ID changes freshly assigned nX constants. The snapshot's saved aliases
        // still describe the original training pass rather than today's topology order.
        reloaded.addEntity(" earlier", "PERSON", "New");
        GraphPslProgramBuilder current = new GraphPslProgramBuilder();
        current.build(reloaded);
        assertNotEquals(builder.entityIdToConstant().get(opaqueId), current.entityIdToConstant().get(opaqueId));
        assertEquals(opaqueId, snapshot.entityAliases().get(builder.entityIdToConstant().get(opaqueId)));
        assertEquals(targets, snapshot.targets());
        assertTrue(UnifiedGraphReasoningLifecycle.isLearnedArtifact(ConsensusTargetsArtifactCodec.ARTIFACT_NAME));
    }

    @Test
    void rejectsWrongRootShapeFormatVersionAndSemantics() {
        String valid = payload("[]", "{}");
        for (String json : List.of("[]", "null", "{}",
                valid.replace("kompile-consensus-targets", "other"),
                valid.replace("training-targets", "posteriors"),
                valid.replace("\"version\":1", "\"version\":2"),
                valid.replace("\"version\":1", "\"version\":1.0"),
                valid.replace("\"version\":1", "\"version\":1e0"),
                valid.replace("\"version\":1", "\"version\":\"1\""),
                valid.replace("\"version\":1,", ""),
                valid.replace("\"targets\":[]", "\"targets\":{}"),
                valid.replace("\"entityAliases\":{}", "\"entityAliases\":[]"),
                valid.replace("\"targets\":[]", "\"targets\":[],\"extra\":true"))) {
            rejects(json);
        }
    }

    @Test
    void rejectsMalformedAndDuplicateTargetRowsAndAliasKeys() {
        for (String rows : List.of("[null]", "[1]", "[[]]", "[{}]",
                "[{\"key\":\"x\"}]", "[{\"key\":1,\"value\":0.5}]",
                "[{\"key\":\" \",\"value\":0.5}]",
                "[{\"key\":\"x\",\"value\":0.5,\"extra\":0}]",
                "[{\"key\":\"x\",\"value\":0.5},{\"key\":\"\\u0078\",\"value\":0.7}]",
                "[{\"key\":\"x\",\"\\u006bey\":\"y\",\"value\":0.5}]")) {
            rejects(payload(rows, "{}"));
        }
        rejects(payload("[]", "{\"n0\":\"a\",\"\\u006e0\":\"b\"}"));
        rejects(payload("[]", "{}").replace("\"version\":1", "\"version\":1,\"version\":1"));
    }

    @Test
    void rejectsNonNumericNonFiniteAndOutOfRangeTargetsWithoutClamping() {
        for (String value : List.of("null", "true", "\"0.5\"", "\"NaN\"", "\"Infinity\"",
                "1e309", "-1e309", "-0.01", "1.00001")) {
            rejects(payload("[{\"key\":\"x\",\"value\":" + value + "}]", "{}"));
        }
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, -0.01, 1.00001}) {
            assertThrows(IllegalArgumentException.class,
                    () -> ConsensusTargetsArtifactCodec.encode(Map.of("x", value), Map.of()));
        }
        Map<String, Double> withNull = new LinkedHashMap<>();
        withNull.put("x", null);
        assertThrows(IllegalArgumentException.class, () -> ConsensusTargetsArtifactCodec.encode(withNull, Map.of()));
    }

    @Test
    void rejectsInvalidAliasesAndAmbiguousEntityMappings() {
        for (String aliases : List.of("{\"other\":\"a\"}", "{\"n-1\":\"a\"}",
                "{\"n00\":\"a\"}", "{\"n0\":null}", "{\"n0\":1}", "{\"n0\":\" \"}",
                "{\"n0\":\"a\",\"n1\":\"a\"}")) {
            rejects(payload("[]", aliases));
        }
        assertThrows(IllegalArgumentException.class,
                () -> ConsensusTargetsArtifactCodec.encode(Map.of(), Map.of("wrong", "id")));
        assertThrows(IllegalArgumentException.class,
                () -> ConsensusTargetsArtifactCodec.encode(Map.of(), Map.of("n0", "id", "n1", "id")));
    }

    @Test
    void rejectsNonJsonGrammarThatMiniJsonOtherwiseTolerates() {
        for (String value : List.of("+0.5", "01", ".5", "0.", "00.5", "NaN", "Infinity")) {
            rejects(payload("[{\"key\":\"x\",\"value\":" + value + "}]", "{}"));
        }
        for (String rows : List.of("[{\"key\":\"raw\nline\",\"value\":0.5}]",
                "[{\"key\":\"\\q\",\"value\":0.5}]", "[{},]", "[", "[[[[]]]]")) {
            rejects(payload(rows, "{}"));
        }
        rejects(payload("[]", "{}") + "garbage");
        rejects(payload("[]", "{}").replace("\"targets\":[]", "\"targets\":[],"));
    }

    @Test
    void enforcesUtf8TextBoundsAndRejectsUnpairedSurrogates() {
        String atLimit = "é".repeat(ConsensusTargetsArtifactCodec.MAX_TEXT_BYTES / 2);
        assertEquals(Map.of(atLimit, 0.5), ConsensusTargetsArtifactCodec.decode(
                ConsensusTargetsArtifactCodec.encode(Map.of(atLimit, 0.5), Map.of())).targets());
        String oversized = atLimit + "é";
        assertThrows(IllegalArgumentException.class,
                () -> ConsensusTargetsArtifactCodec.encode(Map.of(oversized, 0.5), Map.of()));
        rejects(payload(MiniJson.write(List.of(Map.of("key", oversized, "value", 0.5))), "{}"));
        assertThrows(IllegalArgumentException.class,
                () -> ConsensusTargetsArtifactCodec.encode(Map.of("x", 0.5), Map.of("n0", oversized)));
        assertThrows(IllegalArgumentException.class,
                () -> ConsensusTargetsArtifactCodec.encode(Map.of("\uD800", 0.5), Map.of()));
        rejects(payload("[{\"key\":\"\\uD800\",\"value\":0.5}]", "{}"));
        rejects(payload("[]", "{\"n0\":\"\\uDC00\"}"));
    }

    @Test
    void enforcesInputByteAndEntryCapsBeforeConversion() {
        assertThrows(IllegalArgumentException.class, () -> ConsensusTargetsArtifactCodec.decode(null));
        rejects(payload("[]", "{}") + " ".repeat(ConsensusTargetsArtifactCodec.MAX_BYTES));
        // Character count fits, but UTF-8 byte count does not.
        rejects("é".repeat(ConsensusTargetsArtifactCodec.MAX_BYTES / 2 + 1));
        rejects(payload("[" + "{},".repeat(ConsensusTargetsArtifactCodec.MAX_TARGETS) + "{}]", "{}"));
        StringBuilder aliases = new StringBuilder("{");
        for (int i = 0; i <= ConsensusTargetsArtifactCodec.MAX_ALIASES; i++) {
            if (i > 0) aliases.append(',');
            aliases.append('"').append('n').append(i).append("\":\"id").append(i).append('"');
        }
        aliases.append('}');
        rejects(payload("[]", aliases.toString()));
    }

    @Test
    void enforcesEncodingEntryAndOutputCaps() {
        Map<String, Double> targets = new LinkedHashMap<>();
        Map<String, String> aliases = new LinkedHashMap<>();
        for (int i = 0; i <= ConsensusTargetsArtifactCodec.MAX_TARGETS; i++) {
            targets.put("key" + i, 0.5);
            aliases.put("n" + i, "id" + i);
        }
        assertThrows(IllegalArgumentException.class, () -> ConsensusTargetsArtifactCodec.encode(targets, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> ConsensusTargetsArtifactCodec.encode(Map.of(), aliases));
        Map<String, Double> largeStrings = new LinkedHashMap<>();
        String prefix = "x".repeat(ConsensusTargetsArtifactCodec.MAX_TEXT_BYTES - 8);
        for (int i = 0; i < 1100; i++) largeStrings.put(prefix + i, 0.5);
        assertThrows(IllegalArgumentException.class, () -> ConsensusTargetsArtifactCodec.encode(largeStrings, Map.of()));
    }

    private static String payload(String targets, String aliases) {
        return "{\"format\":\"kompile-consensus-targets\",\"version\":1,"
                + "\"semantics\":\"training-targets\",\"targets\":" + targets
                + ",\"entityAliases\":" + aliases + "}";
    }

    private static void rejects(String json) {
        assertThrows(IllegalArgumentException.class, () -> ConsensusTargetsArtifactCodec.decode(json));
    }
}
