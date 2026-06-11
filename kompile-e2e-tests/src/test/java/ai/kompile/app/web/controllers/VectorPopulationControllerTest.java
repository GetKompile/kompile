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

import ai.kompile.app.config.IngestConfiguration;
import ai.kompile.app.ingest.service.IngestEventService;
import ai.kompile.app.services.VectorPopulationProgressTracker;
import ai.kompile.app.services.VectorStorePopulationService;
import ai.kompile.app.services.subprocess.SubprocessRestartManager;
import ai.kompile.app.services.subprocess.VectorPopulationSubprocessLauncher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link VectorPopulationController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VectorPopulationControllerTest {

    @Mock
    private VectorStorePopulationService populationService;

    @Mock
    private VectorPopulationProgressTracker progressTracker;

    @Mock
    private VectorPopulationSubprocessLauncher subprocessLauncher;

    @Mock
    private SubprocessRestartManager restartManager;

    @Mock
    private IngestEventService ingestEventService;

    @Mock
    private IngestConfiguration ingestConfiguration;

    private VectorPopulationController buildController(
            VectorStorePopulationService svc,
            VectorPopulationProgressTracker tracker,
            VectorPopulationSubprocessLauncher launcher,
            SubprocessRestartManager restartMgr,
            IngestEventService ingestSvc,
            IngestConfiguration config) {
        return new VectorPopulationController(svc, tracker, launcher, restartMgr, ingestSvc, config);
    }

    @Test
    void getServiceStatus_allNull_returnsOkWithFalseAvailability() {
        VectorPopulationController controller = buildController(null, null, null, null, null, null);

        ResponseEntity<Map<String, Object>> response = controller.getServiceStatus();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(false, response.getBody().get("populationServiceAvailable"));
        assertEquals(false, response.getBody().get("trackerAvailable"));
        assertEquals(false, response.getBody().get("subprocessLauncherAvailable"));
    }

    @Test
    void getServiceStatus_withServices_returnsOkWithTrueAvailability() {
        when(progressTracker.getActiveTaskCount()).thenReturn(0);
        when(progressTracker.getAllTasks()).thenReturn(Collections.emptyList());
        when(subprocessLauncher.getAllStatuses()).thenReturn(List.of());

        VectorPopulationController controller = buildController(
                populationService, progressTracker, subprocessLauncher, null, null, null);

        ResponseEntity<Map<String, Object>> response = controller.getServiceStatus();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, response.getBody().get("populationServiceAvailable"));
        assertEquals(true, response.getBody().get("trackerAvailable"));
    }

    @Test
    void startPopulation_withoutService_returnsBadRequest() {
        VectorPopulationController controller = buildController(null, null, null, null, null, null);

        ResponseEntity<Map<String, Object>> response = controller.startPopulation();

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(false, response.getBody().get("success"));
    }

    @Test
    void getTaskStatus_withoutService_returnsOkUnavailable() {
        // When populationService is null, controller returns ok with available=false
        VectorPopulationController controller = buildController(null, null, null, null, null, null);

        ResponseEntity<Map<String, Object>> response = controller.getTaskStatus("task-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(false, response.getBody().get("available"));
    }

    @Test
    void getActiveTasks_withService_returnsOk() {
        when(populationService.getActiveTasks()).thenReturn(Collections.emptyMap());

        VectorPopulationController controller = buildController(
                populationService, null, null, null, null, null);

        ResponseEntity<Map<String, Object>> response = controller.getActiveTasks();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void getSummary_withServices_returnsOk() {
        when(progressTracker.getAllTasks()).thenReturn(Collections.emptyList());
        when(subprocessLauncher.getAllStatuses()).thenReturn(List.of());
        when(populationService.getActiveTasks()).thenReturn(Collections.emptyMap());

        VectorPopulationController controller = buildController(
                populationService, progressTracker, subprocessLauncher, null, null, null);

        ResponseEntity<Map<String, Object>> response = controller.getSummary();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }
}
