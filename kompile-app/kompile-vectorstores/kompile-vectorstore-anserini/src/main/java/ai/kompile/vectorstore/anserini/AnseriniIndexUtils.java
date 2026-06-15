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

package ai.kompile.vectorstore.anserini;

import lombok.extern.slf4j.Slf4j;
import io.anserini.index.codecs.AnseriniLucene99FlatVectorFormat;
import io.anserini.index.codecs.AnseriniLucene99ScalarQuantizedVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.codecs.lucene99.Lucene99Codec;
import org.apache.lucene.codecs.lucene99.Lucene99HnswScalarQuantizedVectorsFormat;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
import org.apache.lucene.index.ConcurrentMergeScheduler;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LogByteSizeMergePolicy;
import org.apache.lucene.index.MergeScheduler;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.TieredMergePolicy;

import java.io.IOException;

/**
 * Utility class for managing Anserini index configurations and codecs.
 */
@Slf4j
public class AnseriniIndexUtils {

    // ========== BATCH MODE CONFIGURATION ==========
    // These constants optimize Lucene for bulk indexing scenarios

    /**
     * Maximum number of documents to buffer before auto-flush.
     * Higher values = better throughput but more memory.
     * -1 disables document-count-based flushing (use RAM buffer instead).
     */
    private static final int MAX_BUFFERED_DOCS = -1; // Disabled, use RAM buffer

    /**
     * Number of threads for concurrent merging during indexing.
     * More threads = faster merges but higher CPU usage.
     */
    private static final int MERGE_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

    /**
     * Creates an IndexWriterConfig optimized for batch/bulk indexing.
     *
     * <p>Optimizations applied:</p>
     * <ul>
     *   <li>Large RAM buffer to reduce flush frequency</li>
     *   <li>Disabled document-count flushing (RAM-based only)</li>
     *   <li>Concurrent merge scheduler for parallel segment merging</li>
     *   <li>Compound files disabled for faster indexing</li>
     * </ul>
     *
     * @param properties Anserini vector store properties
     * @return Configured IndexWriterConfig optimized for batch operations
     */
    public static IndexWriterConfig createIndexWriterConfig(AnseriniVectorStoreProperties properties) {
        IndexWriterConfig config = new IndexWriterConfig();

        // NOTE: Lock factory should be set on the Directory object when opening it,
        // not on IndexWriterConfig (Lucene 9.x API change). Ensure callers use
        // NativeCompatibleDirectoryFactory.open(path, NoLockFactory.INSTANCE) to prevent
        // lock conflicts and ensure GraalVM native image compatibility.

        // Configure codec based on indexing strategy and quantization
        if (properties.getHnsw().isEnabled()) {
            config.setCodec(createHnswCodec(properties));
        } else {
            config.setCodec(createFlatCodec(properties));
        }

        // Basic configuration
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        config.setUseCompoundFile(properties.isUseCompoundFile());

        // ========== BATCH MODE OPTIMIZATIONS ==========

        // 1. Large RAM buffer - key optimization for bulk indexing
        // Documents are buffered in memory until this limit is reached
        // Higher = fewer flushes = better throughput
        config.setRAMBufferSizeMB(properties.getMemoryBufferSizeMb());

        // 2. Disable document-count based flushing
        // Let RAM buffer size control flushing instead
        // This prevents premature flushes during bulk operations
        config.setMaxBufferedDocs(MAX_BUFFERED_DOCS);

        // 3. Configure concurrent merge scheduler for parallel segment merging
        // This allows merges to happen in background while indexing continues
        ConcurrentMergeScheduler mergeScheduler =
                new ConcurrentMergeScheduler();
        mergeScheduler.setMaxMergesAndThreads(MERGE_THREADS + 1, MERGE_THREADS);
        config.setMergeScheduler(mergeScheduler);

        TieredMergePolicy mergePolicy =
                new TieredMergePolicy();
        // Increase max merge size for bulk indexing (default is 5GB)
        mergePolicy.setMaxMergedSegmentMB(10 * 1024); // 10GB max segment
        // Reduce merge at to delay merging until more segments exist
        mergePolicy.setSegmentsPerTier(10.0);
        config.setMergePolicy(mergePolicy);

        log.info("Created IndexWriterConfig (batch-optimized) - HNSW: {}, Quantization: {}, " +
                        "RAM Buffer: {}MB, Merge Threads: {}, Compound Files: {}",
                properties.getHnsw().isEnabled(),
                properties.isQuantizeInt8(),
                properties.getMemoryBufferSizeMb(),
                MERGE_THREADS,
                properties.isUseCompoundFile());

        return config;
    }

    /**
     * Creates an IndexWriterConfig for high-throughput bulk loading scenarios.
     * This configuration prioritizes indexing speed over search performance.
     *
     * <p>Use this for initial data loading, then consider force-merging.</p>
     *
     * @param properties Anserini vector store properties
     * @param ramBufferMB Override RAM buffer size (use larger values for bulk loads)
     * @return Configured IndexWriterConfig for bulk loading
     */
    public static IndexWriterConfig createBulkLoadConfig(AnseriniVectorStoreProperties properties, double ramBufferMB) {
        IndexWriterConfig config = createIndexWriterConfig(properties);

        // Override RAM buffer for bulk loading
        config.setRAMBufferSizeMB(Math.max(ramBufferMB, properties.getMemoryBufferSizeMb()));

        // For bulk loading, use LogByteSizeMergePolicy which is more predictable
        // and better for scenarios where we'll force-merge at the end
        LogByteSizeMergePolicy bulkMergePolicy =
                new LogByteSizeMergePolicy();
        bulkMergePolicy.setMaxMergeMB(5 * 1024); // 5GB max merge
        bulkMergePolicy.setMergeFactor(10); // Merge 10 segments at a time
        config.setMergePolicy(bulkMergePolicy);

        log.info("Created bulk-load IndexWriterConfig - RAM Buffer: {}MB", ramBufferMB);

        return config;
    }

    private static Lucene99Codec createHnswCodec(AnseriniVectorStoreProperties properties) {
        return new Lucene99Codec() {
            @Override
            public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
                KnnVectorsFormat baseFormat;
                
                if (properties.isQuantizeInt8()) {
                    baseFormat = new Lucene99HnswScalarQuantizedVectorsFormat(
                            properties.getHnsw().getM(),
                            properties.getHnsw().getEfConstruction()
                    );
                } else {
                    baseFormat = new Lucene99HnswVectorsFormat(
                            properties.getHnsw().getM(),
                            properties.getHnsw().getEfConstruction()
                    );
                }
                
                return new DelegatingKnnVectorsFormat(baseFormat, properties.getMaxDimensions());
            }
        };
    }

    private static Lucene99Codec createFlatCodec(AnseriniVectorStoreProperties properties) {
        return new Lucene99Codec() {
            @Override
            public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
                KnnVectorsFormat baseFormat;
                
                if (properties.isQuantizeInt8()) {
                    baseFormat = new AnseriniLucene99ScalarQuantizedVectorsFormat();
                } else {
                    baseFormat = new AnseriniLucene99FlatVectorFormat();
                }
                
                return new DelegatingKnnVectorsFormat(baseFormat, properties.getMaxDimensions());
            }
        };
    }

    /**
     * Delegating KnnVectorsFormat to support custom maximum dimensions.
     * This is necessary because Lucene's default formats have fixed dimension limits.
     */
    public static final class DelegatingKnnVectorsFormat extends KnnVectorsFormat {
        private final KnnVectorsFormat delegate;
        private final int maxDimensions;

        public DelegatingKnnVectorsFormat(KnnVectorsFormat delegate, int maxDimensions) {
            super(delegate.getName());
            this.delegate = delegate;
            this.maxDimensions = maxDimensions;
        }

        @Override
        public KnnVectorsWriter fieldsWriter(SegmentWriteState state) throws IOException {
            return delegate.fieldsWriter(state);
        }

        @Override
        public KnnVectorsReader fieldsReader(SegmentReadState state) throws IOException {
            return delegate.fieldsReader(state);
        }

        @Override
        public int getMaxDimensions(String fieldName) {
            return maxDimensions;
        }
    }
}