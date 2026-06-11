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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link VlmPipelineConfigController}.
 *
 * Note: VlmPipelineConfigController uses a static VlmPipelineRegistry.getInstance()
 * which is not easily mockable. We test only the endpoints that don't require
 * specific registry state.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VlmPipelineConfigControllerTest {

    private VlmPipelineConfigController controller;

    @BeforeEach
    void setUp() {
        controller = new VlmPipelineConfigController();
    }

    @Test
    void listPipelines_returnsOk() {
        ResponseEntity<?> response = controller.listPipelines(false);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void getStats_returnsOk() {
        ResponseEntity<Map<String, Object>> response = controller.getStats();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void listModelSets_returnsOk() {
        ResponseEntity<?> response = controller.listModelSets(false);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }
}
