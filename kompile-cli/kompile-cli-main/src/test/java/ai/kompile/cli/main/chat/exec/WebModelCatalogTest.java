/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.LiveModelDiscovery;
import ai.kompile.cli.main.chat.config.ModelDiscovery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Web {@code /model} listing and validation follow the terminal picker: the
 * route's live discovery is the list, a verified list is recorded as the last
 * known good catalog, and that catalog is shown only when the provider could
 * not be reached. Anthropic's Claude Code route and its API-key route share the
 * provider id but never each other's catalog.
 */
class WebModelCatalogTest {

    /** The Claude Code route's discovery endpoint marker, which keys its own catalog. */
    private static final String CLAUDE_CODE = "native:claude";

    @TempDir
    Path tempDir;

    private final AtomicReference<ModelDiscovery.Result> next = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void installDiscovery() {
        WebModelCatalog.useDiscovery(config -> {
            calls.incrementAndGet();
            return next.get();
        });
    }

    @AfterEach
    void restoreDiscovery() {
        WebModelCatalog.useDiscovery(null);
    }

    @Test
    void listsTheLiveCatalogAndFallsBackToItOnlyWhenTheProviderIsUnreachable() {
        Path store = tempDir.resolve("model-catalogs.json");
        ChatConfig apiKey = anthropic("api-key");

        next.set(live("claude-opus-5-5", "claude-sonnet-5"));
        WebModelCatalog.Listing listing = WebModelCatalog.listing(apiKey, store);
        assertEquals(List.of("claude-opus-5-5", "claude-sonnet-5"), ids(listing));
        assertTrue(listing.liveListingAvailable());
        assertEquals("", listing.note());

        // Transport failure: the recorded list comes back, flagged as not live.
        next.set(ModelDiscovery.Result.failure(ModelDiscovery.Status.UNAVAILABLE, "connection refused", List.of()));
        listing = WebModelCatalog.listing(apiKey, store);
        assertEquals(List.of("claude-opus-5-5", "claude-sonnet-5"), ids(listing));
        assertFalse(listing.liveListingAvailable());
        assertTrue(listing.note().contains("last known good"), listing.note());

        // An authoritative answer never shows the stale list.
        next.set(ModelDiscovery.Result.failure(ModelDiscovery.Status.AUTH_REQUIRED, "missing key", List.of()));
        listing = WebModelCatalog.listing(apiKey, store);
        assertEquals(List.of(), ids(listing));
        assertFalse(listing.note().isBlank());
        assertEquals(WebModelCatalog.Selection.UNKNOWN, WebModelCatalog.validate(apiKey, "claude-sonnet-5", store));
    }

    @Test
    void selectionsAreDecidedLikeTheTerminalModelCommand() {
        Path store = tempDir.resolve("model-catalogs.json");
        ChatConfig apiKey = anthropic("api-key");
        next.set(live("claude-opus-5-5", "claude-sonnet-5"));

        assertEquals(WebModelCatalog.Selection.KNOWN, WebModelCatalog.validate(apiKey, "claude-sonnet-5", store));
        // A day-one id absent from an authoritative live list is accepted; the provider validates it.
        assertEquals(WebModelCatalog.Selection.KNOWN, WebModelCatalog.validate(apiKey, "claude-new-6", store));
        // A menu index is never persisted as a model id.
        assertEquals(WebModelCatalog.Selection.UNKNOWN, WebModelCatalog.validate(apiKey, "2", store));
        ModelDiscovery.Result discovery = WebModelCatalog.discover(apiKey);
        assertEquals("claude-sonnet-5", WebModelCatalog.canonicalId(apiKey, "CLAUDE-SONNET-5", discovery, store));

        // An empty authoritative answer still honours the recorded catalog, and nothing else.
        next.set(ModelDiscovery.Result.success(List.of(), List.of()));
        assertEquals(WebModelCatalog.Selection.KNOWN, WebModelCatalog.validate(apiKey, "claude-sonnet-5", store));
        assertEquals(WebModelCatalog.Selection.UNKNOWN, WebModelCatalog.validate(apiKey, "never-listed", store));
    }

    @Test
    void claudeCodeAndApiKeyRoutesKeepSeparateLastKnownGoodCatalogs() {
        Path store = tempDir.resolve("model-catalogs.json");
        ChatConfig claudeCode = anthropic("oauth");
        ChatConfig apiKey = anthropic("api-key");

        next.set(ModelDiscovery.Result.success(models("default", "opus"),
                List.of(CLAUDE_CODE)));
        assertEquals(List.of("default", "opus"), ids(WebModelCatalog.listing(claudeCode, store)));
        next.set(live("claude-sonnet-5"));
        assertEquals(List.of("claude-sonnet-5"), ids(WebModelCatalog.listing(apiKey, store)));

        next.set(ModelDiscovery.Result.failure(ModelDiscovery.Status.TIMEOUT, "timed out",
                List.of(CLAUDE_CODE)));
        assertEquals(List.of("default", "opus"), ids(WebModelCatalog.listing(claudeCode, store)));
        next.set(ModelDiscovery.Result.failure(ModelDiscovery.Status.TIMEOUT, "timed out", List.of()));
        assertEquals(List.of("claude-sonnet-5"), ids(WebModelCatalog.listing(apiKey, store)));

        // Each route's recorded catalog vouches only for its own ids.
        next.set(ModelDiscovery.Result.success(List.of(), List.of(CLAUDE_CODE)));
        assertEquals(WebModelCatalog.Selection.KNOWN, WebModelCatalog.validate(claudeCode, "opus", store));
        next.set(ModelDiscovery.Result.success(List.of(), List.of()));
        assertEquals(WebModelCatalog.Selection.UNKNOWN, WebModelCatalog.validate(apiKey, "opus", store));
    }

    @Test
    void oneListingRunsOneDiscovery() {
        next.set(live("glm-5"));
        ChatConfig config = new ChatConfig("zai", null, "glm-5", null);
        WebModelCatalog.listing(config, tempDir.resolve("model-catalogs.json"));
        assertEquals(1, calls.get());
    }

    private static ModelDiscovery.Result live(String... ids) {
        return ModelDiscovery.Result.success(models(ids), List.of("https://provider.example/v1/models"));
    }

    private static List<LiveModelDiscovery.Model> models(String... ids) {
        return java.util.Arrays.stream(ids).map(id -> new LiveModelDiscovery.Model(id, List.of())).toList();
    }

    private static ChatConfig anthropic(String authenticationMethod) {
        ChatConfig config = new ChatConfig("anthropic", null, "claude-opus-5-5", null);
        config.setAuthenticationMethod(authenticationMethod);
        return config;
    }

    private static List<String> ids(WebModelCatalog.Listing listing) {
        return listing.entries().stream().map(WebModelCatalog.Entry::id).toList();
    }
}
