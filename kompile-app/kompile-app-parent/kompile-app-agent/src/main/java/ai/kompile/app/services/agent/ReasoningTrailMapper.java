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
package ai.kompile.app.services.agent;

import ai.kompile.graph.reasoning.explain.ConfidenceBreakdown;
import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.graph.reasoning.explain.ReasoningTraceGapAnalyzer;
import ai.kompile.graph.reasoning.explain.ReasoningTraceRenderer;
import ai.kompile.graph.reasoning.explain.ReasoningTrail;
import ai.kompile.graph.reasoning.fol.EntailmentRecord;
import ai.kompile.graph.reasoning.fol.grounding.DerivationTree;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a {@link ReasoningTrail} (and its confidence breakdown / derivation tree / entailments)
 * into the frontend-facing {@code Map<String,Object>} shape consumed by the reasoning-trace UI.
 *
 * <p>Extracted from {@code ai.kompile.app.tools.KbVerifyExplainTool} so that {@code AgentChatService}
 * (in this agent module) no longer has to reach up into the app-main tools package for it — the
 * previous {@code agent → tools.KbVerifyExplainTool} edge was the sole compile coupling that kept the
 * agent domain from being a standalone module. The mapping is a pure function of
 * {@code ai.kompile.graph.reasoning} types (a leaf module), so it carries no app-main coupling.
 * {@code KbVerifyExplainTool} now delegates here.</p>
 */
public final class ReasoningTrailMapper {

    private ReasoningTrailMapper() {
    }

    public static Map<String, Object> toTrailDto(ReasoningTrail trail) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("targetId", trail.targetId());
        dto.put("question", trail.question() != null ? trail.question() : "");
        dto.put("confidence", trail.confidence());
        dto.put("inferenceMode", trail.inferenceMode());
        String llmContext = ReasoningTraceRenderer.toLlmContext(trail, 40);
        if (!llmContext.isBlank()) {
            dto.put("llmContext", llmContext);
        }
        dto.put("attributionIndex", toAttributionDtos(ReasoningTraceRenderer.attributionIndex(trail)));
        dto.put("traceGaps", toTraceGapDtos(ReasoningTraceGapAnalyzer.traceGaps(trail)));
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
            dto.put("derivationTree", toDerivationNodeDto(trail.derivationTree(), "trace.derivation.root"));
        }
        // entailments → EntailmentRecordDto[]
        if (!trail.entailments().isEmpty()) {
            dto.put("entailments", toEntailmentDtos(trail.entailments()));
        }
        return dto;
    }

    /**
     * Convert a canonical {@link ReasoningTrace} into the same frontend DTO shape as
     * {@link #toTrailDto(ReasoningTrail)}. The whole step-tree is rendered into the
     * {@code derivationTree} field — a {@link ReasoningTrace.Step} maps 1:1 onto the frontend
     * derivation-node shape {@code {atom, confidence, rule, source, children}} — so any producer that
     * can build a {@code ReasoningTrace} (from a trail, opinion tree, composite trail, or explanation
     * via its {@code toReasoningTrace()}) emits through the existing {@code reasoning_trace} SSE
     * contract unchanged. This is the app-side half of the trace consolidation: the library collapses
     * every "how was this concluded" type onto {@code ReasoningTrace}; this collapses every emission
     * path onto one DTO mapping.
     */
    public static Map<String, Object> toTrailDto(ReasoningTrace trace) {
        Map<String, Object> dto = new LinkedHashMap<>();
        ReasoningTrace.Step root = trace.conclusion();
        dto.put("targetId", root.conclusion());
        dto.put("question", "");
        dto.put("confidence", root.confidence());
        dto.put("inferenceMode", root.kind().name());
        String llmContext = ReasoningTraceRenderer.toLlmContext(trace, 40);
        if (!llmContext.isBlank()) {
            dto.put("llmContext", llmContext);
        }
        dto.put("attributionIndex", toAttributionDtos(ReasoningTraceRenderer.attributionIndex(trace)));
        dto.put("traceGaps", toTraceGapDtos(ReasoningTraceGapAnalyzer.traceGaps(trace)));

        // naturalLanguageSummary — synthesize from a short llmContext rendering
        String shortCtx = ReasoningTraceRenderer.toLlmContext(trace, 3);
        if (!shortCtx.isBlank()) {
            dto.put("naturalLanguageSummary", shortCtx);
        }

        // evidence — all leaf (FACT/ASSUMPTION) step conclusions
        List<String> evidenceList = new ArrayList<>();
        for (ReasoningTrace.Step step : trace.steps()) {
            if (step.isLeaf() && step.source() != null && !step.source().isBlank()) {
                evidenceList.add(step.conclusion() + " [" + step.source() + "]");
            } else if (step.isLeaf()) {
                evidenceList.add(step.conclusion());
            }
        }
        if (!evidenceList.isEmpty()) {
            dto.put("evidence", evidenceList);
        }

        // activatedRules — unique non-blank operations from non-leaf steps
        List<String> ruleList = new ArrayList<>();
        for (ReasoningTrace.Step step : trace.steps()) {
            if (!step.isLeaf() && step.operation() != null && !step.operation().isBlank()
                    && !step.operation().equals("observed") && !step.operation().equals("assumed")
                    && !ruleList.contains(step.operation())) {
                ruleList.add(step.operation());
            }
        }
        if (!ruleList.isEmpty()) {
            dto.put("activatedRules", ruleList);
        }

        // computedAt — read from root step meta if set by the engine
        if (root.meta() != null) {
            String computedAt = root.meta().get("computedAt");
            if (computedAt != null && !computedAt.isBlank()) {
                dto.put("computedAt", computedAt);
            }
        }

        // breakdown — derived from root opinion when present
        if (root.opinion() != null) {
            Map<String, Object> bd = new LinkedHashMap<>();
            bd.put("groundingScore", root.confidence());
            dto.put("breakdown", bd);
        }

        dto.put("derivationTree", toStepNodeDto(root, "trace.root"));
        return dto;
    }

    private static Map<String, Object> toStepNodeDto(ReasoningTrace.Step step, String stepId) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("stepId", stepId);
        n.put("atom", step.conclusion());
        n.put("confidence", step.confidence());
        if (step.operation() != null && !step.operation().isEmpty()) n.put("rule", step.operation());
        if (step.source() != null)                                   n.put("source", step.source());
        if (!step.premises().isEmpty()) {
            List<Map<String, Object>> childDtos = new ArrayList<>();
            for (int i = 0; i < step.premises().size(); i++) {
                childDtos.add(toStepNodeDto(step.premises().get(i), stepId + "." + i));
            }
            n.put("children", childDtos);
        }
        return n;
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

    private static Map<String, Object> toDerivationNodeDto(DerivationTree node, String stepId) {
        if (node == null) return Map.of();
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("stepId", stepId);
        n.put("atom", node.atomKey());
        n.put("confidence", node.confidence());
        if (node.ruleApplied() != null)      n.put("rule", node.ruleApplied());
        if (node.sourceProvenance() != null) n.put("source", node.sourceProvenance());
        if (node.children() != null && !node.children().isEmpty()) {
            List<Map<String, Object>> childDtos = new ArrayList<>();
            for (int i = 0; i < node.children().size(); i++) {
                childDtos.add(toDerivationNodeDto(node.children().get(i), stepId + "." + i));
            }
            n.put("children", childDtos);
        }
        return n;
    }

    private static List<Map<String, Object>> toEntailmentDtos(List<EntailmentRecord> records) {
        List<Map<String, Object>> dtos = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            EntailmentRecord r = records.get(i);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("stepId", "trace.entailment." + i);
            e.put("conclusion", r.groundedRvOrAtomKey());
            e.put("confidence", r.posterior());
            if (r.activatedRules() != null && !r.activatedRules().isEmpty()) {
                e.put("rule", String.join("; ", r.activatedRules()));
            }
            dtos.add(e);
        }
        return dtos;
    }

    private static List<Map<String, Object>> toAttributionDtos(
            List<ReasoningTraceRenderer.AttributionStep> attributions) {
        List<Map<String, Object>> dtos = new ArrayList<>();
        for (ReasoningTraceRenderer.AttributionStep attribution : attributions) {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("stepId", attribution.stepId());
            dto.put("kind", attribution.kind());
            dto.put("conclusion", attribution.conclusion());
            if (attribution.operation() != null && !attribution.operation().isBlank()) {
                dto.put("operation", attribution.operation());
            }
            dto.put("confidence", attribution.confidence());
            if (attribution.source() != null && !attribution.source().isBlank()) {
                dto.put("source", attribution.source());
            }
            if (!attribution.evidenceRefs().isEmpty()) {
                dto.put("evidenceRefs", toEvidenceRefDtos(attribution.evidenceRefs()));
            }
            dtos.add(dto);
        }
        return dtos;
    }

    private static List<Map<String, Object>> toEvidenceRefDtos(
            List<ReasoningTraceRenderer.EvidenceReference> evidenceRefs) {
        List<Map<String, Object>> dtos = new ArrayList<>();
        for (ReasoningTraceRenderer.EvidenceReference evidenceRef : evidenceRefs) {
            Map<String, Object> dto = new LinkedHashMap<>();
            putIfPresent(dto, "refType", evidenceRef.refType());
            putIfPresent(dto, "sourceId", evidenceRef.sourceId());
            putIfPresent(dto, "nodeId", evidenceRef.nodeId());
            putIfPresent(dto, "edgeId", evidenceRef.edgeId());
            putIfPresent(dto, "documentId", evidenceRef.documentId());
            putIfPresent(dto, "chunkId", evidenceRef.chunkId());
            putIfPresent(dto, "factSheetId", evidenceRef.factSheetId());
            putIfPresent(dto, "runId", evidenceRef.runId());
            putIfPresent(dto, "atomKey", evidenceRef.atomKey());
            putIfPresent(dto, "findingKey", evidenceRef.findingKey());
            putIfPresent(dto, "ruleId", evidenceRef.ruleId());
            putIfPresent(dto, "modality", evidenceRef.modality());
            putIfPresent(dto, "raw", evidenceRef.raw());
            dtos.add(dto);
        }
        return dtos;
    }

    private static void putIfPresent(Map<String, Object> dto, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String s && s.isBlank()) {
            return;
        }
        dto.put(key, value);
    }

    private static List<Map<String, Object>> toTraceGapDtos(
            List<ReasoningTraceGapAnalyzer.TraceGap> traceGaps) {
        List<Map<String, Object>> dtos = new ArrayList<>();
        for (ReasoningTraceGapAnalyzer.TraceGap traceGap : traceGaps) {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("gapType", traceGap.gapType());
            dto.put("severity", traceGap.severity());
            dto.put("question", traceGap.question());
            dto.put("reason", traceGap.reason());
            dto.put("relatedStepIds", traceGap.relatedStepIds());
            dtos.add(dto);
        }
        return dtos;
    }
}
