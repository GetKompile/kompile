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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeakReportCleanupServiceTest {

    @TempDir
    Path tempDir;

    private LeakReportCleanupService service;

    @BeforeEach
    void setUp() {
        service = new LeakReportCleanupService();
        ReflectionTestUtils.setField(service, "cleanupDirectory", tempDir.toString());
        ReflectionTestUtils.setField(service, "maxAgeDays", 7);
        ReflectionTestUtils.setField(service, "maxFiles", 100);
        service.init();
    }

    @Test
    void triggerCleanup_nonExistentDirectory_returnsZero() {
        ReflectionTestUtils.setField(service, "cleanupDirectory", "/nonexistent/path/12345");
        LeakReportCleanupService.CleanupResult result = service.triggerCleanup();
        assertThat(result.deletedCount).isZero();
        assertThat(result.freedBytes).isZero();
    }

    @Test
    void triggerCleanup_emptyDirectory_returnsZero() {
        LeakReportCleanupService.CleanupResult result = service.triggerCleanup();
        assertThat(result.deletedCount).isZero();
        assertThat(result.freedBytes).isZero();
    }

    @Test
    void triggerCleanup_recentFiles_notDeleted() throws IOException {
        Files.writeString(tempDir.resolve("recent.txt"), "recent data");

        LeakReportCleanupService.CleanupResult result = service.triggerCleanup();
        assertThat(result.deletedCount).isZero();
        assertThat(Files.exists(tempDir.resolve("recent.txt"))).isTrue();
    }

    @Test
    void triggerCleanup_oldFiles_deleted() throws IOException {
        Path oldFile = tempDir.resolve("old_leak_report.txt");
        Files.writeString(oldFile, "old report");

        // Set file modification time to 10 days ago
        Instant tenDaysAgo = Instant.now().minus(10, ChronoUnit.DAYS);
        Files.setLastModifiedTime(oldFile, FileTime.from(tenDaysAgo));

        LeakReportCleanupService.CleanupResult result = service.triggerCleanup();
        assertThat(result.deletedCount).isEqualTo(1);
        assertThat(result.freedBytes).isGreaterThan(0);
        assertThat(Files.exists(oldFile)).isFalse();
    }

    @Test
    void triggerCleanup_nonReportFiles_notDeleted() throws IOException {
        // Files that don't match report naming patterns
        Files.writeString(tempDir.resolve("config.xml"), "xml content");
        Files.writeString(tempDir.resolve("data.bin"), "binary data");

        // Make them old
        for (String name : new String[]{"config.xml", "data.bin"}) {
            Instant old = Instant.now().minus(10, ChronoUnit.DAYS);
            Files.setLastModifiedTime(tempDir.resolve(name), FileTime.from(old));
        }

        LeakReportCleanupService.CleanupResult result = service.triggerCleanup();
        // xml and bin are not report files, should not be deleted
        assertThat(result.deletedCount).isZero();
    }

    @Test
    void triggerCleanup_logFiles_deletedWhenOld() throws IOException {
        Path logFile = tempDir.resolve("comprehensive_leak_20250101.log");
        Files.writeString(logFile, "log data here");
        Files.setLastModifiedTime(logFile, FileTime.from(Instant.now().minus(10, ChronoUnit.DAYS)));

        LeakReportCleanupService.CleanupResult result = service.triggerCleanup();
        assertThat(result.deletedCount).isEqualTo(1);
    }

    @Test
    void triggerCleanup_excessFiles_deletedEvenIfRecent() throws IOException {
        ReflectionTestUtils.setField(service, "maxFiles", 2);

        // Create 5 .txt files
        for (int i = 0; i < 5; i++) {
            Path f = tempDir.resolve("report_" + i + ".txt");
            Files.writeString(f, "data " + i);
        }

        LeakReportCleanupService.CleanupResult result = service.triggerCleanup();
        // Should delete 3 (oldest) to get down to 2
        assertThat(result.deletedCount).isGreaterThanOrEqualTo(3);
    }

    @Test
    void setCleanupEnabled_disablesCleanup() throws IOException {
        Path oldFile = tempDir.resolve("old.txt");
        Files.writeString(oldFile, "data");
        Files.setLastModifiedTime(oldFile, FileTime.from(Instant.now().minus(10, ChronoUnit.DAYS)));

        service.setCleanupEnabled(false);
        // cleanupOldReports should skip when disabled
        service.cleanupOldReports();

        // File should still exist because cleanup was disabled
        assertThat(Files.exists(oldFile)).isTrue();
    }

    @Test
    void setCleanupEnabled_reEnablesCleanup() {
        service.setCleanupEnabled(false);
        service.setCleanupEnabled(true);

        LeakReportCleanupService.CleanupConfig config = service.getConfig();
        assertThat(config.enabled).isTrue();
    }

    @Test
    void getConfig_returnsCurrentConfiguration() {
        LeakReportCleanupService.CleanupConfig config = service.getConfig();
        assertThat(config.enabled).isTrue();
        assertThat(config.directory).isEqualTo(tempDir.toString());
        assertThat(config.maxAgeDays).isEqualTo(7);
        assertThat(config.maxFiles).isEqualTo(100);
    }

    @Test
    void updateConfig_updatesMaxAge() {
        service.updateConfig(30, null);
        LeakReportCleanupService.CleanupConfig config = service.getConfig();
        assertThat(config.maxAgeDays).isEqualTo(30);
    }

    @Test
    void updateConfig_updatesMaxFiles() {
        service.updateConfig(null, 50);
        LeakReportCleanupService.CleanupConfig config = service.getConfig();
        assertThat(config.maxFiles).isEqualTo(50);
    }

    @Test
    void updateConfig_ignoresInvalidValues() {
        service.updateConfig(-1, -5);
        LeakReportCleanupService.CleanupConfig config = service.getConfig();
        assertThat(config.maxAgeDays).isEqualTo(7); // unchanged
        assertThat(config.maxFiles).isEqualTo(100); // unchanged
    }

    @Test
    void triggerCleanup_snapshotFiles_deleted() throws IOException {
        Path snapshotFile = tempDir.resolve("heap_snapshot_1234.log");
        Files.writeString(snapshotFile, "snapshot data");
        Files.setLastModifiedTime(snapshotFile, FileTime.from(Instant.now().minus(10, ChronoUnit.DAYS)));

        LeakReportCleanupService.CleanupResult result = service.triggerCleanup();
        assertThat(result.deletedCount).isEqualTo(1);
    }

    @Test
    void cleanupOldReports_skipsNonDirectory() throws IOException {
        // Set the cleanup path to a file, not a directory
        Path notADir = tempDir.resolve("notadir.txt");
        Files.writeString(notADir, "I am not a directory");
        ReflectionTestUtils.setField(service, "cleanupDirectory", notADir.toString());

        // Should complete without error
        service.cleanupOldReports();
    }
}
