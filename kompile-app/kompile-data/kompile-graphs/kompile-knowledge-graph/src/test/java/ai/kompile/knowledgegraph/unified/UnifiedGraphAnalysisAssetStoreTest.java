/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.knowledgegraph.unified;

import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedGraphAnalysisAssetStoreTest {

    @TempDir
    Path directory;

    private String previousDirectory;
    private UnifiedGraphAnalysisAssetStore store;

    @BeforeEach
    void setUp() {
        previousDirectory = System.getProperty(UnifiedGraphAnalysisAssetStore.SIDECAR_DIR_PROPERTY);
        System.setProperty(UnifiedGraphAnalysisAssetStore.SIDECAR_DIR_PROPERTY, directory.toString());
        store = new UnifiedGraphAnalysisAssetStore();
    }

    @AfterEach
    void tearDown() {
        if (previousDirectory == null) {
            System.clearProperty(UnifiedGraphAnalysisAssetStore.SIDECAR_DIR_PROPERTY);
        } else {
            System.setProperty(UnifiedGraphAnalysisAssetStore.SIDECAR_DIR_PROPERTY, previousDirectory);
        }
    }

    @Test
    void compensationRestoresPriorAbsence() throws Exception {
        UnifiedGraphAnalysisAssetStore.AssetState prior = store.captureStrict(7L);
        store.replaceStrict(7L, graph("incoming"));

        store.restoreStrict(7L, prior);

        assertFalse(store.get(7L).isPresent());
        assertFalse(Files.exists(directory.resolve("factsheet-7.kgraph")));
    }

    @Test
    void compensationRestoresExactPriorGraph() throws Exception {
        store.replaceStrict(7L, graph("prior"));
        UnifiedGraphAnalysisAssetStore.AssetState prior = store.captureStrict(7L);
        store.replaceStrict(7L, graph("incoming"));

        store.restoreStrict(7L, prior);

        UnifiedGraph restored = store.get(7L).orElseThrow();
        assertEquals("prior", restored.entity("prior").orElseThrow().label());
        assertTrue(restored.entity("incoming").isEmpty());
        assertEquals("prior", UnifiedGraph.load(directory.resolve("factsheet-7.kgraph"))
                .entity("prior").orElseThrow().label());
    }

    private static UnifiedGraph graph(String id) {
        return new UnifiedGraph().factSheetId(7L).addEntity(id, "ENTITY", id);
    }
}
