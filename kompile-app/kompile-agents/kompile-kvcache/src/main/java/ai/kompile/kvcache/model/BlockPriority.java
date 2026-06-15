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

package ai.kompile.kvcache.model;

/**
 * Priority level for a KV cache block. Lower priority blocks are evicted first.
 * Within the same priority, oldest (least recently accessed) blocks go first.
 */
public record BlockPriority(int priority, long lastAccessedMs) implements Comparable<BlockPriority> {

    /** System prompts should almost never be evicted */
    public static final int SYSTEM_PROMPT = 90;
    /** Cached prefix blocks shared across requests */
    public static final int CACHED_PREFIX = 70;
    /** Active user context */
    public static final int USER_CONTEXT = 50;
    /** Ephemeral blocks safe to evict */
    public static final int EVICTABLE = 10;

    public static BlockPriority systemPrompt() {
        return new BlockPriority(SYSTEM_PROMPT, System.currentTimeMillis());
    }

    public static BlockPriority cachedPrefix() {
        return new BlockPriority(CACHED_PREFIX, System.currentTimeMillis());
    }

    public static BlockPriority userContext() {
        return new BlockPriority(USER_CONTEXT, System.currentTimeMillis());
    }

    public static BlockPriority evictable() {
        return new BlockPriority(EVICTABLE, System.currentTimeMillis());
    }

    public static BlockPriority withPriority(int priority) {
        return new BlockPriority(priority, System.currentTimeMillis());
    }

    public BlockPriority touch() {
        return new BlockPriority(priority, System.currentTimeMillis());
    }

    /**
     * Compare: lowest priority first; within same priority, oldest first.
     * This ordering means the first element is the best eviction candidate.
     */
    @Override
    public int compareTo(BlockPriority other) {
        int cmp = Integer.compare(this.priority, other.priority);
        if (cmp != 0) return cmp;
        return Long.compare(this.lastAccessedMs, other.lastAccessedMs);
    }
}
