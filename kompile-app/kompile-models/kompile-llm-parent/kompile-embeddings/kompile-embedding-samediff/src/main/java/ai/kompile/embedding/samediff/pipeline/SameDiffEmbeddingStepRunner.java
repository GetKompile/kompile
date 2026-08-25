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

package ai.kompile.embedding.samediff.pipeline;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.embedding.samediff.SameDiffEmbeddingModelImpl;
import ai.kompile.embedding.samediff.config.SameDiffEmbeddingProperties;
import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.ValueType;
import org.nd4j.common.base.Preconditions;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SameDiffEmbeddingStepRunner implements PipelineStepRunner {

    private SameDiffEmbeddingStepConfig config;
    private EmbeddingModel embeddingModel;
    private boolean initialized;

    public SameDiffEmbeddingStepRunner(SameDiffEmbeddingStepConfig config) {
        initialize(config);
    }



    public SameDiffEmbeddingStepRunner() {}

    @Override
    public void init(StepConfig stepConfig, Context context) throws Exception {
        SameDiffEmbeddingStepConfig typed = stepConfig instanceof SameDiffEmbeddingStepConfig existing
                ? existing
                : new SameDiffEmbeddingStepConfig(stepConfig.getParameters().toMap());
        initialize(typed);
    }

    @Override
    public Data exec(Data input, Context context) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("SameDiffEmbeddingStepRunner is not initialized");
        }
        Object value = input.get(config.getInputTextKey());
        INDArray embeddings;
        if (value instanceof String text) {
            embeddings = embeddingModel.embed(text);
        } else if (value instanceof List<?>) {
            List<String> texts = input.getList(config.getInputTextKey(), ValueType.STRING);
            embeddings = embeddingModel.embed(texts);
        } else {
            throw new IllegalArgumentException("Input key '" + config.getInputTextKey()
                    + "' must contain a String or List<String>");
        }

        try {
            Data result = Data.empty();
            result.put(config.getOutputEmbeddingsKey(), toLists(embeddings));
            return result;
        } finally {
            if (embeddings != null && !embeddings.wasClosed()) {
                embeddings.close();
            }
        }
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void close() throws Exception {
        // Clean up embedding model to release native resources (SameDiff OpContexts, etc.)
        if (embeddingModel != null) {
            try {
                embeddingModel.close();
            } catch (Exception e) {
                // Log but don't propagate to ensure cleanup continues
            }
            embeddingModel = null;
        }
        config = null;
        initialized = false;
    }

    private void initialize(SameDiffEmbeddingStepConfig config) {
        Preconditions.checkNotNull(config, "SameDiffEmbeddingStepConfig cannot be null.");
        if (embeddingModel != null) {
            try {
                embeddingModel.close();
            } catch (Exception ignored) {
            }
        }
        this.config = config;
        SameDiffEmbeddingProperties properties = new SameDiffEmbeddingProperties();
        properties.setModelUri(config.getModelUri());
        properties.setTokenizerUri(config.getTokenizerUri());
        properties.setInputTensorName(config.getInputTensorName());
        properties.setAttentionMaskTensorName(config.getAttentionMaskTensorName());
        properties.setTokenTypeIdsTensorName(config.getTokenTypeIdsTensorName());
        properties.setOutputTensorName(config.getOutputTensorName());
        properties.setMaxSequenceLength(config.getMaxSequenceLength());
        properties.setAddSpecialTokens(config.isAddSpecialTokens());
        properties.setPoolingStrategy(SameDiffEmbeddingProperties.PoolingStrategy.valueOf(
                config.getPoolingStrategy().trim().toUpperCase(Locale.ROOT)));
        properties.setNormalizeOutput(config.isNormalizeOutput());
        properties.setInputPrefix(config.getInputPrefix());
        this.embeddingModel = new SameDiffEmbeddingModelImpl(properties);
        this.initialized = true;
    }

    private static Object toLists(INDArray embeddings) {
        if (embeddings == null || embeddings.isEmpty()) {
            return List.of();
        }
        float[] allValues = embeddings.data().asFloat();
        if (embeddings.rank() == 1 || (embeddings.rank() == 2 && embeddings.rows() == 1)) {
            List<Double> result = new ArrayList<>(allValues.length);
            for (float value : allValues) {
                result.add((double) value);
            }
            return result;
        }
        int rowCount = (int) embeddings.rows();
        int columns = (int) embeddings.length() / rowCount;
        List<List<Double>> rows = new ArrayList<>(rowCount);
        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            List<Double> row = new ArrayList<>(columns);
            int offset = rowIndex * columns;
            for (int column = 0; column < columns; column++) {
                row.add((double) allValues[offset + column]);
            }
            rows.add(row);
        }
        return rows;
    }
}