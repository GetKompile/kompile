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

package ai.kompile.app.services.prompt;

import ai.kompile.core.prompt.PromptTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileBasedPromptTemplateRepositoryTest {

    @TempDir
    Path tempDir;

    private FileBasedPromptTemplateRepository repository;

    @BeforeEach
    void setUp() {
        repository = new FileBasedPromptTemplateRepository(tempDir.toString());
        // Initialize without default templates for clean test
        repository.initialize();
    }

    private PromptTemplate buildTemplate(String name) {
        return PromptTemplate.builder()
                .name(name)
                .displayName(name + " Display")
                .description("Test template")
                .category("test")
                .content("Hello {{value}}")
                .variables(List.of(
                        PromptTemplate.TemplateVariable.builder()
                                .name("value").required(true).type("string").build()
                ))
                .tags(List.of("test"))
                .enabled(true)
                .builtIn(false)
                .build();
    }

    @Test
    void testInitialize_createsDefaultTemplates() {
        // Default templates were created during setUp
        long count = repository.count();
        assertTrue(count > 0, "Default templates should be created on init");
    }

    @Test
    void testSave_andFindByName() {
        PromptTemplate template = buildTemplate("my_test_tpl");
        PromptTemplate saved = repository.save(template);

        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());

        Optional<PromptTemplate> found = repository.findByName("my_test_tpl");
        assertTrue(found.isPresent());
        assertEquals("my_test_tpl", found.get().getName());
    }

    @Test
    void testFindByName_notFound() {
        Optional<PromptTemplate> found = repository.findByName("nonexistent");
        assertFalse(found.isPresent());
    }

    @Test
    void testFindById_found() {
        PromptTemplate template = buildTemplate("id_test_tpl");
        PromptTemplate saved = repository.save(template);

        Optional<PromptTemplate> found = repository.findById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals(saved.getId(), found.get().getId());
    }

    @Test
    void testFindById_notFound() {
        assertFalse(repository.findById("nonexistent-id").isPresent());
    }

    @Test
    void testFindAll_includesDefaultsAndSaved() {
        PromptTemplate template = buildTemplate("extra_tpl");
        repository.save(template);

        List<PromptTemplate> all = repository.findAll();
        assertNotNull(all);
        assertFalse(all.isEmpty());
        assertTrue(all.stream().anyMatch(t -> "extra_tpl".equals(t.getName())));
    }

    @Test
    void testFindByCategory() {
        PromptTemplate t = buildTemplate("cat_tpl");
        t.setCategory("special_category");
        repository.save(t);

        List<PromptTemplate> found = repository.findByCategory("special_category");
        assertFalse(found.isEmpty());
        assertTrue(found.stream().allMatch(tpl ->
                "special_category".equalsIgnoreCase(tpl.getCategory())));
    }

    @Test
    void testSearch_byName() {
        PromptTemplate t = buildTemplate("searchable_name");
        repository.save(t);

        List<PromptTemplate> results = repository.search("searchable");
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(tpl -> tpl.getName().contains("searchable")));
    }

    @Test
    void testSearch_emptyQuery_returnsAll() {
        List<PromptTemplate> results = repository.search("");
        assertFalse(results.isEmpty());
    }

    @Test
    void testSearch_nullQuery_returnsAll() {
        List<PromptTemplate> results = repository.search(null);
        assertFalse(results.isEmpty());
    }

    @Test
    void testFindByTag() {
        PromptTemplate t = buildTemplate("tagged_tpl");
        t.setTags(List.of("unique-tag-xyz"));
        repository.save(t);

        List<PromptTemplate> found = repository.findByTag("unique-tag-xyz");
        assertFalse(found.isEmpty());
        assertTrue(found.stream().anyMatch(tpl -> tpl.getName().equals("tagged_tpl")));
    }

    @Test
    void testFindEnabled() {
        PromptTemplate enabled = buildTemplate("enabled_tpl");
        enabled.setEnabled(true);
        repository.save(enabled);

        PromptTemplate disabled = buildTemplate("disabled_tpl");
        disabled.setEnabled(false);
        repository.save(disabled);

        List<PromptTemplate> enabledTemplates = repository.findEnabled();
        assertFalse(enabledTemplates.isEmpty());
        assertTrue(enabledTemplates.stream().allMatch(PromptTemplate::isEnabled));
    }

    @Test
    void testDeleteByName_customTemplate() {
        PromptTemplate t = buildTemplate("delete_me_tpl");
        repository.save(t);
        assertTrue(repository.existsByName("delete_me_tpl"));

        boolean deleted = repository.deleteByName("delete_me_tpl");
        assertTrue(deleted);
        assertFalse(repository.existsByName("delete_me_tpl"));
    }

    @Test
    void testDeleteByName_builtIn_throws() {
        // Find a built-in template
        List<PromptTemplate> all = repository.findAll();
        PromptTemplate builtIn = all.stream().filter(PromptTemplate::isBuiltIn).findFirst().orElse(null);
        if (builtIn == null) {
            // No built-in templates, skip
            return;
        }
        assertThrows(IllegalArgumentException.class, () -> repository.deleteByName(builtIn.getName()));
    }

    @Test
    void testDeleteByName_notFound_returnsFalse() {
        assertFalse(repository.deleteByName("nonexistent_tpl"));
    }

    @Test
    void testDeleteById() {
        PromptTemplate t = buildTemplate("delete_by_id_tpl");
        PromptTemplate saved = repository.save(t);

        boolean deleted = repository.deleteById(saved.getId());
        assertTrue(deleted);
        assertFalse(repository.findById(saved.getId()).isPresent());
    }

    @Test
    void testExistsByName_true() {
        repository.save(buildTemplate("exists_tpl"));
        assertTrue(repository.existsByName("exists_tpl"));
    }

    @Test
    void testExistsByName_false() {
        assertFalse(repository.existsByName("not_there_tpl"));
    }

    @Test
    void testCount() {
        long before = repository.count();
        repository.save(buildTemplate("count_tpl"));
        assertEquals(before + 1, repository.count());
    }

    @Test
    void testSave_noName_throws() {
        PromptTemplate t = PromptTemplate.builder()
                .content("content")
                .build();
        assertThrows(IllegalArgumentException.class, () -> repository.save(t));
    }

    @Test
    void testRefresh_reloadsFromDisk() {
        PromptTemplate t = buildTemplate("refresh_tpl");
        repository.save(t);

        // Clear and reload
        repository.refresh();
        assertTrue(repository.existsByName("refresh_tpl"));
    }

    @Test
    void testSave_generatesIdIfMissing() {
        PromptTemplate t = buildTemplate("auto_id_tpl");
        t.setId(null);
        PromptTemplate saved = repository.save(t);
        assertNotNull(saved.getId());
        assertFalse(saved.getId().isEmpty());
    }

    @Test
    void testSave_preservesExistingId() {
        PromptTemplate t = buildTemplate("preserve_id_tpl");
        t.setId("fixed-id-123");
        PromptTemplate saved = repository.save(t);
        assertEquals("fixed-id-123", saved.getId());
    }

    @Test
    void testSave_sanitizesFilename() {
        // Names with special characters should be sanitized for file storage
        PromptTemplate t = buildTemplate("template-with-dashes");
        PromptTemplate saved = repository.save(t);
        assertNotNull(saved);
        // Should be findable
        assertTrue(repository.existsByName("template-with-dashes"));
    }
}
