# Kompile Graph Reasoning — Native C Library SDK

`libkompile_reasoning` is a GraalVM-compiled C shared library that loads a
`.kgraph` file and answers tool calls against it locally — no JVM, no server,
no network required.  It is the device-side graph reasoning engine for the
Kompile mobile stack.

## SDK layout

```
include/kompile_reasoning.h      canonical public C header (self-contained)
lib/libkompile_reasoning.so      shared library (Linux x86_64)
bindings/python/kompile_reasoning.py  ctypes wrapper
README.md                        this file
```

## ABI version

The current ABI version is **1** (`KGR_ABI_VERSION` macro in the header).
Check at startup:

```c
if (kgr_abi_version(thread) != KGR_ABI_VERSION) { /* abort */ }
```

Minor additions (new tool names, new JSON response keys) do NOT bump the
version.  Breaking changes (removed functions, changed parameter types) do.

## C quickstart

```c
#include "kompile_reasoning.h"
#include <stdio.h>
#include <string.h>

int main(void) {
    kgr_isolate_t *isolate = NULL;
    kgr_thread_t  *thread  = NULL;

    // 1. Create isolate (no graal_isolate.h needed)
    if (kgr_create_isolate(NULL, &isolate, &thread) != 0) return 1;

    // 2. ABI version check
    if (kgr_abi_version(thread) != KGR_ABI_VERSION) {
        fprintf(stderr, "ABI mismatch\n");
        kgr_tear_down_isolate(thread);
        return 1;
    }

    // 3. Open a .kgraph file
    long long session = kgr_open(thread, "project.kgraph");
    if (session == 0) { kgr_tear_down_isolate(thread); return 1; }

    // 4. Dispatch a tool call
    const char *result = kgr_dispatch(thread, session,
                                      "graph_reasoning_query",
                                      "{\"operation\":\"OVERVIEW\"}");
    printf("%s\n", result);
    kgr_free(thread, result);   // always free returned strings

    // 5. Save mutations and clean up
    kgr_save(thread, session, "updated.kgraph");
    kgr_close(thread, session);
    kgr_tear_down_isolate(thread);
    return 0;
}
```

Compile:
```bash
gcc -I include/ -L lib/ -Wl,-rpath,'$ORIGIN/lib' \
    -o myapp myapp.c -lkompile_reasoning
```

## Thread rules

- One `kgr_thread_t*` per OS thread.  Handles are **not** shareable across threads.
- Creating thread: `kgr_create_isolate` gives you the first handle.
- Additional threads: `kgr_attach_thread` on entry, `kgr_detach_thread` on exit.
- `kgr_tear_down_isolate` invalidates **all** thread handles.

## Memory ownership

Every `const char*` returned by `kgr_tools()` or `kgr_dispatch()` is a
heap-allocated UTF-8 string.  **You must call `kgr_free(thread, ptr)` after
copying its content.**  Never pass these pointers to `free(3)`.

## Error contract

The library never throws exceptions across the C boundary:

| Function | Failure indicator |
|---|---|
| `kgr_create_isolate` | returns non-zero |
| `kgr_attach_thread` / `kgr_detach_thread` / `kgr_tear_down_isolate` | returns non-zero |
| `kgr_open` | returns 0 |
| `kgr_save` | returns non-zero |
| `kgr_tools` / `kgr_dispatch` | returns `{"status":"ERROR","message":"..."}` JSON |

## JNA snippet (Kotlin / Android)

```kotlin
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

interface KomReasoningLib : Library {
    fun kgr_create_isolate(params: Pointer?, isolate: PointerByReference,
                            thread: PointerByReference): Int
    fun kgr_abi_version(thread: Pointer): Int
    fun kgr_open(thread: Pointer, kgraphPath: String): Long
    fun kgr_tools(thread: Pointer): Pointer
    fun kgr_dispatch(thread: Pointer, sessionId: Long,
                     toolName: String, argsJson: String): Pointer
    fun kgr_save(thread: Pointer, sessionId: Long, path: String): Int
    fun kgr_free(thread: Pointer, result: Pointer)
    fun kgr_close(thread: Pointer, sessionId: Long)
    fun kgr_tear_down_isolate(thread: Pointer): Int
}

val lib = Native.load("kompile_reasoning", KomReasoningLib::class.java)
val isolate = PointerByReference()
val thread  = PointerByReference()
lib.kgr_create_isolate(null, isolate, thread)
val session = lib.kgr_open(thread.value, "/data/user/0/…/project.kgraph")
val raw     = lib.kgr_dispatch(thread.value, session,
                               "graph_reasoning_query", """{"operation":"OVERVIEW"}""")
val json    = raw.getString(0, "UTF-8")
lib.kgr_free(thread.value, raw)
lib.kgr_close(thread.value, session)
lib.kgr_tear_down_isolate(thread.value)
```

## ctypes snippet (Python)

See `bindings/python/kompile_reasoning.py` for a complete ready-made wrapper:

```python
from bindings.python.kompile_reasoning import KomReasoningLib

lib = KomReasoningLib("lib/libkompile_reasoning.so")
with lib.session("project.kgraph") as sess:
    overview = sess.dispatch("graph_reasoning_query", {"operation": "OVERVIEW"})
    print(overview)
lib.close()
```

## Available tools

Call `kgr_tools(thread)` to get the full catalog.  Key tools:

| Tool | Description |
|---|---|
| `graph_reasoning_query` | 17 operations: OVERVIEW, SEARCH, DESCRIBE, NEIGHBORS, PATH, FACTS, VERIFY, WHY, WHY_NOT, RANK, SCHEMA, TIMELINE, SIMILAR, RELATIONS, CAPABILITIES, ASSETS, ARTIFACT |
| `ask_graph_verify` | Check if an atom is SUPPORTED / REFUTED / UNKNOWN |
| `ask_graph_query` | Conjunctive pattern query |
| `ask_graph_explain` | Derivation trace for a verdict |
| `ask_graph_assert` | Assert a new atom (write-through to graph) |
| `ask_graph_retract` | Retract an atom (write-through; survives `kgr_save`) |
| `ask_graph_claim` | Build a claim dossier with evidence |
| `ask_graph_synthesize` | Answer synthesis over graph entities |
| `graph_centrality` | PageRank / degree centrality |
| `graph_embeddings` | KGE similarity / nearest-neighbours |

Full parameter schemas are in the catalog JSON returned by `kgr_tools`.

## Android AArch64 shared library

The Android build uses stock GraalVM Native Image as the AOT compiler driver
and Android NDK clang/LLD as the target toolchain. It does not use Gluon or
GluonFX. Run `build-android-ndk.sh`; the audited SDK is written under
`target/android-aot`.

The Android artifact targets bionic/API 28, uses 16 KiB load segments, exports
the exact `kgr_*` ABI, and has no OpenBLAS or host fallback. See
[`src/main/android/README.md`](src/main/android/README.md) for pinned source
provenance, requirements, fast relink options, and the dependency audit.
