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

package ai.kompile.knowledgegraph.embedding.impl;

import ai.kompile.core.kgembedding.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TransEModel.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransEModelTest {

    private TransEModel model;

    // Minimal config for fast training in tests
    private static final KGEmbeddingConfig FAST_CONFIG = KGEmbeddingConfig.builder()
            .embeddingDim(10)
            .epochs(2)
            .learningRate(0.01)
            .batchSize(4)
            .margin(1.0)
            .negativeSamples(2)
            .normalizeEntities(true)
            .build();

    private static final List<Triple> SAMPLE_TRIPLES = Arrays.asList(
            new Triple("Alice", "KNOWS", "Bob"),
            new Triple("Bob", "LIKES", "Charlie"),
            new Triple("Alice", "WORKS_WITH", "Charlie"),
            new Triple("Dave", "KNOWS", "Eve"),
            new Triple("Eve", "LIKES", "Alice")
    );

    @BeforeEach
    void setUp() {
        model = new TransEModel();
    }

    @Test
    void getAlgorithmName_returnsTransE() {
        assertEquals("TransE", model.getAlgorithmName());
    }

    @Test
    void getAlgorithm_returnsTransEEnum() {
        assertEquals(KGEmbeddingAlgorithm.TRANSE, model.getAlgorithm());
    }

    @Test
    void initialState_isNotTrained() {
        assertFalse(model.isTrained());
        assertFalse(model.isTraining());
        assertEquals(0, model.getEntityCount());
        assertEquals(0, model.getRelationCount());
    }

    @Test
    void train_withNullTriples_returnsFailure() {
        TrainingResult result = model.train(null, FAST_CONFIG);
        assertFalse(result.success());
        assertNotNull(result.errorMessage());
    }

    @Test
    void train_withEmptyTriples_returnsFailure() {
        TrainingResult result = model.train(Collections.emptyList(), FAST_CONFIG);
        assertFalse(result.success());
    }

    @Test
    void train_withValidTriples_returnsSuccess() {
        TrainingResult result = model.train(SAMPLE_TRIPLES, FAST_CONFIG);
        assertTrue(result.success(), "Training should succeed. Error: " + result.errorMessage());
        assertTrue(model.isTrained());
    }

    @Test
    void train_populatesEntityAndRelationCounts() {
        model.train(SAMPLE_TRIPLES, FAST_CONFIG);

        // 5 distinct entities: Alice, Bob, Charlie, Dave, Eve
        assertEquals(5, model.getEntityCount());
        // 3 distinct relations: KNOWS, LIKES, WORKS_WITH
        assertEquals(3, model.getRelationCount());
    }

    @Test
    void train_populatesEntityIds() {
        model.train(SAMPLE_TRIPLES, FAST_CONFIG);
        Set<String> entityIds = model.getEntityIds();

        assertTrue(entityIds.contains("Alice"));
        assertTrue(entityIds.contains("Bob"));
        assertTrue(entityIds.contains("Charlie"));
        assertTrue(entityIds.contains("Dave"));
        assertTrue(entityIds.contains("Eve"));
    }

    @Test
    void train_populatesRelationTypes() {
        model.train(SAMPLE_TRIPLES, FAST_CONFIG);
        Set<String> relations = model.getRelationTypes();

        assertTrue(relations.contains("KNOWS"));
        assertTrue(relations.contains("LIKES"));
        assertTrue(relations.contains("WORKS_WITH"));
    }

    @Test
    void train_setsEmbeddingDimension() {
        model.train(SAMPLE_TRIPLES, FAST_CONFIG);
        assertEquals(10, model.getEmbeddingDimension());
    }

    @Test
    void getEntityEmbedding_afterTraining_returnsNonNullArray() {
        model.train(SAMPLE_TRIPLES, FAST_CONFIG);
        INDArray emb = model.getEntityEmbedding("Alice");

        assertNotNull(emb);
        assertEquals(10, emb.columns());
    }

    @Test
    void getEntityEmbedding_forUnknownEntity_returnsNull() {
        model.train(SAMPLE_TRIPLES, FAST_CONFIG);
        INDArray emb = model.getEntityEmbedding("NonExistentEntity");
        assertNull(emb);
    }

    @Test
    void getRelationEmbedding_afterTraining_returnsNonNullArray() {
        model.train(SAMPLE_TRIPLES, FAST_CONFIG);
        INDArray emb = model.getRelationEmbedding("KNOWS");

        assertNotNull(emb);
        assertEquals(10, emb.columns());
    }

    @Test
    void getAllEntityEmbeddings_returnsAllEntities() {
        model.train(SAMPLE_TRIPLES, FAST_CONFIG);
        Map<String, INDArray> embeddings = model.getAllEntityEmbeddings();

        assertEquals(5, embeddings.size());
        assertTrue(embeddings.containsKey("Alice"));
    }

    @Test
    void getAllRelationEmbeddings_returnsAllRelations() {
        model.train(SAMPLE_TRIPLES, FAST_CONFIG);
        Map<String, INDArray> embeddings = model.getAllRelationEmbeddings();

        assertEquals(3, embeddings.size());
        assertTrue(embeddings.containsKey("KNOWS"));
    }

    @Test
    void scoreTriple_forKnownTriple_returnsFiniteScore() {
        model.train(SAMPLE_TRIPLES, FAST_CONFIG);
        double score = model.scoreTriple("Alice", "KNOWS", "Bob");
        assertTrue(Double.isFinite(score));
        assertTrue(score >= 0.0);
    }

    @Test
    void scoreTriple_forUnknownEntity_returnsMaxValue() {
        model.train(SAMPLE_TRIPLES, FAST_CONFIG);
        double score = model.scoreTriple("Unknown", "KNOWS", "Bob");
        assertEquals(Double.MAX_VALUE, score);
    }

    @Test
    void predictTails_returnsNonEmptyList_afterTraining() {
        model.train(SAMPLE_TRIPLES, FAST_CONFIG);
        List<EmbeddingScore> predictions = model.predictTails("Alice", "KNOWS", 3);
        assertFalse(predictions.isEmpty());
        assertTrue(predictions.size() <= 3);
    }

    @Test
    void predictTails_resultsAreRanked() {
        model.train(SAMPLE_TRIPLES, FAST_CONFIG);
        List<EmbeddingScore> predictions = model.predictTails("Alice", "KNOWS", 5);

        for (int i = 0; i < predictions.size(); i++) {
            assertEquals(i + 1, predictions.get(i).rank());
        }
    }

    @Test
    void predictTails_scoresInAscendingOrder() {
        model.train(SAMPLE_TRIPLES, FAST_CONFIG);
        List<EmbeddingScore> predictions = model.predictTails("Alice", "KNOWS", 5);

        for (int i = 1; i < predictions.size(); i++) {
            assertTrue(predictions.get(i).score() >= predictions.get(i - 1).score(),
                    "Scores should be in ascending order (lower = better)");
        }
    }

    @Test
    void predictTails_forUnknownEntity_returnsEmptyList() {
        model.train(SAMPLE_TRIPLES, FAST_CONFIG);
        List<EmbeddingScore> predictions = model.predictTails("Unknown", "KNOWS", 5);
        assertTrue(predictions.isEmpty());
    }

    @Test
    void predictHeads_returnsNonEmptyList() {
        model.train(SAMPLE_TRIPLES, FAST_CONFIG);
        List<EmbeddingScore> predictions = model.predictHeads("KNOWS", "Bob", 3);
        assertFalse(predictions.isEmpty());
    }

    @Test
    void predictRelations_returnsNonEmptyList() {
        model.train(SAMPLE_TRIPLES, FAST_CONFIG);
        List<EmbeddingScore> predictions = model.predictRelations("Alice", "Bob", 3);
        assertFalse(predictions.isEmpty());
    }

    @Test
    void findSimilarEntities_returnsTopKEntities() {
        model.train(SAMPLE_TRIPLES, FAST_CONFIG);
        List<EmbeddingScore> similar = model.findSimilarEntities("Alice", 3);
        assertFalse(similar.isEmpty());
        assertTrue(similar.size() <= 3);
        // Should not include the entity itself
        assertTrue(similar.stream().noneMatch(s -> s.entity().equals("Alice")));
    }

    @Test
    void entitySimilarity_returnsBetweenMinusOneAndOne() {
        model.train(SAMPLE_TRIPLES, FAST_CONFIG);
        double sim = model.entitySimilarity("Alice", "Bob");
        assertTrue(sim >= -1.0 && sim <= 1.0, "Cosine similarity should be in [-1, 1], got " + sim);
    }

    @Test
    void entitySimilarity_forUnknownEntity_returnsZero() {
        model.train(SAMPLE_TRIPLES, FAST_CONFIG);
        double sim = model.entitySimilarity("Unknown", "Bob");
        assertEquals(0.0, sim);
    }

    @Test
    void cancelTraining_setsFlag() {
        // cancelTraining should be safe to call even without active training
        assertDoesNotThrow(() -> model.cancelTraining());
    }

    @Test
    void importEntityEmbeddings_populatesModel() {
        Map<String, INDArray> embeddings = new HashMap<>();
        embeddings.put("Alice", Nd4j.rand(1, 10));
        embeddings.put("Bob", Nd4j.rand(1, 10));

        model.importEntityEmbeddings(embeddings);

        assertTrue(model.isTrained());
        assertEquals(2, model.getEntityCount());
        assertNotNull(model.getEntityEmbedding("Alice"));
    }

    @Test
    void importRelationEmbeddings_populatesRelations() {
        // First import entities (required for embeddingDim)
        Map<String, INDArray> entityEmbeddings = new HashMap<>();
        entityEmbeddings.put("Alice", Nd4j.rand(1, 10));
        model.importEntityEmbeddings(entityEmbeddings);

        Map<String, INDArray> relEmbeddings = new HashMap<>();
        relEmbeddings.put("KNOWS", Nd4j.rand(1, 10));
        model.importRelationEmbeddings(relEmbeddings);

        assertEquals(1, model.getRelationCount());
        assertNotNull(model.getRelationEmbedding("KNOWS"));
    }

    @Test
    void scoreTriples_returnsCorrectNumberOfScores() {
        model.train(SAMPLE_TRIPLES, FAST_CONFIG);
        double[] scores = model.scoreTriples(SAMPLE_TRIPLES);
        assertEquals(SAMPLE_TRIPLES.size(), scores.length);
    }

    @Test
    void close_doesNotThrow() throws Exception {
        model.train(SAMPLE_TRIPLES, FAST_CONFIG);
        assertDoesNotThrow(() -> model.close());
    }
}
