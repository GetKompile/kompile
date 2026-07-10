/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.McpToolAnnotations;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP tool: {@code ask_graph_verify}
 *
 * <p>Verify a factual claim against the production knowledge base via
 * {@code POST /api/kb-grounding/verify}. Returns SUPPORTED, REFUTED, or UNKNOWN with
 * calibrated confidence and evidence atoms.</p>
 */
public class AskGraphVerifyTool implements CliTool {

    private final GroundingBackendClient groundingClient;
    private final ObjectMapper objectMapper;

    public AskGraphVerifyTool(String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.groundingClient = new GroundingBackendClient(baseUrl);
    }

    /** Visible for testing — lets a {@code MockRestServiceServer} intercept HTTP calls. */
    AskGraphVerifyTool(GroundingBackendClient groundingClient, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.groundingClient = groundingClient;
    }

    @Override
    public String id() { return "ask_graph_verify"; }

    @Override
    public String description() {
        return "Verify a factual claim against the production knowledge base. " +
                "Returns SUPPORTED, REFUTED, or UNKNOWN with a calibrated confidence " +
                "score [0,1] and the supporting evidence keys + activated rules that justify " +
                "the verdict. Use this before accepting any LLM-generated claim as fact. " +
                "Specify asOf for temporal point-in-time verification.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("atom")
                .put("type", "string")
                .put("description", "Canonical atom key, e.g. 'isEmployedBy(Alice, Acme)'. "
                        + "Predicate name is case-sensitive. Arguments separated by ', ' (comma-space).");
        props.putObject("factSheetId")
                .put("type", "integer")
                .put("description", "Scope verification to a specific fact sheet. Null or absent = search all active fact sheets.");
        props.putObject("asOf")
                .put("type", "string")
                .put("description", "ISO-8601 instant for temporal point-in-time verification. Absent = current truth.");
        props.putObject("minConfidence")
                .put("type", "number")
                .put("description", "Override the default confidence threshold [0,1]. Default: 0.5.");
        props.putObject("sessionId")
                .put("type", "string")
                .put("description", "Optional agent session ID for grounding-session tracking. Echoed in meta.");

        schema.putArray("required").add("atom");
        return schema;
    }

    @Override
    public String permissionKey() { return "ask_graph_verify"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }

    @Override
    public String compactHint() {
        return "Verify a factual claim: POST /api/kb-grounding/verify {atom, factSheetId?, minConfidence?}. " +
               "atom format: 'predicate(arg1, arg2)' — case-sensitive predicate name, e.g. 'worksFor(Alice, Acme)'. " +
               "Discover predicate names with knowledge_graph list_predicates. " +
               "Returns verdict (SUPPORTED/REFUTED/UNKNOWN), confidence, supporting evidence, counter-evidence, " +
               "unknownReason (entity-not-in-graph|no-evidence|contested|near-miss), " +
               "opinion (support/counter-evidence/uncertainty), " +
               "nearMissSuggestions (facts to assert to make this claim provable — appears once the knowledge base " +
               "has learned reasoning rules), " +
               "fragility{wouldFlipIf,minimalSupportSize,robustness} (for SUPPORTED verdicts: " +
               "robustness 0=only one fact supports it, 1=many independent supports). " +
               "factSheetId optional — discover via knowledge_graph list_fact_sheets.";
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Verify KB claim");

        String atom = params.path("atom").asText("");
        if (atom.isBlank()) {
            return ToolResult.error("atom is required");
        }

        if (!groundingClient.isAvailable()) {
            return ToolResult.error("ask_graph_verify requires a running kompile-app. " +
                    "Start kompile-app or use --url to connect.");
        }

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("atom", atom);
            if (!params.path("factSheetId").isMissingNode()) body.set("factSheetId", params.get("factSheetId"));
            if (!params.path("asOf").isMissingNode())        body.set("asOf", params.get("asOf"));
            if (!params.path("minConfidence").isMissingNode()) body.set("minConfidence", params.get("minConfidence"));
            if (!params.path("sessionId").isMissingNode())   body.set("sessionId", params.get("sessionId"));

            var resp = groundingClient.post("/api/kb-grounding/verify",
                    objectMapper.writeValueAsString(body));

            if (resp.statusCode() != 200) {
                return ToolResult.error("ask_graph_verify failed (HTTP " + resp.statusCode() + "): "
                        + extractError(resp.body()));
            }

            JsonNode result = objectMapper.readTree(resp.body());
            String verdict              = result.path("verdict").asText("UNKNOWN");
            double conf                 = result.path("confidence").asDouble(0.0);
            double calibratedConfidence = result.path("calibratedConfidence").asDouble(conf);
            String strengthBand         = result.path("strengthBand").asText("");
            JsonNode evidence           = result.path("evidenceAtoms");
            boolean stale               = result.path("meta").path("stale").asBoolean(false);
            // New fields (E4/E5/fix #3/#4/#6)
            int derivationDepth         = result.path("derivationDepth").asInt(0);
            int evidenceCount           = result.path("evidenceCount").asInt(evidence.size());
            JsonNode counterEvidence    = result.path("counterEvidence");
            String refutationBasis      = result.path("refutationBasis").asText(null);
            JsonNode sourceProvenance   = result.path("sourceProvenance");
            String unknownReason        = result.path("unknownReason").asText(null);
            boolean openWorld           = result.path("openWorld").asBoolean(false);
            boolean entityKnown         = result.path("entityKnown").asBoolean(true);
            JsonNode contradictions     = result.path("contradictions");
            JsonNode opinion            = result.path("opinion");
            // E9: near-miss suggestions
            JsonNode nearMissSuggestions = result.path("nearMissSuggestions");
            // E12: fragility (only present for SUPPORTED verdicts)
            JsonNode fragility = result.path("fragility");
            // deepWhyNot: multi-hop completion sets (defensive — present only in enriched backends)
            JsonNode deepWhyNot = result.path("deepWhyNot");

            // Build structured result map
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("verdict", verdict);
            structured.put("confidence", conf);
            structured.put("calibratedConfidence", calibratedConfidence);
            structured.put("strengthBand", strengthBand);
            structured.put("stale", stale);
            structured.put("derivationDepth", derivationDepth);
            structured.put("evidenceCount", evidenceCount);
            structured.put("refutationBasis", refutationBasis != null ? refutationBasis : "");
            structured.put("unknownReason", unknownReason != null ? unknownReason : "");
            structured.put("entityKnown", entityKnown);
            if (!fragility.isMissingNode() && !fragility.isNull()) {
                structured.put("fragilityRobustness", fragility.path("robustness").asDouble(1.0));
                structured.put("fragilityMinimalSupportSize", fragility.path("minimalSupportSize").asInt(0));
            }

            return ToolResult.success("ask_graph_verify: " + atom,
                    formatVerifyResult(atom, verdict, conf, calibratedConfidence,
                            strengthBand, evidence, stale, derivationDepth, evidenceCount,
                            counterEvidence, refutationBasis, sourceProvenance, unknownReason,
                            openWorld, entityKnown, contradictions, opinion, nearMissSuggestions,
                            fragility, deepWhyNot),
                    structured);

        } catch (Exception e) {
            return ToolResult.error("ask_graph_verify error: " + e.getMessage());
        }
    }

    private String formatVerifyResult(String atom, String verdict, double conf,
                                       double calibratedConfidence, String strengthBand,
                                       JsonNode evidence, boolean stale,
                                       int derivationDepth, int evidenceCount,
                                       JsonNode counterEvidence, String refutationBasis,
                                       JsonNode sourceProvenance, String unknownReason,
                                       boolean openWorld, boolean entityKnown,
                                       JsonNode contradictions, JsonNode opinion,
                                       JsonNode nearMissSuggestions,
                                       JsonNode fragility, JsonNode deepWhyNot) {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(verdict).append("** — ").append(atom);
        sb.append("\nConfidence: ").append(String.format("%.3f", conf));
        sb.append("\nCalibrated confidence: ").append(String.format("%.3f", calibratedConfidence));
        if (!strengthBand.isBlank()) {
            sb.append("\nStrength band: ").append(strengthBand);
        }

        // Fix #3: real depth + count
        sb.append("\nDerivation depth: ").append(derivationDepth);
        sb.append(" | Evidence count: ").append(evidenceCount);

        if (evidence.isArray() && evidence.size() > 0) {
            sb.append("\nEvidence:");
            evidence.forEach(e -> sb.append("\n  - ").append(e.asText()));
        }

        // Fix #4: real sourceProvenance
        if (sourceProvenance.isArray() && sourceProvenance.size() > 0) {
            sb.append("\nSource provenance:");
            sourceProvenance.forEach(s -> sb.append("\n  - ").append(s.asText()));
        }

        // E4/E5: counter-evidence and refutation basis
        if (counterEvidence.isArray() && counterEvidence.size() > 0) {
            sb.append("\nCounter-evidence:");
            counterEvidence.forEach(c -> sb.append("\n  - ").append(c.asText()));
        }
        if (refutationBasis != null && !refutationBasis.isBlank()) {
            sb.append("\nRefutation basis: ").append(refutationBasis);
        }

        // E4: contradiction descriptions
        if (contradictions.isArray() && contradictions.size() > 0) {
            sb.append("\nContradictions detected:");
            contradictions.forEach(c -> sb.append("\n  ⊗ ").append(c.asText()));
        }

        // Subjective-logic opinion in plain language — rendered for every verdict
        if (opinion != null && !opinion.isMissingNode() && !opinion.isNull()) {
            sb.append("\nOpinion:")
              .append(" support=").append(String.format("%.3f", opinion.path("b").asDouble()))
              .append(" counter-evidence=").append(String.format("%.3f", opinion.path("d").asDouble()))
              .append(" uncertainty=").append(String.format("%.3f", opinion.path("u").asDouble()));
        }

        // Fix #6: UNKNOWN detail
        if ("UNKNOWN".equals(verdict)) {
            if (unknownReason != null && !unknownReason.isBlank()) {
                sb.append("\nUnknown reason: ").append(unknownReason);
            }
            sb.append("\nEntity known in graph: ").append(entityKnown);
            sb.append("\nOpen-world assessment: ").append(openWorld);
        }

        // E9: near-miss suggestions
        if ("UNKNOWN".equals(verdict)
                && nearMissSuggestions != null
                && nearMissSuggestions.isArray()
                && nearMissSuggestions.size() > 0) {
            sb.append("\nWould be provable if:");
            nearMissSuggestions.forEach(s -> sb.append("\n  + ").append(s.asText()));
        }

        // deepWhyNot: completion sets (defensive — present only in enriched backends)
        if (deepWhyNot != null && !deepWhyNot.isMissingNode() && deepWhyNot.isArray() && deepWhyNot.size() > 0) {
            sb.append("\nTo make this provable, you would also need:");
            int sets = Math.min(deepWhyNot.size(), 3);
            for (int i = 0; i < sets; i++) {
                JsonNode completionSet = deepWhyNot.get(i);
                if (completionSet.isArray() && completionSet.size() > 0) {
                    StringBuilder chain = new StringBuilder();
                    for (JsonNode fact : completionSet) {
                        if (chain.length() > 0) chain.append(" -> ");
                        chain.append(fact.asText());
                    }
                    sb.append("\n  ").append(chain);
                } else if (!completionSet.isMissingNode()) {
                    sb.append("\n  ").append(completionSet.asText());
                }
            }
            if (deepWhyNot.size() > 3) {
                sb.append("\n  ... and ").append(deepWhyNot.size() - 3).append(" more paths.");
            }
        }

        // E12: fragility (SUPPORTED verdicts only)
        if ("SUPPORTED".equals(verdict)
                && fragility != null && !fragility.isMissingNode() && !fragility.isNull()) {
            double robustness = fragility.path("robustness").asDouble(1.0);
            JsonNode wouldFlipIf = fragility.path("wouldFlipIf");
            sb.append(String.format("\nFragility: robustness %.2f", robustness));
            if (wouldFlipIf.isArray() && wouldFlipIf.size() > 0) {
                sb.append(" — would flip if:");
                wouldFlipIf.forEach(w -> sb.append("\n  - ").append(w.asText()));
            } else {
                sb.append(" — no single-fact flip point found (robust)");
            }
        }

        if (stale) {
            sb.append("\nWARNING: KB is pending a cascade update — consider retrying.");
        }
        return sb.toString();
    }

    private String extractError(String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            String msg = json.path("message").asText(null);
            if (msg != null) return msg;
            msg = json.path("error").asText(null);
            if (msg != null) return msg;
        } catch (Exception ignored) {}
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }
}
