/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.graph.reasoning.local.nativeapi;

import ai.kompile.graph.reasoning.local.LocalReasoningSession;
import ai.kompile.graph.reasoning.local.LocalToolDispatcher;
import org.graalvm.nativeimage.Isolate;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.word.WordFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GraalVM native-image {@code @CEntryPoint} shim exporting the {@code kgr_*} C ABI.
 *
 * <p>Exported symbols — isolate lifecycle (builtins, no {@code graal_isolate.h} needed):
 * <pre>
 *   int                   kgr_create_isolate  (void* params, kgr_isolate_t**, kgr_thread_t**)
 *   int                   kgr_attach_thread   (kgr_isolate_t*, kgr_thread_t**)
 *   int                   kgr_detach_thread   (kgr_thread_t*)
 *   int                   kgr_tear_down_isolate(kgr_thread_t*)
 * </pre>
 *
 * <p>Session and reasoning symbols (all require a valid thread handle as first arg):
 * <pre>
 *   int         kgr_abi_version (kgr_thread_t*)
 *   long long   kgr_open        (kgr_thread_t*, const char* kgraph_path)
 *   const char* kgr_last_error  (kgr_thread_t*)
 *   const char* kgr_tools       (kgr_thread_t*)
 *   const char* kgr_dispatch    (kgr_thread_t*, long long session, const char* tool, const char* args)
 *   int         kgr_save        (kgr_thread_t*, long long session, const char* path)
 *   void        kgr_free        (kgr_thread_t*, const char* result)
 *   void        kgr_close       (kgr_thread_t*, long long session)
 * </pre>
 *
 * <h3>Isolate lifecycle (multi-thread rule)</h3>
 * <p>An isolate is a self-contained GraalVM heap and thread registry. Each OS thread that
 * calls into the library MUST hold its own attached thread handle. Handles are NOT
 * shareable across threads. Typical single-threaded usage:
 * <ol>
 *   <li>Call {@code kgr_create_isolate(NULL, &amp;isolate, &amp;thread)} once (creates isolate
 *       + attaches the calling thread).</li>
 *   <li>Pass {@code thread} to every {@code kgr_*} call.</li>
 *   <li>Call {@code kgr_tear_down_isolate(thread)} when done.</li>
 * </ol>
 * For additional OS threads: call {@code kgr_attach_thread(isolate, &amp;thread)} on entry
 * and {@code kgr_detach_thread(thread)} on exit. Swift / Kotlin JNA callers see the same
 * conventions — refer to {@code bindings/} for ready-made wrappers.
 *
 * <h3>Memory contract</h3>
 * <p>Strings returned by {@code kgr_last_error}, {@code kgr_tools}, and
 * {@code kgr_dispatch} are UTF-8 C strings
 * allocated in unmanaged (C-heap) memory via {@link UnmanagedMemory}. The caller is responsible
 * for freeing them with {@code kgr_free}. This ensures GC cannot move them after the JNI
 * boundary.</p>
 *
 * <h3>Error handling</h3>
 * <p>All entry points catch {@link Throwable} and never let exceptions cross the boundary.
 * Failures return 0 / null / error-JSON as appropriate.</p>
 */
public final class GraphReasoningCApi {

    // ── Session registry ──────────────────────────────────────────────────────

    private static final ConcurrentHashMap<Long, LocalReasoningSession> SESSIONS =
            new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_ID = new AtomicLong(1L);
    private static final ThreadLocal<String> LAST_ERROR = ThreadLocal.withInitial(() -> "");
    private static final int MAX_LAST_ERROR_CHARS = 64 * 1024;

    // Shared dispatcher (stateless, thread-safe) — create once at class init.
    private static final LocalToolDispatcher DISPATCHER = LocalToolDispatcher.create();

    // ── Isolate lifecycle builtins ────────────────────────────────────────────
    //
    // These four entry points are GraalVM BUILTIN implementations — the method
    // body is never executed; GraalVM substitutes the real isolate/thread
    // management implementation at native-image build time.  The Java signatures
    // MUST match CEntryPointBuiltins exactly (see com.oracle.svm.core.c.function
    // .CEntryPointBuiltins).  The exported C symbol names are under our control
    // (kgr_*) so consumers never need to include graal_isolate.h.
    //
    // Thread rule: one kgr_thread_t* per OS thread; handles are NOT shared.
    // Single-threaded: kgr_create_isolate → all kgr_* calls → kgr_tear_down_isolate.
    // Additional threads: kgr_attach_thread on entry, kgr_detach_thread on exit.

    /**
     * Create a new GraalVM isolate and attach the calling OS thread.
     * Equivalent to {@code graal_create_isolate} but exported under the {@code kgr_*}
     * namespace so consumers need only {@code kompile_reasoning.h}.
     *
     * <p>C signature (generated by native-image):
     * <pre>
     *   int kgr_create_isolate(graal_create_isolate_params_t* params,
     *                          graal_isolate_t**              isolate,
     *                          graal_isolatethread_t**        thread);
     * </pre>
     *
     * @return 0 on success, non-zero on failure
     */
    @CEntryPoint(name = "kgr_create_isolate",
                 builtin = CEntryPoint.Builtin.CREATE_ISOLATE)
    public static native IsolateThread kgrCreateIsolate();

    /**
     * Attach the calling OS thread to an existing isolate and return a thread handle.
     * Must be called from any thread other than the one that created the isolate,
     * before that thread makes any other {@code kgr_*} calls.
     *
     * <p>C signature (generated by native-image):
     * <pre>
     *   int kgr_attach_thread(graal_isolate_t*        isolate,
     *                         graal_isolatethread_t** thread);
     * </pre>
     *
     * @param isolate the isolate to attach to
     * @return 0 on success, non-zero on failure
     */
    @CEntryPoint(name = "kgr_attach_thread",
                 builtin = CEntryPoint.Builtin.ATTACH_THREAD)
    public static native IsolateThread kgrAttachThread(Isolate isolate);

    /**
     * Detach the calling OS thread from its isolate and release its thread handle.
     * After this call, the thread handle is invalid. The isolate remains alive.
     *
     * <p>C signature (generated by native-image):
     * <pre>
     *   int kgr_detach_thread(graal_isolatethread_t* thread);
     * </pre>
     *
     * @param thread the thread handle to detach
     * @return 0 on success, non-zero on failure
     */
    @CEntryPoint(name = "kgr_detach_thread",
                 builtin = CEntryPoint.Builtin.DETACH_THREAD)
    public static native int kgrDetachThread(IsolateThread thread);

    /**
     * Tear down the isolate: closes all sessions, frees all heap, detaches all threads.
     * After this call no {@code kgr_*} function may be called until a new isolate is
     * created. The thread handle is also invalidated.
     *
     * <p>C signature (generated by native-image):
     * <pre>
     *   int kgr_tear_down_isolate(graal_isolatethread_t* thread);
     * </pre>
     *
     * @param thread any still-attached thread handle for this isolate
     * @return 0 on success, non-zero on failure
     */
    @CEntryPoint(name = "kgr_tear_down_isolate",
                 builtin = CEntryPoint.Builtin.TEAR_DOWN_ISOLATE)
    public static native int kgrTearDownIsolate(IsolateThread thread);

    // ── Session / reasoning entry points ──────────────────────────────────────

    /**
     * Open a {@code .kgraph} file and return a session handle (&gt;0), or 0 on failure.
     *
     * @param thread      the GraalVM isolate thread (injected by the runtime)
     * @param kgraphPath  NUL-terminated UTF-8 path to the {@code .kgraph} file;
     *                    if null or empty, an empty session is created
     * @return session handle &gt;0 on success, 0 on failure
     */
    @CEntryPoint(name = "kgr_open")
    public static long kgrOpen(IsolateThread thread, CCharPointer kgraphPath) {
        try {
            LAST_ERROR.set("");
            LocalReasoningSession session;
            if (kgraphPath.isNull() || kgraphPath.read() == 0) {
                session = LocalReasoningSession.createEmpty();
            } else {
                String pathStr = CTypeConversion.toJavaString(kgraphPath);
                Path p = Paths.get(pathStr);
                session = LocalReasoningSession.open(p);
            }
            long id = NEXT_ID.getAndIncrement();
            SESSIONS.put(id, session);
            return id;
        } catch (Throwable t) {
            LAST_ERROR.set(failureDetails(t));
            return 0L;
        }
    }

    /**
     * Return the complete failure from the most recent C API operation on this isolate thread.
     * The caller must release the returned string with {@code kgr_free}.
     */
    @CEntryPoint(name = "kgr_last_error")
    public static CCharPointer kgrLastError(IsolateThread thread) {
        try {
            String error = LAST_ERROR.get();
            return copyToUnmanaged(error == null || error.isBlank()
                    ? "No native graph error was recorded"
                    : error);
        } catch (Throwable ignored) {
            return WordFactory.nullPointer();
        }
    }

    /**
     * Return the tool catalog as a JSON string (caller must free with {@code kgr_free}).
     *
     * @param thread the GraalVM isolate thread
     * @return unmanaged-memory C string; never null (returns error JSON on failure)
     */
    @CEntryPoint(name = "kgr_tools")
    public static CCharPointer kgrTools(IsolateThread thread) {
        try {
            String json = DISPATCHER.catalog().toJson();
            return copyToUnmanaged(json);
        } catch (Throwable t) {
            return copyToUnmanaged("{\"status\":\"ERROR\",\"message\":\"kgr_tools failed: "
                    + escapeJson(t.getMessage()) + "\"}");
        }
    }

    /**
     * Dispatch a tool call against an open session.
     *
     * @param thread    the GraalVM isolate thread
     * @param sessionId handle returned by {@code kgr_open}
     * @param toolName  NUL-terminated UTF-8 tool name
     * @param argsJson  NUL-terminated UTF-8 JSON args object (may be null / empty)
     * @return unmanaged-memory C string with JSON result; null if session not found
     */
    @CEntryPoint(name = "kgr_dispatch")
    public static CCharPointer kgrDispatch(IsolateThread thread,
                                            long sessionId,
                                            CCharPointer toolName,
                                            CCharPointer argsJson) {
        try {
            LocalReasoningSession session = SESSIONS.get(sessionId);
            if (session == null) {
                return copyToUnmanaged("{\"status\":\"ERROR\",\"message\":\"Session "
                        + sessionId + " not found\"}");
            }
            String tool = toolName.isNull() ? "" : CTypeConversion.toJavaString(toolName);
            String args = argsJson.isNull() ? "{}" : CTypeConversion.toJavaString(argsJson);
            String result = DISPATCHER.dispatch(session, tool, args);
            return copyToUnmanaged(result);
        } catch (Throwable t) {
            return copyToUnmanaged("{\"status\":\"ERROR\",\"message\":\"kgr_dispatch failed: "
                    + escapeJson(t.getMessage()) + "\"}");
        }
    }

    /**
     * Save the session's graph to the given path.
     *
     * @param thread    the GraalVM isolate thread
     * @param sessionId session handle
     * @param path      NUL-terminated UTF-8 path to write
     * @return 0 on success, non-zero on failure
     */
    @CEntryPoint(name = "kgr_save")
    public static int kgrSave(IsolateThread thread, long sessionId, CCharPointer path) {
        try {
            LocalReasoningSession session = SESSIONS.get(sessionId);
            if (session == null) return 1;
            String pathStr = CTypeConversion.toJavaString(path);
            session.save(Paths.get(pathStr));
            return 0;
        } catch (Throwable t) {
            return 2;
        }
    }

    /**
     * Free a C string previously returned by {@code kgr_tools} or {@code kgr_dispatch}.
     *
     * @param thread the GraalVM isolate thread
     * @param result pointer to free (null is a no-op)
     */
    @CEntryPoint(name = "kgr_free")
    public static void kgrFree(IsolateThread thread, CCharPointer result) {
        try {
            if (!result.isNull()) {
                UnmanagedMemory.free(result);
            }
        } catch (Throwable t) {
            // best-effort; never propagate
        }
    }

    /**
     * Close a session and release all in-memory resources.
     *
     * @param thread    the GraalVM isolate thread
     * @param sessionId session handle
     */
    @CEntryPoint(name = "kgr_close")
    public static void kgrClose(IsolateThread thread, long sessionId) {
        try {
            LocalReasoningSession session = SESSIONS.remove(sessionId);
            if (session != null) {
                session.close();
            }
        } catch (Throwable t) {
            // best-effort
        }
    }

    /**
     * Return the ABI version of this library.
     *
     * @param thread the GraalVM isolate thread
     * @return 1
     */
    @CEntryPoint(name = "kgr_abi_version")
    public static int kgrAbiVersion(IsolateThread thread) {
        return 1;
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Copy a Java string into a NUL-terminated UTF-8 C string in unmanaged memory.
     * The returned pointer MUST be freed with {@link UnmanagedMemory#free}.
     */
    private static CCharPointer copyToUnmanaged(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        // Allocate len + 1 for the NUL terminator
        CCharPointer ptr = UnmanagedMemory.malloc(bytes.length + 1);
        for (int i = 0; i < bytes.length; i++) {
            ptr.write(i, bytes[i]);
        }
        ptr.write(bytes.length, (byte) 0); // NUL terminator
        return ptr;
    }

    /** Minimal JSON string escaper for error messages embedded in JSON. */
    private static String escapeJson(String s) {
        if (s == null) return "null";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String failureDetails(Throwable failure) {
        StringWriter text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        String details = text.toString();
        if (details.length() <= MAX_LAST_ERROR_CHARS) {
            return details;
        }
        return details.substring(0, MAX_LAST_ERROR_CHARS) + "\n[truncated]";
    }
}
