/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.web.controllers.grounding;

import ai.kompile.app.services.grounding.KbFactSubscriptionService;
import ai.kompile.app.services.grounding.KbFactSubscriptionService.KbSubscription;
import ai.kompile.app.services.grounding.KbFactSubscriptionService.PollResult;
import ai.kompile.app.services.grounding.KbFactSubscriptionService.SequencedEvent;
import ai.kompile.graph.reasoning.claims.ClaimDossier;
import ai.kompile.graph.reasoning.claims.DossierItem;
import ai.kompile.graph.reasoning.confidence.StrengthBand;
import ai.kompile.graph.reasoning.explain.ProvSerializer;
import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.grounding.ConjunctiveQueryEngine;
import ai.kompile.graph.reasoning.fol.grounding.ConcurrentFactStore;
import ai.kompile.graph.reasoning.fol.grounding.DerivationTree;
import ai.kompile.graph.reasoning.fol.grounding.PlattCalibrator;
import ai.kompile.graph.reasoning.fol.grounding.QueryBinding;
import ai.kompile.graph.reasoning.fol.grounding.StrengthCalibrator;
import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.synthesis.AnswerScorerTrainingHarness;
import ai.kompile.graph.reasoning.synthesis.AnswerSynthesizer;
import ai.kompile.graph.reasoning.tms.BeliefRevisionResult;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.grounding.FactSheetKbState;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.reasoning.AnswerSynthesisService;
import ai.kompile.knowledgegraph.reasoning.GraphToFactStoreProjector;
import ai.kompile.knowledgegraph.reasoning.TraceHumanizer;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * REST controller that surfaces the {@link KbGroundingService} (L2 Spring service)
 * as HTTP endpoints for MCP tools and the frontend.
 *
 * <p>All endpoints are under {@code /api/kb-grounding}. This controller is in the
 * {@code ai.kompile.app.web.controllers.grounding} package, which is registered in
 * {@code GlobalExceptionHandler.basePackages} so structured error responses are
 * returned on failures instead of opaque HTTP 500s.</p>
 *
 * <h3>Endpoint summary</h3>
 * <ul>
 *   <li>{@code POST /verify}   — verify an atom claim; returns SUPPORTED/REFUTED/UNKNOWN</li>
 *   <li>{@code POST /query}    — conjunctive pattern query; returns variable bindings</li>
 *   <li>{@code POST /explain}  — derivation tree for an atom</li>
 *   <li>{@code POST /assert}   — assert a new fact from an agent session</li>
 *   <li>{@code POST /subscribe}              — create a subscription; returns subscriptionId + URLs</li>
 *   <li>{@code GET /subscribe/{id}/events}  — SSE stream for browser/UI consumers</li>
 *   <li>{@code GET /subscribe/{id}/poll}    — cursor long-poll for MCP tools</li>
 *   <li>{@code DELETE /subscribe/{id}}      — cancel a subscription</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/kb-grounding")
public class KbGroundingController {

    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1_000L; // 30 min
    private static final long SSE_HEARTBEAT_INTERVAL_MS = 15_000L;

    private final KbGroundingService groundingService;
    private final PlattCalibrator calibrator;

    /**
     * Optional: subscription service for real-time KB event streaming. Wired in full Spring
     * contexts; null-safe so the controller compiles in plain-lib test contexts where only
     * KbGroundingService is available.
     */
    @Nullable
    @Autowired(required = false)
    private KbFactSubscriptionService subscriptionService;

    /** Executor for SSE heartbeat threads — one thread per active emitter (short-lived). */
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "kb-sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    /**
     * Optional: wired when app-main runs with the full Spring context. Used to resolve
     * atom-key arguments to human-readable entity titles in derivation trees.
     * Null-safe everywhere it is used so the controller works in plain-lib test contexts.
     */
    @Nullable
    @Autowired(required = false)
    private KnowledgeGraphService graphService;

    /**
     * Optional: wired in the full Spring context. Humanizes atom keys, evidence, and
     * rule strings for the grounding-trace UI. Null-safe — falls back to empty maps in
     * plain-lib test contexts where only KbGroundingService is present.
     */
    @Nullable
    @Autowired(required = false)
    private TraceHumanizer traceHumanizer;

    /**
     * Optional: the WP12 answer-synthesis orchestrator. Wired in the full Spring context; null-safe so
     * the rest of the controller works in plain-lib test contexts where only KbGroundingService exists.
     */
    @Nullable
    @Autowired(required = false)
    private AnswerSynthesisService answerSynthesisService;

    /**
     * Optional: UnifiedGraphBridge wired in the full Spring context. Used by
     * {@code POST /claim} to obtain a live UnifiedGraph for the fact sheet so
     * DossierBuilder can query direct edges and paths. Null-safe — when absent,
     * an empty stub graph is used so the verifier and KGE channels still fire.
     */
    @Nullable
    @Autowired(required = false)
    private UnifiedGraphBridge unifiedGraphBridge;

    /** Emitted at most once per controller instance to avoid flooding logs when the full
     *  graph context is not available (e.g. in plain-lib test contexts). */
    private final AtomicBoolean traceHumanizerAbsentWarned = new AtomicBoolean(false);

    @Autowired
    public KbGroundingController(KbGroundingService groundingService) {
        this.groundingService = groundingService;
        this.calibrator = new PlattCalibrator();
    }

    /** Emit a single WARN if traceHumanizer is absent; no-op on subsequent calls. */
    private void warnIfHumanizerAbsent(String consumer) {
        if (traceHumanizer == null && traceHumanizerAbsentWarned.compareAndSet(false, true)) {
            log.warn("[{}] TraceHumanizer bean is absent — raw atom keys / entity ids will be surfaced "
                    + "to the user without title resolution. Ensure kompile-knowledge-graph is on the "
                    + "classpath and the Spring context covers ai.kompile.knowledgegraph.reasoning.", consumer);
        }
    }

    // ── Verify ───────────────────────────────────────────────────────────────────

    /**
     * Verify a factual atom claim against the knowledge base.
     *
     * <p>Returns SUPPORTED, REFUTED, or UNKNOWN with calibrated confidence, evidence,
     * counter-evidence (E4/E5), real derivation depth (fix #3), real sourceProvenance (fix #4),
     * open-world UNKNOWN detail (fix #6), and contradiction descriptions (E4).</p>
     */
    @PostMapping("/verify")
    public ResponseEntity<VerifyResponse> verify(@RequestBody VerifyRequest req) {
        if (req.atom() == null || req.atom().isBlank()) {
            throw new IllegalArgumentException("atom must not be blank");
        }
        long factSheetId = resolveFactSheetId(req.factSheetId());
        Instant asOf = req.asOf() != null ? req.asOf() : Instant.now();

        String atom = resolveAtom(req.atom());
        double threshold = (req.minConfidence() != null && req.minConfidence() > 0.0)
                ? req.minConfidence() : 0.0;

        // Use the enriched verify path that computes depth, sourceProvenance, opinion, contradictions
        // under a single read lock (no redundant store scans).
        KbGroundingService.EnrichedVerifyResult enriched =
                groundingService.verifyEnriched(factSheetId, atom, threshold, 0.5);
        VerifyResult result = enriched.result();

        FactSheetKbState state = groundingService.getState(factSheetId);
        GroundingMeta meta = buildMeta(factSheetId, asOf, state, req.sessionId());

        // Split evidence list: items starting with "0." or containing ":-" are rules
        List<String> evidenceAtoms = new ArrayList<>();
        List<String> activatedRules = new ArrayList<>();
        for (String ev : result.evidence()) {
            if (ev.contains(":-") || (ev.length() > 1 && Character.isDigit(ev.charAt(0)))) {
                activatedRules.add(ev);
            } else {
                evidenceAtoms.add(ev);
            }
        }

        // Humanize evidence + rules for the UI (raw PSL atom keys → entity titles, rule syntax →
        // readable form) using the same TraceHumanizer the /explain endpoint uses. Null-safe: in
        // plain-lib test contexts traceHumanizer is null and the raw strings are surfaced unchanged.
        warnIfHumanizerAbsent("verify");
        List<String> displayEvidence = evidenceAtoms;
        List<String> displayRules = activatedRules;
        if (traceHumanizer != null) {
            displayEvidence = traceHumanizer.humanizeEvidenceAtoms(evidenceAtoms);
            displayRules = traceHumanizer.humanizeRules(activatedRules);
        }

        double calibratedConfidence = calibrator.calibrate(
                result.confidence(), StrengthCalibrator.SignalType.OBSERVED, result);
        StrengthBand band = StrengthBand.fromScalar(calibratedConfidence);

        // Fix #6 + E9: entity-known check and unknownReason for UNKNOWN verdicts
        boolean entityKnown = resolveEntityKnown(atom);
        String unknownReason = null;
        if (result.status() == VerifyResult.Status.UNKNOWN) {
            if (!entityKnown) {
                unknownReason = "entity-not-in-graph";
            } else if (!enriched.nearMissSuggestions().isEmpty()) {
                // E9: near-miss found — entity is known and completing facts identified
                unknownReason = "near-miss";
            } else if (!enriched.contradictions().isEmpty() || !result.counterEvidence().isEmpty()) {
                unknownReason = "contested";
            } else {
                unknownReason = "no-evidence";
            }
        }

        // Build opinion DTO (only populated for UNKNOWN)
        VerifyResponse.OpinionDto opinionDto = null;
        if (enriched.opinion() != null) {
            var op = enriched.opinion();
            opinionDto = new VerifyResponse.OpinionDto(
                    op.belief(), op.disbelief(), op.uncertainty(), op.baseRate());
        }

        // E12: build fragility DTO (only populated for SUPPORTED)
        VerifyResponse.FragilityDto fragilityDto = null;
        if (enriched.fragilityRobustness() != null) {
            fragilityDto = new VerifyResponse.FragilityDto(
                    enriched.fragilityWouldFlipIf() != null ? enriched.fragilityWouldFlipIf() : List.of(),
                    enriched.fragilityMinimalSupportSize() != null ? enriched.fragilityMinimalSupportSize() : 0,
                    enriched.fragilityRobustness()
            );
        }

        // P1-6: build DeepWhyNot DTO from the enriched result
        VerifyResponse.DeepWhyNotDto deepWhyNotDto = null;
        if (enriched.deepWhyNotReport() != null) {
            var report = enriched.deepWhyNotReport();
            List<List<String>> completionSets = report.completionSets().stream()
                    .map(cs -> List.copyOf(cs.facts()))
                    .collect(Collectors.toList());
            deepWhyNotDto = new VerifyResponse.DeepWhyNotDto(
                    completionSets,
                    report.flatSuggestions(),
                    report.budgetExhausted()
            );
        }

        VerifyResponse response = new VerifyResponse(
                result.status().name(),
                result.confidence(),
                displayEvidence,
                displayRules,
                enriched.derivationDepth(),           // fix #3: real depth
                enriched.sourceProvenance(),           // fix #4: real source IDs
                calibratedConfidence,
                band.name(),
                meta,
                enriched.evidenceCount(),             // fix #3: separate evidenceCount
                result.counterEvidence(),             // E4/E5: competing/negating atoms
                result.refutationBasis(),             // E5: why it's refuted/tensioned
                opinionDto,                           // fix #6: SL opinion for UNKNOWN
                enriched.openWorld(),                 // fix #6: open-world flag
                entityKnown,                          // fix #6: subject in graph
                unknownReason,                        // fix #6+E9: "entity-not-in-graph"/"no-evidence"/"contested"/"near-miss"
                enriched.contradictions(),            // E4: detected contradictions
                enriched.nearMissSuggestions(),       // E9: completing facts for near-miss
                fragilityDto,                         // E12: counterfactual fragility
                deepWhyNotDto                         // P1-6: multi-hop completion chains
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Check whether the claim's subject/object resolve to known graph nodes (fix #6 entityKnown).
     * Extracts the first arg of the atom key and does a best-effort node lookup.
     * Returns {@code false} when graphService is unavailable (plain-lib test contexts).
     */
    private boolean resolveEntityKnown(String atomKey) {
        if (graphService == null || atomKey == null) return false;
        int lp = atomKey.indexOf('(');
        int rp = atomKey.lastIndexOf(')');
        if (lp < 0 || rp <= lp) return false;
        String inside = atomKey.substring(lp + 1, rp).trim();
        if (inside.isBlank()) return false;
        // Check the first argument only (the "subject" of most binary predicates)
        int comma = inside.indexOf(',');
        String firstArg = (comma >= 0 ? inside.substring(0, comma) : inside).trim();
        if (firstArg.isBlank()) return false;
        // Try as node id
        if (graphService.getNode(firstArg).isPresent()) return true;
        // Try as external id across common node levels
        for (var level : ATOM_LOOKUP_LEVELS) {
            if (graphService.getNodeByExternalId(firstArg, level).isPresent()) return true;
        }
        return false;
    }

    // ── Synthesize (WP12) ──────────────────────────────────────────────────────────

    /**
     * Synthesize ranked answers to a natural-language query by fusing calibrated signals about each
     * candidate entity (retrieval, ontology type, and — as they are wired — KB verification, MEBN, and
     * consistency). Each answer carries the operator-tree trace that produced its likelihood.
     */
    @PostMapping("/synthesize")
    public ResponseEntity<SynthesizeResponse> synthesize(@RequestBody SynthesizeRequest req) {
        if (req.query() == null || req.query().isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (answerSynthesisService == null) {
            throw new IllegalStateException("Answer synthesis is not available in this context");
        }
        long factSheetId = resolveFactSheetId(req.factSheetId());
        int maxCandidates = req.maxCandidates() != null ? req.maxCandidates() : 0;

        List<AnswerSynthesizer.SynthesizedAnswer> answers = answerSynthesisService.synthesize(
                factSheetId, req.query(), req.expectedType(), maxCandidates);

        warnIfHumanizerAbsent("synthesize");
        List<SynthesizeResponse.Answer> views = answers.stream()
                .map(a -> new SynthesizeResponse.Answer(
                        resolveEntityTitle(a.entityId()),
                        a.entityId(),
                        a.likelihood(),
                        a.opinion().belief(),
                        a.opinion().disbelief(),
                        a.opinion().uncertainty(),
                        a.trace()))
                .collect(Collectors.toList());

        return ResponseEntity.ok(new SynthesizeResponse(req.query(), factSheetId, views.size(), views));
    }

    // ── Train scorer (WP12 learned re-ranker) ──────────────────────────────────────

    /**
     * Train the answer-scorer from a golden QA set and report held-out metrics — learned vs.
     * retrieval-only MRR plus the {@code beatsBaseline} ship-gate. Optionally writes the model JSON to
     * {@code savePath} so the live re-rank ({@code kbAnswerScorerModelPath}) picks it up.
     */
    @PostMapping("/train-scorer")
    public ResponseEntity<TrainScorerResponse> trainScorer(@RequestBody TrainScorerRequest req) {
        if (req.golden() == null || req.golden().isEmpty()) {
            throw new IllegalArgumentException("golden must not be empty");
        }
        if (answerSynthesisService == null) {
            throw new IllegalStateException("Answer synthesis is not available in this context");
        }
        List<AnswerSynthesisService.GoldenQA> golden = req.golden().stream()
                .filter(g -> g != null && g.query() != null && g.correctAnswer() != null)
                .map(g -> new AnswerSynthesisService.GoldenQA(
                        g.query(), g.correctAnswer(),
                        g.factSheetId() != null ? g.factSheetId() : 0L, g.expectedType()))
                .collect(Collectors.toList());
        double heldOut = req.heldOutFraction() != null ? req.heldOutFraction() : 0.3;

        AnswerScorerTrainingHarness.TrainingReport r =
                answerSynthesisService.trainAnswerScorer(golden, heldOut, req.savePath());

        return ResponseEntity.ok(new TrainScorerResponse(
                r.trainRows(), r.heldOutQueries(), r.trainLogLoss(), r.heldOutLogLoss(),
                r.heldOutAccuracy(), r.learnedMrr(), r.retrievalBaselineMrr(), r.algebraicFoldMrr(),
                r.beatsBaseline(), r.beatsFold(),
                r.learnedRecallAt1(), r.learnedRecallAt3(), r.learnedNdcgAt3(),
                r.featureNames(), r.weights(), r.bias()));
    }

    // ── Query ────────────────────────────────────────────────────────────────────

    /**
     * Conjunctive pattern query against the KB.
     *
     * <p>Each conjunct is an atom pattern with "?"-prefixed variables.</p>
     */
    @PostMapping("/query")
    public ResponseEntity<QueryResponse> query(@RequestBody QueryRequest req) {
        if (req.conjuncts() == null || req.conjuncts().isEmpty()) {
            throw new IllegalArgumentException("conjuncts must not be empty");
        }
        int maxResults = req.maxResults() > 0 ? req.maxResults() : ConjunctiveQueryEngine.DEFAULT_MAX_RESULTS;
        if (maxResults > 1000) {
            throw new IllegalArgumentException("maxResults must not exceed 1000");
        }

        long factSheetId = resolveFactSheetId(req.factSheetId());
        Instant asOf = req.asOf() != null ? req.asOf() : Instant.now();

        // Map request conjuncts to lib AtomPattern
        List<ConjunctiveQueryEngine.AtomPattern> patterns = req.conjuncts().stream()
                .map(c -> new ConjunctiveQueryEngine.AtomPattern(c.predicate(), c.args()))
                .collect(Collectors.toList());

        List<QueryBinding> raw = groundingService.query(factSheetId, patterns, maxResults);

        double minConf = req.minConfidence() > 0 ? req.minConfidence() : 0.3;
        List<QueryBinding> filtered = raw.stream()
                .filter(b -> b.confidence() >= minConf)
                .collect(Collectors.toList());

        boolean truncated = raw.size() == maxResults;

        warnIfHumanizerAbsent("query");
        List<QueryResponse.BindingRow> rows = filtered.stream()
                .map(b -> new QueryResponse.BindingRow(
                        b.bindings(),
                        b.confidence(),
                        List.of(),   // matchedAtoms: not surfaced by lib QueryBinding; empty for Phase 1
                        buildDisplayBindings(b.bindings())
                ))
                .collect(Collectors.toList());

        FactSheetKbState state = groundingService.getState(factSheetId);
        GroundingMeta meta = buildMeta(factSheetId, asOf, state, req.sessionId());

        return ResponseEntity.ok(new QueryResponse(rows, rows.size(), truncated, meta));
    }

    // ── Explain ──────────────────────────────────────────────────────────────────

    /**
     * Build a derivation tree explaining why the KB believes (or disbelieves) a fact.
     */
    @PostMapping("/explain")
    public ResponseEntity<ExplainResponse> explain(@RequestBody ExplainRequest req) {
        if (req.atom() == null || req.atom().isBlank()) {
            throw new IllegalArgumentException("atom must not be blank");
        }
        long factSheetId = resolveFactSheetId(req.factSheetId());
        Instant asOf = Instant.now();

        int depth = req.depth() > 0
                ? Math.min(req.depth(), DerivationTree.DEFAULT_MAX_DEPTH)
                : DerivationTree.DEFAULT_MAX_DEPTH;

        String atom = resolveAtom(req.atom());
        DerivationTree tree = groundingService.explain(factSheetId, atom, depth);
        VerifyResult verdict = groundingService.verify(factSheetId, atom);

        // Build human-readable title map and rule humanization map via TraceHumanizer.
        // traceHumanizer is null in plain-lib test contexts (required=false) — fall back to empty maps.
        warnIfHumanizerAbsent("explain");
        Map<String, String> atomKeyToTitle;
        Map<String, String> ruleToHumanized;
        if (traceHumanizer != null) {
            atomKeyToTitle = traceHumanizer.buildAtomKeyToTitle(tree.allAtomKeys());
            List<String> treeRules = new ArrayList<>();
            collectRuleStrings(tree, treeRules);
            ruleToHumanized = traceHumanizer.buildRuleMap(treeRules);
        } else {
            atomKeyToTitle = Map.of();
            ruleToHumanized = Map.of();
        }

        String summary = deterministicSummary(tree, atom, verdict, atomKeyToTitle);

        FactSheetKbState state = groundingService.getState(factSheetId);
        GroundingMeta meta = buildMeta(factSheetId, asOf, state, req.sessionId());

        ExplainResponse response = new ExplainResponse(
                atom,
                verdict.status().name(),
                verdict.confidence(),
                summary,
                tree.toJsonWithTitlesAndRules(atomKeyToTitle, ruleToHumanized),
                meta
        );
        return ResponseEntity.ok(response);
    }

    // ── Assert ───────────────────────────────────────────────────────────────────

    /**
     * Assert a fact from an agent session into the KB.
     *
     * <p>Contradiction-checking runs synchronously. A background re-reasoning cascade is
     * scheduled after the write lock is released — {@code cascadeTriggered=true} signals
     * that cascade is in-flight. The {@code stale} flag in subsequent verify/query responses
     * will be true until the cascade completes.</p>
     *
     * <h4>Soft retract via assert</h4>
     * <p>Passing {@code value=0.0} performs a <em>soft overwrite</em> of the fact to a
     * zero soft-truth value and returns status {@code RETRACTED}. This differs from the true
     * TMS retraction at {@code POST /retract} in that the fact key remains in the observed
     * store (at value 0.0) rather than being physically removed. Use {@code /retract} when
     * you need proper dependency-analysis (which atoms became unsupported) and need retracted
     * atoms to disappear from query results immediately.</p>
     */
    @PostMapping("/assert")
    public ResponseEntity<AssertResponse> assertFact(@RequestBody AssertRequest req) {
        if (req.atom() == null || req.atom().isBlank()) {
            throw new IllegalArgumentException("atom must not be blank");
        }
        if (req.factSheetId() == null) {
            throw new IllegalArgumentException("factSheetId is required for assert");
        }
        if (req.value() < 0.0 || req.value() > 1.0) {
            throw new IllegalArgumentException("value must be in [0,1]");
        }

        long factSheetId = req.factSheetId();

        // Build source ID from sessionId + source per the spec's provenance convention
        String sourceId = buildSourceId(req.sessionId(), req.source());
        Fact fact = Fact.soft(resolveAtom(req.atom()), req.value(), sourceId);

        KbGroundingService.AssertResult result;
        if (req.expectedVersion() != null) {
            result = groundingService.assertFact(factSheetId, fact, req.expectedVersion());
        } else {
            result = groundingService.assertFact(factSheetId, fact);
        }

        // Determine status
        String status;
        boolean cascadeTriggered = false;
        if (result.isConflict()) {
            status = "CONFLICT_QUEUED";
        } else if (!result.contradictions().isEmpty()) {
            status = "CONTRADICTION_DETECTED";
        } else if (req.value() == 0.0) {
            status = "RETRACTED";
            cascadeTriggered = true;
        } else {
            status = "ASSERTED";
            cascadeTriggered = true;
        }

        FactSheetKbState state = groundingService.getState(factSheetId);
        long kbVersion = result.isConflict()
                ? state.concurrentFactStore().version()
                : result.version();
        GroundingMeta meta = new GroundingMeta(
                factSheetId,
                Instant.now(),
                cascadeTriggered,           // stale if cascade was triggered
                cascadeTriggered ? 2500L : 0L,
                kbVersion,
                req.sessionId()
        );

        return ResponseEntity.ok(new AssertResponse(
                status,
                result.isConflict() ? ConcurrentFactStore.CONFLICT : result.version(),
                result.contradictions(),
                cascadeTriggered,
                meta
        ));
    }

    // ── Retract ──────────────────────────────────────────────────────────────────

    /**
     * True TMS retraction: physically removes an atom from the observed fact store,
     * performs dependency analysis (which derived atoms became unsupported or weakened),
     * and schedules a background re-reasoning cascade.
     *
     * <h4>When to use this vs. {@code POST /assert} with {@code value=0.0}</h4>
     * <ul>
     *   <li>{@code /retract} — TMS retraction with full dependency analysis. The atom is
     *       physically removed from the store. {@code dependentAtomsUnsupported} lists every
     *       atom whose only support was the retracted fact. Use this when correctness matters
     *       (e.g. resolving a conflict found by the process miner or belief revision).</li>
     *   <li>{@code /assert value=0.0} — soft overwrite: the atom key remains at value 0.0.
     *       No dependency analysis, no unsupported list. Use for quick probabilistic downgrades
     *       where physical removal is not required.</li>
     * </ul>
     *
     * <h4>Modes</h4>
     * <ul>
     *   <li>{@code "retract"} (default) — single-atom retraction; background cascade re-reasons.</li>
     *   <li>{@code "revise"} — additionally removes all sole-dependent atoms from the store
     *       synchronously before returning, giving a cleaner in-flight state while the cascade runs.</li>
     * </ul>
     *
     * @param req the retraction request carrying factSheetId, atomKey, and optional mode
     * @return {@code 200 OK} with status {@code "RETRACTED"} or {@code "NOT_FOUND"} plus
     *         dependency summary and cascade flag
     */
    @PostMapping("/retract")
    public ResponseEntity<RetractResponse> retractFact(@RequestBody RetractRequest req) {
        if (req.atomKey() == null || req.atomKey().isBlank()) {
            throw new IllegalArgumentException("atomKey must not be blank");
        }
        if (req.factSheetId() == null) {
            throw new IllegalArgumentException("factSheetId is required for retract");
        }

        long factSheetId = req.factSheetId();
        String atomKey = resolveAtom(req.atomKey().trim());
        String mode = (req.mode() == null || req.mode().isBlank()) ? "retract" : req.mode().trim();

        KbGroundingService.RetractResult retractResult;
        if ("revise".equalsIgnoreCase(mode)) {
            retractResult = groundingService.retractAndRevise(factSheetId, atomKey);
        } else {
            retractResult = groundingService.retractFact(factSheetId, atomKey);
        }

        BeliefRevisionResult result = retractResult.revision();
        String status = retractResult.found() ? "RETRACTED" : "NOT_FOUND";

        java.util.List<String> unsupported = result.unsupportedAtoms() != null
                ? new java.util.ArrayList<>(result.unsupportedAtoms())
                : java.util.List.of();
        java.util.List<String> weakened = result.weakenedAtoms() != null
                ? new java.util.ArrayList<>(result.weakenedAtoms())
                : java.util.List.of();

        FactSheetKbState state = groundingService.getState(factSheetId);
        GroundingMeta meta = new GroundingMeta(
                factSheetId,
                Instant.now(),
                true,      // stale — cascade is now in-flight
                2500L,
                state.concurrentFactStore().version(),
                null
        );

        return ResponseEntity.ok(new RetractResponse(
                status,
                atomKey,
                mode.toLowerCase(),
                unsupported,
                weakened,
                true,  // cascadeTriggered — event was published by retractFact/retractAndRevise
                meta
        ));
    }

    // ── Claim dossier (P1-5) ─────────────────────────────────────────────────

    /**
     * Assess a (subject, predicate, object) claim and return a fused multi-signal dossier.
     *
     * <p>Aggregates five evidence channels — direct graph edge, KB verifier, connecting path,
     * KGE link-plausibility, and mined rules — into a single verdict + per-signal breakdown
     * and fused confidence. The full reasoning trace rides along.</p>
     *
     * <p>If {@code factSheetId} is absent the global fact sheet (0L) is used.
     * The live UnifiedGraph is obtained from {@link UnifiedGraphBridge}; when the bridge is
     * unavailable a lightweight stub (entities known to the KB only) is used so the verifier
     * and path channels still fire.</p>
     */
    @PostMapping("/claim")
    public ResponseEntity<ClaimResponse> assessClaim(@RequestBody ClaimRequest req) {
        if (req.subject() == null || req.subject().isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        if (req.predicate() == null || req.predicate().isBlank()) {
            throw new IllegalArgumentException("predicate must not be blank");
        }
        if (req.object() == null || req.object().isBlank()) {
            throw new IllegalArgumentException("object must not be blank");
        }

        long factSheetId = resolveFactSheetId(req.factSheetId());

        // Obtain a ReasoningGraph: prefer the live UnifiedGraph from the bridge; fall back to
        // a minimal stub graph so the verifier+KGE channels still fire without the bridge.
        ReasoningGraph graph = resolveGraphForClaim(factSheetId);

        ClaimDossier dossier = groundingService.assessClaim(
                factSheetId, graph, req.subject(), req.predicate(), req.object());

        // Map supporting/refuting items to plain-English signal names
        List<ClaimResponse.SignalItem> supporting = dossier.supporting().stream()
                .map(item -> new ClaimResponse.SignalItem(
                        plainEnglishSignal(item.kind()),
                        item.description(),
                        item.probability(),
                        item.provenance()))
                .collect(Collectors.toList());

        List<ClaimResponse.SignalItem> refuting = dossier.refuting().stream()
                .map(item -> new ClaimResponse.SignalItem(
                        plainEnglishSignal(item.kind()),
                        item.description(),
                        item.probability(),
                        item.provenance()))
                .collect(Collectors.toList());

        // Derive a human verdict label from fused score
        String verdict = dossier.fusedScore() >= 0.65 ? "SUPPORTED"
                : dossier.fusedScore() <= 0.35 ? "REFUTED"
                : "UNCERTAIN";

        FactSheetKbState state = groundingService.getState(factSheetId);
        GroundingMeta meta = buildMeta(factSheetId, java.time.Instant.now(), state, req.sessionId());

        // Emit the reasoning trace via the same pattern as the explain endpoint.
        ai.kompile.graph.reasoning.explain.ReasoningTrace trace = dossier.toReasoningTrace();

        return ResponseEntity.ok(new ClaimResponse(
                dossier.claimAtom(),
                dossier.subject(),
                dossier.predicate(),
                dossier.object(),
                verdict,
                dossier.fusedScore(),
                supporting,
                refuting,
                trace,
                meta
        ));
    }

    /**
     * Resolve a live {@link ReasoningGraph} for the given fact sheet.
     * Returns an empty stub {@link ai.kompile.graph.reasoning.unified.UnifiedGraph}
     * when {@link UnifiedGraphBridge} is not wired or export fails.
     */
    private ReasoningGraph resolveGraphForClaim(long factSheetId) {
        if (unifiedGraphBridge != null) {
            try {
                ai.kompile.graph.reasoning.unified.UnifiedGraph ug =
                        unifiedGraphBridge.export(factSheetId);
                if (ug != null) return ug;
            } catch (Exception e) {
                log.debug("[claim] UnifiedGraphBridge export failed for factSheet={}: {}",
                        factSheetId, e.getMessage());
            }
        }
        // Stub: empty mutable graph satisfying the ReasoningGraph contract
        return new ai.kompile.graph.reasoning.unified.UnifiedGraph();
    }

    /** Map a DossierItem.Kind to a plain-English signal label for the API response. */
    private static String plainEnglishSignal(DossierItem.Kind kind) {
        return switch (kind) {
            case DIRECT_EDGE        -> "direct connection";
            case DATALOG_PROOF      -> "verified facts";
            case PSL                -> "verified facts";
            case PATH               -> "connecting paths";
            case KGE                -> "link plausibility";
            case MINED_RULE         -> "learned rules";
            case FUNCTIONAL_CONFLICT-> "functional conflict";
            case NEGATED_ATOM       -> "negated fact";
        };
    }

    // ── Predicate discovery (P2 enabler) ─────────────────────────────────────

    /**
     * List the distinct predicate names in a fact sheet's current KB state, with counts.
     *
     * <p>Returns both observed and inferred predicates. When a predicate appears only in the
     * inferred store (i.e. no observed base fact exists with that predicate) it is flagged
     * {@code inferred=true}.</p>
     *
     * <p>Response shape:
     * <pre>{"predicates":[{"name":"isEmployedBy","count":14,"inferred":false}, ...]}</pre>
     *
     * @param factSheetId scope to a specific fact sheet (null/absent = global sheet 0)
     */
    @org.springframework.web.bind.annotation.GetMapping("/predicates")
    public ResponseEntity<PredicatesResponse> listPredicates(
            @RequestParam(required = false) Long factSheetId) {
        long fsId = resolveFactSheetId(factSheetId);
        FactSheetKbState state = groundingService.getState(fsId);

        List<PredicatesResponse.PredicateEntry> entries = new ArrayList<>();
        // Use inferred store for full enumeration with counts
        java.util.Map<String, Integer> inferredCounts = new java.util.LinkedHashMap<>();
        for (InferredFact inf : state.inferredFactStore().allLatest()) {
            String pred = extractPredicate(inf.atomKey());
            if (pred != null) inferredCounts.merge(pred, 1, Integer::sum);
        }
        java.util.Map<String, Integer> observedCounts = new java.util.LinkedHashMap<>();
        for (ai.kompile.graph.reasoning.fol.Fact f : state.factStore().allFacts()) {
            String pred = extractPredicate(f.atomKey());
            if (pred != null) observedCounts.merge(pred, 1, Integer::sum);
        }
        // Union of all predicates
        java.util.Set<String> allPreds = new java.util.LinkedHashSet<>();
        allPreds.addAll(observedCounts.keySet());
        allPreds.addAll(inferredCounts.keySet());
        for (String pred : allPreds) {
            int obsCnt = observedCounts.getOrDefault(pred, 0);
            int infCnt = inferredCounts.getOrDefault(pred, 0);
            int total  = Math.max(obsCnt, infCnt);  // don't double-count (inferred often overlaps observed)
            boolean inferred = obsCnt == 0 && infCnt > 0;
            entries.add(new PredicatesResponse.PredicateEntry(pred, total, inferred));
        }
        // Sort by count descending, then by name ascending
        entries.sort(java.util.Comparator
                .comparingInt(PredicatesResponse.PredicateEntry::count).reversed()
                .thenComparing(PredicatesResponse.PredicateEntry::name));

        return ResponseEntity.ok(new PredicatesResponse(entries));
    }

    /** Extract predicate name from an atom key like {@code pred(args...)}. Returns null for malformed keys. */
    private static String extractPredicate(String atomKey) {
        if (atomKey == null || atomKey.isBlank()) return null;
        int lp = atomKey.indexOf('(');
        if (lp <= 0) return null;
        return atomKey.substring(0, lp).trim();
    }

    // ── Subscribe ─────────────────────────────────────────────────────────────

    /**
     * Create a KB subscription for browser SSE or MCP long-poll consumers.
     *
     * <p>Returns a {@code subscriptionId} with two consumption URLs:
     * <ul>
     *   <li>{@code eventsUrl} — SSE stream for browsers/UI: {@code GET /subscribe/{id}/events}</li>
     *   <li>{@code pollUrl}   — cursor long-poll for MCP tools: {@code GET /subscribe/{id}/poll}</li>
     * </ul>
     *
     * @param req {@code factSheetId} (null → 0) and {@code predicates} filter (null → all)
     * @return 200 with the subscription descriptor, or 503 if the subscription service is unavailable
     */
    @PostMapping("/subscribe")
    public ResponseEntity<SubscribeResponse> subscribe(
            @RequestBody(required = false) SubscribeRequest req) {
        if (subscriptionService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new SubscribeResponse("KbFactSubscriptionService not available in this context"));
        }
        long factSheetId = (req != null && req.factSheetId() != null) ? req.factSheetId() : 0L;
        List<String> predicates = (req != null && req.predicates() != null) ? req.predicates() : List.of();

        KbSubscription sub = subscriptionService.createSubscription(factSheetId, predicates);
        String eventsUrl = "/api/kb-grounding/subscribe/" + sub.subscriptionId() + "/events";
        String pollUrl   = "/api/kb-grounding/subscribe/" + sub.subscriptionId() + "/poll";
        return ResponseEntity.ok(new SubscribeResponse(
                sub.subscriptionId(), eventsUrl, pollUrl, sub.expiresAt(), null));
    }

    /**
     * SSE stream for browser/UI consumers. Sends live KB events as JSON-encoded SSE events
     * and a heartbeat comment every ~15 seconds to keep the connection alive.
     *
     * <p>Event names match the {@code type} field of the payload: {@code asserted},
     * {@code retracted}, {@code graph_mutated}, {@code changed}.</p>
     *
     * @param subscriptionId the UUID returned by {@code POST /subscribe}
     * @return SSE emitter (30 min timeout); {@code 404} if the id is unknown or expired
     */
    @GetMapping(value = "/subscribe/{subscriptionId}/events",
                produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> subscribeEvents(@PathVariable String subscriptionId) {
        if (subscriptionService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        Optional<KbSubscription> subOpt = subscriptionService.get(subscriptionId);
        if (subOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        KbSubscription sub = subOpt.get();

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        // Drain any buffered events immediately
        PollResult buffered = sub.poll(-1L, 0);
        sendBufferedToSse(emitter, buffered);

        // Heartbeat thread that also drains newly arriving events
        AtomicBoolean done = new AtomicBoolean(false);
        emitter.onCompletion(() -> done.set(true));
        emitter.onTimeout(() -> done.set(true));
        emitter.onError(ex -> done.set(true));

        long[] cursor = { buffered.nextCursor() };
        sseExecutor.submit(() -> {
            while (!done.get()) {
                try {
                    // Long-park for up to heartbeat interval, then wake up to drain + send heartbeat
                    PollResult result = sub.poll(cursor[0], SSE_HEARTBEAT_INTERVAL_MS);
                    if (done.get()) break;

                    // Send any new events
                    for (SequencedEvent se : result.events()) {
                        if (done.get()) break;
                        try {
                            String json = buildEventJson(se);
                            emitter.send(SseEmitter.event()
                                    .name(se.event().type())
                                    .data(json, MediaType.APPLICATION_JSON));
                        } catch (IOException ex) {
                            done.set(true);
                            break;
                        }
                    }
                    cursor[0] = result.nextCursor();

                    // Always send heartbeat after each poll cycle (whether or not there were events)
                    if (!done.get()) {
                        try {
                            emitter.send(SseEmitter.event()
                                    .comment("heartbeat")
                                    .name("heartbeat")
                                    .data(Map.of("ts", System.currentTimeMillis())));
                        } catch (IOException ex) {
                            done.set(true);
                        }
                    }
                } catch (IllegalArgumentException ex) {
                    // Subscription expired or cancelled
                    done.set(true);
                } catch (Exception ex) {
                    log.debug("[kb-sse] error on subscription {}: {}", subscriptionId, ex.getMessage());
                    done.set(true);
                }
            }
            try { emitter.complete(); } catch (Exception ignored) {}
        });

        return ResponseEntity.ok(emitter);
    }

    /**
     * Cursor-based long-poll for MCP tool consumers.
     *
     * <p>Returns immediately if events with {@code seq > cursor} already exist in the ring buffer;
     * otherwise parks the calling thread for up to {@code waitMs} ms (capped server-side at 25 s).
     * Pass the returned {@code nextCursor} in the next call.</p>
     *
     * <p>Example response:
     * <pre>{@code
     * {
     *   "events": [
     *     {"seq":1, "ts":"...", "type":"asserted", "factSheetId":42,
     *      "atomKey":"trusts(Alice,Bob)", "value":0.9, "source":"agent-sess-001"}
     *   ],
     *   "nextCursor": 1,
     *   "expiresAt": "...",
     *   "overflow": false
     * }
     * }</pre>
     *
     * @param subscriptionId the UUID returned by {@code POST /subscribe}
     * @param cursor         return only events with seq strictly greater than this; default -1
     *                       (returns all buffered events on the first call)
     * @param waitMs         max time to wait for new events; capped at 25000 ms; default 10000
     * @return 200 with poll result; 404 if id is unknown or expired
     */
    @GetMapping("/subscribe/{subscriptionId}/poll")
    public ResponseEntity<PollResponse> pollSubscription(
            @PathVariable String subscriptionId,
            @RequestParam(defaultValue = "-1") long cursor,
            @RequestParam(defaultValue = "10000") long waitMs) {
        if (subscriptionService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        Optional<KbSubscription> subOpt = subscriptionService.get(subscriptionId);
        if (subOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        KbSubscription sub = subOpt.get();

        PollResult result;
        try {
            result = subscriptionService.poll(subscriptionId, cursor, waitMs);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        List<PollResponse.EventDto> dtos = result.events().stream()
                .map(se -> new PollResponse.EventDto(
                        se.seq(), se.ts(),
                        se.event().type(), se.event().factSheetId(),
                        se.event().atomKey(), se.event().value(), se.event().source()))
                .collect(Collectors.toList());

        return ResponseEntity.ok(new PollResponse(dtos, result.nextCursor(), sub.expiresAt(), result.overflow()));
    }

    /**
     * Cancel a subscription and free its ring buffer.
     *
     * @param subscriptionId the UUID to cancel
     * @return 204 on success; 404 if unknown or already expired
     */
    @DeleteMapping("/subscribe/{subscriptionId}")
    public ResponseEntity<Void> cancelSubscription(@PathVariable String subscriptionId) {
        if (subscriptionService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        Optional<KbSubscription> subOpt = subscriptionService.get(subscriptionId);
        if (subOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        subscriptionService.cancel(subscriptionId);
        return ResponseEntity.noContent().build();
    }

    // ── Subscribe internal helpers ────────────────────────────────────────────────

    /** Drain buffered events from a PollResult and push them synchronously to an SSE emitter. */
    private void sendBufferedToSse(SseEmitter emitter, PollResult buffered) {
        for (SequencedEvent se : buffered.events()) {
            try {
                emitter.send(SseEmitter.event()
                        .name(se.event().type())
                        .data(buildEventJson(se), MediaType.APPLICATION_JSON));
            } catch (IOException ex) {
                log.debug("[kb-sse] failed to send buffered event seq={}: {}", se.seq(), ex.getMessage());
                return;
            }
        }
    }

    /** Serialize a SequencedEvent to a JSON string for SSE payloads. */
    private String buildEventJson(SequencedEvent se) {
        // Build a simple JSON string directly to avoid a jackson dependency on the inner record.
        // Format: {"seq":N,"ts":"...","type":"...","factSheetId":N,"atomKey":"...","value":N,"source":"..."}
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"seq\":").append(se.seq()).append(",");
        sb.append("\"ts\":\"").append(se.ts()).append("\",");
        sb.append("\"type\":\"").append(se.event().type()).append("\",");
        sb.append("\"factSheetId\":").append(se.event().factSheetId());
        if (se.event().atomKey() != null) {
            sb.append(",\"atomKey\":\"").append(se.event().atomKey().replace("\"", "\\\"")).append("\"");
        }
        if (se.event().value() != null) {
            sb.append(",\"value\":").append(se.event().value());
        }
        if (se.event().source() != null) {
            sb.append(",\"source\":\"").append(se.event().source().replace("\"", "\\\"")).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /**
     * Resolve factSheetId: if null, use 0L as the default (global) fact sheet.
     */
    private long resolveFactSheetId(Long factSheetId) {
        return factSheetId != null ? factSheetId : 0L;
    }

    /**
     * Resolve an entity id to a human-readable title. Uses the TraceHumanizer when available
     * (graph lookup via KnowledgeGraphService), falls back to {@link TraceHumanizer#cleanLabel}
     * (no graph required). Never returns a raw path-bearing or UUID-shaped string to the caller.
     *
     * @param entityId raw entity id from the synthesis pipeline
     * @return human-readable title; never null
     */
    private String resolveEntityTitle(String entityId) {
        if (entityId == null || entityId.isBlank()) return entityId != null ? entityId : "";
        // Try via traceHumanizer (which does a real graph lookup)
        if (traceHumanizer != null) {
            String humanized = traceHumanizer.humanizeAtom(entityId);
            if (humanized != null && !humanized.isBlank() && !humanized.equals(entityId)) {
                return humanized;
            }
        }
        // Try direct node lookup when graphService is available
        if (graphService != null) {
            String byId = graphService.getNode(entityId)
                    .map(n -> n.getTitle() != null && !n.getTitle().isBlank() ? n.getTitle() : null)
                    .orElse(null);
            if (byId != null) return byId;
        }
        // Final fallback: static label cleaner (strips paths/underscores, never raw UUID)
        return TraceHumanizer.cleanLabel(entityId);
    }

    /**
     * Build a parallel {@code displayVariables} map for a binding row. Each raw variable value is
     * resolved to a human title via {@link #resolveEntityTitle} (graph lookup when available, static
     * clean-label fallback otherwise). The display map has the same keys as {@code raw} but
     * human-readable values — clients should prefer these when rendering results to users.
     *
     * @param raw raw binding variables (variable name → raw entity id)
     * @return mutable map of variable name → human title; empty when raw is null/empty
     */
    private Map<String, String> buildDisplayBindings(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<String, String> display = new LinkedHashMap<>(raw.size() * 2);
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String humanized = resolveEntityTitle(entry.getValue());
            display.put(entry.getKey(), humanized);
        }
        return display;
    }

    /** NodeLevels tried, in priority order, when resolving a bare external id to a node. */
    private static final NodeLevel[] ATOM_LOOKUP_LEVELS = {
        NodeLevel.ENTITY, NodeLevel.SOURCE, NodeLevel.DOCUMENT, NodeLevel.SNIPPET,
        NodeLevel.TABLE, NodeLevel.CUSTOM, NodeLevel.ATTACHMENT, NodeLevel.IDENTIFIER
    };

    /** Matches a well-formed PSL atom key: {@code predicate(args...)}. */
    private static final java.util.regex.Pattern ATOM_SHAPE =
            java.util.regex.Pattern.compile("^[\\w\\-]+\\(.*\\)$", java.util.regex.Pattern.DOTALL);

    /**
     * Resolve a UI-supplied target into the PSL atom key the fact store is keyed by.
     *
     * <p>The graph UI passes a node id (e.g. {@code entity_country_usa}) or an external id, but the
     * store is keyed by atom keys built by {@link GraphToFactStoreProjector} (e.g.
     * {@code entity(country_usa)}). Without this, KB-Context "Verify"/"Why?" always returned UNKNOWN
     * for graph-selected nodes. Already atom-shaped inputs — and inputs that resolve to no node —
     * pass through unchanged, so existing atom-key callers (e.g. MCP tools) are unaffected.</p>
     */
    private String resolveAtom(String input) {
        if (input == null || input.isBlank()) return input;
        String trimmed = input.trim();
        if (ATOM_SHAPE.matcher(trimmed).matches()) {
            return trimmed; // already an atom key
        }
        if (graphService == null) return trimmed;
        // Try as an internal node id (the form the graph visualizer emits).
        String byNodeId = graphService.getNode(trimmed)
                .map(GraphToFactStoreProjector::atomKeyForNode)
                .orElse(null);
        if (byNodeId != null) return byNodeId;
        // Fall back: try as an external id across the common node levels.
        for (NodeLevel level : ATOM_LOOKUP_LEVELS) {
            String byExt = graphService.getNodeByExternalId(trimmed, level)
                    .map(GraphToFactStoreProjector::atomKeyForNode)
                    .orElse(null);
            if (byExt != null) return byExt;
        }
        return trimmed;
    }

    /**
     * Build the common meta-block from the current state.
     *
     * <p>The {@code stale} flag is read directly from {@link KbGroundingService#isStale}: it
     * is {@code true} when a graph mutation has been observed but the re-ground cascade has not
     * yet completed. The {@code stalenessBudgetMs} is set to 3 s when stale to give the UI a
     * polling hint; 0 when fresh.</p>
     */
    private GroundingMeta buildMeta(long factSheetId, Instant asOf,
                                    FactSheetKbState state, String sessionId) {
        long version = state.concurrentFactStore().version();
        boolean stale = groundingService.isStale(factSheetId);
        return new GroundingMeta(factSheetId, asOf, stale, stale ? 3000L : 0L, version, sessionId);
    }

    /**
     * Build a combined provenance source ID from sessionId + source label.
     */
    private String buildSourceId(String sessionId, String source) {
        if (sessionId != null && !sessionId.isBlank() && source != null && !source.isBlank()) {
            return sessionId + ":" + source;
        } else if (sessionId != null && !sessionId.isBlank()) {
            return sessionId;
        } else if (source != null && !source.isBlank()) {
            return source;
        }
        return "agent-assert";
    }

    /** Collect all non-null ruleApplied strings from a derivation tree (BFS). */
    private static void collectRuleStrings(DerivationTree tree, List<String> acc) {
        if (tree.ruleApplied() != null) acc.add(tree.ruleApplied());
        for (DerivationTree child : tree.children()) {
            collectRuleStrings(child, acc);
        }
    }

    /**
     * Deterministic NL summary of a derivation tree — no LLM required.
     *
     * @param atomKeyToTitle optional map from atom key to human-readable title; used to replace
     *                       synthetic PSL constants with display names in child descriptions
     */
    private String deterministicSummary(DerivationTree tree, String atom,
            VerifyResult verdict, Map<String, String> atomKeyToTitle) {
        if (verdict.status() == VerifyResult.Status.UNKNOWN) {
            return "The atom '" + atom + "' is not derivable from the current KB.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("'").append(atom).append("' is ")
                .append(verdict.status().name().toLowerCase())
                .append(" (confidence ").append(String.format("%.2f", verdict.confidence())).append(")");
        if (!tree.children().isEmpty()) {
            sb.append(" because: ");
            List<String> childDescs = new ArrayList<>();
            for (DerivationTree child : tree.children()) {
                String childLabel = atomKeyToTitle.getOrDefault(child.atomKey(), child.atomKey());
                childDescs.add("'" + childLabel + "' (confidence "
                        + String.format("%.2f", child.confidence()) + ")");
            }
            sb.append(String.join(" and ", childDescs));
            if (tree.ruleApplied() != null) {
                sb.append(", via rule: ").append(tree.ruleApplied());
            }
        }
        sb.append(".");
        return sb.toString();
    }
}
