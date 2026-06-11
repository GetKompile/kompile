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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges the crawl pipeline to CrossIndexTrackingService so that
 * crawl-originated documents and passages appear in the index browser's
 * Documents and Tables tabs.
 */
@Component
public class CrawlIndexTrackingCallbackImpl implements CrawlIndexTrackingCallback {

    private static final Logger log = LoggerFactory.getLogger(CrawlIndexTrackingCallbackImpl.class);

    private final CrossIndexTrackingService crossIndexTrackingService;

    public CrawlIndexTrackingCallbackImpl(CrossIndexTrackingService crossIndexTrackingService) {
        this.crossIndexTrackingService = crossIndexTrackingService;
    }

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
