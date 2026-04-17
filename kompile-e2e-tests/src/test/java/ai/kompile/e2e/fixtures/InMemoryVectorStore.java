package ai.kompile.e2e.fixtures;

import ai.kompile.core.embeddings.ScoredDocument;
import ai.kompile.core.embeddings.VectorStore;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.springframework.ai.document.Document;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory vector store backed by a ConcurrentHashMap.
 * Uses brute-force cosine similarity for search.
 * Suitable for small test datasets only.
 */
public class InMemoryVectorStore implements VectorStore {

    private final ConcurrentHashMap<String, StoredDoc> store = new ConcurrentHashMap<>();
    private final InMemoryEmbeddingModel embeddingModel;

    public InMemoryVectorStore(InMemoryEmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public int add(List<Document> documents) {
        INDArray embeddings = embeddingModel.embedDocuments(documents);
        return addWithEmbeddings(documents, embeddings);
    }

    @Override
    public int addWithEmbeddings(List<Document> documents, INDArray embeddings) {
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            float[] vec = embeddings.getRow(i).toFloatVector();
            String id = doc.getId() != null ? doc.getId() : UUID.randomUUID().toString();
            store.put(id, new StoredDoc(doc, vec));
        }
        return documents.size();
    }

    @Override
    @SuppressWarnings("deprecation")
    public int add(List<Document> documents, List<List<Float>> embeddings) {
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            List<Float> emb = embeddings.get(i);
            float[] vec = new float[emb.size()];
            for (int j = 0; j < emb.size(); j++) {
                vec[j] = emb.get(j);
            }
            String id = doc.getId() != null ? doc.getId() : UUID.randomUUID().toString();
            store.put(id, new StoredDoc(doc, vec));
        }
        return documents.size();
    }

    @Override
    public List<Document> similaritySearch(String query, int k) {
        return similaritySearch(query, k, 0.0);
    }

    @Override
    public List<Document> similaritySearch(String query, int k, double threshold) {
        float[] queryVec = embeddingModel.embed(query).getRow(0).toFloatVector();
        return scoredSearch(queryVec, k, threshold).stream()
                .map(ScoredDocument::document)
                .collect(Collectors.toList());
    }

    @Override
    @SuppressWarnings("deprecation")
    public List<Document> similaritySearch(List<Float> queryEmbedding, int k, double threshold) {
        float[] vec = new float[queryEmbedding.size()];
        for (int i = 0; i < queryEmbedding.size(); i++) {
            vec[i] = queryEmbedding.get(i);
        }
        return scoredSearch(vec, k, threshold).stream()
                .map(ScoredDocument::document)
                .collect(Collectors.toList());
    }

    @Override
    public List<ScoredDocument> similaritySearchWithScores(INDArray queryEmbedding, int k, double threshold) {
        float[] queryVec = queryEmbedding.isMatrix()
                ? queryEmbedding.getRow(0).toFloatVector()
                : queryEmbedding.toFloatVector();
        return scoredSearch(queryVec, k, threshold);
    }

    @Override
    public boolean delete(List<String> ids) {
        ids.forEach(store::remove);
        return true;
    }

    @Override
    public long getApproxVectorCount() {
        return store.size();
    }

    @Override
    public boolean deleteAll() {
        store.clear();
        return true;
    }

    private List<ScoredDocument> scoredSearch(float[] queryVec, int k, double threshold) {
        return store.entrySet().stream()
                .map(e -> {
                    double score = cosineSimilarity(queryVec, e.getValue().vector);
                    return new ScoredDocument(e.getValue().document, score);
                })
                .filter(sd -> sd.score() >= threshold)
                .sorted(Comparator.comparingDouble(ScoredDocument::score).reversed())
                .limit(k)
                .collect(Collectors.toList());
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0.0 : dot / denom;
    }

    private record StoredDoc(Document document, float[] vector) {}
}
