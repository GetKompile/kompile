/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.crawl.graph.passes;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.crawl.graph.GraphExtractionConfig.DecomposedPromptTier;
import ai.kompile.core.crawl.graph.GraphExtractionConfig.ExtractionTarget;
import ai.kompile.core.crawl.graph.GraphExtractionValidationPolicy;
import ai.kompile.core.embeddings.ScoredDocument;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.graphrag.GraphConstructor.ConceptHint;
import ai.kompile.core.graphrag.GraphConstructor.ExtractionTaskContext;
import ai.kompile.core.graphrag.ExtractorUtils;
import ai.kompile.core.graphrag.format.GraphExtractionSchema;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionMetadata;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.core.graphrag.format.GraphExtractionValidator;
import ai.kompile.core.graphrag.format.GraphExtractionValidator.ValidationResult;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.PropertyType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.core.graphrag.model.schema.SchemaHierarchyVocabulary;
import ai.kompile.crawl.graph.CrawlIndexTrackingCallback.CrawlCorpusPassage;
import ai.kompile.crawl.graph.CrawlIndexTrackingCallback.CrawlCorpusSnapshot;
import ai.kompile.crawl.graph.CrawlOntology;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.unified.GraphReasoningQueryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.document.Document;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Production extraction tools backed by the pooled crawl corpus and the reasoning-ready graph.
 */
public final class CrawlExtractionToolBackend implements ExtractionToolBackend {

    public static final String UNIFIED_CORPUS = "unified_corpus";
    public static final String GRAPH_REASONING_QUERY = "graph_reasoning_query";
    public static final String SUBMIT_GRAPH_DELTA = "submit_graph_delta";
    public static final String SUBMIT_ENTITIES = "submit_entities";
    public static final String SUBMIT_TYPED_ENTITIES = "submit_typed_entities";
    public static final String SUBMIT_RELATIONS = "submit_relations";
    public static final String UPDATE_ONTOLOGY = "update_ontology";

    private static final String COMPACT_SUBMIT_FORMAT_FIELD = "format";
    private static final String COMPACT_SUBMIT_FORMAT = "indexed";
    private static final int MAX_CORPUS_RESULTS = 8;
    private static final int MAX_CORPUS_PAGE_CHARS = 4_000;
    private static final int MAX_GRAPH_TOP_K = 20;
    private static final int MAX_GRAPH_DEPTH = 8;
    private static final int MAX_ENTITY_NAMES_PER_CALL = 32;
    private static final int MAX_ENTITY_NAME_CHARS = 160;
    private static final int MAX_GRAPH_ID_CHARS = 160;
    private static final int MAX_GRAPH_TYPE_CHARS = 160;
    private static final int MAX_DISCOVERY_TYPE_CHARS = 32;
    private static final int MAX_GRAPH_TEXT_CHARS = 512;
    private static final int MAX_COMPACT_TYPE_GUIDE_CHARS = 512;
    private static final int MAX_GRAPH_TIME_CHARS = 128;
    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper();

    private final String chunkId;
    private final String documentId;
    private final String model;
    private final String graphId;
    private final String parentGraphId;
    private final GraphExtractionValidationPolicy policy;
    private final CrawlOntology ontology;
    private final CrawlCorpusSnapshot corpus;
    private final Map<String, CrawlCorpusPassage> passagesById;
    private final int incompletePassagesExcluded;
    private final VectorStore vectorStore;
    private final String vectorStoreInitializationError;
    private final Supplier<UnifiedGraph> graphSupplier;
    private final GraphReasoningQueryService reasoningService;
    private volatile ExtractionTarget extractionTarget;
    private volatile List<ExtractedEntity> fixedPhaseEntities = List.of();
    private final boolean ontologyUpdatesAllowed;
    private final Set<String> retrievedEvidence = new LinkedHashSet<>();

    private volatile ExtractionResult accepted;
    private volatile ExtractionTaskContext activeTaskContext;
    private volatile Integer compactEntityMinItems;
    private volatile Integer compactEntityMaxItems;
    private volatile Integer compactRelationMinItems;
    private volatile Integer compactRelationMaxItems;
    private volatile Integer compactEntityNameMaxLength;
    private volatile List<String> compactEntityTypeOrder = List.of();
    private volatile List<String> compactRelationTypeOrder = List.of();
    private volatile List<Map<String, String>> compactEntityCandidates = List.of();
    private volatile List<CompactRelationCandidate> compactRelationCandidates = List.of();
    private volatile Set<String> strictEntityTypes = Set.of();
    private volatile Set<String> strictRelationTypes = Set.of();

    private record CompactRelationCandidate(int source, int target, String proposedType) {
    }

    public CrawlExtractionToolBackend(
            String chunkId,
            String documentId,
            String model,
            String graphId,
            String parentGraphId,
            GraphExtractionValidationPolicy policy,
            GraphSchema schema,
            CrawlCorpusSnapshot corpus,
            VectorStore vectorStore,
            Supplier<UnifiedGraph> graphSupplier,
            GraphReasoningQueryService reasoningService) {
        this(chunkId, documentId, model, graphId, parentGraphId, policy, schema, corpus,
                vectorStore, null, graphSupplier, reasoningService, ExtractionTarget.FULL_GRAPH);
    }

    public CrawlExtractionToolBackend(
            String chunkId,
            String documentId,
            String model,
            String graphId,
            String parentGraphId,
            GraphExtractionValidationPolicy policy,
            GraphSchema schema,
            CrawlCorpusSnapshot corpus,
            VectorStore vectorStore,
            String vectorStoreInitializationError,
            Supplier<UnifiedGraph> graphSupplier,
            GraphReasoningQueryService reasoningService) {
        this(chunkId, documentId, model, graphId, parentGraphId, policy, schema, corpus,
                vectorStore, vectorStoreInitializationError, graphSupplier, reasoningService,
                ExtractionTarget.FULL_GRAPH);
    }

    public CrawlExtractionToolBackend(
            String chunkId,
            String documentId,
            String model,
            String graphId,
            String parentGraphId,
            GraphExtractionValidationPolicy policy,
            GraphSchema schema,
            CrawlCorpusSnapshot corpus,
            VectorStore vectorStore,
            String vectorStoreInitializationError,
            Supplier<UnifiedGraph> graphSupplier,
            GraphReasoningQueryService reasoningService,
            ExtractionTarget extractionTarget) {
        this(chunkId, documentId, model, graphId, parentGraphId, policy, schema, corpus,
                vectorStore, vectorStoreInitializationError, graphSupplier, reasoningService,
                extractionTarget, null);
    }

    public CrawlExtractionToolBackend(
            String chunkId,
            String documentId,
            String model,
            String graphId,
            String parentGraphId,
            GraphExtractionValidationPolicy policy,
            GraphSchema schema,
            CrawlCorpusSnapshot corpus,
            VectorStore vectorStore,
            String vectorStoreInitializationError,
            Supplier<UnifiedGraph> graphSupplier,
            GraphReasoningQueryService reasoningService,
            ExtractionTarget extractionTarget,
            CrawlOntology crawlOntology) {
        this(chunkId, documentId, model, graphId, parentGraphId, policy, schema, corpus,
                vectorStore, vectorStoreInitializationError, graphSupplier, reasoningService,
                extractionTarget, crawlOntology, true);
    }

    public CrawlExtractionToolBackend(
            String chunkId,
            String documentId,
            String model,
            String graphId,
            String parentGraphId,
            GraphExtractionValidationPolicy policy,
            GraphSchema schema,
            CrawlCorpusSnapshot corpus,
            VectorStore vectorStore,
            String vectorStoreInitializationError,
            Supplier<UnifiedGraph> graphSupplier,
            GraphReasoningQueryService reasoningService,
            ExtractionTarget extractionTarget,
            CrawlOntology crawlOntology,
            boolean ontologyUpdatesAllowed) {
        this.chunkId = chunkId;
        this.documentId = documentId;
        this.model = model;
        this.graphId = graphId;
        this.parentGraphId = parentGraphId;
        this.policy = policy == null ? GraphExtractionValidationPolicy.defaults() : policy;
        this.ontology = crawlOntology == null ? new CrawlOntology(schema) : crawlOntology;
        this.vectorStore = vectorStore;
        this.vectorStoreInitializationError = vectorStoreInitializationError;
        this.graphSupplier = graphSupplier;
        this.reasoningService = reasoningService;
        this.extractionTarget = extractionTarget == null
                ? ExtractionTarget.FULL_GRAPH : extractionTarget;
        this.ontologyUpdatesAllowed = ontologyUpdatesAllowed;

        Map<String, CrawlCorpusPassage> complete = new LinkedHashMap<>();
        int incomplete = 0;
        if (corpus != null) {
            for (CrawlCorpusPassage passage : corpus.passages()) {
                if (passage == null || passage.chunkId() == null || passage.chunkId().isBlank()) {
                    continue;
                }
                if (!passage.completeText() || passage.content() == null) {
                    incomplete++;
                    continue;
                }
                complete.put(passage.chunkId(), passage);
            }
        }
        this.incompletePassagesExcluded = incomplete;
        this.passagesById = Map.copyOf(complete);
        this.corpus = new CrawlCorpusSnapshot(
                corpus == null ? null : corpus.snapshotId(),
                new ArrayList<>(complete.values()));
    }

    /**
     * Opt-in native-schema cardinality ceilings supplied by a deterministic source inventory.
     * The model still chooses all names, ontology types, endpoints, and relation types.
     */
    public void configureCompactProposalBounds(int entityMaxItems, int relationMaxItems) {
        this.compactEntityMaxItems = Math.max(0, entityMaxItems);
        this.compactRelationMaxItems = Math.max(0, relationMaxItems);
    }

    /**
     * Opt-in exact cardinalities from deterministic source assertion analysis. Only counts are
     * constrained unless source-derived entity candidates have also been configured; candidate
     * names are then immutable positional boundaries while the model still selects ontology types,
     * endpoints, and relations.
     */
    public void configureCompactProposalCardinality(int entityItems, int relationItems) {
        int entityCount = Math.max(0, entityItems);
        if (!compactEntityCandidates.isEmpty() && compactEntityCandidates.size() != entityCount) {
            throw new IllegalArgumentException(
                    "Compact entity candidate count must match the configured entity cardinality");
        }
        int relationCount = Math.max(0, relationItems);
        if (!compactRelationCandidates.isEmpty()
                && compactRelationCandidates.size() != relationCount) {
            throw new IllegalArgumentException(
                    "Compact relation candidate count must match the configured relation cardinality");
        }
        this.compactEntityMinItems = entityCount;
        this.compactEntityMaxItems = entityCount;
        this.compactRelationMinItems = relationCount;
        this.compactRelationMaxItems = relationCount;
    }

    /**
     * Source-derived presentation bounds for a compact proposal. These values never add ontology
     * labels or fix facts: preferred labels are intersected with the authoritative ontology, and
     * the name bound carries only a length rather than any source text.
     */
    public void configureCompactProposalGuidance(
            int entityNameMaxLength,
            List<String> entityTypeOrder,
            List<String> relationTypeOrder) {
        this.compactEntityNameMaxLength = Math.max(
                1, Math.min(MAX_ENTITY_NAME_CHARS, entityNameMaxLength));
        this.compactEntityTypeOrder = entityTypeOrder == null
                ? List.of() : List.copyOf(entityTypeOrder);
        this.compactRelationTypeOrder = relationTypeOrder == null
                ? List.of() : List.copyOf(relationTypeOrder);
    }

    /**
     * Restricts emitted extraction types for a caller-owned strict schema without removing the
     * baseline hierarchy from the ontology used for assignability and metadata projection.
     */
    public void configureStrictExtractionTypes(
            List<String> entityTypes, List<String> relationTypes) {
        this.strictEntityTypes = scopedTypes(entityTypes, schema().getAllNodeLabels());
        this.strictRelationTypes = scopedTypes(
                relationTypes, schema().getAllRelationshipTypes());
    }

    /**
     * Adds the exact name/type rows found by deterministic source assertion analysis. Candidate
     * names are immutable source boundaries and become positional native-schema slots. Proposed
     * types remain verification hints: the model must still select each type from the authoritative
     * ontology using the current text.
     */
    public void configureCompactEntityCandidates(List<String> names, List<String> types) {
        if (names == null || types == null || names.size() != types.size()) {
            throw new IllegalArgumentException("Compact entity candidate names and types must align");
        }
        if ((compactEntityMinItems != null && compactEntityMinItems != names.size())
                || (compactEntityMaxItems != null && compactEntityMaxItems != names.size())) {
            throw new IllegalArgumentException(
                    "Compact entity candidate count must match the configured entity cardinality");
        }
        List<Map<String, String>> candidates = new ArrayList<>(names.size());
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index);
            String type = types.get(index);
            if (name == null || name.isBlank() || type == null || type.isBlank()) {
                throw new IllegalArgumentException("Compact entity candidates require non-blank names and types");
            }
            candidates.add(Map.of("name", name, "type", type));
        }
        this.compactEntityCandidates = List.copyOf(candidates);
        this.compactEntityMinItems = names.size();
        this.compactEntityMaxItems = names.size();
    }

    /**
     * Adds source-derived relation rows in deterministic prepass order. Endpoint indices are
     * immutable positional boundaries. Proposed relation types remain verification hints: the model
     * still selects each type from the authoritative ontology using the current text.
     */
    public void configureCompactRelationCandidates(
            List<Integer> sources, List<Integer> targets, List<String> types) {
        if (sources == null || targets == null || types == null
                || sources.size() != targets.size() || sources.size() != types.size()) {
            throw new IllegalArgumentException(
                    "Compact relation candidate sources, targets, and types must align");
        }
        if ((compactRelationMinItems != null && compactRelationMinItems != sources.size())
                || (compactRelationMaxItems != null && compactRelationMaxItems != sources.size())) {
            throw new IllegalArgumentException(
                    "Compact relation candidate count must match the configured relation cardinality");
        }
        List<CompactRelationCandidate> candidates = new ArrayList<>(sources.size());
        for (int index = 0; index < sources.size(); index++) {
            Integer source = sources.get(index);
            Integer target = targets.get(index);
            String type = types.get(index);
            if (source == null || source < 0 || target == null || target < 0
                    || type == null || type.isBlank()) {
                throw new IllegalArgumentException(
                        "Compact relation candidates require non-negative endpoints and non-blank types");
            }
            if (!compactEntityCandidates.isEmpty()
                    && (source >= compactEntityCandidates.size()
                            || target >= compactEntityCandidates.size())) {
                throw new IllegalArgumentException(
                        "Compact relation candidate endpoint is outside the entity candidate table");
            }
            candidates.add(new CompactRelationCandidate(source, target, type));
        }
        this.compactRelationCandidates = List.copyOf(candidates);
        this.compactRelationMinItems = sources.size();
        this.compactRelationMaxItems = sources.size();
    }

    /**
     * Start a fresh typed-entity model pass. Relations are not exposed in this pass.
     */
    public void beginTypedEntityPhase() {
        extractionTarget = ExtractionTarget.TYPED_ENTITIES_ONLY;
        fixedPhaseEntities = List.of();
        accepted = null;
    }

    /**
     * Start a fresh relation model pass over the immutable entities accepted by phase one.
     */
    public void beginRelationPhase(List<ExtractedEntity> entities) {
        List<ExtractedEntity> fixed = entities == null ? List.of()
                : entities.stream().filter(java.util.Objects::nonNull).toList();
        for (CompactRelationCandidate candidate : compactRelationCandidates) {
            if (candidate.source() >= fixed.size() || candidate.target() >= fixed.size()) {
                throw new IllegalArgumentException(
                        "Compact relation candidate endpoint is outside the accepted entity table");
            }
        }
        extractionTarget = ExtractionTarget.RELATIONS_ONLY;
        fixedPhaseEntities = List.copyOf(fixed);
        compactEntityMinItems = fixed.size();
        compactEntityMaxItems = fixed.size();
        accepted = null;
    }

    @Override
    public ExtractionTarget extractionTarget() {
        return extractionTarget;
    }

    @Override
    public boolean ontologyUpdatesAllowed() {
        return ontologyUpdatesAllowed;
    }

    @Override
    public String catalogJson() {
        return catalogJson(DecomposedPromptTier.STANDARD);
    }

    /**
     * Reveal a small executable contract first and let tool results expose detail on demand.
     * This keeps short-context models focused on the current evidence instead of asking them to
     * copy a large schema before they have chosen an operation.
     */
    @Override
    public String catalogJson(DecomposedPromptTier configuredTier) {
        DecomposedPromptTier tier = effectiveTier(configuredTier);
        UnifiedGraph graph = graphSnapshot();
        if (typedEntitiesOnly()) {
            return json(Map.of(
                    "phase", "TYPED_ENTITIES_ONLY",
                    "callShape", Map.of(
                            "tool", SUBMIT_TYPED_ENTITIES,
                            "args", Map.of("entities", List.of())),
                    "allowedEntityTypes", schema().getAllNodeLabels().stream().sorted().toList()));
        }
        if (relationsOnly()) {
            List<Map<String, Object>> entityTable = new ArrayList<>();
            for (int index = 0; index < fixedPhaseEntities.size(); index++) {
                ExtractedEntity entity = fixedPhaseEntities.get(index);
                entityTable.add(Map.of(
                        "index", index,
                        "name", entity.name(),
                        "type", entity.type()));
            }
            return json(Map.of(
                    "phase", "RELATIONS_ONLY",
                    "callShape", Map.of(
                            "tool", SUBMIT_RELATIONS,
                            "args", Map.of("relations", List.of())),
                    "entities", entityTable,
                    "allowedRelationTypes",
                    schema().getAllRelationshipTypes().stream().sorted().toList(),
                    "allowedRelationPatterns",
                    schema().getPatterns() == null ? List.of() : schema().getPatterns()));
        }
        boolean directCompact = !entitiesOnly()
                && !ontologyUpdatesAllowed
                && tier == DecomposedPromptTier.COMPACT;

        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("version", "tiered-compact-v12");
        Map<String, Object> callShape = new LinkedHashMap<>();
        callShape.put("tool", entitiesOnly() ? SUBMIT_ENTITIES : SUBMIT_GRAPH_DELTA);
        callShape.put("args", entitiesOnly()
                ? Map.of("names", List.of("<SOURCE NAME>"))
                : directCompact
                        ? Map.of(
                                COMPACT_SUBMIT_FORMAT_FIELD, COMPACT_SUBMIT_FORMAT,
                                "entities", List.of(),
                                "relations", List.of())
                        : Map.of("entities", List.of(), "relations", List.of()));
        catalog.put("callShape", callShape);

        boolean emptyGraph = graph.entityCount() == 0;
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("graphEntities", graph.entityCount());
        state.put("graphRelations", graph.relationCount());
        state.put("completeCorpusPassages", passagesById.size());
        state.put("graphState", emptyGraph ? "EMPTY" : "POPULATED");
        state.put("extractionTarget", extractionTarget.name());
        state.put("recommendedTool", entitiesOnly()
                ? SUBMIT_ENTITIES
                : emptyGraph ? SUBMIT_GRAPH_DELTA : GRAPH_REASONING_QUERY);
        state.put("next", entitiesOnly()
                ? "fill callShape.names with exact source names and submit"
                : emptyGraph
                        ? "fill callShape arrays from the source and submit"
                        : "query graph only for ambiguity, then submit source-supported additions");
        catalog.put("state", state);
        if (entitiesOnly()) {
            catalog.put(SUBMIT_ENTITIES, Map.of(
                    "purpose", "submit every distinct entity name explicitly present in SOURCE",
                    "argument", "names",
                    "unlimited", true));
            return json(catalog);
        }

        catalog.put("ontologyRevision", ontology.revision());
        Map<String, Object> vocabulary = schemaVocabulary(graph, tier, activeTaskContext);
        if (!vocabulary.isEmpty()) {
            catalog.put("graphSchema", vocabulary);
        }

        Map<String, Object> submit = new LinkedHashMap<>();
        submit.put("purpose", "finish by proposing all source-supported graph additions");
        if (directCompact) {
            submit.put("entityFields", List.of("name", "type"));
            submit.put("relationFields", List.of("source", "target", "type"));
            submit.put("endpointRule",
                    "relation source and target are zero-based indices into entities");
            submit.put("engineOwned", List.of("entity ids", "metadata", "graph admission"));
        } else {
            List<String> entityFields = new ArrayList<>(List.of("id", "name", "type", "aliases"));
            List<String> relationFields = new ArrayList<>(List.of("source", "target", "type"));
            if (requiresDescriptions()) {
                entityFields.add("description");
                relationFields.add("description");
            }
            if (tierRank(tier) >= tierRank(DecomposedPromptTier.RICH)) {
                entityFields.add("confidence");
                relationFields.add("confidence");
            }
            if (tierRank(tier) >= tierRank(DecomposedPromptTier.EXPANDED)) {
                entityFields.add("properties");
                relationFields.addAll(List.of("properties", "occurredAt"));
            }
            submit.put("entityFields", List.copyOf(entityFields));
            if (!entitiesOnly()) {
                submit.put("relationFields", List.copyOf(relationFields));
            }
        }
        submit.put("unlimited", true);
        catalog.put(SUBMIT_GRAPH_DELTA, submit);

        Map<String, Object> graphTool = new LinkedHashMap<>();
        graphTool.put("purpose", "reuse or disambiguate existing graph identities and structure");
        graphTool.put("operations", List.of(
                "SEARCH requires operation,queryText,topK",
                "SCHEMA requires operation",
                "CAPABILITIES requires operation"));
        if (tierRank(tier) >= tierRank(DecomposedPromptTier.RICH)) {
            graphTool.put("capabilityHint",
                    "CAPABILITIES exposes provenance, embeddings, first-order logic, PSL and Bayesian structure");
        }
        if (tierRank(tier) >= tierRank(DecomposedPromptTier.EXPANDED)) {
            graphTool.put("optionalArgs", List.of(
                    "entityId", "targetId", "relationTypes", "topK at most " + MAX_GRAPH_TOP_K,
                    "maxDepth at most " + MAX_GRAPH_DEPTH, "structural PSL or BAYESIAN", "queryText"));
        }
        catalog.put(GRAPH_REASONING_QUERY, graphTool);

        Map<String, Object> corpusTool = new LinkedHashMap<>();
        corpusTool.put("purpose", "retrieve missing cross-source evidence from the unified corpus");
        corpusTool.put("operations", List.of(
                "SEARCH requires action,query,limit",
                "GET requires action,chunkId,start,length"));
        catalog.put(UNIFIED_CORPUS, corpusTool);
        if (ontologyUpdatesAllowed) {
            catalog.put(UPDATE_ONTOLOGY, Map.of(
                    "purpose", "add source-supported reusable node types, relationship types, aliases, properties, and directed endpoint patterns",
                    "behavior", "validated additive update; established definitions cannot be deleted or redefined",
                    "next", "use the refreshed ontology revision on the following turn"));
        }

        return json(catalog);
    }

    @Override
    public String toolContextJson(DecomposedPromptTier configuredTier) {
        return toolContextJson(configuredTier, null);
    }

    @Override
    public String toolContextJson(
            DecomposedPromptTier configuredTier, ExtractionTaskContext taskContext) {
        activeTaskContext = taskContext;
        DecomposedPromptTier tier = effectiveTier(configuredTier);
        UnifiedGraph graph = graphSnapshot();
        boolean emptyGraph = graph.entityCount() == 0;
        if (typedEntitiesOnly()) {
            GraphSchema currentSchema = schema();
            Map<String, Object> phase = new LinkedHashMap<>();
            phase.put("phase", "TYPED_ENTITIES_ONLY");
            phase.put("allowedEntityTypes", allowedEntityTypes().stream().sorted().toList());
            phase.put("entityTypeGuide", compactEntityTypeDescription(currentSchema));
            if (!compactEntityCandidates.isEmpty()) {
                phase.put("sourceEntityCandidates", compactEntityCandidates);
            }
            if (compactEntityMinItems != null
                    && compactEntityMinItems.equals(compactEntityMaxItems)) {
                phase.put("expectedEntityRows", compactEntityMinItems);
            }
            phase.put("submitTool", SUBMIT_TYPED_ENTITIES);
            return json(phase);
        }
        if (relationsOnly()) {
            List<Map<String, Object>> entityTable = new ArrayList<>();
            for (int index = 0; index < fixedPhaseEntities.size(); index++) {
                ExtractedEntity entity = fixedPhaseEntities.get(index);
                entityTable.add(Map.of(
                        "index", index,
                        "name", entity.name(),
                        "type", entity.type()));
            }
            GraphSchema currentSchema = schema();
            Map<String, Object> phase = new LinkedHashMap<>();
            phase.put("phase", "RELATIONS_ONLY");
            phase.put("entities", entityTable);
            if (!compactRelationCandidates.isEmpty()) {
                List<Map<String, Object>> candidates =
                        new ArrayList<>(compactRelationCandidates.size());
                for (CompactRelationCandidate candidate : compactRelationCandidates) {
                    ExtractedEntity source = fixedPhaseEntities.get(candidate.source());
                    ExtractedEntity target = fixedPhaseEntities.get(candidate.target());
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sourceIndex", candidate.source());
                    row.put("sourceName", source.name());
                    row.put("targetIndex", candidate.target());
                    row.put("targetName", target.name());
                    row.put("type", candidate.proposedType());
                    candidates.add(row);
                }
                phase.put("sourceRelationCandidates", candidates);
            }
            phase.put("allowedRelationTypes", allowedRelationTypes().stream().sorted().toList());
            phase.put("relationTypeGuide", compactRelationTypeDescription(currentSchema));
            phase.put("allowedRelationPatterns",
                    currentSchema.getPatterns() == null ? List.of() : currentSchema.getPatterns());
            if (compactRelationMinItems != null
                    && compactRelationMinItems.equals(compactRelationMaxItems)) {
                phase.put("expectedRelationRows", compactRelationMinItems);
            }
            phase.put("submitTool", SUBMIT_RELATIONS);
            return json(phase);
        }
        if (!ontologyUpdatesAllowed
                && !entitiesOnly()
                && tier == DecomposedPromptTier.COMPACT) {
            GraphSchema currentSchema = schema();
            Map<String, Object> direct = new LinkedHashMap<>();
            direct.put("graphState", emptyGraph ? "EMPTY" : "POPULATED");
            direct.put("allowedEntityTypes",
                    currentSchema.getAllNodeLabels().stream().sorted().toList());
            direct.put("allowedRelationTypes",
                    currentSchema.getAllRelationshipTypes().stream().sorted().toList());
            direct.put("allowedRelationPatterns",
                    currentSchema.getPatterns() == null ? List.of()
                            : currentSchema.getPatterns().stream()
                                    .filter(value -> value != null && !value.isBlank())
                                    .map(String::trim)
                                    .sorted()
                                    .toList());
            direct.put("submitTool", SUBMIT_GRAPH_DELTA);
            direct.put("guidance", emptyGraph
                    ? "Read SOURCE SHARD and submit its explicit facts now."
                    : "Resolve an identity only when ambiguous, then submit explicit SOURCE facts.");
            return json(direct);
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("graphEntities", graph.entityCount());
        state.put("graphRelations", graph.relationCount());
        state.put("completeCorpusPassages", passagesById.size());
        state.put("graphState", emptyGraph ? "EMPTY" : "POPULATED");
        state.put("extractionTarget", extractionTarget.name());
        if (!entitiesOnly()) {
            state.put("ontologyRevision", ontology.revision());
        }
        state.put("recommendedFirstTool", entitiesOnly()
                ? SUBMIT_ENTITIES
                : emptyGraph ? SUBMIT_GRAPH_DELTA : GRAPH_REASONING_QUERY);
        state.put("submitArgumentKeys", entitiesOnly()
                ? List.of("names") : List.of("entities", "relations"));
        if (!entitiesOnly()) {
            state.put("entityRequiredFields", requiredEntityFields());
            state.put("relationRequiredFields", requiredRelationFields());
            Map<String, Object> vocabulary = schemaVocabulary(graph, tier, taskContext);
            if (!vocabulary.isEmpty()) {
                state.put("graphSchema", vocabulary);
            }
        }
        state.put("guidance", entitiesOnly()
                ? "Call submit_entities with exact names copied from SOURCE; typing is a later phase."
                : emptyGraph
                        ? ontologyUpdatesAllowed
                                ? "Use the prepass ontology, update it only when SOURCE establishes a missing reusable type, then submit all typed entities and relations."
                                : "Use the strict configured ontology and submit all typed entities and relations."
                        : "Search names, emails, and aliases before creating identities; resolve duplicates, then submit typed entities and typed relations.");
        return json(state);
    }

    @Override
    public List<ToolDefinition> toolDefinitions(DecomposedPromptTier configuredTier) {
        DecomposedPromptTier tier = effectiveTier(configuredTier);
        if (entitiesOnly()) {
            Map<String, Object> parameters = objectSchema(
                    Map.of("names", entityNamesSchema(
                            "Every distinct entity name explicitly present in SOURCE, once each.")),
                    List.of("names"));
            return List.of(new ToolDefinition(
                    SUBMIT_ENTITIES,
                    "Submit every distinct entity name explicitly present in SOURCE. The engine owns "
                            + "stable ids, provisional records, graph admission, and later ontology typing.",
                    parameters));
        }
        GraphSchema currentSchema = schema();
        if (typedEntitiesOnly() || relationsOnly()) {
            return phasedToolDefinitions(currentSchema, tier);
        }
        boolean directCompact = !ontologyUpdatesAllowed
                && tier == DecomposedPromptTier.COMPACT;
        boolean discoveringTypes = directCompact
                && currentSchema.getAllNodeLabels().isEmpty()
                && currentSchema.getAllRelationshipTypes().isEmpty();
        boolean closedRelationshipVocabulary = !ontologyUpdatesAllowed
                && currentSchema.getRelationshipTypes() != null
                && currentSchema.getRelationshipTypes().isEmpty();
        int inlineEnumLimit = inlineSchemaEnumLimit(tier);
        Map<String, Object> entityProperties = new LinkedHashMap<>();
        entityProperties.put("id", stringSchema(directCompact
                ? "Short id, such as alex-rivera. Reuse it in relations."
                : entitiesOnly()
                ? "Stable identifier for this source-supported entity."
                : "Stable entity identifier. Create one for every source-supported entity and "
                        + "reuse it exactly in relations[].source and relations[].target.",
                MAX_GRAPH_ID_CHARS));
        entityProperties.put("name", stringSchema(
                directCompact
                        ? "Exact name copied from the text."
                        : "Exact name stated in SOURCE or a retrieved passage; never copy a value that "
                                + "appears only in graph, schema, or hints.",
                MAX_ENTITY_NAME_CHARS));
        entityProperties.put("type", standardizedTypeSchema(
                discoveringTypes
                        ? "Short reusable entity category inferred from the text."
                        : directCompact
                                ? "Allowed entity type."
                                : "Entity type from CURRENT GRAPH AND CORPUS STATE.graphSchema.entityTypes; "
                                        + "query the graph SCHEMA operation when another source-supported type is needed.",
                currentSchema.getAllNodeLabels(), inlineEnumLimit));
        if (requiresDescriptions()) {
            entityProperties.put("description",
                    stringSchema("Concise source-supported description."));
        }
        if (tierRank(tier) >= tierRank(DecomposedPromptTier.RICH)
                || (tier == DecomposedPromptTier.STANDARD && ontology.revision() > 0)) {
            entityProperties.put("aliases", arraySchema(
                    stringSchema("Alternate name for this same identity.", MAX_ENTITY_NAME_CHARS),
                    "Source-supported alternate names, emails, identifiers, or spellings for this same identity."));
        }
        if (tierRank(tier) >= tierRank(DecomposedPromptTier.RICH)) {
            entityProperties.put("confidence", confidenceSchema());
        }
        if (tierRank(tier) >= tierRank(DecomposedPromptTier.EXPANDED)) {
            entityProperties.put("properties", stringMapSchema());
        }
        Map<String, Object> entity = objectSchema(entityProperties, requiredEntityFields());

        Map<String, Object> relationProperties = new LinkedHashMap<>();
        relationProperties.put("source", stringSchema(
                directCompact
                        ? "Source entity id from entities."
                        : "Exact source entity id copied from entities[].id in this call or an existing "
                                + "current graph entity id; never a display name or type label.",
                MAX_GRAPH_ID_CHARS));
        relationProperties.put("target", stringSchema(
                directCompact
                        ? "Target entity id from entities."
                        : "Exact target entity id copied from entities[].id in this call or an existing "
                                + "current graph entity id; never a display name or type label.",
                MAX_GRAPH_ID_CHARS));
        relationProperties.put("type", standardizedTypeSchema(
                discoveringTypes
                        ? "Short directed relation category inferred from the text."
                        : directCompact
                                ? "Allowed relation type."
                                : "Directed relation type from CURRENT GRAPH AND CORPUS STATE.graphSchema.relationTypes; "
                                        + "query the graph SCHEMA operation when another source-supported type is needed.",
                currentSchema.getAllRelationshipTypes(), inlineEnumLimit));
        if (requiresDescriptions()) {
            relationProperties.put("description",
                    stringSchema("Concise source-supported relation description."));
        }
        if (tierRank(tier) >= tierRank(DecomposedPromptTier.RICH)) {
            relationProperties.put("confidence", confidenceSchema());
        }
        if (tierRank(tier) >= tierRank(DecomposedPromptTier.EXPANDED)) {
            relationProperties.put("properties", stringMapSchema());
            relationProperties.put("occurredAt",
                    stringSchema("Optional source-supported event time.", MAX_GRAPH_TIME_CHARS));
        }
        Map<String, Object> relation = objectSchema(relationProperties, requiredRelationFields());
        Map<String, Object> submitProperties = new LinkedHashMap<>();
        if (directCompact) {
            Map<String, Object> compactEntityType = standardizedTypeSchema(
                    discoveringTypes
                            ? "One uppercase reusable entity type token inferred from the text; no prose."
                            : compactEntityTypeDescription(currentSchema),
                    currentSchema.getAllNodeLabels(),
                    inlineEnumLimit,
                    compactEntityTypeOrder);
            Map<String, Object> compactRelationType = standardizedTypeSchema(
                    discoveringTypes
                            ? "One uppercase directed relation type token inferred from the text; no prose."
                            : compactRelationTypeDescription(currentSchema),
                    currentSchema.getAllRelationshipTypes(),
                    inlineEnumLimit,
                    compactRelationTypeOrder);
            if (discoveringTypes) {
                compactEntityType.put("maxLength", MAX_DISCOVERY_TYPE_CHARS);
                compactRelationType.put("maxLength", MAX_DISCOVERY_TYPE_CHARS);
            }
            Map<String, Object> compactEntityName = new LinkedHashMap<>(stringSchema(
                    "Exact non-empty named-node span copied from Text; never copy an ontology label or instruction.",
                    compactEntityNameMaxLength == null
                            ? MAX_ENTITY_NAME_CHARS : compactEntityNameMaxLength));
            compactEntityName.put("minLength", 1);
            Map<String, Object> compactEntity = objectSchema(
                    Map.of(
                            "name", compactEntityName,
                            "type", compactEntityType),
                    List.of("name", "type"));
            Map<String, Object> compactSourceIndex = compactEntityIndexSchema(
                    "source", compactEntityMaxItems);
            Map<String, Object> compactTargetIndex = compactEntityIndexSchema(
                    "target", compactEntityMaxItems);
            Map<String, Object> compactRelation = objectSchema(
                    Map.of(
                            "source", compactSourceIndex,
                            "target", compactTargetIndex,
                            "type", compactRelationType),
                    List.of("source", "target", "type"));
            Map<String, Object> compactEntities = new LinkedHashMap<>(arraySchema(
                    compactEntity, "Each distinct named node explicitly present in Text once; names stay unique even when types differ."));
            compactEntities.put("uniqueItems", true);
            Map<String, Object> compactRelations = new LinkedHashMap<>(arraySchema(
                    compactRelation, "Each explicit Text-supported directed fact between extracted entities once."));
            compactRelations.put("uniqueItems", true);
            if (compactEntityMinItems != null) {
                compactEntities.put("minItems", compactEntityMinItems);
            }
            if (compactEntityMaxItems != null) {
                compactEntities.put("maxItems", compactEntityMaxItems);
            }
            if (compactRelationMinItems != null) {
                compactRelations.put("minItems", compactRelationMinItems);
            }
            if (compactRelationMaxItems != null) {
                compactRelations.put("maxItems", compactRelationMaxItems);
            }
            if (closedRelationshipVocabulary) {
                compactRelations.put("minItems", 0);
                compactRelations.put("maxItems", 0);
            }

            submitProperties.put(COMPACT_SUBMIT_FORMAT_FIELD, Map.of(
                    "type", "string",
                    "const", COMPACT_SUBMIT_FORMAT,
                    "description", "Indexed compact graph delta."));
            submitProperties.put("entities", compactEntities);
            submitProperties.put("relations", compactRelations);
        } else {
            submitProperties.put("entities", arraySchema(entity, entitiesOnly()
                    ? "Every distinct entity explicitly present in SOURCE."
                    : "Every source-supported entity. Include an entity object for every relation endpoint "
                            + "that is not already an entity in the current graph."));
            if (!entitiesOnly()) {
                Map<String, Object> relationsSchema = new LinkedHashMap<>(arraySchema(relation,
                        "Every directed relation explicitly stated in SOURCE or a retrieved passage, "
                                + "between submitted or existing graph entity ids."));
                if (closedRelationshipVocabulary) {
                    relationsSchema.put("maxItems", 0);
                }
                submitProperties.put("relations", relationsSchema);
            }
        }
        Map<String, Object> submitParameters = objectSchema(
                submitProperties, directCompact
                        ? List.of(COMPACT_SUBMIT_FORMAT_FIELD, "entities", "relations")
                        : entitiesOnly()
                                ? List.of("entities") : List.of("entities", "relations"));

        Map<String, Object> graphParameters = tier == DecomposedPromptTier.COMPACT
                ? objectSchema(Map.ofEntries(
                        Map.entry("operation", Map.of(
                                "type", "string",
                                "enum", GraphReasoningQueryService.queryRequestOperations())),
                        Map.entry("entityId", Map.of("type", "string")),
                        Map.entry("targetId", Map.of("type", "string")),
                        Map.entry("relationTypes", arraySchema(Map.of("type", "string"))),
                        Map.entry("topK", Map.of(
                                "type", "integer", "minimum", 1, "maximum", MAX_GRAPH_TOP_K)),
                        Map.entry("queryText", Map.of("type", "string"))),
                        List.of("operation"))
                : objectSchema(Map.ofEntries(
                        Map.entry("operation", Map.of(
                                "type", "string",
                                "enum", GraphReasoningQueryService.queryRequestOperations(),
                                "description", GraphReasoningQueryService.queryRequestOperationGuide())),
                        Map.entry("entityId", stringSchema(
                                "Existing graph entity id or a name/phrase for ranked resolution.")),
                        Map.entry("targetId", stringSchema(
                                "Optional target entity id or name/phrase for ranked resolution.")),
                        Map.entry("direction", Map.of(
                                "type", "string",
                                "enum", java.util.Arrays.stream(GraphQueryEngine.Direction.values())
                                        .map(Enum::name).toList())),
                        Map.entry("relationTypes", arraySchema(stringSchema("Relation type filter."))),
                        Map.entry("maxDepth", integerSchema(1, MAX_GRAPH_DEPTH,
                                "Maximum graph traversal depth.")),
                        Map.entry("topK", integerSchema(1, MAX_GRAPH_TOP_K,
                                "Maximum ranked results.")),
                        Map.entry("structural", Map.of(
                                "type", "string",
                                "enum", List.of("PSL", "BAYESIAN"),
                                "description", "Optional structural reasoner for RANK or SIMILAR; defaults to PSL.")),
                        Map.entry("queryText", stringSchema(
                                "Natural-language identity, relation, or fact query.")),
                        Map.entry("question", stringSchema(
                                "Question to answer from graph reasoning state."))),
                        List.of("operation"));

        Map<String, Object> corpusParameters = tier == DecomposedPromptTier.COMPACT
                ? objectSchema(Map.ofEntries(
                        Map.entry("action", Map.of(
                                "type", "string", "enum", List.of("SEARCH", "GET"))),
                        Map.entry("query", Map.of("type", "string")),
                        Map.entry("limit", Map.of(
                                "type", "integer", "minimum", 1, "maximum", MAX_CORPUS_RESULTS)),
                        Map.entry("offset", Map.of("type", "integer", "minimum", 0)),
                        Map.entry("chunkId", Map.of("type", "string")),
                        Map.entry("start", Map.of("type", "integer", "minimum", 0)),
                        Map.entry("length", Map.of(
                                "type", "integer", "minimum", 1, "maximum", MAX_CORPUS_PAGE_CHARS))),
                        List.of("action"))
                : objectSchema(Map.ofEntries(
                        Map.entry("action", Map.of(
                                "type", "string",
                                "enum", List.of("SEARCH", "GET"),
                                "description", "SEARCH ranks passages; GET pages exact passage text.")),
                        Map.entry("query", stringSchema("Cross-source evidence query for SEARCH.")),
                        Map.entry("limit", integerSchema(1, MAX_CORPUS_RESULTS,
                                "Maximum SEARCH results.")),
                        Map.entry("offset", integerSchema(0, Integer.MAX_VALUE,
                                "SEARCH result offset.")),
                        Map.entry("chunkId", stringSchema("Passage identifier for GET.")),
                        Map.entry("start", integerSchema(0, Integer.MAX_VALUE,
                                "GET character offset.")),
                        Map.entry("length", integerSchema(1, MAX_CORPUS_PAGE_CHARS,
                                "GET page length."))),
                        List.of("action"));

        Map<String, Object> ontologyParameters = ontologyUpdateSchema(tier);

        ToolDefinition submit = new ToolDefinition(
                SUBMIT_GRAPH_DELTA,
                entitiesOnly()
                        ? "Validate and stage every source-supported entity. Arguments use exactly "
                                + "the top-level key entities; do not submit relations. Metadata and "
                                + "graph mutation remain engine-owned."
                        : directCompact
                                ? "Submit named Text entities and explicit directed relations separately; "
                                        + "entity names are exact Text spans, relation types are predicate labels, "
                                        + "and the engine creates ids."
                                : "Validate and stage every source-supported entity and relation addition. "
                                        + "Arguments use exactly the top-level keys entities and relations. "
                                        + "Metadata and graph mutation remain engine-owned; validation feedback "
                                        + "must be corrected and resubmitted.",
                submitParameters);
        ToolDefinition graph = new ToolDefinition(
                GRAPH_REASONING_QUERY,
                tier == DecomposedPromptTier.COMPACT
                        ? "Inspect current graph identities and schema."
                        : "Inspect current graph identities, schema, embeddings, first-order logic, "
                                + "probabilistic structure, paths, facts, and explanations before resolving "
                                + "an ambiguous graph addition.",
                graphParameters);
        ToolDefinition corpusTool = new ToolDefinition(
                UNIFIED_CORPUS,
                tier == DecomposedPromptTier.COMPACT
                        ? "Retrieve supporting passages from the unified corpus."
                        : "Retrieve exact supporting evidence from the preassembled unified corpus when the "
                                + "current source shard does not contain enough cross-source context.",
                corpusParameters);
        ToolDefinition updateOntology = new ToolDefinition(
                UPDATE_ONTOLOGY,
                tier == DecomposedPromptTier.COMPACT
                        ? "Add reusable graph types and directed endpoint patterns."
                        : "Add source-supported reusable node and relationship definitions to the crawl ontology. "
                                + "Relationship types require at least one directed source/relation/target pattern. "
                                + "Updates are validated and additive; use the refreshed ontology on the next turn.",
                ontologyParameters);

        if (entitiesOnly()) {
            return List.of(submit);
        }
        boolean emptyGraph = graphSnapshot().entityCount() == 0;
        if (!ontologyUpdatesAllowed) {
            return emptyGraph
                    ? List.of(submit)
                    : List.of(graph, corpusTool, submit);
        }
        return emptyGraph
                ? List.of(submit, graph, updateOntology, corpusTool)
                : List.of(graph, updateOntology, corpusTool, submit);
    }

    private List<ToolDefinition> phasedToolDefinitions(
            GraphSchema currentSchema, DecomposedPromptTier tier) {
        int inlineEnumLimit = inlineSchemaEnumLimit(tier);
        if (typedEntitiesOnly()) {
            Map<String, Object> entity = objectSchema(
                    Map.of(
                            "name", stringSchema(
                                    "Entity name: exact non-empty named-node span copied from Text.",
                                    compactEntityNameMaxLength == null
                                            ? MAX_ENTITY_NAME_CHARS : compactEntityNameMaxLength),
                            "type", boundedPhasedTypeSchema(
                                    compactEntityTypeDescription(currentSchema),
                                    allowedEntityTypes(),
                                    inlineEnumLimit,
                                    compactEntityTypeOrder)),
                    List.of("name", "type"));
            Map<String, Object> entities = new LinkedHashMap<>(arraySchema(
                    entity,
                    "Typed entity rows: one immutable source candidate per positional row."));
            if (!compactEntityCandidates.isEmpty()) {
                List<Map<String, Object>> positionalEntities =
                        new ArrayList<>(compactEntityCandidates.size());
                for (Map<String, String> candidate : compactEntityCandidates) {
                    Map<String, Object> nameSchema = new LinkedHashMap<>(stringSchema(
                            "Immutable source-derived entity name for this positional row.",
                            compactEntityNameMaxLength == null
                                    ? MAX_ENTITY_NAME_CHARS : compactEntityNameMaxLength));
                    nameSchema.put("const", candidate.get("name"));
                    Map<String, Object> properties = new LinkedHashMap<>();
                    properties.put("name", nameSchema);
                    properties.put("type", boundedPhasedTypeSchema(
                            compactEntityTypeDescription(currentSchema),
                            allowedEntityTypes(),
                            inlineEnumLimit,
                            compactEntityTypeOrder));
                    positionalEntities.add(objectSchema(
                            properties, List.of("name", "type")));
                }
                entities.put("prefixItems", positionalEntities);
                entities.put("items", false);
            }
            entities.put("uniqueItems", true);
            if (compactEntityMinItems != null) {
                entities.put("minItems", compactEntityMinItems);
            }
            if (compactEntityMaxItems != null) {
                entities.put("maxItems", compactEntityMaxItems);
            }
            return List.of(new ToolDefinition(
                    SUBMIT_TYPED_ENTITIES,
                    "Submit every distinct phase-one entity explicitly named in Text. name is one exact Text "
                            + "span, never a type label, instruction, example, placeholder, alternate casing, or "
                            + "duplicate. Classify each name independently from its Text referent; type is the one "
                            + "allowed ontology node label whose definition matches that referent.",
                    objectSchema(Map.of("entities", entities), List.of("entities"))));
        }

        int entityCount = fixedPhaseEntities.size();
        Map<String, Object> relation = objectSchema(
                Map.of(
                        "source", compactEntityIndexSchema("source", entityCount),
                        "target", compactEntityIndexSchema("target", entityCount),
                        "type", boundedPhasedTypeSchema(
                                compactRelationTypeDescription(currentSchema),
                                allowedRelationTypes(),
                                inlineEnumLimit,
                                compactRelationTypeOrder)),
                List.of("source", "target", "type"));
        Map<String, Object> relations = new LinkedHashMap<>(arraySchema(
                relation,
                "Directed predicate rows from Text between fixed phase-one entity indices."));
        if (!compactRelationCandidates.isEmpty()) {
            List<Map<String, Object>> positionalRelations =
                    new ArrayList<>(compactRelationCandidates.size());
            for (CompactRelationCandidate candidate : compactRelationCandidates) {
                Map<String, Object> sourceSchema =
                        new LinkedHashMap<>(compactEntityIndexSchema("source", entityCount));
                sourceSchema.put("const", candidate.source());
                Map<String, Object> targetSchema =
                        new LinkedHashMap<>(compactEntityIndexSchema("target", entityCount));
                targetSchema.put("const", candidate.target());
                Map<String, Object> properties = new LinkedHashMap<>();
                properties.put("source", sourceSchema);
                properties.put("target", targetSchema);
                properties.put("type", boundedPhasedTypeSchema(
                        compactRelationTypeDescription(currentSchema),
                        allowedRelationTypes(),
                        inlineEnumLimit,
                        compactRelationTypeOrder));
                positionalRelations.add(objectSchema(
                        properties, List.of("source", "target", "type")));
            }
            relations.put("prefixItems", positionalRelations);
            relations.put("items", false);
        }
        relations.put("uniqueItems", true);
        boolean closedRelationshipVocabulary = !ontologyUpdatesAllowed
                && currentSchema.getRelationshipTypes() != null
                && currentSchema.getRelationshipTypes().isEmpty();
        if (compactRelationMinItems != null) {
            relations.put("minItems", compactRelationMinItems);
        }
        if (compactRelationMaxItems != null) {
            relations.put("maxItems", compactRelationMaxItems);
        }
        if (closedRelationshipVocabulary) {
            relations.put("minItems", 0);
            relations.put("maxItems", 0);
        }
        return List.of(new ToolDefinition(
                SUBMIT_RELATIONS,
                "Submit every distinct explicit Text predicate between phase-one entities. source and target "
                        + "are independently resolved immutable entity indices in the relation's directed roles; "
                        + "type is an allowed ontology relation label whose definition and endpoint pattern match. "
                        + "Never use background knowledge or endpoints from different predicates.",
                objectSchema(Map.of("relations", relations), List.of("relations"))));
    }

    private static Map<String, Object> objectSchema(
            Map<String, Object> properties,
            List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required != null && !required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> ontologyUpdateSchema(DecomposedPromptTier tier) {
        if (tier == DecomposedPromptTier.COMPACT) {
            Map<String, Object> nodeType = objectSchema(Map.of(
                    "label", Map.of("type", "string"),
                    "description", Map.of("type", "string"),
                    "parentType", Map.of("type", "string")),
                    List.of("label", "description"));
            Map<String, Object> relationshipType = objectSchema(Map.of(
                    "type", Map.of("type", "string"),
                    "description", Map.of("type", "string"),
                    "connectionFamily", Map.of("type", "string")),
                    List.of("type", "description", "connectionFamily"));
            return objectSchema(Map.of(
                    "nodeTypes", arraySchema(nodeType),
                    "relationshipTypes", arraySchema(relationshipType),
                    "patterns", arraySchema(Map.of("type", "string"))),
                    List.of("nodeTypes", "relationshipTypes", "patterns"));
        }
        Map<String, Object> propertyType = objectSchema(Map.of(
                "name", stringSchema("Reusable property name."),
                "type", Map.of(
                        "type", "string",
                        "enum", List.of("String", "Integer", "Decimal", "Boolean", "Date", "Year", "YearMonth", "DateTime"),
                        "description", "Standard property value type.")),
                List.of("name", "type"));
        Map<String, Object> nodeType = objectSchema(Map.of(
                "label", stringSchema("Canonical reusable entity type label supported by the corpus."),
                "description", stringSchema("Concise semantic definition of the entity type."),
                "parentType", Map.of(
                        "type", "string",
                        "enum", SchemaHierarchyVocabulary.BASE_ENTITY_TYPES,
                        "description", "Direct baseline parent for this domain entity type."),
                "properties", arraySchema(propertyType)),
                List.of("label", "description"));
        Map<String, Object> relationshipType = objectSchema(Map.of(
                "type", stringSchema("Canonical directed relationship type supported by the corpus."),
                "description", stringSchema("Concise semantic definition of the relationship."),
                "connectionFamily", Map.of(
                        "type", "string",
                        "enum", SchemaHierarchyVocabulary.CONNECTION_FAMILIES,
                        "description", "Semantic family for this specific directed predicate; never the emitted edge type."),
                "properties", arraySchema(propertyType),
                "aliases", arraySchema(stringSchema("Source-language predicate or synonym."))),
                List.of("type", "description", "connectionFamily"));
        return objectSchema(Map.of(
                "nodeTypes", arraySchema(nodeType,
                        "New node definitions or missing details for established definitions."),
                "relationshipTypes", arraySchema(relationshipType,
                        "New directed relationship definitions or aliases for established definitions."),
                "patterns", arraySchema(stringSchema(
                        "Directed endpoint pattern shaped as (SOURCE_TYPE)-[:RELATION_TYPE]->(TARGET_TYPE)."))),
                List.of("nodeTypes", "relationshipTypes", "patterns"));
    }

    private static DecomposedPromptTier effectiveTier(DecomposedPromptTier configuredTier) {
        return configuredTier == null || configuredTier == DecomposedPromptTier.AUTO
                ? DecomposedPromptTier.STANDARD : configuredTier;
    }

    private static int inlineSchemaEnumLimit(DecomposedPromptTier tier) {
        return switch (tier) {
            case COMPACT -> 8;
            case AUTO, STANDARD -> 12;
            case RICH -> 16;
            case EXPANDED -> 32;
        };
    }

    private static int schemaDefinitionLimit(DecomposedPromptTier tier) {
        return switch (tier) {
            case COMPACT -> 0;
            case AUTO, STANDARD -> 12;
            case RICH -> 24;
            case EXPANDED -> 64;
        };
    }

    private boolean entitiesOnly() {
        return extractionTarget == ExtractionTarget.ENTITIES_ONLY;
    }

    private boolean typedEntitiesOnly() {
        return extractionTarget == ExtractionTarget.TYPED_ENTITIES_ONLY;
    }

    private boolean relationsOnly() {
        return extractionTarget == ExtractionTarget.RELATIONS_ONLY;
    }

    private GraphSchema schema() {
        return ontology.snapshot();
    }

    private Set<String> allowedEntityTypes() {
        return strictEntityTypes.isEmpty() ? schema().getAllNodeLabels() : strictEntityTypes;
    }

    private Set<String> allowedRelationTypes() {
        return strictRelationTypes.isEmpty()
                ? schema().getAllRelationshipTypes() : strictRelationTypes;
    }

    private boolean requiresDescriptions() {
        return policy.effectiveFailureMode()
                == GraphExtractionValidationPolicy.FailureMode.RETRY
                && policy.isValidatorEnabled(GraphExtractionValidationPolicy.REQUIRED_DESCRIPTIONS);
    }

    private List<String> requiredEntityFields() {
        return requiresDescriptions()
                ? List.of("id", "name", "type", "description")
                : List.of("id", "name", "type");
    }

    private List<String> requiredRelationFields() {
        return requiresDescriptions()
                ? List.of("source", "target", "type", "description")
                : List.of("source", "target", "type");
    }

    private Map<String, Object> schemaVocabulary(
            UnifiedGraph graph,
            DecomposedPromptTier tier,
            ExtractionTaskContext taskContext) {
        Set<String> entityTypes = new LinkedHashSet<>();
        Set<String> relationTypes = new LinkedHashSet<>();
        Set<String> relationPatterns = new LinkedHashSet<>();
        GraphSchema currentSchema = schema();
        boolean standardizedEntityTypes = !currentSchema.getAllNodeLabels().isEmpty();
        boolean standardizedRelationTypes = currentSchema.getRelationshipTypes() != null;

        if (standardizedEntityTypes) {
            entityTypes.addAll(currentSchema.getAllNodeLabels());
        } else {
            for (GraphEntity entity : graph.entities()) {
                if (entity != null && entity.type() != null && !entity.type().isBlank()) {
                    entityTypes.add(entity.type());
                }
            }
        }
        if (standardizedRelationTypes) {
            relationTypes.addAll(currentSchema.getAllRelationshipTypes());
        } else {
            graph.relations().forEach(relation -> {
                if (relation != null && relation.type() != null && !relation.type().isBlank()) {
                    relationTypes.add(relation.type());
                }
            });
        }
        if (currentSchema.getPatterns() != null) {
            currentSchema.getPatterns().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .forEach(relationPatterns::add);
        }
        policy.effectiveRelationPatterns().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .forEach(relationPatterns::add);
        if (entityTypes.isEmpty() && relationTypes.isEmpty() && relationPatterns.isEmpty()) {
            return Map.of();
        }

        List<String> allEntityTypes = entityTypes.stream().sorted().toList();
        List<String> allRelationTypes = relationTypes.stream().sorted().toList();
        List<String> allRelationPatterns = relationPatterns.stream().sorted().toList();
        SchemaFocus focus = schemaFocus(
                taskContext, allEntityTypes, allRelationTypes, allRelationPatterns);
        Map<String, Object> vocabulary = new LinkedHashMap<>();
        vocabulary.put("ontologyRevision", ontology.revision());
        // Initial context is vocabulary, not a source of candidate facts. Keep every
        // authoritative label, but leave descriptions, aliases, examples, and (at the compact
        // tier) the long endpoint-signature list behind the deterministic SCHEMA operation.
        vocabulary.put("entityTypes", allEntityTypes);
        vocabulary.put("relationTypes", allRelationTypes);
        if (tier == DecomposedPromptTier.COMPACT) {
            vocabulary.put("relationPatterns",
                    focus.active() ? focus.relationPatterns() : List.of());
            vocabulary.put("compact", true);
            vocabulary.put("fullCounts", Map.of(
                    "entityTypes", allEntityTypes.size(),
                    "relationTypes", allRelationTypes.size(),
                    "relationPatterns", allRelationPatterns.size()));
            vocabulary.put("detailedDefinitionsAvailableVia", Map.of(
                    "tool", GRAPH_REASONING_QUERY,
                    "operation", "SCHEMA"));
        } else {
            if (!currentSchema.getNodeParentTypes().isEmpty()) {
                vocabulary.put("entityTypeParents", currentSchema.getNodeParentTypes());
            }
            if (!currentSchema.getRelationshipConnectionFamilies().isEmpty()) {
                vocabulary.put("relationTypeFamilies",
                        currentSchema.getRelationshipConnectionFamilies());
                vocabulary.put("relationFamilyRule",
                        "Emit the specific relation type, never its connection-family value.");
            }
            vocabulary.put("relationPatterns", allRelationPatterns);
            int definitionLimit = schemaDefinitionLimit(tier);
            List<Map<String, Object>> nodeDefinitions =
                    entityDefinitions(tier, definitionLimit, null);
            List<Map<String, Object>> relationshipDefinitions =
                    relationDefinitions(tier, definitionLimit, null);
            if (!nodeDefinitions.isEmpty()) {
                vocabulary.put("nodeDefinitions", nodeDefinitions);
            }
            if (!relationshipDefinitions.isEmpty()) {
                vocabulary.put("relationshipDefinitions", relationshipDefinitions);
            }
            if (nodeDefinitions.size() < allEntityTypes.size()
                    || relationshipDefinitions.size() < allRelationTypes.size()) {
                vocabulary.put("detailedDefinitionsAvailableVia", Map.of(
                        "tool", GRAPH_REASONING_QUERY,
                        "operation", "SCHEMA"));
            }
        }
        vocabulary.put("authoritative",
                standardizedEntityTypes || standardizedRelationTypes);
        if (focus.active()) {
            vocabulary.put("focusedByConceptHints", true);
            vocabulary.put("focusIsEvidence", false);
            vocabulary.put("focus", Map.of(
                    "entityTypes", focus.entityTypes(),
                    "relationTypes", focus.relationTypes(),
                    "relationPatterns", focus.relationPatterns()));
            if (tier != DecomposedPromptTier.COMPACT) {
                vocabulary.put("fullCounts", Map.of(
                        "entityTypes", allEntityTypes.size(),
                        "relationTypes", allRelationTypes.size(),
                        "relationPatterns", allRelationPatterns.size()));
                vocabulary.put("detailedDefinitionsAvailableVia", Map.of(
                        "tool", GRAPH_REASONING_QUERY,
                        "operation", "SCHEMA"));
            }
        }
        vocabulary.put("truncated", tier == DecomposedPromptTier.COMPACT
                && !allRelationPatterns.isEmpty() && !focus.active());
        return vocabulary;
    }

    private List<ConceptCandidate> conceptCandidates(
            ExtractionTaskContext taskContext,
            List<String> presentedEntityTypes,
            int limit) {
        if (taskContext == null || taskContext.conceptHints().isEmpty()
                || presentedEntityTypes == null || presentedEntityTypes.isEmpty()) {
            return List.of();
        }
        Map<String, String> canonicalEntityTypes = canonicalTypes(presentedEntityTypes);
        LinkedHashMap<String, ConceptCandidate> candidates = new LinkedHashMap<>();
        for (ConceptHint hint : taskContext.conceptHints()) {
            String name = hint == null || hint.term() == null ? null : hint.term().trim();
            String normalizedCategory = hint == null ? null : normalizedType(hint.category());
            String type = normalizedCategory == null
                    ? null : canonicalEntityTypes.get(normalizedCategory);
            if (name == null || name.isBlank() || type == null) {
                continue;
            }
            String key = normalizedCategory + "|" + name.toLowerCase(Locale.ROOT);
            candidates.putIfAbsent(key, new ConceptCandidate(name, type));
            if (candidates.size() >= limit) {
                break;
            }
        }
        return List.copyOf(candidates.values());
    }

    private SchemaFocus schemaFocus(
            ExtractionTaskContext taskContext,
            List<String> allEntityTypes,
            List<String> allRelationTypes,
            List<String> allRelationPatterns) {
        if (taskContext == null || taskContext.conceptHints().isEmpty()
                || allEntityTypes.isEmpty()) {
            return SchemaFocus.empty();
        }

        Map<String, String> canonicalEntityTypes = canonicalTypes(allEntityTypes);
        Map<String, String> canonicalRelationTypes = canonicalTypes(allRelationTypes);
        LinkedHashSet<String> hintedTypes = new LinkedHashSet<>();
        for (ConceptHint hint : taskContext.conceptHints()) {
            String category = hint == null ? null : normalizedType(hint.category());
            if (category != null && canonicalEntityTypes.containsKey(category)) {
                hintedTypes.add(category);
            }
        }
        if (hintedTypes.isEmpty()) {
            return SchemaFocus.empty();
        }

        List<String> exactPatterns = new ArrayList<>();
        List<String> partialPatterns = new ArrayList<>();
        for (String expression : allRelationPatterns) {
            GraphExtractionValidator.parseRelationPattern(expression).ifPresent(signature -> {
                boolean sourceHinted = hintedTypes.contains(normalizedType(signature.sourceType()));
                boolean targetHinted = hintedTypes.contains(normalizedType(signature.targetType()));
                if (sourceHinted && targetHinted) {
                    exactPatterns.add(expression);
                } else if (sourceHinted || targetHinted) {
                    partialPatterns.add(expression);
                }
            });
        }
        List<String> selectedPatterns = exactPatterns.isEmpty() ? partialPatterns : exactPatterns;
        LinkedHashSet<String> focusedEntities = new LinkedHashSet<>();
        hintedTypes.stream().map(canonicalEntityTypes::get).forEach(focusedEntities::add);
        LinkedHashSet<String> focusedRelations = new LinkedHashSet<>();
        for (String expression : selectedPatterns) {
            GraphExtractionValidator.parseRelationPattern(expression).ifPresent(signature -> {
                String sourceType = canonicalEntityTypes.get(normalizedType(signature.sourceType()));
                String targetType = canonicalEntityTypes.get(normalizedType(signature.targetType()));
                String relationType = canonicalRelationTypes.get(
                        normalizedType(signature.relationType()));
                if (sourceType != null) {
                    focusedEntities.add(sourceType);
                }
                if (targetType != null) {
                    focusedEntities.add(targetType);
                }
                if (relationType != null) {
                    focusedRelations.add(relationType);
                }
            });
        }
        return new SchemaFocus(
                List.copyOf(focusedEntities),
                List.copyOf(focusedRelations),
                List.copyOf(selectedPatterns));
    }

    private static Map<String, String> canonicalTypes(List<String> values) {
        Map<String, String> canonical = new LinkedHashMap<>();
        for (String value : values) {
            String normalized = normalizedType(value);
            if (normalized != null) {
                canonical.putIfAbsent(normalized, value.trim());
            }
        }
        return canonical;
    }

    private static String normalizedType(String value) {
        return value == null || value.isBlank()
                ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private List<Map<String, Object>> entityDefinitions(
            DecomposedPromptTier tier, int limit, Set<String> includedTypes) {
        GraphSchema currentSchema = schema();
        if (currentSchema.getNodeTypes() == null) {
            return List.of();
        }
        return currentSchema.getNodeTypes().stream()
                .filter(type -> type != null && type.getLabel() != null
                        && !type.getLabel().isBlank())
                .filter(type -> includedTypes == null || includedTypes.isEmpty()
                        || includedTypes.contains(type.getLabel()))
                .sorted(Comparator.comparing(NodeType::getLabel))
                .limit(limit)
                .map(type -> {
                    Map<String, Object> definition = new LinkedHashMap<>();
                    definition.put("type", type.getLabel());
                    if (type.getParentType() != null && !type.getParentType().isBlank()) {
                        definition.put("parentType", type.getParentType().trim());
                    }
                    if (type.getDescription() != null && !type.getDescription().isBlank()) {
                        definition.put("description", type.getDescription().trim());
                    }
                    if (tierRank(tier) >= tierRank(DecomposedPromptTier.RICH)) {
                        List<String> properties = compactProperties(type.getProperties());
                        if (!properties.isEmpty()) {
                            definition.put("properties", properties);
                        }
                    }
                    return definition;
                })
                .toList();
    }

    private List<Map<String, Object>> relationDefinitions(
            DecomposedPromptTier tier, int limit, Set<String> includedTypes) {
        GraphSchema currentSchema = schema();
        if (currentSchema.getRelationshipTypes() == null) {
            return List.of();
        }
        return currentSchema.getRelationshipTypes().stream()
                .filter(type -> type != null && type.getType() != null
                        && !type.getType().isBlank())
                .filter(type -> includedTypes == null || includedTypes.isEmpty()
                        || includedTypes.contains(type.getType()))
                .sorted(Comparator.comparing(RelationshipType::getType))
                .limit(limit)
                .map(type -> {
                    Map<String, Object> definition = new LinkedHashMap<>();
                    definition.put("type", type.getType());
                    if (type.getConnectionFamily() != null
                            && !type.getConnectionFamily().isBlank()) {
                        definition.put("connectionFamily",
                                type.getConnectionFamily().trim());
                    }
                    if (type.getDescription() != null && !type.getDescription().isBlank()) {
                        definition.put("description", type.getDescription().trim());
                    }
                    if (type.getAliases() != null && !type.getAliases().isEmpty()) {
                        definition.put("aliases", type.getAliases());
                    }
                    if (tierRank(tier) >= tierRank(DecomposedPromptTier.RICH)) {
                        List<String> properties = compactProperties(type.getProperties());
                        if (!properties.isEmpty()) {
                            definition.put("properties", properties);
                        }
                    }
                    return definition;
                })
                .toList();
    }

    private static List<String> compactProperties(List<PropertyType> properties) {
        if (properties == null || properties.isEmpty()) {
            return List.of();
        }
        return properties.stream()
                .filter(property -> property != null && property.getName() != null
                        && !property.getName().isBlank())
                .map(property -> property.getName()
                        + (property.getType() == null || property.getType().isBlank()
                                ? "" : ":" + property.getType()))
                .sorted()
                .toList();
    }

    private static Map<String, Object> stringSchema(String description) {
        return stringSchema(description, MAX_GRAPH_TEXT_CHARS);
    }

    private static Map<String, Object> stringSchema(String description, int maxLength) {
        return Map.of(
                "type", "string",
                "description", description,
                "maxLength", maxLength);
    }

    private String compactEntityTypeDescription(GraphSchema schema) {
        Set<String> allowedTypes = allowedEntityTypes();
        List<String> definitions = schema == null || schema.getNodeTypes() == null
                ? List.of()
                : schema.getNodeTypes().stream()
                        .filter(type -> type != null && type.getLabel() != null
                                && !type.getLabel().isBlank())
                        .filter(type -> allowedTypes.contains(normalizedType(type.getLabel())))
                        .map(type -> typeDefinition(type.getLabel(), type.getDescription()))
                        .filter(value -> !value.isBlank())
                        .sorted()
                        .toList();
        return appendBoundedTypeDefinitions(
                "Classify this exact name independently from its Text evidence. Re-read the sentence containing "
                        + "the name, identify what its referent is, and match that meaning to exactly one ontology "
                        + "node label. Enum order, row count, and covering every label are not evidence; never reuse "
                        + "a name under a second type. Relation labels are invalid.",
                definitions);
    }

    private String compactRelationTypeDescription(GraphSchema schema) {
        Set<String> allowedTypes = allowedRelationTypes();
        List<String> definitions = schema == null || schema.getRelationshipTypes() == null
                ? List.of()
                : schema.getRelationshipTypes().stream()
                        .filter(type -> type != null && type.getType() != null
                                && !type.getType().isBlank())
                        .filter(type -> allowedTypes.contains(normalizedType(type.getType())))
                        .map(type -> relationTypeDefinition(type, schema))
                        .filter(value -> !value.isBlank())
                        .sorted()
                        .toList();
        return appendBoundedTypeDefinitions(
                "Classify the directed predicate stated by Text. Use one ontology relation label and its endpoint pattern.",
                definitions);
    }

    private static String relationTypeDefinition(RelationshipType type, GraphSchema schema) {
        String definition = typeDefinition(type.getType(), type.getDescription());
        if (schema == null || schema.getPatterns() == null || schema.getPatterns().isEmpty()) {
            return definition;
        }
        String marker = "[:" + type.getType().strip() + "]";
        List<String> patterns = schema.getPatterns().stream()
                .filter(pattern -> pattern != null && !pattern.isBlank() && pattern.contains(marker))
                .map(String::strip)
                .sorted()
                .toList();
        return patterns.isEmpty()
                ? definition
                : definition + " | directed endpoints " + String.join(", ", patterns);
    }

    private static String typeDefinition(String label, String description) {
        if (label == null || label.isBlank()) {
            return "";
        }
        if (description == null || description.isBlank()) {
            return label.strip();
        }
        String normalizedDescription = String.join(" ", description.strip().split("\\s+"));
        return label.strip() + " = " + normalizedDescription;
    }

    private static String appendBoundedTypeDefinitions(String base, List<String> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return base;
        }
        StringBuilder result = new StringBuilder(base).append(" Ontology definitions: ");
        boolean appended = false;
        for (String definition : definitions) {
            String separator = appended ? "; " : "";
            if (result.length() + separator.length() + definition.length()
                    > MAX_COMPACT_TYPE_GUIDE_CHARS) {
                break;
            }
            result.append(separator).append(definition);
            appended = true;
        }
        return appended ? result.append('.').toString() : base;
    }

    private static Map<String, Object> standardizedTypeSchema(
            String description, Set<String> allowedTypes, int inlineEnumLimit) {
        return standardizedTypeSchema(
                description, allowedTypes, inlineEnumLimit, List.of());
    }

    private static Map<String, Object> standardizedTypeSchema(
            String description,
            Set<String> allowedTypes,
            int inlineEnumLimit,
            List<String> preferredOrder) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "string");
        result.put("description", description);
        result.put("maxLength", MAX_GRAPH_TYPE_CHARS);
        List<String> ordered = orderedAllowedTypes(allowedTypes, preferredOrder);
        if (!ordered.isEmpty() && ordered.size() <= inlineEnumLimit) {
            result.put("enum", ordered);
        }
        return result;
    }

    /**
     * Positional phase tools already opt into a bounded source-derived proposal. Keep their
     * executable type value constrained as well, prioritizing candidate labels without turning
     * any one label into a per-row constant. The full authoritative vocabulary remains available
     * in tool context and is not narrowed for ordinary graph submission tools.
     */
    private static Map<String, Object> boundedPhasedTypeSchema(
            String description,
            Set<String> allowedTypes,
            int inlineEnumLimit,
            List<String> preferredOrder) {
        Map<String, Object> result = standardizedTypeSchema(
                description, allowedTypes, inlineEnumLimit, preferredOrder);
        if (result.containsKey("enum") || inlineEnumLimit <= 0) {
            return result;
        }
        List<String> ordered = orderedAllowedTypes(allowedTypes, preferredOrder);
        long requiredTypes = preferredOrder == null ? 0L : preferredOrder.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .filter(ordered::contains)
                .distinct()
                .count();
        if (requiredTypes > inlineEnumLimit) {
            return result;
        }
        if (!ordered.isEmpty()) {
            result.put("enum", ordered.stream().limit(inlineEnumLimit).toList());
        }
        return result;
    }

    private static List<String> orderedAllowedTypes(
            Set<String> allowedTypes, List<String> preferredOrder) {
        if (allowedTypes == null || allowedTypes.isEmpty()) {
            return List.of();
        }
        List<String> normalizedAllowed = allowedTypes.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .sorted()
                .toList();
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (preferredOrder != null) {
            for (String preferred : preferredOrder) {
                if (preferred == null) {
                    continue;
                }
                String normalized = preferred.trim();
                if (normalizedAllowed.contains(normalized)) {
                    ordered.add(normalized);
                }
            }
        }
        ordered.addAll(normalizedAllowed);
        return List.copyOf(ordered);
    }

    private static Map<String, Object> arraySchema(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }

    private static Map<String, Object> entityNamesSchema(String description) {
        Map<String, Object> item = new LinkedHashMap<>(
                stringSchema("One exact entity name copied from SOURCE."));
        item.put("maxLength", MAX_ENTITY_NAME_CHARS);

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", "array");
        value.put("description", description);
        value.put("items", item);
        value.put("maxItems", MAX_ENTITY_NAMES_PER_CALL);
        value.put("uniqueItems", true);
        return value;
    }

    private static Map<String, Object> arraySchema(
            Map<String, Object> items, String description) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", "array");
        value.put("description", description);
        value.put("items", items);
        return value;
    }

    private static Map<String, Object> stringMapSchema() {
        return Map.of(
                "type", "object",
                "additionalProperties", Map.of(
                        "type", "string",
                        "maxLength", MAX_GRAPH_TEXT_CHARS));
    }

    private static Map<String, Object> confidenceSchema() {
        return Map.of(
                "type", "number",
                "minimum", 0.0d,
                "maximum", 1.0d,
                "description", "Calibrated source-supported confidence.");
    }

    private static Map<String, Object> integerSchema(
            int minimum,
            int maximum,
            String description) {
        return Map.of(
                "type", "integer",
                "minimum", minimum,
                "maximum", maximum,
                "description", description);
    }

    private static int tierRank(DecomposedPromptTier tier) {
        return switch (tier) {
            case AUTO -> 0;
            case COMPACT -> 1;
            case STANDARD -> 2;
            case RICH -> 3;
            case EXPANDED -> 4;
        };
    }

    @Override
    public ToolExecution execute(String toolName, JsonNode arguments) {
        return execute(toolName, arguments, null);
    }

    @Override
    public ToolExecution execute(String toolName, JsonNode arguments, String sourceText) {
        String normalized = toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
        JsonNode args = arguments == null ? MAPPER.createObjectNode() : arguments;
        return switch (normalized) {
            case UNIFIED_CORPUS -> corpus(args);
            case GRAPH_REASONING_QUERY -> graphQuery(args);
            case UPDATE_ONTOLOGY -> updateOntology(args);
            case SUBMIT_GRAPH_DELTA -> submit(args, sourceText);
            case SUBMIT_ENTITIES -> submitEntities(args, sourceText);
            case SUBMIT_TYPED_ENTITIES -> submitTypedEntities(args, sourceText);
            case SUBMIT_RELATIONS -> submitRelations(args, sourceText);
            default -> ToolExecution.continuing(json(Map.of(
                    "ok", false,
                    "error", "unknown_tool",
                    "tool", toolName == null ? "" : toolName,
                    "available", entitiesOnly()
                            ? List.of(SUBMIT_ENTITIES)
                            : typedEntitiesOnly()
                                    ? List.of(SUBMIT_TYPED_ENTITIES)
                                    : relationsOnly()
                                            ? List.of(SUBMIT_RELATIONS)
                                            : List.of(UNIFIED_CORPUS, GRAPH_REASONING_QUERY,
                                                    UPDATE_ONTOLOGY, SUBMIT_GRAPH_DELTA))));
        };
    }

    @Override
    public Optional<ExtractionResult> acceptedResult() {
        if (!phaseResultComplete(accepted)) {
            return Optional.empty();
        }
        return Optional.ofNullable(accepted);
    }

    public CrawlCorpusSnapshot corpusSnapshot() {
        return corpus;
    }

    private ToolExecution corpus(JsonNode args) {
        String action = text(args, "action", "SEARCH").toUpperCase(Locale.ROOT);
        return switch (action) {
            case "SEARCH" -> searchCorpus(args);
            case "GET" -> getCorpusPassage(args);
            default -> ToolExecution.continuing(json(Map.of(
                    "ok", false,
                    "error", "unknown_corpus_action",
                    "available", List.of("SEARCH", "GET"))));
        };
    }

    private ToolExecution searchCorpus(JsonNode args) {
        String query = text(args, "query", null);
        if (query == null || query.isBlank()) {
            return ToolExecution.continuing(json(Map.of(
                    "ok", false,
                    "error", "query_required")));
        }
        int offset = Math.max(0, integer(args, "offset", 0));
        int limit = bounded(integer(args, "limit", 5), 1, MAX_CORPUS_RESULTS);

        EmbeddingStatus embedding = embeddingStatus();
        List<ScoredDocument> semantic = List.of();
        if (embedding.available()) {
            try {
                semantic = vectorStore.similaritySearchWithScores(
                        query, Math.max(24, offset + limit * 3), 0.0);
                embedding = embedding.markUsed();
            } catch (RuntimeException e) {
                embedding = embedding.failed(message(e));
            }
        }

        Map<String, CorpusHit> hits = new HashMap<>();
        int semanticRank = 0;
        for (ScoredDocument scored : semantic) {
            String id = passageId(scored);
            CrawlCorpusPassage passage = passagesById.get(id);
            if (passage == null) {
                continue;
            }
            semanticRank++;
            CorpusHit hit = hits.computeIfAbsent(id, ignored -> new CorpusHit(passage));
            hit.semanticScore = scored.score();
            hit.rankScore += reciprocalRank(semanticRank);
        }

        List<CorpusHit> lexical = new ArrayList<>();
        for (CrawlCorpusPassage passage : passagesById.values()) {
            double score = lexicalScore(query, passage.content());
            if (score > 0.0) {
                CorpusHit hit = hits.computeIfAbsent(passage.chunkId(), ignored -> new CorpusHit(passage));
                hit.lexicalScore = score;
                lexical.add(hit);
            }
        }
        lexical.sort(Comparator.comparingDouble((CorpusHit hit) -> hit.lexicalScore).reversed()
                .thenComparingInt(hit -> hit.passage.chunkIndex())
                .thenComparing(hit -> hit.passage.chunkId()));
        for (int i = 0; i < lexical.size(); i++) {
            lexical.get(i).rankScore += reciprocalRank(i + 1);
        }

        List<CorpusHit> ranked = new ArrayList<>(hits.values());
        ranked.sort(Comparator.comparingDouble((CorpusHit hit) -> hit.rankScore).reversed()
                .thenComparing(Comparator.comparingDouble(
                        (CorpusHit hit) -> hit.semanticScore).reversed())
                .thenComparingInt(hit -> hit.passage.chunkIndex())
                .thenComparing(hit -> hit.passage.chunkId()));

        int from = Math.min(offset, ranked.size());
        int to = Math.min(ranked.size(), from + limit);
        List<Map<String, Object>> results = new ArrayList<>();
        for (CorpusHit hit : ranked.subList(from, to)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chunkId", hit.passage.chunkId());
            row.put("chunkIndex", hit.passage.chunkIndex());
            row.put("score", hit.rankScore);
            row.put("semanticScore", hit.semanticScore);
            row.put("lexicalScore", hit.lexicalScore);
            String exactExcerpt = excerpt(hit.passage.content(), query, 900);
            row.put("exactExcerpt", exactExcerpt);
            retrievedEvidence.add(exactExcerpt);
            row.put("completeTextAvailable", true);
            row.put("metadata", hit.passage.metadata());
            results.add(row);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("snapshotId", corpus.snapshotId());
        response.put("corpusPassages", passagesById.size());
        response.put("incompletePassagesExcluded", incompletePassagesExcluded);
        response.put("embedding", embedding.asMap());
        response.put("offset", from);
        response.put("returned", results.size());
        response.put("totalMatches", ranked.size());
        response.put("results", results);
        response.put("guidance", "Use action=GET with chunkId to page exact passage text.");
        return ToolExecution.continuing(json(response));
    }

    private ToolExecution getCorpusPassage(JsonNode args) {
        String id = text(args, "chunkId", null);
        CrawlCorpusPassage passage = id == null ? null : passagesById.get(id);
        if (passage == null) {
            return ToolExecution.continuing(json(Map.of(
                    "ok", false,
                    "error", "passage_not_found",
                    "chunkId", id == null ? "" : id)));
        }
        int start = bounded(integer(args, "start", 0), 0, passage.content().length());
        int length = bounded(integer(args, "length", MAX_CORPUS_PAGE_CHARS),
                1, MAX_CORPUS_PAGE_CHARS);
        int end = Math.min(passage.content().length(), start + length);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("snapshotId", corpus.snapshotId());
        response.put("chunkId", passage.chunkId());
        response.put("chunkIndex", passage.chunkIndex());
        response.put("start", start);
        response.put("end", end);
        response.put("totalChars", passage.content().length());
        response.put("hasMore", end < passage.content().length());
        String exactText = passage.content().substring(start, end);
        response.put("exactText", exactText);
        retrievedEvidence.add(exactText);
        response.put("metadata", passage.metadata());
        return ToolExecution.continuing(json(response));
    }

    private ToolExecution updateOntology(JsonNode args) {
        if (!ontologyUpdatesAllowed) {
            return ToolExecution.continuing(json(Map.of(
                    "ok", false,
                    "error", "ontology_update_disabled_by_strict_schema")));
        }
        if (entitiesOnly()) {
            return ToolExecution.continuing(json(Map.of(
                    "ok", false,
                    "error", "ontology_update_unavailable_for_entities_only")));
        }
        if (args == null || !args.isObject()) {
            return ToolExecution.continuing(json(Map.of(
                    "ok", false,
                    "error", "invalid_ontology_update",
                    "detail", "Arguments must be an ontology overlay object.")));
        }
        try {
            GraphSchema overlay = MAPPER.treeToValue(args, GraphSchema.class);
            CrawlOntology.UpdateResult result = ontology.update(overlay);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", result.valid());
            response.put("updated", result.updated());
            response.put("ontologyRevision", result.revision());
            response.put("errors", result.errors());
            response.put("ontology", result.schema());
            response.put("guidance", result.valid()
                    ? "The ontology is authoritative immediately. Use the refreshed tool context and type enums on the next turn."
                    : "Correct the ontology definitions and endpoint patterns, then call update_ontology again.");
            return ToolExecution.continuing(json(response));
        } catch (Exception e) {
            return ToolExecution.continuing(json(Map.of(
                    "ok", false,
                    "error", "invalid_ontology_update",
                    "detail", message(e))));
        }
    }

    private ToolExecution ontologySchema() {
        GraphSchema currentSchema = schema();
        UnifiedGraph graph = graphSnapshot();
        Map<String, Object> schemaResult = new LinkedHashMap<>();
        schemaResult.put("ontologyRevision", ontology.revision());
        schemaResult.put("authoritative", true);
        schemaResult.put("nodeTypes", currentSchema.getNodeTypes() == null
                ? List.of() : currentSchema.getNodeTypes());
        schemaResult.put("relationshipTypes", currentSchema.getRelationshipTypes() == null
                ? List.of() : currentSchema.getRelationshipTypes());
        schemaResult.put("patterns", currentSchema.getPatterns() == null
                ? List.of() : currentSchema.getPatterns());
        schemaResult.put("observedGraphEntities", graph.entityCount());
        schemaResult.put("observedGraphRelations", graph.relationCount());
        schemaResult.put("guidance", "Use nodeTypes for entities and relationshipTypes plus directed patterns for relations. "
                + "SEARCH names, emails, and aliases before creating a duplicate identity.");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("graphEntities", graph.entityCount());
        response.put("graphRelations", graph.relationCount());
        response.put("result", schemaResult);
        return ToolExecution.continuing(json(response));
    }

    private ToolExecution graphQuery(JsonNode args) {
        if ("SCHEMA".equalsIgnoreCase(text(args, "operation", ""))) {
            return ontologySchema();
        }
        if (reasoningService == null) {
            return ToolExecution.continuing(json(Map.of(
                    "ok", false,
                    "error", "graph_reasoning_unavailable",
                    "detail", "GraphReasoningQueryService is not configured")));
        }
        try {
            GraphReasoningQueryService.QueryRequest raw =
                    MAPPER.treeToValue(args, GraphReasoningQueryService.QueryRequest.class);
            GraphReasoningQueryService.QueryRequest request =
                    new GraphReasoningQueryService.QueryRequest(
                            raw.factSheetId(),
                            raw.operation(),
                            raw.entityId(),
                            raw.targetId(),
                            raw.direction(),
                            raw.relationTypes(),
                            raw.maxDepth() == null ? null
                                    : bounded(raw.maxDepth(), 1, MAX_GRAPH_DEPTH),
                            raw.topK() == null ? null
                                    : bounded(raw.topK(), 1, MAX_GRAPH_TOP_K),
                            raw.queryEmbedding(),
                            raw.structural(),
                            raw.queryText(),
                            raw.question());
            UnifiedGraph graph = graphSnapshot();
            GraphQueryEngine.Result result = reasoningService.execute(graph, request);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", result.status() != GraphQueryEngine.Status.INVALID
                    && result.status() != GraphQueryEngine.Status.FAILED);
            response.put("graphEntities", graph.entityCount());
            response.put("graphRelations", graph.relationCount());
            response.put("result", result);
            return ToolExecution.continuing(json(response));
        } catch (Exception e) {
            return ToolExecution.continuing(json(Map.of(
                    "ok", false,
                    "error", "invalid_graph_query",
                    "detail", message(e),
                    "guidance", "Call graph_reasoning_query with operation=CAPABILITIES.")));
        }
    }

    private ToolExecution submitTypedEntities(JsonNode args, String sourceText) {
        if (!typedEntitiesOnly()) {
            return phaseToolUnavailable(SUBMIT_TYPED_ENTITIES);
        }
        ToolExecution shapeError = validatePhaseArguments(args, "entities");
        if (shapeError != null) {
            return shapeError;
        }
        ObjectNode compact = MAPPER.createObjectNode();
        compact.put(COMPACT_SUBMIT_FORMAT_FIELD, COMPACT_SUBMIT_FORMAT);
        compact.set("entities", args.get("entities").deepCopy());
        compact.putArray("relations");
        return submit(compact, sourceText);
    }

    private ToolExecution submitRelations(JsonNode args, String sourceText) {
        if (!relationsOnly()) {
            return phaseToolUnavailable(SUBMIT_RELATIONS);
        }
        ToolExecution shapeError = validatePhaseArguments(args, "relations");
        if (shapeError != null) {
            return shapeError;
        }
        ObjectNode compact = MAPPER.createObjectNode();
        compact.put(COMPACT_SUBMIT_FORMAT_FIELD, COMPACT_SUBMIT_FORMAT);
        var entities = compact.putArray("entities");
        for (ExtractedEntity entity : fixedPhaseEntities) {
            entities.addObject()
                    .put("name", entity.name())
                    .put("type", entity.type());
        }
        compact.set("relations", args.get("relations").deepCopy());
        return submit(compact, sourceText);
    }

    private ToolExecution validatePhaseArguments(JsonNode args, String requiredArray) {
        if (args == null || !args.isObject()) {
            return invalidPhaseSubmission(requiredArray,
                    "Arguments must be an object containing only " + requiredArray + ".");
        }
        List<String> keys = new ArrayList<>();
        args.fieldNames().forEachRemaining(keys::add);
        if (!Set.of(requiredArray).equals(new LinkedHashSet<>(keys))) {
            return invalidPhaseSubmission(requiredArray,
                    "Arguments must contain exactly the top-level key " + requiredArray + ".");
        }
        JsonNode values = args.get(requiredArray);
        if (values == null || !values.isArray()) {
            return invalidPhaseSubmission(requiredArray,
                    requiredArray + " must be an array.");
        }
        Integer minItems = "entities".equals(requiredArray)
                ? compactEntityMinItems : compactRelationMinItems;
        Integer maxItems = "entities".equals(requiredArray)
                ? compactEntityMaxItems : compactRelationMaxItems;
        if (minItems != null && values.size() < minItems) {
            return invalidPhaseSubmission(requiredArray,
                    requiredArray + " must contain at least " + minItems + " rows.");
        }
        if (maxItems != null && values.size() > maxItems) {
            return invalidPhaseSubmission(requiredArray,
                    requiredArray + " must contain at most " + maxItems + " rows.");
        }
        if ("entities".equals(requiredArray) && !compactEntityCandidates.isEmpty()) {
            for (int index = 0; index < values.size(); index++) {
                JsonNode row = values.get(index);
                String expectedName = compactEntityCandidates.get(index).get("name");
                if (!row.isObject() || !row.path("name").isTextual()
                        || !expectedName.equals(row.path("name").asText())) {
                    return invalidPhaseSubmission(requiredArray,
                            "entities[" + index + "].name must equal the immutable source candidate '"
                                    + expectedName + "'.");
                }
            }
        }
        if ("relations".equals(requiredArray) && !compactRelationCandidates.isEmpty()) {
            for (int index = 0; index < values.size(); index++) {
                JsonNode row = values.get(index);
                CompactRelationCandidate expected = compactRelationCandidates.get(index);
                Integer source = exactInteger(row.path("source"));
                Integer target = exactInteger(row.path("target"));
                if (!row.isObject() || source == null || target == null
                        || source != expected.source() || target != expected.target()) {
                    return invalidPhaseSubmission(requiredArray,
                            "relations[" + index + "] must use immutable endpoints source="
                                    + expected.source() + " and target=" + expected.target() + ".");
                }
            }
        }
        return null;
    }

    private static Integer exactInteger(JsonNode value) {
        if (value == null || !value.isNumber()) {
            return null;
        }
        try {
            return value.decimalValue().intValueExact();
        } catch (ArithmeticException invalidInteger) {
            return null;
        }
    }

    private ToolExecution invalidPhaseSubmission(String requiredArray, String detail) {
        return ToolExecution.continuing(json(Map.of(
                "ok", false,
                "error", "invalid_" + requiredArray + "_phase_submission",
                "detail", detail,
                "requiredTopLevel", List.of(requiredArray))));
    }

    private ToolExecution phaseToolUnavailable(String tool) {
        return ToolExecution.continuing(json(Map.of(
                "ok", false,
                "error", "phase_tool_unavailable",
                "tool", tool,
                "extractionTarget", extractionTarget.name())));
    }

    private ToolExecution submitEntities(JsonNode args, String sourceText) {
        if (!entitiesOnly()) {
            return ToolExecution.continuing(json(Map.of(
                    "ok", false,
                    "error", "entity_only_tool_unavailable")));
        }
        if (args == null || !args.isObject()) {
            return invalidEntitySubmission("Arguments must be an object containing names.");
        }
        List<String> unexpected = new ArrayList<>();
        args.fieldNames().forEachRemaining(key -> {
            if (!"names".equals(key)) {
                unexpected.add(key);
            }
        });
        if (!unexpected.isEmpty()) {
            return invalidEntitySubmission("Unexpected argument keys: " + unexpected);
        }
        JsonNode names = args.get("names");
        if (names == null || !names.isArray()) {
            return invalidEntitySubmission(
                    "names must be an array of exact SOURCE-name strings.");
        }

        Map<String, String> distinctNames = new LinkedHashMap<>();
        for (JsonNode value : names) {
            if (!value.isTextual()) {
                return invalidEntitySubmission(
                        "Every names entry must be an exact SOURCE-name string.");
            }
            String name = value.asText().strip();
            if (!name.isEmpty()) {
                distinctNames.putIfAbsent(canonicalEntityName(name), name);
            }
        }

        List<String> unsupportedNames = distinctNames.values().stream()
                .filter(name -> !sourceSupports(name, sourceText))
                .toList();
        if (!unsupportedNames.isEmpty()) {
            return invalidEntitySubmission(
                    "Names are not present in SOURCE or retrieved evidence: " + unsupportedNames);
        }

        List<ExtractedEntity> entities = distinctNames.values().stream()
                .map(name -> new ExtractedEntity(
                        stableEntityId(name),
                        name,
                        "ENTITY",
                        List.of(),
                        null,
                        null,
                        Map.of("ontologyStatus", "DEFERRED")))
                .toList();
        ExtractionMetadata metadata = ExtractionMetadata.forChunkInGraph(
                chunkId, documentId, model, graphId, parentGraphId);
        ExtractionResult staged = new ExtractionResult(
                GraphExtractionSchema.SCHEMA_VERSION, entities, List.of(), metadata);
        ValidationResult validation = GraphExtractionValidator.validate(
                staged, policy, null, Map.of());
        if (!validation.valid()) {
            return ToolExecution.continuing(json(Map.of(
                    "ok", false,
                    "error", "engine_entity_normalization_failed",
                    "errors", validation.errors(),
                    "warnings", validation.warnings())));
        }

        accepted = mergeAndValidateAccepted(metadata, entities, List.of());
        return ToolExecution.terminal(json(Map.of(
                "ok", true,
                "accepted", true,
                "entities", entities.size(),
                "relations", 0,
                "idsAndTypesOwnedByEngine", true,
                "ontologyTypingDeferred", true)));
    }

    private ToolExecution invalidEntitySubmission(String detail) {
        return ToolExecution.continuing(json(Map.of(
                "ok", false,
                "error", "invalid_entity_submission",
                "detail", detail,
                "requiredShape", Map.of(
                        "names", List.of("<exact SOURCE name>")))));
    }

    private static String canonicalEntityName(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFKC)
                .strip()
                .toLowerCase(Locale.ROOT);
    }

    private boolean sourceSupports(String value, String sourceText) {
        if (sourceText == null || sourceText.isBlank()) {
            return true;
        }
        String needle = normalizedEvidence(value);
        if (needle.isBlank()) {
            return false;
        }
        if (normalizedEvidence(sourceText).contains(needle)) {
            return true;
        }
        return retrievedEvidence.stream()
                .map(CrawlExtractionToolBackend::normalizedEvidence)
                .anyMatch(evidence -> evidence.contains(needle));
    }

    private static String normalizedEvidence(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static String stableEntityId(String name) {
        String canonical = canonicalEntityName(name);
        String slug = Normalizer.normalize(canonical, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^\\p{L}\\p{N}]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            slug = "entity";
        } else if (slug.length() > 48) {
            slug = slug.substring(0, 48).replaceAll("-+$", "");
        }
        String fingerprint = UUID.nameUUIDFromBytes(
                canonical.getBytes(StandardCharsets.UTF_8)).toString().substring(0, 8);
        return slug + "-" + fingerprint;
    }

    private ToolExecution submit(JsonNode args, String sourceText) {
        JsonNode submitted = args.has("delta") && args.get("delta").isObject()
                ? args.get("delta") : args;
        boolean compactWire = isCompactSubmission(submitted);
        if (compactWire) {
            ToolExecution shapeError = validateCompactSubmitShape(submitted);
            if (shapeError != null) {
                return shapeError;
            }
            submitted = expandCompactSubmission(submitted);
        } else {
            ToolExecution shapeError = validateSubmitShape(submitted);
            if (shapeError != null) {
                return shapeError;
            }
        }
        JsonNode delta = entitiesOnly() ? withEmptyRelations(submitted) : submitted;
        final ExtractionResult parsed;
        try {
            parsed = MAPPER.treeToValue(delta, ExtractionResult.class);
        } catch (Exception e) {
            return invalidSubmission("invalid_graph_delta_json", message(e), delta);
        }

        ExtractionMetadata metadata = ExtractionMetadata.forChunkInGraph(
                chunkId, documentId, model, graphId, parentGraphId);
        ExtractionResult staged = new ExtractionResult(
                GraphExtractionSchema.SCHEMA_VERSION,
                parsed.entities(),
                parsed.relations(),
                metadata);
        ValidationResult validation = GraphExtractionValidator.validate(
                staged, policy, schema(), knownEntityTypes());
        List<String> errors = new ArrayList<>(validation.errors());
        List<String> groundingErrors = sourceGroundingErrors(staged, sourceText);
        List<String> typeScopeErrors = strictTypeScopeErrors(staged);
        List<String> sourceAssertionErrors = sourceAssertionTypeErrors(staged);
        errors.addAll(groundingErrors);
        errors.addAll(typeScopeErrors);
        errors.addAll(sourceAssertionErrors);
        boolean valid = validation.valid() && groundingErrors.isEmpty()
                && typeScopeErrors.isEmpty() && sourceAssertionErrors.isEmpty();
        Admission admission = groundingErrors.isEmpty() && typeScopeErrors.isEmpty()
                ? admitValidatorCleanItems(staged)
                : new Admission(accepted, 0, 0);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", valid);
        response.put("entities", staged.entities().size());
        response.put("relations", staged.relations().size());
        response.put("errors", errors);
        response.put("warnings", validation.warnings());
        if (!valid && typedEntityAccumulationComplete(admission.accepted())) {
            response.put("ok", true);
            response.put("accepted", true);
            response.put("acceptedAcrossRepairRounds", true);
            response.put("entities", admission.accepted().entities().size());
            response.put("currentSubmissionErrors", List.copyOf(errors));
            response.put("errors", List.of());
            return ToolExecution.terminal(json(response));
        }
        if (!valid) {
            if (compactWire) {
                if (typedEntitiesOnly() || relationsOnly()) {
                    response.put("requiredShape", phaseSubmitContract());
                    Map<String, Object> correction = phaseCorrectionContext();
                    if (groundingErrors.isEmpty() && typeScopeErrors.isEmpty()) {
                        addValidatorCleanRepairSeed(correction, delta, staged, admission);
                    }
                    response.put("correction", correction);
                    response.put("guidance", typedEntitiesOnly()
                            ? "Keep validator-clean rows unchanged. Recheck Text, correct rejected positions, and "
                                    + "resubmit the complete positional entity array with submit_typed_entities."
                            : "Keep validator-clean rows unchanged. Recheck Text, independently remap both endpoint "
                                    + "names, and resubmit the complete positional relation array with submit_relations.");
                } else {
                    response.put("requiredShape", compactSubmitContract());
                    response.put("correction", compactCorrectionContext());
                    response.put("guidance",
                            "Recheck the text and resubmit named entities and relations with exact names and allowed types.");
                }
            } else {
                response.put("requiredShape", submitContract());
                Map<String, Object> correction = correctionContext(delta);
                if (groundingErrors.isEmpty()) {
                    addValidatorCleanRepairSeed(correction, delta, staged, admission);
                }
                response.put("correction", correction);
                response.put("guidance",
                        "If correction.alreadyRetained is present, it is accepted, not evidence. Recheck SOURCE; "
                                + "repair or omit only rejected items, then resubmit with exact keys.");
            }
            return ToolExecution.continuing(json(response));
        }

        accepted = mergeAndValidateAccepted(staged.metadata(), staged.entities(), staged.relations());
        response.put("accepted", true);
        response.put("metadataOwnedByEngine", true);
        return ToolExecution.terminal(json(response));
    }

    private static boolean isCompactSubmission(JsonNode submitted) {
        if (submitted == null || !submitted.isObject()) {
            return false;
        }
        return COMPACT_SUBMIT_FORMAT.equals(
                submitted.path(COMPACT_SUBMIT_FORMAT_FIELD).asText());
    }

    private ToolExecution validateCompactSubmitShape(JsonNode submitted) {
        if (submitted == null || !submitted.isObject()) {
            return invalidCompactSubmission(
                    "invalid_compact_graph_delta_shape", "Arguments must be an object.", submitted);
        }
        List<String> keys = new ArrayList<>();
        submitted.fieldNames().forEachRemaining(keys::add);
        List<String> unexpected = keys.stream()
                .filter(key -> !Set.of(
                        COMPACT_SUBMIT_FORMAT_FIELD, "entities", "relations").contains(key))
                .sorted()
                .toList();
        if (!unexpected.isEmpty()) {
            return invalidCompactSubmission(
                    "invalid_compact_graph_delta_shape",
                    "Unexpected top-level argument keys: " + unexpected,
                    submitted);
        }
        if (!COMPACT_SUBMIT_FORMAT.equals(
                submitted.path(COMPACT_SUBMIT_FORMAT_FIELD).asText())) {
            return invalidCompactSubmission(
                    "invalid_compact_graph_delta_shape",
                    "format must be indexed.",
                    submitted);
        }
        JsonNode entities = submitted.get("entities");
        JsonNode relations = submitted.get("relations");
        if (entities == null || !entities.isArray()
                || relations == null || !relations.isArray()) {
            return invalidCompactSubmission(
                    "invalid_compact_graph_delta_shape",
                    "entities and relations must be arrays.",
                    submitted);
        }

        Map<String, String> idsByName = new LinkedHashMap<>();
        Set<String> generatedIds = new LinkedHashSet<>();
        for (int index = 0; index < entities.size(); index++) {
            JsonNode entity = entities.get(index);
            ToolExecution error = validateCompactObject(
                    entity, Set.of("name", "type"), "entities[" + index + "]", submitted);
            if (error != null) {
                return error;
            }
            String name = entity.path("name").asText().trim();
            String type = entity.path("type").asText().trim();
            if (name.isBlank() || type.isBlank()) {
                return invalidCompactSubmission(
                        "invalid_compact_graph_delta_shape",
                        "entities[" + index + "] name and type must be nonblank.",
                        submitted);
            }
            if (idsByName.containsKey(name)) {
                return invalidCompactSubmission(
                        "invalid_compact_graph_delta_shape",
                        "entities contains a duplicate exact name: " + name,
                        submitted);
            }
            String id = stableEntityId(name);
            if (!generatedIds.add(id)) {
                return invalidCompactSubmission(
                        "invalid_compact_graph_delta_shape",
                        "entities contains names that normalize to the same identity.",
                        submitted);
            }
            idsByName.put(name, id);
        }

        Set<String> relationKeys = new LinkedHashSet<>();
        for (int index = 0; index < relations.size(); index++) {
            JsonNode relation = relations.get(index);
            ToolExecution error = validateCompactObject(
                    relation, Set.of("source", "target", "type"),
                    "relations[" + index + "]", submitted);
            if (error != null) {
                return error;
            }
            JsonNode sourceNode = relation.path("source");
            JsonNode targetNode = relation.path("target");
            String type = relation.path("type").asText().trim();
            if (!sourceNode.canConvertToInt() || !targetNode.canConvertToInt() || type.isBlank()) {
                return invalidCompactSubmission(
                        "invalid_compact_graph_delta_shape",
                        "relations[" + index + "] source and target must be integer entity indices "
                                + "and type must be nonblank.",
                        submitted);
            }
            int source = sourceNode.asInt();
            int target = targetNode.asInt();
            if (source < 0 || source >= entities.size()
                    || target < 0 || target >= entities.size()) {
                return invalidCompactSubmission(
                        "invalid_compact_graph_delta_shape",
                        "relations[" + index + "] endpoint indices must reference entities.",
                        submitted);
            }
            String relationKey = source + ":" + type.length() + ":" + type + ":" + target;
            if (!relationKeys.add(relationKey)) {
                return invalidCompactSubmission(
                        "invalid_compact_graph_delta_shape",
                        "relations contains a duplicate relation at index " + index + ".",
                        submitted);
            }
        }
        return null;
    }

    private ToolExecution validateCompactObject(
            JsonNode value,
            Set<String> expectedKeys,
            String path,
            JsonNode submitted) {
        if (value == null || !value.isObject()) {
            return invalidCompactSubmission(
                    "invalid_compact_graph_delta_shape",
                    path + " must be an object.",
                    submitted);
        }
        List<String> keys = new ArrayList<>();
        value.fieldNames().forEachRemaining(keys::add);
        Set<String> actual = new LinkedHashSet<>(keys);
        if (!actual.equals(expectedKeys)) {
            return invalidCompactSubmission(
                    "invalid_compact_graph_delta_shape",
                    path + " must contain exactly " + expectedKeys + ".",
                    submitted);
        }
        return null;
    }

    private static Map<String, Object> compactEntityIndexSchema(
            String endpoint, Integer entityMaxItems) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "integer");
        schema.put("minimum", 0);
        String lookupRule = " For each relation independently, look up the exact Text " + endpoint
                + " name and verify this index maps back to that same name.";
        if (entityMaxItems != null && entityMaxItems > 0) {
            schema.put("maximum", entityMaxItems - 1);
            schema.put("description", "Zero-based " + endpoint
                    + " index in entities (0 through " + (entityMaxItems - 1) + ")." + lookupRule);
        } else {
            schema.put("description", "Zero-based " + endpoint + " index in entities." + lookupRule);
        }
        return schema;
    }

    private static JsonNode expandCompactSubmission(JsonNode submitted) {
        ObjectNode canonical = MAPPER.createObjectNode();
        var entities = canonical.putArray("entities");
        List<String> idsByIndex = new ArrayList<>();
        for (JsonNode entity : submitted.path("entities")) {
            String name = entity.path("name").asText().trim();
            String id = stableEntityId(name);
            idsByIndex.add(id);
            entities.addObject()
                    .put("id", id)
                    .put("name", name)
                    .put("type", entity.path("type").asText().trim());
        }
        var relations = canonical.putArray("relations");
        for (JsonNode relation : submitted.path("relations")) {
            int source = relation.path("source").asInt();
            int target = relation.path("target").asInt();
            relations.addObject()
                    .put("source", idsByIndex.get(source))
                    .put("target", idsByIndex.get(target))
                    .put("type", relation.path("type").asText().trim());
        }
        return canonical;
    }

    private List<String> sourceGroundingErrors(
            ExtractionResult staged, String sourceText) {
        if (sourceText == null || sourceText.isBlank() || staged == null) {
            return List.of();
        }
        Set<String> knownIds = knownEntityTypes().keySet();
        List<String> errors = new ArrayList<>();
        for (int index = 0; index < staged.entities().size(); index++) {
            ExtractedEntity entity = staged.entities().get(index);
            if (entity == null || knownIds.contains(entity.id())) {
                continue;
            }
            if (!sourceSupports(entity.name(), sourceText)) {
                errors.add("[SOURCE_GROUNDING] entities[" + index
                        + "].name must be copied exactly from SOURCE or retrieved evidence.");
            }
        }
        return List.copyOf(errors);
    }

    private ToolExecution validateSubmitShape(JsonNode delta) {
        if (delta == null || !delta.isObject()) {
            return invalidSubmission("invalid_graph_delta_shape",
                    "Arguments must be an object.", delta);
        }
        List<String> keys = new ArrayList<>();
        delta.fieldNames().forEachRemaining(keys::add);
        Set<String> allowed = entitiesOnly()
                ? Set.of("entities") : Set.of("entities", "relations");
        List<String> unexpected = keys.stream()
                .filter(key -> !allowed.contains(key))
                .sorted()
                .toList();
        if (!unexpected.isEmpty()) {
            return invalidSubmission("invalid_graph_delta_shape",
                    "Unexpected top-level argument keys: " + unexpected, delta);
        }
        if (!delta.has("entities") || !delta.get("entities").isArray()) {
            return invalidSubmission("invalid_graph_delta_shape",
                    "entities must be an array.", delta);
        }
        if (!entitiesOnly()
                && (!delta.has("relations") || !delta.get("relations").isArray())) {
            return invalidSubmission("invalid_graph_delta_shape",
                    "Both entities and relations must be arrays.", delta);
        }
        return null;
    }

    private static JsonNode withEmptyRelations(JsonNode submitted) {
        ObjectNode normalized = ((ObjectNode) submitted).deepCopy();
        normalized.putArray("relations");
        return normalized;
    }

    private ToolExecution invalidSubmission(String code, String detail, JsonNode received) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", false);
        response.put("error", code);
        response.put("detail", detail == null ? "" : detail);
        response.put("requiredShape", submitContract());
        response.put("correction", correctionContext(received));
        if (received != null && received.isObject()) {
            List<String> keys = new ArrayList<>();
            received.fieldNames().forEachRemaining(keys::add);
            response.put("receivedTopLevelKeys", keys);
        }
        response.put("guidance",
                "Call submit_graph_delta again using the exact field names and omit unsupported optional fields.");
        return ToolExecution.continuing(json(response));
    }

    private ToolExecution invalidCompactSubmission(
            String code, String detail, JsonNode received) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", false);
        response.put("error", code);
        response.put("detail", detail == null ? "" : detail);
        response.put("requiredShape", compactSubmitContract());
        response.put("correction", compactCorrectionContext());
        if (received != null && received.isObject()) {
            List<String> keys = new ArrayList<>();
            received.fieldNames().forEachRemaining(keys::add);
            response.put("receivedTopLevelKeys", keys);
        }
        response.put("guidance",
                "Re-read SOURCE and resubmit the complete indexed graph delta. Use every distinct name once; "
                        + "relation source and target are zero-based indices into this submission's entities array.");
        return ToolExecution.continuing(json(response));
    }

    private Map<String, Object> compactSubmitContract() {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("topLevel",
                List.of(COMPACT_SUBMIT_FORMAT_FIELD, "entities", "relations"));
        contract.put("format", COMPACT_SUBMIT_FORMAT);
        contract.put("entityRequired", List.of("name", "type"));
        contract.put("relationRequired", List.of("source", "target", "type"));
        contract.put("endpointDomain",
                "relation endpoints are zero-based indices into submitted entities");
        contract.put("argumentTemplate", Map.of(
                COMPACT_SUBMIT_FORMAT_FIELD, COMPACT_SUBMIT_FORMAT,
                "entities", List.of(Map.of(
                        "name", "<exact text name>",
                        "type", "<allowed entity type>")),
                "relations", List.of(Map.of(
                        "source", 0,
                        "target", 1,
                        "type", "<allowed relation type>"))));
        contract.put("entityIdsOwnedByEngine", true);
        contract.put("metadataOwnedByEngine", true);
        return contract;
    }

    private Map<String, Object> compactCorrectionContext() {
        GraphSchema currentSchema = schema();
        Map<String, Object> correction = new LinkedHashMap<>();
        correction.put("action", "correct_and_resubmit_indexed_graph_delta");
        correction.put("allowedEntityTypes",
                currentSchema.getAllNodeLabels().stream().sorted().toList());
        correction.put("allowedRelationTypes",
                currentSchema.getAllRelationshipTypes().stream().sorted().toList());
        correction.put("endpointRule",
                "relation endpoints are zero-based indices into entities");
        return correction;
    }

    private Map<String, Object> phaseSubmitContract() {
        Map<String, Object> contract = new LinkedHashMap<>();
        if (typedEntitiesOnly()) {
            contract.put("topLevel", List.of("entities"));
            contract.put("entityRequired", List.of("name", "type"));
            contract.put("argumentTemplate", Map.of(
                    "entities", List.of(Map.of(
                            "name", "<exact Text name>",
                            "type", "<allowed entity type>"))));
            return contract;
        }
        contract.put("topLevel", List.of("relations"));
        contract.put("relationRequired", List.of("source", "target", "type"));
        contract.put("endpointDomain", "zero-based indices into the immutable phase-one entity table");
        contract.put("argumentTemplate", Map.of(
                "relations", List.of(Map.of(
                        "source", 0,
                        "target", 1,
                        "type", "<allowed relation type>"))));
        return contract;
    }

    private Map<String, Object> phaseCorrectionContext() {
        GraphSchema currentSchema = schema();
        Map<String, Object> correction = new LinkedHashMap<>();
        if (typedEntitiesOnly()) {
            correction.put("action", "correct_and_resubmit_submit_typed_entities");
            correction.put("requiredCall",
                    "submit_typed_entities with the complete positional entities array in immutable candidate order, "
                            + "using exact Text names and allowed ontology node labels");
            correction.put("allowedEntityTypes", allowedEntityTypes().stream().sorted().toList());
            correction.put("entityTypeGuide", compactEntityTypeDescription(currentSchema));
            correction.put("rules", List.of(
                    "Use each distinct exact Text referent once, preserving one source spelling.",
                    "For each name independently, re-read its sentence and identify what that referent is before matching one ontology node definition.",
                    "Enum order, requested row count, and unused labels are not type evidence; never reuse a name under a second type.",
                    "A type label, relation label, instruction, example, placeholder, alternate casing, or duplicate is not a missing entity.",
                    "Resubmit the complete positional table; keep validator-clean rows unchanged and correct rejected positions."));
            return correction;
        }
        List<Map<String, Object>> entityTable = new ArrayList<>();
        for (int index = 0; index < fixedPhaseEntities.size(); index++) {
            ExtractedEntity entity = fixedPhaseEntities.get(index);
            entityTable.add(Map.of(
                    "index", index,
                    "name", entity.name(),
                    "type", entity.type()));
        }
        correction.put("action", "correct_and_resubmit_submit_relations");
        correction.put("requiredCall",
                "submit_relations with the complete positional relations array, using the immutable integer endpoints "
                        + "and allowed ontology relation labels");
        correction.put("entities", entityTable);
        correction.put("allowedRelationTypes", allowedRelationTypes().stream().sorted().toList());
        correction.put("relationTypeGuide", compactRelationTypeDescription(currentSchema));
        correction.put("allowedRelationPatterns",
                currentSchema.getPatterns() == null ? List.of() : currentSchema.getPatterns());
        correction.put("rules", List.of(
                "For each explicit Text predicate, copy both endpoint names from the same sentence and independently map them to indices.",
                "Match the relation definition and directed endpoint pattern; source and target are semantic roles, not list order.",
                "Use no background knowledge and never substitute an endpoint from another predicate.",
                "Resubmit the complete positional table; keep validator-clean rows unchanged and correct rejected positions."));
        return correction;
    }

    private Map<String, Object> submitContract() {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("topLevel", entitiesOnly()
                ? List.of("entities") : List.of("entities", "relations"));
        contract.put("entityRequired", requiredEntityFields());
        if (!entitiesOnly()) {
            contract.put("relationRequired", requiredRelationFields());
        }
        Map<String, Object> entityTemplate = new LinkedHashMap<>();
        entityTemplate.put("id", "<stable-entity-id>");
        entityTemplate.put("name", "<source-supported-name>");
        entityTemplate.put("type", "<graphSchema.entityTypes value>");
        if (requiresDescriptions()) {
            entityTemplate.put("description", "<source-supported-description>");
        }
        Map<String, Object> relationTemplate = new LinkedHashMap<>();
        relationTemplate.put("source", "<entity id>");
        relationTemplate.put("target", "<entity id>");
        relationTemplate.put("type", "<graphSchema.relationTypes value>");
        if (requiresDescriptions()) {
            relationTemplate.put("description", "<source-supported-description>");
        }
        contract.put("argumentTemplate", entitiesOnly()
                ? Map.of("entities", List.of(entityTemplate))
                : Map.of(
                        "entities", List.of(entityTemplate),
                        "relations", List.of(relationTemplate)));
        contract.put("fieldDomains", entitiesOnly()
                ? Map.of("entities[].type", "graphSchema.entityTypes")
                : Map.of(
                        "entities[].type", "graphSchema.entityTypes",
                        "relations[].type", "graphSchema.relationTypes",
                        "relations[].source", "submitted or existing entity id",
                        "relations[].target", "submitted or existing entity id"));
        contract.put("schemaContext",
                "CURRENT GRAPH AND CORPUS STATE.graphSchema in the original request");
        contract.put("proposalCount", "unlimited");
        contract.put("metadataOwnedByEngine", true);
        return contract;
    }

    /**
     * Describe rejected facets without changing the proposal. The response is derived from the
     * same graph schema and current graph state exposed to the model, so a small model can repair
     * field-domain and endpoint mistakes without any domain-specific prompt vocabulary.
     */
    private Map<String, Object> correctionContext(JsonNode delta) {
        Map<String, Object> correction = new LinkedHashMap<>();
        correction.put("action", "correct_and_resubmit_submit_graph_delta");

        Map<String, Object> vocabulary = schemaVocabulary(
                graphSnapshot(), DecomposedPromptTier.COMPACT, activeTaskContext);
        Set<String> entityTypes = normalizedVocabulary(vocabulary.get("entityTypes"));
        Set<String> relationTypes = normalizedVocabulary(vocabulary.get("relationTypes"));
        Set<String> knownIds = new LinkedHashSet<>(knownEntityTypes().keySet());
        Set<String> submittedIds = new LinkedHashSet<>();
        List<Map<String, Object>> typeDomainConflicts = new ArrayList<>();

        JsonNode entities = delta == null ? null : delta.get("entities");
        if (entities != null && entities.isArray()) {
            for (int index = 0; index < entities.size(); index++) {
                JsonNode entity = entities.get(index);
                String id = text(entity, "id", null);
                if (id != null && !id.isBlank()) {
                    submittedIds.add(id);
                }
                addTypeDomainConflict(typeDomainConflicts,
                        "entities[" + index + "].type",
                        text(entity, "type", null),
                        entityTypes,
                        relationTypes,
                        "entityTypes",
                        "relationTypes");
            }
        }

        LinkedHashSet<String> missingEndpointIds = new LinkedHashSet<>();
        JsonNode relations = delta == null ? null : delta.get("relations");
        if (relations != null && relations.isArray()) {
            for (int index = 0; index < relations.size(); index++) {
                JsonNode relation = relations.get(index);
                for (String field : List.of("source", "target")) {
                    String endpoint = text(relation, field, null);
                    if (endpoint != null && !endpoint.isBlank()
                            && !submittedIds.contains(endpoint) && !knownIds.contains(endpoint)) {
                        missingEndpointIds.add(endpoint);
                    }
                }
                addTypeDomainConflict(typeDomainConflicts,
                        "relations[" + index + "].type",
                        text(relation, "type", null),
                        relationTypes,
                        entityTypes,
                        "relationTypes",
                        "entityTypes");
            }
        }

        correction.put("missingEndpointIds", List.copyOf(missingEndpointIds));
        correction.put("typeDomainConflicts", List.copyOf(typeDomainConflicts));
        correction.put("rules", List.of(
                "add every missing endpoint as an entity or use an existing entity id",
                "copy entity ids exactly into relation source and target",
                "choose entity type and relation type from their separate graphSchema domains",
                "follow graphSchema.relationPatterns for source type, relation type, and target type"));
        return correction;
    }

    /**
     * Build a deterministic, non-mutating repair seed from the subset of submitted entities and
     * relations that already satisfies the configured production validators. The seed is still an
     * LLM proposal, never evidence: the model must recheck it against SOURCE and explicitly
     * resubmit it. Invalid items stay outside the seed with item-level diagnostics, so one bad edge
     * cannot hide otherwise valid facts from a small model during the next fresh request.
     */
    private void addValidatorCleanRepairSeed(Map<String, Object> correction,
                                             JsonNode delta,
                                             ExtractionResult staged,
                                             Admission admission) {
        ExtractionResult retained = admission.accepted();
        if (retained != null && (!retained.entities().isEmpty() || !retained.relations().isEmpty())) {
            correction.put("alreadyRetained", Map.of(
                    "entityIds", retained.entities().stream().map(ExtractedEntity::id).toList(),
                    "relationAtoms", retained.relations().stream()
                            .map(CrawlExtractionToolBackend::relationAtom).toList()));
            correction.put("alreadyRetainedIsEvidence", false);
        }
        Map<String, String> knownTypes = knownEntityTypes();
        List<ExtractedEntity> retainedEntities = retained == null
                ? new ArrayList<>() : new ArrayList<>(retained.entities());
        List<ExtractedRelation> retainedRelations = retained == null
                ? new ArrayList<>() : new ArrayList<>(retained.relations());
        List<JsonNode> seedEntities = new ArrayList<>();
        List<JsonNode> seedRelations = new ArrayList<>();
        List<Integer> retainedEntityIndexes = new ArrayList<>();
        List<Integer> retainedRelationIndexes = new ArrayList<>();
        List<Map<String, Object>> rejectedEntities = new ArrayList<>();
        List<Map<String, Object>> rejectedRelations = new ArrayList<>();

        JsonNode submittedEntities = delta.path("entities");
        for (int index = 0; index < staged.entities().size(); index++) {
            List<ExtractedEntity> candidateEntities = mergeEntities(
                    retainedEntities, List.of(staged.entities().get(index)));
            ValidationResult itemValidation = withSourceAssertionValidation(
                    validateRepairCandidate(
                            candidateEntities, List.of(), staged.metadata(), knownTypes),
                    staged.entities().get(index), index);
            if (itemValidation.valid()) {
                retainedEntities = candidateEntities;
                retainedEntityIndexes.add(index);
                seedEntities.add(submittedEntities.get(index));
            } else {
                rejectedEntities.add(rejectedItem(index, itemValidation));
            }
        }

        JsonNode submittedRelations = delta.path("relations");
        for (int index = 0; index < staged.relations().size(); index++) {
            List<ExtractedRelation> candidateRelations = mergeRelations(
                    retainedRelations, List.of(staged.relations().get(index)));
            ValidationResult itemValidation = validateRepairCandidate(
                    retainedEntities, candidateRelations, staged.metadata(), knownTypes);
            if (itemValidation.valid()) {
                retainedRelations = candidateRelations;
                retainedRelationIndexes.add(index);
                seedRelations.add(submittedRelations.get(index));
            } else {
                rejectedRelations.add(rejectedItem(index, itemValidation));
            }
        }

        retainedEntities = orderTypedPhaseEntities(retainedEntities);
        Map<String, Object> repairSeed = new LinkedHashMap<>();
        repairSeed.put("entities", retainedEntities);
        repairSeed.put("relations", retainedRelations);
        ValidationResult seedValidation = validateRepairCandidate(
                retainedEntities, retainedRelations, staged.metadata(), knownTypes);

        correction.put("repairSeed", repairSeed);
        correction.put("repairSeedValid", seedValidation.valid());
        correction.put("repairSeedIsEvidence", false);
        correction.put("retainedEntityIndexes", List.copyOf(retainedEntityIndexes));
        correction.put("retainedRelationIndexes", List.copyOf(retainedRelationIndexes));
        correction.put("rejectedEntities", List.copyOf(rejectedEntities));
        correction.put("rejectedRelations", List.copyOf(rejectedRelations));
        correction.put("repairSeedGuidance",
                "This seed is already retained, not evidence. Recheck it against SOURCE but repair or "
                        + "omit only rejected items; retained entity ids may be referenced by corrected relations.");
    }

    private Admission admitValidatorCleanItems(ExtractionResult staged) {
        Map<String, String> knownTypes = knownEntityTypes();
        List<ExtractedEntity> entities = accepted == null
                ? new ArrayList<>() : new ArrayList<>(accepted.entities());
        List<ExtractedRelation> relations = accepted == null
                ? new ArrayList<>() : new ArrayList<>(accepted.relations());
        int admittedEntityItems = 0;
        int admittedRelationItems = 0;
        for (int index = 0; index < staged.entities().size(); index++) {
            ExtractedEntity entity = staged.entities().get(index);
            List<ExtractedEntity> validationCandidate = replaceEntityForValidation(entities, entity);
            ValidationResult itemValidation = withSourceAssertionValidation(
                    validateRepairCandidate(
                            validationCandidate, relations, staged.metadata(), knownTypes),
                    entity, index);
            if (itemValidation.valid()) {
                entities = mergeEntities(entities, List.of(entity));
                admittedEntityItems++;
            }
        }
        for (ExtractedRelation relation : staged.relations()) {
            List<ExtractedRelation> validationCandidate = replaceRelationForValidation(relations, relation);
            if (validateRepairCandidate(entities, validationCandidate, staged.metadata(), knownTypes).valid()) {
                relations = mergeRelations(relations, List.of(relation));
                admittedRelationItems++;
            }
        }
        entities = orderTypedPhaseEntities(entities);
        ExtractionResult merged = new ExtractionResult(GraphExtractionSchema.SCHEMA_VERSION,
                List.copyOf(entities), List.copyOf(relations), staged.metadata());
        if (!GraphExtractionValidator.validate(merged, policy, schema(), knownTypes).valid()) {
            return new Admission(accepted, 0, 0);
        }
        if (!merged.entities().isEmpty() || !merged.relations().isEmpty()) {
            accepted = merged;
        }
        return new Admission(accepted, admittedEntityItems, admittedRelationItems);
    }

    private ExtractionResult mergeAndValidateAccepted(ExtractionMetadata metadata,
                                                       List<ExtractedEntity> entities,
                                                       List<ExtractedRelation> relations) {
        List<ExtractedEntity> mergedEntities = orderTypedPhaseEntities(mergeEntities(
                accepted == null ? List.of() : accepted.entities(), entities));
        List<ExtractedRelation> mergedRelations = mergeRelations(
                accepted == null ? List.of() : accepted.relations(), relations);
        ExtractionResult merged = new ExtractionResult(GraphExtractionSchema.SCHEMA_VERSION,
                mergedEntities, mergedRelations, metadata);
        ValidationResult validation = GraphExtractionValidator.validate(
                merged,
                policy,
                entitiesOnly() ? null : schema(),
                entitiesOnly() ? Map.of() : knownEntityTypes());
        if (!validation.valid()) {
            throw new IllegalStateException("merged accepted result failed validation: "
                    + String.join("; ", validation.errors()));
        }
        return merged;
    }

    private static List<ExtractedEntity> replaceEntityForValidation(
            List<ExtractedEntity> existing, ExtractedEntity entity) {
        List<ExtractedEntity> candidate = new ArrayList<>();
        for (ExtractedEntity retained : existing) {
            if (!retained.id().equals(entity.id())) {
                candidate.add(retained);
            }
        }
        candidate.add(entity);
        return candidate;
    }

    private static List<ExtractedRelation> replaceRelationForValidation(
            List<ExtractedRelation> existing, ExtractedRelation relation) {
        String atom = relationAtom(relation);
        List<ExtractedRelation> candidate = new ArrayList<>();
        for (ExtractedRelation retained : existing) {
            if (!relationAtom(retained).equals(atom)) {
                candidate.add(retained);
            }
        }
        candidate.add(relation);
        return candidate;
    }

    private static List<ExtractedEntity> mergeEntities(List<ExtractedEntity> existing,
                                                        List<ExtractedEntity> additions) {
        Map<String, ExtractedEntity> byId = new LinkedHashMap<>();
        for (ExtractedEntity entity : existing) {
            if (entity != null && entity.id() != null && !entity.id().isBlank()) {
                ExtractorUtils.addEntity(byId, entity);
            }
        }
        for (ExtractedEntity entity : additions) {
            if (entity != null && entity.id() != null && !entity.id().isBlank()) {
                byId.merge(entity.id(), entity, ExtractorUtils::replaceEntity);
            }
        }
        return List.copyOf(byId.values());
    }

    private static List<ExtractedRelation> mergeRelations(List<ExtractedRelation> existing,
                                                           List<ExtractedRelation> additions) {
        Map<String, ExtractedRelation> byAtom = new LinkedHashMap<>();
        for (ExtractedRelation relation : existing) {
            if (relation != null) {
                ExtractorUtils.addRelation(byAtom, relation);
            }
        }
        for (ExtractedRelation relation : additions) {
            if (relation != null) {
                byAtom.merge(relationAtom(relation), relation, ExtractorUtils::replaceRelation);
            }
        }
        return List.copyOf(byAtom.values());
    }

    private static String relationAtom(ExtractedRelation relation) {
        return ExtractorUtils.relationKey(relation);
    }

    private record Admission(ExtractionResult accepted, int admittedEntityItems,
                             int admittedRelationItems) {
    }

    private ValidationResult validateRepairCandidate(List<ExtractedEntity> entities,
                                                     List<ExtractedRelation> relations,
                                                     ExtractionMetadata metadata,
                                                     Map<String, String> knownTypes) {
        ExtractionResult candidate = new ExtractionResult(
                GraphExtractionSchema.SCHEMA_VERSION,
                List.copyOf(entities),
                List.copyOf(relations),
                metadata);
        return GraphExtractionValidator.validate(candidate, policy, schema(), knownTypes);
    }

    private Map<String, Object> rejectedItem(int index, ValidationResult validation) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("index", index);
        item.put("errors", validation.errors().stream()
                .limit(policy.effectiveMaxErrorsInRetryPrompt())
                .toList());
        return item;
    }

    private static Set<String> normalizedVocabulary(Object values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (!(values instanceof Iterable<?>)) {
            return normalized;
        }
        for (Object value : (Iterable<?>) values) {
            String type = value == null ? null : normalizedType(value.toString());
            if (type != null) {
                normalized.add(type);
            }
        }
        return normalized;
    }

    private static Set<String> scopedTypes(List<String> requested, Set<String> authoritative) {
        Set<String> requestedTypes = normalizedVocabulary(requested);
        if (requestedTypes.isEmpty() || authoritative == null || authoritative.isEmpty()) {
            return Set.of();
        }
        Set<String> authoritativeTypes = normalizedVocabulary(authoritative);
        requestedTypes.retainAll(authoritativeTypes);
        return requestedTypes.isEmpty()
                ? Set.of() : java.util.Collections.unmodifiableSet(requestedTypes);
    }

    private List<String> strictTypeScopeErrors(ExtractionResult staged) {
        List<String> errors = new ArrayList<>();
        if (!strictEntityTypes.isEmpty()) {
            for (int index = 0; index < staged.entities().size(); index++) {
                String type = normalizedType(staged.entities().get(index).type());
                if (type != null && !strictEntityTypes.contains(type)) {
                    errors.add("[ENTITY_TYPE_SCOPE] $.entities[" + index + "].type '"
                            + staged.entities().get(index).type() + "' is outside the strict extraction types "
                            + strictEntityTypes);
                }
            }
        }
        if (!strictRelationTypes.isEmpty()) {
            for (int index = 0; index < staged.relations().size(); index++) {
                String type = normalizedType(staged.relations().get(index).type());
                if (type != null && !strictRelationTypes.contains(type)) {
                    errors.add("[RELATION_TYPE_SCOPE] $.relations[" + index + "].type '"
                            + staged.relations().get(index).type() + "' is outside the strict extraction types "
                            + strictRelationTypes);
                }
            }
        }
        return errors;
    }

    private List<String> sourceAssertionTypeErrors(ExtractionResult staged) {
        if (!typedEntitiesOnly() || compactEntityCandidates.isEmpty()) {
            return List.of();
        }
        List<String> errors = new ArrayList<>();
        int compared = Math.min(staged.entities().size(), compactEntityCandidates.size());
        for (int index = 0; index < compared; index++) {
            String error = sourceAssertionTypeError(staged.entities().get(index), index);
            if (error != null) {
                errors.add(error);
            }
        }
        return errors;
    }

    private ValidationResult withSourceAssertionValidation(
            ValidationResult validation, ExtractedEntity actual, int index) {
        String assertionError = sourceAssertionTypeError(actual, index);
        if (assertionError == null) {
            return validation;
        }
        List<String> errors = new ArrayList<>(validation.errors());
        errors.add(assertionError);
        return new ValidationResult(false, errors, validation.warnings());
    }

    private String sourceAssertionTypeError(ExtractedEntity actual, int index) {
        if (!typedEntitiesOnly() || index < 0 || index >= compactEntityCandidates.size()) {
            return null;
        }
        Map<String, String> asserted = compactEntityCandidates.get(index);
        String assertedName = asserted.get("name");
        String assertedType = normalizedType(asserted.get("type"));
        String actualName = actual == null ? null : actual.name();
        String actualType = actual == null ? null : normalizedType(actual.type());
        if (actualName == null || assertedName == null
                || !actualName.strip().equals(assertedName.strip())) {
            return null; // positional name const/schema validation owns this mismatch
        }
        if (assertedType != null && !assertedType.equals(actualType)) {
            return "[SOURCE_ENTITY_TYPE_ASSERTION] $.entities[" + index + "].type '"
                    + actual.type() + "' contradicts Text's explicit classification of '"
                    + assertedName + "' as " + assertedType;
        }
        return null;
    }

    private boolean typedEntityAccumulationComplete(ExtractionResult retained) {
        return typedEntitiesOnly()
                && compactEntityMinItems != null
                && compactEntityMinItems.equals(compactEntityMaxItems)
                && retained != null
                && retained.entities().size() == compactEntityMinItems
                && sourceAssertionTypeErrors(retained).isEmpty();
    }

    private boolean phaseResultComplete(ExtractionResult retained) {
        if (retained == null) {
            return false;
        }
        if (typedEntitiesOnly() && compactEntityMinItems != null
                && compactEntityMinItems.equals(compactEntityMaxItems)) {
            return retained.entities().size() == compactEntityMinItems
                    && sourceAssertionTypeErrors(retained).isEmpty();
        }
        if (relationsOnly() && compactRelationMinItems != null
                && compactRelationMinItems.equals(compactRelationMaxItems)) {
            return retained.relations().size() == compactRelationMinItems;
        }
        return true;
    }

    private List<ExtractedEntity> orderTypedPhaseEntities(List<ExtractedEntity> entities) {
        if (!typedEntitiesOnly() || compactEntityCandidates.isEmpty()
                || entities == null || entities.size() < 2) {
            return entities == null ? List.of() : List.copyOf(entities);
        }
        Map<String, Integer> orderById = new LinkedHashMap<>();
        for (int index = 0; index < compactEntityCandidates.size(); index++) {
            orderById.put(stableEntityId(compactEntityCandidates.get(index).get("name")), index);
        }
        List<ExtractedEntity> ordered = new ArrayList<>(entities);
        ordered.sort(java.util.Comparator.comparingInt(entity ->
                orderById.getOrDefault(entity.id(), Integer.MAX_VALUE)));
        return List.copyOf(ordered);
    }

    private static void addTypeDomainConflict(
            List<Map<String, Object>> conflicts,
            String path,
            String value,
            Set<String> expectedDomain,
            Set<String> otherDomain,
            String expectedDomainName,
            String otherDomainName) {
        String normalized = normalizedType(value);
        if (normalized == null || expectedDomain.isEmpty() || expectedDomain.contains(normalized)) {
            return;
        }
        Map<String, Object> conflict = new LinkedHashMap<>();
        conflict.put("path", path);
        conflict.put("value", value);
        conflict.put("expectedDomain", "graphSchema." + expectedDomainName);
        conflict.put("valueBelongsTo", otherDomain.contains(normalized)
                ? "graphSchema." + otherDomainName : "neither presented schema domain");
        conflicts.add(conflict);
    }

    private Map<String, String> knownEntityTypes() {
        Map<String, String> known = new LinkedHashMap<>();
        for (GraphEntity entity : graphSnapshot().entities()) {
            if (entity != null && entity.id() != null && !entity.id().isBlank()
                    && entity.type() != null && !entity.type().isBlank()) {
                known.put(entity.id(), entity.type());
            }
        }
        ExtractionResult retained = accepted;
        if (retained != null) {
            for (ExtractedEntity entity : retained.entities()) {
                if (entity != null && entity.id() != null && !entity.id().isBlank()
                        && entity.type() != null && !entity.type().isBlank()) {
                    known.putIfAbsent(entity.id(), entity.type());
                }
            }
        }
        return known;
    }

    private UnifiedGraph graphSnapshot() {
        if (graphSupplier == null) {
            return new UnifiedGraph();
        }
        UnifiedGraph graph = graphSupplier.get();
        return graph == null ? new UnifiedGraph() : graph;
    }

    private EmbeddingStatus embeddingStatus() {
        if (vectorStoreInitializationError != null
                && !vectorStoreInitializationError.isBlank()) {
            return new EmbeddingStatus(false, false, false,
                    "initialization_failed: " + vectorStoreInitializationError);
        }
        if (vectorStore == null) {
            return new EmbeddingStatus(false, false, false, "not_configured");
        }
        try {
            boolean available = vectorStore.isVectorStoreAvailable();
            boolean fallback = vectorStore.isUsingFallbackIndex();
            return new EmbeddingStatus(available, fallback, false,
                    available ? null : "backend_reported_unavailable");
        } catch (RuntimeException e) {
            return new EmbeddingStatus(false, false, false, message(e));
        }
    }

    private String passageId(ScoredDocument scored) {
        if (scored == null || scored.document() == null) {
            return null;
        }
        Document document = scored.document();
        if (passagesById.containsKey(document.getId())) {
            return document.getId();
        }
        Map<String, Object> metadata = document.getMetadata();
        if (metadata != null) {
            for (String key : List.of("chunk_id", "chunkId", "passage_id", "passageId", "id")) {
                Object value = metadata.get(key);
                if (value != null && passagesById.containsKey(value.toString())) {
                    return value.toString();
                }
            }
        }
        return null;
    }

    private static double lexicalScore(String query, String content) {
        if (content == null || content.isBlank()) {
            return 0.0;
        }
        String normalizedQuery = query.toLowerCase(Locale.ROOT).trim();
        String normalizedContent = content.toLowerCase(Locale.ROOT);
        Set<String> tokens = tokens(normalizedQuery);
        if (tokens.isEmpty()) {
            return normalizedContent.contains(normalizedQuery) ? 1.0 : 0.0;
        }
        int matched = 0;
        for (String token : tokens) {
            if (normalizedContent.contains(token)) {
                matched++;
            }
        }
        double overlap = (double) matched / tokens.size();
        if (normalizedContent.contains(normalizedQuery)) {
            overlap += 1.0;
        }
        return overlap;
    }

    private static Set<String> tokens(String value) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                token.append(c);
            } else if (!token.isEmpty()) {
                tokens.add(token.toString());
                token.setLength(0);
            }
        }
        if (!token.isEmpty()) {
            tokens.add(token.toString());
        }
        return tokens;
    }

    private static String excerpt(String content, String query, int maxChars) {
        if (content.length() <= maxChars) {
            return content;
        }
        int match = content.toLowerCase(Locale.ROOT)
                .indexOf(query.toLowerCase(Locale.ROOT));
        int start = match < 0 ? 0 : Math.max(0, match - maxChars / 3);
        int end = Math.min(content.length(), start + maxChars);
        return content.substring(start, end);
    }

    private static double reciprocalRank(int rank) {
        return 1.0 / (60.0 + Math.max(1, rank));
    }

    private static int bounded(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int integer(JsonNode node, String field, int fallback) {
        JsonNode value = node.get(field);
        return value != null && value.canConvertToInt() ? value.asInt() : fallback;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value != null && value.isValueNode() && !value.isNull()
                ? value.asText() : fallback;
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"serialization_failed\"}";
        }
    }

    private static String message(Throwable error) {
        return error == null || error.getMessage() == null || error.getMessage().isBlank()
                ? (error == null ? "unknown error" : error.getClass().getSimpleName())
                : error.getMessage();
    }

    private static final class CorpusHit {
        private final CrawlCorpusPassage passage;
        private double semanticScore;
        private double lexicalScore;
        private double rankScore;

        private CorpusHit(CrawlCorpusPassage passage) {
            this.passage = passage;
        }
    }

    private record ConceptCandidate(String name, String type) {

        private Map<String, Object> asContext() {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("name", name);
            context.put("type", type);
            return context;
        }
    }

    private record SchemaFocus(
            List<String> entityTypes,
            List<String> relationTypes,
            List<String> relationPatterns) {

        private SchemaFocus {
            entityTypes = entityTypes == null ? List.of() : List.copyOf(entityTypes);
            relationTypes = relationTypes == null ? List.of() : List.copyOf(relationTypes);
            relationPatterns = relationPatterns == null ? List.of() : List.copyOf(relationPatterns);
        }

        static SchemaFocus empty() {
            return new SchemaFocus(List.of(), List.of(), List.of());
        }

        boolean active() {
            return !entityTypes.isEmpty();
        }
    }

    private record EmbeddingStatus(
            boolean available,
            boolean fallbackIndex,
            boolean used,
            String error) {

        EmbeddingStatus markUsed() {
            return new EmbeddingStatus(available, fallbackIndex, true, error);
        }

        EmbeddingStatus failed(String detail) {
            return new EmbeddingStatus(false, fallbackIndex, false, detail);
        }

        Map<String, Object> asMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("configured", error == null || !"not_configured".equals(error));
            value.put("available", available);
            value.put("fallbackIndex", fallbackIndex);
            value.put("used", used);
            if (error != null) {
                value.put("error", error);
            }
            return value;
        }
    }
}
