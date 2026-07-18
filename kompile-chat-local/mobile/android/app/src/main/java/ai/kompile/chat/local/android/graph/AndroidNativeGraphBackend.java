package ai.kompile.chat.local.android.graph;

import ai.kompile.chat.local.GraphToolBackend;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Thread-affine Android graph backend over the JavaCPP {@code kgr_*} transport.
 *
 * <p>A Graal isolate thread handle belongs to exactly one OS thread. Every native
 * graph call, including open and teardown, therefore runs on one dedicated
 * executor thread. Callers may safely invoke this facade from arbitrary
 * coroutine/ART threads without sharing the isolate handle across them.</p>
 */
public final class AndroidNativeGraphBackend implements GraphToolBackend {

    private static final int ABI_VERSION = 1;

    private final Object lifecycle = new Object();
    private final ExecutorService executor;
    private Pointer isolateThread;
    private long sessionId;
    private String catalogJson;
    private boolean closed;

    private AndroidNativeGraphBackend(String kgraphPath) throws IOException {
        executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "kompile-graph-aot");
            thread.setDaemon(true);
            return thread;
        });

        try {
            NativeState state = awaitInitialization(executor.submit(() -> initialize(kgraphPath)));
            isolateThread = state.thread();
            sessionId = state.sessionId();
            catalogJson = state.catalogJson();
        } catch (IOException failure) {
            executor.shutdownNow();
            throw failure;
        }
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
            return await(executor.submit(() -> dispatch(toolName, normalizedArgs)));
        }
    }

    /** Persist the native session without crossing the thread-affinity boundary. */
    public void save(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        synchronized (lifecycle) {
            requireOpen();
            try {
                await(executor.submit(() -> {
                    try (BytePointer destination = utf8(path.toAbsolutePath().normalize().toString())) {
                        int status = KompileGraphNative.kgr_save(
                                isolateThread, sessionId, destination);
                        if (status != 0) {
                            throw new IOException("Native graph save failed with status " + status);
                        }
                    }
                    return null;
                }));
            } catch (IllegalStateException failure) {
                if (failure.getCause() instanceof IOException ioFailure) {
                    throw ioFailure;
                }
                throw failure;
            }
        }
    }

    @Override
    public void close() {
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            closed = true;
            RuntimeException closeFailure = null;
            try {
                await(executor.submit(() -> {
                    if (sessionId != 0) {
                        KompileGraphNative.kgr_close(isolateThread, sessionId);
                        sessionId = 0;
                    }
                    if (isolateThread != null && !isolateThread.isNull()) {
                        int status = KompileGraphNative.kgr_tear_down_isolate(isolateThread);
                        isolateThread.setNull();
                        if (status != 0) {
                            throw new IllegalStateException(
                                    "Native graph isolate teardown failed with status " + status);
                        }
                    }
                    return null;
                }));
            } catch (RuntimeException failure) {
                closeFailure = failure;
            } finally {
                catalogJson = null;
                executor.shutdownNow();
            }
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    private NativeState initialize(String kgraphPath) throws IOException {
        Pointer thread = null;
        long openedSession = 0;
        try {
            thread = KompileGraphNative.kgr_create_isolate();
            if (thread == null || thread.isNull()) {
                throw new IOException("Native graph isolate creation failed");
            }

            int abi = KompileGraphNative.kgr_abi_version(thread);
            if (abi != ABI_VERSION) {
                throw new IOException(
                        "Native graph ABI mismatch: expected " + ABI_VERSION + ", got " + abi);
            }

            try (BytePointer path = utf8(kgraphPath)) {
                openedSession = KompileGraphNative.kgr_open(thread, path);
            }
            if (openedSession == 0) {
                throw new IOException("Native graph session could not open: " + kgraphPath);
            }

            String catalog = copyAndFree(thread, KompileGraphNative.kgr_tools(thread));
            return new NativeState(thread, openedSession, catalog);
        } catch (Throwable failure) {
            if (openedSession != 0 && thread != null && !thread.isNull()) {
                try {
                    KompileGraphNative.kgr_close(thread, openedSession);
                } catch (Throwable ignored) {
                    failure.addSuppressed(ignored);
                }
            }
            if (thread != null && !thread.isNull()) {
                try {
                    KompileGraphNative.kgr_tear_down_isolate(thread);
                    thread.setNull();
                } catch (Throwable ignored) {
                    failure.addSuppressed(ignored);
                }
            }
            if (failure instanceof IOException ioFailure) {
                throw ioFailure;
            }
            throw new IOException("Native graph runtime initialization failed", failure);
        }
    }

    private String dispatch(String toolName, String argsJson) {
        try (BytePointer tool = utf8(toolName);
             BytePointer args = utf8(argsJson)) {
            BytePointer result = KompileGraphNative.kgr_dispatch(
                    isolateThread, sessionId, tool, args);
            return copyAndFree(isolateThread, result);
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
        if (closed || isolateThread == null || isolateThread.isNull() || sessionId == 0) {
            throw new IllegalStateException("Native graph backend is closed");
        }
    }

    private static NativeState awaitInitialization(Future<NativeState> future) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Native graph initialization interrupted", interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException ioFailure) {
                throw ioFailure;
            }
            throw new IOException("Native graph initialization failed", cause);
        }
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Native graph call interrupted", interrupted);
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

    private record NativeState(Pointer thread, long sessionId, String catalogJson) {
    }
}
