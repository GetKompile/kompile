/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.tools;

import ai.kompile.graph.reasoning.claims.ClaimDossier;
import ai.kompile.graph.reasoning.claims.DossierItem;
import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.grounding.ConjunctiveQueryEngine;
import ai.kompile.graph.reasoning.fol.grounding.QueryBinding;
import ai.kompile.graph.reasoning.tms.BeliefRevisionResult;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tools exposing the KB grounding verbs (conjunctive query + assert) to chat-spawned
 * agents — the in-process counterpart of the CLI's {@code ask_graph_query} /
 * {@code ask_graph_assert}, backed directly by the live {@link KbGroundingService}.
 * Together with {@link KbVerifyExplainTool} (verify/explain) this covers the KB grounding
 * surface from the agent-tool side; both run against the same ConcurrentFactStore the
 * REST controllers serve.
 */
@Component
public class KbGroundingTool {

    private static final Logger log = LoggerFactory.getLogger(KbGroundingTool.class);
    private static final int MAX_RESULTS_CAP = 1000;
    private static final double DEFAULT_MIN_CONFIDENCE = 0.3;

    private final KbGroundingService groundingService;

    @Nullable
    @Autowired(required = false)
    private UnifiedGraphBridge unifiedGraphBridge;

    @Autowired
    public KbGroundingTool(@Nullable KbGroundingService groundingService) {
        this.groundingService = groundingService;
    }

    // ─── Input records ────────────────────────────────────────────────────────

    public record Conjunct(String predicate, List<String> args) {}

    public record KbQueryInput(
            List<Conjunct> conjuncts,
            Long factSheetId,
            Integer maxResults,
            Double minConfidence) {}

    public record KbAssertInput(
            String atom,
            Double value,
            Long factSheetId,
            String source) {}

    public record KbRetractInput(
            String atomKey,
            Long factSheetId,
            /** "retract" (default) or "revise" — see KbGroundingService.retractAndRevise */
            String mode) {}

    public record KbClaimInput(
            String subject,
            String predicate,
            String object,
            Long factSheetId) {}

    // ─── Tools ────────────────────────────────────────────────────────────────

    @Tool(name = "kb_query",
          description = "Conjunctive pattern query against the knowledge base. Each conjunct is an " +
                  "atom pattern with a predicate and args, where '?'-prefixed args are variables — " +
                  "e.g. predicate='isEmployedBy', args=['?person','Acme'] finds everyone employed by " +
                  "Acme. Multiple conjuncts share variables (joins). Returns variable bindings with " +
                  "confidence. factSheetId (optional): scope to a fact sheet, 0/null = global. " +
                  "maxResults (optional, max 1000). minConfidence (optional, default 0.3).")
    public Map<String, Object> kbQuery(KbQueryInput input) {
        if (groundingService == null) {
            return Map.of("status", "unavailable",
                    "message", "KB grounding service is not initialised.");
        }
        if (input.conjuncts() == null || input.conjuncts().isEmpty()) {
            return Map.of("status", "error", "message", "conjuncts must not be empty");
        }

        int maxResults = input.maxResults() != null && input.maxResults() > 0
                ? Math.min(input.maxResults(), MAX_RESULTS_CAP)
                : ConjunctiveQueryEngine.DEFAULT_MAX_RESULTS;
        long factSheetId = input.factSheetId() != null ? input.factSheetId() : 0L;
        double minConfidence = input.minConfidence() != null && input.minConfidence() > 0
                ? input.minConfidence()
                : DEFAULT_MIN_CONFIDENCE;

        try {
            List<ConjunctiveQueryEngine.AtomPattern> patterns = input.conjuncts().stream()
                    .map(c -> new ConjunctiveQueryEngine.AtomPattern(c.predicate(), c.args()))
                    .toList();

            List<QueryBinding> raw = groundingService.query(factSheetId, patterns, maxResults);
            List<Map<String, Object>> rows = raw.stream()
                    .filter(b -> b.confidence() >= minConfidence)
                    .map(b -> Map.<String, Object>of(
                            "bindings", b.bindings(),
                            "confidence", b.confidence()))
                    .toList();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("count", rows.size());
            result.put("truncated", raw.size() == maxResults);
            result.put("results", rows);
            return result;
        } catch (Exception e) {
            log.debug("kb_query failed: {}", e.getMessage());
            return Map.of("status", "error", "message", e.getMessage() == null ? "query failed" : e.getMessage());
        }
    }

    @Tool(name = "kb_assert",
          description = "Assert a fact into the knowledge base with contradiction checking. " +
                  "atom: atom key like 'isEmployedBy(Alice,Acme)'. value: belief strength in [0,1] " +
                  "(0.0 retracts the fact). factSheetId: REQUIRED — the fact sheet to write to. " +
                  "source (optional): provenance label recorded with the fact. Returns status " +
                  "ASSERTED / RETRACTED / CONTRADICTION_DETECTED / CONFLICT_QUEUED plus any " +
                  "contradicting facts found.")
    public Map<String, Object> kbAssert(KbAssertInput input) {
        if (groundingService == null) {
            return Map.of("status", "unavailable",
                    "message", "KB grounding service is not initialised.");
        }
        if (input.atom() == null || input.atom().isBlank()) {
            return Map.of("status", "error", "message", "atom must not be blank");
        }
        if (input.factSheetId() == null) {
            return Map.of("status", "error", "message", "factSheetId is required for assert");
        }
        double value = input.value() != null ? input.value() : 1.0;
        if (value < 0.0 || value > 1.0) {
            return Map.of("status", "error", "message", "value must be in [0,1]");
        }

        try {
            String source = input.source() == null || input.source().isBlank()
                    ? "agent-chat" : input.source().trim();
            Fact fact = Fact.soft(input.atom().trim(), value, "agent:" + source);

            KbGroundingService.AssertResult result = groundingService.assertFact(input.factSheetId(), fact);

            String status;
            if (result.isConflict()) {
                status = "CONFLICT_QUEUED";
            } else if (!result.contradictions().isEmpty()) {
                status = "CONTRADICTION_DETECTED";
            } else if (value == 0.0) {
                status = "RETRACTED";
            } else {
                status = "ASSERTED";
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", status);
            out.put("kbVersion", result.isConflict() ? -1L : result.version());
            out.put("contradictions", result.contradictions());
            return out;
        } catch (Exception e) {
            log.debug("kb_assert failed: {}", e.getMessage());
            return Map.of("status", "error", "message", e.getMessage() == null ? "assert failed" : e.getMessage());
        }
    }

    @Tool(name = "kb_claim",
          description = "Assess a (subject, predicate, object) claim against the knowledge base. " +
                  "Fuses five evidence channels — direct connection, verified facts, connecting " +
                  "paths, link plausibility, and learned rules — into a single fused confidence " +
                  "score and a per-signal breakdown. subject: entity id of the claim subject. " +
                  "predicate: relation type (case-insensitive). object: entity id of the claim " +
                  "object. factSheetId (optional): scope to a fact sheet, 0/null = global. " +
                  "Returns verdict (SUPPORTED/REFUTED/UNCERTAIN), fusedScore [0,1], and " +
                  "supporting/refuting signal items labeled in plain English.")
    public Map<String, Object> kbClaim(KbClaimInput input) {
        if (groundingService == null) {
            return Map.of("status", "unavailable",
                    "message", "KB grounding service is not initialised.");
        }
        if (input.subject() == null || input.subject().isBlank()) {
            return Map.of("status", "error", "message", "subject must not be blank");
        }
        if (input.predicate() == null || input.predicate().isBlank()) {
            return Map.of("status", "error", "message", "predicate must not be blank");
        }
        if (input.object() == null || input.object().isBlank()) {
            return Map.of("status", "error", "message", "object must not be blank");
        }

        long factSheetId = input.factSheetId() != null ? input.factSheetId() : 0L;

        try {
            // Obtain a ReasoningGraph: prefer live UnifiedGraph, fall back to empty stub
            ai.kompile.graph.reasoning.model.ReasoningGraph graph = null;
            if (unifiedGraphBridge != null) {
                try {
                    graph = unifiedGraphBridge.export(factSheetId);
                } catch (Exception e) {
                    log.debug("kb_claim: UnifiedGraphBridge export failed for factSheet={}: {}", factSheetId, e.getMessage());
                }
            }
            if (graph == null) {
                graph = new UnifiedGraph();
            }

            ClaimDossier dossier = groundingService.assessClaim(
                    factSheetId, graph, input.subject(), input.predicate(), input.object());

            String verdict = dossier.fusedScore() >= 0.65 ? "SUPPORTED"
                    : dossier.fusedScore() <= 0.35 ? "REFUTED"
                    : "UNCERTAIN";

            List<Map<String, Object>> supporting = dossier.supporting().stream()
                    .map(item -> Map.<String, Object>of(
                            "signal", plainEnglishSignal(item.kind()),
                            "description", item.description(),
                            "probability", item.probability()))
                    .toList();

            List<Map<String, Object>> refuting = dossier.refuting().stream()
                    .map(item -> Map.<String, Object>of(
                            "signal", plainEnglishSignal(item.kind()),
                            "description", item.description(),
                            "probability", item.probability()))
                    .toList();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "ok");
            out.put("claimAtom", dossier.claimAtom());
            out.put("verdict", verdict);
            out.put("fusedScore", dossier.fusedScore());
            out.put("supporting", supporting);
            out.put("refuting", refuting);
            return out;
        } catch (Exception e) {
            log.debug("kb_claim failed: {}", e.getMessage());
            return Map.of("status", "error", "message", e.getMessage() == null ? "claim assessment failed" : e.getMessage());
        }
    }

    /** Map a DossierItem.Kind to a plain-English label. */
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

    @Tool(name = "kb_retract",
          description = "True TMS retraction: physically removes an atom from the knowledge base " +
                  "and performs dependency analysis. atomKey: atom key to retract, e.g. " +
                  "'trusts(Alice,Bob)'. factSheetId: REQUIRED — the fact sheet to retract from. " +
                  "mode (optional): 'retract' (default) = single-atom TMS retraction with dependency " +
                  "analysis, cascade re-reasons async; 'revise' = additionally removes sole-dependent " +
                  "atoms synchronously before returning. Returns status RETRACTED/NOT_FOUND plus " +
                  "dependentAtomsUnsupported (list of atoms that lost all support) and " +
                  "dependentAtomsWeakened (atoms with reduced but remaining support). " +
                  "Prefer kb_retract over kb_assert(value=0.0) when correctness of dependency " +
                  "propagation matters.")
    public Map<String, Object> kbRetract(KbRetractInput input) {
        if (groundingService == null) {
            return Map.of("status", "unavailable",
                    "message", "KB grounding service is not initialised.");
        }
        if (input.atomKey() == null || input.atomKey().isBlank()) {
            return Map.of("status", "error", "message", "atomKey must not be blank");
        }
        if (input.factSheetId() == null) {
            return Map.of("status", "error", "message", "factSheetId is required for retract");
        }

        try {
            String mode = (input.mode() == null || input.mode().isBlank()) ? "retract" : input.mode().trim();
            KbGroundingService.RetractResult retractResult;
            if ("revise".equalsIgnoreCase(mode)) {
                retractResult = groundingService.retractAndRevise(input.factSheetId(), input.atomKey().trim());
            } else {
                retractResult = groundingService.retractFact(input.factSheetId(), input.atomKey().trim());
            }

            String status = retractResult.found() ? "RETRACTED" : "NOT_FOUND";
            BeliefRevisionResult revision = retractResult.revision();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", status);
            out.put("atomKey", input.atomKey().trim());
            out.put("mode", mode.toLowerCase());
            out.put("dependentAtomsUnsupported",
                    revision.unsupportedAtoms() != null ? List.copyOf(revision.unsupportedAtoms()) : List.of());
            out.put("dependentAtomsWeakened",
                    revision.weakenedAtoms() != null ? List.copyOf(revision.weakenedAtoms()) : List.of());
            out.put("cascadeTriggered", true);
            return out;
        } catch (Exception e) {
            log.debug("kb_retract failed: {}", e.getMessage());
            return Map.of("status", "error", "message", e.getMessage() == null ? "retract failed" : e.getMessage());
        }
    }
}
