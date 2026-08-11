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
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
