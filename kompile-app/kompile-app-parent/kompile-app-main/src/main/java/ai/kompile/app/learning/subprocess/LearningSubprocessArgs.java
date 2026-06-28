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

/**
 * Arguments passed to {@link LearningSubprocessMain} via a temp JSON file.
 *
 * <p>The path to this file is the sole command-line argument received by the
 * subprocess. This mirrors the pattern used by {@code TrainingSubprocessArgs}
 * in kompile-model-staging.</p>
 *
 * @param factSheetId               the fact-sheet this job belongs to (for logging)
 * @param algorithm                 {@code "KGE_TRANSE"} or {@code "KGE_ROTATE"}
 * @param embeddingDim              embedding dimension
 * @param epochs                    number of training epochs
 * @param learningRate              SGD learning rate
 * @param margin                    margin for the margin-ranking loss
 * @param negativeSamples           negative samples per positive triple
 * @param normalizeEntities         whether to L2-normalise entity embeddings each epoch
 * @param batchSize                 triples per mini-batch (honours the minibatch cap)
 * @param triplesFilePath           path to the PortableGraph JSON file produced by the launcher
 * @param outputEmbeddingsPath      path where the subprocess should write the embeddings JSON
 * @param warmStartEmbeddingsPath   optional path to a JSON file with prior entity/relation vectors
 *                                  (same {@code {entities:{id:[floats]},relations:{id:[floats]}}} shape
 *                                  as the output file); {@code null} means cold start / random init
 */
public record LearningSubprocessArgs(
        Long factSheetId,
        String algorithm,
        int embeddingDim,
        int epochs,
        double learningRate,
        double margin,
        int negativeSamples,
        boolean normalizeEntities,
        int batchSize,
        String triplesFilePath,
        String outputEmbeddingsPath,
        String warmStartEmbeddingsPath
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Serialises this record to a temp file and returns its path.
     * The caller is responsible for deleting the file after the subprocess exits.
     */
    public Path writeToTempFile() throws IOException {
        Path tmp = Files.createTempFile("kompile-learning-args-", ".json");
        Files.writeString(tmp, MAPPER.writeValueAsString(this), StandardCharsets.UTF_8);
        return tmp;
    }

    /**
     * Deserialises from a file written by {@link #writeToTempFile()}.
     */
    public static LearningSubprocessArgs readFromFile(Path path) throws IOException {
        return MAPPER.readValue(path.toFile(), LearningSubprocessArgs.class);
    }
}
