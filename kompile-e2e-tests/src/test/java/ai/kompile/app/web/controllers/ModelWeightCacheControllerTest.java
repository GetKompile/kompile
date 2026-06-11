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

import ai.kompile.app.config.ModelWeightCacheConfig;
import ai.kompile.app.services.ModelWeightCache;
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
class ModelWeightCacheControllerTest {

    @Mock
    private ModelWeightCache weightCache;

    private ModelWeightCacheController controller;

    @BeforeEach
    void setUp() {
        controller = new ModelWeightCacheController();
        ReflectionTestUtils.setField(controller, "weightCache", weightCache);
    }

    @Test
    void getConfig_returnsCurrentConfig() {
        ModelWeightCacheConfig config = new ModelWeightCacheConfig();
        when(weightCache.getConfiguration()).thenReturn(config);

        ResponseEntity<ModelWeightCacheConfig> response = controller.getConfig();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void updateConfig_successfullySavesAndReturnsUpdated() throws IOException {
        ModelWeightCacheConfig config = new ModelWeightCacheConfig();
        ModelWeightCacheConfig updated = new ModelWeightCacheConfig();
        doNothing().when(weightCache).saveConfiguration(config);
        when(weightCache.getConfiguration()).thenReturn(updated);

        ResponseEntity<Map<String, Object>> response = controller.updateConfig(config);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("updated", response.getBody().get("status"));
        assertEquals(updated, response.getBody().get("config"));
    }

    @Test
    void updateConfig_whenSaveThrows_returnsInternalServerError() throws IOException {
        ModelWeightCacheConfig config = new ModelWeightCacheConfig();
        doThrow(new IOException("Disk full")).when(weightCache).saveConfiguration(config);

        ResponseEntity<Map<String, Object>> response = controller.updateConfig(config);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertTrue(response.getBody().containsKey("error"));
    }

    @Test
    void getStatus_returnsStatusFromCache() {
        Map<String, Object> status = Map.of("gpuLayers", 32, "cpuLayers", 8);
        when(weightCache.getStatus()).thenReturn(status);

        ResponseEntity<Map<String, Object>> response = controller.getStatus();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(status, response.getBody());
    }

    @Test
    void demote_withoutLayerName_demotesAllLayersAndReturnsOk() {
        doNothing().when(weightCache).demoteAllToHost("model-xyz");

        ResponseEntity<Map<String, Object>> response = controller.demote("model-xyz", null);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("demoted", response.getBody().get("status"));
        assertEquals("model-xyz", response.getBody().get("modelId"));
        verify(weightCache).demoteAllToHost("model-xyz");
        verify(weightCache, never()).demoteToHost(any(), any());
    }

    @Test
    void demote_withLayerName_demotesSingleLayerAndReturnsOk() {
        doNothing().when(weightCache).demoteToHost("model-xyz", "layer-4");

        ResponseEntity<Map<String, Object>> response = controller.demote("model-xyz", "layer-4");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("demoted", response.getBody().get("status"));
        verify(weightCache).demoteToHost("model-xyz", "layer-4");
    }

    @Test
    void demote_whenExceptionThrown_returnsInternalServerError() {
        doThrow(new RuntimeException("Cannot demote")).when(weightCache).demoteAllToHost("model-xyz");

        ResponseEntity<Map<String, Object>> response = controller.demote("model-xyz", null);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertTrue(response.getBody().containsKey("error"));
    }

    @Test
    void promote_withoutLayerName_promotesAllLayersAndReturnsOk() {
        doNothing().when(weightCache).promoteAllToGpu("model-xyz");

        ResponseEntity<Map<String, Object>> response = controller.promote("model-xyz", null);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("promoted", response.getBody().get("status"));
        verify(weightCache).promoteAllToGpu("model-xyz");
        verify(weightCache, never()).promoteToGpu(any(), any());
    }

    @Test
    void promote_withLayerName_promotesSingleLayerAndReturnsOk() {
        doNothing().when(weightCache).promoteToGpu("model-xyz", "layer-2");

        ResponseEntity<Map<String, Object>> response = controller.promote("model-xyz", "layer-2");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("promoted", response.getBody().get("status"));
        verify(weightCache).promoteToGpu("model-xyz", "layer-2");
    }

    @Test
    void promote_whenExceptionThrown_returnsInternalServerError() {
        doThrow(new RuntimeException("Cannot promote")).when(weightCache).promoteAllToGpu("model-xyz");

        ResponseEntity<Map<String, Object>> response = controller.promote("model-xyz", null);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }
}
