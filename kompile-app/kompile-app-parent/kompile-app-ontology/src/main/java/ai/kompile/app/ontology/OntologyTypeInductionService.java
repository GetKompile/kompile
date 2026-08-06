/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.ontology;

import ai.kompile.app.web.dto.ontology.TypedEntityMatchResponse;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.graphrag.typing.GraphNodeTypes;
import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.ontology.EntityClassification;
import ai.kompile.process.ontology.EntityTypeDefinition;
import ai.kompile.process.ontology.FieldDefinition;
import ai.kompile.process.ontology.FieldType;
import ai.kompile.process.ontology.OntologySchema;
import ai.kompile.process.service.ProcessEngineService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Post-OWL schema enrichment.
 *
 * <p>OWL classification is authoritative for crisp inherited memberships. This service runs after
 * OWL and only patches gaps that OWL cannot know from the TBox alone: aliases, multilingual labels,
 * and missing entity type definitions observed in the crawl. When an LLM is configured it may propose
 * additional canonical types and parent links from graph evidence; without an LLM the pass still
 * preserves exact crawl labels as aliases for already-known canonical types.</p>
 */
@Slf4j
@Service
public class OntologyTypeInductionService {

    private static final int MAX_SIGNALS_IN_PROMPT = 80;
    private static final int MAX_EXAMPLES_PER_TYPE = 6;
    private static final double MIN_LLM_TYPE_CONFIDENCE = 0.45d;

    private static final String SYSTEM_PROMPT = """
            Enrich an existing ontology schema after OWL-RL classification has already run.
            Return exactly one raw JSON object with one top-level key, "entityTypes", whose value is
            an array. If no supported addition exists, return exactly {"entityTypes":[]}.

            Each emitted entity-type object requires:
            - "name": a concise canonical name grounded in an observed signal;
            - "parentType": an existing or separately emitted canonical type name, or null;
            - "description": a short definition grounded in the supplied examples;
            - "aliases": an array containing only supplied source labels;
            - "localizedLabels": an object mapping language tags to supplied native labels;
            - "confidence": a number from 0.45 through 1.0;
            - "evidenceEntityIds": an array containing only supplied example entity IDs.

            Rules:
            - Add only types or aliases supported by the supplied crawled entities.
            - Prefer aliases on an existing canonical type when it already represents the signal.
            - Create a type only when the current schema lacks a suitable type or supported is-a node.
            - Preserve non-English labels; do not translate away source evidence.
            - Do not remove or rename existing types.
            - Never emit schema placeholders, field-description prose, or confidence below 0.45.
            - Output only valid JSON, without markdown, commentary, or a second root object.
            """;

    static String systemPromptContract() {
        return SYSTEM_PROMPT;
    }

    private final GraphOntologyBindingService bindingService;
    private final ProcessEngineService processEngineService;
    private final KnowledgeGraphService knowledgeGraphService;
    private final ObjectMapper mapper = JsonUtils.standardMapper().copy()
            .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
            .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Autowired(required = false)
    private LLMChat llmChat;

    public OntologyTypeInductionService(GraphOntologyBindingService bindingService,
                                        ProcessEngineService processEngineService,
                                        KnowledgeGraphService knowledgeGraphService) {
        this.bindingService = bindingService;
        this.processEngineService = processEngineService;
        this.knowledgeGraphService = knowledgeGraphService;
    }

    void setLlmChat(LLMChat llmChat) {
        this.llmChat = llmChat;
    }

    public OntologyTypeInductionResult enrichAfterOwl(long factSheetId) {
        Optional<OntologySchema> active = bindingService.resolveActiveOntology(factSheetId);
        if (active.isEmpty()) {
            return OntologyTypeInductionResult.unchanged(0);
        }
        OntologySchema original = active.get();
        OntologySchema working = deepCopy(original);
        List<GraphNode> entities = knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY);
        Map<String, ObservedType> observed = collectObservedTypes(entities);

        MergeStats stats = mergeObservedAliases(working, observed.values());
        TypeInductionPatch patch = induceWithLlm(working, observed.values()).orElse(null);
        if (patch != null && patch.entityTypes != null) {
            stats = stats.plus(mergeLlmPatch(working, patch));
        }

        if (!stats.changed()) {
            return OntologyTypeInductionResult.unchanged(original.getVersion());
        }

        Map<String, Object> metadata = working.getMetadata() != null
                ? new LinkedHashMap<>(working.getMetadata()) : new LinkedHashMap<>();
        metadata.put("typeInductionUpdatedAt", Instant.now().toString());
        metadata.put("typeInductionSource", patch == null ? "crawl-aliases" : "llm-post-owl");
        metadata.put("typeInductionAliasesAdded", stats.aliasesAdded);
        metadata.put("typeInductionTypesAdded", stats.typesAdded);
        working.setMetadata(metadata);
        working.setUpdatedBy("post-owl-type-induction");

        OntologySchema updated = processEngineService.updateOntology(original.getId(), working);
        bindingService.bindOntology(factSheetId, updated.getId(), updated.getVersion());
        log.info("Post-OWL type induction updated ontology {} to v{} for factSheet={}: aliasesAdded={}, typesAdded={}",
                updated.getId(), updated.getVersion(), factSheetId, stats.aliasesAdded, stats.typesAdded);
        return new OntologyTypeInductionResult(true, stats.aliasesAdded, stats.typesAdded, updated.getVersion());
    }

    public TypedEntityMatchResponse findEntitiesByType(long factSheetId,
                                                       String queryType,
                                                       boolean includeInherited,
                                                       double minConfidence) {
        Optional<OntologySchema> active = bindingService.resolveActiveOntology(factSheetId);
        OntologySchema schema = active.orElse(null);
        TypeIndex index = TypeIndex.from(schema);
        String resolved = index.resolve(queryType);
        if (resolved == null || resolved.isBlank()) {
            resolved = canonical(queryType);
        }
        Set<String> accepted = new LinkedHashSet<>();
        if (resolved != null && !resolved.isBlank()) {
            accepted.add(resolved);
            if (includeInherited) {
                accepted.addAll(index.descendantsOf(resolved));
            }
        }

        List<TypedEntityMatchResponse.Match> matches = new ArrayList<>();
        for (GraphNode node : knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY)) {
            EntityTypeEvidence evidence = evidenceFor(node, index);
            double confidence = evidence.confidenceFor(accepted);
            if (confidence >= minConfidence && evidence.matchesAny(accepted)) {
                matches.add(TypedEntityMatchResponse.Match.builder()
                        .nodeId(node.getNodeId())
                        .title(node.getTitle())
                        .matchedTypes(evidence.matchedTypes(accepted))
                        .directTypes(evidence.directTypes())
                        .inheritedTypes(evidence.inheritedTypes())
                        .confidence(confidence)
                        .build());
            }
        }
        matches.sort(Comparator
                .comparingDouble(TypedEntityMatchResponse.Match::getConfidence).reversed()
                .thenComparing(TypedEntityMatchResponse.Match::getTitle,
                        Comparator.nullsLast(String::compareToIgnoreCase)));

        return TypedEntityMatchResponse.builder()
                .factSheetId(factSheetId)
                .queryType(queryType)
                .resolvedType(resolved)
                .includeInherited(includeInherited)
                .matchCount(matches.size())
                .matches(matches)
                .build();
    }

    private Optional<TypeInductionPatch> induceWithLlm(OntologySchema schema,
                                                       Collection<ObservedType> observedTypes) {
        if (llmChat == null || observedTypes == null || observedTypes.isEmpty()) {
            return Optional.empty();
        }
        try {
            String prompt = buildPrompt(schema, observedTypes);
            String content = llmChat.prompt().system(SYSTEM_PROMPT).user(prompt).call().content();
            if (content == null || content.isBlank()) {
                return Optional.empty();
            }
            TypeInductionPatch patch = mapper.readValue(OntologyDerivationService.extractJsonObject(content),
                    TypeInductionPatch.class);
            return Optional.ofNullable(patch);
        } catch (RuntimeException e) {
            log.warn("LLM type induction failed; keeping OWL/schema output unchanged: {}", e.toString());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("LLM type induction response could not be parsed; keeping OWL/schema output unchanged: {}",
                    e.toString());
            return Optional.empty();
        }
    }

    private String buildPrompt(OntologySchema schema, Collection<ObservedType> observedTypes) {
        StringBuilder sb = new StringBuilder();
        sb.append("Current schema entity types:\n");
        if (schema.getEntityTypes() != null) {
            for (EntityTypeDefinition type : schema.getEntityTypes()) {
                if (type == null || !hasText(type.getName())) {
                    continue;
                }
                sb.append("- ").append(type.getName());
                if (hasText(type.getParentType())) {
                    sb.append(" parent=").append(type.getParentType());
                }
                if (type.getAliases() != null && !type.getAliases().isEmpty()) {
                    sb.append(" aliases=").append(type.getAliases());
                }
                sb.append('\n');
            }
        }
        sb.append("\nObserved post-OWL type signals:\n");
        observedTypes.stream()
                .sorted(Comparator.comparingLong(ObservedType::count).reversed())
                .limit(MAX_SIGNALS_IN_PROMPT)
                .forEach(t -> {
                    sb.append("- raw='").append(t.rawName).append("' canonical='").append(t.canonicalName)
                            .append("' count=").append(t.count);
                    if (!t.parents.isEmpty()) {
                        sb.append(" parents=").append(t.parents);
                    }
                    if (!t.exampleEntities.isEmpty()) {
                        sb.append(" examples=").append(t.exampleEntities);
                    }
                    sb.append('\n');
                });
        sb.append("\nReturn only aliases and missing type definitions worth adding.");
        return sb.toString();
    }

    private MergeStats mergeObservedAliases(OntologySchema schema, Collection<ObservedType> observedTypes) {
        TypeIndex index = TypeIndex.from(schema);
        int aliases = 0;
        for (ObservedType observed : observedTypes) {
            String existing = index.resolve(observed.canonicalName);
            if (existing == null) {
                existing = index.resolve(observed.rawName);
            }
            if (existing == null || observed.rawName.equals(existing)) {
                continue;
            }
            EntityTypeDefinition type = index.byName.get(existing);
            if (type != null && addAlias(type, observed.rawName)) {
                aliases++;
            }
        }
        return new MergeStats(aliases, 0);
    }

    private MergeStats mergeLlmPatch(OntologySchema schema, TypeInductionPatch patch) {
        if (schema.getEntityTypes() == null) {
            schema.setEntityTypes(new ArrayList<>());
        }
        TypeIndex index = TypeIndex.from(schema);
        int aliases = 0;
        int types = 0;
        for (PatchEntityType candidate : patch.entityTypes) {
            if (candidate == null || !hasText(candidate.name)) {
                continue;
            }
            String canonicalName = canonical(candidate.name);
            if (!hasText(canonicalName) || candidate.confidence < MIN_LLM_TYPE_CONFIDENCE) {
                continue;
            }
            String existingName = index.resolve(canonicalName);
            EntityTypeDefinition target;
            if (existingName != null) {
                target = index.byName.get(existingName);
            } else {
                ensureParentExists(schema, index, candidate.parentType);
                target = EntityTypeDefinition.builder()
                        .name(canonicalName)
                        .description(hasText(candidate.description)
                                ? candidate.description
                                : "Induced from post-OWL crawl type evidence.")
                        .aliases(cleanAliases(candidate.aliases, canonicalName))
                        .localizedLabels(cleanLocalizedLabels(candidate.localizedLabels))
                        .classification(OntologyDerivationService.guessClassification(canonicalName))
                        .parentType(resolveParent(index, candidate.parentType))
                        .confidence(candidate.confidence)
                        .fields(defaultFields())
                        .build();
                schema.getEntityTypes().add(target);
                index.add(target);
                types++;
            }
            if (target == null) {
                continue;
            }
            if (!hasText(target.getDescription()) && hasText(candidate.description)) {
                target.setDescription(candidate.description);
            }
            if (!hasText(target.getParentType()) && hasText(candidate.parentType)) {
                ensureParentExists(schema, index, candidate.parentType);
                target.setParentType(resolveParent(index, candidate.parentType));
            }
            aliases += addAliases(target, candidate.aliases);
            aliases += addLocalizedLabels(target, candidate.localizedLabels);
            index.add(target);
        }
        return new MergeStats(aliases, types);
    }

    private void ensureParentExists(OntologySchema schema, TypeIndex index, String rawParent) {
        String parent = canonical(rawParent);
        if (!hasText(parent) || index.resolve(parent) != null) {
            return;
        }
        EntityTypeDefinition parentType = EntityTypeDefinition.builder()
                .name(parent)
                .description("Parent type induced from post-OWL crawl type evidence.")
                .aliases(cleanAliases(List.of(rawParent), parent))
                .classification(OntologyDerivationService.guessClassification(parent))
                .confidence(0.5d)
                .fields(defaultFields())
                .build();
        schema.getEntityTypes().add(parentType);
        index.add(parentType);
    }

    private static String resolveParent(TypeIndex index, String rawParent) {
        if (!hasText(rawParent)) {
            return null;
        }
        String resolved = index.resolve(rawParent);
        return hasText(resolved) ? resolved : canonical(rawParent);
    }

    private static List<FieldDefinition> defaultFields() {
        return List.of(
                FieldDefinition.builder().name("id").type(FieldType.STRING)
                        .primaryKey(true).required(true).description("Unique identifier.").build(),
                FieldDefinition.builder().name("name").type(FieldType.STRING)
                        .required(true).description("Human-readable name.").build());
    }

    private Map<String, ObservedType> collectObservedTypes(List<GraphNode> entities) {
        Map<String, ObservedType> observed = new LinkedHashMap<>();
        if (entities == null) {
            return observed;
        }
        for (GraphNode node : entities) {
            Map<String, Object> metadata = node.getMetadata();
            for (String rawType : GraphNodeTypes.resolveTypeMemberships(metadata)) {
                observed(rawType, observed).add(node, null);
            }
            for (String inferred : strings(metadata.get("owlInferredTypes"))) {
                observed(inferred, observed).add(node, null);
            }
            for (TypeCandidate candidate : typeCandidates(metadata.get("ontology.typeCandidates"))) {
                observed(candidate.type, observed).add(node, candidate.confidence);
            }
            for (GraphNodeTypes.TypeHierarchyEdge edge : GraphNodeTypes.resolveTypeHierarchy(metadata)) {
                observed(edge.type(), observed).parents.add(canonical(edge.parentType()));
                observed(edge.parentType(), observed);
            }
            for (Map<String, Object> row : typeHierarchyRows(metadata.get("ontology.typeHierarchy"))) {
                String child = string(row.get("type"));
                String parent = string(row.get("parentType"));
                if (hasText(child) && hasText(parent)) {
                    observed(child, observed).parents.add(canonical(parent));
                    observed(parent, observed);
                }
            }
        }
        return observed;
    }

    private static ObservedType observed(String rawType, Map<String, ObservedType> observed) {
        String raw = string(rawType);
        String canonical = canonical(raw);
        return observed.computeIfAbsent(canonical, k -> new ObservedType(raw, canonical));
    }

    private static EntityTypeEvidence evidenceFor(GraphNode node, TypeIndex index) {
        Map<String, Object> metadata = node.getMetadata();
        LinkedHashMap<String, Double> direct = new LinkedHashMap<>();
        LinkedHashMap<String, Double> inherited = new LinkedHashMap<>();
        for (String raw : GraphNodeTypes.resolveTypeMemberships(metadata)) {
            putMax(direct, index.resolveOrCanonical(raw), 1.0d);
        }
        for (TypeCandidate candidate : typeCandidates(metadata.get("ontology.typeCandidates"))) {
            putMax(direct, index.resolveOrCanonical(candidate.type), candidate.confidence);
        }
        for (String raw : strings(metadata.get("owlInferredTypes"))) {
            putMax(inherited, index.resolveOrCanonical(raw), 1.0d);
        }
        return new EntityTypeEvidence(direct, inherited);
    }

    private OntologySchema deepCopy(OntologySchema schema) {
        return mapper.convertValue(schema, OntologySchema.class);
    }

    private static int addAliases(EntityTypeDefinition type, Collection<String> aliases) {
        int count = 0;
        if (aliases != null) {
            for (String alias : aliases) {
                if (addAlias(type, alias)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean addAlias(EntityTypeDefinition type, String alias) {
        String clean = string(alias);
        if (!hasText(clean) || clean.equals(type.getName())) {
            return false;
        }
        List<String> aliases = type.getAliases() != null ? new ArrayList<>(type.getAliases()) : new ArrayList<>();
        if (aliases.stream().anyMatch(a -> a.equalsIgnoreCase(clean))) {
            return false;
        }
        aliases.add(clean);
        type.setAliases(aliases);
        return true;
    }

    private static int addLocalizedLabels(EntityTypeDefinition type, Map<String, String> localizedLabels) {
        Map<String, String> clean = cleanLocalizedLabels(localizedLabels);
        if (clean == null || clean.isEmpty()) {
            return 0;
        }
        Map<String, String> labels = type.getLocalizedLabels() != null
                ? new LinkedHashMap<>(type.getLocalizedLabels()) : new LinkedHashMap<>();
        int added = 0;
        for (Map.Entry<String, String> entry : clean.entrySet()) {
            if (!entry.getValue().equals(labels.get(entry.getKey()))) {
                labels.put(entry.getKey(), entry.getValue());
                added++;
            }
            if (addAlias(type, entry.getValue())) {
                added++;
            }
        }
        type.setLocalizedLabels(labels);
        return added;
    }

    private static List<String> cleanAliases(Collection<String> aliases, String canonicalName) {
        if (aliases == null) {
            return null;
        }
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        for (String alias : aliases) {
            String value = string(alias);
            if (hasText(value) && !value.equals(canonicalName)) {
                clean.add(value);
            }
        }
        return clean.isEmpty() ? null : new ArrayList<>(clean);
    }

    private static Map<String, String> cleanLocalizedLabels(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return null;
        }
        Map<String, String> clean = new LinkedHashMap<>();
        labels.forEach((lang, label) -> {
            String key = string(lang);
            String value = string(label);
            if (hasText(key) && hasText(value)) {
                clean.put(key, value);
            }
        });
        return clean.isEmpty() ? null : clean;
    }

    private static List<TypeCandidate> typeCandidates(Object raw) {
        List<TypeCandidate> result = new ArrayList<>();
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                appendTypeCandidate(result, item);
            }
        } else {
            appendTypeCandidate(result, raw);
        }
        return result;
    }

    private static void appendTypeCandidate(List<TypeCandidate> result, Object raw) {
        if (raw instanceof String s && hasText(s)) {
            result.add(new TypeCandidate(s, 1.0d));
        } else if (raw instanceof Map<?, ?> map) {
            String type = string(first(map, "type", "candidateType", "typeName", "inferredType", "label", "iri"));
            if (hasText(type)) {
                result.add(new TypeCandidate(type, doubleValue(first(map, "confidence", "score", "probability"), 1.0d)));
            }
        }
    }

    private static List<Map<String, Object>> typeHierarchyRows(Object raw) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item instanceof Map<?, ?> map) {
                    result.add(stringMap(map));
                }
            }
        } else if (raw instanceof Map<?, ?> map) {
            result.add(stringMap(map));
        }
        return result;
    }

    private static List<String> strings(Object raw) {
        if (raw instanceof Iterable<?> iterable) {
            List<String> result = new ArrayList<>();
            for (Object item : iterable) {
                String value = string(item);
                if (hasText(value)) {
                    result.add(value);
                }
            }
            return result;
        }
        String value = string(raw);
        return hasText(value) ? List.of(value) : List.of();
    }

    private static Map<String, Object> stringMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (k != null && v != null) {
                result.put(String.valueOf(k), v);
            }
        });
        return result;
    }

    private static Object first(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private static void putMax(Map<String, Double> map, String type, double confidence) {
        if (hasText(type)) {
            map.merge(type, confidence, Math::max);
        }
    }

    private static double doubleValue(Object raw, double fallback) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String canonical(String raw) {
        String canonical = OntologyDerivationService.toPascalCase(raw);
        return hasText(canonical) ? canonical : string(raw);
    }

    private static String string(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record TypeCandidate(String type, double confidence) {
    }

    private static final class ObservedType {
        private final String rawName;
        private final String canonicalName;
        private long count;
        private double maxConfidence;
        private final LinkedHashSet<String> parents = new LinkedHashSet<>();
        private final LinkedHashSet<String> exampleEntities = new LinkedHashSet<>();

        private ObservedType(String rawName, String canonicalName) {
            this.rawName = rawName;
            this.canonicalName = canonicalName;
        }

        private void add(GraphNode node, Double confidence) {
            count++;
            maxConfidence = Math.max(maxConfidence, confidence == null ? 1.0d : confidence);
            if (node != null && exampleEntities.size() < MAX_EXAMPLES_PER_TYPE) {
                exampleEntities.add(node.getNodeId() + ":" + node.getTitle());
            }
        }

        long count() {
            return count;
        }
    }

    private record MergeStats(int aliasesAdded, int typesAdded) {
        boolean changed() {
            return aliasesAdded > 0 || typesAdded > 0;
        }

        MergeStats plus(MergeStats other) {
            return new MergeStats(aliasesAdded + other.aliasesAdded, typesAdded + other.typesAdded);
        }
    }

    private record EntityTypeEvidence(LinkedHashMap<String, Double> direct,
                                      LinkedHashMap<String, Double> inherited) {
        private boolean matchesAny(Set<String> accepted) {
            return direct.keySet().stream().anyMatch(accepted::contains)
                    || inherited.keySet().stream().anyMatch(accepted::contains);
        }

        private double confidenceFor(Set<String> accepted) {
            double best = 0.0d;
            for (String type : accepted) {
                best = Math.max(best, direct.getOrDefault(type, 0.0d));
                best = Math.max(best, inherited.getOrDefault(type, 0.0d));
            }
            return best;
        }

        private List<String> matchedTypes(Set<String> accepted) {
            LinkedHashSet<String> matches = new LinkedHashSet<>();
            direct.keySet().stream().filter(accepted::contains).forEach(matches::add);
            inherited.keySet().stream().filter(accepted::contains).forEach(matches::add);
            return new ArrayList<>(matches);
        }

        private List<String> directTypes() {
            return new ArrayList<>(direct.keySet());
        }

        private List<String> inheritedTypes() {
            return new ArrayList<>(inherited.keySet());
        }
    }

    private static final class TypeIndex {
        private final Map<String, EntityTypeDefinition> byName = new LinkedHashMap<>();
        private final Map<String, String> aliasToName = new LinkedHashMap<>();
        private final Map<String, LinkedHashSet<String>> childrenByParent = new LinkedHashMap<>();

        static TypeIndex from(OntologySchema schema) {
            TypeIndex index = new TypeIndex();
            if (schema != null && schema.getEntityTypes() != null) {
                schema.getEntityTypes().forEach(index::add);
            }
            return index;
        }

        void add(EntityTypeDefinition type) {
            if (type == null || !hasText(type.getName())) {
                return;
            }
            byName.put(type.getName(), type);
            aliasToName.put(key(type.getName()), type.getName());
            aliasToName.put(key(canonical(type.getName())), type.getName());
            if (type.getAliases() != null) {
                type.getAliases().forEach(alias -> aliasToName.put(key(alias), type.getName()));
            }
            if (type.getLocalizedLabels() != null) {
                type.getLocalizedLabels().values().forEach(label -> aliasToName.put(key(label), type.getName()));
            }
            if (hasText(type.getParentType())) {
                childrenByParent.computeIfAbsent(type.getParentType(), k -> new LinkedHashSet<>()).add(type.getName());
            }
        }

        String resolveOrCanonical(String raw) {
            String resolved = resolve(raw);
            return hasText(resolved) ? resolved : canonical(raw);
        }

        String resolve(String raw) {
            if (!hasText(raw)) {
                return null;
            }
            String direct = aliasToName.get(key(raw));
            if (direct != null) {
                return direct;
            }
            return aliasToName.get(key(canonical(raw)));
        }

        Set<String> descendantsOf(String type) {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            collectDescendants(type, result);
            return result;
        }

        private void collectDescendants(String type, Set<String> result) {
            for (String child : childrenByParent.getOrDefault(type, new LinkedHashSet<>())) {
                if (result.add(child)) {
                    collectDescendants(child, result);
                }
            }
        }

        private static String key(String value) {
            return string(value).toLowerCase(Locale.ROOT);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class TypeInductionPatch {
        public List<PatchEntityType> entityTypes;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class PatchEntityType {
        public String name;
        public String parentType;
        public String description;
        public List<String> aliases;
        public Map<String, String> localizedLabels;
        public double confidence = 1.0d;
    }
}
