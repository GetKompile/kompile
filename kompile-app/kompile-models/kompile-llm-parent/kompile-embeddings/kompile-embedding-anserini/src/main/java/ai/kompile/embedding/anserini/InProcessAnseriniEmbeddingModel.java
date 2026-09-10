/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.embedding.anserini;

import ai.kompile.core.embeddings.EmbeddingModel;
import io.anserini.encoder.samediff.SameDiffEncoder;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.nd4j.linalg.factory.Nd4j;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Objects;

/** Direct {@link EmbeddingModel} view over a first-party Anserini SameDiff encoder. */
public final class InProcessAnseriniEmbeddingModel implements EmbeddingModel {
    private final String modelIdentifier;
    private final int dimensions;
    private final SameDiffEncoder<float[]> encoder;

    InProcessAnseriniEmbeddingModel(
            String modelIdentifier, int dimensions, SameDiffEncoder<float[]> encoder) {
        this.modelIdentifier = Objects.requireNonNull(modelIdentifier, "modelIdentifier");
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        this.dimensions = dimensions;
        this.encoder = Objects.requireNonNull(encoder, "encoder");
    }

    @Override
    public INDArray embed(String text) {
        return Nd4j.create(requireVector(encoder.encode(Objects.requireNonNull(text, "text"))));
    }

    @Override
    public INDArray embed(List<String> texts) {
        List<float[]> vectors = embedBatch(texts);
        return Nd4j.create(vectors.toArray(float[][]::new));
    }

    @Override
    public INDArray embedDocuments(List<Document> documents) {
        return embed(documents.stream().map(Document::getText).toList());
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        Objects.requireNonNull(texts, "texts");
        List<float[]> vectors = encoder.encodeBatch(texts);
        if (vectors == null || vectors.size() != texts.size()) {
            throw new IllegalStateException("Encoder returned an incomplete embedding batch");
        }
        vectors.forEach(this::requireVector);
        return List.copyOf(vectors);
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String getModelName() {
        return modelIdentifier;
    }

    @Override
    public String getModelIdentifier() {
        return modelIdentifier;
    }

    @Override
    public boolean isInitialized() {
        return true;
    }

    @Override
    public void close() throws Exception {
        try {
            encoder.close();
        } finally {
            // This adapter owns the encoder lifecycle. Once its model and tokenizer are closed,
            // no encoder arrays may remain live, so release cudaMallocAsync reservations before
            // handing the device to another first-party model in the same JVM.
            Nd4j.getExecutioner().commit();
            NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();
            int devices = Nd4j.getAffinityManager().getNumberOfDevices();
            for (int device = 0; device < devices; device++) {
                nativeOps.trimMemoryPoolOnStream(device, null);
            }
        }
    }

    private float[] requireVector(float[] vector) {
        if (vector == null || vector.length != dimensions) {
            throw new IllegalStateException("Expected " + dimensions
                    + " embedding values but received " + (vector == null ? 0 : vector.length));
        }
        return vector;
    }
}
