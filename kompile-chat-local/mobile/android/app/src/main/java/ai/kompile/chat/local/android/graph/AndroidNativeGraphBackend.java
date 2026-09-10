package ai.kompile.chat.local.android.graph;

import ai.kompile.chat.local.GraphToolBackend;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Thread-affine Android graph backend over the JavaCPP {@code kgr_*} transport.
 *
 * <p>A Graal isolate thread handle belongs to exactly one OS thread. Every native
 * graph call, including session open and close, therefore runs on one dedicated
 * process-lifetime executor thread. Callers may safely invoke this facade from arbitrary
 * coroutine/ART threads without sharing the isolate handle across them.</p>
 */
public final class AndroidNativeGraphBackend implements GraphToolBackend {

    private static final int ABI_VERSION = 1;
    private static final GraphRuntimeOwner RUNTIME = new GraphRuntimeOwner();

    private final Object lifecycle = new Object();
    private long sessionId;
    private String catalogJson;
    private boolean closed;

    private AndroidNativeGraphBackend(String kgraphPath) throws IOException {
        NativeSession state = RUNTIME.open(kgraphPath);
        sessionId = state.sessionId();
        catalogJson = state.catalogJson();
    }

    /** Open an existing {@code .kgraph} with the native AOT runtime. */
    public static AndroidNativeGraphBackend open(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return new AndroidNativeGraphBackend(path.toAbsolutePath().normalize().toString());
    }

    /** Create an empty native graph session. */
    public static AndroidNativeGraphBackend empty() throws IOException {
        return new AndroidNativeGraphBackend("");
    }

    @Override
    public String catalogJson() {
        synchronized (lifecycle) {
            requireOpen();
            return catalogJson;
        }
    }

    @Override
    public String execute(String toolName, String argsJson) {
        Objects.requireNonNull(toolName, "toolName");
        String normalizedArgs = argsJson == null || argsJson.isBlank() ? "{}" : argsJson;
        synchronized (lifecycle) {
            requireOpen();
            return RUNTIME.dispatch(sessionId, toolName, normalizedArgs);
        }
    }

    /** Persist the native session without crossing the thread-affinity boundary. */
    public void save(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        synchronized (lifecycle) {
            requireOpen();
            RUNTIME.save(sessionId, path.toAbsolutePath().normalize().toString());
        }
    }

    @Override
    public void close() {
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            closed = true;
            long closingSession = sessionId;
            sessionId = 0;
            catalogJson = null;
            if (closingSession != 0) {
                RUNTIME.close(closingSession);
            }
        }
    }

    private static String copyAndFree(Pointer thread, BytePointer result) {
        if (result == null || result.isNull()) {
            throw new IllegalStateException("Native graph returned a null result");
        }
        try {
            return result.getString(StandardCharsets.UTF_8);
        } finally {
            KompileGraphNative.kgr_free(thread, result);
            result.setNull();
        }
    }

    private static BytePointer utf8(String value) {
        return new BytePointer(value, StandardCharsets.UTF_8);
    }

    private void requireOpen() {
        if (closed || sessionId == 0) {
            throw new IllegalStateException("Native graph backend is closed");
        }
    }

    /**
     * Owns the one Graal isolate permitted by the Android native image.
     *
     * <p>The Android image is built with isolate spawning disabled. Graph sessions may be opened
     * and closed repeatedly, but tearing down this isolate during model churn makes a later graph
     * open attempt bootstrap a forbidden second isolate. Keep the owner thread and isolate alive
     * for the process lifetime; Android process death reclaims them.</p>
     */
    private static final class GraphRuntimeOwner {
        private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "kompile-graph-aot");
            thread.setDaemon(true);
            return thread;
        });
        private Pointer isolateThread;
        private IOException isolateInitializationFailure;

        NativeSession open(String kgraphPath) throws IOException {
            return awaitInitialization(executor.submit(() -> openOnOwnerThread(kgraphPath)));
        }

        String dispatch(long session, String toolName, String argsJson) {
            return await(executor.submit(() -> {
                Pointer thread = requireIsolateThread();
                try (BytePointer tool = utf8(toolName);
                     BytePointer args = utf8(argsJson)) {
                    BytePointer result = KompileGraphNative.kgr_dispatch(
                            thread, session, tool, args);
                    return copyAndFree(thread, result);
                }
            }));
        }

        void save(long session, String destinationPath) throws IOException {
            awaitInitialization(executor.submit(() -> {
                Pointer thread = requireIsolateThread();
                try (BytePointer destination = utf8(destinationPath)) {
                    int status = KompileGraphNative.kgr_save(thread, session, destination);
                    if (status != 0) {
                        throw new IOException("Native graph save failed with status " + status);
                    }
                }
                return null;
            }));
        }

        void close(long session) {
            await(executor.submit(() -> {
                KompileGraphNative.kgr_close(requireIsolateThread(), session);
                return null;
            }));
        }

        private NativeSession openOnOwnerThread(String kgraphPath) throws IOException {
            Pointer thread = initializeIsolateThread();
            long openedSession = 0;
            try {
                try (BytePointer path = utf8(kgraphPath)) {
                    openedSession = KompileGraphNative.kgr_open(thread, path);
                }
                if (openedSession == 0) {
                    BytePointer nativeError = KompileGraphNative.kgr_last_error(thread);
                    String detail = nativeError == null || nativeError.isNull()
                            ? "No native graph error was returned"
                            : copyAndFree(thread, nativeError);
                    throw new IOException("Native graph session could not open: " + detail);
                }
                String catalog = copyAndFree(thread, KompileGraphNative.kgr_tools(thread));
                return new NativeSession(openedSession, catalog);
            } catch (Throwable failure) {
                if (openedSession != 0) {
                    try {
                        KompileGraphNative.kgr_close(thread, openedSession);
                    } catch (Throwable closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                }
                if (failure instanceof IOException ioFailure) {
                    throw ioFailure;
                }
                throw new IOException("Native graph session initialization failed", failure);
            }
        }

        private Pointer initializeIsolateThread() throws IOException {
            if (isolateInitializationFailure != null) {
                throw new IOException(
                        "Native graph isolate initialization previously failed",
                        isolateInitializationFailure);
            }
            if (isolateThread != null && !isolateThread.isNull()) {
                return isolateThread;
            }
            Pointer created = KompileGraphNative.kgr_create_isolate();
            if (created == null || created.isNull()) {
                isolateInitializationFailure =
                        new IOException("Native graph isolate creation failed");
                throw isolateInitializationFailure;
            }
            // Retain the first handle before any validation call. The Android image forbids
            // spawning another isolate even when validation of this one fails.
            isolateThread = created;
            try {
                int abi = KompileGraphNative.kgr_abi_version(created);
                if (abi != ABI_VERSION) {
                    throw new IOException(
                            "Native graph ABI mismatch: expected " + ABI_VERSION + ", got " + abi);
                }
            } catch (Throwable failure) {
                isolateInitializationFailure = failure instanceof IOException ioFailure
                        ? ioFailure
                        : new IOException("Native graph ABI validation failed", failure);
                throw isolateInitializationFailure;
            }
            return isolateThread;
        }

        private Pointer requireIsolateThread() {
            if (isolateThread == null || isolateThread.isNull()) {
                throw new IllegalStateException("Native graph isolate is not initialized");
            }
            return isolateThread;
        }
    }

    private static <T> T awaitInitialization(Future<T> future) throws IOException {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return future.get();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                } catch (ExecutionException failed) {
                    Throwable cause = failed.getCause();
                    if (cause instanceof IOException ioFailure) {
                        throw ioFailure;
                    }
                    throw new IOException("Native graph initialization failed", cause);
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static <T> T await(Future<T> future) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return future.get();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                } catch (ExecutionException failed) {
                    Throwable cause = failed.getCause();
                    if (cause instanceof RuntimeException runtimeFailure) {
                        throw runtimeFailure;
                    }
                    if (cause instanceof Error error) {
                        throw error;
                    }
                    throw new IllegalStateException("Native graph call failed", cause);
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private record NativeSession(long sessionId, String catalogJson) {
    }
}
