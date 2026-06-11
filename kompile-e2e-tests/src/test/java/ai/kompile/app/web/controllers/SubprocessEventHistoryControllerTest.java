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

import ai.kompile.app.ingest.domain.SubprocessEventHistory;
import ai.kompile.app.ingest.service.SubprocessEventHistoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link SubprocessEventHistoryController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubprocessEventHistoryControllerTest {

    @Mock
    private SubprocessEventHistoryService historyService;

    private SubprocessEventHistoryController buildController(SubprocessEventHistoryService svc) {
        return new SubprocessEventHistoryController(svc);
    }

    @Test
    void getEvents_withService_returnsOk() {
        SubprocessEventHistoryController controller = buildController(historyService);
        Page<SubprocessEventHistory> page = new PageImpl<>(Collections.emptyList(),
                PageRequest.of(0, 20), 0);
        when(historyService.getAllEvents(anyInt(), anyInt())).thenReturn(page);

        ResponseEntity<Page<SubprocessEventHistory>> response = controller.getEvents(0, 20);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void getEvents_withoutService_returnsOk() {
        SubprocessEventHistoryController controller = buildController(null);

        ResponseEntity<Page<SubprocessEventHistory>> response = controller.getEvents(0, 20);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void getRecentEvents_withService_returnsOk() {
        SubprocessEventHistoryController controller = buildController(historyService);
        when(historyService.getRecentEvents(anyInt())).thenReturn(List.of());

        ResponseEntity<List<SubprocessEventHistory>> response = controller.getRecentEvents(24);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void getRecentEvents_withoutService_returnsEmpty() {
        SubprocessEventHistoryController controller = buildController(null);

        ResponseEntity<List<SubprocessEventHistory>> response = controller.getRecentEvents(24);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void getLatestEvents_withService_returnsOk() {
        SubprocessEventHistoryController controller = buildController(historyService);
        when(historyService.getLatestEvents()).thenReturn(List.of());

        ResponseEntity<List<SubprocessEventHistory>> response = controller.getLatestEvents();

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void getRestartEvents_withService_returnsOk() {
        SubprocessEventHistoryController controller = buildController(historyService);
        when(historyService.getRestartEvents()).thenReturn(List.of());

        ResponseEntity<List<SubprocessEventHistory>> response = controller.getRestartEvents();

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }
}
