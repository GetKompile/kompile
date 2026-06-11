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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NegativeSampler.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NegativeSamplerTest {

    private static final Set<String> ENTITIES = new LinkedHashSet<>(
            Arrays.asList("Alice", "Bob", "Charlie", "Dave", "Eve")
    );

    @Test
    void constructor_withEntities_setsEntityCount() {
        NegativeSampler sampler = new NegativeSampler(ENTITIES);
        assertEquals(5, sampler.entityCount());
    }

    @Test
    void constructor_withEntitiesAndTriples_setsEntityCount() {
        Set<Triple> existingTriples = new HashSet<>();
        existingTriples.add(new Triple("Alice", "knows", "Bob"));
        NegativeSampler sampler = new NegativeSampler(ENTITIES, existingTriples);
        assertEquals(5, sampler.entityCount());
    }

    @Test
    void randomEntity_returnsEntityFromSet() {
        NegativeSampler sampler = new NegativeSampler(ENTITIES);
        for (int i = 0; i < 20; i++) {
            String entity = sampler.randomEntity();
            assertTrue(ENTITIES.contains(entity), "Random entity should be in entity set: " + entity);
        }
    }

    @Test
    void corruptHead_returnsTripleWithSameRelationAndTail() {
        NegativeSampler sampler = new NegativeSampler(ENTITIES);
        Triple positive = new Triple("Alice", "knows", "Bob");
        Triple corrupted = sampler.corruptHead(positive);

        assertNotNull(corrupted);
        assertEquals("knows", corrupted.relation());
        assertEquals("Bob", corrupted.tail());
        assertTrue(ENTITIES.contains(corrupted.head()), "Corrupted head should be in entity set");
    }

    @Test
    void corruptTail_returnsTripleWithSameHeadAndRelation() {
        NegativeSampler sampler = new NegativeSampler(ENTITIES);
        Triple positive = new Triple("Alice", "knows", "Bob");
        Triple corrupted = sampler.corruptTail(positive);

        assertNotNull(corrupted);
        assertEquals("Alice", corrupted.head());
        assertEquals("knows", corrupted.relation());
        assertTrue(ENTITIES.contains(corrupted.tail()), "Corrupted tail should be in entity set");
    }

    @Test
    void corruptTriple_returnsValidCorruptedTriple() {
        NegativeSampler sampler = new NegativeSampler(ENTITIES);
        Triple positive = new Triple("Alice", "knows", "Bob");

        // Run multiple times to exercise both head and tail corruption paths
        for (int i = 0; i < 20; i++) {
            Triple corrupted = sampler.corruptTriple(positive);
            assertNotNull(corrupted);
            assertEquals("knows", corrupted.relation(), "Relation should be preserved");
            assertTrue(ENTITIES.contains(corrupted.head()), "Head must be in entity set");
            assertTrue(ENTITIES.contains(corrupted.tail()), "Tail must be in entity set");
        }
    }

    @Test
    void corrupt_producesCorrectNumberOfNegatives() {
        NegativeSampler sampler = new NegativeSampler(ENTITIES);
        List<Triple> positives = Arrays.asList(
                new Triple("Alice", "knows", "Bob"),
                new Triple("Charlie", "likes", "Dave")
        );
        int numNegatives = 3;

        List<Triple> negatives = sampler.corrupt(positives, numNegatives);
        assertEquals(positives.size() * numNegatives, negatives.size());
    }

    @Test
    void corrupt_withSinglePositive_returnsCorrectCount() {
        NegativeSampler sampler = new NegativeSampler(ENTITIES);
        List<Triple> positives = List.of(new Triple("Alice", "knows", "Bob"));
        List<Triple> negatives = sampler.corrupt(positives, 5);
        assertEquals(5, negatives.size());
    }

    @Test
    void corrupt_negativesHaveSameRelation() {
        NegativeSampler sampler = new NegativeSampler(ENTITIES);
        Triple positive = new Triple("Alice", "KNOWS", "Bob");
        List<Triple> negatives = sampler.corrupt(List.of(positive), 10);

        for (Triple neg : negatives) {
            assertEquals("KNOWS", neg.relation(), "Negatives should preserve relation type");
        }
    }

    @Test
    void corruptHead_avoidsExistingTriples() {
        // Set up so that (Bob, knows, Bob) is an existing triple
        Set<Triple> existing = new HashSet<>();
        existing.add(new Triple("Bob", "knows", "Bob"));
        existing.add(new Triple("Charlie", "knows", "Bob"));
        existing.add(new Triple("Dave", "knows", "Bob"));

        NegativeSampler sampler = new NegativeSampler(ENTITIES, existing);
        Triple positive = new Triple("Alice", "knows", "Bob");

        // Over many attempts, corrupted head should avoid creating existing triples
        for (int i = 0; i < 20; i++) {
            Triple corrupted = sampler.corruptHead(positive);
            // Should be a valid entity
            assertTrue(ENTITIES.contains(corrupted.head()));
        }
    }

    @Test
    void entityCount_matchesInputSize() {
        Set<String> smallSet = new HashSet<>(Arrays.asList("A", "B", "C"));
        NegativeSampler sampler = new NegativeSampler(smallSet);
        assertEquals(3, sampler.entityCount());
    }

    @Test
    void corrupt_withEmptyPositives_returnsEmptyList() {
        NegativeSampler sampler = new NegativeSampler(ENTITIES);
        List<Triple> negatives = sampler.corrupt(Collections.emptyList(), 5);
        assertTrue(negatives.isEmpty());
    }
}
