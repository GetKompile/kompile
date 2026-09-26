/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.ModelCatalogFallback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Web {@code /model} listing and validation read the catalog of the config's
 * route: Anthropic's Claude Code route and its API-key route share the provider
 * id but never each other's model ids.
 */
class WebModelCatalogTest {

    @TempDir
    Path tempDir;

    @Test
    void anthropicRoutesListAndValidateOnlyTheirOwnCatalog() {
        Path store = tempDir.resolve("model-catalogs.json");
        ModelCatalogFallback.record(ModelCatalogFallback.catalogKey("anthropic", true),
                List.of("default", "opus"), null, store);
        ModelCatalogFallback.record("anthropic", List.of("claude-sonnet-4-6"),
                "https://api.anthropic.com/v1", store);

        ChatConfig claudeCode = anthropic("oauth");
        assertEquals(List.of("claude-opus-5-5", "default", "opus"),
                ids(WebModelCatalog.listing(claudeCode, store)));
        assertEquals(WebModelCatalog.Selection.KNOWN,
                WebModelCatalog.validate(claudeCode, "opus", store));
        assertEquals(WebModelCatalog.Selection.UNKNOWN,
                WebModelCatalog.validate(claudeCode, "claude-sonnet-4-6", store));

        ChatConfig apiKey = anthropic("api-key");
        assertEquals(List.of("claude-opus-5-5", "claude-sonnet-4-6"),
                ids(WebModelCatalog.listing(apiKey, store)));
        assertEquals(WebModelCatalog.Selection.UNKNOWN,
                WebModelCatalog.validate(apiKey, "default", store));
        // Canonical casing only comes from the route's own catalog.
        assertEquals("DEFAULT", WebModelCatalog.canonicalId(apiKey, "DEFAULT", store));
        assertEquals("opus", WebModelCatalog.canonicalId(claudeCode, "OPUS", store));
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
