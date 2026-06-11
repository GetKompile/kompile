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

import ai.kompile.query.transformer.QueryTransformerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QueryTransformerConfigControllerTest {

    private QueryTransformerProperties properties;
    private QueryTransformerConfigController controller;
    private QueryTransformerConfigController controllerWithNull;

    @BeforeEach
    void setUp() {
        properties = new QueryTransformerProperties();
        properties.setType("passthrough");
        properties.setEnabled(true);
        properties.setMaxQueries(3);
        properties.setIncludeOriginal(true);
        properties.setTemperature(0.7);
        properties.setMaxTokens(512);

        controller = new QueryTransformerConfigController(properties);
        controllerWithNull = new QueryTransformerConfigController(null);
    }

    // ── getConfig ─────────────────────────────────────────────────────────

    @Test
    void getConfig_withProperties_returnsConfig() {
        ResponseEntity<Map<String, Object>> resp = controller.getConfig();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(true, resp.getBody().get("available"));
        assertEquals("passthrough", resp.getBody().get("type"));
        assertEquals(true, resp.getBody().get("enabled"));
    }

    @Test
    void getConfig_nullProperties_returnsUnavailable() {
        ResponseEntity<Map<String, Object>> resp = controllerWithNull.getConfig();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(false, resp.getBody().get("available"));
    }

    // ── updateConfig ──────────────────────────────────────────────────────

    @Test
    void updateConfig_nullProperties_returns503() {
        ResponseEntity<Map<String, Object>> resp = controllerWithNull.updateConfig(Map.of("enabled", false));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
    }

    @Test
    void updateConfig_updatesEnabled() {
        Map<String, Object> req = new HashMap<>();
        req.put("enabled", false);

        ResponseEntity<Map<String, Object>> resp = controller.updateConfig(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(false, resp.getBody().get("enabled"));
    }

    @Test
    void updateConfig_updatesType() {
        Map<String, Object> req = new HashMap<>();
        req.put("type", "compression");

        ResponseEntity<Map<String, Object>> resp = controller.updateConfig(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("compression", resp.getBody().get("type"));
    }

    @Test
    void updateConfig_updatesMaxQueries() {
        Map<String, Object> req = new HashMap<>();
        req.put("maxQueries", 5);

        ResponseEntity<Map<String, Object>> resp = controller.updateConfig(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(5, resp.getBody().get("maxQueries"));
    }

    // ── toggleQueryTransformer ────────────────────────────────────────────

    @Test
    void toggleQueryTransformer_nullProperties_returns503() {
        ResponseEntity<Map<String, Object>> resp = controllerWithNull.toggleQueryTransformer(Map.of("enabled", false));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
    }

    @Test
    void toggleQueryTransformer_setsEnabled() {
        ResponseEntity<Map<String, Object>> resp = controller.toggleQueryTransformer(Map.of("enabled", false));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(true, resp.getBody().get("success"));
        assertEquals(false, resp.getBody().get("enabled"));
    }

    // ── setTransformerType ────────────────────────────────────────────────

    @Test
    void setTransformerType_nullProperties_returns503() {
        ResponseEntity<Map<String, Object>> resp = controllerWithNull.setTransformerType("passthrough");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
    }

    @Test
    void setTransformerType_validType_returns200() {
        ResponseEntity<Map<String, Object>> resp = controller.setTransformerType("expansion");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(true, resp.getBody().get("success"));
        assertEquals("expansion", resp.getBody().get("type"));
    }

    @Test
    void setTransformerType_invalidType_returns400() {
        ResponseEntity<Map<String, Object>> resp = controller.setTransformerType("invalid-type");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals(false, resp.getBody().get("success"));
    }

    // ── getTransformerTypes ───────────────────────────────────────────────

    @Test
    void getTransformerTypes_returnsList() {
        ResponseEntity<List<Map<String, Object>>> resp = controller.getTransformerTypes();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertFalse(resp.getBody().isEmpty());
        assertTrue(resp.getBody().stream().anyMatch(t -> "passthrough".equals(t.get("type"))));
    }

    // ── applyPreset ───────────────────────────────────────────────────────

    @Test
    void applyPreset_nullProperties_returns503() {
        ResponseEntity<Map<String, Object>> resp = controllerWithNull.applyPreset("simple");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
    }

    @Test
    void applyPreset_simple_appliesPassthrough() {
        ResponseEntity<Map<String, Object>> resp = controller.applyPreset("simple");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("passthrough", resp.getBody().get("type"));
    }

    @Test
    void applyPreset_conversational_appliesCompression() {
        ResponseEntity<Map<String, Object>> resp = controller.applyPreset("conversational");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("compression", resp.getBody().get("type"));
    }

    @Test
    void applyPreset_unknown_returns400() {
        ResponseEntity<Map<String, Object>> resp = controller.applyPreset("unknown-preset");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals(false, resp.getBody().get("success"));
    }

    // ── getPresets ────────────────────────────────────────────────────────

    @Test
    void getPresets_returnsList() {
        ResponseEntity<List<Map<String, Object>>> resp = controller.getPresets();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertFalse(resp.getBody().isEmpty());
        assertTrue(resp.getBody().stream().anyMatch(p -> "simple".equals(p.get("preset"))));
    }
}
