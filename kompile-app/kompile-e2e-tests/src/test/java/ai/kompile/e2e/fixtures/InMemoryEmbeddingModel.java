package ai.kompile.e2e.fixtures;

import ai.kompile.core.embeddings.EmbeddingModel;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Deterministic in-memory embedding model for testing.
 * Generates normalized vectors seeded by text hash, ensuring the same
 * input always produces the same embedding while different inputs
 * produce different (but stable) embeddings.
 */
public class InMemoryEmbeddingModel implements EmbeddingModel {

    private final int dims;

    public InMemoryEmbeddingModel(int dimensions) {
        this.dims = dimensions;
    }

    @Override
    public INDArray embed(String text) {
        float[] vector = hashToVector(text);
        return Nd4j.createFromArray(new float[][] { vector });
    }

    @Override
    public INDArray embed(List<String> texts) {
        float[][] matrix = new float[texts.size()][dims];
        for (int i = 0; i < texts.size(); i++) {
            matrix[i] = hashToVector(texts.get(i));
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
    public void close() {
        // nothing to release
    }

    private float[] hashToVector(String text) {
        float[] vector = new float[dims];
        long seed = text.hashCode();
        double norm = 0.0;
        for (int i = 0; i < dims; i++) {
            // Simple LCG-based PRNG seeded by text hash
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            vector[i] = (float) ((seed >> 33) / (double) Integer.MAX_VALUE);
            norm += vector[i] * vector[i];
        }
        // normalize to unit vector
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dims; i++) {
                vector[i] /= (float) norm;
            }
        }
        return vector;
    }
}
