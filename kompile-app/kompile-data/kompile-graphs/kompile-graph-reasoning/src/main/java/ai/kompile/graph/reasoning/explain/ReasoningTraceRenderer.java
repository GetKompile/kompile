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
package ai.kompile.graph.reasoning.explain;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.fol.EntailmentRecord;
import ai.kompile.graph.reasoning.fol.grounding.DerivationTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic, compact renderings of graph reasoning traces for LLM prompt context.
 *
 * <p>The library has several trace carriers because different engines expose different proof
 * details. This renderer gives all of them one bounded text shape that preserves the pieces an
 * LLM can reason over: target, question, confidence, modality, evidence, rules, entailments, and
 * derivation structure.</p>
 */
public final class ReasoningTraceRenderer {

    public static final int DEFAULT_MAX_LINES = 20;
    private static final int MAX_LINE_CHARS = 280;

    private ReasoningTraceRenderer() {
    }

    /** Structured source/provenance references attached to a citeable trace step. */
    public record EvidenceReference(
            String refType,
            String sourceId,
            String nodeId,
            String edgeId,
            String documentId,
            String chunkId,
            Long factSheetId,
            String runId,
            String atomKey,
            String findingKey,
            String ruleId,
            String modality,
            String raw) {
        public EvidenceReference {
            refType = cleanOrNull(refType);
            sourceId = cleanOrNull(sourceId);
            nodeId = cleanOrNull(nodeId);
            edgeId = cleanOrNull(edgeId);
            documentId = cleanOrNull(documentId);
            chunkId = cleanOrNull(chunkId);
            runId = cleanOrNull(runId);
            atomKey = cleanOrNull(atomKey);
            findingKey = cleanOrNull(findingKey);
            ruleId = cleanOrNull(ruleId);
            modality = cleanOrNull(modality);
            raw = cleanOrNull(raw);
        }
    }

    /** One citeable trace step for structured claim attribution. */
    public record AttributionStep(
            String stepId,
            String kind,
            String conclusion,
            String operation,
            double confidence,
            String source,
            List<EvidenceReference> evidenceRefs) {
        public AttributionStep(String stepId, String kind, String conclusion, String operation,
                               double confidence, String source) {
            this(stepId, kind, conclusion, operation, confidence, source, List.of());
        }

        public AttributionStep {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    private static List<EvidenceReference> refs(EvidenceReference... references) {
        List<EvidenceReference> out = new ArrayList<>();
        if (references != null) {
            for (EvidenceReference reference : references) {
                if (reference != null) {
                    out.add(reference);
                }
            }
        }
        return List.copyOf(out);
    }

    private static EvidenceReference ref(String refType, String sourceId, String runId, String atomKey,
                                         String findingKey, String ruleId, String modality, String raw) {
        String parseText = String.join(" ", List.of(
                cleanOrNull(sourceId) == null ? "" : cleanOrNull(sourceId),
                cleanOrNull(raw) == null ? "" : cleanOrNull(raw)));
        return new EvidenceReference(
                refType,
                sourceId,
                tagged(parseText, "node", "nodeId"),
                tagged(parseText, "edge", "edgeId"),
                tagged(parseText, "document", "documentId", "doc"),
                tagged(parseText, "chunk", "chunkId"),
                taggedLong(parseText, "factSheet", "factSheetId"),
                runId,
                atomKey,
                findingKey,
                ruleId,
                modality,
                raw);
    }

    private static List<EvidenceReference> entailmentRefs(EntailmentRecord entailment) {
        List<EvidenceReference> refs = new ArrayList<>();
        refs.add(ref("ENTAILMENT", null, entailment.inferenceRunId(), entailment.groundedRvOrAtomKey(),
                null, null, null, entailment.groundedRvOrAtomKey()));
        for (String finding : entailment.supportingFindingKeys()) {
            refs.add(ref("FINDING", null, entailment.inferenceRunId(), null, finding,
                    null, null, finding));
        }
        for (String rule : entailment.activatedRules()) {
            refs.add(ref("RULE", null, entailment.inferenceRunId(), null, null,
                    rule, null, rule));
        }
        return List.copyOf(refs);
    }

    private static List<EvidenceReference> traceStepRefs(ReasoningTrace.Step step) {
        String ruleId = isBlank(step.operation()) ? null : step.operation();
        return refs(ref(step.kind().name(), step.source(), null, step.conclusion(), null,
                ruleId, null, step.conclusion()));
    }

    public static List<AttributionStep> attributionIndex(ReasoningTrail trail) {
        if (trail == null) {
            return List.of();
        }
        List<AttributionStep> out = new ArrayList<>();
        out.add(new AttributionStep("trace.root", nonBlank(trail.inferenceMode(), "UNKNOWN"),
                trail.targetId(), trail.inferenceMode(), trail.confidence(), trail.runId(),
                refs(ref("TRACE_ROOT", null, trail.runId(), trail.targetId(), null, null,
                        trail.inferenceMode(), trail.targetId()))));
        for (int i = 0; i < trail.evidence().size(); i++) {
            String evidence = trail.evidence().get(i);
            out.add(new AttributionStep("trace.evidence." + i, ReasoningTrace.StepKind.FACT.name(),
                    evidence, "evidence", 1.0, "evidence",
                    refs(ref("EVIDENCE", "evidence", trail.runId(), null, null, null, null, evidence))));
        }
        for (int i = 0; i < trail.activatedRules().size(); i++) {
            String rule = trail.activatedRules().get(i);
            out.add(new AttributionStep("trace.rule." + i, ReasoningTrace.StepKind.RULE.name(),
                    rule, "activatedRule", trail.confidence(), trail.runId(),
                    refs(ref("RULE", null, trail.runId(), null, null, rule, trail.inferenceMode(), rule))));
        }
        for (int i = 0; i < trail.entailments().size(); i++) {
            EntailmentRecord entailment = trail.entailments().get(i);
            String operation = entailment.activatedRules().isEmpty()
                    ? "entailment"
                    : String.join("; ", entailment.activatedRules());
            out.add(new AttributionStep("trace.entailment." + i, ReasoningTrace.StepKind.INFERENCE.name(),
                    entailment.groundedRvOrAtomKey(), operation, entailment.posterior(),
                    entailment.inferenceRunId(), entailmentRefs(entailment)));
        }
        collectDerivationAttributions(trail.derivationTree(), "trace.derivation.root", out);
        return List.copyOf(out);
    }

    public static List<AttributionStep> attributionIndex(ReasoningTrace trace) {
        if (trace == null) {
            return List.of();
        }
        List<AttributionStep> out = new ArrayList<>();
        collectTraceAttributions(trace.conclusion(), "trace.root", out);
        return List.copyOf(out);
    }

    public static List<AttributionStep> attributionIndex(CompositeReasoningTrail trail) {
        if (trail == null) {
            return List.of();
        }
        List<AttributionStep> out = new ArrayList<>();
        String conclusion = trail.naturalLanguageAnswer().isEmpty() ? trail.targetId() : trail.naturalLanguageAnswer();
        out.add(new AttributionStep("trace.root", ReasoningTrace.StepKind.FUSION.name(),
                conclusion, "fused", trail.fusedConfidence(), trail.runId(),
                refs(ref("FUSION_RUN", null, trail.runId(), trail.targetId(), null, null, null, conclusion))));
        for (int i = 0; i < trail.modalities().size(); i++) {
            ModalityEvidence modality = trail.modalities().get(i);
            String modalityId = "trace.modality." + i;
            String label = modality.summary().isEmpty() ? modality.kind().name() : modality.summary();
            out.add(new AttributionStep(modalityId, ReasoningTrace.StepKind.FUSION.name(),
                    label, modality.kind().name(), modality.confidence(), null,
                    refs(ref("MODALITY", null, trail.runId(), null, null, null,
                            modality.kind().name(), label))));
            for (int j = 0; j < modality.details().size(); j++) {
                String detail = modality.details().get(j);
                out.add(new AttributionStep(modalityId + ".detail." + j,
                        ReasoningTrace.StepKind.FACT.name(), detail,
                        "modalityDetail", modality.confidence(), modality.kind().name(),
                        refs(ref("MODALITY_DETAIL", modality.kind().name(), trail.runId(), null,
                                null, null, modality.kind().name(), detail))));
            }
        }
        return List.copyOf(out);
    }

    public static String toLlmContext(ReasoningTrail trail) {
        return toLlmContext(trail, DEFAULT_MAX_LINES);
    }

    public static String toLlmContext(ReasoningTrail trail, int maxLines) {
        if (trail == null) {
            return "";
        }
        LineWriter out = new LineWriter(maxLines);
        String mode = nonBlank(trail.inferenceMode(), "UNKNOWN");
        // E3: include runId and computedAt in the header line when present
        StringBuilder header = new StringBuilder("Reasoning trace (").append(mode)
                .append("): id=trace.root; target=")
                .append(displayAtom(trail.targetId(), trail.atomKeyToTitle()))
                .append("; confidence=").append(confidence(trail.confidence()));
        if (!isBlank(trail.runId())) header.append("; runId=").append(clean(trail.runId()));
        if (trail.computedAt() != null) header.append("; computedAt=").append(trail.computedAt());
        out.add(header.toString());
        addIfPresent(out, "Question", trail.question());
        addIfPresent(out, "Summary", trail.naturalLanguageSummary());
        addBreakdown(out, trail.breakdown());
        addList(out, "Evidence", trail.evidence(), null, "trace.evidence");
        addList(out, "Rules", trail.activatedRules(), trail.ruleToHumanized(), "trace.rule");
        addEntailments(out, trail.entailments(), trail.atomKeyToTitle(), trail.ruleToHumanized());
        addDerivation(out, trail.derivationTree(), trail.atomKeyToTitle(), trail.ruleToHumanized());
        return out.text();
    }

    public static String toLlmContext(ReasoningTrace trace) {
        return toLlmContext(trace, DEFAULT_MAX_LINES);
    }

    public static String toLlmContext(ReasoningTrace trace, int maxLines) {
        if (trace == null) {
            return "";
        }
        LineWriter out = new LineWriter(maxLines);
        ReasoningTrace.Step root = trace.conclusion();
        // E3: include runId and computedAt from root step meta when present
        StringBuilder header = new StringBuilder("Reasoning trace (").append(root.kind())
                .append("): id=trace.root; conclusion=").append(clean(root.conclusion()))
                .append("; confidence=").append(confidence(root.confidence()));
        if (root.meta() != null) {
            String runId = root.meta().get("runId");
            String computedAt = root.meta().get("computedAt");
            if (!isBlank(runId)) header.append("; runId=").append(clean(runId));
            if (!isBlank(computedAt)) header.append("; computedAt=").append(clean(computedAt));
        }
        out.add(header.toString());
        addTraceStep(out, root, 0, "trace.root");
        return out.text();
    }

    public static String toLlmContext(CompositeReasoningTrail trail) {
        return toLlmContext(trail, DEFAULT_MAX_LINES);
    }

    public static String toLlmContext(CompositeReasoningTrail trail, int maxLines) {
        if (trail == null) {
            return "";
        }
        LineWriter out = new LineWriter(maxLines);
        // E3: include runId and computedAt in the header line when present
        StringBuilder header = new StringBuilder("Reasoning trace (FUSION): id=trace.root; target=")
                .append(clean(trail.targetId()))
                .append("; confidence=").append(confidence(trail.fusedConfidence()))
                .append("; modalities=").append(trail.modalities().size());
        if (!isBlank(trail.runId())) header.append("; runId=").append(clean(trail.runId()));
        if (trail.computedAt() != null) header.append("; computedAt=").append(trail.computedAt());
        out.add(header.toString());
        addIfPresent(out, "Question", trail.question());
        addIfPresent(out, "Answer", trail.naturalLanguageAnswer());
        if (!trail.modalities().isEmpty() && out.remaining() > 0) {
            if (out.remaining() == 1) {
                ModalityEvidence first = trail.modalities().get(0);
                out.add("Modalities: [trace.modality.0] " + modalityLine(first));
                return out.text();
            }
            out.add("Modalities:");
            for (int i = 0; i < trail.modalities().size(); i++) {
                ModalityEvidence modality = trail.modalities().get(i);
                String modalityId = "trace.modality." + i;
                if (!out.add("- [" + modalityId + "] " + modalityLine(modality))) {
                    break;
                }
                for (int j = 0; j < modality.details().size(); j++) {
                    if (!out.add("  - [" + modalityId + ".detail." + j + "] "
                            + clean(modality.details().get(j)))) {
                        break;
                    }
                }
            }
        }
        return out.text();
    }

    private static void collectTraceAttributions(ReasoningTrace.Step step, String stepId,
                                                 List<AttributionStep> out) {
        if (step == null) {
            return;
        }
        out.add(new AttributionStep(stepId, step.kind().name(), step.conclusion(), step.operation(),
                step.confidence(), step.source(), traceStepRefs(step)));
        for (int i = 0; i < step.premises().size(); i++) {
            collectTraceAttributions(step.premises().get(i), stepId + "." + i, out);
        }
    }

    private static void collectDerivationAttributions(DerivationTree node, String stepId,
                                                      List<AttributionStep> out) {
        if (node == null) {
            return;
        }
        String kind = node.isLeaf() ? ReasoningTrace.StepKind.FACT.name() : ReasoningTrace.StepKind.RULE.name();
        out.add(new AttributionStep(stepId, kind, node.atomKey(), node.ruleApplied(),
                node.confidence(), node.sourceProvenance(),
                refs(ref(kind, node.sourceProvenance(), null, node.atomKey(), null,
                        node.ruleApplied(), null, node.atomKey()))));
        for (int i = 0; i < node.children().size(); i++) {
            collectDerivationAttributions(node.children().get(i), stepId + "." + i, out);
        }
    }

    private static void addIfPresent(LineWriter out, String label, String value) {
        if (!isBlank(value)) {
            out.add(label + ": " + clean(value));
        }
    }

    private static void addBreakdown(LineWriter out, ConfidenceBreakdown breakdown) {
        if (breakdown == null) {
            return;
        }
        List<String> parts = new ArrayList<>();
        addMetric(parts, "grounding", breakdown.groundingConfidence());
        addMetric(parts, "psl", breakdown.pslSoftTruth());
        addMetric(parts, "mebn", breakdown.mebnPosterior());
        addMetric(parts, "structural", breakdown.structuralScore());
        addMetric(parts, "semantic", breakdown.semanticScore());
        if (!Double.isNaN(breakdown.structuralWeight()) || !Double.isNaN(breakdown.semanticWeight())) {
            parts.add("weights=" + confidence(breakdown.structuralWeight()) + "/"
                    + confidence(breakdown.semanticWeight()));
        }
        addMetric(parts, "distanceToSatisfaction", breakdown.distanceToSatisfaction());
        if (!parts.isEmpty()) {
            out.add("Confidence breakdown: " + String.join(", ", parts));
        }
    }

    private static void addMetric(List<String> parts, String name, double value) {
        if (!Double.isNaN(value)) {
            parts.add(name + "=" + confidence(value));
        }
    }

    private static void addList(LineWriter out, String label, List<String> values,
                                Map<String, String> displayMap, String idPrefix) {
        if (values == null || values.isEmpty() || out.remaining() <= 0) {
            return;
        }
        if (out.remaining() == 1) {
            out.add(label + ": [" + idPrefix + ".0] " + display(values.get(0), displayMap));
            return;
        }
        if (!out.add(label + ":")) {
            return;
        }
        for (int i = 0; i < values.size(); i++) {
            if (!out.add("- [" + idPrefix + "." + i + "] " + display(values.get(i), displayMap))) {
                break;
            }
        }
    }

    private static void addEntailments(LineWriter out, List<EntailmentRecord> entailments,
                                       Map<String, String> atomDisplay,
                                       Map<String, String> ruleDisplay) {
        if (entailments == null || entailments.isEmpty() || out.remaining() <= 0) {
            return;
        }
        if (out.remaining() == 1) {
            out.add("Entailments: [trace.entailment.0] "
                    + entailmentLine(entailments.get(0), atomDisplay, ruleDisplay));
            return;
        }
        if (!out.add("Entailments:")) {
            return;
        }
        for (int i = 0; i < entailments.size(); i++) {
            if (!out.add("- [trace.entailment." + i + "] "
                    + entailmentLine(entailments.get(i), atomDisplay, ruleDisplay))) {
                break;
            }
        }
    }

    private static String entailmentLine(EntailmentRecord entailment,
                                         Map<String, String> atomDisplay,
                                         Map<String, String> ruleDisplay) {
        StringBuilder sb = new StringBuilder(displayAtom(entailment.groundedRvOrAtomKey(), atomDisplay))
                .append(" (posterior ").append(confidence(entailment.posterior())).append(')');
        if (!entailment.activatedRules().isEmpty()) {
            sb.append(" via ").append(display(String.join("; ", entailment.activatedRules()), ruleDisplay));
        }
        if (!entailment.supportingFindingKeys().isEmpty()) {
            sb.append(" support=").append(clean(String.join(", ", entailment.supportingFindingKeys())));
        }
        return clean(sb.toString());
    }

    private static void addDerivation(LineWriter out, DerivationTree tree,
                                      Map<String, String> atomDisplay,
                                      Map<String, String> ruleDisplay) {
        if (tree == null || out.remaining() <= 0) {
            return;
        }
        if (out.remaining() == 1) {
            out.add("Derivation: [trace.derivation.root] " + displayAtom(tree.atomKey(), atomDisplay));
            return;
        }
        if (!out.add("Derivation:")) {
            return;
        }
        addDerivationNode(out, tree, 0, "trace.derivation.root", atomDisplay, ruleDisplay);
    }

    private static void addDerivationNode(LineWriter out, DerivationTree node, int depth, String stepId,
                                          Map<String, String> atomDisplay,
                                          Map<String, String> ruleDisplay) {
        if (node == null || out.remaining() <= 0) {
            return;
        }
        String kind = node.isLeaf() ? "FACT" : "RULE";
        StringBuilder line = new StringBuilder(indent(depth))
                .append("- [").append(stepId).append("] [")
                .append(kind).append(" confidence=").append(confidence(node.confidence())).append("] ")
                .append(displayAtom(node.atomKey(), atomDisplay));
        if (!isBlank(node.ruleApplied())) {
            line.append(" via ").append(display(node.ruleApplied(), ruleDisplay));
        }
        if (!isBlank(node.sourceProvenance())) {
            line.append(" source=").append(clean(node.sourceProvenance()));
        }
        if (!out.add(line.toString())) {
            return;
        }
        for (int i = 0; i < node.children().size(); i++) {
            addDerivationNode(out, node.children().get(i), depth + 1, stepId + "." + i,
                    atomDisplay, ruleDisplay);
        }
    }

    private static void addTraceStep(LineWriter out, ReasoningTrace.Step step, int depth, String stepId) {
        if (step == null || out.remaining() <= 0) {
            return;
        }
        StringBuilder line = new StringBuilder(indent(depth))
                .append("- [").append(stepId).append("] [")
                .append(step.kind()).append(" confidence=").append(confidence(step.confidence()));
        // E3: emit compact opinion annotation when present
        if (step.opinion() != null) {
            Opinion op = step.opinion();
            line.append(" [b=").append(confidence(op.belief()))
                .append(" d=").append(confidence(op.disbelief()))
                .append(" u=").append(confidence(op.uncertainty()))
                .append(']');
        }
        line.append("] ").append(clean(step.conclusion()));
        if (!isBlank(step.operation())) {
            line.append(" via ").append(clean(step.operation()));
        }
        if (!isBlank(step.source())) {
            line.append(" source=").append(clean(step.source()));
        }
        if (!out.add(line.toString())) {
            return;
        }
        for (int i = 0; i < step.premises().size(); i++) {
            addTraceStep(out, step.premises().get(i), depth + 1, stepId + "." + i);
        }
    }

    private static String modalityLine(ModalityEvidence modality) {
        StringBuilder line = new StringBuilder(modality.kind().name())
                .append(" confidence=").append(confidence(modality.confidence()));
        if (!isBlank(modality.summary())) {
            line.append(": ").append(clean(modality.summary()));
        }
        return clean(line.toString());
    }

    private static String display(String raw, Map<String, String> displayMap) {
        if (raw == null) {
            return "";
        }
        if (displayMap != null) {
            String mapped = displayMap.get(raw);
            if (!isBlank(mapped) && !mapped.equals(raw)) {
                return clean(mapped) + " [" + clean(raw) + "]";
            }
        }
        return clean(raw);
    }

    private static String displayAtom(String raw, Map<String, String> atomDisplay) {
        return display(raw, atomDisplay);
    }

    private static String tagged(String text, String... keys) {
        if (isBlank(text) || keys == null) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String key : keys) {
            if (isBlank(key)) {
                continue;
            }
            String normalizedKey = key.toLowerCase(Locale.ROOT);
            String value = taggedWithSeparator(text, lower, normalizedKey + ":");
            if (!isBlank(value)) {
                return value;
            }
            value = taggedWithSeparator(text, lower, normalizedKey + "=");
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static Long taggedLong(String text, String... keys) {
        String value = tagged(text, keys);
        if (isBlank(value)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String taggedWithSeparator(String original, String lower, String token) {
        int start = lower.indexOf(token);
        if (start < 0) {
            return null;
        }
        start += token.length();
        int end = start;
        while (end < original.length()) {
            char c = original.charAt(end);
            if (Character.isWhitespace(c) || ",;)]}\"'".indexOf(c) >= 0) {
                break;
            }
            end++;
        }
        if (end <= start) {
            return null;
        }
        return cleanOrNull(original.substring(start, end));
    }

    private static String cleanOrNull(String value) {
        String cleaned = clean(value);
        return cleaned.isBlank() ? null : cleaned;
    }

    private static String nonBlank(String value, String fallback) {
        return isBlank(value) ? fallback : clean(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String confidence(double value) {
        if (Double.isNaN(value)) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String indent(int depth) {
        if (depth <= 0) {
            return "";
        }
        return "  ".repeat(Math.min(depth, 8));
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .trim()
                .replaceAll("[ ]{2,}", " ");
        if (cleaned.length() <= MAX_LINE_CHARS) {
            return cleaned;
        }
        return cleaned.substring(0, MAX_LINE_CHARS - 3) + "...";
    }

    private static final class LineWriter {
        private final int maxLines;
        private final StringBuilder sb = new StringBuilder();
        private int lines;

        private LineWriter(int maxLines) {
            this.maxLines = Math.max(0, maxLines);
        }

        private int remaining() {
            return maxLines - lines;
        }

        private boolean add(String line) {
            if (remaining() <= 0) {
                return false;
            }
            if (lines > 0) {
                sb.append('\n');
            }
            sb.append(cleanRenderedLine(line));
            lines++;
            return true;
        }

        private String cleanRenderedLine(String line) {
            if (line == null) {
                return "";
            }
            String cleaned = line.replace('\r', ' ')
                    .replace('\n', ' ')
                    .replace('\t', ' ')
                    .stripTrailing();
            if (cleaned.length() <= MAX_LINE_CHARS) {
                return cleaned;
            }
            return cleaned.substring(0, MAX_LINE_CHARS - 3) + "...";
        }

        private String text() {
            return sb.toString();
        }
    }
}
