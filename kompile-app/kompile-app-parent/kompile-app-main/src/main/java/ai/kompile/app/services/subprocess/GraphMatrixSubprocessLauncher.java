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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

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
@ConditionalOnProperty(
        name = "kompile.graph.subprocess.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class GraphMatrixSubprocessLauncher extends ManagedSubprocessLauncher {

    private static final String SUBPROCESS_ID = "graph-matrix";
    private static final String MAIN_CLASS =
            "ai.kompile.app.subprocess.GraphMatrixSubprocessMain";

    /** Heap for the matrix subsystem — the in-heap matrix lives HERE now (default 32 g). */
    @Value("${kompile.graph.subprocess.heap-mb:32768}")
    private int heapMb;

    /** Loopback HTTP port the subprocess serves graph operations on. */
    @Value("${kompile.graph.subprocess.port:8094}")
    private int port;

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
        return heapMb;
    }

    @Override
    protected long getMaxPhysicalMb() {
        // The matrix is JVM-heap (nested maps), not native; the native cap only bounds the small
        // INDArray node-embeddings. 0 ⇒ base default (4×heap → resolved against the system ceiling).
        return maxPhysicalMbConfig > 0 ? maxPhysicalMbConfig : 0L;
    }

    @Override
    protected List<String> getExtraJvmArgs() {
        // CPU-safe ND4J defaults when the parent didn't set them (mirrors LearningSubprocessLauncher):
        // a no-GPU host otherwise probes CUDA at Nd4j.<clinit> and crashes the subprocess.
        List<String> args = new ArrayList<>();
        if (System.getProperty("nd4j.backend.priority") == null) {
            args.add("-Dnd4j.backend.priority=CPU");
        }
        if (System.getProperty("nd4j.multibackend.enabled") == null) {
            args.add("-Dnd4j.multibackend.enabled=false");
        }
        if (System.getProperty("org.nd4j.backend.multi.auto") == null) {
            args.add("-Dorg.nd4j.backend.multi.auto=false");
        }
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
        return "http://127.0.0.1:" + port;
    }

    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        ManagedRun r = run.get();
        return r != null && r.process().isAlive();
    }

    /**
     * Start the persistent matrix subprocess once at boot. The child boots the matrix subsystem,
     * rehydrates the graphs, and serves {@code /invoke} on {@link #port}. No structured-stdout
     * protocol (all stdout is logs) — a no-op handler is passed. The base streams its logs live and
     * stops it on shutdown via {@code @PreDestroy}.
     */
    @PostConstruct
    public void start() {
        try {
            ManagedRun r = startProcess(SUBPROCESS_ID, null, List.of("--port=" + port), payload -> { });
            run.set(r);
            log.info("[graph-matrix] persistent subprocess started on port {} (heap {} MB) — owns the in-heap matrix",
                    port, heapMb);
        } catch (Exception e) {
            log.error("[graph-matrix] failed to start persistent subprocess: {}", e.getMessage(), e);
        }
    }
}
