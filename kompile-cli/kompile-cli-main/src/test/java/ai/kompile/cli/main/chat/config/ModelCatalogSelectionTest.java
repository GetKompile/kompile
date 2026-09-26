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

    @Test
    void pickerNumbersResolveOnlyAgainstDisplayedChoices() {
        List<String> models = List.of("gpt-6-astra", "gpt-5.6-sol");
        assertEquals("gpt-6-astra", ModelCatalogSelection.resolvePickerInput("1", models));
        assertEquals("gpt-5.6-sol", ModelCatalogSelection.resolvePickerInput(" 2 ", models));
        assertEquals("gpt-6-astra", ModelCatalogSelection.resolvePickerInput("", models));
        assertEquals("gpt-6-astra", ModelCatalogSelection.resolvePickerInput("GPT-6-ASTRA", models));
        assertEquals("custom-model", ModelCatalogSelection.resolvePickerInput("custom-model", List.of()));
        for (String input : List.of("1", "0", "-1", "+3", "999999999999999999999")) {
            org.junit.jupiter.api.Assertions.assertNull(
                    ModelCatalogSelection.resolvePickerInput(input, List.of()), input);
        }
        org.junit.jupiter.api.Assertions.assertNull(ModelCatalogSelection.resolvePickerInput("3", models));
        org.junit.jupiter.api.Assertions.assertNull(ModelCatalogSelection.resolvePickerInput("", List.of()));
    }

    @Test
    void discoveryFailureDoesNotAcceptMenuNumbersAsExplicitModelIds() {
        for (ModelDiscovery.Status status : List.of(ModelDiscovery.Status.TIMEOUT,
                ModelDiscovery.Status.UNAVAILABLE, ModelDiscovery.Status.AUTH_REQUIRED)) {
            assertEquals(ModelCatalogSelection.SelectionDecision.UNKNOWN,
                    ModelCatalogSelection.decisionFor(failure(status, "discovery failed"),
                            "openai", "1", tempDir.resolve("catalogs.json")));
        }
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
                success("glm-4.5", "glm-4.6"), "zai", tempDir.resolve("catalogs.json"));

        assertFalse(list.fromFallback());
        assertEquals(List.of("glm-4.5", "glm-4.6"), list.models());
        assertEquals("", list.banner());
    }

    @Test
    void transportFailureFallsBackToRecordedCatalogWithLabeledBanner() {
        ModelCatalogFallback.record("zai", List.of("glm-4.5", "glm-4.6"), null,
                tempDir.resolve("catalogs.json"));

        ModelCatalogSelection.CatalogList list = ModelCatalogSelection.listForPicker(
                failure(ModelDiscovery.Status.UNAVAILABLE, "connection reset"), "zai", tempDir.resolve("catalogs.json"));

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
                failure(ModelDiscovery.Status.TIMEOUT, "timed out"), "zai", tempDir.resolve("catalogs.json"));

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
                "zai", tempDir.resolve("catalogs.json"));

        assertFalse(list.fromFallback());
        assertTrue(list.models().isEmpty());
    }

    @Test
    void unsupportedSchemaFailureNeverFallsBack() {
        ModelCatalogFallback.record("zai", List.of("glm-4.5"), null,
                tempDir.resolve("catalogs.json"));

        ModelCatalogSelection.CatalogList list = ModelCatalogSelection.listForPicker(
                failure(ModelDiscovery.Status.INVALID_RESPONSE, "bad json"), "zai", tempDir.resolve("catalogs.json"));

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
            assertTrue(list.banner().contains("Model selection is blocked"));
            assertTrue(list.banner().contains("credential"));
            for (String id : List.of("account-a-model", "custom-model", "1")) {
                assertEquals(ModelCatalogSelection.SelectionDecision.UNKNOWN,
                        ModelCatalogSelection.decisionFor(failure(status, "rejected"),
                                "openai-codex", id, store));
            }
        }
    }

    @Test
    void noRecordedCatalogYieldsEmptyListRatherThanFakeData() {
        ModelCatalogSelection.CatalogList list = ModelCatalogSelection.listForPicker(
                failure(ModelDiscovery.Status.UNAVAILABLE, "offline"), "some-provider", tempDir.resolve("catalogs.json"));

        assertFalse(list.fromFallback());
        assertTrue(list.models().isEmpty());
    }

    @Test
    void liveListMembershipWinsForExplicitSelection() {
        assertEquals(ModelCatalogSelection.SelectionDecision.LIVE_LIST,
                ModelCatalogSelection.decisionFor(
                        success("glm-4.5"), "zai", "GLM-4.5", tempDir.resolve("catalogs.json")));
    }

    @Test
    void authoritativeLiveListAcceptsUnlistedIdsWithCaution() {
        ModelCatalogFallback.record("zai", List.of("glm-stale"), null,
                tempDir.resolve("catalogs.json"));

        // A successful catalog that lacks the id must not hard-block explicit
        // selection: day-one releases routinely appear before catalog/CLI
        // propagation. Accept with a caution; the provider validates the id.
        assertEquals(ModelCatalogSelection.SelectionDecision.UNLISTED,
                ModelCatalogSelection.decisionFor(
                        success("glm-4.5", "glm-4.6"), "zai", "glm-stale", tempDir.resolve("catalogs.json")));
        assertFalse(ModelCatalogSelection.noteFor(
                ModelCatalogSelection.SelectionDecision.UNLISTED, "glm-stale", "zai").isEmpty());
    }

    @Test
    void dayOneOpenAiModelIsSelectableAgainstAuthoritativeCatalog() {
        ModelDiscovery.Result live = success("gpt-6-astra", "gpt-5.6-sol");

        assertEquals(ModelCatalogSelection.SelectionDecision.UNLISTED,
                ModelCatalogSelection.decisionFor(live, "openai", "gpt-6-luna"));
        assertFalse(ModelCatalogSelection.noteFor(
                ModelCatalogSelection.SelectionDecision.UNLISTED, "gpt-6-luna", "openai").isEmpty());
    }

    @Test
    void transportFailureAcceptsRecordedCatalogMatch() {
        ModelCatalogFallback.record("zai", List.of("glm-4.5"), null,
                tempDir.resolve("catalogs.json"));

        assertEquals(ModelCatalogSelection.SelectionDecision.FALLBACK_LIST,
                ModelCatalogSelection.decisionFor(
                        failure(ModelDiscovery.Status.UNAVAILABLE, "offline"),
                        "zai", "glm-4.5", tempDir.resolve("catalogs.json")));
    }

    @Test
    void transportFailureAllowsManualEntryForUnlistedIds() {
        assertEquals(ModelCatalogSelection.SelectionDecision.MANUAL_ENTRY,
                ModelCatalogSelection.decisionFor(
                        failure(ModelDiscovery.Status.TIMEOUT, "timed out"),
                        "zai", "brand-new-model", tempDir.resolve("catalogs.json")));
    }

    @Test
    void deliberateEmptyCatalogRejectsUnknownIds() {
        ModelDiscovery.Result empty = new ModelDiscovery.Result(
                ModelDiscovery.Status.SUCCESS_EMPTY, List.of(), "", List.of());
        assertEquals(ModelCatalogSelection.SelectionDecision.UNKNOWN,
                ModelCatalogSelection.decisionFor(empty, "zai", "anything", tempDir.resolve("catalogs.json")));
    }

    @Test
    void blankIdsAlwaysReject() {
        assertEquals(ModelCatalogSelection.SelectionDecision.UNKNOWN,
                ModelCatalogSelection.decisionFor(success("glm-4.5"), "zai", "  ", tempDir.resolve("catalogs.json")));
        assertEquals(ModelCatalogSelection.SelectionDecision.UNKNOWN,
                ModelCatalogSelection.decisionFor(null, "zai", "glm-4.5", tempDir.resolve("catalogs.json")));
    }

    @Test
    void notesExistOnlyForNonLiveAcceptances() {
        assertTrue(ModelCatalogSelection.noteFor(
                ModelCatalogSelection.SelectionDecision.LIVE_LIST, "m", "zai").isEmpty());
        assertFalse(ModelCatalogSelection.noteFor(
                ModelCatalogSelection.SelectionDecision.FALLBACK_LIST, "m", "zai").isEmpty());
        assertFalse(ModelCatalogSelection.noteFor(
                ModelCatalogSelection.SelectionDecision.MANUAL_ENTRY, "m", "zai").isEmpty());
        assertFalse(ModelCatalogSelection.noteFor(
                ModelCatalogSelection.SelectionDecision.UNLISTED, "m", "zai").isEmpty());
        assertTrue(ModelCatalogSelection.noteFor(
                ModelCatalogSelection.SelectionDecision.UNKNOWN, "m", "zai").isEmpty());
    }

    @Test
    void claudeCodeRouteCatalogNeverFeedsTheAnthropicApiRoute() {
        // Both routes are provider "anthropic". Claude Code's rows include
        // aliases (default, opus) that the Anthropic API rejects, so they are
        // recorded apart and never offered or matched on the API-key route.
        Path store = tempDir.resolve("catalogs.json");
        ModelDiscovery.Result claudeCode = ModelDiscoveryHttp.claudeCliResult(
                List.of(model("default"), model("opus"), model("claude-sonnet-4-6")));
        assertEquals(List.of("default", "opus", "claude-sonnet-4-6"),
                ModelCatalogSelection.listForPicker(claudeCode, "anthropic", store).models());

        ModelDiscovery.Result apiOutage = failure(ModelDiscovery.Status.UNAVAILABLE, "connection reset");
        assertTrue(ModelCatalogSelection.listForPicker(apiOutage, "anthropic", store).models().isEmpty());
        assertEquals(ModelCatalogSelection.SelectionDecision.MANUAL_ENTRY,
                ModelCatalogSelection.decisionFor(apiOutage, "anthropic", "default", store));

        // The Claude Code route keeps its own last known good catalog.
        ModelDiscovery.Result claudeOutage = ModelDiscoveryHttp.claudeCliResult(List.of(), "timed out");
        ModelCatalogSelection.CatalogList fallback =
                ModelCatalogSelection.listForPicker(claudeOutage, "anthropic", store);
        assertTrue(fallback.fromFallback());
        assertEquals(List.of("default", "opus", "claude-sonnet-4-6"), fallback.models());
        assertEquals(ModelCatalogSelection.SelectionDecision.FALLBACK_LIST,
                ModelCatalogSelection.decisionFor(claudeOutage, "anthropic", "opus", store));
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
