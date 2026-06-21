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

import java.util.List;

/**
 * Storage-agnostic bridge between the KG-embedding (TransE/RotatE) training pipeline and a concrete
 * graph store. Historically the pipeline read/wrote the relational (JPA) graph only, while the live
 * production graph is held in the vector/matrix store — so trained embeddings never reached the graph
 * the retriever actually queries. Implementations of this interface let the training job extract
 * triples from, and write structural embeddings back to, whichever store actually holds the graph.
 *
 * <p>The training job picks the highest-{@link #priority()} adapter that {@link #hasGraphData}, so the
 * live store wins whenever it has been populated, falling back to JPA otherwise.</p>
 */
public interface KgEmbeddingGraphAdapter {

    /** Higher wins when more than one adapter reports data for the same fact sheet. */
    int priority();

    /** Short identifier of the backing store ("jpa", "matrix"); used for logging/diagnostics. */
    String storeType();

    /** Whether this store actually holds graph edges (i.e. triples to train on) for the fact sheet. */
    boolean hasGraphData(Long factSheetId);

    /**
     * Extracts {@code (head, relation, tail)} training triples from this store for the fact sheet.
     * Entity keys must be consistent with the keys {@link #storeEmbeddings} expects back from the
     * trained model (JPA keys by entity title; matrix keys by stable node id).
     */
    List<Triple> extractTriples(Long factSheetId);

    /**
     * Persists trained entity (and, where supported, relation) embeddings from {@code model} back into
     * this store for the fact sheet.
     *
     * @return number of graph elements updated
     */
    int storeEmbeddings(KGEmbeddingModel model, Long factSheetId, Long version);
}
