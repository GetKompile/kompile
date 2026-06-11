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

import ai.kompile.app.services.BackupService;
import ai.kompile.app.services.BackupService.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BackupControllerTest {

    @Mock
    private BackupService backupService;

    @InjectMocks
    private BackupController controller;

    private MockMvc mockMvc;

    @TempDir
    Path tempDir;

    private BackupStatus sampleStatus;
    private BackupResult sampleResult;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        sampleResult = new BackupResult(true, "Backup completed", "/backups/backup-20250101-120000.tar.gz",
                10, 1024 * 1024, 500L, List.of());

        sampleStatus = new BackupStatus(true, false, Instant.now(), sampleResult,
                tempDir.toString(), 7, "tar.gz", 3600000L);

        when(backupService.getStatus()).thenReturn(sampleStatus);
        when(backupService.triggerBackup()).thenReturn(sampleResult);
        when(backupService.listBackups()).thenReturn(List.of());
        when(backupService.cleanupOldBackups()).thenReturn(0);
    }

    @Test
    void getStatus_returnsOk() throws Exception {
        mockMvc.perform(get("/api/backup/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.inProgress").value(false));
    }

    @Test
    void getStatus_serviceThrows_returns500() throws Exception {
        when(backupService.getStatus()).thenThrow(new RuntimeException("Service unavailable"));

        mockMvc.perform(get("/api/backup/status"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void triggerBackup_returnsSuccessResult() throws Exception {
        mockMvc.perform(post("/api/backup/trigger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value("success"));

        verify(backupService).triggerBackup();
    }

    @Test
    void triggerBackup_serviceThrows_returns500() throws Exception {
        when(backupService.triggerBackup()).thenThrow(new RuntimeException("Backup failed"));

        mockMvc.perform(post("/api/backup/trigger"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void listBackups_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/backup/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.backups").isArray())
                .andExpect(jsonPath("$.status").value("success"));
    }

    @Test
    void listBackups_withItems_returnsList() throws Exception {
        BackupInfo info = new BackupInfo("backup-20250101-120000.tar.gz",
                "/backups/backup-20250101-120000.tar.gz", Instant.now(), 1024L, "tar.gz");
        when(backupService.listBackups()).thenReturn(List.of(info));

        mockMvc.perform(get("/api/backup/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.backups[0].name").value("backup-20250101-120000.tar.gz"));
    }

    @Test
    void cleanupBackups_returnsDeletedCount() throws Exception {
        when(backupService.cleanupOldBackups()).thenReturn(3);

        mockMvc.perform(post("/api/backup/cleanup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedCount").value(3))
                .andExpect(jsonPath("$.status").value("success"));
    }

    @Test
    void deleteBackup_invalidName_returnsBadRequest() throws Exception {
        // Path traversal chars in URL get resolved by Spring MVC before reaching the controller,
        // so use a name that reaches the controller but fails the backup-name regex
        mockMvc.perform(delete("/api/backup/etc-passwd-hack"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteBackup_invalidNameFormat_returnsBadRequest() throws Exception {
        mockMvc.perform(delete("/api/backup/invalid-name"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid backup name format"));
    }

    @Test
    void deleteBackup_validNameNotFound_returns404() throws Exception {
        when(backupService.getStatus()).thenReturn(
                new BackupStatus(true, false, null, null, tempDir.toString(), 7, "tar.gz", 3600000L));

        mockMvc.perform(delete("/api/backup/backup-20250101-120000"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteBackup_validNameFound_deletesAndReturnsOk() throws Exception {
        Path backupFile = tempDir.resolve("backup-20250101-120000");
        Files.createFile(backupFile);

        when(backupService.getStatus()).thenReturn(
                new BackupStatus(true, false, null, null, tempDir.toString(), 7, "tar.gz", 3600000L));

        mockMvc.perform(delete("/api/backup/backup-20250101-120000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.deleted").value("backup-20250101-120000"));
    }

    @Test
    void restoreBackup_invalidNameFormat_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/backup/not-a-backup/restore"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid backup name format"));
    }

    @Test
    void restoreBackup_validName_callsServiceAndReturnsOk() throws Exception {
        RestoreResult restoreResult = new RestoreResult(true, "Restored successfully", 300L, List.of());
        when(backupService.restoreBackup("backup-20250101-120000")).thenReturn(restoreResult);

        mockMvc.perform(post("/api/backup/backup-20250101-120000/restore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value("success"));
    }

    @Test
    void downloadBackup_invalidNameFormat_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/backup/badname/download"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void downloadBackup_validNameNotFound_returns404() throws Exception {
        when(backupService.getBackupFile("backup-20250101-120000")).thenReturn(null);

        mockMvc.perform(get("/api/backup/backup-20250101-120000/download"))
                .andExpect(status().isNotFound());
    }
}
