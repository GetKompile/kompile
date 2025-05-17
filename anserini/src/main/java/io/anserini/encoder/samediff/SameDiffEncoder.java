/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

package io.anserini.encoder.samediff;

import io.anserini.encoder.samediff.tokenizer.SamediffBertTokenizerPreProcessor;
import io.anserini.encoder.samediff.tokenizer.SamediffBertVocabulary;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.nd4j.autodiff.samediff.SameDiff;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
// Removed unused import java.util.Map; as it's not directly used here anymore.

public abstract class SameDiffEncoder<T> implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(SameDiffEncoder.class);
    private static final String CACHE_DIR_BASE = System.getProperty("user.home");
    private static final String CACHE_DIR = Path.of(CACHE_DIR_BASE, ".cache", "anserini", "samediff_encoders").toString();

    protected final String modelName;
    protected final String modelUrl;
    protected final String vocabName;
    protected final String vocabUrl;

    protected SameDiff sameDiffModel;
    protected SamediffBertTokenizerPreProcessor tokenizerPreProcessor;

    protected List<String> inputTensorNamesForModel;
    protected List<String> outputTensorNamesFromModel;

    public SameDiffEncoder(@NotNull String modelName, @Nullable String modelUrl,
                           @NotNull String vocabName, @Nullable String vocabUrl,
                           @Nullable String providedModelPath, @Nullable String providedVocabPath,
                           @NotNull List<String> inputTensorNamesForModel,
                           @NotNull List<String> outputTensorNamesFromModel,
                           boolean doLowerCaseAndStripAccents, int maxSequenceLength, boolean addSpecialTokens)
            throws IOException, URISyntaxException {
        this.modelName = modelName;
        this.modelUrl = modelUrl;
        this.vocabName = vocabName;
        this.vocabUrl = vocabUrl;
        this.inputTensorNamesForModel = inputTensorNamesForModel;
        this.outputTensorNamesFromModel = outputTensorNamesFromModel;

        Path vocabPath;
        if (providedVocabPath != null && !providedVocabPath.isEmpty()) {
            vocabPath = Paths.get(providedVocabPath);
            LOG.info("Loading vocabulary from provided path: {}", vocabPath);
            if (!vocabPath.toFile().exists()){
                throw new IOException("Provided vocabulary path does not exist: " + providedVocabPath);
            }
        } else if (vocabUrl != null && !vocabUrl.isEmpty()) {
            LOG.info("Downloading vocabulary {} from URL: {}", vocabName, vocabUrl);
            vocabPath = downloadFile(vocabName, vocabUrl);
        } else {
            throw new IllegalArgumentException("Either vocabulary path or vocabulary URL must be provided.");
        }

        SamediffBertVocabulary vocabulary = new SamediffBertVocabulary(vocabPath.toFile(), SamediffBertVocabulary.DEFAULT_UNKNOWN_TOKEN);
        this.tokenizerPreProcessor = new SamediffBertTokenizerPreProcessor(vocabulary, doLowerCaseAndStripAccents, addSpecialTokens, maxSequenceLength);

        Path modelPath;
        if (providedModelPath != null && !providedModelPath.isEmpty()) {
            modelPath = Paths.get(providedModelPath);
            LOG.info("Loading SameDiff model from provided path: {}", modelPath);
            if (!modelPath.toFile().exists()){
                throw new IOException("Provided model path does not exist: " + providedModelPath);
            }
        } else if (modelUrl != null && !modelUrl.isEmpty()) {
            LOG.info("Downloading SameDiff model {} from URL: {}", modelName, modelUrl);
            modelPath = downloadFile(modelName, modelUrl);
        } else {
            throw new IllegalArgumentException("Either model path or model URL must be provided.");
        }

        try {
            this.sameDiffModel = SameDiff.load(modelPath.toFile(), true);
            LOG.info("Successfully loaded SameDiff model from: {}", modelPath);
        } catch (Exception e) {
            LOG.error("Failed to load SameDiff model from " + modelPath, e);
            throw new IOException("Failed to load SameDiff model from " + modelPath + ": " + e.getMessage(), e);
        }
    }

    private Path downloadFile(String fileName, String fileUrl) throws IOException, URISyntaxException {
        File localFile = new File(getCacheDir(), fileName);
        if (!localFile.exists()) {
            LOG.info("Downloading {} from {} to {}", fileName, fileUrl, localFile.getAbsolutePath());
            try {
                FileUtils.copyURLToFile(new URI(fileUrl).toURL(), localFile);
            } catch (IOException e) {
                LOG.error("Failed to download {} from {}: {}", fileName, fileUrl, e.getMessage());
                throw e;
            }
        } else {
            LOG.info("{} already exists locally at {}", fileName, localFile.getAbsolutePath());
        }
        return localFile.toPath();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public String getCacheDir() {
        File cacheDir = new File(CACHE_DIR);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        return cacheDir.getPath();
    }

    public SamediffBertTokenizerPreProcessor getTokenizerPreProcessor() {
        return tokenizerPreProcessor;
    }

    /**
     * Encodes the input query or text.
     * @param text The text to encode.
     * @return The encoded representation, type T (e.g., float[] for dense, Map<String, Float> for sparse).
     */
    public abstract T encode(@NotNull String text); // MODIFIED HERE

    @Override
    public void close() {
        if (this.sameDiffModel != null) {
            // No explicit close method in SameDiff for the model itself,
            // but nullifying helps GC and indicates it's no longer usable.
            LOG.debug("Closing SameDiffEncoder, nullifying model: {}", modelName);
        }
        // It's good practice to also ensure resources within components are released if possible.
        // For example, if tokenizerPreProcessor held significant resources, it might have a close() too.
        this.sameDiffModel = null;
        this.tokenizerPreProcessor = null; // Or tokenizerPreProcessor.close() if available
    }
}