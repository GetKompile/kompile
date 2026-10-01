/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.event.attribution.service;

import ai.kompile.graph.reasoning.domain.BayesianInferenceResult;
import ai.kompile.graph.reasoning.mebn.EntityType;
import ai.kompile.graph.reasoning.mebn.MFrag;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.mebn.RandomVariable;
import ai.kompile.graph.reasoning.mebn.type.TypeHierarchy;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MEBN evidence names grounded variables (posterior keys) and indexes their states. A name the
 * network lacks used to be dropped and an out-of-range state zeroed the joint, both silently; a
 * model then read unconditioned posteriors as its answer.
 */
class BayesianNetworkServiceMebnEvidenceTest {

    private final BayesianNetworkService service = new BayesianNetworkService(mock(KnowledgeGraphService.class));

    private static MTheory personTheory() {
        EntityType person = new EntityType("Person");
        person.addEntity("alice");
        person.addEntity("bob");
        MFrag frag = new MFrag("PersonRelevance");
        frag.addResidentNode(RandomVariable.unary("isRelevant", person, RandomVariable.NodeRole.RESIDENT));
        MTheory theory = new MTheory("evidenceTest");
        theory.addEntityType(person);
        theory.addMFrag(frag);
        return theory;
    }

    private String aliceVariable(MTheory theory) {
        return service.queryWithMTheory(theory, Map.of()).getPosteriors().keySet().stream()
                .filter(name -> name.contains("alice")).findFirst().orElseThrow();
    }

    @Test
    void evidenceOnAGroundedVariableIsApplied() {
        MTheory theory = personTheory();
        String alice = aliceVariable(theory);

        BayesianInferenceResult result = service.queryWithMTheory(theory, Map.of(alice, 0));

        assertEquals(0.0, result.getPosteriors().get(alice), 1e-12);
        assertEquals(Map.of(alice, 0), result.getEvidence());
    }

    @Test
    void anUnknownEvidenceVariableIsRejectedWithTheGroundedNames() {
        MTheory theory = personTheory();
        String alice = aliceVariable(theory);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.queryWithMTheory(theory, Map.of("isRelevant(carol)", 1)));

        assertTrue(error.getMessage().contains("isRelevant(carol)"), error.getMessage());
        assertTrue(error.getMessage().contains(alice), error.getMessage());
    }

    @Test
    void aStateOutsideTheVariableStatesIsRejected() {
        MTheory theory = personTheory();
        String alice = aliceVariable(theory);
        Map<String, Integer> missingState = new HashMap<>();
        missingState.put(alice, null);

        IllegalArgumentException outOfRange = assertThrows(IllegalArgumentException.class,
                () -> service.queryWithMTheory(theory, Map.of(alice, 2)));

        assertTrue(outOfRange.getMessage().contains("must be 0 or 1"), outOfRange.getMessage());
        assertThrows(IllegalArgumentException.class, () -> service.queryWithMTheory(theory, Map.of(alice, -1)));
        assertThrows(IllegalArgumentException.class, () -> service.queryWithMTheory(theory, missingState));
    }

    @Test
    void evidenceOnAnEmptyNetworkIsRejectedButAPlainQueryIsNot() {
        MTheory empty = new MTheory("empty");

        assertTrue(service.queryWithMTheory(empty, Map.of()).getPosteriors().isEmpty());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.queryWithMTheory(empty, Map.of("isRelevant(alice)", 1)));
        assertTrue(error.getMessage().contains("The network has no variables"), error.getMessage());
    }

    @Test
    void theAutoPathRejectsEvidenceWhenTheSeedsGroundNothing() {
        // The graph knows no seed, so the auto-built theory has no MFrags and no variables.
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.queryMebnFromKg(List.of("missing"), Map.of("isRelevant(missing)", 1), 2, 10));

        assertTrue(error.getMessage().contains("isRelevant(missing)"), error.getMessage());
        assertTrue(service.queryMebnFromKg(List.of("missing"), Map.of(), 2, 10).getPosteriors().isEmpty());
    }

    @Test
    void theAutoPathGroundsInsideTheRequestedFactSheet() {
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
        when(graph.getNode("seed")).thenReturn(Optional.of(GraphNode.builder().nodeId("seed")
                .nodeType(NodeLevel.ENTITY).title("Seed").factSheetId(42L).build()));
        AtomicReference<Long> inferenceScope = new AtomicReference<>();
        BayesianNetworkService scoped = new BayesianNetworkService(graph) {
            @Override
            public BayesianInferenceResult queryWithMTheory(MTheory theory, Map<String, Integer> evidence,
                                                            TypeHierarchy hierarchy, Long factSheetId) {
                inferenceScope.set(factSheetId);
                return BayesianInferenceResult.builder().computedAt(Instant.now()).build();
            }
        };

        scoped.queryMebnFromKg(List.of("seed"), Map.of(), 1, 5, null, 42L);

        assertEquals(42L, inferenceScope.get(),
                "the SSBN's node and edge predicates read the fact sheet the traversal was scoped to");
    }
}
