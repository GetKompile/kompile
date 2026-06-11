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

import ai.kompile.app.services.agent.KompileLocalModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KompileLocalModelControllerTest {

    @Mock
    private KompileLocalModelService localModelService;

    private KompileLocalModelController controller;

    @BeforeEach
    void setUp() {
        controller = new KompileLocalModelController(localModelService);
    }

    @Test
    void getStatus_delegatesToServiceAndReturnsOk() {
        Map<String, Object> statusMap = Map.of("connected", true, "url", "http://localhost:8080");
        when(localModelService.getStatus()).thenReturn(statusMap);

        ResponseEntity<Map<String, Object>> response = controller.getStatus();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(statusMap, response.getBody());
        verify(localModelService).getStatus();
    }

    @Test
    void discover_delegatesToServiceAndReturnsOk() {
        Map<String, Object> result = Map.of("success", true, "discovered", 2);
        when(localModelService.discoverAndRegister()).thenReturn(result);

        ResponseEntity<Map<String, Object>> response = controller.discover();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(result, response.getBody());
        verify(localModelService).discoverAndRegister();
    }

    @Test
    void connect_withValidUrl_delegatesToServiceAndReturnsOk() {
        Map<String, String> body = new HashMap<>();
        body.put("stagingUrl", "http://localhost:9090");
        Map<String, Object> result = Map.of("success", true);
        when(localModelService.connectTo("http://localhost:9090")).thenReturn(result);

        ResponseEntity<Map<String, Object>> response = controller.connect(body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(result, response.getBody());
        verify(localModelService).connectTo("http://localhost:9090");
    }

    @Test
    void connect_withNullUrl_returnsBadRequest() {
        Map<String, String> body = new HashMap<>();
        body.put("stagingUrl", null);

        ResponseEntity<Map<String, Object>> response = controller.connect(body);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(false, response.getBody().get("success"));
        verify(localModelService, never()).connectTo(any());
    }

    @Test
    void connect_withBlankUrl_returnsBadRequest() {
        Map<String, String> body = new HashMap<>();
        body.put("stagingUrl", "   ");

        ResponseEntity<Map<String, Object>> response = controller.connect(body);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(false, response.getBody().get("success"));
        verify(localModelService, never()).connectTo(any());
    }

    @Test
    void connect_withMissingStagingUrlKey_returnsBadRequest() {
        Map<String, String> body = new HashMap<>();
        // no "stagingUrl" key

        ResponseEntity<Map<String, Object>> response = controller.connect(body);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(localModelService, never()).connectTo(any());
    }

    @Test
    void disconnect_callsServiceAndReturnsOk() {
        doNothing().when(localModelService).disconnect();

        ResponseEntity<Map<String, Object>> response = controller.disconnect();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().get("success"));
        assertEquals("Disconnected", response.getBody().get("message"));
        verify(localModelService).disconnect();
    }
}
