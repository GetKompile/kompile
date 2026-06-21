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
package ai.kompile.graph.reasoning.embedding.learn;

import org.nd4j.linalg.api.ndarray.INDArray;

import java.util.List;

/**
 * Package-private bridge that constructs a {@link RotatELearner.TrainedRotatE} from raw
 * {@link INDArray} references, bypassing the private constructor.
 *
 * <p>{@link RotatELearner.TrainedRotatE} has a package-private constructor that accepts INDArrays;
 * {@link SameDiffModelIO} (also in this package) needs to call it during load without exposing
 * the constructor publicly. This bridge lives in the same package and delegates directly.</p>
 *
 * @see SameDiffModelIO#loadRotatECheckpoint(java.nio.file.Path)
 */
final class RotatEPersistenceBridge {

    private RotatEPersistenceBridge() { }

    /**
     * Construct a {@link RotatELearner.TrainedRotatE} from raw INDArrays (restored from disk).
     *
     * @param entityIds  entity id list in insertion order
     * @param relTypes   relation type list in first-seen order
     * @param dim        embedding dimension
     * @param entityReArr entity real-part embedding matrix {@code [numEntities, dim]}
     * @param entityImArr entity imaginary-part embedding matrix {@code [numEntities, dim]}
     * @param relPhaseArr relation phase matrix {@code [numRelations, dim]}
     */
    static RotatELearner.TrainedRotatE fromArrays(
            List<String> entityIds,
            List<String> relTypes,
            int dim,
            INDArray entityReArr,
            INDArray entityImArr,
            INDArray relPhaseArr) {
        return RotatELearner.TrainedRotatE.fromArrays(
                entityIds, relTypes, dim, entityReArr, entityImArr, relPhaseArr);
    }
}
