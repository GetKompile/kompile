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
 * Serializable snapshot of the data the PSL-learning subprocess needs to run one
 * {@code StructuredPerceptronLearner.learn()} step.
 *
 * <p>Written to a temp file by the main JVM (via {@link #writeToFile}) and read back
 * by {@link LearningSubprocessMain} when {@code algorithm=PSL}.</p>
 *
 * @param ruleTexts        PSL rule strings (each is parseable via {@code PslRule.parse()});
 *                         the rule text includes the leading weight, e.g. {@code "0.8: A(X) -> B(X) ^2"}
 * @param observedAtoms    ground atom key → observed value (fixed evidence during inference)
 * @param targetAtoms      ground atom keys that are inference targets (soft-truth variables)
 * @param groundTruthLabels atom key → label in [0,1] used as ground truth for weight gradient
 * @param programKey       the FileWeightStore program key for writing the updated weights
 * @param weightOutputPath path where the subprocess writes the updated weights JSON
 * @param tolerance        convergence threshold on max weight update
 * @param batchSize        PSL ground-rule mini-batch size; 0 means full-batch
 * @param seed             deterministic mini-batch seed
 * @param weightPriorStrength MAP prior strength; 0 disables prior regularization
 * @param weightPriorMean  scalar fallback prior mean
 * @param perRuleMeans     optional per-rule prior means for band-aware regularization
 */
public record PslLearningInput(
        List<String> ruleTexts,
        Map<String, Double> observedAtoms,
        List<String> targetAtoms,
        Map<String, Double> groundTruthLabels,
        String programKey,
        String weightOutputPath,
        double tolerance,
        int batchSize,
        long seed,
        double weightPriorStrength,
        double weightPriorMean,
        double[] perRuleMeans
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Serialise to a temp file and return the path. Caller deletes after subprocess exits. */
    public Path writeToFile() throws IOException {
        Path tmp = Files.createTempFile("kompile-psl-input-", ".json");
        Files.writeString(tmp, MAPPER.writeValueAsString(this), StandardCharsets.UTF_8);
        return tmp;
    }

    /** Deserialise from a file written by {@link #writeToFile()}. */
    public static PslLearningInput readFromFile(Path path) throws IOException {
        return MAPPER.readValue(path.toFile(), PslLearningInput.class);
    }
}
