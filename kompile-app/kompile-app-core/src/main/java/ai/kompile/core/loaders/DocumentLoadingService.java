/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

package ai.kompile.core.loaders;

import org.springframework.ai.document.Document;
import java.util.List;
import java.util.Map;

/**
 * Service interface for orchestrating the loading of documents from various
 * configured sources using available DocumentLoader implementations.
 */
public interface DocumentLoadingService {

    /**
     * Loads all documents from the sources configured for the application.
     * Implementations will typically use a list of available {@link DocumentLoader}
     * beans to process different types of sources.
     *
     * @return A list of all loaded Spring AI {@link Document} objects.
     * Returns an empty list if no sources are configured or no documents are found.
     */
    List<Document> loadAllConfiguredDocuments();

    /**
     * Loads documents from a specific source descriptor using a specified loader.
     *
     * @param sourceDescriptor The descriptor of the document source.
     * @param loaderName The name of the {@link DocumentLoader} to use.
     * @return A list of Spring AI {@link Document} objects.
     * @throws Exception if loading fails or the specified loader is not found/supports the source.
     */
    List<Document> loadDocumentsFromSource(DocumentSourceDescriptor sourceDescriptor, String loaderName) throws Exception;

    /**
     * Loads documents from a list of source descriptors, each potentially with its own loader or a default one.
     * This method is intended for batch processing.
     *
     * @param sourceRequests List of {@link BatchLoadRequestItem}.
     * @param defaultLoaderName Optional default loader name to use if a request item doesn't specify one.
     * @return A map where keys are source paths/URLs and values are lists of loaded Spring AI {@link Document} objects or error messages.
     */
    Map<String, Object> loadDocumentsBatch(List<BatchLoadRequestItem> sourceRequests, String defaultLoaderName);

    // Helper record for batch requests
    record BatchLoadRequestItem(String pathOrUrl, DocumentSourceDescriptor.SourceType type, String loaderName, Map<String, Object> metadata) {}

}