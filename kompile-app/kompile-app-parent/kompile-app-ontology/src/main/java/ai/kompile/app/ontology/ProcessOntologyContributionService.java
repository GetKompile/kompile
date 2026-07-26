/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package ai.kompile.app.ontology;

import ai.kompile.knowledgegraph.staging.ModelTrainedEvent;
import ai.kompile.process.ontology.Cardinality;
import ai.kompile.process.ontology.OntologySchema;
import ai.kompile.process.ontology.RelationshipTypeDefinition;
import ai.kompile.process.service.ProcessEngineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Contributes the business-process relations the miner discovers into the fact sheet's governing
 * ontology, mirroring {@link OntologyTypeInductionService}'s update-and-rebind pattern:
 *
 * <ul>
 *   <li>{@code PRECEDES} — declared <b>transitive</b>, so OWL-RL enrichment computes the
 *       precedence closure over the materialized instance edges (the OWL-side complement of the
 *       PSL transitivity rule in {@code ProcessEntailment}), and
 *       {@code OntologicalConstraintBuilder}/{@code owlDerivedPslRules} see process control flow
 *       as a first-class object property;</li>
 *   <li>{@code DIRECTLY_FOLLOWS} — the observed instance-level flow, deliberately NOT transitive
 *       (adjacency is not order).</li>
 * </ul>
 *
 * <p>Activity classes need no contribution here: activities project from {@code entity_type}
 * values, which the structural derivation and post-OWL type induction already promote to ontology
 * entity types.</p>
 *
 * <p>Triggered by the {@code ModelTrainedEvent(psl-mined)} the miner publishes after persisting
 * its rules — i.e. exactly when a process was actually mined. Idempotent: an ontology that already
 * declares both relations is left untouched; a fact sheet with no ontology gets the structural
 * auto-provision first (which is itself get-or-create).</p>
 */
@Service
public class ProcessOntologyContributionService {

    private static final Logger log = LoggerFactory.getLogger(ProcessOntologyContributionService.class);

    static final String PRECEDES = "PRECEDES";
    static final String DIRECTLY_FOLLOWS = "DIRECTLY_FOLLOWS";

    private final GraphOntologyBindingService bindingService;
    private final ProcessEngineService processEngineService;

    /** Optional: resolves ALL labeled relations after the process relations are contributed. */
    @Autowired(required = false)
    private RelationSchemaResolutionService relationSchemaResolutionService;

    public ProcessOntologyContributionService(GraphOntologyBindingService bindingService,
                                              ProcessEngineService processEngineService) {
        this.bindingService = bindingService;
        this.processEngineService = processEngineService;
    }

    void setRelationSchemaResolutionService(RelationSchemaResolutionService service) {
        this.relationSchemaResolutionService = service;
    }

    /** The miner publishes {@code ModelTrainedEvent(psl, factSheetId, rules, "psl-mined")} per run. */
    @Async
    @EventListener
    public void onProcessMined(ModelTrainedEvent event) {
        if (!"psl-mined".equals(event.getBaseModelId())) {
            return;
        }
        contributeProcessRelations(event.getFactSheetId());
        // Sequential (same async handler — never a second listener racing this one's rebind):
        // resolve every labeled relation, including the freshly materialized control-flow edges,
        // into domain/range/cardinality + richer metadata for the OWL/MEBN layers.
        if (relationSchemaResolutionService != null) {
            relationSchemaResolutionService.resolveRelationSchema(event.getFactSheetId());
        }
    }

    /**
     * Ensure the fact sheet's governing ontology declares the process control-flow relations.
     *
     * @return true when the ontology was updated (and rebound to the new version)
     */
    public boolean contributeProcessRelations(long factSheetId) {
        try {
            Optional<OntologySchema> active = bindingService.resolveActiveOntology(factSheetId);
            if (active.isEmpty()) {
                active = bindingService.autoProvisionStructuralOntology(factSheetId);
            }
            if (active.isEmpty()) {
                log.debug("Process ontology contribution: no ontology resolvable for factSheet={} — skipped",
                        factSheetId);
                return false;
            }
            OntologySchema schema = active.get();
            List<RelationshipTypeDefinition> relationships = schema.getRelationshipTypes() != null
                    ? new ArrayList<>(schema.getRelationshipTypes())
                    : new ArrayList<>();

            boolean changed = false;
            if (missing(relationships, PRECEDES)) {
                relationships.add(RelationshipTypeDefinition.builder()
                        .type(PRECEDES)
                        .transitive(true)
                        .cardinality(Cardinality.MANY_TO_MANY)
                        .description("Entailed business-process control flow: the source activity "
                                + "precedes the target. Transitive — OWL-RL computes the precedence "
                                + "closure over materialized process edges.")
                        .build());
                changed = true;
            }
            if (missing(relationships, DIRECTLY_FOLLOWS)) {
                relationships.add(RelationshipTypeDefinition.builder()
                        .type(DIRECTLY_FOLLOWS)
                        .cardinality(Cardinality.MANY_TO_MANY)
                        .description("Observed business-process control flow at instance level: the "
                                + "target event directly followed the source in a mined trace. Not "
                                + "transitive — adjacency is not order.")
                        .build());
                changed = true;
            }
            if (!changed) {
                return false;
            }

            schema.setRelationshipTypes(relationships);
            Map<String, Object> metadata = schema.getMetadata() != null
                    ? new LinkedHashMap<>(schema.getMetadata()) : new LinkedHashMap<>();
            metadata.put("processRelationsContributedAt", Instant.now().toString());
            schema.setMetadata(metadata);
            schema.setUpdatedBy("process-mining-contribution");

            OntologySchema updated = processEngineService.updateOntology(schema.getId(), schema);
            bindingService.bindOntology(factSheetId, updated.getId(), updated.getVersion());
            log.info("Process ontology contribution: added {}/{} to ontology {} (now v{}) for factSheet={}",
                    PRECEDES, DIRECTLY_FOLLOWS, updated.getId(), updated.getVersion(), factSheetId);
            return true;
        } catch (Exception e) {
            log.warn("Process ontology contribution failed for factSheet={} — {}", factSheetId, e.getMessage());
            return false;
        }
    }

    private static boolean missing(List<RelationshipTypeDefinition> relationships, String type) {
        return relationships.stream().noneMatch(r -> type.equalsIgnoreCase(r.getType()));
    }
}
