package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.logs.LogPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("SYSTEM_ERR")
class TranscriptLogScopeTest {

    @Test
    void isolatedScopesDoNotClaimUnboundOrInheritedWork(@TempDir Path tempDir) throws Exception {
        try (TestEnvironment env = new TestEnvironment(tempDir);
             TranscriptLogScope first = TranscriptLogScope.openIsolated("first", tempDir, false);
             TranscriptLogScope second = TranscriptLogScope.openIsolated("second", tempDir, false)) {
            assertEquals("configured-root", TranscriptLogScope.currentTranscriptId());
            System.err.println("unbound-only");
            try (TranscriptLogScope.Binding ignored = first.bind()) {
                assertEquals("first", TranscriptLogScope.currentTranscriptId());
                assertEquals("configured-root", System.getProperty(TranscriptLogScope.TRANSCRIPT_ID_PROPERTY));
                System.err.println("first-only");
                // The worker is created inside a binding: no InheritableThreadLocal propagation.
                ExecutorService worker = Executors.newSingleThreadExecutor();
                try {
                    assertEquals("configured-root", worker.submit(() -> {
                        System.err.println("child-unbound-only");
                        return TranscriptLogScope.currentTranscriptId();
                    }).get(5, TimeUnit.SECONDS));
                    assertEquals("second", worker.submit(second.capture((Callable<String>) () -> {
                        System.err.println("second-only");
                        return TranscriptLogScope.currentTranscriptId();
                    })).get(5, TimeUnit.SECONDS));
                } finally {
                    stop(worker);
                }
                assertEquals("first", TranscriptLogScope.currentTranscriptId());
            }
            String firstLog = Files.readString(first.logFile());
            String secondLog = Files.readString(second.logFile());
            assertTrue(firstLog.contains("first-only"));
            assertTrue(secondLog.contains("second-only"));
            assertFalse(firstLog.contains("second-only"));
            assertFalse(secondLog.contains("first-only"));
            assertFalse(firstLog.contains("unbound-only"));
            assertFalse(secondLog.contains("unbound-only"));
            assertTrue(env.output().contains("child-unbound-only"));
        }
    }

    @Test
    void concurrentCapturedScopesAndReusedPoolDoNotLeakBindings(@TempDir Path tempDir) throws Exception {
        try (TestEnvironment env = new TestEnvironment(tempDir);
             TranscriptLogScope first = TranscriptLogScope.openIsolated("first", tempDir, false);
             TranscriptLogScope second = TranscriptLogScope.openIsolated("second", tempDir, false)) {
            ExecutorService workers = Executors.newFixedThreadPool(2);
            CyclicBarrier barrier = new CyclicBarrier(2);
            try {
                for (int round = 0; round < 3; round++) {
                    Future<String> a = workers.submit(first.capture((Callable<String>) () -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        System.err.println("pool-first-only");
                        return TranscriptLogScope.currentTranscriptId();
                    }));
                    Future<String> b = workers.submit(second.capture((Callable<String>) () -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        System.err.println("pool-second-only");
                        return TranscriptLogScope.currentTranscriptId();
                    }));
                    assertEquals("first", a.get(5, TimeUnit.SECONDS));
                    assertEquals("second", b.get(5, TimeUnit.SECONDS));
                    // Barrier forces both reused workers to prove they are unbound.
                    Callable<String> unbound = () -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        System.err.println("pool-unbound-only");
                        return TranscriptLogScope.currentTranscriptId();
                    };
                    Future<String> u = workers.submit(unbound);
                    Future<String> v = workers.submit(unbound);
                    assertEquals("configured-root", u.get(5, TimeUnit.SECONDS));
                    assertEquals("configured-root", v.get(5, TimeUnit.SECONDS));
                }
            } finally {
                stop(workers);
            }
            String firstLog = Files.readString(first.logFile());
            String secondLog = Files.readString(second.logFile());
            assertTrue(firstLog.contains("pool-first-only"));
            assertTrue(secondLog.contains("pool-second-only"));
            assertFalse(firstLog.contains("pool-second-only"));
            assertFalse(secondLog.contains("pool-first-only"));
            assertFalse(firstLog.contains("pool-unbound-only"));
            assertFalse(secondLog.contains("pool-unbound-only"));
        }
    }

    @Test
    void nestedBindingsRestoreOnRunnableAndCallableFailure(@TempDir Path tempDir) throws Exception {
        try (TestEnvironment env = new TestEnvironment(tempDir);
             TranscriptLogScope first = TranscriptLogScope.openIsolated("first", tempDir, false);
             TranscriptLogScope second = TranscriptLogScope.openIsolated("second", tempDir, false)) {
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                worker.submit(first.capture((Runnable) () -> {
                    RuntimeException failure = new RuntimeException("expected");
                    assertSame(failure, assertThrows(RuntimeException.class,
                            () -> second.capture((Runnable) () -> {
                                assertEquals("second", TranscriptLogScope.currentTranscriptId());
                                System.err.println("runnable-second-only");
                                throw failure;
                            }).run()));
                    assertEquals("first", TranscriptLogScope.currentTranscriptId());
                    java.io.IOException checked = new java.io.IOException("expected checked");
                    assertSame(checked, assertThrows(java.io.IOException.class,
                            () -> second.capture((Callable<Void>) () -> {
                                assertEquals("second", TranscriptLogScope.currentTranscriptId());
                                throw checked;
                            }).call()));
                    assertEquals("first", TranscriptLogScope.currentTranscriptId());
                    assertThrows(RuntimeException.class, () -> {
                        try (TranscriptLogScope.Binding ignored = second.bind()) {
                            assertEquals("second", TranscriptLogScope.currentTranscriptId());
                            throw failure;
                        }
                    });
                    assertEquals("first", TranscriptLogScope.currentTranscriptId());
                    System.err.println("restored-first-only");
                })).get(5, TimeUnit.SECONDS);
                assertEquals("configured-root", worker.submit(TranscriptLogScope::currentTranscriptId)
                        .get(5, TimeUnit.SECONDS));
            } finally {
                stop(worker);
            }
            assertTrue(Files.readString(first.logFile()).contains("restored-first-only"));
            assertFalse(Files.readString(first.logFile()).contains("runnable-second-only"));
            assertTrue(Files.readString(second.logFile()).contains("runnable-second-only"));
        }
    }

    @Test
    void closingOwnerOutOfOrderMakesStaleCallbacksTerminalOnly(@TempDir Path tempDir) throws Exception {
        try (TestEnvironment env = new TestEnvironment(tempDir)) {
            try (TranscriptLogScope legacy = TranscriptLogScope.open("legacy", tempDir, false);
                 TranscriptLogScope first = TranscriptLogScope.openIsolated("first", tempDir, false);
                 TranscriptLogScope second = TranscriptLogScope.openIsolated("second", tempDir, false)) {
                Runnable stale = first.capture((Runnable) () -> {
                    assertNull(TranscriptLogScope.currentTranscriptId());
                    System.err.println("stale-only");
                });
                try (TranscriptLogScope.Binding ignored = first.bind()) {
                    first.close();
                    assertNull(TranscriptLogScope.currentTranscriptId());
                    System.err.println("closed-bound-only");
                }
                assertThrows(IllegalStateException.class, first::bind);
                assertThrows(java.io.IOException.class, () -> first.switchTo("no", tempDir, false));
                try (TranscriptLogScope.Binding ignored = second.bind()) {
                    stale.run();
                    assertEquals("second", TranscriptLogScope.currentTranscriptId());
                    System.err.println("live-second-only");
                }
                System.err.println("legacy-unbound-only");
                String firstLog = Files.readString(first.logFile());
                String secondLog = Files.readString(second.logFile());
                String legacyLog = Files.readString(legacy.logFile());
                for (String log : new String[]{firstLog, secondLog, legacyLog}) {
                    assertFalse(log.contains("stale-only"));
                    assertFalse(log.contains("closed-bound-only"));
                }
                assertTrue(secondLog.contains("live-second-only"));
                assertFalse(legacyLog.contains("live-second-only"));
                assertTrue(legacyLog.contains("legacy-unbound-only"));
                assertFalse(secondLog.contains("legacy-unbound-only"));
                legacy.close();
                assertEquals("configured-root", TranscriptLogScope.currentTranscriptId());
                second.capture((Runnable) () -> System.err.println("survives-legacy-close")).run();
                assertTrue(Files.readString(second.logFile()).contains("survives-legacy-close"));
            }
            assertSame(env.terminal, System.err);
            System.err.println("root-still-open");
            assertTrue(env.output().contains("stale-only"));
            assertTrue(env.output().contains("root-still-open"));
        }
    }

    @Test
    void isolatedClearSwitchesOnlyItsOwnerAndFailedSwitchKeepsSink(@TempDir Path tempDir) throws Exception {
        try (TestEnvironment env = new TestEnvironment(tempDir);
             TranscriptLogScope first = TranscriptLogScope.openIsolated("first", tempDir, false);
             TranscriptLogScope second = TranscriptLogScope.openIsolated("second", tempDir, false)) {
            Path oldLog = first.logFile();
            Callable<String> captured = first.capture((Callable<String>) () -> {
                System.err.println("after-clear-only");
                return TranscriptLogScope.currentTranscriptId();
            });
            try (TranscriptLogScope.Binding ignored = second.bind()) {
                first.switchTo("fresh", tempDir, false);
                assertEquals("second", TranscriptLogScope.currentTranscriptId());
                System.err.println("second-after-clear");
            }
            assertEquals("fresh", captured.call());
            try (TranscriptLogScope.Binding ignored = first.bind()) {
                Files.writeString(LogPaths.transcriptDirectory("blocked").toPath(), "not a directory");
                assertThrows(java.io.IOException.class, () -> first.switchTo("blocked", tempDir, false));
                assertEquals("fresh", TranscriptLogScope.currentTranscriptId());
                System.err.println("after-failure-only");
                first.switchTo("fresh-again", tempDir, false);
                assertEquals("fresh-again", TranscriptLogScope.currentTranscriptId());
            }
            assertEquals("configured-root", System.getProperty(TranscriptLogScope.TRANSCRIPT_ID_PROPERTY));
            String freshLog = Files.readString(LogPaths.transcriptDirectory("fresh").toPath().resolve("cli.log"));
            assertTrue(freshLog.contains("transcript=fresh"));
            assertTrue(freshLog.contains("after-clear-only"));
            assertTrue(freshLog.contains("after-failure-only"));
            assertFalse(Files.readString(oldLog).contains("after-clear-only"));
            assertFalse(Files.readString(second.logFile()).contains("after-clear-only"));
            assertFalse(freshLog.contains("second-after-clear"));
            assertTrue(Files.readString(second.logFile()).contains("second-after-clear"));
        }
    }

    @Test
    void legacyReentryAndOutOfOrderCloseRestoreLiveParentNotClosedSink(@TempDir Path tempDir) throws Exception {
        try (TestEnvironment env = new TestEnvironment(tempDir)) {
            try (TranscriptLogScope parent = TranscriptLogScope.open("parent", tempDir, false);
                 TranscriptLogScope reentry = TranscriptLogScope.open("parent", tempDir, false)) {
                assertThrows(java.io.IOException.class, () -> parent.switchTo("fresh", tempDir, false));
                parent.close();
                System.err.println("reentered-still-live");
                try (TranscriptLogScope child = TranscriptLogScope.open("child", tempDir, false)) {
                    reentry.close();
                    assertEquals("child", TranscriptLogScope.currentTranscriptId());
                    System.err.println("child-still-live");
                    assertTrue(Files.readString(child.logFile()).contains("child-still-live"));
                }
                assertEquals("configured-root", TranscriptLogScope.currentTranscriptId());
                assertSame(env.terminal, System.err);
                assertTrue(Files.readString(parent.logFile()).contains("reentered-still-live"));
                assertFalse(Files.readString(parent.logFile()).contains("child-still-live"));
            }
        }
    }

    @Test
    void nestedLegacyScopeRestoresTemporarilyInterceptedStderr(@TempDir Path tempDir) throws Exception {
        try (TestEnvironment env = new TestEnvironment(tempDir)) {
            try (TranscriptLogScope parent = TranscriptLogScope.open("parent", tempDir, false)) {
                PrintStream parentDispatcher = System.err;
                ByteArrayOutputStream intercepted = new ByteArrayOutputStream();
                try (PrintStream interceptor = new PrintStream(intercepted, true, StandardCharsets.UTF_8)) {
                    // KompileTui.runCommandOutput installs an interceptor around a slash
                    // handler, which may launch a nested legacy chat in the same JVM.
                    System.setErr(interceptor);
                    try {
                        System.err.println("intercepted-before-child");
                        Path childLog;
                        try (TranscriptLogScope child = TranscriptLogScope.open("child", tempDir, true)) {
                            childLog = child.logFile();
                            assertSame(parentDispatcher, System.err);
                            assertEquals("child", TranscriptLogScope.currentTranscriptId());
                            System.err.println("nested-child-only");
                        }
                        assertSame(interceptor, System.err);
                        assertEquals("parent", TranscriptLogScope.currentTranscriptId());
                        System.err.println("intercepted-after-child");
                        assertTrue(Files.readString(childLog).contains("nested-child-only"));
                        assertFalse(Files.readString(childLog).contains("intercepted-"));
                        assertFalse(Files.readString(parent.logFile()).contains("nested-child-only"));
                        assertFalse(intercepted.toString(StandardCharsets.UTF_8).contains("nested-child-only"));
                        assertTrue(intercepted.toString(StandardCharsets.UTF_8).contains("intercepted-after-child"));
                        assertTrue(env.output().contains("nested-child-only"));
                    } finally {
                        System.setErr(parentDispatcher);
                    }
                }
                System.err.println("parent-after-interceptor");
                assertTrue(Files.readString(parent.logFile()).contains("parent-after-interceptor"));
            }
            assertSame(env.terminal, System.err);
        }
    }

    @Test
    void isolatedOpenDoesNotReplaceTemporarilyInterceptedStderr(@TempDir Path tempDir) throws Exception {
        try (TestEnvironment env = new TestEnvironment(tempDir);
             TranscriptLogScope parent = TranscriptLogScope.open("parent", tempDir, false)) {
            PrintStream parentDispatcher = System.err;
            try (PrintStream interceptor = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)) {
                System.setErr(interceptor);
                try {
                    try (TranscriptLogScope isolated = TranscriptLogScope.openIsolated("isolated", tempDir, false)) {
                        assertSame(interceptor, System.err);
                        try (TranscriptLogScope.Binding ignored = isolated.bind()) {
                            parentDispatcher.println("isolated-via-retained-dispatcher");
                        }
                        assertTrue(Files.readString(isolated.logFile()).contains("isolated-via-retained-dispatcher"));
                        assertFalse(Files.readString(parent.logFile()).contains("isolated-via-retained-dispatcher"));
                    }
                    assertSame(interceptor, System.err);
                } finally {
                    System.setErr(parentDispatcher);
                }
            }
        }
    }

    @Test
    void staleCallableCannotClaimNewScopeEvenWithSameId(@TempDir Path tempDir) throws Exception {
        try (TestEnvironment env = new TestEnvironment(tempDir)) {
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Callable<String> stale;
                PrintStream oldDispatcher;
                try (TranscriptLogScope old = TranscriptLogScope.openIsolated("reused", tempDir, false)) {
                    oldDispatcher = System.err;
                    stale = old.capture((Callable<String>) () -> {
                        System.err.println("stale-new-lifetime-only");
                        return TranscriptLogScope.currentTranscriptId();
                    });
                    assertEquals("reused", worker.submit(old.capture(
                            (Callable<String>) TranscriptLogScope::currentTranscriptId)).get(5, TimeUnit.SECONDS));
                }
                try (TranscriptLogScope next = TranscriptLogScope.open("reused", tempDir, true)) {
                    assertNull(worker.submit(stale).get(5, TimeUnit.SECONDS));
                    assertEquals("reused", worker.submit(TranscriptLogScope::currentTranscriptId)
                            .get(5, TimeUnit.SECONDS));
                    oldDispatcher.println("retained-dispatcher-only");
                    System.err.println("new-lifetime-live");
                    String log = Files.readString(next.logFile());
                    assertFalse(log.contains("stale-new-lifetime-only"));
                    assertFalse(log.contains("retained-dispatcher-only"));
                    assertTrue(log.contains("new-lifetime-live"));
                }
                assertTrue(env.output().contains("stale-new-lifetime-only"));
                assertTrue(env.output().contains("retained-dispatcher-only"));
                assertSame(env.terminal, System.err);
            } finally {
                stop(worker);
            }
        }
    }

    @Test
    void failedIsolatedOpenLeavesRootAndAbsentIdentityUntouched(@TempDir Path tempDir) throws Exception {
        try (TestEnvironment env = new TestEnvironment(tempDir)) {
            System.clearProperty(TranscriptLogScope.TRANSCRIPT_ID_PROPERTY);
            Files.createDirectories(LogPaths.transcriptsRoot().toPath());
            Files.writeString(LogPaths.transcriptDirectory("blocked").toPath(), "not a directory");
            assertThrows(java.io.IOException.class,
                    () -> TranscriptLogScope.openIsolated("blocked", tempDir, false));
            assertSame(env.terminal, System.err);
            assertNull(TranscriptLogScope.currentTranscriptId());
            try (TranscriptLogScope live = TranscriptLogScope.openIsolated("live", tempDir, false)) {
                assertNull(System.getProperty(TranscriptLogScope.TRANSCRIPT_ID_PROPERTY));
                assertNull(TranscriptLogScope.currentTranscriptId());
                try (TranscriptLogScope.Binding ignored = live.bind()) {
                    assertEquals("live", TranscriptLogScope.currentTranscriptId());
                }
                assertNull(TranscriptLogScope.currentTranscriptId());
            }
            assertNull(System.getProperty(TranscriptLogScope.TRANSCRIPT_ID_PROPERTY));
            assertSame(env.terminal, System.err);
        }
    }

    private static void stop(ExecutorService worker) throws InterruptedException {
        worker.shutdownNow();
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS), "executor did not stop");
    }

    private static final class TestEnvironment implements AutoCloseable {
        private final String previousHome = System.getProperty("user.home");
        private final String previousTranscript = System.getProperty(TranscriptLogScope.TRANSCRIPT_ID_PROPERTY);
        private final PrintStream previousErr = System.err;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final PrintStream terminal = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        private TestEnvironment(Path home) {
            System.setProperty("user.home", home.toString());
            System.setProperty(TranscriptLogScope.TRANSCRIPT_ID_PROPERTY, "configured-root");
            System.setErr(terminal);
        }

        private String output() { return bytes.toString(StandardCharsets.UTF_8); }

        @Override
        public void close() {
            System.setErr(previousErr);
            restoreProperty("user.home", previousHome);
            restoreProperty(TranscriptLogScope.TRANSCRIPT_ID_PROPERTY, previousTranscript);
            terminal.close();
        }

        private static void restoreProperty(String name, String value) {
            if (value == null) System.clearProperty(name);
            else System.setProperty(name, value);
        }
    }

    @Test
    void separatesTranscriptsAndAppendsResumedDiagnostics(@TempDir Path tempDir)
            throws Exception {
        String previousHome = System.getProperty("user.home");
        String previousTranscript = System.getProperty(
                TranscriptLogScope.TRANSCRIPT_ID_PROPERTY);
        PrintStream previousErr = System.err;
        PrintStream terminal = new PrintStream(
                new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        try {
            System.setProperty("user.home", tempDir.toString());
            System.setErr(terminal);

            try (TranscriptLogScope ignored =
                         TranscriptLogScope.open(first, tempDir, false)) {
                System.err.println("first-only");
                assertTrue(first.equals(TranscriptLogScope.currentTranscriptId()));
            }
            try (TranscriptLogScope ignored =
                         TranscriptLogScope.open(second, tempDir, false)) {
                System.err.println("second-only");
            }
            try (TranscriptLogScope ignored =
                         TranscriptLogScope.open(first, tempDir, true)) {
                System.err.println("first-resumed");
            }

            String firstLog = Files.readString(
                    LogPaths.transcriptDirectory(first).toPath().resolve("cli.log"));
            String secondLog = Files.readString(
                    LogPaths.transcriptDirectory(second).toPath().resolve("cli.log"));
            assertTrue(firstLog.contains("first-only"));
            assertTrue(firstLog.contains("first-resumed"));
            assertTrue(firstLog.contains("event=resume"));
            assertFalse(firstLog.contains("second-only"));
            assertTrue(secondLog.contains("second-only"));
            assertFalse(secondLog.contains("first-only"));
        } finally {
            System.setErr(previousErr);
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
            if (previousTranscript == null) {
                System.clearProperty(TranscriptLogScope.TRANSCRIPT_ID_PROPERTY);
            } else {
                System.setProperty(
                        TranscriptLogScope.TRANSCRIPT_ID_PROPERTY, previousTranscript);
            }
            terminal.close();
        }
    }

    @Test
    void nestedScopesTeeTheirsOwnLogAndRestoreTheParentSink(@TempDir Path tempDir)
            throws Exception {
        // The in-process resume path (ResumeTool -> picocli -> ChatCommand) opens a
        // second transcript scope while the hosting chat still holds its own. The
        // nested scope must capture stderr without failing the child session, and
        // closing it must hand the stream back to the parent transcript.
        String previousHome = System.getProperty("user.home");
        String previousTranscript = System.getProperty(
                TranscriptLogScope.TRANSCRIPT_ID_PROPERTY);
        PrintStream previousErr = System.err;
        PrintStream terminal = new PrintStream(
                new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        String parent = UUID.randomUUID().toString();
        String child = UUID.randomUUID().toString();
        try {
            System.setProperty("user.home", tempDir.toString());
            System.setErr(terminal);

            try (TranscriptLogScope parentScope =
                         TranscriptLogScope.open(parent, tempDir, false)) {
                System.err.println("parent-before-nested");
                try (TranscriptLogScope childScope =
                             TranscriptLogScope.open(child, tempDir, true)) {
                    System.err.println("child-only");
                    assertEquals(child, TranscriptLogScope.currentTranscriptId());
                    assertEquals(child, childScope.logFile().getParent().getFileName().toString());
                }
                assertEquals(parent, TranscriptLogScope.currentTranscriptId());
                System.err.println("parent-after-nested");
            }

            String parentLog = Files.readString(
                    LogPaths.transcriptDirectory(parent).toPath().resolve("cli.log"));
            String childLog = Files.readString(
                    LogPaths.transcriptDirectory(child).toPath().resolve("cli.log"));
            assertTrue(parentLog.contains("parent-before-nested"));
            assertTrue(parentLog.contains("parent-after-nested"));
            assertFalse(parentLog.contains("child-only"));
            assertTrue(childLog.contains("child-only"));
            assertTrue(childLog.contains("event=resume"));
            assertFalse(childLog.contains("parent-after-nested"));
        } finally {
            System.setErr(previousErr);
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
            if (previousTranscript == null) {
                System.clearProperty(TranscriptLogScope.TRANSCRIPT_ID_PROPERTY);
            } else {
                System.setProperty(
                        TranscriptLogScope.TRANSCRIPT_ID_PROPERTY, previousTranscript);
            }
            terminal.close();
        }
    }

    @Test
    void failedClearSwitchKeepsTheOriginalTranscriptSink(@TempDir Path tempDir)
            throws Exception {
        String previousHome = System.getProperty("user.home");
        String previousTranscript = System.getProperty(
                TranscriptLogScope.TRANSCRIPT_ID_PROPERTY);
        PrintStream previousErr = System.err;
        PrintStream terminal = new PrintStream(
                new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        String first = UUID.randomUUID().toString();
        String blocked = UUID.randomUUID().toString();
        try {
            System.setProperty("user.home", tempDir.toString());
            System.setErr(terminal);
            try (TranscriptLogScope scope =
                         TranscriptLogScope.open(first, tempDir, false)) {
                Path blockedDirectory = LogPaths.transcriptDirectory(blocked).toPath();
                Files.writeString(blockedDirectory, "not a directory");

                assertThrows(java.io.IOException.class,
                        () -> scope.switchTo(blocked, tempDir, false));
                assertEquals(first, TranscriptLogScope.currentTranscriptId());
                System.err.println("still-visible-after-failed-switch");
            }
            String firstLog = Files.readString(
                    LogPaths.transcriptDirectory(first).toPath().resolve("cli.log"));
            assertTrue(firstLog.contains("still-visible-after-failed-switch"));
        } finally {
            System.setErr(previousErr);
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
            if (previousTranscript == null) {
                System.clearProperty(TranscriptLogScope.TRANSCRIPT_ID_PROPERTY);
            } else {
                System.setProperty(
                        TranscriptLogScope.TRANSCRIPT_ID_PROPERTY, previousTranscript);
            }
            terminal.close();
        }
    }
}
