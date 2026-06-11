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
import ai.kompile.core.prompt.PromptTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PromptTemplateServiceTest {

    @Mock
    private PromptTemplateRepository repository;

    private PromptTemplateService service;

    private PromptTemplate makeTemplate(String name, String category, boolean builtIn, boolean enabled) {
        return PromptTemplate.builder()
                .id(UUID.randomUUID().toString())
                .name(name)
                .displayName(name + " Display")
                .description("A template for " + name)
                .category(category)
                .content("Hello {{name}}")
                .variables(List.of(
                        PromptTemplate.TemplateVariable.builder()
                                .name("name").displayName("Name").required(true).type("string").build()
                ))
                .tags(List.of("tag1", "tag2"))
                .enabled(enabled)
                .builtIn(builtIn)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @BeforeEach
    void setUp() {
        service = new PromptTemplateService(repository);
    }

    // ── getAllTemplates ────────────────────────────────────────────────────────

    @Test
    void testGetAllTemplates_empty() {
        when(repository.findAll()).thenReturn(List.of());
        List<PromptTemplate> result = service.getAllTemplates();
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testGetAllTemplates_returnsAll() {
        PromptTemplate t1 = makeTemplate("t1", "rag", false, true);
        PromptTemplate t2 = makeTemplate("t2", "code", false, true);
        when(repository.findAll()).thenReturn(List.of(t1, t2));
        List<PromptTemplate> result = service.getAllTemplates();
        assertEquals(2, result.size());
    }

    // ── getEnabledTemplates ────────────────────────────────────────────────────

    @Test
    void testGetEnabledTemplates_filtersEnabled() {
        PromptTemplate t = makeTemplate("enabled_only", "rag", false, true);
        when(repository.findEnabled()).thenReturn(List.of(t));
        List<PromptTemplate> result = service.getEnabledTemplates();
        assertEquals(1, result.size());
    }

    // ── getTemplateByName ──────────────────────────────────────────────────────

    @Test
    void testGetTemplateByName_found() {
        PromptTemplate t = makeTemplate("my_template", "rag", false, true);
        when(repository.findByName("my_template")).thenReturn(Optional.of(t));
        Optional<PromptTemplate> result = service.getTemplateByName("my_template");
        assertTrue(result.isPresent());
        assertEquals("my_template", result.get().getName());
    }

    @Test
    void testGetTemplateByName_notFound() {
        when(repository.findByName("missing")).thenReturn(Optional.empty());
        Optional<PromptTemplate> result = service.getTemplateByName("missing");
        assertFalse(result.isPresent());
    }

    // ── getTemplateById ────────────────────────────────────────────────────────

    @Test
    void testGetTemplateById_found() {
        String id = "abc-123";
        PromptTemplate t = makeTemplate("ById", "rag", false, true);
        t.setId(id);
        when(repository.findById(id)).thenReturn(Optional.of(t));
        Optional<PromptTemplate> result = service.getTemplateById(id);
        assertTrue(result.isPresent());
    }

    // ── getTemplatesByCategory ────────────────────────────────────────────────

    @Test
    void testGetTemplatesByCategory() {
        PromptTemplate t = makeTemplate("rag_query", "rag", false, true);
        when(repository.findByCategory("rag")).thenReturn(List.of(t));
        List<PromptTemplate> result = service.getTemplatesByCategory("rag");
        assertEquals(1, result.size());
        assertEquals("rag_query", result.get(0).getName());
    }

    // ── searchTemplates ────────────────────────────────────────────────────────

    @Test
    void testSearchTemplates() {
        PromptTemplate t = makeTemplate("code_review", "code", false, true);
        when(repository.search("code")).thenReturn(List.of(t));
        List<PromptTemplate> result = service.searchTemplates("code");
        assertEquals(1, result.size());
    }

    // ── getTemplatesByTag ──────────────────────────────────────────────────────

    @Test
    void testGetTemplatesByTag() {
        PromptTemplate t = makeTemplate("tagged", "rag", false, true);
        when(repository.findByTag("rag")).thenReturn(List.of(t));
        List<PromptTemplate> result = service.getTemplatesByTag("rag");
        assertEquals(1, result.size());
    }

    // ── createTemplate ────────────────────────────────────────────────────────

    @Test
    void testCreateTemplate_success() {
        PromptTemplate template = PromptTemplate.builder()
                .name("my_template")
                .displayName("My Template")
                .content("Hello {{user}}")
                .variables(List.of(PromptTemplate.TemplateVariable.builder()
                        .name("user").required(true).type("string").build()))
                .build();

        when(repository.existsByName("my_template")).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PromptTemplate result = service.createTemplate(template);
        assertNotNull(result);
        assertFalse(result.isBuiltIn());
        assertNotNull(result.getId());
        verify(repository).save(any());
    }

    @Test
    void testCreateTemplate_duplicateName_throws() {
        PromptTemplate template = PromptTemplate.builder()
                .name("existing")
                .content("Some content")
                .build();
        when(repository.existsByName("existing")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.createTemplate(template));
        verify(repository, never()).save(any());
    }

    @Test
    void testCreateTemplate_nullName_throws() {
        PromptTemplate template = PromptTemplate.builder()
                .content("some content")
                .build();
        when(repository.existsByName(null)).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> service.createTemplate(template));
    }

    @Test
    void testCreateTemplate_invalidName_throws() {
        PromptTemplate template = PromptTemplate.builder()
                .name("123-invalid-start")
                .content("some content")
                .build();
        when(repository.existsByName("123-invalid-start")).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> service.createTemplate(template));
    }

    @Test
    void testCreateTemplate_setsDefaultCategory() {
        PromptTemplate template = PromptTemplate.builder()
                .name("myTemplate")
                .content("Hello {{name}}")
                .build();
        when(repository.existsByName("myTemplate")).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PromptTemplate result = service.createTemplate(template);
        assertEquals("custom", result.getCategory());
    }

    // ── updateTemplate ────────────────────────────────────────────────────────

    @Test
    void testUpdateTemplate_customTemplate() {
        PromptTemplate existing = makeTemplate("custom_tpl", "rag", false, true);
        when(repository.findByName("custom_tpl")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PromptTemplate updates = PromptTemplate.builder()
                .displayName("Updated Display")
                .description("New desc")
                .content("New content {{x}}")
                .enabled(false)
                .build();

        PromptTemplate result = service.updateTemplate("custom_tpl", updates);
        assertNotNull(result);
        assertEquals("Updated Display", result.getDisplayName());
    }

    @Test
    void testUpdateTemplate_builtIn_onlyAllowsEnabledChange() {
        PromptTemplate existing = makeTemplate("builtin_tpl", "rag", true, true);
        when(repository.findByName("builtin_tpl")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PromptTemplate updates = PromptTemplate.builder()
                .enabled(false)
                .build();

        PromptTemplate result = service.updateTemplate("builtin_tpl", updates);
        assertFalse(result.isEnabled());
    }

    @Test
    void testUpdateTemplate_notFound_throws() {
        when(repository.findByName("nonexistent")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> service.updateTemplate("nonexistent", PromptTemplate.builder().build()));
    }

    // ── deleteTemplate ────────────────────────────────────────────────────────

    @Test
    void testDeleteTemplate_success() {
        PromptTemplate t = makeTemplate("delete_me", "rag", false, true);
        when(repository.findByName("delete_me")).thenReturn(Optional.of(t));
        when(repository.deleteByName("delete_me")).thenReturn(true);

        assertTrue(service.deleteTemplate("delete_me"));
        verify(repository).deleteByName("delete_me");
    }

    @Test
    void testDeleteTemplate_notFound_returnsFalse() {
        when(repository.findByName("missing")).thenReturn(Optional.empty());
        assertFalse(service.deleteTemplate("missing"));
        verify(repository, never()).deleteByName(any());
    }

    @Test
    void testDeleteTemplate_builtIn_throws() {
        PromptTemplate t = makeTemplate("builtin", "rag", true, true);
        when(repository.findByName("builtin")).thenReturn(Optional.of(t));

        assertThrows(IllegalArgumentException.class, () -> service.deleteTemplate("builtin"));
        verify(repository, never()).deleteByName(any());
    }

    // ── setTemplateEnabled ────────────────────────────────────────────────────

    @Test
    void testSetTemplateEnabled_enable() {
        PromptTemplate t = makeTemplate("my_tpl", "rag", false, false);
        when(repository.findByName("my_tpl")).thenReturn(Optional.of(t));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PromptTemplate result = service.setTemplateEnabled("my_tpl", true);
        assertTrue(result.isEnabled());
    }

    @Test
    void testSetTemplateEnabled_disable() {
        PromptTemplate t = makeTemplate("active_tpl", "rag", false, true);
        when(repository.findByName("active_tpl")).thenReturn(Optional.of(t));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PromptTemplate result = service.setTemplateEnabled("active_tpl", false);
        assertFalse(result.isEnabled());
    }

    @Test
    void testSetTemplateEnabled_notFound_throws() {
        when(repository.findByName("gone")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.setTemplateEnabled("gone", true));
    }

    // ── renderTemplate ────────────────────────────────────────────────────────

    @Test
    void testRenderTemplate_success() {
        PromptTemplate t = PromptTemplate.builder()
                .name("hello_tpl")
                .content("Hello {{name}}!")
                .variables(List.of(
                        PromptTemplate.TemplateVariable.builder()
                                .name("name").required(true).type("string").build()
                ))
                .build();
        when(repository.findByName("hello_tpl")).thenReturn(Optional.of(t));

        String result = service.renderTemplate("hello_tpl", Map.of("name", "World"));
        assertTrue(result.contains("World"), "Rendered template should contain 'World'");
    }

    @Test
    void testRenderTemplate_notFound_throws() {
        when(repository.findByName("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> service.renderTemplate("missing", Map.of()));
    }

    // ── renderTemplateWithSystemPrompt ────────────────────────────────────────

    @Test
    void testRenderTemplateWithSystemPrompt_noSystemPrompt() {
        PromptTemplate t = PromptTemplate.builder()
                .name("simple_tpl")
                .content("Hello {{user}}")
                .variables(List.of(
                        PromptTemplate.TemplateVariable.builder()
                                .name("user").required(true).type("string").build()
                ))
                .build();
        when(repository.findByName("simple_tpl")).thenReturn(Optional.of(t));

        PromptTemplateService.RenderedTemplate rendered =
                service.renderTemplateWithSystemPrompt("simple_tpl", Map.of("user", "Alice"));

        assertNotNull(rendered);
        assertNull(rendered.systemPrompt());
        assertEquals("simple_tpl", rendered.templateName());
    }

    // ── getSummary ────────────────────────────────────────────────────────────

    @Test
    void testGetSummary() {
        PromptTemplate builtIn = makeTemplate("bi", "rag", true, true);
        PromptTemplate custom = makeTemplate("c1", "code", false, true);
        PromptTemplate disabled = makeTemplate("d1", "system", false, false);

        when(repository.findAll()).thenReturn(List.of(builtIn, custom, disabled));
        when(repository.findEnabled()).thenReturn(List.of(builtIn, custom));

        PromptTemplateService.TemplateSummary summary = service.getSummary();
        assertEquals(3, summary.totalTemplates());
        assertEquals(2, summary.enabledTemplates());
        assertEquals(1, summary.builtInTemplates());
        assertEquals(2, summary.customTemplates());
    }

    // ── duplicateTemplate ─────────────────────────────────────────────────────

    @Test
    void testDuplicateTemplate_success() {
        PromptTemplate source = makeTemplate("source_tpl", "rag", false, true);
        when(repository.findByName("source_tpl")).thenReturn(Optional.of(source));
        when(repository.existsByName("copy_tpl")).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PromptTemplate copy = service.duplicateTemplate("source_tpl", "copy_tpl");
        assertNotNull(copy);
        assertEquals("copy_tpl", copy.getName());
        assertFalse(copy.isBuiltIn());
    }

    @Test
    void testDuplicateTemplate_sourceNotFound_throws() {
        when(repository.findByName("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> service.duplicateTemplate("missing", "new_name"));
    }

    @Test
    void testDuplicateTemplate_targetNameExists_throws() {
        PromptTemplate source = makeTemplate("source", "rag", false, true);
        when(repository.findByName("source")).thenReturn(Optional.of(source));
        when(repository.existsByName("taken")).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> service.duplicateTemplate("source", "taken"));
    }

    // ── refresh ───────────────────────────────────────────────────────────────

    @Test
    void testRefresh_delegatesToRepository() {
        service.refresh();
        verify(repository).refresh();
    }

    // ── getCategories ─────────────────────────────────────────────────────────

    @Test
    void testGetCategories_returnsMap() {
        Map<String, PromptTemplate.CategoryInfo> categories = service.getCategories();
        assertNotNull(categories);
    }

    // ── getTemplatesByCategories ──────────────────────────────────────────────

    @Test
    void testGetTemplatesByCategories() {
        PromptTemplate t1 = makeTemplate("rag_q", "rag", false, true);
        PromptTemplate t2 = makeTemplate("code_r", "code", false, true);
        when(repository.findAll()).thenReturn(List.of(t1, t2));

        Map<String, List<PromptTemplate>> grouped = service.getTemplatesByCategories();
        assertTrue(grouped.containsKey("rag") || grouped.containsKey("code"));
    }
}
