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
package ai.kompile.graph.reasoning.embedding;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEmbeddingResolverTest {

    @Test
    void preservesDirectVectorsAndResolvesRelationsFromEndpoints() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(GraphEntity.builder("a").type("NODE").label("A")
                .embedding(new double[]{1.0, 0.0}).build());
        graph.addEntity(GraphEntity.builder("b").type("NODE").label("B")
                .embedding(new double[]{0.0, 1.0}).build());
        graph.addRelation("ab", "a", "b", "LINK", 1.0);

        GraphEmbeddingResolver.Resolved direct = GraphEmbeddingResolver.resolve(graph, "a");
        GraphEmbeddingResolver.Resolved relation = GraphEmbeddingResolver.resolve(graph, "ab");

        assertEquals(GraphEmbeddingResolver.Origin.DIRECT_ENTITY, direct.origin());
        assertEquals(GraphEmbeddingResolver.Origin.RELATION_ENDPOINTS, relation.origin());
        assertEquals(2, relation.supportCount());
        assertTrue(Embeddings.cosine(relation.vector(), new double[]{1.0, 1.0}) > 0.99);
    }

    @Test
    void propagatesSparseEmbeddingsAcrossBoundedNeighborhoods() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(GraphEntity.builder("a").type("NODE").label("A")
                .embedding(new double[]{1.0, 0.25, 0.0}).build());
        graph.addEntity("b", "NODE", "B");
        graph.addEntity("c", "NODE", "C");
        graph.addRelation("ab", "a", "b", "LINK", 1.0);
        graph.addRelation("bc", "b", "c", "LINK", 0.8);

        GraphEmbeddingResolver.Resolved oneHop = GraphEmbeddingResolver.resolve(graph, "b", 1);
        GraphEmbeddingResolver.Resolved twoHops = GraphEmbeddingResolver.resolve(graph, "c", 2);

        assertTrue(oneHop.present());
        assertTrue(twoHops.present());
        assertEquals(GraphEmbeddingResolver.Origin.NEIGHBORHOOD, twoHops.origin());
        assertEquals(2, twoHops.hops());
        assertTrue(Embeddings.cosine(oneHop.vector(), twoHops.vector()) > 0.99);
    }

    @Test
    void invalidDirectVectorFallsBackToValidNeighborhood() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(GraphEntity.builder("bad").type("NODE").label("Bad")
                .embedding(new double[]{Double.NaN, 1.0}).build());
        graph.addEntity(GraphEntity.builder("good").type("NODE").label("Good")
                .embedding(new double[]{0.0, 1.0}).build());
        graph.addRelation("link", "bad", "good", "LINK", 1.0);

        GraphEmbeddingResolver.Resolved resolved = GraphEmbeddingResolver.resolve(graph, "bad", 1);

        assertTrue(resolved.present());
        assertEquals(GraphEmbeddingResolver.Origin.NEIGHBORHOOD, resolved.origin());
        assertTrue(Embeddings.cosine(resolved.vector(), new double[]{0.0, 1.0}) > 0.99);
    }
}
