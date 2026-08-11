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
package ai.kompile.graph.reasoning.admission;

import ai.kompile.graph.reasoning.hybrid.HybridReasoner;
import ai.kompile.graph.reasoning.hybrid.HybridReasoner.ScoredEntity;
import ai.kompile.graph.reasoning.model.GraphEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Conservative graph admission policy backed by HybridReasoner.
 *
 * <p>This evaluator only makes a reuse/reject decision when the selected candidate is present in the
 * frozen graph and clearly separated from its nearest competitor. A candidate absent from the graph
 * is deliberately deferred because a bare node has no structural evidence; an adapter can supply a
 * richer policy for CREATE_PROVISIONAL once it materializes the candidate and its incident facts in a
 * read-only snapshot.</p>
 *
 * <p>Predicate-aware evidence is advisory to identity admission: an entity marked "Do not use" still
 * exists and should be reused rather than duplicated. Its cautionary status, requirements, overrides,
 * and supporting paths are therefore attached as a bounded trace for downstream policy or a small
 * model instead of being misinterpreted as identity invalidity.</p>
 */
public final class HybridGraphAdmissionEvaluator implements GraphAdmissionEvaluator {

    private final HybridReasoner reasoner;
    private final AdmissionEvidenceCollector evidenceCollector;
    private final double reuseThreshold;
    private final double rejectThreshold;
    private final double minimumMargin;

    public HybridGraphAdmissionEvaluator() {
        this(new HybridReasoner(), 0.80, 0.20, 0.10,
                AdmissionPredicateSemantics.canonical());
    }

    public HybridGraphAdmissionEvaluator(HybridReasoner reasoner,
                                         double reuseThreshold,
                                         double rejectThreshold,
                                         double minimumMargin) {
        this(reasoner, reuseThreshold, rejectThreshold, minimumMargin,
                AdmissionPredicateSemantics.canonical());
    }

    public HybridGraphAdmissionEvaluator(HybridReasoner reasoner,
                                         double reuseThreshold,
                                         double rejectThreshold,
                                         double minimumMargin,
                                         AdmissionPredicateSemantics predicateSemantics) {
        this.reasoner = Objects.requireNonNull(reasoner, "reasoner");
        this.evidenceCollector = new AdmissionEvidenceCollector(
                Objects.requireNonNull(predicateSemantics, "predicateSemantics"));
        requireUnitInterval(reuseThreshold, "reuseThreshold");
        requireUnitInterval(rejectThreshold, "rejectThreshold");
        requireUnitInterval(minimumMargin, "minimumMargin");
        if (reuseThreshold <= rejectThreshold) {
            throw new IllegalArgumentException("reuseThreshold must exceed rejectThreshold");
        }
        this.reuseThreshold = reuseThreshold;
        this.rejectThreshold = rejectThreshold;
        this.minimumMargin = minimumMargin;
    }

    @Override
    public GraphAdmissionResult evaluate(AdmissionRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.graph() == null) {
            return GraphAdmissionResult.defer("no frozen graph snapshot was supplied");
        }
        if (request.graph().isEmpty()) {
            return GraphAdmissionResult.defer("frozen graph is empty");
        }

        List<ScoredEntity> ranking = reasoner.rank(request.graph());
        MatchResolution selectedResolution = findMatches(request, request.candidate(), ranking);
        if (selectedResolution.matches().isEmpty()) {
            AdmissionEvidenceTrace trace = noMatchTrace(request.candidate());
            return new GraphAdmissionResult(
                    AdmissionDecision.DEFER, 0.0, 0.0,
                    "candidate is absent from frozen graph", true, false, null, null, trace);
        }
        if (selectedResolution.ambiguous()) {
            AdmissionEvidenceTrace trace = ambiguityTrace(selectedResolution.matches());
            return new GraphAdmissionResult(
                    AdmissionDecision.DEFER, 0.0, 0.0,
                    "candidate canonical identity matches multiple graph entities",
                    true, false, null, null, trace);
        }
        ScoredEntity selected = selectedResolution.matches().get(0);

        List<ScoredEntity> competitors = request.ballot().stream()
                .filter(item -> !sameCandidate(item, request.candidate()))
                .flatMap(item -> findMatches(request, item, ranking).matches().stream())
                .sorted(Comparator.comparingDouble(ScoredEntity::score).reversed()
                        .thenComparing(ScoredEntity::entityId))
                .toList();
        ScoredEntity leadingCompetitor = competitors.isEmpty() ? null : competitors.get(0);
        double competitorScore = leadingCompetitor == null ? 0.0 : leadingCompetitor.score();
        double margin = Math.min(1.0, Math.abs(selected.score() - competitorScore));
        AdmissionEvidenceTrace trace = evidenceTrace(
                request, selectedResolution, selected, leadingCompetitor);

        if (hasMaterialConflict(request, selected.entityId())) {
            return new GraphAdmissionResult(
                    AdmissionDecision.DEFER, clamp(selected.score()), margin,
                    "candidate has contradictory graph evidence", true, false, null, null, trace);
        }
        boolean thresholdReuse = selected.score() >= reuseThreshold;
        boolean calibratedHighConfidenceReuse = selected.score() >= Math.max(rejectThreshold,
                reuseThreshold - minimumMargin)
                && selected.score() >= competitorScore
                && margin >= minimumMargin
                && request.graph().entity(selected.entityId())
                .map(GraphEntity::confidence)
                .filter(confidence -> confidence >= 0.90)
                .isPresent()
                && hasPositiveIdentityEvidence(request, selected.entityId());
        if ((thresholdReuse || calibratedHighConfidenceReuse)
                && selected.score() >= competitorScore
                && margin >= minimumMargin) {
            String reason = calibratedHighConfidenceReuse && !thresholdReuse
                    ? "high-confidence identity evidence supports reuse"
                    : "hybrid score supports reuse";
            if (trace.hasKind(AdmissionEvidence.Kind.CAUTION)) {
                reason += "; cautionary domain evidence is attached";
            }
            return new GraphAdmissionResult(
                    AdmissionDecision.REUSE, clamp(selected.score()), margin,
                    reason, true, false, null, selected.entityId(), trace);
        }
        if (selected.score() <= rejectThreshold) {
            return new GraphAdmissionResult(
                    AdmissionDecision.REJECT, clamp(selected.score()), margin,
                    "hybrid score is below reject threshold", true, false, null, null, trace);
        }
        return new GraphAdmissionResult(
                AdmissionDecision.DEFER, clamp(selected.score()), margin,
                "hybrid score is ambiguous", true, false, null, null, trace);
    }

    private AdmissionEvidenceTrace evidenceTrace(AdmissionRequest request,
                                                 MatchResolution resolution,
                                                 ScoredEntity selected,
                                                 ScoredEntity leadingCompetitor) {
        List<AdmissionEvidence> items = new ArrayList<>();
        GraphEntity selectedEntity = request.graph().entity(selected.entityId()).orElseThrow();
        items.add(new AdmissionEvidence(
                AdmissionEvidence.Kind.IDENTITY_MATCH,
                resolution.canonicalMatch() ? "identity.canonical-match" : "identity.exact-id-match",
                List.of(selected.entityId()),
                List.of(),
                List.of(),
                clamp(selectedEntity.confidence()),
                resolution.canonicalMatch()
                        ? "candidate canonical identity resolves to " + selected.entityId()
                        : "candidate id exactly matches " + selected.entityId()));
        items.add(new AdmissionEvidence(
                AdmissionEvidence.Kind.STRUCTURAL_SCORE,
                "hybrid.structural-score",
                List.of(selected.entityId()),
                List.of(),
                List.of(),
                clamp(selected.score()),
                "hybrid=" + clamp(selected.score())
                        + ", structural=" + clamp(selected.structuralScore())
                        + ", semantic=" + clamp(selected.semanticScore())));
        if (leadingCompetitor != null) {
            items.add(new AdmissionEvidence(
                    AdmissionEvidence.Kind.COMPETITOR,
                    "identity.leading-ballot-competitor",
                    List.of(leadingCompetitor.entityId()),
                    List.of(),
                    List.of(),
                    clamp(leadingCompetitor.score()),
                    "leading ballot competitor is " + leadingCompetitor.entityId()));
        }

        AdmissionEvidenceTrace relationTrace =
                evidenceCollector.collect(request.graph(), selected.entityId());
        items.addAll(relationTrace.items());
        return new AdmissionEvidenceTrace(
                items, relationTrace.examinedPathCount(), relationTrace.truncated());
    }

    private static AdmissionEvidenceTrace noMatchTrace(AdmissionCandidate candidate) {
        AdmissionEvidence evidence = new AdmissionEvidence(
                AdmissionEvidence.Kind.NO_MATCH,
                "identity.absent",
                List.of(candidate.candidateId()),
                List.of(),
                List.of(),
                0.0,
                "candidate has no exact-id or canonical identity match in the frozen graph");
        return new AdmissionEvidenceTrace(List.of(evidence), 0, false);
    }

    private static AdmissionEvidenceTrace ambiguityTrace(List<ScoredEntity> matches) {
        List<AdmissionEvidence> evidence = matches.stream()
                .sorted(Comparator.comparingDouble(ScoredEntity::score).reversed()
                        .thenComparing(ScoredEntity::entityId))
                .map(match -> new AdmissionEvidence(
                        AdmissionEvidence.Kind.AMBIGUITY,
                        "identity.canonical-multiple",
                        List.of(match.entityId()),
                        List.of(),
                        List.of(),
                        clamp(match.score()),
                        "canonical identity also matches " + match.entityId()))
                .toList();
        return new AdmissionEvidenceTrace(evidence, 0, false);
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static void requireUnitInterval(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be finite and in [0,1]: " + value);
        }
    }

    private MatchResolution findMatches(AdmissionRequest request,
                                        AdmissionCandidate candidate,
                                        List<ScoredEntity> ranking) {
        List<ScoredEntity> exact = ranking.stream()
                .filter(entity -> candidate.candidateId().equals(entity.entityId()))
                .toList();
        if (!exact.isEmpty()) {
            return new MatchResolution(exact, false, false);
        }
        List<ScoredEntity> canonical = ranking.stream()
                .filter(entity -> request.graph().entity(entity.entityId())
                        .map(graphEntity -> canonicalMatches(graphEntity, candidate))
                        .orElse(false))
                .sorted(Comparator.comparingDouble(ScoredEntity::score).reversed()
                        .thenComparing(ScoredEntity::entityId))
                .toList();
        return new MatchResolution(canonical, canonical.size() > 1, true);
    }

    private record MatchResolution(
            List<ScoredEntity> matches,
            boolean ambiguous,
            boolean canonicalMatch) {
    }

    private static boolean sameCandidate(AdmissionCandidate left, AdmissionCandidate right) {
        return left.candidateId().equals(right.candidateId())
                && left.canonicalKey().equals(right.canonicalKey());
    }

    private static boolean canonicalMatches(GraphEntity entity, AdmissionCandidate candidate) {
        Object raw = entity.attributes().get("canonicalKey");
        if (raw == null) {
            raw = entity.attributes().get("canonical_key");
        }
        if (raw == null) {
            raw = entity.attributes().get("canonical");
        }
        return raw != null && candidate.canonicalKey().equals(String.valueOf(raw));
    }

    private static boolean hasMaterialConflict(AdmissionRequest request, String entityId) {
        return request.graph().relations().stream()
                .filter(relation -> entityId.equals(relation.sourceId())
                        || entityId.equals(relation.targetId()))
                .filter(relation -> isConflictType(relation.type()))
                .filter(relation -> relation.weight() * relation.confidence() >= 0.05)
                .anyMatch(relation -> conflictAppliesToBallot(request, entityId, relation));
    }

    private static boolean conflictAppliesToBallot(
            AdmissionRequest request,
            String entityId,
            ai.kompile.graph.reasoning.model.GraphRelation relation) {
        if (!isIdentitySeparationType(relation.type())) {
            return true;
        }
        String otherEntityId = entityId.equals(relation.sourceId())
                ? relation.targetId()
                : relation.sourceId();
        return request.ballot().stream()
                .filter(item -> !sameCandidate(item, request.candidate()))
                .anyMatch(item -> item.candidateId().equals(otherEntityId)
                        || request.graph().entity(otherEntityId)
                        .map(entity -> canonicalMatches(entity, item))
                        .orElse(false));
    }

    private static boolean hasPositiveIdentityEvidence(AdmissionRequest request, String entityId) {
        return request.graph().relations().stream()
                .filter(relation -> entityId.equals(relation.sourceId())
                        || entityId.equals(relation.targetId()))
                .filter(relation -> !isConflictType(relation.type()))
                .anyMatch(relation -> relation.weight() * relation.confidence() >= 0.50);
    }

    private static boolean isConflictType(String type) {
        String normalized = type == null ? "" : type.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.equals("CONTRADICTS")
                || normalized.equals("CONFLICTS")
                || normalized.equals("REFUTES")
                || normalized.equals("DISAGREES")
                || normalized.equals("OPPOSES")
                || isIdentitySeparationType(normalized);
    }

    private static boolean isIdentitySeparationType(String type) {
        String normalized = type == null ? "" : type.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.equals("NOT_SAME_AS")
                || normalized.equals("NOT_SAME")
                || normalized.equals("DIFFERENT_FROM");
    }
}
