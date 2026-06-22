/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.web.controllers.grounding;

import ai.kompile.graph.reasoning.learning.MebnWeightSerializer;
import ai.kompile.knowledgegraph.persistence.MebnWeightPersistenceAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit 5 (no Spring) tests for the MEBN endpoint on {@link KbWeightsController}.
 *
 * <p>The tests use a {@link MebnWeightPersistenceAdapter} wired to a temp directory so
 * they do not touch the real file-system and need no Spring context.</p>
 */
@DisplayName("KbWeightsController — MEBN endpoint")
class KbWeightsControllerMebnTest {

    @TempDir
    Path tempDir;

    private KbWeightsController controller;
    private MebnWeightPersistenceAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        adapter = adapterFor(tempDir.toString());
        // weightStore = null (PSL), mebnWeightPersistenceAdapter = the real adapter
        controller = new KbWeightsController(null, adapter);
    }

    // ── Adapter absent ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Adapter absent")
    class AdapterAbsent {

        @Test
        @DisplayName("returns 503 when MebnWeightPersistenceAdapter is null")
        void noAdapter_returns503() {
            KbWeightsController nullAdapter = new KbWeightsController(null, null);
            ResponseEntity<List<KbWeightsController.MebnWeightRow>> resp =
                    nullAdapter.getMebnWeights(1L);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        }
    }

    // ── Empty store ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Empty store")
    class EmptyStore {

        @Test
        @DisplayName("returns 200 with empty list when no weights file exists")
        void noFile_returns200Empty() {
            ResponseEntity<List<KbWeightsController.MebnWeightRow>> resp =
                    controller.getMebnWeights(42L);
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            assertTrue(resp.getBody().isEmpty(), "Expected empty list when no weights file");
        }
    }

    // ── Happy path ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        @DisplayName("returns rows for a written weights file")
        void writtenFile_returnsRows() throws IOException {
            // Write a synthetic mebn-weights.json for factSheet 7
            long fsId = 7L;
            String json = "{\"FragA|cause->isActive\":0.75,\"FragA|risk->isActive\":0.4,"
                    + "\"FragB|parent->child\":0.9}";
            writeWeightsFile(fsId, json);

            ResponseEntity<List<KbWeightsController.MebnWeightRow>> resp =
                    controller.getMebnWeights(fsId);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            List<KbWeightsController.MebnWeightRow> rows = resp.getBody();
            assertEquals(3, rows.size(), "Expected one row per edge");

            // Rows should be sorted by mFragName then conditionDescription
            KbWeightsController.MebnWeightRow first = rows.get(0);
            assertEquals("FragA", first.mFragName());
            assertEquals("cause->isActive", first.conditionDescription());
            assertEquals(0.75, first.learnedStrength(), 1e-9);

            KbWeightsController.MebnWeightRow second = rows.get(1);
            assertEquals("FragA", second.mFragName());
            assertEquals("risk->isActive", second.conditionDescription());
            assertEquals(0.4, second.learnedStrength(), 1e-9);

            KbWeightsController.MebnWeightRow third = rows.get(2);
            assertEquals("FragB", third.mFragName());
            assertEquals("parent->child", third.conditionDescription());
            assertEquals(0.9, third.learnedStrength(), 1e-9);
        }

        @Test
        @DisplayName("mFragName and conditionDescription are correctly split on last pipe")
        void compositeSplit_onLastPipe() throws IOException {
            // Key with pipe in mFrag name (edge case: mFrag name itself contains '|')
            long fsId = 8L;
            String json = "{\"Frag|With|Pipe|cause->effect\":0.55}";
            writeWeightsFile(fsId, json);

            ResponseEntity<List<KbWeightsController.MebnWeightRow>> resp =
                    controller.getMebnWeights(fsId);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            List<KbWeightsController.MebnWeightRow> rows = resp.getBody();
            assertNotNull(rows);
            assertEquals(1, rows.size());
            // Last pipe splits correctly
            assertEquals("Frag|With|Pipe", rows.get(0).mFragName());
            assertEquals("cause->effect", rows.get(0).conditionDescription());
        }

        @Test
        @DisplayName("empty weights JSON returns 200 with empty list")
        void emptyJson_returns200Empty() throws IOException {
            writeWeightsFile(9L, "{}");

            ResponseEntity<List<KbWeightsController.MebnWeightRow>> resp =
                    controller.getMebnWeights(9L);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            assertTrue(resp.getBody().isEmpty());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private MebnWeightPersistenceAdapter adapterFor(String dataDirPath) throws Exception {
        MebnWeightPersistenceAdapter a = new MebnWeightPersistenceAdapter();
        Field f = MebnWeightPersistenceAdapter.class.getDeclaredField("dataDir");
        f.setAccessible(true);
        f.set(a, dataDirPath);
        return a;
    }

    private void writeWeightsFile(long factSheetId, String json) throws IOException {
        Path dir = tempDir.resolve("data").resolve("graph").resolve("reasoning")
                .resolve(String.valueOf(factSheetId));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mebn-weights.json"), json, StandardCharsets.UTF_8);
    }
}
