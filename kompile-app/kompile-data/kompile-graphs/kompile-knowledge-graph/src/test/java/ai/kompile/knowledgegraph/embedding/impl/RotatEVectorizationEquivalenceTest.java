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
 * Verifies that the vectorized {@code RotatEModel.trainBatch} produces results
 * numerically equivalent (within 1e-5) to the original scalar per-triple loop.
 *
 * <p>The scalar oracle is a verbatim copy of the old implementation (trainBatch +
 * updateGradients logic inlined) placed inside this test. Both start from
 * <em>identical</em> initial embeddings derived from a fixed seed and operate on
 * the same positives/negatives lists. The test compares resulting entity real/imag
 * embeddings, relation phase angles, and returned loss value.
 */
class RotatEVectorizationEquivalenceTest {

    private static final double TOLERANCE = 1e-5;
    private static final int    DIM       = 4;
    private static final double LR        = 0.01;
    private static final double MARGIN    = 6.0;

    // -----------------------------------------------------------------------
    // Scalar oracle — verbatim copy of the old RotatEModel trainBatch + updateGradients.
    // Operates directly on the provided embedding arrays (mutated in place).
    // -----------------------------------------------------------------------
    private static double scalarTrainBatch(
            INDArray entityRealEmb,    // [nE, dim]
            INDArray entityImagEmb,    // [nE, dim]
            INDArray relPhaseAngles,   // [nR, dim]
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

            INDArray hReal  = entityRealEmb.getRow(hIdx).dup();
            INDArray hImag  = entityImagEmb.getRow(hIdx).dup();
            INDArray tReal  = entityRealEmb.getRow(tIdx).dup();
            INDArray tImag  = entityImagEmb.getRow(tIdx).dup();
            INDArray theta  = relPhaseAngles.getRow(rIdx).dup();

            INDArray cosTheta = Transforms.cos(theta);
            INDArray sinTheta = Transforms.sin(theta);

            INDArray hrReal = hReal.mul(cosTheta).sub(hImag.mul(sinTheta));
            INDArray hrImag = hReal.mul(sinTheta).add(hImag.mul(cosTheta));

            double posScore = complexDistance(hrReal, hrImag, tReal, tImag);

            for (int j = 0; j < negPerPos; j++) {
                Triple neg = negatives.get(i * negPerPos + j);
                Integer hNegIdx = entityToIndex.get(neg.head());
                Integer tNegIdx = entityToIndex.get(neg.tail());
                if (hNegIdx == null || tNegIdx == null) continue;

                INDArray hNegReal = entityRealEmb.getRow(hNegIdx).dup();
                INDArray hNegImag = entityImagEmb.getRow(hNegIdx).dup();
                INDArray tNegReal = entityRealEmb.getRow(tNegIdx).dup();
                INDArray tNegImag = entityImagEmb.getRow(tNegIdx).dup();

                INDArray hrNegReal = hNegReal.mul(cosTheta).sub(hNegImag.mul(sinTheta));
                INDArray hrNegImag = hNegReal.mul(sinTheta).add(hNegImag.mul(cosTheta));

                double negScore = complexDistance(hrNegReal, hrNegImag, tNegReal, tNegImag);

                double posLoss = -Math.log(sigmoid(margin - posScore) + 1e-10);
                double negLoss = -Math.log(sigmoid(negScore - margin) + 1e-10);
                double loss = posLoss + negLoss;

                if (Double.isFinite(loss)) {
                    totalLoss += loss;

                    // updateGradients inlined
                    INDArray diffReal = hrReal.sub(tReal);
                    INDArray diffImag = hrImag.sub(tImag);
                    INDArray magnitude = Transforms.sqrt(
                            diffReal.mul(diffReal).add(diffImag.mul(diffImag)).add(1e-10));
                    INDArray gradReal = diffReal.div(magnitude);
                    INDArray gradImag = diffImag.div(magnitude);

                    double sigPos = sigmoid(margin - posScore);
                    double gradWeight = sigPos * (1.0 - sigPos);

                    INDArray dhReal = gradReal.mul(cosTheta).add(gradImag.mul(sinTheta))
                            .mul(learningRate * gradWeight);
                    INDArray dhImag = gradReal.mul(sinTheta).sub(gradImag.mul(cosTheta))
                            .neg().mul(learningRate * gradWeight);

                    entityRealEmb.getRow(hIdx).subi(dhReal);
                    entityImagEmb.getRow(hIdx).subi(dhImag);

                    entityRealEmb.getRow(tIdx).addi(gradReal.mul(learningRate * gradWeight));
                    entityImagEmb.getRow(tIdx).addi(gradImag.mul(learningRate * gradWeight));

                    INDArray dTheta = hReal.mul(sinTheta).neg().sub(hImag.mul(cosTheta))
                            .mul(gradReal)
                            .add(hReal.mul(cosTheta).sub(hImag.mul(sinTheta)).mul(gradImag))
                            .mul(learningRate * gradWeight);
                    relPhaseAngles.getRow(rIdx).subi(dTheta);

                    // Negative side
                    INDArray diffNegReal = hrNegReal.sub(tNegReal);
                    INDArray diffNegImag = hrNegImag.sub(tNegImag);
                    INDArray magNeg = Transforms.sqrt(
                            diffNegReal.mul(diffNegReal).add(diffNegImag.mul(diffNegImag)).add(1e-10));
                    INDArray gradNegReal = diffNegReal.div(magNeg);
                    INDArray gradNegImag = diffNegImag.div(magNeg);

                    double sigNeg = sigmoid(negScore - margin);
                    double gradNegWeight = sigNeg * (1.0 - sigNeg);

                    INDArray dhNegReal = gradNegReal.mul(cosTheta).add(gradNegImag.mul(sinTheta))
                            .mul(learningRate * gradNegWeight);
                    INDArray dhNegImag = gradNegReal.mul(sinTheta).sub(gradNegImag.mul(cosTheta))
                            .neg().mul(learningRate * gradNegWeight);

                    entityRealEmb.getRow(hNegIdx).addi(dhNegReal);
                    entityImagEmb.getRow(hNegIdx).addi(dhNegImag);

                    entityRealEmb.getRow(tNegIdx).subi(gradNegReal.mul(learningRate * gradNegWeight));
                    entityImagEmb.getRow(tNegIdx).subi(gradNegImag.mul(learningRate * gradNegWeight));
                }
            }
        }
        return totalLoss / positives.size();
    }

    /** Sum of per-dim magnitudes — matches RotatEModel.computeComplexDistance exactly. */
    private static double complexDistance(INDArray aR, INDArray aI, INDArray bR, INDArray bI) {
        INDArray dR = aR.sub(bR);
        INDArray dI = aI.sub(bI);
        return Transforms.sqrt(dR.mul(dR).add(dI.mul(dI))).sumNumber().doubleValue();
    }

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    // -----------------------------------------------------------------------
    // Reflection helpers
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

    private static double callTrainBatch(RotatEModel model,
                                          List<Triple> positives,
                                          List<Triple> negatives,
                                          double lr, double margin) throws Exception {
        Method m = RotatEModel.class.getDeclaredMethod("trainBatch",
                List.class, List.class, double.class, double.class);
        m.setAccessible(true);
        return (Double) m.invoke(model, positives, negatives, lr, margin);
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------
    @Test
    void trainBatch_vectorized_matchesScalarOracle_withinTolerance() throws Exception {
        Map<String, Integer> entityMap = new HashMap<>();
        entityMap.put("A", 0); entityMap.put("B", 1); entityMap.put("C", 2);
        entityMap.put("D", 3); entityMap.put("E", 4);
        Map<String, Integer> relMap = new HashMap<>();
        relMap.put("R0", 0); relMap.put("R1", 1);

        List<String> indexToEntity   = List.of("A", "B", "C", "D", "E");
        List<String> indexToRelation = List.of("R0", "R1");

        Nd4j.getRandom().setSeed(42L);
        INDArray eRealSeed  = Nd4j.rand(5, DIM);
        INDArray eImagSeed  = Nd4j.rand(5, DIM);
        INDArray thetaSeed  = Nd4j.rand(2, DIM).muli(2 * Math.PI).subi(Math.PI);

        List<Triple> positives = List.of(
                new Triple("A", "R0", "B"),
                new Triple("C", "R1", "D"),
                new Triple("B", "R0", "E")
        );
        List<Triple> negatives = new ArrayList<>();
        negatives.add(new Triple("A", "R0", "C")); negatives.add(new Triple("A", "R0", "D"));
        negatives.add(new Triple("C", "R1", "A")); negatives.add(new Triple("C", "R1", "E"));
        negatives.add(new Triple("B", "R0", "C")); negatives.add(new Triple("B", "R0", "D"));

        // Scalar oracle
        INDArray sEReal  = eRealSeed.dup();
        INDArray sEImag  = eImagSeed.dup();
        INDArray sTheta  = thetaSeed.dup();
        double sLoss = scalarTrainBatch(sEReal, sEImag, sTheta,
                entityMap, relMap, positives, negatives, LR, MARGIN);

        // Vectorized model
        RotatEModel model = new RotatEModel();
        setField(model, "entityToIndex",    entityMap);
        setField(model, "relationToIndex",  relMap);
        setField(model, "indexToEntity",    indexToEntity);
        setField(model, "indexToRelation",  indexToRelation);
        setField(model, "embeddingDim",     DIM);
        setField(model, "embeddingRange",   MARGIN / DIM);
        INDArray vEReal  = eRealSeed.dup();
        INDArray vEImag  = eImagSeed.dup();
        INDArray vTheta  = thetaSeed.dup();
        setField(model, "entityRealEmbeddings", vEReal);
        setField(model, "entityImagEmbeddings", vEImag);
        setField(model, "relationPhaseAngles",  vTheta);

        double vLoss = callTrainBatch(model, positives, negatives, LR, MARGIN);

        INDArray aEReal = getField(model, "entityRealEmbeddings");
        INDArray aEImag = getField(model, "entityImagEmbeddings");
        INDArray aTheta = getField(model, "relationPhaseAngles");

        assertEquals(sLoss, vLoss, TOLERANCE,
                String.format("Loss: scalar=%.8f  vector=%.8f", sLoss, vLoss));

        for (int row = 0; row < 5; row++)
            for (int col = 0; col < DIM; col++) {
                assertEquals(sEReal.getDouble(row, col), aEReal.getDouble(row, col), TOLERANCE,
                        String.format("entityReal[%d,%d]", row, col));
                assertEquals(sEImag.getDouble(row, col), aEImag.getDouble(row, col), TOLERANCE,
                        String.format("entityImag[%d,%d]", row, col));
            }

        for (int row = 0; row < 2; row++)
            for (int col = 0; col < DIM; col++)
                assertEquals(sTheta.getDouble(row, col), aTheta.getDouble(row, col), TOLERANCE,
                        String.format("relPhase[%d,%d]", row, col));
    }

    /**
     * Same entity "A" appears as head in two positives — gradient accumulation test.
     */
    @Test
    void trainBatch_repeatedEntityIndex_accumulatesGradients() throws Exception {
        Map<String, Integer> entityMap = new HashMap<>();
        entityMap.put("A", 0); entityMap.put("B", 1); entityMap.put("C", 2); entityMap.put("D", 3);
        Map<String, Integer> relMap = new HashMap<>();
        relMap.put("R0", 0);

        List<String> indexToEntity   = List.of("A", "B", "C", "D");
        List<String> indexToRelation = List.of("R0");

        Nd4j.getRandom().setSeed(77L);
        INDArray eRealSeed = Nd4j.rand(4, DIM);
        INDArray eImagSeed = Nd4j.rand(4, DIM);
        INDArray thetaSeed = Nd4j.rand(1, DIM).muli(2 * Math.PI).subi(Math.PI);

        // A is head in BOTH positives → must accumulate, not overwrite
        List<Triple> positives = List.of(
                new Triple("A", "R0", "B"),
                new Triple("A", "R0", "C")
        );
        List<Triple> negatives = List.of(
                new Triple("D", "R0", "B"),
                new Triple("D", "R0", "C")
        );

        INDArray sEReal = eRealSeed.dup(); INDArray sEImag = eImagSeed.dup();
        INDArray sTheta = thetaSeed.dup();
        double sLoss = scalarTrainBatch(sEReal, sEImag, sTheta,
                entityMap, relMap, positives, negatives, LR, MARGIN);

        RotatEModel model = new RotatEModel();
        setField(model, "entityToIndex",    entityMap);
        setField(model, "relationToIndex",  relMap);
        setField(model, "indexToEntity",    indexToEntity);
        setField(model, "indexToRelation",  indexToRelation);
        setField(model, "embeddingDim",     DIM);
        setField(model, "embeddingRange",   MARGIN / DIM);
        INDArray vEReal = eRealSeed.dup(); INDArray vEImag = eImagSeed.dup();
        INDArray vTheta = thetaSeed.dup();
        setField(model, "entityRealEmbeddings", vEReal);
        setField(model, "entityImagEmbeddings", vEImag);
        setField(model, "relationPhaseAngles",  vTheta);

        double vLoss = callTrainBatch(model, positives, negatives, LR, MARGIN);

        INDArray aEReal = getField(model, "entityRealEmbeddings");
        INDArray aEImag = getField(model, "entityImagEmbeddings");
        INDArray aTheta = getField(model, "relationPhaseAngles");

        assertEquals(sLoss, vLoss, TOLERANCE,
                String.format("Loss: scalar=%.8f  vector=%.8f", sLoss, vLoss));

        for (int row = 0; row < 4; row++)
            for (int col = 0; col < DIM; col++) {
                assertEquals(sEReal.getDouble(row, col), aEReal.getDouble(row, col), TOLERANCE,
                        String.format("entityReal[%d,%d]", row, col));
                assertEquals(sEImag.getDouble(row, col), aEImag.getDouble(row, col), TOLERANCE,
                        String.format("entityImag[%d,%d]", row, col));
            }

        for (int col = 0; col < DIM; col++)
            assertEquals(sTheta.getDouble(0, col), aTheta.getDouble(0, col), TOLERANCE,
                    String.format("relPhase[0,%d]", col));
    }
}
