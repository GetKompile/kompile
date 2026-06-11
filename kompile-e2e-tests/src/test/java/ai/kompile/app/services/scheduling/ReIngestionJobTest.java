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

package ai.kompile.app.services.scheduling;

import ai.kompile.app.services.IndexSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReIngestionJobTest {

    @Mock
    private IndexSyncService syncService;

    @Mock
    private JobExecutionContext context;

    @Mock
    private JobDetail jobDetail;

    private ReIngestionJob job;

    @BeforeEach
    void setUp() {
        job = new ReIngestionJob();
        ReflectionTestUtils.setField(job, "syncService", syncService);

        JobDataMap dataMap = new JobDataMap();
        dataMap.put("factSheetId", 42L);
        when(context.getJobDetail()).thenReturn(jobDetail);
        when(jobDetail.getJobDataMap()).thenReturn(dataMap);
    }

    @Test
    void testExecute_success() throws Exception {
        IndexSyncService.SyncResult syncResult = new IndexSyncService.SyncResult(
                "job-001",
                IndexSyncService.SyncStatus.COMPLETED,
                10, 100, 0,
                List.of(),
                Duration.ofSeconds(5)
        );
        when(syncService.syncAll(42L)).thenReturn(CompletableFuture.completedFuture(syncResult));

        assertDoesNotThrow(() -> job.execute(context));
        verify(syncService).syncAll(42L);
    }

    @Test
    void testExecute_syncReturnsPartialSuccess() throws Exception {
        IndexSyncService.SyncResult syncResult = new IndexSyncService.SyncResult(
                "job-002",
                IndexSyncService.SyncStatus.PARTIAL,
                5, 50, 2,
                List.of("error1", "error2"),
                Duration.ofSeconds(3)
        );
        when(syncService.syncAll(42L)).thenReturn(CompletableFuture.completedFuture(syncResult));

        assertDoesNotThrow(() -> job.execute(context));
    }

    @Test
    void testExecute_syncThrows_wrapsException() throws Exception {
        when(syncService.syncAll(42L)).thenReturn(CompletableFuture.failedFuture(
                new RuntimeException("Sync failed")
        ));

        assertThrows(JobExecutionException.class, () -> job.execute(context));
    }

    @Test
    void testExecute_syncServiceThrowsDirectly() throws Exception {
        when(syncService.syncAll(42L)).thenThrow(new RuntimeException("Unexpected failure"));

        assertThrows(JobExecutionException.class, () -> job.execute(context));
    }

    @Test
    void testExecute_usesFactSheetIdFromDataMap() throws Exception {
        JobDataMap dataMap = new JobDataMap();
        dataMap.put("factSheetId", 99L);
        when(jobDetail.getJobDataMap()).thenReturn(dataMap);

        IndexSyncService.SyncResult syncResult = new IndexSyncService.SyncResult(
                "job-003",
                IndexSyncService.SyncStatus.COMPLETED,
                1, 5, 0, List.of(), Duration.ofMillis(100)
        );
        when(syncService.syncAll(99L)).thenReturn(CompletableFuture.completedFuture(syncResult));

        job.execute(context);
        verify(syncService).syncAll(99L);
    }
}
