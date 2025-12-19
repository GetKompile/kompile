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

package ai.kompile.core.indexers;

import ai.kompile.core.retrievers.RetrievedDoc;
import org.springframework.ai.document.Document;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public abstract class IndexerService {
    public abstract void indexDocuments(List<RetrievedDoc> documents, String collectionNameParam);

    /**
     * Indexes documents with pre-computed embeddings.
     * This allows the embedding step to be separated from the indexing step for
     * better parallelization.
     *
     * @param documents  The documents to index
     * @param embeddings Pre-computed embeddings for each document (same order as
     *                   documents)
     * @throws IOException if there is an error during indexing
     */
    public abstract void indexDocumentsWithEmbeddings(List<Document> documents, List<List<Float>> embeddings)
            throws IOException;

    /**
     * Indexes RetrievedDoc documents with pre-computed float[] embeddings.
     * This is the most efficient method for bulk indexing as it avoids boxing
     * overhead.
     * <p>
     * The embedding and indexing stages can run in parallel when using this method,
     * as embeddings are computed ahead of time by a separate worker.
     *
     * @param documents  The documents to index
     * @param embeddings Pre-computed embeddings as float[] arrays (same order as
     *                   documents)
     * @throws IOException if there is an error during indexing
     */
    public void indexDocumentsWithFloatEmbeddings(List<RetrievedDoc> documents, List<float[]> embeddings)
            throws IOException {
        // Default implementation converts to List<Float> for backward compatibility
        if (documents == null || documents.isEmpty()) {
            return;
        }

        List<Document> springDocs = new java.util.ArrayList<>(documents.size());
        List<List<Float>> floatEmbeddings = embeddings != null ? new java.util.ArrayList<>(embeddings.size()) : null;

        for (int i = 0; i < documents.size(); i++) {
            RetrievedDoc doc = documents.get(i);
            springDocs.add(new Document(doc.getId(), doc.getText(), doc.getMetadata()));

            if (embeddings != null && i < embeddings.size() && embeddings.get(i) != null) {
                float[] emb = embeddings.get(i);
                List<Float> floatList = new java.util.ArrayList<>(emb.length);
                for (float f : emb) {
                    floatList.add(f);
                }
                floatEmbeddings.add(floatList);
            }
        }

        if (floatEmbeddings != null && !floatEmbeddings.isEmpty()) {
            indexDocumentsWithEmbeddings(springDocs, floatEmbeddings);
        } else {
            indexDocuments(documents);
        }
    }

    /**
     * Indexes documents to the keyword index only (no vector store).
     * This method enables parallel indexing where keyword and vector stores
     * are updated independently by separate workers.
     *
     * @param documents The documents to index
     * @throws IOException if there is an error during indexing
     */
    public void indexToKeywordIndexOnly(List<RetrievedDoc> documents) throws IOException {
        // Default: delegates to full indexDocuments (implementations should override)
        indexDocuments(documents);
    }

    /**
     * Indexes documents to the vector store only (no keyword index).
     * This method enables parallel indexing where keyword and vector stores
     * are updated independently by separate workers.
     *
     * @param documents  The documents to index
     * @param embeddings Pre-computed embeddings as float[] arrays
     * @return The actual number of documents successfully persisted to the vector store.
     *         This may be less than documents.size() if some documents were skipped.
     * @throws IOException if there is an error during indexing
     */
    public int indexToVectorStoreOnly(List<RetrievedDoc> documents, List<float[]> embeddings) throws IOException {
        // Default: delegates to full indexDocumentsWithFloatEmbeddings (implementations
        // should override)
        indexDocumentsWithFloatEmbeddings(documents, embeddings);
        return documents != null ? documents.size() : 0; // Default assumes all succeeded
    }

    /**
     * Result of a parallel indexing operation containing actual counts of
     * documents persisted to each store.
     */
    public record IndexingResult(int keywordIndexed, int vectorIndexed) {
        /**
         * Returns the minimum of keyword and vector indexed counts.
         * This represents the guaranteed number of documents fully indexed.
         */
        public int minIndexed() {
            return Math.min(keywordIndexed, vectorIndexed);
        }
    }

    /**
     * Indexes documents in parallel to both keyword index and vector store.
     * This is the most efficient method for bulk indexing as it:
     * 1. Accepts pre-computed float[] embeddings (no re-embedding)
     * 2. Updates keyword index and vector store concurrently
     * 3. Returns a future that completes when both indexes are updated
     *
     * @param documents  The documents to index
     * @param embeddings Pre-computed embeddings as float[] arrays
     * @return A CompletableFuture that completes with the actual counts of documents indexed
     */
    public java.util.concurrent.CompletableFuture<IndexingResult> indexDocumentsParallel(
            List<RetrievedDoc> documents, List<float[]> embeddings) {
        // Default implementation runs keyword and vector indexing in parallel
        if (documents == null || documents.isEmpty()) {
            return java.util.concurrent.CompletableFuture.completedFuture(new IndexingResult(0, 0));
        }

        // Use atomic integers to capture actual counts from each async task
        java.util.concurrent.atomic.AtomicInteger keywordCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger vectorCount = new java.util.concurrent.atomic.AtomicInteger(0);

        java.util.concurrent.CompletableFuture<Void> keywordFuture = java.util.concurrent.CompletableFuture
                .runAsync(() -> {
                    try {
                        indexToKeywordIndexOnly(documents);
                        keywordCount.set(documents.size()); // Keyword indexing doesn't return count yet
                    } catch (IOException e) {
                        throw new java.util.concurrent.CompletionException("Keyword indexing failed", e);
                    }
                });

        java.util.concurrent.CompletableFuture<Void> vectorFuture = java.util.concurrent.CompletableFuture
                .runAsync(() -> {
                    try {
                        int indexed = indexToVectorStoreOnly(documents, embeddings);
                        vectorCount.set(indexed);
                    } catch (IOException e) {
                        throw new java.util.concurrent.CompletionException("Vector indexing failed", e);
                    }
                });

        return java.util.concurrent.CompletableFuture.allOf(keywordFuture, vectorFuture)
                .thenApply(v -> new IndexingResult(keywordCount.get(), vectorCount.get()));
    }

    public abstract void indexFile(Path filePath, String sourceId, String collectionNameParam) throws IOException;

    public abstract void indexDirectory(Path directoryPath, String sourceIdPrefix, String collectionNameParam)
            throws IOException;

    public abstract boolean deleteDocuments(List<String> documentIds, String collectionNameParam);

    public abstract boolean deleteAll(String collectionNameParam);

    public abstract long getApproxTotalDocCount(String collectionNameParam);

    /**
     * Indexes the provided list of RetrievedDoc documents.
     * Implementations will handle any necessary staging (e.g., to JSON) and the
     * actual indexing logic.
     */
    public abstract void indexDocuments(List<RetrievedDoc> documents) throws IOException;

    /**
     * Triggers a full re-indexing process.
     * This typically involves using a DocumentLoadingService to fetch all documents
     * and then passing them to the indexDocuments(List<RetrievedDoc> documents)
     * method.
     */
    public abstract void reprocessAndIndexAllSources() throws IOException;

    /**
     * Checks if the underlying index is considered valid and ready for querying.
     * 
     * @return true if the index is available, false otherwise.
     */
    public abstract boolean isIndexAvailable();

    // New methods for Index Browser
    /**
     * Retrieves a list of information about documents/chunks in the index.
     * 
     * @param offset the starting offset for pagination
     * @param limit  the maximum number of documents to return
     * @return a list of maps, where each map represents an indexed document's info
     *         (e.g., id, stored fields).
     * @throws IOException if there is an error reading from the index
     */
    public abstract List<Map<String, Object>> listIndexedDocuments(int offset, int limit) throws IOException;

    /**
     * Retrieves a specific document/chunk from the index by its ID.
     * 
     * @param docId The ID of the document/chunk to retrieve.
     * @return A map representing the document's fields, or null if not found.
     * @throws IOException if there is an error reading from the index
     */
    public abstract Map<String, Object> getIndexedDocument(String docId) throws IOException;

    /**
     * Updates the content of a specific document/chunk in the keyword index.
     * Note: This is intended for debugging and directly modifies the Lucene index.
     * It does not update the original source document or the vector store.
     * 
     * @param docId      The ID of the document/chunk to update.
     * @param newContent The new content for the document.
     * @return true if the update was successful, false otherwise.
     * @throws IOException if there is an error updating the index
     */
    public abstract boolean updateIndexedDocumentContent(String docId, String newContent) throws IOException;

    /**
     * Returns the filesystem path where the index is stored.
     *
     * @return The absolute path to the index directory, or a descriptive string if
     *         not applicable.
     */
    public abstract String getIndexPath();

    /**
     * Switches the indexer to use a different index path.
     * <p>
     * This allows dynamic switching between different keyword indices at runtime,
     * which is useful for per-fact-sheet index storage. After switching, the indexer
     * will read from and write to the new location.
     * </p>
     * <p>
     * Implementations should close any existing resources for the old path and
     * open/create resources for the new path. If the new path doesn't exist,
     * implementations should create it.
     * </p>
     *
     * @param newPath The new index path to switch to
     * @return true if the switch was successful, false otherwise
     */
    public boolean switchIndexPath(String newPath) {
        // Default implementation does nothing - implementations that support
        // index path switching should override this
        return false;
    }

    /**
     * Indexes all documents directly from the underlying keyword index (e.g.
     * Lucene)
     * into the Vector Store.
     * This avoids re-parsing original source files and allows bootstrapping
     * detailed
     * vector indices from existing text indices.
     * 
     * @throws IOException if there is an error reading the source index or writing
     *                     to
     *                     the vector store.
     */
    /**
     * Indexes all documents directly from the underlying keyword index (e.g.
     * Lucene)
     * into the Vector Store.
     * This avoids re-parsing original source files and allows bootstrapping
     * detailed
     * vector indices from existing text indices.
     * 
     * @throws IOException if there is an error reading the source index or writing
     *                     to
     *                     the vector store.
     */
    public abstract void indexFromLucene() throws IOException;

    // --- Async Job Management ---

    public enum JobState {
        IDLE,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public static class JobStatus {
        private JobState state;
        private String message;
        private int percentComplete;
        private long documentsProcessed;

        public JobStatus(JobState state, String message, int percentComplete, long documentsProcessed) {
            this.state = state;
            this.message = message;
            this.percentComplete = percentComplete;
            this.documentsProcessed = documentsProcessed;
        }

        public static JobStatus idle() {
            return new JobStatus(JobState.IDLE, "No active job", 0, 0);
        }

        public JobState getState() {
            return state;
        }

        public String getMessage() {
            return message;
        }

        public int getPercentComplete() {
            return percentComplete;
        }

        public long getDocumentsProcessed() {
            return documentsProcessed;
        }
    }

    /**
     * Starts the vector index creation process asynchronously.
     * 
     * @return true if job started, false if a job is already running.
     */
    public abstract boolean startVectorIndexCreationAsync();

    /**
     * Requests cancellation of the current async job.
     */
    public abstract void cancelCurrentJob();

    /**
     * Gets the current status of the async job.
     * 
     * @return JobStatus object.
     */
    public abstract JobStatus getJobStatus();
}
