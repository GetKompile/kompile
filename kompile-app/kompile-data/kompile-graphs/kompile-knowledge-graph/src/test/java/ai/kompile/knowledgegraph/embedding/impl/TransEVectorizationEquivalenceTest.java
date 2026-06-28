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

import ai.kompile.core.kgembedding.Triple;
import org.junit.jupiter.api.Test;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.ops.transforms.Transforms;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that the vectorized {@code TransEModel.trainBatch} produces results
 * numerically equivalent (within 1e-5) to the original scalar per-triple loop.
 *
 * <p>The scalar oracle is a verbatim copy of the old implementation placed inside
 * this test. Both start from <em>identical</em> initial embeddings derived from a
 * fixed seed and operate on the same positives/negatives list. The test compares
 * the resulting entity embeddings, relation embeddings, and returned loss value.
 */
class TransEVectorizationEquivalenceTest {

    private static final double TOLERANCE = 1e-5;
    private static final int    DIM       = 4;
    private static final double LR        = 0.1;
    private static final double MARGIN    = 1.0;

    // -----------------------------------------------------------------------
    // Scalar oracle (verbatim copy of the old private trainBatch logic).
    // Operates directly on the supplied embedding INDArrays (in-place).
    // Returns totalLoss / positives.size().
    // -----------------------------------------------------------------------
    private static double scalarTrainBatch(
            INDArray entityEmb,          // [numEntities, dim]  — mutated in-place
            INDArray relationEmb,         // [numRelations, dim] — mutated in-place
            Map<String, Integer> entityToIndex,
            Map<String, Integer> relationToIndex,
            List<Triple> positives,
            List<Triple> negatives,
            double learningRate,
            double margin) {

        double totalLoss = 0.0;
        int negPerPos = negatives.size() / positives.size();

        for (int i = 0; i < positives.size(); i++) {
            Triple pos = positives.get(i);

            Integer hIdx = entityToIndex.get(pos.head());
            Integer rIdx = relationToIndex.get(pos.relation());
            Integer tIdx = entityToIndex.get(pos.tail());
            if (hIdx == null || rIdx == null || tIdx == null) continue;

            INDArray h = entityEmb.getRow(hIdx).dup();
            INDArray r = relationEmb.getRow(rIdx).dup();
            INDArray t = entityEmb.getRow(tIdx).dup();

            // posScore = ||h + r - t||_2
            INDArray posDiffScalar = h.add(r).sub(t);
            double posScore = posDiffScalar.norm2Number().doubleValue();

            for (int j = 0; j < negPerPos; j++) {
                Triple neg = negatives.get(i * negPerPos + j);
                Integer hNegIdx = entityToIndex.get(neg.head());
                Integer tNegIdx = entityToIndex.get(neg.tail());
                if (hNegIdx == null || tNegIdx == null) continue;

                INDArray hNeg = entityEmb.getRow(hNegIdx).dup();
                INDArray tNeg = entityEmb.getRow(tNegIdx).dup();

                INDArray negDiffScalar = hNeg.add(r).sub(tNeg);
                double negScore = negDiffScalar.norm2Number().doubleValue();

                double loss = Math.max(0, margin + posScore - negScore);
                if (loss > 0) {
                    totalLoss += loss;

                    INDArray diff = h.add(r).sub(t);
                    INDArray diffNorm = diff.div(diff.norm2Number().doubleValue() + 1e-10);

                    INDArray diffNeg = hNeg.add(r).sub(tNeg);
                    INDArray diffNegNorm = diffNeg.div(diffNeg.norm2Number().doubleValue() + 1e-10);

                    entityEmb.getRow(hIdx).subi(diffNorm.mul(learningRate));
                    entityEmb.getRow(tIdx).addi(diffNorm.mul(learningRate));
                    relationEmb.getRow(rIdx).subi(diffNorm.mul(learningRate));
                    entityEmb.getRow(hNegIdx).addi(diffNegNorm.mul(learningRate));
                    entityEmb.getRow(tNegIdx).subi(diffNegNorm.mul(learningRate));
                }
            }
        }
        return totalLoss / positives.size();
    }

    // -----------------------------------------------------------------------
    // Reflection helpers: set the model's private embedding matrices and maps
    // directly so we can control the exact starting state.
    // -----------------------------------------------------------------------
    private static void setField(Object obj, String name, Object value) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(obj, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T getField(Object obj, String name) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(obj);
    }

    /**
     * Calls TransEModel#trainBatch via reflection.
     *
     * <p>The current production signature is:
     * {@code trainBatch(List, List, double, double, float[][], float[][])}
     *
     * <p>This wrapper:
     * <ol>
     *   <li>Reads the injected INDArray embedding fields to build {@code float[][]} mirrors.</li>
     *   <li>Invokes the 6-arg {@code trainBatch} with those mirrors.</li>
     *   <li>Writes the mutated mirrors back into the INDArray buffers so that field reads
     *       after the call reflect the updated embeddings (matching the production training
     *       loop's epoch-end sync via {@code copyToINDArray}).</li>
     * </ol>
     */
    private static double callTrainBatch(TransEModel model,
                                         List<Triple> positives,
                                         List<Triple> negatives,
                                         double lr, double margin) throws Exception {
        int dim = (int) getField(model, "embeddingDim");

        INDArray entityEmb   = getField(model, "entityEmbeddings");
        INDArray relationEmb = getField(model, "relationEmbeddings");

        int numEntities  = (int) entityEmb.rows();
        int numRelations = (int) relationEmb.rows();

        // Build float[][] mirrors from the current INDArray matrices (same logic as
        // TransEModel.copyFromINDArray — replicated here to avoid calling a private method)
        float[][] entityMirror   = new float[numEntities][dim];
        float[][] relationMirror = new float[numRelations][dim];
        float[] eFlat = entityEmb.data().asFloat();
        float[] rFlat = relationEmb.data().asFloat();
        for (int r = 0; r < numEntities;  r++) System.arraycopy(eFlat, r * dim, entityMirror[r],   0, dim);
        for (int r = 0; r < numRelations; r++) System.arraycopy(rFlat, r * dim, relationMirror[r], 0, dim);

        // Invoke the 6-arg trainBatch
        Method m = TransEModel.class.getDeclaredMethod("trainBatch",
                List.class, List.class, double.class, double.class,
                float[][].class, float[][].class);
        m.setAccessible(true);
        double loss = (Double) m.invoke(model, positives, negatives, lr, margin,
                entityMirror, relationMirror);

        // Sync mirrors → INDArray buffers so assertions on the injected INDArray fields are correct.
        // (Mirrors the production copyToINDArray logic: scalar DataBuffer.put, no ND4J ops.)
        for (int r = 0; r < numEntities; r++)
            for (int c = 0; c < dim; c++)
                entityEmb.data().put((long) r * dim + c, entityMirror[r][c]);
        for (int r = 0; r < numRelations; r++)
            for (int c = 0; c < dim; c++)
                relationEmb.data().put((long) r * dim + c, relationMirror[r][c]);

        return loss;
    }

    // -----------------------------------------------------------------------
    // Test: 5 entities, 2 relations, dim=4, 3 positives × 2 negatives each.
    // -----------------------------------------------------------------------
    @Test
    void trainBatch_vectorized_matchesScalarOracle_withinTolerance() throws Exception {
        // Fixed vocabularies
        // Entities: A=0, B=1, C=2, D=3, E=4
        // Relations: R0=0, R1=1
        Map<String, Integer> entityMap = new HashMap<>();
        entityMap.put("A", 0);
        entityMap.put("B", 1);
        entityMap.put("C", 2);
        entityMap.put("D", 3);
        entityMap.put("E", 4);
        Map<String, Integer> relMap = new HashMap<>();
        relMap.put("R0", 0);
        relMap.put("R1", 1);

        // Fixed initial embeddings via Nd4j.rand with a seed
        Nd4j.getRandom().setSeed(42L);
        INDArray entityEmbSeed   = Nd4j.rand(5, DIM);  // [5,4]
        INDArray relationEmbSeed = Nd4j.rand(2, DIM);  // [2,4]

        // Positives
        List<Triple> positives = new ArrayList<>();
        positives.add(new Triple("A", "R0", "B"));
        positives.add(new Triple("C", "R1", "D"));
        positives.add(new Triple("B", "R0", "E"));

        // Negatives: 2 per positive (total 6)
        List<Triple> negatives = new ArrayList<>();
        // For A-R0-B: corrupt tails
        negatives.add(new Triple("A", "R0", "C"));
        negatives.add(new Triple("A", "R0", "D"));
        // For C-R1-D
        negatives.add(new Triple("C", "R1", "A"));
        negatives.add(new Triple("C", "R1", "E"));
        // For B-R0-E
        negatives.add(new Triple("B", "R0", "C"));
        negatives.add(new Triple("B", "R0", "D"));

        // ---- ORACLE: scalar on a copy ----
        INDArray scalarEntityEmb   = entityEmbSeed.dup();
        INDArray scalarRelationEmb = relationEmbSeed.dup();
        double scalarLoss = scalarTrainBatch(scalarEntityEmb, scalarRelationEmb,
                entityMap, relMap, positives, negatives, LR, MARGIN);

        // ---- VECTORIZED: via TransEModel with reflection ----
        TransEModel model = new TransEModel();

        // Inject vocabulary maps
        List<String> indexToEntity = new ArrayList<>();
        List<String> indexToRelation = new ArrayList<>();
        for (Map.Entry<String, Integer> e : entityMap.entrySet()) {
            while (indexToEntity.size() <= e.getValue()) indexToEntity.add(null);
            indexToEntity.set(e.getValue(), e.getKey());
        }
        for (Map.Entry<String, Integer> e : relMap.entrySet()) {
            while (indexToRelation.size() <= e.getValue()) indexToRelation.add(null);
            indexToRelation.set(e.getValue(), e.getKey());
        }
        setField(model, "entityToIndex",    entityMap);
        setField(model, "relationToIndex",  relMap);
        setField(model, "indexToEntity",    indexToEntity);
        setField(model, "indexToRelation",  indexToRelation);
        setField(model, "embeddingDim",     DIM);

        // Inject SAME initial embeddings (dup so oracle's copies are independent)
        INDArray vectorEntityEmb   = entityEmbSeed.dup();
        INDArray vectorRelationEmb = relationEmbSeed.dup();
        setField(model, "entityEmbeddings",   vectorEntityEmb);
        setField(model, "relationEmbeddings", vectorRelationEmb);

        // Call the new vectorized trainBatch
        double vectorLoss = callTrainBatch(model, positives, negatives, LR, MARGIN);

        // Read back the mutated embedding matrices
        INDArray actualEntityEmb   = getField(model, "entityEmbeddings");
        INDArray actualRelationEmb = getField(model, "relationEmbeddings");

        // ---- Assertions ----
        assertEquals(scalarLoss, vectorLoss, TOLERANCE,
                String.format("Loss mismatch: scalar=%.8f  vector=%.8f", scalarLoss, vectorLoss));

        for (int row = 0; row < 5; row++) {
            for (int col = 0; col < DIM; col++) {
                double exp = scalarEntityEmb.getDouble(row, col);
                double got = actualEntityEmb.getDouble(row, col);
                assertEquals(exp, got, TOLERANCE,
                        String.format("entityEmb[%d,%d]: expected=%.8f  got=%.8f", row, col, exp, got));
            }
        }
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < DIM; col++) {
                double exp = scalarRelationEmb.getDouble(row, col);
                double got = actualRelationEmb.getDouble(row, col);
                assertEquals(exp, got, TOLERANCE,
                        String.format("relationEmb[%d,%d]: expected=%.8f  got=%.8f", row, col, exp, got));
            }
        }
    }

    /**
     * Verifies that when NO pairs have loss>0 (posScore < negScore - margin for all pairs),
     * both oracle and vectorized return 0 loss and leave embeddings unchanged.
     */
    @Test
    void trainBatch_noLossPairs_returnsZeroAndNoUpdate() throws Exception {
        Map<String, Integer> entityMap = new HashMap<>();
        entityMap.put("A", 0); entityMap.put("B", 1); entityMap.put("C", 2);
        Map<String, Integer> relMap = new HashMap<>();
        relMap.put("R0", 0);

        List<String> indexToEntity   = List.of("A", "B", "C");
        List<String> indexToRelation = List.of("R0");

        // Build embeddings where posScore >> negScore so loss = max(0, margin + pos - neg) = 0
        // h=[10,0,0,0], r=[0,0,0,0], t=[10,0,0,0] → posScore=0
        // hNeg=[100,0,0,0], tNeg=[0,0,0,0] → negScore=100
        // loss = max(0, 1 + 0 - 100) = 0
        INDArray entityEmb = Nd4j.zeros(3, DIM);
        entityEmb.putRow(0, Nd4j.create(new double[]{10.0, 0.0, 0.0, 0.0})); // h (A)
        entityEmb.putRow(1, Nd4j.create(new double[]{10.0, 0.0, 0.0, 0.0})); // t (B)
        entityEmb.putRow(2, Nd4j.create(new double[]{100.0, 0.0, 0.0, 0.0})); // hNeg (C)
        INDArray relationEmb = Nd4j.zeros(1, DIM);

        List<Triple> positives = List.of(new Triple("A", "R0", "B"));
        List<Triple> negatives = List.of(new Triple("C", "R0", "B")); // 1 neg per pos

        // Oracle
        INDArray sE = entityEmb.dup();
        INDArray sR = relationEmb.dup();
        double sLoss = scalarTrainBatch(sE, sR, entityMap, relMap, positives, negatives, LR, MARGIN);

        // Vectorized
        TransEModel model = new TransEModel();
        setField(model, "entityToIndex",    entityMap);
        setField(model, "relationToIndex",  relMap);
        setField(model, "indexToEntity",    indexToEntity);
        setField(model, "indexToRelation",  indexToRelation);
        setField(model, "embeddingDim",     DIM);
        INDArray vE = entityEmb.dup();
        INDArray vR = relationEmb.dup();
        setField(model, "entityEmbeddings",   vE);
        setField(model, "relationEmbeddings", vR);
        double vLoss = callTrainBatch(model, positives, negatives, LR, MARGIN);

        assertEquals(sLoss, vLoss, TOLERANCE, "Loss should be 0 when no active pairs");
        assertEquals(0.0, vLoss, TOLERANCE);

        // Embeddings unchanged
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < DIM; c++)
                assertEquals(entityEmb.getDouble(r, c),
                        getField(model, "entityEmbeddings") instanceof INDArray
                                ? ((INDArray) getField(model, "entityEmbeddings")).getDouble(r, c)
                                : Double.NaN,
                        TOLERANCE, "Entity emb should not change");
    }

    /**
     * Verifies repeated-index accumulation: if the same entity appears as head in two
     * different positive triples in the same batch, both contributions must sum (not overwrite).
     */
    @Test
    void trainBatch_repeatedEntityIndex_accumulatesGradients() throws Exception {
        Map<String, Integer> entityMap = new HashMap<>();
        entityMap.put("A", 0); entityMap.put("B", 1); entityMap.put("C", 2); entityMap.put("D", 3);
        Map<String, Integer> relMap = new HashMap<>();
        relMap.put("R0", 0);

        List<String> indexToEntity   = List.of("A", "B", "C", "D");
        List<String> indexToRelation = List.of("R0");

        // Fixed seed embeddings
        Nd4j.getRandom().setSeed(99L);
        INDArray entityEmbSeed   = Nd4j.rand(4, DIM);
        INDArray relationEmbSeed = Nd4j.rand(1, DIM);

        // SAME head entity "A" in two positives → gradient for A must accumulate
        List<Triple> positives = new ArrayList<>();
        positives.add(new Triple("A", "R0", "B"));
        positives.add(new Triple("A", "R0", "C"));   // A appears again as head

        List<Triple> negatives = new ArrayList<>();
        negatives.add(new Triple("C", "R0", "B"));   // neg for first positive
        negatives.add(new Triple("D", "R0", "C"));   // neg for second positive

        INDArray sE = entityEmbSeed.dup();
        INDArray sR = relationEmbSeed.dup();
        double sLoss = scalarTrainBatch(sE, sR, entityMap, relMap, positives, negatives, LR, MARGIN);

        TransEModel model = new TransEModel();
        setField(model, "entityToIndex",    entityMap);
        setField(model, "relationToIndex",  relMap);
        setField(model, "indexToEntity",    indexToEntity);
        setField(model, "indexToRelation",  indexToRelation);
        setField(model, "embeddingDim",     DIM);
        INDArray vE = entityEmbSeed.dup();
        INDArray vR = relationEmbSeed.dup();
        setField(model, "entityEmbeddings",   vE);
        setField(model, "relationEmbeddings", vR);
        double vLoss = callTrainBatch(model, positives, negatives, LR, MARGIN);

        INDArray actualE = getField(model, "entityEmbeddings");
        INDArray actualR = getField(model, "relationEmbeddings");

        assertEquals(sLoss, vLoss, TOLERANCE,
                String.format("Loss: scalar=%.8f vector=%.8f", sLoss, vLoss));

        for (int row = 0; row < 4; row++)
            for (int col = 0; col < DIM; col++)
                assertEquals(sE.getDouble(row, col), actualE.getDouble(row, col), TOLERANCE,
                        String.format("entityEmb[%d,%d]", row, col));

        for (int col = 0; col < DIM; col++)
            assertEquals(sR.getDouble(0, col), actualR.getDouble(0, col), TOLERANCE,
                    String.format("relationEmb[0,%d]", col));
    }
}
