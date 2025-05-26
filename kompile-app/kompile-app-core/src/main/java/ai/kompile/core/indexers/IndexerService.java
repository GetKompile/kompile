/*
 * Copyright 2025 Kompile Inc.
 * *
 * * Licensed under the Apache License, Version 2.0 (the "License");
 * * you may not use this file except in compliance with the License.
 * * You may obtain a copy of the License at
 * *
 * * http://www.apache.org/licenses/LICENSE-2.0
 * *
 * * Unless required by applicable law or agreed to in writing, software
 * * distributed under the License is distributed on an "AS IS" BASIS,
 * * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * * See the License for the specific language governing permissions and
 * * limitations under the License.
 */

package ai.kompile.core.indexers;

import org.springframework.ai.document.Document; // From spring-ai-commons
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public abstract class IndexerService {
    public abstract void indexDocuments(List<Document> documents, String collectionNameParam);

    public abstract void indexFile(Path filePath, String sourceId, String collectionNameParam) throws IOException;

    public abstract void indexDirectory(Path directoryPath, String sourceIdPrefix, String collectionNameParam) throws IOException;

    public abstract boolean deleteDocuments(List<String> documentIds, String collectionNameParam);

    public abstract boolean deleteAll(String collectionNameParam);

    public abstract long getApproxTotalDocCount(String collectionNameParam);

    /**
     * Indexes the provided list of Spring AI documents.
     * Implementations will handle any necessary staging (e.g., to JSON) and the actual indexing logic.
     */
    public abstract void indexDocuments(List<Document> documents) throws IOException;

    /**
     * Triggers a full re-indexing process.
     * This typically involves using a DocumentLoadingService to fetch all documents
     * and then passing them to the indexDocuments(List<Document> documents) method.
     */
    public abstract void reprocessAndIndexAllSources() throws IOException;

    /**
     * Checks if the underlying index is considered valid and ready for querying.
     * @return true if the index is available, false otherwise.
     */
    public abstract boolean isIndexAvailable();

    // New methods for Index Browser
    /**
     * Retrieves a list of information about documents/chunks in the index.
     * @param offset the starting offset for pagination
     * @param limit the maximum number of documents to return
     * @return a list of maps, where each map represents an indexed document's info (e.g., id, stored fields).
     * @throws IOException if there is an error reading from the index
     */
    public abstract List<Map<String, Object>> listIndexedDocuments(int offset, int limit) throws IOException;

    /**
     * Retrieves a specific document/chunk from the index by its ID.
     * @param docId The ID of the document/chunk to retrieve.
     * @return A map representing the document's fields, or null if not found.
     * @throws IOException if there is an error reading from the index
     */
    public abstract Map<String, Object> getIndexedDocument(String docId) throws IOException;

    /**
     * Updates the content of a specific document/chunk in the keyword index.
     * Note: This is intended for debugging and directly modifies the Lucene index.
     * It does not update the original source document or the vector store.
     * @param docId The ID of the document/chunk to update.
     * @param newContent The new content for the document.
     * @return true if the update was successful, false otherwise.
     * @throws IOException if there is an error updating the index
     */
    public abstract boolean updateIndexedDocumentContent(String docId, String newContent) throws IOException;
}