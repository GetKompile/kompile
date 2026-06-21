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
package ai.kompile.knowledgegraph.embedding.adapter;

import ai.kompile.core.kgembedding.KGEmbeddingModel;
import ai.kompile.core.kgembedding.Triple;
import ai.kompile.knowledgegraph.embedding.service.KGEmbeddingStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * JPA-backed {@link KgEmbeddingGraphAdapter}. Delegates to the existing {@link KGEmbeddingStorageService},
 * which extracts triples from and writes embeddings to the relational {@code graph_nodes}/{@code graph_edges}
 * tables. Lowest priority — used only when the live (matrix/vector) store has no data for the fact sheet,
 * preserving the original behavior for relational-graph deployments.
 */
@Component
public class JpaKgEmbeddingGraphAdapter implements KgEmbeddingGraphAdapter {

    private KGEmbeddingStorageService storageService;

    @Autowired
    public JpaKgEmbeddingGraphAdapter(KGEmbeddingStorageService storageService) {
        this.storageService = storageService;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected JpaKgEmbeddingGraphAdapter() {}

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public String storeType() {
        return "jpa";
    }

    @Override
    public boolean hasGraphData(Long factSheetId) {
        return storageService.getStats(factSheetId).totalEdges() > 0;
    }

    @Override
    public List<Triple> extractTriples(Long factSheetId) {
        return storageService.extractTriples(factSheetId);
    }

    @Override
    public int storeEmbeddings(KGEmbeddingModel model, Long factSheetId, Long version) {
        return storageService.storeEmbeddings(model, factSheetId, version);
    }
}
