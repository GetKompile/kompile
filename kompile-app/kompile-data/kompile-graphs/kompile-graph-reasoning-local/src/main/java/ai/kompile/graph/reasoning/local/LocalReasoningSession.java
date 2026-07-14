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
package ai.kompile.graph.reasoning.local;

import ai.kompile.graph.reasoning.unified.Dtype;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

/**
 * A local reasoning session backed by a single {@link UnifiedGraph} loaded from (or built for)
 * a {@code .kgraph} file.
 *
 * <p>This is the infra-free, no-Spring equivalent of the server-side combination of
 * {@code UnifiedGraphBridge} (load/save) and {@code KbGroundingService} (fact store management).
 * A session owns:</p>
 * <ul>
 *   <li>The loaded {@link UnifiedGraph} — the graph topology, opinions, embeddings, etc.</li>
 *   <li>A {@link LocalKbState} — observed {@link ai.kompile.graph.reasoning.fol.FactStore},
 *       inferred-fact store, and justification index — primed from the graph on load so that
 *       {@code GraphQueryEngine.query()} returns meaningful VERIFY / WHY / SEARCH results
 *       immediately.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * <p>All public methods are {@code synchronized}. A single-caller (MCP tool dispatch)
 * pattern is expected; synchronization prevents accidental corruption if two threads
 * share a session handle.</p>
 *
 * <h3>Lifecycle</h3>
 * <pre>
 *   LocalReasoningSession session = LocalReasoningSession.open(path);
 *   // ... dispatch tool calls ...
 *   session.save(otherPath);
 *   session.close();
 * </pre>
 */
public final class LocalReasoningSession implements Closeable {

    private volatile boolean closed = false;
    private UnifiedGraph graph;
    private LocalKbState kbState;

    private LocalReasoningSession(UnifiedGraph graph) {
        this.graph = graph;
        this.kbState = LocalKbState.primeFromGraph(graph);
    }

    // ── Factory methods ──────────────────────────────────────────────────────

    /**
     * Open an existing {@code .kgraph} file and prime the KB fact store from it.
     *
     * @param path path to the {@code .kgraph} file
     * @return a new open session
     * @throws IOException if the file cannot be read or parsed
     */
    public static LocalReasoningSession open(Path path) throws IOException {
        UnifiedGraph g = UnifiedGraph.load(path);
        return new LocalReasoningSession(g);
    }

    /**
     * Create a session with an empty graph (for testing or programmatic graph construction).
     *
     * @return a new session wrapping an empty {@link UnifiedGraph}
     */
    public static LocalReasoningSession createEmpty() {
        return new LocalReasoningSession(new UnifiedGraph());
    }

    /**
     * Create a session wrapping an already-constructed graph (e.g. in tests).
     *
     * @param graph the graph to wrap
     * @return a new session with the KB fact store primed from {@code graph}
     */
    public static LocalReasoningSession of(UnifiedGraph graph) {
        return new LocalReasoningSession(graph);
    }

    // ── Graph and KB access ──────────────────────────────────────────────────

    /**
     * Return the current graph. Handlers must call this under session synchronization
     * (they always receive the session and call this method before dispatching).
     */
    public synchronized UnifiedGraph graph() {
        checkOpen();
        return graph;
    }

    /**
     * Return the current KB state (fact store + inferred store + justification index).
     */
    public synchronized LocalKbState kbState() {
        checkOpen();
        return kbState;
    }

    /**
     * Replace the current graph with a new one (e.g. after a graph_load tool call)
     * and re-prime the KB fact store from the new graph.
     */
    public synchronized void replaceGraph(UnifiedGraph newGraph) {
        checkOpen();
        this.graph = newGraph;
        this.kbState = LocalKbState.primeFromGraph(newGraph);
    }

    /**
     * Re-project observed facts from the current graph into the fact store.
     * Call this after any structural mutation (add/remove entity or relation) to keep
     * the KB store consistent with the graph topology.
     */
    public synchronized void reprimeKb() {
        checkOpen();
        kbState.reprimeFromGraph(graph);
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    /**
     * Save the current graph to a {@code .kgraph} file at the given path.
     *
     * @param path target path (parent dirs must exist)
     * @throws IOException if the file cannot be written
     */
    public synchronized void save(Path path) throws IOException {
        checkOpen();
        graph.save(path);
    }

    /**
     * Save the current graph with an explicit {@link Dtype} for embedding quantization.
     *
     * @param path  target path
     * @param dtype embedding quantization precision (F64, F32, F16, I8)
     * @throws IOException if the file cannot be written
     */
    public synchronized void save(Path path, Dtype dtype) throws IOException {
        checkOpen();
        graph.save(path, dtype);
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /** @return {@code true} if this session has been closed */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Close this session and release any in-memory resources. After calling this method,
     * all other methods will throw {@link IllegalStateException}.
     */
    @Override
    public synchronized void close() {
        closed = true;
        graph = null;
        kbState = null;
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("LocalReasoningSession has been closed");
        }
    }
}
