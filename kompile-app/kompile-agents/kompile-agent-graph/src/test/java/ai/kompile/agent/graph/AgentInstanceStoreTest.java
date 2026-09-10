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
package ai.kompile.agent.graph;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.model.SimpleGraphRelation;
import ai.kompile.graph.reasoning.unified.Dtype;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.VectorLayer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AgentInstanceStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void provisionsAndReopensAfterStoreRestart() throws Exception {
        Path root = tempDir.resolve("agents");
        UUID ownerId = UUID.randomUUID();
        AgentInstanceStore firstStore = new AgentInstanceStore(root);
        AgentInstance provisioned = firstStore.provision(ownerId, "Hermes");
        AgentGraphRevision originalRevision = firstStore.open(provisioned.principal()).currentRevision();

        AgentInstanceStore restartedStore = new AgentInstanceStore(root);
        assertEquals(List.of(provisioned), restartedStore.list(ownerId));
        AgentPrivateGraphSession reopened = restartedStore.open(provisioned.principal());

        assertEquals(provisioned, reopened.instance());
        assertEquals(originalRevision, reopened.currentRevision());
        Path agentDirectory = root.resolve(ownerId.toString())
                .resolve(provisioned.principal().agentId().toString());
        assertTrue(Files.isRegularFile(agentDirectory.resolve("agent.json")));
        assertTrue(Files.isRegularFile(agentDirectory.resolve("graph/private.kgraph")));
    }

    @Test
    void enforcesOwnerOnlyPermissionsWhenPosixIsSupported() throws Exception {
        Path root = tempDir.resolve("agents");
        assumeTrue(Files.getFileAttributeView(
                tempDir, PosixFileAttributeView.class) != null, "POSIX permissions are unavailable");
        AgentInstanceStore store = new AgentInstanceStore(root);
        AgentInstance instance = store.provision(UUID.randomUUID(), "Private permissions");
        Path ownerDirectory = root.resolve(instance.principal().ownerId().toString());
        Path agentDirectory = ownerDirectory.resolve(instance.principal().agentId().toString());
        Path graphDirectory = agentDirectory.resolve("graph");

        assertPosixPermissions(root, "rwx------");
        assertPosixPermissions(ownerDirectory, "rwx------");
        assertPosixPermissions(agentDirectory, "rwx------");
        assertPosixPermissions(graphDirectory, "rwx------");
        assertPosixPermissions(agentDirectory.resolve("agent.json"), "rw-------");
        assertPosixPermissions(agentDirectory.resolve(".private-graph.lock"), "rw-------");
        assertPosixPermissions(graphDirectory.resolve("private.kgraph"), "rw-------");
    }

    @Test
    void isolatesTwoAgentsAndRejectsWrongOwner() throws Exception {
        Path root = tempDir.resolve("agents");
        AgentInstanceStore store = new AgentInstanceStore(root);
        UUID ownerId = UUID.randomUUID();
        AgentInstance first = store.provision(ownerId, "First");
        AgentInstance second = store.provision(ownerId, "Second");

        AgentPrivateGraphSession firstSession = store.open(first.principal());
        AgentGraphRevision firstRevision = firstSession.currentRevision();
        firstSession.mutate(firstRevision, graph -> graph.addEntity("only-first", "PRIVATE", "First only"));

        assertTrue(firstSession.read().graph().containsEntity("only-first"));
        assertFalse(store.open(second.principal()).read().graph().containsEntity("only-first"));
        assertNotEquals(first.principal().agentId(), second.principal().agentId());

        AgentPrincipal wrongOwner = new AgentPrincipal(UUID.randomUUID(), first.principal().agentId());
        assertTrue(store.find(wrongOwner).isEmpty());
        assertThrows(NoSuchElementException.class, () -> store.open(wrongOwner));
    }

    @Test
    void displayNameCannotInfluenceStoragePath() throws Exception {
        Path root = tempDir.resolve("agents");
        AgentInstanceStore store = new AgentInstanceStore(root);
        UUID ownerId = UUID.randomUUID();
        String malicious = "../../outside/agent.json\\..\\escape\n<script>";

        AgentInstance instance = store.provision(ownerId, malicious);

        assertEquals(malicious, instance.displayName());
        assertFalse(Files.exists(tempDir.resolve("outside")));
        Path expectedDirectory = root.resolve(ownerId.toString())
                .resolve(instance.principal().agentId().toString());
        assertTrue(Files.isRegularFile(expectedDirectory.resolve("agent.json")));
        assertEquals(4, store.list(ownerId).get(0).principal().agentId().version(),
                "agent identity must be a server-generated random UUID");
    }

    @Test
    void rejectsSymlinkEscape() throws Exception {
        Path root = tempDir.resolve("agents");
        AgentInstanceStore store = new AgentInstanceStore(root);
        AgentInstance instance = store.provision(UUID.randomUUID(), "Symlink test");
        AgentPrivateGraphSession session = store.open(instance.principal());
        Path agentDirectory = root.resolve(instance.principal().ownerId().toString())
                .resolve(instance.principal().agentId().toString());
        Path graphDirectory = agentDirectory.resolve("graph");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside);
        Files.delete(graphDirectory.resolve("private.kgraph"));
        Files.delete(graphDirectory);
        try {
            Files.createSymbolicLink(graphDirectory, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            assumeTrue(false, "Symbolic links are unavailable: " + unavailable.getMessage());
        }

        assertThrows(IOException.class, session::read);
        assertFalse(Files.exists(outside.resolve("private.kgraph")));
    }

    @Test
    void readsAreDetachedAndIndependent() throws Exception {
        AgentInstanceStore store = new AgentInstanceStore(tempDir.resolve("agents"));
        AgentPrivateGraphSession session = store.open(
                store.provision(UUID.randomUUID(), "Detached").principal());
        AgentGraphRevision initial = session.currentRevision();
        session.mutate(initial, graph -> graph.addEntity("persisted", "THING", "Persisted"));

        AgentGraphSnapshot first = session.read();
        AgentGraphSnapshot second = session.read();
        assertNotSame(first.graph(), second.graph());
        first.graph().addEntity("not-persisted", "THING", "Detached only");

        AgentGraphSnapshot third = session.read();
        assertTrue(third.graph().containsEntity("persisted"));
        assertFalse(third.graph().containsEntity("not-persisted"));
        assertEquals(first.revision(), third.revision());
    }

    @Test
    void staleRevisionFailsWithoutChangingArchive() throws Exception {
        AgentInstanceStore store = new AgentInstanceStore(tempDir.resolve("agents"));
        AgentPrivateGraphSession session = store.open(
                store.provision(UUID.randomUUID(), "Optimistic").principal());
        AgentGraphRevision stale = session.currentRevision();
        AgentGraphRevision current = session.mutate(stale,
                graph -> graph.addEntity("winner", "THING", "Winner"));
        AgentGraphArchive beforeStaleWrite = session.readArchive();

        StaleAgentGraphRevisionException failure = assertThrows(
                StaleAgentGraphRevisionException.class,
                () -> session.mutate(stale,
                        graph -> graph.addEntity("loser", "THING", "Loser")));

        assertEquals(stale, failure.expected());
        assertEquals(current, failure.actual());
        assertEquals(current, session.currentRevision());
        assertArrayEquals(beforeStaleWrite.bytes(), session.readArchive().bytes());
        assertFalse(session.read().graph().containsEntity("loser"));
    }

    @Test
    void twoConcurrentSameRevisionWritersHaveExactlyOneWinner() throws Exception {
        AgentInstanceStore store = new AgentInstanceStore(tempDir.resolve("agents"));
        AgentPrivateGraphSession session = store.open(
                store.provision(UUID.randomUUID(), "Concurrent").principal());
        AgentGraphRevision sharedRevision = session.currentRevision();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> attemptWrite(
                    session, sharedRevision, "writer-one", ready, start));
            Future<Boolean> second = executor.submit(() -> attemptWrite(
                    session, sharedRevision, "writer-two", ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int successes = (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        UnifiedGraph graph = session.read().graph();
        assertEquals(1, graph.entityCount());
        assertTrue(graph.containsEntity("writer-one") || graph.containsEntity("writer-two"));
    }

    @Test
    void mutationCallbackDoesNotHoldThePublicationLock() throws Exception {
        AgentInstanceStore store = new AgentInstanceStore(tempDir.resolve("agents"));
        AgentPrivateGraphSession session = store.open(
                store.provision(UUID.randomUUID(), "Callback isolation").principal());
        AgentGraphRevision sharedRevision = session.currentRevision();
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AgentGraphRevision> slow = executor.submit(() -> session.mutate(
                    sharedRevision,
                    graph -> {
                        callbackEntered.countDown();
                        awaitUnchecked(releaseCallback);
                        graph.addEntity("slow", "WRITER", "Slow");
                    }));
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));

            Future<AgentGraphRevision> fast = executor.submit(() -> session.mutate(
                    sharedRevision,
                    graph -> graph.addEntity("fast", "WRITER", "Fast")));
            AgentGraphRevision winner = fast.get(5, TimeUnit.SECONDS);
            releaseCallback.countDown();

            ExecutionException stale = assertThrows(
                    ExecutionException.class, () -> slow.get(5, TimeUnit.SECONDS));
            assertTrue(stale.getCause() instanceof StaleAgentGraphRevisionException);
            assertEquals(winner, session.currentRevision());
            assertTrue(session.read().graph().containsEntity("fast"));
            assertFalse(session.read().graph().containsEntity("slow"));
        } finally {
            releaseCallback.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void corruptImportAndFailedMutationLeaveArchiveIntact() throws Exception {
        AgentInstanceStore store = new AgentInstanceStore(tempDir.resolve("agents"));
        AgentPrivateGraphSession session = store.open(
                store.provision(UUID.randomUUID(), "Failure atomicity").principal());
        AgentGraphRevision initial = session.currentRevision();
        AgentGraphRevision populated = session.mutate(initial,
                graph -> graph.addEntity("safe", "THING", "Safe"));
        AgentGraphArchive before = session.readArchive();

        assertThrows(IOException.class,
                () -> session.replaceArchive(populated, new byte[] {1, 2, 3, 4}));
        assertThrows(IllegalStateException.class, () -> session.mutate(populated, graph -> {
            graph.addEntity("partial", "THING", "Must not persist");
            throw new IllegalStateException("synthetic mutation failure");
        }));

        AgentGraphArchive after = session.readArchive();
        assertEquals(populated, after.revision());
        assertArrayEquals(before.bytes(), after.bytes());
        assertTrue(session.read().graph().containsEntity("safe"));
        assertFalse(session.read().graph().containsEntity("partial"));
    }

    @Test
    void rejectsOversizedImportBeforePublication() throws Exception {
        Path root = tempDir.resolve("agents");
        AgentInstanceStore provisioningStore = new AgentInstanceStore(root);
        AgentInstance instance = provisioningStore.provision(UUID.randomUUID(), "Import limit");
        AgentGraphArchive current = provisioningStore.open(instance.principal()).readArchive();
        int limit = current.bytes().length;
        AgentPrivateGraphSession limited = new AgentInstanceStore(root, limit).open(instance.principal());
        byte[] oversized = Arrays.copyOf(current.bytes(), limit + 1);

        IOException failure = assertThrows(
                IOException.class,
                () -> limited.replaceArchive(current.revision(), oversized));

        assertTrue(failure.getMessage().contains("configured maximum"));
        assertEquals(current.revision(), limited.currentRevision());
        assertArrayEquals(current.bytes(), limited.readArchive().bytes());
    }

    @Test
    void rejectsOversizedCurrentArchiveBeforeReadingItIntoMemory() throws Exception {
        Path root = tempDir.resolve("agents");
        AgentInstanceStore provisioningStore = new AgentInstanceStore(root);
        AgentInstance instance = provisioningStore.provision(UUID.randomUUID(), "Current limit");
        int limit = provisioningStore.open(instance.principal()).readArchive().bytes().length;
        AgentPrivateGraphSession limited = new AgentInstanceStore(root, limit).open(instance.principal());
        Path graphFile = root.resolve(instance.principal().ownerId().toString())
                .resolve(instance.principal().agentId().toString())
                .resolve("graph/private.kgraph");
        Files.write(
                graphFile,
                new byte[limit + 1],
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);

        IOException revisionFailure = assertThrows(IOException.class, limited::currentRevision);
        IOException archiveFailure = assertThrows(IOException.class, limited::readArchive);
        assertTrue(revisionFailure.getMessage().contains("configured maximum"));
        assertTrue(archiveFailure.getMessage().contains("configured maximum"));
    }

    @Test
    void rejectsArchiveWhoseExpandedPayloadExceedsLimit() throws Exception {
        Path root = tempDir.resolve("agents");
        AgentInstanceStore provisioningStore = new AgentInstanceStore(root);
        AgentInstance instance = provisioningStore.provision(UUID.randomUUID(), "Expanded limit");
        AgentPrivateGraphSession limited = new AgentInstanceStore(
                root, 1024 * 1024, 64 * 1024).open(instance.principal());
        AgentGraphArchive before = limited.readArchive();
        byte[] compressedBomb = zipEntry("models/oversized.bin", new byte[128 * 1024]);
        assertTrue(compressedBomb.length < 64 * 1024,
                "fixture must exercise expanded rather than compressed size enforcement");

        IOException failure = assertThrows(
                IOException.class,
                () -> limited.replaceArchive(before.revision(), compressedBomb));

        assertTrue(failure.getMessage().contains("Expanded agent graph archive"));
        assertEquals(before.revision(), limited.currentRevision());
        assertArrayEquals(before.bytes(), limited.readArchive().bytes());
    }

    @Test
    void reportsCandidateRevisionWhenPostMoveDirectoryFsyncFails() throws Exception {
        AtomicBoolean injectFailure = new AtomicBoolean();
        AtomicInteger publicationFsyncs = new AtomicInteger();
        AgentInstanceStore store = new AgentInstanceStore(
                tempDir.resolve("agents"),
                AgentInstanceStore.DEFAULT_MAX_ARCHIVE_BYTES,
                directory -> {
                    AgentInstanceStore.forceDirectory(directory);
                    if (injectFailure.get() && publicationFsyncs.incrementAndGet() == 2) {
                        throw new IOException("synthetic post-move fsync failure");
                    }
                });
        AgentPrivateGraphSession session = store.open(
                store.provision(UUID.randomUUID(), "Indeterminate commit").principal());
        AgentGraphRevision expected = session.currentRevision();
        injectFailure.set(true);

        IndeterminateAgentGraphCommitException failure = assertThrows(
                IndeterminateAgentGraphCommitException.class,
                () -> session.mutate(expected,
                        graph -> graph.addEntity("maybe-committed", "THING", "Maybe committed")));

        assertEquals(failure.candidateRevision(), session.currentRevision());
        assertTrue(session.read().graph().containsEntity("maybe-committed"));
        assertTrue(failure.getMessage().contains("Reread"));
    }

    @Test
    void rejectsNonCanonicalManifestUuids() throws Exception {
        Path root = tempDir.resolve("agents");
        AgentInstanceStore store = new AgentInstanceStore(root);
        UUID ownerId = UUID.fromString("abcdefab-cdef-4abc-8def-abcdefabcdef");
        AgentInstance instance = store.provision(ownerId, "Canonical UUIDs");
        Path manifest = root.resolve(ownerId.toString())
                .resolve(instance.principal().agentId().toString())
                .resolve("agent.json");
        String tampered = Files.readString(manifest)
                .replace(ownerId.toString(), ownerId.toString().toUpperCase());
        Files.writeString(
                manifest,
                tampered,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);

        IOException failure = assertThrows(IOException.class, () -> store.find(instance.principal()));
        assertTrue(failure.getMessage().contains("Non-canonical UUID"));
    }

    @Test
    void richArchiveRoundTripPreservesEveryAssetAndExactImportedBytes() throws Exception {
        Path root = tempDir.resolve("agents");
        AgentInstanceStore store = new AgentInstanceStore(root);
        AgentInstance instance = store.provision(UUID.randomUUID(), "Full fidelity");
        AgentPrivateGraphSession session = store.open(instance.principal());
        byte[] richArchive = serialize(buildRichGraph());

        AgentGraphRevision imported = session.replaceArchive(session.currentRevision(), richArchive);
        AgentGraphArchive exact = session.readArchive();
        assertEquals(AgentGraphRevision.fromArchive(richArchive), imported);
        assertArrayEquals(richArchive, exact.bytes(),
                "validated imports must publish original bytes rather than normalizing the archive");

        UnifiedGraph graph = session.read().graph();
        assertEquals(2, graph.entityCount());
        assertEquals(1, graph.relationCount());
        assertEquals("WORKS_FOR", graph.relations().iterator().next().type());
        assertArrayEquals(new double[] {1.0, 2.0, 3.0},
                graph.entity("alice").orElseThrow().embedding(), 0.0);
        assertArrayEquals(new double[] {0.25, 0.5},
                graph.vectorLayer("kge").get("alice"), 0.0);
        assertEquals(Opinion.fromBetaEvidence(8, 2), graph.entityOpinion("alice"));
        assertEquals(Opinion.fromSoftTruth(0.8, 5), graph.relationOpinion("works"));
        assertEquals(1.75, graph.weightMap("pslWeights").get("worksForRule"), 0.0);
        assertEquals("crawl:document-7",
                graph.entity("alice").orElseThrow().attributes().get("provenance"));
        assertTrue(graph.entity("alice").orElseThrow().hasTypeMembership("Employee"));
        assertEquals("@prefix ex: <urn:test:> .", graph.artifactText("ontology.ttl"));
        assertArrayEquals(new byte[] {1, 2}, graph.artifact("fol-program.bin"));
        assertArrayEquals(new byte[] {3, 4}, graph.artifact("psl-program.bin"));
        assertArrayEquals(new byte[] {5, 6}, graph.artifact("mebn-theory.bin"));
        assertArrayEquals(new byte[] {7, 8}, graph.artifact("samediff-model.bin"));
        assertEquals("agent-private", graph.meta().get("scope"));

        AgentGraphRevision mutated = session.mutate(imported,
                mutable -> mutable.addEntity("after-import", "THING", "After import"));
        AgentPrivateGraphSession restarted = new AgentInstanceStore(root).open(instance.principal());
        UnifiedGraph afterRestart = restarted.read().graph();
        assertEquals(mutated, restarted.currentRevision());
        assertTrue(afterRestart.containsEntity("after-import"));
        assertArrayEquals(new double[] {0.25, 0.5},
                afterRestart.vectorLayer("kge").get("alice"), 0.0);
        assertEquals(Opinion.fromBetaEvidence(8, 2), afterRestart.entityOpinion("alice"));
        assertEquals(1.75, afterRestart.weightMap("pslWeights").get("worksForRule"), 0.0);
        assertEquals("@prefix ex: <urn:test:> .", afterRestart.artifactText("ontology.ttl"));
        assertArrayEquals(new byte[] {1, 2}, afterRestart.artifact("fol-program.bin"));
        assertArrayEquals(new byte[] {3, 4}, afterRestart.artifact("psl-program.bin"));
        assertArrayEquals(new byte[] {5, 6}, afterRestart.artifact("mebn-theory.bin"));
        assertArrayEquals(new byte[] {7, 8}, afterRestart.artifact("samediff-model.bin"));
        assertEquals("agent-private", afterRestart.meta().get("scope"));
    }

    private static boolean attemptWrite(
            AgentPrivateGraphSession session,
            AgentGraphRevision revision,
            String entityId,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        start.await(5, TimeUnit.SECONDS);
        try {
            session.mutate(revision, graph -> graph.addEntity(entityId, "WRITER", entityId));
            return true;
        } catch (StaleAgentGraphRevisionException stale) {
            return false;
        }
    }

    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for test latch", interrupted);
        }
    }

    private static void assertPosixPermissions(Path path, String expected) throws IOException {
        assertEquals(
                PosixFilePermissions.fromString(expected),
                Files.getPosixFilePermissions(path),
                () -> "Unexpected permissions for " + path);
    }

    private static UnifiedGraph buildRichGraph() {
        Instant timestamp = Instant.parse("2026-08-26T12:00:00Z");
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(new SimpleGraphEntity(
                "alice",
                "PERSON",
                "Alice",
                0.9,
                0.85,
                Set.of("trusted"),
                new double[] {1.0, 2.0, 3.0},
                timestamp,
                Map.of(
                        "provenance", "crawl:document-7",
                        "owlInferredTypes", List.of("Employee"))));
        graph.addEntity(new SimpleGraphEntity(
                "acme", "ORG", "Acme", 1.0, 0.95, Set.of(), null, timestamp,
                Map.of("sourcePath", "document-7")));
        graph.addRelation(new SimpleGraphRelation(
                "works", "alice", "acme", "WORKS_FOR", 0.8, 0.9, true,
                Set.of("extracted"), new double[] {0.3, 0.4}, timestamp,
                Map.of("provenance", "extractor:1")));

        VectorLayer kge = new VectorLayer("kge", VectorLayer.Target.ENTITY, 2, Dtype.F64);
        kge.put("alice", new double[] {0.25, 0.5});
        kge.put("acme", new double[] {0.75, 1.0});
        graph.putVectorLayer(kge);
        graph.putEntityOpinion("alice", Opinion.fromBetaEvidence(8, 2));
        graph.putRelationOpinion("works", Opinion.fromSoftTruth(0.8, 5));
        graph.putWeightMap("pslWeights", Map.of("worksForRule", 1.75));
        graph.putArtifactText("ontology.ttl", "@prefix ex: <urn:test:> .");
        graph.putArtifact("fol-program.bin", new byte[] {1, 2});
        graph.putArtifact("psl-program.bin", new byte[] {3, 4});
        graph.putArtifact("mebn-theory.bin", new byte[] {5, 6});
        graph.putArtifact("samediff-model.bin", new byte[] {7, 8});
        graph.meta("scope", "agent-private").graphId("private-agent-graph");
        return graph;
    }

    private static byte[] serialize(UnifiedGraph graph) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        graph.save(out, Dtype.F64);
        return out.toByteArray();
    }

    private static byte[] zipEntry(String name, byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content);
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
