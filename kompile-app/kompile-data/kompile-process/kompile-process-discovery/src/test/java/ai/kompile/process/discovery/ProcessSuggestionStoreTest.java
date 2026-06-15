/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */

package ai.kompile.process.discovery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the in-memory + file-backed ProcessSuggestionStore.
 * Uses a temporary directory to avoid polluting ~/.kompile during tests.
 */
class ProcessSuggestionStoreTest {

    @TempDir
    Path tempDir;

    private ProcessSuggestionStore store;

    @BeforeEach
    void setUp() throws Exception {
        // Point the store at a temp directory so tests don't write to ~/.kompile
        Path storageDir = tempDir.resolve("suggestions");
        store = new ProcessSuggestionStore();
        injectStorageDir(store, storageDir);
    }

    /**
     * Injects a custom storageDir via reflection so the store uses the temp directory.
     */
    private void injectStorageDir(ProcessSuggestionStore storeInstance, Path dir) throws Exception {
        Field field = ProcessSuggestionStore.class.getDeclaredField("storageDir");
        field.setAccessible(true);
        field.set(storeInstance, dir);

        // Also clear any suggestions loaded from the real ~/.kompile directory
        Field suggestionsField = ProcessSuggestionStore.class.getDeclaredField("suggestions");
        suggestionsField.setAccessible(true);
        ((Map<?, ?>) suggestionsField.get(storeInstance)).clear();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper builders
    // ─────────────────────────────────────────────────────────────────────────

    private ProcessSuggestion buildSuggestion(String name, double confidence) {
        return ProcessSuggestion.builder()
                .name(name)
                .description("Description for " + name)
                .discoverySource("EMAIL_FLOW")
                .confidence(confidence)
                .factSheetId(42L)
                .sourceGraphNodeIds(List.of("node-1", "node-2"))
                .evidence(List.of("2 occurrences observed"))
                .build();
    }

    private ProcessSuggestion buildEnrichedSuggestion() {
        Map<String, Double> posteriors = new LinkedHashMap<>();
        posteriors.put("node-1", 0.82);
        posteriors.put("node-2", 0.61);

        List<ProcessSuggestion.StructuredEvidence> structured = new ArrayList<>();
        structured.add(ProcessSuggestion.StructuredEvidence.builder()
                .type("BAYESIAN")
                .description("Node A: prior=0.50 → posterior=0.82 (shift +0.32)")
                .score(0.82)
                .supportingNodeIds(List.of("node-1"))
                .build());
        structured.add(ProcessSuggestion.StructuredEvidence.builder()
                .type("CAUSAL")
                .description("Bayesian inference: 3 elimination steps, 2 network nodes")
                .score(0.75)
                .build());

        return ProcessSuggestion.builder()
                .name("Enriched Process")
                .description("Has Bayesian posteriors")
                .discoverySource("EXCEL_COMPUTATION")
                .confidence(0.75)
                .factSheetId(99L)
                .sourceGraphNodeIds(List.of("node-1", "node-2"))
                .evidence(List.of("1 occurrence observed"))
                .bayesianPosteriors(posteriors)
                .structuredEvidence(structured)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. save assigns an ID when none is set
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void save_assignsIdWhenMissing() {
        ProcessSuggestion s = buildSuggestion("Test Process", 0.7);
        assertNull(s.getId(), "Pre-save ID should be null");

        store.save(s);

        assertNotNull(s.getId(), "save() must assign a non-null ID");
        assertTrue(s.getId().startsWith("suggestion-"), "ID must follow suggestion-* pattern");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. save assigns discoveredAt when null
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void save_assignsDiscoveredAtWhenMissing() {
        ProcessSuggestion s = buildSuggestion("Timed Process", 0.6);
        assertNull(s.getDiscoveredAt(), "Pre-save discoveredAt should be null");

        Instant before = Instant.now();
        store.save(s);
        Instant after = Instant.now();

        assertNotNull(s.getDiscoveredAt());
        assertFalse(s.getDiscoveredAt().isBefore(before));
        assertFalse(s.getDiscoveredAt().isAfter(after));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. get retrieves saved suggestion by ID
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void get_retrievesSavedSuggestion() {
        ProcessSuggestion s = buildSuggestion("Retrieve Me", 0.8);
        store.save(s);
        String id = s.getId();

        Optional<ProcessSuggestion> found = store.get(id);

        assertTrue(found.isPresent(), "Should find saved suggestion by ID");
        assertEquals("Retrieve Me", found.get().getName());
        assertEquals(0.8, found.get().getConfidence(), 1e-9);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. get returns empty for unknown ID
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void get_returnsEmptyForUnknownId() {
        Optional<ProcessSuggestion> found = store.get("nonexistent-id-xyz");
        assertFalse(found.isPresent(), "Should return empty for unknown ID");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. listAll returns all saved suggestions
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void listAll_returnsAllSaved() {
        store.save(buildSuggestion("Process 1", 0.7));
        store.save(buildSuggestion("Process 2", 0.8));
        store.save(buildSuggestion("Process 3", 0.9));

        List<ProcessSuggestion> all = store.listAll();

        assertEquals(3, all.size(), "listAll must return all 3 saved suggestions");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. listAll returns empty when no suggestions saved
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void listAll_emptyWhenNoneSaved() {
        List<ProcessSuggestion> all = store.listAll();
        assertNotNull(all);
        assertTrue(all.isEmpty(), "listAll should be empty initially");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. listByFactSheet returns only suggestions for that factSheetId
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void listByFactSheet_filtersCorrectly() {
        ProcessSuggestion fs1a = buildSuggestion("FS1 Process A", 0.7);
        ProcessSuggestion fs1b = buildSuggestion("FS1 Process B", 0.8);
        ProcessSuggestion fs2 = ProcessSuggestion.builder()
                .name("FS2 Process")
                .confidence(0.65)
                .discoverySource("DOCUMENT_PIPELINE")
                .factSheetId(200L)
                .build();

        store.save(fs1a);
        store.save(fs1b);
        store.save(fs2);

        List<ProcessSuggestion> forFs1 = store.listByFactSheet(42L);
        List<ProcessSuggestion> forFs2 = store.listByFactSheet(200L);

        assertEquals(2, forFs1.size(), "Should return 2 suggestions for factSheetId=42");
        assertEquals(1, forFs2.size(), "Should return 1 suggestion for factSheetId=200");

        assertTrue(forFs1.stream().anyMatch(s -> "FS1 Process A".equals(s.getName())));
        assertTrue(forFs1.stream().anyMatch(s -> "FS1 Process B".equals(s.getName())));
        assertTrue(forFs2.stream().anyMatch(s -> "FS2 Process".equals(s.getName())));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. listByFactSheet returns empty for unknown factSheetId
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void listByFactSheet_emptyForUnknownId() {
        store.save(buildSuggestion("Process A", 0.7));

        List<ProcessSuggestion> result = store.listByFactSheet(999L);
        assertNotNull(result);
        assertTrue(result.isEmpty(), "Should return empty for unknown factSheetId");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 9. listPending returns suggestions not yet accepted
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void listPending_returnsNonAccepted() {
        ProcessSuggestion pending = buildSuggestion("Pending Process", 0.7);
        ProcessSuggestion accepted = buildSuggestion("Accepted Process", 0.8);

        store.save(pending);
        store.save(accepted);

        store.markAccepted(accepted.getId(), "process-def-1");

        List<ProcessSuggestion> pendingList = store.listPending();

        assertEquals(1, pendingList.size(), "Should return only 1 pending suggestion");
        assertEquals("Pending Process", pendingList.get(0).getName());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 10. listPending returns all when none accepted
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void listPending_returnsAllWhenNoneAccepted() {
        store.save(buildSuggestion("Process 1", 0.6));
        store.save(buildSuggestion("Process 2", 0.7));

        List<ProcessSuggestion> pending = store.listPending();
        assertEquals(2, pending.size(), "All suggestions should be pending initially");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 11. markAccepted sets accepted=true and processDefinitionId
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void markAccepted_setsAcceptedFlagAndDefinitionId() {
        ProcessSuggestion s = buildSuggestion("Accept Me", 0.9);
        store.save(s);
        String id = s.getId();

        store.markAccepted(id, "def-abc123");

        Optional<ProcessSuggestion> updated = store.get(id);
        assertTrue(updated.isPresent());
        assertEquals(Boolean.TRUE, updated.get().getAccepted(),
                "accepted must be true after markAccepted");
        assertEquals("def-abc123", updated.get().getAcceptedProcessDefinitionId(),
                "acceptedProcessDefinitionId must be set");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 12. markAccepted on unknown ID does nothing (no exception)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void markAccepted_unknownIdDoesNotThrow() {
        assertDoesNotThrow(() -> store.markAccepted("nonexistent-id", "def-xyz"),
                "markAccepted on unknown ID must not throw");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 13. delete removes suggestion from memory
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void delete_removesSuggestionFromMemory() {
        ProcessSuggestion s = buildSuggestion("Delete Me", 0.5);
        store.save(s);
        String id = s.getId();

        assertTrue(store.get(id).isPresent(), "Should exist before delete");

        store.delete(id);

        assertFalse(store.get(id).isPresent(), "Should not exist after delete");
        assertEquals(0, store.listAll().size(), "listAll must be empty after delete");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 14. delete on unknown ID does not throw
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void delete_unknownIdDoesNotThrow() {
        assertDoesNotThrow(() -> store.delete("nonexistent-id-for-delete"),
                "delete on unknown ID must not throw");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 15. save preserves bayesianPosteriors
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void save_preservesBayesianPosteriors() {
        ProcessSuggestion enriched = buildEnrichedSuggestion();
        store.save(enriched);

        Optional<ProcessSuggestion> found = store.get(enriched.getId());
        assertTrue(found.isPresent());

        Map<String, Double> posteriors = found.get().getBayesianPosteriors();
        assertNotNull(posteriors);
        assertFalse(posteriors.isEmpty(), "bayesianPosteriors must be preserved");
        assertEquals(0.82, posteriors.get("node-1"), 1e-9);
        assertEquals(0.61, posteriors.get("node-2"), 1e-9);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 16. save preserves structuredEvidence
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void save_preservesStructuredEvidence() {
        ProcessSuggestion enriched = buildEnrichedSuggestion();
        store.save(enriched);

        Optional<ProcessSuggestion> found = store.get(enriched.getId());
        assertTrue(found.isPresent());

        List<ProcessSuggestion.StructuredEvidence> structured = found.get().getStructuredEvidence();
        assertNotNull(structured);
        assertEquals(2, structured.size(), "Both structured evidence entries must be preserved");

        // Check BAYESIAN entry
        ProcessSuggestion.StructuredEvidence bayesianEntry = structured.stream()
                .filter(e -> "BAYESIAN".equals(e.getType())).findFirst().orElse(null);
        assertNotNull(bayesianEntry);
        assertEquals(0.82, bayesianEntry.getScore(), 1e-9);
        assertTrue(bayesianEntry.getDescription().contains("prior=0.50"));

        // Check CAUSAL entry
        ProcessSuggestion.StructuredEvidence causalEntry = structured.stream()
                .filter(e -> "CAUSAL".equals(e.getType())).findFirst().orElse(null);
        assertNotNull(causalEntry);
        assertTrue(causalEntry.getDescription().contains("Bayesian inference"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 17. saveAll persists multiple suggestions
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void saveAll_persistsMultipleSuggestions() {
        List<ProcessSuggestion> batch = List.of(
                buildSuggestion("Batch A", 0.6),
                buildSuggestion("Batch B", 0.7),
                buildSuggestion("Batch C", 0.8)
        );

        store.saveAll(batch);

        assertEquals(3, store.listAll().size(), "saveAll must persist all 3 suggestions");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 18. save with pre-set ID preserves that ID
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void save_withPresetId_preservesId() {
        ProcessSuggestion s = buildSuggestion("Fixed ID Process", 0.75);
        s.setId("my-custom-id-42");

        store.save(s);

        Optional<ProcessSuggestion> found = store.get("my-custom-id-42");
        assertTrue(found.isPresent(), "Should be retrievable via preset ID");
        assertEquals("Fixed ID Process", found.get().getName());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 19. save with pre-set discoveredAt preserves that timestamp
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void save_withPresetDiscoveredAt_preservesTimestamp() {
        Instant fixedTime = Instant.parse("2025-01-15T10:30:00Z");
        ProcessSuggestion s = buildSuggestion("Timestamped Process", 0.7);
        s.setDiscoveredAt(fixedTime);

        store.save(s);

        Optional<ProcessSuggestion> found = store.get(s.getId());
        assertTrue(found.isPresent());
        assertEquals(fixedTime, found.get().getDiscoveredAt(),
                "Pre-set discoveredAt must be preserved");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 20. After delete, listPending count decreases
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void delete_removedSuggestionNoLongerInListPending() {
        ProcessSuggestion s1 = buildSuggestion("Keep", 0.7);
        ProcessSuggestion s2 = buildSuggestion("Remove", 0.6);

        store.save(s1);
        store.save(s2);

        assertEquals(2, store.listPending().size());

        store.delete(s2.getId());

        List<ProcessSuggestion> remaining = store.listPending();
        assertEquals(1, remaining.size());
        assertEquals("Keep", remaining.get(0).getName());
    }
}
