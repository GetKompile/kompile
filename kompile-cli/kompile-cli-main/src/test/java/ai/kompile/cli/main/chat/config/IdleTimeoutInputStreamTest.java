package ai.kompile.cli.main.chat.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdleTimeoutInputStreamTest {

    @Test
    void closesAConnectedButSilentBody() throws Exception {
        try (PipedInputStream source = new PipedInputStream();
             PipedOutputStream ignored = new PipedOutputStream(source);
             IdleTimeoutInputStream guarded = new IdleTimeoutInputStream(
                     source, Duration.ofMillis(75))) {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                IdleTimeoutInputStream.StreamIdleTimeoutException failure = assertThrows(
                        IdleTimeoutInputStream.StreamIdleTimeoutException.class,
                        guarded::read);
                assertTrue(failure.getMessage().contains("idle"));
            });
            assertTrue(guarded.timedOut());
        }
    }

    @Test
    void cancellationClosesSilentBodyWithoutWaitingForIdleDeadline() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        try (PipedInputStream source = new PipedInputStream() {
                 @Override public synchronized int read() throws IOException {
                     entered.countDown();
                     return super.read();
                 }
             };
             PipedOutputStream ignored = new PipedOutputStream(source);
             IdleTimeoutInputStream guarded = new IdleTimeoutInputStream(
                     source, Duration.ofMinutes(1), cancelled::get)) {
            FutureTask<IOException> result = new FutureTask<>(() -> assertThrows(IOException.class, guarded::read));
            Thread reader = new Thread(result, "cancel-stream-test");
            reader.setDaemon(true);
            reader.start();
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                cancelled.set(true);
                assertInstanceOf(InterruptedIOException.class, result.get(2, TimeUnit.SECONDS));
                assertFalse(guarded.timedOut(), "User cancellation must not become a retryable idle timeout");
            } finally {
                cancelled.set(true);
                guarded.close();
                reader.interrupt();
                reader.join(2_000);
                assertFalse(reader.isAlive(), "Cancelled stream reader must terminate");
            }
        }
    }

    @Test
    void incomingBytesRearmTheIdleDeadline() throws Exception {
        try (PipedInputStream source = new PipedInputStream();
             PipedOutputStream sink = new PipedOutputStream(source);
             IdleTimeoutInputStream guarded = new IdleTimeoutInputStream(
                     source, Duration.ofMillis(150))) {
            sink.write('a');
            sink.flush();
            assertEquals('a', guarded.read());
            Thread.sleep(75);
            sink.write('b');
            sink.flush();
            assertEquals('b', guarded.read());
        }
    }
}
