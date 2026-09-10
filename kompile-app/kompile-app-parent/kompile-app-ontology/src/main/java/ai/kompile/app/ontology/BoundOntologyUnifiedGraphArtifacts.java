/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.app.ontology;

import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.unified.UnifiedGraphArtifactContributor;
import ai.kompile.knowledgegraph.unified.UnifiedGraphArtifactImporter;
import ai.kompile.process.ontology.OntologySchema;
import ai.kompile.process.service.ProcessEngineService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bundles and restores the exact ontology schema governing a fact-sheet graph. */
@Component
public class BoundOntologyUnifiedGraphArtifacts
        implements UnifiedGraphArtifactContributor, UnifiedGraphArtifactImporter {

    public static final String ARTIFACT = "schema/bound-ontology.json";
    private static final String FORMAT = "kompile-bound-ontology";
    private static final int VERSION = 1;
    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private final GraphOntologyBindingService bindings;
    private final ProcessEngineService processEngine;

    public BoundOntologyUnifiedGraphArtifacts(GraphOntologyBindingService bindings,
                                              ProcessEngineService processEngine) {
        this.bindings = bindings;
        this.processEngine = processEngine;
    }

    @Override
    public void contribute(Long factSheetId, UnifiedGraph graph) {
        if (factSheetId == null || graph == null) return;
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("format", FORMAT);
        envelope.put("formatVersion", VERSION);
        bindings.resolveActiveOntology(factSheetId)
                .ifPresentOrElse(schema -> envelope.set("ontology", MAPPER.valueToTree(schema)),
                        () -> envelope.putNull("ontology"));
        graph.putArtifactText(ARTIFACT, write(envelope));
    }

    @Override
    public void validateArtifacts(Long factSheetId, UnifiedGraph graph) {
        decodeOntology(graph);
    }

    @Override
    public int importArtifacts(Long factSheetId, UnifiedGraph graph) {
        Optional<OntologySchema> decoded = decodeOntology(graph);
        if (decoded.isEmpty()) return 0;
        OntologySchema schema = decoded.get();
        processEngine.restoreOntologySchema(schema);
        if (factSheetId != null) {
            bindings.bindOntology(factSheetId, schema.getId(), schema.getVersion());
        }
        return 1;
    }

    @Override
    public boolean supportsExactRollback() {
        return true;
    }

    @Override
    public java.util.Set<String> managedArtifactPrefixes() {
        return java.util.Set.of(ARTIFACT);
    }

    @Override
    public PreparedImport prepareArtifacts(Long factSheetId, UnifiedGraph incoming, UnifiedGraph previous) {
        validateArtifacts(factSheetId, incoming);
        Optional<OntologySchema> incomingSchema = decodeOntology(incoming);
        Optional<OntologySchema> previousBinding = factSheetId == null
                ? Optional.empty() : bindings.resolveActiveOntology(factSheetId);
        boolean incomingExisted = incomingSchema.flatMap(schema -> existingOntology(schema)).isPresent();
        if (incomingSchema.isPresent() && !incomingExisted
                && !processEngine.supportsOntologySnapshotRemoval()) {
            throw new IllegalStateException("Process engine does not support ontology snapshot rollback");
        }
        AtomicBoolean rolledBack = new AtomicBoolean();
        return new PreparedImport() {
            @Override
            public int commit() {
                return importArtifacts(factSheetId, incoming);
            }

            @Override
            public synchronized void rollback() {
                if (rolledBack.get()) return;
                if (previousBinding.isPresent()) {
                    OntologySchema previous = previousBinding.get();
                    processEngine.restoreOntologySchema(previous);
                    bindings.bindOntology(factSheetId, previous.getId(), previous.getVersion());
                } else if (factSheetId != null) {
                    bindings.unbindOntology(factSheetId);
                }
                if (incomingSchema.isPresent() && !incomingExisted) {
                    OntologySchema added = incomingSchema.get();
                    processEngine.removeOntologySchemaSnapshot(added.getId(), added.getVersion());
                }
                rolledBack.set(true);
            }
        };
    }

    private Optional<OntologySchema> existingOntology(OntologySchema schema) {
        try {
            return Optional.ofNullable(processEngine.getOntology(schema.getId(), schema.getVersion()));
        } catch (IllegalArgumentException notFound) {
            return Optional.empty();
        }
    }

    private static Optional<OntologySchema> decodeOntology(UnifiedGraph graph) {
        if (graph == null) return Optional.empty();
        String json = graph.artifactText(ARTIFACT);
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            JsonNode envelope = MAPPER.readTree(json);
            if (!FORMAT.equals(envelope.path("format").asText())
                    || envelope.path("formatVersion").asInt(-1) != VERSION) {
                throw new IllegalArgumentException("Unsupported bound ontology artifact");
            }
            JsonNode ontologyNode = envelope.get("ontology");
            if (ontologyNode == null || ontologyNode.isNull()) return Optional.empty();
            OntologySchema schema = MAPPER.treeToValue(ontologyNode, OntologySchema.class);
            if (schema.getId() == null
                    || !schema.getId().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")
                    || schema.getId().contains("..") || schema.getVersion() < 1) {
                throw new IllegalArgumentException("Invalid ontology snapshot identity");
            }
            return Optional.of(schema);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not decode bound ontology artifact", e);
        }
    }

    private static String write(JsonNode value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize bound ontology artifact", e);
        }
    }
}
