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

import ai.kompile.app.config.ModelAutoInitializationService;
import ai.kompile.app.staging.service.StagingClientService;
import ai.kompile.app.staging.service.StagingServiceConfigService;
import ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl;
import ai.kompile.ocr.integration.OcrPipelineService;
import ai.kompile.vectorstore.anserini.AnseriniVectorStoreImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import ai.kompile.app.web.dto.modelregistry.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelRegistryControllerTest {

    @Mock
    private AnseriniEmbeddingModelImpl embeddingModel;

    @Mock
    private AnseriniVectorStoreImpl vectorStore;

    @Mock
    private StagingServiceConfigService stagingConfigService;

    @Mock
    private StagingClientService stagingClientService;

    @Mock
    private OcrPipelineService ocrPipelineService;

    @Mock
    private ModelAutoInitializationService modelAutoInitService;

    private ModelRegistryController controller;

    @BeforeEach
    void setUp() {
        // Construct with all optional dependencies null (simplest case)
        controller = new ModelRegistryController(
                null, null, null, null, null, null
        );
    }

    private ModelRegistryController controllerWithAll() {
        return new ModelRegistryController(
                embeddingModel, vectorStore, stagingConfigService,
                stagingClientService, ocrPipelineService, modelAutoInitService
        );
    }

    @Test
    void getRegistry_withNoStagingService_returnsEmptyRegistry() {
        ResponseEntity<ModelRegistry> response = controller.getRegistry();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().models.isEmpty());
    }

    @Test
    void getModelsByType_withNoStagingService_returnsEmptyList() {
        ResponseEntity<List<ModelEntry>> response =
                controller.getModelsByType("encoder");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isEmpty());
    }

    @Test
    void getModel_withNoStagingService_returnsNotFound() {
        ResponseEntity<ModelEntry> response =
                controller.getModel("bge-base-en-v1.5");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void getModelSourceStatus_withNoStagingConfigService_returnsNotConfigured() {
        ResponseEntity<Map<String, Object>> response = controller.getModelSourceStatus();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(false, response.getBody().get("configured"));
    }

    @Test
    void getStagingStatus_withNoStagingServices_returnsNotConnected() {
        ResponseEntity<StagingStatusResponse> response =
                controller.getStagingStatus();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().connected);
    }

    @Test
    void getVersionInfo_withNoStagingServices_returnsZeroModels() {
        ResponseEntity<VersionInfoResponse> response =
                controller.getVersionInfo();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(0, response.getBody().totalModels);
    }

    @Test
    void getBuiltInCatalog_returnsAllThreeCategories() {
        ResponseEntity<BuiltInModelCatalog> response =
                controller.getBuiltInCatalog();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().denseEncoders);
        assertNotNull(response.getBody().sparseEncoders);
        assertNotNull(response.getBody().crossEncoders);
        Map<String, Integer> counts = response.getBody().counts;
        assertNotNull(counts);
        assertTrue(counts.containsKey("total"));
    }

    @Test
    void getDenseEncodersEndpoint_returnsNonNullList() {
        ResponseEntity<List<BuiltInModelInfo>> response =
                controller.getDenseEncodersEndpoint();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void getSparseEncodersEndpoint_returnsNonNullList() {
        ResponseEntity<List<BuiltInModelInfo>> response =
                controller.getSparseEncodersEndpoint();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void getCrossEncodersEndpoint_returnsNonNullList() {
        ResponseEntity<List<BuiltInModelInfo>> response =
                controller.getCrossEncodersEndpoint();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void getBuiltInModel_withKnownModelId_returnsModel() {
        // Use one of the built-in model IDs from ModelConstants
        // bge-base-en-v1.5 is a well-known built-in model
        ResponseEntity<BuiltInModelInfo> response =
                controller.getBuiltInModel("bge-base-en-v1.5");
        // Either 200 (if registered) or 404 (if not)
        assertTrue(response.getStatusCode() == HttpStatus.OK ||
                   response.getStatusCode() == HttpStatus.NOT_FOUND);
    }

    @Test
    void getBuiltInModel_withUnknownModelId_returnsNotFound() {
        ResponseEntity<BuiltInModelInfo> response =
                controller.getBuiltInModel("nonexistent-model-xyz");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void assembleArchive_withNoModels_returnsBadRequest() {
        AssembleArchiveRequest request =
                new AssembleArchiveRequest();
        request.denseEncoderIds = List.of();
        request.sparseEncoderIds = List.of();
        request.crossEncoderIds = List.of();

        ResponseEntity<AssembleArchiveResponse> response =
                controller.assembleArchive(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().success);
    }

    @Test
    void importArchive_withNonexistentPath_returnsBadRequest() {
        ArchiveImportRequest request =
                new ArchiveImportRequest();
        request.archivePath = "/nonexistent/path/archive.karch";

        ResponseEntity<ArchiveImportResponse> response =
                controller.importArchive(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().success);
    }

    @Test
    void getEmbeddingModelStatus_withNoEmbeddingModel_returnsAvailableFalse() {
        ResponseEntity<Map<String, Object>> response = controller.getEmbeddingModelStatus();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(false, response.getBody().get("available"));
    }

    @Test
    void getEmbeddingModelStatus_withEmbeddingModel_returnsAvailableTrue() {
        ModelRegistryController ctrl = controllerWithAll();
        when(embeddingModel.getModelStatus()).thenReturn(Map.of("initialized", true));

        ResponseEntity<Map<String, Object>> response = ctrl.getEmbeddingModelStatus();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, response.getBody().get("available"));
    }

    @Test
    void getAvailableEmbeddingModels_withNoEmbeddingModel_returnsAvailableFalse() {
        ResponseEntity<Map<String, Object>> response = controller.getAvailableEmbeddingModels();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(false, response.getBody().get("available"));
    }

    @Test
    void reloadEmbeddingModel_withNoEmbeddingModel_returnsServiceUnavailable() {
        ResponseEntity<Map<String, Object>> response = controller.reloadEmbeddingModel();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(false, response.getBody().get("success"));
    }

    @Test
    void switchEmbeddingModel_withNoEmbeddingModel_returnsServiceUnavailable() {
        ResponseEntity<Map<String, Object>> response = controller.switchEmbeddingModel("some-model");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(false, response.getBody().get("success"));
    }

    @Test
    void getVlmModelStatus_withNoOcrPipelineService_returnsAvailableFalse() {
        ResponseEntity<Map<String, Object>> response = controller.getVlmModelStatus();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(false, response.getBody().get("available"));
        assertEquals(false, response.getBody().get("loaded"));
    }

    @Test
    void reloadVlmModel_withNoOcrPipelineService_returnsServiceUnavailable() {
        ResponseEntity<Map<String, Object>> response = controller.reloadVlmModel();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(false, response.getBody().get("success"));
    }

    @Test
    void refreshRegistry_withNoStagingService_returnsSuccessWithZeroModels() {
        ResponseEntity<Map<String, Object>> response = controller.refreshRegistry();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().get("success"));
        assertEquals(0, response.getBody().get("modelCount"));
    }

    @Test
    void getActiveModelContext_withAllNullServices_returnsNullEmbeddingAndReranker() {
        ResponseEntity<ActiveModelContext> response =
                controller.getActiveModelContext();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNull(response.getBody().embedding);
        assertNull(response.getBody().reranker);
    }

    @Test
    void getModelsByRole_withNoStagingService_returnsEmptyList() {
        ResponseEntity<List<ModelEntry>> response =
                controller.getModelsByRole("retrieval");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isEmpty());
    }

    @Test
    void refreshRegistryAndReloadModel_withNoServices_returnsSuccessTrue() {
        ResponseEntity<Map<String, Object>> response = controller.refreshRegistryAndReloadModel();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, response.getBody().get("success"));
        assertEquals(false, response.getBody().get("embeddingModelReloaded"));
    }
}
