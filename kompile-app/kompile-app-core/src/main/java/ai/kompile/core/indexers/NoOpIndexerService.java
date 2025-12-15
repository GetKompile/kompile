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
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;


@Service
public class NoOpIndexerService extends IndexerService {
    @Override
    public void indexDocuments(List<RetrievedDoc> documents, String collectionNameParam) {

    }

    @Override
    public void indexFile(Path filePath, String sourceId, String collectionNameParam) throws IOException {

    }

    @Override
    public void indexDirectory(Path directoryPath, String sourceIdPrefix, String collectionNameParam) throws IOException {

    }

    @Override
    public boolean deleteDocuments(List<String> documentIds, String collectionNameParam) {
        return false;
    }

    @Override
    public boolean deleteAll(String collectionNameParam) {
        return false;
    }

    @Override
    public long getApproxTotalDocCount(String collectionNameParam) {
        return 0;
    }

    @Override
    public void indexDocuments(List<RetrievedDoc> documents) throws IOException {
        // No-op
    }

    @Override
    public void indexDocumentsWithEmbeddings(List<Document> documents, List<List<Float>> embeddings) throws IOException {
        // No-op
    }

    @Override
    public void reprocessAndIndexAllSources() throws IOException {

    }

    @Override
    public boolean isIndexAvailable() {
        return false;
    }

    @Override
    public List<Map<String, Object>> listIndexedDocuments(int offset, int limit) throws IOException {
        return null;
    }

    @Override
    public Map<String, Object> getIndexedDocument(String docId) throws IOException {
        return null;
    }

    @Override
    public boolean updateIndexedDocumentContent(String docId, String newContent) throws IOException {
        return false;
    }
}

