package ai.kompile.cli.main.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("SYSTEM_OUT")
@ResourceLock("SYSTEM_ERR")
class SessionOutputRouterTest {
    @Test
    void warningsUseOwningHeaderWithoutTouchingChatOutput(@TempDir Path home) throws Exception {
        try (Environment env = new Environment(home);
             SessionOutputRouter router = SessionOutputRouter.install();
             ChatUiSession a = new ChatUiSession(); ChatUiSession b = new ChatUiSession()) {
            List<String> contentA = new CopyOnWriteArrayList<>(), contentB = new CopyOnWriteArrayList<>();
            List<String> alertsA = new CopyOnWriteArrayList<>(), alertsB = new CopyOnWriteArrayList<>();
            ChatSessionContext ca = context(a, contentA), cb = context(b, contentB);
            ca.wrap(() -> ChatCompleter.setAlertOutput(alertsA::add)).run();
            cb.wrap(() -> ChatCompleter.setAlertOutput(alertsB::add)).run();
            ca.wrap(() -> {
                router.sessionErr().println("OpenAI authentication warning");
                ChatCompleter.detachTerminalRef(null);
                router.sessionErr().println("OpenAI connection lost");
                System.out.println("model response");
            }).run();
            cb.wrap(() -> router.sessionErr().println("another warning")).run();
            assertEquals(List.of("OpenAI authentication warning", "OpenAI connection lost"), alertsA);
            assertEquals(List.of("another warning"), alertsB);
            assertEquals(List.of("model response"), contentA);
            assertTrue(contentB.isEmpty());
            a.close();
            ca.wrap(() -> router.sessionErr().println("late warning")).run();
            assertEquals(2, alertsA.size());
            assertEquals("", env.outText());
            assertEquals("", env.errText());
        }
    }

    @Test
    void optInPreservesLegacyFallthroughAndExistingStderrDispatcher(@TempDir Path home) throws Exception {
        try (Environment env = new Environment(home); ChatUiSession ui = new ChatUiSession()) {
            try (var ignored = ui.bind()) { System.out.println("not-installed"); }
            assertTrue(env.outText().contains("not-installed"));
            try (TranscriptLogScope outer = TranscriptLogScope.open("outer", home, false)) {
                PrintStream dispatcher = System.err;
                try (SessionOutputRouter router = SessionOutputRouter.install()) {
                    assertSame(dispatcher, System.err);
                    assertThrows(IllegalStateException.class, SessionOutputRouter::install);
                    System.out.println("legacy-out");
                    System.err.println("legacy-logged");
                    router.sessionErr().println("echo-only");
                    assertTrue(env.outText().contains("legacy-out"));
                    assertTrue(env.errText().contains("legacy-logged"));
                    assertTrue(env.errText().contains("echo-only"));
                    String log = Files.readString(outer.logFile());
                    assertTrue(log.contains("legacy-logged"));
                    assertFalse(log.contains("echo-only"));
                }
                assertSame(dispatcher, System.err);
                assertSame(env.out, System.out);
            }
            assertSame(env.err, System.err);
        }
    }

    @Test
    void splitUtf8PartialFlushAndInterleavedOwnersAndStreamsStaySeparate(@TempDir Path home) {
        try (Environment env = new Environment(home);
             SessionOutputRouter router = SessionOutputRouter.install();
             ChatUiSession a = new ChatUiSession(); ChatUiSession b = new ChatUiSession()) {
            List<String> outA = new CopyOnWriteArrayList<>(), outB = new CopyOnWriteArrayList<>();
            ChatSessionContext ca = context(a, outA), cb = context(b, outB);
            byte[] euro = "€".getBytes(StandardCharsets.UTF_8);
            ca.wrap(() -> {
                System.out.print("A-");
                System.out.write(euro, 0, 1);
            }).run();
            cb.wrap(() -> System.out.print("B-")).run();
            ca.wrap(() -> {
                router.sessionErr().print("diagnostic-");
                System.out.flush(); // emit A-, retain incomplete euro
                assertEquals(List.of("A-"), outA);
                System.out.write(euro, 1, 1);
                System.out.flush(); // still incomplete, no replacement character
                assertEquals(List.of("A-"), outA);
            }).run();
            cb.wrap(() -> System.out.println("done")).run();
            ca.wrap(() -> {
                System.out.write(euro, 2, 1);
                System.out.println("-done");
                router.sessionErr().println("done");
                System.out.print("partial");
                System.out.flush();
                System.out.println(); // delimiter after fragment is not an extra blank line
                System.out.println(); // genuine blank line
                System.out.print("last");
            }).run();
            assertEquals(List.of("A-", "€-done", "diagnostic-done", "partial", ""), outA);
            assertEquals(List.of("B-done"), outB);
            router.close(); // flush remaining complete fragment against A, not caller/legacy
            assertEquals(List.of("A-", "€-done", "diagnostic-done", "partial", "", "last"), outA);
            assertEquals("", env.outText());
            assertEquals("", env.errText());
        }
    }

    @Test
    void invisibleConcurrentSessionsRetainOutputAndIndependentDiagnosticFiles(@TempDir Path home) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try (Environment env = new Environment(home);
             TranscriptLogScope outer = TranscriptLogScope.open("outer", home, false);
             SessionOutputRouter router = SessionOutputRouter.install();
             ChatUiSession a = new ChatUiSession(); ChatUiSession b = new ChatUiSession();
             TranscriptLogScope logA = TranscriptLogScope.openIsolated("a", home, false, router.sessionErr());
             TranscriptLogScope logB = TranscriptLogScope.openIsolated("b", home, false, router.sessionErr())) {
            List<String> outA = new CopyOnWriteArrayList<>(), outB = new CopyOnWriteArrayList<>();
            ChatSessionContext ca, cb;
            try (var ignored = logA.bind()) { ca = context(a, outA); }
            try (var ignored = logB.bind()) { cb = context(b, outB); }
            var barrier = new CyclicBarrier(2);
            var fa = pool.submit(ca.wrapCallable(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                System.out.println("a-stdout");
                System.err.println("a-stderr");
                return null;
            }));
            var fb = pool.submit(cb.wrapCallable(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                System.out.println("b-stdout");
                System.err.println("b-stderr");
                return null;
            }));
            fa.get(5, TimeUnit.SECONDS);
            fb.get(5, TimeUnit.SECONDS);
            // Neither session has an attached terminal/reader; both remain retained.
            assertEquals(List.of("a-stdout", "a-stderr"), outA);
            assertEquals(List.of("b-stdout", "b-stderr"), outB);
            String la = Files.readString(logA.logFile()), lb = Files.readString(logB.logFile());
            assertEquals(1, la.lines().filter("a-stderr"::equals).count());
            assertEquals(1, lb.lines().filter("b-stderr"::equals).count());
            assertFalse(la.contains("b-stderr"));
            assertFalse(lb.contains("a-stderr"));
            assertFalse(la.contains("a-stdout"));
            assertFalse(Files.readString(outer.logFile()).contains("-stderr"));
            assertEquals("", env.outText());
            assertEquals("", env.errText());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void sinklessClosedOwnersAndLateWritesNeverLeakToSiblingOrPhysicalTerminal(@TempDir Path home) throws Exception {
        try (Environment env = new Environment(home);
             SessionOutputRouter router = SessionOutputRouter.install();
             ChatUiSession a = new ChatUiSession(); ChatUiSession b = new ChatUiSession();
             TranscriptLogScope logA = TranscriptLogScope.openIsolated("a", home, false, router.sessionErr());
             TranscriptLogScope logB = TranscriptLogScope.openIsolated("b", home, false, router.sessionErr())) {
            List<String> outB = new CopyOnWriteArrayList<>();
            context(b, outB);
            ChatSessionContext ca;
            try (var ui = a.bind(); var log = logA.bind()) {
                ca = ChatSessionContext.current();
                System.out.println("sinkless");
                System.err.println("sinkless-diagnostic");
            }
            assertTrue(Files.readString(logA.logFile()).contains("sinkless-diagnostic"));
            PrintStream oldOut = System.out, oldErr = System.err;
            Runnable late = ca.wrap(() -> {
                assertNull(TranscriptLogScope.currentTranscriptId());
                System.out.println("late-out");
                System.err.println("late-err");
                oldOut.println("retained-out");
                oldErr.println("retained-err");
            });
            a.close();
            logA.close();
            try (var ui = b.bind(); var log = logB.bind()) { late.run(); }
            assertTrue(outB.isEmpty());
            assertFalse(Files.readString(logB.logFile()).contains("late-err"));
            logB.close(); // host lease must still route closed A, despite no live log files
            late.run();
            assertSame(oldErr, System.err);
            assertEquals("", env.outText());
            assertEquals("", env.errText());
            router.close();
            assertSame(env.out, System.out);
            assertSame(env.err, System.err);
            try (SessionOutputRouter next = SessionOutputRouter.install(); var ui = b.bind()) {
                ca.wrap(() -> {
                    oldOut.println("old-router-out");
                    oldErr.println("old-dispatcher-err");
                }).run();
                assertTrue(outB.isEmpty());
            }
            assertEquals("", env.outText());
            assertEquals("", env.errText());
        }
    }

    @Test
    void logCloseFlushesOwnedFragmentOutsideTheDispatchLock(@TempDir Path home) throws Exception {
        try (Environment env = new Environment(home);
             SessionOutputRouter router = SessionOutputRouter.install();
             ChatUiSession ui = new ChatUiSession();
             TranscriptLogScope log = TranscriptLogScope.openIsolated("owner", home, false, router.sessionErr());
             var u = ui.bind(); var l = log.bind()) {
            List<String> output = new CopyOnWriteArrayList<>();
            ChatCompleter.setContentOutput(text -> {
                assertFalse(TranscriptLogScope.isDispatchLockedByCurrentThread());
                assertNull(TranscriptLogScope.currentTranscriptId());
                output.add(text);
            });
            router.sessionErr().print("tail");
            assertTrue(output.isEmpty());
            log.close();
            assertEquals(List.of("tail"), output);
            assertEquals("", env.errText());
        }
    }

    @Test
    void closedLogKeepsExplicitEchoUntilHostCloses(@TempDir Path home) throws Exception {
        try (Environment env = new Environment(home);
             SessionOutputRouter router = SessionOutputRouter.install();
             ChatUiSession ui = new ChatUiSession();
             TranscriptLogScope log = TranscriptLogScope.openIsolated("owner", home, false, router.sessionErr())) {
            List<String> output = new CopyOnWriteArrayList<>();
            ChatSessionContext captured;
            try (var ignored = log.bind()) { captured = context(ui, output); }
            log.close();
            captured.wrap(() -> {
                assertNull(TranscriptLogScope.currentTranscriptId());
                System.err.println("echo-after-file-close");
            }).run();
            assertEquals(List.of("echo-after-file-close"), output);
            assertFalse(Files.readString(log.logFile()).contains("echo-after-file-close"));
            assertEquals("", env.errText());
        }
    }

    @Test
    void formattingAppendAndFourByteUtf8UseTheSameOwnerBuffer(@TempDir Path home) {
        try (Environment env = new Environment(home);
             SessionOutputRouter router = SessionOutputRouter.install();
             ChatUiSession ui = new ChatUiSession()) {
            List<String> output = new CopyOnWriteArrayList<>();
            context(ui, output).wrap(() -> {
                System.out.printf(java.util.Locale.ROOT, "%s:%d", "value", 7);
                System.out.append('-').append("xyz", 1, 3);
                System.out.println(new char[]{'!'});
                byte[] face = "😀".getBytes(StandardCharsets.UTF_8);
                for (byte value : face) {
                    router.sessionErr().write(value);
                    router.sessionErr().flush();
                }
                router.sessionErr().println();
                System.out.write(0xe2); // incomplete sequence: flush must not corrupt it
                System.out.flush();
            }).run();
            assertEquals(List.of("value:7-yz!", "😀"), output);
            router.close(); // EOF replaces a genuinely truncated sequence
            assertEquals(List.of("value:7-yz!", "😀", "\ufffd"), output);
            assertEquals("", env.outText());
            assertEquals("", env.errText());
        }
    }

    @Test
    void directDiagnosticSinkDoesNotLockOutConcurrentLogClose(@TempDir Path home) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (Environment env = new Environment(home);
             SessionOutputRouter router = SessionOutputRouter.install();
             ChatUiSession ui = new ChatUiSession();
             TranscriptLogScope log = TranscriptLogScope.openIsolated("owner", home, false, router.sessionErr())) {
            ChatSessionContext captured;
            try (var u = ui.bind(); var l = log.bind()) {
                ChatCompleter.setContentOutput(text -> {
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                    assertNull(TranscriptLogScope.currentTranscriptId());
                });
                captured = ChatSessionContext.current();
            }
            var writer = pool.submit(captured.wrap(() -> router.sessionErr().println("direct")));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                pool.submit(log::close).get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            writer.get(5, TimeUnit.SECONDS);
            assertEquals("", env.errText());
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void temporaryGlobalInterceptorsAreNotOverwrittenOnClose(@TempDir Path home) throws Exception {
        try (Environment env = new Environment(home);
             SessionOutputRouter router = SessionOutputRouter.install();
             PrintStream temporaryOut = new PrintStream(new ByteArrayOutputStream());
             PrintStream temporaryErr = new PrintStream(new ByteArrayOutputStream())) {
            PrintStream savedOut = System.out, savedErr = System.err;
            System.setOut(temporaryOut);
            System.setErr(temporaryErr);
            router.close();
            router.close();
            assertSame(temporaryOut, System.out);
            assertSame(temporaryErr, System.err);
            // Model KompileTui's finally restoration, after host retirement.
            System.setOut(savedOut);
            System.setErr(savedErr);
            System.out.println("legacy-restored-out");
            System.err.println("legacy-restored-err");
            assertTrue(env.outText().contains("legacy-restored-out"));
            assertTrue(env.errText().contains("legacy-restored-err"));
        }
    }

    @Test
    void competingDiagnosticWriterDoesNotWaitForStdoutSinkAndRecursiveEchoIsSuppressed(@TempDir Path home)
            throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (Environment env = new Environment(home);
             SessionOutputRouter router = SessionOutputRouter.install();
             ChatUiSession a = new ChatUiSession(); ChatUiSession b = new ChatUiSession();
             TranscriptLogScope logB = TranscriptLogScope.openIsolated("b", home, false, router.sessionErr())) {
            List<String> seen = new CopyOnWriteArrayList<>();
            ChatSessionContext ca, cb;
            try (var ui = a.bind()) {
                ChatCompleter.setContentOutput(text -> {
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                    System.out.println("recursive-out");
                    router.sessionErr().println("recursive-err");
                    seen.add("a:" + text);
                });
                ca = ChatSessionContext.current();
            }
            try (var ui = b.bind(); var log = logB.bind()) {
                ChatCompleter.setContentOutput(text -> {
                    assertFalse(TranscriptLogScope.isDispatchLockedByCurrentThread());
                    assertSame(b, ChatUiSession.current());
                    assertEquals("b", TranscriptLogScope.currentTranscriptId());
                    seen.add("b:" + text);
                });
                cb = ChatSessionContext.current();
            }
            var fa = pool.submit(ca.wrap(() -> System.out.println("one")));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var fb = pool.submit(cb.wrap(() -> System.err.println("two")));
                fb.get(5, TimeUnit.SECONDS); // must return while A's retained sink is paused
            } finally {
                release.countDown();
            }
            fa.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("a:one", "b:two"), seen);
            assertEquals("", env.outText());
            assertEquals("", env.errText());
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static ChatSessionContext context(ChatUiSession ui, List<String> out) {
        try (var ignored = ui.bind()) {
            ChatCompleter.setContentOutput(out::add);
            return ChatSessionContext.current();
        }
    }

    private static final class Environment implements AutoCloseable {
        private final String home = System.getProperty("user.home");
        private final String transcript = System.getProperty(TranscriptLogScope.TRANSCRIPT_ID_PROPERTY);
        private final PrintStream previousOut = System.out, previousErr = System.err;
        private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream(), errBytes = new ByteArrayOutputStream();
        private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        Environment(Path path) {
            System.setProperty("user.home", path.toString());
            System.clearProperty(TranscriptLogScope.TRANSCRIPT_ID_PROPERTY);
            System.setOut(out);
            System.setErr(err);
        }

        String outText() { return outBytes.toString(StandardCharsets.UTF_8); }
        String errText() { return errBytes.toString(StandardCharsets.UTF_8); }

        @Override public void close() {
            System.setOut(previousOut);
            System.setErr(previousErr);
            restore("user.home", home);
            restore(TranscriptLogScope.TRANSCRIPT_ID_PROPERTY, transcript);
            out.close();
            err.close();
        }

        private static void restore(String name, String value) {
            if (value == null) System.clearProperty(name);
            else System.setProperty(name, value);
        }
    }
}
