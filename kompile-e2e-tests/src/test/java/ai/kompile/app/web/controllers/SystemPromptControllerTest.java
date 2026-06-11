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

import ai.kompile.app.prompts.domain.SystemPromptEntity;
import ai.kompile.app.prompts.service.SystemPromptEvalIntegrationService;
import ai.kompile.app.prompts.service.SystemPromptService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemPromptControllerTest {

    @Mock
    private SystemPromptService promptService;

    @Mock
    private SystemPromptEvalIntegrationService evalIntegrationService;

    private SystemPromptController controller;

    @BeforeEach
    void setUp() {
        controller = new SystemPromptController(promptService, evalIntegrationService);
    }

    // ─── listPrompts ──────────────────────────────────────────────────────────

    @Test
    void listPrompts_returnsAllPrompts() {
        SystemPromptEntity prompt = mock(SystemPromptEntity.class);
        when(promptService.getPromptsForActiveFactSheet()).thenReturn(List.of(prompt));

        ResponseEntity<List<SystemPromptEntity>> resp = controller.listPrompts();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(1);
    }

    // ─── listPromptsForFactSheet ──────────────────────────────────────────────

    @Test
    void listPromptsForFactSheet_returnsPromptsForId() {
        when(promptService.getPromptsForFactSheet(42L)).thenReturn(List.of());

        ResponseEntity<List<SystemPromptEntity>> resp = controller.listPromptsForFactSheet(42L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(promptService).getPromptsForFactSheet(42L);
    }

    // ─── getPrompt ────────────────────────────────────────────────────────────

    @Test
    void getPrompt_found_returnsOk() {
        SystemPromptEntity prompt = mock(SystemPromptEntity.class);
        when(promptService.getPromptById("id1")).thenReturn(Optional.of(prompt));

        ResponseEntity<?> resp = controller.getPrompt("id1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getPrompt_notFound_returns404() {
        when(promptService.getPromptById("missing")).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.getPrompt("missing");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─── getActivePrompt ──────────────────────────────────────────────────────

    @Test
    void getActivePrompt_found_returnsOk() {
        SystemPromptEntity prompt = mock(SystemPromptEntity.class);
        when(promptService.getActivePrompt()).thenReturn(Optional.of(prompt));

        ResponseEntity<?> resp = controller.getActivePrompt();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getActivePrompt_notFound_returns404() {
        when(promptService.getActivePrompt()).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.getActivePrompt();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─── createPrompt ─────────────────────────────────────────────────────────

    @Test
    void createPrompt_missingName_returnsBadRequest() {
        SystemPromptController.CreatePromptRequest req = new SystemPromptController.CreatePromptRequest(
                null, null, "content", null, null, null);

        ResponseEntity<?> resp = controller.createPrompt(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createPrompt_missingContent_returnsBadRequest() {
        SystemPromptController.CreatePromptRequest req = new SystemPromptController.CreatePromptRequest(
                "name", null, null, null, null, null);

        ResponseEntity<?> resp = controller.createPrompt(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createPrompt_valid_returns201() {
        SystemPromptEntity created = mock(SystemPromptEntity.class);
        when(promptService.createPrompt(any())).thenReturn(created);

        SystemPromptController.CreatePromptRequest req = new SystemPromptController.CreatePromptRequest(
                "prompt1", null, "hello", null, null, "user1");

        ResponseEntity<?> resp = controller.createPrompt(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(promptService).createPrompt(any());
    }

    // ─── updatePrompt ─────────────────────────────────────────────────────────

    @Test
    void updatePrompt_notFound_returns404() {
        when(promptService.updatePrompt(eq("missing"), any()))
                .thenThrow(new IllegalArgumentException("Not found"));

        SystemPromptController.UpdatePromptRequest req = new SystemPromptController.UpdatePromptRequest(
                "name", null, "content", null, null, null);

        ResponseEntity<?> resp = controller.updatePrompt("missing", req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updatePrompt_success_returnsOk() {
        SystemPromptEntity updated = mock(SystemPromptEntity.class);
        when(promptService.updatePrompt(eq("id1"), any())).thenReturn(updated);

        SystemPromptController.UpdatePromptRequest req = new SystemPromptController.UpdatePromptRequest(
                "updated", null, "content", null, null, "changed");

        ResponseEntity<?> resp = controller.updatePrompt("id1", req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ─── deletePrompt ─────────────────────────────────────────────────────────

    @Test
    void deletePrompt_notFound_returns404() {
        doThrow(new IllegalArgumentException("Not found")).when(promptService).deletePrompt("missing");

        ResponseEntity<?> resp = controller.deletePrompt("missing");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deletePrompt_success_returns204() {
        doNothing().when(promptService).deletePrompt("id1");

        ResponseEntity<?> resp = controller.deletePrompt("id1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ─── activatePrompt ───────────────────────────────────────────────────────

    @Test
    void activatePrompt_notFound_returns404() {
        when(promptService.activatePrompt("missing")).thenThrow(new IllegalArgumentException("Not found"));

        ResponseEntity<?> resp = controller.activatePrompt("missing");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void activatePrompt_success_returnsOk() {
        SystemPromptEntity activated = mock(SystemPromptEntity.class);
        when(promptService.activatePrompt("id1")).thenReturn(activated);

        ResponseEntity<?> resp = controller.activatePrompt("id1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ─── searchPrompts ────────────────────────────────────────────────────────

    @Test
    void searchPrompts_returnsMatchingResults() {
        SystemPromptEntity p = mock(SystemPromptEntity.class);
        when(promptService.searchByName("found")).thenReturn(List.of(p));

        ResponseEntity<List<SystemPromptEntity>> resp = controller.searchPrompts("found");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(1);
    }

    // ─── findByTag ────────────────────────────────────────────────────────────

    @Test
    void findByTag_returnsTaggedPrompts() {
        when(promptService.findByTag("rag")).thenReturn(List.of());

        ResponseEntity<List<SystemPromptEntity>> resp = controller.findByTag("rag");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(promptService).findByTag("rag");
    }

    // ─── getPromptCount ───────────────────────────────────────────────────────

    @Test
    void getPromptCount_returnsCount() {
        when(promptService.getPromptCount()).thenReturn(5L);

        ResponseEntity<Map<String, Long>> resp = controller.getPromptCount();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("count", 5L);
    }

    // ─── getVersionHistory ────────────────────────────────────────────────────

    @Test
    void getVersionHistory_notFound_returns404() {
        when(promptService.getVersionHistory("missing")).thenThrow(new IllegalArgumentException("Not found"));

        ResponseEntity<?> resp = controller.getVersionHistory("missing");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getVersionHistory_found_returnsOk() {
        when(promptService.getVersionHistory("id1")).thenReturn(List.of());

        ResponseEntity<?> resp = controller.getVersionHistory("id1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
