/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.ocr.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OcrDebugControllerSplitRoutingTest {

    @Test
    void modelInventoryComesFromManagedStagingRegistryAndFiltersToOcrVlmTypes() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        Map<String, Object> models = new LinkedHashMap<>();
        models.put("vision-model", Map.of(
                "model_id", "vision-model",
                "type", "vlm_pipeline",
                "status", "active",
                "path", "vlm/vision-model",
                "metadata", Map.of("description", "Vision model", "framework", "samediff")));
        models.put("embedding-model", Map.of(
                "model_id", "embedding-model",
                "type", "dense_encoder",
                "status", "active",
                "path", "encoders/embedding-model"));
        when(restTemplate.getForEntity(
                "http://localhost:19090/api/staging/registry", Map.class))
                .thenReturn(ResponseEntity.ok(Map.of("models", models)));

        OcrDebugController controller = new OcrDebugController(restTemplate);
        controller.setStagingBaseUrl("http://localhost:19090/");

        ResponseEntity<List<Map<String, Object>>> response = controller.listModels();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        assertEquals("vision-model", response.getBody().get(0).get("modelId"));
        assertEquals("VLM_PIPELINE", response.getBody().get(0).get("type"));
        assertEquals("ACTIVE", response.getBody().get(0).get("status"));
        assertEquals("Vision model", response.getBody().get(0).get("description"));
        verify(restTemplate).getForEntity(
                "http://localhost:19090/api/staging/registry", Map.class);
    }
}
