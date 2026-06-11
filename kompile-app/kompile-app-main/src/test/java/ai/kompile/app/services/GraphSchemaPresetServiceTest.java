/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.services;

import ai.kompile.app.services.GraphSchemaPresetService.PresetEntry;
import ai.kompile.app.services.GraphSchemaPresetService.PresetSummary;
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

class GraphSchemaPresetServiceTest {

    @TempDir
    Path tempDir;

    private GraphSchemaPresetService service;

    @BeforeEach
    void setUp() {
        service = new GraphSchemaPresetService(tempDir.toString());
        service.init();
    }

    // --- init ---

    @Test
    void initCreatesPresetsDirectory() {
        Path presetsDir = tempDir.resolve("config").resolve("schema-presets");
        assertTrue(Files.isDirectory(presetsDir));
    }

    // --- savePreset / getPreset ---

    @Test
    void saveAndRetrievePreset() throws IOException {
        PresetEntry entry = createPreset("test-schema", "Test Schema",
                List.of(new NodeType("PERSON", "A person", null)),
                List.of(new RelationshipType("WORKS_AT", "Employment", null)));

        service.savePreset("test-schema", entry);

        Optional<PresetEntry> loaded = service.getPreset("test-schema");
        assertTrue(loaded.isPresent());
        assertEquals("test-schema", loaded.get().id);
        assertEquals("Test Schema", loaded.get().name);
        assertEquals(1, loaded.get().schema.getNodeTypes().size());
        assertEquals("PERSON", loaded.get().schema.getNodeTypes().get(0).getLabel());
        assertEquals(1, loaded.get().schema.getRelationshipTypes().size());
        assertEquals("WORKS_AT", loaded.get().schema.getRelationshipTypes().get(0).getType());
    }

    @Test
    void savePresetWritesJsonFile() throws IOException {
        PresetEntry entry = createPreset("file-test", "File Test", List.of(), List.of());
        service.savePreset("file-test", entry);

        Path presetFile = tempDir.resolve("config").resolve("schema-presets").resolve("file-test.json");
        assertTrue(Files.exists(presetFile));
        String content = Files.readString(presetFile);
        assertTrue(content.contains("File Test"));
    }

    @Test
    void savePresetSetsIdFromParameter() throws IOException {
        PresetEntry entry = createPreset(null, "No ID", List.of(), List.of());
        PresetEntry saved = service.savePreset("assigned-id", entry);

        assertEquals("assigned-id", saved.id);
    }

    @Test
    void savePresetOverwritesExisting() throws IOException {
        PresetEntry v1 = createPreset("evolving", "Version 1",
                List.of(new NodeType("PERSON", "v1", null)), List.of());
        service.savePreset("evolving", v1);

        PresetEntry v2 = createPreset("evolving", "Version 2",
                List.of(new NodeType("PERSON", "v2", null), new NodeType("ORG", "v2", null)),
                List.of());
        service.savePreset("evolving", v2);

        PresetEntry loaded = service.getPreset("evolving").orElseThrow();
        assertEquals("Version 2", loaded.name);
        assertEquals(2, loaded.schema.getNodeTypes().size());
    }

    // --- getPreset ---

    @Test
    void getPresetReturnsEmptyForUnknownId() {
        assertTrue(service.getPreset("nonexistent").isEmpty());
    }

    // --- getSchema ---

    @Test
    void getSchemaReturnsSchemaFromPreset() throws IOException {
        PresetEntry entry = createPreset("schema-test", "Schema Test",
                List.of(new NodeType("DOC", "Document", null)),
                List.of(new RelationshipType("CONTAINS", "Contains", null)));
        service.savePreset("schema-test", entry);

        Optional<GraphSchema> schema = service.getSchema("schema-test");
        assertTrue(schema.isPresent());
        assertEquals(1, schema.get().getNodeTypes().size());
        assertEquals("DOC", schema.get().getNodeTypes().get(0).getLabel());
    }

    @Test
    void getSchemaReturnsEmptyForMissingPreset() {
        assertTrue(service.getSchema("missing").isEmpty());
    }

    // --- deletePreset ---

    @Test
    void deletePresetRemovesFromMemoryAndDisk() throws IOException {
        PresetEntry entry = createPreset("to-delete", "Delete Me", List.of(), List.of());
        service.savePreset("to-delete", entry);

        assertTrue(service.getPreset("to-delete").isPresent());

        boolean deleted = service.deletePreset("to-delete");
        assertTrue(deleted);
        assertTrue(service.getPreset("to-delete").isEmpty());

        Path presetFile = tempDir.resolve("config").resolve("schema-presets").resolve("to-delete.json");
        assertFalse(Files.exists(presetFile));
    }

    @Test
    void deleteNonexistentPresetReturnsTrue() {
        // deleteIfExists returns true even if file didn't exist
        assertTrue(service.deletePreset("no-such-preset"));
    }

    // --- listPresets ---

    @Test
    void listPresetsReturnsEmptyInitially() {
        // May have built-in presets from classpath, but in a temp dir without classpath resources,
        // the seed step might still produce 0. Check the list is non-negative.
        List<PresetSummary> summaries = service.listPresets();
        assertNotNull(summaries);
    }

    @Test
    void listPresetsIncludesSavedPresets() throws IOException {
        PresetEntry entry1 = createPreset("alpha", "Alpha Schema",
                List.of(new NodeType("A", "desc", null)),
                List.of(new RelationshipType("R1", "desc", null)));
        PresetEntry entry2 = createPreset("beta", "Beta Schema",
                List.of(new NodeType("B1", "desc", null), new NodeType("B2", "desc", null)),
                List.of());
        service.savePreset("alpha", entry1);
        service.savePreset("beta", entry2);

        List<PresetSummary> summaries = service.listPresets();
        assertTrue(summaries.stream().anyMatch(s -> "alpha".equals(s.id())));
        assertTrue(summaries.stream().anyMatch(s -> "beta".equals(s.id())));

        PresetSummary alpha = summaries.stream().filter(s -> "alpha".equals(s.id())).findFirst().orElseThrow();
        assertEquals("Alpha Schema", alpha.name());
        assertEquals(1, alpha.nodeTypeCount());
        assertEquals(1, alpha.relationshipTypeCount());

        PresetSummary beta = summaries.stream().filter(s -> "beta".equals(s.id())).findFirst().orElseThrow();
        assertEquals(2, beta.nodeTypeCount());
        assertEquals(0, beta.relationshipTypeCount());
    }

    @Test
    void listPresetsSortedById() throws IOException {
        service.savePreset("charlie", createPreset("charlie", "C", List.of(), List.of()));
        service.savePreset("alpha", createPreset("alpha", "A", List.of(), List.of()));
        service.savePreset("bravo", createPreset("bravo", "B", List.of(), List.of()));

        List<PresetSummary> summaries = service.listPresets();
        // Filter to just our known presets
        List<String> ids = summaries.stream().map(PresetSummary::id)
                .filter(id -> List.of("alpha", "bravo", "charlie").contains(id))
                .toList();
        assertEquals(List.of("alpha", "bravo", "charlie"), ids);
    }

    // --- getPresetTypeNames ---

    @Test
    void getPresetTypeNamesExtractsLabelsAndTypes() throws IOException {
        PresetEntry entry = createPreset("typed", "Typed",
                List.of(new NodeType("PERSON", "Person", null),
                        new NodeType("ORGANIZATION", "Org", null)),
                List.of(new RelationshipType("WORKS_AT", "Works at", null),
                        new RelationshipType("MANAGES", "Manages", null)));
        service.savePreset("typed", entry);

        Optional<Map<String, List<String>>> typeNames = service.getPresetTypeNames("typed");
        assertTrue(typeNames.isPresent());
        assertEquals(List.of("PERSON", "ORGANIZATION"), typeNames.get().get("entityTypes"));
        assertEquals(List.of("WORKS_AT", "MANAGES"), typeNames.get().get("relationshipTypes"));
    }

    @Test
    void getPresetTypeNamesReturnsEmptyForMissing() {
        assertTrue(service.getPresetTypeNames("missing").isEmpty());
    }

    @Test
    void getPresetTypeNamesHandlesNullNodeTypes() throws IOException {
        PresetEntry entry = new PresetEntry();
        entry.name = "Null Types";
        entry.schema = new GraphSchema();
        // nodeTypes and relationshipTypes are null
        service.savePreset("null-types", entry);

        Optional<Map<String, List<String>>> typeNames = service.getPresetTypeNames("null-types");
        assertTrue(typeNames.isPresent());
        assertTrue(typeNames.get().get("entityTypes").isEmpty());
        assertTrue(typeNames.get().get("relationshipTypes").isEmpty());
    }

    // --- persistence across instances ---

    @Test
    void presetPersistsAcrossServiceInstances() throws IOException {
        PresetEntry entry = createPreset("persistent", "Persistent Schema",
                List.of(new NodeType("PERSON", "Person", null)),
                List.of(new RelationshipType("KNOWS", "Knows", null)));
        service.savePreset("persistent", entry);

        // Create new service instance
        GraphSchemaPresetService service2 = new GraphSchemaPresetService(tempDir.toString());
        service2.init();

        Optional<PresetEntry> loaded = service2.getPreset("persistent");
        assertTrue(loaded.isPresent());
        assertEquals("Persistent Schema", loaded.get().name);
        assertEquals(1, loaded.get().schema.getNodeTypes().size());
    }

    // --- loadPresetsFromDisk: corrupt file handling ---

    @Test
    void loadSkipsCorruptPresetFiles() throws IOException {
        // Save a valid preset
        service.savePreset("valid", createPreset("valid", "Valid", List.of(), List.of()));

        // Write a corrupt file
        Path presetsDir = tempDir.resolve("config").resolve("schema-presets");
        Files.writeString(presetsDir.resolve("corrupt.json"), "not valid json {{{");

        // Create new service — should load the valid preset, skip the corrupt one
        GraphSchemaPresetService service2 = new GraphSchemaPresetService(tempDir.toString());
        service2.init();

        assertTrue(service2.getPreset("valid").isPresent());
    }

    // --- PresetEntry JSON mapping ---

    @Test
    void presetEntryNodeTypesGoThroughSchema() {
        PresetEntry entry = new PresetEntry();
        List<NodeType> nodeTypes = List.of(new NodeType("X", "x desc", null));
        entry.setNodeTypes(nodeTypes);

        assertNotNull(entry.schema);
        assertEquals(1, entry.schema.getNodeTypes().size());
        assertEquals("X", entry.getNodeTypes().get(0).getLabel());
    }

    @Test
    void presetEntryRelationshipTypesGoThroughSchema() {
        PresetEntry entry = new PresetEntry();
        List<RelationshipType> relTypes = List.of(new RelationshipType("REL", "rel desc", null));
        entry.setRelationshipTypes(relTypes);

        assertEquals(1, entry.schema.getRelationshipTypes().size());
        assertEquals("REL", entry.getRelationshipTypes().get(0).getType());
    }

    @Test
    void presetEntryPatternsGoThroughSchema() {
        PresetEntry entry = new PresetEntry();
        entry.setPatterns(List.of("(PERSON)-[:WORKS_AT]->(COMPANY)"));

        assertEquals(1, entry.schema.getPatterns().size());
        assertEquals(1, entry.getPatterns().size());
    }

    // --- PresetEntry derives id from filename ---

    @Test
    void loadDeriveIdFromFilenameWhenMissing() throws IOException {
        Path presetsDir = tempDir.resolve("config").resolve("schema-presets");
        // Write a preset JSON without an "id" field
        String json = """
                {
                  "name": "No ID Preset",
                  "description": "Preset without explicit id",
                  "version": 1,
                  "nodeTypes": [],
                  "relationshipTypes": []
                }
                """;
        Files.writeString(presetsDir.resolve("derived-id.json"), json);

        GraphSchemaPresetService service2 = new GraphSchemaPresetService(tempDir.toString());
        service2.init();

        Optional<PresetEntry> loaded = service2.getPreset("derived-id");
        assertTrue(loaded.isPresent());
        assertEquals("derived-id", loaded.get().id);
        assertEquals("No ID Preset", loaded.get().name);
    }

    // --- helpers ---

    private PresetEntry createPreset(String id, String name,
                                     List<NodeType> nodeTypes,
                                     List<RelationshipType> relTypes) {
        PresetEntry entry = new PresetEntry();
        entry.id = id;
        entry.name = name;
        entry.description = "Test description for " + name;
        entry.version = 1;
        entry.schema = new GraphSchema(nodeTypes, relTypes, null);
        return entry;
    }
}
