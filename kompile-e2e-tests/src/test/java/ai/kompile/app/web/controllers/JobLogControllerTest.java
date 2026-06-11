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

import ai.kompile.app.ingest.domain.JobLogEntry;
import ai.kompile.app.ingest.domain.JobLogEntry.LogLevel;
import ai.kompile.app.ingest.service.JobLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JobLogControllerTest {

    @Mock
    private JobLogService jobLogService;

    private JobLogController controller;

    @BeforeEach
    void setUp() {
        controller = new JobLogController(jobLogService);
        when(jobLogService.isEnabled()).thenReturn(true);
    }

    // ─── getLogsForJob ────────────────────────────────────────────────────────

    @Test
    void getLogsForJob_serviceDisabled_returnsDisabled() {
        when(jobLogService.isEnabled()).thenReturn(false);

        ResponseEntity<?> resp = controller.getLogsForJob("t1", null, null, null, 0, 500);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertThat(body.get("enabled")).isEqualTo(false);
    }

    @Test
    void getLogsForJob_noFilters_returnsAllLogs() {
        Page<JobLogEntry> page = new PageImpl<>(List.of());
        when(jobLogService.getLogsForTask(eq("t1"), eq(0), eq(500))).thenReturn(page);
        when(jobLogService.getLogCountsByLevel("t1")).thenReturn(Map.of());

        ResponseEntity<?> resp = controller.getLogsForJob("t1", null, null, null, 0, 500);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("enabled")).isEqualTo(true);
        assertThat(body.get("taskId")).isEqualTo("t1");
        assertThat(body).containsKey("logs");
        assertThat(body).containsKey("levelCounts");
    }

    @Test
    void getLogsForJob_withSingleLevel_usesLevelFilter() {
        Page<JobLogEntry> page = new PageImpl<>(List.of());
        when(jobLogService.getLogsForTask(eq("t1"), eq(LogLevel.ERROR), eq(0), eq(500))).thenReturn(page);
        when(jobLogService.getLogCountsByLevel("t1")).thenReturn(Map.of());

        ResponseEntity<?> resp = controller.getLogsForJob("t1", "ERROR", null, null, 0, 500);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(jobLogService).getLogsForTask(eq("t1"), eq(LogLevel.ERROR), eq(0), eq(500));
    }

    @Test
    void getLogsForJob_withSearch_usesSearchFilter() {
        Page<JobLogEntry> page = new PageImpl<>(List.of());
        when(jobLogService.searchLogs(eq("t1"), eq("error"), eq(0), eq(500))).thenReturn(page);
        when(jobLogService.getLogCountsByLevel("t1")).thenReturn(Map.of());

        ResponseEntity<?> resp = controller.getLogsForJob("t1", null, null, "error", 0, 500);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(jobLogService).searchLogs(eq("t1"), eq("error"), eq(0), eq(500));
    }

    @Test
    void getLogsForJob_invalidLevel_returnsBadRequest() {
        ResponseEntity<?> resp = controller.getLogsForJob("t1", "NOT_A_LEVEL", null, null, 0, 500);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ─── tailLogs ─────────────────────────────────────────────────────────────

    @Test
    void tailLogs_serviceDisabled_returnsDisabled() {
        when(jobLogService.isEnabled()).thenReturn(false);

        ResponseEntity<?> resp = controller.tailLogs("t1", 100);

        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertThat(body.get("enabled")).isEqualTo(false);
    }

    @Test
    void tailLogs_enabled_returnsLogs() {
        when(jobLogService.tailLogs("t1", 100)).thenReturn(List.of());

        ResponseEntity<?> resp = controller.tailLogs("t1", 100);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("taskId")).isEqualTo("t1");
        assertThat(body).containsKey("count");
    }

    // ─── downloadLogs ─────────────────────────────────────────────────────────

    @Test
    void downloadLogs_serviceDisabled_returnsNotFound() {
        when(jobLogService.isEnabled()).thenReturn(false);

        ResponseEntity<?> resp = controller.downloadLogs("t1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void downloadLogs_noContent_returnsNotFound() {
        when(jobLogService.formatLogsForDownload("t1")).thenReturn("");

        ResponseEntity<?> resp = controller.downloadLogs("t1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void downloadLogs_hasContent_returnsTextFile() {
        when(jobLogService.formatLogsForDownload("t1")).thenReturn("2025-01-01 INFO starting...\n");

        ResponseEntity<?> resp = controller.downloadLogs("t1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
        assertThat(resp.getHeaders().getFirst("Content-Disposition")).contains("t1");
    }

    // ─── getErrorsWithStackTrace ──────────────────────────────────────────────

    @Test
    void getErrorsWithStackTrace_enabled_returnsErrors() {
        when(jobLogService.getErrorsWithStackTrace("t1")).thenReturn(List.of());

        ResponseEntity<?> resp = controller.getErrorsWithStackTrace("t1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("taskId")).isEqualTo("t1");
        assertThat(body).containsKey("count");
    }

    // ─── getLogCount ──────────────────────────────────────────────────────────

    @Test
    void getLogCount_enabled_returnsCount() {
        when(jobLogService.getLogCount("t1")).thenReturn(42L);
        when(jobLogService.getLogCountsByLevel("t1")).thenReturn(Map.of(LogLevel.INFO, 40L, LogLevel.ERROR, 2L));

        ResponseEntity<?> resp = controller.getLogCount("t1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("count")).isEqualTo(42L);
        assertThat(body.get("taskId")).isEqualTo("t1");
    }

    // ─── deleteLogsForJob ─────────────────────────────────────────────────────

    @Test
    void deleteLogsForJob_enabled_deletesAndReturnsOk() {
        when(jobLogService.getLogCount("t1")).thenReturn(10L);
        doNothing().when(jobLogService).deleteLogsForTask("t1");

        ResponseEntity<?> resp = controller.deleteLogsForJob("t1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertThat(body.get("success")).isEqualTo(true);
        assertThat(body.get("deletedCount")).isEqualTo(10L);
    }

    // ─── getLogStatistics ─────────────────────────────────────────────────────

    @Test
    void getLogStatistics_enabled_returnsStats() {
        when(jobLogService.getStatistics()).thenReturn(Map.of("totalLogs", 100L));

        ResponseEntity<?> resp = controller.getLogStatistics();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsKey("totalLogs");
    }

    // ─── getArchivedLogs ──────────────────────────────────────────────────────

    @Test
    void getArchivedLogs_noService_returnsUnavailable() {
        JobLogController ctrl = new JobLogController(null);

        ResponseEntity<?> resp = ctrl.getArchivedLogs("t1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertThat(body.get("available")).isEqualTo(false);
    }

    @Test
    void getArchivedLogs_noArchives_returnsNotAvailable() {
        when(jobLogService.hasArchivedLogsForTask("t1")).thenReturn(false);

        ResponseEntity<?> resp = controller.getArchivedLogs("t1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertThat(body.get("available")).isEqualTo(false);
    }

    @Test
    void getArchivedLogs_hasArchives_returnsLogs() throws Exception {
        when(jobLogService.hasArchivedLogsForTask("t1")).thenReturn(true);
        when(jobLogService.readLogsFromArchive("t1")).thenReturn(List.of());

        ResponseEntity<?> resp = controller.getArchivedLogs("t1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertThat(body.get("available")).isEqualTo(true);
        assertThat(body.get("source")).isEqualTo("archive");
    }

    // ─── checkArchivedLogs ────────────────────────────────────────────────────

    @Test
    void checkArchivedLogs_noService_returnsNotAvailable() {
        JobLogController ctrl = new JobLogController(null);

        ResponseEntity<?> resp = ctrl.checkArchivedLogs("t1");

        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertThat(body.get("available")).isEqualTo(false);
        assertThat(body.get("hasArchive")).isEqualTo(false);
    }

    @Test
    void checkArchivedLogs_hasArchive_returnsTrue() {
        when(jobLogService.hasArchivedLogsForTask("t1")).thenReturn(true);

        ResponseEntity<?> resp = controller.checkArchivedLogs("t1");

        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertThat(body.get("hasArchive")).isEqualTo(true);
    }
}
