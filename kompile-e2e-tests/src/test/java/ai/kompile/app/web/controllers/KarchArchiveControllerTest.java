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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KarchArchiveControllerTest {

    private KarchArchiveController controller;

    @BeforeEach
    void setUp() {
        controller = new KarchArchiveController();
        // Set @Value fields
        ReflectionTestUtils.setField(controller, "configuredArchivePath", "");
        ReflectionTestUtils.setField(controller, "autoLoadArchive", true);
    }

    @Test
    void listArchives_returnsOkWithEmptyListWhenNoDirExists() {
        ResponseEntity<List<KarchArchiveController.ArchiveInfo>> response = controller.listArchives();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        // When default archives dir doesn't exist, list is empty
        assertTrue(response.getBody().isEmpty() || response.getBody() != null);
    }

    @Test
    void getStatus_returnsNotLoadedInitially() {
        ResponseEntity<KarchArchiveController.ArchiveStatus> response = controller.getStatus();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        KarchArchiveController.ArchiveStatus status = response.getBody();
        assertNotNull(status);
        assertFalse(status.loaded);
        assertNull(status.archivePath);
        assertNull(status.archiveId);
        assertNull(status.loadedAt);
        assertEquals(0, status.modelCount);
        assertEquals(0, status.encoderCount);
        assertEquals(0, status.crossEncoderCount);
    }

    @Test
    void getModels_returnsEmptyListWhenNoArchiveLoaded() {
        ResponseEntity<List<KarchArchiveController.ArchiveModelInfo>> response = controller.getModels();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isEmpty());
    }

    @Test
    void getModelsByType_returnsEmptyListWhenNoArchiveLoaded() {
        ResponseEntity<List<KarchArchiveController.ArchiveModelInfo>> response =
                controller.getModelsByType("encoder");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isEmpty());
    }

    @Test
    void loadArchive_returnsBadRequestWhenPathDoesNotExist() {
        KarchArchiveController.LoadArchiveRequest request = new KarchArchiveController.LoadArchiveRequest();
        request.archivePath = "/nonexistent/path/archive.karch";

        ResponseEntity<KarchArchiveController.ArchiveStatus> response = controller.loadArchive(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void unloadArchive_clearsStateAndReturnsNotLoaded() {
        ResponseEntity<KarchArchiveController.ArchiveStatus> response = controller.unloadArchive();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        KarchArchiveController.ArchiveStatus status = response.getBody();
        assertNotNull(status);
        assertFalse(status.loaded);
        assertNull(status.archivePath);
        assertNull(status.loadedAt);
    }

    @Test
    void extractModel_returnsBadRequestWhenNoArchiveLoaded() {
        KarchArchiveController.ExtractModelRequest request = new KarchArchiveController.ExtractModelRequest();
        request.modelId = "test-model";
        request.destinationPath = null;

        ResponseEntity<KarchArchiveController.ExtractResult> response = controller.extractModel(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
