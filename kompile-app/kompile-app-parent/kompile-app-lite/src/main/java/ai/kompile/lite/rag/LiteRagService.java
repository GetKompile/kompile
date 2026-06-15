/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.lite.rag;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.graphrag.GraphConstructor;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Document ingestion pipeline for Kompile Lite.
 * Handles file upload, text extraction, chunking, embedding, and indexing.
 */
@Service
public class LiteRagService {

    private static final Logger logger = LoggerFactory.getLogger(LiteRagService.class);

    @Autowired
    private VectorStore vectorStore;

    @Autowired(required = false)
    private EmbeddingModel embeddingModel;

    @Autowired(required = false)
    private GraphConstructor graphConstructor;

    /**
     * Ingest a file: extract text, chunk, embed, and index.
     */
    public IngestResult ingestFile(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        logger.info("Ingesting file: {} ({} bytes)", filename, file.getSize());

        // Save to temp file for processing
        Path tempFile = Files.createTempFile("kompile-lite-", getSuffix(filename));
        file.transferTo(tempFile.toFile());

        try {
            // Extract text based on file type
            String text = extractText(tempFile, filename);
            if (text == null || text.isBlank()) {
                return new IngestResult(filename, 0, 0, "No text could be extracted from file");
            }

            // Chunk the text
            List<String> chunks = chunkText(text);
            logger.info("Created {} chunks from {}", chunks.size(), filename);

            // Create documents with metadata
            List<Document> documents = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("source", filename);
                metadata.put("chunk_index", i);
                metadata.put("total_chunks", chunks.size());
                documents.add(new Document(chunks.get(i), metadata));
            }

            // Embed and index
            int indexed;
            if (embeddingModel != null) {
                List<String> texts = documents.stream().map(Document::getText).collect(Collectors.toList());
                INDArray embeddings = embeddingModel.embed(texts);
                indexed = vectorStore.addWithEmbeddings(documents, embeddings);
            } else {
                indexed = vectorStore.add(documents);
            }

            vectorStore.flushAndCommit();
            logger.info("Indexed {} documents from {}", indexed, filename);

            return new IngestResult(filename, chunks.size(), indexed, "Success");
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * Ingest raw text directly.
     */
    public IngestResult ingestText(String text, String sourceName) {
        if (text == null || text.isBlank()) {
            return new IngestResult(sourceName, 0, 0, "Empty text provided");
        }

        List<String> chunks = chunkText(text);
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", sourceName);
            metadata.put("chunk_index", i);
            documents.add(new Document(chunks.get(i), metadata));
        }

        int indexed;
        if (embeddingModel != null) {
            List<String> texts = documents.stream().map(Document::getText).collect(Collectors.toList());
            INDArray embeddings = embeddingModel.embed(texts);
            indexed = vectorStore.addWithEmbeddings(documents, embeddings);
        } else {
            indexed = vectorStore.add(documents);
        }

        vectorStore.flushAndCommit();
        return new IngestResult(sourceName, chunks.size(), indexed, "Success");
    }

    /**
     * List indexed documents.
     */
    public List<Map<String, Object>> listDocuments(int offset, int limit) {
        return vectorStore.listVectorDocuments(offset, limit);
    }

    /**
     * Delete documents by ID.
     */
    public boolean deleteDocuments(List<String> ids) {
        return vectorStore.delete(ids);
    }

    /**
     * Delete all documents.
     */
    public boolean deleteAll() {
        return vectorStore.deleteAll();
    }

    /**
     * Get approximate document count.
     */
    public long getDocumentCount() {
        return vectorStore.getApproxVectorCount();
    }

    /**
     * Trigger graph construction from indexed documents.
     */
    public String buildGraph() {
        if (graphConstructor == null) {
            return "Graph constructor not available";
        }

        try {
            var graph = graphConstructor.constructGraph("default");
            return "Graph constructed successfully with " +
                    (graph != null ? graph.getEntities().size() + " entities and " + graph.getRelationships().size() + " relationships" : "0 entities");
        } catch (Exception e) {
            logger.error("Graph construction failed: {}", e.getMessage());
            return "Graph construction failed: " + e.getMessage();
        }
    }

    private String extractText(Path filePath, String filename) {
        try {
            String lowerName = filename != null ? filename.toLowerCase() : "";
            if (lowerName.endsWith(".txt") || lowerName.endsWith(".md") || lowerName.endsWith(".csv")) {
                return Files.readString(filePath);
            }
            // For PDF and other formats, try to use available loaders via Spring context
            // Fall back to reading as plain text
            return Files.readString(filePath);
        } catch (IOException e) {
            logger.warn("Failed to extract text from {}: {}", filename, e.getMessage());
            return null;
        }
    }

    private List<String> chunkText(String text) {
        // Simple sentence-based chunking with overlap
        int chunkSize = 500;
        int overlap = 50;

        List<String> chunks = new ArrayList<>();
        String[] sentences = text.split("(?<=[.!?])\\s+");

        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            if (current.length() + sentence.length() > chunkSize && current.length() > 0) {
                chunks.add(current.toString().trim());
                // Keep overlap
                String overlapText = current.substring(Math.max(0, current.length() - overlap));
                current = new StringBuilder(overlapText);
            }
            current.append(sentence).append(" ");
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }

        return chunks;
    }

    private String getSuffix(String filename) {
        if (filename == null) return ".tmp";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : ".tmp";
    }

    public record IngestResult(String filename, int chunks, int indexed, String status) {}
}
