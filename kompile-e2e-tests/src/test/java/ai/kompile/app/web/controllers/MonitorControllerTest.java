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

import ai.kompile.app.monitor.domain.MonitorRegistration;
import ai.kompile.app.monitor.dto.MonitorRequest;
import ai.kompile.app.monitor.service.MonitorService;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonitorControllerTest {

    @Mock
    private MonitorService monitorService;

    private MonitorController controller;

    @BeforeEach
    void setUp() {
        controller = new MonitorController(monitorService);
    }

    private MonitorRegistration makeRegistration(String monitorId) {
        MonitorRegistration reg = new MonitorRegistration();
        reg.setMonitorId(monitorId);
        reg.setSessionId("session-1");
        return reg;
    }

    // ── watchTask ──────────────────────────────────────────────────────────

    @Test
    void watchTask_success_returns200() {
        MonitorRequest.WatchTask req = new MonitorRequest.WatchTask("sess1", "task-1", "desc", "payload");
        MonitorRegistration reg = makeRegistration("m-1");
        when(monitorService.watchTask("sess1", "task-1", "desc", "payload")).thenReturn(reg);

        ResponseEntity<?> resp = controller.watchTask(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(reg, resp.getBody());
    }

    @Test
    void watchTask_illegalArgument_returns400() {
        MonitorRequest.WatchTask req = new MonitorRequest.WatchTask(null, "task-1", "desc", null);
        when(monitorService.watchTask(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("sessionId required"));

        ResponseEntity<?> resp = controller.watchTask(req);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().toString().contains("error"));
    }

    // ── scheduleOnce ───────────────────────────────────────────────────────

    @Test
    void scheduleOnce_success_returns200() {
        long fireAt = System.currentTimeMillis() + 60_000;
        MonitorRequest.ScheduleOnce req = new MonitorRequest.ScheduleOnce("sess1", fireAt, "desc", null);
        MonitorRegistration reg = makeRegistration("m-2");
        when(monitorService.scheduleOnce("sess1", fireAt, "desc", null)).thenReturn(reg);

        ResponseEntity<?> resp = controller.scheduleOnce(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(reg, resp.getBody());
    }

    @Test
    void scheduleOnce_illegalArgument_returns400() {
        MonitorRequest.ScheduleOnce req = new MonitorRequest.ScheduleOnce(null, 0L, "desc", null);
        when(monitorService.scheduleOnce(any(), anyLong(), any(), any()))
                .thenThrow(new IllegalArgumentException("bad session"));

        ResponseEntity<?> resp = controller.scheduleOnce(req);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void scheduleOnce_illegalState_returns500() {
        MonitorRequest.ScheduleOnce req = new MonitorRequest.ScheduleOnce("sess1", 1L, "desc", null);
        when(monitorService.scheduleOnce(any(), anyLong(), any(), any()))
                .thenThrow(new IllegalStateException("scheduler unavailable"));

        ResponseEntity<?> resp = controller.scheduleOnce(req);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
    }

    // ── scheduleCron ───────────────────────────────────────────────────────

    @Test
    void scheduleCron_success_returns200() {
        MonitorRequest.ScheduleCron req = new MonitorRequest.ScheduleCron("sess1", "0 * * * * *", "desc", null);
        MonitorRegistration reg = makeRegistration("m-3");
        when(monitorService.scheduleCron("sess1", "0 * * * * *", "desc", null)).thenReturn(reg);

        ResponseEntity<?> resp = controller.scheduleCron(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(reg, resp.getBody());
    }

    @Test
    void scheduleCron_illegalArgument_returns400() {
        MonitorRequest.ScheduleCron req = new MonitorRequest.ScheduleCron("sess1", "bad-cron", "desc", null);
        when(monitorService.scheduleCron(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("bad cron"));

        ResponseEntity<?> resp = controller.scheduleCron(req);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void scheduleCron_illegalState_returns500() {
        MonitorRequest.ScheduleCron req = new MonitorRequest.ScheduleCron("sess1", "0 * * * * *", "desc", null);
        when(monitorService.scheduleCron(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("scheduler down"));

        ResponseEntity<?> resp = controller.scheduleCron(req);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
    }

    // ── list ───────────────────────────────────────────────────────────────

    @Test
    void list_withSessionId_callsListBySession() {
        List<MonitorRegistration> expected = List.of(makeRegistration("m-1"));
        when(monitorService.listBySession("sess1")).thenReturn(expected);

        ResponseEntity<List<MonitorRegistration>> resp = controller.list("sess1", false);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(expected, resp.getBody());
    }

    @Test
    void list_allTrue_callsListAll() {
        List<MonitorRegistration> expected = List.of(makeRegistration("m-1"), makeRegistration("m-2"));
        when(monitorService.listAll()).thenReturn(expected);

        ResponseEntity<List<MonitorRegistration>> resp = controller.list(null, true);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(expected, resp.getBody());
    }

    @Test
    void list_noFilter_callsListActive() {
        List<MonitorRegistration> expected = List.of(makeRegistration("m-3"));
        when(monitorService.listActive()).thenReturn(expected);

        ResponseEntity<List<MonitorRegistration>> resp = controller.list(null, false);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(expected, resp.getBody());
    }

    // ── get ────────────────────────────────────────────────────────────────

    @Test
    void get_found_returns200() {
        MonitorRegistration reg = makeRegistration("m-1");
        when(monitorService.get("m-1")).thenReturn(Optional.of(reg));

        ResponseEntity<MonitorRegistration> resp = controller.get("m-1");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(reg, resp.getBody());
    }

    @Test
    void get_notFound_returns404() {
        when(monitorService.get("m-999")).thenReturn(Optional.empty());

        ResponseEntity<MonitorRegistration> resp = controller.get("m-999");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // ── cancel ─────────────────────────────────────────────────────────────

    @Test
    void cancel_success_returns204() {
        when(monitorService.cancel("m-1")).thenReturn(true);

        ResponseEntity<?> resp = controller.cancel("m-1");

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
    }

    @Test
    void cancel_notFound_returns404() {
        when(monitorService.cancel("m-999")).thenReturn(false);

        ResponseEntity<?> resp = controller.cancel("m-999");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }
}
