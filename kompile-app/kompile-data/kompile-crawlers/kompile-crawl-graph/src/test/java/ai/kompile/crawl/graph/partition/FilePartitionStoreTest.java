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
 *  limitations under the License.
 */

package ai.kompile.crawl.graph.partition;

import ai.kompile.core.graphrag.partition.AccessScope;
import ai.kompile.core.graphrag.partition.ChunkCandidate;
import ai.kompile.core.graphrag.partition.DiscoveryChannel;
import ai.kompile.core.graphrag.partition.EntityPartition;
import ai.kompile.core.graphrag.partition.MembershipState;
import ai.kompile.core.graphrag.partition.PartitionKey;
import ai.kompile.core.graphrag.partition.PartitionMember;
import ai.kompile.core.graphrag.partition.PartitionPhase;
import ai.kompile.core.graphrag.partition.PartitionStore;
import ai.kompile.utils.HashUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the file-backed store has to get right for a coverage claim to outlive the run that made it.
 *
 * <p>Each test opens a <em>second</em> store over the same directory wherever the point is
 * durability, because reading back through the instance that just wrote is not evidence that
 * anything reached the disk.</p>
 */
@DisplayName("Partitions that outlive the run that made them")
class FilePartitionStoreTest {

    @TempDir
    Path home;

    private Path root() {
        return home.resolve("data/graph/partitions");
    }

    private FilePartitionStore store() {
        return FilePartitionStore.at(root());
    }

    /** A store that has never met the one under test — a restart, in effect. */
    private FilePartitionStore afterRestart() {
        return FilePartitionStore.at(root());
    }

    private PartitionKey acme() {
        return PartitionKey.forEntity("acme-corp", "policy-v1", "77");
    }

    private EntityPartition fullPartition() {
        ChunkCandidate candidate = new ChunkCandidate("chunk-7", "doc-3",
                DiscoveryChannel.DIRECT_IDENTIFIER, 0.91, "matched the alias 'Acme Corp'",
                AccessScope.of("finance", "legal"), "v42", "2019-Q1-003");
        ChunkCandidate weaker = ChunkCandidate.of("chunk-9", DiscoveryChannel.SEMANTIC, 0.42)
                .inDocument("doc-4");
        return EntityPartition.open(acme())
                .withPhase(PartitionPhase.PROCESSING)
                .withRound(3)
                .withPin(PartitionFactSheets.FACT_SHEET_PIN, "77")
                .withPin("ontology", "owl-v2")
                .admit(candidate, MembershipState.DISCOVERED, 2)
                .admit(weaker, MembershipState.DEFERRED, 3)
                .withState("chunk-7", MembershipState.PROCESSED, "extracted 4 entities");
    }

    private EntityPartition partitionFor(String entityId, String policyVersion, String documentId) {
        return EntityPartition.open(PartitionKey.forEntity(entityId, policyVersion, "77"))
                .admit(ChunkCandidate.of(entityId + "-c1", DiscoveryChannel.SEED, 1.0)
                        .inDocument(documentId), MembershipState.DISCOVERED, 0);
    }

    private List<Path> files() throws IOException {
        if (!Files.isDirectory(root())) {
            return List.of();
        }
        try (Stream<Path> listed = Files.list(root())) {
            return listed.sorted().toList();
        }
    }

    private List<String> subjects(List<EntityPartition> partitions) {
        return partitions.stream().map(p -> p.key().subject()).sorted().toList();
    }

    @Nested
    @DisplayName("Surviving the process that wrote it")
    class Surviving {

        @Test
        void aPartitionComesBackWholeFromAStoreThatNeverSawItWritten() {
            EntityPartition saved = fullPartition();
            store().save(saved);

            EntityPartition reloaded = afterRestart().load(saved.id()).orElseThrow();

            // Record equality covers every component of the key, the phase, the round, the pins
            // and all twelve fields of every member — "whole" with nothing left to enumerate.
            assertEquals(saved, reloaded);
        }

        @Test
        void everyFieldOfAMemberSurvivesNotJustTheOnesTheKeyIsBuiltFrom() {
            store().save(fullPartition());

            PartitionMember member = afterRestart().load(fullPartition().id())
                    .orElseThrow().member("chunk-7").orElseThrow();

            assertEquals("doc-3", member.documentId());
            assertEquals(DiscoveryChannel.DIRECT_IDENTIFIER, member.channel());
            assertEquals(MembershipState.PROCESSED, member.state());
            assertEquals(0.91, member.confidence(), 1e-9);
            assertEquals("matched the alias 'Acme Corp'", member.reason());
            assertEquals(Set.of("finance", "legal"), member.accessScope().domains());
            assertEquals("v42", member.chunkVersion());
            assertEquals("2019-Q1-003", member.orderKey());
            assertEquals(2, member.discoveredRound());
            assertEquals("extracted 4 entities", member.note());
        }

        @Test
        void thePhaseRoundAndPinsComeBackToo() {
            store().save(fullPartition());

            EntityPartition reloaded = afterRestart().load(fullPartition().id()).orElseThrow();

            assertEquals(PartitionPhase.PROCESSING, reloaded.phase());
            assertEquals(3, reloaded.round());
            assertEquals("77", reloaded.pins().get(PartitionFactSheets.FACT_SHEET_PIN));
            assertEquals("owl-v2", reloaded.pins().get("ontology"));
        }

        @Test
        void aPartitionThatNamesNoPolicyOrSnapshotStillRoundTrips() {
            EntityPartition bare = EntityPartition.open(PartitionKey.forEntity("bob", null, null));
            store().save(bare);

            assertEquals(bare, afterRestart().load(bare.id()).orElseThrow());
        }

        @Test
        void aGroupPartitionRoundTripsAsAGroupNotAsAnEntity() {
            EntityPartition group = EntityPartition.open(
                    PartitionKey.forGroup("acme-cluster", "policy-v1", "77"));
            store().save(group);

            EntityPartition reloaded = afterRestart().load(group.id()).orElseThrow();

            assertEquals("acme-cluster", reloaded.key().groupId());
            assertNull(reloaded.key().entityId());
        }

        @Test
        void anIdFullOfCharactersAFilesystemWouldChokeOnStillGetsAFile() throws IOException {
            EntityPartition saved = fullPartition();
            // e=acme-corp|p=policy-v1|s=77 — pipes and equals signs, on purpose.
            assertTrue(saved.id().contains("|"), "the id is meant to be human-legible");

            store().save(saved);

            assertEquals(1, files().size());
            assertEquals(saved, afterRestart().load(saved.id()).orElseThrow());
        }

        @Test
        void theFileIsNamedForTheDigestOfTheIdAndSaysTheIdInside() throws IOException {
            EntityPartition saved = fullPartition();
            store().save(saved);

            Path file = files().get(0);
            assertEquals(HashUtils.sha256Hex(saved.id()) + ".json", file.getFileName().toString());
            // The name is a digest, but the document is not: a human opening the directory can
            // still tell what each file is a claim about.
            assertTrue(Files.readString(file).contains("acme-corp"));
        }

        @Test
        void aSuccessfulWriteLeavesNoTemporaryFileBehind() throws IOException {
            store().save(fullPartition());
            store().save(partitionFor("bob", "policy-v1", "doc-9"));

            List<String> names = files().stream().map(p -> p.getFileName().toString()).toList();
            assertEquals(2, names.size(), () -> "expected only the two partitions, got " + names);
            assertTrue(names.stream().allMatch(n -> n.endsWith(".json")),
                    () -> "a temp file survived the write: " + names);
        }
    }

    @Nested
    @DisplayName("Answering questions about what is stored")
    class Answering {

        @Test
        void nothingSavedIsNothingFoundAndNoDirectoryEither() {
            FilePartitionStore store = store();

            assertEquals(Optional.empty(), store.load(fullPartition().id()));
            assertTrue(store.findByPolicy("policy-v1").isEmpty());
            assertTrue(store.findByDocument("doc-3").isEmpty());
            assertEquals(0, store.size());
            assertFalse(Files.exists(store.directory()),
                    "the directory is created by the first save, not by looking");
        }

        @Test
        void findByPolicyReturnsOnlyTheRunsThatHappenedUnderIt() {
            FilePartitionStore store = store();
            store.save(partitionFor("acme", "policy-v1", "doc-1"));
            store.save(partitionFor("bob", "policy-v1", "doc-2"));
            store.save(partitionFor("carol", "policy-v2", "doc-3"));

            assertEquals(List.of("acme", "bob"), subjects(afterRestart().findByPolicy("policy-v1")));
            assertEquals(List.of("carol"), subjects(afterRestart().findByPolicy("policy-v2")));
            assertTrue(afterRestart().findByPolicy("policy-v3").isEmpty());
        }

        @Test
        void aPolicyOfNullMeansRecordedUnderNoPolicyNotEveryPolicy() {
            List<EntityPartition> saved = List.of(
                    partitionFor("acme", "policy-v1", "doc-1"),
                    partitionFor("bob", null, "doc-2"));
            FilePartitionStore files = store();
            PartitionStore memory = PartitionStore.inMemory();
            saved.forEach(partition -> {
                files.save(partition);
                memory.save(partition);
            });

            assertEquals(List.of("bob"), subjects(afterRestart().findByPolicy(null)));
            // The two implementations answer the same question the same way; a durable store that
            // quietly changed the semantics would be a different store, not a persisted one.
            assertEquals(subjects(memory.findByPolicy(null)),
                    subjects(afterRestart().findByPolicy(null)));
            assertEquals(subjects(memory.findByPolicy("policy-v1")),
                    subjects(afterRestart().findByPolicy("policy-v1")));
        }

        @Test
        void findByDocumentFindsEveryPartitionHoldingAChunkFromIt() {
            FilePartitionStore store = store();
            store.save(partitionFor("acme", "policy-v1", "doc-1"));
            store.save(partitionFor("bob", "policy-v1", "doc-1"));
            store.save(partitionFor("carol", "policy-v1", "doc-2"));

            assertEquals(List.of("acme", "bob"), subjects(afterRestart().findByDocument("doc-1")));
            assertEquals(List.of("carol"), subjects(afterRestart().findByDocument("doc-2")));
        }

        @Test
        void findByDocumentOfNothingIsNothingRatherThanEverything() {
            store().save(partitionFor("acme", "policy-v1", "doc-1"));

            assertTrue(afterRestart().findByDocument(null).isEmpty());
            assertTrue(afterRestart().findByDocument("  ").isEmpty());
            assertTrue(afterRestart().findByDocument("doc-missing").isEmpty());
        }

        @Test
        void loadingNothingIsAskedForIsEmptyRatherThanAnError() {
            FilePartitionStore store = store();
            store.save(fullPartition());

            assertEquals(Optional.empty(), store.load(null));
            assertEquals(Optional.empty(), store.load("   "));
            assertEquals(Optional.empty(), store.load("e=never-seen|p=policy-v1|s=77"));
        }

        @Test
        void loadOrOpenGivesAnEmptyPartitionUntilThereIsSomethingToLoad() {
            FilePartitionStore store = store();

            EntityPartition opened = store.loadOrOpen(acme());
            assertEquals(PartitionPhase.NEW, opened.phase());
            assertEquals(0, opened.size());

            store.save(fullPartition());

            EntityPartition reopened = afterRestart().loadOrOpen(acme());
            assertEquals(PartitionPhase.PROCESSING, reopened.phase());
            assertEquals(2, reopened.size());
        }
    }

    @Nested
    @DisplayName("Changing what is stored")
    class Changing {

        @Test
        void savingTheSameSubjectTwiceLeavesOneFileAndTheLaterAnswer() throws IOException {
            FilePartitionStore store = store();
            store.save(fullPartition());
            store.save(fullPartition().withRound(9).withPhase(PartitionPhase.CLOSED));

            assertEquals(1, files().size(), "a partition is one file, not one file per save");
            EntityPartition reloaded = afterRestart().load(fullPartition().id()).orElseThrow();
            assertEquals(9, reloaded.round());
            assertEquals(PartitionPhase.CLOSED, reloaded.phase());
        }

        @Test
        void deleteRemovesTheClaimAndTheFileWithIt() throws IOException {
            FilePartitionStore store = store();
            store.save(fullPartition());
            store.save(partitionFor("bob", "policy-v1", "doc-9"));

            store.delete(fullPartition().id());

            assertEquals(Optional.empty(), afterRestart().load(fullPartition().id()));
            assertEquals(1, files().size());
            assertEquals(List.of("bob"), subjects(afterRestart().findByPolicy("policy-v1")));
        }

        @Test
        void deletingSomethingNeverSavedIsNotAnError() {
            FilePartitionStore store = store();

            assertDoesNotThrow(() -> store.delete("e=never-seen|p=policy-v1|s=77"));
            assertDoesNotThrow(() -> store.delete(null));
            assertDoesNotThrow(() -> store.delete("   "));
        }

        @Test
        void savingNothingIsNotAnErrorAndWritesNothing() throws IOException {
            FilePartitionStore store = store();

            assertDoesNotThrow(() -> store.save(null));

            assertTrue(files().isEmpty());
        }

        @Test
        void manyThreadsWritingDifferentSubjectsAllLand() throws Exception {
            FilePartitionStore store = store();
            List<Callable<Void>> writes = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                String subject = "subject-" + i;
                writes.add(() -> {
                    store.save(partitionFor(subject, "policy-v1", "doc-" + subject));
                    return null;
                });
            }
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                for (Future<Void> done : pool.invokeAll(writes)) {
                    done.get(30, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            assertEquals(16, afterRestart().size(),
                    "concurrent writers must not lose each other's partitions");
            assertEquals(16, files().size());
        }
    }

    @Nested
    @DisplayName("When the disk lies")
    class WhenTheDiskLies {

        private Path theOnlyFile() throws IOException {
            List<Path> files = files();
            assertEquals(1, files.size());
            return files.get(0);
        }

        @Test
        void aFileThatIsNotJsonIsTreatedAsNeverRecorded() throws IOException {
            store().save(fullPartition());
            Files.writeString(theOnlyFile(), "this is not a partition");

            // Forgetting a claim costs the work again; half-reading one would invent a claim
            // nobody made. Empty is the safe direction.
            assertEquals(Optional.empty(), afterRestart().load(fullPartition().id()));
            assertTrue(afterRestart().findByPolicy("policy-v1").isEmpty());
            assertEquals(0, afterRestart().size());
        }

        @Test
        void aHalfWrittenFileDoesNotBecomeHalfAPartition() throws IOException {
            FilePartitionStore store = store();
            store.save(fullPartition());
            Path acmeFile = theOnlyFile();
            store.save(partitionFor("bob", "policy-v1", "doc-9"));
            Files.writeString(acmeFile, "{\"key\":{\"entityId\":\"acme-corp\",\"policyVersion\":");

            assertEquals(Optional.empty(), afterRestart().load(fullPartition().id()));
            // The healthy neighbour is still readable — one torn file is not a corrupt store.
            assertEquals(List.of("bob"), subjects(afterRestart().findByPolicy("policy-v1")));
        }

        @Test
        void aClaimThatCouldNotBeReadCanBeMadeAgain() throws IOException {
            store().save(fullPartition());
            Files.writeString(theOnlyFile(), "");

            assertEquals(Optional.empty(), afterRestart().load(fullPartition().id()));

            EntityPartition remade = fullPartition().withRound(4);
            afterRestart().save(remade);

            assertEquals(remade, afterRestart().load(remade.id()).orElseThrow());
            assertEquals(1, files().size());
        }

        @Test
        void aFileThatIsNotAPartitionAtAllIsIgnoredRatherThanCounted() throws IOException {
            FilePartitionStore store = store();
            store.save(fullPartition());
            Files.writeString(root().resolve("notes.txt"), "left here by a human");
            Files.createDirectory(root().resolve("archive"));

            assertEquals(1, store.size());
            assertNotNull(store.load(fullPartition().id()).orElse(null));
        }
    }
}
