/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.discovery;

import ai.kompile.graph.reasoning.embedding.Embeddings;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Graph-only candidate generation for missing typed relations.
 *
 * <p>This is deliberately a bounded discovery primitive, not a domain-specific process miner. A
 * caller supplies a {@link RelationProfile}: relation type, endpoint types, generic text/embedding
 * weights, and optional ontology/process-library term rules. The scorer then proposes relation
 * candidates using only fields already present on the {@link ReasoningGraph}: entity types,
 * labels, tags, attributes, confidence/weight, and embeddings exposed by the graph.</p>
 *
 * <p>The resulting candidates are useful as an explicit bridge into relation-bounded reasoners such
 * as {@code RelationalMTheoryBuilder}, whose MEBN grounding only considers pairs connected by an
 * edge. Callers can materialize candidates as {@code CANDIDATE_<RELATION>} edges, feed those into
 * MEBN/PSL as soft evidence, and keep the original observed graph unchanged.</p>
 */
public final class RelationCandidateDiscovery {

    private RelationCandidateDiscovery() {
    }

    /** A scored proposed relation between two graph entities. */
    public record Candidate(String relationType,
                            String sourceId,
                            String sourceLabel,
                            String targetId,
                            String targetLabel,
                            double semanticScore,
                            double lexicalScore,
                            double termScore,
                            double priorScore,
                            double score,
                            List<String> matchedRules) {
        public Candidate {
            Objects.requireNonNull(relationType, "relationType");
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(targetId, "targetId");
            sourceLabel = sourceLabel == null ? "" : sourceLabel;
            targetLabel = targetLabel == null ? "" : targetLabel;
            semanticScore = clamp(semanticScore);
            lexicalScore = clamp(lexicalScore);
            termScore = clamp(termScore);
            priorScore = clamp(priorScore);
            score = clamp(score);
            matchedRules = matchedRules == null ? List.of() : List.copyOf(matchedRules);
        }

        public String groundedKey() {
            return relationType + "(" + sourceId + "," + targetId + ")";
        }
    }

    /** Positive text evidence for a candidate relation. */
    public record TermRule(String name,
                           Set<String> sourceTerms,
                           Set<String> targetTerms,
                           double weight) {
        public TermRule {
            name = (name == null || name.isBlank()) ? "term-rule" : name;
            sourceTerms = normalizeTerms(sourceTerms);
            targetTerms = normalizeTerms(targetTerms);
            weight = clamp(weight);
        }
    }

    /** Negative text evidence, with optional source-side exemption terms. */
    public record TermPenalty(String name,
                              Set<String> targetTerms,
                              Set<String> exemptSourceTerms,
                              double penalty) {
        public TermPenalty {
            name = (name == null || name.isBlank()) ? "term-penalty" : name;
            targetTerms = normalizeTerms(targetTerms);
            exemptSourceTerms = normalizeTerms(exemptSourceTerms);
            penalty = clamp(penalty);
        }
    }

    /** Scoring and endpoint constraints for one relation type. */
    public record RelationProfile(String relationType,
                                  Set<String> sourceTypes,
                                  Set<String> targetTypes,
                                  Set<String> relationTerms,
                                  List<TermRule> termRules,
                                  List<TermPenalty> penalties,
                                  double semanticWeight,
                                  double lexicalWeight,
                                  double termWeight,
                                  double priorWeight,
                                  double minScore,
                                  int topKPerSource,
                                  boolean skipSourcesWithExistingRelation,
                                  boolean excludeExistingPairs) {
        public RelationProfile {
            relationType = Objects.requireNonNull(relationType, "relationType").trim();
            if (relationType.isEmpty()) {
                throw new IllegalArgumentException("relationType must not be blank");
            }
            sourceTypes = normalizeTypes(sourceTypes);
            targetTypes = normalizeTypes(targetTypes);
            relationTerms = normalizeTerms(relationTerms);
            termRules = termRules == null ? List.of() : List.copyOf(termRules);
            penalties = penalties == null ? List.of() : List.copyOf(penalties);
            semanticWeight = Math.max(0.0, semanticWeight);
            lexicalWeight = Math.max(0.0, lexicalWeight);
            termWeight = Math.max(0.0, termWeight);
            priorWeight = Math.max(0.0, priorWeight);
            double weightSum = semanticWeight + lexicalWeight + termWeight + priorWeight;
            if (weightSum == 0.0) {
                semanticWeight = 0.35;
                lexicalWeight = 0.20;
                termWeight = 0.35;
                priorWeight = 0.10;
            }
            minScore = clamp(minScore);
            topKPerSource = topKPerSource <= 0 ? 1 : topKPerSource;
        }

        public static Builder builder(String relationType) {
            return new Builder(relationType);
        }

        public static final class Builder {
            private final String relationType;
            private final Set<String> sourceTypes = new LinkedHashSet<>();
            private final Set<String> targetTypes = new LinkedHashSet<>();
            private final Set<String> relationTerms = new LinkedHashSet<>();
            private final List<TermRule> termRules = new ArrayList<>();
            private final List<TermPenalty> penalties = new ArrayList<>();
            private double semanticWeight = 0.35;
            private double lexicalWeight = 0.20;
            private double termWeight = 0.35;
            private double priorWeight = 0.10;
            private double minScore = 0.50;
            private int topKPerSource = 1;
            private boolean skipSourcesWithExistingRelation;
            private boolean excludeExistingPairs = true;

            private Builder(String relationType) {
                this.relationType = relationType;
            }

            public Builder sourceTypes(String... types) {
                addAll(sourceTypes, types == null ? List.of() : List.of(types));
                return this;
            }

            public Builder targetTypes(String... types) {
                addAll(targetTypes, types == null ? List.of() : List.of(types));
                return this;
            }

            public Builder relationTerms(String... terms) {
                addAll(relationTerms, terms == null ? List.of() : List.of(terms));
                return this;
            }

            public Builder boost(String name, double weight, Collection<String> sourceTerms, Collection<String> targetTerms) {
                termRules.add(new TermRule(name, mutableCopy(sourceTerms), mutableCopy(targetTerms), weight));
                return this;
            }

            public Builder penalizeTarget(String name, double penalty, Collection<String> targetTerms) {
                penalties.add(new TermPenalty(name, mutableCopy(targetTerms), Set.of(), penalty));
                return this;
            }

            public Builder penalizeTargetUnlessSource(String name,
                                                      double penalty,
                                                      Collection<String> targetTerms,
                                                      Collection<String> exemptSourceTerms) {
                penalties.add(new TermPenalty(name, mutableCopy(targetTerms), mutableCopy(exemptSourceTerms), penalty));
                return this;
            }

            public Builder weights(double semanticWeight, double lexicalWeight, double termWeight, double priorWeight) {
                this.semanticWeight = semanticWeight;
                this.lexicalWeight = lexicalWeight;
                this.termWeight = termWeight;
                this.priorWeight = priorWeight;
                return this;
            }

            public Builder minScore(double minScore) {
                this.minScore = minScore;
                return this;
            }

            public Builder topKPerSource(int topKPerSource) {
                this.topKPerSource = topKPerSource;
                return this;
            }

            public Builder skipSourcesWithExistingRelation(boolean skipSourcesWithExistingRelation) {
                this.skipSourcesWithExistingRelation = skipSourcesWithExistingRelation;
                return this;
            }

            public Builder excludeExistingPairs(boolean excludeExistingPairs) {
                this.excludeExistingPairs = excludeExistingPairs;
                return this;
            }

            public RelationProfile build() {
                return new RelationProfile(relationType, sourceTypes, targetTypes, relationTerms, termRules,
                        penalties, semanticWeight, lexicalWeight, termWeight, priorWeight, minScore,
                        topKPerSource, skipSourcesWithExistingRelation, excludeExistingPairs);
            }
        }
    }

    public static List<Candidate> discover(ReasoningGraph graph, RelationProfile profile) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(profile, "profile");
        List<GraphEntity> sources = graph.entities().stream()
                .filter(e -> hasAnyType(e, profile.sourceTypes()))
                .sorted(Comparator.comparing(GraphEntity::id))
                .toList();
        List<GraphEntity> targets = graph.entities().stream()
                .filter(e -> hasAnyType(e, profile.targetTypes()))
                .sorted(Comparator.comparing(GraphEntity::id))
                .toList();
        List<Candidate> out = new ArrayList<>();
        for (GraphEntity source : sources) {
            if (profile.skipSourcesWithExistingRelation()
                    && hasOutgoingRelation(graph, source.id(), profile.relationType())) {
                continue;
            }
            List<Candidate> sourceCandidates = new ArrayList<>();
            for (GraphEntity target : targets) {
                if (source.id().equals(target.id())) {
                    continue;
                }
                if (profile.excludeExistingPairs()
                        && hasRelation(graph, source.id(), target.id(), profile.relationType())) {
                    continue;
                }
                Candidate candidate = score(source, target, profile);
                if (candidate.score() >= profile.minScore()) {
                    sourceCandidates.add(candidate);
                }
            }
            sourceCandidates.sort(Comparator.comparingDouble(Candidate::score).reversed()
                    .thenComparing(Candidate::targetId));
            int limit = Math.min(profile.topKPerSource(), sourceCandidates.size());
            out.addAll(sourceCandidates.subList(0, limit));
        }
        out.sort(Comparator.comparingDouble(Candidate::score).reversed()
                .thenComparing(Candidate::sourceId)
                .thenComparing(Candidate::targetId));
        return out;
    }

    public static Candidate score(GraphEntity source, GraphEntity target, RelationProfile profile) {
        double semanticScore = positiveCosine(source.embedding(), target.embedding());
        double lexicalScore = lexicalJaccard(source, target);
        TermScore termScore = termScore(source, target, profile);
        double priorScore = (clamp(source.confidence()) + clamp(target.confidence())) / 2.0;
        double weightSum = profile.semanticWeight() + profile.lexicalWeight()
                + profile.termWeight() + profile.priorWeight();
        double score = (profile.semanticWeight() * semanticScore
                + profile.lexicalWeight() * lexicalScore
                + profile.termWeight() * termScore.score()
                + profile.priorWeight() * priorScore) / weightSum;
        return new Candidate(profile.relationType(), source.id(), source.label(), target.id(), target.label(),
                semanticScore, lexicalScore, termScore.score(), priorScore, score, termScore.matchedRules());
    }

    public static GraphRelation candidateRelation(Candidate candidate) {
        return GraphRelation.builder("candidate:" + sanitize(candidate.relationType()) + ":"
                        + sanitize(candidate.sourceId()) + ":" + sanitize(candidate.targetId()),
                        candidate.sourceId(), candidate.targetId())
                .type("CANDIDATE_" + candidate.relationType())
                .weight(candidate.score())
                .confidence(candidate.score())
                .tag("candidate")
                .tag("graph-only")
                .attribute("source", "graph-only-relation-candidate-discovery")
                .attribute("relationType", candidate.relationType())
                .attribute("semanticScore", candidate.semanticScore())
                .attribute("lexicalScore", candidate.lexicalScore())
                .attribute("termScore", candidate.termScore())
                .attribute("priorScore", candidate.priorScore())
                .attribute("score", candidate.score())
                .attribute("matchedRules", candidate.matchedRules())
                .build();
    }

    private record TermScore(double score, List<String> matchedRules) {
        private TermScore {
            score = clamp(score);
            matchedRules = matchedRules == null ? List.of() : List.copyOf(matchedRules);
        }
    }

    private static TermScore termScore(GraphEntity source, GraphEntity target, RelationProfile profile) {
        String sourceText = graphText(source);
        String targetText = graphText(target);
        String combinedText = sourceText + " " + targetText;
        List<String> matchedRules = new ArrayList<>();
        double score = 0.0;
        double relationCue = termFraction(combinedText, profile.relationTerms());
        if (relationCue > 0.0) {
            score += 0.25 * relationCue;
            matchedRules.add("relation-terms");
        }
        for (TermRule rule : profile.termRules()) {
            boolean sourceMatches = rule.sourceTerms().isEmpty() || containsAny(sourceText, rule.sourceTerms());
            boolean targetMatches = rule.targetTerms().isEmpty() || containsAny(targetText, rule.targetTerms());
            if (sourceMatches && targetMatches) {
                score += rule.weight();
                matchedRules.add(rule.name());
            }
        }
        for (TermPenalty penalty : profile.penalties()) {
            boolean targetMatches = containsAny(targetText, penalty.targetTerms());
            boolean sourceExempt = !penalty.exemptSourceTerms().isEmpty()
                    && containsAny(sourceText, penalty.exemptSourceTerms());
            if (targetMatches && !sourceExempt) {
                score -= penalty.penalty();
                matchedRules.add("penalty:" + penalty.name());
            }
        }
        return new TermScore(score, matchedRules);
    }

    private static double lexicalJaccard(GraphEntity left, GraphEntity right) {
        Set<String> leftTokens = graphTokens(left);
        Set<String> rightTokens = graphTokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new LinkedHashSet<>(leftTokens);
        intersection.retainAll(rightTokens);
        Set<String> union = new LinkedHashSet<>(leftTokens);
        union.addAll(rightTokens);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private static Set<String> graphTokens(GraphEntity entity) {
        Set<String> stopWords = Set.of("and", "the", "for", "from", "into", "with", "source", "graph");
        Set<String> tokens = new LinkedHashSet<>();
        for (String raw : graphText(entity).replaceAll("[^a-z0-9]+", " ").split(" ")) {
            if (raw.length() >= 2 && !stopWords.contains(raw)) {
                tokens.add(raw);
            }
        }
        return tokens;
    }

    private static String graphText(GraphEntity entity) {
        StringBuilder text = new StringBuilder();
        text.append(entity.id()).append(' ')
                .append(entity.type()).append(' ')
                .append(entity.label()).append(' ');
        for (String tag : entity.tags()) {
            text.append(tag).append(' ');
        }
        entity.attributes().forEach((key, value) -> text.append(key).append(' ').append(value).append(' '));
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private static double positiveCosine(double[] left, double[] right) {
        if (left == null || right == null || left.length == 0 || right.length == 0 || left.length != right.length) {
            return 0.0;
        }
        return clamp(Embeddings.cosine(left, right));
    }

    private static boolean hasAnyType(GraphEntity entity, Set<String> types) {
        if (types.isEmpty()) {
            return true;
        }
        for (String type : types) {
            if (entity.hasTypeMembership(type)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasOutgoingRelation(ReasoningGraph graph, String sourceId, String relationType) {
        return graph.relations().stream()
                .anyMatch(r -> sourceId.equals(r.sourceId()) && relationType.equalsIgnoreCase(r.type()));
    }

    private static boolean hasRelation(ReasoningGraph graph, String sourceId, String targetId, String relationType) {
        return graph.relations().stream()
                .anyMatch(r -> sourceId.equals(r.sourceId())
                        && targetId.equals(r.targetId())
                        && relationType.equalsIgnoreCase(r.type()));
    }

    private static double termFraction(String text, Set<String> terms) {
        if (terms.isEmpty()) {
            return 0.0;
        }
        long matches = terms.stream().filter(text::contains).count();
        return (double) matches / terms.size();
    }

    private static boolean containsAny(String text, Set<String> terms) {
        if (terms.isEmpty()) {
            return false;
        }
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> normalizeTypes(Collection<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String value : raw) {
                if (value != null && !value.isBlank()) {
                    out.add(value.trim());
                }
            }
        }
        return Set.copyOf(out);
    }

    private static Set<String> normalizeTerms(Collection<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String value : raw) {
                if (value != null && !value.isBlank()) {
                    out.add(value.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return Set.copyOf(out);
    }

    private static void addAll(Set<String> out, Collection<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                out.add(value.trim());
            }
        }
    }

    private static Set<String> mutableCopy(Collection<String> values) {
        Set<String> out = new LinkedHashSet<>();
        addAll(out, values);
        return out;
    }

    private static String sanitize(String id) {
        return id.replaceAll("[^A-Za-z0-9]+", "_");
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
