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

package ai.kompile.app.services.mcp;

import ai.kompile.core.mcp.EnhancedToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FileBasedToolDefinitionRepository}.
 * Uses a JUnit 5 {@link TempDir} so tests never touch the real filesystem.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileBasedToolDefinitionRepositoryTest {

    @TempDir
    Path tempDir;

    private FileBasedToolDefinitionRepository repo;

    /** Subdirectory used as the repository storage dir (keeps exports separate from storage). */
    private Path storageDir;

    @BeforeEach
    void setUp() throws Exception {
        storageDir = tempDir.resolve("storage");
        Files.createDirectories(storageDir);
        repo = new FileBasedToolDefinitionRepository(storageDir.toString());
        repo.init();
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private EnhancedToolDefinition buildTool(String name) {
        return EnhancedToolDefinition.builder()
                .name(name)
                .displayName(name)
                .description("Description for " + name)
                .category("rag")
                .source(EnhancedToolDefinition.ToolSource.BUILT_IN)
                .enabled(true)
                .build();
    }

    // ─── save ─────────────────────────────────────────────────────────────────

    @Test
    void save_assignsIdWhenAbsent() {
        EnhancedToolDefinition tool = buildTool("rag_query");
        tool.setId(null);

        EnhancedToolDefinition saved = repo.save(tool);
        assertNotNull(saved.getId());
    }

    @Test
    void save_keepsExistingId() {
        EnhancedToolDefinition tool = buildTool("rag_query");
        String id = UUID.randomUUID().toString();
        tool.setId(id);

        EnhancedToolDefinition saved = repo.save(tool);
        assertEquals(id, saved.getId());
    }

    @Test
    void save_setsTimestamps() {
        EnhancedToolDefinition tool = buildTool("rag_query");
        EnhancedToolDefinition saved = repo.save(tool);

        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
    }

    @Test
    void save_persistsJsonFileToDisk() throws Exception {
        repo.save(buildTool("rag_query"));

        long jsonFiles = Files.list(storageDir)
                .filter(p -> p.toString().endsWith(".json"))
                .count();
        assertEquals(1, jsonFiles);
    }

    @Test
    void save_throwsOnNullDefinition() {
        assertThrows(NullPointerException.class, () -> repo.save(null));
    }

    @Test
    void save_throwsOnNullName() {
        EnhancedToolDefinition tool = buildTool("x");
        tool.setName(null);
        assertThrows(NullPointerException.class, () -> repo.save(tool));
    }

    // ─── findById / findByName ────────────────────────────────────────────────

    @Test
    void findById_returnsEmptyForUnknownId() {
        assertTrue(repo.findById("unknown-id").isEmpty());
    }

    @Test
    void findByName_returnsToolAfterSave() {
        repo.save(buildTool("rag_query"));

        Optional<EnhancedToolDefinition> found = repo.findByName("rag_query");
        assertTrue(found.isPresent());
        assertEquals("rag_query", found.get().getName());
    }

    @Test
    void findByName_isCaseInsensitive() {
        repo.save(buildTool("RAG_QUERY"));

        Optional<EnhancedToolDefinition> found = repo.findByName("rag_query");
        assertTrue(found.isPresent());
    }

    @Test
    void findByName_returnsEmptyForNull() {
        assertTrue(repo.findByName(null).isEmpty());
    }

    @Test
    void findById_returnsToolAfterSave() {
        EnhancedToolDefinition saved = repo.save(buildTool("rag_query"));
        Optional<EnhancedToolDefinition> found = repo.findById(saved.getId());
        assertTrue(found.isPresent());
    }

    // ─── findAll / findAllEnabled ─────────────────────────────────────────────

    @Test
    void findAll_returnsAllSavedTools() {
        repo.save(buildTool("rag_query"));
        repo.save(buildTool("write_file"));
        repo.save(buildTool("read_file"));

        assertEquals(3, repo.findAll().size());
    }

    @Test
    void findAllEnabled_returnsOnlyEnabledTools() {
        repo.save(buildTool("enabled_tool"));

        EnhancedToolDefinition disabled = buildTool("disabled_tool");
        disabled.setEnabled(false);
        repo.save(disabled);

        List<EnhancedToolDefinition> enabled = repo.findAllEnabled();
        assertEquals(1, enabled.size());
        assertEquals("enabled_tool", enabled.get(0).getName());
    }

    // ─── findByCategory / findBySource / findByTag ────────────────────────────

    @Test
    void findByCategory_filtersCorrectly() {
        repo.save(buildTool("rag_query")); // category = rag

        EnhancedToolDefinition fsTool = buildTool("read_file");
        fsTool.setCategory("filesystem");
        repo.save(fsTool);

        List<EnhancedToolDefinition> ragTools = repo.findByCategory("rag");
        assertEquals(1, ragTools.size());
        assertEquals("rag_query", ragTools.get(0).getName());
    }

    @Test
    void findByCategory_returnsEmptyForNull() {
        assertTrue(repo.findByCategory(null).isEmpty());
    }

    @Test
    void findBySource_filtersCorrectly() {
        repo.save(buildTool("rag_query")); // BUILT_IN

        EnhancedToolDefinition custom = buildTool("my_custom");
        custom.setSource(EnhancedToolDefinition.ToolSource.CUSTOM);
        repo.save(custom);

        List<EnhancedToolDefinition> builtIn = repo.findBySource(EnhancedToolDefinition.ToolSource.BUILT_IN);
        assertEquals(1, builtIn.size());
        assertEquals("rag_query", builtIn.get(0).getName());
    }

    @Test
    void findByTag_findsMatchingTools() {
        EnhancedToolDefinition tool = buildTool("rag_query");
        tool.setTags(List.of("search", "retrieval", "nlp"));
        repo.save(tool);

        List<EnhancedToolDefinition> result = repo.findByTag("retrieval");
        assertEquals(1, result.size());
    }

    @Test
    void findByTag_returnsEmptyForNull() {
        assertTrue(repo.findByTag(null).isEmpty());
    }

    // ─── search ───────────────────────────────────────────────────────────────

    @Test
    void search_byName() {
        repo.save(buildTool("rag_query"));
        // Use a tool with a category that does not contain "rag" to avoid false match
        repo.save(EnhancedToolDefinition.builder()
                .name("write_file")
                .displayName("write_file")
                .description("Description for write_file")
                .category("filesystem")
                .source(EnhancedToolDefinition.ToolSource.BUILT_IN)
                .enabled(true)
                .build());

        List<EnhancedToolDefinition> result = repo.search("rag");
        assertEquals(1, result.size());
    }

    @Test
    void search_emptyQueryReturnsAll() {
        repo.save(buildTool("rag_query"));
        repo.save(buildTool("write_file"));

        List<EnhancedToolDefinition> result = repo.search("");
        assertEquals(2, result.size());
    }

    @Test
    void search_nullQueryReturnsAll() {
        repo.save(buildTool("rag_query"));
        List<EnhancedToolDefinition> result = repo.search(null);
        assertEquals(1, result.size());
    }

    // ─── deleteById / deleteByName ────────────────────────────────────────────

    @Test
    void deleteById_removesToolAndReturnsTrue() {
        EnhancedToolDefinition saved = repo.save(buildTool("rag_query"));
        assertTrue(repo.deleteById(saved.getId()));
        assertTrue(repo.findById(saved.getId()).isEmpty());
    }

    @Test
    void deleteById_returnsFalseForUnknownId() {
        assertFalse(repo.deleteById("nonexistent-id"));
    }

    @Test
    void deleteByName_removesToolAndReturnsTrue() {
        repo.save(buildTool("rag_query"));
        assertTrue(repo.deleteByName("rag_query"));
        assertTrue(repo.findByName("rag_query").isEmpty());
    }

    @Test
    void deleteByName_returnsFalseForNull() {
        assertFalse(repo.deleteByName(null));
    }

    @Test
    void deleteByName_removesDiskFile() throws Exception {
        repo.save(buildTool("rag_query"));
        repo.deleteByName("rag_query");

        long jsonFiles = Files.list(tempDir)
                .filter(p -> p.toString().endsWith(".json"))
                .count();
        assertEquals(0, jsonFiles);
    }

    // ─── existsByName / count ─────────────────────────────────────────────────

    @Test
    void existsByName_trueAfterSave() {
        repo.save(buildTool("rag_query"));
        assertTrue(repo.existsByName("rag_query"));
    }

    @Test
    void existsByName_falseForNull() {
        assertFalse(repo.existsByName(null));
    }

    @Test
    void count_reflectsSavedTools() {
        assertEquals(0, repo.count());
        repo.save(buildTool("t1"));
        repo.save(buildTool("t2"));
        assertEquals(2, repo.count());
    }

    // ─── deleteAll ────────────────────────────────────────────────────────────

    @Test
    void deleteAll_removesAllToolsAndFiles() throws Exception {
        repo.save(buildTool("t1"));
        repo.save(buildTool("t2"));

        repo.deleteAll();

        assertEquals(0, repo.count());
        long jsonFiles = Files.list(tempDir)
                .filter(p -> p.toString().endsWith(".json"))
                .count();
        assertEquals(0, jsonFiles);
    }

    // ─── refresh ─────────────────────────────────────────────────────────────

    @Test
    void refresh_reloadsFromDisk() throws Exception {
        // Save a tool, then corrupt the cache via refresh
        repo.save(buildTool("rag_query"));
        repo.refresh();

        // Tool should still be available after refresh (loaded from disk)
        assertTrue(repo.findByName("rag_query").isPresent());
    }

    // ─── exportAll / importAll ────────────────────────────────────────────────

    @Test
    void exportAll_thenImportAll_roundTrips() throws Exception {
        repo.save(buildTool("rag_query"));
        repo.save(buildTool("write_file"));

        Path exportFile = tempDir.resolve("export.json");
        repo.exportAll(exportFile);

        // Clear and import
        repo.deleteAll();
        assertEquals(0, repo.count());

        int imported = repo.importAll(exportFile);
        assertEquals(2, imported);
        assertTrue(repo.findByName("rag_query").isPresent());
        assertTrue(repo.findByName("write_file").isPresent());
    }

    // ─── getStorageDirectory ─────────────────────────────────────────────────

    @Test
    void getStorageDirectory_returnsConfiguredPath() {
        assertEquals(storageDir, repo.getStorageDirectory());
    }
}
