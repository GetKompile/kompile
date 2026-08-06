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

package ai.kompile.app.services;

import ai.kompile.app.ingest.domain.IndexedDocument;
import ai.kompile.app.ingest.domain.IndexedDocument.IndexStatus;
import ai.kompile.app.ingest.domain.IndexedPassage;
import ai.kompile.crawl.graph.CrawlIndexTrackingCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Bridges the crawl pipeline to CrossIndexTrackingService so that
 * crawl-originated documents and passages appear in the index browser's
 * Documents and Tables tabs.
 */
@Component
public class CrawlIndexTrackingCallbackImpl implements CrawlIndexTrackingCallback {

    private static final Logger log = LoggerFactory.getLogger(CrawlIndexTrackingCallbackImpl.class);

    @Autowired
    private CrossIndexTrackingService crossIndexTrackingService;

    public CrawlIndexTrackingCallbackImpl(CrossIndexTrackingService crossIndexTrackingService) {
        this.crossIndexTrackingService = crossIndexTrackingService;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected CrawlIndexTrackingCallbackImpl() {}


    @Override
    @Transactional
    public int registerDocumentAndPassages(String sourceId, String fileName,
                                            Long factSheetId, List<CrawlPassageInfo> passages) {
        if (sourceId == null || factSheetId == null) {
            return 0;
        }

        try {
            IndexedDocument doc = crossIndexTrackingService.registerDocument(
                    sourceId, fileName, null, null, factSheetId);

            if (passages == null || passages.isEmpty()) {
                return 0;
            }

            int registered = 0;
            for (CrawlPassageInfo passage : passages) {
                try {
                    crossIndexTrackingService.registerPassage(
                            doc,
                            passage.chunkId(),
                            passage.chunkIndex(),
                            passage.content(),
                            passage.metadata());
                    registered++;
                } catch (Exception e) {
                    log.debug("Failed to register passage {}: {}", passage.chunkId(), e.getMessage());
                }
            }

            log.debug("Registered document '{}' with {} passage(s) in cross-index tracker",
                    fileName != null ? fileName : sourceId, registered);
            return registered;
        } catch (Exception e) {
            log.warn("Failed to register crawl document '{}' in cross-index: {}",
                    sourceId, e.getMessage());
            return 0;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CrawlCorpusSnapshot> loadCorpusSnapshot(Long factSheetId) {
        if (factSheetId == null) {
            return Optional.empty();
        }
        List<IndexedPassage> tracked = new ArrayList<>(
                crossIndexTrackingService.findPassagesByFactSheetId(factSheetId));
        tracked.sort(Comparator
                .comparing(CrawlIndexTrackingCallbackImpl::sourceIdentity)
                .thenComparing(p -> p.getChunkIndex() == null ? Integer.MAX_VALUE : p.getChunkIndex())
                .thenComparing(p -> Objects.toString(p.getChunkId(), "")));

        List<CrawlCorpusPassage> passages = new ArrayList<>(tracked.size());
        MessageDigest snapshot = sha256();
        for (IndexedPassage passage : tracked) {
            String full = passage.getFullContent();
            String preview = passage.getContentPreview();
            boolean complete = full != null
                    || (preview != null && Objects.equals(passage.getContentHash(), hash(preview)));
            String content = full != null ? full : preview;
            Map<String, Object> metadata = passageMetadata(passage);
            passages.add(new CrawlCorpusPassage(
                    passage.getChunkId(),
                    passage.getChunkIndex() == null ? 0 : passage.getChunkIndex(),
                    content,
                    passage.getContentHash(),
                    metadata,
                    complete));

            update(snapshot, sourceIdentity(passage));
            update(snapshot, String.valueOf(passage.getChunkIndex()));
            update(snapshot, passage.getChunkId());
            update(snapshot, passage.getContentHash());
        }
        return Optional.of(new CrawlCorpusSnapshot(
                "sha256:" + hex(snapshot.digest()), passages));
    }

    private static Map<String, Object> passageMetadata(IndexedPassage passage) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source_chunk_id", passage.getChunkId());
        if (passage.getDocument() != null && passage.getDocument().getSourceId() != null) {
            metadata.put("source_document_id", passage.getDocument().getSourceId());
        }
        put(metadata, "content_type", passage.getContentType());
        put(metadata, "source_type", passage.getSourceType());
        put(metadata, "source_url", passage.getSourceUrl());
        put(metadata, "source_path", passage.getSourcePath());
        put(metadata, "source_title", passage.getSourceTitle());
        put(metadata, "source_author", passage.getSourceAuthor());
        put(metadata, "source_date", passage.getSourceDate());
        metadata.put("chunk_index", passage.getChunkIndex() == null ? 0 : passage.getChunkIndex());
        metadata.put("content_hash", passage.getContentHash());
        return metadata;
    }

    private static void put(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private static String sourceIdentity(IndexedPassage passage) {
        if (passage != null && passage.getDocument() != null
                && passage.getDocument().getSourceId() != null) {
            return passage.getDocument().getSourceId();
        }
        return "";
    }

    private static String hash(String content) {
        MessageDigest digest = sha256();
        if (content != null) {
            digest.update(content.getBytes(StandardCharsets.UTF_8));
        }
        return hex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = Objects.toString(value, "").getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0xF, 16));
            result.append(Character.forDigit(value & 0xF, 16));
        }
        return result.toString();
    }

    @Override
    @Transactional
    public void markPassagesVectorIndexed(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return;
        try {
            crossIndexTrackingService.markPassagesVectorIndexed(chunkIds);
            log.debug("Marked {} passage(s) as vector-indexed", chunkIds.size());
        } catch (Exception e) {
            log.warn("Failed to mark {} passage(s) as vector-indexed: {}",
                    chunkIds.size(), e.getMessage());
        }
    }

    @Override
    @Transactional
    public void markPassageGraphIndexed(String chunkId, String graphNodeId) {
        if (chunkId == null) return;
        try {
            crossIndexTrackingService.markPassageGraphIndexed(chunkId, graphNodeId);
        } catch (Exception e) {
            log.debug("Failed to mark passage {} as graph-indexed: {}", chunkId, e.getMessage());
        }
    }

    @Override
    @Transactional
    public void markDocumentVectorIndexed(String sourceId, Long factSheetId, int passageCount) {
        if (sourceId == null || factSheetId == null) return;
        try {
            crossIndexTrackingService.findDocumentBySourceId(sourceId, factSheetId)
                    .ifPresent(doc -> crossIndexTrackingService.updateVectorStoreStatus(
                            doc.getId(), IndexStatus.INDEXED, null, passageCount));
        } catch (Exception e) {
            log.warn("Failed to update vector index status for document '{}': {}",
                    sourceId, e.getMessage());
        }
    }

    @Override
    @Transactional
    public void markDocumentGraphIndexed(String sourceId, Long factSheetId, int nodeCount) {
        if (sourceId == null || factSheetId == null) return;
        try {
            crossIndexTrackingService.findDocumentBySourceId(sourceId, factSheetId)
                    .ifPresent(doc -> crossIndexTrackingService.updateGraphStatus(
                            doc.getId(), IndexStatus.INDEXED, nodeCount));
        } catch (Exception e) {
            log.warn("Failed to update graph index status for document '{}': {}",
                    sourceId, e.getMessage());
        }
    }
}
