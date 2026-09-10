/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.project.NativeChatModels;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in live acceptance for the native Codex schema-prepass path.
 *
 * <p>The request intentionally supplies no final schema, entity-type list, relationship-type list,
 * or answer-bearing prompt. The model must discover a small ontology from two source passages, run
 * the endpoint-signature pass, and then use that frozen ontology for two separately planned graph
 * extraction batches. The graph and the job trace are both read back from disk so a fallback or a
 * metadata-only no-op cannot satisfy this test.</p>
 */
class ChatModelSchemaPrepassLiveIT {
    private static final String ENABLE_PROPERTY = "kompile.remote.chat.schema.prepass.live";
    private static final String CONFIG_ROOT_PROPERTY = "kompile.remote.chat.configRoot";
    private static final String MODEL = "gpt-5.6-luna";
    private static final String THINKING = "xhigh";
    private static final String KNOWLEDGE_BASE = "codex-luna-schema-prepass-live-it";
    private static final String AVERY = "Avery Stone";
    private static final String ROWAN = "Rowan Kim";
    private static final String NORTHSTAR = "Northstar Labs";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @TempDir
    Path projectRoot;

    @Test
    @Timeout(value = 12, unit = TimeUnit.MINUTES)
    void nativeCodexSchemaPrepassFreezesTypesAndEndpointsAcrossBatches() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(ENABLE_PROPERTY),
                "Native Codex schema-prepass crawl is opt-in; pass -D" + ENABLE_PROPERTY + "=true");

        ChatConfig configured = requireCodexConfiguration();
        configureRequestScopedCodex(configured);
        List<Path> sources = writeCorpus();
        ObjectNode request = crawlRequest(sources);

        // This is an acceptance guard against accidentally turning the live test into a strict-schema
        // extraction smoke. The final vocabulary must come from the corpus prepass/model response.
        JsonNode extraction = request.path("graphExtraction");
        assertFalse(extraction.has("standardizedSchema"));
        assertFalse(extraction.has("entityTypes"));
        assertFalse(extraction.has("relationshipTypes"));
        assertFalse(extraction.has("customPrompt"));

        ToolContext context = context();
        CrawlDocumentsTool crawlDocuments = new CrawlDocumentsTool((String) null, mapper);
        CrawlControlTool crawlControl = new CrawlControlTool((String) null, mapper);
        CrawlResultTool crawlResult = new CrawlResultTool((String) null, mapper);
        String jobId = null;
        try {
            ToolResult accepted = crawlDocuments.execute(request, context);
            assertFalse(accepted.isError(), accepted.getOutput());
            assertEquals("QUEUED", accepted.getMetadata().get("status"), accepted.getOutput());
            jobId = String.valueOf(accepted.getMetadata().get("jobId"));
            assertFalse(jobId.isBlank(), accepted.getOutput());

            ToolResult terminal = awaitTerminal(crawlControl, context, jobId,
                    Duration.ofMinutes(10));
            assertFalse(terminal.isError(), terminal.getOutput());
            assertEquals(Boolean.TRUE, terminal.getMetadata().get("terminal"), terminal.getOutput());
            assertEquals("COMPLETED", terminal.getMetadata().get("status"), terminal.getOutput());

            ToolResult result = crawlResult.execute(
                    mapper.createObjectNode().put("jobId", jobId), context);
            assertFalse(result.isError(), result.getOutput());
            assertEquals("COMPLETED", result.getMetadata().get("status"), result.getOutput());
            assertTrue(((Number) result.getMetadata().get("semanticEntityCount")).intValue() >= 3,
                    result.getMetadata().toString());
            assertTrue(((Number) result.getMetadata().get("semanticRelationCount")).intValue() >= 2,
                    result.getMetadata().toString());
            assertTrue(((List<?>) result.getMetadata().get("semanticExtractionErrors")).isEmpty(),
                    result.getMetadata().toString());

            Path persistedRequest = projectRoot.resolve(".kompile/state/crawl-jobs")
                    .resolve(jobId).resolve("request.json");
            assertTrue(Files.isRegularFile(persistedRequest), persistedRequest.toString());
            JsonNode persisted = mapper.readTree(Files.readString(persistedRequest));
            JsonNode persistedExtraction = persisted.path("graphExtraction");
            assertFalse(persistedExtraction.has("standardizedSchema"), persisted.toPrettyString());
            assertFalse(persistedExtraction.has("entityTypes"), persisted.toPrettyString());
            assertFalse(persistedExtraction.has("relationshipTypes"), persisted.toPrettyString());

            List<JsonNode> calls = readTrace(jobId).stream()
                    .filter(event -> "LLM_CALL".equals(event.path("eventType").asText()))
                    .map(event -> event.path("payload"))
                    .toList();
            assertPrepassCalls(calls);
            assertExtractionBatches(calls);

            Path graphPath = projectRoot.resolve("data/crawls").resolve(KNOWLEDGE_BASE)
                    .resolve(LocalProjectGraphBackend.GRAPH_FILE);
            assertTrue(Files.isRegularFile(graphPath), graphPath.toString());
            UnifiedGraph graph = UnifiedGraph.load(graphPath);
            assertPersistedCanonicalGraph(graph, calls);
        } finally {
            cancelIfActive(crawlControl, context, jobId);
        }
    }

    private ChatConfig requireCodexConfiguration() {
        Path configRoot = Path.of(System.getProperty(
                CONFIG_ROOT_PROPERTY, System.getProperty("user.dir")))
                .toAbsolutePath().normalize();
        ChatConfig configured = ChatConfig.loadOrFromEnv(configRoot);
        assertNotNull(configured,
                "Native Codex chat configuration is missing; configure openai-codex and authenticate it before enabling this test");
        assertEquals("openai-codex", NativeChatModels.normalizeProvider(configured.getProvider()));
        assertEquals("standard", configured.getChatMode());
        assertFalse(configured.isKompileLocalServing());
        assertFalse(configured.isKompileServer());
        assertTrue(configured.isValid(),
                "Native Codex authentication/configuration is unavailable; authenticate openai-codex before retrying");
        return configured;
    }

    private void configureRequestScopedCodex(ChatConfig configured) throws Exception {
        ChatConfig scoped = new ChatConfig(configured.getProvider(), null, MODEL, configured.getBaseUrl());
        scoped.setAuthenticationMethod(configured.getAuthenticationMethod());
        scoped.setThinking(THINKING);
        scoped.setChatMode("standard");
        scoped.setApiKey(null);
        scoped.saveProject(projectRoot);

        ChatConfig saved = ChatConfig.loadProject(projectRoot);
        assertNotNull(saved);
        assertEquals(MODEL, saved.getModel());
        assertEquals(THINKING, saved.getThinking());
        String persisted = Files.readString(ChatConfig.projectConfigPath(projectRoot), StandardCharsets.UTF_8);
        assertFalse(persisted.contains("apiKey"), persisted);
    }

    private List<Path> writeCorpus() throws Exception {
        Path first = projectRoot.resolve("people-and-company-a.md");
        Files.writeString(first, "# Employment note A\n\n"
                + AVERY + " works for " + NORTHSTAR + ". "
                + AVERY + " coordinates the platform team at " + NORTHSTAR + ".\n",
                StandardCharsets.UTF_8);
        Path second = projectRoot.resolve("people-and-company-b.md");
        Files.writeString(second, "# Employment note B\n\n"
                + ROWAN + " works for " + NORTHSTAR + ". "
                + ROWAN + " collaborates with " + AVERY + " at " + NORTHSTAR + ".\n",
                StandardCharsets.UTF_8);
        return List.of(first, second);
    }

    private ObjectNode crawlRequest(List<Path> sources) {
        ObjectNode request = mapper.createObjectNode()
                .put("name", "Native Codex schema prepass acceptance")
                .put("async", true)
                .put("deriveOntology", true)
                .put("strictSteps", true);
        request.putObject("knowledgeBase").put("name", KNOWLEDGE_BASE);
        var documents = request.putArray("documents");
        for (Path source : sources) {
            documents.addObject().put("path", source.toString());
        }
        request.putArray("steps")
                .add("LOADING")
                .add("MARKDOWN_EXTRACTION")
                .add("CHUNKING")
                .add("GRAPH_EXTRACTION");
        request.putObject("embeddingTraining").put("enabled", false);
        request.putObject("reasoningLearning").put("enabled", false);
        request.putObject("runtimeConfig")
                .put("graphExtractionParallelism", 2)
                .put("graphExtractionRemoteParallelism", 2)
                .put("graphExtractionBatchSize", 1)
                .put("graphExtractionMaxItemsPerBatch", 1)
                .put("graphExtractionTargetCharsPerBatch", 2_048)
                .put("llmCallTimeoutSeconds", 180);

        request.putObject("graphExtraction")
                .put("llmProvider", "chat:codex")
                .put("modelName", MODEL)
                .put("thinking", THINKING)
                .put("schemaMode", "LENIENT")
                .put("extractionMode", "SINGLE_PASS")
                .put("entityResolution", false)
                .put("minConfidence", 0.0);
        return request;
    }

    private ToolContext context() {
        PermissionService permissions = new PermissionService();
        for (String tool : List.of("crawl_documents", "crawl_control", "crawl_result")) {
            permissions.setUserOverride(tool, PermissionService.PermissionLevel.ALLOW);
        }
        return new ToolContext(
                "codex-luna-schema-prepass-live-it",
                AgentConfig.builder("codex-luna-schema-prepass-live-it")
                        .enabledTools(Set.of("*")).build(),
                permissions,
                projectRoot,
                new ToolRegistry(mapper));
    }

    private void assertPrepassCalls(List<JsonNode> calls) {
        List<JsonNode> prepass = calls.stream()
                .filter(call -> "SCHEMA_PREPASS".equals(call.path("phase").asText()))
                .toList();
        assertFalse(prepass.isEmpty(),
                "No SCHEMA_PREPASS call was recorded; the test must not pass through a fallback/no-op path");
        assertTrue(prepass.stream().allMatch(call -> call.path("success").asBoolean()
                        && call.path("structured").asBoolean()), prepass.toString());
        assertTrue(prepass.stream().anyMatch(call ->
                        call.path("passId").asText().contains("relationship-signatures")),
                "The endpoint-signature prepass was not executed: " + prepass);
        assertTrue(prepass.stream().anyMatch(call -> {
            String prompt = call.path("promptText").asText().toLowerCase(Locale.ROOT);
            return prompt.contains("base entity type hierarchy")
                    || prompt.contains("base connection families")
                    || prompt.contains("canonical frozen node types");
        }), "The configured baseline hierarchy was not supplied to schema prepass: " + prepass);
    }

    private void assertExtractionBatches(List<JsonNode> calls) {
        List<JsonNode> extraction = calls.stream()
                .filter(call -> !"SCHEMA_PREPASS".equals(call.path("phase").asText()))
                .filter(call -> call.path("success").asBoolean())
                .toList();
        assertTrue(extraction.size() >= 2,
                "Expected two successful extraction batches, got " + extraction);
        Set<String> sourceMarkers = extraction.stream()
                .map(call -> call.path("promptText").asText())
                .flatMap(prompt -> java.util.stream.Stream.of(
                        prompt.contains("Employment note A") ? "A" : null,
                        prompt.contains("Employment note B") ? "B" : null))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        assertEquals(Set.of("A", "B"), sourceMarkers,
                "The extraction calls did not cover two separate source batches: " + extraction);
    }

    private void assertPersistedCanonicalGraph(UnifiedGraph graph, List<JsonNode> calls) {
        List<GraphEntity> semanticEntities = graph.entities().stream()
                .filter(entity -> entity.hasTag("semantic"))
                .toList();
        Set<String> semanticEntityIds = semanticEntities.stream()
                .map(GraphEntity::id)
                .collect(Collectors.toSet());
        List<GraphRelation> semanticRelations = graph.relations().stream()
                .filter(relation -> "unified-corpus-extraction".equals(
                        String.valueOf(relation.attributes().get("provenance"))))
                // A model-extracted fact is schema-backed and connects two extracted entities.
                // Provenance alone also matches the graph's structural CONTAINS_ENTITY edges.
                .filter(relation -> semanticEntityIds.contains(relation.sourceId())
                        && semanticEntityIds.contains(relation.targetId()))
                .toList();
        List<GraphRelation> structuralRelations = graph.relations().stream()
                .filter(relation -> "unified-corpus-extraction".equals(
                        String.valueOf(relation.attributes().get("provenance"))))
                .filter(relation -> "CONTAINS_ENTITY".equalsIgnoreCase(relation.type()))
                .toList();
        FrozenSchemaContract frozenSchema = frozenSchemaContract(calls);
        assertFalse(frozenSchema.endpointPatterns().isEmpty(),
                "Successful extraction context did not expose the frozen endpoint contract");
        assertTrue(semanticEntities.stream().anyMatch(entity -> containsLabel(entity, AVERY)),
                semanticEntities.toString());
        assertTrue(semanticEntities.stream().anyMatch(entity -> containsLabel(entity, ROWAN)),
                semanticEntities.toString());
        assertTrue(semanticEntities.stream().anyMatch(entity -> containsLabel(entity, NORTHSTAR)),
                semanticEntities.toString());
        assertFalse(semanticRelations.isEmpty(), "The persisted graph contains no semantic endpoints");
        assertTrue(graph.types().stream().anyMatch(type -> !type.isBlank()), graph.types().toString());
        assertTrue(semanticEntities.stream().allMatch(entity ->
                        !entity.type().isBlank()
                                && entity.type().equals(entity.type().toUpperCase(Locale.ROOT))),
                semanticEntities.toString());
        assertTrue(semanticRelations.stream().allMatch(relation ->
                        !relation.type().isBlank()
                                && relation.type().equals(relation.type().toUpperCase(Locale.ROOT))),
                semanticRelations.toString());
        assertTrue(semanticRelations.stream().allMatch(relation -> {
            Object family = relation.attributes().get("schema.connectionFamily");
            return family != null && !String.valueOf(family).isBlank();
        }), "Persisted relationships must retain their canonical connection-family signature: "
                + semanticRelations);

        Set<String> fingerprints = new HashSet<>();
        semanticEntities.forEach(entity -> addFingerprint(fingerprints, entity.attributes()));
        assertEquals(1, fingerprints.size(),
                "Every persisted fact must carry the same frozen schema fingerprint: " + fingerprints);
        String frozenFingerprint = fingerprints.iterator().next();
        assertFalse(frozenFingerprint.isBlank());
        assertTrue(semanticRelations.stream().allMatch(relation ->
                        frozenFingerprint.equals(String.valueOf(
                                relation.attributes().get("schema.fingerprint")))),
                "Every semantic endpoint edge must carry the exact frozen fingerprint: "
                        + semanticRelations);
        assertTrue(semanticRelations.stream().allMatch(relation -> {
            GraphEntity source = graph.entity(relation.sourceId()).orElse(null);
            GraphEntity target = graph.entity(relation.targetId()).orElse(null);
            return source != null && target != null && frozenSchema.accepts(relation, source, target);
        }), "Semantic endpoint edge does not match the frozen schema contract: "
                + semanticRelations + " patterns=" + frozenSchema.endpointPatterns());

        Map<String, Set<String>> typesByName = new HashMap<>();
        semanticEntities.forEach(entity -> typesByName
                .computeIfAbsent(normalize(entity.label()), ignored -> new HashSet<>())
                .add(entity.type()));
        for (String repeated : List.of(AVERY, ROWAN, NORTHSTAR)) {
            assertEquals(1, typesByName.getOrDefault(normalize(repeated), Set.of()).size(),
                    "Repeated entity was admitted under aliases: " + repeated + " " + typesByName);
        }
        assertTrue(semanticRelations.stream().allMatch(relation ->
                        graph.entity(relation.sourceId()).isPresent()
                                && graph.entity(relation.targetId()).isPresent()),
                "Persisted relation endpoints must resolve to canonical persisted entities");
        assertTrue(structuralRelations.stream().allMatch(relation ->
                        !relation.attributes().containsKey("schema.fingerprint")
                                && !relation.attributes().containsKey("schema.connectionFamily")),
                "Structural containment edges must not receive model schema metadata: "
                        + structuralRelations);
        Set<String> structuralTargets = structuralRelations.stream()
                .map(GraphRelation::targetId)
                .collect(Collectors.toSet());
        assertTrue(structuralTargets.containsAll(semanticEntityIds),
                "Persisted structural containment edges must retain every extracted entity: "
                        + structuralRelations);
    }

    private static FrozenSchemaContract frozenSchemaContract(List<JsonNode> calls) {
        Pattern endpointPattern = Pattern.compile(
                "\\(([A-Z][A-Z0-9_]*)\\)-\\[:([A-Z][A-Z0-9_]*)\\]->\\(([A-Z][A-Z0-9_]*)\\)");
        Pattern parentDeclarations = Pattern.compile(
                "child -> parent declarations:\\s*\\{([^}]*)\\}");
        Set<SchemaEndpoint> endpointPatterns = new LinkedHashSet<>();
        Map<String, String> parentTypes = new HashMap<>();
        for (JsonNode call : calls) {
            if (!call.path("success").asBoolean()) continue;
            String context = call.path("promptText").asText("") + "\n"
                    + call.path("structuredRequestJson").asText("");
            Matcher endpointMatcher = endpointPattern.matcher(context);
            while (endpointMatcher.find()) {
                endpointPatterns.add(new SchemaEndpoint(
                        endpointMatcher.group(1), endpointMatcher.group(2), endpointMatcher.group(3)));
            }
            Matcher parentMatcher = parentDeclarations.matcher(context);
            while (parentMatcher.find()) {
                Matcher pair = Pattern.compile("([A-Z][A-Z0-9_]*)\\s*=\\s*([A-Z][A-Z0-9_]*)")
                        .matcher(parentMatcher.group(1));
                while (pair.find()) {
                    parentTypes.put(pair.group(1), pair.group(2));
                }
            }
        }
        return new FrozenSchemaContract(List.copyOf(endpointPatterns), Map.copyOf(parentTypes));
    }

    private record SchemaEndpoint(String sourceType, String relationType, String targetType) {
    }

    private record FrozenSchemaContract(List<SchemaEndpoint> endpointPatterns,
                                       Map<String, String> parentTypes) {
        private boolean accepts(GraphRelation relation, GraphEntity source, GraphEntity target) {
            String relationType = canonicalType(relation.type());
            return endpointPatterns.stream().anyMatch(pattern ->
                    pattern.relationType().equals(relationType)
                            && matchesType(source.type(), pattern.sourceType(), parentTypes)
                            && matchesType(target.type(), pattern.targetType(), parentTypes));
        }
    }

    private static boolean matchesType(String actual, String expected, Map<String, String> parentTypes) {
        String current = canonicalType(actual);
        String wanted = canonicalType(expected);
        Set<String> seen = new HashSet<>();
        while (!current.isBlank() && seen.add(current)) {
            if (current.equals(wanted)) return true;
            current = canonicalType(parentTypes.get(current));
        }
        return false;
    }

    private static String canonicalType(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean containsLabel(GraphEntity entity, String label) {
        return normalize(entity.label()).contains(normalize(label));
    }

    private static void addFingerprint(Set<String> fingerprints, Map<String, Object> attributes) {
        Object fingerprint = attributes == null ? null : attributes.get("schema.fingerprint");
        assertNotNull(fingerprint, "Persisted fact has no schema.fingerprint: " + attributes);
        fingerprints.add(String.valueOf(fingerprint));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private List<JsonNode> readTrace(String jobId) throws Exception {
        Path trace = projectRoot.resolve(".kompile/state/crawl-jobs")
                .resolve(jobId).resolve("trace.jsonl");
        assertTrue(Files.isRegularFile(trace), trace.toString());
        List<JsonNode> events = new ArrayList<>();
        for (String line : Files.readAllLines(trace, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) events.add(mapper.readTree(line));
        }
        return events;
    }

    private static ToolResult awaitTerminal(CrawlControlTool crawlControl,
                                            ToolContext context,
                                            String jobId,
                                            Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        ToolResult latest = null;
        while (Instant.now().isBefore(deadline)) {
            latest = crawlControl.execute(
                    new ObjectMapper().createObjectNode().put("operation", "status")
                            .put("jobId", jobId), context);
            if (latest.isError() || Boolean.TRUE.equals(latest.getMetadata().get("terminal"))) {
                return latest;
            }
            Thread.sleep(Math.min(1_000L, Math.max(100L,
                    ((Number) latest.getMetadata().getOrDefault("pollAfterMs", 1_000L)).longValue())));
        }
        assertNotNull(latest, "crawl_control returned no status before timeout");
        throw new AssertionError("Native Codex schema-prepass crawl timed out: " + latest.getOutput());
    }

    private static void cancelIfActive(CrawlControlTool crawlControl,
                                       ToolContext context,
                                       String jobId) throws Exception {
        if (jobId == null || jobId.isBlank()) return;
        ObjectMapper mapper = new ObjectMapper();
        ToolResult status = crawlControl.execute(
                mapper.createObjectNode().put("operation", "status").put("jobId", jobId), context);
        if (status.isError() || Boolean.TRUE.equals(status.getMetadata().get("terminal"))) return;
        crawlControl.execute(
                mapper.createObjectNode().put("operation", "cancel").put("jobId", jobId), context);
    }
}
