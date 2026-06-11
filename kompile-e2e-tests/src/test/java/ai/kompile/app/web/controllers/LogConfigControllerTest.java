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

import ai.kompile.app.ingest.service.JobLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LogConfigControllerTest {

    @Mock
    private JobLogService jobLogService;

    private LogConfigController controller;
    private LogConfigController controllerWithNullService;

    @BeforeEach
    void setUp() {
        controller = new LogConfigController(jobLogService);
        controllerWithNullService = new LogConfigController(null);
    }

    @Test
    void getConfiguration_withNullService_returnsAvailableFalse() {
        ResponseEntity<?> response = controllerWithNullService.getConfiguration();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals(false, body.get("available"));
    }

    @Test
    void getConfiguration_withService_returnsConfigAndStatus() {
        Map<String, Object> config = Map.of("enabled", true);
        Map<String, Object> status = Map.of("totalEntries", 100L);
        when(jobLogService.getConfiguration()).thenReturn(config);
        when(jobLogService.getStatus()).thenReturn(status);

        ResponseEntity<?> response = controller.getConfiguration();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("available"));
        assertEquals(config, body.get("config"));
        assertEquals(status, body.get("status"));
    }

    @Test
    void getConfiguration_withServiceThrowingException_returnsInternalServerError() {
        when(jobLogService.getConfiguration()).thenThrow(new RuntimeException("DB error"));

        ResponseEntity<?> response = controller.getConfiguration();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    @Test
    void updateConfiguration_withNullService_returnsBadRequest() {
        LogConfigController.LogConfigUpdateRequest request = new LogConfigController.LogConfigUpdateRequest(
                true, 7, 1000, 100000L, false, null, false);
        ResponseEntity<?> response = controllerWithNullService.updateConfiguration(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void updateConfiguration_withService_returnsOk() throws Exception {
        Map<String, Object> config = Map.of("enabled", true);
        Map<String, Object> status = Map.of("totalEntries", 50L);
        doNothing().when(jobLogService).updateConfiguration(any(), any(), any(), any(), any(), any(), any());
        when(jobLogService.getConfiguration()).thenReturn(config);
        when(jobLogService.getStatus()).thenReturn(status);

        LogConfigController.LogConfigUpdateRequest request = new LogConfigController.LogConfigUpdateRequest(
                true, 7, 1000, 100000L, false, null, false);
        ResponseEntity<?> response = controller.updateConfiguration(request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void getStatus_withNullService_returnsAvailableFalse() {
        ResponseEntity<?> response = controllerWithNullService.getStatus();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals(false, body.get("available"));
    }

    @Test
    void getStatus_withService_returnsStatus() {
        Map<String, Object> status = Map.of("totalEntries", 200L);
        when(jobLogService.getStatus()).thenReturn(status);

        ResponseEntity<?> response = controller.getStatus();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(status, response.getBody());
    }

    @Test
    void triggerCleanup_withNullService_returnsBadRequest() {
        ResponseEntity<?> response = controllerWithNullService.triggerCleanup(168);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void triggerCleanup_withService_returnsOk() {
        Map<String, Object> status = Map.of("totalEntries", 10L);
        when(jobLogService.getStatus()).thenReturn(status);
        when(jobLogService.forceCleanup(168)).thenReturn(5);

        ResponseEntity<?> response = controller.triggerCleanup(168);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("success"));
        assertEquals(5, body.get("deletedCount"));
    }

    @Test
    void enable_withNullService_returnsBadRequest() {
        ResponseEntity<?> response = controllerWithNullService.enable();
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void enable_withService_returnsOk() throws Exception {
        doNothing().when(jobLogService).updateConfiguration(eq(true), isNull(), isNull(), isNull());

        ResponseEntity<?> response = controller.enable();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("success"));
        assertEquals(true, body.get("enabled"));
    }

    @Test
    void disable_withNullService_returnsBadRequest() {
        ResponseEntity<?> response = controllerWithNullService.disable();
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void disable_withService_returnsOk() throws Exception {
        doNothing().when(jobLogService).updateConfiguration(eq(false), isNull(), isNull(), isNull());

        ResponseEntity<?> response = controller.disable();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("success"));
        assertEquals(false, body.get("enabled"));
    }

    @Test
    void listArchives_withNullService_returnsAvailableFalse() {
        ResponseEntity<?> response = controllerWithNullService.listArchives();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals(false, body.get("available"));
    }

    @Test
    void listArchives_withService_returnsArchiveList() throws IOException {
        List<Map<String, Object>> archives = List.of(Map.of("fileName", "logs-2025.zip"));
        when(jobLogService.listArchives()).thenReturn(archives);
        when(jobLogService.isArchiveEnabled()).thenReturn(true);
        when(jobLogService.getArchivePath()).thenReturn("/tmp/archives");
        when(jobLogService.isArchiveOnCleanup()).thenReturn(false);

        ResponseEntity<?> response = controller.listArchives();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("available"));
        assertEquals(archives, body.get("archives"));
    }

    @Test
    void createArchive_withNullService_returnsBadRequest() {
        ResponseEntity<?> response = controllerWithNullService.createArchive();
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void createArchive_withServiceReturningNull_returnsSuccessFalse() throws IOException {
        when(jobLogService.createFullArchive()).thenReturn(null);

        ResponseEntity<?> response = controller.createArchive();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals(false, body.get("success"));
    }

    @Test
    void createArchive_withService_returnsArchivePath() throws IOException {
        Path archivePath = Paths.get("/tmp/archives/logs-2025.zip");
        when(jobLogService.createFullArchive()).thenReturn(archivePath);

        ResponseEntity<?> response = controller.createArchive();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("success"));
    }

    @Test
    void archiveTaskLogs_withNullService_returnsBadRequest() {
        ResponseEntity<?> response = controllerWithNullService.archiveTaskLogs("task-123");
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void archiveTaskLogs_withService_returnsOk() throws IOException {
        Path archivePath = Paths.get("/tmp/archives/task-123.zip");
        when(jobLogService.archiveLogsForTask("task-123")).thenReturn(archivePath);

        ResponseEntity<?> response = controller.archiveTaskLogs("task-123");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("success"));
    }

    @Test
    void downloadArchive_withNullService_returnsBadRequest() {
        ResponseEntity<?> response = controllerWithNullService.downloadArchive("logs.zip");
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void downloadArchive_withServiceReturningNull_returnsNotFound() {
        when(jobLogService.getArchiveFile("logs.zip")).thenReturn(null);

        ResponseEntity<?> response = controller.downloadArchive("logs.zip");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void deleteArchive_withNullService_returnsBadRequest() {
        ResponseEntity<?> response = controllerWithNullService.deleteArchive("logs.zip");
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void deleteArchive_withServiceDeleting_returnsOk() throws IOException {
        when(jobLogService.deleteArchive("logs.zip")).thenReturn(true);

        ResponseEntity<?> response = controller.deleteArchive("logs.zip");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("success"));
    }

    @Test
    void deleteArchive_withServiceNotFinding_returnsNotFound() throws IOException {
        when(jobLogService.deleteArchive("nonexistent.zip")).thenReturn(false);

        ResponseEntity<?> response = controller.deleteArchive("nonexistent.zip");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
