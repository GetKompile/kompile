/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.ontology;

import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.app.web.dto.ontology.DeriveOntologyRequest;
import ai.kompile.app.web.dto.ontology.OntologyCandidatesResponse;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.graphrag.agent.ExtractionLlmService;
import ai.kompile.core.graphrag.agent.ExtractionLlmServiceRegistry;
import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.knowledgegraph.service.FactSheetGraphService;
import ai.kompile.process.ontology.Cardinality;
import ai.kompile.process.ontology.EntityClassification;
import ai.kompile.process.ontology.EntityTypeDefinition;
import ai.kompile.process.ontology.FieldDefinition;
import ai.kompile.process.ontology.FieldType;
import ai.kompile.process.ontology.OntologySchema;
import ai.kompile.process.ontology.RelationshipTypeDefinition;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Derives a {@link OntologySchema} from the knowledge graph that a crawl built for a fact sheet.
 *
 * <p>This service lives in {@code kompile-app-main} — the composition layer that already depends on
 * the process-engine ontology model, the knowledge-graph services, and the core {@link LLMChat}
 * abstraction — because derivation crosses all three. The lower-level
 * {@code kompile-process-engine} module owns persistence/validation of ontologies but knows nothing
 * about fact sheets or LLMs, so the derivation glue belongs here.</p>
 *
 * <p>Two derivation modes feed one path:</p>
 * <ul>
 *   <li><b>LLM</b> — graph context + user guidance/seeds are sent to {@link LLMChat}; the JSON
 *       response is parsed into an {@link OntologySchema}.</li>
 *   <li><b>Structural fallback</b> — when no LLM bean is wired (or the LLM response cannot be
 *       parsed), a deterministic schema is built directly from the graph's top concepts / the
 *       wizard's seed entity types, so the feature degrades gracefully instead of failing.</li>
 * </ul>
 *
 * <p>{@link #derive(DeriveOntologyRequest)} returns an <b>unsaved draft</b>. The caller reviews/edits
 * it and persists via the existing {@code POST /api/process/ontology} create endpoint, keeping
 * derivation non-destructive.</p>
 */
@Service
public class OntologyDerivationService {

    private static final Logger log = LoggerFactory.getLogger(OntologyDerivationService.class);

    private static final int DEFAULT_MAX_ENTITY_TYPES = 12;
    private static final int DEFAULT_MAX_CONCEPTS = 60;
    private static final int MAX_ENTITY_LABELS_IN_PROMPT = 50;
    private static final int MAX_EXAMPLE_LINKS_IN_PROMPT = 12;

    /**
     * Strict output contract handed to the model. Mirrors {@link OntologySchema} and the allowed enum
     * values so the response parses cleanly into the process-engine model.
     */
    private static final String SYSTEM_PROMPT = """
            You are an expert knowledge- and process-ontology engineer. You convert a knowledge graph
            that was extracted from crawled documents into a strict, well-typed ontology schema.

            Return ONLY a single JSON object — no markdown fences, no commentary — with this shape:
            {
              "name": "string",
              "entityTypes": [
                {
                  "name": "PascalCaseTypeName",
                  "description": "string",
                  "classification": "one of REFERENCE | TRANSACTIONAL | PATTERN | CONTROL | METRIC | ACTOR",
                  "confidence": 0.0,
                  "fields": [
                    {
                      "name": "camelCaseField",
                      "type": "one of STRING | INTEGER | DECIMAL | BOOLEAN | DATE | DATETIME | ENUM | ENUM_ARRAY | MAP",
                      "required": true,
                      "primaryKey": false,
                      "description": "string",
                      "enumValues": ["only", "for", "ENUM", "types"]
                    }
                  ],
                  "rules": [
                    {
                      "name": "string",
                      "ruleType": "one of ASSERTION | BUDGET_LIMIT | ESCALATION_TRIGGER | INVARIANT | THRESHOLD | SUM_CHECK | RANGE_CHECK | CUSTOM",
                      "expression": "a SpEL/CEL-style boolean expression",
                      "severity": "one of INFO | WARNING | ERROR | CRITICAL",
                      "description": "string"
                    }
                  ]
                }
              ],
              "relationshipTypes": [
                {
                  "type": "VERB_PHRASE_IN_CAPS",
                  "sourceEntityType": "PascalCaseTypeName",
                  "targetEntityType": "PascalCaseTypeName",
                  "cardinality": "one of ONE_TO_ONE | ONE_TO_MANY | MANY_TO_ONE | MANY_TO_MANY",
                  "description": "string"
                }
              ],
              "globalRules": [
                {
                  "name": "string",
                  "ruleType": "see ruleType values above",
                  "expression": "string",
                  "severity": "see severity values above",
                  "description": "string"
                }
              ]
            }

            Hard requirements:
            - Ground every entity type in the supplied concepts/labels; do not invent unrelated domains.
            - Use concise PascalCase entity names and camelCase field names.
            - Give every entity type exactly one field with "primaryKey": true.
            - Use only the enum values listed above, spelled exactly (UPPERCASE).
            - Emit "relationshipTypes" and rules only when the user asks for them.
            - The output MUST be valid JSON parseable by Jackson.
            """;

    private final FactSheetGraphService graphService;
    private final FactSheetService factSheetService;

    /** Optional — absent when no chat model is configured; we fall back to structural derivation. */
    @Autowired(required = false)
    private LLMChat llmChat;

    /**
     * Optional registry that routes a prompt to a chosen provider+model — CLI agents (e.g.
     * {@code claude-cli}, {@code opencode-cli}) or kompile-hosted/served models. When a request names a
     * non-default {@code modelProvider}, generation goes through this instead of {@link #llmChat}.
     */
    @Autowired(required = false)
    private ExtractionLlmServiceRegistry extractionRegistry;

    /** Lenient copy of the shared mapper: unknown enum values become null rather than failing the parse. */
    private final ObjectMapper mapper = JsonUtils.standardMapper().copy()
            .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true)
            .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);

    @Autowired
    public OntologyDerivationService(FactSheetGraphService graphService,
                                     FactSheetService factSheetService) {
        this.graphService = graphService;
        this.factSheetService = factSheetService;
    }

    /** Test seam for injecting (or clearing) the optional chat model. */
    void setLlmChat(LLMChat llmChat) {
        this.llmChat = llmChat;
    }

    /** Test seam for injecting (or clearing) the optional provider/model routing registry. */
    void setExtractionRegistry(ExtractionLlmServiceRegistry extractionRegistry) {
        this.extractionRegistry = extractionRegistry;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Wizard step 2: graph-grounded candidates (no LLM)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Surface candidate entity types and relationship hints straight from a fact sheet's graph,
     * with no LLM call, so the wizard can show the user real choices to seed derivation.
     */
    public OntologyCandidatesResponse candidates(Long factSheetId, int conceptLimit) {
        FactSheet sheet = requireSheet(factSheetId);
        Map<String, Object> stats = safeStats(factSheetId);
        long totalNodes = asLong(stats.get("totalNodes"));

        int limit = clamp(conceptLimit <= 0 ? DEFAULT_MAX_CONCEPTS : conceptLimit, 1, 200);
        List<Map<String, Object>> concepts = safeTopConcepts(factSheetId, limit);

        List<OntologyCandidatesResponse.Candidate> candidates = concepts.stream()
                .map(c -> {
                    String name = str(c.get("name"));
                    long mentions = asLong(c.containsKey("totalMentions") ? c.get("totalMentions") : c.get("count"));
                    return new OntologyCandidatesResponse.Candidate(
                            name, mentions, toPascalCase(name), guessClassification(name).name());
                })
                .filter(c -> !c.concept().isBlank())
                .collect(Collectors.toList());

        List<OntologyCandidatesResponse.RelationshipHint> hints = buildRelationshipHints(factSheetId, stats);

        List<String> classifications = Arrays.stream(EntityClassification.values())
                .map(Enum::name).collect(Collectors.toList());

        return new OntologyCandidatesResponse(
                factSheetId,
                sheet.getName(),
                totalNodes > 0,
                asLong(stats.get("entityCount")),
                asLong(stats.get("documentCount")),
                asLong(stats.get("distinctConcepts")),
                totalNodes,
                asLong(stats.get("totalEdges")),
                candidates,
                hints,
                classifications);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Derivation
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Derive an unsaved draft {@link OntologySchema} for a fact sheet.
     *
     * @throws IllegalArgumentException if the fact sheet does not exist
     * @throws IllegalStateException    if there is nothing to derive from (empty graph, no LLM, no seeds)
     */
    public OntologySchema derive(DeriveOntologyRequest req) {
        return derive(req, DerivationProgress.NOOP);
    }

    /**
     * Derive a draft while emitting progress + the generation transcript to {@code progress}. The async
     * job path supplies a streaming sink; the synchronous {@link #derive(DeriveOntologyRequest)} passes
     * {@link DerivationProgress#NOOP}. Same result contract either way.
     */
    public OntologySchema derive(DeriveOntologyRequest req, DerivationProgress progress) {
        if (req == null || req.factSheetId() == null) {
            throw new IllegalArgumentException("factSheetId is required");
        }
        FactSheet sheet = requireSheet(req.factSheetId());

        int maxEntityTypes = clamp(defInt(req.maxEntityTypes(), DEFAULT_MAX_ENTITY_TYPES), 1, 40);
        boolean includeRelationships = defBool(req.includeRelationships(), true);
        boolean includeRules = defBool(req.includeValidationRules(), true);
        int maxConcepts = clamp(defInt(req.maxConcepts(), DEFAULT_MAX_CONCEPTS), 1, 200);
        List<String> seeds = nonNull(req.seedEntityTypes());
        List<String> focus = nonNull(req.focusClassifications());
        String guidance = req.guidance() == null ? "" : req.guidance().trim();

        progress.log("Loading crawl-graph context for fact sheet '" + sheet.getName() + "'…");
        GraphContext ctx = buildGraphContext(req.factSheetId(), maxConcepts);
        progress.log("Graph context: " + ctx.totalNodes + " nodes, " + ctx.concepts.size()
                + " concepts, " + ctx.edgesByType.size() + " edge types.");

        boolean canLlm = useRegistry(req) || llmChat != null;
        boolean haveSignal = ctx.totalNodes > 0 || !seeds.isEmpty() || !guidance.isBlank();
        if (!haveSignal && !canLlm) {
            throw new IllegalStateException("Fact sheet '" + sheet.getName()
                    + "' has no knowledge graph yet and no LLM is configured — build the graph first.");
        }

        OntologySchema schema;
        String method;
        if (canLlm) {
            String userPrompt = buildUserPrompt(sheet, ctx, guidance, seeds, focus, maxEntityTypes,
                    includeRelationships, includeRules);
            try {
                schema = deriveWithModel(req, userPrompt, progress);
                method = useRegistry(req) ? ("llm:" + req.modelProvider()) : "llm";
            } catch (Exception e) {
                log.warn("Model ontology derivation failed for factSheet {} — falling back to structural: {}",
                        req.factSheetId(), e.toString());
                progress.log("Generation failed (" + e.getMessage() + ") — building a structural draft instead.");
                schema = deriveStructural(ctx, seeds, maxEntityTypes, includeRelationships);
                method = "structural-fallback";
            }
        } else {
            progress.log("No LLM configured — building a structural draft from graph concepts/seeds.");
            schema = deriveStructural(ctx, seeds, maxEntityTypes, includeRelationships);
            method = "structural";
        }

        applyStructuralOptions(schema, maxEntityTypes, includeRelationships, includeRules);
        finalizeDraft(schema, req, sheet, ctx, method);
        progress.log("Draft ready: " + (schema.getEntityTypes() == null ? 0 : schema.getEntityTypes().size())
                + " entity types via " + method + ".");
        return schema;
    }

    /** True when the request names a concrete (non-default) provider the registry should route. */
    private boolean useRegistry(DeriveOntologyRequest req) {
        String provider = req.modelProvider();
        return extractionRegistry != null && provider != null
                && !provider.isBlank() && !provider.equalsIgnoreCase("default");
    }

    /** Generate and parse schema JSON via the chosen provider/model (registry) or the default LLM. */
    private OntologySchema deriveWithModel(DeriveOntologyRequest req, String userPrompt,
                                           DerivationProgress progress) throws Exception {
        String content;
        String usedProvider;
        String usedModel;
        if (useRegistry(req)) {
            ExtractionLlmService svc = extractionRegistry.getOrFallback(req.modelProvider());
            if (svc == null || !svc.isAvailable()) {
                throw new IllegalStateException("Provider '" + req.modelProvider() + "' is not available");
            }
            if (req.modelName() != null && !req.modelName().isBlank()) {
                svc.setModelOverride(req.modelName());
            }
            usedProvider = svc.getId();
            usedModel = svc.getEffectiveModel();
            progress.log("Generating with provider '" + usedProvider + "', model '" + usedModel + "'…");
            content = svc.complete(SYSTEM_PROMPT + "\n\n" + userPrompt);
        } else {
            usedProvider = "default";
            usedModel = (req.modelName() == null || req.modelName().isBlank()) ? "(default)" : req.modelName();
            progress.log("Generating with the default LLM…");
            content = llmChat.prompt().system(SYSTEM_PROMPT).user(userPrompt).call().content();
        }

        if (content == null || content.isBlank()) {
            throw new IllegalStateException("The model returned an empty response");
        }
        progress.transcript(usedProvider, usedModel, SYSTEM_PROMPT + "\n\n" + userPrompt, content);

        OntologySchema parsed = mapper.readValue(extractJsonObject(content), OntologySchema.class);
        if (parsed == null || parsed.getEntityTypes() == null || parsed.getEntityTypes().isEmpty()) {
            throw new IllegalStateException("Model response contained no entity types");
        }
        return parsed;
    }

    private OntologySchema deriveStructural(GraphContext ctx, List<String> seeds, int maxEntityTypes,
                                            boolean includeRelationships) {
        List<String> names = !seeds.isEmpty()
                ? seeds
                : ctx.concepts.stream().map(ConceptStat::name).collect(Collectors.toList());

        List<EntityTypeDefinition> entityTypes = names.stream()
                .map(OntologyDerivationService::toPascalCase)
                .filter(n -> !n.isBlank())
                .distinct()
                .limit(maxEntityTypes)
                .map(name -> EntityTypeDefinition.builder()
                        .name(name)
                        .description("Derived from crawl graph concept '" + name + "'.")
                        .classification(guessClassification(name))
                        .confidence(0.4)
                        .fields(List.of(
                                FieldDefinition.builder().name("id").type(FieldType.STRING)
                                        .primaryKey(true).required(true).description("Unique identifier.").build(),
                                FieldDefinition.builder().name("name").type(FieldType.STRING)
                                        .required(true).description("Human-readable name.").build()))
                        .build())
                .collect(Collectors.toList());

        if (entityTypes.isEmpty()) {
            throw new IllegalStateException(
                    "No concepts or seed entity types available to derive a structural ontology.");
        }

        List<RelationshipTypeDefinition> relationships = new ArrayList<>();
        if (includeRelationships && entityTypes.size() >= 2) {
            String source = entityTypes.get(0).getName();
            for (int i = 1; i < entityTypes.size() && relationships.size() < 8; i++) {
                relationships.add(RelationshipTypeDefinition.builder()
                        .type("RELATES_TO")
                        .sourceEntityType(source)
                        .targetEntityType(entityTypes.get(i).getName())
                        .cardinality(Cardinality.MANY_TO_MANY)
                        .description("Concepts co-occur in the crawl graph; refine this relationship.")
                        .build());
            }
        }

        return OntologySchema.builder()
                .entityTypes(entityTypes)
                .relationshipTypes(relationships.isEmpty() ? null : relationships)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Prompt construction
    // ─────────────────────────────────────────────────────────────────────────────

    private String buildUserPrompt(FactSheet sheet, GraphContext ctx, String guidance,
                                   List<String> seeds, List<String> focus, int maxEntityTypes,
                                   boolean includeRelationships, boolean includeRules) {
        StringBuilder sb = new StringBuilder();
        sb.append("Fact sheet: ").append(sheet.getName()).append('\n');
        sb.append("Description: ")
                .append(sheet.getDescription() == null || sheet.getDescription().isBlank()
                        ? "(none)" : sheet.getDescription())
                .append("\n\n");

        sb.append("Knowledge-graph summary: ")
                .append(ctx.entityCount).append(" entities, ")
                .append(ctx.documentCount).append(" documents, ")
                .append(ctx.distinctConcepts).append(" distinct concepts, ")
                .append(ctx.totalNodes).append(" nodes, ")
                .append(ctx.totalEdges).append(" edges.\n\n");

        if (!ctx.concepts.isEmpty()) {
            sb.append("Top concepts (by mention count):\n");
            ctx.concepts.forEach(c -> sb.append("- ").append(c.name())
                    .append(" (").append(c.mentions()).append(")\n"));
            sb.append('\n');
        }
        if (!ctx.entityLabels.isEmpty()) {
            sb.append("Representative entity labels: ")
                    .append(String.join(", ", ctx.entityLabels)).append("\n\n");
        }
        if (!ctx.edgesByType.isEmpty()) {
            sb.append("Relationship signals (edge type: count):\n");
            ctx.edgesByType.forEach((t, c) -> sb.append("- ").append(t).append(": ").append(c).append('\n'));
            if (!ctx.exampleLinks.isEmpty()) {
                sb.append("Example links:\n");
                ctx.exampleLinks.forEach(l -> sb.append("- ").append(l).append('\n'));
            }
            sb.append('\n');
        }

        sb.append("Constraints:\n");
        sb.append("- Maximum entity types: ").append(maxEntityTypes).append('\n');
        sb.append("- Include relationship types: ").append(includeRelationships ? "yes" : "no").append('\n');
        sb.append("- Include validation rules: ").append(includeRules ? "yes" : "no").append('\n');
        sb.append("- Focus on classifications: ")
                .append(focus.isEmpty() ? "any" : String.join(", ", focus)).append('\n');
        sb.append("- Seed entity types to define (use these names): ")
                .append(seeds.isEmpty() ? "(model decides)" : String.join(", ", seeds)).append("\n\n");

        if (!guidance.isBlank()) {
            sb.append("Additional guidance from the user:\n").append(guidance).append("\n\n");
        }
        sb.append("Produce the ontology JSON now.");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Post-processing
    // ─────────────────────────────────────────────────────────────────────────────

    /** Enforce the structural toggles regardless of which path produced the schema. */
    private void applyStructuralOptions(OntologySchema schema, int maxEntityTypes,
                                        boolean includeRelationships, boolean includeRules) {
        if (schema.getEntityTypes() != null && schema.getEntityTypes().size() > maxEntityTypes) {
            schema.setEntityTypes(new ArrayList<>(schema.getEntityTypes().subList(0, maxEntityTypes)));
        }
        if (!includeRelationships) {
            schema.setRelationshipTypes(null);
        }
        if (!includeRules) {
            schema.setGlobalRules(null);
            if (schema.getEntityTypes() != null) {
                schema.getEntityTypes().forEach(et -> et.setRules(null));
            }
        }
    }

    private void finalizeDraft(OntologySchema schema, DeriveOntologyRequest req, FactSheet sheet,
                               GraphContext ctx, String method) {
        schema.setName(resolveName(req, sheet));
        schema.setVersion(1);
        schema.setUpdatedBy("ontology-derivation");

        Map<String, Object> metadata = schema.getMetadata() != null
                ? new HashMap<>(schema.getMetadata()) : new HashMap<>();
        metadata.put("derivedFromFactSheetId", sheet.getId());
        metadata.put("derivedFromFactSheetName", sheet.getName());
        metadata.put("generationMethod", method);
        metadata.put("generatedAt", Instant.now().toString());
        metadata.put("conceptsSampled", ctx.concepts.size());
        metadata.put("guidanceProvided", req.guidance() != null && !req.guidance().isBlank());
        if (req.modelProvider() != null && !req.modelProvider().isBlank()) {
            metadata.put("modelProvider", req.modelProvider());
        }
        if (req.modelName() != null && !req.modelName().isBlank()) {
            metadata.put("modelName", req.modelName());
        }
        metadata.put("draft", true);
        schema.setMetadata(metadata);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Graph context gathering
    // ─────────────────────────────────────────────────────────────────────────────

    private GraphContext buildGraphContext(Long factSheetId, int maxConcepts) {
        Map<String, Object> stats = safeStats(factSheetId);
        List<ConceptStat> concepts = safeTopConcepts(factSheetId, maxConcepts).stream()
                .map(c -> new ConceptStat(str(c.get("name")),
                        asLong(c.containsKey("totalMentions") ? c.get("totalMentions") : c.get("count"))))
                .filter(c -> !c.name().isBlank())
                .collect(Collectors.toList());

        List<String> entityLabels = new ArrayList<>();
        List<String> exampleLinks = new ArrayList<>();
        try {
            FactSheetGraphService.GraphVisualizationData viz =
                    graphService.getVisualizationData(factSheetId, 150, 80);
            if (viz != null) {
                Map<String, String> labelById = new LinkedHashMap<>();
                for (Map<String, Object> node : nonNull(viz.nodes())) {
                    String id = str(node.get("id"));
                    String label = str(node.get("label"));
                    if (!id.isBlank()) {
                        labelById.put(id, label.isBlank() ? id : label);
                    }
                    if ("ENTITY".equalsIgnoreCase(str(node.get("type")))
                            && !label.isBlank() && entityLabels.size() < MAX_ENTITY_LABELS_IN_PROMPT
                            && !entityLabels.contains(label)) {
                        entityLabels.add(label);
                    }
                }
                for (Map<String, Object> edge : nonNull(viz.edges())) {
                    if (exampleLinks.size() >= MAX_EXAMPLE_LINKS_IN_PROMPT) {
                        break;
                    }
                    String src = labelById.getOrDefault(str(edge.get("source")), str(edge.get("source")));
                    String tgt = labelById.getOrDefault(str(edge.get("target")), str(edge.get("target")));
                    String type = str(edge.get("type"));
                    if (!src.isBlank() && !tgt.isBlank()) {
                        exampleLinks.add(src + " --" + (type.isBlank() ? "RELATED" : type) + "--> " + tgt);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not load visualization data for factSheet {}: {}", factSheetId, e.toString());
        }

        Map<String, Long> edgesByType = new LinkedHashMap<>();
        Object rawEdges = stats.get("edgesByType");
        if (rawEdges instanceof Map<?, ?> m) {
            m.forEach((k, v) -> edgesByType.put(str(k), asLong(v)));
        }

        return new GraphContext(
                asLong(stats.get("entityCount")),
                asLong(stats.get("documentCount")),
                asLong(stats.get("distinctConcepts")),
                asLong(stats.get("totalNodes")),
                asLong(stats.get("totalEdges")),
                concepts, entityLabels, edgesByType, exampleLinks);
    }

    private List<OntologyCandidatesResponse.RelationshipHint> buildRelationshipHints(
            Long factSheetId, Map<String, Object> stats) {
        Map<String, String> exampleByType = new LinkedHashMap<>();
        try {
            FactSheetGraphService.GraphVisualizationData viz =
                    graphService.getVisualizationData(factSheetId, 100, 60);
            if (viz != null) {
                Map<String, String> labelById = new LinkedHashMap<>();
                for (Map<String, Object> node : nonNull(viz.nodes())) {
                    String id = str(node.get("id"));
                    if (!id.isBlank()) {
                        String label = str(node.get("label"));
                        labelById.put(id, label.isBlank() ? id : label);
                    }
                }
                for (Map<String, Object> edge : nonNull(viz.edges())) {
                    String type = str(edge.get("type"));
                    if (type.isBlank() || exampleByType.containsKey(type)) {
                        continue;
                    }
                    String src = labelById.getOrDefault(str(edge.get("source")), str(edge.get("source")));
                    String tgt = labelById.getOrDefault(str(edge.get("target")), str(edge.get("target")));
                    if (!src.isBlank() && !tgt.isBlank()) {
                        exampleByType.put(type, src + " → " + tgt);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not load relationship hints for factSheet {}: {}", factSheetId, e.toString());
        }

        List<OntologyCandidatesResponse.RelationshipHint> hints = new ArrayList<>();
        Object rawEdges = stats.get("edgesByType");
        if (rawEdges instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                String type = str(k);
                hints.add(new OntologyCandidatesResponse.RelationshipHint(
                        type, asLong(v), exampleByType.get(type)));
            });
        }
        return hints;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private FactSheet requireSheet(Long factSheetId) {
        return factSheetService.getSheetById(factSheetId)
                .orElseThrow(() -> new IllegalArgumentException("Fact sheet not found: " + factSheetId));
    }

    private Map<String, Object> safeStats(Long factSheetId) {
        try {
            Map<String, Object> stats = graphService.getGraphStatistics(factSheetId);
            return stats != null ? stats : Map.of();
        } catch (Exception e) {
            log.debug("Could not load graph statistics for factSheet {}: {}", factSheetId, e.toString());
            return Map.of();
        }
    }

    private List<Map<String, Object>> safeTopConcepts(Long factSheetId, int limit) {
        try {
            List<Map<String, Object>> concepts = graphService.getTopConcepts(factSheetId, limit);
            return concepts != null ? concepts : List.of();
        } catch (Exception e) {
            log.debug("Could not load top concepts for factSheet {}: {}", factSheetId, e.toString());
            return List.of();
        }
    }

    private String resolveName(DeriveOntologyRequest req, FactSheet sheet) {
        if (req.name() != null && !req.name().isBlank()) {
            return req.name().trim();
        }
        return sheet.getName() + " Ontology";
    }

    /** Strip markdown fences and isolate the outermost JSON object from an LLM response. */
    static String extractJsonObject(String response) {
        String text = response.trim();
        if (text.startsWith("```")) {
            text = text.replaceAll("^```[a-zA-Z]*\\R?", "").replaceAll("\\R?```\\s*$", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    /** Convert an arbitrary concept/label into a PascalCase entity-type name. */
    static String toPascalCase(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String[] tokens = raw.trim().split("[^A-Za-z0-9]+");
        StringBuilder sb = new StringBuilder();
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            sb.append(Character.toUpperCase(token.charAt(0)));
            if (token.length() > 1) {
                sb.append(token.substring(1));
            }
        }
        String result = sb.toString();
        if (result.isEmpty()) {
            return "";
        }
        if (!Character.isLetter(result.charAt(0))) {
            result = "Entity" + result;
        }
        return result;
    }

    /** Lightweight heuristic mapping a concept name to a default classification for seeds/structural. */
    static EntityClassification guessClassification(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (containsAny(n, "forecast", "actual", "transaction", "invoice", "order", "submission", "adjustment")) {
            return EntityClassification.TRANSACTIONAL;
        }
        if (containsAny(n, "metric", "kpi", "rate", "ratio", "score", "margin", "revenue")) {
            return EntityClassification.METRIC;
        }
        if (containsAny(n, "approval", "control", "policy", "compliance", "gate", "audit", "rule")) {
            return EntityClassification.CONTROL;
        }
        if (containsAny(n, "user", "approver", "owner", "team", "person", "agent", "manager", "role")) {
            return EntityClassification.ACTOR;
        }
        if (containsAny(n, "pattern", "anomaly", "trend", "signal")) {
            return EntityClassification.PATTERN;
        }
        return EntityClassification.REFERENCE;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static <T> List<T> nonNull(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static int defInt(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static boolean defBool(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Internal value types
    // ─────────────────────────────────────────────────────────────────────────────

    private record ConceptStat(String name, long mentions) {
    }

    private record GraphContext(
            long entityCount,
            long documentCount,
            long distinctConcepts,
            long totalNodes,
            long totalEdges,
            List<ConceptStat> concepts,
            List<String> entityLabels,
            Map<String, Long> edgesByType,
            List<String> exampleLinks) {
    }
}
