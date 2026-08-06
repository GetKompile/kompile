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
import ai.kompile.core.graphrag.typing.GraphNodeTypes;
import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.knowledgegraph.service.FactSheetGraphService;
import ai.kompile.process.ontology.Cardinality;
import ai.kompile.process.ontology.EntityClassification;
import ai.kompile.process.ontology.EntityTypeDefinition;
import ai.kompile.process.ontology.FieldDefinition;
import ai.kompile.process.ontology.FieldType;
import ai.kompile.process.ontology.OntologySchema;
import ai.kompile.process.ontology.RelationshipTypeDefinition;
import ai.kompile.process.ontology.RuleSeverity;
import ai.kompile.process.ontology.RuleType;
import ai.kompile.process.ontology.ValidationRule;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final int MAX_RELATIONSHIP_CORRECTION_PASSES = 3;
    private static final int MAX_CLASSIFICATION_CORRECTION_PASSES = 1;
    private static final int MAX_RULE_ACTION_CORRECTION_PASSES = 2;

    /**
     * Strict output contract handed to the model. Mirrors {@link OntologySchema} and the allowed enum
     * values so the response parses cleanly into the process-engine model.
     */
    private static final String SYSTEM_PROMPT = """
            You are an expert knowledge- and process-ontology engineer. Convert only the supplied
            knowledge-graph evidence into a strict, well-typed ontology schema.

            Return exactly one raw JSON object with these top-level keys in order: "name",
            "entityTypes", "relationshipTypes", and "globalRules". The final three values are arrays.
            Do not return markdown, commentary, ellipses, or a second root object.

            ENTITY TYPE OBJECT CONTRACT:
            - Required: "name" (source-grounded type name), "description" (source-grounded string),
              "classification" (exactly REFERENCE, TRANSACTIONAL, PATTERN, CONTROL, METRIC, or ACTOR),
              "confidence" (number from 0.45 through 1.0), and "fields" (array).
            - Optional: "aliases" (source labels), "localizedLabels" (language-tag to source label),
              "parentType" (another emitted entity type name or null), and "rules" (array).
            - Each domain field requires "name", "type", "required", and "description".
              Field type must be exactly STRING, INTEGER, DECIMAL, BOOLEAN, DATE, DATETIME, ENUM,
              ENUM_ARRAY, or MAP. "enumValues" is allowed only for ENUM and ENUM_ARRAY.
            - The engine owns the identifier field and primary-key choice; do not emit them.

            RELATIONSHIP TYPE OBJECT CONTRACT:
            - Required: "type", "sourceEntityType", "targetEntityType", "cardinality", "transitive",
              and "description".
            - "type" must be a concise source-grounded UPPERCASE_WITH_UNDERSCORES verb phrase.
            - Endpoints must be names present in "entityTypes".
            - Cardinality must be exactly ONE_TO_ONE, ONE_TO_MANY, MANY_TO_ONE, or MANY_TO_MANY.

            RULE OBJECT CONTRACT:
            - Required: "name", "ruleType", "expression", "severity", and "description".
            - ruleType must be exactly ASSERTION, BUDGET_LIMIT, ESCALATION_TRIGGER, INVARIANT,
              THRESHOLD, SUM_CHECK, RANGE_CHECK, or CUSTOM.
            - severity must be exactly INFO, WARNING, ERROR, or CRITICAL.

            GROUNDING REQUIREMENTS:
            - Ground every emitted name, type, field, relationship, and rule in the supplied concepts,
              labels, graph statistics, example links, or explicit user guidance.
            - Never emit metasyntactic placeholders or copy a descriptive phrase from this contract as data.
            - Use concise PascalCase entity names and camelCase field names for Latin-script evidence;
              preserve non-Latin names when no faithful Latin-script canonical name is supplied.
            - Preserve source-language labels as aliases/localizedLabels.
            - Emit relationshipTypes and rules only when supported by evidence or requested guidance.
            - Use transitive=true only when the supplied evidence supports a transitive hierarchy.
            - The output must be valid JSON parseable by Jackson.
            """;

    private static final String ENTITY_TYPE_SYSTEM_PROMPT = """
            TASK: Identify only the entity types supported by the supplied graph evidence.
            Return one raw JSON object with top-level key "entityTypes" (array).
            Each object requires "name", "description", and "confidence";
            optional keys are "aliases" and "localizedLabels".
            Classification and hierarchy are separate engine-controlled tasks; do not emit them here.
            confidence is from 0.45 through 1.0.
            Do not emit fields, relationships, rules, schema metadata, placeholders, or examples.
            If no type is supported, return exactly {"entityTypes":[]}.
            Output JSON only.
            """;

    private static final String ENTITY_CLASSIFICATION_SYSTEM_PROMPT = """
            TASK: Classify exactly one engine-fixed entity type.
            Return exactly one raw JSON object shaped as {"selectedOrdinal":null}.
            Replace null with exactly one ordinal from this classification ballot:
            1 = REFERENCE: stable lookup/master data.
            2 = TRANSACTIONAL: a business record or object that is created, submitted, reviewed, or approved.
            3 = PATTERN: a reusable recurring template or behavioral pattern.
            4 = CONTROL: a policy, constraint, checkpoint, or control definition.
            5 = METRIC: a quantitative measure or calculated indicator.
            6 = ACTOR: a person, role, team, or organization that performs actions.
            Classify what the fixed type itself represents, not a person or role related to it.
            Do not rename or repeat the type and do not emit fields, relationships, or rules.
            Output JSON only.
            """;

    private static final String ENTITY_CLASSIFICATION_CORRECTION_SYSTEM_PROMPT = """
            TASK: Correct one rejected entity classification response.
            Return exactly one raw JSON object shaped as {"selectedOrdinal":null}.
            Use the required ordinal supplied by production validation. Do not rename or repeat the
            entity type and do not emit fields, relationships, rules, markdown, or prose.
            Output JSON only.
            """;

    private static final String FIELD_SYSTEM_PROMPT = """
            TASK: Select domain fields for exactly one engine-fixed entity type.
            Return one raw JSON object with top-level key "fields" (array).
            Each field requires "name", "type", "required", and "description".
            type is exactly STRING, INTEGER, DECIMAL, BOOLEAN, DATE, DATETIME, ENUM, ENUM_ARRAY, or MAP.
            Optional constraints are maxLength, immutable, regex, fkReference, enumValues, min, max,
            and defaultValue. The engine adds the identifier and primary key after this task.
            Never return id, primaryKey, the entity type name, another entity type name, relationships,
            rules, placeholders, or examples as fields.
            If no domain field is supported, return exactly {"fields":[]}.
            Output JSON only.
            """;

    private static final String RELATIONSHIP_SYSTEM_PROMPT = """
            TASK: Identify only supported relationships between the engine-fixed entity types.
            Return one raw JSON object with top-level key "relationshipTypes" (array).
            Each object requires "type", "sourceOrdinal", "targetOrdinal", "cardinality",
            "transitive", and "description". type is a concise source-grounded uppercase verb phrase.
            sourceOrdinal and targetOrdinal must come from the supplied ballot; do not return entity names.
            cardinality is exactly ONE_TO_ONE, ONE_TO_MANY, MANY_TO_ONE, or MANY_TO_MANY.
            Emit at most one direction for a relationship type and endpoint pair; never emit its inverse duplicate.
            Do not emit entity types, fields, rules, placeholders, or examples.
            If no relationship is supported, return exactly {"relationshipTypes":[]}.
            Output JSON only.
            """;

    private static final String RELATIONSHIP_CORRECTION_SYSTEM_PROMPT = """
            TASK: Correct one rejected relationship extraction response.
            Return the full corrected raw JSON object with top-level key "relationshipTypes" (array).
            The array may contain zero, one, or many relationships. There is no one-relationship limit.
            Preserve every distinct relationship that is supported by the bounded source evidence; do not
            reduce the answer to one relationship merely because validation rejected another entry.
            Every relationship requires "type", "sourceOrdinal", "targetOrdinal", "cardinality",
            "transitive", and "description". Endpoint ordinals must come from the supplied ballot.
            cardinality is exactly ONE_TO_ONE, ONE_TO_MANY, MANY_TO_ONE, or MANY_TO_MANY.
            Fix every supplied validation error. Never emit duplicate or inverse-duplicate entries.
            Output exactly one JSON object and no prose.
            """;

    private static final String RULE_SYSTEM_PROMPT = """
            TASK: Identify only executable validation-rule cores explicitly supported by the supplied
            evidence and engine-fixed ontology. Return one raw JSON object with top-level key
            "globalRules" (array). Each rule requires only "name", "expression", and "description".
            "expression" must be one complete condition from the engine-grounded expression ballot and
            must include its comparison/operator/constraint phrase. A bare entity name, field reference,
            relationship type, or source label is not an executable rule.
            Do not classify ruleType, severity, or violation action in this task. Do not emit an id,
            types, fields, relationships, placeholders, or examples.
            If no executable rule is supported, return exactly {"globalRules":[]}.
            Output JSON only.
            """;

    private static final String RULE_TYPE_SYSTEM_PROMPT = """
            TASK: Classify exactly one engine-fixed validation rule.
            Return exactly one raw JSON object shaped as {"selectedOrdinal":null}.
            Replace null with exactly one ordinal chosen by meaning:
            1 = ASSERTION: a general business or record condition that must hold, including required-field checks.
            2 = BUDGET_LIMIT: a spending or value budget constraint.
            3 = ESCALATION_TRIGGER: a condition whose purpose is to trigger escalation.
            4 = INVARIANT: a structural ontology condition that must remain true across state changes;
              do not use it for an ordinary record-field validation.
            5 = THRESHOLD: a one-sided threshold alert.
            6 = SUM_CHECK: values must sum to a target.
            7 = RANGE_CHECK: a value must remain between lower and upper bounds.
            8 = CUSTOM: a domain-specific composite not covered above.
            Do not change or repeat the rule core.
            Output JSON only.
            """;

    private static final String RULE_ACTION_SYSTEM_PROMPT = """
            TASK: Select the violation action for exactly one engine-fixed validation rule.
            Return exactly one raw JSON object shaped as {"selectedOrdinal":null,"escalateTo":null}.
            Replace selectedOrdinal with exactly one ordinal chosen by meaning:
            1 = no violation action is specified by the evidence.
            2 = halt.
            3 = log.
            4 = escalate.
            5 = auto_correct.
            escalateTo must be a source-grounded role/person for ordinal 4 and must be null for every
            other ordinal. Do not change or repeat the rule core.
            Output JSON only.
            """;

    private static final String RULE_ACTION_CORRECTION_SYSTEM_PROMPT = """
            TASK: Correct one rejected validation-rule action response.
            Return exactly one raw JSON object shaped as {"selectedOrdinal":null,"escalateTo":null}.
            Copy the production-required selectedOrdinal supplied in the correction task; do not classify
            the action again. Copy the required escalateTo value exactly. Do not repeat the rule, evidence,
            rejected response, validation errors, markdown, or prose. Output JSON only.
            """;

    private static final String RULE_SEVERITY_SYSTEM_PROMPT = """
            TASK: Classify severity for exactly one engine-fixed rule and violation action.
            Return exactly one raw JSON object shaped as {"selectedOrdinal":null}.
            Replace null with exactly one ordinal from this severity ballot:
            1 = INFO: informational and non-blocking; normally paired with log.
            2 = WARNING: recoverable degradation that does not reject the result.
            3 = ERROR: invalid result that must be rejected or halted.
            4 = CRITICAL: severe systemic, safety, or catastrophic business failure.
            When the fixed action is halt and the evidence is not catastrophic, select 3 (ERROR).
            Do not change or repeat the fixed rule or action. Output JSON only.
            """;

    static String systemPromptContract() {
        return SYSTEM_PROMPT;
    }

    static List<String> splitPromptContracts() {
        return List.of(ENTITY_TYPE_SYSTEM_PROMPT, ENTITY_CLASSIFICATION_SYSTEM_PROMPT,
                ENTITY_CLASSIFICATION_CORRECTION_SYSTEM_PROMPT, FIELD_SYSTEM_PROMPT,
                RELATIONSHIP_SYSTEM_PROMPT, RELATIONSHIP_CORRECTION_SYSTEM_PROMPT, RULE_SYSTEM_PROMPT,
                RULE_TYPE_SYSTEM_PROMPT, RULE_ACTION_SYSTEM_PROMPT, RULE_ACTION_CORRECTION_SYSTEM_PROMPT,
                RULE_SEVERITY_SYSTEM_PROMPT);
    }

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
                schema = deriveWithModel(req, userPrompt, progress, maxEntityTypes,
                        includeRelationships, includeRules);
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

    /**
     * Build a deterministic <b>structural</b> ontology draft (no LLM) for a fact sheet — used to
     * auto-provision a governing ontology during crawl enrichment so OWL/PSL reasoning is not inert.
     * Finalized (named, versioned, metadata-stamped) like any other draft.
     *
     * @throws IllegalArgumentException if the fact sheet does not exist
     * @throws IllegalStateException    if the graph is empty (nothing to derive from)
     */
    public OntologySchema deriveStructuralDraft(Long factSheetId) {
        FactSheet sheet = requireSheet(factSheetId);
        GraphContext ctx = buildGraphContext(factSheetId, DEFAULT_MAX_CONCEPTS);
        if (ctx.totalNodes == 0) {
            throw new IllegalStateException("Fact sheet '" + sheet.getName()
                    + "' has no knowledge graph yet — nothing to derive.");
        }
        OntologySchema schema = deriveStructural(ctx, List.of(), DEFAULT_MAX_ENTITY_TYPES, true);
        applyStructuralOptions(schema, DEFAULT_MAX_ENTITY_TYPES, true, false);
        DeriveOntologyRequest req = new DeriveOntologyRequest(
                factSheetId, null, null, DEFAULT_MAX_ENTITY_TYPES, true, false,
                null, null, DEFAULT_MAX_CONCEPTS, null, null);
        finalizeDraft(schema, req, sheet, ctx, "structural-auto");
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
                                           DerivationProgress progress, int maxEntityTypes,
                                           boolean includeRelationships,
                                           boolean includeRules) throws Exception {
        ExtractionLlmService routedService = null;
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
            routedService = svc;
        } else {
            usedProvider = "default";
            usedModel = (req.modelName() == null || req.modelName().isBlank()) ? "(default)" : req.modelName();
        }

        progress.log("Generating ontology entity types with provider '" + usedProvider
                + "', model '" + usedModel + "'…");
        String entityContent = completeStage(routedService, usedProvider, usedModel,
                ENTITY_TYPE_SYSTEM_PROMPT, userPrompt, progress);
        JsonNode entityRoot = mapper.readTree(extractJsonObject(entityContent));
        if (isLegacyCompleteSchema(entityRoot)) {
            OntologySchema parsed = mapper.treeToValue(entityRoot, OntologySchema.class);
            if (parsed == null || parsed.getEntityTypes() == null || parsed.getEntityTypes().isEmpty()) {
                throw new IllegalStateException("Model response contained no entity types");
            }
            return parsed;
        }

        List<EntityTypeDefinition> entityTypes = parseEntityTypes(entityRoot, maxEntityTypes);
        if (entityTypes.isEmpty()) {
            throw new IllegalStateException("Model response contained no entity types");
        }
        String evidence = boundedEvidence(userPrompt);
        for (int i = 0; i < entityTypes.size(); i++) {
            EntityTypeDefinition entityType = entityTypes.get(i);
            progress.log("Classifying entity type " + (i + 1) + "/" + entityTypes.size()
                    + ": " + entityType.getName());
            EntityClassification classification = null;
            String classificationContent = null;
            try {
                classificationContent = completeStage(routedService, usedProvider, usedModel,
                        ENTITY_CLASSIFICATION_SYSTEM_PROMPT,
                        entityClassificationPrompt(entityType, evidence), progress);
                classification = parseEntityClassification(classificationContent);
            } catch (Exception failure) {
                reportStageFailure(progress, "classification for " + entityType.getName(), failure);
            }
            Optional<EntityClassification> groundedHint = strongClassificationHint(entityType.getName());
            if (groundedHint.isPresent() && classification != groundedHint.get()) {
                for (int attempt = 1; attempt <= MAX_CLASSIFICATION_CORRECTION_PASSES
                        && classification != groundedHint.get(); attempt++) {
                    try {
                        progress.log("Entity classification rejected by production validation; requesting "
                                + "bounded correction " + attempt + "/"
                                + MAX_CLASSIFICATION_CORRECTION_PASSES + ".");
                        String correctionContent = completeStage(routedService, usedProvider, usedModel,
                                ENTITY_CLASSIFICATION_CORRECTION_SYSTEM_PROMPT,
                                entityClassificationCorrectionPrompt(entityType, evidence,
                                        classificationContent, groundedHint.get()), progress);
                        classification = parseEntityClassification(correctionContent);
                    } catch (Exception failure) {
                        reportStageFailure(progress,
                                "classification correction for " + entityType.getName(), failure);
                    }
                }
            }
            EntityClassification finalizedClassification = groundedHint.isPresent()
                    ? groundedHint.get() : (classification == null
                    ? guessClassification(entityType.getName()) : classification);
            entityType.setClassification(finalizedClassification);
        }
        Set<String> reservedEntityFieldNames = entityTypes.stream()
                .map(EntityTypeDefinition::getName)
                .map(OntologyDerivationService::fieldNameKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (int i = 0; i < entityTypes.size(); i++) {
            EntityTypeDefinition entityType = entityTypes.get(i);
            List<String> fieldHints = fieldHintsFor(entityType, evidence);
            if (fieldHints.isEmpty()) {
                progress.log("No grounded domain-field ballot for " + entityType.getName()
                        + "; adding only the engine-owned identifier.");
                entityType.setFields(List.of(identifierField()));
                continue;
            }
            progress.log("Generating fields for entity type " + (i + 1) + "/"
                    + entityTypes.size() + ": " + entityType.getName());
            try {
                String fieldContent = completeStage(routedService, usedProvider, usedModel,
                        FIELD_SYSTEM_PROMPT, fieldPrompt(entityType, evidence, fieldHints), progress);
                entityType.setFields(parseFields(fieldContent, fieldHints, reservedEntityFieldNames));
            } catch (Exception failure) {
                reportStageFailure(progress, "fields for " + entityType.getName(), failure);
                entityType.setFields(List.of(identifierField()));
            }
        }

        List<RelationshipTypeDefinition> relationships = null;
        if (includeRelationships && entityTypes.size() >= 2) {
            progress.log("Generating relationships over " + entityTypes.size()
                    + " engine-fixed entity types…");
            String boundedRelationshipPrompt = relationshipPrompt(entityTypes, evidence);
            try {
                String relationContent = completeStage(routedService, usedProvider, usedModel,
                        RELATIONSHIP_SYSTEM_PROMPT, boundedRelationshipPrompt, progress);
                RelationshipValidation validation = validateRelationships(relationContent, entityTypes);
                RelationshipValidation bestSalvage = validation;
                for (int attempt = 1; !validation.valid()
                        && attempt <= MAX_RELATIONSHIP_CORRECTION_PASSES; attempt++) {
                    progress.log("Relationship response rejected by production validation; requesting "
                            + "bounded correction " + attempt + "/"
                            + MAX_RELATIONSHIP_CORRECTION_PASSES + ".");
                    String correctionContent = completeStage(routedService, usedProvider, usedModel,
                            RELATIONSHIP_CORRECTION_SYSTEM_PROMPT,
                            relationshipCorrectionPrompt(boundedRelationshipPrompt, relationContent,
                                    validation.errors()), progress);
                    boolean unchangedPayload = sameJsonPayload(relationContent, correctionContent);
                    validation = validateRelationships(correctionContent, entityTypes);
                    if (validation.relationships().size() > bestSalvage.relationships().size()) {
                        bestSalvage = validation;
                    }
                    relationContent = correctionContent;
                    if (!validation.valid() && unchangedPayload) {
                        progress.log("Relationship correction repeated the rejected JSON payload; "
                                + "ending the feedback loop without redundant model calls.");
                        break;
                    }
                }
                if (!validation.valid()) {
                    if (!bestSalvage.relationships().isEmpty()) {
                        relationships = bestSalvage.relationships();
                        progress.log("Relationship feedback did not fully converge; retaining "
                                + relationships.size() + " distinct validator-approved relationship(s) "
                                + "and rejecting the remaining invalid or ambiguous candidates.");
                    } else {
                        throw new IllegalArgumentException("relationship validation failed after correction: "
                                + String.join("; ", validation.errors()));
                    }
                } else {
                    relationships = validation.relationships();
                }
            } catch (Exception failure) {
                reportStageFailure(progress, "relationships", failure);
                relationships = List.of();
            }
        }
        List<ValidationRule> globalRules = null;
        if (includeRules) {
            globalRules = new ArrayList<>();
            List<String> ruleExpressionHints = ruleExpressionHints(evidence);
            if (ruleExpressionHints.isEmpty()) {
                progress.log("No grounded executable-rule ballot; skipping rule-model calls.");
            } else {
                progress.log("Discovering validation-rule cores over the engine-fixed ontology…");
            }
            List<RuleCore> ruleCores = List.of();
            if (!ruleExpressionHints.isEmpty()) {
                try {
                    String ruleCoreContent = completeStage(routedService, usedProvider, usedModel,
                            RULE_SYSTEM_PROMPT,
                            rulePrompt(entityTypes, relationships, evidence, ruleExpressionHints), progress);
                    ruleCores = parseRuleCores(ruleCoreContent, ruleExpressionHints);
                } catch (Exception failure) {
                    reportStageFailure(progress, "rule-core discovery", failure);
                }
            }
            for (int i = 0; i < ruleCores.size(); i++) {
                RuleCore core = ruleCores.get(i);
                String relevantEvidence = relevantActionEvidenceForRule(core, evidence);
                progress.log("Classifying validation rule " + (i + 1) + "/" + ruleCores.size()
                        + ": " + core.name());
                RuleType ruleType = null;
                RuleAction action = null;
                RuleSeverity severity = null;
                try {
                    String typeContent = completeStage(routedService, usedProvider, usedModel,
                            RULE_TYPE_SYSTEM_PROMPT, ruleTypePrompt(core), progress);
                    ruleType = parseRuleType(typeContent);
                } catch (Exception failure) {
                    reportStageFailure(progress, "rule type for " + core.name(), failure);
                }
                try {
                    String actionContent = completeStage(routedService, usedProvider, usedModel,
                            RULE_ACTION_SYSTEM_PROMPT, ruleActionPrompt(core, relevantEvidence), progress);
                    RuleActionValidation actionValidation = validateRuleAction(actionContent, relevantEvidence);
                    RuleAction groundedSalvage = actionValidation.action();
                    for (int attempt = 1; !actionValidation.valid()
                            && attempt <= MAX_RULE_ACTION_CORRECTION_PASSES; attempt++) {
                        progress.log("Rule action rejected by production validation; requesting bounded "
                                + "correction " + attempt + "/" + MAX_RULE_ACTION_CORRECTION_PASSES + ".");
                        String correctionContent = completeStage(routedService, usedProvider, usedModel,
                                RULE_ACTION_CORRECTION_SYSTEM_PROMPT,
                                ruleActionCorrectionPrompt(core, relevantEvidence, actionContent,
                                        actionValidation.errors()), progress);
                        boolean unchangedPayload = sameJsonPayload(actionContent, correctionContent);
                        actionValidation = validateRuleAction(correctionContent, relevantEvidence);
                        if (actionValidation.action() != null) {
                            groundedSalvage = actionValidation.action();
                        }
                        actionContent = correctionContent;
                        if (!actionValidation.valid() && unchangedPayload) {
                            progress.log("Rule-action correction repeated the rejected JSON payload; "
                                    + "ending the feedback loop without redundant model calls.");
                            break;
                        }
                    }
                    if (!actionValidation.valid()) {
                        if (groundedSalvage != null) {
                            action = groundedSalvage;
                            progress.log("Rule-action feedback did not converge; using the unambiguous "
                                    + "source-grounded action selected by production validation.");
                        } else {
                            throw new IllegalArgumentException("rule-action validation failed after correction: "
                                    + String.join("; ", actionValidation.errors()));
                        }
                    } else {
                        action = actionValidation.action();
                    }
                } catch (Exception failure) {
                    reportStageFailure(progress, "rule action for " + core.name(), failure);
                }
                if (action != null) {
                    try {
                        String severityContent = completeStage(routedService, usedProvider, usedModel,
                                RULE_SEVERITY_SYSTEM_PROMPT, ruleSeverityPrompt(core, action), progress);
                        severity = parseRuleSeverity(severityContent);
                    } catch (Exception failure) {
                        reportStageFailure(progress, "rule severity for " + core.name(), failure);
                    }
                }
                if (ruleType == null || action == null || severity == null) {
                    continue;
                }
                globalRules.add(ValidationRule.builder()
                        .id("rule-" + (globalRules.size() + 1))
                        .name(core.name())
                        .description(core.description())
                        .expression(core.expression())
                        .ruleType(ruleType)
                        .severity(severity)
                        .onViolation(action.onViolation())
                        .escalateTo(action.escalateTo())
                        .build());
            }
        }
        return OntologySchema.builder()
                .entityTypes(entityTypes)
                .relationshipTypes(relationships == null || relationships.isEmpty()
                        ? null : relationships)
                .globalRules(globalRules == null || globalRules.isEmpty() ? null : globalRules)
                .build();
    }

    private String completeStage(ExtractionLlmService routedService, String provider, String model,
                                 String systemPrompt, String userPrompt,
                                 DerivationProgress progress) throws Exception {
        String content = routedService != null
                ? routedService.complete(systemPrompt + "\n\n" + userPrompt)
                : llmChat.prompt().system(systemPrompt).user(userPrompt).call().content();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("The model returned an empty response");
        }
        progress.transcript(provider, model, systemPrompt + "\n\n" + userPrompt, content);
        return content;
    }

    private void reportStageFailure(DerivationProgress progress, String stage, Exception failure) {
        log.warn("Ontology model stage '{}' failed and was isolated: {}", stage, failure.toString());
        progress.log("Model stage '" + stage + "' failed validation and was isolated: "
                + conciseError(failure));
    }

    private static boolean isLegacyCompleteSchema(JsonNode root) {
        if (root == null || !root.isObject()) {
            return false;
        }
        if (root.has("name") || root.has("relationshipTypes") || root.has("globalRules")) {
            return true;
        }
        JsonNode types = root.get("entityTypes");
        if (types != null && types.isArray()) {
            for (JsonNode type : types) {
                if (type.has("fields") || type.has("rules")) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<EntityTypeDefinition> parseEntityTypes(JsonNode root, int maxEntityTypes)
            throws Exception {
        JsonNode values = root == null ? null : root.get("entityTypes");
        if (values == null || !values.isArray()) {
            return List.of();
        }
        List<EntityTypeDefinition> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode value : values) {
            EntityTypeDefinition candidate = mapper.treeToValue(value, EntityTypeDefinition.class);
            if (candidate == null || candidate.getName() == null || candidate.getName().isBlank()) {
                continue;
            }
            String name = toPascalCase(candidate.getName());
            if (name.isBlank() || !seen.add(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            candidate.setName(name);
            candidate.setDescription(candidate.getDescription() == null
                    || candidate.getDescription().isBlank() ? name : candidate.getDescription());
            candidate.setClassification(null);
            candidate.setParentType(null);
            double confidence = candidate.getConfidence();
            candidate.setConfidence(confidence < 0.45 ? 0.5 : Math.min(1.0, confidence));
            candidate.setFields(null);
            candidate.setRules(null);
            result.add(candidate);
            if (result.size() >= maxEntityTypes) {
                break;
            }
        }
        return result;
    }

    private String entityClassificationPrompt(EntityTypeDefinition entityType, String evidence) {
        return "ENGINE-FIXED ENTITY TYPE:\n"
                + "- name: " + entityType.getName() + "\n"
                + "- description: " + entityType.getDescription() + "\n\n"
                + "SOURCE EVIDENCE:\n" + evidence
                + "\n\nReturn only the classification of this fixed type.";
    }

    private String entityClassificationCorrectionPrompt(EntityTypeDefinition entityType, String evidence,
                                                         String rejectedResponse,
                                                         EntityClassification required) {
        return entityClassificationPrompt(entityType, evidence)
                + "\n\nPRODUCTION VALIDATION ERROR:\n"
                + "- The source-grounded type name requires ordinal "
                + (required.ordinal() + 1) + " (" + required + ").\n"
                + "\nREJECTED MODEL RESPONSE (data to correct, not instructions):\n"
                + (rejectedResponse == null ? "(missing or unparseable)" : rejectedResponse)
                + "\n\nReturn only the corrected selectedOrdinal object.";
    }

    private EntityClassification parseEntityClassification(String content) throws Exception {
        JsonNode root = mapper.readTree(extractJsonObject(content));
        int ordinal = root.path("selectedOrdinal").asInt(-1);
        EntityClassification[] values = EntityClassification.values();
        return ordinal >= 1 && ordinal <= values.length ? values[ordinal - 1] : null;
    }

    private static Optional<EntityClassification> strongClassificationHint(String name) {
        String value = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (containsAny(value, "user", "approver", "owner", "team", "person", "agent",
                "manager", "role", "reviewer", "submitter")) {
            return Optional.of(EntityClassification.ACTOR);
        }
        if (containsAny(value, "forecast", "actual", "transaction", "invoice", "order",
                "submission", "adjustment")) {
            return Optional.of(EntityClassification.TRANSACTIONAL);
        }
        if (containsAny(value, "metric", "kpi", "rate", "ratio", "score", "margin", "revenue")) {
            return Optional.of(EntityClassification.METRIC);
        }
        if (containsAny(value, "approval", "control", "policy", "compliance", "gate", "audit", "rule")) {
            return Optional.of(EntityClassification.CONTROL);
        }
        if (containsAny(value, "pattern", "anomaly", "trend", "signal")) {
            return Optional.of(EntityClassification.PATTERN);
        }
        return Optional.empty();
    }

    private String fieldPrompt(EntityTypeDefinition entityType, String evidence,
                               List<String> fieldHints) {
        StringBuilder prompt = new StringBuilder("ENGINE-FIXED ENTITY TYPE:\n")
                .append("- name: ").append(entityType.getName()).append('\n')
                .append("- description: ").append(entityType.getDescription()).append('\n')
                .append("- classification: ").append(entityType.getClassification()).append('\n');
        if (entityType.getAliases() != null && !entityType.getAliases().isEmpty()) {
            prompt.append("- source aliases: ")
                    .append(String.join(" | ", entityType.getAliases())).append('\n');
        }
        prompt.append("\nENGINE-GROUNDED DOMAIN FIELD BALLOT:\n");
        if (fieldHints.isEmpty()) {
            prompt.append("- none extracted; emit a field only when the source evidence directly supports it\n");
        } else {
            for (String hint : fieldHints) {
                prompt.append("- ").append(hint).append('\n');
            }
            prompt.append("Choose only names from this ballot.\n");
        }
        prompt.append("\nSOURCE EVIDENCE:\n").append(evidence)
                .append("\n\nReturn domain fields only; the engine adds id and owns primaryKey.");
        return prompt.toString();
    }

    private List<String> fieldHintsFor(EntityTypeDefinition entityType, String evidence) {
        if (entityType == null || entityType.getName() == null || evidence == null || evidence.isBlank()) {
            return List.of();
        }
        LinkedHashMap<String, String> hints = new LinkedHashMap<>();
        Matcher qualified = Pattern.compile(
                        "(?iu)\\b" + Pattern.quote(entityType.getName())
                                + "\\.([\\p{L}][\\p{L}\\p{N}_-]*)\\b")
                .matcher(evidence);
        while (qualified.find()) {
            addFieldHint(hints, qualified.group(1));
        }
        Matcher listed = Pattern.compile(
                        "(?iu)\\b" + Pattern.quote(entityType.getName())
                                + "\\s+(?:has|contains|includes|with)\\s+([^.;\\n]+?)\\s+fields?\\b")
                .matcher(evidence);
        while (listed.find()) {
            for (String candidate : listed.group(1).split("(?iu)\\s*(?:,|\\band\\b|\\bor\\b)\\s*")) {
                addFieldHint(hints, candidate);
            }
        }
        return List.copyOf(hints.values());
    }

    private static void addFieldHint(Map<String, String> hints, String raw) {
        if (raw == null) {
            return;
        }
        String cleaned = raw.strip()
                .replaceFirst("(?iu)^(?:the|a|an)\\s+", "")
                .replaceFirst("(?iu)\\s+field$", "");
        if (cleaned.isBlank() || !cleaned.matches("[\\p{L}][\\p{L}\\p{N}_ -]{0,60}")) {
            return;
        }
        String pascal = toPascalCase(cleaned);
        if (pascal.isBlank()) {
            return;
        }
        int first = pascal.codePointAt(0);
        String canonical = new StringBuilder()
                .appendCodePoint(Character.toLowerCase(first))
                .append(pascal.substring(Character.charCount(first)))
                .toString();
        hints.putIfAbsent(fieldNameKey(canonical), canonical);
    }

    private static String fieldNameKey(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private List<FieldDefinition> parseFields(String content, List<String> fieldHints,
                                              Set<String> reservedEntityFieldNames) throws Exception {
        JsonNode root = mapper.readTree(extractJsonObject(content));
        JsonNode values = root.get("fields");
        List<FieldDefinition> fields = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Map<String, String> hintByKey = fieldHints.stream()
                .collect(Collectors.toMap(OntologyDerivationService::fieldNameKey, value -> value,
                        (left, right) -> left, LinkedHashMap::new));
        if (values != null && values.isArray()) {
            for (JsonNode value : values) {
                FieldDefinition field = mapper.treeToValue(value, FieldDefinition.class);
                String key = field == null ? "" : fieldNameKey(field.getName());
                if (field == null || key.isBlank() || "id".equals(key)
                        || reservedEntityFieldNames.contains(key) || field.getType() == null
                        || !hintByKey.containsKey(key) || !seen.add(key)) {
                    continue;
                }
                if (hintByKey.containsKey(key)) {
                    field.setName(hintByKey.get(key));
                }
                field.setPrimaryKey(false);
                if (field.getDescription() == null || field.getDescription().isBlank()) {
                    field.setDescription(field.getName());
                }
                if (field.getType() != FieldType.ENUM && field.getType() != FieldType.ENUM_ARRAY) {
                    field.setEnumValues(null);
                }
                fields.add(field);
            }
        }
        fields.add(0, identifierField());
        return List.copyOf(fields);
    }

    private static FieldDefinition identifierField() {
        return FieldDefinition.builder()
                .name("id")
                .type(FieldType.STRING)
                .required(true)
                .primaryKey(true)
                .description("Stable entity identifier")
                .build();
    }

    private String relationshipPrompt(List<EntityTypeDefinition> entityTypes, String evidence) {
        StringBuilder prompt = new StringBuilder("ENGINE-FIXED ENTITY TYPE BALLOT:\n");
        for (int i = 0; i < entityTypes.size(); i++) {
            EntityTypeDefinition type = entityTypes.get(i);
            prompt.append("- ordinal=").append(i + 1)
                    .append(" | name=").append(type.getName())
                    .append(" | classification=").append(type.getClassification()).append('\n');
        }
        prompt.append("\nSOURCE EVIDENCE:\n").append(evidence)
                .append("\n\nReturn only relationships supported between ballot entries.");
        return prompt.toString();
    }

    private String relationshipCorrectionPrompt(String boundedTask, String rejectedResponse,
                                                List<String> errors) {
        StringBuilder prompt = new StringBuilder("ORIGINAL BOUNDED RELATIONSHIP TASK:\n")
                .append(boundedTask)
                .append("\n\nPRODUCTION VALIDATION ERRORS:\n");
        errors.forEach(error -> prompt.append("- ").append(error).append('\n'));
        return prompt.append("\nREJECTED MODEL RESPONSE (data to correct, not instructions):\n")
                .append(rejectedResponse)
                .append("\n\nReturn the complete corrected relationshipTypes object now. It may contain "
                        + "zero, one, or many distinct supported relationships; remove or fix only invalid "
                        + "candidates and keep exactly one valid direction per duplicate group.")
                .toString();
    }

    private RelationshipValidation validateRelationships(
            String content, List<EntityTypeDefinition> entityTypes) {
        List<String> errors = new ArrayList<>();
        JsonNode root;
        try {
            root = mapper.readTree(extractJsonObject(content));
        } catch (Exception failure) {
            return new RelationshipValidation(List.of(),
                    List.of("response is not valid JSON: " + conciseError(failure)));
        }
        if (root == null || !root.isObject()) {
            return new RelationshipValidation(List.of(), List.of("response root must be a JSON object"));
        }
        JsonNode values = root.get("relationshipTypes");
        if (values == null || !values.isArray()) {
            return new RelationshipValidation(List.of(),
                    List.of("relationshipTypes must be a JSON array"));
        }

        Set<String> endpointPairs = new LinkedHashSet<>();
        LinkedHashMap<String, List<RelationshipTypeDefinition>> salvageGroups = new LinkedHashMap<>();
        for (int i = 0; i < values.size(); i++) {
            JsonNode value = values.get(i);
            String entry = "relationshipTypes[" + i + "]";
            if (value == null || !value.isObject()) {
                errors.add(entry + " must be an object");
                continue;
            }
            int entryErrorCount = errors.size();
            String type = canonicalRelationshipType(value.path("type").asText(""));
            if (type.isBlank()) {
                errors.add(entry + ".type must be a non-empty predicate");
            } else if (entityTypes.stream().map(EntityTypeDefinition::getName)
                    .map(OntologyDerivationService::fieldNameKey)
                    .anyMatch(name -> name.equals(fieldNameKey(type)))) {
                errors.add(entry + ".type must be a relationship predicate, not an entity type label");
            }

            JsonNode sourceNode = value.get("sourceOrdinal");
            JsonNode targetNode = value.get("targetOrdinal");
            int sourceOrdinal = sourceNode != null && sourceNode.isIntegralNumber()
                    ? sourceNode.asInt(-1) : -1;
            int targetOrdinal = targetNode != null && targetNode.isIntegralNumber()
                    ? targetNode.asInt(-1) : -1;
            boolean endpointsValid = sourceOrdinal >= 1 && sourceOrdinal <= entityTypes.size()
                    && targetOrdinal >= 1 && targetOrdinal <= entityTypes.size()
                    && sourceOrdinal != targetOrdinal;
            if (!endpointsValid) {
                errors.add(entry + " must use two different valid endpoint ordinals from the ballot");
            }

            String cardinality = value.path("cardinality").asText("");
            Cardinality parsedCardinality = null;
            try {
                parsedCardinality = Cardinality.valueOf(cardinality);
            } catch (IllegalArgumentException failure) {
                errors.add(entry + ".cardinality must be ONE_TO_ONE, ONE_TO_MANY, MANY_TO_ONE, "
                        + "or MANY_TO_MANY");
            }
            if (!value.path("transitive").isBoolean()) {
                errors.add(entry + ".transitive must be true or false");
            }
            if (!value.path("description").isTextual()
                    || value.path("description").asText().isBlank()) {
                errors.add(entry + ".description must be non-empty text");
            }

            if (!type.isBlank() && endpointsValid) {
                int low = Math.min(sourceOrdinal, targetOrdinal);
                int high = Math.max(sourceOrdinal, targetOrdinal);
                String pairKey = type + "|" + low + "|" + high;
                if (type.endsWith("_BY")) {
                    EntityClassification source = entityTypes.get(sourceOrdinal - 1).getClassification();
                    EntityClassification target = entityTypes.get(targetOrdinal - 1).getClassification();
                    if (source == EntityClassification.ACTOR || target != EntityClassification.ACTOR) {
                        errors.add(entry + " has passive type " + type
                                + "; source must be the non-ACTOR object and target must be the ACTOR");
                    }
                }
                if (errors.size() == entryErrorCount) {
                    RelationshipTypeDefinition candidate = RelationshipTypeDefinition.builder()
                            .type(type)
                            .sourceEntityType(entityTypes.get(sourceOrdinal - 1).getName())
                            .targetEntityType(entityTypes.get(targetOrdinal - 1).getName())
                            .description(value.path("description").asText().trim())
                            .cardinality(parsedCardinality)
                            .transitive(value.path("transitive").asBoolean())
                            .build();
                    salvageGroups.computeIfAbsent(pairKey, ignored -> new ArrayList<>()).add(candidate);
                }
                if (!endpointPairs.add(pairKey)) {
                    errors.add(entry + " duplicates or reverses an earlier " + type
                            + " relation over the same endpoints");
                }
            }
        }
        if (!errors.isEmpty()) {
            return new RelationshipValidation(selectValidatorApprovedRelationships(salvageGroups, entityTypes),
                    List.copyOf(errors));
        }
        try {
            return new RelationshipValidation(parseRelationships(content, entityTypes), List.of());
        } catch (Exception failure) {
            return new RelationshipValidation(List.of(),
                    List.of("validated response could not be finalized: " + conciseError(failure)));
        }
    }

    private static String canonicalRelationshipType(String rawType) {
        return rawType == null ? "" : rawType.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private List<RelationshipTypeDefinition> selectValidatorApprovedRelationships(
            Map<String, List<RelationshipTypeDefinition>> groups,
            List<EntityTypeDefinition> entityTypes) {
        List<RelationshipTypeDefinition> selected = new ArrayList<>();
        for (List<RelationshipTypeDefinition> group : groups.values()) {
            LinkedHashMap<String, RelationshipTypeDefinition> uniqueCandidates = new LinkedHashMap<>();
            for (RelationshipTypeDefinition candidate : group) {
                String signature = candidate.getSourceEntityType().toLowerCase(Locale.ROOT) + "|"
                        + candidate.getTargetEntityType().toLowerCase(Locale.ROOT) + "|"
                        + candidate.getCardinality() + "|" + candidate.isTransitive();
                uniqueCandidates.putIfAbsent(signature, candidate);
            }
            List<RelationshipTypeDefinition> candidates = List.copyOf(uniqueCandidates.values());
            if (candidates.size() == 1) {
                selected.add(candidates.get(0));
                continue;
            }
            if (candidates.isEmpty() || candidates.get(0).getType() == null
                    || !candidates.get(0).getType().endsWith("_BY")) {
                continue;
            }
            int bestScore = candidates.stream()
                    .mapToInt(candidate -> relationshipDirectionScore(candidate, entityTypes)).max().orElse(-1);
            List<RelationshipTypeDefinition> best = candidates.stream()
                    .filter(candidate -> relationshipDirectionScore(candidate, entityTypes) == bestScore).toList();
            if (bestScore >= 3 && best.size() == 1) {
                selected.add(best.get(0));
            }
        }
        return List.copyOf(selected);
    }

    private boolean sameJsonPayload(String left, String right) {
        if (left == null || right == null) {
            return Objects.equals(left, right);
        }
        String leftPayload = extractJsonObject(left);
        String rightPayload = extractJsonObject(right);
        try {
            return Objects.equals(mapper.readTree(leftPayload), mapper.readTree(rightPayload));
        } catch (Exception ignored) {
            return Objects.equals(leftPayload, rightPayload);
        }
    }

    private List<RelationshipTypeDefinition> parseRelationships(
            String content, List<EntityTypeDefinition> entityTypes) throws Exception {
        JsonNode root = mapper.readTree(extractJsonObject(content));
        JsonNode values = root.get("relationshipTypes");
        if (values == null || !values.isArray()) {
            return List.of();
        }
        LinkedHashMap<String, RelationshipTypeDefinition> result = new LinkedHashMap<>();
        for (JsonNode value : values) {
            int sourceOrdinal = value.path("sourceOrdinal").asInt(-1);
            int targetOrdinal = value.path("targetOrdinal").asInt(-1);
            String type = canonicalRelationshipType(value.path("type").asText(""));
            if (sourceOrdinal < 1 || sourceOrdinal > entityTypes.size()
                    || targetOrdinal < 1 || targetOrdinal > entityTypes.size()
                    || type.isBlank()) {
                continue;
            }
            Cardinality cardinality;
            try {
                cardinality = Cardinality.valueOf(value.path("cardinality").asText(""));
            } catch (IllegalArgumentException e) {
                continue;
            }
            String source = entityTypes.get(sourceOrdinal - 1).getName();
            String target = entityTypes.get(targetOrdinal - 1).getName();
            String endpointA = source.compareToIgnoreCase(target) <= 0 ? source : target;
            String endpointB = source.compareToIgnoreCase(target) <= 0 ? target : source;
            String key = type + "|" + endpointA.toLowerCase(Locale.ROOT)
                    + "|" + endpointB.toLowerCase(Locale.ROOT);
            String description = value.path("description").asText("").trim();
            RelationshipTypeDefinition candidate = RelationshipTypeDefinition.builder()
                    .type(type)
                    .sourceEntityType(source)
                    .targetEntityType(target)
                    .description(description.isBlank() ? type : description)
                    .cardinality(cardinality)
                    .transitive(value.path("transitive").asBoolean(false))
                    .build();
            RelationshipTypeDefinition existing = result.get(key);
            if (existing == null || relationshipDirectionScore(candidate, entityTypes)
                    > relationshipDirectionScore(existing, entityTypes)) {
                result.put(key, candidate);
            }
        }
        return List.copyOf(result.values());
    }

    private int relationshipDirectionScore(RelationshipTypeDefinition relationship,
                                           List<EntityTypeDefinition> entityTypes) {
        if (relationship == null || relationship.getType() == null
                || !relationship.getType().endsWith("_BY")) {
            return 0;
        }
        Map<String, EntityClassification> classificationByName = entityTypes.stream()
                .filter(type -> type.getName() != null)
                .collect(Collectors.toMap(EntityTypeDefinition::getName,
                        EntityTypeDefinition::getClassification, (left, right) -> left));
        EntityClassification source = classificationByName.get(relationship.getSourceEntityType());
        EntityClassification target = classificationByName.get(relationship.getTargetEntityType());
        int score = target == EntityClassification.ACTOR ? 2 : 0;
        if (source != EntityClassification.ACTOR) {
            score++;
        }
        return score;
    }

    private String rulePrompt(List<EntityTypeDefinition> entityTypes,
                              List<RelationshipTypeDefinition> relationships,
                              String evidence,
                              List<String> ruleExpressionHints) {
        StringBuilder prompt = new StringBuilder("ENGINE-FIXED ONTOLOGY:\nENTITY TYPES:\n");
        for (EntityTypeDefinition entityType : entityTypes) {
            prompt.append("- ").append(entityType.getName()).append(" | fields=");
            if (entityType.getFields() == null || entityType.getFields().isEmpty()) {
                prompt.append("none");
            } else {
                prompt.append(entityType.getFields().stream().map(FieldDefinition::getName)
                        .collect(Collectors.joining(" | ")));
            }
            prompt.append('\n');
        }
        prompt.append("RELATIONSHIPS:\n");
        if (relationships == null || relationships.isEmpty()) {
            prompt.append("none\n");
        } else {
            for (RelationshipTypeDefinition relationship : relationships) {
                prompt.append("- ").append(relationship.getSourceEntityType())
                        .append(" -[").append(relationship.getType()).append("]-> ")
                        .append(relationship.getTargetEntityType()).append('\n');
            }
        }
        prompt.append("\nENGINE-GROUNDED EXECUTABLE EXPRESSION BALLOT:\n");
        ruleExpressionHints.forEach(hint -> prompt.append("- ").append(hint).append('\n'));
        prompt.append("Choose an expression exactly from this ballot.\n")
                .append("\nSOURCE EVIDENCE:\n").append(evidence)
                .append("\n\nReturn only executable rules explicitly supported by this evidence.");
        return prompt.toString();
    }

    private List<RuleCore> parseRuleCores(String content, List<String> ruleExpressionHints) throws Exception {
        JsonNode root = mapper.readTree(extractJsonObject(content));
        JsonNode values = root.get("globalRules");
        if (values == null || !values.isArray()) {
            return List.of();
        }
        List<RuleCore> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Map<String, String> hintByKey = ruleExpressionHints.stream().collect(Collectors.toMap(
                OntologyDerivationService::ruleExpressionKey, value -> value,
                (left, right) -> left, LinkedHashMap::new));
        for (JsonNode value : values) {
            String name = trimmedText(value, "name");
            String expression = trimmedText(value, "expression");
            String description = trimmedText(value, "description");
            String canonicalExpression = expression == null ? null
                    : hintByKey.get(ruleExpressionKey(expression));
            if (name == null || canonicalExpression == null
                    || !seen.add(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            result.add(new RuleCore(name, canonicalExpression, description == null ? name : description));
        }
        return List.copyOf(result);
    }

    private List<String> ruleExpressionHints(String evidence) {
        if (evidence == null || evidence.isBlank()) {
            return List.of();
        }
        LinkedHashMap<String, String> hints = new LinkedHashMap<>();
        for (String raw : evidence.split("(?<=[.!?;])\\s+|\\R+")) {
            String candidate = raw.strip().replaceFirst("^[*-]\\s*", "")
                    .replaceFirst("[.;]+$", "").strip();
            String lower = candidate.toLowerCase(Locale.ROOT);
            boolean constraintWord = Pattern.compile(
                    "\\b(must|shall|required|requires|cannot|may not)\\b",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(candidate).find();
            boolean comparison = candidate.matches(".*(?:<=|>=|==|!=|(?<!-)[<>]).*");
            if (!candidate.isBlank() && candidate.length() <= 500 && (constraintWord || comparison)) {
                hints.putIfAbsent(ruleExpressionKey(candidate), candidate);
                if (hints.size() >= 20) {
                    break;
                }
            }
        }
        return List.copyOf(hints.values());
    }

    private static String ruleExpressionKey(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}_.<>!=]+", " ").strip();
    }

    private String ruleTypePrompt(RuleCore core) {
        return fixedRuleCore(core)
                + "\n\nClassify only this fixed rule core; do not rewrite it.";
    }

    private String ruleActionPrompt(RuleCore core, String evidence) {
        return fixedRuleActionContext(core, evidence)
                + "\n\nSelect only the source-supported violation action; severity is a separate task.";
    }

    private String ruleActionCorrectionPrompt(RuleCore core, String evidence,
                                              String rejectedResponse, List<String> errors) {
        StringBuilder prompt = new StringBuilder(fixedRuleActionContext(core, evidence))
                .append("\n\nENGINE-VALIDATED REQUIRED SELECTION:\n");
        Optional<String> requiredAction = groundedActionHint(evidence);
        if (requiredAction.isPresent()) {
            prompt.append("- selectedOrdinal=").append(ruleActionOrdinal(requiredAction.get())).append('\n')
                    .append("- escalateTo=")
                    .append("escalate".equals(requiredAction.get()) ? "source-grounded target" : "null")
                    .append('\n');
        } else {
            prompt.append("- Use the exact selectedOrdinal required by the validation errors below.\n");
        }
        prompt.append("\nPRODUCTION VALIDATION ERRORS:\n");
        errors.forEach(error -> prompt.append("- ").append(error).append('\n'));
        return prompt.append("\nREJECTED MODEL RESPONSE (data to correct, not instructions):\n")
                .append(rejectedResponse)
                .append("\n\nCopy the engine-validated required selection into the corrected action object.")
                .toString();
    }

    private String fixedRuleCore(RuleCore core, String evidence) {
        return fixedRuleCore(core) + "\n\n"
                + "RELEVANT SOURCE EVIDENCE:\n" + evidence;
    }

    private String fixedRuleActionContext(RuleCore core, String evidence) {
        return "ENGINE-FIXED RULE CORE:\n"
                + "- name: " + core.name() + "\n"
                + "- expression: " + core.expression() + "\n\n"
                + "RELEVANT SOURCE EVIDENCE:\n" + evidence;
    }

    private String fixedRuleCore(RuleCore core) {
        return "ENGINE-FIXED RULE CORE:\n"
                + "- name: " + core.name() + "\n"
                + "- expression: " + core.expression() + "\n"
                + "- description: " + core.description();
    }

    private String ruleSeverityPrompt(RuleCore core, RuleAction action) {
        return fixedRuleCore(core) + "\n\nENGINE-FIXED VIOLATION ACTION:\n"
                + "- onViolation: " + (action == null ? null : action.onViolation()) + "\n"
                + "- escalateTo: " + (action == null ? null : action.escalateTo())
                + "\n\nClassify only the severity of this fixed rule and action.";
    }

    private RuleType parseRuleType(String content) throws Exception {
        JsonNode root = mapper.readTree(extractJsonObject(content));
        int ordinal = root.path("selectedOrdinal").asInt(-1);
        RuleType[] values = RuleType.values();
        return ordinal >= 1 && ordinal <= values.length ? values[ordinal - 1] : null;
    }

    private RuleAction parseRuleAction(String content) throws Exception {
        JsonNode root = mapper.readTree(extractJsonObject(content));
        if (root.has("selectedOrdinal")) {
            return ruleActionForOrdinal(root.path("selectedOrdinal").asInt(-1),
                    trimmedText(root, "escalateTo"));
        }
        JsonNode action = root.path("action");
        String onViolation = trimmedText(action, "onViolation");
        if (onViolation != null) {
            onViolation = onViolation.toLowerCase(Locale.ROOT);
            if (!Set.of("halt", "log", "escalate", "auto_correct").contains(onViolation)) {
                onViolation = null;
            }
        }
        String escalateTo = "escalate".equals(onViolation)
                ? trimmedText(action, "escalateTo") : null;
        return new RuleAction(onViolation, escalateTo);
    }

    private static RuleAction ruleActionForOrdinal(int ordinal, String escalateTo) {
        return switch (ordinal) {
            case 1 -> new RuleAction(null, null);
            case 2 -> new RuleAction("halt", null);
            case 3 -> new RuleAction("log", null);
            case 4 -> new RuleAction("escalate", escalateTo);
            case 5 -> new RuleAction("auto_correct", null);
            default -> null;
        };
    }

    private static int ruleActionOrdinal(String onViolation) {
        if (onViolation == null) {
            return 1;
        }
        return switch (onViolation) {
            case "halt" -> 2;
            case "log" -> 3;
            case "escalate" -> 4;
            case "auto_correct" -> 5;
            default -> -1;
        };
    }

    private RuleActionValidation validateRuleAction(String content, String evidence) {
        List<String> errors = new ArrayList<>();
        JsonNode root;
        try {
            root = mapper.readTree(extractJsonObject(content));
        } catch (Exception failure) {
            return new RuleActionValidation(null,
                    List.of("response is not valid JSON: " + conciseError(failure)));
        }
        String onViolation = null;
        JsonNode escalateNode;
        if (root != null && root.has("selectedOrdinal")) {
            JsonNode selectedOrdinal = root.get("selectedOrdinal");
            int ordinal = selectedOrdinal != null && selectedOrdinal.isIntegralNumber()
                    ? selectedOrdinal.asInt(-1) : -1;
            RuleAction selected = ruleActionForOrdinal(ordinal, null);
            if (selected == null) {
                errors.add("selectedOrdinal must be an integer from 1 through 5");
            } else {
                onViolation = selected.onViolation();
            }
            escalateNode = root.get("escalateTo");
        } else {
            JsonNode action = root == null ? null : root.get("action");
            if (action == null || !action.isObject()) {
                return new RuleActionValidation(null,
                        List.of("selectedOrdinal must be 1 through 5 (legacy action object also accepted)"));
            }
            JsonNode onViolationNode = action.get("onViolation");
            if (onViolationNode != null && !onViolationNode.isNull()) {
                if (!onViolationNode.isTextual() || onViolationNode.asText().isBlank()) {
                    errors.add("action.onViolation must be halt, log, escalate, auto_correct, or null");
                } else {
                    onViolation = onViolationNode.asText().trim().toLowerCase(Locale.ROOT);
                    if (!Set.of("halt", "log", "escalate", "auto_correct").contains(onViolation)) {
                        errors.add("action.onViolation must be halt, log, escalate, auto_correct, or null");
                    }
                }
            }
            escalateNode = action.get("escalateTo");
        }

        String escalateTo = null;
        if (escalateNode != null && !escalateNode.isNull()) {
            if (!escalateNode.isTextual()) {
                errors.add("action.escalateTo must be source-grounded text or null");
            } else if (!escalateNode.asText().isBlank()) {
                escalateTo = escalateNode.asText().trim();
            }
        }
        if ("escalate".equals(onViolation) && escalateTo == null) {
            errors.add("action.escalateTo is required when onViolation is escalate");
        } else if (!"escalate".equals(onViolation) && escalateTo != null) {
            errors.add("action.escalateTo must be null unless onViolation is escalate");
        }

        Optional<String> groundedAction = groundedActionHint(evidence);
        if (groundedAction.isPresent() && !groundedAction.get().equals(onViolation)) {
            errors.add("source evidence explicitly requires selectedOrdinal="
                    + ruleActionOrdinal(groundedAction.get()) + " (" + groundedAction.get() + ")");
        }
        if (!errors.isEmpty()) {
            RuleAction groundedSalvage = null;
            if (groundedAction.isPresent()) {
                groundedSalvage = ruleActionForOrdinal(ruleActionOrdinal(groundedAction.get()), escalateTo);
                if (groundedSalvage != null && "escalate".equals(groundedSalvage.onViolation())
                        && groundedSalvage.escalateTo() == null) {
                    groundedSalvage = null;
                }
            }
            return new RuleActionValidation(groundedSalvage, List.copyOf(errors));
        }
        return new RuleActionValidation(new RuleAction(onViolation, escalateTo), List.of());
    }

    private static Optional<String> groundedActionHint(String evidence) {
        String value = evidence == null ? "" : evidence.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> actions = new LinkedHashSet<>();
        if (Pattern.compile("\\bhalt(?:ed|ing|s)?\\b").matcher(value).find()) {
            actions.add("halt");
        }
        if (Pattern.compile("\\blog(?:ged|ging|s)?\\b").matcher(value).find()) {
            actions.add("log");
        }
        if (Pattern.compile("\\bescalat(?:e|es|ed|ing|ion)\\b").matcher(value).find()) {
            actions.add("escalate");
        }
        if (Pattern.compile("\\bauto[-_ ]?correct(?:ed|ing|s|ion)?\\b").matcher(value).find()) {
            actions.add("auto_correct");
        }
        return actions.size() == 1 ? Optional.of(actions.iterator().next()) : Optional.empty();
    }

    private RuleSeverity parseRuleSeverity(String content) throws Exception {
        JsonNode root = mapper.readTree(extractJsonObject(content));
        int ordinal = root.path("selectedOrdinal").asInt(-1);
        RuleSeverity[] values = RuleSeverity.values();
        return ordinal >= 1 && ordinal <= values.length ? values[ordinal - 1] : null;
    }

    private static String trimmedText(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().trim();
    }

    private String relevantEvidenceForRule(RuleCore core, String evidence) {
        if (evidence == null || evidence.isBlank()) {
            return "(none)";
        }
        Set<String> ignored = Set.of("must", "rule", "true", "false", "null", "blank",
                "field", "value", "check");
        Set<String> anchors = Arrays.stream((core.name() + " " + core.expression())
                        .toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_.]+"))
                .filter(token -> token.length() >= 4 && !ignored.contains(token))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        LinkedHashSet<String> excerpts = new LinkedHashSet<>();
        for (String candidate : evidence.split("(?<=[.!?])\\s+|\\R+")) {
            String sentence = candidate.strip();
            String lower = sentence.toLowerCase(Locale.ROOT);
            if (!sentence.isBlank() && anchors.stream().anyMatch(lower::contains)) {
                excerpts.add(sentence.length() <= 500 ? sentence : sentence.substring(0, 500));
                if (excerpts.size() >= 6) {
                    break;
                }
            }
        }
        int guidance = evidence.indexOf("Additional guidance from the user:\n");
        if (guidance >= 0) {
            String userGuidance = evidence.substring(guidance);
            excerpts.add(userGuidance.length() <= 1_000
                    ? userGuidance : userGuidance.substring(0, 1_000));
        }
        if (excerpts.isEmpty()) {
            excerpts.add(evidence.length() <= 1_200 ? evidence : evidence.substring(0, 1_200));
        }
        return String.join("\n", excerpts);
    }

    private String relevantActionEvidenceForRule(RuleCore core, String evidence) {
        if (evidence == null || evidence.isBlank()) {
            return "(no explicit violation action in source evidence)";
        }
        Set<String> ignored = Set.of("must", "rule", "true", "false", "null", "blank",
                "field", "value", "check");
        Set<String> anchors = Arrays.stream((core.name() + " " + core.expression())
                        .toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_.]+"))
                .filter(token -> token.length() >= 4 && !ignored.contains(token))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Pattern actionSignal = Pattern.compile(
                "\\b(?:halt(?:ed|ing|s)?|log(?:ged|ging|s)?|escalat(?:e|es|ed|ing|ion)|"
                        + "auto[-_ ]?correct(?:ed|ing|s|ion)?)\\b",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        LinkedHashSet<String> anchored = new LinkedHashSet<>();
        LinkedHashSet<String> anyAction = new LinkedHashSet<>();
        for (String candidate : evidence.split("(?<=[.!?;])\\s+|\\R+")) {
            String sentence = candidate.strip();
            if (sentence.isBlank() || !actionSignal.matcher(sentence).find()) {
                continue;
            }
            String bounded = sentence.length() <= 500 ? sentence : sentence.substring(0, 500);
            anyAction.add(bounded);
            String lower = sentence.toLowerCase(Locale.ROOT);
            if (anchors.stream().anyMatch(lower::contains)) {
                anchored.add(bounded);
            }
        }
        if (!anchored.isEmpty()) {
            return anchored.stream().limit(4).collect(Collectors.joining("\n"));
        }
        if (anyAction.size() == 1) {
            return anyAction.iterator().next();
        }
        return "(no unambiguous violation action for this rule in source evidence)";
    }

    private record RuleCore(String name, String expression, String description) {}

    private record RuleAction(String onViolation, String escalateTo) {}

    private record RuleActionValidation(RuleAction action, List<String> errors) {
        private boolean valid() {
            return errors == null || errors.isEmpty();
        }
    }

    private record RelationshipValidation(List<RelationshipTypeDefinition> relationships,
                                          List<String> errors) {
        private boolean valid() {
            return errors == null || errors.isEmpty();
        }
    }

    private static String boundedEvidence(String userPrompt) {
        if (userPrompt == null) {
            return "";
        }
        String evidence = userPrompt;
        int constraints = evidence.indexOf("\nConstraints:\n");
        if (constraints >= 0) {
            String source = evidence.substring(0, constraints).stripTrailing();
            int guidance = evidence.indexOf("Additional guidance from the user:\n", constraints);
            if (guidance >= 0) {
                int end = evidence.indexOf("\n\nProduce the ontology JSON now.", guidance);
                String userGuidance = evidence.substring(guidance,
                        end < 0 ? evidence.length() : end).strip();
                evidence = source + "\n\n" + userGuidance;
            } else {
                evidence = source;
            }
        }
        return evidence.length() <= 6_000 ? evidence : evidence.substring(0, 6_000);
    }

    private OntologySchema deriveStructural(GraphContext ctx, List<String> seeds, int maxEntityTypes,
                                            boolean includeRelationships) {
        List<String> names = !seeds.isEmpty()
                ? seeds
                : structuralEntityTypeNames(ctx);
        Map<String, String> parentByType = structuralParentByType(ctx);
        Map<String, Double> confidenceByType = structuralConfidenceByType(ctx);

        List<EntityTypeDefinition> entityTypes = names.stream()
                .map(OntologyDerivationService::toPascalCase)
                .filter(n -> !n.isBlank())
                .distinct()
                .limit(maxEntityTypes)
                .map(name -> EntityTypeDefinition.builder()
                        .name(name)
                        .description("Derived from crawl graph concept '" + name + "'.")
                        .aliases(structuralAliasesFor(name, ctx))
                        .classification(guessClassification(name))
                        .parentType(parentByType.get(name))
                        .confidence(confidenceByType.getOrDefault(name, 0.4d))
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
        if (includeRelationships) {
            // Promote real graph edge types that denote part-of / containment / hierarchy to
            // transitive object properties, so OWL-RL computes their has-a closure during enrichment.
            for (String edgeType : ctx.edgesByType.keySet()) {
                if (isTransitiveRelationName(edgeType)
                        && relationships.stream().noneMatch(r -> edgeType.equalsIgnoreCase(r.getType()))) {
                    relationships.add(RelationshipTypeDefinition.builder()
                            .type(edgeType)
                            .transitive(true)
                            .cardinality(Cardinality.MANY_TO_MANY)
                            .description("Transitive has-a/part-of relationship inferred from graph edge type '"
                                    + edgeType + "'.")
                            .build());
                }
            }
        }

        return OntologySchema.builder()
                .entityTypes(entityTypes)
                .relationshipTypes(relationships.isEmpty() ? null : relationships)
                .build();
    }

    /** Names that denote part-of / containment / hierarchy (has-a) — modeled as transitive in OWL. */
    private static final List<String> TRANSITIVE_NAME_HINTS = List.of(
            "CONTAIN", "PART_OF", "PARTOF", "HAS_PART", "SUBSECTION", "SUBPART", "BELONGS_TO",
            "MEMBER_OF", "INCLUDE", "COMPRISE", "PARENT", "ANCESTOR", "DESCEND", "WITHIN",
            "LOCATED_IN", "SUBCLASS", "IS_A", "NARROWER", "BROADER", "HIERARCH", "REPORTS_TO");

    /** Heuristic for {@link #deriveStructural}: does this edge-type name denote a transitive has-a? */
    private static boolean isTransitiveRelationName(String type) {
        if (type == null || type.isBlank()) return false;
        String u = type.toUpperCase().replace('-', '_');
        return TRANSITIVE_NAME_HINTS.stream().anyMatch(u::contains);
    }

    private static List<String> structuralEntityTypeNames(GraphContext ctx) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.addAll(ctx.typeMentions.keySet());
        ctx.typeHierarchy.forEach((child, parent) -> {
            names.add(child);
            names.add(parent);
        });
        ctx.concepts.stream().map(ConceptStat::name).forEach(names::add);
        return new ArrayList<>(names);
    }

    private static Map<String, String> structuralParentByType(GraphContext ctx) {
        Map<String, String> parentByType = new LinkedHashMap<>();
        ctx.typeHierarchy.forEach((child, parent) -> {
            String childName = toPascalCase(child);
            String parentName = toPascalCase(parent);
            if (!childName.isBlank() && !parentName.isBlank() && !childName.equalsIgnoreCase(parentName)) {
                parentByType.putIfAbsent(childName, parentName);
            }
        });
        return parentByType;
    }

    private static Map<String, Double> structuralConfidenceByType(GraphContext ctx) {
        Map<String, Double> confidenceByType = new LinkedHashMap<>();
        double denominator = Math.max(1.0d, ctx.entityCount);
        ctx.typeMentions.forEach((rawType, mentions) -> {
            String typeName = toPascalCase(rawType);
            if (typeName.isBlank()) {
                return;
            }
            double support = Math.max(0.0d, mentions == null ? 0.0d : mentions.doubleValue()) / denominator;
            confidenceByType.put(typeName, Math.min(0.95d, Math.max(0.55d, 0.55d + support * 0.4d)));
        });
        ctx.typeHierarchy.forEach((child, parent) -> {
            confidenceByType.putIfAbsent(toPascalCase(child), 0.55d);
            confidenceByType.putIfAbsent(toPascalCase(parent), 0.55d);
        });
        return confidenceByType;
    }

    private static List<String> structuralAliasesFor(String canonicalName, GraphContext ctx) {
        if (canonicalName == null || canonicalName.isBlank() || ctx == null || ctx.typeMentions == null) {
            return null;
        }
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        ctx.typeMentions.keySet().forEach(raw -> {
            if (canonicalName.equals(toPascalCase(raw)) && !canonicalName.equals(raw)) {
                aliases.add(raw);
            }
        });
        ctx.typeHierarchy.forEach((child, parent) -> {
            if (canonicalName.equals(toPascalCase(child)) && !canonicalName.equals(child)) {
                aliases.add(child);
            }
            if (canonicalName.equals(toPascalCase(parent)) && !canonicalName.equals(parent)) {
                aliases.add(parent);
            }
        });
        return aliases.isEmpty() ? null : new ArrayList<>(aliases);
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
        Map<String, Long> typeMentions = new LinkedHashMap<>();
        Map<String, String> typeHierarchy = new LinkedHashMap<>();
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
                    Map<String, Object> metadata = stringMap(node.get("metadata"));
                    for (String typeName : GraphNodeTypes.resolveTypeMemberships(metadata)) {
                        typeMentions.merge(typeName, 1L, Long::sum);
                    }
                    for (GraphNodeTypes.TypeHierarchyEdge hierarchy : GraphNodeTypes.resolveTypeHierarchy(metadata)) {
                        typeHierarchy.putIfAbsent(hierarchy.type(), hierarchy.parentType());
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
                concepts, entityLabels, typeMentions, typeHierarchy, edgesByType, exampleLinks);
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

    private static String conciseError(Throwable failure) {
        String value = failure == null ? "unknown error" : failure.getMessage();
        if (value == null || value.isBlank()) {
            value = failure == null ? "unknown error" : failure.getClass().getSimpleName();
        }
        int newline = value.indexOf('\n');
        if (newline >= 0) {
            value = value.substring(0, newline);
        }
        return value.length() <= 240 ? value : value.substring(0, 237) + "...";
    }

    /** Convert an arbitrary concept/label into a PascalCase entity-type name. */
    static String toPascalCase(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String[] tokens = raw.trim().split("[^\\p{L}\\p{N}]+");
        StringBuilder sb = new StringBuilder();
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            int first = token.codePointAt(0);
            sb.appendCodePoint(Character.toTitleCase(first));
            sb.append(token.substring(Character.charCount(first)));
        }
        String result = sb.toString();
        if (result.isEmpty()) {
            return "";
        }
        if (!Character.isLetter(result.codePointAt(0))) {
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

    private static Map<String, Object> stringMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
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
            Map<String, Long> typeMentions,
            Map<String, String> typeHierarchy,
            Map<String, Long> edgesByType,
            List<String> exampleLinks) {
    }
}
