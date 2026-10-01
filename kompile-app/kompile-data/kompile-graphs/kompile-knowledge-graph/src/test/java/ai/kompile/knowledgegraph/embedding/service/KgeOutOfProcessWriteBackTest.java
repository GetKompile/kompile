/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.embedding.service;

import ai.kompile.core.kgembedding.KGEmbeddingAlgorithm;
import ai.kompile.core.kgembedding.KGEmbeddingConfig;
import ai.kompile.core.kgembedding.KGEmbeddingModel;
import ai.kompile.core.kgembedding.KgeTrainingExecutor;
import ai.kompile.core.kgembedding.KgeTrainingExecutor.KgeTrainingResult;
import ai.kompile.core.kgembedding.Triple;
import ai.kompile.knowledgegraph.embedding.adapter.KgEmbeddingGraphAdapter;
import ai.kompile.knowledgegraph.embedding.domain.KGEmbeddingJob;
import ai.kompile.knowledgegraph.embedding.domain.KGEmbeddingJob.JobStatus;
import ai.kompile.knowledgegraph.embedding.repository.KGEmbeddingJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An out-of-process KGE run hands back the file it trained into, not a model. Both job paths must
 * write that file's vectors to the store the triples came from, report the run's own counts, and
 * delete the file whether or not the write succeeds.
 *
 * <p>No ND4J: the vectors stay float arrays all the way to the (mocked) store.</p>
 */
class KgeOutOfProcessWriteBackTest {

    private static final Long FACT_SHEET_ID = 7L;
    private static final String JOB_ID = "oop-write-back-job";
    private static final String TRAINED =
            "{\"entities\":{\"a\":[1.0,2.0],\"b\":[3.0,4.0]},\"relations\":{\"R\":[5.0,6.0]}}";
    private static final Map<String, float[]> TRAINED_ENTITIES =
            Map.of("a", new float[]{1f, 2f}, "b", new float[]{3f, 4f});
    private static final Map<String, float[]> TRAINED_RELATIONS = Map.of("R", new float[]{5f, 6f});
    private static final KGEmbeddingConfig CONFIG = KGEmbeddingConfig.builder()
            .embeddingDim(2).epochs(3).learningRate(0.01).batchSize(4).margin(1.0).negativeSamples(1).build();
    private static final List<Triple> TRIPLES = List.of(new Triple("a", "R", "b"));

    @TempDir
    Path tmp;

    private KGEmbeddingJobRepository repository;
    private KGEmbeddingStorageService storage;
    private KgeTrainingExecutor executor;
    private KgEmbeddingGraphAdapter adapter;
    private KGEmbeddingJobService service;
    private Path trainedFile;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(KGEmbeddingJobRepository.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        storage = mock(KGEmbeddingStorageService.class);
        when(storage.extractTriples(FACT_SHEET_ID)).thenReturn(TRIPLES);
        executor = mock(KgeTrainingExecutor.class);
        adapter = mock(KgEmbeddingGraphAdapter.class);
        when(adapter.priority()).thenReturn(10);
        when(adapter.storeType()).thenReturn("matrix");
        when(adapter.hasGraphData(FACT_SHEET_ID)).thenReturn(true);
        when(adapter.extractTriples(FACT_SHEET_ID)).thenReturn(TRIPLES);
        when(adapter.loadEmbeddings(FACT_SHEET_ID)).thenReturn(Map.of());
        when(adapter.loadRelationEmbeddings(FACT_SHEET_ID)).thenReturn(Map.of());

        service = new KGEmbeddingJobService(repository, storage, null);
        service.graphAdapters = List.of(adapter);
        service.kgeTrainingExecutor = executor;

        trainedFile = Files.writeString(tmp.resolve("trained.json"), TRAINED);
    }

    @Test
    void syncRunStoresTheFileItTrainedIntoAndDeletesIt() {
        runReturns(KgeTrainingResult.success(trainedFile, 2, 1, 0.5));

        KGEmbeddingJob job = trainSynchronously(KGEmbeddingAlgorithm.TRANSE);

        assertStoredThroughTheAdapter(job, KGEmbeddingAlgorithm.TRANSE);
    }

    @Test
    void asyncRunStoresTheFileItTrainedIntoAndDeletesIt() {
        KGEmbeddingJob job = KGEmbeddingJob.builder()
                .jobId(JOB_ID)
                .factSheetId(FACT_SHEET_ID)
                .algorithm(KGEmbeddingAlgorithm.ROTATE)
                .status(JobStatus.PENDING)
                .createdAt(Instant.now())
                .build();
        when(repository.findByJobId(JOB_ID)).thenReturn(Optional.of(job));
        runReturns(KgeTrainingResult.success(trainedFile, 2, 1, 0.5));

        service.executeTrainingAsync(JOB_ID, FACT_SHEET_ID, KGEmbeddingAlgorithm.ROTATE, CONFIG);

        assertStoredThroughTheAdapter(job, KGEmbeddingAlgorithm.ROTATE);
    }

    @Test
    void withoutAnAdapterTheStorageServiceStoresTheFile() {
        service.graphAdapters = null;
        runReturns(KgeTrainingResult.success(trainedFile, 2, 1, 0.5));

        KGEmbeddingJob job = trainSynchronously(KGEmbeddingAlgorithm.ROTATE);

        assertEquals(JobStatus.COMPLETED, job.getStatus(), job.getErrorMessage());
        ArgumentCaptor<Map<String, float[]>> entities = vectors();
        ArgumentCaptor<Map<String, float[]>> relations = vectors();
        verify(storage).storeEmbeddings(entities.capture(), relations.capture(),
                eq(KGEmbeddingAlgorithm.ROTATE), eq(FACT_SHEET_ID), eq(job.getEmbeddingVersion()));
        assertVectors(TRAINED_ENTITIES, entities.getValue());
        assertVectors(TRAINED_RELATIONS, relations.getValue());
        verify(storage, never()).storeEmbeddings(any(KGEmbeddingModel.class), any(), any());
        assertFalse(Files.exists(trainedFile), "the job deletes the trained file once it has stored it");
    }

    @Test
    void aFailedWriteFailsTheJobAndStillDeletesTheFile() {
        runReturns(KgeTrainingResult.success(trainedFile, 2, 1, 0.5));
        when(adapter.storeEmbeddings(anyMap(), anyMap(), any(), any(), any()))
                .thenThrow(new IllegalStateException("store is down"));

        KGEmbeddingJob job = trainSynchronously(KGEmbeddingAlgorithm.TRANSE);

        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals("store is down", job.getErrorMessage());
        assertFalse(Files.exists(trainedFile), "a failed write must not leave the trained file behind");
    }

    @Test
    void successWithoutEmbeddingsFailsTheJob() {
        runReturns(new KgeTrainingResult(true, null, 0.5, null, null, 0, 0));

        KGEmbeddingJob job = trainSynchronously(KGEmbeddingAlgorithm.TRANSE);

        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals("Out-of-process KGE training reported success without any embeddings", job.getErrorMessage());
        verify(adapter, never()).storeEmbeddings(anyMap(), anyMap(), any(), any(), any());
        verify(adapter, never()).storeEmbeddings(any(KGEmbeddingModel.class), any(), any());
    }

    @Test
    void aModelBackedResultIsStillStoredAsAModel() {
        KGEmbeddingModel model = mock(KGEmbeddingModel.class);
        when(model.getEntityCount()).thenReturn(2);
        when(model.getRelationCount()).thenReturn(1);
        runReturns(KgeTrainingResult.success(model, 0.5));

        KGEmbeddingJob job = trainSynchronously(KGEmbeddingAlgorithm.TRANSE);

        assertEquals(JobStatus.COMPLETED, job.getStatus(), job.getErrorMessage());
        assertEquals(2, job.getEntitiesEmbedded().intValue());
        assertEquals(1, job.getRelationsEmbedded().intValue());
        verify(adapter).storeEmbeddings(model, FACT_SHEET_ID, job.getEmbeddingVersion());
        verify(adapter, never()).storeEmbeddings(anyMap(), anyMap(), any(), any(), any());
    }

    private void assertStoredThroughTheAdapter(KGEmbeddingJob job, KGEmbeddingAlgorithm algorithm) {
        assertEquals(JobStatus.COMPLETED, job.getStatus(), job.getErrorMessage());
        assertEquals(2, job.getEntitiesEmbedded().intValue());
        assertEquals(1, job.getRelationsEmbedded().intValue());
        assertEquals(0.5, job.getCurrentLoss().doubleValue());
        ArgumentCaptor<Map<String, float[]>> entities = vectors();
        ArgumentCaptor<Map<String, float[]>> relations = vectors();
        verify(adapter).storeEmbeddings(entities.capture(), relations.capture(),
                eq(algorithm), eq(FACT_SHEET_ID), eq(job.getEmbeddingVersion()));
        assertVectors(TRAINED_ENTITIES, entities.getValue());
        assertVectors(TRAINED_RELATIONS, relations.getValue());
        verify(adapter, never()).storeEmbeddings(any(KGEmbeddingModel.class), any(), any());
        verify(storage, never()).storeEmbeddings(anyMap(), anyMap(), any(), any(), any());
        assertFalse(Files.exists(trainedFile), "the job deletes the trained file once it has stored it");
    }

    private KGEmbeddingJob trainSynchronously(KGEmbeddingAlgorithm algorithm) {
        return service.trainSynchronously("crawl-1", FACT_SHEET_ID, algorithm, CONFIG, null);
    }

    private void runReturns(KgeTrainingResult result) {
        when(executor.trainOutOfProcess(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(result);
    }

    private static void assertVectors(Map<String, float[]> expected, Map<String, float[]> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((key, vector) -> assertArrayEquals(vector, actual.get(key), key));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Map<String, float[]>> vectors() {
        return ArgumentCaptor.forClass((Class) Map.class);
    }
}
