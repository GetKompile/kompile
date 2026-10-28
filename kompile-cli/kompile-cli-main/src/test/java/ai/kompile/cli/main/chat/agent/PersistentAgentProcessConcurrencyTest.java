package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.harness.CliJudgeBackend;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
@Timeout(10)
class PersistentAgentProcessConcurrencyTest {
    @TempDir Path directory;

    private Path script(String body) throws Exception {
        Path file = directory.resolve("fixture-agent");
        Files.writeString(file, "#!/bin/sh\ncd '" + directory.toString().replace("'", "'\"'\"'")
                + "'\nIFS= read -r init\nprintf '%s' $$ > pid\n" + body);
        assertTrue(file.toFile().setExecutable(true));
        return file;
    }

    private PersistentAgentProcess process(Path script) {
        return PersistentAgentProcess.builder(script.toString()).workDir(directory).build();
    }

    @Test
    void fatalDiagnosticBeforeReadyFailsPromptlyInsteadOfWaitingForStartupTimeout() throws Exception {
        Path script = script("printf '%s\\n' 'Error: 401 Unauthorized' >&2\nwhile IFS= read -r line; do :; done\n");
        try (PersistentAgentProcess process = process(script)) {
            long start = System.nanoTime();
            IOException failure = assertThrows(IOException.class, () -> process.start(4));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(failure.getMessage().contains("401"), failure.getMessage());
            assertFalse(process.isAlive());
            assertTrue(elapsedMs < 1500, "fatal diagnostics must wake startup immediately: " + elapsedMs);
            System.out.println("persistent judge fixture: pre-ready auth failure returned in " + elapsedMs + " ms");
        }
    }

    @Test
    void interruptingStartupDestroysTheSpawnedProcess() throws Exception {
        Path script = script("while IFS= read -r line; do :; done\n");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch finished = new CountDownLatch(1);
        try (PersistentAgentProcess process = process(script)) {
            Future<?> startup = executor.submit(() -> {
                try { process.start(30); }
                catch (IOException | InterruptedException expected) { /* cancelled bootstrap */ }
                finally { finished.countDown(); }
            });
            awaitFile("pid");
            long pid = Long.parseLong(Files.readString(directory.resolve("pid")));
            startup.cancel(true);
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            assertFalse(process.isAlive());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void closeDuringSilentStartupDoesNotWaitForReadinessTimeout() throws Exception {
        Path script = script("while IFS= read -r line; do :; done\n");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (PersistentAgentProcess process = process(script)) {
            Future<?> starting = executor.submit(() -> {
                try { process.start(30); }
                catch (IOException | InterruptedException expected) { /* closed bootstrap */ }
            });
            awaitFile("pid");
            long pid = Long.parseLong(Files.readString(directory.resolve("pid")));
            executor.submit(process::close).get(1, TimeUnit.SECONDS);
            starting.get(1, TimeUnit.SECONDS);
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            assertFalse(process.isAlive());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private Path turnScript() throws Exception {
        return script("""
                printf '%s\n' '{"type":"system","subtype":"init"}'
                while IFS= read -r line; do
                  printf '%s\n' "$line" >> requests
                  while [ ! -f release ]; do sleep 0.01; done
                  printf '%s\n' '{"type":"result","result":"verdict"}'
                done
                """);
    }

    @Test
    void queuedDeadlineDoesNotSendARequestOrKillTheActiveTurn() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (PersistentAgentProcess process = process(turnScript())) {
            process.start(3);
            Future<String> first = executor.submit(() -> process.sendMessage("first", 5));
            awaitFile("requests");
            assertThrows(PersistentAgentProcess.NotAdmittedException.class,
                    () -> process.sendMessage("queued", 1));
            assertTrue(process.isAlive(), "queue timeout must not close the active caller's process");
            Files.writeString(directory.resolve("release"), "go");
            assertEquals("verdict", first.get(2, TimeUnit.SECONDS));
            assertEquals(1, Files.readAllLines(directory.resolve("requests")).size());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellingQueuedBackendDoesNotAbortAnotherLeaseActiveVerdict() throws Exception {
        Path script = turnScript();
        var spec = new PersistentJudgeProcessPool.Spec(script.toString(), "", false,
                List.of(), List.of(), "", 3);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        var active = PersistentJudgeProcessPool.acquire(spec);
        var queued = PersistentJudgeProcessPool.acquire(spec);
        CliJudgeBackend backend = new CliJudgeBackend("claude");
        var leaseField = CliJudgeBackend.class.getDeclaredField("judgeLease");
        leaseField.setAccessible(true);
        leaseField.set(backend, queued);
        var generate = CliJudgeBackend.class.getDeclaredMethod("generatePersistent", String.class, String.class);
        generate.setAccessible(true);
        CountDownLatch finished = new CountDownLatch(1);
        CountDownLatch attempting = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            Future<String> first = executor.submit(() -> active.sendMessage("first", 5));
            awaitFile("requests");
            Future<?> second = executor.submit(() -> {
                attempting.countDown();
                try { generate.invoke(backend, "cancelled-queued-request", ""); }
                catch (java.lang.reflect.InvocationTargetException wrapped) { failure.set(wrapped.getCause()); }
                catch (Exception unexpected) { failure.set(unexpected); }
                finally { finished.countDown(); }
            });
            assertTrue(attempting.await(2, TimeUnit.SECONDS));
            second.cancel(true);
            assertTrue(finished.await(1, TimeUnit.SECONDS), "queued cancellation must not wait for the active verdict");
            assertInstanceOf(PersistentAgentProcess.NotAdmittedException.class, failure.get());
            assertTrue(active.isAlive(), "backend must not invalidate the shared process for a request never admitted");
            Files.writeString(directory.resolve("release"), "go");
            assertEquals("verdict", first.get(2, TimeUnit.SECONDS));
            assertEquals(1, Files.readAllLines(directory.resolve("requests")).size());
        } finally {
            Files.writeString(directory.resolve("release"), "go");
            backend.close();
            active.abort();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private void awaitFile(String name) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while ((!Files.exists(directory.resolve(name)) || Files.size(directory.resolve(name)) == 0)
                && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(Files.exists(directory.resolve(name)), "fixture did not create " + name);
    }
}
