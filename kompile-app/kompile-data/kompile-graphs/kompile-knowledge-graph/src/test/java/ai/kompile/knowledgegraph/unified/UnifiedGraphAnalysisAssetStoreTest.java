/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.knowledgegraph.unified;

import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedGraphAnalysisAssetStoreTest {

    private static final String DATA_DIR_PROPERTY = "kompile.data.dir";

    @TempDir
    Path directory;

    private String previousAssetsDirectory;
    private String previousDataDirectory;
    private UnifiedGraphAnalysisAssetStore store;

    @BeforeEach
    void setUp() {
        previousAssetsDirectory = System.getProperty(UnifiedGraphAnalysisAssetStore.SIDECAR_DIR_PROPERTY);
        previousDataDirectory = System.getProperty(DATA_DIR_PROPERTY);
        store = new UnifiedGraphAnalysisAssetStore(directory);
    }

    @AfterEach
    void tearDown() {
        restore(UnifiedGraphAnalysisAssetStore.SIDECAR_DIR_PROPERTY, previousAssetsDirectory);
        restore(DATA_DIR_PROPERTY, previousDataDirectory);
    }

    @Test
    void compensationRestoresPriorAbsence() throws Exception {
        UnifiedGraphAnalysisAssetStore.AssetState prior = store.captureStrict(7L);
        store.replaceStrict(7L, graph("incoming"));

        store.restoreStrict(7L, prior);

        assertFalse(store.get(7L).isPresent());
        assertFalse(Files.exists(directory.resolve("factsheet-7.assets")));
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
        assertEquals("prior", UnifiedGraph.load(directory.resolve("factsheet-7.assets"))
                .entity("prior").orElseThrow().label());
    }

    @Test
    void anotherProcessSeesEveryRewriteAndReusesItsCopyUntilThen() {
        UnifiedGraphAnalysisAssetStore otherProcess = new UnifiedGraphAnalysisAssetStore(directory);
        assertTrue(otherProcess.get(7L).isEmpty());

        store.put(7L, graph("first"));
        UnifiedGraph first = otherProcess.get(7L).orElseThrow();
        assertTrue(first.entity("first").isPresent());
        assertSame(first, otherProcess.get(7L).orElseThrow(), "an unchanged sidecar is not reloaded");

        store.put(7L, graph("second"));
        UnifiedGraph second = otherProcess.get(7L).orElseThrow();
        assertTrue(second.entity("second").isPresent());
        assertTrue(second.entity("first").isEmpty());
        assertTrue(otherProcess.snapshot(7L).orElseThrow().entity("second").isPresent());
    }

    @Test
    void aSidecarAnotherProcessDeletedIsNoLongerServed() throws Exception {
        UnifiedGraphAnalysisAssetStore otherProcess = new UnifiedGraphAnalysisAssetStore(directory);
        store.put(7L, graph("published"));
        assertTrue(otherProcess.get(7L).isPresent());

        store.removeForFactSheet(7L);

        assertTrue(otherProcess.snapshot(7L).isEmpty());
        assertFalse(otherProcess.captureStrict(7L).present());
        assertTrue(otherProcess.get(7L).isEmpty());
    }

    @Test
    void writesLeaveOnlyTheSidecar() throws Exception {
        store.put(7L, graph("first"));
        store.replaceStrict(7L, graph("second"));

        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(List.of(directory.resolve("factsheet-7.assets")), files.toList());
        }
    }

    @Test
    void theDirectoryPropertyOverridesTheProjectDefault() throws Exception {
        Path override = directory.resolve("override");
        Path project = directory.resolve("project");
        System.setProperty(UnifiedGraphAnalysisAssetStore.SIDECAR_DIR_PROPERTY, override.toString());
        System.setProperty(DATA_DIR_PROPERTY, project.toString());

        new UnifiedGraphAnalysisAssetStore().replaceStrict(7L, graph("override"));

        assertTrue(Files.isRegularFile(override.resolve("factsheet-7.assets")));
        assertFalse(Files.exists(project));
    }

    @Test
    void theProjectDefaultKeepsSidecarsBesideTheProjectGraph() throws Exception {
        Path project = directory.resolve("project");
        System.clearProperty(UnifiedGraphAnalysisAssetStore.SIDECAR_DIR_PROPERTY);
        System.setProperty(DATA_DIR_PROPERTY, project.toString());

        new UnifiedGraphAnalysisAssetStore().replaceStrict(7L, graph("project"));

        assertTrue(Files.isRegularFile(
                project.resolve("data").resolve("graph").resolve("analysis-assets").resolve("factsheet-7.assets")));
    }

    private static UnifiedGraph graph(String id) {
        return new UnifiedGraph().factSheetId(7L).addEntity(id, "ENTITY", id);
    }

    private static void restore(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }
}
