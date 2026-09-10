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
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCatalogFallbackTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void isolateStore() {
        // The store caches in memory keyed by path; reset between tests so each
        // test's temp store is authoritative.
        ModelCatalogFallback.resetMemoryForTest();
    }

    private Path store() {
        return tempDir.resolve("model-catalogs.json");
    }

    @Test
    void recordPersistsAndReloadsAcrossInstances() {
        ModelCatalogFallback.record("zai", List.of("glm-4.5", "glm-4.6"),
                "https://api.z.ai/api/coding/paas/v4", store());
        // New lookup must survive without any in-memory seeding (fresh read).
        assertTrue(ModelCatalogFallback.knows("zai", "GLM-4.6", store()));
        assertTrue(ModelCatalogFallback.knows("ZAI", "glm-4.5", store()));
        assertFalse(ModelCatalogFallback.knows("zai", "nope", store()));
        assertFalse(ModelCatalogFallback.knows("openai", "glm-4.6", store()));
    }

    @Test
    void recordReplacesEarlierEntriesForTheSameProvider() {
        ModelCatalogFallback.record("zai", List.of("old-model"), null, store());
        ModelCatalogFallback.record("zai", List.of("new-model"), null, store());
        ModelCatalogFallback.RecordedCatalog entry =
                ModelCatalogFallback.lookup("zai", store()).orElseThrow();
        assertEquals(List.of("new-model"), entry.models());
    }

    @Test
    void emptyOrBlankEntriesAreNeverRecorded() {
        ModelCatalogFallback.record("zai", List.of(), null, store());
        ModelCatalogFallback.record("zai", null, null, store());
        ModelCatalogFallback.record(null, List.of("glm-4.5"), null, store());
        assertTrue(ModelCatalogFallback.lookup("zai", store()).isEmpty());
        assertFalse(Files.exists(store()));
    }

    @Test
    void corruptStoreDegradesToEmptyInsteadOfThrowing() throws Exception {
        Files.writeString(store(), "{ not json !!!");
        assertTrue(ModelCatalogFallback.lookup("zai", store()).isEmpty());
    }

    @Test
    void persistedFormatRoundTripsProviderBaseUrlAndTime() throws Exception {
        ModelCatalogFallback.record("zai", List.of("glm-4.5", "glm-4.6"),
                "https://api.z.ai/api/coding/paas/v4", store());
        // Overwrite recordedAt through a second write, then verify parse of the shape.
        ModelCatalogFallback.lookup("zai", store()).orElseThrow();

        String body = Files.readString(store());
        assertTrue(body.contains("\"schemaVersion\" : 1"));
        assertTrue(body.contains("glm-4.5"));
        assertTrue(body.contains("https://api.z.ai/api/coding/paas/v4"));
        assertTrue(body.contains("recordedAt"));
        // Parsing again must not throw and must keep the provider entry.
        assertTrue(ModelCatalogFallback.knows("zai", "glm-4.6", store()));
    }

    @Test
    void ageLabelFormatsHumanReadableDurations() {
        Instant now = Instant.parse("2026-09-13T12:00:00Z");
        assertEquals("just now",
                ModelCatalogFallback.ageLabel(now.minusSeconds(30), now));
        assertEquals("5m ago",
                ModelCatalogFallback.ageLabel(now.minusSeconds(5 * 60), now));
        assertEquals("3h ago",
                ModelCatalogFallback.ageLabel(now.minusSeconds(3 * 3600), now));
        assertEquals("2d ago",
                ModelCatalogFallback.ageLabel(now.minusSeconds(2 * 86400), now));
    }
}
