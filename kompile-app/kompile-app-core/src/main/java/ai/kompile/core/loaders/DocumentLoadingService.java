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

package ai.kompile.core.loaders;

import org.springframework.ai.document.Document;
import java.util.List;

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
}