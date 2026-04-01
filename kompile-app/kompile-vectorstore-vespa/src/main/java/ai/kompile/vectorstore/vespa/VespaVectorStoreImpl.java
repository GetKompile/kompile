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

package ai.kompile.vectorstore.vespa;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.embeddings.ScoredDocument;
import ai.kompile.core.embeddings.VectorStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ai.vespa.feed.client.*;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Vespa implementation of the VectorStore interface.
 *
 * <p>This implementation uses:</p>
 * <ul>
 *   <li>Vespa Feed Client (HTTP/2) for document feeding operations</li>
 *   <li>Vespa Query API for similarity search</li>
 *   <li>YQL (Vespa Query Language) with nearestNeighbor for vector search</li>
 * </ul>
 *
 * <p>Vespa is a distributed search engine and vector database that supports
 * hybrid search combining BM25 text matching with vector similarity.</p>
 *
 * <p><b>Requirements:</b></p>
 * <ul>
 *   <li>Running Vespa instance (Docker or Vespa Cloud)</li>
 *   <li>Deployed application package with matching schema</li>
 * </ul>
 */
public class VespaVectorStoreImpl implements VectorStore, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(VespaVectorStoreImpl.class);
    private static final String SOURCE_ID_FIELD = "source_id";

    private final VespaVectorStoreProperties properties;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final FeedClient feedClient;
    private final AtomicLong documentCount = new AtomicLong(0);
    private final Map<String, CompletableFuture<Result>> pendingOperations = new ConcurrentHashMap<>();

    private volatile boolean available = false;

    public VespaVectorStoreImpl(VespaVectorStoreProperties properties, EmbeddingModel embeddingModel) {
        this.properties = properties;
        this.embeddingModel = embeddingModel;
        this.objectMapper = new ObjectMapper();

        // Build HTTP client for queries
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnection().getConnectionTimeoutMs()))
                .build();

        // Build Feed Client for document operations
        FeedClientBuilder feedClientBuilder = FeedClientBuilder.create(
                URI.create(properties.getEffectiveFeedEndpoint())
        );

        VespaVectorStoreProperties.ConnectionProperties conn = properties.getConnection();
        feedClientBuilder
                .setConnectionsPerEndpoint(conn.getMaxConnections())
                .setMaxStreamPerConnection(128)
                .setRetryStrategy(new FeedClient.RetryStrategy() {
                    @Override
                    public boolean retry(FeedClient.OperationType type) {
                        return true;
                    }

                    @Override
                    public int retries() {
                        return conn.getMaxRetries();
                    }
                });

        // Configure TLS if enabled
        if (properties.getTls().isEnabled()) {
            // Note: TLS configuration would be added here
            // For production, configure certs via feedClientBuilder
            logger.info("TLS enabled for Vespa connection");
        }

        this.feedClient = feedClientBuilder.build();

        // Check connection
        checkConnection();

        logger.info("VespaVectorStoreImpl initialized with endpoint: {}", properties.getEndpoint());
    }

    private void checkConnection() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getEndpoint() + "/state/v1/health"))
                    .timeout(Duration.ofMillis(properties.getConnection().getRequestTimeoutMs()))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            available = response.statusCode() == 200;

            if (available) {
                logger.info("Successfully connected to Vespa at {}", properties.getEndpoint());
            } else {
                logger.warn("Vespa health check returned status {}: {}",
                        response.statusCode(), response.body());
            }
        } catch (Exception e) {
            logger.warn("Failed to connect to Vespa at {}: {}", properties.getEndpoint(), e.getMessage());
            available = false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BROWSING AND STATUS METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public String getVectorStorePath() {
        return properties.getEndpoint();
    }

    @Override
    public boolean isVectorStoreAvailable() {
        return available;
    }

    @Override
    public boolean isUsingFallbackIndex() {
        return false;
    }

    @Override
    public long getApproxVectorCount() {
        return documentCount.get();
    }

    @Override
    public List<Map<String, Object>> listVectorDocuments(int offset, int limit) {
        try {
            String yql = String.format(
                    "select * from %s where true limit %d offset %d",
                    properties.getDocumentType(), limit, offset
            );
            return executeQuery(yql, limit, 0.0);
        } catch (Exception e) {
            logger.error("Error listing documents: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public boolean refreshReader() {
        checkConnection();
        return available;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DOCUMENT ADDITION METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public int add(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return 0;
        }

        if (embeddingModel == null) {
            logger.error("Cannot add documents: EmbeddingModel is not configured");
            return 0;
        }

        // Generate embeddings
        INDArray embeddings = embeddingModel.embedDocuments(documents);
        return addWithEmbeddings(documents, embeddings);
    }

    @Override
    public int addWithEmbeddings(List<Document> documents, INDArray embeddings) {
        if (documents == null || documents.isEmpty()) {
            return 0;
        }

        if (embeddings == null || embeddings.isEmpty()) {
            logger.warn("No embeddings provided, attempting to generate");
            return add(documents);
        }

        if (documents.size() != embeddings.rows()) {
            throw new IllegalArgumentException(String.format(
                    "Document count (%d) does not match embedding count (%d)",
                    documents.size(), embeddings.rows()
            ));
        }

        // Convert INDArray to float[][] for efficient processing
        float[][] embeddingArrays = new float[documents.size()][];
        for (int i = 0; i < embeddings.rows(); i++) {
            embeddingArrays[i] = embeddings.getRow(i).toFloatVector();
        }

        return addWithFloatArrayEmbeddings(documents, embeddingArrays);
    }

    @Override
    public int addWithFloatArrayEmbeddings(List<Document> documents, float[][] embeddings) {
        if (documents == null || documents.isEmpty()) {
            return 0;
        }

        if (!available) {
            logger.warn("Vespa is not available, cannot add documents");
            return 0;
        }

        int successCount = 0;
        List<CompletableFuture<Result>> futures = new ArrayList<>();

        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            float[] embedding = (embeddings != null && i < embeddings.length) ? embeddings[i] : null;

            if (embedding == null) {
                logger.warn("Skipping document {} - no embedding", doc.getId());
                continue;
            }

            try {
                String jsonDoc = buildDocumentJson(doc, embedding);
                DocumentId docId = DocumentId.of(
                        properties.getNamespace(),
                        properties.getDocumentType(),
                        doc.getId() != null ? doc.getId() : UUID.randomUUID().toString()
                );

                CompletableFuture<Result> future = feedClient.put(
                        docId,
                        jsonDoc,
                        OperationParameters.empty()
                );

                futures.add(future);

            } catch (Exception e) {
                logger.error("Error preparing document {}: {}", doc.getId(), e.getMessage());
            }
        }

        // Wait for all operations to complete
        for (CompletableFuture<Result> future : futures) {
            try {
                Result result = future.get();
                if (result.type() == Result.Type.success) {
                    successCount++;
                    documentCount.incrementAndGet();
                } else {
                    logger.warn("Document feed failed: {}", result.toString());
                }
            } catch (Exception e) {
                logger.error("Error waiting for feed operation: {}", e.getMessage());
            }
        }

        logger.info("Added {}/{} documents to Vespa", successCount, documents.size());
        return successCount;
    }

    @SuppressWarnings("deprecation")
    @Override
    public int add(List<Document> documents, List<List<Float>> embeddings) {
        if (documents == null || documents.isEmpty()) {
            return 0;
        }

        if (embeddings == null || embeddings.isEmpty()) {
            return add(documents);
        }

        // Convert List<List<Float>> to float[][]
        float[][] embeddingArrays = new float[embeddings.size()][];
        for (int i = 0; i < embeddings.size(); i++) {
            List<Float> embedding = embeddings.get(i);
            if (embedding != null) {
                embeddingArrays[i] = new float[embedding.size()];
                for (int j = 0; j < embedding.size(); j++) {
                    embeddingArrays[i][j] = embedding.get(j);
                }
            }
        }

        return addWithFloatArrayEmbeddings(documents, embeddingArrays);
    }

    private String buildDocumentJson(Document doc, float[] embedding) throws JsonProcessingException {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode fields = root.putObject("fields");

        // Add content
        fields.put(properties.getContentField(), doc.getText());

        // Add embedding as tensor
        ObjectNode tensor = fields.putObject(properties.getVectorField());
        ArrayNode values = tensor.putArray("values");
        for (float v : embedding) {
            values.add(v);
        }

        // Add metadata
        if (doc.getMetadata() != null && !doc.getMetadata().isEmpty()) {
            ObjectNode metadata = fields.putObject(properties.getMetadataField());
            for (Map.Entry<String, Object> entry : doc.getMetadata().entrySet()) {
                if (entry.getValue() != null) {
                    metadata.put(entry.getKey(), entry.getValue().toString());
                }
            }

            // Also add source_id at top level if present
            if (doc.getMetadata().containsKey(SOURCE_ID_FIELD)) {
                fields.put(SOURCE_ID_FIELD, doc.getMetadata().get(SOURCE_ID_FIELD).toString());
            }
        }

        // Add document ID field
        fields.put("id", doc.getId() != null ? doc.getId() : UUID.randomUUID().toString());

        return objectMapper.writeValueAsString(root);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SIMILARITY SEARCH METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public List<Document> similaritySearch(String query, int k) {
        return similaritySearch(query, k, 0.0);
    }

    @Override
    public List<Document> similaritySearch(String query, int k, double threshold) {
        List<ScoredDocument> results = similaritySearchWithScores(query, k, threshold);
        return results.stream()
                .map(ScoredDocument::document)
                .collect(Collectors.toList());
    }

    @Override
    public List<ScoredDocument> similaritySearchWithScores(String query, int k, double threshold) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        if (embeddingModel == null) {
            logger.error("Cannot search: EmbeddingModel is not configured");
            return Collections.emptyList();
        }

        INDArray queryEmbedding = embeddingModel.embed(query);
        return similaritySearchWithScores(queryEmbedding, k, threshold);
    }

    @Override
    public List<ScoredDocument> similaritySearchWithScores(INDArray queryEmbedding, int k, double threshold) {
        if (queryEmbedding == null || queryEmbedding.isEmpty()) {
            return Collections.emptyList();
        }

        if (!available) {
            logger.warn("Vespa is not available, cannot search");
            return Collections.emptyList();
        }

        float[] queryVector = queryEmbedding.isVector()
                ? queryEmbedding.toFloatVector()
                : queryEmbedding.getRow(0).toFloatVector();

        return executeVectorSearch(queryVector, k, threshold);
    }

    @SuppressWarnings("deprecation")
    @Override
    public List<Document> similaritySearch(List<Float> queryEmbedding, int k, double threshold) {
        if (queryEmbedding == null || queryEmbedding.isEmpty()) {
            return Collections.emptyList();
        }

        float[] queryVector = new float[queryEmbedding.size()];
        for (int i = 0; i < queryEmbedding.size(); i++) {
            queryVector[i] = queryEmbedding.get(i);
        }

        List<ScoredDocument> results = executeVectorSearch(queryVector, k, threshold);
        return results.stream()
                .map(ScoredDocument::document)
                .collect(Collectors.toList());
    }

    private List<ScoredDocument> executeVectorSearch(float[] queryVector, int k, double threshold) {
        try {
            // Build YQL query with nearestNeighbor
            String yql = buildVectorSearchYql(k);

            // Build input tensor for query
            String inputTensor = buildInputTensor(queryVector);

            // Execute search
            return executeSearchRequest(yql, inputTensor, k, threshold);

        } catch (Exception e) {
            logger.error("Error executing vector search: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private String buildVectorSearchYql(int k) {
        String vectorField = properties.getVectorField();
        int targetHits = Math.max(k, properties.getTargetHits());

        if (properties.getHybridSearch().isEnabled()) {
            // Hybrid search combining vector + text
            String textField = properties.getHybridSearch().getTextField();
            return String.format(
                    "select * from %s where " +
                            "({targetHits:%d}nearestNeighbor(%s, query_embedding)) or " +
                            "(userQuery())",
                    properties.getDocumentType(), targetHits, vectorField
            );
        } else {
            // Pure vector search
            return String.format(
                    "select * from %s where {targetHits:%d}nearestNeighbor(%s, query_embedding)",
                    properties.getDocumentType(), targetHits, vectorField
            );
        }
    }

    private String buildInputTensor(float[] queryVector) throws JsonProcessingException {
        ObjectNode tensor = objectMapper.createObjectNode();
        ArrayNode values = tensor.putArray("values");
        for (float v : queryVector) {
            values.add(v);
        }
        return objectMapper.writeValueAsString(tensor);
    }

    private List<ScoredDocument> executeSearchRequest(String yql, String inputTensor, int k, double threshold)
            throws IOException, InterruptedException {

        // Build query URL
        StringBuilder queryBuilder = new StringBuilder(properties.getEndpoint())
                .append("/search/?")
                .append("yql=").append(java.net.URLEncoder.encode(yql, "UTF-8"))
                .append("&input.query(query_embedding)=").append(java.net.URLEncoder.encode(inputTensor, "UTF-8"))
                .append("&hits=").append(k);

        if (properties.getHybridSearch().isEnabled()) {
            queryBuilder.append("&ranking=").append(properties.getHybridSearch().getRankingProfile());
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(queryBuilder.toString()))
                .timeout(Duration.ofMillis(properties.getConnection().getRequestTimeoutMs()))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            logger.error("Search request failed with status {}: {}", response.statusCode(), response.body());
            return Collections.emptyList();
        }

        return parseSearchResponse(response.body(), threshold);
    }

    private List<ScoredDocument> parseSearchResponse(String responseBody, double threshold)
            throws JsonProcessingException {

        List<ScoredDocument> results = new ArrayList<>();
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode hitsNode = root.path("root").path("children");

        if (hitsNode.isMissingNode() || !hitsNode.isArray()) {
            return results;
        }

        for (JsonNode hit : hitsNode) {
            double score = hit.path("relevance").asDouble(0.0);

            // Skip if below threshold
            if (threshold > 0 && score < threshold) {
                continue;
            }

            JsonNode fields = hit.path("fields");
            String id = extractStringField(fields, "id", hit.path("id").asText());
            String content = extractStringField(fields, properties.getContentField(), "");

            // Extract metadata
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("score", score);

            JsonNode metadataNode = fields.path(properties.getMetadataField());
            if (!metadataNode.isMissingNode() && metadataNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fieldIterator = metadataNode.fields();
                while (fieldIterator.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fieldIterator.next();
                    metadata.put(entry.getKey(), entry.getValue().asText());
                }
            }

            Document doc = new Document(id, content, metadata);
            results.add(new ScoredDocument(doc, score));
        }

        return results;
    }

    private String extractStringField(JsonNode fields, String fieldName, String defaultValue) {
        JsonNode node = fields.path(fieldName);
        return node.isMissingNode() ? defaultValue : node.asText(defaultValue);
    }

    private List<Map<String, Object>> executeQuery(String yql, int limit, double threshold) {
        try {
            StringBuilder queryBuilder = new StringBuilder(properties.getEndpoint())
                    .append("/search/?")
                    .append("yql=").append(java.net.URLEncoder.encode(yql, "UTF-8"))
                    .append("&hits=").append(limit);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(queryBuilder.toString()))
                    .timeout(Duration.ofMillis(properties.getConnection().getRequestTimeoutMs()))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.error("Query failed with status {}: {}", response.statusCode(), response.body());
                return Collections.emptyList();
            }

            List<Map<String, Object>> results = new ArrayList<>();
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode hitsNode = root.path("root").path("children");

            if (!hitsNode.isMissingNode() && hitsNode.isArray()) {
                for (JsonNode hit : hitsNode) {
                    Map<String, Object> doc = new HashMap<>();
                    JsonNode fields = hit.path("fields");

                    doc.put("id", extractStringField(fields, "id", hit.path("id").asText()));
                    doc.put("content", extractStringField(fields, properties.getContentField(), ""));

                    JsonNode metadataNode = fields.path(properties.getMetadataField());
                    if (!metadataNode.isMissingNode()) {
                        Map<String, Object> metadata = new HashMap<>();
                        Iterator<Map.Entry<String, JsonNode>> fieldIterator = metadataNode.fields();
                        while (fieldIterator.hasNext()) {
                            Map.Entry<String, JsonNode> entry = fieldIterator.next();
                            metadata.put(entry.getKey(), entry.getValue().asText());
                        }
                        doc.put("metadata", metadata);
                    }

                    results.add(doc);
                }
            }

            return results;

        } catch (Exception e) {
            logger.error("Error executing query: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MANAGEMENT METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public boolean delete(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return true;
        }

        if (!available) {
            logger.warn("Vespa is not available, cannot delete");
            return false;
        }

        int successCount = 0;
        List<CompletableFuture<Result>> futures = new ArrayList<>();

        for (String id : ids) {
            try {
                DocumentId docId = DocumentId.of(
                        properties.getNamespace(),
                        properties.getDocumentType(),
                        id
                );

                CompletableFuture<Result> future = feedClient.remove(
                        docId,
                        OperationParameters.empty()
                );

                futures.add(future);

            } catch (Exception e) {
                logger.error("Error preparing delete for document {}: {}", id, e.getMessage());
            }
        }

        for (CompletableFuture<Result> future : futures) {
            try {
                Result result = future.get();
                if (result.type() == Result.Type.success) {
                    successCount++;
                    documentCount.decrementAndGet();
                }
            } catch (Exception e) {
                logger.error("Error waiting for delete operation: {}", e.getMessage());
            }
        }

        logger.info("Deleted {}/{} documents from Vespa", successCount, ids.size());
        return successCount == ids.size();
    }

    @Override
    public boolean deleteAll() {
        logger.warn("deleteAll() is not directly supported by Vespa - use visit API for bulk delete");
        // Would need to implement using Vespa's visit API
        return false;
    }

    @Override
    public List<String> getDocumentIdsBySourceId(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return Collections.emptyList();
        }

        try {
            String yql = String.format(
                    "select id from %s where %s contains \"%s\" limit 10000",
                    properties.getDocumentType(), SOURCE_ID_FIELD, sourceId
            );

            List<Map<String, Object>> results = executeQuery(yql, 10000, 0.0);
            return results.stream()
                    .map(doc -> (String) doc.get("id"))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            logger.error("Error getting document IDs by source: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public boolean flushAndCommit() {
        // Vespa commits are automatic, but we can wait for pending operations
        for (CompletableFuture<Result> future : pendingOperations.values()) {
            try {
                future.get();
            } catch (Exception e) {
                logger.warn("Error waiting for pending operation: {}", e.getMessage());
            }
        }
        pendingOperations.clear();
        return true;
    }

    @Override
    public boolean switchIndexPath(String newPath) {
        // Not applicable for Vespa - would need to reconnect to different endpoint
        logger.warn("switchIndexPath() not supported for Vespa - reconnect with different endpoint");
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    @PreDestroy
    @Override
    public void close() {
        logger.info("Closing VespaVectorStoreImpl...");

        // Wait for pending operations
        flushAndCommit();

        // Close feed client
        if (feedClient != null) {
            try {
                feedClient.close();
            } catch (Exception e) {
                logger.warn("Error closing feed client: {}", e.getMessage());
            }
        }

        logger.info("VespaVectorStoreImpl closed");
    }
}
