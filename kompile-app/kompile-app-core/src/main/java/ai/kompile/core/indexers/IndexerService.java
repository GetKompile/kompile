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

package ai.kompile.core.indexers;

import org.springframework.ai.document.Document; // From spring-ai-commons
import java.io.IOException;
import java.util.List;

public interface IndexerService {
    /**
     * Indexes the provided list of Spring AI documents.
     * Implementations will handle any necessary staging (e.g., to JSON) and the actual indexing logic.
     */
    void indexDocuments(List<Document> documents) throws IOException;

    /**
     * Triggers a full re-indexing process.
     * This typically involves using a DocumentLoadingService to fetch all documents
     * and then passing them to the indexDocuments(List<Document> documents) method.
     */
    void reprocessAndIndexAllSources() throws IOException;

    /**
     * Checks if the underlying index is considered valid and ready for querying.
     * @return true if the index is available, false otherwise.
     */
    boolean isIndexAvailable();
}