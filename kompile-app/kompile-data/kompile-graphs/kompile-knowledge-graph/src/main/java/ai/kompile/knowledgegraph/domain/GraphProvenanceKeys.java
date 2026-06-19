/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reserved {@code metadataJson} keys used to record where a node/edge came from, plus a helper
 * to project a node's provenance.
 *
 * <p>Provenance rides in {@link GraphNode#getMetadata()} — the store-agnostic seam that both the
 * JPA and the (@Primary) matrix/vector store surface — rather than in dedicated JPA columns,
 * which the live matrix store would not persist. Because Phase-1 portability carries node
 * metadata, provenance recorded here also travels with a {@code git clone}.</p>
 */
public final class GraphProvenanceKeys {

    /** Coarse origin label, e.g. {@code "crawl"} or {@code "channel:slack"}. */
    public static final String SOURCE = "_source";
    /** Identifier of the source document the fact was extracted from. */
    public static final String SOURCE_DOCUMENT_ID = "_sourceDocumentId";
    /** Identifier of the source chunk the fact was extracted from. */
    public static final String SOURCE_CHUNK_ID = "_sourceChunkId";
    /** Crawl run / job id that produced the fact. */
    public static final String CRAWL_RUN_ID = "_crawlRunId";
    /** Model/provider that performed the extraction. */
    public static final String EXTRACTION_MODEL = "_extractionModel";
    /** ISO timestamp of extraction. */
    public static final String EXTRACTED_AT = "_extractedAt";
    /** Link to the full prompt/response record (ExtractionLogRecord). */
    public static final String EXTRACTION_LOG_ID = "_extractionLogId";

    /** All reserved provenance keys, in display order. */
    public static final List<String> ALL = List.of(
            SOURCE, SOURCE_DOCUMENT_ID, SOURCE_CHUNK_ID, CRAWL_RUN_ID,
            EXTRACTION_MODEL, EXTRACTED_AT, EXTRACTION_LOG_ID);

    private GraphProvenanceKeys() {
    }

    /**
     * Build a store-agnostic provenance view of a node: its structural lineage (source node it
     * belongs to, timestamps) plus the reserved provenance metadata keys (with the leading
     * underscore stripped for readability).
     */
    public static Map<String, Object> describe(GraphNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (node == null) {
            return out;
        }
        out.put("nodeId", node.getNodeId());
        out.put("externalId", node.getExternalId());
        out.put("nodeType", node.getNodeType() != null ? node.getNodeType().name() : null);

        // Structural lineage — the SOURCE node this node traces to. Guarded because the source
        // ref may be a lazy JPA association accessed outside a session.
        try {
            GraphNode source = node.getSourceNode();
            if (source != null) {
                out.put("sourceNodeId", source.getNodeId());
                out.put("sourceExternalId", source.getExternalId());
                out.put("sourceTitle", source.getTitle());
                out.put("sourceType", source.getSourceType());
            }
        } catch (Exception ignored) {
            // No accessible source association; structural lineage simply omitted.
        }

        if (node.getOccurredAt() != null) {
            out.put("occurredAt", node.getOccurredAt().toString());
        }
        if (node.getObservedAt() != null) {
            out.put("observedAt", node.getObservedAt().toString());
        }
        if (node.getCreatedAt() != null) {
            out.put("createdAt", node.getCreatedAt().toString());
        }

        Map<String, Object> meta = node.getMetadata();
        Map<String, Object> provenance = new LinkedHashMap<>();
        if (meta != null) {
            for (String key : ALL) {
                Object value = meta.get(key);
                if (value != null) {
                    provenance.put(key.substring(1), value);
                }
            }
        }
        out.put("provenance", provenance);
        return out;
    }
}
