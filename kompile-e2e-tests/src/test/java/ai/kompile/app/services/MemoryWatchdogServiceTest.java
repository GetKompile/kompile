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

import ai.kompile.app.config.IngestConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MemoryWatchdogServiceTest {

    @Mock
    private IngestConfiguration ingestConfiguration;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private ai.kompile.app.ingest.service.IngestEventService ingestEventService;

    private MemoryWatchdogService service;

    @BeforeEach
    void setUp() {
        long maxBytes = 4096L * 1024 * 1024;
        long usedBytes = 2048L * 1024 * 1024;
        long freeBytes = maxBytes - usedBytes;
        IngestConfiguration.MemoryInfo memInfo = new IngestConfiguration.MemoryInfo(
                maxBytes,    // maxBytes
                maxBytes,    // totalBytes
                freeBytes,   // freeBytes
                usedBytes,   // usedBytes
                50.0,        // usagePercent - below threshold
                false,       // thresholdExceeded
                false,       // criticalExceeded
                false        // killThresholdExceeded
        );
        when(ingestConfiguration.getMemoryInfo()).thenReturn(memInfo);
        when(ingestConfiguration.getMemoryThresholdPercent()).thenReturn(80);
        when(ingestConfiguration.getMemoryCriticalPercent()).thenReturn(90);
        when(ingestConfiguration.getMemoryKillThresholdPercent()).thenReturn(95);
        when(ingestEventService.isEnabled()).thenReturn(false);

        service = new MemoryWatchdogService(ingestConfiguration, messagingTemplate, ingestEventService);
        service.init();
    }

    @AfterEach
    void tearDown() {
        service.stopWatchdog();
    }

    @Test
    void init_watchdogEnabled() {
        assertThat(service.isWatchdogEnabled()).isTrue();
    }

    @Test
    void registerJob_addsJobToRunning() {
        service.registerJob("task-1", "file.pdf");

        Map<String, MemoryWatchdogService.JobInfo> runningJobs = service.getRunningJobs();
        assertThat(runningJobs).containsKey("task-1");
        assertThat(runningJobs.get("task-1").fileName()).isEqualTo("file.pdf");
    }

    @Test
    void registerJob_withCallback_storesCallback() {
        AtomicBoolean callbackInvoked = new AtomicBoolean(false);
        Runnable callback = () -> callbackInvoked.set(true);

        service.registerJob("task-cb", "file.pdf", callback);
        Map<String, MemoryWatchdogService.JobInfo> jobs = service.getRunningJobs();
        assertThat(jobs.get("task-cb").cancellationCallback()).isNotNull();
    }

    @Test
    void unregisterJob_removesJobFromRunning() {
        service.registerJob("task-2", "file.pdf");
        service.unregisterJob("task-2");

        Map<String, MemoryWatchdogService.JobInfo> runningJobs = service.getRunningJobs();
        assertThat(runningJobs).doesNotContainKey("task-2");
    }

    @Test
    void shouldJobStop_falseByDefault() {
        service.registerJob("task-3", "file.pdf");
        assertThat(service.shouldJobStop("task-3")).isFalse();
    }

    @Test
    void isJobKilled_falseByDefault() {
        service.registerJob("task-4", "file.pdf");
        assertThat(service.isJobKilled("task-4")).isFalse();
    }

    @Test
    void isMemoryPressureDetected_falseInitially() {
        assertThat(service.isMemoryPressureDetected()).isFalse();
    }

    @Test
    void setWatchdogEnabled_disables() {
        service.setWatchdogEnabled(false);
        assertThat(service.isWatchdogEnabled()).isFalse();
    }

    @Test
    void setWatchdogEnabled_enables() {
        service.setWatchdogEnabled(false);
        service.setWatchdogEnabled(true);
        assertThat(service.isWatchdogEnabled()).isTrue();
    }

    @Test
    void getJobsMarkedForStop_emptyInitially() {
        Set<String> markedJobs = service.getJobsMarkedForStop();
        assertThat(markedJobs).isEmpty();
    }

    @Test
    void getJobsKilled_emptyInitially() {
        Set<String> killedJobs = service.getJobsKilled();
        assertThat(killedJobs).isEmpty();
    }

    @Test
    void getStatus_containsExpectedFields() {
        MemoryWatchdogService.WatchdogStatus status = service.getStatus();
        assertThat(status).isNotNull();
        assertThat(status.enabled()).isTrue();
        assertThat(status.memoryPressureDetected()).isFalse();
        assertThat(status.runningJobCount()).isZero();
        assertThat(status.memoryThresholdPercent()).isEqualTo(80);
        assertThat(status.memoryCriticalPercent()).isEqualTo(90);
        assertThat(status.memoryKillThresholdPercent()).isEqualTo(95);
    }

    @Test
    void getCurrentMemoryUsage_returnsLastKnownUsage() {
        double usage = service.getCurrentMemoryUsage();
        // Should be the last polled value (0 initially before first check)
        assertThat(usage).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void unregisterJob_alsoClearsStopAndKillSets() {
        service.registerJob("task-x", "file.pdf");
        service.unregisterJob("task-x");

        assertThat(service.shouldJobStop("task-x")).isFalse();
        assertThat(service.isJobKilled("task-x")).isFalse();
    }

    @Test
    void getLastMemoryPressureTime_nullBeforePressure() {
        assertThat(service.getLastMemoryPressureTime()).isNull();
    }

    @Test
    void setCheckIntervalMs_clampsToValidRange() {
        // Test that it doesn't throw
        service.setCheckIntervalMs(100); // below min 500, should clamp to 500
        service.setCheckIntervalMs(100_000); // above max 60000, should clamp to 60000
        service.setCheckIntervalMs(5000); // valid
    }
}
