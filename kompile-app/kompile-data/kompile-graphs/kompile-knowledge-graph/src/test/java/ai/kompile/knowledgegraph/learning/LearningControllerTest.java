/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.knowledgegraph.learning;

import ai.kompile.core.kgembedding.KGEmbeddingAlgorithm;
import ai.kompile.core.kgembedding.KGEmbeddingConfig;
import ai.kompile.knowledgegraph.embedding.domain.KGEmbeddingJob;
import ai.kompile.knowledgegraph.embedding.service.KGEmbeddingJobService;
import ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator;
import ai.kompile.knowledgegraph.reasoning.MebnTheoryRegistrationService;
import ai.kompile.knowledgegraph.reasoning.RegroundResult;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.ResponseEntity;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LearningControllerTest {

    @Test
    void completesKgeBeforeRegisteringFreshGraphAndRunningReasoning() {
        KGEmbeddingJobService kge = mock(KGEmbeddingJobService.class);
        IncrementalReasoningOrchestrator orchestrator =
                mock(IncrementalReasoningOrchestrator.class);
        MebnTheoryRegistrationService registration =
                mock(MebnTheoryRegistrationService.class);
        LearningController controller = new LearningController(kge, orchestrator, registration);

        KGEmbeddingJob completed = KGEmbeddingJob.builder()
                .jobId("kge-1")
                .factSheetId(42L)
                .status(KGEmbeddingJob.JobStatus.COMPLETED)
                .build();
        when(kge.trainSynchronously(
                anyString(), eq(42L), eq(KGEmbeddingAlgorithm.TRANSE),
                any(KGEmbeddingConfig.class), isNull()))
                .thenReturn(completed);
        when(registration.registerMTheoryForFactSheet(42L)).thenReturn(2);
        when(orchestrator.runFullReground(42L))
                .thenReturn(new RegroundResult(3, "reground-1", Set.of()));

        ResponseEntity<LearningController.LearningJobResponse> response =
                controller.triggerLearning(42L, "transe");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("COMPLETED", response.getBody().status());
        assertEquals(3, response.getBody().pslVersionsWritten());
        assertEquals(2, response.getBody().mebnMFragsRegistered());

        InOrder order = inOrder(kge, registration, orchestrator);
        order.verify(kge).trainSynchronously(
                anyString(), eq(42L), eq(KGEmbeddingAlgorithm.TRANSE),
                any(KGEmbeddingConfig.class), isNull());
        order.verify(registration).registerMTheoryForFactSheet(42L);
        order.verify(orchestrator).runFullReground(42L);
    }

    @Test
    void failedKgeSkipsStructuralLearners() {
        KGEmbeddingJobService kge = mock(KGEmbeddingJobService.class);
        IncrementalReasoningOrchestrator orchestrator =
                mock(IncrementalReasoningOrchestrator.class);
        MebnTheoryRegistrationService registration =
                mock(MebnTheoryRegistrationService.class);
        LearningController controller = new LearningController(kge, orchestrator, registration);

        KGEmbeddingJob failed = KGEmbeddingJob.builder()
                .jobId("kge-2")
                .factSheetId(42L)
                .status(KGEmbeddingJob.JobStatus.FAILED)
                .errorMessage("training failed")
                .build();
        when(kge.trainSynchronously(
                anyString(), eq(42L), eq(KGEmbeddingAlgorithm.ROTATE),
                any(KGEmbeddingConfig.class), isNull()))
                .thenReturn(failed);

        ResponseEntity<LearningController.LearningJobResponse> response =
                controller.triggerLearning(42L, "rotate");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("FAILED", response.getBody().status());
        assertEquals(0, response.getBody().pslVersionsWritten());
        assertEquals(0, response.getBody().mebnMFragsRegistered());
        verifyNoInteractions(registration, orchestrator);
    }
}
