package ai.kompile.pipeline.serving.launcher;

import ai.kompile.app.subprocess.SubprocessProtocolChannel;
import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import ai.kompile.pipeline.serving.protocol.PipelineRuntimeProtocol;
import ai.kompile.pipeline.serving.protocol.PipelineRuntimeProtocol.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs a session against a fake child whose stdio the test speaks for, so the readers' handling of
 * what a real runtime's stdout and stderr carry (native log text, damaged lines, the protocol lines
 * a wrapped child wrote to fd 1, an early close) is checked without starting a process or loading
 * ND4J.
 */
class PipelineRuntimeSessionTest {
    private static final String PIPELINE_ID = "p1";
    private static final UnifiedPipelineDefinition DEFINITION =
            UnifiedPipelineDefinition.builder().pipelineId(PIPELINE_ID).build();
    /** A RESULT line cut short, as one interleaved with a large native write arrives. */
    private static final String TRUNCATED_RESULT =
            PipelineRuntimeProtocol.PREFIX + "{\"version\":1,\"type\":\"RESU";
    /** Marks the end of what the child wrote, for tests that check it all reached the run log. */
    private static final String MARKER = "last-words-marker";

    @TempDir
    Path home;

    private String savedHome;
    private final List<FakeChild> children = new ArrayList<>();
    private final List<PipelineRuntimeSession> sessions = new ArrayList<>();

    @BeforeEach
    void isolateRunLogs() {
        // Sessions write their run logs under ~/.kompile.
        savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void stopEverything() throws InterruptedException {
        children.forEach(child -> child.exit(137));
        sessions.forEach(PipelineRuntimeSession::close);
        // A reader can still be writing its end record; let it finish before @TempDir is removed.
        // The stdin writer never touches the log, and an idle one lingers for its keep-alive.
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            String name = thread.getName();
            if (name.startsWith("pipeline-runtime-stdout") || name.startsWith("pipeline-runtime-stderr")) {
                thread.join(10_000);
            }
        }
        System.setProperty("user.home", savedHome);
    }

    @Test
    void readyBehindNativeOutputOnTheSameLineIsDelivered() throws Exception {
        FakeChild child = child();
        PipelineRuntimeSession session = session(child);

        // libnd4j printf()s to fd 1 without a trailing newline, so READY lands on its line.
        child.emit("[native] cuBLAS handle created on device 0"
                + line(PipelineRuntimeProtocol.READY, null, Map.of()));

        session.awaitReady(Duration.ofSeconds(5));
        assertTrue(session.isAlive());
    }

    @Test
    void undecodableLineDoesNotStopTheResponsesAfterIt() throws Exception {
        FakeChild child = child();
        PipelineRuntimeSession session = readySession(child);

        PipelineRuntimeSession.Execution execution = session.start(Map.of("question", "q"));
        Message execute = child.nextCommand();
        assertEquals(PipelineRuntimeProtocol.EXECUTE, execute.type());
        child.emit(TRUNCATED_RESULT);
        child.emit(line(PipelineRuntimeProtocol.RESULT, execute.requestId(),
                Map.of("output", Map.of("answer", 42))));

        assertEquals(42, execution.await(Duration.ofSeconds(5)).get("answer"));
        assertTrue(session.isAlive());
    }

    @Test
    void timeoutNamesTheUndecodableLineThatLostTheResponse() throws Exception {
        FakeChild child = child();
        PipelineRuntimeSession session = readySession(child);

        PipelineRuntimeSession.Execution execution = session.start(Map.of());
        child.nextCommand();
        child.emit(TRUNCATED_RESULT);

        IOException timedOut = assertThrows(IOException.class,
                () -> execution.await(Duration.ofMillis(300)));
        assertTrue(timedOut.getMessage().contains("Undecodable pipeline runtime output"),
                timedOut.getMessage());
    }

    @Test
    void undecodableLineBeforeReadyFailsStartupAtOnce() throws Exception {
        FakeChild child = child();
        PipelineRuntimeSession session = session(child);

        // Before READY a runtime writes only READY or its fatal ERROR, so the lost line was one.
        child.emit(PipelineRuntimeProtocol.PREFIX + "{\"version\":99,\"type\":\"READY\"}");

        ExecutionException failed = assertThrows(ExecutionException.class,
                () -> session.awaitReady(Duration.ofSeconds(5)));
        assertTrue(failed.getCause().getMessage()
                        .contains("Unsupported pipeline runtime protocol version 99"),
                failed.getCause().getMessage());
    }

    @Test
    void stdoutClosingUnderALiveChildRetiresTheSessionAndStopsTheChild() throws Exception {
        FakeChild child = child();
        PipelineRuntimeSession session = readySession(child);
        PipelineRuntimeSession.Execution execution = session.start(Map.of());
        child.nextCommand();

        child.closeStdout();

        ExecutionException lost = assertThrows(ExecutionException.class,
                () -> execution.await(Duration.ofSeconds(5)));
        assertTrue(lost.getCause().getMessage().contains("stdout closed"), lost.getCause().getMessage());
        // Retired before its pending requests fail, so no caller leases it in between.
        assertFalse(session.isAlive(), "a runtime whose responses nothing drains must not be leased again");
        assertThrows(IOException.class, () -> session.start(Map.of()));
        assertEquals(PipelineRuntimeProtocol.SHUTDOWN, child.nextCommand().type());
        assertTrue(child.exited.await(10, TimeUnit.SECONDS),
                "a child writing into an undrained pipe would block, so it is stopped");
        assertTrue(child.destroyCalls.get() > 0);
        String end = endRecord();
        assertTrue(end.contains("state=CLOSED"), end);
    }

    @Test
    void childExitingJustAfterItsStdoutClosesIsRecordedAsExited() throws Exception {
        FakeChild child = child();
        readySession(child);

        child.closeStdout();
        child.exitLater(0, Duration.ofMillis(200));

        String end = endRecord();
        assertTrue(end.contains("state=EXITED exit=0"), end);
        assertEquals(0, child.destroyCalls.get());
        assertTrue(child.commands.isEmpty(), "a child that exits on its own is sent nothing");
    }

    @Test
    void closeStopsAChildThatStoppedReadingStdin() throws Exception {
        FakeChild child = child();
        PipelineRuntimeSession session = readySession(child);
        child.stdinStalled = true;

        PipelineRuntimeSession.Execution execution = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> session.start(Map.of("question", "q")),
                "a caller must not block on a child that stopped reading");
        assertTrue(child.stalledWrite.await(10, TimeUnit.SECONDS), "the request reached the full pipe");

        assertTimeoutPreemptively(Duration.ofSeconds(10), session::close,
                "close must still stop a child whose stdin pipe is full");
        assertFalse(child.isAlive());
        // Failed by the dead pipe, the close or the reader's EOF, whichever lands first.
        assertThrows(ExecutionException.class, () -> execution.await(Duration.ofSeconds(5)));
    }

    @Test
    void timedOutExecutionStopsAChildThatStoppedReadingStdin() throws Exception {
        FakeChild child = child();
        PipelineRuntimeSession session = readySession(child);
        PipelineRuntimeSession.Execution execution = session.start(Map.of());
        child.nextCommand();
        child.stdinStalled = true;
        CompletableFuture<Boolean> healthy =
                CompletableFuture.supplyAsync(() -> session.health(Duration.ofSeconds(30)));
        assertTrue(child.stalledWrite.await(10, TimeUnit.SECONDS), "the health check reached the full pipe");

        // The timeout's CANCEL and SHUTDOWN queue behind the blocked write; the kill must not.
        IOException timedOut = assertTimeoutPreemptively(Duration.ofSeconds(15),
                () -> assertThrows(IOException.class, () -> execution.await(Duration.ofMillis(300))));
        assertTrue(timedOut.getMessage().contains("timed out"), timedOut.getMessage());
        assertFalse(child.isAlive());
        assertFalse(healthy.get(10, TimeUnit.SECONDS), "the blocked health check fails once the child dies");
    }

    @Test
    void cancelWaitsForAKilledChildToExit() throws Exception {
        FakeChild child = child();
        PipelineRuntimeSession session = readySession(child);
        child.ignoresTerm = true;
        child.killExitDelay = Duration.ofMillis(1_500);
        PipelineRuntimeSession.Execution execution = session.start(Map.of());
        child.nextCommand();

        // The kill comes after the cancel budget is spent; the child still gets to exit, since a
        // replacement started while it holds its device memory can fail to allocate.
        assertTrue(execution.cancel(Duration.ofSeconds(1)), "cancel returns once the child is gone");
        assertFalse(child.isAlive());
        String end = endRecord();
        assertTrue(end.contains("state=CANCELLED exit=137"), end);
    }

    @Test
    void interruptedCloseStillWaitsForTheKilledChild() throws Exception {
        FakeChild child = child();
        PipelineRuntimeSession session = readySession(child);
        child.killExitDelay = Duration.ofMillis(500);

        Thread.currentThread().interrupt();
        try {
            session.close();
            assertFalse(child.isAlive(), "an interrupt must not skip waiting for the killed child");
            assertTrue(Thread.interrupted(), "the caller's interrupt is kept");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void executionFailsAtOnceWhenItsCommandCannotBeWritten() throws Exception {
        FakeChild child = child();
        PipelineRuntimeSession session = readySession(child);
        // Still running and reporting, but no longer reading: the write fails with a broken pipe.
        child.closeStdin();

        PipelineRuntimeSession.Execution execution = session.start(Map.of());
        ExecutionException failed = assertThrows(ExecutionException.class,
                () -> execution.await(Duration.ofSeconds(10)), "the write failure, not a timeout");
        assertInstanceOf(IOException.class, failed.getCause());
    }

    @Test
    void protocolAWrappedChildWritesToFd1IsReadFromItsStderr() throws Exception {
        FakeChild child = child();
        PipelineRuntimeSession session = session(child, true);

        // A build from before the channel writes its protocol to fd 1, which the launcher's
        // wrapper points at stderr; native printf output lands on the same line.
        child.emitErr("[native] cuBLAS handle created on device 0"
                + line(PipelineRuntimeProtocol.READY, null, Map.of()));
        session.awaitReady(Duration.ofSeconds(5));

        PipelineRuntimeSession.Execution answered = session.start(Map.of("question", "q"));
        Message execute = child.nextCommand();
        child.emitErr(line(PipelineRuntimeProtocol.RESULT, execute.requestId(),
                Map.of("output", Map.of("answer", 42))));
        assertEquals(42, answered.await(Duration.ofSeconds(5)).get("answer"));

        PipelineRuntimeSession.Execution failing = session.start(Map.of());
        Message second = child.nextCommand();
        child.emitErr(PipelineRuntimeProtocol.encode(PipelineRuntimeProtocol.error(
                second.requestId(), PIPELINE_ID, new IllegalStateException("step failed"))));
        ExecutionException failed = assertThrows(ExecutionException.class,
                () -> failing.await(Duration.ofSeconds(5)));
        PipelineRuntimeSession.RuntimeFailure failure =
                assertInstanceOf(PipelineRuntimeSession.RuntimeFailure.class, failed.getCause());
        assertEquals("step failed", failure.getMessage());
        // The stderr a failure reports is the child's log text, without its protocol lines.
        String stderr = String.valueOf(failure.diagnostic().get("stderr"));
        assertTrue(stderr.contains("cuBLAS handle created on device 0"), stderr);
        assertFalse(stderr.contains(PipelineRuntimeProtocol.PREFIX), stderr);
    }

    @Test
    void onceTheChildReportsTheChannelItsStderrIsOnlyLogText() throws Exception {
        FakeChild child = child();
        PipelineRuntimeSession session = session(child, true);
        child.emitErr(SubprocessProtocolChannel.CHANNEL_OPEN_NOTICE + 3);
        child.emit(line(PipelineRuntimeProtocol.READY, null, Map.of()));
        session.awaitReady(Duration.ofSeconds(5));

        PipelineRuntimeSession.Execution execution = session.start(Map.of());
        Message execute = child.nextCommand();
        // A log line quoting the response the child is about to send is not that response.
        child.emitErr("DEBUG sending " + line(PipelineRuntimeProtocol.RESULT, execute.requestId(),
                Map.of("output", Map.of("answer", 1))));
        child.emitErr("stderr-read-up-to-here");
        awaitLogged("stderr-read-up-to-here");
        child.emit(line(PipelineRuntimeProtocol.RESULT, execute.requestId(),
                Map.of("output", Map.of("answer", 42))));

        assertEquals(42, execution.await(Duration.ofSeconds(5)).get("answer"));
    }

    @Test
    void stderrOfAChildTheLauncherDidNotWrapIsOnlyLogText() throws Exception {
        FakeChild child = child();
        PipelineRuntimeSession session = session(child);

        // Without the wrapper the child's fd 1 is the stdout pipe, so nothing on stderr is protocol.
        child.emitErr(line(PipelineRuntimeProtocol.READY, null, Map.of()));
        child.emitErr("stderr-read-up-to-here");
        awaitLogged("stderr-read-up-to-here");

        IOException notReady = assertThrows(IOException.class,
                () -> session.awaitReady(Duration.ofMillis(200)));
        assertTrue(notReady.getMessage().contains("did not become ready"), notReady.getMessage());
    }

    @Test
    void prefixedStderrLineThatDoesNotDecodeFailsNothing() throws Exception {
        FakeChild child = child();
        PipelineRuntimeSession session = session(child, true);

        // Until the child reports the channel its stderr is also its log, which can quote the prefix.
        child.emitErr("WARN could not parse " + TRUNCATED_RESULT);
        child.emitErr(line(PipelineRuntimeProtocol.READY, null, Map.of()));

        session.awaitReady(Duration.ofSeconds(5));
        assertTrue(session.isAlive());
    }

    @Test
    void responseALegacyChildSentJustBeforeExitingIsDelivered() throws Exception {
        FakeChild child = child();
        child.holdStderr();
        PipelineRuntimeSession session = session(child, true);
        PipelineRuntimeSession.Execution execution = session.start(Map.of());
        Message execute = child.nextCommand();

        // It answers on fd 1 (stderr) and exits, closing stdout before the parent read its answer.
        for (String logLine : logLines(200)) child.emitErr(logLine);
        child.emitErr(line(PipelineRuntimeProtocol.RESULT, execute.requestId(),
                Map.of("output", Map.of("answer", 42))));
        child.exit(0);

        assertEquals(42, execution.await(Duration.ofSeconds(10)).get("answer"));
    }

    @Test
    void requestsLostWithTheChildCarryItsLastStderr() throws Exception {
        FakeChild child = child();
        child.holdStderr();
        PipelineRuntimeSession session = session(child, true);
        child.emitErr(SubprocessProtocolChannel.CHANNEL_OPEN_NOTICE + 3);
        child.emit(line(PipelineRuntimeProtocol.READY, null, Map.of()));
        session.awaitReady(Duration.ofSeconds(5));
        PipelineRuntimeSession.Execution execution = session.start(Map.of());
        child.nextCommand();

        // HotSpot writes this to fd 1, which is stderr for a wrapped child, when
        // -XX:+ExitOnOutOfMemoryError ends it with exit code 3; the parent is behind on stderr.
        for (String logLine : logLines(200)) child.emitErr(logLine);
        child.emitErr("Terminating due to java.lang.OutOfMemoryError: Java heap space");
        child.exit(3);

        ExecutionException lost = assertThrows(ExecutionException.class,
                () -> execution.await(Duration.ofSeconds(10)));
        String message = lost.getCause().getMessage();
        assertTrue(message.contains("stdout closed"), message);
        assertTrue(message.contains("Terminating due to java.lang.OutOfMemoryError"), message);
    }

    @Test
    void closeRecordsWhatTheChildWritesOnItsWayOut() throws Exception {
        FakeChild child = child();
        child.holdStderr();
        PipelineRuntimeSession session = readySession(child);
        // Printed after SIGTERM, by its shutdown hooks; Process.destroy() would close the pipe.
        List<String> lastWords = new ArrayList<>(logLines(200));
        lastWords.add(MARKER);
        child.lastWords = lastWords;

        session.close();

        assertMarkerLoggedBeforeTheEndRecord();
    }

    @Test
    void killedChildsUnreadStderrIsRecordedBeforeItsEndRecord() throws Exception {
        FakeChild child = child();
        child.holdStderr();
        child.ignoresTerm = true;
        PipelineRuntimeSession session = readySession(child);
        for (String logLine : logLines(200)) child.emitErr(logLine);
        child.emitErr(MARKER);

        session.close();

        assertEquals(2, child.destroyCalls.get(), "it ignored SIGTERM and was killed");
        assertMarkerLoggedBeforeTheEndRecord();
    }

    private FakeChild child() throws IOException {
        FakeChild child = new FakeChild();
        children.add(child);
        return child;
    }

    private PipelineRuntimeSession session(FakeChild child) {
        return session(child, false);
    }

    /** @param wrapped whether the launcher started the child behind the protocol channel */
    private PipelineRuntimeSession session(FakeChild child, boolean wrapped) {
        PipelineRuntimeSession session = new PipelineRuntimeSession(
                DEFINITION, child, List.of("pipeline-runtime-under-test"), null, wrapped);
        sessions.add(session);
        return session;
    }

    private PipelineRuntimeSession readySession(FakeChild child) throws Exception {
        PipelineRuntimeSession session = session(child);
        child.emit(line(PipelineRuntimeProtocol.READY, null, Map.of()));
        session.awaitReady(Duration.ofSeconds(5));
        return session;
    }

    private static String line(String type, String requestId, Map<String, Object> payload)
            throws IOException {
        return PipelineRuntimeProtocol.encode(
                PipelineRuntimeProtocol.message(type, requestId, PIPELINE_ID, payload));
    }

    private static List<String> logLines(int count) {
        return IntStream.range(0, count).mapToObj(i -> "INFO runtime log line " + i).toList();
    }

    private void assertMarkerLoggedBeforeTheEndRecord() throws Exception {
        List<String> records = runLog();
        int marker = -1;
        int end = -1;
        for (int i = 0; i < records.size(); i++) {
            if (marker < 0 && records.get(i).contains(MARKER)) marker = i;
            if (end < 0 && isEndRecord(records.get(i))) end = i;
        }
        assertTrue(marker >= 0 && marker < end, "marker at record " + marker + ", end record at "
                + end + " of " + records.size());
    }

    /** The end record of the session's run log, once it is written. */
    private String endRecord() throws Exception {
        return runLog().stream().filter(PipelineRuntimeSessionTest::isEndRecord).findFirst()
                .orElseThrow();
    }

    /** The records of the session's run log, once its end record is written. */
    private List<String> runLog() throws Exception {
        return awaitRunLog(PipelineRuntimeSessionTest::isEndRecord, "no end record");
    }

    /** Waits until the session has logged a line of the child's stderr holding {@code text}. */
    private void awaitLogged(String text) throws Exception {
        awaitRunLog(record -> record.contains("\"stream\":\"STDERR\"") && record.contains(text),
                "no stderr record of " + text);
    }

    /** The records of the run log that has a complete record {@code wanted} accepts. */
    private List<String> awaitRunLog(Predicate<String> wanted, String failure) throws Exception {
        Path logs = home.resolve(".kompile").resolve("logs").resolve("subprocesses");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (Files.isDirectory(logs)) {
                List<Path> runLogs;
                try (Stream<Path> files = Files.walk(logs)) {
                    runLogs = files.filter(file -> file.toString().endsWith(".log")).toList();
                }
                for (Path runLog : runLogs) {
                    List<String> records = Files.readAllLines(runLog);
                    if (records.stream().anyMatch(record -> record.endsWith("}") && wanted.test(record))) {
                        return records;
                    }
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError(failure + " under " + logs);
    }

    private static boolean isEndRecord(String record) {
        return record.contains("subprocess run ended") && record.endsWith("}");
    }

    /**
     * A child the test speaks for: {@link #emit} writes the runtime's stdout and {@link #emitErr}
     * its stderr, and every command the session sends is queued for {@link #nextCommand}. It exits
     * only when signalled or told to. Signals sent through {@link #toHandle} leave the pipes open,
     * as they do for a real child; {@link #destroy} and {@link #destroyForcibly} also close the
     * parent's ends, as the JDK's do.
     */
    static final class FakeChild extends Process {
        final BlockingQueue<Message> commands = new LinkedBlockingQueue<>();
        final AtomicInteger destroyCalls = new AtomicInteger();
        final CountDownLatch exited = new CountDownLatch(1);
        /** Counted down when a write first blocks on the stalled stdin. */
        final CountDownLatch stalledWrite = new CountDownLatch(1);
        /** Stops reading stdin, as a child stuck in native code does, so writes block on a full pipe. */
        volatile boolean stdinStalled;
        /** Ignores SIGTERM, as a child busy in native code can. */
        volatile boolean ignoresTerm;
        /** How long the child takes to exit once killed, as one releasing a GPU context does. */
        volatile Duration killExitDelay = Duration.ZERO;
        /** What the child writes to stderr between SIGTERM and its exit, as shutdown hooks do. */
        volatile List<String> lastWords = List.of();
        private final AtomicBoolean exiting = new AtomicBoolean();
        private final Pipe stdout;
        private final Pipe stdin;
        private final Pipe stderr;
        private final OutputStream stdoutWriter;
        private final OutputStream stderrWriter;
        private final OutputStream stdinWriter;
        private final InputStream errorStream;
        private final Handle handle = new Handle();
        /** Holds the parent's reads of stderr while it is up; see {@link #holdStderr}. */
        private volatile CountDownLatch stderrGate = new CountDownLatch(0);
        private volatile int exitCode;

        FakeChild() throws IOException {
            stdout = Pipe.open();
            stdin = Pipe.open();
            stderr = Pipe.open();
            stdoutWriter = Channels.newOutputStream(stdout.sink());
            stderrWriter = Channels.newOutputStream(stderr.sink());
            OutputStream stdinSink = Channels.newOutputStream(stdin.sink());
            stdinWriter = new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    blockWhileStalled();
                    stdinSink.write(b);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    blockWhileStalled();
                    stdinSink.write(bytes, offset, length);
                }

                @Override
                public void flush() throws IOException {
                    stdinSink.flush();
                }

                @Override
                public void close() throws IOException {
                    stdinSink.close();
                }
            };
            InputStream stderrSource = Channels.newInputStream(stderr.source());
            errorStream = new InputStream() {
                @Override
                public int read() throws IOException {
                    awaitStderrGate();
                    return stderrSource.read();
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    awaitStderrGate();
                    return stderrSource.read(bytes, offset, length);
                }

                @Override
                public void close() throws IOException {
                    stderrSource.close();
                }
            };
            Thread reader = new Thread(this::readCommands, "fake-pipeline-child-stdin");
            reader.setDaemon(true);
            reader.start();
        }

        private void readCommands() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    Channels.newInputStream(stdin.source()), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    commands.add(PipelineRuntimeProtocol.decode(line));
                }
            } catch (IOException closed) {
                // The child exited and closed its end of stdin.
            }
        }

        void emit(String line) throws IOException {
            write(stdoutWriter, line);
        }

        void emitErr(String line) throws IOException {
            write(stderrWriter, line);
        }

        /** One write per line, as a child's line-flushed stream makes. */
        private static void write(OutputStream stream, String line) throws IOException {
            synchronized (stream) {
                stream.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }

        /**
         * Keeps the parent from reading stderr until the child exits, as a reader that fell behind
         * a burst of output is. Call before the session starts; what the child writes meanwhile
         * must fit in the pipe.
         */
        void holdStderr() {
            stderrGate = new CountDownLatch(1);
        }

        private void awaitStderrGate() throws InterruptedIOException {
            try {
                stderrGate.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted while stderr was held");
            }
        }

        /** A write to a full pipe: it blocks through interrupts until the child dies, then fails. */
        private void blockWhileStalled() throws IOException {
            if (!stdinStalled) return;
            stalledWrite.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    exited.await();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            throw new IOException("Broken pipe");
        }

        Message nextCommand() throws InterruptedException {
            Message command = commands.poll(10, TimeUnit.SECONDS);
            assertNotNull(command, "the session sent no command");
            return command;
        }

        void closeStdout() throws IOException {
            stdout.sink().close();
        }

        /** Closes the end of stdin the child reads, as a child that shut its stdin does. */
        void closeStdin() throws IOException {
            stdin.source().close();
        }

        void exitLater(int code, Duration delay) {
            Thread exiter = new Thread(() -> {
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                exit(code);
            }, "fake-pipeline-child-exit");
            exiter.setDaemon(true);
            exiter.start();
        }

        /** Like a dead process: stdout and stderr reach EOF and stdin is no longer read. */
        void exit(int code) {
            if (!exiting.compareAndSet(false, true)) return;
            exitCode = code;
            exited.countDown();
            closeQuietly(stdout.sink());
            closeQuietly(stderr.sink());
            closeQuietly(stdin.source());
            closeQuietly(stdin.sink());
            // All it wrote is in the pipe now, for a held reader to get to.
            stderrGate.countDown();
        }

        private static void closeQuietly(Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }

        @Override
        public OutputStream getOutputStream() {
            return stdinWriter;
        }

        @Override
        public InputStream getInputStream() {
            return Channels.newInputStream(stdout.source());
        }

        @Override
        public InputStream getErrorStream() {
            return errorStream;
        }

        @Override
        public int waitFor() throws InterruptedException {
            exited.await();
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return exited.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            if (isAlive()) throw new IllegalThreadStateException("still running");
            return exitCode;
        }

        @Override
        public boolean isAlive() {
            return exited.getCount() > 0;
        }

        @Override
        public long pid() {
            return 4242L;
        }

        @Override
        public ProcessHandle toHandle() {
            return handle;
        }

        /**
         * As the JDK's does on Linux: signals the child and closes the parent's ends of its pipes,
         * so what the parent had not read yet is lost. The JDK signals first, but a real child takes
         * longer to act on the signal than the close takes, so the fake closes first.
         */
        @Override
        public void destroy() {
            closeParentEnds();
            handle.destroy();
        }

        @Override
        public Process destroyForcibly() {
            closeParentEnds();
            handle.destroyForcibly();
            return this;
        }

        private void closeParentEnds() {
            closeQuietly(stdinWriter);
            closeQuietly(stdout.source());
            closeQuietly(stderr.source());
        }

        /** Signals the child as a {@link ProcessHandle} does, leaving its pipes open. */
        final class Handle implements ProcessHandle {
            @Override
            public long pid() {
                return FakeChild.this.pid();
            }

            @Override
            public Optional<ProcessHandle> parent() {
                return Optional.empty();
            }

            @Override
            public Stream<ProcessHandle> children() {
                return Stream.empty();
            }

            @Override
            public Stream<ProcessHandle> descendants() {
                return Stream.empty();
            }

            @Override
            public Info info() {
                throw new UnsupportedOperationException();
            }

            @Override
            public CompletableFuture<ProcessHandle> onExit() {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean supportsNormalTermination() {
                return true;
            }

            /** SIGTERM: unless it ignores it, the child writes its {@link #lastWords} and exits 143. */
            @Override
            public boolean destroy() {
                destroyCalls.incrementAndGet();
                if (ignoresTerm) return true;
                Thread shutdown = new Thread(() -> {
                    try {
                        for (String line : lastWords) emitErr(line);
                    } catch (IOException pipeClosed) {
                        // The parent closed its end, so nothing reads the rest.
                    }
                    exit(143);
                }, "fake-pipeline-child-sigterm");
                shutdown.setDaemon(true);
                shutdown.start();
                return true;
            }

            /** SIGKILL: the child exits 137, after {@link #killExitDelay}. */
            @Override
            public boolean destroyForcibly() {
                destroyCalls.incrementAndGet();
                if (killExitDelay.isZero()) {
                    exit(137);
                } else {
                    exitLater(137, killExitDelay);
                }
                return true;
            }

            @Override
            public boolean isAlive() {
                return FakeChild.this.isAlive();
            }

            @Override
            public int compareTo(ProcessHandle other) {
                return Long.compare(pid(), other.pid());
            }
        }
    }
}
