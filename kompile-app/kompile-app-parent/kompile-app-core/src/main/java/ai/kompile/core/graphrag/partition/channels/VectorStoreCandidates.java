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

package ai.kompile.core.graphrag.partition.channels;

import ai.kompile.core.embeddings.ScoredDocument;
import ai.kompile.core.graphrag.partition.AccessScope;
import ai.kompile.core.graphrag.partition.ChunkCandidate;
import ai.kompile.core.graphrag.partition.DiscoveryChannel;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns vector-store hits into partition candidates.
 *
 * <p>The mapping is deliberately narrow. A retrieved chunk carries three things the partition
 * needs and the vector store already knows: which source document it came from, what version of
 * that source it was cut from, and who is allowed to read it. Dropping any of them is what makes
 * a partition unable to answer later questions — selective invalidation needs the document link,
 * staleness detection needs the version, and an honest coverage claim needs the access scope.</p>
 *
 * <p>Metadata keys are read leniently because chunk writers across the codebase have not agreed
 * on one spelling; an absent key is treated as "not stated", never as a default that would widen
 * access or hide a stale chunk.</p>
 */
public final class VectorStoreCandidates {

    /** Metadata keys naming the source document, in order of preference. */
    private static final List<String> DOCUMENT_KEYS =
            List.of("source_id", "sourceId", "source", "document_id", "documentId", "file_path");

    /** Metadata keys carrying a source version or content fingerprint. */
    private static final List<String> VERSION_KEYS =
            List.of("chunk_version", "chunkVersion", "version", "content_hash", "contentHash",
                    "checksum", "etag");

    /** Metadata keys carrying access-control domains. */
    private static final List<String> SCOPE_KEYS =
            List.of("access_scope", "accessScope", "acl", "acl_domains", "security_labels",
                    "visibility");

    /** Metadata keys giving a chunk its position, so a process reads in the order it happened. */
    private static final List<String> ORDER_KEYS =
            List.of("order_key", "orderKey", "chunk_index", "chunkIndex", "position", "timestamp",
                    "published_at");

    private VectorStoreCandidates() {
    }

    /**
     * Maps one scored hit to a candidate.
     *
     * @param hit     the retrieved chunk and its score
     * @param channel channel to attribute the proposal to
     * @param reason  why this chunk was proposed, for the audit trail
     * @return the candidate, or {@code null} when the hit has no usable chunk id
     */
    public static ChunkCandidate toCandidate(ScoredDocument hit, DiscoveryChannel channel,
                                             String reason) {
        if (hit == null || hit.document() == null) {
            return null;
        }
        Document document = hit.document();
        String chunkId = document.getId();
        if (chunkId == null || chunkId.isBlank()) {
            return null;
        }
        Map<String, Object> metadata = document.getMetadata();
        return ChunkCandidate.of(chunkId, channel, hit.score(), reason)
                .inDocument(firstString(metadata, DOCUMENT_KEYS))
                .withVersion(firstString(metadata, VERSION_KEYS))
                .withOrderKey(firstString(metadata, ORDER_KEYS))
                .withAccessScope(scopeOf(metadata));
    }

    /**
     * Maps a whole result list, dropping unusable hits and keeping the store's ranking.
     *
     * @param hits    retrieved chunks, best first
     * @param channel channel to attribute the proposals to
     * @param reason  why these chunks were proposed
     */
    public static List<ChunkCandidate> toCandidates(Collection<ScoredDocument> hits,
                                                    DiscoveryChannel channel, String reason) {
        List<ChunkCandidate> candidates = new ArrayList<>();
        if (hits == null) {
            return candidates;
        }
        for (ScoredDocument hit : hits) {
            ChunkCandidate candidate = toCandidate(hit, channel, reason);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    /**
     * Reads access domains from chunk metadata.
     *
     * <p>No metadata means unrestricted, which is the only reading available: a chunk that never
     * recorded a label cannot be treated as secret without making every unlabelled corpus
     * unreadable. The place to fix that is at ingest, by labelling; this class will not invent a
     * label it was not given.</p>
     */
    static AccessScope scopeOf(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return AccessScope.unrestricted();
        }
        Set<String> domains = new LinkedHashSet<>();
        for (String key : SCOPE_KEYS) {
            Object value = metadata.get(key);
            if (value == null) {
                continue;
            }
            if (value instanceof Collection<?> collection) {
                for (Object element : collection) {
                    addDomain(domains, element);
                }
            } else if (value instanceof Object[] array) {
                for (Object element : array) {
                    addDomain(domains, element);
                }
            } else {
                // A single string may itself be a delimited list — "hr,legal" is the shape most
                // ingest paths write when the sink is a flat string column.
                for (String part : String.valueOf(value).split("[,;|]")) {
                    addDomain(domains, part);
                }
            }
        }
        return domains.isEmpty() ? AccessScope.unrestricted() : AccessScope.of(domains);
    }

    private static void addDomain(Set<String> domains, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (!text.isEmpty()) {
            domains.add(text);
        }
    }

    private static String firstString(Map<String, Object> metadata, List<String> keys) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return null;
    }
}
