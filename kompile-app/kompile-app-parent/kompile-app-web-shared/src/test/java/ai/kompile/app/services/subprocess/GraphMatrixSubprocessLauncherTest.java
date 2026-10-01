package ai.kompile.app.services.subprocess;

import ai.kompile.app.subprocess.GraphMatrixSubprocessMain;
import ai.kompile.app.subprocess.ManagedSubprocessLauncher;
import ai.kompile.app.subprocess.RestartableSubprocess;
import ai.kompile.app.subprocess.SubprocessRegistry;
import ai.kompile.app.subprocess.SubprocessRegistry.SubprocessInfo;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The matrix child is persistent, so when the RSS watchdog restarts it, it has to come back. The
 * restarts here go the way the watchdog's do: through the handler the registry holds under the id
 * {@link SubprocessRegistry#listAll()} reports. {@link IdleMatrixLauncher} runs the real launcher
 * with a small idle JVM in place of the matrix server.
 */
@Timeout(60)
class GraphMatrixSubprocessLauncherTest {

    private final SubprocessRegistry registry = new SubprocessRegistry();
    private final IdleMatrixLauncher launcher = new IdleMatrixLauncher(registry);
    private final Logger launcherLog = (Logger) LoggerFactory.getLogger(IdleMatrixLauncher.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void captureLauncherLog() {
        previousLevel = launcherLog.getLevel();
        launcherLog.setLevel(Level.INFO);
        appender.start();
        launcherLog.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        launcher.shutdown();
        registry.shutdownAll();
        launcherLog.detachAppender(appender);
        launcherLog.setLevel(previousLevel);
    }

    @Test
    void graphMatrixExtraJvmArgsContainNoHardcodedMultiBackendDisables() {
        GraphMatrixSubprocessLauncher launcher = new GraphMatrixSubprocessLauncher();

        List<String> args = launcher.getExtraJvmArgs();

        assertFalse(
                args.stream().anyMatch(arg -> arg.contains("nd4j.multibackend.enabled=false")),
                "graph-matrix launcher must not hardcode nd4j.multibackend.enabled=false");
        assertFalse(
                args.stream().anyMatch(arg -> arg.contains("org.nd4j.backend.multi.auto=false")),
                "graph-matrix launcher must not hardcode org.nd4j.backend.multi.auto=false");

        assertTrue(launcher.getBackendPreference() == ManagedSubprocessLauncher.BackendPreference.CPU,
                "graph-matrix launcher remains CPU preference for matrix storage workload");
    }

    @Test
    void theChildGetsTheRpcLimitsTheAppsClientUses() {
        String key = GraphMatrixSubprocessMain.MAX_RESPONSE_BYTES_PROPERTY;
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, "33554432");
            assertTrue(new GraphMatrixSubprocessLauncher().getExtraJvmArgs().contains("-D" + key + "=33554432"),
                    "a raised response cap on the app left the child rejecting replies at its default");

            System.clearProperty(key);
            assertFalse(new GraphMatrixSubprocessLauncher().getExtraJvmArgs().stream()
                    .anyMatch(arg -> arg.startsWith("-D" + key)), "an unset limit is left to the child's default");
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    @Test
    void theWatchdogsRestartRespawnsTheMatrixChild() throws Exception {
        startMatrix();
        SubprocessInfo first = matrixChild();
        ProcessHandle firstChild = ProcessHandle.of(first.pid()).orElseThrow();

        watchdogHandler().requestRestart("RSS over the limit");

        firstChild.onExit().get(20, TimeUnit.SECONDS);
        awaitRestartDone();
        assertTrue(launcher.isRunning(), "the matrix child was not respawned");
        SubprocessInfo second = matrixChild();
        assertEquals(first.id(), second.id(), "the respawned child is registered under another id");
        assertNotEquals(first.pid(), second.pid());
        assertTrue(second.alive());
        assertTrue(registry.getRestartHandler(second.id()).isPresent(),
                "the respawned child has no handler for its next restart");
    }

    @Test
    void aRestartRequestedAfterShutdownDoesNothing() throws Exception {
        startMatrix();
        // The watchdog found the handler just before shutdown began
        RestartableSubprocess handler = watchdogHandler();
        launcher.shutdown();

        handler.requestRestart("RSS over the limit");
        awaitRestartDone();

        assertEquals(1, launcher.startCalls.get(), "a child was spawned after shutdown");
        assertEquals(List.of(), eventsAtOrAbove(Level.WARN));
    }

    @Test
    void shutdownDuringARespawnEndsItWithoutAnError() throws Exception {
        startMatrix();
        launcher.shutdownAtStart = 2;

        watchdogHandler().requestRestart("RSS over the limit");
        awaitRestartDone();

        assertEquals(2, launcher.startCalls.get(), "expected the first start and one respawn, which shutdown ended");
        assertFalse(launcher.isRunning());
        assertEquals(List.of(), registry.listAll(), "a child outlived the restart");
        assertEquals(List.of(), eventsAtOrAbove(Level.ERROR));
    }

    private void startMatrix() {
        launcher.start();
        assertTrue(launcher.isRunning(), "the matrix child did not start");
    }

    /** The one registered child, as the RSS watchdog sees it. */
    private SubprocessInfo matrixChild() {
        List<SubprocessInfo> children = registry.listAll();
        assertEquals(1, children.size(), "expected one registered child: " + children);
        return children.get(0);
    }

    /** The handler the RSS watchdog finds for that child. */
    private RestartableSubprocess watchdogHandler() {
        String id = matrixChild().id();
        return registry.getRestartHandler(id).orElseThrow(() -> new AssertionError("no restart handler under " + id));
    }

    /** Wait for the respawn thread, if one was started, to finish. */
    private void awaitRestartDone() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (launcher.restarting.get()) {
            assertTrue(System.nanoTime() < deadline, "the restart did not finish");
            Thread.sleep(20);
        }
    }

    private List<String> eventsAtOrAbove(Level level) {
        // The launcher's threads append while holding the appender's lock
        synchronized (appender) {
            return appender.list.stream()
                    .filter(event -> event.getLevel().isGreaterOrEqual(level))
                    .map(event -> event.getLevel() + " " + event.getFormattedMessage())
                    .toList();
        }
    }

    /** The real launcher, with {@link IdleChild} in place of the matrix server. */
    static final class IdleMatrixLauncher extends GraphMatrixSubprocessLauncher {

        final AtomicInteger startCalls = new AtomicInteger();
        /** The start call during which shutdown begins; 0 for none. */
        volatile int shutdownAtStart;

        IdleMatrixLauncher(SubprocessRegistry registry) {
            subprocessRegistry = registry;
        }

        @Override
        protected ManagedRun startProcess(String runId, String jobId, List<String> programArgs,
                                          StructuredLineHandler structuredHandler) throws IOException {
            if (startCalls.incrementAndGet() == shutdownAtStart) {
                shutdown();
            }
            return super.startProcess(runId, jobId, programArgs, structuredHandler);
        }

        @Override
        protected List<String> buildJvmCommand(List<String> programArgs) {
            List<String> command = new ArrayList<>(List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx32m",
                    "-cp", System.getProperty("java.class.path"), IdleChild.class.getName()));
            command.addAll(programArgs);
            return command;
        }
    }

    /** A child that runs until it is stopped, or until the test JVM exits and so closes its stdin. */
    public static final class IdleChild {

        private IdleChild() {
        }

        public static void main(String[] args) throws IOException {
            while (System.in.read() != -1) {
                // nothing to do but wait
            }
        }
    }
}
