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
package ai.kompile.graph.reasoning.resolution;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.psl.SameAsCollectiveResolution;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.VectorLayer;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Store-free entity resolution for portable unified-corpus graphs.
 *
 * <p>The resolver operates on the same {@link UnifiedGraph} used by the graph reasoners. Candidate
 * identity is anchored by names, aliases, or exclusive identifiers; typed relation neighborhoods
 * and graph embeddings provide supporting evidence. Candidate components are resolved collectively
 * with PSL {@link SameAsCollectiveResolution}, then the complete graph and its attached analysis
 * assets are remapped to the elected canonical entity IDs.</p>
 *
 * <p>An email address is an identifier, not a person alias. Email-shaped entities incorrectly typed
 * as {@code PERSON} are corrected to {@code EMAIL_ADDRESS}; an email node related to a person is
 * retained as a distinct node. Two person nodes may still resolve when they carry the same email
 * identifier in their attributes.</p>
 */
public final class UnifiedGraphEntityResolver {

    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> IDENTIFIER_TYPES = Set.of(
            "EMAIL", "EMAIL_ADDRESS", "PHONE", "PHONE_NUMBER", "URL", "URI", "IDENTIFIER");
    private static final Set<String> UNKNOWN_TYPES = Set.of("", "ENTITY", "UNKNOWN", "OTHER");
    private static final List<String> ALIAS_KEYS = List.of(
            "aliases", "alias", "alternateNames", "alternate_names", "aka");
    private static final List<String> EMAIL_KEYS = List.of(
            "email", "emailAddress", "email_address", "e-mail", "e_mail");

    /**
     * Resolve identities and return a canonical graph. Only semantic corpus entities participate;
     * project, document, chunk, and code topology is retained but never coalesced.
     */
    public Result resolve(UnifiedGraph source, Config config) {
        Objects.requireNonNull(source, "source");
        Config effective = config == null ? Config.defaults() : config;

        List<GraphEntity> candidates = source.entities().stream()
                .filter(UnifiedGraphEntityResolver::isSemanticCandidate)
                .sorted(Comparator.comparing(GraphEntity::id))
                .toList();

        Map<String, String> correctedTypes = new LinkedHashMap<>();
        for (GraphEntity entity : candidates) {
            String corrected = correctedType(entity);
            if (!corrected.equals(entity.type())) {
                correctedTypes.put(entity.id(), corrected);
            }
        }

        Map<String, List<Signature>> signatureIndex = new LinkedHashMap<>();
        for (GraphEntity entity : candidates) {
            for (Signature signature : signatures(entity)) {
                signatureIndex.computeIfAbsent(signature.key(), ignored -> new ArrayList<>())
                        .add(signature);
            }
        }

        LinkedHashMap<String, CandidatePair> pairs = new LinkedHashMap<>();
        boolean capped = false;
        outer:
        for (List<Signature> block : signatureIndex.values()) {
            for (int i = 0; i < block.size(); i++) {
                for (int j = i + 1; j < block.size(); j++) {
                    Signature leftSignature = block.get(i);
                    Signature rightSignature = block.get(j);
                    GraphEntity left = leftSignature.entity();
                    GraphEntity right = rightSignature.entity();
                    if (left.id().equals(right.id())
                            || !compatibleTypes(effectiveType(left, correctedTypes),
                            effectiveType(right, correctedTypes))) {
                        continue;
                    }
                    String key = pairKey(left.id(), right.id());
                    CandidatePair previous = pairs.get(key);
                    double score = pairScore(
                            source, left, right,
                            leftSignature.identifier() && rightSignature.identifier(),
                            effective);
                    if (score < effective.minimumCandidateScore()) {
                        continue;
                    }
                    CandidatePair candidate = new CandidatePair(left, right, score);
                    if (previous == null || score > previous.score()) {
                        pairs.put(key, candidate);
                    }
                    if (pairs.size() >= effective.maxCandidatePairs()) {
                        capped = true;
                        break outer;
                    }
                }
            }
        }

        UnionFind unions = new UnionFind(candidates.stream().map(GraphEntity::id).toList());
        int acceptedPairs = applyCollectiveResolution(pairs.values(), unions, correctedTypes, effective);

        Map<String, String> canonicalIds = electCanonicalIds(candidates, unions);
        int mergedEntities = (int) canonicalIds.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(entry.getValue()))
                .count();
        boolean needsIdentifierLinks = needsEmailResolutionMaterialization(
                source, canonicalIds, correctedTypes);
        boolean changed = mergedEntities > 0 || !correctedTypes.isEmpty()
                || needsIdentifierLinks;

        UnifiedGraph resolved = changed
                ? canonicalize(source, canonicalIds, correctedTypes)
                : source;
        int identifierLinksCreated = changed
                ? materializeEmailResolutions(resolved) : 0;
        resolved.meta("entityResolution.backend", "portable-unified-graph")
                .meta("entityResolution.candidatePairs", pairs.size())
                .meta("entityResolution.acceptedPairs", acceptedPairs)
                .meta("entityResolution.entitiesMerged", mergedEntities)
                .meta("entityResolution.typeCorrections", correctedTypes.size())
                .meta("entityResolution.identifierLinksCreated", identifierLinksCreated)
                .meta("entityResolution.candidateCapReached", capped)
                .meta("entityResolution.updatedAt", Instant.now().toString());

        return new Result(
                resolved,
                mergedEntities,
                correctedTypes.size(),
                identifierLinksCreated,
                pairs.size(),
                acceptedPairs,
                capped,
                Map.copyOf(canonicalIds));
    }

    private static int applyCollectiveResolution(
            Collection<CandidatePair> pairs,
            UnionFind unions,
            Map<String, String> correctedTypes,
            Config config) {
        if (pairs.isEmpty()) {
            return 0;
        }

        Map<String, List<CandidatePair>> components = candidateComponents(pairs);
        int accepted = 0;
        for (List<CandidatePair> component : components.values()) {
            SameAsCollectiveResolution collective = new SameAsCollectiveResolution()
                    .simWeight(config.pslSimilarityWeight())
                    .transitivityWeight(config.pslTransitivityWeight())
                    .negativePriorWeight(config.pslNegativePriorWeight());
            LinkedHashMap<String, GraphEntity> entities = new LinkedHashMap<>();
            Map<String, Double> directScores = new HashMap<>();
            for (CandidatePair pair : component) {
                entities.put(pair.left().id(), pair.left());
                entities.put(pair.right().id(), pair.right());
                directScores.put(pairKey(pair.left().id(), pair.right().id()), pair.score());
                collective.addSimilarity(pair.left().id(), pair.right().id(), pair.score());
            }
            SameAsCollectiveResolution.Result result = collective.resolve();
            List<GraphEntity> ordered = new ArrayList<>(entities.values());
            for (int i = 0; i < ordered.size(); i++) {
                for (int j = i + 1; j < ordered.size(); j++) {
                    GraphEntity left = ordered.get(i);
                    GraphEntity right = ordered.get(j);
                    if (!compatibleTypes(effectiveType(left, correctedTypes),
                            effectiveType(right, correctedTypes))) {
                        continue;
                    }
                    double posterior = Math.max(
                            result.sameAs(left.id(), right.id()),
                            directScores.getOrDefault(pairKey(left.id(), right.id()), 0.0));
                    if (posterior >= config.mergeThreshold()) {
                        if (unions.union(left.id(), right.id())) {
                            accepted++;
                        }
                    }
                }
            }
        }
        return accepted;
    }

    private static Map<String, List<CandidatePair>> candidateComponents(
            Collection<CandidatePair> pairs) {
        UnionFind components = new UnionFind(pairs.stream()
                .flatMap(pair -> java.util.stream.Stream.of(pair.left().id(), pair.right().id()))
                .distinct()
                .toList());
        for (CandidatePair pair : pairs) {
            components.union(pair.left().id(), pair.right().id());
        }
        Map<String, List<CandidatePair>> grouped = new LinkedHashMap<>();
        for (CandidatePair pair : pairs) {
            grouped.computeIfAbsent(components.find(pair.left().id()), ignored -> new ArrayList<>())
                    .add(pair);
        }
        return grouped;
    }

    private static Map<String, String> electCanonicalIds(
            List<GraphEntity> candidates,
            UnionFind unions) {
        Map<String, List<GraphEntity>> groups = new LinkedHashMap<>();
        for (GraphEntity entity : candidates) {
            groups.computeIfAbsent(unions.find(entity.id()), ignored -> new ArrayList<>())
                    .add(entity);
        }
        Map<String, String> canonicalIds = new LinkedHashMap<>();
        Comparator<GraphEntity> canonicalOrder = Comparator
                .comparingDouble(GraphEntity::confidence).reversed()
                .thenComparing(Comparator.comparingInt(
                        (GraphEntity entity) -> entity.label() == null ? 0 : entity.label().length())
                        .reversed())
                .thenComparing(GraphEntity::id);
        for (List<GraphEntity> group : groups.values()) {
            String canonical = group.stream().min(canonicalOrder).orElseThrow().id();
            for (GraphEntity entity : group) {
                canonicalIds.put(entity.id(), canonical);
            }
        }
        return canonicalIds;
    }

    private static UnifiedGraph canonicalize(
            UnifiedGraph source,
            Map<String, String> canonicalIds,
            Map<String, String> correctedTypes) {
        UnifiedGraph target = new UnifiedGraph();
        source.meta().forEach(target::meta);
        source.weightMaps().forEach(target::putWeightMap);
        source.artifacts().forEach(target::putArtifact);

        Map<String, List<GraphEntity>> entityGroups = new LinkedHashMap<>();
        for (GraphEntity entity : source.entities()) {
            String canonicalId = canonicalIds.getOrDefault(entity.id(), entity.id());
            entityGroups.computeIfAbsent(canonicalId, ignored -> new ArrayList<>()).add(entity);
        }
        for (Map.Entry<String, List<GraphEntity>> entry : entityGroups.entrySet()) {
            target.addEntity(mergeEntities(
                    entry.getKey(), entry.getValue(), correctedTypes));
        }

        Map<String, RelationAccumulator> relations = new LinkedHashMap<>();
        Map<String, String> retainedRelationIds = new HashMap<>();
        for (GraphRelation relation : source.relations()) {
            String sourceId = canonicalIds.getOrDefault(relation.sourceId(), relation.sourceId());
            String targetId = canonicalIds.getOrDefault(relation.targetId(), relation.targetId());
            if (sourceId.equals(targetId) && !relation.sourceId().equals(relation.targetId())) {
                continue;
            }
            String key = sourceId + "\u0000" + relation.type() + "\u0000"
                    + targetId + "\u0000" + relation.directed();
            RelationAccumulator accumulator = relations.computeIfAbsent(
                    key, ignored -> new RelationAccumulator(relation.id(), sourceId, targetId,
                            relation.type(), relation.directed()));
            accumulator.add(relation);
            retainedRelationIds.put(relation.id(), accumulator.id);
        }
        for (RelationAccumulator relation : relations.values()) {
            target.addRelation(relation.build());
        }

        copyVectorLayers(source, target, canonicalIds, retainedRelationIds);
        copyOpinions(source, target, canonicalIds, retainedRelationIds);
        return target;
    }

    private static GraphEntity mergeEntities(
            String canonicalId,
            List<GraphEntity> entities,
            Map<String, String> correctedTypes) {
        GraphEntity canonical = entities.stream()
                .filter(entity -> entity.id().equals(canonicalId))
                .findFirst()
                .orElse(entities.get(0));

        LinkedHashSet<String> tags = new LinkedHashSet<>();
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        LinkedHashSet<String> types = new LinkedHashSet<>();
        LinkedHashMap<String, Object> attributes = new LinkedHashMap<>(canonical.attributes());
        List<double[]> embeddings = new ArrayList<>();
        double confidence = canonical.confidence();
        double weight = canonical.weight();

        for (GraphEntity entity : entities) {
            tags.addAll(entity.tags());
            aliases.addAll(aliases(entity));
            if (entity.label() != null && !entity.label().isBlank()
                    && !normalize(entity.label()).equals(normalize(canonical.label()))) {
                aliases.add(entity.label());
            }
            String type = correctedTypes.getOrDefault(entity.id(), entity.type());
            if (type != null && !type.isBlank()) {
                types.add(type);
            }
            entity.attributes().forEach(attributes::putIfAbsent);
            if (entity.hasEmbedding()) {
                embeddings.add(entity.embedding());
            }
            confidence = Math.max(confidence, entity.confidence());
            weight = Math.max(weight, entity.weight());
        }

        String primaryType = correctedTypes.getOrDefault(canonical.id(), canonical.type());
        types.remove(primaryType);
        if (!aliases.isEmpty()) {
            attributes.put("aliases", List.copyOf(aliases));
        }
        if (!types.isEmpty()) {
            attributes.put("additionalTypes", List.copyOf(types));
        }
        if (entities.size() > 1) {
            attributes.put("mergedEntityIds",
                    entities.stream().map(GraphEntity::id).filter(id -> !id.equals(canonicalId)).toList());
        }

        return GraphEntity.builder(canonicalId)
                .type(primaryType)
                .label(canonical.label())
                .weight(weight)
                .confidence(confidence)
                .tags(tags)
                .embedding(averageVectors(embeddings))
                .timestamp(canonical.timestamp())
                .attributes(attributes)
                .build();
    }

    private static void copyVectorLayers(
            UnifiedGraph source,
            UnifiedGraph target,
            Map<String, String> canonicalIds,
            Map<String, String> retainedRelationIds) {
        for (VectorLayer layer : source.vectorLayers().values()) {
            VectorLayer copy = new VectorLayer(layer.name(), layer.target(), layer.dim(), layer.dtype());
            if (layer.target() == VectorLayer.Target.ENTITY) {
                Map<String, List<double[]>> grouped = new LinkedHashMap<>();
                layer.rows().forEach((id, vector) -> grouped
                        .computeIfAbsent(canonicalIds.getOrDefault(id, id), ignored -> new ArrayList<>())
                        .add(vector));
                grouped.forEach((id, vectors) -> {
                    double[] average = averageVectors(vectors);
                    if (average != null) copy.put(id, average);
                });
            } else if (layer.target() == VectorLayer.Target.RELATION) {
                Map<String, List<double[]>> grouped = new LinkedHashMap<>();
                layer.rows().forEach((id, vector) -> {
                    String retained = retainedRelationIds.get(id);
                    if (retained != null) {
                        grouped.computeIfAbsent(retained, ignored -> new ArrayList<>()).add(vector);
                    }
                });
                grouped.forEach((id, vectors) -> {
                    double[] average = averageVectors(vectors);
                    if (average != null) copy.put(id, average);
                });
            } else {
                layer.rows().forEach((id, vector) -> copy.put(id, vector.clone()));
            }
            target.putVectorLayer(copy);
        }
    }

    private static void copyOpinions(
            UnifiedGraph source,
            UnifiedGraph target,
            Map<String, String> canonicalIds,
            Map<String, String> retainedRelationIds) {
        Map<String, Opinion> entityOpinions = new LinkedHashMap<>();
        source.entityOpinions().forEach((id, opinion) -> {
            String canonicalId = canonicalIds.getOrDefault(id, id);
            if (id.equals(canonicalId)) {
                entityOpinions.put(canonicalId, opinion);
            } else {
                entityOpinions.putIfAbsent(canonicalId, opinion);
            }
        });
        entityOpinions.forEach(target::putEntityOpinion);

        Map<String, Opinion> relationOpinions = new LinkedHashMap<>();
        source.relationOpinions().forEach((id, opinion) -> {
            String retained = retainedRelationIds.get(id);
            if (retained != null) {
                relationOpinions.putIfAbsent(retained, opinion);
            }
        });
        relationOpinions.forEach(target::putRelationOpinion);
    }

    private static boolean needsEmailResolutionMaterialization(
            UnifiedGraph graph,
            Map<String, String> canonicalIds,
            Map<String, String> correctedTypes) {
        Map<String, Set<String>> owners = emailOwners(graph.entities(), canonicalIds, correctedTypes);
        for (GraphEntity entity : graph.entities()) {
            String type = correctedTypes.getOrDefault(entity.id(), entity.type());
            if (!isSemanticCandidate(entity) || !IDENTIFIER_TYPES.contains(normalizeType(type))) {
                continue;
            }
            for (String email : emails(entity, type)) {
                Set<String> matches = owners.getOrDefault(email, Set.of());
                if (matches.size() != 1) continue;
                String identifierId = canonicalIds.getOrDefault(entity.id(), entity.id());
                String ownerId = matches.iterator().next();
                if (!identifierId.equals(ownerId)
                        && !hasIdentityRelation(graph, canonicalIds, identifierId, ownerId)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int materializeEmailResolutions(UnifiedGraph graph) {
        Map<String, String> identityMap = new LinkedHashMap<>();
        graph.entities().forEach(entity -> identityMap.put(entity.id(), entity.id()));
        Map<String, Set<String>> owners = emailOwners(graph.entities(), identityMap, Map.of());
        int created = 0;
        for (GraphEntity identifier : new ArrayList<>(graph.entities())) {
            if (!isSemanticCandidate(identifier)
                    || !IDENTIFIER_TYPES.contains(normalizeType(identifier.type()))) {
                continue;
            }
            for (String email : emails(identifier)) {
                Set<String> matches = owners.getOrDefault(email, Set.of());
                if (matches.size() != 1) continue;
                String ownerId = matches.iterator().next();
                if (identifier.id().equals(ownerId)
                        || hasIdentityRelation(graph, identityMap, identifier.id(), ownerId)) {
                    continue;
                }
                graph.addRelation(GraphRelation.builder(
                                "identity:email:" + email + "->" + ownerId,
                                identifier.id(),
                                ownerId)
                        .type("RESOLVES_TO")
                        .confidence(1.0)
                        .tag("identity")
                        .attribute("identifierKind", "EMAIL")
                        .attribute("identifierValue", email)
                        .attribute("provenance", "portable-entity-resolution")
                        .build());
                created++;
            }
        }
        return created;
    }

    private static Map<String, Set<String>> emailOwners(
            Collection<GraphEntity> entities,
            Map<String, String> canonicalIds,
            Map<String, String> correctedTypes) {
        Map<String, Set<String>> owners = new LinkedHashMap<>();
        for (GraphEntity entity : entities) {
            String type = correctedTypes.getOrDefault(entity.id(), entity.type());
            if (!isSemanticCandidate(entity) || IDENTIFIER_TYPES.contains(normalizeType(type))) {
                continue;
            }
            String canonicalId = canonicalIds.getOrDefault(entity.id(), entity.id());
            for (String email : emails(entity, type)) {
                owners.computeIfAbsent(email, ignored -> new LinkedHashSet<>()).add(canonicalId);
            }
        }
        return owners;
    }

    private static boolean hasIdentityRelation(
            UnifiedGraph graph,
            Map<String, String> canonicalIds,
            String identifierId,
            String ownerId) {
        for (GraphRelation relation : graph.relations()) {
            String sourceId = canonicalIds.getOrDefault(relation.sourceId(), relation.sourceId());
            String targetId = canonicalIds.getOrDefault(relation.targetId(), relation.targetId());
            boolean endpointsMatch = sourceId.equals(identifierId) && targetId.equals(ownerId)
                    || sourceId.equals(ownerId) && targetId.equals(identifierId);
            if (endpointsMatch && isIdentityRelationType(relation.type())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIdentityRelationType(String type) {
        String normalized = normalizeType(type);
        return normalized.equals("RESOLVES_TO")
                || normalized.equals("IDENTIFIES")
                || normalized.equals("EMAIL_OF")
                || normalized.equals("HAS_EMAIL")
                || normalized.equals("EMAIL_ADDRESS_OF");
    }

    private static double pairScore(
            UnifiedGraph graph,
            GraphEntity left,
            GraphEntity right,
            boolean sharedIdentifier,
            Config config) {
        String leftLabel = normalize(left.label());
        String rightLabel = normalize(right.label());
        Set<String> leftAliases = normalizedAliases(left);
        Set<String> rightAliases = normalizedAliases(right);

        double score = sharedIdentifier ? 1.0 : 0.0;
        if (!leftLabel.isEmpty() && leftLabel.equals(rightLabel)) {
            score = Math.max(score, 1.0);
        }
        if (leftAliases.contains(rightLabel) || rightAliases.contains(leftLabel)) {
            score = Math.max(score, 0.97);
        }
        Set<String> overlap = new HashSet<>(leftAliases);
        overlap.retainAll(rightAliases);
        if (!overlap.isEmpty()) {
            score = Math.max(score, 0.95);
        }

        if (score <= 0.0) {
            return 0.0;
        }

        double relationSupport = neighborhoodSimilarity(graph, left.id(), right.id());
        score = Math.min(1.0, score + 0.04 * relationSupport);
        if (config.useEmbeddings()) {
            double embedding = cosine(left.embedding(), right.embedding());
            if (embedding >= config.embeddingThreshold()) {
                score = Math.min(1.0, score + 0.04 * embedding);
            }
        }
        return score;
    }

    private static double neighborhoodSimilarity(UnifiedGraph graph, String leftId, String rightId) {
        Set<String> left = neighborhoodSignatures(graph, leftId);
        Set<String> right = neighborhoodSignatures(graph, rightId);
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private static Set<String> neighborhoodSignatures(UnifiedGraph graph, String entityId) {
        LinkedHashSet<String> signatures = new LinkedHashSet<>();
        for (GraphRelation relation : graph.relationsOf(entityId)) {
            boolean outgoing = relation.sourceId().equals(entityId);
            String neighborId = outgoing ? relation.targetId() : relation.sourceId();
            GraphEntity neighbor = graph.entity(neighborId).orElse(null);
            String neighborLabel = neighbor == null ? neighborId : normalize(neighbor.label());
            signatures.add((outgoing ? "out:" : "in:") + normalize(relation.type())
                    + ":" + neighborLabel);
        }
        return signatures;
    }

    private static List<Signature> signatures(GraphEntity entity) {
        LinkedHashMap<String, Boolean> values = new LinkedHashMap<>();
        String label = normalize(entity.label());
        if (label.length() >= 3 && !EMAIL.matcher(entity.label() == null ? "" : entity.label()).matches()) {
            values.put("name:" + label, false);
        }
        for (String alias : aliases(entity)) {
            String normalized = normalize(alias);
            if (normalized.length() >= 3 && !EMAIL.matcher(alias).matches()) {
                values.put("name:" + normalized, false);
            }
        }
        for (String email : emails(entity)) {
            values.put("identifier:email:" + email, true);
        }

        List<Signature> result = new ArrayList<>();
        values.forEach((value, identifier) ->
                result.add(new Signature(entity, value, identifier)));
        return result;
    }

    private static Set<String> aliases(GraphEntity entity) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        for (String key : ALIAS_KEYS) {
            addStrings(aliases, entity.attributes().get(key));
        }
        return aliases;
    }

    private static Set<String> normalizedAliases(GraphEntity entity) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String alias : aliases(entity)) {
            String value = normalize(alias);
            if (!value.isEmpty()) normalized.add(value);
        }
        return normalized;
    }

    private static Set<String> emails(GraphEntity entity) {
        return emails(entity, entity.type());
    }

    private static Set<String> emails(GraphEntity entity, String effectiveType) {
        LinkedHashSet<String> emails = new LinkedHashSet<>();
        for (String key : EMAIL_KEYS) {
            LinkedHashSet<String> raw = new LinkedHashSet<>();
            addStrings(raw, entity.attributes().get(key));
            for (String value : raw) {
                String normalized = value.trim().toLowerCase(Locale.ROOT);
                if (EMAIL.matcher(normalized).matches()) {
                    emails.add(normalized);
                }
            }
        }
        if (IDENTIFIER_TYPES.contains(normalizeType(effectiveType))
                && entity.label() != null && EMAIL.matcher(entity.label().trim()).matches()) {
            emails.add(entity.label().trim().toLowerCase(Locale.ROOT));
        }
        return emails;
    }

    private static void addStrings(Set<String> target, Object raw) {
        if (raw instanceof Iterable<?> iterable) {
            for (Object value : iterable) addStrings(target, value);
        } else if (raw instanceof Object[] array) {
            for (Object value : array) addStrings(target, value);
        } else if (raw != null) {
            String value = String.valueOf(raw).trim();
            if (!value.isEmpty()) target.add(value);
        }
    }

    private static String correctedType(GraphEntity entity) {
        String title = entity.label() == null ? "" : entity.label().trim();
        if ("PERSON".equalsIgnoreCase(entity.type()) && EMAIL.matcher(title).matches()) {
            return "EMAIL_ADDRESS";
        }
        return entity.type();
    }

    private static String effectiveType(
            GraphEntity entity,
            Map<String, String> correctedTypes) {
        return correctedTypes.getOrDefault(entity.id(), entity.type());
    }

    private static boolean compatibleTypes(String left, String right) {
        String a = normalizeType(left);
        String b = normalizeType(right);
        if (a.equals(b)) return true;
        if (IDENTIFIER_TYPES.contains(a) || IDENTIFIER_TYPES.contains(b)) return false;
        return UNKNOWN_TYPES.contains(a) || UNKNOWN_TYPES.contains(b);
    }

    private static boolean isSemanticCandidate(GraphEntity entity) {
        return entity.hasTag("semantic")
                || "unified-corpus-extraction".equals(entity.attributes().get("provenance"));
    }

    private static String normalizeType(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "");
        return decomposed.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}@]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static String pairKey(String left, String right) {
        return left.compareTo(right) <= 0
                ? left + "\u0000" + right
                : right + "\u0000" + left;
    }

    private static double cosine(double[] left, double[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            return 0.0;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) return 0.0;
        return Math.max(0.0, Math.min(1.0, dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm))));
    }

    private static double[] averageVectors(List<double[]> vectors) {
        if (vectors == null || vectors.isEmpty()) return null;
        int dimension = vectors.get(0).length;
        if (dimension == 0) return null;
        double[] sum = new double[dimension];
        int count = 0;
        for (double[] vector : vectors) {
            if (vector == null || vector.length != dimension) continue;
            for (int i = 0; i < dimension; i++) sum[i] += vector[i];
            count++;
        }
        if (count == 0) return null;
        for (int i = 0; i < dimension; i++) sum[i] /= count;
        return sum;
    }

    public record Config(
            double mergeThreshold,
            double minimumCandidateScore,
            boolean useEmbeddings,
            double embeddingThreshold,
            int maxCandidatePairs,
            double pslSimilarityWeight,
            double pslTransitivityWeight,
            double pslNegativePriorWeight) {
        public Config {
            requireProbability(mergeThreshold, "mergeThreshold");
            requireProbability(minimumCandidateScore, "minimumCandidateScore");
            requireProbability(embeddingThreshold, "embeddingThreshold");
            if (maxCandidatePairs < 1) {
                throw new IllegalArgumentException("maxCandidatePairs must be >= 1");
            }
            if (pslSimilarityWeight <= 0.0
                    || pslTransitivityWeight <= 0.0
                    || pslNegativePriorWeight < 0.0) {
                throw new IllegalArgumentException("PSL weights must be positive (negative prior may be zero)");
            }
        }

        public static Config defaults() {
            return new Config(0.86, 0.80, true, 0.90, 50_000, 20.0, 4.0, 1.0);
        }

        private static void requireProbability(double value, String name) {
            if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
                throw new IllegalArgumentException(name + " must be in [0, 1]");
            }
        }
    }

    public record Result(
            UnifiedGraph graph,
            int entitiesMerged,
            int typesCorrected,
            int identifierLinksCreated,
            int candidatePairs,
            int acceptedPairs,
            boolean candidateCapReached,
            Map<String, String> canonicalIds) {
        public Result {
            Objects.requireNonNull(graph, "graph");
            canonicalIds = Map.copyOf(canonicalIds);
        }

        public boolean graphChanged() {
            return entitiesMerged > 0 || typesCorrected > 0 || identifierLinksCreated > 0;
        }
    }

    private record Signature(GraphEntity entity, String key, boolean identifier) {
    }

    private record CandidatePair(GraphEntity left, GraphEntity right, double score) {
    }

    private static final class RelationAccumulator {
        private final String id;
        private final String sourceId;
        private final String targetId;
        private final String type;
        private final boolean directed;
        private final LinkedHashSet<String> tags = new LinkedHashSet<>();
        private final LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
        private final List<double[]> embeddings = new ArrayList<>();
        private double weight;
        private double confidence;
        private Instant timestamp;

        private RelationAccumulator(
                String id,
                String sourceId,
                String targetId,
                String type,
                boolean directed) {
            this.id = id;
            this.sourceId = sourceId;
            this.targetId = targetId;
            this.type = type;
            this.directed = directed;
        }

        private void add(GraphRelation relation) {
            weight = Math.max(weight, relation.weight());
            confidence = Math.max(confidence, relation.confidence());
            tags.addAll(relation.tags());
            relation.attributes().forEach(attributes::putIfAbsent);
            if (relation.hasEmbedding()) embeddings.add(relation.embedding());
            if (timestamp == null || relation.timestamp() != null && relation.timestamp().isAfter(timestamp)) {
                timestamp = relation.timestamp();
            }
        }

        private GraphRelation build() {
            return GraphRelation.builder(id, sourceId, targetId)
                    .type(type)
                    .weight(weight)
                    .confidence(confidence)
                    .directed(directed)
                    .tags(tags)
                    .embedding(averageVectors(embeddings))
                    .timestamp(timestamp)
                    .attributes(attributes)
                    .build();
        }
    }

    private static final class UnionFind {
        private final Map<String, String> parent = new HashMap<>();

        private UnionFind(Collection<String> ids) {
            for (String id : ids) parent.put(id, id);
        }

        private String find(String id) {
            String current = parent.computeIfAbsent(id, ignored -> id);
            if (!current.equals(id)) {
                current = find(current);
                parent.put(id, current);
            }
            return current;
        }

        private boolean union(String left, String right) {
            String leftRoot = find(left);
            String rightRoot = find(right);
            if (leftRoot.equals(rightRoot)) return false;
            String first = leftRoot.compareTo(rightRoot) <= 0 ? leftRoot : rightRoot;
            String second = first.equals(leftRoot) ? rightRoot : leftRoot;
            parent.put(second, first);
            return true;
        }
    }
}
