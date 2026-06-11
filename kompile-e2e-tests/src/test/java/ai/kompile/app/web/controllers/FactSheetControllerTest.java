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

import ai.kompile.app.facts.domain.Fact;
import ai.kompile.app.facts.domain.Fact.SourceType;
import ai.kompile.app.facts.domain.Fact.ViewMode;
import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.app.facts.service.FactSheetService;
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
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FactSheetControllerTest {

    @Mock
    private FactSheetService factSheetService;

    private FactSheetController controller;

    @BeforeEach
    void setUp() {
        // embeddingModel is null (optional), controller must handle null gracefully
        controller = new FactSheetController(factSheetService, null);
        // Default stub: toDto calls these helpers so stub them to avoid NPE
        when(factSheetService.getFactCount(anyLong())).thenReturn(0L);
        when(factSheetService.getTotalSize(anyLong())).thenReturn(0L);
        when(factSheetService.getIndexedCount(anyLong())).thenReturn(0L);
        when(factSheetService.getUnindexedCount(anyLong())).thenReturn(0L);
        when(factSheetService.needsReindex(anyLong())).thenReturn(false);
    }

    private FactSheet sheet(Long id, String name) {
        FactSheet s = new FactSheet();
        s.setId(id);
        s.setName(name);
        s.setIsActive(false);
        return s;
    }

    private Fact fact(Long id, FactSheet sheet) {
        Fact f = new Fact();
        f.setId(id);
        f.setFactSheet(sheet);
        f.setFileName("file.pdf");
        f.setFilePath("/tmp/file.pdf");
        f.setSourceType(SourceType.UPLOAD);
        f.setViewMode(ViewMode.DOWNLOAD_ONLY);
        f.setCanPreview(false);
        return f;
    }

    // ─── getAllSheets ──────────────────────────────────────────────────────────

    @Test
    void getAllSheets_returnsOkWithList() {
        when(factSheetService.getAllSheets()).thenReturn(List.of(sheet(1L, "Default")));

        ResponseEntity<List<FactSheetController.FactSheetDto>> resp = controller.getAllSheets();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(1);
        assertThat(resp.getBody().get(0).name()).isEqualTo("Default");
    }

    @Test
    void getAllSheets_emptyList_returnsOk() {
        when(factSheetService.getAllSheets()).thenReturn(List.of());

        ResponseEntity<List<FactSheetController.FactSheetDto>> resp = controller.getAllSheets();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEmpty();
    }

    // ─── getActiveSheet ───────────────────────────────────────────────────────

    @Test
    void getActiveSheet_returnsOk() {
        FactSheet active = sheet(1L, "Active");
        active.setIsActive(true);
        when(factSheetService.getActiveSheet()).thenReturn(active);

        ResponseEntity<FactSheetController.FactSheetDto> resp = controller.getActiveSheet();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().isActive()).isTrue();
    }

    // ─── getSheet ─────────────────────────────────────────────────────────────

    @Test
    void getSheet_found_returnsOk() {
        when(factSheetService.getSheetById(1L)).thenReturn(Optional.of(sheet(1L, "s1")));

        ResponseEntity<FactSheetController.FactSheetDto> resp = controller.getSheet(1L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getSheet_notFound_returnsNotFound() {
        when(factSheetService.getSheetById(99L)).thenReturn(Optional.empty());

        ResponseEntity<FactSheetController.FactSheetDto> resp = controller.getSheet(99L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─── createSheet ──────────────────────────────────────────────────────────

    @Test
    void createSheet_success_returnsCreated() {
        FactSheet created = sheet(2L, "New Sheet");
        when(factSheetService.createSheet(
                anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(created);

        FactSheetController.CreateFactSheetRequest req = new FactSheetController.CreateFactSheetRequest(
                "New Sheet", "desc", "blue", "icon",
                null, null, null, null, null,
                null, null, null, null, null, null, null
        );

        ResponseEntity<FactSheetController.FactSheetDto> resp = controller.createSheet(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().name()).isEqualTo("New Sheet");
    }

    @Test
    void createSheet_illegalArgument_returnsBadRequest() {
        when(factSheetService.createSheet(
                anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        )).thenThrow(new IllegalArgumentException("invalid name"));

        FactSheetController.CreateFactSheetRequest req = new FactSheetController.CreateFactSheetRequest(
                "", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );

        ResponseEntity<FactSheetController.FactSheetDto> resp = controller.createSheet(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ─── deleteSheet ──────────────────────────────────────────────────────────

    @Test
    void deleteSheet_success_returnsNoContent() {
        doNothing().when(factSheetService).deleteSheet(1L);

        ResponseEntity<Void> resp = controller.deleteSheet(1L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void deleteSheet_illegalArgument_returnsBadRequest() {
        doThrow(new IllegalArgumentException("not found")).when(factSheetService).deleteSheet(99L);

        ResponseEntity<Void> resp = controller.deleteSheet(99L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ─── activateSheet ────────────────────────────────────────────────────────

    @Test
    void activateSheet_success_returnsOk() {
        FactSheet activated = sheet(1L, "s1");
        activated.setIsActive(true);
        when(factSheetService.activateSheet(1L)).thenReturn(activated);

        ResponseEntity<FactSheetController.FactSheetDto> resp = controller.activateSheet(1L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().isActive()).isTrue();
    }

    // ─── getActiveFacts ───────────────────────────────────────────────────────

    @Test
    void getActiveFacts_returnsOkWithFacts() {
        FactSheet s = sheet(1L, "s1");
        Fact f = fact(10L, s);
        when(factSheetService.getActiveFacts()).thenReturn(List.of(f));

        ResponseEntity<List<FactSheetController.FactDto>> resp = controller.getActiveFacts();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(1);
        assertThat(resp.getBody().get(0).fileName()).isEqualTo("file.pdf");
    }

    // ─── getFact ──────────────────────────────────────────────────────────────

    @Test
    void getFact_found_returnsOk() {
        FactSheet s = sheet(1L, "s1");
        when(factSheetService.getFactById(10L)).thenReturn(Optional.of(fact(10L, s)));

        ResponseEntity<FactSheetController.FactDto> resp = controller.getFact(10L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getFact_notFound_returnsNotFound() {
        when(factSheetService.getFactById(99L)).thenReturn(Optional.empty());

        ResponseEntity<FactSheetController.FactDto> resp = controller.getFact(99L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─── deleteFact ───────────────────────────────────────────────────────────

    @Test
    void deleteFact_success_returnsNoContent() {
        doNothing().when(factSheetService).deleteFact(10L);

        ResponseEntity<Void> resp = controller.deleteFact(10L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ─── deleteFacts ──────────────────────────────────────────────────────────

    @Test
    void deleteFacts_callsServiceAndReturnsNoContent() {
        doNothing().when(factSheetService).deleteFacts(any());
        FactSheetController.DeleteFactsRequest req = new FactSheetController.DeleteFactsRequest(Set.of(1L, 2L));

        ResponseEntity<Void> resp = controller.deleteFacts(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(factSheetService).deleteFacts(Set.of(1L, 2L));
    }

    // ─── copyFacts ────────────────────────────────────────────────────────────

    @Test
    void copyFacts_success_returnsCopiedCount() {
        when(factSheetService.copyFacts(1L, 2L, Set.of(10L))).thenReturn(1);
        FactSheetController.CopyFactsRequest req = new FactSheetController.CopyFactsRequest(Set.of(10L));

        ResponseEntity<java.util.Map<String, Integer>> resp = controller.copyFacts(1L, 2L, req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("copiedCount")).isEqualTo(1);
    }

    @Test
    void copyFacts_illegalArgument_returnsBadRequest() {
        when(factSheetService.copyFacts(anyLong(), anyLong(), any()))
                .thenThrow(new IllegalArgumentException("sheet not found"));
        FactSheetController.CopyFactsRequest req = new FactSheetController.CopyFactsRequest(Set.of());

        ResponseEntity<java.util.Map<String, Integer>> resp = controller.copyFacts(1L, 99L, req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ─── markFactsAsIndexed ───────────────────────────────────────────────────

    @Test
    void markFactsAsIndexed_returnsMarkedCount() {
        when(factSheetService.markFactsAsIndexed(any())).thenReturn(3);
        FactSheetController.MarkIndexedRequest req = new FactSheetController.MarkIndexedRequest(Set.of(1L, 2L, 3L));

        ResponseEntity<java.util.Map<String, Integer>> resp = controller.markFactsAsIndexed(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("markedCount")).isEqualTo(3);
    }

    // ─── markFactAsIndexed ────────────────────────────────────────────────────

    @Test
    void markFactAsIndexed_found_returnsOk() {
        when(factSheetService.markFactAsIndexed(10L)).thenReturn(true);

        ResponseEntity<Void> resp = controller.markFactAsIndexed(10L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void markFactAsIndexed_notFound_returnsNotFound() {
        when(factSheetService.markFactAsIndexed(99L)).thenReturn(false);

        ResponseEntity<Void> resp = controller.markFactAsIndexed(99L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─── getActiveSheetIndexingStats ──────────────────────────────────────────

    @Test
    void getActiveSheetIndexingStats_returnsOk() {
        FactSheetService.IndexingStats stats = new FactSheetService.IndexingStats(10, 8, 2);
        when(factSheetService.getActiveSheetIndexingStats()).thenReturn(stats);

        ResponseEntity<FactSheetController.IndexingStatsDto> resp = controller.getActiveSheetIndexingStats();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().totalFacts()).isEqualTo(10);
        assertThat(resp.getBody().indexedFacts()).isEqualTo(8);
        assertThat(resp.getBody().unindexedFacts()).isEqualTo(2);
    }

    // ─── searchFacts ──────────────────────────────────────────────────────────

    @Test
    void searchFacts_returnsMatchingFacts() {
        FactSheet s = sheet(1L, "s1");
        Fact f = fact(5L, s);
        when(factSheetService.searchFacts("AI")).thenReturn(List.of(f));

        ResponseEntity<List<FactSheetController.FactDto>> resp = controller.searchFacts("AI");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(1);
    }

    // ─── checkNeedsReindex ────────────────────────────────────────────────────

    @Test
    void checkNeedsReindex_sheetFound_returnsReindexStatus() {
        FactSheet s = sheet(1L, "s1");
        when(factSheetService.getSheetById(1L)).thenReturn(Optional.of(s));
        when(factSheetService.needsReindex(1L)).thenReturn(true);

        ResponseEntity<java.util.Map<String, Object>> resp = controller.checkNeedsReindex(1L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("needsReindex")).isEqualTo(true);
        assertThat(resp.getBody().get("factSheetId")).isEqualTo(1L);
    }

    @Test
    void checkNeedsReindex_sheetNotFound_returnsNotFound() {
        when(factSheetService.getSheetById(99L)).thenReturn(Optional.empty());

        ResponseEntity<java.util.Map<String, Object>> resp = controller.checkNeedsReindex(99L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
