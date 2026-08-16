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
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.crawl.graph.CrawlOntology;
import ai.kompile.crawl.graph.CrawlIndexTrackingCallback.CrawlCorpusPassage;
import ai.kompile.crawl.graph.CrawlIndexTrackingCallback.CrawlCorpusSnapshot;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.unified.GraphReasoningQueryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrawlExtractionToolBackendTest {

    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper();

    @Test
    void unifiedCorpusCombinesEmbeddingAndLexicalRankingAndCanPageExactText() throws Exception {
        CrawlCorpusSnapshot corpus = corpus();
        VectorStore vectors = mock(VectorStore.class);
        when(vectors.isVectorStoreAvailable()).thenReturn(true);
        when(vectors.isUsingFallbackIndex()).thenReturn(false);
        when(vectors.similaritySearchWithScores("orchid approval", 24, 0.0))
                .thenReturn(List.of(new ScoredDocument(
                        new Document("p2", "semantic index row", Map.of("chunkId", "p2")),
                        0.93)));

        CrawlExtractionToolBackend backend = backend(corpus, vectors, new UnifiedGraph());
        JsonNode search = execute(backend, CrawlExtractionToolBackend.UNIFIED_CORPUS,
                """
                {"action":"SEARCH","query":"orchid approval","limit":8}
                """);

        assertTrue(search.path("ok").asBoolean());
        assertTrue(search.path("embedding").path("available").asBoolean());
        assertTrue(search.path("embedding").path("used").asBoolean());
        assertEquals("p2", search.path("results").get(0).path("chunkId").asText());
        assertTrue(search.path("results").get(0).path("exactExcerpt").asText()
                .contains("Orchid approval"));

        JsonNode page = execute(backend, CrawlExtractionToolBackend.UNIFIED_CORPUS,
                """
                {"action":"GET","chunkId":"p2","start":0,"length":19}
                """);
        assertEquals("Orchid approval was", page.path("exactText").asText());
        assertTrue(page.path("hasMore").asBoolean());
    }

    @Test
    void embeddingInitializationFailureIsExplicitAndNeverSilentlyInvoked() throws Exception {
        VectorStore vectors = mock(VectorStore.class);
        when(vectors.isVectorStoreAvailable()).thenReturn(false);
        when(vectors.isUsingFallbackIndex()).thenReturn(false);

        CrawlExtractionToolBackend backend = backend(corpus(), vectors, new UnifiedGraph());
        JsonNode search = execute(backend, CrawlExtractionToolBackend.UNIFIED_CORPUS,
                """
                {"action":"SEARCH","query":"orchid","limit":3}
                """);

        assertFalse(search.path("embedding").path("available").asBoolean());
        assertEquals("backend_reported_unavailable",
                search.path("embedding").path("error").asText());
        assertTrue(search.path("results").size() > 0,
                "lexical evidence remains available but the embedding failure stays visible");
        verify(vectors, never()).similaritySearchWithScores(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    void providerConstructionFailureIsReportedAsInitializationFailure() throws Exception {
        CrawlExtractionToolBackend backend = new CrawlExtractionToolBackend(
                "chunk-1", "document-1", "lfm", "graph-1", null,
                GraphExtractionValidationPolicy.defaults(), null, corpus(), null,
                "BGE model could not initialize", UnifiedGraph::new,
                new GraphReasoningQueryService(null));

        JsonNode search = execute(backend, CrawlExtractionToolBackend.UNIFIED_CORPUS,
                """
                {"action":"SEARCH","query":"orchid","limit":3}
                """);

        assertTrue(search.path("embedding").path("configured").asBoolean());
        assertFalse(search.path("embedding").path("available").asBoolean());
        assertEquals("initialization_failed: BGE model could not initialize",
                search.path("embedding").path("error").asText());
        assertTrue(search.path("results").size() > 0);
    }

    @Test
    void toolCatalogIsTieredCompactAndOrientsTheModelFromLiveGraphState() throws Exception {
        CrawlExtractionToolBackend emptyBackend =
                backend(corpus(), null, new UnifiedGraph());
        String compactJson = emptyBackend.catalogJson(DecomposedPromptTier.COMPACT);
        JsonNode compact = MAPPER.readTree(compactJson);

        assertEquals("tiered-compact-v12", compact.path("version").asText());
        assertEquals("EMPTY", compact.path("state").path("graphState").asText());
        assertEquals(0, compact.path("state").path("graphEntities").asInt());
        assertEquals(2, compact.path("state").path("completeCorpusPassages").asInt());
        assertEquals(CrawlExtractionToolBackend.SUBMIT_GRAPH_DELTA,
                compact.path("state").path("recommendedTool").asText());
        assertEquals(CrawlExtractionToolBackend.SUBMIT_GRAPH_DELTA,
                compact.path("callShape").path("tool").asText());
        assertTrue(compact.path("callShape").path("args").path("entities").isArray());
        assertTrue(compact.path("callShape").path("args").path("relations").isArray());
        assertTrue(compact.path(CrawlExtractionToolBackend.UNIFIED_CORPUS)
                .path("operations").isArray());
        assertTrue(compact.path(CrawlExtractionToolBackend.SUBMIT_GRAPH_DELTA)
                .path("entityFields").isArray());
        assertEquals(List.of("id", "name", "type", "aliases"),
                MAPPER.convertValue(
                        compact.path(CrawlExtractionToolBackend.SUBMIT_GRAPH_DELTA)
                                .path("entityFields"),
                        MAPPER.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertFalse(compactJson.contains("<"),
                "the compact contract should not invite placeholder copying");
        assertEquals(1, compactJson.split("\\\"tool\\\"", -1).length - 1,
                "only the single call envelope may contain a tool field");
        assertFalse(compact.path(CrawlExtractionToolBackend.GRAPH_REASONING_QUERY)
                .has("operationGroups"));
        assertFalse(compactJson.contains("validationRules"));
        assertTrue(compactJson.length() < 3_000,
                () -> "compact context should receive an executable tool contract, not a schema dump: "
                        + compactJson.length());

        UnifiedGraph populated = new UnifiedGraph();
        populated.addEntity(GraphEntity.builder("lead")
                .type("PERSON").label("Finance Lead").confidence(0.9).build());
        populated.addEntity(GraphEntity.builder("close")
                .type("PROCESS_STEP").label("Close Review").confidence(0.9).build());
        populated.addRelation(GraphRelation.builder("approval", "lead", "close")
                .type("APPROVED").confidence(0.92).weight(0.92).directed(true).build());

        String richJson = backend(corpus(), null, populated)
                .catalogJson(DecomposedPromptTier.RICH);
        JsonNode rich = MAPPER.readTree(richJson);
        assertEquals("POPULATED", rich.path("state").path("graphState").asText());
        assertEquals(CrawlExtractionToolBackend.GRAPH_REASONING_QUERY,
                rich.path("state").path("recommendedTool").asText());
        assertEquals(2, rich.path("state").path("graphEntities").asInt());
        assertEquals(1, rich.path("state").path("graphRelations").asInt());
        assertFalse(rich.path(CrawlExtractionToolBackend.GRAPH_REASONING_QUERY)
                .has("operationGroups"), "operation discovery belongs behind CAPABILITIES");
        String capabilityHint = rich.path(CrawlExtractionToolBackend.GRAPH_REASONING_QUERY)
                .path("capabilityHint").asText();
        assertTrue(capabilityHint.contains("first-order logic"));
        assertTrue(capabilityHint.contains("embeddings"));
        assertTrue(richJson.length() < 3_200,
                () -> "rich context should advertise graph facets without dumping operations: "
                        + richJson.length());
    }

    @Test
    void nativeContractSurfacesSchemaInContextWithoutComplicatingSubmitShape() throws Exception {
        GraphSchema schema = new GraphSchema(
                List.of(
                        new NodeType("PERSON", "A person", null),
                        new NodeType("ROLE", "An organizational role", null)),
                List.of(new RelationshipType(
                        "HAS_ROLE", "A person holds a role", null, List.of("serves_as"))),
                List.of("(PERSON)-[:HAS_ROLE]->(ROLE)"));
        CrawlExtractionToolBackend backend =
                backend(corpus(), null, new UnifiedGraph(), schema);

        JsonNode context = MAPPER.readTree(
                backend.toolContextJson(DecomposedPromptTier.STANDARD));
        assertEquals(List.of("PERSON", "ROLE"), MAPPER.convertValue(
                context.path("graphSchema").path("entityTypes"),
                MAPPER.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertEquals("HAS_ROLE",
                context.path("graphSchema").path("relationTypes").get(0).asText());
        assertEquals("(PERSON)-[:HAS_ROLE]->(ROLE)",
                context.path("graphSchema").path("relationPatterns").get(0).asText());
        assertTrue(context.path("graphSchema").path("authoritative").asBoolean());
        assertFalse(context.path("graphSchema").has("submitFormatExamples"));
        assertFalse(context.path("graphSchema").has("submitFormatExamplesAreEvidence"));
        assertFalse(context.toString().contains("source-entity-id"));
        assertFalse(context.toString().contains("target-entity-id"));
        assertEquals("A person", context.path("graphSchema")
                .path("nodeDefinitions").get(0).path("description").asText());
        assertEquals("serves_as", context.path("graphSchema")
                .path("relationshipDefinitions").get(0).path("aliases").get(0).asText());

        JsonNode submit = MAPPER.valueToTree(
                backend.toolDefinitions(DecomposedPromptTier.STANDARD).get(0).parameters());
        JsonNode submitProperties = submit.path("properties");
        List<String> submitFieldOrder = new ArrayList<>();
        submitProperties.fieldNames().forEachRemaining(submitFieldOrder::add);
        assertEquals(List.of("entities", "relations"), submitFieldOrder,
                "small-model contracts must present endpoint entities before their relations");
        assertTrue(submitProperties.path("entities").path("description").asText()
                        .contains("every relation endpoint"),
                "the entity array contract must make endpoint completeness explicit");
        JsonNode entityProperties = submitProperties.path("entities").path("items")
                .path("properties");
        JsonNode entityType = entityProperties.path("type");
        assertTrue(entityProperties.path("aliases").path("description").asText()
                .contains("same identity"));
        JsonNode relationProperties = submitProperties.path("relations").path("items")
                .path("properties");
        JsonNode relationType = relationProperties.path("type");
        assertEquals("string", entityType.path("type").asText());
        assertEquals("string", relationType.path("type").asText());
        assertTrue(relationProperties.path("source").path("description").asText()
                        .contains("entities[].id"),
                "the native function contract must tell small models where endpoint ids come from");
        assertTrue(relationProperties.path("target").path("description").asText()
                        .contains("current graph"),
                "the native function contract must distinguish ids from names and type labels");
        assertEquals(List.of("PERSON", "ROLE"), MAPPER.convertValue(
                entityType.path("enum"),
                MAPPER.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertEquals(List.of("HAS_ROLE"), MAPPER.convertValue(
                relationType.path("enum"),
                MAPPER.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertTrue(entityType.path("description").asText().contains("graphSchema.entityTypes"));
        assertTrue(relationType.path("description").asText().contains("graphSchema.relationTypes"));
    }

    @Test
    void ontologyUpdateRefreshesPromptsEnumsAndFullRelationshipSchema() throws Exception {
        GraphSchema initial = new GraphSchema(
                List.of(
                        new NodeType("PERSON", "A person", null),
                        new NodeType("ORGANIZATION", "An organization", null)),
                List.of(new RelationshipType(
                        "WORKS_AT", "A person works at an organization", null)),
                List.of("(PERSON)-[:WORKS_AT]->(ORGANIZATION)"));
        CrawlExtractionToolBackend backend =
                backend(corpus(), null, new UnifiedGraph(), initial);

        List<String> initialTools = backend.toolDefinitions(DecomposedPromptTier.STANDARD)
                .stream().map(ExtractionToolBackend.ToolDefinition::name).toList();
        assertTrue(initialTools.contains(CrawlExtractionToolBackend.UPDATE_ONTOLOGY));
        assertTrue(initialTools.contains(CrawlExtractionToolBackend.UNIFIED_CORPUS));

        JsonNode updated = execute(backend, CrawlExtractionToolBackend.UPDATE_ONTOLOGY,
                """
                {
                  "nodeTypes":[
                    {"label":"EMAIL_ADDRESS","description":"An email address identifying a graph entity"}
                  ],
                  "relationshipTypes":[
                    {"type":"IDENTIFIES","description":"An email address identifies a person",
                     "aliases":["email_for"]}
                  ],
                  "patterns":["(EMAIL_ADDRESS)-[:IDENTIFIES]->(PERSON)"]
                }
                """);

        assertTrue(updated.path("ok").asBoolean(), updated::toPrettyString);
        assertTrue(updated.path("updated").asBoolean());
        assertEquals(2L, updated.path("ontologyRevision").asLong());

        JsonNode context = MAPPER.readTree(
                backend.toolContextJson(DecomposedPromptTier.STANDARD));
        assertEquals(2L, context.path("ontologyRevision").asLong());
        assertTrue(context.path("graphSchema").path("entityTypes").toString()
                .contains("EMAIL_ADDRESS"));
        assertTrue(context.path("graphSchema").path("relationTypes").toString()
                .contains("IDENTIFIES"));
        assertTrue(context.path("graphSchema").path("relationPatterns").toString()
                .contains("(EMAIL_ADDRESS)-[:IDENTIFIES]->(PERSON)"));

        ExtractionToolBackend.ToolDefinition submit =
                backend.toolDefinitions(DecomposedPromptTier.STANDARD).stream()
                        .filter(tool -> CrawlExtractionToolBackend.SUBMIT_GRAPH_DELTA
                                .equals(tool.name()))
                        .findFirst().orElseThrow();
        JsonNode submitSchema = MAPPER.valueToTree(submit.parameters());
        assertTrue(submitSchema.path("properties").path("entities").path("items")
                .path("properties").path("type").path("enum").toString()
                .contains("EMAIL_ADDRESS"));
        assertTrue(submitSchema.path("properties").path("relations").path("items")
                .path("properties").path("type").path("enum").toString()
                .contains("IDENTIFIES"));

        JsonNode schemaQuery = execute(
                backend, CrawlExtractionToolBackend.GRAPH_REASONING_QUERY,
                """
                {"operation":"SCHEMA"}
                """);
        assertTrue(schemaQuery.path("ok").asBoolean());
        assertEquals(2L, schemaQuery.path("result").path("ontologyRevision").asLong());
        JsonNode relationshipTypes = schemaQuery.path("result").path("relationshipTypes");
        assertTrue(relationshipTypes.toString().contains("IDENTIFIES"));
        assertTrue(relationshipTypes.toString().contains("email_for"));
        assertTrue(schemaQuery.path("result").path("patterns").toString()
                .contains("(EMAIL_ADDRESS)-[:IDENTIFIES]->(PERSON)"));
    }

    @Test
    void entityOnlyContractExposesOneEntitySubmissionToolAndStagesNoRelations() throws Exception {
        GraphSchema schema = new GraphSchema(
                List.of(new NodeType("PERSON", "A person", null)),
                List.of(new RelationshipType(
                        "HAS_ROLE", "A person holds a role", null, List.of("serves_as"))),
                List.of("(PERSON)-[:HAS_ROLE]->(PERSON)"));
        CrawlExtractionToolBackend backend = backend(
                corpus(), null, new UnifiedGraph(), schema, ExtractionTarget.ENTITIES_ONLY);

        List<ExtractionToolBackend.ToolDefinition> tools =
                backend.toolDefinitions(DecomposedPromptTier.COMPACT);
        assertEquals(1, tools.size());
        assertEquals(CrawlExtractionToolBackend.SUBMIT_ENTITIES, tools.get(0).name());

        JsonNode parameters = MAPPER.valueToTree(tools.get(0).parameters());
        assertEquals(List.of("names"), MAPPER.convertValue(
                parameters.path("required"),
                MAPPER.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertTrue(parameters.path("properties").has("names"));
        assertEquals("array", parameters.path("properties").path("names")
                .path("type").asText());
        assertEquals("string", parameters.path("properties").path("names")
                .path("items").path("type").asText());
        assertEquals(32, parameters.path("properties").path("names")
                .path("maxItems").asInt());
        assertTrue(parameters.path("properties").path("names")
                .path("uniqueItems").asBoolean());
        assertEquals(160, parameters.path("properties").path("names")
                .path("items").path("maxLength").asInt());
        assertFalse(parameters.toString().contains("relations"));
        JsonNode context = MAPPER.readTree(
                backend.toolContextJson(DecomposedPromptTier.COMPACT));
        assertEquals("ENTITIES_ONLY", context.path("extractionTarget").asText());
        assertFalse(context.has("graphSchema"), "schema belongs to the later ontology phase");

        JsonNode response = execute(backend, CrawlExtractionToolBackend.SUBMIT_ENTITIES,
                """
                {"names":["M. Chen","m. chen","J. Park"]}
                """);

        assertTrue(response.path("accepted").asBoolean(), response::toPrettyString);
        assertTrue(response.path("idsAndTypesOwnedByEngine").asBoolean());
        assertTrue(response.path("ontologyTypingDeferred").asBoolean());
        assertEquals(2, backend.acceptedResult().orElseThrow().entities().size());
        assertEquals(List.of("M. Chen", "J. Park"),
                backend.acceptedResult().orElseThrow().entities().stream()
                        .map(entity -> entity.name()).toList());
        assertTrue(backend.acceptedResult().orElseThrow().entities().stream()
                .allMatch(entity -> "ENTITY".equals(entity.type())));
        assertEquals(0, backend.acceptedResult().orElseThrow().relations().size());
    }

    @Test
    void entityOnlySubmissionRejectsLegacyPipeDelimitedString() throws Exception {
        CrawlExtractionToolBackend backend = backend(
                corpus(), null, new UnifiedGraph(), null, ExtractionTarget.ENTITIES_ONLY);

        JsonNode response = execute(backend, CrawlExtractionToolBackend.SUBMIT_ENTITIES,
                """
                {"names":"M. Chen | J. Park"}
                """);

        assertEquals("invalid_entity_submission", response.path("error").asText());
        assertTrue(response.path("requiredShape").path("names").isArray());
        assertTrue(backend.acceptedResult().isEmpty());
    }

    @Test
    void nativeGraphToolAdvertisesOnlyExecutableExtractionCommandsAndReasoningFacets() {
        CrawlExtractionToolBackend backend = backend(corpus(), null, new UnifiedGraph());
        ExtractionToolBackend.ToolDefinition graphTool = backend
                .toolDefinitions(DecomposedPromptTier.STANDARD).stream()
                .filter(tool -> CrawlExtractionToolBackend.GRAPH_REASONING_QUERY.equals(tool.name()))
                .findFirst()
                .orElseThrow();
        JsonNode parameters = MAPPER.valueToTree(graphTool.parameters());
        JsonNode properties = parameters.path("properties");
        List<String> operations = MAPPER.convertValue(
                properties.path("operation").path("enum"),
                MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));

        assertEquals(GraphReasoningQueryService.queryRequestOperations(), operations);
        assertTrue(operations.containsAll(List.of(
                "SCHEMA", "SEARCH", "FACTS", "SIMILAR", "VERIFY", "WHY", "RANK")));
        assertFalse(operations.contains("CALCULATE"));
        assertFalse(operations.contains("SCENARIO"));
        assertFalse(operations.contains("SOLVE_TARGET"));
        String operationGuide = properties.path("operation").path("description").asText();
        assertEquals(GraphReasoningQueryService.queryRequestOperationGuide(), operationGuide);
        assertTrue(operationGuide.contains("SEARCH(queryText)"));
        assertTrue(operationGuide.contains("SIMILAR(entityId)"));
        assertTrue(operationGuide.contains(
                "VERIFY(entityId,targetId,relationTypes[0])"));
        assertTrue(graphTool.description().contains("embeddings"));
        assertTrue(graphTool.description().contains("first-order logic"));
        assertEquals(List.of("PSL", "BAYESIAN"), MAPPER.convertValue(
                properties.path("structural").path("enum"),
                MAPPER.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertFalse(parameters.path("additionalProperties").asBoolean());
    }

    @Test
    void compactContextPreservesVocabularyAndDefersLongSignaturesWithoutSeedingFacts() throws Exception {
        List<NodeType> nodeTypes = new ArrayList<>();
        List<RelationshipType> relationTypes = new ArrayList<>();
        List<String> patterns = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String suffix = String.format("%02d", i);
            String nodeType = "TYPE_" + suffix;
            String relationType = "REL_" + suffix;
            nodeTypes.add(new NodeType(nodeType, "Schema type " + suffix, null));
            relationTypes.add(new RelationshipType(
                    relationType, "Schema relation " + suffix, null));
            patterns.add("(" + nodeType + ")-[:" + relationType + "]->(" + nodeType + ")");
        }
        CrawlExtractionToolBackend backend = backend(
                corpus(), null, new UnifiedGraph(),
                new GraphSchema(nodeTypes, relationTypes, patterns));

        JsonNode graphSchema = MAPPER.readTree(
                backend.toolContextJson(DecomposedPromptTier.COMPACT)).path("graphSchema");
        List<String> presentedEntityTypes = MAPPER.convertValue(
                graphSchema.path("entityTypes"),
                MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
        List<String> presentedRelationTypes = MAPPER.convertValue(
                graphSchema.path("relationTypes"),
                MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));

        assertEquals(30, presentedEntityTypes.size());
        assertEquals("TYPE_29", presentedEntityTypes.get(29));
        assertEquals(30, presentedRelationTypes.size());
        assertEquals("REL_29", presentedRelationTypes.get(29));
        assertEquals(0, graphSchema.path("relationPatterns").size());
        assertEquals(30, graphSchema.path("fullCounts").path("relationPatterns").asInt());
        assertTrue(graphSchema.path("compact").asBoolean());
        assertTrue(graphSchema.path("truncated").asBoolean());
        assertEquals("SCHEMA", graphSchema.path("detailedDefinitionsAvailableVia")
                .path("operation").asText());
        assertFalse(graphSchema.has("submitFormatExamples"));
        assertFalse(graphSchema.toString().contains("source-entity-id"));
        assertFalse(graphSchema.toString().contains("target-entity-id"));
    }

    @Test
    void prePassHintsFocusLargeSchemaWithoutChangingAuthoritativeValidation() throws Exception {
        List<NodeType> nodeTypes = new ArrayList<>();
        nodeTypes.add(new NodeType("PERSON", "A person", null));
        nodeTypes.add(new NodeType("ROLE", "An organizational role", null));
        List<RelationshipType> relationTypes = new ArrayList<>();
        relationTypes.add(new RelationshipType(
                "HAS_ROLE", "A person holds a role", null));
        List<String> patterns = new ArrayList<>();
        patterns.add("(PERSON)-[:HAS_ROLE]->(ROLE)");
        for (int i = 0; i < 20; i++) {
            String nodeType = "NOISE_" + i;
            String relationType = "REL_" + i;
            nodeTypes.add(new NodeType(nodeType, "Unrelated type " + i, null));
            relationTypes.add(new RelationshipType(
                    relationType, "Unrelated relation " + i, null));
            patterns.add("(" + nodeType + ")-[:" + relationType + "]->(" + nodeType + ")");
        }
        GraphSchema schema = new GraphSchema(nodeTypes, relationTypes, patterns);
        CrawlExtractionToolBackend backend =
                backend(corpus(), null, new UnifiedGraph(), schema);
        ExtractionTaskContext task = new ExtractionTaskContext(
                "task-1", "partition-1", "snapshot-1", List.of("M. Chen"), "chunk-1",
                "concept-prepass", "concepts found in source", 1.0, "revision-1", null,
                List.of(
                        new ConceptHint("M. Chen", "PERSON", "prepass", "M. Chen"),
                        new ConceptHint("VP", "ROLE", "prepass", "VP")));

        JsonNode context = MAPPER.readTree(
                backend.toolContextJson(DecomposedPromptTier.RICH, task));
        JsonNode graphSchema = context.path("graphSchema");
        assertTrue(graphSchema.path("focusedByConceptHints").asBoolean());
        assertFalse(graphSchema.path("focusIsEvidence").asBoolean());
        assertTrue(graphSchema.path("focus").path("entityTypes").toString()
                .contains("PERSON") && graphSchema.path("focus").path("entityTypes").toString().contains("ROLE"));
        assertTrue(graphSchema.path("focus").path("relationTypes").toString().contains("HAS_ROLE"));
        assertTrue(graphSchema.path("focus").path("relationPatterns").toString()
                .contains("(PERSON)-[:HAS_ROLE]->(ROLE)"));
        assertFalse(graphSchema.has("conceptCandidates"));
        assertFalse(graphSchema.has("entityDefinitions"));
        assertFalse(graphSchema.has("relationDefinitions"));
        assertTrue(graphSchema.path("entityTypes").toString()
                .contains("PERSON") && graphSchema.path("entityTypes").toString().contains("ROLE")
                && graphSchema.path("entityTypes").toString().contains("NOISE_0"));
        assertTrue(graphSchema.path("relationTypes").toString()
                .contains("HAS_ROLE") && graphSchema.path("relationTypes").toString().contains("REL_0"));
        assertTrue(graphSchema.path("relationPatterns").toString()
                .contains("(PERSON)-[:HAS_ROLE]->(ROLE)"));
        assertFalse(context.toString().contains("M. Chen"));
        assertFalse(context.toString().contains("\"VP\""));

        JsonNode submit = MAPPER.valueToTree(
                backend.toolDefinitions(DecomposedPromptTier.RICH).get(0).parameters());
        assertFalse(submit.path("properties").path("entities").path("items")
                .path("properties").path("type").has("enum"));
        assertFalse(submit.path("properties").path("relations").path("items")
                .path("properties").path("type").has("enum"));

        JsonNode accepted = execute(backend, CrawlExtractionToolBackend.SUBMIT_GRAPH_DELTA, """
                {"entities":[{"id":"noise","name":"Noise","type":"NOISE_0"}],
                 "relations":[]}
                """);
        assertTrue(accepted.path("accepted").asBoolean(),
                "prompt focus must not narrow the authoritative validation vocabulary");
    }

    @Test
    void genericProductionConceptsDoNotFocusSchemaByLexicalOverlap() throws Exception {
        List<NodeType> nodeTypes = new ArrayList<>();
        nodeTypes.add(new NodeType(
                "APPROVAL_ROLE", "Canonical approval role such as VP FP&A or Controller", null));
        nodeTypes.add(new NodeType(
                "PERSON", "Named individual with organizational context", null));
        List<RelationshipType> relationTypes = new ArrayList<>();
        relationTypes.add(new RelationshipType(
                "HAS_ROLE", "A person holds a named organizational role", null));
        List<String> patterns = new ArrayList<>();
        patterns.add("(PERSON)-[:HAS_ROLE]->(APPROVAL_ROLE)");
        for (int i = 0; i < 20; i++) {
            String suffix = String.format("%02d", i);
            nodeTypes.add(new NodeType(
                    "NOISE_" + suffix, "Unrelated schema concept " + suffix, null));
            relationTypes.add(new RelationshipType(
                    "NOISE_REL_" + suffix, "Unrelated schema relation " + suffix, null));
            patterns.add("(NOISE_" + suffix + ")-[:NOISE_REL_" + suffix
                    + "]->(NOISE_" + suffix + ")");
        }
        CrawlExtractionToolBackend backend = backend(
                corpus(), null, new UnifiedGraph(),
                new GraphSchema(nodeTypes, relationTypes, patterns));
        ExtractionTaskContext task = new ExtractionTaskContext(
                "task-generic", "partition-generic", "snapshot-generic", List.of(), "chunk-1",
                "concept-prepass", "domain-neutral concepts found in source", 1.0,
                "revision-1", null,
                List.of(new ConceptHint(
                        "VP, FP&A", "KEYWORD", "deterministic-statistical-prepass",
                        "A named individual is identified as VP, FP&A")));

        JsonNode graphSchema = MAPPER.readTree(
                backend.toolContextJson(DecomposedPromptTier.COMPACT, task)).path("graphSchema");
        assertFalse(graphSchema.path("focusedByConceptHints").asBoolean(),
                "generic categories cannot select schema types by lexical overlap");
        assertFalse(graphSchema.has("focus"),
                "generic prepass terms must not become a model-visible schema focus");
        assertTrue(graphSchema.path("entityTypes").toString()
                .contains("APPROVAL_ROLE") && graphSchema.path("entityTypes").toString().contains("PERSON")
                && graphSchema.path("entityTypes").toString().contains("NOISE_00"));
        assertTrue(graphSchema.path("relationTypes").toString()
                .contains("HAS_ROLE") && graphSchema.path("relationTypes").toString().contains("NOISE_REL_00"));
        assertTrue(graphSchema.path("relationPatterns").isEmpty());
        assertEquals(21, graphSchema.path("fullCounts").path("relationPatterns").asInt());
        assertFalse(graphSchema.toString().contains("VP FP&A"));
        assertFalse(graphSchema.toString().contains("Controller"));
        assertFalse(graphSchema.has("entityDefinitions"));
        assertFalse(graphSchema.has("relationDefinitions"));
        assertFalse(graphSchema.has("conceptCandidates"));
    }

    @Test
    void standardizedSchemaValidationReturnsConcreteEntityAndRelationErrors() throws Exception {
        GraphSchema schema = new GraphSchema(
                List.of(
                        new NodeType("PERSON", "A person", null),
                        new NodeType("ROLE", "A role", null)),
                List.of(new RelationshipType("HAS_ROLE", "Person has role", null)),
                List.of("(PERSON)-[:HAS_ROLE]->(ROLE)"));
        CrawlExtractionToolBackend backend = backend(corpus(), null, new UnifiedGraph(), schema);

        JsonNode rejected = execute(backend, CrawlExtractionToolBackend.SUBMIT_GRAPH_DELTA, """
                {"entities":[
                  {"id":"person","name":"M. Chen","type":"PERSON"},
                  {"id":"role","name":"VP, FP&A","type":"JOB_TITLE"}
                ],"relations":[
                  {"source":"person","target":"role","type":"OCCUPIES"}
                ]}
                """);

        assertFalse(rejected.path("ok").asBoolean());
        assertTrue(rejected.path("errors").toString().contains("ENTITY_TYPE_SCHEMA"));
        assertTrue(rejected.path("errors").toString().contains("RELATION_TYPE_SCHEMA"));
    }

    @Test
    void rejectedLfmDeltaReturnsSchemaDerivedEndpointAndTypeFacetCorrections() throws Exception {
        GraphSchema schema = new GraphSchema(
                List.of(
                        new NodeType("PERSON", "A person", null),
                        new NodeType("APPROVAL_ROLE", "An approval role", null)),
                List.of(new RelationshipType(
                        "HAS_ROLE", "A person holds an approval role", null)),
                List.of("(PERSON)-[:HAS_ROLE]->(APPROVAL_ROLE)"));
        CrawlExtractionToolBackend backend = backend(corpus(), null, new UnifiedGraph(), schema);
        ExtractionTaskContext task = new ExtractionTaskContext(
                "task-1", "partition-1", "snapshot-1", List.of("M. Chen", "VP FP&A"),
                "chunk-1", "concept-prepass", "concepts found in source", 1.0,
                "revision-1", null,
                List.of(
                        new ConceptHint("M. Chen", "PERSON", "prepass", "M. Chen"),
                        new ConceptHint("VP FP&A", "APPROVAL_ROLE", "prepass", "VP FP&A")));
        JsonNode originalContext = MAPPER.readTree(
                backend.toolContextJson(DecomposedPromptTier.STANDARD, task));

        JsonNode rejected = execute(backend, CrawlExtractionToolBackend.SUBMIT_GRAPH_DELTA, """
                {"entities":[
                  {"id":"michael.wong","name":"M. Chen","type":"PERSON"}
                ],"relations":[
                  {"source":"michael.wong","target":"VP FP&A","type":"APPROVAL_ROLE"}
                ]}
                """);

        assertFalse(rejected.path("ok").asBoolean());
        assertTrue(rejected.path("errors").toString().contains("unknown target entity"));
        JsonNode required = rejected.path("requiredShape");
        assertEquals("<graphSchema.entityTypes value>", required.path("argumentTemplate")
                .path("entities").get(0).path("type").asText());
        assertEquals("CURRENT GRAPH AND CORPUS STATE.graphSchema in the original request",
                required.path("schemaContext").asText());
        assertFalse(required.has("graphSchema"),
                "repair feedback must reference, not duplicate, the original schema context");
        assertEquals("HAS_ROLE", originalContext.path("graphSchema")
                .path("relationTypes").get(0).asText());
        assertEquals("(PERSON)-[:HAS_ROLE]->(APPROVAL_ROLE)",
                originalContext.path("graphSchema").path("relationPatterns").get(0).asText());
        assertFalse(originalContext.path("graphSchema").has("conceptCandidates"));
        assertFalse(originalContext.toString().contains("M. Chen"));
        assertFalse(originalContext.toString().contains("VP FP&A"));
        assertTrue(rejected.toString().length() < 2_500,
                () -> "repair feedback must fit a short-context validation turn: "
                        + rejected.toString().length());

        JsonNode correction = rejected.path("correction");
        assertEquals("VP FP&A", correction.path("missingEndpointIds").get(0).asText());
        JsonNode conflict = correction.path("typeDomainConflicts").get(0);
        assertEquals("relations[0].type", conflict.path("path").asText());
        assertEquals("APPROVAL_ROLE", conflict.path("value").asText());
        assertEquals("graphSchema.relationTypes", conflict.path("expectedDomain").asText());
        assertEquals("graphSchema.entityTypes", conflict.path("valueBelongsTo").asText());
        assertEquals(1, backend.acceptedResult().orElseThrow().entities().size(),
                "a validator-clean entity must remain retained while rejected relation facets are repaired");
    }

    @Test
    void mixedLfmSubmissionRetainsOnlyValidatorCleanAtomsAndMergesLaterCorrections()
            throws Exception {
        GraphSchema schema = new GraphSchema(
                List.of(
                        new NodeType("PERSON", "A person", null),
                        new NodeType("APPROVAL_ROLE", "An approval role", null),
                        new NodeType("VARIANCE_TRIAGE", "A variance triage item", null)),
                List.of(
                        new RelationshipType("HAS_ROLE", "A person holds a role", null),
                        new RelationshipType("ESCALATED_TO", "A triage item escalates to a role", null)),
                List.of(
                        "(PERSON)-[:HAS_ROLE]->(APPROVAL_ROLE)",
                        "(VARIANCE_TRIAGE)-[:ESCALATED_TO]->(APPROVAL_ROLE)"));
        CrawlExtractionToolBackend backend = backend(corpus(), null, new UnifiedGraph(), schema);

        JsonNode rejected = execute(backend, CrawlExtractionToolBackend.SUBMIT_GRAPH_DELTA, """
                {"entities":[
                  {"id":"M. Chen","name":"M. Chen","type":"PERSON"},
                  {"id":"J. Park","name":"J. Park","type":"PERSON"},
                  {"id":"VP, FP&A","name":"VP, FP&A","type":"APPROVAL_ROLE"},
                  {"id":"Controller","name":"Controller","type":"APPROVAL_ROLE"},
                  {"id":"inventory","name":"Inventory","type":"VARIANCE_TRIAGE"}
                ],"relations":[
                  {"source":"M. Chen","target":"VP, FP&A","type":"HAS_ROLE"},
                  {"source":"J. Park","target":"VP, FP&A","type":"HAS_ROLE"},
                  {"source":"J. Park","target":"Controller","type":"ESCALATED_TO"},
                  {"source":"inventory","target":"VARIANCE_TRIAGE","type":"ESCALATED_TO"}
                ]}
                """);

        assertFalse(rejected.path("ok").asBoolean());
        assertEquals(5, backend.acceptedResult().orElseThrow().entities().size(),
                "validator-clean entities must be retained even when another atom is rejected");
        assertEquals(2, backend.acceptedResult().orElseThrow().relations().size());
        assertFalse(backend.acceptedResult().orElseThrow().relations().stream()
                .anyMatch(relation -> "J. Park".equals(relation.source())
                        && "Controller".equals(relation.target())),
                "invalid atoms must never enter the accumulated accepted result");
        JsonNode correction = rejected.path("correction");
        JsonNode seed = correction.path("repairSeed");
        assertTrue(correction.path("repairSeedValid").asBoolean());
        assertFalse(correction.path("repairSeedIsEvidence").asBoolean());
        assertEquals(5, seed.path("entities").size());
        assertEquals(2, seed.path("relations").size());
        assertEquals("M. Chen", seed.path("entities").get(0).path("id").asText());
        assertEquals("M. Chen", seed.path("relations").get(0).path("source").asText());
        assertEquals("VP, FP&A", seed.path("relations").get(0).path("target").asText());
        assertEquals(List.of(0, 1), MAPPER.convertValue(
                correction.path("retainedRelationIndexes"),
                MAPPER.getTypeFactory().constructCollectionType(List.class, Integer.class)));
        assertTrue(correction.path("rejectedEntities").isEmpty());
        assertEquals(2, correction.path("rejectedRelations").size());
        assertEquals(2, correction.path("rejectedRelations").get(0).path("index").asInt());
        assertTrue(correction.path("rejectedRelations").get(0).path("errors").toString()
                .contains("RELATION_SCHEMA_PATTERN"));
        assertEquals(3, correction.path("rejectedRelations").get(1).path("index").asInt());
        assertTrue(correction.path("rejectedRelations").get(1).path("errors").toString()
                .contains("unknown target entity"));
        assertTrue(correction.path("repairSeedGuidance").asText().contains("SOURCE"));
        assertTrue(correction.path("alreadyRetained").path("entityIds").toString()
                .contains("M. Chen"));

        JsonNode rejectedRetry = execute(backend, CrawlExtractionToolBackend.SUBMIT_GRAPH_DELTA, """
                {"entities":[],"relations":[
                  {"source":"inventory","target":"missing-role","type":"ESCALATED_TO"}
                ]}
                """);
        assertFalse(rejectedRetry.path("ok").asBoolean());
        assertEquals(List.of("missing-role"), MAPPER.convertValue(
                rejectedRetry.path("correction").path("missingEndpointIds"),
                MAPPER.getTypeFactory().constructCollectionType(List.class, String.class)),
                "retained entity ids must be recognized as valid endpoints in later correction rounds");

        JsonNode accepted = execute(backend, CrawlExtractionToolBackend.SUBMIT_GRAPH_DELTA, """
                {"entities":[
                  {"id":"inventory","name":"Inventory Variance","type":"VARIANCE_TRIAGE",
                   "aliases":["Stock variance"],"confidence":0.95,
                   "properties":{"reviewed":"true"}}
                ],"relations":[
                  {"source":"inventory","target":"VP, FP&A","type":"ESCALATED_TO"},
                  {"source":"M. Chen","target":"VP, FP&A","type":"HAS_ROLE",
                   "confidence":0.99,"properties":{"evidence":"second-pass"}}
                ]}
                """);
        assertTrue(accepted.path("accepted").asBoolean(),
                "a corrected-only submission may reference an already retained entity id");
        assertEquals(5, backend.acceptedResult().orElseThrow().entities().size());
        assertEquals(3, backend.acceptedResult().orElseThrow().relations().size());
        var correctedInventory = backend.acceptedResult().orElseThrow().entities().stream()
                .filter(entity -> "inventory".equals(entity.id()))
                .findFirst().orElseThrow();
        assertEquals("Inventory Variance", correctedInventory.name(),
                "validated corrections must replace the retained identity");
        assertEquals(List.of("Stock variance", "Inventory"), correctedInventory.aliases());
        assertEquals("true", correctedInventory.properties().get("reviewed"));
        var correctedRole = backend.acceptedResult().orElseThrow().relations().stream()
                .filter(relation -> "M. Chen".equals(relation.source())
                        && "HAS_ROLE".equals(relation.type()))
                .findFirst().orElseThrow();
        assertEquals("second-pass", correctedRole.properties().get("evidence"));
        assertEquals(0.99, correctedRole.confidence());
    }

    @Test
    void sourceAwareSubmissionRejectsUngroundedEntityNamesBeforeRetention() throws Exception {
        CrawlExtractionToolBackend backend = backend(corpus(), null, new UnifiedGraph());
        String source = "Alex Rivera works at Acme Robotics.";

        ExtractionToolBackend.ToolExecution rejectedExecution = backend.execute(
                CrawlExtractionToolBackend.SUBMIT_GRAPH_DELTA,
                MAPPER.readTree("""
                        {"entities":[
                          {"id":"alex","name":"submit_graph_delta validation output","type":"PERSON"}
                        ],"relations":[]}
                        """),
                source);
        JsonNode rejected = MAPPER.readTree(rejectedExecution.json());

        assertFalse(rejected.path("ok").asBoolean());
        assertFalse(rejectedExecution.terminal());
        assertTrue(rejected.path("errors").toString().contains("SOURCE_GROUNDING"));
        assertTrue(backend.acceptedResult().isEmpty(),
                "ungrounded entity text must not enter the retained repair seed");

        ExtractionToolBackend.ToolExecution acceptedExecution = backend.execute(
                CrawlExtractionToolBackend.SUBMIT_GRAPH_DELTA,
                MAPPER.readTree("""
                        {"entities":[
                          {"id":"alex","name":"Alex Rivera","type":"PERSON"},
                          {"id":"acme","name":"Acme Robotics","type":"COMPANY"}
                        ],"relations":[
                          {"source":"alex","target":"acme","type":"WORKS_AT"}
                        ]}
                        """),
                source);
        JsonNode accepted = MAPPER.readTree(acceptedExecution.json());

        assertTrue(accepted.path("ok").asBoolean(), accepted::toPrettyString);
        assertTrue(acceptedExecution.terminal());
        assertEquals(2, backend.acceptedResult().orElseThrow().entities().size());
        assertEquals(1, backend.acceptedResult().orElseThrow().relations().size());
    }

    @Test
    void globallyInvalidDuplicateSubmissionRemainsNonTerminalWhileCleanSubsetIsRetained()
            throws Exception {
        CrawlExtractionToolBackend backend = backend(corpus(), null, new UnifiedGraph());
        ExtractionToolBackend.ToolExecution execution = backend.execute(
                CrawlExtractionToolBackend.SUBMIT_GRAPH_DELTA,
                MAPPER.readTree("""
                        {"entities":[
                          {"id":"duplicate","name":"First","type":"PERSON"},
                          {"id":"duplicate","name":"Second","type":"PERSON"}
                        ],"relations":[]}
                        """));
        JsonNode response = MAPPER.readTree(execution.json());

        assertFalse(response.path("ok").asBoolean());
        assertFalse(response.path("accepted").asBoolean());
        assertFalse(execution.terminal(),
                "per-item retention must never turn a globally invalid call into terminal success");
        assertEquals(1, backend.acceptedResult().orElseThrow().entities().size());
    }

    @Test
    void strictSchemaOmitsAndRejectsOntologyMutationTool() throws Exception {
        CrawlExtractionToolBackend backend = new CrawlExtractionToolBackend(
                "chunk-1",
                "document-1",
                "lfm",
                "graph-1",
                null,
                GraphExtractionValidationPolicy.defaults(),
                new GraphSchema(
                        List.of(
                                new NodeType("PERSON", "A person", null),
                                new NodeType("COMPANY", "A company", null)),
                        List.of(new RelationshipType(
                                "WORKS_AT", "A person works at a company", null)),
                        List.of("(PERSON)-[:WORKS_AT]->(COMPANY)")),
                corpus(),
                null,
                null,
                UnifiedGraph::new,
                new GraphReasoningQueryService(null),
                ExtractionTarget.FULL_GRAPH,
                null,
                false);

        assertFalse(backend.catalogJson(DecomposedPromptTier.COMPACT)
                .contains(CrawlExtractionToolBackend.UPDATE_ONTOLOGY));
        List<ExtractionToolBackend.ToolDefinition> compactTools =
                backend.toolDefinitions(DecomposedPromptTier.COMPACT);
        assertEquals(List.of(CrawlExtractionToolBackend.SUBMIT_GRAPH_DELTA),
                compactTools.stream().map(ExtractionToolBackend.ToolDefinition::name).toList());
        assertEquals(
                "Submit named Text entities and explicit directed relations separately; entity names are exact "
                        + "Text spans, relation types are predicate labels, and the engine creates ids.",
                compactTools.get(0).description());
        assertTrue(compactTools.get(0).description().length() < 200);
        JsonNode compactParameters = MAPPER.valueToTree(compactTools.get(0).parameters());
        List<String> compactFields = new ArrayList<>();
        compactParameters.path("properties").fieldNames().forEachRemaining(compactFields::add);
        assertEquals(List.of("format", "entities", "relations"), compactFields);
        assertEquals(compactFields, MAPPER.convertValue(
                compactParameters.path("required"),
                MAPPER.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertEquals("indexed",
                compactParameters.path("properties").path("format").path("const").asText());
        JsonNode compactEntities = compactParameters.path("properties").path("entities");
        JsonNode compactRelations = compactParameters.path("properties").path("relations");
        assertEquals("array", compactEntities.path("type").asText());
        assertEquals("array", compactRelations.path("type").asText());
        assertTrue(compactEntities.path("uniqueItems").asBoolean());
        assertTrue(compactRelations.path("uniqueItems").asBoolean());
        JsonNode compactEntity = compactEntities.path("items").path("properties");
        JsonNode compactRelation = compactRelations.path("items").path("properties");
        assertFalse(compactEntity.has("id"));
        assertEquals(List.of("COMPANY", "PERSON"), MAPPER.convertValue(
                compactEntity.path("type").path("enum"),
                MAPPER.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertEquals(List.of("WORKS_AT"), MAPPER.convertValue(
                compactRelation.path("type").path("enum"),
                MAPPER.getTypeFactory().constructCollectionType(List.class, String.class)));

        JsonNode compactContext = MAPPER.readTree(
                backend.toolContextJson(DecomposedPromptTier.COMPACT));
        assertEquals("EMPTY", compactContext.path("graphState").asText());
        assertEquals(List.of("COMPANY", "PERSON"), MAPPER.convertValue(
                compactContext.path("allowedEntityTypes"),
                MAPPER.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertEquals(List.of("WORKS_AT"), MAPPER.convertValue(
                compactContext.path("allowedRelationTypes"),
                MAPPER.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertEquals("(PERSON)-[:WORKS_AT]->(COMPANY)",
                compactContext.path("allowedRelationPatterns").get(0).asText());
        assertFalse(compactContext.has("ontologyRevision"));
        assertFalse(compactContext.has("completeCorpusPassages"));
        assertTrue(compactContext.toString().length() < 450);

        JsonNode rejected = execute(
                backend, CrawlExtractionToolBackend.UPDATE_ONTOLOGY,
                "{\"nodeTypes\":[],\"relationshipTypes\":[],\"patterns\":[]}");
        assertEquals("ontology_update_disabled_by_strict_schema",
                rejected.path("error").asText());

        JsonNode malformed = execute(
                backend, CrawlExtractionToolBackend.SUBMIT_GRAPH_DELTA,
                """
                {
                  "format":"indexed",
                  "entities":[{"name":"Alex Rivera"}],
                  "relations":[]
                }
                """);
        assertEquals("invalid_compact_graph_delta_shape",
                malformed.path("error").asText());
        assertEquals("format",
                malformed.path("requiredShape").path("topLevel").get(0).asText());

        JsonNode accepted = execute(
                backend, CrawlExtractionToolBackend.SUBMIT_GRAPH_DELTA,
                """
                {
                  "format":"indexed",
                  "entities":[
                    {"name":"Alex Rivera","type":"PERSON"},
                    {"name":"Acme Robotics","type":"COMPANY"}
                  ],
                  "relations":[
                    {"source":0,"target":1,"type":"WORKS_AT"}
                  ]
                }
                """);
        assertTrue(accepted.path("ok").asBoolean(), accepted::toPrettyString);
        var canonical = backend.acceptedResult().orElseThrow();
        assertEquals(2, canonical.entities().size());
        assertEquals(1, canonical.relations().size());
        assertTrue(canonical.entities().get(0).id().startsWith("alex-rivera-"));
        assertTrue(canonical.entities().get(1).id().startsWith("acme-robotics-"));
        assertEquals(canonical.entities().get(0).id(),
                canonical.relations().get(0).source());
        assertEquals(canonical.entities().get(1).id(),
                canonical.relations().get(0).target());
    }

    @Test
    void compactOpenSchemaDiscoveryBoundsFreeTypeTokensWithoutInventingEnums() {
        CrawlExtractionToolBackend discovery = new CrawlExtractionToolBackend(
                "chunk-1",
                "document-1",
                "lfm",
                "graph-1",
                null,
                GraphExtractionValidationPolicy.defaults(),
                null,
                corpus(),
                null,
                null,
                UnifiedGraph::new,
                new GraphReasoningQueryService(null),
                ExtractionTarget.FULL_GRAPH,
                null,
                false);
        discovery.configureCompactProposalBounds(2, 1);

        JsonNode parameters = MAPPER.valueToTree(
                discovery.toolDefinitions(DecomposedPromptTier.COMPACT).get(0).parameters());
        JsonNode entityType = parameters.path("properties").path("entities")
                .path("items").path("properties").path("type");
        JsonNode relationType = parameters.path("properties").path("relations")
                .path("items").path("properties").path("type");

        assertEquals(32, entityType.path("maxLength").asInt());
        assertEquals(32, relationType.path("maxLength").asInt());
        assertEquals(2, parameters.path("properties").path("entities").path("maxItems").asInt());
        assertEquals(1, parameters.path("properties").path("relations").path("maxItems").asInt());
        assertFalse(entityType.has("enum"));
        assertFalse(relationType.has("enum"));
        assertTrue(entityType.path("description").asText().contains("no prose"));
        assertTrue(relationType.path("description").asText().contains("no prose"));
    }

    @Test
    void compactProposalBoundsPublishCardinalityWithoutHardcodedFacts() {
        GraphSchema schema = new GraphSchema(
                List.of(
                        new NodeType("PERSON", "A person", null),
                        new NodeType("COMPANY", "A company", null)),
                List.of(new RelationshipType(
                        "FOUNDED", "A person founded a company", null)),
                List.of("(PERSON)-[:FOUNDED]->(COMPANY)"));
        CrawlExtractionToolBackend backend = new CrawlExtractionToolBackend(
                "chunk-1",
                "document-1",
                "lfm",
                "graph-1",
                null,
                GraphExtractionValidationPolicy.defaults(),
                schema,
                corpus(),
                null,
                null,
                UnifiedGraph::new,
                new GraphReasoningQueryService(null),
                ExtractionTarget.FULL_GRAPH,
                new CrawlOntology(schema),
                false);
        backend.configureCompactProposalCardinality(2, 1);
        backend.configureCompactProposalGuidance(
                15, List.of("PERSON", "COMPANY"), List.of("FOUNDED"));

        ExtractionToolBackend.ToolDefinition submit =
                backend.toolDefinitions(DecomposedPromptTier.COMPACT).stream()
                        .filter(tool -> CrawlExtractionToolBackend.SUBMIT_GRAPH_DELTA
                                .equals(tool.name()))
                        .findFirst()
                        .orElseThrow();
        JsonNode parameters = MAPPER.valueToTree(submit.parameters());
        JsonNode entities = parameters.path("properties").path("entities");
        JsonNode relations = parameters.path("properties").path("relations");

        assertEquals(2, entities.path("maxItems").asInt());
        assertEquals(1, relations.path("maxItems").asInt());
        assertEquals(2, entities.path("minItems").asInt());
        assertFalse(entities.has("prefixItems"));
        assertEquals(1, relations.path("minItems").asInt());
        assertFalse(relations.has("prefixItems"));
        assertFalse(entities.path("items").path("properties").path("name").has("const"));
        assertEquals(1, entities.path("items").path("properties")
                .path("name").path("minLength").asInt());
        assertFalse(entities.path("items").path("properties").path("type").has("const"));
        assertFalse(relations.path("items").path("properties").path("source").has("const"));
        assertFalse(relations.path("items").path("properties").path("target").has("const"));
        assertEquals(1, relations.path("items").path("properties")
                .path("source").path("maximum").asInt());
        assertEquals(1, relations.path("items").path("properties")
                .path("target").path("maximum").asInt());
        assertTrue(relations.path("items").path("properties")
                .path("target").path("description").asText().contains("0 through 1"));
        assertFalse(relations.path("items").path("properties").path("type").has("const"));
        assertEquals(15, entities.path("items").path("properties")
                .path("name").path("maxLength").asInt());
        assertEquals(List.of("PERSON", "COMPANY"), MAPPER.convertValue(
                entities.path("items").path("properties").path("type").path("enum"),
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}));
    }

    @Test
    void typedEntityCandidatesBindNamesPositionallyWhileTypesRemainModelSelected() {
        GraphSchema schema = new GraphSchema(
                List.of(
                        new NodeType("PERSON", "A person", null),
                        new NodeType("COMPANY", "A company", null)),
                List.of(new RelationshipType(
                        "FOUNDED", "A person founded a company", null)),
                List.of("(PERSON)-[:FOUNDED]->(COMPANY)"));
        CrawlExtractionToolBackend backend = new CrawlExtractionToolBackend(
                "chunk-1",
                "document-1",
                "qwen",
                "graph-1",
                null,
                GraphExtractionValidationPolicy.defaults(),
                schema,
                corpus(),
                null,
                null,
                UnifiedGraph::new,
                new GraphReasoningQueryService(null),
                ExtractionTarget.FULL_GRAPH,
                new CrawlOntology(schema),
                false);
        backend.configureCompactProposalCardinality(2, 1);
        backend.configureCompactEntityCandidates(
                List.of("Alex Rivera", "Acme Robotics"),
                List.of("PERSON", "COMPANY"));
        backend.beginTypedEntityPhase();

        ExtractionToolBackend.ToolDefinition submit =
                backend.toolDefinitions(DecomposedPromptTier.COMPACT).stream()
                        .filter(tool -> CrawlExtractionToolBackend.SUBMIT_TYPED_ENTITIES
                                .equals(tool.name()))
                        .findFirst()
                        .orElseThrow();
        JsonNode entities = MAPPER.valueToTree(submit.parameters())
                .path("properties").path("entities");
        JsonNode prefixItems = entities.path("prefixItems");

        assertEquals(2, prefixItems.size());
        assertEquals(2, entities.path("minItems").asInt());
        assertEquals(2, entities.path("maxItems").asInt());
        assertTrue(entities.has("items"));
        assertFalse(entities.path("items").asBoolean(true));
        assertEquals("Alex Rivera", prefixItems.path(0).path("properties")
                .path("name").path("const").asText());
        assertEquals("Acme Robotics", prefixItems.path(1).path("properties")
                .path("name").path("const").asText());
        for (JsonNode row : prefixItems) {
            JsonNode type = row.path("properties").path("type");
            assertFalse(type.has("const"));
            List<String> allowedTypes = MAPPER.convertValue(
                    type.path("enum"),
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            assertEquals(2, allowedTypes.size());
            assertTrue(allowedTypes.containsAll(List.of("PERSON", "COMPANY")));
        }
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> backend.configureCompactProposalCardinality(1, 1));
    }

    @Test
    void relationCandidatesBindEndpointPairsPositionallyWhileTypesRemainModelSelected()
            throws Exception {
        GraphSchema schema = new GraphSchema(
                List.of(
                        new NodeType("PERSON", "A person", null),
                        new NodeType("COMPANY", "A company", null)),
                List.of(new RelationshipType(
                        "WORKS_AT", "A person works at a company", null)),
                List.of("(PERSON)-[:WORKS_AT]->(COMPANY)"));
        CrawlExtractionToolBackend backend = backend(corpus(), null, new UnifiedGraph(), schema);
        backend.configureCompactProposalCardinality(4, 2);
        backend.configureCompactEntityCandidates(
                List.of("Alex Rivera", "Morgan Chen", "Acme Robotics", "Nova Labs"),
                List.of("PERSON", "PERSON", "COMPANY", "COMPANY"));
        backend.configureCompactRelationCandidates(
                List.of(0, 1), List.of(2, 3), List.of("WORKS_AT", "WORKS_AT"));
        backend.beginRelationPhase(List.of(
                new ExtractedEntity("e0", "Alex Rivera", "PERSON", List.of(), null, 1.0, Map.of()),
                new ExtractedEntity("e1", "Morgan Chen", "PERSON", List.of(), null, 1.0, Map.of()),
                new ExtractedEntity("e2", "Acme Robotics", "COMPANY", List.of(), null, 1.0, Map.of()),
                new ExtractedEntity("e3", "Nova Labs", "COMPANY", List.of(), null, 1.0, Map.of())));

        ExtractionToolBackend.ToolDefinition submit =
                backend.toolDefinitions(DecomposedPromptTier.COMPACT).stream()
                        .filter(tool -> CrawlExtractionToolBackend.SUBMIT_RELATIONS
                                .equals(tool.name()))
                        .findFirst()
                        .orElseThrow();
        JsonNode context = MAPPER.readTree(
                backend.toolContextJson(DecomposedPromptTier.COMPACT));
        JsonNode candidates = context.path("sourceRelationCandidates");
        assertEquals(2, candidates.size());
        assertEquals(0, candidates.path(0).path("sourceIndex").asInt());
        assertEquals("Alex Rivera", candidates.path(0).path("sourceName").asText());
        assertEquals(2, candidates.path(0).path("targetIndex").asInt());
        assertEquals("Acme Robotics", candidates.path(0).path("targetName").asText());
        assertEquals("WORKS_AT", candidates.path(0).path("type").asText());
        assertEquals(1, candidates.path(1).path("sourceIndex").asInt());
        assertEquals("Morgan Chen", candidates.path(1).path("sourceName").asText());
        assertEquals(3, candidates.path(1).path("targetIndex").asInt());
        assertEquals("Nova Labs", candidates.path(1).path("targetName").asText());

        JsonNode relations = MAPPER.valueToTree(submit.parameters())
                .path("properties").path("relations");
        JsonNode prefixItems = relations.path("prefixItems");

        assertEquals(2, prefixItems.size());
        assertEquals(2, relations.path("minItems").asInt());
        assertEquals(2, relations.path("maxItems").asInt());
        assertTrue(relations.has("items"));
        assertFalse(relations.path("items").asBoolean(true));
        assertEquals(0, prefixItems.path(0).path("properties")
                .path("source").path("const").asInt());
        assertEquals(2, prefixItems.path(0).path("properties")
                .path("target").path("const").asInt());
        assertEquals(1, prefixItems.path(1).path("properties")
                .path("source").path("const").asInt());
        assertEquals(3, prefixItems.path(1).path("properties")
                .path("target").path("const").asInt());
        for (JsonNode row : prefixItems) {
            JsonNode type = row.path("properties").path("type");
            assertFalse(type.has("const"));
            assertEquals(List.of("WORKS_AT"), MAPPER.convertValue(
                    type.path("enum"),
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}));
        }
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> backend.configureCompactProposalCardinality(4, 1));
    }

    @Test
    void nativeSubmitSchemaIsMinimalByContextTierBoundsStringsAndNeverCapsProposalArrays() {
        CrawlExtractionToolBackend backend = backend(corpus(), null, new UnifiedGraph());

        JsonNode compact = MAPPER.valueToTree(backend.toolDefinitions(DecomposedPromptTier.COMPACT)
                .get(0).parameters());
        JsonNode compactEntity = compact.path("properties").path("entities")
                .path("items").path("properties");
        JsonNode compactRelation = compact.path("properties").path("relations")
                .path("items").path("properties");
        assertFalse(compactEntity.has("description"),
                "descriptions are optional evidence, not a default reason to reject a fact");
        assertFalse(compactEntity.has("aliases"));
        assertFalse(compactEntity.has("confidence"));
        assertFalse(compactEntity.has("properties"));
        assertFalse(compactRelation.has("confidence"));
        assertFalse(compactRelation.has("occurredAt"));
        assertTrue(compact.findValues("maxItems").isEmpty(),
                "proposal arrays are intentionally unlimited");
        assertEquals(160, compactEntity.path("id").path("maxLength").asInt());
        assertEquals(160, compactEntity.path("name").path("maxLength").asInt());
        assertEquals(160, compactEntity.path("type").path("maxLength").asInt());
        assertEquals(160, compactRelation.path("source").path("maxLength").asInt());
        assertEquals(160, compactRelation.path("target").path("maxLength").asInt());
        assertEquals(160, compactRelation.path("type").path("maxLength").asInt());
        assertTrue(compactEntity.path("name").path("description").asText()
                .contains("SOURCE or a retrieved passage"));
        assertTrue(compactEntity.path("name").path("description").asText()
                .contains("never copy a value"));
        assertTrue(compact.path("properties").path("relations").path("description").asText()
                .contains("explicitly stated in SOURCE or a retrieved passage"));

        JsonNode standard = MAPPER.valueToTree(backend.toolDefinitions(DecomposedPromptTier.STANDARD)
                .get(0).parameters());
        assertFalse(standard.path("properties").path("entities")
                .path("items").path("properties").has("aliases"));

        JsonNode rich = MAPPER.valueToTree(backend.toolDefinitions(DecomposedPromptTier.RICH)
                .get(0).parameters());
        assertTrue(rich.path("properties").path("entities")
                .path("items").path("properties").has("aliases"));
        assertTrue(rich.path("properties").path("relations")
                .path("items").path("properties").has("confidence"));
        assertFalse(rich.path("properties").path("relations")
                .path("items").path("properties").has("occurredAt"));

        JsonNode expanded = MAPPER.valueToTree(backend.toolDefinitions(DecomposedPromptTier.EXPANDED)
                .get(0).parameters());
        assertTrue(expanded.path("properties").path("entities")
                .path("items").path("properties").has("properties"));
        assertTrue(expanded.path("properties").path("relations")
                .path("items").path("properties").has("occurredAt"));
    }

    @Test
    void malformedTopLevelArgumentsReturnAnExecutableRetryContract() throws Exception {
        CrawlExtractionToolBackend backend = backend(corpus(), null, new UnifiedGraph());

        JsonNode rejected = execute(backend, CrawlExtractionToolBackend.SUBMIT_GRAPH_DELTA,
                """
                {"entities":[],"relationships":[]}
                """);

        assertFalse(rejected.path("ok").asBoolean());
        assertEquals("invalid_graph_delta_shape", rejected.path("error").asText());
        assertEquals("relations",
                rejected.path("requiredShape").path("topLevel").get(1).asText());
        assertEquals("relationships", rejected.path("receivedTopLevelKeys").get(1).asText());
        assertTrue(backend.acceptedResult().isEmpty());
    }

    @Test
    void graphToolUsesProductionFolAndEmbeddingAwareReasoning() throws Exception {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(GraphEntity.builder("lead")
                .type("PERSON").label("Finance Lead").confidence(0.9)
                .embedding(new double[]{1.0, 0.0}).build());
        graph.addEntity(GraphEntity.builder("close")
                .type("PROCESS_STEP").label("Close Review").confidence(0.9)
                .embedding(new double[]{0.9, 0.1}).build());
        graph.addRelation(GraphRelation.builder("approval", "lead", "close")
                .type("APPROVED").confidence(0.92).weight(0.92).directed(true).build());

        CrawlExtractionToolBackend backend = backend(corpus(), null, graph);
        JsonNode verifyResult = execute(backend, CrawlExtractionToolBackend.GRAPH_REASONING_QUERY,
                """
                {"operation":"VERIFY","entityId":"lead","targetId":"close",
                 "relationTypes":["APPROVED"]}
                """);
        assertEquals("SUPPORTED",
                verifyResult.path("result").path("status").asText());

        JsonNode similarResult = execute(backend, CrawlExtractionToolBackend.GRAPH_REASONING_QUERY,
                """
                {"operation":"SIMILAR","entityId":"lead","topK":5,"structural":"PSL"}
                """);
        assertTrue(similarResult.path("result").path("data")
                .path("semanticVectorAvailable").asBoolean());
        assertEquals("PSL", similarResult.path("result").path("data")
                .path("structuralEngine").asText());

        JsonNode incompleteSearch = execute(
                backend, CrawlExtractionToolBackend.GRAPH_REASONING_QUERY,
                """
                {"operation":"SEARCH"}
                """);
        assertFalse(incompleteSearch.path("ok").asBoolean());
        assertEquals("INVALID",
                incompleteSearch.path("result").path("status").asText());
        assertTrue(incompleteSearch.path("result").path("summary").asText()
                .contains("SEARCH requires queryText"));

        JsonNode incompleteClaim = execute(
                backend, CrawlExtractionToolBackend.GRAPH_REASONING_QUERY,
                """
                {"operation":"VERIFY","entityId":"lead","targetId":"close"}
                """);
        assertFalse(incompleteClaim.path("ok").asBoolean());
        assertTrue(incompleteClaim.path("result").path("summary").asText()
                .contains("relationTypes[0]"));
    }

    @Test
    void emptyGraphStillAllowsSchemaAndCapabilitiesQueries() throws Exception {
        CrawlExtractionToolBackend backend = backend(corpus(), null, new UnifiedGraph());

        JsonNode schema = execute(backend, CrawlExtractionToolBackend.GRAPH_REASONING_QUERY,
                "{\"operation\":\"SCHEMA\"}");
        JsonNode capabilities = execute(backend, CrawlExtractionToolBackend.GRAPH_REASONING_QUERY,
                "{\"operation\":\"CAPABILITIES\"}");

        assertTrue(schema.path("ok").asBoolean(), schema::toString);
        assertTrue(capabilities.path("ok").asBoolean(), capabilities::toString);
        assertEquals(0, schema.path("graphEntities").asInt());
        assertEquals(0, capabilities.path("graphEntities").asInt());
    }

    @Test
    void submitStagesManyProposalsAndMayReferenceExistingGraphEntities() throws Exception {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(GraphEntity.builder("existing")
                .type("PERSON").label("Existing Person").confidence(0.95).build());
        CrawlExtractionToolBackend backend = backend(corpus(), null, graph);

        JsonNode rejected = execute(backend, CrawlExtractionToolBackend.SUBMIT_GRAPH_DELTA,
                """
                {"entities":[],"relations":[
                  {"source":"existing","target":"missing","type":"OWNS",
                   "description":"source evidence","confidence":0.8}
                ]}
                """);
        assertFalse(rejected.path("ok").asBoolean());
        assertTrue(backend.acceptedResult().isEmpty(),
                "a rejected proposal must not change staged or live graph state");
        assertEquals(1, graph.entityCount());

        JsonNode accepted = execute(backend, CrawlExtractionToolBackend.SUBMIT_GRAPH_DELTA,
                """
                {"entities":[
                  {"id":"asset-1","name":"Asset One","type":"ASSET",
                   "description":"first source entity","confidence":0.9},
                  {"id":"asset-2","name":"Asset Two","type":"ASSET",
                   "description":"second source entity","confidence":0.8}
                ],"relations":[
                  {"source":"existing","target":"asset-1","type":"OWNS",
                   "description":"ownership evidence","confidence":0.8},
                  {"source":"asset-1","target":"asset-2","type":"RELATED_TO",
                   "description":"relationship evidence","confidence":0.7}
                ]}
                """);

        assertTrue(accepted.path("accepted").asBoolean());
        assertEquals(2, backend.acceptedResult().orElseThrow().entities().size());
        assertEquals(2, backend.acceptedResult().orElseThrow().relations().size());
        assertEquals("chunk-1",
                backend.acceptedResult().orElseThrow().metadata().sourceChunkId());
        assertEquals(1, graph.entityCount(),
                "submission is staged; the orchestrator owns the later merge");
    }

    private static CrawlExtractionToolBackend backend(
            CrawlCorpusSnapshot corpus, VectorStore vectors, UnifiedGraph graph) {
        return backend(corpus, vectors, graph, null);
    }

    private static CrawlExtractionToolBackend backend(
            CrawlCorpusSnapshot corpus,
            VectorStore vectors,
            UnifiedGraph graph,
            GraphSchema schema) {
        return backend(corpus, vectors, graph, schema, ExtractionTarget.FULL_GRAPH);
    }

    private static CrawlExtractionToolBackend backend(
            CrawlCorpusSnapshot corpus,
            VectorStore vectors,
            UnifiedGraph graph,
            GraphSchema schema,
            ExtractionTarget extractionTarget) {
        return new CrawlExtractionToolBackend(
                "chunk-1",
                "document-1",
                "lfm",
                "graph-1",
                null,
                GraphExtractionValidationPolicy.defaults(),
                schema,
                corpus,
                vectors,
                null,
                () -> graph,
                new GraphReasoningQueryService(null),
                extractionTarget);
    }

    private static CrawlCorpusSnapshot corpus() {
        return new CrawlCorpusSnapshot("snapshot-1", List.of(
                new CrawlCorpusPassage(
                        "p1", 0,
                        "The first passage mentions Orchid planning.",
                        "h1", Map.of("source", "one"), true),
                new CrawlCorpusPassage(
                        "p2", 1,
                        "Orchid approval was recorded by the finance team in the source.",
                        "h2", Map.of("source", "two"), true),
                new CrawlCorpusPassage(
                        "legacy-preview", 2,
                        "This is not full source text.",
                        "h3", Map.of(), false)));
    }

    private static JsonNode execute(
            CrawlExtractionToolBackend backend, String tool, String args) throws Exception {
        ExtractionToolBackend.ToolExecution execution =
                backend.execute(tool, MAPPER.readTree(args));
        return MAPPER.readTree(execution.json());
    }
}
