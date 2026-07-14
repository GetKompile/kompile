// KompileReasoning.cs — P/Invoke wrapper for libkompile_reasoning
//
// Source: kompile/kompile-local-sdk/bindings/csharp/KompileReasoning.cs
// Namespace: Kompile.Local.Sdk.Reasoning
// Mirrors the style of SdxLlmRuntime.cs (deeplearning4j/libnd4j/include/dsp/runtime/bindings/csharp)
// C ABI defined in: include/kompile_reasoning.h (ABI version 1)
//
// Library resolution (NativeLibrary.SetDllImportResolver — same pattern as SdxLlmRuntime.cs):
//   1. KGR_LIBRARY env var (explicit absolute path)
//   2. <sdk>/lib/libkompile_reasoning.so (SDK zip layout, relative to this assembly)
//   3. System NativeLibrary.Load("kompile_reasoning") fallback
//
// Threading: all kgr_* calls on a given session must come from the same OS thread.
// The isolate thread handle is bound to its creating OS thread.
//
// Note: Unlike JVM wrappers, .NET CAN call Environment.SetEnvironmentVariable after
// process start (C getenv sees it). No subprocess trick needed.
//
// @license Apache-2.0

using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;

namespace Kompile.Local.Sdk.Reasoning
{
    // ── Constants ─────────────────────────────────────────────────────────────

    public static class KgrConstants
    {
        public const int KGR_ABI_VERSION = 1;
    }

    // ── Native bindings (internal) ────────────────────────────────────────────

    internal static class KgrNative
    {
        private const string LibName = "kompile_reasoning";

        static KgrNative()
        {
            NativeLibrary.SetDllImportResolver(
                typeof(KgrNative).Assembly,
                ResolveLibrary);
        }

        private static IntPtr ResolveLibrary(string libraryName, System.Reflection.Assembly assembly, DllImportSearchPath? searchPath)
        {
            if (libraryName != LibName) return IntPtr.Zero;

            // 1. Explicit env var
            var explicit_ = Environment.GetEnvironmentVariable("KGR_LIBRARY");
            if (!string.IsNullOrEmpty(explicit_) && System.IO.File.Exists(explicit_))
                return NativeLibrary.Load(explicit_);

            // 2. SDK layout: lib/ sibling to this assembly's directory
            var ext = RuntimeInformation.IsOSPlatform(OSPlatform.OSX) ? "dylib" : "so";
            var name = $"libkompile_reasoning.{ext}";
            var asmDir = System.IO.Path.GetDirectoryName(typeof(KgrNative).Assembly.Location) ?? ".";
            var sdkLib = System.IO.Path.Combine(asmDir, "..", "..", "lib", name);
            if (System.IO.File.Exists(sdkLib))
                return NativeLibrary.Load(sdkLib);

            // 3. System fallback
            if (NativeLibrary.TryLoad(LibName, out var handle))
                return handle;

            return IntPtr.Zero;
        }

        // Isolate lifecycle
        [DllImport(LibName, CallingConvention = CallingConvention.Cdecl)]
        public static extern IntPtr kgr_create_isolate();

        [DllImport(LibName, CallingConvention = CallingConvention.Cdecl)]
        public static extern IntPtr kgr_attach_thread(IntPtr isolate);

        [DllImport(LibName, CallingConvention = CallingConvention.Cdecl)]
        public static extern int kgr_detach_thread(IntPtr thread);

        [DllImport(LibName, CallingConvention = CallingConvention.Cdecl)]
        public static extern int kgr_tear_down_isolate(IntPtr thread);

        // ABI version
        [DllImport(LibName, CallingConvention = CallingConvention.Cdecl)]
        public static extern int kgr_abi_version(IntPtr thread);

        // Session management
        [DllImport(LibName, CallingConvention = CallingConvention.Cdecl, CharSet = CharSet.Ansi)]
        public static extern long kgr_open(IntPtr thread, [MarshalAs(UnmanagedType.LPStr)] string? kgraphPath);

        [DllImport(LibName, CallingConvention = CallingConvention.Cdecl, CharSet = CharSet.Ansi)]
        public static extern int kgr_save(IntPtr thread, long sessionId, [MarshalAs(UnmanagedType.LPStr)] string path);

        [DllImport(LibName, CallingConvention = CallingConvention.Cdecl)]
        public static extern void kgr_close(IntPtr thread, long sessionId);

        // Tool catalog + dispatch — return IntPtr (GraalVM-owned) must be freed via kgr_free
        [DllImport(LibName, CallingConvention = CallingConvention.Cdecl)]
        public static extern IntPtr kgr_tools(IntPtr thread);

        [DllImport(LibName, CallingConvention = CallingConvention.Cdecl, CharSet = CharSet.Ansi)]
        public static extern IntPtr kgr_dispatch(
            IntPtr thread,
            long sessionId,
            [MarshalAs(UnmanagedType.LPStr)] string toolName,
            [MarshalAs(UnmanagedType.LPStr)] string argsJson);

        // Memory management — ptr is GraalVM-allocated, do NOT call Marshal.FreeHGlobal
        [DllImport(LibName, CallingConvention = CallingConvention.Cdecl)]
        public static extern void kgr_free(IntPtr thread, IntPtr result);

        /// Helper: read UTF-8 string from IntPtr, then free it via kgr_free.
        public static string FetchAndFree(IntPtr thread, IntPtr ptr)
        {
            if (ptr == IntPtr.Zero) return string.Empty;
            var str = Marshal.PtrToStringUTF8(ptr) ?? string.Empty;
            kgr_free(thread, ptr);
            return str;
        }
    }

    // ── KgrSession ────────────────────────────────────────────────────────────

    /// A single open reasoning session over a `.kgraph` file.
    /// Implements IDisposable — use `using` to ensure the session is closed.
    public sealed class KgrSession : IDisposable
    {
        private readonly IntPtr _thread;
        private long _sessionId;

        internal KgrSession(IntPtr thread, long sessionId)
        {
            _thread = thread;
            _sessionId = sessionId;
        }

        /// Return the tools catalog as a list of tool descriptor dictionaries.
        public List<JsonElement> Tools()
        {
            var json = KgrNative.FetchAndFree(_thread, KgrNative.kgr_tools(_thread));
            return JsonSerializer.Deserialize<List<JsonElement>>(json) ?? new();
        }

        /// Return the tools catalog as a raw JSON string.
        public string ToolsJson()
        {
            return KgrNative.FetchAndFree(_thread, KgrNative.kgr_tools(_thread));
        }

        /// Dispatch a tool call and return the result as a parsed JsonElement.
        ///
        /// <param name="tool">Tool name, e.g. "ask_graph_verify".</param>
        /// <param name="argsJson">Arguments as a JSON string, or "{}".</param>
        /// <returns>Parsed JSON result.</returns>
        /// <exception cref="InvalidOperationException">If the result contains {"status":"ERROR",...}.</exception>
        public JsonElement Dispatch(string tool, string argsJson = "{}")
        {
            var ptr = KgrNative.kgr_dispatch(_thread, _sessionId, tool, argsJson);
            var json = KgrNative.FetchAndFree(_thread, ptr);
            var result = JsonSerializer.Deserialize<JsonElement>(json);

            if (result.ValueKind == JsonValueKind.Object &&
                result.TryGetProperty("status", out var status) &&
                status.GetString() == "ERROR")
            {
                var msg = result.TryGetProperty("message", out var m) ? m.GetString() ?? json : json;
                throw new InvalidOperationException($"kgr_dispatch({tool}) ERROR: {msg}");
            }
            return result;
        }

        /// Save the session graph to a `.kgraph` file.
        public void Save(string path)
        {
            var rc = KgrNative.kgr_save(_thread, _sessionId, path);
            if (rc != 0) throw new InvalidOperationException($"kgr_save failed: rc={rc}");
        }

        public void Dispose()
        {
            if (_sessionId != 0)
            {
                KgrNative.kgr_close(_thread, _sessionId);
                _sessionId = 0;
            }
        }
    }

    // ── KgrRuntime ────────────────────────────────────────────────────────────

    /// GraalVM isolate handle. One per process. Implements IDisposable.
    public sealed class KgrRuntime : IDisposable
    {
        private IntPtr _thread;

        private KgrRuntime(IntPtr thread)
        {
            _thread = thread;
        }

        /// Create a new isolate and verify the ABI version.
        public static KgrRuntime Create()
        {
            var thread = KgrNative.kgr_create_isolate();
            if (thread == IntPtr.Zero)
                throw new InvalidOperationException("kgr_create_isolate returned NULL");

            var abi = KgrNative.kgr_abi_version(thread);
            if (abi != KgrConstants.KGR_ABI_VERSION)
            {
                KgrNative.kgr_tear_down_isolate(thread);
                throw new InvalidOperationException(
                    $"KGR ABI version mismatch: library={abi}, expected={KgrConstants.KGR_ABI_VERSION}");
            }
            return new KgrRuntime(thread);
        }

        /// ABI version from the loaded library.
        public int AbiVersion => KgrNative.kgr_abi_version(_thread);

        /// Open a `.kgraph` file and return a session.
        /// Pass null for an empty session (build graph via assert dispatches).
        public KgrSession Open(string? kgraphPath = null)
        {
            var id = KgrNative.kgr_open(_thread, kgraphPath);
            if (id == 0)
                throw new InvalidOperationException($"kgr_open failed for path: {kgraphPath ?? "(null)"}");
            return new KgrSession(_thread, id);
        }

        public void Dispose()
        {
            if (_thread != IntPtr.Zero)
            {
                KgrNative.kgr_tear_down_isolate(_thread);
                _thread = IntPtr.Zero;
            }
        }
    }
}
