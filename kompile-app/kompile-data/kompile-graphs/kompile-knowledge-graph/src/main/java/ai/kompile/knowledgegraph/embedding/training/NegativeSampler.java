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

package ai.kompile.knowledgegraph.embedding.training;

import ai.kompile.core.kgembedding.Triple;

import java.util.*;

/**
 * Generates negative samples by corrupting positive triples.
 *
 * <p>Negative sampling is essential for training knowledge graph embeddings.
 * The model learns to score positive triples lower (better) than corrupted
 * negative triples.
 */
public class NegativeSampler {

    private final List<String> entityList;
    private final Set<String> entitySet;
    private final Set<String> existingTriples;
    private final Random random;

    /**
     * Creates a negative sampler.
     *
     * @param entities Set of all entities in the knowledge graph
     */
    public NegativeSampler(Set<String> entities) {
        this(entities, new HashSet<>());
    }

    /**
     * Creates a negative sampler with existing triple filtering.
     *
     * @param entities Set of all entities
     * @param triples Existing triples to avoid generating as negatives
     */
    public NegativeSampler(Set<String> entities, Set<Triple> triples) {
        this.entityList = new ArrayList<>(entities);
        this.entitySet = entities;
        this.existingTriples = new HashSet<>();
        for (Triple t : triples) {
            this.existingTriples.add(tripleKey(t));
        }
        this.random = new Random();
    }

    /**
     * Creates a key for a triple for fast lookup.
     */
    private String tripleKey(Triple t) {
        return t.head() + "\t" + t.relation() + "\t" + t.tail();
    }

    /**
     * Generates corrupted (negative) versions of the given triples.
     *
     * @param positiveTriples The positive triples to corrupt
     * @param numNegatives Number of negatives per positive
     * @return List of negative triples
     */
    public List<Triple> corrupt(List<Triple> positiveTriples, int numNegatives) {
        List<Triple> negatives = new ArrayList<>(positiveTriples.size() * numNegatives);

        for (Triple positive : positiveTriples) {
            for (int i = 0; i < numNegatives; i++) {
                Triple negative = corruptTriple(positive);
                negatives.add(negative);
            }
        }

        return negatives;
    }

    /**
     * Generates a single corrupted version of a triple.
     * Randomly corrupts either the head or tail.
     */
    public Triple corruptTriple(Triple positive) {
        // 50% chance to corrupt head, 50% chance to corrupt tail
        boolean corruptHead = random.nextBoolean();
        return corruptHead ?
                corruptHead(positive) :
                corruptTail(positive);
    }

    /**
     * Corrupts the head entity of a triple.
     */
    public Triple corruptHead(Triple triple) {
        String newHead;
        int attempts = 0;
        do {
            newHead = entityList.get(random.nextInt(entityList.size()));
            attempts++;
            // Avoid infinite loop if all entities would create valid triples
            if (attempts > 100) break;
        } while (newHead.equals(triple.head()) ||
                existingTriples.contains(tripleKey(new Triple(newHead, triple.relation(), triple.tail()))));

        return triple.corruptHead(newHead);
    }

    /**
     * Corrupts the tail entity of a triple.
     */
    public Triple corruptTail(Triple triple) {
        String newTail;
        int attempts = 0;
        do {
            newTail = entityList.get(random.nextInt(entityList.size()));
            attempts++;
            if (attempts > 100) break;
        } while (newTail.equals(triple.tail()) ||
                existingTriples.contains(tripleKey(new Triple(triple.head(), triple.relation(), newTail))));

        return triple.corruptTail(newTail);
    }

    /**
     * Gets a random entity.
     */
    public String randomEntity() {
        return entityList.get(random.nextInt(entityList.size()));
    }

    /**
     * Returns the number of entities.
     */
    public int entityCount() {
        return entityList.size();
    }
}
