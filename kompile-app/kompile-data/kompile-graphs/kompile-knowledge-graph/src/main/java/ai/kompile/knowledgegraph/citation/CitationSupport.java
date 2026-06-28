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
package ai.kompile.knowledgegraph.citation;

import ai.kompile.core.citation.CitationDto;
import ai.kompile.core.source.SourceMetadataConstants;
import ai.kompile.knowledgegraph.domain.GraphProvenanceKeys;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Factory for building {@link CitationDto} instances from document or graph-node metadata.
 *
 * <p>Lives in {@code kompile-knowledge-graph} so it can reference both
 * {@link SourceMetadataConstants} (from {@code kompile-app-core}) and
 * {@link GraphProvenanceKeys} (from this module) without creating an upward dependency.</p>
 *
 * <p>Usage:
 * <pre>{@code
 * CitationDto citation = CitationSupport.from(doc.getMetadata(), doc.getScore(), null);
 * }</pre></p>
 */
public final class CitationSupport {

    private CitationSupport() {
        // prevent instantiation
    }

    /**
     * Build a {@link CitationDto} from a raw metadata map.
     *
     * <p>Extracts keys defined in {@link SourceMetadataConstants} and
     * {@link GraphProvenanceKeys}. Any key missing from the map produces a {@code null}
     * field — callers should treat all fields as optional.</p>
     *
     * @param metadata   document or graph-node metadata; may be null
     * @param score      retrieval similarity/relevance score; may be null
     * @param confidence calibrated epistemic confidence [0,1]; may be null
     * @return a populated (potentially sparse) {@link CitationDto}
     */
    public static CitationDto from(Map<String, Object> metadata, Double score, Double confidence) {
        if (metadata == null) {
            metadata = Map.of();
        }

        String sourceId = firstString(metadata,
                SourceMetadataConstants.SOURCE_ID,
                GraphProvenanceKeys.SOURCE_DOCUMENT_ID);

        String sourceName = firstString(metadata,
                SourceMetadataConstants.SOURCE_FILENAME,
                "title",
                "name");

        Integer pageNumber = toInteger(metadata.get(SourceMetadataConstants.PAGE_NUMBER));
        Integer chunkIndex = toInteger(metadata.get(SourceMetadataConstants.CHUNK_INDEX));

        String basisType  = (String) metadata.get(GraphProvenanceKeys.BASIS_TYPE);
        String crawlRunId = (String) metadata.get(GraphProvenanceKeys.CRAWL_RUN_ID);

        String sourceUrl = firstString(metadata,
                SourceMetadataConstants.SOURCE_URL,
                SourceMetadataConstants.SOURCE_PATH);

        // Collect all reserved provenance keys, strip the leading underscore for readability
        Map<String, Object> prov = new LinkedHashMap<>();
        for (String key : GraphProvenanceKeys.ALL) {
            Object val = metadata.get(key);
            if (val != null) {
                prov.put(key.substring(1), val);
            }
        }

        return new CitationDto(
                sourceId,
                sourceName,
                pageNumber,
                chunkIndex,
                score,
                confidence,
                basisType,
                crawlRunId,
                sourceUrl,
                prov.isEmpty() ? null : prov
        );
    }

    /**
     * As {@link #from(Map, Double, Double)}, but falls back to {@code fallbackSourceName} /
     * {@code fallbackSourceId} (typically a graph node's title and id) when the metadata does
     * not supply a source name/id. This keeps graph-node citations human-readable — e.g. a
     * community/entity node renders as its title ("United States") instead of an empty source.
     */
    public static CitationDto from(Map<String, Object> metadata, Double score, Double confidence,
                                   String fallbackSourceName, String fallbackSourceId) {
        CitationDto base = from(metadata, score, confidence);
        String name = base.sourceName() != null ? base.sourceName() : fallbackSourceName;
        String id = base.sourceId() != null ? base.sourceId() : fallbackSourceId;
        if (name == null && id == null) {
            return base;
        }
        return new CitationDto(
                id, name, base.pageNumber(), base.chunkIndex(), base.score(), base.confidence(),
                base.basisType(), base.crawlRunId(), base.sourceUrl(), base.provenance());
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private static String firstString(Map<String, Object> m, String... keys) {
        for (String key : keys) {
            Object v = m.get(key);
            if (v instanceof String s && !s.isEmpty()) {
                return s;
            }
        }
        return null;
    }

    private static Integer toInteger(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
