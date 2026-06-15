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

package ai.kompile.modelmanager.cache;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * No-op implementation of {@link PipelineOutputCache} that never caches anything.
 * Used when caching is disabled or as a fallback.
 */
public class NoOpPipelineOutputCache implements PipelineOutputCache {

    @Override
    public Optional<PipelineCacheEntry> get(PipelineCacheKey key) {
        return Optional.empty();
    }

    @Override
    public void put(PipelineCacheKey key, PipelineCacheEntry entry) {
        // no-op
    }

    @Override
    public boolean contains(PipelineCacheKey key) {
        return false;
    }

    @Override
    public boolean remove(PipelineCacheKey key) {
        return false;
    }

    @Override
    public List<PipelineCacheEntry> getStageCheckpoints(String contentHash, String pipelineId) {
        return Collections.emptyList();
    }

    @Override
    public int removeStageCheckpoints(String contentHash, String pipelineId) {
        return 0;
    }

    @Override
    public int removeByContentHash(String contentHash) {
        return 0;
    }

    @Override
    public int removeByPipeline(String pipelineId) {
        return 0;
    }

    @Override
    public int evictExpired() {
        return 0;
    }

    @Override
    public void clear() {
        // no-op
    }

    @Override
    public CacheStats getStats() {
        return CacheStats.empty();
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
