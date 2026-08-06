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
package ai.kompile.app.services.subprocess;

import ai.kompile.app.subprocess.ManagedSubprocessLauncher;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Persistent managed subprocess that OWNS the matrix graph subsystem — the
 * {@code AdjacencyMatrixGraph}, {@code VectorStoreMatrixGraphStore}, the matrix graph services, and
 * startup rehydration. Its whole reason to exist: the 17k-node / 1.27M-edge in-heap matrix (nested
 * {@code HashMap} adjacency + per-edge description Strings, ~24 GB) OOM'd the MAIN JVM at
 * {@code -Xmx32g}. Moving it here keeps the main app light (24–32 g) and contains a graph OOM in a
 * killable, restartable child instead of taking down the orchestrator.
 *
 * <p>Built on {@link ManagedSubprocessLauncher}: the base owns the JVM command (heap + JavaCPP
 * native cap + uber-jar classpath), spawn, {@code SubprocessRegistry} registration, the
 * stdout/stderr reader threads, live-log streaming to the {@code SubprocessLogBus}, and
 * {@link #requestRestart}. Unlike the one-shot learning/KGE launchers, this is a <b>persistent
 * server</b>: started once at boot, it boots the matrix Spring context, rehydrates the graphs
 * <em>here</em>, and serves graph operations over HTTP ({@code GraphMatrixSubprocessMain}) for the
 * app's lifetime. The base's {@code @PreDestroy stopAll()} stops it on shutdown.</p>
 *
 * <p>The main-app client ({@code SubprocessMatrixGraphStore}) calls {@link #baseUrl()} and never
 * materializes the matrix — see {@code plans/graph-matrix-subprocess.md}.</p>
 *
 * <p><b>Enabled only when {@code kompile.graph.subprocess.enabled=true}.</b> Off ⇒ the in-process
 * {@code VectorStoreMatrixGraphStore} remains {@code @Primary} (current behaviour; dev/small graphs).</p>
 */
@Service
// Always starts the graph-matrix subprocess at boot — isolation is the default, not a Spring-gated opt-in.
// Enable/disable + heap are kompile JSON managed-config (SubprocessConfigService), never a Spring property.
public class GraphMatrixSubprocessLauncher extends ManagedSubprocessLauncher {

    private static final String SUBPROCESS_ID = "graph-matrix";
    private static final String MAIN_CLASS =
            "ai.kompile.app.subprocess.GraphMatrixSubprocessMain";

    /** Default heap for the matrix subsystem (32 g); overridden per-type via the SubprocessConfigService JSON. */
    private static final int DEFAULT_HEAP_MB = 32768;

    /** UI-controllable managed-config (subprocess-ingest-config.json → subprocessTypes.graph-matrix). */
    @Autowired(required = false)
    private SubprocessConfigService subprocessConfig;

    /**
     * Explicit loopback port override for the subprocess; {@code 0} (the default) means "derive it
     * from this process's own HTTP port" — see {@link #preferredPort()}.
     */
    @Value("${kompile.graph.subprocess.port:0}")
    private int configuredPort;

    /** This process's own HTTP port: 8080 admin console, 8081 chat, 8082 crawl manager. */
    @Value("${server.port:8080}")
    private int appServerPort;

    /**
     * Offset from the owning process's HTTP port to its matrix subprocess port, so the admin console
     * on 8080 keeps the historical 8094.
     */
    private static final int GRAPH_PORT_OFFSET = 14;

    /**
     * The port the running child actually bound, resolved in {@link #start()}. Zero until then.
     *
     * <p>Since the persona split there can be several kompile processes on one machine — the admin
     * console, kompile-app-chat and kompile-app-crawl-manager — and each launches its own matrix
     * subprocess. A single fixed port made that a guaranteed collision: the second child died on
     * {@code failed to bind HttpServer on port 8094: Address already in use}, leaving that process
     * with a {@code @Primary} store pointed at nothing. Probing the port for freedom first is not
     * enough either — the child binds several seconds after the parent probes, so two parents
     * starting together both see 8094 free and both hand it to their children. Deriving the port
     * from the owning process's own (already unique) HTTP port removes the race instead of narrowing
     * it.</p>
     */
    private volatile int activePort;

    /** Hard JavaCPP native cap override (MB); {@code 0} ⇒ base default (near-machine-total ceiling). */
    @Value("${kompile.graph.subprocess.max-physical-mb:0}")
    private int maxPhysicalMbConfig;

    /**
     * Main app's resolved data dir. run-cpu.sh passes it as a Spring arg ({@code --kompile.data.dir=…}),
     * NOT a {@code -D} system property, so the base's system-property propagation does not forward it —
     * we must pass it explicitly so the subprocess's vector store reads the SAME persisted graph index.
     */
    @Value("${kompile.data.dir:#{null}}")
    private String dataDir;

    /** Optional explicit anserini index path (forwarded when set). */
    @Value("${kompile.vectorstore.anserini.index-path:#{null}}")
    private String indexPath;

    private final AtomicReference<ManagedRun> run = new AtomicReference<>();

    // ── ManagedSubprocessLauncher configuration ───────────────────────────────

    @Override
    public String getSubprocessId() {
        return SUBPROCESS_ID;
    }

    @Override
    protected String getTypeLabel() {
        return "graph-matrix";
    }

    @Override
    protected String getMainClass() {
        return MAIN_CLASS;
    }

    @Override
    protected int getHeapMb() {
        return subprocessConfig != null
                ? parseHeapMb(subprocessConfig.heapSizeForType("graph-matrix", "32g"), DEFAULT_HEAP_MB)
                : DEFAULT_HEAP_MB;
    }

    /** Parse a heap-size string ({@code "32g"}, {@code "4096m"}, {@code "2048"}) to MB. */
    private static int parseHeapMb(String heap, int defaultMb) {
        if (heap == null || heap.isBlank()) return defaultMb;
        String h = heap.trim().toLowerCase();
        try {
            if (h.endsWith("g")) return (int) (Double.parseDouble(h.substring(0, h.length() - 1)) * 1024);
            if (h.endsWith("m")) return (int) Double.parseDouble(h.substring(0, h.length() - 1));
            return Integer.parseInt(h);
        } catch (NumberFormatException e) {
            return defaultMb;
        }
    }

    @Override
    protected long getMaxPhysicalMb() {
        // The matrix is JVM-heap (nested maps), not native; the native cap only bounds the small
        // INDArray node-embeddings. 0 ⇒ base default (4×heap → resolved against the system ceiling).
        return maxPhysicalMbConfig > 0 ? maxPhysicalMbConfig : 0L;
    }

    /**
     * The matrix store does no GPU compute (sparse JVM-heap maps + trivial INDArray node embeddings);
     * it remains CPU-pinned through normal backend preference flags emitted by the base launcher.
     * Additional backend toggles are not hardcoded here; they are inherited from parent/runtime config.
     */
    @Override
    protected BackendPreference getBackendPreference() {
        return BackendPreference.CPU;
    }

    @Override
    protected List<String> getExtraJvmArgs() {
        List<String> args = new ArrayList<>();
        // Forward the data dir / index path so the subprocess reads the SAME persisted graph index as
        // the main app (the Spring --kompile.data.dir arg is not a -D, so it isn't auto-propagated).
        if (dataDir != null && !dataDir.isBlank()) {
            args.add("-Dkompile.data.dir=" + dataDir);
        }
        if (indexPath != null && !indexPath.isBlank()) {
            args.add("-Dkompile.vectorstore.anserini.index-path=" + indexPath);
        }
        return args;
    }

    // ── persistent lifecycle ──────────────────────────────────────────────────

    /** Loopback base URL the main-app client uses to reach the matrix subprocess. */
    public String baseUrl() {
        return "http://127.0.0.1:" + getPort();
    }

    public int getPort() {
        int active = activePort;
        return active > 0 ? active : preferredPort();
    }

    /**
     * An explicit {@code kompile.graph.subprocess.port} if one is set, else this process's HTTP port
     * plus {@link #GRAPH_PORT_OFFSET} — 8080 → 8094 for the admin console, 8095 for chat on 8081,
     * 8096 for the crawl manager on 8082. Distinct server ports are what make the personas' matrix
     * subprocesses distinct.
     */
    private int preferredPort() {
        if (configuredPort > 0) {
            return configuredPort;
        }
        return appServerPort > 0 ? appServerPort + GRAPH_PORT_OFFSET : 0;
    }

    /**
     * The preferred port when it is free, otherwise an ephemeral one — the fallback covers a custom
     * port layout that happens to collide, or a leftover child from a previous run. Binding is the
     * only reliable free test, and the socket has to close before the child can take it, so a
     * foreign process could still slip in; the child then logs its bind failure exactly as it would
     * for any other spawn problem.
     */
    private int resolvePort() {
        int preferred = preferredPort();
        if (preferred > 0 && isPortFree(preferred)) {
            return preferred;
        }
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int ephemeral = probe.getLocalPort();
            log.info("[graph-matrix] preferred port {} unavailable — using ephemeral port {}",
                    preferred, ephemeral);
            return ephemeral;
        } catch (IOException e) {
            log.warn("[graph-matrix] could not allocate an ephemeral port ({}) — falling back to {}",
                    e.getMessage(), preferred);
            return preferred;
        }
    }

    private static boolean isPortFree(int candidate) {
        try (ServerSocket probe = new ServerSocket(candidate, 1, InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort() == candidate;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean isRunning() {
        ManagedRun r = run.get();
        return r != null && r.process().isAlive();
    }

    /**
     * Start the persistent matrix subprocess once at boot. The child boots the matrix subsystem,
     * rehydrates the graphs, and serves {@code /invoke} on {@link #resolvePort()}. No structured-stdout
     * protocol (all stdout is logs) — a no-op handler is passed. The base streams its logs live and
     * stops it on shutdown via {@code @PreDestroy}.
     */
    @PostConstruct
    public void start() {
        if (subprocessConfig != null && !subprocessConfig.isTypeEnabled("graph-matrix", true)) {
            log.info("[graph-matrix] disabled via subprocess-ingest-config.json (subprocessTypes.graph-matrix.enabled=false) — not spawning");
            return;
        }
        try {
            int bindPort = resolvePort();
            activePort = bindPort;
            ManagedRun r = startProcess(SUBPROCESS_ID, null, List.of("--port=" + bindPort), payload -> { });
            run.set(r);
            log.info("[graph-matrix] persistent subprocess started on port {} (heap {} MB) — owns the in-heap matrix",
                    bindPort, getHeapMb());
        } catch (Exception e) {
            log.error("[graph-matrix] failed to start persistent subprocess: {}", e.getMessage(), e);
        }
    }
}
