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

package ai.kompile.crawl.graph.partition;

import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.GraphProvenanceKeys;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the chunks a graph node or edge was extracted from.
 *
 * <p>This is what lets a graph walk produce partition evidence at all: an edge says two entities
 * are related, but a partition is a claim about <em>chunks</em>, and the only link back to the
 * text is the provenance the extractor wrote into {@code metadataJson}. Without it a
 * neighbourhood walk can name entities it cannot cite.</p>
 *
 * <p>Two spellings of the same key are in the tree and both are read here. Writers use the
 * reserved, underscore-prefixed {@link GraphProvenanceKeys#SOURCE_CHUNK_ID}; readers that went
 * through {@link GraphProvenanceKeys#describe} see it with the underscore stripped, and the older
 * extraction projection wrote the bare form directly. Preferring one and ignoring the other would
 * silently halve the citable graph, so both are accepted and the reserved spelling wins ties.</p>
 */
public final class GraphProvenanceChunks {

    /** Keys naming the chunk a fact was extracted from, most authoritative first. */
    static final List<String> CHUNK_KEYS = List.of(
            GraphProvenanceKeys.SOURCE_CHUNK_ID, "sourceChunkId", "source_chunk_id",
            "chunkId", "chunk_id", "chunkIds", "chunk_ids");

    /**
     * Keys naming the source document. Deliberately excludes {@code _source}, which carries a
     * coarse origin label ({@code "crawl"}, {@code "upload"}) and would otherwise be mistaken for
     * a document id and make every crawl-sourced chunk look like it came from one document.
     */
    static final List<String> DOCUMENT_KEYS = List.of(
            GraphProvenanceKeys.SOURCE_DOCUMENT_ID, "sourceDocumentId", "source_document_id",
            "documentId", "document_id", "original_document_id", "sourceId", "source_id");

    /** Thread-safe for reads; edge metadata is only ever parsed here, never written. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GraphProvenanceChunks() {
    }

    /**
     * Parsed metadata of an edge.
     *
     * <p>{@link GraphNode} exposes this as {@code getMetadata()}; {@link GraphEdge} does not, so
     * the parse lives here rather than being open-coded by each caller. Unparseable metadata is
     * an empty map, matching how the node side already degrades — provenance that cannot be read
     * is missing provenance, not a failed walk.</p>
     */
    public static Map<String, Object> metadataOf(GraphEdge edge) {
        if (edge == null) {
            return Map.of();
        }
        String json = edge.getMetadataJson();
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Parsed metadata of a node, guarded the same way. */
    public static Map<String, Object> metadataOf(GraphNode node) {
        if (node == null) {
            return Map.of();
        }
        Map<String, Object> metadata = node.getMetadata();
        return metadata == null ? Map.of() : metadata;
    }

    /** Chunk ids recorded on an edge, in metadata order, without duplicates. */
    public static List<String> chunkIdsOf(GraphEdge edge) {
        return chunkIds(metadataOf(edge));
    }

    /** Chunk ids recorded on a node, in metadata order, without duplicates. */
    public static List<String> chunkIdsOf(GraphNode node) {
        return chunkIds(metadataOf(node));
    }

    /** Source document recorded on an edge, or {@code null} when it never recorded one. */
    public static String documentIdOf(GraphEdge edge) {
        return documentId(metadataOf(edge));
    }

    /** Source document recorded on a node, or {@code null} when it never recorded one. */
    public static String documentIdOf(GraphNode node) {
        return documentId(metadataOf(node));
    }

    /**
     * Chunk ids in a provenance map.
     *
     * <p>A fact may be supported by several chunks, and writers express that in whichever shape
     * their sink allowed — a list, an array, or one delimited string. All three are read; a value
     * that is none of them is taken as a single id.</p>
     */
    public static List<String> chunkIds(Map<String, Object> metadata) {
        Set<String> ids = new LinkedHashSet<>();
        if (metadata == null || metadata.isEmpty()) {
            return List.of();
        }
        for (String key : CHUNK_KEYS) {
            collect(ids, metadata.get(key));
        }
        return List.copyOf(ids);
    }

    /** First document id recorded under any accepted spelling, or {@code null}. */
    public static String documentId(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        for (String key : DOCUMENT_KEYS) {
            String value = text(metadata.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static void collect(Set<String> ids, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Collection<?> collection) {
            for (Object element : collection) {
                collect(ids, element);
            }
        } else if (value instanceof Object[] array) {
            for (Object element : array) {
                collect(ids, element);
            }
        } else {
            for (String part : String.valueOf(value).split("[,;|]")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    ids.add(trimmed);
                }
            }
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String trimmed = String.valueOf(value).trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
