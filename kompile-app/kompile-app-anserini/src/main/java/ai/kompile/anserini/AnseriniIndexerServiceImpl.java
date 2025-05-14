/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.anserini;

import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.loaders.DocumentLoadingService;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.anserini.config.AnseriniConfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.anserini.index.IndexCollection;
import io.anserini.search.SimpleSearcher;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Service("anseriniIndexerService")
public class AnseriniIndexerServiceImpl implements IndexerService {
    private static final Logger logger = LogManager.getLogger(AnseriniIndexerServiceImpl.class);
    private final AnseriniConfig anseriniConfig;
    private final ObjectMapper objectMapper;
    private final DocumentLoadingService documentLoadingService;
    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;

    @Autowired
    public AnseriniIndexerServiceImpl(AnseriniConfig anseriniConfig,
                                      ObjectMapper objectMapper,
                                      DocumentLoadingService documentLoadingService,
                                      EmbeddingModel embeddingModel,
                                      VectorStore vectorStore) {
        this.anseriniConfig = anseriniConfig;
        this.objectMapper = objectMapper;
        this.documentLoadingService = documentLoadingService;
        this.embeddingModel = embeddingModel;
        this.vectorStore = vectorStore;
        logger.debug("AnseriniIndexerServiceImpl constructed with EmbeddingModel and VectorStore.");
    }

    @PostConstruct
    public void initialIndexOnStartup() {
        try {
            logger.info("AnseriniIndexerService PostConstruct: Checking if initial indexing is needed.");
            if (!isKeywordIndexAvailable()) { // This now calls the private helper
                logger.info("Keyword index not available or invalid. Triggering full re-processing and indexing of all sources.");
                reprocessAndIndexAllSources();
            } else {
                logger.info("Anserini keyword index at {} appears to be available and valid. Initial full indexing skipped.", anseriniConfig.getIndexPath());
            }
        } catch (Exception e) {
            logger.error("Error during AnseriniIndexerService initial indexing check/trigger: {}", e.getMessage(), e);
        }
    }

    @Override
    public void reprocessAndIndexAllSources() throws IOException {
        logger.info("Full re-processing and indexing of all sources triggered (Keyword Index + Vector Store).");
        List<Document> allLoadedDocs = documentLoadingService.loadAllConfiguredDocuments();

        if (allLoadedDocs == null || allLoadedDocs.isEmpty()) {
            logger.warn("No documents loaded from sources. Both keyword index and vector store will be (or remain) empty/minimal.");
            createOrClearAnseriniKeywordIndex(Collections.emptyList());
            if (vectorStore != null) {
                try {
                    vectorStore.add(Collections.emptyList());
                } catch (Exception e) {
                    logger.error("Error when calling add with empty list on vector store: {}", e.getMessage(), e);
                }
            }
            return;
        }
        logger.info("Loaded {} documents from sources for indexing.", allLoadedDocs.size());

        if (vectorStore != null) {
            try {
                logger.info("Populating Vector Store with {} documents...", allLoadedDocs.size());
                vectorStore.add(allLoadedDocs);
                logger.info("Successfully submitted documents to Vector Store.");
            } catch (Exception e) {
                logger.error("Failed to populate Vector Store: {}. Keyword indexing will still proceed.", e.getMessage(), e);
            }
        } else {
            logger.warn("VectorStore bean is not available. Skipping vector store population.");
        }
        createOrClearAnseriniKeywordIndex(allLoadedDocs);
    }

    @Override
    public void indexDocuments(List<Document> springAiDocuments) throws IOException {
        logger.info("Keyword indexing (Anserini) and Vector Store population requested for {} documents.",
                springAiDocuments == null ? 0 : springAiDocuments.size());

        if (springAiDocuments != null && !springAiDocuments.isEmpty()) {
            if (vectorStore != null) {
                try {
                    logger.info("Populating Vector Store with {} specific documents...", springAiDocuments.size());
                    vectorStore.add(springAiDocuments);
                    logger.info("Successfully submitted {} specific documents to Vector Store.", springAiDocuments.size());
                } catch (Exception e) {
                    logger.error("Failed to populate Vector Store with specific documents: {}. Keyword indexing will still proceed.", e.getMessage(), e);
                }
            } else {
                logger.warn("VectorStore bean is not available. Skipping vector store population for specific documents.");
            }
        }
        createOrClearAnseriniKeywordIndex(springAiDocuments);
    }

    private void createOrClearAnseriniKeywordIndex(List<Document> springAiDocuments) throws IOException {
        // ... (Content of this method for JSON staging and IndexCollection remains exactly as in the previous response)
        if (anseriniConfig.getCorpusPath() == null || anseriniConfig.getIndexPath() == null) {
            String msg = "Anserini corpusPath (for staging) or indexPath is not configured. Cannot create keyword index.";
            logger.error(msg);
            throw new IOException(msg);
        }
        Path stagingPath = Paths.get(anseriniConfig.getCorpusPath());
        Path indexPath = Paths.get(anseriniConfig.getIndexPath());
        logger.info("Preparing Anserini keyword index for {} documents. Staging JSON at: {}, Final index at: {}",
                springAiDocuments == null ? 0 : springAiDocuments.size(), stagingPath, indexPath);

        // 1. Clean or create staging directory
        if (Files.exists(stagingPath)) {
            try (Stream<Path> walk = Files.walk(stagingPath)) {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
        Files.createDirectories(stagingPath);
        logger.info("Keyword index staging directory {} prepared.", stagingPath);

        // 2. Convert Spring AI Documents to Anserini-consumable JSON
        int docCounter = 0;
        if (springAiDocuments != null && !springAiDocuments.isEmpty()) {
            for (Document springDoc : springAiDocuments) {
                if (springDoc == null || springDoc.getText() == null || springDoc.getText().trim().isEmpty()) {
                    logger.warn("Skipping a null document or document with empty content for keyword index. Metadata: {}", springDoc != null ? springDoc.getMetadata() : "null document");
                    continue;
                }
                ObjectNode anseriniJsonDoc = objectMapper.createObjectNode();

                String idFromMeta = springDoc.getMetadata().get("id") != null ? springDoc.getMetadata().get("id").toString() : null;
                String sourcePath = springDoc.getMetadata().get("source_path_or_url") != null ? springDoc.getMetadata().get("source_path_or_url").toString() : null;
                String originalFileName = springDoc.getMetadata().get("original_filename") != null ? springDoc.getMetadata().get("original_filename").toString() : null;
                String pageNum = springDoc.getMetadata().get("page_number") != null ? springDoc.getMetadata().get("page_number").toString() : "";

                String baseId = idFromMeta;
                if (baseId == null || baseId.trim().isEmpty()) {
                    baseId = originalFileName;
                }
                if (baseId == null || baseId.trim().isEmpty()) {
                    baseId = (sourcePath != null && !sourcePath.equals("/") && Paths.get(sourcePath).getFileName() != null) ?
                            Paths.get(sourcePath).getFileName().toString() :
                            UUID.randomUUID().toString();
                }
                if (!pageNum.isEmpty() && !baseId.contains("_p" + pageNum)) {
                    baseId += "_p" + pageNum;
                }
                String sanitizedId = baseId.replaceAll("[^a-zA-Z0-9_.-]", "_");
                if (sanitizedId.length() > 200) sanitizedId = sanitizedId.substring(0, 200);
                if (sanitizedId.isEmpty()) sanitizedId = "doc_" + UUID.randomUUID().toString().substring(0,8);

                anseriniJsonDoc.put("id", sanitizedId + "_" + docCounter);
                anseriniJsonDoc.put("contents", springDoc.getText());

                Path jsonFile = stagingPath.resolve(sanitizedId + "_" + docCounter++ + ".json");
                try {
                    Files.writeString(jsonFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(anseriniJsonDoc),
                            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (IOException e) {
                    logger.error("Failed to write Anserini JSON for document (id base {}): {}", sanitizedId, e.getMessage());
                }
            }
        }
        logger.info("{} documents for Anserini keyword index converted to JSON and written to staging directory {}.", docCounter, stagingPath);

        // 3. Clean or create the final Anserini index directory
        if (Files.exists(indexPath)) {
            try(Stream<Path> walk = Files.walk(indexPath)) {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
        Files.createDirectories(indexPath);

        // 4. Indexing logic: Use IndexCollection or create minimal empty index
        if (docCounter == 0) {
            logger.warn("No documents were processed to JSON for keyword index. Creating a minimal empty Lucene index at {}.", indexPath);
            try (Directory dir = FSDirectory.open(indexPath);
                 IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
                writer.commit();
            }
            logger.info("Minimal empty Lucene keyword index created at {}.", indexPath);
        } else {
            logger.info("Starting Anserini keyword indexing for {} documents from {} to {}", docCounter, stagingPath, indexPath);
            IndexCollection.Args args = new IndexCollection.Args();
            args.input = stagingPath.toString();
            args.collectionClass = "JsonCollection";
            args.generatorClass = "DefaultLuceneDocumentGenerator";
            args.index = indexPath.toString();
            args.threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
            args.storePositions = true;
            args.storeDocvectors = true;
            args.storeRaw = true;

            try {
                IndexCollection indexer = new IndexCollection(args);
                indexer.run();
                logger.info("Anserini keyword indexing completed using IndexCollection for {} documents.", docCounter);
            } catch (Exception e) {
                logger.error("Error during Anserini IndexCollection from {}: {}", stagingPath, e.getMessage(), e);
                throw new IOException("Failed to create keyword index with Anserini IndexCollection: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Checks if the Anserini keyword index is available and valid.
     * @return true if the index exists and can be opened by SimpleSearcher, false otherwise.
     */
    private boolean isKeywordIndexAvailable() { // <<<< THIS METHOD IS NOW DEFINED
        if (anseriniConfig.getIndexPath() == null) {
            logger.warn("isKeywordIndexAvailable: Anserini index path is not configured.");
            return false;
        }
        Path indexPath = Paths.get(anseriniConfig.getIndexPath());
        if (Files.exists(indexPath) && Files.isDirectory(indexPath)) {
            // A more robust check is to try opening it with SimpleSearcher
            try (SimpleSearcher checker = new SimpleSearcher(indexPath.toString())) {
                // If constructor doesn't throw, index is considered basically valid
                logger.debug("isKeywordIndexAvailable: Anserini keyword index at {} successfully opened by SimpleSearcher.", indexPath);
                return true;
            } catch (Exception e) {
                // This will catch IndexNotFoundException if segments are missing, or other errors
                logger.warn("isKeywordIndexAvailable: Anserini keyword index at {} exists but is not valid/readable by SimpleSearcher: {}", indexPath, e.getMessage());
                return false;
            }
        }
        logger.warn("isKeywordIndexAvailable: Anserini keyword index path {} does not exist or is not a directory.", indexPath);
        return false;
    }

    @Override
    public boolean isIndexAvailable() {
        // The IndexerService.isIndexAvailable() can now reflect a combined state
        // or be more specific if needed. For now, it primarily reflects the keyword index.
        boolean keywordIndexOk = isKeywordIndexAvailable();
        if (!keywordIndexOk) {
            logger.warn("Primary Anserini keyword index is not available.");
        }
        // You could add a check for vectorStore.isReady() or similar if your VectorStore interface had such a method
        // For now, this IndexerService's availability is tied to its primary index (Anserini keyword index).
        return keywordIndexOk;
    }
}