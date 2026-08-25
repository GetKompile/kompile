/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.embedding.samediff;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.embedding.samediff.config.SameDiffEmbeddingProperties;
import ai.kompile.pipelines.steps.samediff.nlp.SameDiffHuggingFaceTokenizer;
import ai.kompile.pipelines.util.URIUtils;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.nd4j.autodiff.samediff.SDVariable;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.common.base.Preconditions;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.ops.transforms.Transforms;
import org.springframework.ai.document.Document;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Managed Hugging Face tokenizer + SameDiff embedding model execution.
 *
 * <p>The model artifact is an imported SameDiff {@code .sdz}; text preprocessing is delegated to
 * Kompile's existing Rust-backed Hugging Face tokenizer adapter. Transformer outputs can be either
 * already-pooled {@code [batch, hidden]} tensors or token embeddings
 * {@code [batch, sequence, hidden]}. Token embeddings are reduced through CLS or
 * attention-mask-aware mean pooling and can be L2-normalized row-wise.</p>
 */
@Slf4j
public class SameDiffEmbeddingModelImpl implements EmbeddingModel {

    private final SameDiffEmbeddingProperties properties;
    private SameDiff sameDiff;
    private SameDiffHuggingFaceTokenizer tokenizer;
    private List<String> modelInputNames = List.of();
    private String outputTensorName;
    private int dimensions = -1;

    public SameDiffEmbeddingModelImpl(@NotNull SameDiffEmbeddingProperties properties) {
        Preconditions.checkNotNull(properties, "SameDiffEmbeddingProperties cannot be null");
        this.properties = properties;
        initialize();
    }

    private void initialize() {
        if (!hasText(properties.getModelUri())) {
            log.info("SameDiff embedding model is not configured; set kompile.embedding.samediff.model-uri");
            return;
        }
        if (!hasText(properties.getTokenizerUri())) {
            log.warn("SameDiff embedding tokenizer is not configured; set kompile.embedding.samediff.tokenizer-uri");
            return;
        }

        try {
            File modelFile = URIUtils.getFileFromUriOrPath(properties.getModelUri());
            if (modelFile == null || !modelFile.isFile()) {
                throw new IllegalArgumentException("SameDiff embedding model does not exist: "
                        + properties.getModelUri());
            }
            this.sameDiff = SameDiff.load(modelFile, true);
            this.modelInputNames = List.copyOf(this.sameDiff.inputs());
            this.outputTensorName = resolveOutputTensorName(this.sameDiff, properties.getOutputTensorName());

            this.tokenizer = new SameDiffHuggingFaceTokenizer();
            this.tokenizer.initialize(properties.getTokenizerUri(), Map.of(
                    "maxLength", Integer.toString(properties.getMaxSequenceLength())));

            this.dimensions = inferDimensionsFromShape();
            log.info("Initialized SameDiff embedding model (inputs={}, output={}, dimensions={}, pooling={})",
                    modelInputNames, outputTensorName, dimensions, properties.getPoolingStrategy());
        } catch (Exception failure) {
            cleanup();
            throw new IllegalStateException("Unable to initialize SameDiff embedding model", failure);
        }
    }

    @Override
    public INDArray embed(String text) {
        if (text == null || text.isBlank()) {
            return Nd4j.empty(DataType.FLOAT);
        }
        INDArray batch = embed(List.of(text));
        if (batch == null || batch.isEmpty()) {
            return Nd4j.empty(DataType.FLOAT);
        }
        try {
            return batch.rank() == 1 ? batch.dup() : batch.getRow(0).dup();
        } finally {
            closeQuietly(batch);
        }
    }

    @Override
    public synchronized INDArray embed(List<String> texts) {
        requireReady();
        if (texts == null || texts.isEmpty()) {
            return Nd4j.empty(DataType.FLOAT);
        }

        List<String> prepared = new ArrayList<>(texts.size());
        String prefix = properties.getInputPrefix() == null ? "" : properties.getInputPrefix();
        for (String text : texts) {
            prepared.add(prefix + (text == null ? "" : text));
        }

        Map<String, INDArray> encoded = tokenizer.batchEncode(
                prepared, properties.isAddSpecialTokens());
        Set<INDArray> inputsToClose = Collections.newSetFromMap(new IdentityHashMap<>());
        inputsToClose.addAll(encoded.values());
        Map<String, INDArray> placeholders = new LinkedHashMap<>();
        Map<String, INDArray> outputs = null;
        INDArray pooled = null;

        try {
            INDArray inputIds = encoded.get("input_ids");
            INDArray attentionMask = encoded.get("attention_mask");
            if (inputIds == null || attentionMask == null) {
                throw new IllegalStateException("Tokenizer did not produce input_ids and attention_mask");
            }

            INDArray tokenTypeIds = null;
            for (String inputName : modelInputNames) {
                if (isInputIds(inputName)) {
                    placeholders.put(inputName, inputIds);
                } else if (isAttentionMask(inputName)) {
                    placeholders.put(inputName, attentionMask);
                } else if (isTokenTypeIds(inputName)) {
                    if (tokenTypeIds == null) {
                        tokenTypeIds = Nd4j.zeros(DataType.INT64, inputIds.shape());
                        inputsToClose.add(tokenTypeIds);
                    }
                    placeholders.put(inputName, tokenTypeIds);
                } else {
                    throw new IllegalStateException("Unsupported SameDiff embedding model input: "
                            + inputName);
                }
            }

            outputs = sameDiff.output(placeholders, outputTensorName);
            INDArray rawOutput = outputs.get(outputTensorName);
            if (rawOutput == null) {
                throw new IllegalStateException("Embedding output '" + outputTensorName
                        + "' was not returned; available outputs=" + outputs.keySet());
            }

            pooled = pool(rawOutput, attentionMask, properties.getPoolingStrategy());
            INDArray result = properties.isNormalizeOutput()
                    ? normalizeRows(pooled)
                    : pooled.dup();
            dimensions = result.rank() == 1
                    ? (int) result.length()
                    : (int) result.size(result.rank() - 1);
            return result;
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("SameDiff embedding execution failed", failure);
        } finally {
            closeQuietly(pooled);
            closeAll(outputs == null ? List.of() : outputs.values());
            closeAll(inputsToClose);
        }
    }

    @Override
    public INDArray embedDocuments(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return Nd4j.empty(DataType.FLOAT);
        }
        return embed(documents.stream().map(Document::getText).toList());
    }

    @Override
    public synchronized int dimensions() {
        if (dimensions > 0) {
            return dimensions;
        }
        dimensions = inferDimensionsFromShape();
        if (dimensions > 0) {
            return dimensions;
        }
        if (sameDiff == null || tokenizer == null) {
            return -1;
        }
        INDArray probe = embed("dimension probe");
        try {
            dimensions = probe == null || probe.isEmpty() ? -1 : (int) probe.length();
            return dimensions;
        } finally {
            closeQuietly(probe);
        }
    }

    static INDArray pool(
            INDArray output,
            INDArray attentionMask,
            SameDiffEmbeddingProperties.PoolingStrategy strategy) {
        if (output == null || output.isEmpty()) {
            throw new IllegalArgumentException("Embedding output must not be null or empty");
        }
        SameDiffEmbeddingProperties.PoolingStrategy effective = strategy == null
                ? SameDiffEmbeddingProperties.PoolingStrategy.AUTO
                : strategy;

        if (output.rank() == 1) {
            return output.reshape(1, output.length()).dup();
        }
        if (output.rank() == 2) {
            return output.dup();
        }
        if (output.rank() != 3) {
            throw new IllegalArgumentException("Unsupported embedding output shape: "
                    + java.util.Arrays.toString(output.shape()));
        }

        if (effective == SameDiffEmbeddingProperties.PoolingStrategy.CLS) {
            return output.get(
                    NDArrayIndex.all(), NDArrayIndex.point(0), NDArrayIndex.all()).dup();
        }
        if (attentionMask == null || attentionMask.rank() != 2
                || attentionMask.size(0) != output.size(0)
                || attentionMask.size(1) != output.size(1)) {
            throw new IllegalArgumentException(
                    "Masked mean pooling requires attention_mask [batch, sequence] matching output");
        }

        INDArray floatMask = null;
        INDArray weighted = null;
        INDArray sums = null;
        INDArray counts = null;
        try {
            floatMask = attentionMask.castTo(output.dataType());
            INDArray expandedMask = floatMask.reshape(
                    floatMask.size(0), floatMask.size(1), 1);
            weighted = output.mul(expandedMask);
            sums = weighted.sum(1);
            counts = floatMask.sum(1).reshape(floatMask.size(0), 1);
            counts.addi(1.0e-12);
            return sums.div(counts);
        } finally {
            closeQuietly(counts);
            closeQuietly(sums);
            closeQuietly(weighted);
            closeQuietly(floatMask);
        }
    }

    static INDArray normalizeRows(INDArray values) {
        INDArray normalized = values.dup();
        if (normalized.rank() == 1) {
            double norm = normalized.norm2Number().doubleValue();
            if (norm > 0.0 && Double.isFinite(norm)) {
                normalized.divi(norm);
            }
            return normalized;
        }
        INDArray rawNorms = null;
        INDArray rowNorms = null;
        INDArray boundedNorms = null;
        INDArray epsilon = null;
        try {
            rawNorms = normalized.norm2(1);
            rowNorms = rawNorms.reshape(normalized.size(0), 1).dup();
            epsilon = Nd4j.scalar(1.0e-12f);
            boundedNorms = Transforms.max(rowNorms, epsilon, false);
            normalized.diviColumnVector(boundedNorms);
        } finally {
            closeQuietly(epsilon);
            if (boundedNorms != rowNorms) closeQuietly(boundedNorms);
            closeQuietly(rowNorms);
            closeQuietly(rawNorms);
        }
        return normalized;
    }

    private int inferDimensionsFromShape() {
        if (sameDiff == null || !hasText(outputTensorName)) {
            return -1;
        }
        SDVariable output = sameDiff.getVariable(outputTensorName);
        long[] shape = output == null ? null : output.getShape();
        if (shape == null || shape.length == 0) {
            return -1;
        }
        long dimension = shape[shape.length - 1];
        return dimension > 0 && dimension <= Integer.MAX_VALUE ? (int) dimension : -1;
    }

    private static String resolveOutputTensorName(SameDiff model, String configured) {
        if (hasText(configured) && model.getVariables().containsKey(configured)) {
            return configured;
        }
        List<String> outputs = model.outputs();
        if (outputs == null || outputs.isEmpty()) {
            throw new IllegalArgumentException("SameDiff embedding model exposes no outputs");
        }
        if (hasText(configured)) {
            log.warn("Configured embedding output '{}' not found; using model output '{}'",
                    configured, outputs.get(0));
        }
        return outputs.get(0);
    }

    private boolean isInputIds(String inputName) {
        String normalized = inputName.toLowerCase(Locale.ROOT);
        return normalized.equals(properties.getInputTensorName().toLowerCase(Locale.ROOT))
                || normalized.equals("input_ids")
                || normalized.equals("inputids");
    }

    private boolean isAttentionMask(String inputName) {
        String normalized = inputName.toLowerCase(Locale.ROOT);
        return normalized.equals(properties.getAttentionMaskTensorName().toLowerCase(Locale.ROOT))
                || normalized.equals("attention_mask")
                || normalized.equals("attentionmask");
    }

    private boolean isTokenTypeIds(String inputName) {
        String normalized = inputName.toLowerCase(Locale.ROOT);
        return normalized.equals(properties.getTokenTypeIdsTensorName().toLowerCase(Locale.ROOT))
                || normalized.equals("token_type_ids")
                || normalized.equals("tokentypeids")
                || normalized.equals("segment_ids");
    }

    private void requireReady() {
        if (sameDiff == null || tokenizer == null) {
            throw new IllegalStateException(
                    "SameDiff embedding model requires both model-uri and tokenizer-uri");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void closeAll(Iterable<INDArray> arrays) {
        for (INDArray array : arrays) {
            closeQuietly(array);
        }
    }

    private static void closeQuietly(INDArray array) {
        if (array != null && !array.wasClosed()) {
            try {
                array.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void close() {
        cleanup();
    }

    @PreDestroy
    public synchronized void cleanup() {
        if (tokenizer != null) {
            tokenizer.close();
            tokenizer = null;
        }
        if (sameDiff != null) {
            try {
                java.lang.reflect.Method closeMethod = sameDiff.getClass().getMethod("close");
                closeMethod.invoke(sameDiff);
            } catch (NoSuchMethodException ignored) {
            } catch (Exception failure) {
                log.warn("Unable to close SameDiff embedding model cleanly", failure);
            }
            sameDiff = null;
        }
        modelInputNames = List.of();
        outputTensorName = null;
        dimensions = -1;
    }
}
