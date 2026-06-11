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

import ai.kompile.app.services.scheduling.ScheduledPipelineService;
import ai.kompile.app.services.scheduling.ScheduledPipelineService.ScheduleInfo;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ScheduleController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScheduleControllerTest {

    @Mock
    private ScheduledPipelineService scheduledPipelineService;

    private ScheduleController controller;

    @BeforeEach
    void setUp() {
        controller = new ScheduleController(scheduledPipelineService);
    }

    @Test
    void scheduleStalenessCheck_success_returnsOk() throws Exception {
        ScheduleController.StalenessCheckRequest request =
                new ScheduleController.StalenessCheckRequest("0 0 * * *", 42L);

        ResponseEntity<Map<String, Object>> response = controller.scheduleStalenessCheck(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("staleness-check", response.getBody().get("type"));
        assertEquals("0 0 * * *", response.getBody().get("cron"));
        verify(scheduledPipelineService).scheduleStalenessCheck(anyString(), eq("0 0 * * *"), eq(42L));
    }

    @Test
    void scheduleStalenessCheck_serviceThrows_returnsBadRequest() throws Exception {
        ScheduleController.StalenessCheckRequest request =
                new ScheduleController.StalenessCheckRequest("bad-cron", 1L);
        doThrow(new RuntimeException("invalid cron")).when(scheduledPipelineService)
                .scheduleStalenessCheck(anyString(), anyString(), anyLong());

        ResponseEntity<Map<String, Object>> response = controller.scheduleStalenessCheck(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("error"));
    }

    @Test
    void scheduleReIngestion_success_returnsOk() {
        ScheduleController.ReIngestionRequest request =
                new ScheduleController.ReIngestionRequest("0 1 * * *", 10L);

        ResponseEntity<Map<String, Object>> response = controller.scheduleReIngestion(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("re-ingestion", response.getBody().get("type"));
    }

    @Test
    void scheduleEvalSuite_success_returnsOk() {
        ScheduleController.EvalSuiteRequest request =
                new ScheduleController.EvalSuiteRequest("0 2 * * *", "suite-1");

        ResponseEntity<Map<String, Object>> response = controller.scheduleEvalSuite(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("eval-suite", response.getBody().get("type"));
        assertEquals("suite-1", response.getBody().get("suiteId"));
    }

    @Test
    void listSchedules_returnsOk() throws Exception {
        List<ScheduleInfo> schedules = List.of(mock(ScheduleInfo.class));
        when(scheduledPipelineService.listSchedules()).thenReturn(schedules);

        ResponseEntity<List<ScheduleInfo>> response = controller.listSchedules();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(schedules, response.getBody());
    }

    @Test
    void cancelSchedule_found_returnsNoContent() throws Exception {
        when(scheduledPipelineService.cancelSchedule("sched-1")).thenReturn(true);

        ResponseEntity<Void> response = controller.cancelSchedule("sched-1");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    void cancelSchedule_notFound_returnsNotFound() throws Exception {
        when(scheduledPipelineService.cancelSchedule("missing")).thenReturn(false);

        ResponseEntity<Void> response = controller.cancelSchedule("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
