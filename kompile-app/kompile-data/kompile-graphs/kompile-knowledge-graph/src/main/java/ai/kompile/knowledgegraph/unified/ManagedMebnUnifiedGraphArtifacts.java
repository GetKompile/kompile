/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.knowledgegraph.unified;

import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.mebn.RelationalMTheoryArtifactCodec;
import ai.kompile.graph.reasoning.mebn.RelationalMTheoryBuilder;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.persistence.MebnWeightPersistenceAdapter;
import ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator;
import ai.kompile.knowledgegraph.reasoning.MebnTheoryRegistrationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Portable managed MEBN theory and learned-strength lifecycle for one fact sheet. */
@Service
public class ManagedMebnUnifiedGraphArtifacts
        implements UnifiedGraphArtifactContributor, UnifiedGraphArtifactImporter {

    public static final String THEORY_ARTIFACT = "reasoning/mebn-theory.v1.json";

    @Autowired(required = false)
    private IncrementalReasoningOrchestrator orchestrator;
    @Autowired(required = false)
    private MebnTheoryRegistrationService registrationService;
    @Autowired(required = false)
    private MebnWeightPersistenceAdapter weights;

    @Override
    public void contribute(Long factSheetId, UnifiedGraph graph) {
        if (factSheetId == null || graph == null || orchestrator == null) return;
        MTheory theory = orchestrator.registeredMTheory(factSheetId).orElse(null);
        try {
            String json;
            if (theory != null) {
                json = RelationalMTheoryArtifactCodec.toJson(theory);
            } else if (weights != null) {
                json = weights.readTheoryArtifact(factSheetId).orElse(null);
                if (json == null || json.isBlank()) return;
                theory = RelationalMTheoryArtifactCodec.fromJson(json);
            } else {
                return;
            }
            try {
                validateEntityReferences(theory, graph);
            } catch (IllegalArgumentException incompleteSubset) {
                if (Boolean.TRUE.equals(graph.meta().get("namedGraphSubset"))) return;
                throw incompleteSubset;
            }
            graph.putArtifactText(THEORY_ARTIFACT, json);
        } catch (IOException e) {
            throw new IllegalStateException("Could not export managed MEBN theory", e);
        }
    }

    @Override
    public void validateArtifacts(Long factSheetId, UnifiedGraph graph) {
        if (factSheetId == null || graph == null) return;
        String json = graph.artifactText(THEORY_ARTIFACT);
        if (json == null || json.isBlank()) return;
        validateEntityReferences(RelationalMTheoryArtifactCodec.fromJson(json), graph);
    }

    @Override
    public int importArtifacts(Long factSheetId, UnifiedGraph graph) {
        if (factSheetId == null || graph == null || orchestrator == null) return 0;
        String json = graph.artifactText(THEORY_ARTIFACT);
        if (json == null || json.isBlank()) {
            orchestrator.unregisterMTheory(factSheetId);
            orchestrator.registerReasoningGraph(factSheetId, graph);
            if (weights != null) {
                try {
                    weights.clearTheoryArtifacts(factSheetId);
                } catch (IOException e) {
                    throw new IllegalStateException("Could not clear stale MEBN artifacts", e);
                }
            }
            return 0;
        }
        MTheory theory = RelationalMTheoryArtifactCodec.fromJson(json);
        validateEntityReferences(theory, graph);
        try {
            if (weights != null) {
                weights.persist(factSheetId, theory);
                weights.persistTheoryArtifact(factSheetId, json);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist imported MEBN theory", e);
        }
        orchestrator.registerMTheory(factSheetId, theory, graph);
        return 1;
    }

    @Override
    public boolean supportsExactRollback() {
        return true;
    }

    @Override
    public Set<String> managedArtifactPrefixes() {
        return Set.of(THEORY_ARTIFACT);
    }

    public Optional<MTheory> theoryForFactSheet(long factSheetId) {
        if (orchestrator != null) {
            Optional<MTheory> current = orchestrator.registeredMTheory(factSheetId);
            if (current.isPresent()) return current;
        }
        if (weights != null) {
            try {
                Optional<String> stored = weights.readTheoryArtifact(factSheetId);
                if (stored.isPresent()) {
                    MTheory theory = RelationalMTheoryArtifactCodec.fromJson(stored.get());
                    weights.load(factSheetId, theory);
                    if (orchestrator != null) {
                        orchestrator.reasoningGraph(factSheetId)
                                .ifPresent(graph -> orchestrator.registerMTheory(factSheetId, theory, graph));
                    }
                    return Optional.of(theory);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Could not read managed MEBN theory", e);
            }
        }
        if (registrationService != null && orchestrator != null) {
            registrationService.registerMTheoryForFactSheet(factSheetId);
            Optional<MTheory> rebuilt = orchestrator.registeredMTheory(factSheetId);
            if (rebuilt.isPresent() && weights != null) {
                try {
                    weights.load(factSheetId, rebuilt.get());
                } catch (IOException e) {
                    throw new IllegalStateException("Could not apply managed MEBN weights", e);
                }
            }
            return rebuilt;
        }
        return Optional.empty();
    }

    private static void validateEntityReferences(MTheory theory, UnifiedGraph graph) {
        Set<String> graphIds = new HashSet<>();
        graph.entities().forEach(entity -> graphIds.add(entity.id()));
        theory.getEntityTypes().forEach(type -> type.getEntityIds().forEach(id -> {
            if (!graphIds.contains(id)) {
                throw new IllegalArgumentException("MEBN artifact references missing entity: " + id);
            }
            if (!RelationalMTheoryBuilder.ALL_NODES_TYPE.equals(type.getTypeName())
                    && graph.entity(id).filter(entity -> hasType(entity, type.getTypeName())).isEmpty()) {
                throw new IllegalArgumentException("MEBN entity type mismatch for " + id
                        + ": expected " + type.getTypeName());
            }
        }));
    }

    private static boolean hasType(ai.kompile.graph.reasoning.model.GraphEntity entity,
                                   String expectedType) {
        if (entity.hasTypeMembership(expectedType)) return true;
        Object storeValue = entity.attributes().get("kompile.store");
        return storeValue instanceof java.util.Map<?, ?> store
                && expectedType.equalsIgnoreCase(String.valueOf(store.get("nodeType")));
    }
}
