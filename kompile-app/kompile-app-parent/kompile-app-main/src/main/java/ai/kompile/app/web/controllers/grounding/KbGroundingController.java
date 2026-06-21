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

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.grounding.ConjunctiveQueryEngine;
import ai.kompile.graph.reasoning.fol.grounding.ConcurrentFactStore;
import ai.kompile.graph.reasoning.fol.grounding.DerivationTree;
import ai.kompile.graph.reasoning.fol.grounding.QueryBinding;
import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
import ai.kompile.knowledgegraph.grounding.FactSheetKbState;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 *   <li>{@code POST /subscribe} — Phase 2 stub: returns HTTP 501</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/kb-grounding")
public class KbGroundingController {

    private final KbGroundingService groundingService;

    @Autowired
    public KbGroundingController(KbGroundingService groundingService) {
        this.groundingService = groundingService;
    }

    // ── Verify ───────────────────────────────────────────────────────────────────

    /**
     * Verify a factual atom claim against the knowledge base.
     *
     * <p>Returns SUPPORTED, REFUTED, or UNKNOWN with confidence + evidence.</p>
     */
    @PostMapping("/verify")
    public ResponseEntity<VerifyResponse> verify(@RequestBody VerifyRequest req) {
        if (req.atom() == null || req.atom().isBlank()) {
            throw new IllegalArgumentException("atom must not be blank");
        }
        long factSheetId = resolveFactSheetId(req.factSheetId());
        Instant asOf = req.asOf() != null ? req.asOf() : Instant.now();

        VerifyResult result;
        if (req.minConfidence() != null && req.minConfidence() > 0.0) {
            result = groundingService.verify(factSheetId, req.atom(), req.minConfidence());
        } else {
            result = groundingService.verify(factSheetId, req.atom());
        }

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

        VerifyResponse response = new VerifyResponse(
                result.status().name(),
                result.confidence(),
                evidenceAtoms,
                activatedRules,
                evidenceAtoms.size(),       // derivationDepth approximation from evidence count
                List.copyOf(evidenceAtoms), // sourceProvenance = evidenceAtoms (crawl-run IDs)
                meta
        );
        return ResponseEntity.ok(response);
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

        List<QueryResponse.BindingRow> rows = filtered.stream()
                .map(b -> new QueryResponse.BindingRow(
                        b.bindings(),
                        b.confidence(),
                        List.of()   // matchedAtoms: not surfaced by lib QueryBinding; empty for Phase 1
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

        DerivationTree tree = groundingService.explain(factSheetId, req.atom(), depth);
        VerifyResult verdict = groundingService.verify(factSheetId, req.atom());
        String summary = deterministicSummary(tree, req.atom(), verdict);

        FactSheetKbState state = groundingService.getState(factSheetId);
        GroundingMeta meta = buildMeta(factSheetId, asOf, state, req.sessionId());

        ExplainResponse response = new ExplainResponse(
                req.atom(),
                verdict.status().name(),
                verdict.confidence(),
                summary,
                tree.toJson(),
                meta
        );
        return ResponseEntity.ok(response);
    }

    // ── Assert ───────────────────────────────────────────────────────────────────

    /**
     * Assert a fact from an agent session into the KB.
     *
     * <p>Contradiction-checking runs synchronously. Background cascade is not yet
     * wired (Phase 2). {@code stale} is always {@code false} in Phase 1.</p>
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
        Fact fact = Fact.soft(req.atom(), req.value(), sourceId);

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

    // ── Subscribe (Phase 2 stub) ──────────────────────────────────────────────

    /**
     * Subscribe to KB change events. Phase 2 — not yet implemented.
     */
    @PostMapping("/subscribe")
    public ResponseEntity<SubscribeResponse> subscribe(@RequestBody(required = false) Object req) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(new SubscribeResponse(null, null, "Phase 2 — not yet implemented"));
    }

    /**
     * SSE stream for a subscription. Phase 2 — not yet implemented.
     */
    @GetMapping(value = "/subscribe/{subscriptionId}/events",
                produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeEvents(@PathVariable String subscriptionId) {
        throw new UnsupportedOperationException("Phase 2 — SSE subscription not yet implemented");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /**
     * Resolve factSheetId: if null, use 0L as the default (global) fact sheet.
     */
    private long resolveFactSheetId(Long factSheetId) {
        return factSheetId != null ? factSheetId : 0L;
    }

    /**
     * Build the common meta-block from the current state.
     * Phase 1: stale is always false (cascade executor not yet wired).
     */
    private GroundingMeta buildMeta(long factSheetId, Instant asOf,
                                    FactSheetKbState state, String sessionId) {
        long version = state.concurrentFactStore().version();
        return new GroundingMeta(factSheetId, asOf, false, 0L, version, sessionId);
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

    /**
     * Deterministic NL summary of a derivation tree — no LLM required.
     */
    private String deterministicSummary(DerivationTree tree, String atom, VerifyResult verdict) {
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
                childDescs.add("'" + child.atomKey() + "' (confidence "
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
