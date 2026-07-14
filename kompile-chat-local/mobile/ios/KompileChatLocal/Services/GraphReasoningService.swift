import Foundation

/// Wraps the kompile_reasoning C library (kgr_* ABI).
///
/// Threading contract (from kompile_reasoning.h):
///   - ONE kgr_thread_t* per OS thread.  NOT shareable across threads.
///   - ALL kgr_* calls dispatched on a single dedicated serial DispatchQueue.
///   - kgr_free() called for EVERY char* returned by kgr_tools() / kgr_dispatch().
///   - kgr_tear_down_isolate() called in deinit.
///
/// When the KompileReasoning xcframework is absent the service degrades
/// gracefully: isAvailable == false, all dispatch calls return an error JSON.
final class GraphReasoningService: ObservableObject {

    // MARK: - Published state

    @Published var isAvailable: Bool = false
    @Published var isLoaded: Bool = false
    @Published var loadError: String? = nil
    /// Short OVERVIEW summary fetched on graph load (nil until a graph is open).
    @Published var overviewSummary: String? = nil

    // MARK: - Private C-bridge state

    /// All kgr_* calls must run on this queue.
    private let kgrQueue = DispatchQueue(label: "ai.kompile.chat.local.kgr", qos: .userInitiated)

    #if canImport(KompileReasoning)
    /// Thread handle — valid after a successful kgr_create_isolate().
    private var kgrThread: OpaquePointer? = nil
    /// Open session handle; 0 means no session.
    private var sessionId: Int64 = 0
    #endif

    // MARK: - Init / deinit

    init() {
        #if canImport(KompileReasoning)
        isAvailable = true
        #else
        isAvailable = false
        #endif
    }

    deinit {
        tearDown()
    }

    // MARK: - Lifecycle

    /// Create the GraalVM isolate.  Must be called once before any other method.
    /// Safe to call multiple times — no-op if already initialised.
    func initialise() {
        #if canImport(KompileReasoning)
        kgrQueue.async { [weak self] in
            guard let self = self else { return }
            guard self.kgrThread == nil else { return }
            let thread = kgr_create_isolate()
            DispatchQueue.main.async {
                if thread != nil {
                    self.kgrThread = thread
                } else {
                    self.isAvailable = false
                    self.loadError = "kgr_create_isolate() returned NULL"
                }
            }
        }
        #endif
    }

    /// Open a .kgraph file.  Publishes isLoaded and fetches an OVERVIEW summary.
    /// - Parameter path: Absolute filesystem path to the .kgraph file.
    func openGraph(at path: String) {
        #if canImport(KompileReasoning)
        kgrQueue.async { [weak self] in
            guard let self = self else { return }
            guard let thread = self.kgrThread else {
                DispatchQueue.main.async { self.loadError = "Isolate not initialised" }
                return
            }
            let sid = kgr_open(thread, path)
            if sid == 0 {
                DispatchQueue.main.async {
                    self.loadError = "kgr_open() failed for path: \(path)"
                    self.isLoaded = false
                }
                return
            }
            self.sessionId = sid
            DispatchQueue.main.async { self.isLoaded = true }

            // Fetch OVERVIEW summary right after opening
            let result = kgr_dispatch(thread, sid, "graph_reasoning_query", "{\"operation\":\"OVERVIEW\"}")
            var overviewStr: String? = nil
            if let result = result {
                overviewStr = String(cString: result)
                kgr_free(thread, result)
            }
            DispatchQueue.main.async { self.overviewSummary = overviewStr }
        }
        #else
        DispatchQueue.main.async {
            self.loadError = "KompileReasoning library not available on this build"
        }
        #endif
    }

    /// Close the current session.
    func closeGraph() {
        #if canImport(KompileReasoning)
        kgrQueue.async { [weak self] in
            guard let self = self, let thread = self.kgrThread, self.sessionId != 0 else { return }
            kgr_close(thread, self.sessionId)
            self.sessionId = 0
            DispatchQueue.main.async {
                self.isLoaded = false
                self.overviewSummary = nil
            }
        }
        #endif
    }

    /// Save the current session to disk.
    /// - Parameter path: Destination .kgraph path.
    /// - Returns: true on success.
    func saveGraph(to path: String) async -> Bool {
        #if canImport(KompileReasoning)
        return await withCheckedContinuation { continuation in
            kgrQueue.async { [weak self] in
                guard let self = self,
                      let thread = self.kgrThread,
                      self.sessionId != 0 else {
                    continuation.resume(returning: false)
                    return
                }
                let rc = kgr_save(thread, self.sessionId, path)
                continuation.resume(returning: rc == 0)
            }
        }
        #else
        return false
        #endif
    }

    /// Return the tool catalog JSON string.  Returns an empty array "[]" when unavailable.
    func catalogJson() -> String {
        #if canImport(KompileReasoning)
        var result = "[]"
        // Synchronous — must be called on kgrQueue; but catalog is session-independent
        // so we use a semaphore to block the caller's thread safely.
        let sema = DispatchSemaphore(value: 0)
        kgrQueue.async { [weak self] in
            guard let self = self, let thread = self.kgrThread else { sema.signal(); return }
            let raw = kgr_tools(thread)
            if let raw = raw {
                result = String(cString: raw)
                kgr_free(thread, raw)
            }
            sema.signal()
        }
        sema.wait()
        return result
        #else
        return "[]"
        #endif
    }

    /// Dispatch a tool call against the open session.
    /// - Parameters:
    ///   - tool: Tool name string.
    ///   - argsJson: JSON arguments string.
    /// - Returns: JSON result string.
    func dispatch(tool: String, argsJson: String) async -> String {
        #if canImport(KompileReasoning)
        return await withCheckedContinuation { continuation in
            kgrQueue.async { [weak self] in
                guard let self = self,
                      let thread = self.kgrThread,
                      self.sessionId != 0 else {
                    continuation.resume(returning: "{\"status\":\"ERROR\",\"message\":\"No open session\"}")
                    return
                }
                let raw = kgr_dispatch(thread, self.sessionId, tool, argsJson)
                var json = "{\"status\":\"ERROR\",\"message\":\"dispatch returned NULL\"}"
                if let raw = raw {
                    json = String(cString: raw)
                    kgr_free(thread, raw)
                }
                continuation.resume(returning: json)
            }
        }
        #else
        return "{\"status\":\"ERROR\",\"message\":\"KompileReasoning library not available\"}"
        #endif
    }

    // MARK: - Private

    private func tearDown() {
        #if canImport(KompileReasoning)
        kgrQueue.sync { [weak self] in
            guard let self = self else { return }
            if let thread = self.kgrThread {
                if self.sessionId != 0 { kgr_close(thread, self.sessionId) }
                kgr_tear_down_isolate(thread)
            }
            self.kgrThread = nil
            self.sessionId = 0
        }
        #endif
    }
}
