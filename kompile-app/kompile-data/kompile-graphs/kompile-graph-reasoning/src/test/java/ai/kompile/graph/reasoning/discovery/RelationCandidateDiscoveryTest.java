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
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelationCandidateDiscoveryTest {

    @Test
    void discoversTypedRelationCandidatesFromGraphOnlySignals() {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(GraphEntity.builder("control:c04")
                .type("CONTROL_ASSERTION")
                .label("C-04 SKU Mapping")
                .confidence(0.90)
                .build());
        graph.addEntity(GraphEntity.builder("step:validate")
                .type("CLOSE_STEP")
                .label("Validate forecast workbook and triage variances")
                .confidence(0.90)
                .build());
        graph.addEntity(GraphEntity.builder("step:intake")
                .type("CLOSE_STEP")
                .label("Receive regional forecast workbooks")
                .confidence(0.90)
                .build());

        RelationProfile profile = RelationProfile.builder("VALIDATES")
                .sourceTypes("CONTROL_ASSERTION")
                .targetTypes("CLOSE_STEP")
                .relationTerms("validate")
                .boost("sku-workbook-validation", 0.80,
                        List.of("sku", "mapping"), List.of("workbook", "validate"))
                .penalizeTarget("generic-intake", 0.40, List.of("receive", "intake"))
                .weights(0.0, 0.0, 1.0, 0.0)
                .minScore(0.50)
                .topKPerSource(1)
                .build();

        List<Candidate> candidates = RelationCandidateDiscovery.discover(graph, profile);

        assertEquals(1, candidates.size());
        Candidate candidate = candidates.get(0);
        assertEquals("VALIDATES", candidate.relationType());
        assertEquals("control:c04", candidate.sourceId());
        assertEquals("step:validate", candidate.targetId());
        assertTrue(candidate.matchedRules().contains("sku-workbook-validation"));

        GraphRelation candidateRelation = RelationCandidateDiscovery.candidateRelation(candidate);
        assertEquals("CANDIDATE_VALIDATES", candidateRelation.type());
        assertEquals(candidate.sourceId(), candidateRelation.sourceId());
        assertEquals(candidate.targetId(), candidateRelation.targetId());
        assertEquals(candidate.score(), candidateRelation.confidence(), 1.0e-9);
    }

    @Test
    void mebnEvaluatorScoresCandidatesAndWritesPosteriorArtifact() {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(GraphEntity.builder("control:c04")
                .type("CONTROL_ASSERTION")
                .label("C-04 SKU Mapping")
                .confidence(0.90)
                .build());
        graph.addEntity(GraphEntity.builder("step:validate")
                .type("CLOSE_STEP")
                .label("Validate forecast workbook")
                .confidence(0.90)
                .build());
        RelationProfile profile = RelationProfile.builder("VALIDATES")
                .sourceTypes("CONTROL_ASSERTION")
                .targetTypes("CLOSE_STEP")
                .boost("sku-workbook-validation", 0.80,
                        List.of("sku", "mapping"), List.of("workbook", "validate"))
                .weights(0.0, 0.0, 1.0, 0.0)
                .minScore(0.50)
                .build();

        RelationCandidateMebnEvaluator.Result result = RelationCandidateMebnEvaluator.evaluate(graph, profile);
        result.putArtifact(graph);

        assertEquals(1, result.candidates().size());
        RelationCandidateMebnEvaluator.CandidatePosterior posterior = result.candidates().get(0);
        assertEquals("VALIDATES", posterior.candidate().relationType());
        assertEquals("control:c04", posterior.candidate().sourceId());
        assertEquals("step:validate", posterior.candidate().targetId());
        assertTrue(posterior.posterior() >= 0.0 && posterior.posterior() <= 1.0);
        assertTrue(graph.artifactText(RelationCandidateMebnEvaluator.DEFAULT_EVIDENCE_ARTIFACT)
                .contains("control:c04"));
    }

    @Test
    void profileConfigRoundTripsThroughUnifiedGraphArtifact() {
        RelationProfile profile = RelationProfile.builder("APPROVES")
                .sourceTypes("PERSON")
                .targetTypes("PROCESS_STEP")
                .relationTerms("approve", "signoff")
                .boost("approver-to-approval-step", 0.70,
                        List.of("approver", "owner"), List.of("approve", "signoff"))
                .weights(0.1, 0.2, 0.6, 0.1)
                .minScore(0.42)
                .topKPerSource(2)
                .skipSourcesWithExistingRelation(true)
                .build();
        UnifiedGraph graph = new UnifiedGraph();

        new RelationProfileConfig(List.of(profile)).putArtifact(graph);
        RelationProfile loaded = RelationProfileConfig.fromArtifact(graph).profile("APPROVES").orElseThrow();

        assertEquals("APPROVES", loaded.relationType());
        assertEquals(profile.sourceTypes(), loaded.sourceTypes());
        assertEquals(profile.targetTypes(), loaded.targetTypes());
        assertEquals(profile.relationTerms(), loaded.relationTerms());
        assertEquals(profile.minScore(), loaded.minScore(), 1.0e-9);
        assertEquals(profile.topKPerSource(), loaded.topKPerSource());
        assertTrue(loaded.skipSourcesWithExistingRelation());
        assertEquals(1, loaded.termRules().size());
        assertEquals("approver-to-approval-step", loaded.termRules().get(0).name());
    }

    @Test
    void relationSchemaConfigRoundTripsThroughUnifiedGraphArtifact() {
        RelationNormalizer.RelationSchema schema = RelationNormalizer.RelationSchema
                .of("APPROVED_BY", List.of("APPROVAL_ROLE"), List.of("CLOSE_STEP"), "APPROVES", "APPROVED_BY");
        UnifiedGraph graph = new UnifiedGraph();

        new RelationSchemaConfig(List.of(schema)).putArtifact(graph);
        RelationNormalizer.RelationSchema loaded = RelationSchemaConfig.fromArtifact(graph)
                .schema("APPROVED_BY")
                .orElseThrow();

        assertEquals("APPROVED_BY", loaded.canonicalType());
        assertEquals(schema.sourceTypes(), loaded.sourceTypes());
        assertEquals(schema.targetTypes(), loaded.targetTypes());
        assertTrue(loaded.observedTypes().contains("APPROVES"));
        assertTrue(loaded.flipWhenSwapped());
    }

    @Test
    void relationSchemaConfigMaterializesNormalizedRelationsFromGraphArtifact() {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(GraphEntity.builder("step:signoff")
                .type("CLOSE_STEP")
                .label("Controller and CFO sign-off")
                .build());
        graph.addEntity(GraphEntity.builder("role:cfo")
                .type("APPROVAL_ROLE")
                .label("CFO approval role")
                .build());
        graph.addRelation(GraphRelation.builder("r-swapped", "step:signoff", "role:cfo")
                .type("APPROVED_BY")
                .confidence(0.80)
                .build());
        new RelationSchemaConfig(List.of(RelationNormalizer.RelationSchema.of("APPROVED_BY",
                List.of("APPROVAL_ROLE"), List.of("CLOSE_STEP"), "APPROVED_BY"))).putArtifact(graph);

        RelationNormalizer.Result result = RelationSchemaConfig.fromArtifact(graph).materialize(graph);

        assertEquals(1, result.normalizations().size());
        assertTrue(graph.relations().stream().anyMatch(r -> "APPROVED_BY".equals(r.type())
                && "role:cfo".equals(r.sourceId())
                && "step:signoff".equals(r.targetId())));
    }

    @Test
    void normalizerFlipsSwappedRelationUsingEndpointTypes() {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(GraphEntity.builder("step:signoff")
                .type("CLOSE_STEP")
                .label("Controller and CFO sign-off")
                .build());
        graph.addEntity(GraphEntity.builder("role:cfo")
                .type("APPROVAL_ROLE")
                .label("CFO approval role")
                .build());
        graph.addRelation(GraphRelation.builder("r-swapped", "step:signoff", "role:cfo")
                .type("APPROVED_BY")
                .confidence(0.80)
                .attribute("routingPolicy", "requires CFO approval")
                .build());

        RelationNormalizer.Result result = RelationNormalizer.normalize(graph, List.of(
                RelationNormalizer.RelationSchema.of("APPROVED_BY",
                        List.of("APPROVAL_ROLE"), List.of("CLOSE_STEP"), "APPROVED_BY")));

        assertEquals(1, result.normalizations().size());
        GraphRelation normalized = result.relations().get(0);
        assertEquals("APPROVED_BY", normalized.type());
        assertEquals("role:cfo", normalized.sourceId());
        assertEquals("step:signoff", normalized.targetId());
        assertEquals(0.80, normalized.confidence(), 1.0e-9);
        assertEquals("FLIPPED_DIRECTION", normalized.attributes().get("normalizationAction"));
        assertEquals("requires CFO approval", normalized.attributes().get("routingPolicy"));
        assertTrue(normalized.tags().contains("relation-normalization"));
    }

    @Test
    void normalizerDoesNotEmitDuplicateCanonicalRelation() {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(GraphEntity.builder("step:signoff")
                .type("CLOSE_STEP")
                .label("Controller and CFO sign-off")
                .build());
        graph.addEntity(GraphEntity.builder("role:cfo")
                .type("APPROVAL_ROLE")
                .label("CFO approval role")
                .build());
        graph.addRelation(GraphRelation.builder("r-swapped", "step:signoff", "role:cfo")
                .type("APPROVED_BY")
                .build());
        graph.addRelation(GraphRelation.builder("r-existing", "role:cfo", "step:signoff")
                .type("APPROVED_BY")
                .build());

        RelationNormalizer.Result result = RelationNormalizer.normalize(graph, List.of(
                RelationNormalizer.RelationSchema.of("APPROVED_BY",
                        List.of("APPROVAL_ROLE"), List.of("CLOSE_STEP"), "APPROVED_BY")));

        assertEquals(0, result.normalizations().size());
    }
}
