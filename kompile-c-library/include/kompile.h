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

#ifndef KOMPILE_H
#define KOMPILE_H

/*
 * kompile.h - canonical public contract for libkompile_pipelines
 *
 * This is the CHECKED-IN, VERSIONED public header for the Kompile pipeline
 * C library.  It is self-contained: no GraalVM headers required.  The opaque
 * handle types defined here are pointer-compatible with the corresponding
 * GraalVM types (graal_isolate_t / graal_isolatethread_t), so they can be cast
 * to those types if you also include graal_isolate.h - but including that header
 * is never required when using this file alone.
 *
 * --- ABI version policy -------------------------------------------------------
 *
 *   kompileAbiVersion() returns an integer.  The current ABI version is 1.
 *
 *   Minor additions (new exported functions) do NOT bump the ABI version.
 *
 *   Breaking changes (removed functions, changed parameter types, changed
 *   meaning of existing parameters) bump the ABI version.  Check at startup:
 *
 *       kompile_thread_t *thread = kompileCreateIsolate();
 *       if (!thread) return 1;
 *       if (kompileAbiVersion(thread) != KOMPILE_ABI_VERSION) { return 1; }
 *
 * --- Isolate lifecycle --------------------------------------------------------
 *
 * Two equivalent paths to obtain a thread handle:
 *
 * PATH A — new-style builtin API (recommended; no graal_isolate.h needed):
 *
 *   kompile_thread_t *thread = kompileCreateIsolate();
 *   if (!thread) return 1;
 *   // ... use thread for all pipeline calls ...
 *   kompileTearDownIsolate(thread);
 *
 * PATH B — legacy graal_create_isolate style (existing consumers; still works
 *   if the caller includes graal_isolate.h themselves; not required by this
 *   header):
 *
 *   #include <graal_isolate.h>
 *   graal_isolate_t *iso = NULL; graal_isolatethread_t *thr = NULL;
 *   graal_create_isolate(NULL, &iso, &thr);
 *   initPipeline((kompile_thread_t *)thr, &h, path);
 *   graal_tear_down_isolate(thr);
 *
 * Both paths produce a pointer that is binary-identical and can be cast freely
 * between graal_isolatethread_t* and kompile_thread_t*.
 *
 * --- Builtin signature note ---------------------------------------------------
 *
 *   GraalVM's CREATE_ISOLATE and ATTACH_THREAD builtins, when exported with
 *   custom names, use a simplified C ABI:
 *
 *     kompileCreateIsolate() takes NO arguments and returns the thread handle
 *     directly (non-NULL = success, NULL = failure).  This differs from
 *     graal_create_isolate's 3-parameter style.
 *
 *     kompileAttachThread() takes only the isolate and returns the thread
 *     handle directly (non-NULL = success, NULL = failure).
 *
 *   These match the signatures native-image emits into libkompile_pipelines.h.
 *
 * --- Thread rules -------------------------------------------------------------
 *
 *   - One kompile_thread_t* per OS thread.  Handles are NOT shareable across
 *     threads.
 *   - The creating thread gets its handle from kompileCreateIsolate().
 *   - Additional threads: call kompileAttachThread() on entry,
 *     kompileDetachThread() on exit.
 *   - kompileTearDownIsolate() invalidates ALL thread handles for this isolate.
 *
 * --- Error contract -----------------------------------------------------------
 *
 *   Functions returning int:              0 = success, non-zero = failure.
 *   kompileCreateIsolate / kompileAttachThread: non-NULL = success, NULL = failure.
 *   Functions taking a thread pointer: behavior is undefined if thread is NULL
 *   or has been detached / the isolate torn down.
 *
 * --- Minimal C usage example (PATH A — self-contained, no graal headers) -----
 *
 *   #include "kompile.h"
 *   #include "numpy_struct.h"
 *   #include <stdio.h>
 *
 *   int main(void) {
 *       // 1. Create isolate (no-arg builtin; returns thread directly)
 *       kompile_thread_t *thread = kompileCreateIsolate();
 *       if (!thread) return 1;
 *
 *       // 2. Check ABI version
 *       if (kompileAbiVersion(thread) != KOMPILE_ABI_VERSION) {
 *           fprintf(stderr, "ABI version mismatch\n");
 *           kompileTearDownIsolate(thread);
 *           return 1;
 *       }
 *
 *       // 3. Init pipeline
 *       handles h = {0};
 *       if (initPipeline(thread, &h, "/path/to/pipeline.json") != 0) {
 *           kompileTearDownIsolate(thread);
 *           return 1;
 *       }
 *
 *       // 4. Prepare and run pipeline
 *       numpy_struct input = {0}, result = {0};
 *       // ... populate input ...
 *       runPipeline(thread, &h, &input, &result);
 *
 *       // 5. Print metrics
 *       printMetrics(thread);
 *
 *       // 6. Tear down
 *       kompileTearDownIsolate(thread);
 *       return 0;
 *   }
 *
 * --- Calling from JNA (Kotlin / Java) ----------------------------------------
 *
 *   interface KomPipelinesLib : Library {
 *       // Lifecycle builtins (PATH A) — simplified no-arg / single-arg forms
 *       fun kompileCreateIsolate(): Pointer         // no args; returns thread
 *       fun kompileAttachThread(isolate: Pointer): Pointer
 *       fun kompileDetachThread(thread: Pointer): Int
 *       fun kompileTearDownIsolate(thread: Pointer): Int
 *       fun kompileAbiVersion(thread: Pointer): Int
 *       // Pipeline operations
 *       fun initPipeline(thread: Pointer, handles: Pointer, pipelinePath: String): Int
 *       fun runPipeline(thread: Pointer, handles: Pointer,
 *                       input: Pointer, result: Pointer): Int
 *       fun printMetrics(thread: Pointer)
 *   }
 *   val lib = Native.load("kompile_pipelines", KomPipelinesLib::class.java)
 *   val thread = lib.kompileCreateIsolate()  // no args!
 */

#include <numpy_struct.h>

#ifdef __cplusplus
extern "C" {
#endif

/* -- ABI version -------------------------------------------------------------
 * Bump when a breaking change is made (parameter types, removed symbols, changed
 * semantics).  Additive changes (new functions) do NOT bump this value.
 */
#define KOMPILE_ABI_VERSION 1

/* -- Opaque handle types -----------------------------------------------------
 *
 * kompile_isolate_t   : represents a GraalVM isolate (an independent heap +
 *                       thread registry).  One per process is the typical pattern.
 * kompile_thread_t    : represents one OS thread's attachment to an isolate.
 *                       Each OS thread needs its own handle; handles are NOT
 *                       thread-safe to share.
 *
 * These structs are intentionally opaque (forward declaration only).  Their
 * binary layout is identical to graal_isolate_t / graal_isolatethread_t, so
 * they may be safely cast to those types if you also link against GraalVM's
 * graal_isolate.h - but you never need to.
 */
struct _kompile_isolate_t;
typedef struct _kompile_isolate_t kompile_isolate_t;

struct _kompile_thread_t;
typedef struct _kompile_thread_t kompile_thread_t;


/* -- Isolate lifecycle (builtin, PATH A) ------------------------------------
 *
 * These four functions are GraalVM BUILTIN implementations compiled directly
 * into libkompile_pipelines.so.  They eliminate the need to include or link
 * against graal_isolate.h.
 *
 * Actual C signatures emitted by native-image for these BUILTIN entry points:
 *
 *   kompileCreateIsolate():         takes NO args; returns thread (NULL = fail)
 *   kompileAttachThread(isolate):   returns thread (NULL = fail)
 *   kompileDetachThread(thread):    returns int   (0 = success)
 *   kompileTearDownIsolate(thread): returns int   (0 = success)
 *
 * These differ from the 3-parameter graal_create_isolate() style — the
 * builtin variants are simpler and library-specific.
 */

/**
 * Create a new GraalVM isolate, attach the calling OS thread, and return the
 * thread handle directly.
 *
 * Single-threaded pattern:
 *   kompile_thread_t *thread = kompileCreateIsolate();
 *   if (!thread) return 1;     // handle error
 *   // ... use thread for all pipeline calls ...
 *   kompileTearDownIsolate(thread);
 *
 * @return thread handle (non-NULL) on success; NULL on failure.
 */
kompile_thread_t *kompileCreateIsolate(void);

/**
 * Attach the calling OS thread to an existing isolate and return its thread
 * handle.  Call this on any thread that did not call kompileCreateIsolate()
 * before its first pipeline call.  Pair with kompileDetachThread() when the
 * thread exits.
 *
 * To get the isolate pointer from an existing thread handle, use
 * graal_get_isolate() (from graal_isolate.h) or keep your own reference.
 *
 * @param isolate the isolate to attach to (obtained from your own bookkeeping
 *                or by casting the kompile_isolate_t* stored during init).
 * @return thread handle (non-NULL) on success; NULL on failure.
 */
kompile_thread_t *kompileAttachThread(kompile_isolate_t *isolate);

/**
 * Detach the calling OS thread from its isolate and release its thread handle.
 * After this call the thread handle is invalid.  The isolate remains alive.
 *
 * @param thread the handle returned by kompileCreateIsolate() or
 *               kompileAttachThread().
 * @return 0 on success, non-zero on failure.
 */
int kompileDetachThread(kompile_thread_t *thread);

/**
 * Tear down the isolate: release all heap, invalidate all thread handles.
 * After this call no pipeline function may be called until a new isolate is
 * created.
 *
 * @param thread any still-attached thread handle for this isolate.
 * @return 0 on success, non-zero on failure.
 */
int kompileTearDownIsolate(kompile_thread_t *thread);


/* -- ABI version query -------------------------------------------------------*/

/**
 * Return the ABI version of this library build.
 * Compare against KOMPILE_ABI_VERSION at startup to detect incompatible builds.
 *
 * @param thread a valid attached thread handle.
 * @return KOMPILE_ABI_VERSION (currently 1).
 */
int kompileAbiVersion(kompile_thread_t *thread);


/* -- Pipeline operations -----------------------------------------------------
 *
 * All three functions take a thread handle (kompile_thread_t*) as their first
 * argument.  The pointer is ABI-identical with graal_isolatethread_t*; PATH B
 * callers may cast freely.
 */

/**
 * Initialize a pipeline from a JSON configuration file path or inline JSON.
 * The handles struct is populated with pipeline/executor handles for
 * subsequent runPipeline() calls.
 *
 * @param thread        a valid attached thread handle
 * @param h             handles struct to populate (must be zero-initialized)
 * @param pipelinePath  NUL-terminated path to a pipeline JSON file, or an
 *                      inline JSON string starting with '{'
 * @return 0 on success, non-zero on failure
 */
int initPipeline(kompile_thread_t *thread, handles *h, char *pipelinePath);

/**
 * Execute a pipeline with numpy array input and populate result arrays.
 *
 * @param thread   a valid attached thread handle
 * @param h        handles struct populated by initPipeline()
 * @param input    input arrays as numpy_struct (caller owns)
 * @param result   output arrays as numpy_struct (must be pre-allocated by
 *                 caller; the library writes into the pre-allocated slots)
 * @return 0 on success, non-zero on failure
 */
int runPipeline(kompile_thread_t *thread, handles *h,
                numpy_struct *input, numpy_struct *result);

/**
 * Print pipeline execution metrics to stdout (active pipeline/executor counts
 * and pipeline IDs).
 *
 * @param thread  a valid attached thread handle
 */
void printMetrics(kompile_thread_t *thread);

/**
 * GraalVM VM locator symbol — used internally by the runtime to locate the
 * JVM in shared-library mode.  Not intended for direct application use.
 *
 * @param thread  a valid attached thread handle
 */
void vmLocatorSymbol(kompile_thread_t *thread);

#ifdef __cplusplus
}
#endif

#endif /* KOMPILE_H */
