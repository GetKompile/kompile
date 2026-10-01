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

package ai.kompile.app.subprocess;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * libnd4j logs with printf straight to fd 1, beneath a child's {@code System.setOut} redirect. The
 * launcher starts a child with a structured protocol behind {@link SubprocessProtocolChannel}, so
 * native output goes to stderr and the structured lines have the stdout pipe to themselves. Structured
 * lines written to fd 1 still have to reach the handler: from stderr for a child built before the
 * channel, and from stdout, behind native text, for a child the wrapper can't run. The children here
 * write their output that way, and run through the launcher's real start and stream draining. A stopped child's
 * last output has to be logged too, before the run is reported stopped.
 */
@Timeout(60)
class ManagedSubprocessLauncherStructuredOutputTest {

    private static final String PREFIX = "TEST_MSG:";
    private static final String HOOK_INSTALLED = "{\"hook\":\"installed\"}";
    private static final String STOP_SEEN = "asked to stop";
    private static final String LAST_WORDS = "last words: done";

    private final SubprocessLogBus logBus = new SubprocessLogBus();
    private final List<SubprocessLogEvent> published = new CopyOnWriteArrayList<>();
    private final List<String> payloads = new CopyOnWriteArrayList<>();
    private ChildLauncher launcher;

    @BeforeEach
    void setUp() {
        logBus.register(published::add);
    }

    @AfterEach
    void tearDown() {
        if (launcher != null) {
            launcher.shutdown();
        }
    }

    @Test
    void structuredLinesReachTheHandlerAndNativeOutputIsLoggedFromStderr() throws Exception {
        run(ChannelChild.class);

        assertEquals(List.of("{\"at\":\"start\"}", "{\"at\":\"after native text\"}"), payloads);
        assertEquals(List.of(), linesFrom(SubprocessLogEvent.Stream.STDOUT));
        // A prefix on stderr after the child opened the channel is only text
        assertEquals(List.of(SubprocessProtocolChannel.CHANNEL_OPEN_NOTICE + "3",
                        "[native] no newline plain line", PREFIX + "{\"on\":\"stderr\"}"),
                linesFrom(SubprocessLogEvent.Stream.STDERR));
    }

    @Test
    void aChildThatWritesStructuredLinesToFd1IsReadFromStderr() throws Exception {
        run(LegacyChild.class);

        assertEquals(List.of("{\"at\":\"start\"}", "{\"at\":\"behind native text\"}"), payloads);
        assertEquals(List.of(), linesFrom(SubprocessLogEvent.Stream.STDOUT));
        assertEquals(List.of("[native] no newline ", "plain line"), linesFrom(SubprocessLogEvent.Stream.STDERR));
    }

    @Test
    void anUnwrappedChildsStructuredLinesAreFoundBehindNativeTextOnStdout() throws Exception {
        // The wrapper leaves a relative command as it is, as it leaves every command where it can't run
        run(LegacyChild.class, PREFIX, true);

        assertEquals(List.of("{\"at\":\"start\"}", "{\"at\":\"behind native text\"}"), payloads);
        assertEquals(List.of("[native] no newline ", "plain line"), linesFrom(SubprocessLogEvent.Stream.STDOUT));
        assertEquals(List.of(), linesFrom(SubprocessLogEvent.Stream.STDERR));
    }

    @Test
    void aRunWithoutAStructuredProtocolLosesAnInheritedProtocolFdName() throws Exception {
        run(FdNameChild.class, null, false);

        assertEquals(List.of(), payloads);
        assertEquals(List.of("fd name: null"), linesFrom(SubprocessLogEvent.Stream.STDOUT));
    }

    @Test
    void aStoppedChildsLastWordsAreLoggedBeforeTheRunIsReportedStopped() throws Exception {
        launcher = new ChildLauncher(LastWordsChild.class, PREFIX, false);
        launcher.logBus = logBus;
        launcher.startProcess("run", null, List.of(), payloads::add);
        awaitPayload(HOOK_INSTALLED);

        launcher.stop("run", "test");

        assertLastWordsLoggedBeforeStopped();
    }

    @Test
    void aChildThatHasToBeKilledStillHasItsLastWordsLoggedBeforeTheRunIsReportedStopped() throws Exception {
        launcher = new ChildLauncher(StubbornChild.class, PREFIX, false);
        launcher.logBus = logBus;
        Process child = launcher.startProcess("run", null, List.of(), payloads::add).process();
        // Holds the stderr reader from the child's first words after the stop request until the child is gone, so
        // its last words are still in the pipe when it is killed
        logBus.register(event -> {
            if (isStopSeen(event)) {
                letLastWordsIntoThePipe(child);
                awaitExit(child);
            }
        });
        awaitPayload(HOOK_INSTALLED);

        launcher.stop("run", "test");

        assertLastWordsLoggedBeforeStopped();
    }

    @Test
    void anInterruptedStopKillsTheChildKeepsTheInterruptAndLosesNoneOfTheChildsLastWords() throws Exception {
        launcher = new ChildLauncher(StubbornChild.class, PREFIX, false);
        launcher.logBus = logBus;
        Process child = launcher.startProcess("run", null, List.of(), payloads::add).process();
        AtomicBoolean interruptKept = new AtomicBoolean();
        Thread stopper = new Thread(() -> {
            launcher.stop("run", "test");
            interruptKept.set(Thread.currentThread().isInterrupted());
        }, "stopper");
        // Interrupts the stop while it waits for the child to exit, with the child's last words in the pipe
        logBus.register(event -> {
            if (isStopSeen(event)) {
                letLastWordsIntoThePipe(child);
                stopper.interrupt();
                awaitExit(child);
            }
        });
        awaitPayload(HOOK_INSTALLED);

        stopper.start();
        stopper.join(TimeUnit.SECONDS.toMillis(30));

        assertFalse(stopper.isAlive(), "the stop never returned");
        assertTrue(interruptKept.get(), "the stop cleared its caller's interrupt");
        assertTrue(child.waitFor(10, TimeUnit.SECONDS), "the child was never killed");
        // An interrupted stop does not wait for them, so they are logged after it
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (indexOf(List.copyOf(published), SubprocessLogEvent.Stream.STDERR, LAST_WORDS) < 0) {
            assertTrue(System.nanoTime() < deadline, "the child's last words were never logged");
            Thread.sleep(10);
        }
    }

    private void awaitPayload(String payload) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!payloads.contains(payload)) {
            assertTrue(System.nanoTime() < deadline, "the child never reported " + payload);
            Thread.sleep(10);
        }
    }

    private static boolean isStopSeen(SubprocessLogEvent event) {
        return event.stream() == SubprocessLogEvent.Stream.STDERR && STOP_SEEN.equals(event.message());
    }

    /**
     * Answers a {@link StubbornChild}, which then writes its last words, and waits until they are in the pipe. Called
     * from the stderr reader, which reads nothing more until this returns.
     */
    private static void letLastWordsIntoThePipe(Process child) {
        try {
            OutputStream stdin = child.getOutputStream();
            stdin.write('\n');
            stdin.flush();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (child.getErrorStream().available() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitExit(Process child) {
        try {
            child.waitFor(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void assertLastWordsLoggedBeforeStopped() {
        List<SubprocessLogEvent> events = List.copyOf(published);
        int lastWords = indexOf(events, SubprocessLogEvent.Stream.STDERR, LAST_WORDS);
        int stopped = indexOf(events, SubprocessLogEvent.Stream.LIFECYCLE, "Subprocess 'native-text' stopped");
        assertTrue(lastWords >= 0, "the child's last words were never logged");
        assertTrue(stopped > lastWords, "the run was reported stopped before the child's last words were logged");
    }

    private static int indexOf(List<SubprocessLogEvent> events, SubprocessLogEvent.Stream stream, String message) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).stream() == stream && message.equals(events.get(i).message())) {
                return i;
            }
        }
        return -1;
    }

    private void run(Class<?> child) throws Exception {
        run(child, PREFIX, false);
    }

    private void run(Class<?> child, String prefix, boolean relativeCommand) throws Exception {
        launcher = new ChildLauncher(child, prefix, relativeCommand);
        launcher.logBus = logBus;
        ManagedSubprocessLauncher.ManagedRun run = launcher.startProcess("run", null, List.of(), payloads::add);

        assertTrue(run.process().waitFor(30, TimeUnit.SECONDS), "the child never exited");
        assertTrue(launcher.awaitOutputDrained(run, 10_000), "the child's output was never read to its end");
    }

    private List<String> linesFrom(SubprocessLogEvent.Stream stream) {
        return published.stream()
                .filter(event -> event.stream() == stream)
                .map(SubprocessLogEvent::message)
                .toList();
    }

    /** Runs a child through the launcher's real start and stream draining. */
    static final class ChildLauncher extends ManagedSubprocessLauncher {

        private final Class<?> child;
        private final String prefix;
        private final boolean relativeCommand;

        ChildLauncher(Class<?> child, String prefix, boolean relativeCommand) {
            this.child = child;
            this.prefix = prefix;
            this.relativeCommand = relativeCommand;
        }

        @Override
        public String getSubprocessId() {
            return "native-text";
        }

        @Override
        protected String getTypeLabel() {
            return "native-text";
        }

        @Override
        protected String getMainClass() {
            return child.getName();
        }

        @Override
        protected int getHeapMb() {
            return 32;
        }

        @Override
        protected String getStructuredMessagePrefix() {
            return prefix;
        }

        @Override
        protected void configureEnvironment(Map<String, String> env) {
            super.configureEnvironment(env);
            // As if this JVM had been started behind the wrapper itself
            env.put(SubprocessProtocolChannel.ENV_PROTOCOL_FD, "3");
        }

        @Override
        protected List<String> buildJvmCommand(List<String> programArgs) {
            // Only stream draining is under test, so the child gets none of the backend or native flags
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            return List.of(relativeCommand ? relativeToWorkingDirectory(java) : java.toString(),
                    "-Xmx" + getHeapMb() + "m", "-cp", System.getProperty("java.class.path"), getMainClass());
        }

        private static String relativeToWorkingDirectory(Path file) {
            try {
                // Real paths: the child resolves ".." against the directories themselves, not the links to them
                return Path.of("").toRealPath().relativize(file.toRealPath()).toString();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Writes structured lines to the protocol channel and native text without a newline to fd 1. */
    public static final class ChannelChild {

        private ChannelChild() {
        }

        public static void main(String[] args) throws IOException {
            PrintStream protocol = SubprocessProtocolChannel.open(System.out);
            System.setOut(System.err);
            FileOutputStream fd1 = new FileOutputStream(FileDescriptor.out);

            protocol.println(PREFIX + "{\"at\":\"start\"}");
            fd1.write("[native] no newline ".getBytes(StandardCharsets.UTF_8));
            protocol.println(PREFIX + "{\"at\":\"after native text\"}");
            fd1.write("plain line\n".getBytes(StandardCharsets.UTF_8));
            System.err.println(PREFIX + "{\"on\":\"stderr\"}");
            System.err.flush();
        }
    }

    /**
     * Writes as a child built before the channel: structured lines to its original stdout, and native text
     * without a newline in front of one of them.
     */
    public static final class LegacyChild {

        private LegacyChild() {
        }

        public static void main(String[] args) {
            System.out.print(PREFIX + "{\"at\":\"start\"}\n");
            System.out.print("[native] no newline ");
            System.out.flush();
            System.out.print(PREFIX + "{\"at\":\"behind native text\"}\n");
            System.out.print("plain line\n");
            System.out.flush();
        }
    }

    /** Prints the protocol fd name it was given. */
    public static final class FdNameChild {

        private FdNameChild() {
        }

        public static void main(String[] args) {
            System.out.println("fd name: " + System.getenv(SubprocessProtocolChannel.ENV_PROTOCOL_FD));
        }
    }

    /** Runs until it is stopped, and writes its last words on the way out. */
    public static final class LastWordsChild {

        private LastWordsChild() {
        }

        public static void main(String[] args) throws IOException {
            PrintStream protocol = SubprocessProtocolChannel.open(System.out);
            System.setOut(System.err);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                // More than the parent reads at once
                for (int i = 0; i < 1000; i++) {
                    System.err.println("last words " + i);
                }
                System.err.println(LAST_WORDS);
            }));
            protocol.println(PREFIX + HOOK_INSTALLED);
            // Until it is stopped, or the parent is gone
            while (System.in.read() >= 0) {
                // nothing is sent
            }
        }
    }

    /**
     * Keeps running when it is asked to stop: it writes its last words once the parent answers the first of them on
     * stdin, and then waits to be killed.
     */
    public static final class StubbornChild {

        private StubbornChild() {
        }

        public static void main(String[] args) throws InterruptedException {
            PrintStream protocol = SubprocessProtocolChannel.open(System.out);
            System.setOut(System.err);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.err.println(STOP_SEEN);
                try {
                    System.in.read();
                    System.err.println(LAST_WORDS);
                    Thread.sleep(TimeUnit.SECONDS.toMillis(30));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            protocol.println(PREFIX + HOOK_INSTALLED);
            Thread.sleep(TimeUnit.SECONDS.toMillis(30));
        }
    }
}
