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
package ai.kompile.knowledgegraph.reasoning;

import ai.kompile.graph.reasoning.fol.EntailmentEngine;
import ai.kompile.graph.reasoning.fol.EntailmentRecord;
import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.InferredFactStore;
import ai.kompile.graph.reasoning.psl.HlMrfMapInference;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.tms.ContradictionDetector;
import ai.kompile.graph.reasoning.tms.JustificationIndex;
import ai.kompile.knowledgegraph.audit.PinGuard;
import ai.kompile.knowledgegraph.grounding.FactSheetKbState;
import ai.kompile.knowledgegraph.grounding.KbCorrectionService;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.stream.Collectors;

/**
 * L3 Grounding Cascade orchestrator: given a {@code factSheetId}, (re-)runs MAP inference
 * over the currently-observed facts and updates that fact sheet's {@link InferredFactStore}
 * so that subsequent {@link KbGroundingService#verify} calls reflect the new graph state.
 *
 * <h3>Grounding strategy: FULL PER-FACTSHEET re-ground (first cut)</h3>
 * <p>This implementation does a <em>full</em> per-fact-sheet re-ground rather than the
 * full hybrid semi-naive/justification-based algorithm described in the design spec.
 * Specifically:
 * <ol>
 *   <li>It reads the current {@link FactStore} from {@link FactSheetKbState} (populated
 *       by prior {@link KbGroundingService#assertFact} calls).</li>
 *   <li>It builds a {@link PslProgram} from those facts: observed atoms (value ≥ 0.9) become
 *       fixed evidence; every fact also registers a target atom
 *       ({@code derived_<predicate>(args)}) so the MAP solver can derive implied values.</li>
 *   <li>It runs {@link HlMrfMapInference#solve} over the full program.</li>
 *   <li>It materializes resulting {@link InferredFact}s into the {@link InferredFactStore},
 *       incrementing versions only when the value changed by more than
 *       {@link #VERSION_EPSILON} (the §7.2 fixed-point termination condition).</li>
 *   <li>It rebuilds the {@link JustificationIndex}.</li>
 *   <li>It calls {@link KbGroundingService#markEpoch} to register the new epoch.</li>
 * </ol>
 *
 * <h3>Why full re-ground instead of incremental (TODO)</h3>
 * <p>The design spec §3.2 calls for a hybrid semi-naive forward + justification-based
 * retraction cascade. That path requires:
 * <ol>
 *   <li>An {@link ai.kompile.graph.reasoning.psl.IncrementalGrounder} stored per factSheet
 *       (requires converting {@link FactSheetKbState} from a record to a mutable class,
 *       so the grounder can be held alongside the {@link FactStore}).</li>
 *   <li>Routing all agent asserts through {@code IncrementalGrounder.addAtom} rather than
 *       directly into {@link FactStore}.</li>
 *   <li>Using {@link JustificationIndex#atomsDependingOnFact} +
 *       {@link JustificationIndex#solelyDependentOn} to restrict the MAP solve to the
 *       depth-2 neighbourhood of the changed atoms.</li>
 * </ol>
 * The full re-ground is correct and produces the right {@link InferredFact} versions.
 * The only cost is O(|all facts|) vs O(|affected atoms|²) — acceptable for fact sheets
 * with up to a few thousand atoms. Incremental optimization is left as a follow-up.</p>
 *
 * <h3>Concurrency model</h3>
 * <p>This service MUST only be called from within the per-factSheet single-threaded executor
 * managed by {@link ai.kompile.graphchangetracking.hook.GroundingCascadeHook}. The write lock
 * on {@link FactSheetKbState#lock()} is acquired here for the full duration of the re-ground.</p>
 */
@Service
@Slf4j
public class IncrementalReasoningOrchestrator {

    /**
     * Minimum value change that warrants a new {@link InferredFact} version (§7.2 termination).
     * If re-ground produces the same value within ε for every atom, no new versions are written.
     */
    static final double VERSION_EPSILON = 0.001;

    private final KbGroundingService kbGroundingService;
    // eventPublisher retained for future ContradictionDetectedEvent (§3.2 STEP 7)
    @SuppressWarnings("unused")
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Optional: graph→FactStore projector. When non-null, {@link #doReground} projects
     * the live graph into the FactStore before the MAP solve. {@code null} in plain-Java
     * test contexts (existing 17 tests) so they remain unaffected.
     */
    @Nullable
    private final GraphToFactStoreProjector graphProjector;

    /**
     * Optional: PinGuard — blocks re-derivation of human-pinned atoms.
     * Null in plain-Java test contexts; injected by Spring in production.
     */
    @Nullable
    private final PinGuard pinGuard;

    /**
     * Optional: correction service — used to emit DERIVED audit events after each
     * inferredStore.store() call and to register the latest PSL program snapshot.
     * Null in plain-Java test contexts.
     */
    @Nullable
    private final KbCorrectionService correctionService;

    /**
     * Optional: project data dir for PSL rule loading.  When set, {@code <dataDir>/rules/*.psl}
     * files are loaded into the PSL program before the MAP solve.
     *
     * <p>Package-private visibility so that same-package tests can inject a temp dir
     * without requiring Spring or reflection. In production Spring injects via {@code @Value}.</p>
     */
    @Nullable
    @Value("${kompile.data.dir:#{null}}")
    String dataDir;

    /**
     * Full Spring constructor: all 5 collaborators injected by Spring.
     */
    public IncrementalReasoningOrchestrator(KbGroundingService kbGroundingService,
                                             ApplicationEventPublisher eventPublisher,
                                             @Nullable GraphToFactStoreProjector graphProjector,
                                             @Nullable PinGuard pinGuard,
                                             @Nullable KbCorrectionService correctionService) {
        this.kbGroundingService = kbGroundingService;
        this.eventPublisher = eventPublisher;
        this.graphProjector = graphProjector;
        this.pinGuard = pinGuard;
        this.correctionService = correctionService;
    }

    /**
     * Three-arg Spring constructor (no PinGuard/KbCorrectionService).
     * Retained for backward compatibility with plain-Java tests and Spring contexts
     * that have not yet wired PinGuard.
     */
    public IncrementalReasoningOrchestrator(KbGroundingService kbGroundingService,
                                             ApplicationEventPublisher eventPublisher,
                                             @Nullable GraphToFactStoreProjector graphProjector) {
        this(kbGroundingService, eventPublisher, graphProjector, null, null);
    }

    /**
     * Backward-compatible constructor for plain-Java tests that wire only the two
     * original collaborators. {@code graphProjector} defaults to {@code null}.
     */
    public IncrementalReasoningOrchestrator(KbGroundingService kbGroundingService,
                                             ApplicationEventPublisher eventPublisher) {
        this(kbGroundingService, eventPublisher, null, null, null);
    }

    /**
     * Re-ground the KB for {@code factSheetId} using all currently-observed facts.
     *
     * <p>The write lock on the state is held for the entire operation. Reads are blocked for
     * the duration; given typical KG sizes (≤ a few thousand atoms) this should be &lt;100 ms.</p>
     *
     * @param factSheetId the fact sheet to re-ground
     * @return a {@link RegroundResult} with the number of {@link InferredFact} versions written,
     *         the run ID, and the set of retracted atom keys (currently always empty)
     */
    public RegroundResult runFullReground(long factSheetId) {
        FactSheetKbState state = kbGroundingService.getState(factSheetId);
        ReadWriteLock lock = state.lock();
        lock.writeLock().lock();
        try {
            return doReground(factSheetId, state);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ─── Internal ─────────────────────────────────────────────────────────────────

    /**
     * Execute the full re-ground. MUST be called while holding {@code state.lock().writeLock()}.
     */
    private RegroundResult doReground(long factSheetId, FactSheetKbState state) {
        FactStore factStore = state.factStore();

        // ── STEP 0 (NEW): Project live graph into FactStore before MAP solve ─────────
        // This is the missing production link (design spec §5): without this call the
        // FactStore is empty after a fresh crawl, so the MAP solve produces nothing.
        // null-safe: graphProjector is null in plain-Java test contexts.
        if (graphProjector != null) {
            try {
                int projected = graphProjector.project(factSheetId);
                log.debug("Grounding cascade for factSheet={}: projected {} graph atoms into FactStore",
                        factSheetId, projected);
            } catch (Exception e) {
                log.warn("Grounding cascade for factSheet={}: graph projection failed — {}",
                        factSheetId, e.getMessage());
                // Non-fatal: continue with whatever is already in the FactStore
            }
        }

        if (factStore.isEmpty()) {
            log.debug("Grounding cascade for factSheet={}: FactStore is empty — skipping MAP solve",
                    factSheetId);
            return RegroundResult.empty();
        }

        // ── STEP 2+3: Build PSL program from the observed FactStore ──────────────────
        PslProgram program = buildProgramFromFactStore(factStore);

        // ── STEP 3b (NEW): Load project-level PSL rules if dataDir is configured ─────
        // If <dataDir>/rules/*.psl files exist, their rules override the default soft-
        // propagation rules added by buildProgramFromFactStore. This is the pluggable
        // rule seam described in design spec §7 (P0 build plan item 4).
        loadProjectPslRules(program);

        if (program.targetKeys().isEmpty()) {
            log.debug("Grounding cascade for factSheet={}: no target atoms in program — skipping",
                    factSheetId);
            return RegroundResult.empty();
        }

        // ── STEP 4: MAP solve ─────────────────────────────────────────────────────────
        HlMrfMapInference.Result result;
        String runId = UUID.randomUUID().toString();
        try {
            result = HlMrfMapInference.solve(program);
            log.debug("Grounding cascade factSheet={}: MAP solve converged={} iterations={} objective={}",
                    factSheetId, result.converged(), result.iterations(), result.objective());
        } catch (Exception e) {
            log.warn("Grounding cascade factSheet={}: MAP solve failed — {}", factSheetId, e.getMessage(), e);
            return RegroundResult.empty();
        }

        // ── STEP 5: Materialize inferred facts ────────────────────────────────────────
        List<EntailmentRecord> records = EntailmentEngine.entailFromPslResult(
                program, result, factStore, runId);

        InferredFactStore inferredStore = state.inferredFactStore();
        int versionsWritten = 0;

        // Snapshot the previous atom keys so we can detect implicitly retracted atoms
        // (atoms that were in the inferred store before this run but are not produced now).
        Set<String> previousAtomKeys = inferredStore.allLatest().stream()
                .map(InferredFact::atomKey)
                .collect(Collectors.toCollection(HashSet::new));
        Set<String> newAtomKeys = new HashSet<>();

        for (EntailmentRecord record : records) {
            String atomKey = record.groundedRvOrAtomKey();
            double newValue = record.posterior();

            newAtomKeys.add(atomKey);

            // PinGuard: skip atoms pinned by human corrections
            if (pinGuard != null && pinGuard.isPinned(factSheetId, atomKey)) {
                log.debug("PinGuard: skipping re-derivation of pinned atom '{}' in factSheet={}",
                        atomKey, factSheetId);
                continue;
            }

            // Fixed-point termination: only write a new version if value changed meaningfully
            Optional<InferredFact> existing = inferredStore.latest(atomKey);
            if (existing.isPresent()
                    && Math.abs(existing.get().value() - newValue) < VERSION_EPSILON) {
                continue; // stable — no new version needed
            }

            double vBefore = existing.map(InferredFact::value).orElse(Double.NaN);
            double cBefore = existing.map(InferredFact::confidence).orElse(Double.NaN);

            long nextVersion = existing.map(f -> f.version() + 1L).orElse(1L);
            InferredFact newFact = new InferredFact(
                    atomKey,
                    newValue,
                    newValue,  // confidence == value for PSL soft-truth
                    record.supportingFindingKeys(),
                    record.activatedRules(),
                    runId,
                    nextVersion,
                    record.computedAt()
            );
            inferredStore.store(newFact);
            versionsWritten++;

            // Emit DERIVED audit event after each successful store
            if (correctionService != null) {
                correctionService.appendDerivedAuditEvent(
                        factSheetId, atomKey, vBefore, newValue, cBefore, newValue, runId);
            }
        }

        // Register the PSL program snapshot with the correction service so that subsequent
        // human corrections have a fresh program for mini-batch weight updates.
        if (correctionService != null) {
            correctionService.registerProgram(factSheetId, program);
        }

        // Compute implicitly retracted atom keys: atoms that were in the inferred store
        // before this run but are not produced by this MAP solve.
        // These are candidates for BeliefReviser-based retraction in a future incremental path.
        Set<String> retractedAtomKeys = new HashSet<>(previousAtomKeys);
        retractedAtomKeys.removeAll(newAtomKeys);

        // ── STEP 6: Rebuild JustificationIndex ───────────────────────────────────────
        // Full rebuild (O(|ground rules|)); incremental merge is a TODO per design §9.1.
        JustificationIndex newIndex = JustificationIndex.build(result, factStore);

        // ── STEP 7: Contradiction scan ────────────────────────────────────────────────
        List<ContradictionDetector.Pair<Fact, Fact>> contradictions =
                ContradictionDetector.findFactContradictions(factStore);
        if (!contradictions.isEmpty()) {
            log.warn("Grounding cascade factSheet={}: {} contradiction pair(s) — not halting cascade",
                    factSheetId, contradictions.size());
            // Does not halt cascade — contradictions flagged per design §3.2 STEP 7
        }

        // ── STEP 8: Epoch bump ────────────────────────────────────────────────────────
        kbGroundingService.markEpoch(factSheetId, runId, newIndex);

        log.info("Grounding cascade factSheet={}: {} InferredFact version(s) written, {} retracted, runId={}",
                factSheetId, versionsWritten, retractedAtomKeys.size(), runId);
        return new RegroundResult(versionsWritten, runId, Set.copyOf(retractedAtomKeys));
    }

    /**
     * Load {@code .psl} rule files from {@code <dataDir>/rules/} into the program.
     *
     * <p>This is the minimal pluggable rule seam: projects can drop {@code *.psl} files under
     * {@code data/rules/} (one rule per line, blank lines and {@code #}-comments ignored) and
     * those rules will be picked up by the next cascade. The full rule-authoring UI is a
     * separate product decision (design spec Q1) — this is just the seam.</p>
     *
     * <p>If {@code dataDir} is null/blank or no rules directory exists, this is a no-op.</p>
     */
    private void loadProjectPslRules(PslProgram program) {
        if (dataDir == null || dataDir.isBlank()) return;
        Path rulesDir = Path.of(dataDir, "rules");
        if (!Files.isDirectory(rulesDir)) return;

        try (java.util.stream.Stream<Path> files = Files.list(rulesDir)) {
            files.filter(p -> p.toString().endsWith(".psl"))
                 .sorted()
                 .forEach(ruleFile -> {
                     try {
                         List<String> lines = Files.readAllLines(ruleFile, StandardCharsets.UTF_8);
                         int loaded = 0;
                         for (String line : lines) {
                             String trimmed = line.trim();
                             if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                             try {
                                 program.addRule(trimmed);
                                 loaded++;
                             } catch (Exception e) {
                                 log.warn("PSL rule load: could not parse rule '{}' in {} — {}",
                                         trimmed, ruleFile.getFileName(), e.getMessage());
                             }
                         }
                         log.debug("PSL rule load: {} rules loaded from {}", loaded, ruleFile.getFileName());
                     } catch (IOException e) {
                         log.warn("PSL rule load: could not read {} — {}", ruleFile, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            log.warn("PSL rule load: could not list rules directory {} — {}", rulesDir, e.getMessage());
        }
    }

    /**
     * Build a PSL program from all observed facts in the {@link FactStore}.
     *
     * <p>Every multi-arg fact is registered as an observed atom at its current value.
     * A corresponding target atom {@code derived_<predicate>(args)} is registered so
     * that the MAP solver can produce a derived confidence value (the cascade output
     * visible in the {@link InferredFactStore}). A soft propagation rule
     * {@code 0.8: <pred>(?X) -> derived_<pred>(?X)} is added per predicate.</p>
     *
     * <p>In production this should be replaced by building the program from a
     * {@link ai.kompile.graph.reasoning.model.ReasoningGraph} via
     * {@link ai.kompile.graph.reasoning.psl.GraphPslProgramBuilder} — that requires a live
     * {@link ai.kompile.knowledgegraph.service.KnowledgeGraphService} (not wired here to
     * keep the test surface minimal and avoid the Spring JPA context).</p>
     */
    static PslProgram buildProgramFromFactStore(FactStore factStore) {
        PslProgram program = new PslProgram();
        Set<String> predicates = new LinkedHashSet<>();

        Collection<Fact> allFacts = factStore.allFacts();
        for (Fact fact : allFacts) {
            String atomKey = fact.atomKey();
            double value = fact.value();
            int lp = atomKey.indexOf('(');
            if (lp < 0) {
                // Zero-arity: observe as-is; register as target too
                program.observe(atomKey, value);
                program.target("derived_" + atomKey);
                predicates.add(atomKey);
            } else {
                int rp = atomKey.lastIndexOf(')');
                String predicate = atomKey.substring(0, lp).trim();
                String inside = (rp > lp) ? atomKey.substring(lp + 1, rp).trim() : "";
                if (inside.isEmpty()) {
                    program.observe(predicate, value);
                    program.target("derived_" + predicate);
                    predicates.add(predicate);
                } else {
                    String[] args = splitArgs(inside);
                    program.observe(predicate, value, args);
                    program.target("derived_" + predicate, args);
                    predicates.add(predicate);
                }
            }
        }

        // Add soft propagation rules so the MAP solver derives confidence values.
        for (String pred : predicates) {
            try {
                // Unary rule — works for any arity ≤ 1 in the predicate index
                program.addRule("0.8: " + pred + "(?X) -> derived_" + pred + "(?X)");
            } catch (Exception ignored) {
                // Rule may fail to parse for 0-arity or higher-arity predicates — silently skip
            }
            try {
                program.addRule("0.8: " + pred + "(?X, ?Y) -> derived_" + pred + "(?X, ?Y)");
            } catch (Exception ignored) {
                // Silently skip — only one arity variant will match
            }
        }

        return program;
    }

    /** Split a comma-separated argument string, trimming each token. */
    private static String[] splitArgs(String inside) {
        String[] raw = inside.split(",");
        String[] args = new String[raw.length];
        for (int i = 0; i < raw.length; i++) {
            args[i] = raw[i].trim();
        }
        return args;
    }
}
