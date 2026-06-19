/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.web.dto.modelregistry;

/**
 * Complete model information for UI display
 */
public class BuiltInModelInfo {
    public String modelId;
    public String modelType;       // "dense_encoder", "sparse_encoder", "cross_encoder"
    public String category;        // RAG pipeline phase: "retrieval", "reranking"
    public String description;
    public String framework;
    public String version;
    public Integer embeddingDim;
    public Integer maxSequenceLength;
    public Integer hiddenSize;
    public Integer numLayers;
    public String trainingData;
    public String inputFormat;
    public String outputType;
    public String tokenizerType;
    public boolean doLowerCase;
    public boolean stripAccents;
    public String downloadUrl;
    public String vocabUrl;
    public String huggingfaceSource;
    public String languages;

    public BuiltInModelInfo(String modelId, String modelType, String category, String description,
                            String framework, String version, Integer embeddingDim, Integer maxSequenceLength,
                            Integer hiddenSize, Integer numLayers, String trainingData, String inputFormat,
                            String outputType, String tokenizerType, boolean doLowerCase, boolean stripAccents,
                            String downloadUrl, String vocabUrl, String huggingfaceSource, String languages) {
        this.modelId = modelId;
        this.modelType = modelType;
        this.category = category;
        this.description = description;
        this.framework = framework;
        this.version = version;
        this.embeddingDim = embeddingDim;
        this.maxSequenceLength = maxSequenceLength;
        this.hiddenSize = hiddenSize;
        this.numLayers = numLayers;
        this.trainingData = trainingData;
        this.inputFormat = inputFormat;
        this.outputType = outputType;
        this.tokenizerType = tokenizerType;
        this.doLowerCase = doLowerCase;
        this.stripAccents = stripAccents;
        this.downloadUrl = downloadUrl;
        this.vocabUrl = vocabUrl;
        this.huggingfaceSource = huggingfaceSource;
        this.languages = languages;
    }
}
