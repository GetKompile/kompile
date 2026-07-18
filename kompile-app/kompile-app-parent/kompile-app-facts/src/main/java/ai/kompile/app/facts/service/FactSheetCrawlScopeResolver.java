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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.app.facts.service;

import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.core.crawl.graph.CrawlFactSheetScopeResolver;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.crawl.graph.VectorIndexConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Persistence-backed crawl scope resolver.
 *
 * <p>An explicit display name is fail-closed: it must resolve exactly and is never
 * redirected to the active sheet. When no scope is supplied, the active sheet preserves
 * the existing application default. Vector indexing inherits the sheet's isolated store
 * path unless the caller deliberately supplied a collection.</p>
 */
@Component
public class FactSheetCrawlScopeResolver implements CrawlFactSheetScopeResolver {

    private static final Logger log = LoggerFactory.getLogger(FactSheetCrawlScopeResolver.class);

    private final FactSheetService factSheetService;

    public FactSheetCrawlScopeResolver(FactSheetService factSheetService) {
        this.factSheetService = factSheetService;
    }

    @Override
    public void resolveScope(UnifiedCrawlRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Crawl request is required");
        }

        FactSheet sheet = null;
        Long requestedId = request.getFactSheetId();
        String requestedName = trimToNull(request.getFactSheetName());

        if (requestedId != null) {
            try {
                sheet = factSheetService.getSheetById(requestedId).orElse(null);
            } catch (RuntimeException lookupFailure) {
                // The concrete ID is already a safe graph namespace. Keep it and use the
                // deterministic vector fallback instead of silently changing scope.
                log.warn("Could not load fact sheet {} while resolving crawl vector scope: {}",
                        requestedId, lookupFailure.getMessage());
            }
        } else if (requestedName != null) {
            sheet = factSheetService.getSheetByName(requestedName)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Fact sheet '" + requestedName + "' does not exist"));
            request.setFactSheetId(requirePersistentId(sheet, requestedName));
        } else {
            sheet = factSheetService.getActiveSheet();
            if (sheet == null) {
                throw new IllegalStateException("No active fact sheet is available for the crawl");
            }
            request.setFactSheetId(requirePersistentId(sheet, sheet.getName()));
        }

        applyVectorScope(request, sheet);
    }

    private void applyVectorScope(UnifiedCrawlRequest request, FactSheet sheet) {
        VectorIndexConfig vectorIndex = request.getVectorIndex();
        if (vectorIndex == null || !vectorIndex.isEnabled()
                || trimToNull(vectorIndex.getCollectionName()) != null) {
            return;
        }

        String collection = sheet != null ? trimToNull(sheet.getVectorStorePath()) : null;
        if (collection == null && request.getFactSheetId() != null) {
            collection = "fact-sheet-" + request.getFactSheetId();
        }
        if (collection == null) {
            throw new IllegalStateException("Vector indexing requires a concrete fact-sheet collection");
        }
        vectorIndex.setCollectionName(collection);
    }

    private static Long requirePersistentId(FactSheet sheet, String displayName) {
        if (sheet.getId() == null) {
            throw new IllegalStateException("Fact sheet '" + displayName + "' has no persistent ID");
        }
        return sheet.getId();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
