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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Arguments passed to {@link LearningSubprocessMain} for PSL weight learning or MEBN
 * edge-strength learning (as opposed to {@link LearningSubprocessArgs} which carries KGE args).
 *
 * <p>Passed to the subprocess as a single temp-file CLI argument, exactly like
 * {@link LearningSubprocessArgs}. The subprocess reads {@link #algorithm()} to choose the
 * dispatch path:
 * <ul>
 *   <li>{@code "PSL"} — runs {@link ai.kompile.graph.reasoning.learning.StructuredPerceptronLearner}
 *       on the serialized rules + labels in {@link #inputFilePath()}, writes updated weights to
 *       {@link #outputWeightsFilePath()}.</li>
 *   <li>{@code "MEBN"} — runs {@link ai.kompile.graph.reasoning.learning.SameDiffMebnStrengthLearner}
 *       on the serialized edge strengths + observations in {@link #inputFilePath()}, writes updated
 *       strengths (in {@code mebn-weights.json} format) to {@link #outputWeightsFilePath()}.</li>
 * </ul>
 *
 * @param factSheetId         the fact-sheet this job belongs to (for logging)
 * @param algorithm           {@code "PSL"} or {@code "MEBN"}
 * @param inputFilePath       path to the serialized input data (rules+labels or edge-strengths+observations)
 * @param outputWeightsFilePath path where the subprocess should write the learned weights JSON
 * @param maxEpochs           number of learning epochs (often 1 for online warm-started accumulation)
 * @param learningRate        SGD step size
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReasoningLearningSubprocessArgs(
        Long factSheetId,
        String algorithm,
        String inputFilePath,
        String outputWeightsFilePath,
        int maxEpochs,
        double learningRate
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Serialises this record to a temp file and returns its path.
     * The caller is responsible for deleting the file after the subprocess exits.
     */
    public Path writeToTempFile() throws IOException {
        Path tmp = Files.createTempFile("kompile-reasoning-args-", ".json");
        Files.writeString(tmp, MAPPER.writeValueAsString(this), StandardCharsets.UTF_8);
        return tmp;
    }

    /**
     * Deserialises from a file written by {@link #writeToTempFile()}.
     */
    public static ReasoningLearningSubprocessArgs readFromFile(Path path) throws IOException {
        return MAPPER.readValue(path.toFile(), ReasoningLearningSubprocessArgs.class);
    }

    /** Convenience: is this a PSL learning job? */
    public boolean isPsl() {
        return "PSL".equalsIgnoreCase(algorithm);
    }

    /** Convenience: is this a MEBN learning job? */
    public boolean isMebn() {
        return "MEBN".equalsIgnoreCase(algorithm);
    }
}
