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

import org.springframework.ai.document.Document; // Spring AI's Document class
import java.util.List;

/**
 * Interface for components capable of loading documents from a specific type of source.
 * Implementations will handle specific file formats (PDF, TXT, HTML from URL, etc.).
 */
public interface DocumentLoader {

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
}