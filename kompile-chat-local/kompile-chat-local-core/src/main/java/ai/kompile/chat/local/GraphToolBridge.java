package ai.kompile.chat.local;

import ai.kompile.graph.reasoning.local.LocalReasoningSession;
import ai.kompile.graph.reasoning.local.LocalToolDispatcher;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Owns a {@link LocalReasoningSession} and {@link LocalToolDispatcher}, exposing
 * a simple execute / catalog API for use by {@link ChatEngine}.
 *
 * <p>Create via {@link #open(Path)} to load an existing {@code .kgraph} file,
 * or {@link #empty()} to start with an in-memory graph.</p>
 *
 * <p>Always call {@link #close()} when done — it closes the underlying session.</p>
 */
public final class GraphToolBridge implements Closeable, GraphToolBackend {

    private final LocalReasoningSession session;
    private final LocalToolDispatcher dispatcher;

    private GraphToolBridge(LocalReasoningSession session, LocalToolDispatcher dispatcher) {
        this.session = session;
        this.dispatcher = dispatcher;
    }

    // ── Factories ────────────────────────────────────────────────────────────

    /**
     * Open a {@code .kgraph} file and initialise a reasoning session over it.
     *
     * @param kgraphPath path to the {@code .kgraph} archive
     * @return a fully initialised bridge ready for tool dispatch
     * @throws IOException if the file cannot be read
     */
    public static GraphToolBridge open(Path kgraphPath) throws IOException {
        LocalReasoningSession session = LocalReasoningSession.open(kgraphPath);
        return new GraphToolBridge(session, LocalToolDispatcher.create());
    }

    /**
     * Create a bridge backed by an empty in-memory graph.
     *
     * @return a bridge with no pre-loaded graph data
     */
    public static GraphToolBridge empty() {
        return new GraphToolBridge(
                LocalReasoningSession.createEmpty(),
                LocalToolDispatcher.create());
    }

    // ── API ──────────────────────────────────────────────────────────────────

    /**
     * Return the tool catalog as a JSON array string.
     *
     * <p>Each element has the shape {@code {"name":"...","description":"...","parameters":{...}}}.</p>
     *
     * @return JSON array of available tools
     */
    public String catalogJson() {
        return dispatcher.catalog().toJson();
    }

    /**
     * Execute a named graph tool with the given JSON arguments.
     *
     * <p>This call never throws — errors are returned as a JSON object with a
     * {@code "status"} field of {@code "ERROR"} or {@code "INVALID"}.</p>
     *
     * @param toolName the tool name (must appear in {@link #catalogJson()})
     * @param argsJson JSON object of arguments, e.g. {@code "{}"}
     * @return JSON result string from the tool handler
     */
    public String execute(String toolName, String argsJson) {
        return dispatcher.dispatch(session, toolName, argsJson);
    }

    /**
     * Access the underlying session (e.g. to call {@link LocalReasoningSession#save(Path)}).
     *
     * @return the live reasoning session
     */
    public LocalReasoningSession session() {
        return session;
    }

    // ── Closeable ────────────────────────────────────────────────────────────

    @Override
    public void close() {
        session.close();
    }
}
