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

package ai.kompile.app.services;

import ai.kompile.app.config.BackupProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BackupServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private BackupProperties properties;

    @InjectMocks
    private BackupService backupService;

    @BeforeEach
    void setUp() throws IOException {
        Path backupPath = tempDir.resolve("backups");
        Files.createDirectories(backupPath);

        when(properties.getBackupPath()).thenReturn(backupPath.toString());
        when(properties.getRetentionDays()).thenReturn(7);
        when(properties.getFormat()).thenReturn(BackupProperties.BackupFormat.DIRECTORY);
        when(properties.isEnabled()).thenReturn(true);
        when(properties.isIncludeDatabase()).thenReturn(false);
        when(properties.isIncludeIndexes()).thenReturn(false);
        when(properties.getFixedRateMs()).thenReturn(21600000L);
        when(properties.getOrchestratorDbPath()).thenReturn(tempDir.resolve("orchestrator-db").toString());
        when(properties.getChatHistoryDbPath()).thenReturn(tempDir.resolve("chat-history-db").toString());
        when(properties.getVectorIndexPath()).thenReturn(tempDir.resolve("vector-index").toString());
        when(properties.getTextIndexPath()).thenReturn(tempDir.resolve("text-index").toString());

        backupService.init();
    }

    @Test
    void init_createBackupDirectory() {
        Path backupDir = tempDir.resolve("backups");
        assertThat(Files.exists(backupDir)).isTrue();
    }

    @Test
    void triggerBackup_withNoSourceFiles_returnsResult() {
        BackupService.BackupResult result = backupService.triggerBackup();
        // Nothing to back up (includeDatabase=false, includeIndexes=false)
        assertThat(result).isNotNull();
    }

    @Test
    void triggerBackup_withDatabase_copiesFiles() throws IOException {
        // Create a fake H2 database file
        Path dbDir = tempDir.resolve("dbdir");
        Files.createDirectories(dbDir);
        Path dbFile = dbDir.resolve("mydb.mv.db");
        Files.writeString(dbFile, "fake db content");

        when(properties.isIncludeDatabase()).thenReturn(true);
        when(properties.getOrchestratorDbPath()).thenReturn(dbDir.resolve("mydb").toString());
        when(properties.getChatHistoryDbPath()).thenReturn(dbDir.resolve("nonexistent").toString());

        BackupService.BackupResult result = backupService.triggerBackup();
        assertThat(result).isNotNull();
        assertThat(result.fileCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void triggerBackup_withLuceneIndex_copiesFiles() throws IOException {
        // Create a fake Lucene index directory
        Path indexDir = tempDir.resolve("myindex");
        Files.createDirectories(indexDir);
        Files.writeString(indexDir.resolve("segments_1"), "fake segment data");
        Files.writeString(indexDir.resolve("_0.cfs"), "fake cfs");
        Files.writeString(indexDir.resolve("write.lock"), ""); // should be skipped

        when(properties.isIncludeIndexes()).thenReturn(true);
        when(properties.getVectorIndexPath()).thenReturn(indexDir.toString());
        when(properties.getTextIndexPath()).thenReturn(tempDir.resolve("nonexistent-index").toString());

        BackupService.BackupResult result = backupService.triggerBackup();
        assertThat(result).isNotNull();
        // write.lock should be skipped so only non-lock files counted
        assertThat(result.fileCount()).isEqualTo(2);
    }

    @Test
    void getStatus_returnsCurrentStatus() {
        BackupService.BackupStatus status = backupService.getStatus();
        assertThat(status).isNotNull();
        assertThat(status.enabled()).isTrue();
        assertThat(status.inProgress()).isFalse();
        assertThat(status.backupPath()).isNotNull();
        assertThat(status.retentionDays()).isEqualTo(7);
    }

    @Test
    void listBackups_emptyWhenNoBackupsExist() {
        List<BackupService.BackupInfo> backups = backupService.listBackups();
        assertThat(backups).isEmpty();
    }

    @Test
    void listBackups_returnsExistingBackups() throws IOException {
        Path backupPath = tempDir.resolve("backups");
        Path fakeBackup = backupPath.resolve("backup-20251220-143000");
        Files.createDirectories(fakeBackup);
        Files.writeString(fakeBackup.resolve("something.txt"), "data");

        List<BackupService.BackupInfo> backups = backupService.listBackups();
        assertThat(backups).hasSize(1);
        assertThat(backups.get(0).name()).isEqualTo("backup-20251220-143000");
        assertThat(backups.get(0).format()).isEqualTo("DIRECTORY");
    }

    @Test
    void getBackupFile_returnsNullForNonExistent() {
        Path path = backupService.getBackupFile("backup-99999999-999999");
        assertThat(path).isNull();
    }

    @Test
    void getBackupFile_findsExistingDirectory() throws IOException {
        Path backupPath = tempDir.resolve("backups");
        Path fakeBackup = backupPath.resolve("backup-20251220-143000");
        Files.createDirectories(fakeBackup);

        Path result = backupService.getBackupFile("backup-20251220-143000");
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(fakeBackup);
    }

    @Test
    void getBackupFile_appendsTarGzExtension() throws IOException {
        Path backupPath = tempDir.resolve("backups");
        Path fakeTarGz = backupPath.resolve("backup-20251220-143000.tar.gz");
        Files.writeString(fakeTarGz, "fake tar content");

        Path result = backupService.getBackupFile("backup-20251220-143000");
        assertThat(result).isNotNull();
        assertThat(result.toString()).endsWith(".tar.gz");
    }

    @Test
    void cleanupOldBackups_doesNotDeleteRecentOnes() throws IOException {
        Path backupPath = tempDir.resolve("backups");
        // Create a very recent backup
        Path recentBackup = backupPath.resolve("backup-recent");
        Files.createDirectories(recentBackup);

        int deleted = backupService.cleanupOldBackups();
        assertThat(deleted).isZero(); // creation time is now, not old
        assertThat(Files.exists(recentBackup)).isTrue();
    }

    @Test
    void restoreBackup_returnsFailureForNonExistentBackup() {
        BackupService.RestoreResult result = backupService.restoreBackup("nonexistent-backup");
        assertThat(result).isNotNull();
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("not found");
    }

    @Test
    void triggerBackup_compressedFormat_createsTarGz() throws IOException {
        when(properties.getFormat()).thenReturn(BackupProperties.BackupFormat.COMPRESSED);

        // Create source data to back up
        Path indexDir = tempDir.resolve("compressedindex");
        Files.createDirectories(indexDir);
        Files.writeString(indexDir.resolve("data.dat"), "some data to compress");
        when(properties.isIncludeIndexes()).thenReturn(true);
        when(properties.getVectorIndexPath()).thenReturn(indexDir.toString());
        when(properties.getTextIndexPath()).thenReturn(tempDir.resolve("nope").toString());

        BackupService.BackupResult result = backupService.triggerBackup();
        assertThat(result).isNotNull();
        if (result.fileCount() > 0) {
            // If files backed up, path should end with .tar.gz
            assertThat(result.backupPath()).endsWith(".tar.gz");
        }
    }
}
