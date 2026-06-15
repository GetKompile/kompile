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

import ai.kompile.app.ingest.repository.IndexedPassageRepository;
import ai.kompile.core.indexers.CrossIndexIdResolver;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Resolves graph node external IDs to index document IDs using the
 * cross-index tracking table (indexed_passages).
 * <p>
 * During crawl, SNIPPET graph nodes are registered with a synthetic external ID
 * (e.g. "chunk:jobId:path:index") stored as {@code IndexedPassage.graphNodeId}.
 * The corresponding index document ID is stored as {@code IndexedPassage.chunkId}.
 */
@Service
public class CrossIndexIdResolverImpl implements CrossIndexIdResolver {

    private final IndexedPassageRepository passageRepository;

    public CrossIndexIdResolverImpl(IndexedPassageRepository passageRepository) {
        this.passageRepository = passageRepository;
    }

    @Override
    public List<String> resolveIndexDocumentIds(String graphNodeExternalId) {
        if (graphNodeExternalId == null || graphNodeExternalId.isBlank()) {
            return List.of();
        }
        return passageRepository.findByGraphNodeId(graphNodeExternalId)
                .map(passage -> passage.getChunkId() != null ? List.of(passage.getChunkId()) : List.<String>of())
                .orElse(List.of());
    }
}
