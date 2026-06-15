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

package ai.kompile.core.loaders;

import org.springframework.ai.document.Document; // Spring AI's Document class
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Interface for components capable of loading documents from a specific type of source.
 * Implementations will handle specific file formats (PDF, TXT, HTML from URL, etc.).
 */
public interface DocumentLoader {

    /**
     * Gets a user-friendly name for this loader.
     * @return The name of the loader.
     */
    String getName();

    /**
     * Checks if this loader can handle the given source descriptor.
     *
     * @param sourceDescriptor The descriptor of the document source.
     * @return true if this loader supports the source type and path, false otherwise.
     */
    boolean supports(DocumentSourceDescriptor sourceDescriptor);

    /**
     * Loads Spring AI Document objects from the given source.
     *
     * @param sourceDescriptor The descriptor of the document source.
     * @return A list of Spring AI {@link Document} objects.
     * @throws Exception if loading fails for any reason (e.g., IOException, parsing errors).
     */
    List<Document> load(DocumentSourceDescriptor sourceDescriptor) throws Exception;

    /**
     * Loads documents with a progress callback for real-time status updates.
     * Default implementation delegates to {@link #load(DocumentSourceDescriptor)} ignoring the callback.
     *
     * @param sourceDescriptor The descriptor of the document source.
     * @param progressCallback Callback for reporting loading progress (may be null).
     * @return A list of Spring AI {@link Document} objects.
     * @throws Exception if loading fails for any reason.
     */
    default List<Document> load(DocumentSourceDescriptor sourceDescriptor,
                                Consumer<LoaderProgress> progressCallback) throws Exception {
        return load(sourceDescriptor);
    }

    /**
     * Progress information reported by a document loader during processing.
     */
    record LoaderProgress(
        String phase,          // "OCR_PROCESSING", "PARSING", etc.
        int progressPercent,   // 0-100 within this loader
        String currentStep,
        String message,
        Map<String, Object> metrics  // Extensible metrics map
    ) {}
}