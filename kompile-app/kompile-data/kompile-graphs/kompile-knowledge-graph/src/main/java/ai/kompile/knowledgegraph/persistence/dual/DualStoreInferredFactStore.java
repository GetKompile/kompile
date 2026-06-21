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
package ai.kompile.knowledgegraph.persistence.dual;

import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.InferredFactStore;
import ai.kompile.graph.reasoning.fol.InMemoryInferredFactStore;
import ai.kompile.knowledgegraph.reasoning.InferredFactGraphMaterializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Dual-store {@link InferredFactStore} implementation: every fact write is mirrored
 * to both an in-memory {@link InMemoryInferredFactStore} (fast reads) and the relational
 * {@link InferredFactRowRepository} (durable across restarts).
 *
 * <p>This class is NOT a Spring {@code @Component}; it is created per fact-sheet by
 * {@link DualStoreGroundingFactory}. The {@code factSheetId} scopes ALL DB queries so
 * this store only reads/writes rows belonging to its own fact sheet.</p>
 *
 * <h3>Construction-time hydration</h3>
 * <p>On construction, the latest inferred fact for every atom key in the DB (for this fact
 * sheet) is loaded into the in-memory delegate. This gives {@link #allLatest()},
 * {@link #latest(String)}, and {@link #size()} O(1) in-memory performance after warm-up.</p>
 *
 * <h3>history() semantics</h3>
 * <p>Full version history per atom key is fetched lazily from the DB on each call to
 * {@link #history(String)} — the in-memory delegate only holds the latest version per key
 * (loaded at construction), so history falls through to the repository.</p>
 *
 * <h3>Async graph materialization</h3>
 * <p>When an {@link InferredFactGraphMaterializer} is supplied, each {@link #store(InferredFact)}
 * call triggers an asynchronous graph-edge/attribute write after the in-memory and DB writes
 * complete. Materializer failures are caught and logged (warn level) so they never block or
 * roll back the fact store write.</p>
 */
public class DualStoreInferredFactStore implements InferredFactStore {

    private static final Logger log = LoggerFactory.getLogger(DualStoreInferredFactStore.class);

    private final InferredFactRowRepository repo;
    private final Long factSheetId;

    @Nullable
    private final InferredFactGraphMaterializer materializer;

    /** In-memory delegate: fast reads; populated at construction and updated on store(). */
    private final InMemoryInferredFactStore delegate;

    /**
     * Construct a dual-store for the given fact sheet.
     *
     * @param repo         the JPA repository for persisting rows
     * @param factSheetId  the fact sheet to scope all queries to; must not be null
     * @param materializer optional graph materializer; null disables async materialization
     */
    public DualStoreInferredFactStore(InferredFactRowRepository repo,
                                       Long factSheetId,
                                       @Nullable InferredFactGraphMaterializer materializer) {
        this.repo = repo;
        this.factSheetId = factSheetId;
        this.materializer = materializer;
        this.delegate = new InMemoryInferredFactStore();
        hydrate();
    }

    // ── InferredFactStore implementation ──────────────────────────────────────────

    @Override
    public void store(InferredFact fact) {
        // 1. In-memory write (fast reads stay current)
        delegate.store(fact);

        // 2. Persist to DB
        InferredFactRow row = InferredFactRow.builder()
                .factSheetId(factSheetId)
                .atomKey(fact.atomKey())
                .version(fact.version())
                .value(fact.value())
                .confidence(fact.confidence())
                .runId(fact.runId())
                .provenanceJson(fact.toJson())
                .inferredAt(fact.inferredAt())
                .build();
        repo.save(row);

        // 3. Async graph materialization (non-blocking; failure must never propagate)
        if (materializer != null) {
            try {
                materializer.materialize(List.of(fact), factSheetId);
            } catch (Exception e) {
                log.warn("DualStoreInferredFactStore: graph materialization failed for atomKey='{}' factSheet={} — {}",
                        fact.atomKey(), factSheetId, e.getMessage());
            }
        }
    }

    @Override
    public Optional<InferredFact> latest(String atomKey) {
        return delegate.latest(atomKey);
    }

    @Override
    public List<InferredFact> history(String atomKey) {
        // Load full history from DB (in-memory delegate only holds the latest per key)
        List<InferredFactRow> rows =
                repo.findByFactSheetIdAndAtomKeyOrderByVersionAsc(factSheetId, atomKey);
        if (rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .map(r -> InferredFact.fromJson(r.getProvenanceJson()))
                .toList();
    }

    @Override
    public Collection<InferredFact> byRun(String runId) {
        // Delegate to DB for run-scoped queries (the in-memory delegate does support byRun,
        // but only for facts loaded at construction + stored in this session; a DB query is
        // authoritative across restarts).
        List<InferredFactRow> rows =
                repo.findByFactSheetIdAndRunIdOrderByAtomKeyAsc(factSheetId, runId);
        if (rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .map(r -> InferredFact.fromJson(r.getProvenanceJson()))
                .toList();
    }

    @Override
    public Collection<InferredFact> allLatest() {
        return delegate.allLatest();
    }

    @Override
    public void purge(String atomKey) {
        // Remove from in-memory delegate
        delegate.purge(atomKey);
        // Remove all rows from DB (the repository method is @Transactional)
        repo.deleteByFactSheetIdAndAtomKey(factSheetId, atomKey);
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    // ── Internal helpers ──────────────────────────────────────────────────────────

    /**
     * Load the latest fact version for every atom key in the DB into the in-memory delegate.
     * Called once at construction. Failures are logged but do not prevent construction —
     * the store will simply be empty (degraded to in-memory-only) and re-populate on writes.
     */
    private void hydrate() {
        try {
            List<InferredFactRow> latestRows = repo.findLatestByFactSheetId(factSheetId);
            for (InferredFactRow row : latestRows) {
                try {
                    InferredFact fact = InferredFact.fromJson(row.getProvenanceJson());
                    delegate.store(fact);
                } catch (Exception e) {
                    log.warn("DualStoreInferredFactStore: could not deserialize row id={} for factSheet={} — {}",
                            row.getId(), factSheetId, e.getMessage());
                }
            }
            if (!latestRows.isEmpty()) {
                log.debug("DualStoreInferredFactStore: hydrated {} atom keys for factSheet={}",
                        latestRows.size(), factSheetId);
            }
        } catch (Exception e) {
            log.warn("DualStoreInferredFactStore: hydration failed for factSheet={} — store will be empty: {}",
                    factSheetId, e.getMessage());
        }
    }
}
