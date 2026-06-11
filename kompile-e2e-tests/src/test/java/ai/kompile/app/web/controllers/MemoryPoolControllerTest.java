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

import ai.kompile.app.config.MemoryPoolConfig;
import ai.kompile.app.services.MemoryPoolManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MemoryPoolControllerTest {

    @Mock
    private MemoryPoolManager memoryPoolManager;

    private MemoryPoolController controller;

    @BeforeEach
    void setUp() {
        controller = new MemoryPoolController();
        ReflectionTestUtils.setField(controller, "memoryPoolManager", memoryPoolManager);
    }

    @Test
    void getConfig_returnsCurrentConfig() {
        MemoryPoolConfig config = new MemoryPoolConfig();
        when(memoryPoolManager.getConfiguration()).thenReturn(config);

        ResponseEntity<MemoryPoolConfig> response = controller.getConfig();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(memoryPoolManager).getConfiguration();
    }

    @Test
    void updateConfig_successfullySavesAndReturnsUpdated() throws IOException {
        MemoryPoolConfig config = new MemoryPoolConfig();
        MemoryPoolConfig updated = new MemoryPoolConfig();
        doNothing().when(memoryPoolManager).saveConfiguration(config);
        when(memoryPoolManager.getConfiguration()).thenReturn(updated);

        ResponseEntity<Map<String, Object>> response = controller.updateConfig(config);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("updated", response.getBody().get("status"));
        assertEquals(updated, response.getBody().get("config"));
    }

    @Test
    void updateConfig_whenSaveThrows_returnsInternalServerError() throws IOException {
        MemoryPoolConfig config = new MemoryPoolConfig();
        doThrow(new IOException("Disk error")).when(memoryPoolManager).saveConfiguration(config);

        ResponseEntity<Map<String, Object>> response = controller.updateConfig(config);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("error"));
    }

    @Test
    void resetConfig_successfullyResetsAndReturnsDefault() throws IOException {
        MemoryPoolConfig defaults = new MemoryPoolConfig();
        doNothing().when(memoryPoolManager).resetToDefaults();
        when(memoryPoolManager.getConfiguration()).thenReturn(defaults);

        ResponseEntity<Map<String, Object>> response = controller.resetConfig();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("reset", response.getBody().get("status"));
    }

    @Test
    void resetConfig_whenResetThrows_returnsInternalServerError() throws IOException {
        doThrow(new IOException("Cannot reset")).when(memoryPoolManager).resetToDefaults();

        ResponseEntity<Map<String, Object>> response = controller.resetConfig();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertTrue(response.getBody().containsKey("error"));
    }

    @Test
    void getStatus_returnsStatusFromManager() {
        Map<String, Object> status = Map.of("gpuUsedBytes", 1024L, "gpuFreeBytes", 8192L);
        when(memoryPoolManager.getStatus()).thenReturn(status);

        ResponseEntity<Map<String, Object>> response = controller.getStatus();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(status, response.getBody());
    }
}
