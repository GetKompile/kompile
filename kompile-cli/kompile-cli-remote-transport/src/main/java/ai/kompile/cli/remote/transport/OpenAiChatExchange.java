package ai.kompile.cli.remote.transport;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one streaming HTTP response. Request policy, status handling, and deadlines belong to the caller. */
public final class OpenAiChatExchange implements AutoCloseable {
    private final HttpResponse<InputStream> response;
    private final Cancellation cancellation;
    private final AtomicBoolean closed = new AtomicBoolean();

    private OpenAiChatExchange(HttpResponse<InputStream> response, Cancellation cancellation) {
        this.response = response;
        this.cancellation = cancellation;
    }

    /** The host-compatible synchronous send; callers retain their existing guarded stream. */
    public static OpenAiChatExchange send(HttpClient client, HttpRequest request)
            throws IOException, InterruptedException {
        return new OpenAiChatExchange(client.send(request, HttpResponse.BodyHandlers.ofInputStream()), null);
    }

    /**
     * Cancellable before headers and during body reads. A cancellation handle belongs to one
     * exchange attempt; the caller schedules any deadline and must close the exchange/handle.
     */
    public static OpenAiChatExchange send(HttpClient client, HttpRequest request, Cancellation cancellation)
            throws IOException, InterruptedException {
        Objects.requireNonNull(cancellation, "cancellation");
        if (cancellation.isCancelled()) throw cancelledFailure(null);
        CompletableFuture<HttpResponse<InputStream>> future =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        // Attach before binding: a response racing cancellation must still hand its body
        // to the handle, even when cancelling the already-completed future has no effect.
        CompletableFuture<HttpResponse<InputStream>> bound = future.thenApply(response -> {
            cancellation.bindBody(response.body());
            return response;
        });
        cancellation.bindFuture(future);
        try {
            HttpResponse<InputStream> response = bound.get();
            if (cancellation.isCancelled()) throw cancelledFailure(null);
            return new OpenAiChatExchange(response, cancellation);
        } catch (CancellationException failure) {
            throw cancelledFailure(failure);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof CancellationException) throw cancelledFailure(cause);
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IOException("HTTP exchange failed", cause);
        } catch (InterruptedException interrupted) {
            cancellation.cancel();
            throw interrupted;
        } finally {
            cancellation.unbindFuture(future);
        }
    }

    public HttpResponse<InputStream> response() {
        return response;
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        if (cancellation == null) response.body().close();
        else cancellation.closeBody(response.body());
    }

    private static IOException cancelledFailure(Throwable cause) {
        return new IOException("HTTP exchange cancelled", cause);
    }

    /**
     * Thread-safe, idempotent cancellation for one send and its response body. Closing the
     * handle cancels; closing only the exchange releases its body without setting cancelled.
     * No scheduler or readLine polling is required to unblock an active body read.
     */
    public static final class Cancellation implements AutoCloseable {
        private boolean cancelled;
        private CompletableFuture<?> future;
        private InputStream body;

        public synchronized boolean isCancelled() {
            return cancelled;
        }

        public void cancel() {
            CompletableFuture<?> pending;
            InputStream activeBody;
            synchronized (this) {
                if (cancelled) return;
                cancelled = true;
                pending = future;
                future = null;
                activeBody = body;
                body = null;
            }
            // Never invoke arbitrary future/stream code under the handle's monitor.
            if (pending != null) pending.cancel(true);
            closeQuietly(activeBody);
        }

        @Override
        public void close() {
            cancel();
        }

        private void bindFuture(CompletableFuture<?> pending) {
            boolean closeNow;
            synchronized (this) {
                closeNow = cancelled;
                if (!closeNow) future = pending;
            }
            if (closeNow) pending.cancel(true);
        }

        private synchronized void unbindFuture(CompletableFuture<?> pending) {
            if (future == pending) future = null;
        }

        private void bindBody(InputStream incoming) {
            boolean closeNow;
            synchronized (this) {
                closeNow = cancelled;
                if (!closeNow) body = incoming;
            }
            if (closeNow) closeQuietly(incoming);
        }

        private void closeBody(InputStream expected) throws IOException {
            synchronized (this) {
                if (body != expected) return; // Cancellation or exchange.close already claimed it.
                body = null;
            }
            expected.close();
        }

        private static void closeQuietly(InputStream stream) {
            if (stream == null) return;
            try {
                stream.close();
            } catch (IOException ignored) {
                // Cancellation is best-effort; preserve the cancellation flag and original failure.
            }
        }
    }
}
