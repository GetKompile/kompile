/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.tools;

import ai.kompile.app.services.agent.ReasoningTraceStore;
import ai.kompile.app.web.controllers.explain.ExplainOrchestrator;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.graph.reasoning.explain.ConfidenceBreakdown;
import ai.kompile.graph.reasoning.explain.ReasoningTrail;
import ai.kompile.graph.reasoning.fol.EntailmentRecord;
import ai.kompile.graph.reasoning.fol.grounding.DerivationTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Tool that grounds agent responses by running ExplainOrchestrator.explain()
 * against the knowledge base and returning a concise trace-backed summary.
 *
 * <p>The full {@link ReasoningTrail} is also pushed to {@link ReasoningTraceStore}
 * so that {@code AgentChatService} can emit it as a {@code reasoning_trace} SSE event,
 * making the decision trail visible in the chat UI via {@code ReasoningTrailComponent}.</p>
 *
 * <h3>Routing (automatic if {@code mode} is blank)</h3>
 * <ul>
 *   <li>{@code causal:<target>} prefix → CAUSAL engine</li>
 *   <li>Atom-key form {@code pred(arg)} (contains {@code (} and {@code )}) → GROUNDING</li>
 *   <li>Bare entity id → HYBRID (structural + semantic scores)</li>
 * </ul>
 * Explicit {@code mode} values: {@code GROUNDING | HYBRID | CAUSAL | PSL | MEBN}.
 */
@Component
public class KbVerifyExplainTool {

    private static final Logger log = LoggerFactory.getLogger(KbVerifyExplainTool.class);

    private final ExplainOrchestrator explainOrchestrator;
    private final ReasoningTraceStore traceStore;
    private final KbGroundingService groundingService;

    @Autowired
    public KbVerifyExplainTool(
            @Nullable ExplainOrchestrator explainOrchestrator,
            @Nullable ReasoningTraceStore traceStore,
            @Nullable KbGroundingService groundingService) {
        this.explainOrchestrator = explainOrchestrator;
        this.traceStore = traceStore;
        this.groundingService = groundingService;
    }

    // ─── Input records ────────────────────────────────────────────────────────

    public record VerifyAndExplainInput(
            String target,
            String mode,
            Long factSheetId) {}

    // ─── Tool ─────────────────────────────────────────────────────────────────

    @Tool(name = "kb_verify_explain",
          description = "Verify or explain a claim, atom, or entity against the knowledge base using " +
                  "the graph reasoning engine. Returns a grounded summary with verdict, confidence " +
                  "score, supporting evidence, and activated rules. " +
                  "Use for: fact-checking statements, deriving entity confidence, causal attribution. " +
                  "target: the claim to verify (atom key like 'isEmployedBy(Alice,Acme)', " +
                  "an entity id, or 'causal:<entityId>' for causal attribution). " +
                  "mode (optional): GROUNDING | HYBRID | CAUSAL | PSL | MEBN — leave blank for auto-routing. " +
                  "factSheetId (optional): scope to a specific fact-sheet; 0 or null = global.")
    public Map<String, Object> verifyAndExplain(VerifyAndExplainInput input) {
        if (explainOrchestrator == null) {
            return Map.of("status", "unavailable",
                    "message", "Knowledge base reasoning engine is not initialised.");
        }

        String target = input.target();
        if (target == null || target.isBlank()) {
            return Map.of("status", "error", "message", "target must not be blank");
        }
        long factSheetId = input.factSheetId() != null ? input.factSheetId() : 0L;
        String mode = input.mode() != null ? input.mode().trim() : null;

        try {
            ReasoningTrail trail = explainOrchestrator.explain(target, factSheetId, 0, mode);

            // Push the full trail into the trace buffer so AgentChatService can
            // emit it as a reasoning_trace SSE event after the subprocess exits.
            if (traceStore != null) {
                Map<String, Object> dto = toTrailDto(trail);
                // Stamp the KB staleness so the chat trace card can show a "re-grounding pending"
                // chip, matching the grounding-console behaviour on the /api/kb-grounding path.
                if (groundingService != null) {
                    dto.put("stale", groundingService.isStale(factSheetId));
                }
                traceStore.storeTrace(dto);
            }

            // Return a concise, agent-readable summary so the model's answer is grounded.
            return buildSummaryResponse(trail);

        } catch (Exception e) {
            log.error("kb_verify_explain failed for target='{}': {}", target, e.getMessage(), e);
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Build a concise Map that the agent can read as its grounded context.
     */
    private Map<String, Object> buildSummaryResponse(ReasoningTrail trail) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "success");
        resp.put("target", trail.targetId());
        resp.put("question", trail.question());
        resp.put("confidence", Math.round(trail.confidence() * 100.0) / 100.0);
        resp.put("inferenceMode", trail.inferenceMode());
        resp.put("summary", trail.naturalLanguageSummary());

        if (!trail.evidence().isEmpty()) {
            resp.put("topEvidence", trail.evidence().subList(0, Math.min(5, trail.evidence().size())));
        }
        if (!trail.activatedRules().isEmpty()) {
            resp.put("topRules", trail.activatedRules().subList(0, Math.min(3, trail.activatedRules().size())));
        }
        return resp;
    }

    /**
     * Convert a {@link ReasoningTrail} to a Map whose field names match the
     * frontend's {@code ReasoningTrailDto} TypeScript interface so it can be
     * deserialised directly in {@code local-agent-chat.service.ts}.
     */
    public static Map<String, Object> toTrailDto(ReasoningTrail trail) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("targetId", trail.targetId());
        dto.put("question", trail.question() != null ? trail.question() : "");
        dto.put("confidence", trail.confidence());
        dto.put("inferenceMode", trail.inferenceMode());
        if (trail.naturalLanguageSummary() != null && !trail.naturalLanguageSummary().isBlank()) {
            dto.put("naturalLanguageSummary", trail.naturalLanguageSummary());
        }
        if (!trail.evidence().isEmpty()) {
            dto.put("evidence", trail.evidence());
        }
        if (!trail.activatedRules().isEmpty()) {
            dto.put("activatedRules", trail.activatedRules());
        }
        if (trail.computedAt() != null) {
            dto.put("computedAt", trail.computedAt().toString());
        }
        // breakdown — remap Java field names to frontend ConfidenceBreakdownDto names
        Map<String, Object> bd = buildBreakdownDto(trail.breakdown());
        if (!bd.isEmpty()) {
            dto.put("breakdown", bd);
        }
        // derivationTree — map DerivationTree → DerivationTreeNodeDto shape
        if (trail.derivationTree() != null) {
            dto.put("derivationTree", toDerivationNodeDto(trail.derivationTree()));
        }
        // entailments → EntailmentRecordDto[]
        if (!trail.entailments().isEmpty()) {
            dto.put("entailments", toEntailmentDtos(trail.entailments()));
        }
        return dto;
    }

    private static Map<String, Object> buildBreakdownDto(ConfidenceBreakdown bd) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (bd == null) return result;
        if (!Double.isNaN(bd.groundingConfidence())) result.put("groundingScore", bd.groundingConfidence());
        if (!Double.isNaN(bd.pslSoftTruth()))        result.put("pslScore",       bd.pslSoftTruth());
        if (!Double.isNaN(bd.mebnPosterior()))        result.put("mebnScore",      bd.mebnPosterior());
        // fusedScore = structural × weight + semantic × weight (hybrid mode)
        if (!Double.isNaN(bd.structuralScore()) && !Double.isNaN(bd.semanticScore())) {
            double sw = Double.isNaN(bd.structuralWeight()) ? 0.5 : bd.structuralWeight();
            double ew = Double.isNaN(bd.semanticWeight())   ? 0.5 : bd.semanticWeight();
            result.put("fusedScore", bd.structuralScore() * sw + bd.semanticScore() * ew);
        }
        return result;
    }

    private static Map<String, Object> toDerivationNodeDto(DerivationTree node) {
        if (node == null) return Map.of();
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("atom", node.atomKey());
        n.put("confidence", node.confidence());
        if (node.ruleApplied() != null)      n.put("rule", node.ruleApplied());
        if (node.sourceProvenance() != null) n.put("source", node.sourceProvenance());
        if (node.children() != null && !node.children().isEmpty()) {
            List<Map<String, Object>> childDtos = new ArrayList<>();
            for (DerivationTree child : node.children()) {
                childDtos.add(toDerivationNodeDto(child));
            }
            n.put("children", childDtos);
        }
        return n;
    }

    private static List<Map<String, Object>> toEntailmentDtos(List<EntailmentRecord> records) {
        List<Map<String, Object>> dtos = new ArrayList<>();
        for (EntailmentRecord r : records) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("conclusion", r.groundedRvOrAtomKey());
            e.put("confidence", r.posterior());
            if (r.activatedRules() != null && !r.activatedRules().isEmpty()) {
                e.put("rule", String.join("; ", r.activatedRules()));
            }
            dtos.add(e);
        }
        return dtos;
    }
}
