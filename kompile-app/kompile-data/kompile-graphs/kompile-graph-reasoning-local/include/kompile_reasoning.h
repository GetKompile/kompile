/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef KOMPILE_REASONING_H
#define KOMPILE_REASONING_H

/*
 * kompile_reasoning.h - canonical public contract for libkompile_reasoning
 *
 * This is the CHECKED-IN, VERSIONED public header for the Kompile graph-reasoning
 * C library.  It is self-contained: no GraalVM headers required.  The opaque
 * handle types defined here are pointer-compatible with the corresponding GraalVM
 * types (graal_isolate_t / graal_isolatethread_t), so you can pass them to any
 * GraalVM API that also accepts those types - but including graal_isolate.h is
 * never needed when using this header alone.
 *
 * --- ABI version policy ------------------------------------------------------
 *
 *   kgr_abi_version() returns an integer.  The current ABI version is 1.
 *
 *   Minor additions (new kgr_* functions, new JSON keys in existing responses)
 *   do NOT bump the ABI version - they are always backward-compatible.
 *
 *   Breaking changes (removed functions, changed parameter types, changed
 *   meaning of existing JSON keys) bump the ABI version.  Check at startup:
 *
 *       if (kgr_abi_version(thread) != KGR_ABI_VERSION) { return 1; }  // abort
 *
 * --- Memory ownership --------------------------------------------------------
 *
 *   Every char* returned by kgr_last_error(), kgr_tools(), or kgr_dispatch() is a NUL-terminated
 *   UTF-8 string allocated in unmanaged (C-heap) memory by the library.  YOU
 *   MUST release it by calling kgr_free(thread, ptr) after copying its contents.
 *   Passing it to free(3) directly, or failing to call kgr_free, are both bugs.
 *
 *   Session handles (long long) are opaque integers; they require no explicit
 *   allocation or deallocation beyond kgr_open / kgr_close.
 *
 * --- Error contract ----------------------------------------------------------
 *
 *   The library NEVER throws exceptions across the C boundary.  All failures
 *   are surfaced via return values:
 *
 *     - Functions that return int: 0 = success, non-zero = failure.
 *     - kgr_open: 0 = failure; > 0 = valid session handle.
 *       Call kgr_last_error() after failure for the complete native diagnostic.
 *     - kgr_tools / kgr_dispatch: the returned JSON may contain:
 *           {"status":"ERROR","message":"..."}
 *       when the operation failed.  A non-NULL return is guaranteed (the
 *       library never returns NULL for these two).
 *
 * --- Thread rules ------------------------------------------------------------
 *
 *   - One kgr_thread_t* per OS thread.  Handles are NOT shareable across threads.
 *   - The creating thread gets its handle from kgr_create_isolate.
 *   - Additional threads: call kgr_attach_thread on entry, kgr_detach_thread on exit.
 *   - kgr_tear_down_isolate invalidates ALL thread handles for this isolate.
 *
 * --- Minimal C usage example -------------------------------------------------
 *
 *   #include "kompile_reasoning.h"
 *   #include <stdio.h>
 *   #include <string.h>
 *
 *   int main(void) {
 *       // 1. Create isolate (no-arg builtin; returns thread directly)
 *       kgr_thread_t *thread = kgr_create_isolate();
 *       if (!thread) return 1;
 *
 *       // 2. Check ABI version
 *       if (kgr_abi_version(thread) != KGR_ABI_VERSION) {
 *           fprintf(stderr, "ABI version mismatch\n");
 *           kgr_tear_down_isolate(thread);
 *           return 1;
 *       }
 *
 *       // 3. Open a .kgraph file
 *       long long session = kgr_open(thread, "/path/to/project.kgraph");
 *       if (session == 0) { kgr_tear_down_isolate(thread); return 1; }
 *
 *       // 4. List available tools
 *       const char *catalog = kgr_tools(thread);
 *       printf("Tools: %s\n", catalog);
 *       kgr_free(thread, catalog);
 *
 *       // 5. Dispatch a tool call
 *       const char *result = kgr_dispatch(thread, session,
 *                                         "graph_reasoning_query",
 *                                         "{\"operation\":\"OVERVIEW\"}");
 *       printf("Result: %s\n", result);
 *       kgr_free(thread, result);
 *
 *       // 6. Close session and tear down
 *       kgr_close(thread, session);
 *       kgr_tear_down_isolate(thread);
 *       return 0;
 *   }
 *
 * --- Calling from JNA (Kotlin / Java) ---------------------------------------
 *
 *   See bindings/python/kompile_reasoning.py for a ctypes example.
 *   JNA pattern:
 *
 *       interface KomReasoningLib : Library {
 *           // CREATE_ISOLATE builtin: no args, returns thread directly
 *           fun kgr_create_isolate(): Pointer
 *           fun kgr_attach_thread(isolate: Pointer): Pointer
 *           fun kgr_detach_thread(thread: Pointer): Int
 *           fun kgr_tear_down_isolate(thread: Pointer): Int
 *           fun kgr_abi_version(thread: Pointer): Int
 *           fun kgr_open(thread: Pointer, kgraphPath: String): Long
 *           fun kgr_last_error(thread: Pointer): Pointer
 *           fun kgr_tools(thread: Pointer): Pointer
 *           fun kgr_dispatch(thread: Pointer, sessionId: Long,
 *                            toolName: String, argsJson: String): Pointer
 *           fun kgr_save(thread: Pointer, sessionId: Long, path: String): Int
 *           fun kgr_free(thread: Pointer, result: Pointer)
 *           fun kgr_close(thread: Pointer, sessionId: Long)
 *       }
 *       val lib = Native.load("kompile_reasoning", KomReasoningLib::class.java)
 *       val thread = lib.kgr_create_isolate()  // no args!
 *
 * --- Calling from Swift ------------------------------------------------------
 *
 *   Add libkompile_reasoning.so / .dylib to your Xcode target and import this
 *   header via a bridging header or module map.  Swift sees all kgr_* functions
 *   as top-level C functions.  Use UnsafePointer<CChar> for char* parameters and
 *   String(cString:) to copy returned strings before calling kgr_free.
 *
 * --- Calling from Python (ctypes) --------------------------------------------
 *
 *   See bindings/python/kompile_reasoning.py - a ready-made wrapper that handles
 *   library loading, isolate lifecycle, string encoding/decoding, and kgr_free.
 */

#ifdef __cplusplus
extern "C" {
#endif

/* -- ABI version -------------------------------------------------------------
 * Bump when a breaking change is made (parameter types, removed symbols, changed
 * response semantics).  Additive changes (new functions, new JSON keys) do NOT
 * bump this value.
 */
#define KGR_ABI_VERSION 1

/* -- Opaque handle types -----------------------------------------------------
 *
 * kgr_isolate_t  : represents a GraalVM isolate (an independent heap + thread
 *                  registry).  One per process is the typical pattern.
 * kgr_thread_t   : represents one OS thread's attachment to an isolate.  Each
 *                  OS thread needs its own handle; handles are NOT thread-safe
 *                  to share.
 *
 * These structs are intentionally opaque (forward declaration only).  Their
 * binary layout is identical to graal_isolate_t / graal_isolatethread_t, so
 * they may be safely cast to those types if you also link against GraalVM's
 * graal_isolate.h - but you never need to.
 */
struct _kgr_isolate_t;
typedef struct _kgr_isolate_t kgr_isolate_t;

struct _kgr_thread_t;
typedef struct _kgr_thread_t kgr_thread_t;

/* Session handle: opaque integer > 0 on success; 0 signals "no session". */
typedef long long kgr_session_t;


/* -- Isolate lifecycle -------------------------------------------------------
 *
 * These four functions are GraalVM BUILTIN implementations compiled directly
 * into libkompile_reasoning.so.  They eliminate the need to include or link
 * against graal_isolate.h.
 *
 * Builtin signatures (non-obvious note): GraalVM's CREATE_ISOLATE builtin uses
 * a simplified C ABI - kgr_create_isolate() takes NO arguments and returns the
 * thread handle directly (not via out-parameters like graal_create_isolate does).
 * kgr_attach_thread() returns the thread directly rather than via out-parameter.
 * These differ from the graal_create_isolate/graal_attach_thread signatures in
 * graal_isolate.h; the builtin variants are simpler and library-specific.
 */

/**
 * Create a new GraalVM isolate, attach the calling OS thread, and return the
 * thread handle.
 *
 * Single-threaded pattern:
 *   kgr_thread_t *thread = kgr_create_isolate();
 *   if (!thread) return 1;    // handle error
 *   // ... use thread for all kgr_* calls ...
 *   kgr_tear_down_isolate(thread);
 *
 * @return thread handle (non-NULL) on success; NULL on failure.
 */
kgr_thread_t *kgr_create_isolate(void);

/**
 * Attach the calling OS thread to an existing isolate and return its thread handle.
 * Call this on any thread that did not call kgr_create_isolate before its first
 * kgr_* call.  Pair with kgr_detach_thread when the thread exits.
 *
 * To get the isolate pointer from an existing thread handle use graal_get_isolate()
 * (from graal_isolate.h) or keep a reference from your own bookkeeping.
 *
 * @param isolate the isolate to attach to (obtained from kgr_create_isolate or
 *                graal_get_isolate).
 * @return thread handle (non-NULL) on success; NULL on failure.
 */
kgr_thread_t *kgr_attach_thread(kgr_isolate_t *isolate);

/**
 * Detach the calling OS thread from its isolate.
 * After this call the thread handle is invalid.  The isolate remains alive.
 *
 * @param thread the handle returned by kgr_create_isolate or kgr_attach_thread.
 * @return 0 on success, non-zero on failure.
 */
int kgr_detach_thread(kgr_thread_t *thread);

/**
 * Tear down the isolate: release all heap, invalidate all thread handles.
 * After this call no kgr_* function may be called until a new isolate is
 * created.
 *
 * @param thread any still-attached thread handle for this isolate.
 * @return 0 on success, non-zero on failure.
 */
int kgr_tear_down_isolate(kgr_thread_t *thread);


/* -- ABI version query -------------------------------------------------------*/

/**
 * Return the ABI version of this library build.
 * Compare against KGR_ABI_VERSION at startup to detect incompatible builds.
 *
 * @param thread a valid attached thread handle.
 * @return KGR_ABI_VERSION (currently 1).
 */
int kgr_abi_version(kgr_thread_t *thread);


/* -- Session management ------------------------------------------------------*/

/**
 * Open a .kgraph file and return a session handle.
 *
 * If kgraph_path is NULL or an empty string, an empty session is created
 * (useful for building a graph programmatically via kgr_dispatch assert calls).
 *
 * Sessions are independent: multiple sessions may be open concurrently within
 * the same isolate, but each session is NOT thread-safe - dispatch all calls
 * for a given session from the same OS thread.
 *
 * @param thread      a valid attached thread handle.
 * @param kgraph_path NUL-terminated UTF-8 path to the .kgraph file, or NULL.
 * @return session handle > 0 on success; 0 on failure.
 */
kgr_session_t kgr_open(kgr_thread_t *thread, const char *kgraph_path);

/**
 * Return the diagnostic from the most recent failed operation on this isolate thread.
 * The returned UTF-8 string must be freed with kgr_free().
 *
 * @param thread a valid attached thread handle.
 * @return heap-allocated diagnostic string; free with kgr_free.
 */
const char *kgr_last_error(kgr_thread_t *thread);

/**
 * Save the session's graph to a .kgraph file.
 *
 * Any mutations made via kgr_dispatch (ask_graph_assert / ask_graph_retract)
 * are included in the saved file.  The session remains open after this call.
 *
 * @param thread     a valid attached thread handle.
 * @param session    the session handle returned by kgr_open.
 * @param path       NUL-terminated UTF-8 destination path.
 * @return 0 on success; 1 if session not found; 2 on I/O or other failure.
 */
int kgr_save(kgr_thread_t *thread, kgr_session_t session, const char *path);

/**
 * Close a session and release all in-memory resources held by it.
 *
 * After this call the session handle is invalid.  Any char* previously
 * returned by kgr_dispatch for this session and not yet freed must still be
 * freed via kgr_free (memory ownership is independent of session lifetime).
 *
 * @param thread  a valid attached thread handle.
 * @param session the session handle to close.
 */
void kgr_close(kgr_thread_t *thread, kgr_session_t session);


/* -- Tool catalog and dispatch -----------------------------------------------*/

/**
 * Return the tool catalog as a UTF-8 JSON array string.
 *
 * The returned string must be freed with kgr_free().  The return value is
 * never NULL: on failure an error-JSON object is returned instead.
 *
 * The catalog describes every tool available via kgr_dispatch: its name,
 * description, and JSON parameter schema.  It is session-independent - the
 * same catalog applies to all open sessions.
 *
 * @param thread a valid attached thread handle.
 * @return heap-allocated NUL-terminated UTF-8 JSON; free with kgr_free.
 */
const char *kgr_tools(kgr_thread_t *thread);

/**
 * Dispatch a tool call against an open session.
 *
 * @param thread    a valid attached thread handle.
 * @param session   the session handle returned by kgr_open.
 * @param tool_name NUL-terminated UTF-8 tool name (e.g. "ask_graph_verify").
 * @param args_json NUL-terminated UTF-8 JSON object with tool arguments,
 *                  or NULL / empty string to pass an empty args object {}.
 * @return heap-allocated NUL-terminated UTF-8 JSON result; free with kgr_free.
 *         Never NULL.  On any failure the JSON contains:
 *             {"status":"ERROR","message":"..."}
 *
 * Example:
 *     const char *r = kgr_dispatch(thread, session,
 *                                  "ask_graph_verify",
 *                                  "{\"atom\":\"WORKS_AT(alice, acme)\"}");
 *     // r -> {"status":"OK","verdict":"SUPPORTED",...}
 *     kgr_free(thread, r);
 */
const char *kgr_dispatch(kgr_thread_t  *thread,
                          kgr_session_t  session,
                          const char    *tool_name,
                          const char    *args_json);

/**
 * Free a string previously returned by kgr_last_error(), kgr_tools(), or kgr_dispatch().
 *
 * Calling kgr_free with a NULL pointer is a no-op.
 * Do NOT call free(3) on these pointers - they are allocated in the library's
 * unmanaged heap region and must be released through this function.
 *
 * @param thread a valid attached thread handle.
 * @param result the pointer to free (may be NULL).
 */
void kgr_free(kgr_thread_t *thread, const char *result);

#ifdef __cplusplus
}
#endif

#endif /* KOMPILE_REASONING_H */
