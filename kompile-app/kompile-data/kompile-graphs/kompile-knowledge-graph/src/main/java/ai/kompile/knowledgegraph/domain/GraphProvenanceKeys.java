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

    /**
     * Build a provenance metadata map for a crawl-extracted fact. Null fields are omitted. Merge
     * the result into a node's metadata so {@code _crawlRunId}/{@code _sourceDocumentId}/etc. are
     * recorded under the reserved keys the provenance view reads.
     */
    public static Map<String, Object> crawl(String crawlRunId, String sourceDocumentId, String sourceChunkId) {
        return crawl(crawlRunId, sourceDocumentId, sourceChunkId, null);
    }

    /** As above, additionally recording the extraction model. */
    public static Map<String, Object> crawl(String crawlRunId, String sourceDocumentId,
                                            String sourceChunkId, String extractionModel) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(SOURCE, "crawl");
        if (crawlRunId != null) {
            m.put(CRAWL_RUN_ID, crawlRunId);
        }
        if (sourceDocumentId != null) {
            m.put(SOURCE_DOCUMENT_ID, sourceDocumentId);
        }
        if (sourceChunkId != null) {
            m.put(SOURCE_CHUNK_ID, sourceChunkId);
        }
        if (extractionModel != null) {
            m.put(EXTRACTION_MODEL, extractionModel);
        }
        return m;
    }

    /**
     * Build a provenance metadata map for a document uploaded via the standard sources interface.
     * The {@code taskId} is used as the source-document identifier (it is the async upload task
     * UUID). The {@code fileName} is stored in {@link #SOURCE_CHUNK_ID} (file-level granularity,
     * analogous to a chunk key for single-file uploads). Null fields are omitted.
     *
     * <p>Merge the result into a graph node's metadata so the cascade and multi-source fusion
     * pipeline can distinguish upload-derived facts from crawl-extracted or channel-inferred ones
     * via {@code _source = "upload"}.</p>
     */
    public static Map<String, Object> upload(String taskId, String fileName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(SOURCE, "upload");
        if (taskId != null) {
            m.put(SOURCE_DOCUMENT_ID, taskId);
        }
        if (fileName != null) {
            m.put(SOURCE_CHUNK_ID, fileName);
        }
        return m;
    }
}
