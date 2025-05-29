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
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.codecs.lucene99.Lucene99Codec;
import org.apache.lucene.codecs.lucene99.Lucene99HnswScalarQuantizedVectorsFormat;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;

import java.io.IOException;

/**
 * Utility class for managing Anserini index configurations and codecs.
 */
@Slf4j
public class AnseriniIndexUtils {

    /**
     * Creates an IndexWriterConfig based on the provided properties.
     *
     * @param properties Anserini vector store properties
     * @return Configured IndexWriterConfig
     */
    public static IndexWriterConfig createIndexWriterConfig(AnseriniVectorStoreProperties properties) {
        IndexWriterConfig config = new IndexWriterConfig();
        
        // Configure codec based on indexing strategy and quantization
        if (properties.getHnsw().isEnabled()) {
            config.setCodec(createHnswCodec(properties));
        } else {
            config.setCodec(createFlatCodec(properties));
        }
        
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        config.setRAMBufferSizeMB(properties.getMemoryBufferSizeMb());
        config.setUseCompoundFile(properties.isUseCompoundFile());
        
        log.info("Created IndexWriterConfig - HNSW: {}, Quantization: {}, RAM Buffer: {}MB", 
                properties.getHnsw().isEnabled(), 
                properties.isQuantizeInt8(),
                properties.getMemoryBufferSizeMb());
        
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
                    baseFormat = new io.anserini.index.codecs.AnseriniLucene99ScalarQuantizedVectorsFormat();
                } else {
                    baseFormat = new io.anserini.index.codecs.AnseriniLucene99FlatVectorFormat();
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