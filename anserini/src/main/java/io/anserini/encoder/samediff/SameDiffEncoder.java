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

package io.anserini.encoder.samediff;

import io.anserini.encoder.samediff.tokenizer.SamediffBertTokenizerPreProcessor;
import io.anserini.encoder.samediff.tokenizer.SamediffBertVocabulary;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.samediff.frameworkimport.onnx.importer.OnnxFrameworkImporter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class SameDiffEncoder<RETURN_TYPE> implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(SameDiffEncoder.class);

    protected SameDiff sameDiffModel;
    protected SamediffBertTokenizerPreProcessor tokenizerPreProcessor;

    protected final List<String> inputTensorNamesForModel;
    protected final List<String> outputTensorNamesFromModel;
    protected final String modelIdentifier;

    public SameDiffEncoder(@NotNull String modelIdentifier,
                           @NotNull String kompileManagedModelPath,
                           @NotNull String kompileManagedVocabPath,
                           @NotNull List<String> inputTensorNamesForModel,
                           @NotNull List<String> outputTensorNamesFromModel,
                           boolean doLowerCaseAndStripAccents,
                           int maxSequenceLength,
                           boolean addSpecialTokens) throws IOException {
        this.modelIdentifier = modelIdentifier;
        this.inputTensorNamesForModel = Collections.unmodifiableList(inputTensorNamesForModel);
        this.outputTensorNamesFromModel = Collections.unmodifiableList(outputTensorNamesFromModel);

        Path vocabPath = Paths.get(kompileManagedVocabPath);
        LOG.info("[{}] Loading vocabulary from Kompile-managed path: {}", modelIdentifier, vocabPath.toAbsolutePath());
        if (!Files.exists(vocabPath) || !Files.isRegularFile(vocabPath)) {
            throw new IOException("Kompile-managed vocabulary path does not exist or is not a file: " + kompileManagedVocabPath);
        }
        // Use the provided SamediffBertVocabulary constructor
        SamediffBertVocabulary vocabulary = new SamediffBertVocabulary(vocabPath.toFile(), SamediffBertVocabulary.DEFAULT_UNKNOWN_TOKEN);
        this.tokenizerPreProcessor = new SamediffBertTokenizerPreProcessor(vocabulary, doLowerCaseAndStripAccents, addSpecialTokens, maxSequenceLength);

        Path onnxModelPath = Paths.get(kompileManagedModelPath);
        LOG.info("[{}] Loading ONNX model from Kompile-managed path: {}", modelIdentifier, onnxModelPath.toAbsolutePath());
        if (!Files.exists(onnxModelPath) || !Files.isRegularFile(onnxModelPath)) {
            throw new IOException("Kompile-managed ONNX model path does not exist or is not a file: " + kompileManagedModelPath);
        }

        try {
            OnnxFrameworkImporter importer = new OnnxFrameworkImporter();
            this.sameDiffModel = importer.runImport(onnxModelPath.toFile().getAbsolutePath(), Collections.emptyMap(), true, true);

            if (this.sameDiffModel == null) {
                throw new IOException("Failed to import ONNX model to SameDiff from " + onnxModelPath + ". Importer returned null.");
            }
            LOG.info("[{}] Successfully imported ONNX model to SameDiff from: {}", modelIdentifier, onnxModelPath.toAbsolutePath());

        } catch (Exception e) {
            LOG.error("[{}] Failed to import ONNX model to SameDiff from path {}", modelIdentifier, onnxModelPath, e);
            throw new IOException("Failed to import ONNX model to SameDiff from " + onnxModelPath + ": " + e.getMessage(), e);
        }
    }

    public SamediffBertTokenizerPreProcessor getTokenizerPreProcessor() {
        return tokenizerPreProcessor;
    }

    public abstract RETURN_TYPE encode(@NotNull String text);

    public Map<String, RETURN_TYPE> bulkEncode(@NotNull List<String> texts) {
        Map<String, RETURN_TYPE> results = new HashMap<>();
        for (String text : texts) {
            if (text == null) continue;
            results.put(text, encode(text));
        }
        return results;
    }

    @Override
    public void close() throws IOException {
        LOG.info("Closing SameDiffEncoder for model: {}", modelIdentifier);
        this.sameDiffModel = null;
        this.tokenizerPreProcessor = null;
    }
}