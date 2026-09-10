package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tui.StatusBar;
import ai.kompile.utils.AnsiConstants;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Widget;
import org.jline.terminal.Size;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ChatUiSessionTest {
    @Test
    void interruptionExpiryPreservesNewerInterruptionsAndLiveActivity() {
        try (ChatUiSession session = new ChatUiSession(); var ignored = session.bind()) {
            List<Runnable> timers = new ArrayList<>();
            ChatCompleter.markInterrupted(timers::add);
            ChatCompleter.markInterrupted(timers::add);
            timers.get(0).run();
            assertTrue(ChatCompleter.isActivityTerminal());
            timers.get(1).run();
            assertNull(ChatCompleter.getActivity());
            assertFalse(ChatCompleter.isActivityTerminal());
            ChatCompleter.markInterrupted(timers::add);
            ChatCompleter.setActivity("running new turn");
            timers.get(2).run();
            assertEquals("running new turn", ChatCompleter.getActivity());
        }
    }

    @Test
    void ephemeralNoticesUseSessionAlertLaneInsteadOfTranscript() {
        try (ChatUiSession session = new ChatUiSession(); var ignored = session.bind()) {
            List<String> transcript = new ArrayList<>();
            List<String> notices = new ArrayList<>();
            ChatCompleter.setContentOutput(transcript::add);
            ChatCompleter.setAlertOutput(notices::add);
            ChatCompleter.showNotice("Background task finished; waking agent");
            assertEquals(List.of("Background task finished; waking agent"), notices);
            assertTrue(transcript.isEmpty());
            ChatCompleter.printAbove("durable result");
            assertEquals(List.of("durable result"), transcript);
            ChatCompleter.setAlertOutput(null);
            ChatCompleter.showNotice("plain terminal fallback");
            assertEquals(List.of("durable result", "plain terminal fallback"), transcript);
        }
    }

    @Test
    void concurrentCallbacksKeepOutputActivityBlocksAndQueueWithTheirOwner() throws Exception {
        ChatUiSession legacy = ChatUiSession.current();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (ChatUiSession a = new ChatUiSession(); ChatUiSession b = new ChatUiSession()) {
            List<String> outA = new CopyOnWriteArrayList<>(), outB = new CopyOnWriteArrayList<>();
            configure(a, outA, "a");
            configure(b, outB, "b");
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Void> first = pool.submit(a.capture((Callable<Void>) () -> {
                barrier.await(5, TimeUnit.SECONDS);
                emit("a");
                assertEquals(List.of("a"), ChatUiSession.current().queueSupplier.get());
                ChatCompleter.setTemporaryWindowActive(true);
                return null;
            }));
            Future<Void> second = pool.submit(b.capture((Callable<Void>) () -> {
                barrier.await(5, TimeUnit.SECONDS);
                emit("b");
                assertEquals(List.of("b"), ChatUiSession.current().queueSupplier.get());
                assertFalse(ChatCompleter.isTemporaryWindowActive());
                return null;
            }));
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("a", "activity:a", "block:a"), outA);
            assertEquals(List.of("b", "activity:b", "block:b"), outB);
            assertEquals("a", a.getActivity());
            assertEquals("b", b.getActivity());
            assertSame(legacy, pool.submit(ChatUiSession::current).get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertSame(legacy, ChatUiSession.current());
    }

    private static void configure(ChatUiSession session, List<String> out, String name) {
        try (var ignored = session.bind()) {
            ChatCompleter.setContentOutput(out::add);
            ChatCompleter.setActivityListener(value -> out.add("activity:" + value));
            ChatCompleter.setTranscriptBlockOutput((key, value) -> out.add(key + ":" + value));
            ChatCompleter.setQueueSupplier(() -> List.of(name));
        }
    }

    private static void emit(String name) {
        ChatCompleter.printAbove(name);
        ChatCompleter.setActivity(name);
        assertTrue(ChatCompleter.upsertTranscriptBlock("block", name));
    }

    @Test
    void nestedCapturesRestoreBindingsAfterExceptionsAndDoNotInheritToThreads() throws Exception {
        ChatUiSession legacy = ChatUiSession.current();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (ChatUiSession a = new ChatUiSession(); ChatUiSession b = new ChatUiSession();
             var ignored = a.bind()) {
            Runnable failure = b.capture((Runnable) () -> {
                assertSame(b, ChatUiSession.current());
                throw new IllegalArgumentException("expected");
            });
            assertThrows(IllegalArgumentException.class, failure::run);
            assertSame(a, ChatUiSession.current());
            assertSame(b, b.capture((Callable<ChatUiSession>) ChatUiSession::current).call());
            Consumer<String> consumer = b.captureConsumer(value -> {
                assertSame(b, ChatUiSession.current());
                ChatCompleter.setActivity(value);
            });
            consumer.accept("b activity");
            assertEquals("b activity", b.getActivity());
            assertSame(a, ChatUiSession.current());
            assertSame(legacy, pool.submit(ChatUiSession::current).get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertSame(legacy, ChatUiSession.current());
    }

    @Test
    void bindingsRejectOutOfOrderAndForeignThreadClose() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (ChatUiSession a = new ChatUiSession(); ChatUiSession b = new ChatUiSession()) {
            try (var outer = a.bind(); var inner = b.bind()) {
                assertThrows(IllegalStateException.class, outer::close);
                pool.submit(() -> assertThrows(IllegalStateException.class, inner::close))
                        .get(5, TimeUnit.SECONDS);
                assertSame(b, ChatUiSession.current());
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void detachRetainsOutputAndCloseCannotRedirectStaleCallbacksToSibling() throws Exception {
        try (ChatUiSession a = new ChatUiSession(); ChatUiSession b = new ChatUiSession();
             var termA = terminal(); var termB = terminal()) {
            LineReader readerA = LineReaderBuilder.builder().terminal(termA).build();
            LineReader readerB = LineReaderBuilder.builder().terminal(termB).build();
            List<String> outA = new ArrayList<>(), outB = new ArrayList<>();
            configure(a, outA, "a");
            configure(b, outB, "b");
            Runnable late = a.capture((Runnable) () -> {
                ChatCompleter.setContentOutput(outB::add);
                ChatCompleter.printAbove("late");
                ChatCompleter.setActivity("late");
                assertFalse(ChatCompleter.upsertTranscriptBlock("late", "late"));
            });
            try (var ignored = a.bind()) {
                ChatCompleter.setTerminalRef(readerA, termA);
                ChatCompleter.clearTerminalRef(readerB);
                assertTrue(ChatCompleter.hasLineReader());
                ChatCompleter.detachTerminalRef(readerA);
                assertFalse(ChatCompleter.hasLineReader());
                emit("detached");
            }
            try (var ignored = b.bind()) {
                ChatCompleter.setTerminalRef(readerB, termB);
                a.close();
                late.run();
                assertSame(b, ChatUiSession.current());
                assertTrue(ChatCompleter.hasLineReader());
                emit("live");
            }
            assertEquals(List.of("detached", "activity:detached", "block:detached"), outA);
            assertEquals(List.of("live", "activity:live", "block:live"), outB);
            assertThrows(IllegalStateException.class, a::bind);
            // Disposal does not close caller-owned terminals.
            termA.setSize(new Size(100, 30));
            assertEquals(100, termA.getWidth());
        }
    }

    @Test
    void isolatedSinklessOrFailedOutputNeverFallsBackToProcessStdout() {
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(bytes);
             ChatUiSession session = new ChatUiSession(); var ignored = session.bind()) {
            System.setOut(capture);
            ChatCompleter.printAbove("sinkless");
            ChatCompleter.setContentOutput(value -> { throw new IllegalStateException("closed sink"); });
            ChatCompleter.printAbove("failed sink");
            assertEquals("", bytes.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(original);
        }
    }

    @Test
    void installedWidgetsUseTheirReaderOwnerEvenWhenAnotherSessionIsBound() throws Exception {
        try (ChatUiSession a = new ChatUiSession(); ChatUiSession b = new ChatUiSession();
             var termA = terminal(); var termB = terminal()) {
            LineReader readerA = LineReaderBuilder.builder().terminal(termA)
                    .completer(new ChatCompleter(List::of)).build();
            LineReader readerB = LineReaderBuilder.builder().terminal(termB)
                    .completer(new ChatCompleter(List::of)).build();
            List<String> completions = new ArrayList<>();
            for (ChatUiSession owner : List.of(a, b)) {
                LineReader reader = owner == a ? readerA : readerB;
                try (var ignored = owner.bind()) {
                    ChatCompleter.setTerminalRef(reader, reader.getTerminal());
                    ChatCompleter.setCompletionDisplay(items -> {
                        if (!items.isEmpty()) completions.add(owner == a ? "a" : "b");
                        return true;
                    });
                    reader.getWidgets().put(LineReader.BACKWARD_DELETE_CHAR, () -> true);
                    ChatCompleter.enableAutoTrigger(reader);
                    reader.getBuffer().write("/cl");
                    ChatCompleter.markInterrupted();
                }
            }
            Widget widgetA = readerA.getWidgets().get(LineReader.BACKWARD_DELETE_CHAR);
            try (var ignored = b.bind()) {
                assertTrue(widgetA.apply());
                assertNull(a.getActivity());
                assertTrue(b.isActivityTerminal());
                assertEquals(List.of("a"), completions);
                assertSame(b, ChatUiSession.current());
                a.close();
                assertFalse(widgetA.apply());
                assertTrue(readerB.getWidgets().get(LineReader.BACKWARD_DELETE_CHAR).apply());
                assertEquals(List.of("a", "b"), completions);
            }
        }
    }

    @Test
    void statusBarKeepsConstructionOwnerOnUnboundWorker() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        BackgroundProcessManager processes = new BackgroundProcessManager("ui-session-status-test");
        try (ChatUiSession a = new ChatUiSession(); ChatUiSession b = new ChatUiSession()) {
            StatusBar bar;
            try (var ignored = a.bind()) {
                ChatCompleter.setActivity("Working: Alpha");
                bar = new StatusBar(new BackgroundTaskManager(), processes, null, new TerminalRenderer(true));
            }
            try (var ignored = b.bind()) {
                ChatCompleter.setActivity("Working: Beta");
                String text = AnsiConstants.stripAnsi(bar.render(40, 120));
                assertTrue(text.contains("Alpha"), text);
                assertFalse(text.contains("Beta"), text);
                String worker = pool.submit(() -> AnsiConstants.stripAnsi(bar.render(40, 120)))
                        .get(5, TimeUnit.SECONDS);
                assertTrue(worker.contains("Alpha"), worker);
                assertFalse(worker.contains("Beta"), worker);
            }
        } finally {
            processes.close();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void disposalSerializesWithPendingRegistrationAndDetach() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (ChatUiSession session = new ChatUiSession(); var terminal = terminal()) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            CountDownLatch entered = new CountDownLatch(2);
            Future<?> registration, detach;
            synchronized (session) {
                try (var ignored = session.bind()) {
                    ChatCompleter.setTerminalRef(reader, terminal);
                    ChatCompleter.setContentOutput(value -> {});
                }
                registration = pool.submit(session.capture((Runnable) () -> {
                    entered.countDown();
                    ChatCompleter.setContentOutput(value -> fail("closed callback"));
                    ChatCompleter.setQueueSupplier(() -> List.of("closed"));
                    ChatCompleter.setTerminalRef(reader, terminal);
                }));
                detach = pool.submit(session.capture((Runnable) () -> {
                    entered.countDown();
                    ChatCompleter.detachTerminalRef(reader);
                }));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                session.close();
            }
            registration.get(5, TimeUnit.SECONDS);
            detach.get(5, TimeUnit.SECONDS);
            assertNull(session.lineReaderRef);
            assertNull(session.terminalRef);
            assertNull(session.contentOutput);
            assertNull(session.queueSupplier);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static LineDisciplineTerminal terminal() throws Exception {
        var terminal = new LineDisciplineTerminal("session-test", "xterm",
                new ByteArrayOutputStream(), StandardCharsets.UTF_8);
        terminal.setSize(new Size(120, 40));
        return terminal;
    }
}
