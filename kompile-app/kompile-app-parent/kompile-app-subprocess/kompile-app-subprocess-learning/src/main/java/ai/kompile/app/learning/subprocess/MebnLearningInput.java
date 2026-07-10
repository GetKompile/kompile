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
package ai.kompile.app.learning.subprocess;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Serializable snapshot of the data the MEBN-learning subprocess needs to run one
 * {@code SameDiffMebnStrengthLearner} gradient step.
 *
 * <p>Written to a temp file by the main JVM (via {@link #writeToFile}) and read back
 * by {@link LearningSubprocessMain} when {@code algorithm=MEBN}.</p>
 *
 * <h3>Design rationale</h3>
 * <p>Serializing a full {@link ai.kompile.graph.reasoning.mebn.MTheory} and
 * {@link ai.kompile.graph.reasoning.model.ReasoningGraph} is expensive. Instead, the main JVM
 * pre-computes the {@code [E × M]} tensor batch that
 * {@link ai.kompile.graph.reasoning.learning.SameDiffMebnStrengthLearner#buildTensorBatch} would
 * build and passes the plain-Java {@code double[][]} matrices to the subprocess. The subprocess
 * then runs only the SameDiff autodiff gradient step ({@code sdGradient}) and projected-gradient
 * update, which is the compute- and memory-intensive native operation.
 *
 * <p>Edge identity (parent→child per MFrag) is transmitted as {@code edgeKeys} so the updated
 * strengths can be written back and re-applied by the main JVM.</p>
 *
 * @param edgeKeys        ordered list of edge identifiers, format {@code "fragName:parent->child"};
 *                        each index corresponds to column {@code m} in the tensor matrices
 * @param currentStrengths current strength values {@code s[m]}, length M (same order as edgeKeys)
 * @param pParentMatrix   [E × M] parent-posterior values (pre-computed by main JVM); each row is
 *                        one entity observation, each column one learnable edge
 * @param targetMatrix    [E × M] child-target values (same shape as pParentMatrix)
 * @param mebnWeightsOutputPath path where the subprocess writes the updated strengths
 *                              (in {@code MebnWeightSerializer} JSON format: flat
 *                              {@code "fragName:parent->child": strength} map)
 */
public record MebnLearningInput(
        List<String> edgeKeys,
        List<Double> currentStrengths,
        double[][] pParentMatrix,
        double[][] targetMatrix,
        String mebnWeightsOutputPath
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Serialise to a temp file and return the path. Caller deletes after subprocess exits. */
    public Path writeToFile() throws IOException {
        Path tmp = Files.createTempFile("kompile-mebn-input-", ".json");
        Files.writeString(tmp, MAPPER.writeValueAsString(this), StandardCharsets.UTF_8);
        return tmp;
    }

    /** Deserialise from a file written by {@link #writeToFile()}. */
    public static MebnLearningInput readFromFile(Path path) throws IOException {
        return MAPPER.readValue(path.toFile(), MebnLearningInput.class);
    }
}
