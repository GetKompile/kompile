/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCatalogSelectionTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void isolateStore() {
        // ModelCatalogSelection default-store overloads are not used here; every
        // call passes the temp store explicitly. Reset anyway so a leaked memory
        // cache from another test cannot satisfy a lookup.
        ModelCatalogFallback.resetMemoryForTest();
    }

    private static LiveModelDiscovery.Model model(String id) {
        return new LiveModelDiscovery.Model(id, List.of());
    }

    private static ModelDiscovery.Result success(String... ids) {
        return ModelDiscovery.Result.success(
                java.util.Arrays.stream(ids).map(ModelCatalogSelectionTest::model).toList(),
                List.of("https://example.test/v1/models"));
    }

    private static ModelDiscovery.Result failure(ModelDiscovery.Status status, String message) {
        return ModelDiscovery.Result.failure(status, message, List.of());
    }

    @Test
    void liveListWinsAndCarriesNoBanner() {
        ModelCatalogFallback.record("zai", List.of("glm-stale"), null,
                tempDir.resolve("catalogs.json"));

        ModelCatalogSelection.CatalogList list = ModelCatalogSelection.listForPicker(
                success("glm-4.5", "glm-4.6"), "zai");

        assertFalse(list.fromFallback());
        assertEquals(List.of("glm-4.5", "glm-4.6"), list.models());
        assertEquals("", list.banner());
    }

    @Test
    void transportFailureFallsBackToRecordedCatalogWithLabeledBanner() {
        ModelCatalogFallback.record("zai", List.of("glm-4.5", "glm-4.6"), null,
                tempDir.resolve("catalogs.json"));

        ModelCatalogSelection.CatalogList list = ModelCatalogSelection.listForPicker(
                failure(ModelDiscovery.Status.UNAVAILABLE, "connection reset"), "zai");

        assertTrue(list.fromFallback());
        assertEquals(List.of("glm-4.5", "glm-4.6"), list.models());
        assertTrue(list.banner().contains("last known good"));
        assertTrue(list.banner().contains("unavailable"));
        assertTrue(list.banner().contains("connection reset"));
    }

    @Test
    void timeoutFailureFallsBackWithTypedStatusLabel() {
        ModelCatalogFallback.record("zai", List.of("glm-4.5"), null,
                tempDir.resolve("catalogs.json"));

        ModelCatalogSelection.CatalogList list = ModelCatalogSelection.listForPicker(
                failure(ModelDiscovery.Status.TIMEOUT, "timed out"), "zai");

        assertTrue(list.fromFallback());
        assertTrue(list.banner().contains("timed out"));
    }

    @Test
    void deliberateEmptyCatalogNeverFallsBack() {
        ModelCatalogFallback.record("zai", List.of("glm-4.5"), null,
                tempDir.resolve("catalogs.json"));

        ModelCatalogSelection.CatalogList list = ModelCatalogSelection.listForPicker(
                new ModelDiscovery.Result(ModelDiscovery.Status.SUCCESS_EMPTY,
                        List.of(), "provider returned zero models", List.of()),
                "zai");

        assertFalse(list.fromFallback());
        assertTrue(list.models().isEmpty());
    }

    @Test
    void unsupportedSchemaFailureNeverFallsBack() {
        ModelCatalogFallback.record("zai", List.of("glm-4.5"), null,
                tempDir.resolve("catalogs.json"));

        ModelCatalogSelection.CatalogList list = ModelCatalogSelection.listForPicker(
                failure(ModelDiscovery.Status.INVALID_RESPONSE, "bad json"), "zai");

        assertFalse(list.fromFallback());
        assertTrue(list.models().isEmpty());
    }

    @Test
    void authenticationFailuresNeverExposeAnotherAccountsRecordedCatalog() {
        Path store = tempDir.resolve("catalogs.json");
        ModelCatalogFallback.record("openai-codex", List.of("account-a-model"), null, store);

        for (ModelDiscovery.Status status : List.of(
                ModelDiscovery.Status.AUTH_REQUIRED, ModelDiscovery.Status.FORBIDDEN)) {
            ModelCatalogSelection.CatalogList list = ModelCatalogSelection.listForPicker(
                    failure(status, "credential rejected"), "openai-codex", store);
            assertFalse(list.fromFallback(), status.name());
            assertTrue(list.models().isEmpty(), status.name());
        }
    }

    @Test
    void noRecordedCatalogYieldsEmptyListRatherThanFakeData() {
        ModelCatalogSelection.CatalogList list = ModelCatalogSelection.listForPicker(
                failure(ModelDiscovery.Status.UNAVAILABLE, "offline"), "some-provider");

        assertFalse(list.fromFallback());
        assertTrue(list.models().isEmpty());
    }

    @Test
    void liveListMembershipWinsForExplicitSelection() {
        assertEquals(ModelCatalogSelection.SelectionDecision.LIVE_LIST,
                ModelCatalogSelection.decisionFor(
                        success("glm-4.5"), "zai", "GLM-4.5"));
    }

    @Test
    void authoritativeLiveListRejectsUnknownIdsEvenWithRecordedCatalog() {
        ModelCatalogFallback.record("zai", List.of("glm-stale"), null,
                tempDir.resolve("catalogs.json"));

        assertEquals(ModelCatalogSelection.SelectionDecision.UNKNOWN,
                ModelCatalogSelection.decisionFor(
                        success("glm-4.5", "glm-4.6"), "zai", "glm-stale"));
    }

    @Test
    void transportFailureAcceptsRecordedCatalogMatch() {
        ModelCatalogFallback.record("zai", List.of("glm-4.5"), null,
                tempDir.resolve("catalogs.json"));

        assertEquals(ModelCatalogSelection.SelectionDecision.FALLBACK_LIST,
                ModelCatalogSelection.decisionFor(
                        failure(ModelDiscovery.Status.UNAVAILABLE, "offline"),
                        "zai", "glm-4.5"));
    }

    @Test
    void transportFailureAllowsManualEntryForUnlistedIds() {
        assertEquals(ModelCatalogSelection.SelectionDecision.MANUAL_ENTRY,
                ModelCatalogSelection.decisionFor(
                        failure(ModelDiscovery.Status.TIMEOUT, "timed out"),
                        "zai", "brand-new-model"));
    }

    @Test
    void deliberateEmptyCatalogRejectsUnknownIds() {
        ModelDiscovery.Result empty = new ModelDiscovery.Result(
                ModelDiscovery.Status.SUCCESS_EMPTY, List.of(), "", List.of());
        assertEquals(ModelCatalogSelection.SelectionDecision.UNKNOWN,
                ModelCatalogSelection.decisionFor(empty, "zai", "anything"));
    }

    @Test
    void blankIdsAlwaysReject() {
        assertEquals(ModelCatalogSelection.SelectionDecision.UNKNOWN,
                ModelCatalogSelection.decisionFor(success("glm-4.5"), "zai", "  "));
        assertEquals(ModelCatalogSelection.SelectionDecision.UNKNOWN,
                ModelCatalogSelection.decisionFor(null, "zai", "glm-4.5"));
    }

    @Test
    void notesExistOnlyForNonLiveAcceptances() {
        assertTrue(ModelCatalogSelection.noteFor(
                ModelCatalogSelection.SelectionDecision.LIVE_LIST, "m", "zai").isEmpty());
        assertFalse(ModelCatalogSelection.noteFor(
                ModelCatalogSelection.SelectionDecision.FALLBACK_LIST, "m", "zai").isEmpty());
        assertFalse(ModelCatalogSelection.noteFor(
                ModelCatalogSelection.SelectionDecision.MANUAL_ENTRY, "m", "zai").isEmpty());
        assertTrue(ModelCatalogSelection.noteFor(
                ModelCatalogSelection.SelectionDecision.UNKNOWN, "m", "zai").isEmpty());
    }

    @Test
    void fallbackStoreIsolationBetweenProviders() throws Exception {
        Path store = tempDir.resolve("catalogs.json");
        ModelCatalogFallback.record("zai", List.of("glm-4.5"), null, store);
        ModelCatalogFallback.record("openai", List.of("gpt-4o"), null, store);
        assertTrue(Files.size(store) > 0);

        assertTrue(ModelCatalogFallback.knows("openai", "gpt-4o", store));
        assertFalse(ModelCatalogFallback.knows("zai", "gpt-4o", store));
    }
}
