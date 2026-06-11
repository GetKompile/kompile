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

import ai.kompile.app.config.ModelSchedulerConfig;
import ai.kompile.app.services.ContinuousBatcher;
import ai.kompile.app.services.ModelScheduler;
import ai.kompile.app.services.ModelSchedulerConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelSchedulerControllerTest {

    @Mock
    private ModelSchedulerConfigService configService;

    @Mock
    private ModelScheduler modelScheduler;

    @Mock
    private ContinuousBatcher continuousBatcher;

    private ModelSchedulerController controller;

    @BeforeEach
    void setUp() {
        controller = new ModelSchedulerController(configService, modelScheduler, continuousBatcher);
    }

    @Test
    void getConfig_returnsCurrentConfig() {
        ModelSchedulerConfig config = new ModelSchedulerConfig();
        when(configService.getConfiguration()).thenReturn(config);

        ResponseEntity<ModelSchedulerConfig> response = controller.getConfig();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(configService).getConfiguration();
    }

    @Test
    void updateConfig_successfullySavesConfig() throws IOException {
        ModelSchedulerConfig config = new ModelSchedulerConfig();
        doNothing().when(configService).saveConfiguration(config);
        when(configService.getConfiguration()).thenReturn(config);

        ResponseEntity<ModelSchedulerConfig> response = controller.updateConfig(config);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(configService).saveConfiguration(config);
    }

    @Test
    void updateConfig_whenSaveThrows_returnsInternalServerError() throws IOException {
        ModelSchedulerConfig config = new ModelSchedulerConfig();
        doThrow(new IOException("Write failed")).when(configService).saveConfiguration(config);

        ResponseEntity<ModelSchedulerConfig> response = controller.updateConfig(config);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    @Test
    void resetConfig_resetsAndReturnsDefault() throws IOException {
        ModelSchedulerConfig defaults = new ModelSchedulerConfig();
        doNothing().when(configService).resetToDefaults();
        when(configService.getConfiguration()).thenReturn(defaults);

        ResponseEntity<ModelSchedulerConfig> response = controller.resetConfig();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(configService).resetToDefaults();
    }

    @Test
    void resetConfig_whenResetThrows_returnsInternalServerError() throws IOException {
        doThrow(new IOException("Cannot reset")).when(configService).resetToDefaults();

        ResponseEntity<ModelSchedulerConfig> response = controller.resetConfig();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    @Test
    void getStatus_returnsSchedulerAndBatcherStatus() {
        Map<String, Object> schedulerStatus = Map.of("queueSize", 5, "activeWorkers", 2);
        Map<String, Object> batcherStatus = Map.of("pendingBatches", 3);
        when(modelScheduler.getStatus()).thenReturn(schedulerStatus);
        when(continuousBatcher.getStatus()).thenReturn(batcherStatus);

        ResponseEntity<Map<String, Object>> response = controller.getStatus();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(schedulerStatus, response.getBody().get("scheduler"));
        assertEquals(batcherStatus, response.getBody().get("continuousBatcher"));
    }
}
