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

package io.anserini.search;

import ai.kompile.utils.NativeImageInfo;

/**
 * Picks the correct Lucene {@code MMapDirectory} provider for the runtime we are actually in —
 * automatically, with no launch flag and no configuration.
 *
 * <p>The Lucene index (postings, stored fields, and the vector/HNSW data) is always served OFF-heap
 * via {@code MMapDirectory} so it does not inflate {@code -Xmx}. Lucene has two mmap providers:</p>
 * <ul>
 *   <li>the default {@code MemorySegmentIndexInput}, which uses {@code java.lang.foreign.Arena}
 *       — faster, but needs {@code Arena.ofShared()}, which a <b>GraalVM native image cannot do</b>;</li>
 *   <li>the legacy {@code MappedByteBuffer} provider — equally off-heap and native-image-safe.</li>
 * </ul>
 *
 * <p>This class asks the shared {@link NativeImageInfo} whether we are in a native image and, only
 * then, switches Lucene to the legacy provider. A {@code java -jar} run is left on the faster
 * default. The selection happens as a side effect of class initialization, so {@link #ensure()}
 * only needs to be touched once — before the first {@code MMapDirectory} is constructed — by every
 * path that opens one (the dense searchers and the vector-store directory factory).</p>
 */
public final class LuceneRuntimeConfig {

    /** Lucene's switch between the MemorySegment (default) and legacy MappedByteBuffer mmap providers. */
    private static final String ENABLE_MEMORY_SEGMENTS =
            "org.apache.lucene.store.MMapDirectory.enableMemorySegments";

    /** Runs the one-time provider selection as a side effect of class initialization. */
    private static final boolean INITIALIZED = selectMmapProvider();

    private LuceneRuntimeConfig() {
    }

    private static boolean selectMmapProvider() {
        // Respect an explicit operator override if one was already set; otherwise auto-select using
        // the shared, reflection-based native-image detector.
        if (NativeImageInfo.isRunningInNativeImage() && System.getProperty(ENABLE_MEMORY_SEGMENTS) == null) {
            System.setProperty(ENABLE_MEMORY_SEGMENTS, "false");
        }
        return true;
    }

    /**
     * Forces this class to initialize (running {@link #selectMmapProvider()}) before the caller
     * constructs its first {@code MMapDirectory}. Idempotent and cheap.
     */
    public static void ensure() {
        if (!INITIALIZED) {
            throw new IllegalStateException("Lucene mmap provider selection failed to initialize");
        }
    }
}
