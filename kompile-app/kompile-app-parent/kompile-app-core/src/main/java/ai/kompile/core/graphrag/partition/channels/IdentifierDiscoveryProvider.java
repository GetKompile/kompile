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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Proposes chunks that name the subject by one of its identifiers — canonical name, alias,
 * ticker, GTIN, registration number.
 *
 * <p>The strongest retrieval channel available without a graph, and the reason it outranks the
 * semantic one: "this chunk contains the string AAPL" is a fact about the chunk, while "this
 * chunk embeds near Apple Inc." is a fact about a model. Both are proposals rather than verdicts,
 * but only one of them is checkable.</p>
 *
 * <p>Where the identifiers come from is the caller's business — an entity store, an alias table,
 * a resolved graph node. That seam is a {@link Function} rather than a hard dependency so this
 * class stays usable before any of those exist, and so a partition over a subject with no known
 * aliases degrades to searching its own name instead of failing.</p>
 */
public final class IdentifierDiscoveryProvider implements DiscoveryChannelProvider {

    private final VectorStore vectorStore;
    private final Function<EntityPartition, List<String>> identifiers;
    private final double threshold;

    /**
     * @param vectorStore store to query
     * @param identifiers surface forms to look for, best first; the subject id itself is used
     *                    when this returns nothing
     * @param threshold   minimum score a hit must reach to be proposed
     */
    public IdentifierDiscoveryProvider(VectorStore vectorStore,
                                       Function<EntityPartition, List<String>> identifiers,
                                       double threshold) {
        if (vectorStore == null) {
            throw new IllegalArgumentException("an identifier channel needs a vector store");
        }
        this.vectorStore = vectorStore;
        this.identifiers = identifiers == null ? partition -> List.of() : identifiers;
        this.threshold = threshold;
    }

    @Override
    public DiscoveryChannel channel() {
        return DiscoveryChannel.DIRECT_IDENTIFIER;
    }

    @Override
    public List<ChunkCandidate> discover(EntityPartition partition, int round, int limit) {
        List<String> surfaceForms = surfaceFormsFor(partition);
        if (surfaceForms.isEmpty()) {
            return List.of();
        }
        int cap = Math.max(1, limit);
        // Spread the round's budget over the identifiers rather than letting the first alias spend
        // it: a subject known by five names should be looked for under all five.
        int perForm = Math.max(1, cap / surfaceForms.size());

        Map<String, ChunkCandidate> best = new LinkedHashMap<>();
        for (String form : surfaceForms) {
            List<ScoredDocument> hits =
                    vectorStore.similaritySearchWithScores(form, perForm, threshold);
            for (ChunkCandidate candidate : VectorStoreCandidates.toCandidates(hits,
                    DiscoveryChannel.DIRECT_IDENTIFIER, "matched identifier \"" + form + "\"")) {
                // One chunk found under two aliases is one proposal, at its best score. The
                // partition would merge these anyway; deduping here keeps the round's proposal
                // count an honest measure of how much distinct evidence the channel found.
                best.merge(candidate.chunkId(), candidate,
                        (existing, incoming) -> existing.confidence() >= incoming.confidence()
                                ? existing : incoming);
            }
            if (best.size() >= cap) {
                break;
            }
        }

        List<ChunkCandidate> candidates = new ArrayList<>(best.values());
        return candidates.size() > cap ? candidates.subList(0, cap) : candidates;
    }

    private List<String> surfaceFormsFor(EntityPartition partition) {
        if (partition == null) {
            return List.of();
        }
        List<String> forms = new ArrayList<>();
        List<String> supplied = identifiers.apply(partition);
        if (supplied != null) {
            for (String form : supplied) {
                if (form != null && !form.isBlank() && !forms.contains(form.trim())) {
                    forms.add(form.trim());
                }
            }
        }
        if (forms.isEmpty()) {
            String subject = partition.key().subject();
            if (subject != null && !subject.isBlank()) {
                forms.add(subject);
            }
        }
        return forms;
    }
}
