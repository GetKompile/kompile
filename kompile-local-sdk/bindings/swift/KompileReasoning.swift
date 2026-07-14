// KompileReasoning.swift — Swift wrapper for libkompile_reasoning
//
// Source: kompile/kompile-local-sdk/bindings/swift/KompileReasoning.swift
// Mirrors the style of SdxLlm.swift (deeplearning4j/libnd4j/include/dsp/runtime/bindings/swift)
// C ABI defined in: include/kompile_reasoning.h (ABI version 1)
//
// Usage: add libkompile_reasoning.so/.dylib to your target and import this file
// via a bridging header or module map. All kgr_* functions are called directly
// as top-level C functions once the bridging header includes kompile_reasoning.h.
//
// Library resolution at build time:
//   swift build -Xlinker -L<sdk>/lib -Xcc -I<sdk>/include
//
// Threading: KgrIsolate and KgrSession are NOT thread-safe. Dispatch all calls
// from the thread that called KgrIsolate().
//
// @license Apache-2.0

import Foundation

// ── Constants ─────────────────────────────────────────────────────────────────

public let KGR_ABI_VERSION: Int32 = 1

// ── Errors ────────────────────────────────────────────────────────────────────

public enum KgrError: Error {
    case isolateInitFailed
    case abiMismatch(library: Int32, expected: Int32)
    case sessionOpenFailed(path: String)
    case sessionSaveFailed(rc: Int32)
    case dispatchError(tool: String, message: String)
    case invalidJSON(raw: String)
}

extension KgrError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .isolateInitFailed:
            return "kgr_create_isolate returned NULL"
        case let .abiMismatch(lib, exp):
            return "KGR ABI version mismatch: library=\(lib), expected=\(exp)"
        case let .sessionOpenFailed(path):
            return "kgr_open failed for path: \(path)"
        case let .sessionSaveFailed(rc):
            return "kgr_save failed: rc=\(rc)"
        case let .dispatchError(tool, msg):
            return "kgr_dispatch(\(tool)) ERROR: \(msg)"
        case let .invalidJSON(raw):
            return "Invalid JSON from library: \(raw)"
        }
    }
}

// ── KgrSession ────────────────────────────────────────────────────────────────

/// A single open reasoning session over a `.kgraph` file.
/// Close with `close()` or rely on `deinit` for automatic cleanup.
public final class KgrSession {
    private let thread: OpaquePointer  // kgr_thread_t*
    private var sessionId: Int64

    fileprivate init(thread: OpaquePointer, sessionId: Int64) {
        self.thread = thread
        self.sessionId = sessionId
    }

    deinit {
        close()
    }

    /// Return the tools catalog as a parsed JSON array.
    public func tools() throws -> [[String: Any]] {
        let json = try toolsJSON()
        guard let data = json.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        else {
            throw KgrError.invalidJSON(raw: json)
        }
        return array
    }

    /// Return the tools catalog as a raw JSON string.
    public func toolsJSON() throws -> String {
        guard let ptr = kgr_tools(thread) else { return "[]" }
        let result = String(cString: ptr)
        kgr_free(thread, ptr)
        return result
    }

    /// Dispatch a tool call and return the result as a parsed JSON object.
    ///
    /// - Parameters:
    ///   - tool: Tool name, e.g. `"ask_graph_verify"`.
    ///   - argsJSON: Arguments as a JSON string, or `"{}"`.
    /// - Returns: Parsed JSON result dictionary.
    /// - Throws: `KgrError.dispatchError` if the result contains `{"status":"ERROR",...}`.
    public func dispatch(tool: String, argsJSON: String = "{}") throws -> [String: Any] {
        guard let ptr = kgr_dispatch(thread, sessionId, tool, argsJSON) else {
            throw KgrError.dispatchError(tool: tool, message: "(null response)")
        }
        let json = String(cString: ptr)
        kgr_free(thread, ptr)

        guard let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else {
            throw KgrError.invalidJSON(raw: json)
        }
        if let status = obj["status"] as? String, status == "ERROR" {
            let msg = obj["message"] as? String ?? json
            throw KgrError.dispatchError(tool: tool, message: msg)
        }
        return obj
    }

    /// Dispatch with a Swift dictionary as arguments.
    public func dispatch(tool: String, args: [String: Any] = [:]) throws -> [String: Any] {
        let data = try JSONSerialization.data(withJSONObject: args)
        let json = String(data: data, encoding: .utf8) ?? "{}"
        return try dispatch(tool: tool, argsJSON: json)
    }

    /// Save the session graph to a `.kgraph` file.
    public func save(to path: String) throws {
        let rc = kgr_save(thread, sessionId, path)
        if rc != 0 { throw KgrError.sessionSaveFailed(rc: rc) }
    }

    /// Close the session. Safe to call multiple times.
    public func close() {
        if sessionId != 0 {
            kgr_close(thread, sessionId)
            sessionId = 0
        }
    }
}

// ── KgrIsolate ────────────────────────────────────────────────────────────────

/// GraalVM isolate handle. Create one per process with `KgrIsolate()`.
/// Tear down with `close()` or rely on `deinit`.
public final class KgrIsolate {
    private var thread: OpaquePointer?  // kgr_thread_t*

    /// Create a new isolate and verify the ABI version.
    public init() throws {
        guard let t = kgr_create_isolate() else {
            throw KgrError.isolateInitFailed
        }
        let abi = kgr_abi_version(t)
        if abi != KGR_ABI_VERSION {
            kgr_tear_down_isolate(t)
            throw KgrError.abiMismatch(library: abi, expected: KGR_ABI_VERSION)
        }
        self.thread = t
    }

    deinit {
        close()
    }

    /// ABI version from the loaded library.
    public var abiVersion: Int32 {
        guard let t = thread else { return 0 }
        return kgr_abi_version(t)
    }

    /// Open a `.kgraph` file and return a session.
    ///
    /// - Parameter path: Path to the `.kgraph` file. Pass `nil` for an empty session.
    public func open(path: String?) throws -> KgrSession {
        guard let t = thread else { throw KgrError.isolateInitFailed }
        let id: Int64
        if let p = path {
            id = kgr_open(t, p)
        } else {
            id = kgr_open(t, nil)
        }
        if id == 0 {
            throw KgrError.sessionOpenFailed(path: path ?? "(null)")
        }
        return KgrSession(thread: t, sessionId: id)
    }

    /// Tear down the isolate. Invalidates all open sessions.
    public func close() {
        if let t = thread {
            kgr_tear_down_isolate(t)
            thread = nil
        }
    }
}
