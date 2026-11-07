package ai.kompile.cli.main.extensions.process;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs an extension-owned target, never a native host self-reexec or a shell command string. */
public final class ProcessExtensionCommandRunner {
    private ProcessExtensionCommandRunner() { }

    public static int run(List<String> command, Path workingDirectory, Map<String, String> environment,
                          long timeoutSeconds) throws IOException, InterruptedException, TimeoutException {
        if (command.isEmpty() || timeoutSeconds < 1 || timeoutSeconds > 600) {
            throw new IllegalArgumentException("A command and a timeout of 1..600 seconds are required");
        }
        ProcessBuilder builder = new ProcessBuilder(List.copyOf(command)).inheritIO();
        builder.directory(Objects.requireNonNull(workingDirectory, "workingDirectory").toFile());
        builder.environment().putAll(Map.copyOf(environment));
        Process child = builder.start();
        Map<Long, ProcessHandle> descendants = new java.util.LinkedHashMap<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        try {
            for (;;) {
                child.descendants().forEach(handle -> descendants.putIfAbsent(handle.pid(), handle));
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException("Extension command exceeded " + timeoutSeconds + " seconds");
                if (child.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)), TimeUnit.NANOSECONDS)) {
                    return child.exitValue();
                }
            }
        } finally {
            child.descendants().forEach(handle -> descendants.putIfAbsent(handle.pid(), handle));
            terminate(child, List.copyOf(descendants.values()));
        }
    }

    private static void terminate(Process child, List<ProcessHandle> descendants) throws IOException {
        // Retain observed descendants even after their parent exits normally.
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
        if (child.isAlive()) child.destroy();
        boolean interrupted = false;
        try {
            try { child.waitFor(1, TimeUnit.SECONDS); }
            catch (InterruptedException failure) { interrupted = true; }
            descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            if (child.isAlive()) child.destroyForcibly();
            try { child.waitFor(5, TimeUnit.SECONDS); }
            catch (InterruptedException failure) { interrupted = true; }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (descendants.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
                if (Thread.interrupted()) interrupted = true;
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }
            if (child.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive)) {
                throw new IOException("Extension command process tree did not terminate");
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
