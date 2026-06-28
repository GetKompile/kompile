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
package ai.kompile.knowledgegraph.embedding;

import ai.kompile.core.kgembedding.KGEmbeddingAlgorithm;
import ai.kompile.core.kgembedding.KGEmbeddingConfig;
import ai.kompile.core.kgembedding.KGEmbeddingModel;
import ai.kompile.core.kgembedding.Triple;
import ai.kompile.knowledgegraph.embedding.adapter.KgEmbeddingGraphAdapter;
import ai.kompile.knowledgegraph.embedding.domain.KGEmbeddingJob;
import ai.kompile.knowledgegraph.embedding.domain.KGEmbeddingJob.JobStatus;
import ai.kompile.knowledgegraph.embedding.repository.KGEmbeddingJobRepository;
import ai.kompile.knowledgegraph.embedding.service.KGEmbeddingJobService;
import ai.kompile.knowledgegraph.embedding.service.KGEmbeddingStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link KGEmbeddingJobService#executeTrainingAsync} warm-starts from prior
 * embeddings when a {@link KgEmbeddingGraphAdapter} returns non-empty embedding maps.
 *
 * <p>Before the fix, {@code executeTrainingAsync} always called {@code createModel(algorithm)}
 * (cold-start) without loading or importing prior embeddings, so every UI-triggered KGE training
 * run discarded the embeddings from the previous crawl and re-trained from random init.
 * After the fix, both {@code executeTrainingAsync} and {@code trainSynchronously} share the
 * {@code prepareModel()} helper which loads + seeds prior embeddings before training.</p>
 *
 * <p>Test approach (deterministic):
 * <ol>
 *   <li>Mock the graph adapter to return a known unit-norm seed vector {@code [0.5,0.5,0.5,0.5]}
 *       for entities "Alice" and "Bob".</li>
 *   <li>Call {@code executeTrainingAsync()} directly (no Spring context → @Async ignored → runs
 *       inline synchronously on the calling thread).</li>
 *   <li>Capture the {@link KGEmbeddingModel} that the adapter's {@code storeEmbeddings} receives
 *       after training completes.</li>
 *   <li>Assert the captured model's embeddings for Alice and Bob match the imported seed
 *       (training uses {@code learningRate=0} so gradient steps are no-ops).</li>
 * </ol>
 *
 * <p>A cold-started model would produce random unit-vectors ≠ [0.5,0.5,0.5,0.5], proving the
 * warm-start test is non-trivially asserting the seeding path.</p>
 *
 * <p>No Spring context, no subprocess required.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KgeAsyncWarmStartTest {

    private static final Long FACT_SHEET_ID = 42L;
    private static final String JOB_ID = "async-warm-start-test-job";

    // Tiny zero-lr config: gradient steps are no-ops so the seed is preserved exactly.
    private static final KGEmbeddingConfig ZERO_LR_CONFIG = KGEmbeddingConfig.builder()
            .embeddingDim(4)
            .epochs(1)
            .learningRate(0.0)
            .batchSize(8)
            .margin(1.0)
            .negativeSamples(1)
            .normalizeEntities(true)
            .build();

    private static final List<Triple> TWO_TRIPLES = List.of(
            new Triple("Alice", "KNOWS", "Bob"),
            new Triple("Bob",   "LIKES", "Alice")
    );

    /**
     * {@code executeTrainingAsync} must seed the trained model from the adapter's prior embeddings,
     * not cold-start from random initialisation.
     *
     * <p>This is the Fix 2 contract: the async path (UI-triggered training) is now equivalent to
     * the sync path (crawl-triggered training) with respect to warm-starting.</p>
     */
    @Test
    void executeTrainingAsync_warmStart_seedsFromPriorEmbeddings() throws Exception {
        // ── dim=4 unit-norm seed: ||[0.5,0.5,0.5,0.5]|| == 1.0, so normalization is a no-op
        float[] seedValues = {0.5f, 0.5f, 0.5f, 0.5f};
        INDArray aliceSeed = Nd4j.create(seedValues).reshape(1, 4);
        INDArray bobSeed   = Nd4j.create(seedValues).reshape(1, 4);

        Map<String, INDArray> priorEmbeddings = new HashMap<>();
        priorEmbeddings.put("Alice", aliceSeed);
        priorEmbeddings.put("Bob",   bobSeed);

        // ── Mock adapter: returns two triples + the prior embeddings ─────────────
        KgEmbeddingGraphAdapter mockAdapter = mock(KgEmbeddingGraphAdapter.class);
        when(mockAdapter.priority()).thenReturn(10);
        when(mockAdapter.hasGraphData(FACT_SHEET_ID)).thenReturn(true);
        when(mockAdapter.extractTriples(FACT_SHEET_ID)).thenReturn(TWO_TRIPLES);
        when(mockAdapter.loadEmbeddings(FACT_SHEET_ID)).thenReturn(priorEmbeddings);
        when(mockAdapter.loadRelationEmbeddings(FACT_SHEET_ID)).thenReturn(Collections.emptyMap());

        // Capture the trained model when the adapter's storeEmbeddings is called
        AtomicReference<KGEmbeddingModel> storedModel = new AtomicReference<>();
        doAnswer(inv -> {
            storedModel.set(inv.getArgument(0));
            return 2; // number of embeddings stored
        }).when(mockAdapter).storeEmbeddings(any(KGEmbeddingModel.class), anyLong(), anyLong());

        // ── Mock job repository + storage service ───────────────────────────────
        KGEmbeddingJob job = KGEmbeddingJob.builder()
                .jobId(JOB_ID)
                .factSheetId(FACT_SHEET_ID)
                .algorithm(KGEmbeddingAlgorithm.TRANSE)
                .status(JobStatus.PENDING)
                .embeddingDim(4)
                .epochs(1)
                .learningRate(0.0)
                .batchSize(8)
                .margin(1.0)
                .negativeSamples(1)
                .createdAt(Instant.now())
                .build();

        KGEmbeddingJobRepository mockRepo = mock(KGEmbeddingJobRepository.class);
        when(mockRepo.findByJobId(JOB_ID)).thenReturn(Optional.of(job));
        // save() must return the passed-in job so status updates propagate
        when(mockRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        KGEmbeddingStorageService mockStorage = mock(KGEmbeddingStorageService.class);

        // ── Wire the service without Spring ──────────────────────────────────────
        KGEmbeddingJobService service = new KGEmbeddingJobService(mockRepo, mockStorage, null);
        injectField(service, "graphAdapters", List.of(mockAdapter));
        // kgeTrainingExecutor stays null → forces the in-JVM path that now uses prepareModel()

        // ── Exercise ─────────────────────────────────────────────────────────────
        // @Async is ignored when called directly (no Spring proxy) → runs synchronously
        service.executeTrainingAsync(JOB_ID, FACT_SHEET_ID, KGEmbeddingAlgorithm.TRANSE, ZERO_LR_CONFIG);

        // ── Assert: job COMPLETED ─────────────────────────────────────────────────
        assertEquals(JobStatus.COMPLETED, job.getStatus(),
                "Async training job must COMPLETED when warm-start succeeds. "
                + "FAILED means prepareModel() or seeding threw an exception.");

        // ── Assert: warm-start seeding preserved the prior vectors ────────────────
        KGEmbeddingModel trained = storedModel.get();
        assertNotNull(trained,
                "adapter.storeEmbeddings must have been called — model was trained successfully");

        INDArray aliceAfter = trained.getEntityEmbedding("Alice");
        assertNotNull(aliceAfter, "Alice must be present in the trained model");
        assertArrayEquals(seedValues, aliceAfter.toFloatVector(), 1e-5f,
                "executeTrainingAsync (in-JVM) must seed Alice's embedding from the prior vector "
                + "(warm-start), not cold-start from random. If this fails, prepareModel() is not "
                + "being called or importEntityEmbeddings() was not invoked.");

        INDArray bobAfter = trained.getEntityEmbedding("Bob");
        assertNotNull(bobAfter, "Bob must be present in the trained model");
        assertArrayEquals(seedValues, bobAfter.toFloatVector(), 1e-5f,
                "executeTrainingAsync (in-JVM) must seed Bob's embedding from the prior vector (warm-start)");
    }

    /**
     * When the adapter returns empty prior embeddings (first crawl), {@code executeTrainingAsync}
     * must still COMPLETE successfully (cold-start path via {@code prepareModel()}).
     */
    @Test
    void executeTrainingAsync_coldStart_whenNoPriorEmbeddings_completesSuccessfully() throws Exception {
        KgEmbeddingGraphAdapter mockAdapter = mock(KgEmbeddingGraphAdapter.class);
        when(mockAdapter.priority()).thenReturn(10);
        when(mockAdapter.hasGraphData(FACT_SHEET_ID)).thenReturn(true);
        when(mockAdapter.extractTriples(FACT_SHEET_ID)).thenReturn(TWO_TRIPLES);
        when(mockAdapter.loadEmbeddings(FACT_SHEET_ID)).thenReturn(Collections.emptyMap()); // cold start
        when(mockAdapter.loadRelationEmbeddings(FACT_SHEET_ID)).thenReturn(Collections.emptyMap());
        when(mockAdapter.storeEmbeddings(any(), anyLong(), anyLong())).thenReturn(2);

        KGEmbeddingJob job = KGEmbeddingJob.builder()
                .jobId(JOB_ID)
                .factSheetId(FACT_SHEET_ID)
                .algorithm(KGEmbeddingAlgorithm.TRANSE)
                .status(JobStatus.PENDING)
                .embeddingDim(4)
                .epochs(2)
                .learningRate(0.01)
                .batchSize(4)
                .margin(1.0)
                .negativeSamples(1)
                .createdAt(Instant.now())
                .build();

        KGEmbeddingJobRepository mockRepo = mock(KGEmbeddingJobRepository.class);
        when(mockRepo.findByJobId(JOB_ID)).thenReturn(Optional.of(job));
        when(mockRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        KGEmbeddingStorageService mockStorage = mock(KGEmbeddingStorageService.class);

        KGEmbeddingJobService service = new KGEmbeddingJobService(mockRepo, mockStorage, null);
        injectField(service, "graphAdapters", List.of(mockAdapter));

        service.executeTrainingAsync(JOB_ID, FACT_SHEET_ID, KGEmbeddingAlgorithm.TRANSE,
                KGEmbeddingConfig.builder().embeddingDim(4).epochs(2).learningRate(0.01)
                        .batchSize(4).margin(1.0).negativeSamples(1).normalizeEntities(true).build());

        assertEquals(JobStatus.COMPLETED, job.getStatus(),
                "Cold-start async training must COMPLETE when no prior embeddings exist");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Inject a value into a private field via reflection (no Spring wiring needed). */
    private static void injectField(Object target, String fieldName, Object value) throws Exception {
        Field f = findField(target.getClass(), fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) {
                return findField(clazz.getSuperclass(), name);
            }
            throw e;
        }
    }
}
