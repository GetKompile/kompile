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

import ai.kompile.app.services.DocumentFreshnessService;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StalenessCheckJobTest {

    @Mock
    private DocumentFreshnessService freshnessService;

    @Mock
    private JobExecutionContext context;

    @Mock
    private JobDetail jobDetail;

    private StalenessCheckJob job;

    @BeforeEach
    void setUp() {
        job = new StalenessCheckJob();

        JobDataMap dataMap = new JobDataMap();
        dataMap.put("factSheetId", 7L);
        when(context.getJobDetail()).thenReturn(jobDetail);
        when(jobDetail.getJobDataMap()).thenReturn(dataMap);
    }

    @Test
    void testExecute_freshnessServiceNull_skips() throws JobExecutionException {
        // freshnessService not injected — should skip gracefully
        assertDoesNotThrow(() -> job.execute(context));
    }

    @Test
    void testExecute_withFreshnessService_scansForStale() throws Exception {
        ReflectionTestUtils.setField(job, "freshnessService", freshnessService);
        when(freshnessService.scanForStaleDocuments(7L)).thenReturn(3);
        when(freshnessService.markStaleByTtl(7L)).thenReturn(1);

        assertDoesNotThrow(() -> job.execute(context));
        verify(freshnessService).scanForStaleDocuments(7L);
        verify(freshnessService).markStaleByTtl(7L);
    }

    @Test
    void testExecute_scanReturnsZero_noError() throws Exception {
        ReflectionTestUtils.setField(job, "freshnessService", freshnessService);
        when(freshnessService.scanForStaleDocuments(7L)).thenReturn(0);
        when(freshnessService.markStaleByTtl(7L)).thenReturn(0);

        assertDoesNotThrow(() -> job.execute(context));
    }

    @Test
    void testExecute_freshnessServiceThrows_wrapsInJobExecutionException() throws Exception {
        ReflectionTestUtils.setField(job, "freshnessService", freshnessService);
        when(freshnessService.scanForStaleDocuments(7L))
                .thenThrow(new RuntimeException("DB connection lost"));

        assertThrows(JobExecutionException.class, () -> job.execute(context));
    }

    @Test
    void testExecute_usesCorrectFactSheetId() throws Exception {
        ReflectionTestUtils.setField(job, "freshnessService", freshnessService);

        JobDataMap dataMap = new JobDataMap();
        dataMap.put("factSheetId", 55L);
        when(jobDetail.getJobDataMap()).thenReturn(dataMap);

        when(freshnessService.scanForStaleDocuments(55L)).thenReturn(2);
        when(freshnessService.markStaleByTtl(55L)).thenReturn(0);

        job.execute(context);
        verify(freshnessService).scanForStaleDocuments(55L);
        verify(freshnessService).markStaleByTtl(55L);
    }

    @Test
    void testExecute_markStaleByTtlThrows_wrapsException() throws Exception {
        ReflectionTestUtils.setField(job, "freshnessService", freshnessService);
        when(freshnessService.scanForStaleDocuments(7L)).thenReturn(5);
        when(freshnessService.markStaleByTtl(7L)).thenThrow(new RuntimeException("TTL error"));

        assertThrows(JobExecutionException.class, () -> job.execute(context));
    }

    @Test
    void testExecute_largeStaleCount_handledGracefully() throws Exception {
        ReflectionTestUtils.setField(job, "freshnessService", freshnessService);
        when(freshnessService.scanForStaleDocuments(7L)).thenReturn(1000);
        when(freshnessService.markStaleByTtl(7L)).thenReturn(500);

        assertDoesNotThrow(() -> job.execute(context));
    }
}
