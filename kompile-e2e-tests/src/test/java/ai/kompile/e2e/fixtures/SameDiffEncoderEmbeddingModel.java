package ai.kompile.e2e.fixtures;

import ai.kompile.core.embeddings.EmbeddingModel;
import io.anserini.encoder.samediff.GenericDenseSameDiffEncoder;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.util.List;

/**
 * EmbeddingModel adapter that wraps a real {@link GenericDenseSameDiffEncoder}
 * for use in integration tests with genuine semantic embeddings.
 *
 * <p>Unlike {@link InMemoryEmbeddingModel} (which uses hash-based vectors),
 * this produces real transformer-derived embeddings so cosine similarity
 * reflects actual semantic relatedness.</p>
 *
 * <p>The underlying SameDiff model is loaded from the local cache at
 * {@code ~/.kompile/models/}. First use may trigger a download if the
 * model is not yet cached.</p>
 */
public class SameDiffEncoderEmbeddingModel implements EmbeddingModel {

    private final GenericDenseSameDiffEncoder encoder;
    private final int dims;
    private final String modelId;

    public SameDiffEncoderEmbeddingModel(String modelIdentifier) throws IOException {
        this.modelId = modelIdentifier;
        this.encoder = new GenericDenseSameDiffEncoder(modelIdentifier);
        // Probe dimensions by encoding a test string
        float[] probe = encoder.encode("test");
        this.dims = probe.length;
    }

    @Override
    public INDArray embed(String text) {
        float[] vec = encoder.encode(text);
        return Nd4j.createFromArray(new float[][] { vec });
    }

    @Override
    public INDArray embed(List<String> texts) {
        float[][] matrix = new float[texts.size()][];
        for (int i = 0; i < texts.size(); i++) {
            matrix[i] = encoder.encode(texts.get(i));
        }
        return Nd4j.createFromArray(matrix);
    }

    @Override
    public INDArray embedDocuments(List<Document> documents) {
        List<String> texts = documents.stream()
                .map(Document::getText)
                .toList();
        return embed(texts);
    }

    @Override
    public int dimensions() {
        return dims;
    }

    @Override
    public String getModelName() {
        return "SameDiffEncoder[" + modelId + "]";
    }

    @Override
    public String getModelIdentifier() {
        return modelId;
    }

    @Override
    public void close() {
        // GenericDenseSameDiffEncoder doesn't expose a close method;
        // the SameDiff session is GC'd with the encoder instance.
    }
}
