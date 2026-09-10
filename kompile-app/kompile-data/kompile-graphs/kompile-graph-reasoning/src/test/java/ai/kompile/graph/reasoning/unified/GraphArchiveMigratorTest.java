/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.unified;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphArchiveMigratorTest {

    @Test
    void sourceToTargetMigrationIsNonDestructiveAndIdempotent(@TempDir Path dir) throws IOException {
        Path source = dir.resolve("legacy.kgraph");
        writeV1(source);
        byte[] original = Files.readAllBytes(source);
        Path target = dir.resolve("current.kgraph");

        GraphArchiveMigrator.MigrationResult first = GraphArchiveMigrator.migrate(source, target);
        GraphArchiveMigrator.MigrationResult second = GraphArchiveMigrator.migrate(source, target);

        assertEquals(GraphArchiveMigrator.Status.MIGRATED, first.status());
        assertEquals(GraphArchiveMigrator.Status.SKIPPED_MATCHING_TARGET, second.status());
        assertEquals(UnifiedGraphFormat.CURRENT_VERSION, GraphArchiveMigrator.detectVersion(target));
        assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(source)));
        assertEquals("legacy", UnifiedGraph.load(target).graphId());
    }

    @Test
    void inPlaceMigrationRetainsValidatedBackup(@TempDir Path dir) throws IOException {
        Path source = dir.resolve("legacy.kgraph");
        writeV1(source);
        byte[] original = Files.readAllBytes(source);

        GraphArchiveMigrator.MigrationResult result = GraphArchiveMigrator.migrateInPlace(source);

        assertEquals(GraphArchiveMigrator.Status.MIGRATED, result.status());
        assertTrue(Files.isRegularFile(result.backup()));
        assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(result.backup())));
        assertEquals(UnifiedGraphFormat.CURRENT_VERSION, GraphArchiveMigrator.detectVersion(source));
        assertEquals(GraphArchiveMigrator.Status.SKIPPED_CURRENT,
                GraphArchiveMigrator.migrateInPlace(source).status());
    }

    @Test
    void legacyRelationsAreDirectlyTranscodedToCompactLinks(@TempDir Path dir) throws IOException {
        Path source = dir.resolve("legacy-links.kgraph");
        writeLegacyLinks(source);
        Path target = dir.resolve("compact-links.kgraph");

        GraphArchiveMigrator.migrate(source, target);

        try (ZipFile zip = new ZipFile(target.toFile())) {
            assertNull(zip.getEntry(UnifiedGraphFormat.ENTRY_RELATIONS));
            assertEquals(ZipEntry.STORED,
                    zip.getEntry(UnifiedGraphFormat.ENTRY_COMPACT_LINKS).getMethod());
            assertEquals(ZipEntry.STORED,
                    zip.getEntry(UnifiedGraphFormat.ENTRY_COMPACT_ADJACENCY).getMethod());
        }
        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(target);
             UnifiedGraphArchive.LinkCursor cursor = archive.openLinks()) {
            assertTrue(archive.hasAdjacencyIndex());
            archive.validateAdjacencyIndex();
            assertEquals(2, archive.linkCount());
            UnifiedGraphArchive.Link first = cursor.next();
            assertEquals("r1", first.id());
            assertEquals("CALLS", first.type());
            assertEquals(0.75, first.weight());
            assertEquals(0.9, first.confidence());
            assertEquals(Map.of("line", 7L), first.attributes());
            assertEquals(List.of("code"), first.tags().stream().sorted().toList());
            UnifiedGraphArchive.Link second = cursor.next();
            assertEquals("r2", second.id());
            assertFalse(second.directed());
            assertNull(cursor.next());
        }

        UnifiedGraph materialized = UnifiedGraph.load(target);
        assertEquals(3, materialized.entityCount());
        assertEquals(2, materialized.relationCount());
        assertEquals(7L, materialized.relation("r1").orElseThrow().attributes().get("line"));
    }

    @Test
    void currentV3WithoutAdjacencyIsUpgradedWithoutRecompressingLinks(@TempDir Path dir)
            throws IOException {
        Path complete = dir.resolve("complete-v3.kgraph");
        new UnifiedGraph()
                .addEntity(ai.kompile.graph.reasoning.model.SimpleGraphEntity.of("a"))
                .addEntity(ai.kompile.graph.reasoning.model.SimpleGraphEntity.of("b"))
                .addRelation(ai.kompile.graph.reasoning.model.SimpleGraphRelation.directed(
                        "r", "a", "b", "CALLS", 1.0))
                .saveCompact(complete);
        Path source = dir.resolve("without-adjacency.kgraph");
        stripAdjacency(complete, source);

        GraphArchiveMigrator.MigrationResult result = GraphArchiveMigrator.migrateInPlace(source);

        assertEquals(GraphArchiveMigrator.Status.MIGRATED, result.status());
        try (ZipFile zip = new ZipFile(source.toFile())) {
            assertEquals(ZipEntry.STORED,
                    zip.getEntry(UnifiedGraphFormat.ENTRY_COMPACT_LINKS).getMethod());
            assertEquals(ZipEntry.STORED,
                    zip.getEntry(UnifiedGraphFormat.ENTRY_COMPACT_ADJACENCY).getMethod());
        }
        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(source)) {
            assertTrue(archive.hasAdjacencyIndex());
            archive.validateAdjacencyIndex();
        }
    }

    @SuppressWarnings("unchecked")
    private static void stripAdjacency(Path source, Path target) throws IOException {
        try (ZipFile input = new ZipFile(source.toFile());
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            var entries = input.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (UnifiedGraphFormat.ENTRY_COMPACT_ADJACENCY.equals(entry.getName())) continue;
                ZipEntry copy = new ZipEntry(entry.getName());
                byte[] bytes;
                try (var stream = input.getInputStream(entry)) {
                    bytes = stream.readAllBytes();
                }
                if (UnifiedGraphFormat.ENTRY_MANIFEST.equals(entry.getName())) {
                    Map<String, Object> manifest = MiniJson.parseObject(
                            new String(bytes, StandardCharsets.UTF_8));
                    Map<String, Object> topology = new LinkedHashMap<>(
                            (Map<String, Object>) manifest.get("topology"));
                    topology.remove("adjacencyEntry");
                    topology.remove("adjacencyEncodingVersion");
                    topology.remove("adjacencyEntries");
                    manifest.put("topology", topology);
                    List<Object> sections = new ArrayList<>((List<Object>) manifest.get("sections"));
                    sections.remove(UnifiedGraphFormat.ENTRY_COMPACT_ADJACENCY);
                    manifest.put("sections", sections);
                    bytes = MiniJson.write(manifest).getBytes(StandardCharsets.UTF_8);
                } else if (entry.getMethod() == ZipEntry.STORED) {
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    crc.update(bytes);
                    copy.setMethod(ZipEntry.STORED);
                    copy.setSize(bytes.length);
                    copy.setCompressedSize(bytes.length);
                    copy.setCrc(crc.getValue());
                }
                output.putNextEntry(copy);
                output.write(bytes);
                output.closeEntry();
            }
        }
    }

    private static void writeV1(Path file) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("manifest.json", """
                {"format":"kompile-graph","formatVersion":1,
                 "counts":{"entities":0,"relations":0,"vectorLayers":0},
                 "embeddingDim":0,"meta":{"graphId":"legacy"},
                 "sections":["entities.jsonl","relations.jsonl"],"vectorLayers":[]}
                """);
        entries.put("entities.jsonl", "");
        entries.put("relations.jsonl", "");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private static void writeLegacyLinks(Path file) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("manifest.json", """
                {"format":"kompile-graph","formatVersion":1,
                 "counts":{"entities":3,"relations":2,"vectorLayers":0},
                 "embeddingDim":0,"meta":{"graphId":"legacy-links"},
                 "sections":["entities.jsonl","relations.jsonl"],"vectorLayers":[]}
                """);
        entries.put("entities.jsonl", """
                {"id":"a","type":"CODE_SYMBOL","label":"A"}
                {"id":"b","type":"CODE_SYMBOL","label":"B"}
                {"id":"isolated","type":"CODE_SYMBOL","label":"Unused"}
                """);
        entries.put("relations.jsonl", """
                {"id":"r1","sourceId":"a","targetId":"b","type":"CALLS","weight":0.75,"confidence":0.9,"tags":["code"],"attributes":{"line":7}}
                {"id":"r2","sourceId":"b","targetId":"a","type":"RELATED","directed":false}
                """);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }
}
