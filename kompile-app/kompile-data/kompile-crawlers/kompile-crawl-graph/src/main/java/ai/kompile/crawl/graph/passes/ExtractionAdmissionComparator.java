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
package ai.kompile.crawl.graph.passes;

import ai.kompile.core.graphrag.GraphConstructor.ExtractionTaskContext;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.graph.reasoning.admission.AdmissionCandidate;
import ai.kompile.graph.reasoning.admission.AdmissionComparison;
import ai.kompile.graph.reasoning.admission.AdmissionComparisonSink;
import ai.kompile.graph.reasoning.admission.AdmissionDecision;
import ai.kompile.graph.reasoning.admission.AdmissionMode;
import ai.kompile.graph.reasoning.admission.AdmissionRequest;
import ai.kompile.graph.reasoning.admission.GraphOperationalPolicy;
import ai.kompile.graph.reasoning.admission.GraphOperationalVerdict;
import ai.kompile.graph.reasoning.admission.HybridGraphAdmissionEvaluator;
import ai.kompile.graph.reasoning.admission.OperationalDisposition;
import ai.kompile.graph.reasoning.admission.ParallelAdmissionCoordinator;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.unified.ExtractionToUnifiedGraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bridges the tool-driven extraction result to the graph-native admission comparison.
 *
 * <p>The extraction result is the model's accepted/staged identity decision. In shadow mode the graph
 * branch remains observational. In graph-policy mode a transient copy overlays the staged delta so
 * candidate-local relations are available to deterministic graph policy; only governed candidates
 * with an explicit policy-matched ALLOW remain in the returned extraction. The supplied snapshot is
 * never mutated.</p>
 */
public final class ExtractionAdmissionComparator {

    public static final String POLICY_VERSION = "hybrid-graph-admission-v2";

    /** Immutable result of applying the selected admission mode to one staged extraction delta. */
    public record AdmissionOutcome(
            ExtractionResult extraction,
            List<AdmissionComparison> comparisons,
            List<GraphOperationalVerdict> verdicts) {

        public AdmissionOutcome {
            comparisons = comparisons == null ? List.of() : List.copyOf(comparisons);
            verdicts = verdicts == null ? List.of() : List.copyOf(verdicts);
        }
    }

    private ExtractionAdmissionComparator() {
    }

    /**
     * Parses the string-valued core configuration without creating a dependency from core to the
     * graph-reasoning module. Missing values retain historical LLM-only behavior; invalid values are
     * rejected so a typo cannot silently disable deterministic admission.
     */
    public static AdmissionMode mode(String configured) {
        if (configured == null || configured.isBlank()) {
            return AdmissionMode.LLM_ONLY;
        }
        return AdmissionMode.valueOf(configured.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Emits one comparison for each valid extracted entity when shadow mode is enabled.
     */
    public static void compare(ExtractionResult extraction,
                               ExtractionTaskContext taskContext,
                               UnifiedGraph frozenGraph,
                               String snapshotId,
                               AdmissionMode admissionMode,
                               AdmissionComparisonSink sink) {
        admit(extraction, taskContext, frozenGraph, snapshotId, admissionMode,
                Set.of(), null, sink);
    }

    /**
     * Applies graph-authoritative operational admission or preserves the extraction for legacy and
     * shadow modes.
     *
     * @param governedEntityTypes case-insensitive entity types governed in GRAPH_POLICY; "*" governs all
     * @param operationalPolicy required in GRAPH_POLICY and ignored otherwise
     */
    public static AdmissionOutcome admit(
            ExtractionResult extraction,
            ExtractionTaskContext taskContext,
            UnifiedGraph frozenGraph,
            String snapshotId,
            AdmissionMode admissionMode,
            Set<String> governedEntityTypes,
            GraphOperationalPolicy operationalPolicy,
            AdmissionComparisonSink sink) {
        if (extraction == null) {
            return new AdmissionOutcome(null, List.of(), List.of());
        }
        Objects.requireNonNull(admissionMode, "admissionMode");
        if (admissionMode == AdmissionMode.LLM_ONLY
                || extraction.entities() == null
                || extraction.entities().isEmpty()) {
            return new AdmissionOutcome(extraction, List.of(), List.of());
        }
        if (frozenGraph == null) {
            if (admissionMode == AdmissionMode.GRAPH_POLICY) {
                throw new IllegalArgumentException(
                        "GRAPH_POLICY requires a frozen graph snapshot");
            }
            return new AdmissionOutcome(extraction, List.of(), List.of());
        }

        Set<String> governedTypes = normalizedTypes(governedEntityTypes);
        if (admissionMode == AdmissionMode.GRAPH_POLICY) {
            if (governedTypes.isEmpty()) {
                throw new IllegalArgumentException(
                        "GRAPH_POLICY requires at least one governed entity type");
            }
            Objects.requireNonNull(operationalPolicy,
                    "GRAPH_POLICY requires an operational graph policy");
        }

        String effectiveSnapshot = firstText(snapshotId, frozenGraph.graphId(), "in-run");
        ParallelAdmissionCoordinator coordinator = new ParallelAdmissionCoordinator(
                admissionMode, new HybridGraphAdmissionEvaluator());
        UnifiedGraph evaluationGraph = admissionMode == AdmissionMode.GRAPH_POLICY
                ? ExtractionToUnifiedGraph.apply(UnifiedGraph.of(frozenGraph), extraction)
                : frozenGraph;
        String decisionScope = firstText(
                taskContext == null ? null : taskContext.taskId(),
                taskContext == null ? null : taskContext.chunkId(),
                "extraction");
        List<AdmissionComparison> comparisons = new ArrayList<>();
        List<GraphOperationalVerdict> verdicts = new ArrayList<>();
        Set<String> rejectedIds = new LinkedHashSet<>();

        for (ExtractedEntity entity : extraction.entities()) {
            if (entity == null || blank(entity.id()) || blank(entity.name())) {
                continue;
            }
            if (admissionMode == AdmissionMode.GRAPH_POLICY
                    && !isGoverned(entity, governedTypes)) {
                continue;
            }
            AdmissionCandidate candidate = new AdmissionCandidate(
                    entity.id().trim(), canonicalKey(entity));
            AdmissionRequest request = new AdmissionRequest(
                    decisionScope + ":entity:" + candidate.candidateId(),
                    candidate,
                    List.of(candidate),
                    effectiveSnapshot,
                    POLICY_VERSION,
                    evaluationGraph);
            AdmissionDecision llmDecision = isExisting(frozenGraph, candidate.candidateId())
                    ? AdmissionDecision.REUSE
                    : AdmissionDecision.CREATE_PROVISIONAL;

            AdmissionComparison comparison = coordinator.evaluate(request, ignored -> llmDecision);
            if (admissionMode == AdmissionMode.GRAPH_POLICY) {
                GraphOperationalVerdict verdict = operationalPolicy.evaluate(
                        candidate.candidateId(), comparison.graphResult());
                verdicts.add(verdict);
                comparison = withOperationalAuthority(comparison, verdict);
                if (!verdict.policyMatched()
                        || verdict.disposition() != OperationalDisposition.ALLOW) {
                    rejectedIds.add(candidate.candidateId());
                }
            }
            comparisons.add(comparison);
            emit(comparison, sink);
        }
        ExtractionResult admitted = admissionMode == AdmissionMode.GRAPH_POLICY
                ? withoutRejected(extraction, rejectedIds)
                : extraction;
        return new AdmissionOutcome(admitted, comparisons, verdicts);
    }

    private static AdmissionComparison withOperationalAuthority(
            AdmissionComparison comparison, GraphOperationalVerdict verdict) {
        AdmissionDecision authoritative = switch (verdict.disposition()) {
            case ALLOW -> comparison.llmDecision();
            case DENY -> AdmissionDecision.REJECT;
            case REVIEW -> AdmissionDecision.DEFER;
        };
        return new AdmissionComparison(
                comparison.decisionGroupId(),
                comparison.candidateId(),
                comparison.canonicalKey(),
                comparison.snapshotId(),
                comparison.policyVersion(),
                comparison.ballotFingerprint(),
                comparison.mode(),
                comparison.llmDecision(),
                comparison.graphResult(),
                authoritative,
                comparison.graphDurationNanos());
    }

    private static void emit(AdmissionComparison comparison, AdmissionComparisonSink sink) {
        if (sink == null) {
            return;
        }
        try {
            sink.accept(comparison);
        } catch (RuntimeException ignored) {
            // Diagnostics never control either the shadow or deterministic admission path.
        }
    }

    private static ExtractionResult withoutRejected(
            ExtractionResult extraction, Set<String> rejectedIds) {
        if (rejectedIds.isEmpty()) {
            return extraction;
        }
        List<ExtractedEntity> entities = extraction.entities().stream()
                .filter(Objects::nonNull)
                .filter(entity -> !blank(entity.id()))
                .filter(entity -> !rejectedIds.contains(entity.id().trim()))
                .toList();
        List<ExtractedRelation> relations = extraction.relations().stream()
                .filter(Objects::nonNull)
                .filter(relation -> !rejectedEndpoint(relation.source(), rejectedIds))
                .filter(relation -> !rejectedEndpoint(relation.target(), rejectedIds))
                .toList();
        return new ExtractionResult(
                extraction.schema(), entities, relations, extraction.metadata());
    }

    private static boolean rejectedEndpoint(String entityId, Set<String> rejectedIds) {
        return !blank(entityId) && rejectedIds.contains(entityId.trim());
    }

    private static Set<String> normalizedTypes(Set<String> configured) {
        if (configured == null || configured.isEmpty()) {
            return Set.of();
        }
        return configured.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean isGoverned(ExtractedEntity entity, Set<String> governedTypes) {
        return governedTypes.contains("*")
                || (!blank(entity.type())
                && governedTypes.contains(entity.type().trim().toUpperCase(Locale.ROOT)));
    }

    private static boolean isExisting(UnifiedGraph graph, String candidateId) {
        return graph.entities().stream()
                .filter(Objects::nonNull)
                .map(GraphEntity::id)
                .anyMatch(candidateId::equals);
    }

    private static String canonicalKey(ExtractedEntity entity) {
        String aliases = entity.aliases() == null ? "" : entity.aliases().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining("|"));
        return firstText(entity.name(), null, "").toLowerCase(Locale.ROOT)
                + "\u0000" + firstText(entity.type(), null, "").toLowerCase(Locale.ROOT)
                + (aliases.isBlank() ? "" : "\u0000" + aliases);
    }

    private static String firstText(String first, String second, String fallback) {
        return !blank(first) ? first.trim() : !blank(second) ? second.trim() : fallback;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
