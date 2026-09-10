/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.agent.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AgentConversationSessionTest {

    @TempDir
    Path tempDir;

    @Test
    void survivesRestartUsesHashedPathAndIsolatesSameKeyAcrossAgents() throws Exception {
        Path root = tempDir.resolve("agents");
        AgentInstanceStore store = new AgentInstanceStore(root);
        UUID owner = UUID.randomUUID();
        AgentInstance first = store.provision(owner, "First");
        AgentInstance second = store.provision(owner, "Second");
        String externalKey = "telegram:user/../../sensitive-thread";
        AgentConversationSession firstSession = store.openConversation(first.principal(), externalKey);
        AgentConversationSession secondSession = store.openConversation(second.principal(), externalKey);

        firstSession.append(message(ConversationRole.USER, "first-only"));
        secondSession.append(message(ConversationRole.USER, "second-only"));

        AgentConversationSession restarted = new AgentInstanceStore(root)
                .openConversation(first.principal(), externalKey);
        assertEquals(List.of("first-only"), contents(restarted));
        assertEquals(null, restarted.loadTail(new ConversationLoadLimits(10, 1024))
                .events().get(0).idempotencyKey());
        assertEquals(List.of("second-only"), contents(secondSession));
        assertNotEquals(firstSession.journalPath(), secondSession.journalPath());
        assertEquals(AgentConversationSession.hashConversationKey(externalKey)
                        + AgentConversationSession.JOURNAL_SUFFIX,
                firstSession.journalPath().getFileName().toString());
        assertFalse(firstSession.journalPath().toString().contains(externalKey));
        assertTrue(firstSession.journalPath().startsWith(root.resolve(owner.toString())
                .resolve(first.principal().agentId().toString())
                .resolve("state/conversations")));
    }

    @Test
    void foreignOwnerAndUnknownAgentCannotBindConversation() throws Exception {
        AgentInstanceStore store = new AgentInstanceStore(tempDir.resolve("agents"));
        AgentInstance instance = store.provision(UUID.randomUUID(), "Private");

        AgentPrincipal foreign = new AgentPrincipal(
                UUID.randomUUID(), instance.principal().agentId());
        assertThrows(IOException.class, () -> store.openConversation(foreign, "shared"));
        assertThrows(IOException.class, () -> store.openConversation(
                new AgentPrincipal(instance.principal().ownerId(), UUID.randomUUID()), "shared"));
    }

    @Test
    void concurrentThreadsProduceUniqueMonotonicOrder() throws Exception {
        AgentConversationSession session = newSession("threads");
        int writers = 8;
        int perWriter = 25;
        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(writers);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int writer = 0; writer < writers; writer++) {
                int writerId = writer;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    for (int index = 0; index < perWriter; index++) {
                        session.append(message(
                                ConversationRole.USER, writerId + ":" + index));
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        ConversationTail tail = session.loadTail(new ConversationLoadLimits(1000, 1024 * 1024));
        assertEquals(writers * perWriter, tail.totalEvents());
        assertFalse(tail.truncated());
        Set<UUID> ids = new HashSet<>();
        Set<String> contents = new HashSet<>();
        for (int index = 0; index < tail.events().size(); index++) {
            ConversationEvent event = tail.events().get(index);
            assertEquals(index + 1L, event.sequence());
            ids.add(event.eventId());
            contents.add(event.content());
        }
        assertEquals(writers * perWriter, ids.size());
        assertEquals(writers * perWriter, contents.size());
    }

    @Test
    void concurrentIdempotentAppendsPublishExactlyOneFrame() throws Exception {
        AgentConversationSession session = newSession("idempotent-threads");
        ConversationEventDraft draft = new ConversationEventDraft(
                ConversationEventType.MESSAGE,
                ConversationRole.USER,
                "same turn",
                Map.of(),
                "turn:01234567-89ab-cdef-0123-456789abcdef:user");
        int writers = 12;
        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(writers);
        try {
            List<Future<ConversationEvent>> futures = new ArrayList<>();
            for (int index = 0; index < writers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return session.appendIdempotent(draft);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            Set<UUID> eventIds = new HashSet<>();
            for (Future<ConversationEvent> future : futures) {
                eventIds.add(future.get(30, TimeUnit.SECONDS).eventId());
            }
            assertEquals(1, eventIds.size());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
        ConversationTail tail = session.loadTail(new ConversationLoadLimits(10, 1024));
        assertEquals(1, tail.totalEvents());
        assertEquals(draft.idempotencyKey(), tail.events().get(0).idempotencyKey());
    }

    @Test
    void idempotentAppendDeduplicatesAfterStoreRestart() throws Exception {
        Path root = tempDir.resolve("restart-idempotency");
        AgentInstanceStore store = new AgentInstanceStore(root);
        AgentInstance instance = store.provision(UUID.randomUUID(), "Restart");
        String conversationKey = "web:restart";
        String idempotencyKey = "turn:01234567-89ab-cdef-0123-456789abcdef:terminal";
        ConversationEvent first = store.openConversation(instance.principal(), conversationKey)
                .appendIdempotent(new ConversationEventDraft(
                        ConversationEventType.MESSAGE,
                        ConversationRole.ASSISTANT,
                        "first outcome",
                        Map.of(),
                        idempotencyKey));

        ConversationEvent duplicate = new AgentInstanceStore(root)
                .openConversation(instance.principal(), conversationKey)
                .appendIdempotent(new ConversationEventDraft(
                        ConversationEventType.ERROR,
                        ConversationRole.SYSTEM,
                        "losing retry",
                        Map.of(),
                        idempotencyKey));

        assertEquals(first.eventId(), duplicate.eventId());
        assertEquals("first outcome", duplicate.content());
        assertEquals(1, new AgentInstanceStore(root)
                .openConversation(instance.principal(), conversationKey)
                .loadTail(new ConversationLoadLimits(10, 1024)).totalEvents());
    }

    @Test
    void forkedProcessesShareFileLockAndSequence() throws Exception {
        Path root = tempDir.resolve("agents");
        AgentInstanceStore store = new AgentInstanceStore(root);
        AgentInstance instance = store.provision(UUID.randomUUID(), "Forked");
        String key = "same-process-independent-key";
        Process first = startAppendProcess(root, instance.principal(), key, "a-", 30);
        Process second = startAppendProcess(root, instance.principal(), key, "b-", 30);

        assertTrue(first.waitFor(45, TimeUnit.SECONDS), "first append process timed out");
        assertTrue(second.waitFor(45, TimeUnit.SECONDS), "second append process timed out");
        String firstOutput = new String(first.getInputStream().readAllBytes());
        String secondOutput = new String(second.getInputStream().readAllBytes());
        assertEquals(0, first.exitValue(), firstOutput);
        assertEquals(0, second.exitValue(), secondOutput);

        ConversationTail tail = store.openConversation(instance.principal(), key)
                .loadTail(new ConversationLoadLimits(100, 1024 * 1024));
        assertEquals(60, tail.totalEvents());
        Set<String> content = new HashSet<>();
        for (int index = 0; index < tail.events().size(); index++) {
            assertEquals(index + 1L, tail.events().get(index).sequence());
            content.add(tail.events().get(index).content());
        }
        assertEquals(60, content.size());
    }

    @Test
    void repairsOnlyIncompleteFinalFrameAndContinuesSequence() throws Exception {
        AgentConversationSession session = newSession("torn-tail");
        session.append(message(ConversationRole.USER, "before"));
        long validSize = Files.size(session.journalPath());
        ByteBuffer torn = ByteBuffer.allocate(AgentConversationSession.FRAME_HEADER_BYTES + 3);
        torn.putInt(AgentConversationSession.FRAME_MAGIC);
        torn.putInt(100);
        torn.putLong(123L);
        torn.put(new byte[] {1, 2, 3});
        Files.write(session.journalPath(), torn.array(), StandardOpenOption.APPEND);

        assertEquals(List.of("before"), contents(session));
        assertEquals(validSize, Files.size(session.journalPath()));
        ConversationEvent after = session.append(message(ConversationRole.ASSISTANT, "after"));
        assertEquals(2, after.sequence());
        assertEquals(List.of("before", "after"), contents(session));
    }

    @Test
    void checksumCorruptionFailsClosedWithoutTruncatingPriorOrFollowingData() throws Exception {
        AgentConversationSession session = newSession("corrupt");
        session.appendAll(List.of(
                message(ConversationRole.USER, "first"),
                message(ConversationRole.ASSISTANT, "second")));
        byte[] journal = Files.readAllBytes(session.journalPath());
        journal[AgentConversationSession.FRAME_HEADER_BYTES + 8] ^= 0x01;
        Files.write(session.journalPath(), journal, StandardOpenOption.TRUNCATE_EXISTING);
        long corruptedSize = Files.size(session.journalPath());

        IOException failure = assertThrows(IOException.class, () -> contents(session));
        assertTrue(failure.getMessage().contains("checksum"), failure::getMessage);
        assertEquals(corruptedSize, Files.size(session.journalPath()));
        assertThrows(IOException.class,
                () -> session.append(message(ConversationRole.USER, "must-not-append")));
    }

    @Test
    void rejectsSymlinkedJournalWithNoFollowLinks() throws Exception {
        AgentConversationSession session = newSession("symlink");
        session.append(message(ConversationRole.USER, "safe"));
        Path outside = tempDir.resolve("outside.events");
        Files.writeString(outside, "outside");
        Files.delete(session.journalPath());
        try {
            Files.createSymbolicLink(session.journalPath(), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            assumeTrue(false, "symbolic links are unavailable: " + unavailable.getMessage());
        }

        assertThrows(IOException.class, () -> contents(session));
        assertEquals("outside", Files.readString(outside));
    }

    @Test
    void enforcesOwnerOnlyPermissionsWhenPosixIsSupported() throws Exception {
        assumeTrue(Files.getFileAttributeView(
                tempDir, PosixFileAttributeView.class) != null,
                "POSIX permissions are unavailable");
        AgentConversationSession session = newSession("permissions");
        session.append(message(ConversationRole.USER, "private"));

        assertEquals(PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(session.journalPath().getParent()));
        assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(session.journalPath()));
        assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(session.lockPath()));
    }

    @Test
    void quotasAndLoadLimitsFailExplicitly() throws Exception {
        Path root = tempDir.resolve("agents");
        AgentInstanceStore store = new AgentInstanceStore(root);
        AgentInstance instance = store.provision(UUID.randomUUID(), "Quota");
        AgentConversationSession eventQuota = store.openConversation(
                instance.principal(), "event-quota", 64 * 1024, 2);
        eventQuota.appendAll(List.of(
                message(ConversationRole.USER, "one"),
                message(ConversationRole.ASSISTANT, "two")));
        IOException eventFailure = assertThrows(IOException.class,
                () -> eventQuota.append(message(ConversationRole.USER, "three")));
        assertTrue(eventFailure.getMessage().contains("event quota"));

        AgentConversationSession byteQuota = store.openConversation(
                instance.principal(), "byte-quota", 600, 100);
        byteQuota.append(message(ConversationRole.USER, "x".repeat(100)));
        IOException byteFailure = assertThrows(IOException.class,
                () -> byteQuota.append(message(ConversationRole.USER, "y".repeat(400))));
        assertTrue(byteFailure.getMessage().contains("journal quota"));

        assertThrows(IllegalArgumentException.class, () -> message(
                ConversationRole.USER,
                "z".repeat(ConversationEventDraft.MAX_CONTENT_BYTES + 1)));
        assertThrows(IllegalArgumentException.class, () -> new ConversationEventDraft(
                ConversationEventType.MESSAGE,
                ConversationRole.USER,
                "metadata",
                Map.of("k", "v".repeat(ConversationEventDraft.MAX_METADATA_VALUE_BYTES + 1))));
        assertThrows(IllegalArgumentException.class, () -> new ConversationEventDraft(
                ConversationEventType.MESSAGE,
                ConversationRole.USER,
                "key",
                Map.of(),
                "bad key"));

        ConversationTail limited = eventQuota.loadTail(new ConversationLoadLimits(1, 1024));
        assertEquals(1, limited.events().size());
        assertEquals(2, limited.totalEvents());
        assertTrue(limited.truncated());
        assertThrows(IOException.class,
                () -> eventQuota.loadTail(new ConversationLoadLimits(2, 1)));
    }

    @Test
    void clearDeletesJournalAndResetsSequence() throws Exception {
        AgentConversationSession session = newSession("clear");
        session.append(message(ConversationRole.USER, "before"));
        session.clear();

        assertFalse(Files.exists(session.journalPath()));
        assertTrue(session.loadTail(new ConversationLoadLimits(10, 1024)).isEmpty());
        assertEquals(1, session.append(message(ConversationRole.USER, "after")).sequence());
    }

    @Test
    void migrationImportAndMarkerAreAtomicAndExactlyOnce() throws Exception {
        AgentConversationSession session = newSession("migration");
        List<ConversationEventDraft> imported = List.of(
                new ConversationEventDraft(
                        ConversationEventType.MESSAGE,
                        ConversationRole.USER,
                        "legacy",
                        Map.of("provenance", "legacy-kclaw-jsonl-v1")));

        assertTrue(session.appendMigrationIfEmpty("legacy-source-hash", imported));
        assertFalse(session.appendMigrationIfEmpty("legacy-source-hash", imported));
        ConversationTail history = session.loadTail(new ConversationLoadLimits(10, 1024));
        assertEquals(2, history.totalEvents());
        assertEquals("legacy", history.events().get(0).content());
        assertEquals(ConversationEventType.MIGRATION, history.events().get(1).type());
        assertEquals(List.of(1L, 2L), history.events().stream()
                .map(ConversationEvent::sequence).toList());
    }

    @Test
    void allNeutralEventKindsRoundTripWithoutProviderDependencies() throws Exception {
        AgentConversationSession session = newSession("types");
        List<ConversationEventDraft> drafts = new ArrayList<>();
        for (ConversationEventType type : ConversationEventType.values()) {
            drafts.add(new ConversationEventDraft(
                    type,
                    type == ConversationEventType.TOOL_CALL
                            || type == ConversationEventType.TOOL_RESULT
                            ? ConversationRole.TOOL : ConversationRole.SYSTEM,
                    type.name(),
                    Map.of("futureProjection", "supported")));
        }

        session.appendAll(drafts);
        ConversationTail tail = session.loadTail(new ConversationLoadLimits(100, 1024 * 1024));
        assertEquals(List.of(ConversationEventType.values()),
                tail.events().stream().map(ConversationEvent::type).toList());
    }

    private AgentConversationSession newSession(String key) throws Exception {
        AgentInstanceStore store = new AgentInstanceStore(tempDir.resolve("agents-" + key));
        AgentInstance instance = store.provision(UUID.randomUUID(), key);
        return store.openConversation(instance.principal(), key);
    }

    private static ConversationEventDraft message(ConversationRole role, String content) {
        return new ConversationEventDraft(ConversationEventType.MESSAGE, role, content);
    }

    private static List<String> contents(AgentConversationSession session) throws IOException {
        return session.loadTail(new ConversationLoadLimits(10_000, 64 * 1024 * 1024))
                .events().stream().map(ConversationEvent::content).toList();
    }

    private Process startAppendProcess(
            Path root,
            AgentPrincipal principal,
            String key,
            String prefix,
            int count) throws IOException {
        String executable = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java")
                .toString();
        return new ProcessBuilder(
                executable,
                "-cp",
                System.getProperty("java.class.path"),
                AgentConversationAppendProcessMain.class.getName(),
                root.toString(),
                principal.ownerId().toString(),
                principal.agentId().toString(),
                key,
                prefix,
                Integer.toString(count))
                .redirectErrorStream(true)
                .start();
    }
}
