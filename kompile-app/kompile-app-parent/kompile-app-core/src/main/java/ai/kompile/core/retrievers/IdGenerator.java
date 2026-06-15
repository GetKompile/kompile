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

package ai.kompile.core.retrievers;

import java.util.Map;

/**
 * Interface for generating unique identifiers for documents.
 * 
 * Implementations can use various strategies for ID generation,
 * including random UUIDs, content-based hashing, or custom logic.
 */
public interface IdGenerator {
    
    /**
     * Generates a unique identifier for a document.
     * 
     * @return a unique identifier string
     */
    String generateId();
    
    /**
     * Generates a unique identifier for a document based on its content and metadata.
     * 
     * @param content the document content (may be null for media documents)
     * @param metadata the document metadata
     * @return a unique identifier string
     */
    String generateId(String content, Map<String, Object> metadata);
}
