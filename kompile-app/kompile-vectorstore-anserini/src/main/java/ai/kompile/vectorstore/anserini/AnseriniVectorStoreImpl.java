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

package ai.kompile.vectorstore.anserini;

import ai.kompile.core.embeddings.VectorStore;
import io.anserini.index.Constants;
import io.anserini.search.BaseDenseSearcher;
import io.anserini.search.ScoredDoc;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Enhanced Anserini-based implementation of the VectorStore interface.
 * Supports both flat and HNSW indexing strategies with configurable similarity functions.
 */
@Slf4j
@Service("anseriniVectorStoreImpl")
public class AnseriniVectorStoreImpl implements VectorStore, DisposableBean {

    private final String indexPath;
    private final EmbeddingModel embeddingModel;
    private final AnseriniVectorStoreProperties properties;
    private final Directory directory;
    private IndexWriter indexWriter;
    private BaseDenseSearcher<String> searcher;
    private final Object writerLock = new Object();

    public AnseriniVectorStoreImpl(AnseriniVectorStoreProperties properties, EmbeddingModel embeddingModel) {
        this.properties = properties;
        this.indexPath = properties.getIndexPath();
        this.embeddingModel = embeddingModel;
        
        try {
            Path path = Paths.get(indexPath);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
            this.directory = FSDirectory.open(path);
            initializeIndexWriter();
            log.info("AnseriniVectorStoreImpl initialized with index path: {}, HNSW: {}", 
                    indexPath, properties.getHnsw().isEnabled());
        } catch (IOException e) {
            log.error("Failed to initialize Anserini vector store at path: {}", indexPath, e);
            throw new RuntimeException("Failed to initialize Anserini vector store", e);
        }
    }

    private void initializeIndexWriter() throws IOException {
        this.indexWriter = new IndexWriter(directory, AnseriniIndexUtils.createIndexWriterConfig(properties));
    }

    @Override
    public void add(List<org.springframework.ai.document.Document> documents, List<List<Float>> embeddings) {
        if (documents == null || documents.isEmpty()) {
            log.debug("No documents provided to add to Anserini VectorStore.");
            return;
        }

        if (embeddings != null && embeddings.size() != documents.size()) {
            log.warn("Pre-computed embeddings size ({}) does not match documents size ({}). " +
                    "Will generate embeddings using configured EmbeddingModel.", 
                    embeddings.size(), documents.size());
        }

        synchronized (writerLock) {
            try {
                for (int i = 0; i < documents.size(); i++) {
                    org.springframework.ai.document.Document springAiDoc = documents.get(i);
                    
                    // Use pre-computed embeddings if available and matching, otherwise generate
                    float[] embedding;
                    if (embeddings != null && i < embeddings.size() && embeddings.get(i) != null) {
                        List<Float> embeddingList = embeddings.get(i);
                        embedding = new float[embeddingList.size()];
                        for (int j = 0; j < embeddingList.size(); j++) {
                            embedding[j] = embeddingList.get(j);
                        }
                    } else {
                        // Generate embedding using the EmbeddingModel
                        embedding = embeddingModel.embed(springAiDoc);
                    }

                    Document luceneDoc = createLuceneDocument(springAiDoc, embedding);
                    indexWriter.addDocument(luceneDoc);
                }
                
                indexWriter.commit();
                log.info("Successfully added {} documents to Anserini VectorStore", documents.size());
                
                // Refresh searcher after adding documents
                refreshSearcher();
                
            } catch (IOException e) {
                log.error("Error adding documents to Anserini VectorStore", e);
                throw new RuntimeException("Failed to add documents to Anserini VectorStore", e);
            }
        }
    }

    @Override
    public void add(List<org.springframework.ai.document.Document> documents) {
        add(documents, null);
    }

    @Override
    public boolean delete(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            log.debug("No IDs provided for deletion from Anserini VectorStore.");
            return true;
        }

        synchronized (writerLock) {
            try {
                for (String id : ids) {
                    indexWriter.deleteDocuments(new org.apache.lucene.index.Term(Constants.ID, id));
                }
                indexWriter.commit();
                log.info("Successfully deleted {} documents from Anserini VectorStore", ids.size());
                
                // Refresh searcher after deletion
                refreshSearcher();
                return true;
                
            } catch (IOException e) {
                log.error("Error deleting documents from Anserini VectorStore: {}", ids, e);
                return false;
            }
        }
    }

    @Override
    public List<org.springframework.ai.document.Document> similaritySearch(List<Float> queryEmbedding, int k, double threshold) {
        if (queryEmbedding == null || queryEmbedding.isEmpty()) {
            log.warn("Empty query embedding provided for similarity search");
            return Collections.emptyList();
        }

        float[] queryVector = new float[queryEmbedding.size()];
        for (int i = 0; i < queryEmbedding.size(); i++) {
            queryVector[i] = queryEmbedding.get(i);
        }

        return performSearch(queryVector, k, threshold);
    }

    @Override
    public List<org.springframework.ai.document.Document> similaritySearch(String query, int k) {
        return similaritySearch(query, k, 0.0);
    }

    @Override
    public List<org.springframework.ai.document.Document> similaritySearch(String query, int k, double threshold) {
        if (query == null || query.trim().isEmpty()) {
            log.warn("Empty query string provided for similarity search");
            return Collections.emptyList();
        }

        // Generate embedding for the query string
        float[] queryVector = embeddingModel.embed(query);
        return performSearch(queryVector, k, threshold);
    }

    private List<org.springframework.ai.document.Document> performSearch(float[] queryVector, int k, double threshold) {
        try {
            ensureSearcherInitialized();
            
            ScoredDoc[] scoredDocs = searcher.search(null, queryVector, k);
            List<org.springframework.ai.document.Document> results = new ArrayList<>();

            for (ScoredDoc scoredDoc : scoredDocs) {
                if (scoredDoc.score >= threshold) {
                    org.springframework.ai.document.Document springAiDoc = convertToSpringAiDocument(scoredDoc);
                    if (springAiDoc != null) {
                        results.add(springAiDoc);
                    }
                }
            }

            log.debug("Similarity search returned {} documents (threshold: {})", results.size(), threshold);
            return results;
            
        } catch (IOException e) {
            log.error("Error performing similarity search", e);
            return Collections.emptyList();
        }
    }

    private Document createLuceneDocument(org.springframework.ai.document.Document springAiDoc, float[] embedding) {
        Document luceneDoc = new Document();
        
        // Add document ID
        luceneDoc.add(new StringField(Constants.ID, springAiDoc.getId(), Field.Store.YES));
        luceneDoc.add(new BinaryDocValuesField(Constants.ID, new BytesRef(springAiDoc.getId())));
        
        // Add vector field with configured similarity function
        VectorSimilarityFunction similarityFunction = parseSimilarityFunction(properties.getSimilarityFunction());
        luceneDoc.add(new KnnFloatVectorField(Constants.VECTOR, embedding, similarityFunction));
        
        // Store document content
        luceneDoc.add(new StoredField("content", springAiDoc.getText()));
        
        // Store metadata as JSON
        if (springAiDoc.getMetadata() != null && !springAiDoc.getMetadata().isEmpty()) {
            try {
                String metadataJson = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(springAiDoc.getMetadata());
                luceneDoc.add(new StoredField("metadata", metadataJson));
            } catch (Exception e) {
                log.warn("Failed to serialize metadata for document {}: {}", springAiDoc.getId(), e.getMessage());
            }
        }
        
        return luceneDoc;
    }

    private VectorSimilarityFunction parseSimilarityFunction(String function) {
        switch (function.toUpperCase()) {
            case "DOT_PRODUCT":
                return VectorSimilarityFunction.DOT_PRODUCT;
            case "EUCLIDEAN":
                return VectorSimilarityFunction.EUCLIDEAN;
            case "COSINE":
            default:
                return VectorSimilarityFunction.COSINE;
        }
    }

    private org.springframework.ai.document.Document convertToSpringAiDocument(ScoredDoc scoredDoc) {
        try {
            IndexReader reader = DirectoryReader.open(directory);
            Document luceneDoc = reader.document(Integer.parseInt(scoredDoc.docid));
            
            String id = luceneDoc.get(Constants.ID);
            String content = luceneDoc.get("content");
            String metadataJson = luceneDoc.get("metadata");
            
            Map<String, Object> metadata = new HashMap<>();
            if (metadataJson != null) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsedMetadata = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(metadataJson, Map.class);
                    metadata.putAll(parsedMetadata);
                } catch (Exception e) {
                    log.warn("Failed to parse metadata for document {}: {}", id, e.getMessage());
                }
            }
            
            // Add score to metadata
            metadata.put("score", (double) scoredDoc.score);
            
            reader.close();
            return new org.springframework.ai.document.Document(id, content, metadata);
            
        } catch (IOException e) {
            log.error("Error converting ScoredDoc to Spring AI Document", e);
            return null;
        }
    }

    private void ensureSearcherInitialized() throws IOException {
        if (searcher == null) {
            refreshSearcher();
        }
    }

    private void refreshSearcher() throws IOException {
        try {
            
            searcher = AnseriniSearcherFactory.createSearcher(properties, indexPath);
            
        } catch (Exception e) {
            throw new IOException("Failed to refresh searcher", e);
        }
    }

    @Override
    public void destroy() throws Exception {
        synchronized (writerLock) {
            if (indexWriter != null) {
                indexWriter.close();
            }

            if (directory != null) {
                directory.close();
            }
        }
        log.info("AnseriniVectorStoreImpl destroyed and resources cleaned up");
    }
}