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

import ai.kompile.app.services.Nd4jEnvironmentConfigService;
import ai.kompile.app.services.PipelineConfigService;
import ai.kompile.app.services.subprocess.SubprocessConfigService;
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

/**
 * Tests for {@link AutoConfigureController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutoConfigureControllerTest {

    @Mock
    private SubprocessConfigService subprocessConfigService;

    @Mock
    private Nd4jEnvironmentConfigService nd4jConfigService;

    @Mock
    private PipelineConfigService pipelineConfigService;

    private AutoConfigureController controller;
    private AutoConfigureController controllerNoServices;

    @BeforeEach
    void setUp() {
        controller = new AutoConfigureController(
                subprocessConfigService, nd4jConfigService, pipelineConfigService);
        controllerNoServices = new AutoConfigureController(null, null, null);
    }

    @Test
    void detect_withDefaultEmbedding_returnsOk() {
        ResponseEntity<Map<String, Object>> response = controller.detect(true);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("hardware"));
        assertTrue(response.getBody().containsKey("recommended"));
        assertTrue(response.getBody().containsKey("note"));
    }

    @Test
    void detect_withoutLocalEmbedding_returnsOk() {
        ResponseEntity<Map<String, Object>> response = controller.detect(false);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("hardware"));
    }

    @Test
    void apply_nullRequest_usesDefaults_returnsOk() {
        ResponseEntity<Map<String, Object>> response = controller.apply(null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("hardware"));
    }

    @Test
    void apply_withRequest_returnsOk() {
        AutoConfigureController.AutoConfigureRequest request =
                new AutoConfigureController.AutoConfigureRequest();
        request.hasLocalEmbedding = false;

        ResponseEntity<Map<String, Object>> response = controller.apply(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void apply_noServices_returnsOkWithHardware() {
        ResponseEntity<Map<String, Object>> response = controllerNoServices.apply(null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("hardware"));
    }
}
