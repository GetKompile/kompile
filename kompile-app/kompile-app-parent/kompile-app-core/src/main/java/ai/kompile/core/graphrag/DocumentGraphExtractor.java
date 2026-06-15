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

package ai.kompile.core.graphrag;

import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Common contract for deterministic, rule-based graph extractors that convert
 * document metadata into structured knowledge graph entities and relationships.
 *
 * <p>Each implementation handles a specific document type (PDF, email, Office, etc.)
 * and produces entities (PERSON, ORGANIZATION, DOCUMENT, etc.) and relationships
 * (AUTHORED_BY, PRODUCED_BY, HYPERLINK_TO, etc.) from the document's metadata —
 * no LLM needed.</p>
 *
 * <p>Implementations should:</p>
 * <ul>
 *   <li>Use deterministic entity IDs via {@code UUID.nameUUIDFromBytes(key.getBytes())}
 *       for stable cross-document deduplication</li>
 *   <li>Return empty results (not null) when no extractable metadata is present</li>
 *   <li>Set provenance to {@code "EXTRACTED"} for all relationships</li>
 * </ul>
 */
public interface DocumentGraphExtractor {

    /**
     * Returns the document types this extractor handles (e.g., "pdf", "email", "office").
     * Used by the ingest pipeline to route documents to the correct extractor.
     */
    List<String> supportedDocumentTypes();

    /**
     * Returns true if this extractor can process the given document based on its metadata.
     *
     * @param doc a loaded document with metadata
     * @return true if this extractor should handle the document
     */
    boolean canExtract(Document doc);

    /**
     * Extracts entities and relationships from a single document's metadata.
     *
     * @param doc a document produced by a DocumentLoader
     * @return an ExtractionResult with deterministic entities and relationships
     */
    ExtractionResult extract(Document doc);

    /**
     * Extracts a graph from a list of documents, deduplicating entities by ID.
     *
     * @param docs documents of the same type
     * @return merged ExtractionResult across all documents
     */
    ExtractionResult extractBatch(List<Document> docs);
}
