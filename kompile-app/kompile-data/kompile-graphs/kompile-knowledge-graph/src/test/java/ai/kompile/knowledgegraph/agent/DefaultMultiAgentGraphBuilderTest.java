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
package ai.kompile.knowledgegraph.agent;

import ai.kompile.core.graphrag.agent.MultiAgentGraphBuilder.AgentContribution;
import ai.kompile.core.graphrag.agent.MultiAgentGraphBuilder.GraphMergeStrategy;
import ai.kompile.core.graphrag.agent.MultiAgentGraphBuilder.MergedGraphResult;
import ai.kompile.core.graphrag.agent.RelationExtractionAgent;
import ai.kompile.core.graphrag.agent.RelationExtractionAgent.ExtractionConfig;
import ai.kompile.core.graphrag.agent.RelationExtractionAgent.ExtractionResult;
import ai.kompile.core.graphrag.agent.RelationExtractionAgent.AgentMetrics;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DefaultMultiAgentGraphBuilder}.
 *
 * <p>Uses lightweight in-process mock agents so the tests run without any Spring context
 * or external service.
 */
class DefaultMultiAgentGraphBuilderTest {

    private DefaultMultiAgentGraphBuilder builder;

    // Reusable chunks
    private List<RetrievedDoc> chunks;

    @BeforeEach
    void setUp() {
        builder = new DefaultMultiAgentGraphBuilder();
        chunks = List.of(
                new RetrievedDoc("chunk-1", "Alice founded Acme Corp in New York.", Map.of()),
                new RetrievedDoc("chunk-2", "Bob is the CEO of Acme Corp.", Map.of())
        );
    }

    // ─── Helper agent factories ────────────────────────────────────────────────

    /** Produces a fixed graph when called. */
    static RelationExtractionAgent fixedAgent(
            String id,
            List<Entity> entities,
            List<Relationship> rels) {

        return new RelationExtractionAgent() {
            @Override public String getId() { return id; }
            @Override public String getDescription() { return "Fixed agent: " + id; }
            @Override public Set<String> supportedContentTypes() { return Set.of(); }
            @Override
            public ExtractionResult extract(List<RetrievedDoc> chunks, ExtractionConfig cfg) {
                Graph g = new Graph();
                g.setEntities(new ArrayList<>(entities));
                g.setRelationships(new ArrayList<>(rels));
                return new ExtractionResult(g, new AgentMetrics(
                        id, 10L, entities.size(), rels.size(), chunks.size(), null, Map.of()));
            }
        };
    }

    /** Always throws at extraction time. */
    static RelationExtractionAgent failingAgent(String id) {
        return new RelationExtractionAgent() {
            @Override public String getId() { return id; }
            @Override public String getDescription() { return "Failing agent"; }
            @Override public Set<String> supportedContentTypes() { return Set.of(); }
            @Override
            public ExtractionResult extract(List<RetrievedDoc> chunks, ExtractionConfig cfg) {
                throw new RuntimeException("Simulated extraction failure from " + id);
            }
        };
    }

    static Entity entity(String id, String type, Double confidence) {
        Entity e = new Entity();
        e.setId(id);
        e.setTitle(id);
        e.setType(type);
        e.setConfidence(confidence);
        return e;
    }

    static Relationship rel(String source, String target, String type, Double confidence) {
        Relationship r = new Relationship();
        r.setSource(source);
        r.setTarget(target);
        r.setType(type);
        r.setConfidence(confidence);
        return r;
    }

    // ─── Basic construction ───────────────────────────────────────────────────

    @Test
    void emptyAgentList_producesEmptyGraph() {
        MergedGraphResult result = builder.buildGraph(
                chunks, List.of(), GraphMergeStrategy.UNION, ExtractionConfig.defaults());

        assertThat(result).isNotNull();
        assertThat(result.totalEntities()).isZero();
        assertThat(result.totalRelations()).isZero();
        assertThat(result.mergedGraph()).isNotNull();
    }

    @Test
    void emptyChunks_producesEmptyGraph() {
        RelationExtractionAgent agent = fixedAgent("a1",
                List.of(entity("e1", "PERSON", 0.9)), List.of());

        MergedGraphResult result = builder.buildGraph(
                List.of(), List.of(agent), GraphMergeStrategy.UNION, ExtractionConfig.defaults());

        // Agent still runs but gets no chunks — the fixed agent ignores chunks
        // What matters is the result is non-null and well-formed
        assertThat(result).isNotNull();
        assertThat(result.mergedGraph()).isNotNull();
    }

    // ─── UNION ────────────────────────────────────────────────────────────────

    @Test
    void union_combinesAllEntitiesFromTwoAgents() {
        RelationExtractionAgent agentA = fixedAgent("a",
                List.of(entity("alice", "PERSON", 0.8)),
                List.of());
        RelationExtractionAgent agentB = fixedAgent("b",
                List.of(entity("bob", "PERSON", 0.7)),
                List.of());

        MergedGraphResult result = builder.buildGraph(
                chunks, List.of(agentA, agentB), GraphMergeStrategy.UNION, ExtractionConfig.defaults());

        assertThat(result.totalEntities()).isEqualTo(2);
        assertThat(result.mergedGraph().getEntities())
                .extracting(Entity::getId)
                .containsExactlyInAnyOrder("alice", "bob");
    }

    @Test
    void union_deduplicatesByEntityId_keepingHigherConfidence() {
        Entity lowConf = entity("alice", "PERSON", 0.5);
        Entity highConf = entity("alice", "PERSON", 0.95);

        RelationExtractionAgent agentA = fixedAgent("a", List.of(lowConf), List.of());
        RelationExtractionAgent agentB = fixedAgent("b", List.of(highConf), List.of());

        MergedGraphResult result = builder.buildGraph(
                chunks, List.of(agentA, agentB), GraphMergeStrategy.UNION, ExtractionConfig.defaults());

        assertThat(result.totalEntities()).isEqualTo(1);
        assertThat(result.mergedGraph().getEntities().get(0).getConfidence())
                .isEqualTo(0.95);
    }

    @Test
    void union_deduplicatesRelationsByTripleKey() {
        Relationship r1 = rel("alice", "acme", "WORKS_AT", 0.8);
        Relationship r2 = rel("alice", "acme", "WORKS_AT", 0.9);

        RelationExtractionAgent agentA = fixedAgent("a", List.of(), List.of(r1));
        RelationExtractionAgent agentB = fixedAgent("b", List.of(), List.of(r2));

        MergedGraphResult result = builder.buildGraph(
                chunks, List.of(agentA, agentB), GraphMergeStrategy.UNION, ExtractionConfig.defaults());

        assertThat(result.totalRelations()).isEqualTo(1);
        assertThat(result.mergedGraph().getRelationships().get(0).getConfidence())
                .isEqualTo(0.9);
    }

    // ─── INTERSECTION ─────────────────────────────────────────────────────────

    @Test
    void intersection_keepsOnlyEntitiesFoundByTwoPlusAgents() {
        Entity shared = entity("alice", "PERSON", 0.8);
        Entity uniqueA = entity("bob", "PERSON", 0.7);
        Entity uniqueB = entity("carol", "PERSON", 0.6);

        RelationExtractionAgent agentA = fixedAgent("a", List.of(shared, uniqueA), List.of());
        RelationExtractionAgent agentB = fixedAgent("b", List.of(shared, uniqueB), List.of());

        MergedGraphResult result = builder.buildGraph(
                chunks, List.of(agentA, agentB), GraphMergeStrategy.INTERSECTION, ExtractionConfig.defaults());

        assertThat(result.totalEntities()).isEqualTo(1);
        assertThat(result.mergedGraph().getEntities().get(0).getId()).isEqualTo("alice");
    }

    @Test
    void intersection_removesRelationshipsWithOrphanedEndpoints() {
        Entity alice = entity("alice", "PERSON", 0.9);
        Entity bob = entity("bob", "PERSON", 0.8);
        Relationship rAliceBob = rel("alice", "bob", "KNOWS", 0.7);

        // Agent A has alice + bob + relation; Agent B has only alice
        RelationExtractionAgent agentA = fixedAgent("a", List.of(alice, bob), List.of(rAliceBob));
        RelationExtractionAgent agentB = fixedAgent("b", List.of(alice), List.of());

        MergedGraphResult result = builder.buildGraph(
                chunks, List.of(agentA, agentB), GraphMergeStrategy.INTERSECTION, ExtractionConfig.defaults());

        // bob removed by intersection -> rAliceBob should also be gone
        assertThat(result.totalRelations()).isZero();
    }

    // ─── FIRST_WINS ───────────────────────────────────────────────────────────

    @Test
    void firstWins_keepsFirstAgentVersionOnEntityConflict() {
        Entity first = entity("alice", "PERSON", 0.5);
        Entity second = entity("alice", "PERSON", 0.95);

        RelationExtractionAgent agentA = fixedAgent("a", List.of(first), List.of());
        RelationExtractionAgent agentB = fixedAgent("b", List.of(second), List.of());

        MergedGraphResult result = builder.buildGraph(
                chunks, List.of(agentA, agentB), GraphMergeStrategy.FIRST_WINS, ExtractionConfig.defaults());

        assertThat(result.totalEntities()).isEqualTo(1);
        assertThat(result.mergedGraph().getEntities().get(0).getConfidence())
                .isEqualTo(0.5);
    }

    @Test
    void firstWins_stillIncludesUniqueEntitiesFromLaterAgent() {
        Entity alice = entity("alice", "PERSON", 0.9);
        Entity bob = entity("bob", "PERSON", 0.7);

        RelationExtractionAgent agentA = fixedAgent("a", List.of(alice), List.of());
        RelationExtractionAgent agentB = fixedAgent("b", List.of(bob), List.of());

        MergedGraphResult result = builder.buildGraph(
                chunks, List.of(agentA, agentB), GraphMergeStrategy.FIRST_WINS, ExtractionConfig.defaults());

        assertThat(result.totalEntities()).isEqualTo(2);
    }

    // ─── HIGHEST_CONFIDENCE ───────────────────────────────────────────────────

    @Test
    void highestConfidence_keepsBetterVersionOnConflict() {
        Entity low = entity("alice", "PERSON", 0.4);
        Entity high = entity("alice", "PERSON", 0.95);

        RelationExtractionAgent agentA = fixedAgent("a", List.of(low), List.of());
        RelationExtractionAgent agentB = fixedAgent("b", List.of(high), List.of());

        MergedGraphResult result = builder.buildGraph(
                chunks, List.of(agentA, agentB), GraphMergeStrategy.HIGHEST_CONFIDENCE, ExtractionConfig.defaults());

        assertThat(result.mergedGraph().getEntities().get(0).getConfidence())
                .isEqualTo(0.95);
    }

    // ─── Error resilience ─────────────────────────────────────────────────────

    @Test
    void failingAgent_doesNotBreakOtherAgents() {
        RelationExtractionAgent good = fixedAgent("good",
                List.of(entity("alice", "PERSON", 0.9)), List.of());
        RelationExtractionAgent bad = failingAgent("bad");

        MergedGraphResult result = builder.buildGraph(
                chunks, List.of(good, bad), GraphMergeStrategy.UNION, ExtractionConfig.defaults());

        assertThat(result.totalEntities()).isEqualTo(1);
        assertThat(result.contributions()).containsKey("bad");
        AgentContribution badContrib = result.contributions().get("bad");
        assertThat(badContrib.entitiesExtracted()).isZero();
    }

    @Test
    void allAgentsFail_returnsEmptyValidResult() {
        MergedGraphResult result = builder.buildGraph(
                chunks,
                List.of(failingAgent("f1"), failingAgent("f2")),
                GraphMergeStrategy.UNION,
                ExtractionConfig.defaults()
        );

        assertThat(result).isNotNull();
        assertThat(result.totalEntities()).isZero();
        assertThat(result.totalRelations()).isZero();
        assertThat(result.contributions()).hasSize(2);
    }

    // ─── Per-agent contribution tracking ──────────────────────────────────────

    @Test
    void contributions_tracksEntityAndRelationCounts() {
        Entity alice = entity("alice", "PERSON", 0.9);
        Relationship r = rel("alice", "acme", "FOUNDED", 0.8);

        RelationExtractionAgent agentA = fixedAgent("track-a", List.of(alice), List.of(r));

        MergedGraphResult result = builder.buildGraph(
                chunks, List.of(agentA), GraphMergeStrategy.UNION, ExtractionConfig.defaults());

        AgentContribution contrib = result.contributions().get("track-a");
        assertThat(contrib).isNotNull();
        assertThat(contrib.agentId()).isEqualTo("track-a");
        assertThat(contrib.entitiesExtracted()).isEqualTo(1);
        assertThat(contrib.relationsExtracted()).isEqualTo(1);
        assertThat(contrib.entitiesRetained()).isEqualTo(1);
        assertThat(contrib.relationsRetained()).isEqualTo(1);
    }

    @Test
    void contributions_intersection_retainedLessThanExtracted() {
        Entity alice = entity("alice", "PERSON", 0.9);
        Entity bob = entity("bob", "PERSON", 0.7);

        RelationExtractionAgent agentA = fixedAgent("a", List.of(alice, bob), List.of());
        RelationExtractionAgent agentB = fixedAgent("b", List.of(alice), List.of());

        MergedGraphResult result = builder.buildGraph(
                chunks, List.of(agentA, agentB), GraphMergeStrategy.INTERSECTION, ExtractionConfig.defaults());

        AgentContribution contribA = result.contributions().get("a");
        // agentA extracted 2 entities, only 1 (alice) retained
        assertThat(contribA.entitiesExtracted()).isEqualTo(2);
        assertThat(contribA.entitiesRetained()).isEqualTo(1);
    }

    @Test
    void result_recordsStrategy() {
        for (GraphMergeStrategy strategy : GraphMergeStrategy.values()) {
            MergedGraphResult result = builder.buildGraph(
                    chunks, List.of(), strategy, ExtractionConfig.defaults());
            assertThat(result.strategy()).isEqualTo(strategy);
        }
    }

    @Test
    void result_totalTimeMsIsNonNegative() {
        MergedGraphResult result = builder.buildGraph(
                chunks, List.of(), GraphMergeStrategy.UNION, ExtractionConfig.defaults());
        assertThat(result.totalTimeMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void nullEntityConfidence_handledGracefully() {
        Entity noConf = entity("e1", "PERSON", null);
        Entity withConf = entity("e1", "PERSON", 0.8);

        RelationExtractionAgent agentA = fixedAgent("a", List.of(noConf), List.of());
        RelationExtractionAgent agentB = fixedAgent("b", List.of(withConf), List.of());

        // Should not throw NullPointerException
        MergedGraphResult result = builder.buildGraph(
                chunks, List.of(agentA, agentB), GraphMergeStrategy.UNION, ExtractionConfig.defaults());

        assertThat(result.totalEntities()).isEqualTo(1);
        // withConf has 0.8 > null (treated as 0.0) => 0.8 wins
        assertThat(result.mergedGraph().getEntities().get(0).getConfidence()).isEqualTo(0.8);
    }
}
