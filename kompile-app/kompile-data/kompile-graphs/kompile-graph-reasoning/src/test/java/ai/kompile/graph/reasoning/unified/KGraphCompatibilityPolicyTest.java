/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.unified;

import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.model.SimpleGraphRelation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KGraphCompatibilityPolicyTest {

    @TempDir
    Path directory;

    @Test
    void defaultRemainsPortableWhileCanonicalPolicyEnablesArchiveNativeV3() throws Exception {
        UnifiedGraph graph = new UnifiedGraph()
                .addEntity(SimpleGraphEntity.of("source"))
                .addEntity(SimpleGraphEntity.of("target"))
                .addRelation(SimpleGraphRelation.directed(
                        "link", "source", "target", "CALLS", 1.0));
        Path portable = directory.resolve("portable.kgraph");
        Path compact = directory.resolve("compact.kgraph");

        graph.save(portable);
        graph.save(compact, KGraphCompatibilityPolicy.canonicalLocal());

        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(portable)) {
            assertEquals(2, archive.formatVersion());
            assertFalse(archive.hasCompactTopology());
        }
        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(compact)) {
            assertEquals(3, archive.formatVersion());
            assertTrue(archive.hasCompactTopology());
            assertTrue(archive.hasAdjacencyIndex());
        }
        assertEquals(2, KGraphCompatibilityPolicy.portableDefault().formatVersion());
        assertTrue(KGraphCompatibilityPolicy.canonicalLocal().supportsArchiveNativeAccess());
    }
}
