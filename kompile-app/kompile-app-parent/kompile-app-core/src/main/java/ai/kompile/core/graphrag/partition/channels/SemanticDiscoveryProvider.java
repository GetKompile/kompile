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

package ai.kompile.core.graphrag.partition.channels;

import ai.kompile.core.embeddings.ScoredDocument;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.graphrag.partition.ChunkCandidate;
import ai.kompile.core.graphrag.partition.DiscoveryChannel;
import ai.kompile.core.graphrag.partition.DiscoveryChannelProvider;
import ai.kompile.core.graphrag.partition.EntityPartition;
import ai.kompile.core.graphrag.partition.PartitionKey;

import java.util.List;
import java.util.function.Function;

/**
 * Proposes chunks that read like they are about the partition's subject.
 *
 * <p>The weakest channel by design: resemblance is not aboutness, and a chunk that merely
 * mentions a similar company is not evidence about this one. That is why proposals land in the
 * {@link DiscoveryChannel#SEMANTIC} stratum, are read after the identifier and structural
 * channels, and are subject to the policy's confidence floor — the engine offers, the policy
 * admits, and the extraction pass still has to find something assertable in the text.</p>
 *
 * <p>The provider re-queries every round rather than answering only once. A corpus that grew
 * between rounds should be seen, and a repeat query over an unchanged corpus is harmless: the
 * partition merges identical proposals into the members it already has, which is exactly the
 * signal the coordinator reads as an exhausted frontier.</p>
 */
public final class SemanticDiscoveryProvider implements DiscoveryChannelProvider {

    private final VectorStore vectorStore;
    private final Function<EntityPartition, String> queryBuilder;
    private final double threshold;

    /**
     * @param vectorStore store to query
     * @param threshold   minimum similarity a hit must reach to be proposed at all; the policy's
     *                    own confidence bands still apply on top of this
     */
    public SemanticDiscoveryProvider(VectorStore vectorStore, double threshold) {
        this(vectorStore, SemanticDiscoveryProvider::describeSubject, threshold);
    }

    /**
     * @param queryBuilder renders a partition into the query text; supply this when the subject id
     *                     is not itself searchable text and a label has to be looked up
     */
    public SemanticDiscoveryProvider(VectorStore vectorStore,
                                     Function<EntityPartition, String> queryBuilder,
                                     double threshold) {
        if (vectorStore == null) {
            throw new IllegalArgumentException("a semantic channel needs a vector store");
        }
        this.vectorStore = vectorStore;
        this.queryBuilder = queryBuilder == null ? SemanticDiscoveryProvider::describeSubject
                : queryBuilder;
        this.threshold = threshold;
    }

    @Override
    public DiscoveryChannel channel() {
        return DiscoveryChannel.SEMANTIC;
    }

    @Override
    public List<ChunkCandidate> discover(EntityPartition partition, int round, int limit) {
        String query = queryBuilder.apply(partition);
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<ScoredDocument> hits =
                vectorStore.similaritySearchWithScores(query, Math.max(1, limit), threshold);
        return VectorStoreCandidates.toCandidates(hits, DiscoveryChannel.SEMANTIC,
                "semantically similar to \"" + query + "\"");
    }

    /**
     * Default query text: the subject, narrowed by whatever the key narrows by.
     *
     * <p>Category and time window are part of the partition's identity, so leaving them out of
     * the query would retrieve evidence the partition is not claiming to cover.</p>
     */
    public static String describeSubject(EntityPartition partition) {
        if (partition == null) {
            return null;
        }
        PartitionKey key = partition.key();
        StringBuilder query = new StringBuilder(key.subject());
        if (key.category() != null) {
            query.append(' ').append(key.category());
        }
        if (key.timeWindow() != null) {
            query.append(' ').append(key.timeWindow());
        }
        return query.toString();
    }
}
