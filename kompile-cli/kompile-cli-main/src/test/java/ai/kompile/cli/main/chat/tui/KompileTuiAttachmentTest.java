package ai.kompile.cli.main.chat.tui;

import ai.kompile.cli.main.chat.BackgroundTaskManager;
import ai.kompile.cli.main.chat.MessageQueue;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Widget;
import org.jline.terminal.Size;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class KompileTuiAttachmentTest {
    @Test
    void detachRetainsActivityViewportDraftQueueAndBackgroundOutput() throws Exception {
        try (Fixture f = new Fixture()) {
            f.tui.attachLineReader(f.reader);
            f.tui.start(f.terminal);
            f.reader.getBuffer().write("unfinished draft");
            f.queue.enqueue("queued work");
            f.tui.recordInScrollRegion("parent transcript");
            String rows = java.util.stream.IntStream.range(0, 60)
                    .mapToObj(i -> "line " + i).collect(java.util.stream.Collectors.joining("\n"));
            f.tui.showActivityView("repl:judge", "Judge", rows);
            f.tui.scrollContent(12);
            List<String> before = f.tui.getVisibleContentLines();
            f.drain();
            f.tui.detachTerminal();
            f.output.reset();

            f.tui.recordInScrollRegion("parent completed while detached");
            f.tui.upsertMainTranscriptBlock("build", "build completed while detached");
            f.tui.updateActivityView("repl:judge", "Judge", rows + "\njudge done");
            f.tui.getStatusBar().setActiveAgent("retained-agent");
            f.tui.redrawContentView();
            assertTrue(f.tui.isStarted(), "detach must not end the retained surface");
            assertFalse(f.tui.isTerminalAttached());
            assertEquals(0, f.output.size());
            assertEquals("unfinished draft", f.reader.getBuffer().toString());
            assertEquals(1, f.queue.getAll().size());
            // Without a terminal, JLine emits Unicode rather than terminal-specific
            // alternate-character-set escapes for the pinned title. Compare the body.
            List<String> detached = f.tui.getVisibleContentLines();
            assertEquals(before.subList(1, before.size()), detached.subList(1, detached.size()),
                    "live append preserves viewport");

            f.tui.attachLineReader(f.reader);
            f.tui.attachTerminal(f.terminal);
            f.drain();
            assertEquals(before, f.tui.getVisibleContentLines());
            assertEquals("unfinished draft", f.reader.getBuffer().toString());
            f.tui.showMainView();
            assertTrue(String.join("\n", f.tui.getVisibleContentLines())
                    .contains("parent completed while detached"));
            assertTrue(String.join("\n", f.tui.getVisibleContentLines())
                    .contains("build completed while detached"));
        }
    }

    @Test
    void detachEndsMouseDragButRetainsSelection() throws Exception {
        try (Fixture f = new Fixture()) {
            f.tui.start(f.terminal);
            f.tui.showActivityView("judge", "Judge", "selectable line\n".repeat(60));
            assertTrue(f.tui.beginTranscriptSelection(1, f.tui.scrollTop() + 1));
            assertTrue(f.tui.dragTranscriptSelection(8, f.tui.scrollTop() - 1));
            Object anchor = field("selectionAnchorCell").get(f.tui);
            long version = (long) field("selectionVersion").get(f.tui);
            assertTrue((boolean) field("selectionAutoScrollScheduled").get(f.tui));
            f.tui.detachTerminal();
            assertFalse((boolean) field("selectionDragging").get(f.tui));
            assertFalse((boolean) field("selectionAutoScrollScheduled").get(f.tui));
            assertTrue((long) field("selectionVersion").get(f.tui) > version,
                    "invalidate pending drag callbacks");
            assertEquals(anchor, field("selectionAnchorCell").get(f.tui));
            assertNotNull(field("selectionActiveCell").get(f.tui));
        }
    }

    @Test
    void detachedCommandsRemainRetainedAndNeverFallBackToStdout() throws Exception {
        try (Fixture f = new Fixture()) {
            f.tui.start(f.terminal);
            f.drain();
            f.tui.detachTerminal();
            f.output.reset();
            PrintStream original = System.out;
            ByteArrayOutputStream stray = new ByteArrayOutputStream();
            try (PrintStream capture = new PrintStream(stray, true, StandardCharsets.UTF_8)) {
                System.setOut(capture);
                assertTrue(f.tui.runCommandOutput(() -> {
                    System.out.println("detached slash result");
                    return true;
                }));
                f.tui.printInScrollRegion("detached streamed result");
                f.tui.getStatusBar().setEnabled(false);
                f.tui.getStatusBar().setEnabled(true);
                f.tui.getStatusBar().redraw();
                f.tui.stop();
                assertEquals(0, stray.size(), "detached surface must not write another chat's stdout");
            } finally {
                System.setOut(original);
            }
            assertEquals(0, f.output.size());
            assertTrue(String.join("\n", f.tui.getVisibleContentLines())
                    .contains("detached slash result"));
        }
    }

    @Test
    void queuedOldFrameCannotPaintAReattachedTerminal() throws Exception {
        try (Fixture f = new Fixture();
             LineDisciplineTerminal other = terminal(new ByteArrayOutputStream())) {
            f.tui.start(f.terminal);
            f.drain();
            ExecutorService executor = f.redrawExecutor();
            CountDownLatch blocked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            executor.submit(() -> {
                blocked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("redraw barrier timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(blocked.await(2, TimeUnit.SECONDS));
            try {
                f.tui.recordInScrollRegion("old queued frame");
                f.tui.detachTerminal();
                f.output.reset();
                f.tui.attachTerminal(other);
                // Observe the full initial frame, then release a callback from the old attachment.
                long lastFrame = (long) field("lastFrameNanos").get(f.tui);
                release.countDown();
                f.drain();
                assertEquals(lastFrame, (long) field("lastFrameNanos").get(f.tui),
                        "stale frame must not render against the new attachment");
                assertEquals(0, f.output.size());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void lateNamedWidgetLookupCannotDrawTheNextSurface() throws Exception {
        try (Fixture first = new Fixture(); Fixture second = new Fixture()) {
            CountDownLatch lookupStarted = new CountDownLatch(1);
            CountDownLatch releaseLookup = new CountDownLatch(1);
            // Reproduce callWidget waiting for JLine's lock before looking up the
            // named widget, after another surface has attached to the same reader.
            LineReader delayed = (LineReader) java.lang.reflect.Proxy.newProxyInstance(
                    LineReader.class.getClassLoader(), new Class<?>[]{LineReader.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("callWidget")) {
                            lookupStarted.countDown();
                            if (!releaseLookup.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("widget lookup barrier timed out");
                            }
                            Widget widget = first.reader.getWidgets().get((String) args[0]);
                            if (widget != null) widget.apply();
                            return null;
                        }
                        return method.invoke(first.reader, args);
                    });
            first.tui.attachLineReader(delayed);
            first.tui.start(first.terminal);
            first.drain();
            first.tui.requestRedraw();
            assertTrue(lookupStarted.await(2, TimeUnit.SECONDS));
            try {
                first.tui.detachTerminal();
                second.tui.attachLineReader(first.reader);
                second.tui.attachTerminal(first.terminal);
                second.drain();
                first.output.reset();
                releaseLookup.countDown();
                first.drain();
                assertEquals(0, first.output.size(), "old callback must not invoke the new surface");
            } finally {
                releaseLookup.countDown();
            }
        }
    }

    @Test
    void delayedResizeCannotOverwriteReattachedGeometryOrSelection() throws Exception {
        try (Fixture f = new Fixture()) {
            CountDownLatch sizeRead = new CountDownLatch(1);
            CountDownLatch releaseSize = new CountDownLatch(1);
            var resizeThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
            org.jline.terminal.Terminal delayed = (org.jline.terminal.Terminal)
                    java.lang.reflect.Proxy.newProxyInstance(
                            org.jline.terminal.Terminal.class.getClassLoader(),
                            new Class<?>[]{org.jline.terminal.Terminal.class}, (proxy, method, args) -> {
                                Object result = method.invoke(f.terminal, args);
                                if (method.getName().equals("getSize")
                                        && Thread.currentThread() == resizeThread.get()) {
                                    sizeRead.countDown();
                                    if (!releaseSize.await(5, TimeUnit.SECONDS)) {
                                        throw new AssertionError("size barrier timed out");
                                    }
                                }
                                return result;
                            });
            f.tui.start(delayed);
            f.drain();
            var notifications = new java.util.concurrent.atomic.AtomicInteger();
            f.tui.addResizeListener(notifications::incrementAndGet);
            ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var pending = worker.submit(() -> {
                    resizeThread.set(Thread.currentThread());
                    f.tui.handleResize();
                });
                assertTrue(sizeRead.await(2, TimeUnit.SECONDS));
                f.tui.detachTerminal();
                f.terminal.setSize(new Size(120, 40));
                // Reusing the very same Terminal object must still invalidate the old read.
                f.tui.attachTerminal(delayed);
                f.tui.showActivityView("judge", "Judge", "selectable line\n".repeat(60));
                assertTrue(f.tui.beginTranscriptSelection(1, f.tui.scrollTop() + 1));
                assertTrue(f.tui.finishTranscriptSelection(8, f.tui.scrollTop() + 1));
                String selection = f.tui.getSelectedTranscriptText();
                assertFalse(selection.isEmpty());
                releaseSize.countDown();
                pending.get(3, TimeUnit.SECONDS);
                assertEquals(120, f.tui.getTerminalWidth());
                assertEquals(40, f.tui.getTerminalHeight());
                assertEquals(selection, f.tui.getSelectedTranscriptText());
                assertEquals(0, notifications.get(), "stale resize must not notify the new attachment");
            } finally {
                releaseSize.countDown();
                worker.shutdownNow();
            }
        }
    }

    @Test
    void lateLookupCannotInvokeRestoredOutputProducingWidgets() throws Exception {
        for (boolean withInput : new boolean[]{false, true}) {
            try (Fixture f = new Fixture()) {
                var calls = new java.util.concurrent.atomic.AtomicInteger();
                Widget previous = () -> {
                    calls.incrementAndGet();
                    f.terminal.writer().print("stray restored widget output");
                    f.terminal.writer().flush();
                    return true;
                };
                f.reader.getWidgets().put("kompile-redraw-frame", previous);
                f.reader.getWidgets().put("kompile-redraw-frame-with-input", previous);
                CountDownLatch lookupStarted = new CountDownLatch(1);
                CountDownLatch releaseLookup = new CountDownLatch(1);
                LineReader delayed = (LineReader) java.lang.reflect.Proxy.newProxyInstance(
                        LineReader.class.getClassLoader(), new Class<?>[]{LineReader.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("callWidget")) {
                                lookupStarted.countDown();
                                if (!releaseLookup.await(5, TimeUnit.SECONDS)) {
                                    throw new AssertionError("widget lookup barrier timed out");
                                }
                                Widget widget = f.reader.getWidgets().get((String) args[0]);
                                if (widget != null) widget.apply();
                                return null;
                            }
                            return method.invoke(f.reader, args);
                        });
                f.tui.attachLineReader(delayed);
                f.tui.start(f.terminal);
                f.drain();
                if (withInput) {
                    var method = KompileTui.class.getDeclaredMethod("requestRedrawWithInput");
                    method.setAccessible(true);
                    method.invoke(f.tui);
                } else {
                    f.tui.requestRedraw();
                }
                assertTrue(lookupStarted.await(2, TimeUnit.SECONDS));
                try {
                    f.tui.detachTerminal();
                    assertSame(previous, f.reader.getWidgets().get("kompile-redraw-frame"));
                    assertSame(previous, f.reader.getWidgets().get("kompile-redraw-frame-with-input"));
                    f.output.reset();
                    releaseLookup.countDown();
                    f.drain();
                    assertEquals(0, calls.get(), "late dispatch must not call a restored widget");
                    assertEquals(0, f.output.size());
                } finally {
                    releaseLookup.countDown();
                }
            }
        }
    }

    @Test
    void repeatedAttachReusesWorkersAndRestoresReaderWidgets() throws Exception {
        try (Fixture f = new Fixture()) {
            Widget previous = () -> true;
            f.reader.getWidgets().put("kompile-redraw-frame", previous);
            f.tui.attachLineReader(f.reader);
            Widget old = f.reader.getWidgets().get("kompile-redraw-frame");
            f.tui.start(f.terminal);
            f.drain();
            Field refresh = StatusBar.class.getDeclaredField("refreshThread");
            refresh.setAccessible(true);
            Object worker = refresh.get(f.tui.getStatusBar());
            f.tui.start(f.terminal);
            assertSame(worker, refresh.get(f.tui.getStatusBar()));
            f.tui.detachTerminal();
            assertSame(previous, f.reader.getWidgets().get("kompile-redraw-frame"));
            assertFalse(old.apply());
            f.tui.attachLineReader(f.reader);
            f.tui.attachTerminal(f.terminal);
            assertFalse(old.apply(), "stale widget must not revive when reusing the same reader");
            assertSame(worker, refresh.get(f.tui.getStatusBar()));
            f.tui.detachTerminal();
            f.tui.detachTerminal();
            f.tui.stop();
            f.tui.stop();
            assertThrows(IllegalStateException.class, () -> f.tui.attachTerminal(f.terminal));
        }
    }

    @Test
    void terminalRemainsCallerOwnedAndCanBeUsedByAnotherSurface() throws Exception {
        try (Fixture first = new Fixture(); Fixture second = new Fixture()) {
            first.tui.start(first.terminal);
            first.tui.detachTerminal();
            second.tui.attachTerminal(first.terminal);
            second.tui.recordInScrollRegion("second session");
            second.drain();
            first.output.reset();
            first.tui.recordInScrollRegion("first session background");
            first.tui.stop();
            assertEquals(0, first.output.size());
            second.tui.redrawContentView();
            assertTrue(first.output.toString(StandardCharsets.UTF_8).contains("second session"));
            second.tui.detachTerminal();
            first.terminal.writer().print("still open");
            first.terminal.writer().flush();
            assertTrue(first.output.toString(StandardCharsets.UTF_8).contains("still open"));
        }
    }

    @Test
    void undersizedStartupCanRetryAndUnstartedStopIsFinal() throws Exception {
        try (Fixture f = new Fixture()) {
            f.terminal.setSize(new Size(80, 8));
            f.tui.start(f.terminal);
            assertFalse(f.tui.isStarted());
            assertFalse(f.tui.isTerminalAttached());
            f.terminal.setSize(new Size(80, 24));
            f.tui.start(f.terminal);
            assertTrue(f.tui.isTerminalAttached());
        }
        try (Fixture f = new Fixture()) {
            f.tui.stop();
            assertThrows(IllegalStateException.class, () -> f.tui.start(f.terminal));
        }
    }

    private static Field field(String name) throws Exception {
        Field field = KompileTui.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static LineDisciplineTerminal terminal(ByteArrayOutputStream output) throws Exception {
        LineDisciplineTerminal terminal = new LineDisciplineTerminal(
                "attachment-test", "xterm", output, StandardCharsets.UTF_8);
        terminal.setSize(new Size(100, 30));
        return terminal;
    }

    private static final class Fixture implements AutoCloseable {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final LineDisciplineTerminal terminal = terminal(output);
        final LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
        final BackgroundProcessManager processes = new BackgroundProcessManager("attachment-" + UUID.randomUUID());
        final MessageQueue queue = new MessageQueue("attachment-" + UUID.randomUUID());
        final KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes, queue, new TerminalRenderer(true));

        Fixture() throws Exception { }

        ExecutorService redrawExecutor() throws Exception {
            return (ExecutorService) field("redrawExecutor").get(tui);
        }

        void drain() throws Exception {
            redrawExecutor().submit(() -> { }).get(3, TimeUnit.SECONDS);
        }

        @Override public void close() throws Exception {
            tui.stop();
            queue.clear();
            processes.close();
            terminal.close();
        }
    }
}
