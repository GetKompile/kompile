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
package ai.kompile.app.services;

import ai.kompile.app.facts.service.FactSheetDeleteConfigurer;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.unified.UnifiedGraphAnalysisAssetStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Implements {@link FactSheetDeleteConfigurer}: when a fact sheet is deleted,
 * removes its graph segment (matrix store), analysis-asset snapshot (.kgraph),
 * KB reasoning state, and observed-fact journal file.
 *
 * <p>All operations are best-effort — failures are logged at WARN and do not
 * propagate back to the caller.  This prevents a cleanup error from aborting the
 * fact-sheet delete transaction.</p>
 */
@Service
public class FactSheetGraphCleanupService implements FactSheetDeleteConfigurer {

    private static final Logger log = LoggerFactory.getLogger(FactSheetGraphCleanupService.class);

    private final KnowledgeGraphService knowledgeGraphService;
    private final UnifiedGraphAnalysisAssetStore assetStore;
    private final KbGroundingService kbGroundingService;

    @Autowired
    public FactSheetGraphCleanupService(
            KnowledgeGraphService knowledgeGraphService,
            UnifiedGraphAnalysisAssetStore assetStore,
            KbGroundingService kbGroundingService) {
        this.knowledgeGraphService = knowledgeGraphService;
        this.assetStore = assetStore;
        this.kbGroundingService = kbGroundingService;
    }

    @Override
    public void deleteGraphDataForSheet(Long factSheetId) {
        if (factSheetId == null) {
            log.warn("deleteGraphDataForSheet called with null factSheetId — skipping");
            return;
        }
        log.info("Cleaning up graph data for deleted fact sheet id={}", factSheetId);

        // 1. Delete matrix/vector graph segment
        try {
            knowledgeGraphService.deleteByFactSheetId(factSheetId);
        } catch (Exception e) {
            log.warn("Failed to delete graph segment for fact sheet id={}: {}", factSheetId, e.getMessage(), e);
        }

        // 2. Evict UnifiedGraph analysis-asset snapshot (in-memory cache + .kgraph file)
        try {
            assetStore.removeForFactSheet(factSheetId);
        } catch (Exception e) {
            log.warn("Failed to remove analysis-asset snapshot for fact sheet id={}: {}", factSheetId, e.getMessage(), e);
        }

        // 3. Permanently destroy KB reasoning state AND the observed-fact journal file.
        //    destroyFactSheet() closes and deletes the journal, evicts the in-memory state,
        //    and clears stale/epoch/queryIndex caches — a single call replaces the old
        //    resetState() + manual Files.deleteIfExists() pair.
        try {
            kbGroundingService.destroyFactSheet(factSheetId);
        } catch (Exception e) {
            log.warn("Failed to destroy KB state for fact sheet id={}: {}", factSheetId, e.getMessage(), e);
        }

        log.info("Graph data cleanup complete for fact sheet id={}", factSheetId);
    }
}
