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

package ai.kompile.app.services;

import ai.kompile.app.config.ModelAutoInitializationService;
import ai.kompile.app.staging.domain.StagingServiceConfig;
import ai.kompile.app.staging.service.StagingServiceConfigService;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.embeddings.NoOpEmbeddingModelImpl;
import ai.kompile.core.embeddings.NoOpVectorStoreImpl;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SetupStatusService}.
 * Verifies the 5-step setup status computation logic.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SetupStatusServiceTest {

    @Mock
    private StagingServerLifecycleService stagingServerLifecycleService;

    @Mock
    private StagingServiceConfigService stagingConfigService;

    @Mock
    private ModelAutoInitializationService autoInitService;

    @Mock
    private IndexStatusService indexStatusService;

    @Mock
    private AnseriniEmbeddingModelImpl anseriniEmbeddingModel;

    private SetupStatusService service;

    @BeforeEach
    void setUp() {
        // Default mocks: staging server running, no config, no index
        when(stagingServerLifecycleService.getStatus()).thenReturn(
                StagingServerLifecycleService.StagingServerStatus.builder()
                        .componentId("kompile-model-staging")
                        .status("running")
                        .installed(true)
                        .port(8081)
                        .url("http://localhost:8081")
                        .build()
        );

        when(stagingConfigService.getActiveConfig()).thenReturn(Optional.empty());

        IndexStatusService.IndexStatus indexStatus = new IndexStatusService.IndexStatus();
        indexStatus.setAnyIndexLoaded(false);
        indexStatus.setVectorDocumentCount(0);
        indexStatus.setKeywordDocumentCount(0);
        indexStatus.setAvailableKarchFiles(List.of());
        indexStatus.setAvailableVectorIndices(List.of());
        when(indexStatusService.getStatus()).thenReturn(indexStatus);

        service = new SetupStatusService(
                stagingServerLifecycleService,
                stagingConfigService,
                autoInitService,
                indexStatusService,
                List.of(anseriniEmbeddingModel),
                List.of(new NoOpVectorStoreImpl())
        );
    }

    @Test
    void testTotalStepsIsFive() {
        SetupStatusService.SetupStatus status = service.getStatus();
        assertEquals(5, status.getTotalSteps());
    }

    @Test
    void testStagingServerStep_running() {
        SetupStatusService.SetupStatus status = service.getStatus();
        assertNotNull(status.getStagingServer());
        assertEquals(1, status.getStagingServer().getStepNumber());
        assertEquals("Staging Server", status.getStagingServer().getName());
        assertTrue(status.getStagingServer().isComplete());
        assertEquals(SetupStatusService.StepState.COMPLETE, status.getStagingServer().getStatus());
    }

    @Test
    void testStagingServerStep_stopped() {
        when(stagingServerLifecycleService.getStatus()).thenReturn(
                StagingServerLifecycleService.StagingServerStatus.builder()
                        .componentId("kompile-model-staging")
                        .status("stopped")
                        .installed(true)
                        .port(8081)
                        .build()
        );

        SetupStatusService.SetupStatus status = service.getStatus();
        assertFalse(status.getStagingServer().isComplete());
        assertEquals(SetupStatusService.StepState.NOT_STARTED, status.getStagingServer().getStatus());
    }

    @Test
    void testStagingServerStep_notInstalled() {
        when(stagingServerLifecycleService.getStatus()).thenReturn(
                StagingServerLifecycleService.StagingServerStatus.builder()
                        .componentId("kompile-model-staging")
                        .status("not_installed")
                        .installed(false)
                        .port(8081)
                        .build()
        );

        SetupStatusService.SetupStatus status = service.getStatus();
        assertFalse(status.getStagingServer().isComplete());
        assertEquals(SetupStatusService.StepState.NOT_STARTED, status.getStagingServer().getStatus());
        assertTrue(status.getStagingServer().getAction().contains("install"));
    }

    @Test
    void testStagingServerStep_starting() {
        when(stagingServerLifecycleService.getStatus()).thenReturn(
                StagingServerLifecycleService.StagingServerStatus.builder()
                        .componentId("kompile-model-staging")
                        .status("starting")
                        .installed(true)
                        .port(8081)
                        .pid(12345L)
                        .build()
        );

        SetupStatusService.SetupStatus status = service.getStatus();
        assertFalse(status.getStagingServer().isComplete());
        assertEquals(SetupStatusService.StepState.IN_PROGRESS, status.getStagingServer().getStatus());
    }

    @Test
    void testModelSourceStep_noConfig() {
        SetupStatusService.SetupStatus status = service.getStatus();
        assertNotNull(status.getModelSource());
        assertEquals(2, status.getModelSource().getStepNumber());
        assertEquals("Model Source", status.getModelSource().getName());
        assertFalse(status.getModelSource().isComplete());
    }

    @Test
    void testModelSourceStep_configuredAndVerified() {
        StagingServiceConfig config = new StagingServiceConfig();
        config.setName("test-staging");
        config.setEndpointUrl("http://localhost:8081");
        config.setVerified(true);
        when(stagingConfigService.getActiveConfig()).thenReturn(Optional.of(config));

        SetupStatusService.SetupStatus status = service.getStatus();
        assertTrue(status.getModelSource().isComplete());
        assertEquals(SetupStatusService.StepState.COMPLETE, status.getModelSource().getStatus());
    }

    @Test
    void testModelSourceStep_configuredNotVerified() {
        StagingServiceConfig config = new StagingServiceConfig();
        config.setName("test-staging");
        config.setEndpointUrl("http://localhost:8081");
        config.setVerified(false);
        when(stagingConfigService.getActiveConfig()).thenReturn(Optional.of(config));

        SetupStatusService.SetupStatus status = service.getStatus();
        assertTrue(status.getModelSource().isComplete()); // configured counts as done
        assertEquals(SetupStatusService.StepState.WARNING, status.getModelSource().getStatus());
    }

    @Test
    void testEmbeddingModelStep_noModel() {
        // Recreate with NoOp embedding
        service = new SetupStatusService(
                stagingServerLifecycleService,
                stagingConfigService,
                autoInitService,
                indexStatusService,
                List.of(new NoOpEmbeddingModelImpl()),
                List.of(new NoOpVectorStoreImpl())
        );

        SetupStatusService.SetupStatus status = service.getStatus();
        assertNotNull(status.getEmbeddingModel());
        assertEquals(3, status.getEmbeddingModel().getStepNumber());
        assertFalse(status.getEmbeddingModel().isComplete());
        assertEquals(SetupStatusService.StepState.NOT_STARTED, status.getEmbeddingModel().getStatus());
    }

    @Test
    void testEmbeddingModelStep_initialized() {
        when(anseriniEmbeddingModel.isInitialized()).thenReturn(true);
        when(anseriniEmbeddingModel.getActiveModelId()).thenReturn("bge-base-en-v1.5");
        when(anseriniEmbeddingModel.dimensions()).thenReturn(768);

        SetupStatusService.SetupStatus status = service.getStatus();
        assertTrue(status.getEmbeddingModel().isComplete());
        assertEquals(SetupStatusService.StepState.COMPLETE, status.getEmbeddingModel().getStatus());
        assertTrue(status.getEmbeddingModel().getMessage().contains("bge-base-en-v1.5"));
    }

    @Test
    void testEmbeddingModelStep_loading() {
        when(anseriniEmbeddingModel.isInitialized()).thenReturn(false);
        when(autoInitService.isEmbeddingInitialized()).thenReturn(false);

        SetupStatusService.SetupStatus status = service.getStatus();
        assertFalse(status.getEmbeddingModel().isComplete());
        assertEquals(SetupStatusService.StepState.IN_PROGRESS, status.getEmbeddingModel().getStatus());
    }

    @Test
    void testIndexingStep_noDocuments() {
        SetupStatusService.SetupStatus status = service.getStatus();
        assertNotNull(status.getIndexing());
        assertEquals(4, status.getIndexing().getStepNumber());
        assertFalse(status.getIndexing().isComplete());
        assertEquals(SetupStatusService.StepState.NOT_STARTED, status.getIndexing().getStatus());
    }

    @Test
    void testIndexingStep_documentsIndexed() {
        IndexStatusService.IndexStatus indexStatus = new IndexStatusService.IndexStatus();
        indexStatus.setAnyIndexLoaded(true);
        indexStatus.setVectorDocumentCount(100);
        indexStatus.setKeywordDocumentCount(50);
        indexStatus.setAvailableKarchFiles(List.of());
        indexStatus.setAvailableVectorIndices(List.of());
        when(indexStatusService.getStatus()).thenReturn(indexStatus);

        SetupStatusService.SetupStatus status = service.getStatus();
        assertTrue(status.getIndexing().isComplete());
        assertEquals(SetupStatusService.StepState.COMPLETE, status.getIndexing().getStatus());
        assertTrue(status.getIndexing().getMessage().contains("150"));
    }

    @Test
    void testSearchReadyStep_allComplete() {
        // Setup all prerequisites
        StagingServiceConfig config = new StagingServiceConfig();
        config.setName("test");
        config.setEndpointUrl("http://localhost:8081");
        config.setVerified(true);
        when(stagingConfigService.getActiveConfig()).thenReturn(Optional.of(config));

        when(anseriniEmbeddingModel.isInitialized()).thenReturn(true);
        when(anseriniEmbeddingModel.getActiveModelId()).thenReturn("bge-base-en-v1.5");
        when(anseriniEmbeddingModel.dimensions()).thenReturn(768);

        IndexStatusService.IndexStatus indexStatus = new IndexStatusService.IndexStatus();
        indexStatus.setAnyIndexLoaded(true);
        indexStatus.setVectorDocumentCount(100);
        indexStatus.setKeywordDocumentCount(50);
        indexStatus.setAvailableKarchFiles(List.of());
        indexStatus.setAvailableVectorIndices(List.of());
        when(indexStatusService.getStatus()).thenReturn(indexStatus);

        SetupStatusService.SetupStatus status = service.getStatus();
        assertNotNull(status.getSearchReady());
        assertEquals(5, status.getSearchReady().getStepNumber());
        assertTrue(status.getSearchReady().isComplete());
        assertEquals(SetupStatusService.StepState.COMPLETE, status.getSearchReady().getStatus());
    }

    @Test
    void testSearchReadyStep_notReady() {
        // When embedding model is loading (IN_PROGRESS), searchReady mirrors that
        when(anseriniEmbeddingModel.isInitialized()).thenReturn(false);
        when(autoInitService.isEmbeddingInitialized()).thenReturn(false);

        SetupStatusService.SetupStatus status = service.getStatus();
        assertFalse(status.getSearchReady().isComplete());
        // searchReady propagates IN_PROGRESS from embedding step
        assertTrue(status.getSearchReady().getStatus() == SetupStatusService.StepState.NOT_STARTED
                || status.getSearchReady().getStatus() == SetupStatusService.StepState.IN_PROGRESS);
    }

    @Test
    void testSetupComplete_allDone() {
        // All steps complete
        StagingServiceConfig config = new StagingServiceConfig();
        config.setName("test");
        config.setEndpointUrl("http://localhost:8081");
        config.setVerified(true);
        when(stagingConfigService.getActiveConfig()).thenReturn(Optional.of(config));

        when(anseriniEmbeddingModel.isInitialized()).thenReturn(true);
        when(anseriniEmbeddingModel.getActiveModelId()).thenReturn("bge-base-en-v1.5");
        when(anseriniEmbeddingModel.dimensions()).thenReturn(768);

        IndexStatusService.IndexStatus indexStatus = new IndexStatusService.IndexStatus();
        indexStatus.setAnyIndexLoaded(true);
        indexStatus.setVectorDocumentCount(100);
        indexStatus.setKeywordDocumentCount(50);
        indexStatus.setAvailableKarchFiles(List.of());
        indexStatus.setAvailableVectorIndices(List.of());
        when(indexStatusService.getStatus()).thenReturn(indexStatus);

        SetupStatusService.SetupStatus status = service.getStatus();
        assertTrue(status.isSetupComplete());
    }

    @Test
    void testSetupComplete_notDone() {
        SetupStatusService.SetupStatus status = service.getStatus();
        assertFalse(status.isSetupComplete());
    }

    @Test
    void testCurrentStep_progression() {
        // Nothing complete except staging server -> current step should be 2
        SetupStatusService.SetupStatus status = service.getStatus();
        assertEquals(2, status.getCurrentStep());
    }

    @Test
    void testCurrentStep_firstStepIncomplete() {
        when(stagingServerLifecycleService.getStatus()).thenReturn(
                StagingServerLifecycleService.StagingServerStatus.builder()
                        .componentId("kompile-model-staging")
                        .status("stopped")
                        .installed(true)
                        .port(8081)
                        .build()
        );

        SetupStatusService.SetupStatus status = service.getStatus();
        assertEquals(1, status.getCurrentStep());
    }

    @Test
    void testWizardDismissal() {
        assertFalse(service.getStatus().isWizardDismissed());

        service.dismissWizard();
        assertTrue(service.getStatus().isWizardDismissed());

        service.resetWizardDismissed();
        assertFalse(service.getStatus().isWizardDismissed());
    }

    @Test
    void testNullDependencies_gracefulHandling() {
        // All null dependencies — service should not NPE
        SetupStatusService nullService = new SetupStatusService(
                null, null, null, null, null, null
        );

        SetupStatusService.SetupStatus status = nullService.getStatus();
        assertNotNull(status);
        assertEquals(5, status.getTotalSteps());
        // Staging server step should still handle null gracefully
        assertNotNull(status.getStagingServer());
    }
}
