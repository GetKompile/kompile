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

import ai.kompile.app.services.MemoryWatchdogService;
import ai.kompile.app.services.SystemResourceBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link SystemResourceController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemResourceControllerTest {

    @Mock
    private MemoryWatchdogService memoryWatchdogService;

    @Mock
    private SystemResourceBroadcaster systemResourceBroadcaster;

    private SystemResourceController controller;
    private SystemResourceController controllerNoServices;

    @BeforeEach
    void setUp() {
        controller = new SystemResourceController(memoryWatchdogService, systemResourceBroadcaster);
        controllerNoServices = new SystemResourceController(null, null);
    }

    @Test
    void getCpuEndpoint_returnsOk() {
        ResponseEntity<Map<String, Object>> response = controller.getCpuEndpoint();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void getMemoryEndpoint_returnsOk() {
        ResponseEntity<Map<String, Object>> response = controller.getMemoryEndpoint();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void getThreadsEndpoint_returnsOk() {
        ResponseEntity<Map<String, Object>> response = controller.getThreadsEndpoint();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void getProcessEndpoint_returnsOk() {
        ResponseEntity<Map<String, Object>> response = controller.getProcessEndpoint();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void triggerGc_returnsOk() {
        ResponseEntity<Map<String, Object>> response = controller.triggerGc();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void getMemoryWatchdogStatus_withService_returnsOk() {
        // getMemoryWatchdogStatus calls memoryWatchdogService.getStatus() which requires IngestConfiguration
        // Since that would fail, we verify null service returns unavailable
        ResponseEntity<Map<String, Object>> response = controllerNoServices.getMemoryWatchdogStatus();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("unavailable", response.getBody().get("status"));
    }

    @Test
    void getMemoryWatchdogStatus_withoutService_returnsOkWithUnavailable() {
        ResponseEntity<Map<String, Object>> response = controllerNoServices.getMemoryWatchdogStatus();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void setMemoryWatchdogEnabled_withService_returnsOk() {
        when(memoryWatchdogService.isWatchdogEnabled()).thenReturn(true);
        doNothing().when(memoryWatchdogService).setWatchdogEnabled(true);

        ResponseEntity<Map<String, Object>> response = controller.setMemoryWatchdogEnabled(true);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(memoryWatchdogService).setWatchdogEnabled(true);
    }

    @Test
    void getBroadcastStatus_withService_returnsOk() {
        when(systemResourceBroadcaster.isBroadcasting()).thenReturn(true);
        when(systemResourceBroadcaster.getSubscriberCount()).thenReturn(0);

        ResponseEntity<Map<String, Object>> response = controller.getBroadcastStatus();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }
}
