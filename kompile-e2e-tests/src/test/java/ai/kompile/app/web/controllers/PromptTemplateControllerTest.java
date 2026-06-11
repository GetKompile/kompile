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

package ai.kompile.app.web.controllers;

import ai.kompile.app.services.prompt.PromptTemplateService;
import ai.kompile.core.prompt.PromptTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PromptTemplateControllerTest {

    @Mock
    private PromptTemplateService templateService;

    private PromptTemplateController controller;

    @BeforeEach
    void setUp() {
        controller = new PromptTemplateController(templateService);
    }

    private PromptTemplate makeTemplate(String name) {
        return PromptTemplate.builder()
                .name(name)
                .content("Hello {{name}}")
                .category("test")
                .build();
    }

    // ── getAllTemplates ────────────────────────────────────────────────────

    @Test
    void getAllTemplates_noFilter_returnsAll() {
        List<PromptTemplate> all = List.of(makeTemplate("t1"), makeTemplate("t2"));
        when(templateService.getAllTemplates()).thenReturn(all);

        ResponseEntity<List<PromptTemplate>> resp = controller.getAllTemplates(null, null, null, false);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(2, resp.getBody().size());
    }

    @Test
    void getAllTemplates_withQuery_searchesTemplates() {
        List<PromptTemplate> found = List.of(makeTemplate("t1"));
        when(templateService.searchTemplates("rag")).thenReturn(found);

        ResponseEntity<List<PromptTemplate>> resp = controller.getAllTemplates(null, "rag", null, false);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(1, resp.getBody().size());
        verify(templateService).searchTemplates("rag");
    }

    @Test
    void getAllTemplates_withCategory_returnsCategory() {
        List<PromptTemplate> catResults = List.of(makeTemplate("t1"));
        when(templateService.getTemplatesByCategory("rag")).thenReturn(catResults);

        ResponseEntity<List<PromptTemplate>> resp = controller.getAllTemplates("rag", null, null, false);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(templateService).getTemplatesByCategory("rag");
    }

    @Test
    void getAllTemplates_enabledOnly_returnsEnabled() {
        List<PromptTemplate> enabled = List.of(makeTemplate("t1"));
        when(templateService.getEnabledTemplates()).thenReturn(enabled);

        ResponseEntity<List<PromptTemplate>> resp = controller.getAllTemplates(null, null, null, true);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(templateService).getEnabledTemplates();
    }

    // ── getTemplateByName ─────────────────────────────────────────────────

    @Test
    void getTemplateByName_found_returns200() {
        PromptTemplate t = makeTemplate("my-template");
        when(templateService.getTemplateByName("my-template")).thenReturn(Optional.of(t));

        ResponseEntity<PromptTemplate> resp = controller.getTemplateByName("my-template");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(t, resp.getBody());
    }

    @Test
    void getTemplateByName_notFound_returns404() {
        when(templateService.getTemplateByName("ghost")).thenReturn(Optional.empty());

        ResponseEntity<PromptTemplate> resp = controller.getTemplateByName("ghost");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // ── createTemplate ────────────────────────────────────────────────────

    @Test
    void createTemplate_success_returns201() {
        PromptTemplate input = makeTemplate("new-template");
        PromptTemplate created = makeTemplate("new-template");
        when(templateService.createTemplate(any())).thenReturn(created);

        ResponseEntity<?> resp = controller.createTemplate(input);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertSame(created, resp.getBody());
    }

    @Test
    void createTemplate_illegalArgument_returns400() {
        when(templateService.createTemplate(any())).thenThrow(new IllegalArgumentException("duplicate name"));

        ResponseEntity<?> resp = controller.createTemplate(makeTemplate("dup"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    // ── updateTemplate ────────────────────────────────────────────────────

    @Test
    void updateTemplate_success_returns200() {
        PromptTemplate updated = makeTemplate("my-template");
        when(templateService.updateTemplate(eq("my-template"), any())).thenReturn(updated);

        ResponseEntity<?> resp = controller.updateTemplate("my-template", makeTemplate("my-template"));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(updated, resp.getBody());
    }

    @Test
    void updateTemplate_notFound_returns400() {
        when(templateService.updateTemplate(eq("missing"), any()))
                .thenThrow(new IllegalArgumentException("not found"));

        ResponseEntity<?> resp = controller.updateTemplate("missing", makeTemplate("missing"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    // ── deleteTemplate ────────────────────────────────────────────────────

    @Test
    void deleteTemplate_found_returns204() {
        when(templateService.deleteTemplate("my-template")).thenReturn(true);

        ResponseEntity<?> resp = controller.deleteTemplate("my-template");

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
    }

    @Test
    void deleteTemplate_notFound_returns404() {
        when(templateService.deleteTemplate("missing")).thenReturn(false);

        ResponseEntity<?> resp = controller.deleteTemplate("missing");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // ── setTemplateEnabled ────────────────────────────────────────────────

    @Test
    void setTemplateEnabled_missingField_returns400() {
        ResponseEntity<?> resp = controller.setTemplateEnabled("t1", Map.of());

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void setTemplateEnabled_found_returns200() {
        PromptTemplate updated = makeTemplate("t1");
        when(templateService.setTemplateEnabled("t1", true)).thenReturn(updated);

        ResponseEntity<?> resp = controller.setTemplateEnabled("t1", Map.of("enabled", true));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(updated, resp.getBody());
    }

    @Test
    void setTemplateEnabled_notFound_returns404() {
        when(templateService.setTemplateEnabled("ghost", true))
                .thenThrow(new IllegalArgumentException("not found"));

        ResponseEntity<?> resp = controller.setTemplateEnabled("ghost", Map.of("enabled", true));

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // ── renderTemplate ────────────────────────────────────────────────────

    @Test
    void renderTemplate_success_returns200() {
        when(templateService.renderTemplate(eq("t1"), any())).thenReturn("Hello World");

        ResponseEntity<?> resp = controller.renderTemplate("t1", Map.of("name", "World"));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals("Hello World", body.get("rendered"));
    }

    @Test
    void renderTemplate_illegalArgument_returns400() {
        when(templateService.renderTemplate(eq("t1"), any()))
                .thenThrow(new IllegalArgumentException("missing variable"));

        ResponseEntity<?> resp = controller.renderTemplate("t1", Map.of());

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    // ── duplicateTemplate ─────────────────────────────────────────────────

    @Test
    void duplicateTemplate_missingNewName_returns400() {
        ResponseEntity<?> resp = controller.duplicateTemplate("t1", Map.of());

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void duplicateTemplate_success_returns201() {
        PromptTemplate copy = makeTemplate("t1-copy");
        when(templateService.duplicateTemplate("t1", "t1-copy")).thenReturn(copy);

        ResponseEntity<?> resp = controller.duplicateTemplate("t1", Map.of("newName", "t1-copy"));

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertSame(copy, resp.getBody());
    }

    // ── previewTemplate ───────────────────────────────────────────────────

    @Test
    void previewTemplate_returns200WithRendered() {
        PromptTemplateController.PreviewRequest previewReq = new PromptTemplateController.PreviewRequest(
                "Hello {{name}}", null, Map.of("name", "World"));

        ResponseEntity<?> resp = controller.previewTemplate(previewReq);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertNotNull(body.get("rendered"));
        assertNotNull(body.get("extractedVariables"));
    }

    // ── getCategories ─────────────────────────────────────────────────────

    @Test
    void getCategories_returnsCategoryMap() {
        Map<String, PromptTemplate.CategoryInfo> cats = Map.of();
        when(templateService.getCategories()).thenReturn(cats);

        ResponseEntity<Map<String, PromptTemplate.CategoryInfo>> resp = controller.getCategories();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(cats, resp.getBody());
    }

    // ── getSummary ────────────────────────────────────────────────────────

    @Test
    void getSummary_returnsSummary() {
        PromptTemplateService.TemplateSummary summary = mock(PromptTemplateService.TemplateSummary.class);
        when(templateService.getSummary()).thenReturn(summary);

        ResponseEntity<PromptTemplateService.TemplateSummary> resp = controller.getSummary();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(summary, resp.getBody());
    }

    // ── refreshTemplates ──────────────────────────────────────────────────

    @Test
    void refreshTemplates_success_returns200() {
        doNothing().when(templateService).refresh();
        when(templateService.getAllTemplates()).thenReturn(List.of(makeTemplate("t1")));

        ResponseEntity<?> resp = controller.refreshTemplates();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals(1, body.get("templateCount"));
    }
}
