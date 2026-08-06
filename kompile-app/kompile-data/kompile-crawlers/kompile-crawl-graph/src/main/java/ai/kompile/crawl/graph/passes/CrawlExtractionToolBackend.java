/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.crawl.graph.passes;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.crawl.graph.GraphExtractionConfig.DecomposedPromptTier;
import ai.kompile.core.crawl.graph.GraphExtractionValidationPolicy;
import ai.kompile.core.embeddings.ScoredDocument;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.graphrag.GraphConstructor.ConceptHint;
import ai.kompile.core.graphrag.GraphConstructor.ExtractionTaskContext;
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
import ai.kompile.crawl.graph.CrawlIndexTrackingCallback.CrawlCorpusPassage;
import ai.kompile.crawl.graph.CrawlIndexTrackingCallback.CrawlCorpusSnapshot;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.unified.GraphReasoningQueryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;

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
import java.util.function.Supplier;

/**
 * Production extraction tools backed by the pooled crawl corpus and the reasoning-ready graph.
 */
public final class CrawlExtractionToolBackend implements ExtractionToolBackend {

    public static final String UNIFIED_CORPUS = "unified_corpus";
    public static final String GRAPH_REASONING_QUERY = "graph_reasoning_query";
    public static final String SUBMIT_GRAPH_DELTA = "submit_graph_delta";

    private static final int MAX_CORPUS_RESULTS = 8;
    private static final int MAX_CORPUS_PAGE_CHARS = 4_000;
    private static final int MAX_GRAPH_TOP_K = 20;
    private static final int MAX_GRAPH_DEPTH = 8;
    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper();

    private final String chunkId;
    private final String documentId;
    private final String model;
    private final String graphId;
    private final String parentGraphId;
    private final GraphExtractionValidationPolicy policy;
    private final GraphSchema schema;
    private final CrawlCorpusSnapshot corpus;
    private final Map<String, CrawlCorpusPassage> passagesById;
    private final int incompletePassagesExcluded;
    private final VectorStore vectorStore;
    private final String vectorStoreInitializationError;
    private final Supplier<UnifiedGraph> graphSupplier;
    private final GraphReasoningQueryService reasoningService;

    private volatile ExtractionResult accepted;
    private volatile ExtractionTaskContext activeTaskContext;

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
                vectorStore, null, graphSupplier, reasoningService);
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
        this.chunkId = chunkId;
        this.documentId = documentId;
        this.model = model;
        this.graphId = graphId;
        this.parentGraphId = parentGraphId;
        this.policy = policy == null ? GraphExtractionValidationPolicy.defaults() : policy;
        this.schema = schema;
        this.vectorStore = vectorStore;
        this.vectorStoreInitializationError = vectorStoreInitializationError;
        this.graphSupplier = graphSupplier;
        this.reasoningService = reasoningService;

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

        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("version", "tiered-compact-v7");
        Map<String, Object> callShape = new LinkedHashMap<>();
        callShape.put("tool", SUBMIT_GRAPH_DELTA);
        callShape.put("args", Map.of("entities", List.of(), "relations", List.of()));
        catalog.put("callShape", callShape);

        boolean emptyGraph = graph.entityCount() == 0;
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("graphEntities", graph.entityCount());
        state.put("graphRelations", graph.relationCount());
        state.put("completeCorpusPassages", passagesById.size());
        state.put("graphState", emptyGraph ? "EMPTY" : "POPULATED");
        state.put("recommendedTool", emptyGraph ? SUBMIT_GRAPH_DELTA : GRAPH_REASONING_QUERY);
        state.put("next", emptyGraph
                ? "fill callShape arrays from the source and submit"
                : "query graph only for ambiguity, then submit source-supported additions");
        catalog.put("state", state);

        Map<String, Object> submit = new LinkedHashMap<>();
        submit.put("purpose", "finish by proposing all source-supported graph additions");
        List<String> entityFields = new ArrayList<>(List.of("id", "name", "type"));
        List<String> relationFields = new ArrayList<>(List.of("source", "target", "type"));
        if (requiresDescriptions()) {
            entityFields.add("description");
            relationFields.add("description");
        }
        if (tierRank(tier) >= tierRank(DecomposedPromptTier.RICH)) {
            entityFields.addAll(List.of("aliases", "confidence"));
            relationFields.add("confidence");
        }
        if (tierRank(tier) >= tierRank(DecomposedPromptTier.EXPANDED)) {
            entityFields.add("properties");
            relationFields.addAll(List.of("properties", "occurredAt"));
        }
        submit.put("entityFields", List.copyOf(entityFields));
        submit.put("relationFields", List.copyOf(relationFields));
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
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("graphEntities", graph.entityCount());
        state.put("graphRelations", graph.relationCount());
        state.put("completeCorpusPassages", passagesById.size());
        state.put("graphState", emptyGraph ? "EMPTY" : "POPULATED");
        state.put("recommendedFirstTool", emptyGraph
                ? SUBMIT_GRAPH_DELTA : GRAPH_REASONING_QUERY);
        state.put("submitArgumentKeys", List.of("entities", "relations"));
        state.put("entityRequiredFields", requiredEntityFields());
        state.put("relationRequiredFields", requiredRelationFields());
        Map<String, Object> vocabulary = schemaVocabulary(graph, tier, taskContext);
        if (!vocabulary.isEmpty()) {
            state.put("graphSchema", vocabulary);
        }
        state.put("guidance", emptyGraph
                ? "Extract all source-supported additions and submit them."
                : "Query existing graph state when identity or relation choice is ambiguous.");
        return json(state);
    }

    @Override
    public List<ToolDefinition> toolDefinitions(DecomposedPromptTier configuredTier) {
        DecomposedPromptTier tier = effectiveTier(configuredTier);
        int inlineEnumLimit = inlineSchemaEnumLimit(tier);
        Map<String, Object> entityProperties = new LinkedHashMap<>();
        entityProperties.put("id", stringSchema(
                "Stable entity identifier. Create one for every source-supported entity and "
                        + "reuse it exactly in relations[].source and relations[].target."));
        entityProperties.put("name", stringSchema(
                "Exact name stated in SOURCE or a retrieved passage; never copy a value that "
                        + "appears only in graph, schema, or hints."));
        entityProperties.put("type", standardizedTypeSchema(
                "Entity type from CURRENT GRAPH AND CORPUS STATE.graphSchema.entityTypes; "
                        + "query the graph SCHEMA operation when another source-supported type is needed.",
                schema == null ? Set.of() : schema.getAllNodeLabels(), inlineEnumLimit));
        if (requiresDescriptions()) {
            entityProperties.put("description",
                    stringSchema("Concise source-supported description."));
        }
        if (tierRank(tier) >= tierRank(DecomposedPromptTier.RICH)) {
            entityProperties.put("aliases", arraySchema(stringSchema("Source-supported alias.")));
            entityProperties.put("confidence", confidenceSchema());
        }
        if (tierRank(tier) >= tierRank(DecomposedPromptTier.EXPANDED)) {
            entityProperties.put("properties", stringMapSchema());
        }
        Map<String, Object> entity = objectSchema(entityProperties, requiredEntityFields());

        Map<String, Object> relationProperties = new LinkedHashMap<>();
        relationProperties.put("source", stringSchema(
                "Exact source entity id copied from entities[].id in this call or an existing "
                        + "current graph entity id; never a display name or type label."));
        relationProperties.put("target", stringSchema(
                "Exact target entity id copied from entities[].id in this call or an existing "
                        + "current graph entity id; never a display name or type label."));
        relationProperties.put("type", standardizedTypeSchema(
                "Directed relation type from CURRENT GRAPH AND CORPUS STATE.graphSchema.relationTypes; "
                        + "query the graph SCHEMA operation when another source-supported type is needed.",
                schema == null ? Set.of() : schema.getAllRelationshipTypes(), inlineEnumLimit));
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
                    stringSchema("Optional source-supported event time."));
        }
        Map<String, Object> relation = objectSchema(relationProperties, requiredRelationFields());
        Map<String, Object> submitProperties = new LinkedHashMap<>();
        submitProperties.put("entities", arraySchema(entity,
                "Every source-supported entity. Include an entity object for every relation endpoint "
                        + "that is not already an entity in the current graph."));
        submitProperties.put("relations", arraySchema(relation,
                "Every directed relation explicitly stated in SOURCE or a retrieved passage, "
                        + "between submitted or existing graph entity ids."));
        Map<String, Object> submitParameters = objectSchema(
                submitProperties, List.of("entities", "relations"));

        Map<String, Object> graphParameters = objectSchema(Map.ofEntries(
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

        Map<String, Object> corpusParameters = objectSchema(Map.ofEntries(
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

        ToolDefinition submit = new ToolDefinition(
                SUBMIT_GRAPH_DELTA,
                "Validate and stage every source-supported entity and relation addition. "
                        + "Arguments use exactly the top-level keys entities and relations. "
                        + "Metadata and graph mutation remain engine-owned; validation feedback "
                        + "must be corrected and resubmitted.",
                submitParameters);
        ToolDefinition graph = new ToolDefinition(
                GRAPH_REASONING_QUERY,
                "Inspect current graph identities, schema, embeddings, first-order logic, "
                        + "probabilistic structure, paths, facts, and explanations before resolving "
                        + "an ambiguous graph addition.",
                graphParameters);
        ToolDefinition corpusTool = new ToolDefinition(
                UNIFIED_CORPUS,
                "Retrieve exact supporting evidence from the preassembled unified corpus when the "
                        + "current source shard does not contain enough cross-source context.",
                corpusParameters);

        boolean emptyGraph = graphSnapshot().entityCount() == 0;
        return emptyGraph
                ? List.of(submit, graph, corpusTool)
                : List.of(graph, submit, corpusTool);
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
        boolean standardizedEntityTypes = schema != null && !schema.getAllNodeLabels().isEmpty();
        boolean standardizedRelationTypes =
                schema != null && !schema.getAllRelationshipTypes().isEmpty();

        if (standardizedEntityTypes) {
            entityTypes.addAll(schema.getAllNodeLabels());
        } else {
            for (GraphEntity entity : graph.entities()) {
                if (entity != null && entity.type() != null && !entity.type().isBlank()) {
                    entityTypes.add(entity.type());
                }
            }
        }
        if (standardizedRelationTypes) {
            relationTypes.addAll(schema.getAllRelationshipTypes());
        } else {
            graph.relations().forEach(relation -> {
                if (relation != null && relation.type() != null && !relation.type().isBlank()) {
                    relationTypes.add(relation.type());
                }
            });
        }
        if (schema != null && schema.getPatterns() != null) {
            schema.getPatterns().stream()
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
        // Initial context is vocabulary, not a source of candidate facts. Keep every
        // authoritative label and pattern, but leave descriptions, aliases, and examples
        // behind the SCHEMA operation.
        vocabulary.put("entityTypes", allEntityTypes);
        vocabulary.put("relationTypes", allRelationTypes);
        vocabulary.put("relationPatterns", allRelationPatterns);
        vocabulary.put("authoritative",
                standardizedEntityTypes || standardizedRelationTypes);
        if (focus.active()) {
            vocabulary.put("focusedByConceptHints", true);
            vocabulary.put("focusIsEvidence", false);
            vocabulary.put("focus", Map.of(
                    "entityTypes", focus.entityTypes(),
                    "relationTypes", focus.relationTypes(),
                    "relationPatterns", focus.relationPatterns()));
            vocabulary.put("fullCounts", Map.of(
                    "entityTypes", allEntityTypes.size(),
                    "relationTypes", allRelationTypes.size(),
                    "relationPatterns", allRelationPatterns.size()));
            vocabulary.put("detailedDefinitionsAvailableVia", Map.of(
                    "tool", GRAPH_REASONING_QUERY,
                    "operation", "SCHEMA"));
        }
        vocabulary.put("truncated", false);
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
        if (schema.getNodeTypes() == null) {
            return List.of();
        }
        return schema.getNodeTypes().stream()
                .filter(type -> type != null && type.getLabel() != null
                        && !type.getLabel().isBlank())
                .filter(type -> includedTypes == null || includedTypes.isEmpty()
                        || includedTypes.contains(type.getLabel()))
                .sorted(Comparator.comparing(NodeType::getLabel))
                .limit(limit)
                .map(type -> {
                    Map<String, Object> definition = new LinkedHashMap<>();
                    definition.put("type", type.getLabel());
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
        if (schema.getRelationshipTypes() == null) {
            return List.of();
        }
        return schema.getRelationshipTypes().stream()
                .filter(type -> type != null && type.getType() != null
                        && !type.getType().isBlank())
                .filter(type -> includedTypes == null || includedTypes.isEmpty()
                        || includedTypes.contains(type.getType()))
                .sorted(Comparator.comparing(RelationshipType::getType))
                .limit(limit)
                .map(type -> {
                    Map<String, Object> definition = new LinkedHashMap<>();
                    definition.put("type", type.getType());
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
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> standardizedTypeSchema(
            String description, Set<String> allowedTypes, int inlineEnumLimit) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "string");
        result.put("description", description);
        if (allowedTypes != null && !allowedTypes.isEmpty()
                && allowedTypes.size() <= inlineEnumLimit) {
            result.put("enum", allowedTypes.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .sorted()
                    .toList());
        }
        return result;
    }

    private static Map<String, Object> arraySchema(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
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
                "additionalProperties", Map.of("type", "string"));
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
        String normalized = toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
        JsonNode args = arguments == null ? MAPPER.createObjectNode() : arguments;
        return switch (normalized) {
            case UNIFIED_CORPUS -> corpus(args);
            case GRAPH_REASONING_QUERY -> graphQuery(args);
            case SUBMIT_GRAPH_DELTA -> submit(args);
            default -> ToolExecution.continuing(json(Map.of(
                    "ok", false,
                    "error", "unknown_tool",
                    "tool", toolName == null ? "" : toolName,
                    "available", List.of(UNIFIED_CORPUS, GRAPH_REASONING_QUERY, SUBMIT_GRAPH_DELTA))));
        };
    }

    @Override
    public Optional<ExtractionResult> acceptedResult() {
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
            row.put("exactExcerpt", excerpt(hit.passage.content(), query, 900));
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
        response.put("exactText", passage.content().substring(start, end));
        response.put("metadata", passage.metadata());
        return ToolExecution.continuing(json(response));
    }

    private ToolExecution graphQuery(JsonNode args) {
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

    private ToolExecution submit(JsonNode args) {
        JsonNode delta = args.has("delta") && args.get("delta").isObject()
                ? args.get("delta") : args;
        ToolExecution shapeError = validateSubmitShape(delta);
        if (shapeError != null) {
            return shapeError;
        }
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
                staged, policy, schema, knownEntityTypes());
        Admission admission = admitValidatorCleanItems(delta, staged);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", validation.valid());
        response.put("entities", staged.entities().size());
        response.put("relations", staged.relations().size());
        response.put("errors", validation.errors());
        response.put("warnings", validation.warnings());
        if (!validation.valid()) {
            response.put("requiredShape", submitContract());
            Map<String, Object> correction = correctionContext(delta);
            addValidatorCleanRepairSeed(correction, delta, staged, admission);
            response.put("correction", correction);
            response.put("guidance",
                    "If correction.alreadyRetained is present, it is accepted, not evidence. Recheck SOURCE; "
                            + "repair or omit only rejected items, then resubmit with exact keys.");
            return ToolExecution.continuing(json(response));
        }

        accepted = mergeAndValidateAccepted(staged.metadata(), staged.entities(), staged.relations());
        response.put("accepted", true);
        response.put("metadataOwnedByEngine", true);
        return ToolExecution.terminal(json(response));
    }

    private ToolExecution validateSubmitShape(JsonNode delta) {
        if (delta == null || !delta.isObject()) {
            return invalidSubmission("invalid_graph_delta_shape",
                    "Arguments must be an object.", delta);
        }
        List<String> keys = new ArrayList<>();
        delta.fieldNames().forEachRemaining(keys::add);
        List<String> unexpected = keys.stream()
                .filter(key -> !Set.of("entities", "relations").contains(key))
                .sorted()
                .toList();
        if (!unexpected.isEmpty()) {
            return invalidSubmission("invalid_graph_delta_shape",
                    "Unexpected top-level argument keys: " + unexpected, delta);
        }
        if (!delta.has("entities") || !delta.get("entities").isArray()
                || !delta.has("relations") || !delta.get("relations").isArray()) {
            return invalidSubmission("invalid_graph_delta_shape",
                    "Both entities and relations must be arrays.", delta);
        }
        return null;
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

    private Map<String, Object> submitContract() {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("topLevel", List.of("entities", "relations"));
        contract.put("entityRequired", requiredEntityFields());
        contract.put("relationRequired", requiredRelationFields());
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
        contract.put("argumentTemplate", Map.of(
                "entities", List.of(entityTemplate),
                "relations", List.of(relationTemplate)));
        contract.put("fieldDomains", Map.of(
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
            ValidationResult itemValidation = validateRepairCandidate(
                    candidateEntities, List.of(), staged.metadata(), knownTypes);
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

    private Admission admitValidatorCleanItems(JsonNode delta, ExtractionResult staged) {
        Map<String, String> knownTypes = knownEntityTypes();
        List<ExtractedEntity> entities = accepted == null
                ? new ArrayList<>() : new ArrayList<>(accepted.entities());
        List<ExtractedRelation> relations = accepted == null
                ? new ArrayList<>() : new ArrayList<>(accepted.relations());
        int admittedEntityItems = 0;
        int admittedRelationItems = 0;
        for (ExtractedEntity entity : staged.entities()) {
            List<ExtractedEntity> validationCandidate = replaceEntityForValidation(entities, entity);
            if (validateRepairCandidate(validationCandidate, relations, staged.metadata(), knownTypes).valid()) {
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
        ExtractionResult merged = new ExtractionResult(GraphExtractionSchema.SCHEMA_VERSION,
                List.copyOf(entities), List.copyOf(relations), staged.metadata());
        if (!GraphExtractionValidator.validate(merged, policy, schema, knownTypes).valid()) {
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
        List<ExtractedEntity> mergedEntities = mergeEntities(
                accepted == null ? List.of() : accepted.entities(), entities);
        List<ExtractedRelation> mergedRelations = mergeRelations(
                accepted == null ? List.of() : accepted.relations(), relations);
        ExtractionResult merged = new ExtractionResult(GraphExtractionSchema.SCHEMA_VERSION,
                mergedEntities, mergedRelations, metadata);
        ValidationResult validation = GraphExtractionValidator.validate(
                merged, policy, schema, knownEntityTypes());
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
                byId.putIfAbsent(entity.id(), entity);
            }
        }
        for (ExtractedEntity entity : additions) {
            if (entity != null && entity.id() != null && !entity.id().isBlank()) {
                byId.putIfAbsent(entity.id(), entity);
            }
        }
        return List.copyOf(byId.values());
    }

    private static List<ExtractedRelation> mergeRelations(List<ExtractedRelation> existing,
                                                           List<ExtractedRelation> additions) {
        Map<String, ExtractedRelation> byAtom = new LinkedHashMap<>();
        for (ExtractedRelation relation : existing) {
            if (relation != null) {
                byAtom.putIfAbsent(relationAtom(relation), relation);
            }
        }
        for (ExtractedRelation relation : additions) {
            if (relation != null) {
                byAtom.putIfAbsent(relationAtom(relation), relation);
            }
        }
        return List.copyOf(byAtom.values());
    }

    private static String relationAtom(ExtractedRelation relation) {
        return relation.source() + "\u0000" + relation.type() + "\u0000" + relation.target();
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
        return GraphExtractionValidator.validate(candidate, policy, schema, knownTypes);
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
