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

import io.anserini.search.BaseDenseSearcher;
import io.anserini.search.FlatDenseSearcher;
import io.anserini.search.HnswDenseSearcher;
import lombok.extern.slf4j.Slf4j;

/**
 * Factory class for creating appropriate Anserini searchers based on configuration.
 */
@Slf4j
public class AnseriniSearcherFactory {

    /**
     * Creates a dense searcher based on the provided properties.
     *
     * @param properties Anserini vector store properties
     * @param indexPath Path to the index directory
     * @return Configured dense searcher
     * @throws Exception if searcher creation fails
     */
    public static BaseDenseSearcher<String> createSearcher(
            AnseriniVectorStoreProperties properties, 
            String indexPath) throws Exception {
        
        if (properties.getHnsw().isEnabled()) {
            log.info("Creating HNSW dense searcher for index: {}", indexPath);
            return createHnswSearcher(properties, indexPath);
        } else {
            log.info("Creating flat dense searcher for index: {}", indexPath);
            return createFlatSearcher(indexPath);
        }
    }

    private static HnswDenseSearcher<String> createHnswSearcher(
            AnseriniVectorStoreProperties properties, 
            String indexPath) throws Exception {
        
        HnswDenseSearcher.Args args = new HnswDenseSearcher.Args();
        args.index = indexPath;
        
        log.debug("HNSW searcher configuration - M: {}, efConstruction: {}", 
                properties.getHnsw().getM(), 
                properties.getHnsw().getEfConstruction());
        
        return new HnswDenseSearcher<>(args);
    }

    private static FlatDenseSearcher<String> createFlatSearcher(String indexPath) throws Exception {
        FlatDenseSearcher.Args args = new FlatDenseSearcher.Args();
        args.index = indexPath;
        
        return new FlatDenseSearcher<>(args);
    }
}