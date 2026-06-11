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

import ai.kompile.app.services.sdx.SdxServingService;
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
import static org.mockito.Mockito.*;

/**
 * Tests for {@link SdxServingController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SdxServingControllerTest {

    @Mock
    private SdxServingService service;

    private SdxServingController controller;

    @BeforeEach
    void setUp() {
        controller = new SdxServingController(service);
    }

    @Test
    void listModels_returnsOk() {
        List<Map<String, Object>> models = List.of(Map.of("id", "model1"));
        when(service.listAvailableModels()).thenReturn(models);

        ResponseEntity<List<Map<String, Object>>> response = controller.listModels();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(models, response.getBody());
    }

    @Test
    void loadModel_success_returnsOk() {
        Map<String, Object> result = Map.of("modelId", "m1", "status", "loaded");
        when(service.loadModel("m1")).thenReturn(result);

        ResponseEntity<Map<String, Object>> response = controller.loadModel("m1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(result, response.getBody());
    }

    @Test
    void loadModel_illegalArgument_returnsBadRequest() {
        when(service.loadModel("bad")).thenThrow(new IllegalArgumentException("not found"));

        ResponseEntity<Map<String, Object>> response = controller.loadModel("bad");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("error", response.getBody().get("status"));
    }

    @Test
    void loadModel_runtimeException_returnsInternalServerError() {
        when(service.loadModel("err")).thenThrow(new RuntimeException("load failed"));

        ResponseEntity<Map<String, Object>> response = controller.loadModel("err");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    @Test
    void unloadModel_returnsOk() {
        when(service.unloadModel("m1")).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.unloadModel("m1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("m1", response.getBody().get("modelId"));
        assertEquals(true, response.getBody().get("unloaded"));
    }

    @Test
    void getSchema_success_returnsOk() {
        Map<String, Object> schema = Map.of("inputs", List.of());
        when(service.getModelSchema("m1")).thenReturn(schema);

        ResponseEntity<Map<String, Object>> response = controller.getSchema("m1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(schema, response.getBody());
    }

    @Test
    void getSchema_notFound_returnsBadRequest() {
        when(service.getModelSchema("missing")).thenThrow(new IllegalArgumentException("not found"));

        ResponseEntity<Map<String, Object>> response = controller.getSchema("missing");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void infer_success_returnsOk() {
        Map<String, Object> input = Map.of("text", "hello");
        Map<String, Object> output = Map.of("result", "world");
        when(service.infer("m1", input)).thenReturn(output);

        ResponseEntity<Map<String, Object>> response = controller.infer("m1", input);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(output, response.getBody());
    }

    @Test
    void getInputTemplate_success_returnsOk() {
        Map<String, Object> template = Map.of("text", "");
        when(service.getInputTemplate("m1")).thenReturn(template);

        ResponseEntity<Map<String, Object>> response = controller.getInputTemplate("m1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(template, response.getBody());
    }

    @Test
    void getStatus_returnsOk() {
        Map<String, Object> status = Map.of("status", "running");
        when(service.getStatus()).thenReturn(status);

        ResponseEntity<Map<String, Object>> response = controller.getStatus();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(status, response.getBody());
    }
}
