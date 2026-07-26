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
import ai.kompile.core.graphrag.partition.DiscoveryPolicy;
import ai.kompile.core.graphrag.partition.EntityPartition;
import ai.kompile.core.graphrag.partition.MembershipState;
import ai.kompile.core.graphrag.partition.PartitionDiscoveryCoordinator;
import ai.kompile.core.graphrag.partition.PartitionKey;
import ai.kompile.core.graphrag.partition.PartitionMember;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The vector-store-backed discovery channels, and what they must carry off a retrieved chunk. */
class VectorStoreDiscoveryProviderTest {

    private static final PartitionKey KEY =
            PartitionKey.forEntity("Acme Corporation", "discovery-v1", "snap-1");

    private static ScoredDocument hit(String id, double score, Map<String, Object> metadata) {
        return new ScoredDocument(new Document(id, "text of " + id, metadata), score);
    }

    private static ScoredDocument hit(String id, double score) {
        return hit(id, score, Map.of());
    }

    /**
     * Records what each channel asked the store for, and answers from a canned table.
     *
     * <p>Hand-written rather than mocked: the interface has six abstract methods, and the one
     * that matters here is a {@code default} the providers call directly, so a fake states the
     * contract more plainly than stubbing would.</p>
     */
    private static final class FakeVectorStore implements VectorStore {

        private final Function<String, List<ScoredDocument>> answers;
        private final List<String> queries = new ArrayList<>();
        private final List<Integer> limits = new ArrayList<>();
        private final List<Double> thresholds = new ArrayList<>();

        private FakeVectorStore(Function<String, List<ScoredDocument>> answers) {
            this.answers = answers;
        }

        static FakeVectorStore returning(List<ScoredDocument> hits) {
            return new FakeVectorStore(query -> hits);
        }

        static FakeVectorStore empty() {
            return new FakeVectorStore(query -> List.of());
        }

        @Override
        public List<ScoredDocument> similaritySearchWithScores(String query, int k,
                                                               double threshold) {
            queries.add(query);
            limits.add(k);
            thresholds.add(threshold);
            List<ScoredDocument> hits = answers.apply(query);
            return hits == null ? List.of() : hits;
        }

        @Override
        public int add(List<Document> documents) {
            throw new UnsupportedOperationException("discovery channels only read");
        }

        @Override
        public int add(List<Document> documents, List<List<Float>> embeddings) {
            throw new UnsupportedOperationException("discovery channels only read");
        }

        @Override
        public boolean delete(List<String> ids) {
            throw new UnsupportedOperationException("discovery channels only read");
        }

        @Override
        public List<Document> similaritySearch(String query, int k) {
            throw new UnsupportedOperationException("providers use the scored overload");
        }

        @Override
        public List<Document> similaritySearch(String query, int k, double threshold) {
            throw new UnsupportedOperationException("providers use the scored overload");
        }

        @Override
        public List<Document> similaritySearch(List<Float> queryEmbedding, int k,
                                               double threshold) {
            throw new UnsupportedOperationException("providers query by text");
        }
    }

    @Nested
    @DisplayName("chunk metadata mapping")
    class Mapping {

        @Test
        void aRetrievedChunkKeepsItsSourceDocumentSoItCanBeInvalidatedLater() {
            ChunkCandidate candidate = VectorStoreCandidates.toCandidate(
                    hit("c1", 0.8, Map.of("source_id", "/corpus/acme-10k.pdf")),
                    DiscoveryChannel.SEMANTIC, "why");
            assertEquals("/corpus/acme-10k.pdf", candidate.documentId());
        }

        @Test
        void alternateSpellingsOfTheSourceKeyAreAllUnderstood() {
            for (String key : List.of("source_id", "sourceId", "source", "document_id",
                    "documentId", "file_path")) {
                ChunkCandidate candidate = VectorStoreCandidates.toCandidate(
                        hit("c1", 0.8, Map.of(key, "docA")), DiscoveryChannel.SEMANTIC, "why");
                assertEquals("docA", candidate.documentId(), key);
            }
        }

        @Test
        void aContentFingerprintBecomesTheChunkVersion() {
            ChunkCandidate candidate = VectorStoreCandidates.toCandidate(
                    hit("c1", 0.8, Map.of("content_hash", "sha256:abc")),
                    DiscoveryChannel.SEMANTIC, "why");
            assertEquals("sha256:abc", candidate.chunkVersion());
        }

        @Test
        void aChunkPositionBecomesTheOrderKeySoAProcessReadsInOrder() {
            ChunkCandidate candidate = VectorStoreCandidates.toCandidate(
                    hit("c1", 0.8, Map.of("chunk_index", 7)), DiscoveryChannel.SEMANTIC, "why");
            assertEquals("7", candidate.orderKey());
        }

        @Test
        void theFirstKeyThatIsActuallyPresentWins() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("file_path", "/fallback/path.md");
            metadata.put("source_id", "preferred");
            assertEquals("preferred", VectorStoreCandidates
                    .toCandidate(hit("c1", 0.8, metadata), DiscoveryChannel.SEMANTIC, "why")
                    .documentId());
        }

        @Test
        void accessLabelsPropagateFromIngestIntoThePartition() {
            ChunkCandidate candidate = VectorStoreCandidates.toCandidate(
                    hit("c1", 0.8, Map.of("access_scope", List.of("hr", "legal"))),
                    DiscoveryChannel.SEMANTIC, "why");
            assertEquals(Set.of("hr", "legal"), candidate.accessScope().domains());
        }

        @Test
        void aFlatDelimitedLabelIsStillAListOfDomains() {
            // The shape most ingest paths write when the sink is one string column.
            assertEquals(Set.of("hr", "legal"),
                    VectorStoreCandidates.scopeOf(Map.of("acl", "hr, legal")).domains());
            assertEquals(Set.of("hr", "legal"),
                    VectorStoreCandidates.scopeOf(Map.of("acl", "hr|legal")).domains());
            assertEquals(Set.of("hr"),
                    VectorStoreCandidates.scopeOf(Map.of("acl", new String[]{"hr", " "})).domains());
        }

        @Test
        void labelsFromSeveralKeysAreUnionedRatherThanOneShadowingTheOther() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("access_scope", "hr");
            metadata.put("security_labels", List.of("legal"));
            assertEquals(Set.of("hr", "legal"), VectorStoreCandidates.scopeOf(metadata).domains());
        }

        @Test
        void anUnlabelledChunkIsUnrestrictedRatherThanInventedAsSecret() {
            assertTrue(VectorStoreCandidates.scopeOf(null).isUnrestricted());
            assertTrue(VectorStoreCandidates.scopeOf(Map.of()).isUnrestricted());
            assertTrue(VectorStoreCandidates.scopeOf(Map.of("unrelated", "x")).isUnrestricted());
            assertTrue(VectorStoreCandidates.scopeOf(Map.of("acl", " , ")).isUnrestricted());
        }

        @Test
        void aBlankMetadataValueIsTreatedAsNotStated() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source_id", "   ");
            metadata.put("version", "");
            ChunkCandidate candidate = VectorStoreCandidates.toCandidate(
                    hit("c1", 0.8, metadata), DiscoveryChannel.SEMANTIC, "why");
            assertNull(candidate.documentId());
            assertNull(candidate.chunkVersion());
        }

        @Test
        void theStoresScoreBecomesTheProposalConfidence() {
            assertEquals(0.83, VectorStoreCandidates
                    .toCandidate(hit("c1", 0.83), DiscoveryChannel.SEMANTIC, "why")
                    .confidence(), 1e-9);
            assertEquals(1.0, VectorStoreCandidates
                    .toCandidate(hit("c1", 4.2), DiscoveryChannel.SEMANTIC, "why")
                    .confidence(), 1e-9, "an out-of-range score is clamped, not trusted");
        }

        @Test
        void unusableHitsAreDroppedRatherThanTurnedIntoBrokenMembers() {
            assertNull(VectorStoreCandidates.toCandidate(null, DiscoveryChannel.SEMANTIC, "why"));
            assertTrue(VectorStoreCandidates
                    .toCandidates(null, DiscoveryChannel.SEMANTIC, "why").isEmpty());
            assertEquals(List.of("kept"), VectorStoreCandidates
                    .toCandidates(Arrays.asList(null, hit("kept", 0.7), null),
                            DiscoveryChannel.SEMANTIC, "why")
                    .stream().map(ChunkCandidate::chunkId).toList());
        }

        @Test
        void rankingFromTheStoreIsPreserved() {
            List<ChunkCandidate> candidates = VectorStoreCandidates.toCandidates(
                    List.of(hit("best", 0.9), hit("middle", 0.7), hit("worst", 0.5)),
                    DiscoveryChannel.SEMANTIC, "why");
            assertEquals(List.of("best", "middle", "worst"),
                    candidates.stream().map(ChunkCandidate::chunkId).toList());
        }

        @Test
        void theProposingChannelAndItsReasonAreCarriedForTheAuditTrail() {
            ChunkCandidate candidate = VectorStoreCandidates.toCandidate(
                    hit("c1", 0.8), DiscoveryChannel.DIRECT_IDENTIFIER, "matched ticker AAPL");
            assertEquals(DiscoveryChannel.DIRECT_IDENTIFIER, candidate.channel());
            assertEquals("matched ticker AAPL", candidate.reason());
        }
    }

    @Nested
    @DisplayName("SemanticDiscoveryProvider")
    class Semantic {

        @Test
        void theSubjectIsWhatGetsSearchedFor() {
            FakeVectorStore store = FakeVectorStore.returning(List.of(hit("c1", 0.8)));

            List<ChunkCandidate> found = new SemanticDiscoveryProvider(store, 0.4)
                    .discover(EntityPartition.open(KEY), 1, 25);

            assertEquals(List.of("Acme Corporation"), store.queries);
            assertEquals(List.of(25), store.limits);
            assertEquals(List.of(0.4), store.thresholds);
            assertEquals(1, found.size());
            assertEquals(DiscoveryChannel.SEMANTIC, found.get(0).channel());
            assertTrue(found.get(0).reason().contains("Acme Corporation"));
        }

        @Test
        void aNarrowedPartitionSearchesForWhatItActuallyClaimsToCover() {
            FakeVectorStore store = FakeVectorStore.empty();
            PartitionKey narrowed = KEY.withCategory("financial").withTimeWindow("2024-Q3");

            new SemanticDiscoveryProvider(store, 0.4)
                    .discover(EntityPartition.open(narrowed), 1, 10);

            assertEquals(List.of("Acme Corporation financial 2024-Q3"), store.queries);
        }

        @Test
        void aCallerCanSupplyTheQueryWhenTheSubjectIdIsNotSearchableText() {
            FakeVectorStore store = FakeVectorStore.empty();

            new SemanticDiscoveryProvider(store, partition -> "resolved label", 0.4)
                    .discover(EntityPartition.open(PartitionKey.forEntity("ent-7", "v", "s")), 1, 10);

            assertEquals(List.of("resolved label"), store.queries);
        }

        @Test
        void anEmptyQueryMeansTheChannelHasNothingToAskNotThatItAsksForEverything() {
            FakeVectorStore store = FakeVectorStore.returning(List.of(hit("c1", 0.9)));

            assertTrue(new SemanticDiscoveryProvider(store, partition -> "  ", 0.4)
                    .discover(EntityPartition.open(KEY), 1, 10).isEmpty());
            assertTrue(new SemanticDiscoveryProvider(store, partition -> null, 0.4)
                    .discover(EntityPartition.open(KEY), 1, 10).isEmpty());
            assertTrue(store.queries.isEmpty(), "the store is never asked an empty question");
        }

        @Test
        void aNonsenseRoundLimitStillAsksForSomething() {
            FakeVectorStore store = FakeVectorStore.empty();
            new SemanticDiscoveryProvider(store, 0.4).discover(EntityPartition.open(KEY), 1, 0);
            new SemanticDiscoveryProvider(store, 0.4).discover(EntityPartition.open(KEY), 1, -5);
            assertEquals(List.of(1, 1), store.limits);
        }

        @Test
        void reQueryingEachRoundIsHowFrontierExhaustionGetsDetected() {
            // Nothing new on round two is the stop signal, so the channel must actually re-ask
            // rather than deciding for itself that it already answered.
            FakeVectorStore store = FakeVectorStore.returning(List.of(hit("c1", 0.9)));
            SemanticDiscoveryProvider provider = new SemanticDiscoveryProvider(store, 0.4);
            EntityPartition partition = EntityPartition.open(KEY);

            assertEquals(1, provider.discover(partition, 1, 10).size());
            assertEquals(1, provider.discover(partition, 2, 10).size());
            assertEquals(2, store.queries.size());
        }

        @Test
        void aChannelWithoutAStoreIsARefusalNotASilentNoOp() {
            assertThrows(IllegalArgumentException.class,
                    () -> new SemanticDiscoveryProvider(null, 0.4));
            assertThrows(IllegalArgumentException.class,
                    () -> new IdentifierDiscoveryProvider(null, partition -> List.of(), 0.4));
        }

        @Test
        void theChannelNamesItselfSoThePolicyCanGateIt() {
            FakeVectorStore store = FakeVectorStore.empty();
            assertEquals(DiscoveryChannel.SEMANTIC,
                    new SemanticDiscoveryProvider(store, 0.4).channel());
            assertEquals(DiscoveryChannel.DIRECT_IDENTIFIER,
                    new IdentifierDiscoveryProvider(store, partition -> List.of(), 0.4).channel());
        }

        @Test
        void describeSubjectToleratesHavingNoPartition() {
            assertNull(SemanticDiscoveryProvider.describeSubject(null));
            assertEquals("Acme Corporation",
                    SemanticDiscoveryProvider.describeSubject(EntityPartition.open(KEY)));
        }
    }

    @Nested
    @DisplayName("IdentifierDiscoveryProvider")
    class Identifiers {

        @Test
        void everyKnownNameForTheSubjectIsSearchedFor() {
            FakeVectorStore store = FakeVectorStore.empty();

            new IdentifierDiscoveryProvider(store,
                    partition -> List.of("Acme Corporation", "Acme Corp.", "ACME"), 0.5)
                    .discover(EntityPartition.open(KEY), 1, 30);

            assertEquals(List.of("Acme Corporation", "Acme Corp.", "ACME"), store.queries);
        }

        @Test
        void theRoundsBudgetIsSpreadOverTheAliasesNotSpentOnTheFirst() {
            FakeVectorStore store = FakeVectorStore.empty();

            new IdentifierDiscoveryProvider(store,
                    partition -> List.of("a", "b", "c", "d"), 0.5)
                    .discover(EntityPartition.open(KEY), 1, 20);

            assertEquals(List.of(5, 5, 5, 5), store.limits);
        }

        @Test
        void aBudgetSmallerThanTheAliasListStillAsksAboutEachAlias() {
            FakeVectorStore store = FakeVectorStore.empty();

            new IdentifierDiscoveryProvider(store, partition -> List.of("a", "b", "c"), 0.5)
                    .discover(EntityPartition.open(KEY), 1, 2);

            assertEquals(List.of(1, 1, 1), store.limits,
                    "an alias is never given a budget of zero, which would silently skip it");
        }

        @Test
        void oneChunkFoundUnderTwoAliasesIsOneProposalAtItsBestScore() {
            FakeVectorStore store = new FakeVectorStore(query -> switch (query) {
                case "Acme Corp." -> List.of(hit("shared", 0.6));
                case "ACME" -> List.of(hit("shared", 0.95), hit("other", 0.7));
                default -> List.of();
            });

            List<ChunkCandidate> found = new IdentifierDiscoveryProvider(store,
                    partition -> List.of("Acme Corp.", "ACME"), 0.5)
                    .discover(EntityPartition.open(KEY), 1, 20);

            assertEquals(List.of("shared", "other"),
                    found.stream().map(ChunkCandidate::chunkId).toList());
            assertEquals(0.95, found.get(0).confidence(), 1e-9);
        }

        @Test
        void aWeakerLaterMatchDoesNotDisplaceAStrongerEarlierOne() {
            FakeVectorStore store = new FakeVectorStore(query -> switch (query) {
                case "ACME" -> List.of(hit("shared", 0.95));
                case "Acme Corp." -> List.of(hit("shared", 0.6));
                default -> List.of();
            });

            List<ChunkCandidate> found = new IdentifierDiscoveryProvider(store,
                    partition -> List.of("ACME", "Acme Corp."), 0.5)
                    .discover(EntityPartition.open(KEY), 1, 20);

            assertEquals(1, found.size());
            assertEquals(0.95, found.get(0).confidence(), 1e-9);
            assertTrue(found.get(0).reason().contains("ACME"));
        }

        @Test
        void aSubjectWithNoKnownAliasesFallsBackToItsOwnName() {
            FakeVectorStore store = FakeVectorStore.empty();

            new IdentifierDiscoveryProvider(store, partition -> List.of(), 0.5)
                    .discover(EntityPartition.open(KEY), 1, 10);
            new IdentifierDiscoveryProvider(store, null, 0.5)
                    .discover(EntityPartition.open(KEY), 1, 10);
            new IdentifierDiscoveryProvider(store, partition -> null, 0.5)
                    .discover(EntityPartition.open(KEY), 1, 10);

            assertEquals(List.of("Acme Corporation", "Acme Corporation", "Acme Corporation"),
                    store.queries);
        }

        @Test
        void aGroupPartitionIsLookedUpByItsGroupName() {
            FakeVectorStore store = FakeVectorStore.empty();
            PartitionKey group = PartitionKey.forGroup("acme-supply-chain", "v1", "snap-1");

            new IdentifierDiscoveryProvider(store, partition -> List.of(), 0.5)
                    .discover(EntityPartition.open(group), 1, 10);

            assertEquals(List.of("acme-supply-chain"), store.queries);
        }

        @Test
        void blankAndDuplicateAliasesAreNotSearchedTwice() {
            FakeVectorStore store = FakeVectorStore.empty();

            new IdentifierDiscoveryProvider(store,
                    partition -> Arrays.asList("ACME", " ACME ", "", null, "Acme"), 0.5)
                    .discover(EntityPartition.open(KEY), 1, 10);

            assertEquals(List.of("ACME", "Acme"), store.queries);
        }

        @Test
        void theChannelStopsOnceItHasFilledTheRoundsBudget() {
            FakeVectorStore store = new FakeVectorStore(query ->
                    List.of(hit(query + "-1", 0.9), hit(query + "-2", 0.9)));

            List<ChunkCandidate> found = new IdentifierDiscoveryProvider(store,
                    partition -> List.of("a", "b", "c"), 0.5)
                    .discover(EntityPartition.open(KEY), 1, 2);

            assertEquals(2, found.size());
            assertEquals(List.of("a"), store.queries,
                    "no point querying further aliases once the round's budget is spent");
        }

        @Test
        void aChannelWithNothingToLookForDoesNotQuery() {
            FakeVectorStore store = FakeVectorStore.empty();
            assertTrue(new IdentifierDiscoveryProvider(store, partition -> List.of("x"), 0.5)
                    .discover(null, 1, 10).isEmpty());
            assertTrue(store.queries.isEmpty());
        }
    }

    @Nested
    @DisplayName("composed with the coordinator")
    class Composed {

        @Test
        void identifierEvidenceOutranksSemanticEvidenceForTheSameChunk() {
            FakeVectorStore store = new FakeVectorStore(query -> switch (query) {
                case "AAPL" -> List.of(hit("filing", 0.95, Map.of("source_id", "10k")));
                case "Acme Corporation" -> List.of(hit("filing", 0.6, Map.of("source_id", "10k")),
                        hit("blog", 0.55, Map.of("source_id", "blog-post")));
                default -> List.of();
            });

            PartitionDiscoveryCoordinator coordinator = new PartitionDiscoveryCoordinator(List.of(
                    new SemanticDiscoveryProvider(store, 0.3),
                    new IdentifierDiscoveryProvider(store, partition -> List.of("AAPL"), 0.3)),
                    DiscoveryPolicy.defaults());

            PartitionDiscoveryCoordinator.Outcome outcome =
                    coordinator.discover(EntityPartition.open(KEY), 1);

            assertEquals(2, outcome.added());
            PartitionMember filing = outcome.partition().member("filing").orElseThrow();
            assertEquals(DiscoveryChannel.DIRECT_IDENTIFIER, filing.channel(),
                    "an identifier match is not downgraded by a weaker semantic hit on it");
            assertEquals(0.95, filing.confidence(), 1e-9);
            assertEquals("10k", filing.documentId());
        }

        @Test
        void aRestrictedChunkRetrievedByAnUnprivilegedRunBecomesARecordedGap() {
            FakeVectorStore store =
                    FakeVectorStore.returning(List.of(hit("sealed", 0.9, Map.of("acl", "hr"))));

            PartitionDiscoveryCoordinator.Outcome outcome = new PartitionDiscoveryCoordinator(
                    List.of(new SemanticDiscoveryProvider(store, 0.3)), DiscoveryPolicy.defaults())
                    .discover(EntityPartition.open(KEY), 1);

            assertEquals(1, outcome.inaccessible());
            assertEquals(MembershipState.INACCESSIBLE,
                    outcome.partition().member("sealed").orElseThrow().state());
            assertEquals(List.of("sealed"), outcome.partition().manifest(true).inaccessible());
        }

        @Test
        void aChangedSourceInvalidatesExactlyTheChunksItProduced() {
            FakeVectorStore store = FakeVectorStore.returning(List.of(
                    hit("a1", 0.9, Map.of("source_id", "docA")),
                    hit("a2", 0.9, Map.of("source_id", "docA")),
                    hit("b1", 0.9, Map.of("source_id", "docB"))));

            EntityPartition partition = new PartitionDiscoveryCoordinator(
                    List.of(new SemanticDiscoveryProvider(store, 0.3)), DiscoveryPolicy.defaults())
                    .discover(EntityPartition.open(KEY), 1).partition()
                    .withState("a1", MembershipState.PROCESSED, null)
                    .withState("a2", MembershipState.PROCESSED, null)
                    .withState("b1", MembershipState.PROCESSED, null)
                    .invalidateDocument("docA", "v2", "source changed");

            assertEquals(List.of("a1", "a2"), partition.schedulable().stream()
                    .map(PartitionMember::chunkId).toList());
            assertEquals(MembershipState.PROCESSED,
                    partition.member("b1").orElseThrow().state());
        }

        @Test
        void aChunkWithNoRecordedSourceCannotBeInvalidatedWhichIsWhyTheLinkIsCarried() {
            FakeVectorStore store = FakeVectorStore.returning(List.of(hit("orphan", 0.9)));

            EntityPartition partition = new PartitionDiscoveryCoordinator(
                    List.of(new SemanticDiscoveryProvider(store, 0.3)), DiscoveryPolicy.defaults())
                    .discover(EntityPartition.open(KEY), 1).partition()
                    .withState("orphan", MembershipState.PROCESSED, null)
                    .invalidateDocument("docA", "v2", "source changed");

            assertNull(partition.member("orphan").orElseThrow().documentId());
            assertTrue(partition.schedulable().isEmpty(),
                    "an unlinked chunk goes on looking processed forever — that is the cost");
        }
    }
}
