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
package ai.kompile.knowledgegraph.grounding;

import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.InferredFactStore;
import ai.kompile.graph.reasoning.fol.InMemoryInferredFactStore;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Durable {@link InferredFactStore} implementation backed by a pair of JSONL files
 * under {@code <dataDir>/data/graph/inferred/}.
 *
 * <h3>File layout</h3>
 * <pre>
 * &lt;dataDir&gt;/data/graph/inferred/
 *   factsheet-&lt;id&gt;-latest.jsonl   – one line per atom key: the latest InferredFact
 *   factsheet-&lt;id&gt;-history.jsonl  – full version history (append-only)
 * </pre>
 *
 * <p>On construction the latest-file is read into an in-memory
 * {@link InMemoryInferredFactStore} delegate, so all read operations are O(1).
 * On each {@link #store(InferredFact)} call:
 * <ol>
 *   <li>The delegate is updated (version auto-assignment is delegated to
 *       {@link InMemoryInferredFactStore}).</li>
 *   <li>The fact is appended to the history file.</li>
 *   <li>The full latest state is rewritten to the latest file
 *       (safe: JSONL file is small; O(latest atoms) rewrite).</li>
 * </ol>
 *
 * <h3>Fallback / tests</h3>
 * <p>When {@code dataDirPath} is {@code null}, the store operates entirely in
 * memory (no file I/O). The existing 17 grounding tests pass {@code null}; only
 * the new integration tests supply a real directory.</p>
 *
 * <h3>Concurrency</h3>
 * <p>Not internally synchronized — callers must hold the per-factSheet write lock
 * (as enforced by {@link KbGroundingService}) while calling {@link #store}.</p>
 */
@Slf4j
public class FileBackedInferredFactStore implements InferredFactStore {

    /** In-memory delegate: handles version assignment and all read operations. */
    private final InMemoryInferredFactStore delegate = new InMemoryInferredFactStore();

    /** Absolute path to the inferred/ directory, or null for in-memory-only mode. */
    private final Path inferredDir;

    /** Fact sheet id, for file name construction. */
    private final long factSheetId;

    // ── Constructors ─────────────────────────────────────────────────────────────

    /**
     * Full constructor: files live under {@code dataDirPath/data/graph/inferred/}.
     *
     * @param dataDirPath path to the project data directory (e.g. {@code ~/.kompile/myproject})
     * @param factSheetId the fact sheet this store belongs to
     */
    public FileBackedInferredFactStore(Path dataDirPath, long factSheetId) {
        this.factSheetId = factSheetId;
        if (dataDirPath != null) {
            this.inferredDir = dataDirPath.resolve("data").resolve("graph").resolve("inferred");
            ensureDir();
            loadLatestFromDisk();
        } else {
            this.inferredDir = null;
        }
    }

    /**
     * In-memory-only constructor (no file I/O). Used by tests and by the Phase-1
     * {@link KbGroundingService#getState} lazy factory when no data dir is configured.
     *
     * @param factSheetId the fact sheet this store belongs to
     */
    public FileBackedInferredFactStore(long factSheetId) {
        this(null, factSheetId);
    }

    // ── InferredFactStore implementation ─────────────────────────────────────────

    @Override
    public void store(InferredFact fact) {
        if (fact == null) throw new IllegalArgumentException("fact must not be null");
        delegate.store(fact);

        if (inferredDir != null) {
            // Retrieve the actually-stored fact (version may have been auto-assigned by delegate)
            InferredFact stored = delegate.latest(fact.atomKey()).orElse(fact);
            appendHistory(stored);
            rewriteLatest();
        }
    }

    @Override
    public Optional<InferredFact> latest(String atomKey) {
        return delegate.latest(atomKey);
    }

    @Override
    public List<InferredFact> history(String atomKey) {
        return delegate.history(atomKey);
    }

    @Override
    public Collection<InferredFact> byRun(String runId) {
        return delegate.byRun(runId);
    }

    @Override
    public Collection<InferredFact> allLatest() {
        return delegate.allLatest();
    }

    @Override
    public void purge(String atomKey) {
        delegate.purge(atomKey);
        if (inferredDir != null) {
            rewriteLatest();
        }
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    // ── File helpers ──────────────────────────────────────────────────────────────

    /** Path to the latest-state JSONL file for this fact sheet. */
    private Path latestFile() {
        return inferredDir.resolve("factsheet-" + factSheetId + "-latest.jsonl");
    }

    /** Path to the full-history append-only JSONL file for this fact sheet. */
    private Path historyFile() {
        return inferredDir.resolve("factsheet-" + factSheetId + "-history.jsonl");
    }

    /** Create the inferred/ directory if it doesn't exist. */
    private void ensureDir() {
        try {
            Files.createDirectories(inferredDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create inferred-facts directory: " + inferredDir, e);
        }
    }

    /**
     * Load the latest JSONL file into the in-memory delegate on construction.
     * Errors on individual lines are logged and skipped (resilience over crash-on-corruption).
     */
    private void loadLatestFromDisk() {
        Path file = latestFile();
        if (!Files.exists(file)) {
            log.debug("FileBackedInferredFactStore[{}]: no latest file at {} — starting fresh",
                    factSheetId, file);
            return;
        }
        int loaded = 0;
        int skipped = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    delegate.store(InferredFact.fromJson(line));
                    loaded++;
                } catch (Exception e) {
                    log.warn("FileBackedInferredFactStore[{}]: skipping malformed line — {}", factSheetId, e.getMessage());
                    skipped++;
                }
            }
        } catch (IOException e) {
            log.warn("FileBackedInferredFactStore[{}]: could not read latest file {} — {}",
                    factSheetId, file, e.getMessage());
        }
        log.debug("FileBackedInferredFactStore[{}]: loaded {} facts ({} skipped) from {}",
                factSheetId, loaded, skipped, file);
    }

    /**
     * Append one fact to the history file (safe append — never overwrites).
     */
    private void appendHistory(InferredFact fact) {
        try (BufferedWriter w = Files.newBufferedWriter(historyFile(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(fact.toJson());
            w.newLine();
        } catch (IOException e) {
            log.warn("FileBackedInferredFactStore[{}]: could not append to history file — {}",
                    factSheetId, e.getMessage());
        }
    }

    /**
     * Rewrite the latest file atomically: write all current latest facts to a temp file,
     * then move it over the target. One line per atom key.
     */
    private void rewriteLatest() {
        Path target = latestFile();
        Path tmp = inferredDir.resolve("factsheet-" + factSheetId + "-latest.jsonl.tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (InferredFact fact : delegate.allLatest()) {
                w.write(fact.toJson());
                w.newLine();
            }
        } catch (IOException e) {
            log.warn("FileBackedInferredFactStore[{}]: could not write latest file — {}",
                    factSheetId, e.getMessage());
            return;
        }
        try {
            Files.move(tmp, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // ATOMIC_MOVE may not be available cross-device; try non-atomic
            try {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                log.warn("FileBackedInferredFactStore[{}]: could not move latest file — {}", factSheetId, ex.getMessage());
            }
        }
    }
}
