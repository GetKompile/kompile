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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SystemInfoController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemInfoControllerTest {

    private SystemInfoController controller;

    @BeforeEach
    void setUp() {
        controller = new SystemInfoController();
    }

    @Test
    void getSystemInfo_returnsOk() {
        ResponseEntity<Map<String, Object>> response = controller.getSystemInfo();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("version"));
    }

    @Test
    void health_returnsUp() {
        ResponseEntity<Map<String, Object>> response = controller.getHealth();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().get("status"));
    }

    @Test
    void getComponents_returnsOk() {
        ResponseEntity<List<Map<String, Object>>> response = controller.getComponents();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void startComponent_unknownComponent_returnsBadRequest() {
        ResponseEntity<Map<String, Object>> response = controller.startComponent("nonexistent-component", null);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void stopComponent_unknownComponent_returnsBadRequest() {
        ResponseEntity<Map<String, Object>> response = controller.stopComponent("nonexistent-component");
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void restartComponent_unknownComponent_returnsBadRequest() {
        ResponseEntity<Map<String, Object>> response = controller.restartComponent("nonexistent-component");
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
