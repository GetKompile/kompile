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

import ai.kompile.graph.reasoning.discovery.RelationCandidateDiscovery.Candidate;
import ai.kompile.graph.reasoning.discovery.RelationCandidateDiscovery.RelationProfile;
import ai.kompile.graph.reasoning.fol.EntailmentEngine;
import ai.kompile.graph.reasoning.fol.EntailmentRecord;
import ai.kompile.graph.reasoning.fol.Finding;
import ai.kompile.graph.reasoning.fol.FindingStore;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.mebn.RelationalMTheoryBuilder;
import ai.kompile.graph.reasoning.mebn.RelationalMTheoryBuilder.RelationDescriptor;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.unified.MiniJson;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Runs graph-only relation candidate discovery and evaluates the candidates with MEBN soft evidence.
 *
 * <p>This class is the generic bridge between {@link RelationCandidateDiscovery} and
 * {@link RelationalMTheoryBuilder}: discovery creates bounded {@code CANDIDATE_<RELATION>} edges,
 * then MEBN scores the requested relation over those candidate pairs and any observed relation
 * findings already present in the graph.</p>
 */
public final class RelationCandidateMebnEvaluator {

    public static final String DEFAULT_EVIDENCE_ARTIFACT = "relation-candidate-posteriors.json";

    private RelationCandidateMebnEvaluator() {
    }

    public record CandidatePosterior(Candidate candidate,
                                     double posterior,
                                     List<String> supportingFindingKeys,
                                     List<String> activatedRules) {
        public CandidatePosterior {
            Objects.requireNonNull(candidate, "candidate");
            posterior = clamp(posterior);
            supportingFindingKeys = supportingFindingKeys == null ? List.of() : List.copyOf(supportingFindingKeys);
            activatedRules = activatedRules == null ? List.of() : List.copyOf(activatedRules);
        }
    }

    public record Result(RelationProfile profile,
                         List<CandidatePosterior> candidates,
                         List<EntailmentRecord> mebnRecords) {
        public Result {
            Objects.requireNonNull(profile, "profile");
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            mebnRecords = mebnRecords == null ? List.of() : List.copyOf(mebnRecords);
        }

        public List<CandidatePosterior> promoted(double posteriorThreshold, double scoreThreshold) {
            return candidates.stream()
                    .filter(c -> c.posterior() >= posteriorThreshold && c.candidate().score() >= scoreThreshold)
                    .toList();
        }

        public String toJson() {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("version", 1);
            root.put("relationType", profile.relationType());
            List<Object> rows = new ArrayList<>();
            for (CandidatePosterior posterior : candidates) {
                rows.add(toMap(posterior));
            }
            root.put("candidates", rows);
            root.put("mebnRecordCount", mebnRecords.size());
            return MiniJson.write(root);
        }

        public void putArtifact(UnifiedGraph graph) {
            putArtifact(graph, DEFAULT_EVIDENCE_ARTIFACT);
        }

        public void putArtifact(UnifiedGraph graph, String artifactName) {
            Objects.requireNonNull(graph, "graph");
            graph.putArtifactText(artifactName, toJson());
        }
    }

    public static Result evaluate(ReasoningGraph graph, RelationProfile profile) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(profile, "profile");
        String sourceType = singleType(profile.sourceTypes(), "sourceTypes");
        String targetType = singleType(profile.targetTypes(), "targetTypes");

        List<Candidate> candidates = RelationCandidateDiscovery.discover(graph, profile);
        UnifiedGraph augmented = UnifiedGraph.of(graph);
        for (Candidate candidate : candidates) {
            augmented.addRelation(RelationCandidateDiscovery.candidateRelation(candidate));
        }

        List<String> sourceIds = graph.entities().stream()
                .filter(e -> e.hasTypeMembership(sourceType))
                .map(GraphEntity::id)
                .sorted()
                .toList();
        List<String> targetIds = graph.entities().stream()
                .filter(e -> e.hasTypeMembership(targetType))
                .map(GraphEntity::id)
                .sorted()
                .toList();

        MTheory theory = RelationalMTheoryBuilder.build("RelationCandidateMebn:" + profile.relationType(),
                List.of(new RelationDescriptor(profile.relationType(), sourceType, targetType,
                        relationActivation(graph, profile.relationType()), sourceIds, targetIds)));

        FindingStore findings = new FindingStore();
        for (GraphRelation relation : graph.relations()) {
            if (profile.relationType().equalsIgnoreCase(relation.type())) {
                findings.assertFinding(Finding.hard(profile.relationType(),
                        List.of(relation.sourceId(), relation.targetId()), 1, relation.id()));
            }
        }
        for (Candidate candidate : candidates) {
            findings.assertFinding(Finding.soft(profile.relationType(),
                    List.of(candidate.sourceId(), candidate.targetId()),
                    new double[]{Math.max(0.05, 1.0 - candidate.score()), Math.max(0.05, candidate.score())},
                    "graph-only:relation-candidate"));
        }

        List<EntailmentRecord> records = EntailmentEngine.entailFromMebn(augmented, findings, theory);
        Map<String, EntailmentRecord> recordsByKey = new LinkedHashMap<>();
        for (EntailmentRecord record : records) {
            recordsByKey.merge(record.groundedRvOrAtomKey(), record,
                    (left, right) -> left.posterior() >= right.posterior() ? left : right);
        }

        List<CandidatePosterior> posteriors = new ArrayList<>();
        for (Candidate candidate : candidates) {
            EntailmentRecord record = recordsByKey.get(relationVariable(profile.relationType(),
                    candidate.sourceId(), candidate.targetId()));
            if (record == null) {
                posteriors.add(new CandidatePosterior(candidate, candidate.score(), List.of(), List.of()));
            } else {
                posteriors.add(new CandidatePosterior(candidate, record.posterior(),
                        record.supportingFindingKeys(), record.activatedRules()));
            }
        }
        posteriors.sort(Comparator.comparingDouble(CandidatePosterior::posterior).reversed()
                .thenComparing(p -> p.candidate().sourceId())
                .thenComparing(p -> p.candidate().targetId()));
        return new Result(profile, posteriors, records);
    }

    private static Map<String, Object> toMap(CandidatePosterior posterior) {
        Candidate c = posterior.candidate();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("relationType", c.relationType());
        out.put("sourceId", c.sourceId());
        out.put("sourceLabel", c.sourceLabel());
        out.put("targetId", c.targetId());
        out.put("targetLabel", c.targetLabel());
        out.put("semanticScore", c.semanticScore());
        out.put("lexicalScore", c.lexicalScore());
        out.put("termScore", c.termScore());
        out.put("priorScore", c.priorScore());
        out.put("score", c.score());
        out.put("posterior", posterior.posterior());
        out.put("matchedRules", new ArrayList<>(c.matchedRules()));
        out.put("supportingFindingKeys", new ArrayList<>(posterior.supportingFindingKeys()));
        out.put("activatedRules", new ArrayList<>(posterior.activatedRules()));
        return out;
    }

    private static String relationVariable(String rvName, String sourceId, String targetId) {
        return rvName + "(" + sourceId + "," + targetId + ")";
    }

    private static double relationActivation(ReasoningGraph graph, String relationType) {
        double sum = 0.0;
        int count = 0;
        for (GraphRelation relation : graph.relations()) {
            if (relationType.equalsIgnoreCase(relation.type())) {
                sum += (clamp(relation.weight()) + clamp(relation.confidence())) / 2.0;
                count++;
            }
        }
        return count == 0 ? 0.50 : clamp(sum / count);
    }

    private static String singleType(Set<String> types, String field) {
        if (types == null || types.isEmpty()) {
            throw new IllegalArgumentException("MEBN relation candidate evaluation requires exactly one " + field);
        }
        Set<String> nonBlank = new LinkedHashSet<>();
        for (String type : types) {
            if (type != null && !type.isBlank()) {
                nonBlank.add(type);
            }
        }
        if (nonBlank.size() != 1) {
            throw new IllegalArgumentException("MEBN relation candidate evaluation requires exactly one "
                    + field + ", got " + nonBlank);
        }
        return nonBlank.iterator().next();
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
