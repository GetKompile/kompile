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

import ai.kompile.app.services.skill.SkillService;
import ai.kompile.app.services.skill.SkillService.SkillDefinition;
import ai.kompile.app.services.skill.SkillService.SkillsSummary;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link SkillController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillControllerTest {

    @Mock
    private SkillService skillService;

    private SkillController controller;

    @BeforeEach
    void setUp() {
        controller = new SkillController(skillService);
    }

    @Test
    void listSkills_noFilter_returnsAll() {
        SkillDefinition skill = mock(SkillDefinition.class);
        when(skillService.listAll()).thenReturn(List.of(skill));

        ResponseEntity<List<SkillDefinition>> response = controller.listSkills(null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        verify(skillService).listAll();
    }

    @Test
    void listSkills_byCategory_returnsFiltered() {
        when(skillService.listByCategory("code")).thenReturn(List.of());

        ResponseEntity<List<SkillDefinition>> response = controller.listSkills("code", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(skillService).listByCategory("code");
    }

    @Test
    void listSkills_byQuery_returnsSearchResult() {
        when(skillService.search("test")).thenReturn(List.of());

        ResponseEntity<List<SkillDefinition>> response = controller.listSkills(null, "test");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(skillService).search("test");
    }

    @Test
    void getSkill_found_returnsOk() {
        SkillDefinition skill = mock(SkillDefinition.class);
        when(skillService.getByName("analyze")).thenReturn(skill);

        ResponseEntity<?> response = controller.getSkill("analyze");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(skill, response.getBody());
    }

    @Test
    void getSkill_notFound_returnsNotFound() {
        when(skillService.getByName("missing")).thenReturn(null);

        ResponseEntity<?> response = controller.getSkill("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void createSkill_success_returnsOk() {
        SkillDefinition skill = mock(SkillDefinition.class);
        SkillDefinition created = mock(SkillDefinition.class);
        when(skillService.create(skill)).thenReturn(created);

        ResponseEntity<?> response = controller.createSkill(skill);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(created, response.getBody());
    }

    @Test
    void createSkill_illegalArgument_returnsBadRequest() {
        SkillDefinition skill = mock(SkillDefinition.class);
        when(skillService.create(skill)).thenThrow(new IllegalArgumentException("name required"));

        ResponseEntity<?> response = controller.createSkill(skill);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void updateSkill_success_returnsOk() {
        SkillDefinition updates = mock(SkillDefinition.class);
        SkillDefinition updated = mock(SkillDefinition.class);
        when(skillService.update("analyze", updates)).thenReturn(updated);

        ResponseEntity<?> response = controller.updateSkill("analyze", updates);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(updated, response.getBody());
    }

    @Test
    void deleteSkill_success_returnsOk() {
        ResponseEntity<?> response = controller.deleteSkill("analyze");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(skillService).delete("analyze");
    }

    @Test
    void deleteSkill_illegalArgument_returnsBadRequest() {
        doThrow(new IllegalArgumentException("built-in")).when(skillService).delete("builtin");

        ResponseEntity<?> response = controller.deleteSkill("builtin");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void getSummary_returnsOk() {
        SkillsSummary summary = mock(SkillsSummary.class);
        when(skillService.getSummary()).thenReturn(summary);

        ResponseEntity<SkillsSummary> response = controller.getSummary();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(summary, response.getBody());
    }

    @Test
    void getByCategory_returnsOk() {
        // getByCategory in SkillController uses listAll() then groups by category
        when(skillService.listAll()).thenReturn(List.of());

        ResponseEntity<Map<String, List<SkillDefinition>>> response = controller.getByCategory();

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void refresh_returnsOk() {
        ResponseEntity<?> response = controller.refresh();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(skillService).refresh();
    }
}
