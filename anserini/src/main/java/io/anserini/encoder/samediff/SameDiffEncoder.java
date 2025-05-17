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
import org.jetbrains.annotations.NotNull;
import org.nd4j.autodiff.samediff.SameDiff;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

public abstract class SameDiffEncoder<T> implements AutoCloseable {
    private static final String CACHE_DIR_BASE = System.getProperty("user.home");
    private static final String CACHE_DIR = Path.of(CACHE_DIR_BASE, ".cache", "anserini", "samediff_encoders").toString();

    private final String modelName;
    private final String modelUrl;

    protected SameDiff sameDiffModel;
    protected SamediffBertTokenizerPreProcessor tokenizerPreProcessor;

    protected List<String> inputTensorNamesForModel;
    protected List<String> outputTensorNamesFromModel;

    public SameDiffEncoder(@NotNull String modelName, @NotNull String modelUrl,
                           @NotNull String vocabName, @NotNull String vocabUrl,
                           @NotNull List<String> inputTensorNamesForModel,
                           @NotNull List<String> outputTensorNamesFromModel,
                           boolean doLowerCaseAndStripAccents, int maxSequenceLength, boolean addSpecialTokens)
            throws IOException, URISyntaxException {
        this.modelName = modelName;
        this.modelUrl = modelUrl;
        this.inputTensorNamesForModel = inputTensorNamesForModel;
        this.outputTensorNamesFromModel = outputTensorNamesFromModel;

        Path vocabPath = downloadFile(vocabName, vocabUrl);
        SamediffBertVocabulary vocabulary = new SamediffBertVocabulary(vocabPath.toFile(), SamediffBertVocabulary.DEFAULT_UNKNOWN_TOKEN);
        this.tokenizerPreProcessor = new SamediffBertTokenizerPreProcessor(vocabulary, doLowerCaseAndStripAccents, addSpecialTokens, maxSequenceLength);

        Path modelPath = downloadFile(modelName, modelUrl);
        try {
            this.sameDiffModel = SameDiff.load(modelPath.toFile(), true);
        } catch (Exception e) {
            throw new IOException("Failed to load SameDiff model from " + modelPath + ": " + e.getMessage(), e);
        }
    }

    private Path downloadFile(String fileName, String fileUrl) throws IOException, URISyntaxException {
        File localFile = new File(getCacheDir(), fileName);
        if (!localFile.exists()) {
            // System.out.println("Downloading " + fileName + " from " + fileUrl + " to " + localFile.getAbsolutePath()); // Removed logging
            FileUtils.copyURLToFile(new URI(fileUrl).toURL(), localFile);
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

    public abstract T encode(@NotNull String query);

    @Override
    public void close() {
        this.sameDiffModel = null; // Allow GC
    }
}