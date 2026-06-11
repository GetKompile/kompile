/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.services;

import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GraphSchemaPresetService} — CRUD operations on schema presets,
 * listing, type name extraction, disk persistence, and empty/null handling.
 */
class GraphSchemaPresetServiceTest {

    @TempDir
    Path tempDir;

    private GraphSchemaPresetService service;

    @BeforeEach
    void setUp() {
        // Use tempDir as dataDir — init() will create config/schema-presets/ inside it
        service = new GraphSchemaPresetService(tempDir.toString());
        service.init();
    }

    private GraphSchemaPresetService.PresetEntry createPreset(
            String name, String description, List<NodeType> nodeTypes, List<RelationshipType> relTypes) {
        GraphSchemaPresetService.PresetEntry entry = new GraphSchemaPresetService.PresetEntry();
        entry.name = name;
        entry.description = description;
        entry.version = 1;
        entry.schema = new GraphSchema(nodeTypes, relTypes, null);
        return entry;
    }

    // ─── Empty state ─────────────────────────────────────────────────

    @Test
    void listPresets_initiallyEmpty() {
        // No classpath presets in test context, so starts empty or with seeded ones
        // Just verify it doesn't throw
        List<GraphSchemaPresetService.PresetSummary> presets = service.listPresets();
        assertNotNull(presets);
    }

    @Test
    void getPreset_unknown_returnsEmpty() {
        assertTrue(service.getPreset("nonexistent").isEmpty());
    }

    @Test
    void getSchema_unknown_returnsEmpty() {
        assertTrue(service.getSchema("nonexistent").isEmpty());
    }

    // ─── Save and retrieve ───────────────────────────────────────────

    @Test
    void savePreset_thenGetPreset_returnsIt() throws IOException {
        GraphSchemaPresetService.PresetEntry entry = createPreset(
                "Test Preset", "A test preset",
                List.of(new NodeType("PERSON", "A person", null)),
                List.of(new RelationshipType("KNOWS", "Knows relationship", null)));

        service.savePreset("test-preset", entry);

        Optional<GraphSchemaPresetService.PresetEntry> result = service.getPreset("test-preset");
        assertTrue(result.isPresent());
        assertEquals("test-preset", result.get().id);
        assertEquals("Test Preset", result.get().name);
        assertEquals("A test preset", result.get().description);
    }

    @Test
    void savePreset_persistsToDisk() throws IOException {
        GraphSchemaPresetService.PresetEntry entry = createPreset(
                "Disk Test", "Saved to disk", List.of(), List.of());

        service.savePreset("disk-test", entry);

        Path presetFile = tempDir.resolve("config").resolve("schema-presets").resolve("disk-test.json");
        assertTrue(Files.exists(presetFile));
        String content = Files.readString(presetFile);
        assertTrue(content.contains("Disk Test"));
    }

    @Test
    void savePreset_getSchema_returnsSchema() throws IOException {
        List<NodeType> nodeTypes = List.of(
                new NodeType("PERSON", "Person entity", null),
                new NodeType("ORG", "Organization", null));

        GraphSchemaPresetService.PresetEntry entry = createPreset(
                "Schema Test", "desc", nodeTypes, List.of());

        service.savePreset("schema-test", entry);

        Optional<GraphSchema> schema = service.getSchema("schema-test");
        assertTrue(schema.isPresent());
        assertEquals(2, schema.get().getNodeTypes().size());
    }

    @Test
    void savePreset_overwritesExisting() throws IOException {
        GraphSchemaPresetService.PresetEntry v1 = createPreset("V1", "Version 1", List.of(), List.of());
        service.savePreset("versioned", v1);

        GraphSchemaPresetService.PresetEntry v2 = createPreset("V2", "Version 2", List.of(), List.of());
        v2.version = 2;
        service.savePreset("versioned", v2);

        Optional<GraphSchemaPresetService.PresetEntry> result = service.getPreset("versioned");
        assertTrue(result.isPresent());
        assertEquals("V2", result.get().name);
        assertEquals(2, result.get().version);
    }

    // ─── List presets ────────────────────────────────────────────────

    @Test
    void listPresets_returnsAllSaved() throws IOException {
        service.savePreset("alpha", createPreset("Alpha", "First", List.of(), List.of()));
        service.savePreset("beta", createPreset("Beta", "Second",
                List.of(new NodeType("X", "X type", null)),
                List.of(new RelationshipType("Y", "Y rel", null))));

        List<GraphSchemaPresetService.PresetSummary> summaries = service.listPresets();

        // Filter to only our test presets (there may be seeded ones)
        long alphaCount = summaries.stream().filter(s -> "alpha".equals(s.id())).count();
        long betaCount = summaries.stream().filter(s -> "beta".equals(s.id())).count();
        assertEquals(1, alphaCount);
        assertEquals(1, betaCount);

        GraphSchemaPresetService.PresetSummary beta = summaries.stream()
                .filter(s -> "beta".equals(s.id())).findFirst().orElseThrow();
        assertEquals(1, beta.nodeTypeCount());
        assertEquals(1, beta.relationshipTypeCount());
    }

    @Test
    void listPresets_sortedById() throws IOException {
        service.savePreset("charlie", createPreset("C", "c", List.of(), List.of()));
        service.savePreset("alpha", createPreset("A", "a", List.of(), List.of()));
        service.savePreset("bravo", createPreset("B", "b", List.of(), List.of()));

        List<GraphSchemaPresetService.PresetSummary> summaries = service.listPresets();
        // Verify alphabetical ordering of our entries
        List<String> ids = summaries.stream().map(GraphSchemaPresetService.PresetSummary::id).toList();
        int alphaIdx = ids.indexOf("alpha");
        int bravoIdx = ids.indexOf("bravo");
        int charlieIdx = ids.indexOf("charlie");
        assertTrue(alphaIdx < bravoIdx);
        assertTrue(bravoIdx < charlieIdx);
    }

    // ─── Delete preset ───────────────────────────────────────────────

    @Test
    void deletePreset_removesFromMemoryAndDisk() throws IOException {
        service.savePreset("doomed", createPreset("Doomed", "Will be deleted", List.of(), List.of()));
        assertTrue(service.getPreset("doomed").isPresent());

        boolean deleted = service.deletePreset("doomed");
        assertTrue(deleted);
        assertTrue(service.getPreset("doomed").isEmpty());

        Path presetFile = tempDir.resolve("config").resolve("schema-presets").resolve("doomed.json");
        assertFalse(Files.exists(presetFile));
    }

    @Test
    void deletePreset_nonexistent_returnsTrue() {
        // deleteIfExists returns true even if file doesn't exist, no exception
        boolean result = service.deletePreset("nonexistent");
        assertTrue(result);
    }

    // ─── getPresetTypeNames ──────────────────────────────────────────

    @Test
    void getPresetTypeNames_extractsLabelsAndTypes() throws IOException {
        List<NodeType> nodeTypes = List.of(
                new NodeType("PERSON", "Person", null),
                new NodeType("COMPANY", "Company", null));
        List<RelationshipType> relTypes = List.of(
                new RelationshipType("WORKS_AT", "Employment", null),
                new RelationshipType("KNOWS", "Social", null));

        service.savePreset("typed", createPreset("Typed", "Has types", nodeTypes, relTypes));

        Optional<Map<String, List<String>>> typeNames = service.getPresetTypeNames("typed");
        assertTrue(typeNames.isPresent());

        List<String> entityTypes = typeNames.get().get("entityTypes");
        List<String> relationshipTypes = typeNames.get().get("relationshipTypes");

        assertEquals(2, entityTypes.size());
        assertTrue(entityTypes.contains("PERSON"));
        assertTrue(entityTypes.contains("COMPANY"));
        assertEquals(2, relationshipTypes.size());
        assertTrue(relationshipTypes.contains("WORKS_AT"));
        assertTrue(relationshipTypes.contains("KNOWS"));
    }

    @Test
    void getPresetTypeNames_unknown_returnsEmpty() {
        assertTrue(service.getPresetTypeNames("missing").isEmpty());
    }

    @Test
    void getPresetTypeNames_emptySchema_returnsEmptyLists() throws IOException {
        service.savePreset("empty-schema", createPreset("Empty", "No types", null, null));

        Optional<Map<String, List<String>>> typeNames = service.getPresetTypeNames("empty-schema");
        assertTrue(typeNames.isPresent());
        assertTrue(typeNames.get().get("entityTypes").isEmpty());
        assertTrue(typeNames.get().get("relationshipTypes").isEmpty());
    }

    // ─── Reload from disk ────────────────────────────────────────────

    @Test
    void loadPresetsFromDisk_reloadsAfterReinit() throws IOException {
        service.savePreset("persistent", createPreset("Persistent", "Survives reload",
                List.of(new NodeType("FOO", "Foo type", null)), List.of()));

        // Create a new service instance pointing at the same tempDir
        GraphSchemaPresetService service2 = new GraphSchemaPresetService(tempDir.toString());
        service2.init();

        Optional<GraphSchemaPresetService.PresetEntry> result = service2.getPreset("persistent");
        assertTrue(result.isPresent());
        assertEquals("Persistent", result.get().name);
        assertEquals(1, result.get().schema.getNodeTypes().size());
    }

    // ─── PresetEntry JSON setters ────────────────────────────────────

    @Test
    void presetEntry_jsonSetters_populateSchema() {
        GraphSchemaPresetService.PresetEntry entry = new GraphSchemaPresetService.PresetEntry();
        entry.setNodeTypes(List.of(new NodeType("A", "A type", null)));
        entry.setRelationshipTypes(List.of(new RelationshipType("R", "R rel", null)));
        entry.setPatterns(List.of("(A)-[:R]->(B)"));

        assertEquals(1, entry.getNodeTypes().size());
        assertEquals("A", entry.getNodeTypes().get(0).getLabel());
        assertEquals(1, entry.getRelationshipTypes().size());
        assertEquals("R", entry.getRelationshipTypes().get(0).getType());
        assertEquals(1, entry.getPatterns().size());
    }

    // ─── PresetSummary record ────────────────────────────────────────

    @Test
    void presetSummary_fieldsAccessible() {
        GraphSchemaPresetService.PresetSummary summary =
                new GraphSchemaPresetService.PresetSummary("id1", "Name", "Desc", 3, 5, 2);

        assertEquals("id1", summary.id());
        assertEquals("Name", summary.name());
        assertEquals("Desc", summary.description());
        assertEquals(3, summary.version());
        assertEquals(5, summary.nodeTypeCount());
        assertEquals(2, summary.relationshipTypeCount());
    }
}
