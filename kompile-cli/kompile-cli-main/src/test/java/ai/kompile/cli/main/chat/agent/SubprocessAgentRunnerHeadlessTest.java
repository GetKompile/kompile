package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.PassthroughStreamParser;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class SubprocessAgentRunnerHeadlessTest {
    @TempDir Path directory;
    static class FakeProcess extends Process {
        final InputStream stdout;
        final boolean wait;
        boolean killed;
        FakeProcess(String output, boolean wait) { this.stdout = new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)); this.wait = wait; }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return killed ? 137 : 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (wait && !killed) { Thread.sleep(Math.min(10, unit.toMillis(timeout))); return false; } return true;
        }
        @Override public int exitValue() { return killed ? 137 : 0; }
        @Override public boolean isAlive() { return wait && !killed; }
        @Override public void destroy() { killed = true; }
        @Override public Process destroyForcibly() { killed = true; return this; }
        @Override public Stream<ProcessHandle> descendants() { return Stream.empty(); }
    }
    class Stub extends SubprocessAgentRunner {
        final FakeProcess process;
        List<String> command;
        Stub(String framework, String output, boolean wait) {
            super(framework, directory.toString(), false, false, null, 0, null, null, null);
            process = new FakeProcess(output, wait);
        }
        @Override protected String resolveHeadlessBinary() { return "agent-binary"; }
        @Override protected Process startHeadlessProcess(ProcessBuilder builder) { command = builder.command(); return process; }
    }
    @Test void exactCodexResumeUsesRecordedThreadAndPreservesPermissionDefaults() {
        var runner = new Stub("codex", "{\"type\":\"thread.started\",\"thread_id\":\"thread-1\"}\n{\"type\":\"message.delta\",\"delta\":\"hello\"}\n", false);
        runner.restoreNativeSession("thread-1");
        var events = new ArrayList<PassthroughStreamParser.PassthroughEvent>();
        assertEquals(0, runner.runHeadless("hi", 1000, events::add).exitCode());
        assertTrue(runner.command.contains("thread-1"));
        assertTrue(runner.command.contains("resume"));
        assertFalse(runner.command.contains("--last"));
        assertFalse(runner.command.contains("--dangerously-bypass-approvals-and-sandbox"));
        assertTrue(events.stream().anyMatch(e -> e instanceof PassthroughStreamParser.SessionInit));
    }
    @Test void changedResumeIdentityFailsClosed() {
        var runner = new Stub("codex", "{\"type\":\"thread.started\",\"thread_id\":\"other\"}\n", false);
        runner.restoreNativeSession("thread-1");
        var result = runner.runHeadless("hi", 1000, e -> { });
        assertNotEquals(0, result.exitCode()); assertTrue(result.error().contains("exact resumed session"));
    }
    @Test void timeoutTerminatesTheOwnedProcess() {
        var runner = new Stub("claude", "", true);
        var result = runner.runHeadless("hi", 20, e -> { });
        assertNotEquals(0, result.exitCode()); assertTrue(result.error().contains("timed out")); assertTrue(runner.process.killed);
    }
    @Test void unknownFrameworkDoesNotStartAProcess() {
        var runner = new Stub("other", "", false);
        assertNotEquals(0, runner.runHeadless("hi", 1000, e -> { }).exitCode()); assertNull(runner.command);
    }
}
