/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GraphEntityTypeMembershipTest {

    @Test
    void typeMemberships_includeDeclaredAndDeterministicTypesButExcludeNeuralCandidates() {
        GraphEntity entity = GraphEntity.builder("n1")
                .type("Account")
                .attribute("additionalTypes", List.of("Customer"))
                .attribute("owlInferredTypes", List.of("AuditableEntity"))
                .attribute("ontology.typeCandidates", List.of(
                        Map.of("type", "NeuralGuess", "confidence", 0.99d, "source", "samediff"),
                        Map.of("type", "DeclaredCandidate", "declared", true),
                        Map.of("type", "OwlCandidate", "confidence", 1.0d, "source", "owl-rl")))
                .build();

        List<String> memberships = new ArrayList<>(entity.typeMemberships());

        assertEquals(List.of(
                "Account",
                "Customer",
                "AuditableEntity",
                "DeclaredCandidate",
                "OwlCandidate"), memberships);
        assertFalse(memberships.contains("NeuralGuess"),
                "SameDiff/neural scores must stay candidates, not crisp OWL ABox assertions");
    }

    @Test
    void typeMemberships_ignoreBareProbabilisticCandidateMaps() {
        GraphEntity entity = GraphEntity.builder("n1")
                .type("Account")
                .attribute("ontology.typeCandidates", Map.of(
                        "Customer", 0.8d,
                        "Supplier", Map.of("confidence", 0.9d, "source", "llm")))
                .build();

        assertEquals(List.of("Account"), new ArrayList<>(entity.typeMemberships()));
    }
}
