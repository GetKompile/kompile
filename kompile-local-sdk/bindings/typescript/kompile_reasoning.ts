/**
 * kompile_reasoning.ts — koffi binding for libkompile_reasoning
 *
 * Source: kompile/kompile-local-sdk/bindings/typescript/kompile_reasoning.ts
 * Mirrors the style of sdx_llm.ts (deeplearning4j/sdx-runtime-examples/typescript/node)
 * C ABI defined in: include/kompile_reasoning.h (ABI version 1)
 *
 * Key difference from sdx_llm.ts: kgr_tools() / kgr_dispatch() return strings owned
 * by the GraalVM heap. We must call kgr_free() after copying. We use koffi's pointer
 * return type and explicit string extraction to get the address before freeing.
 *
 * Library resolution (same priority as sdx_llm.ts):
 *   1. KGR_LIBRARY env var (explicit absolute path)
 *   2. ../lib/libkompile_reasoning.so relative to this file (SDK layout)
 *   3. LD_LIBRARY_PATH / system linker
 *
 * Symbol scoping: koffi.load() wraps dlopen with handle-scoped lookup (equivalent to
 * RTLD_LOCAL). Both libkompile_reasoning and libsdx_llm export the same graal_* /
 * JNI_* symbols — RTLD_GLOBAL would route one library's runtime calls into the other,
 * causing ExceptionInInitializerError. koffi is safe by design; do NOT use
 * NativeLibrary or any mechanism that calls dlopen with RTLD_GLOBAL on either library.
 *
 * Threading: koffi is synchronous on the calling thread. kgr_thread_t is bound
 * to the OS thread — do NOT use a Worker for this binding (no GraalVM stack limit
 * constraint unlike sdxLlm which needs 128 MB stack).
 *
 * @license Apache-2.0
 */

import koffi from "koffi";
import * as path from "path";
import * as fs from "fs";

// ── Constants ─────────────────────────────────────────────────────────────────

export const KGR_ABI_VERSION = 1;

// ── Error type ────────────────────────────────────────────────────────────────

export class KgrError extends Error {
  constructor(
    message: string,
    public readonly tool?: string
  ) {
    super(message);
    this.name = "KgrError";
  }
}

// ── Library resolution ────────────────────────────────────────────────────────

function resolveLibrary(): string {
  // 1. Explicit env override
  const env = process.env["KGR_LIBRARY"];
  if (env && fs.existsSync(env)) return env;

  // 2. SDK layout: lib/ sibling to bindings/typescript/
  const ext = process.platform === "darwin" ? "dylib" : "so";
  const name = `libkompile_reasoning.${ext}`;
  const sdkLib = path.resolve(__dirname, "..", "..", "lib", name);
  if (fs.existsSync(sdkLib)) return sdkLib;

  // 3. Same directory (flat layout)
  const flat = path.resolve(__dirname, name);
  if (fs.existsSync(flat)) return flat;

  throw new Error(
    `Cannot find ${name}. Set KGR_LIBRARY to the absolute path, or place the ` +
    `SDK lib/ directory two levels above this file.`
  );
}

// ── koffi type registration ───────────────────────────────────────────────────

// Opaque pointers — koffi represents them as void*
const VoidPtr = koffi.pointer("void");
const StringPtr = koffi.pointer("char");  // char* (owned by GraalVM, freed via kgr_free)

// ── Raw ABI binding ───────────────────────────────────────────────────────────

let _lib: ReturnType<typeof koffi.load> | null = null;
let _thread: unknown | null = null;

function lib(): ReturnType<typeof koffi.load> {
  if (!_lib) throw new KgrError("KgrRuntime not initialized. Call KgrRuntime.create() first.");
  return _lib;
}

function thread(): unknown {
  if (!_thread) throw new KgrError("KgrRuntime not initialized.");
  return _thread;
}

// ── KgrRuntime ────────────────────────────────────────────────────────────────

/**
 * Top-level handle for the Kompile graph-reasoning library.
 *
 * Creates a GraalVM isolate (one per process). Dispose with .close().
 *
 * Example:
 *   const runtime = KgrRuntime.create();
 *   const session = runtime.open("path/to/graph.kgraph");
 *   const tools = session.tools();
 *   const result = session.dispatch("ask_graph_verify", JSON.stringify({atom: "WORKS_AT(alice, acme)"}));
 *   session.close();
 *   runtime.close();
 */
export class KgrRuntime {
  private readonly _lib: ReturnType<typeof koffi.load>;
  private _thread: unknown;

  // Bound native functions (cached for performance)
  private readonly _kgr_tear_down_isolate: (t: unknown) => number;
  private readonly _kgr_abi_version: (t: unknown) => number;
  private readonly _kgr_open: (t: unknown, path: string | null) => bigint;
  private readonly _kgr_save: (t: unknown, id: bigint, path: string) => number;
  private readonly _kgr_close: (t: unknown, id: bigint) => void;
  private readonly _kgr_tools: (t: unknown) => unknown;     // returns void* (GraalVM ptr)
  private readonly _kgr_dispatch: (t: unknown, id: bigint, tool: string, args: string) => unknown;
  private readonly _kgr_free: (t: unknown, ptr: unknown) => void;

  private constructor(libPath: string) {
    this._lib = koffi.load(libPath);

    // Isolate lifecycle
    const kgr_create_isolate = this._lib.func("kgr_create_isolate", VoidPtr, []);
    this._kgr_tear_down_isolate = this._lib.func("kgr_tear_down_isolate", "int", [VoidPtr]);
    this._lib.func("kgr_attach_thread", VoidPtr, [VoidPtr]);  // register for completeness

    // ABI version + session
    this._kgr_abi_version = this._lib.func("kgr_abi_version", "int", [VoidPtr]);
    this._kgr_open = this._lib.func("kgr_open", "int64", [VoidPtr, "string"]);
    this._kgr_save = this._lib.func("kgr_save", "int", [VoidPtr, "int64", "string"]);
    this._kgr_close = this._lib.func("kgr_close", "void", [VoidPtr, "int64"]);

    // Tool dispatch — return void* so we can pass to kgr_free
    this._kgr_tools = this._lib.func("kgr_tools", VoidPtr, [VoidPtr]);
    this._kgr_dispatch = this._lib.func("kgr_dispatch", VoidPtr, [VoidPtr, "int64", "string", "string"]);
    this._kgr_free = this._lib.func("kgr_free", "void", [VoidPtr, VoidPtr]);

    // Create isolate
    this._thread = kgr_create_isolate();
    if (!this._thread) throw new KgrError("kgr_create_isolate returned NULL");

    const abi = this._kgr_abi_version(this._thread);
    if (abi !== KGR_ABI_VERSION) {
      this._kgr_tear_down_isolate(this._thread);
      throw new KgrError(`KGR ABI version mismatch: library=${abi}, expected=${KGR_ABI_VERSION}`);
    }
  }

  static create(libraryPath?: string): KgrRuntime {
    return new KgrRuntime(libraryPath ?? resolveLibrary());
  }

  get abiVersion(): number {
    return this._kgr_abi_version(this._thread);
  }

  /**
   * Open a .kgraph file and return a KgrSession.
   * Pass null for an empty session (build graph via assert dispatches).
   */
  open(kgraphPath: string | null): KgrSession {
    const id = this._kgr_open(this._thread, kgraphPath);
    if (id === 0n) {
      throw new KgrError(`kgr_open failed for path: ${kgraphPath ?? "(null)"}`);
    }
    return new KgrSession(this, this._thread, id);
  }

  /** Return the tools catalog JSON (session-independent). */
  toolsJson(): string {
    return this._fetchAndFree(this._kgr_tools(this._thread));
  }

  /** @internal */
  _dispatchRaw(thread: unknown, id: bigint, tool: string, argsJson: string): string {
    const ptr = this._kgr_dispatch(thread, id, tool, argsJson);
    return this._fetchAndFree(ptr);
  }

  /** @internal */
  _saveSession(thread: unknown, id: bigint, path: string): void {
    const rc = this._kgr_save(thread, id, path);
    if (rc !== 0) throw new KgrError(`kgr_save failed: rc=${rc}`);
  }

  /** @internal */
  _closeSession(thread: unknown, id: bigint): void {
    this._kgr_close(thread, id);
  }

  /** Extract string from GraalVM-allocated pointer, then free it. */
  private _fetchAndFree(ptr: unknown): string {
    if (!ptr) return "";
    // koffi: read string from void* pointer
    const str = koffi.decode(ptr, "char", 65536).toString("utf8").replace(/\0.*/, "");
    this._kgr_free(this._thread, ptr);
    return str;
  }

  [Symbol.dispose](): void { this.close(); }

  close(): void {
    if (this._thread) {
      this._kgr_tear_down_isolate(this._thread);
      this._thread = null;
    }
  }
}

// ── KgrSession ────────────────────────────────────────────────────────────────

/**
 * A single open reasoning session over a `.kgraph` file.
 * Close with .close() or use `using` (TypeScript 5.2+ explicit resource management).
 */
export class KgrSession {
  private _id: bigint;

  constructor(
    private readonly runtime: KgrRuntime,
    private readonly _thread: unknown,
    id: bigint
  ) {
    this._id = id;
  }

  /** Return the tools catalog as a parsed JSON array. */
  tools(): unknown[] {
    return JSON.parse(this.runtime.toolsJson()) as unknown[];
  }

  /**
   * Dispatch a tool call.
   * @param tool - tool name e.g. "ask_graph_verify"
   * @param args - arguments as a plain object or JSON string
   * @returns parsed JSON result
   * @throws KgrError if the result contains {"status":"ERROR",...}
   */
  dispatch(tool: string, args: Record<string, unknown> | string = {}): unknown {
    const argsJson = typeof args === "string" ? args : JSON.stringify(args);
    const json = this.runtime._dispatchRaw(this._thread, this._id, tool, argsJson);
    const result = JSON.parse(json);
    if (result && typeof result === "object" && (result as Record<string, unknown>)["status"] === "ERROR") {
      throw new KgrError(
        `kgr_dispatch(${tool}) ERROR: ${(result as Record<string, unknown>)["message"] ?? json}`,
        tool
      );
    }
    return result;
  }

  /** Save the session graph to a .kgraph file. */
  save(outputPath: string): void {
    this.runtime._saveSession(this._thread, this._id, outputPath);
  }

  [Symbol.dispose](): void { this.close(); }

  close(): void {
    if (this._id !== 0n) {
      this.runtime._closeSession(this._thread, this._id);
      this._id = 0n;
    }
  }
}
