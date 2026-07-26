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

package ai.kompile.core.graphrag.partition.staging;

import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.partition.ChunkCandidate;
import ai.kompile.core.graphrag.partition.DiscoveryChannel;
import ai.kompile.core.graphrag.partition.MembershipState;
import ai.kompile.core.graphrag.partition.PartitionMember;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a staged graph owes the partition that fills it: one entity per thing, a receipt for every
 * disagreement it settled, and an output the ordinary write path can take without deduplicating
 * anything itself.
 */
class StagedGraphTest {

    // ---------------------------------------------------------------------
    // fixtures
    // ---------------------------------------------------------------------

    private static Entity entity(String id, String title, String type, String description) {
        Entity entity = new Entity();
        entity.setId(id);
        entity.setTitle(title);
        entity.setType(type);
        entity.setDescription(description);
        return entity;
    }

    private static Relationship relationship(String source, String target, String type,
                                             String description) {
        Relationship relationship = new Relationship();
        relationship.setSource(source);
        relationship.setTarget(target);
        relationship.setType(type);
        relationship.setDescription(description);
        return relationship;
    }

    private static Graph graph(List<Entity> entities, List<Relationship> relationships) {
        return Graph.builder().entities(entities).relationships(relationships).build();
    }

    private static StagedProvenance from(String chunkId) {
        return StagedProvenance.ofChunk(chunkId);
    }

    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("folding one chunk's output in")
    class Staging {

        @Test
        void anEmptyGraphLeavesTheStagedGraphAloneRatherThanCopyingIt() {
            StagedGraph staged = StagedGraph.empty();

            assertSame(staged, staged.stage(graph(List.of(), List.of()), from("c1")));
            assertSame(staged, staged.stage(null, from("c1")));
            assertSame(staged, staged.stage(graph(List.of(), List.of()), null));
        }

        @Test
        void stagingReturnsANewGraphAndLeavesTheOldOneUntouched() {
            StagedGraph before = StagedGraph.empty();
            StagedGraph after = before.stage(
                    graph(List.of(entity("e1", "Acme", "COMPANY", null)), List.of()), from("c1"));

            assertNotSame(before, after);
            assertTrue(before.isEmpty());
            assertEquals(1, after.entities().size());
        }

        @Test
        void anEntityWithNothingToKeyOnIsSkippedRatherThanStagedUnderABlankKey() {
            StagedGraph staged = StagedGraph.empty().stage(
                    graph(List.of(entity(null, "  ", "COMPANY", "nameless")), List.of()),
                    from("c1"));

            assertTrue(staged.isEmpty());
        }

        @Test
        void aMemberStagesUnderItsOwnChunkChannelAndRound() {
            PartitionMember member = PartitionMember.admit(
                    ChunkCandidate.of("c7", DiscoveryChannel.DIRECT_IDENTIFIER, 0.9)
                            .inDocument("d1"),
                    MembershipState.DISCOVERED, 3);

            StagedGraph staged = StagedGraph.empty()
                    .stage(member, graph(List.of(entity("e1", "Acme", "COMPANY", null)), List.of()));

            StagedProvenance provenance = staged.entity("acme").orElseThrow().provenance().get(0);
            assertEquals("c7", provenance.chunkId());
            assertEquals("d1", provenance.documentId());
            assertEquals(DiscoveryChannel.DIRECT_IDENTIFIER, provenance.channel());
            assertEquals(3, provenance.round());
        }
    }

    @Nested
    @DisplayName("two chunks about the same entity")
    class Merging {

        @Test
        void theSameNameFromTwoChunksIsOneEntityWithTwoProvenanceEntries() {
            StagedGraph staged = StagedGraph.empty()
                    .stage(graph(List.of(entity("e1", "Acme", "COMPANY", null)), List.of()),
                            from("c1"))
                    .stage(graph(List.of(entity("x9", "acme", "COMPANY", null)), List.of()),
                            from("c2"));

            assertEquals(1, staged.entities().size());
            assertEquals(List.of("c1", "c2"), staged.entity("acme").orElseThrow().chunkIds());
        }

        @Test
        void caseAndSpacingDoNotMakeTwoEntities() {
            StagedGraph staged = StagedGraph.empty()
                    .stage(graph(List.of(entity("e1", "Acme  Corp", null, null)), List.of()),
                            from("c1"))
                    .stage(graph(List.of(entity("e2", "  acme corp ", null, null)), List.of()),
                            from("c2"));

            assertEquals(1, staged.entities().size());
            assertTrue(staged.entity("acme corp").isPresent());
        }

        @Test
        void theFirstChunkToDescribeSomethingKeepsTheDescription() {
            StagedGraph staged = StagedGraph.empty()
                    .stage(graph(List.of(entity("e1", "Acme", "COMPANY",
                            "a manufacturer of anvils")), List.of()), from("c1"))
                    .stage(graph(List.of(entity("e1", "Acme", "COMPANY", "a company")), List.of()),
                            from("c2"));

            assertEquals("a manufacturer of anvils",
                    staged.entity("acme").orElseThrow().description());
        }

        @Test
        void aLaterChunkFillsInWhatTheFirstOneLeftBlank() {
            StagedGraph staged = StagedGraph.empty()
                    .stage(graph(List.of(entity("e1", "Acme", null, null)), List.of()), from("c1"))
                    .stage(graph(List.of(entity("e1", "Acme", "COMPANY", "makes anvils")),
                            List.of()), from("c2"));

            StagedEntity acme = staged.entity("acme").orElseThrow();
            assertEquals("COMPANY", acme.type());
            assertEquals("makes anvils", acme.description());
            assertTrue(acme.conflicts().isEmpty(), "filling a blank is not a disagreement");
        }

        @Test
        void confidenceTakesTheHighestAnyChunkReported() {
            Entity low = entity("e1", "Acme", "COMPANY", null);
            low.setConfidence(0.4);
            Entity high = entity("e1", "Acme", "COMPANY", null);
            high.setConfidence(0.85);

            StagedGraph staged = StagedGraph.empty()
                    .stage(graph(List.of(high), List.of()), from("c1"))
                    .stage(graph(List.of(low), List.of()), from("c2"));

            assertEquals(0.85, staged.entity("acme").orElseThrow().confidence(), 1e-9);
        }

        @Test
        void aDifferentSurfaceFormBecomesAnAliasRatherThanAConflict() {
            StagedGraph staged = StagedGraph.using(new StagedKeys() {
                        @Override
                        public String entityKey(Entity entity) {
                            return "acme";
                        }

                        @Override
                        public String referenceKey(String rawReference) {
                            return "acme";
                        }
                    })
                    .stage(graph(List.of(entity("e1", "Acme", "COMPANY", null)), List.of()),
                            from("c1"))
                    .stage(graph(List.of(entity("e1", "Acme Corporation", "COMPANY", null)),
                            List.of()), from("c2"));

            StagedEntity acme = staged.entity("acme").orElseThrow();
            assertEquals("Acme", acme.title());
            assertEquals(List.of("Acme Corporation"), acme.aliases());
            assertTrue(acme.conflicts().isEmpty());
        }

        @Test
        void textUnitsFromEveryChunkAreKept() {
            Entity first = entity("e1", "Acme", "COMPANY", null);
            first.setTextUnits(List.of("t1", "t2"));
            Entity second = entity("e1", "Acme", "COMPANY", null);
            second.setTextUnits(List.of("t2", "t3"));

            StagedGraph staged = StagedGraph.empty()
                    .stage(graph(List.of(first), List.of()), from("c1"))
                    .stage(graph(List.of(second), List.of()), from("c2"));

            assertEquals(List.of("t1", "t2", "t3"),
                    staged.entity("acme").orElseThrow().textUnits());
        }

        @Test
        void metadataMergesWithoutTheEarlierValueBeingOverwritten() {
            Entity first = entity("e1", "Acme", "COMPANY", null);
            first.setMetadata(Map.of("ticker", "ACME", "source", "10-K"));
            Entity second = entity("e1", "Acme", "COMPANY", null);
            second.setMetadata(Map.of("ticker", "ACM", "sector", "industrials"));

            StagedGraph staged = StagedGraph.empty()
                    .stage(graph(List.of(first), List.of()), from("c1"))
                    .stage(graph(List.of(second), List.of()), from("c2"));

            StagedEntity acme = staged.entity("acme").orElseThrow();
            assertEquals("ACME", acme.metadata().get("ticker"));
            assertEquals("industrials", acme.metadata().get("sector"));
        }

        @Test
        void restagingTheSameChunkDoesNotDoubleItsProvenance() {
            Graph produced = graph(List.of(entity("e1", "Acme", "COMPANY", null)), List.of());

            StagedGraph staged = StagedGraph.empty()
                    .stage(produced, from("c1"))
                    .stage(produced, from("c1"));

            assertEquals(List.of("c1"), staged.entity("acme").orElseThrow().chunkIds());
        }
    }

    @Nested
    @DisplayName("recording disagreement instead of silently resolving it")
    class Conflicts {

        @Test
        void twoChunksTypingTheSameEntityDifferentlyKeepsTheFirstAndWritesAReceipt() {
            StagedGraph staged = StagedGraph.empty()
                    .stage(graph(List.of(entity("e1", "Acme", "COMPANY", null)), List.of()),
                            from("c1"))
                    .stage(graph(List.of(entity("e1", "Acme", "ORGANIZATION", null)), List.of()),
                            from("c2"));

            StagedEntity acme = staged.entity("acme").orElseThrow();
            assertEquals("COMPANY", acme.type());
            assertEquals(1, acme.conflicts().size());
            StagedConflict conflict = acme.conflicts().get(0);
            assertEquals("type", conflict.field());
            assertEquals("COMPANY", conflict.kept());
            assertEquals("ORGANIZATION", conflict.rejected());
            assertEquals("c2", conflict.chunkId(),
                    "the receipt has to name the chunk whose answer was dropped");
        }

        @Test
        void aDifferingDescriptionIsAConflictToo() {
            StagedGraph staged = StagedGraph.empty()
                    .stage(graph(List.of(entity("e1", "Acme", "COMPANY", "makes anvils")),
                            List.of()), from("c1"))
                    .stage(graph(List.of(entity("e1", "Acme", "COMPANY", "makes rockets")),
                            List.of()), from("c2"));

            assertEquals(List.of("description"),
                    staged.conflicts().stream().map(StagedConflict::field).toList());
        }

        @Test
        void agreeingChunksProduceNoConflicts() {
            StagedGraph staged = StagedGraph.empty()
                    .stage(graph(List.of(entity("e1", "Acme", "COMPANY", "makes anvils")),
                            List.of()), from("c1"))
                    .stage(graph(List.of(entity("e1", "Acme", "company", "makes anvils")),
                            List.of()), from("c2"));

            assertTrue(staged.conflicts().isEmpty(),
                    "type differing only in case is the same answer");
        }

        @Test
        void metadataClashesAreNotConflictsBecauseMetadataIsNotAClaim() {
            Entity first = entity("e1", "Acme", "COMPANY", null);
            first.setMetadata(Map.of("ticker", "ACME"));
            Entity second = entity("e1", "Acme", "COMPANY", null);
            second.setMetadata(Map.of("ticker", "ACM"));

            StagedGraph staged = StagedGraph.empty()
                    .stage(graph(List.of(first), List.of()), from("c1"))
                    .stage(graph(List.of(second), List.of()), from("c2"));

            assertTrue(staged.conflicts().isEmpty());
        }

        @Test
        void conflictsFromEntitiesAndEdgesAreAllReportedTogether() {
            StagedGraph staged = StagedGraph.empty()
                    .stage(graph(
                            List.of(entity("e1", "Acme", "COMPANY", null),
                                    entity("e2", "Bob", "PERSON", null)),
                            List.of(relationship("e1", "e2", "employs", "since 2019"))),
                            from("c1"))
                    .stage(graph(
                            List.of(entity("e1", "Acme", "ORGANIZATION", null)),
                            List.of(relationship("Acme", "Bob", "employs", "since 2021"))),
                            from("c2"));

            assertEquals(List.of("type", "description"),
                    staged.conflicts().stream().map(StagedConflict::field).toList());
        }
    }

    @Nested
    @DisplayName("edges")
    class Relationships {

        @Test
        void anEndpointGivenAsAnExtractorIdResolvesToTheEntityItNamed() {
            StagedGraph staged = StagedGraph.empty().stage(graph(
                    List.of(entity("e1", "Acme", "COMPANY", null),
                            entity("e2", "Bob", "PERSON", null)),
                    List.of(relationship("e1", "e2", "employs", null))), from("c1"));

            StagedRelationship edge = staged.relationships().iterator().next();
            assertEquals("acme", edge.sourceKey());
            assertEquals("bob", edge.targetKey());
        }

        @Test
        void anEndpointGivenAsANameResolvesJustAsWell() {
            StagedGraph staged = StagedGraph.empty().stage(graph(
                    List.of(entity("e1", "Acme", "COMPANY", null),
                            entity("e2", "Bob", "PERSON", null)),
                    List.of(relationship("Acme", "Bob", "employs", null))), from("c1"));

            StagedRelationship edge = staged.relationships().iterator().next();
            assertEquals("acme", edge.sourceKey());
            assertEquals("bob", edge.targetKey());
        }

        @Test
        void anExtractorIdIsOnlyMeaningfulInsideTheChunkThatProducedIt() {
            StagedGraph staged = StagedGraph.empty()
                    .stage(graph(List.of(entity("e1", "Acme", "COMPANY", null)), List.of()),
                            from("c1"))
                    .stage(graph(List.of(entity("e1", "Zeta", "COMPANY", null)),
                            List.of(relationship("e1", "Bob", "employs", null))), from("c2"));

            StagedRelationship edge = staged.relationships().iterator().next();
            assertEquals("zeta", edge.sourceKey(),
                    "e1 in chunk c2 is c2's entity, not the one c1 happened to call e1");
        }

        @Test
        void theSameEdgeAssertedTwiceIsOneEdgeWithTwoChunksBehindIt() {
            StagedGraph staged = StagedGraph.empty()
                    .stage(graph(List.of(entity("e1", "Acme", null, null),
                                    entity("e2", "Bob", null, null)),
                            List.of(relationship("e1", "e2", "employs", null))), from("c1"))
                    .stage(graph(List.of(entity("a", "Acme", null, null),
                                    entity("b", "Bob", null, null)),
                            List.of(relationship("a", "b", "EMPLOYS", null))), from("c2"));

            assertEquals(1, staged.relationships().size());
            assertEquals(List.of("c1", "c2"),
                    staged.relationships().iterator().next().chunkIds());
        }

        @Test
        void differentRelationTypesBetweenTheSamePairStayDifferentEdges() {
            StagedGraph staged = StagedGraph.empty().stage(graph(
                    List.of(entity("e1", "Acme", null, null), entity("e2", "Bob", null, null)),
                    List.of(relationship("e1", "e2", "employs", null),
                            relationship("e1", "e2", "sued", null))), from("c1"));

            assertEquals(2, staged.relationships().size());
        }

        @Test
        void anEdgeWithAMissingEndIsDroppedBecauseNoGraphCouldUseIt() {
            StagedGraph staged = StagedGraph.empty().stage(graph(
                    List.of(entity("e1", "Acme", null, null)),
                    List.of(relationship("e1", "   ", "employs", null),
                            relationship(null, "e1", "employs", null))), from("c1"));

            assertTrue(staged.relationships().isEmpty());
        }

        @Test
        void anEdgeToSomethingThisPartitionNeverStagedIsKeptAndReported() {
            StagedGraph staged = StagedGraph.empty().stage(graph(
                    List.of(entity("e1", "Acme", null, null)),
                    List.of(relationship("e1", "Zeta Holdings", "owns", null))), from("c1"));

            assertEquals(1, staged.relationships().size(),
                    "the endpoint may already exist in the graph from an earlier partition");
            assertEquals(1, staged.danglingRelationships().size());
            assertEquals("zeta holdings",
                    staged.danglingRelationships().get(0).targetKey());
        }

        @Test
        void weightAndConfidenceTakeTheStrongestCorroboration() {
            Relationship weak = relationship("e1", "e2", "employs", null);
            weak.setWeight(0.2);
            weak.setConfidence(0.3);
            Relationship strong = relationship("e1", "e2", "employs", null);
            strong.setWeight(0.9);
            strong.setConfidence(0.7);
            List<Entity> both = List.of(entity("e1", "Acme", null, null),
                    entity("e2", "Bob", null, null));

            StagedGraph staged = StagedGraph.empty()
                    .stage(graph(both, List.of(weak)), from("c1"))
                    .stage(graph(both, List.of(strong)), from("c2"));

            StagedRelationship edge = staged.relationships().iterator().next();
            assertEquals(0.9, edge.weight(), 1e-9);
            assertEquals(0.7, edge.confidence(), 1e-9);
        }

        @Test
        void aDifferentEventTimeForTheSameEdgeIsAConflict() {
            Relationship first = relationship("e1", "e2", "employs", null);
            first.setOccurredAt("2019-01-01T00:00:00Z");
            Relationship second = relationship("e1", "e2", "employs", null);
            second.setOccurredAt("2021-06-01T00:00:00Z");
            List<Entity> both = List.of(entity("e1", "Acme", null, null),
                    entity("e2", "Bob", null, null));

            StagedGraph staged = StagedGraph.empty()
                    .stage(graph(both, List.of(first)), from("c1"))
                    .stage(graph(both, List.of(second)), from("c2"));

            StagedRelationship edge = staged.relationships().iterator().next();
            assertEquals("2019-01-01T00:00:00Z", edge.occurredAt());
            assertEquals(List.of("occurredAt"),
                    edge.conflicts().stream().map(StagedConflict::field).toList());
        }

        @Test
        void anUntypedEdgeGetsAUsableDefaultRatherThanABlankLabel() {
            StagedGraph staged = StagedGraph.empty().stage(graph(
                    List.of(entity("e1", "Acme", null, null), entity("e2", "Bob", null, null)),
                    List.of(relationship("e1", "e2", null, null))), from("c1"));

            assertEquals("RELATED_TO", staged.relationships().iterator().next().type());
        }
    }

    @Nested
    @DisplayName("handing the merged picture to the write path")
    class Emitting {

        @Test
        void entityIdsAreTheStagedKeysSoTwoMentionsLandOnOneNode() {
            Graph produced = StagedGraph.empty()
                    .stage(graph(List.of(entity("e1", "Acme", "COMPANY", null)), List.of()),
                            from("c1"))
                    .stage(graph(List.of(entity("zzz", "ACME", "COMPANY", null)), List.of()),
                            from("c2"))
                    .toGraph("partition-1", 7L);

            assertEquals(1, produced.getEntities().size());
            assertEquals("acme", produced.getEntities().get(0).getId());
            assertEquals("Acme", produced.getEntities().get(0).getTitle());
            assertEquals(7L, produced.getFactSheetId());
        }

        @Test
        void edgeEndpointsReferToThoseSameIds() {
            Graph produced = StagedGraph.empty().stage(graph(
                            List.of(entity("e1", "Acme", null, null),
                                    entity("e2", "Bob", null, null)),
                            List.of(relationship("e1", "e2", "employs", null))), from("c1"))
                    .toGraph("partition-1", null);

            Relationship edge = produced.getRelationships().get(0);
            assertEquals("acme", edge.getSource());
            assertEquals("bob", edge.getTarget());
            assertTrue(produced.getEntities().stream()
                            .map(Entity::getId)
                            .toList()
                            .containsAll(List.of(edge.getSource(), edge.getTarget())),
                    "the writer resolves edges through entity ids, so they have to match");
        }

        @Test
        void provenanceRidesAlongInTheEntityMetadata() {
            Graph produced = StagedGraph.empty()
                    .stage(graph(List.of(entity("e1", "Acme", "COMPANY", null)), List.of()),
                            new StagedProvenance("c1", "d1", DiscoveryChannel.SEED, 1, 1.0))
                    .stage(graph(List.of(entity("e1", "Acme", "COMPANY", null)), List.of()),
                            new StagedProvenance("c2", "d2", DiscoveryChannel.SEMANTIC, 2, 0.4))
                    .toGraph("partition-1", null);

            Map<String, Object> metadata = produced.getEntities().get(0).getMetadata();
            assertEquals(List.of("c1", "c2"), metadata.get(StagedEntity.META_CHUNKS));
            assertEquals(List.of("SEED", "SEMANTIC"), metadata.get(StagedEntity.META_CHANNELS));
            assertEquals("acme", metadata.get(StagedEntity.META_STAGED_KEY));
        }

        @Test
        void theGraphItselfSaysWhichChunksAndHowManyDisagreements() {
            Graph produced = StagedGraph.empty()
                    .stage(graph(List.of(entity("e1", "Acme", "COMPANY", null)), List.of()),
                            from("c1"))
                    .stage(graph(List.of(entity("e1", "Acme", "ORGANIZATION", null)), List.of()),
                            from("c2"))
                    .toGraph("partition-1", null);

            assertEquals(List.of("c1", "c2"), produced.getMetadata().get("stagedChunks"));
            assertEquals(1, produced.getMetadata().get("stagedConflicts"));
        }

        @Test
        void anEmptyStagedGraphStillProducesAWellFormedGraph() {
            Graph produced = StagedGraph.empty().toGraph("partition-1", 3L);

            assertTrue(produced.getEntities().isEmpty());
            assertTrue(produced.getRelationships().isEmpty());
            assertEquals("partition-1", produced.getName());
        }

        @Test
        void anEntityWithNoAliasesOrTextUnitsLeavesThoseFieldsUnsetRatherThanEmpty() {
            Graph produced = StagedGraph.empty()
                    .stage(graph(List.of(entity("e1", "Acme", "COMPANY", null)), List.of()),
                            from("c1"))
                    .toGraph("partition-1", null);

            assertNull(produced.getEntities().get(0).getAliases());
            assertNull(produced.getEntities().get(0).getTextUnits());
        }
    }

    @Nested
    @DisplayName("what the partition can report about itself")
    class Reporting {

        @Test
        void everyChunkThatContributedAnythingIsNamed() {
            StagedGraph staged = StagedGraph.empty()
                    .stage(graph(List.of(entity("e1", "Acme", null, null)), List.of()), from("c1"))
                    .stage(graph(List.of(entity("e2", "Bob", null, null)), List.of()), from("c2"));

            assertEquals(2, staged.chunkIds().size());
            assertTrue(staged.chunkIds().containsAll(List.of("c1", "c2")));
        }

        @Test
        void theDescriptionCountsWhatMattersAndSaysNothingElse() {
            StagedGraph staged = StagedGraph.empty().stage(graph(
                    List.of(entity("e1", "Acme", null, null)),
                    List.of(relationship("e1", "Zeta", "owns", null))), from("c1"));

            String described = staged.describe();
            assertTrue(described.contains("1 entities"), described);
            assertTrue(described.contains("1 relationships"), described);
            assertTrue(described.contains("unstaged endpoints"), described);
            assertFalse(described.contains("conflict"), described);
        }

        @Test
        void twoGraphsStagedFromTheSameOutputAreEqual() {
            Graph produced = graph(List.of(entity("e1", "Acme", "COMPANY", null)), List.of());

            assertEquals(StagedGraph.empty().stage(produced, from("c1")),
                    StagedGraph.empty().stage(produced, from("c1")));
        }
    }
}
