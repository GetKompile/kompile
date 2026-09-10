package ai.kompile.cli.main.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("SYSTEM_ERR")
class ChatSessionContextTest {
    @Test
    void concurrentCapturesKeepBothOwnersAndRestoreReusedWorkers(@TempDir Path home) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        ChatUiSession legacy = ChatUiSession.current();
        try (Environment env = new Environment(home);
             ChatUiSession a = new ChatUiSession(); ChatUiSession b = new ChatUiSession();
             TranscriptLogScope logA = TranscriptLogScope.openIsolated("a", home, false);
             TranscriptLogScope logB = TranscriptLogScope.openIsolated("b", home, false)) {
            List<String> outA = new CopyOnWriteArrayList<>(), outB = new CopyOnWriteArrayList<>();
            ChatSessionContext ca = context(a, logA, outA), cb = context(b, logB, outB);
            var barrier = new CyclicBarrier(2);
            for (int round = 0; round < 3; round++) {
                var first = pool.submit(ca.wrapCallable(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    ChatCompleter.printAbove("a-ui");
                    System.err.println("a-diagnostic");
                    assertSame(a, ChatUiSession.current());
                    return TranscriptLogScope.currentTranscriptId();
                }));
                var second = pool.submit(cb.wrapCallable(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    ChatCompleter.printAbove("b-ui");
                    System.err.println("b-diagnostic");
                    assertSame(b, ChatUiSession.current());
                    return TranscriptLogScope.currentTranscriptId();
                }));
                assertEquals("a", first.get(5, TimeUnit.SECONDS));
                assertEquals("b", second.get(5, TimeUnit.SECONDS));
            }
            var clean = (java.util.concurrent.Callable<Void>) () -> {
                barrier.await(5, TimeUnit.SECONDS);
                assertSame(legacy, ChatUiSession.current());
                assertNull(TranscriptLogScope.currentExplicitScope());
                return null;
            };
            var firstClean = pool.submit(clean);
            var secondClean = pool.submit(clean);
            firstClean.get(5, TimeUnit.SECONDS);
            secondClean.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("a-ui", "a-ui", "a-ui"), outA);
            assertEquals(List.of("b-ui", "b-ui", "b-ui"), outB);
            assertTrue(Files.readString(logA.logFile()).contains("a-diagnostic"));
            assertFalse(Files.readString(logA.logFile()).contains("b-diagnostic"));
            assertTrue(Files.readString(logB.logFile()).contains("b-diagnostic"));
            assertFalse(Files.readString(logB.logFile()).contains("a-diagnostic"));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void allWrappersRestoreBothBindingsAfterFailure(@TempDir Path home) throws Exception {
        try (Environment env = new Environment(home);
             ChatUiSession a = new ChatUiSession(); ChatUiSession b = new ChatUiSession();
             TranscriptLogScope logA = TranscriptLogScope.openIsolated("a", home, false);
             TranscriptLogScope logB = TranscriptLogScope.openIsolated("b", home, false)) {
            ChatSessionContext cb = context(b, logB, new CopyOnWriteArrayList<>());
            try (var ui = a.bind(); var log = logA.bind()) {
                Runnable verifyB = () -> {
                    assertSame(b, ChatUiSession.current());
                    assertSame(logB, TranscriptLogScope.currentExplicitScope());
                };
                Error failure = new AssertionError("expected");
                assertSame(failure, assertThrows(AssertionError.class, cb.wrap(() -> {
                    verifyB.run();
                    throw failure;
                })::run));
                assertRestored(a, logA);
                IOException checked = new IOException("expected");
                assertSame(checked, assertThrows(IOException.class, () -> cb.wrapCallable(() -> {
                    verifyB.run();
                    throw checked;
                }).call()));
                assertRestored(a, logA);
                assertThrows(IllegalArgumentException.class, () -> cb.<String>wrapConsumer(value -> {
                    verifyB.run();
                    assertEquals("value", value);
                    throw new IllegalArgumentException("expected");
                }).accept("value"));
                assertRestored(a, logA);
                assertEquals("ok", cb.wrapCallable(() -> "ok").call());
                assertRestored(a, logA);
            }
        }
    }

    @Test
    void unboundLogCaptureRemainsDynamicLegacyAndMasksWorkerExplicitOwner(@TempDir Path home) throws Exception {
        try (Environment env = new Environment(home);
             TranscriptLogScope parent = TranscriptLogScope.open("parent", home, false);
             ChatUiSession sibling = new ChatUiSession();
             TranscriptLogScope siblingLog = TranscriptLogScope.openIsolated("sibling", home, false)) {
            ChatUiSession legacy = ChatUiSession.current();
            ChatSessionContext captured = ChatSessionContext.current();
            assertNull(TranscriptLogScope.currentExplicitScope());
            try (TranscriptLogScope child = TranscriptLogScope.open("child", home, false);
                 var ui = sibling.bind(); var log = siblingLog.bind()) {
                captured.wrap(() -> {
                    assertSame(legacy, ChatUiSession.current());
                    assertNull(TranscriptLogScope.currentExplicitScope());
                    assertEquals("child", TranscriptLogScope.currentTranscriptId());
                    System.err.println("dynamic-child-only");
                }).run();
                assertRestored(sibling, siblingLog);
                assertTrue(Files.readString(child.logFile()).contains("dynamic-child-only"));
                assertFalse(Files.readString(parent.logFile()).contains("dynamic-child-only"));
                assertFalse(Files.readString(siblingLog.logFile()).contains("dynamic-child-only"));
            }
            assertEquals("parent", captured.wrapCallable(TranscriptLogScope::currentTranscriptId).call());
        }
    }

    @Test
    void closedCapturesNeverClaimSiblingAndFollowOnlyTheirOwnClear(@TempDir Path home) throws Exception {
        try (Environment env = new Environment(home);
             ChatUiSession a = new ChatUiSession(); ChatUiSession b = new ChatUiSession();
             TranscriptLogScope logA = TranscriptLogScope.openIsolated("a", home, false);
             TranscriptLogScope logB = TranscriptLogScope.openIsolated("b", home, false)) {
            List<String> outA = new CopyOnWriteArrayList<>(), outB = new CopyOnWriteArrayList<>();
            ChatSessionContext ca = context(a, logA, outA);
            context(b, logB, outB);
            logA.switchTo("a-fresh", home, false);
            assertEquals("a-fresh", ca.wrapCallable(TranscriptLogScope::currentTranscriptId).call());
            a.close();
            logA.close();
            try (var ui = b.bind(); var log = logB.bind()) {
                ca.wrap(() -> {
                    assertSame(a, ChatUiSession.current());
                    assertNull(TranscriptLogScope.currentTranscriptId());
                    ChatCompleter.printAbove("late-ui");
                    System.err.println("late-log");
                }).run();
                assertRestored(b, logB);
            }
            assertTrue(outA.isEmpty());
            assertTrue(outB.isEmpty());
            assertFalse(Files.readString(logB.logFile()).contains("late-log"));
        }
    }

    private static void assertRestored(ChatUiSession ui, TranscriptLogScope log) {
        assertSame(ui, ChatUiSession.current());
        assertSame(log, TranscriptLogScope.currentExplicitScope());
    }

    private static ChatSessionContext context(ChatUiSession ui, TranscriptLogScope log, List<String> out) {
        try (var u = ui.bind(); var l = log.bind()) {
            ChatCompleter.setContentOutput(out::add);
            return ChatSessionContext.current();
        }
    }

    private static final class Environment implements AutoCloseable {
        private final String home = System.getProperty("user.home");
        private final String transcript = System.getProperty(TranscriptLogScope.TRANSCRIPT_ID_PROPERTY);
        private final PrintStream err = System.err;
        private final PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

        Environment(Path path) {
            System.setProperty("user.home", path.toString());
            System.clearProperty(TranscriptLogScope.TRANSCRIPT_ID_PROPERTY);
            System.setErr(sink);
        }

        @Override public void close() {
            System.setErr(err);
            restore("user.home", home);
            restore(TranscriptLogScope.TRANSCRIPT_ID_PROPERTY, transcript);
            sink.close();
        }

        private static void restore(String name, String value) {
            if (value == null) System.clearProperty(name);
            else System.setProperty(name, value);
        }
    }
}
