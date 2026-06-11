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

import ai.kompile.app.services.ScaffoldService;
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
 * Tests for {@link SdkController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SdkControllerTest {

    @Mock
    private ScaffoldService scaffoldService;

    private SdkController controller;

    @BeforeEach
    void setUp() {
        // SdkController accepts null KompileModelManager — creates new one internally
        controller = new SdkController(null, scaffoldService);
    }

    @Test
    void getPlatforms_returnsOk() {
        ResponseEntity<Map<String, Object>> response = controller.getPlatforms();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("basePlatforms"));
        assertTrue(response.getBody().containsKey("mobilePlatforms"));
    }

    @Test
    void listSdks_returnsOk() {
        ResponseEntity<Map<String, Object>> response = controller.listSdks(null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void getStatus_returnsOk() {
        ResponseEntity<Map<String, Object>> response = controller.getStatus();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("cacheDirectory"));
    }

    @Test
    void downloadSdk_missingPlatform_returnsBadRequest() {
        ResponseEntity<?> response = controller.downloadSdk(Map.of());

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void downloadModel_missingModelId_returnsBadRequest() {
        ResponseEntity<?> response = controller.downloadModel(Map.of());

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
