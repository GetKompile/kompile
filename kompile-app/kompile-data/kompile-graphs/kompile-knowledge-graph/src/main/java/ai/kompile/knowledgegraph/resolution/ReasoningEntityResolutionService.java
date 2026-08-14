/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.knowledgegraph.resolution;

import ai.kompile.graph.reasoning.embedding.Embeddings;
import ai.kompile.graph.reasoning.embedding.GraphEmbeddingResolver;
import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.MebnInferenceService;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.grounding.FactSheetKbState;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a read-only, corpus-wide evidence snapshot for final entity resolution.
 *
 * <p>The snapshot deliberately consumes the same models learned by the final grounding
 * cascade: observed FOL facts, PSL inferred facts, the registered MEBN theory, graph
 * topology, and KGE entity/relation embeddings. It performs no graph writes and never
 * learns a second disconnected model.</p>
 */
@Service
public class ReasoningEntityResolutionService {

    private static final Logger log = LoggerFactory.getLogger(ReasoningEntityResolutionService.class);
    private static final Pattern BINARY_ATOM =
            Pattern.compile("^\\s*([^()]+)\\(([^,()]+),\\s*([^,()]+)\\)\\s*$");

    private static final Set<String> POSITIVE_IDENTITY_PREDICATES = Set.of(
            "sameas", "sameentity", "sameidentity", "equivalentto", "aliasof",
            "resolvesto", "coreferswith", "identicalto", "identitymatch");

    private static final Set<String> NEGATIVE_IDENTITY_PREDICATES = Set.of(
            "differentfrom", "distinctfrom", "notsameas", "notidenticalto",
            "identityconflict", "incompatibleidentity");

    private static final Set<String> EXCLUSIVE_NEIGHBOR_TOKENS = Set.of(
            "email", "mailbox", "phone", "telephone", "identifier", "identity",
            "account", "username", "login", "employeeid", "customerid");

    private final KbGroundingService kbGroundingService;

    @Nullable
    @Autowired(required = false)
    private IncrementalReasoningOrchestrator reasoningOrchestrator;

    public ReasoningEntityResolutionService(KbGroundingService kbGroundingService) {
        this.kbGroundingService = kbGroundingService;
    }

    /**
     * Capture all currently learned evidence for one completed corpus crawl.
     */
    public Snapshot snapshot(long factSheetId) {
        ReasoningGraph graph = reasoningOrchestrator != null
                ? reasoningOrchestrator.reasoningGraph(factSheetId).orElse(null)
                : null;

        Map<String, Double> observedPositive = new LinkedHashMap<>();
        Map<String, Double> observedNegative = new LinkedHashMap<>();
        Map<String, Double> inferredPositive = new LinkedHashMap<>();
        Map<String, Double> inferredNegative = new LinkedHashMap<>();
        Map<String, double[]> pslImportance = new LinkedHashMap<>();

        FactSheetKbState state = kbGroundingService.getState(factSheetId);
        Lock readLock = state.lock().readLock();
        readLock.lock();
        try {
            for (Fact fact : state.factStore().allFacts()) {
                collectIdentityAtom(fact.atomKey(), fact.value(), observedPositive, observedNegative);
            }
            for (InferredFact fact : state.inferredFactStore().allLatest()) {
                collectIdentityAtom(fact.atomKey(), fact.value(), inferredPositive, inferredNegative);
                accumulateImportance(pslImportance, fact.atomKey(), fact.value());
            }
        } finally {
            readLock.unlock();
        }

        Map<String, double[]> mebnImportance = new LinkedHashMap<>();
        if (reasoningOrchestrator != null && graph != null) {
            MTheory theory = reasoningOrchestrator.registeredMTheory(factSheetId).orElse(null);
            if (theory != null) {
                try {
                    Map<String, Double> posteriors =
                            new MebnInferenceService().infer(graph, theory, Map.of());
                    posteriors.forEach((atom, value) ->
                            accumulateImportance(mebnImportance, atom, value));
                } catch (Exception ex) {
                    log.warn("MEBN entity-resolution evidence unavailable for factSheet={}: {}",
                            factSheetId, ex.getMessage());
                }
            }
        }

        Snapshot snapshot = new Snapshot(
                graph,
                Map.copyOf(observedPositive),
                Map.copyOf(observedNegative),
                Map.copyOf(inferredPositive),
                Map.copyOf(inferredNegative),
                averageImportance(pslImportance),
                averageImportance(mebnImportance));

        log.info("Prepared reasoning-assisted entity-resolution snapshot for factSheet={}: "
                        + "graphEntities={}, graphRelations={}, folIdentity={}, pslIdentity={}, "
                        + "pslContext={}, mebnContext={}",
                factSheetId,
                graph != null ? graph.entityCount() : 0,
                graph != null ? graph.relationCount() : 0,
                observedPositive.size() + observedNegative.size(),
                inferredPositive.size() + inferredNegative.size(),
                snapshot.pslImportance().size(),
                snapshot.mebnImportance().size());
        return snapshot;
    }

    private static void collectIdentityAtom(
            String atomKey,
            double value,
            Map<String, Double> positive,
            Map<String, Double> negative) {
        ParsedBinaryAtom atom = parseBinaryAtom(atomKey);
        if (atom == null) return;
        String predicate = normalizePredicate(atom.predicate());
        String pairKey = pairKey(atom.left(), atom.right());
        if (POSITIVE_IDENTITY_PREDICATES.contains(predicate)) {
            positive.merge(pairKey, clamp01(value), Math::max);
        } else if (NEGATIVE_IDENTITY_PREDICATES.contains(predicate)) {
            negative.merge(pairKey, clamp01(value), Math::max);
        }
    }

    private static void accumulateImportance(Map<String, double[]> accumulator, String atomKey, double value) {
        ParsedBinaryAtom atom = parseBinaryAtom(atomKey);
        if (atom == null) return;
        addImportance(accumulator, atom.left(), value);
        addImportance(accumulator, atom.right(), value);
    }

    private static void addImportance(Map<String, double[]> accumulator, String entityId, double value) {
        String key = normalizeEntityKey(entityId);
        if (key.isEmpty()) return;
        double[] pair = accumulator.computeIfAbsent(key, ignored -> new double[2]);
        pair[0] += clamp01(value);
        pair[1] += 1.0;
    }

    private static Map<String, Double> averageImportance(Map<String, double[]> accumulated) {
        Map<String, Double> out = new LinkedHashMap<>();
        accumulated.forEach((key, pair) -> {
            if (pair[1] > 0.0) out.put(key, clamp01(pair[0] / pair[1]));
        });
        return Map.copyOf(out);
    }

    private static ParsedBinaryAtom parseBinaryAtom(String atomKey) {
        if (atomKey == null) return null;
        Matcher matcher = BINARY_ATOM.matcher(atomKey);
        if (!matcher.matches()) return null;
        return new ParsedBinaryAtom(matcher.group(1), matcher.group(2), matcher.group(3));
    }

    private static String normalizePredicate(String predicate) {
        String normalized = predicate == null ? "" : predicate.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
        while (normalized.startsWith("derived")) {
            normalized = normalized.substring("derived".length());
        }
        return normalized;
    }

    private static String pairKey(String left, String right) {
        String a = normalizeEntityKey(left);
        String b = normalizeEntityKey(right);
        return a.compareTo(b) <= 0 ? a + "\u0000" + b : b + "\u0000" + a;
    }

    private static String normalizeEntityKey(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT)
                .replace('(', '_')
                .replace(')', '_')
                .replace(',', '_')
                .replace(' ', '_');
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private record ParsedBinaryAtom(String predicate, String left, String right) {}

    /**
     * Immutable evidence captured after corpus-wide learning.
     */
    public record Snapshot(
            @Nullable ReasoningGraph graph,
            Map<String, Double> observedPositive,
            Map<String, Double> observedNegative,
            Map<String, Double> inferredPositive,
            Map<String, Double> inferredNegative,
            Map<String, Double> pslImportance,
            Map<String, Double> mebnImportance) {

        public static Snapshot empty() {
            return new Snapshot(null, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }

        public boolean available() {
            return graph != null || !observedPositive.isEmpty() || !inferredPositive.isEmpty()
                    || !observedNegative.isEmpty() || !inferredNegative.isEmpty();
        }

        /**
         * Evaluate one candidate pair without mutating the graph or learned models.
         */
        public Evidence evaluate(GraphNode left, GraphNode right) {
            if (left == null || right == null) return Evidence.empty();

            Set<String> leftKeys = entityKeys(left);
            Set<String> rightKeys = entityKeys(right);

            double folIdentity = pairSignal(observedPositive, leftKeys, rightKeys);
            double folConflict = pairSignal(observedNegative, leftKeys, rightKeys);
            double pslIdentity = pairSignal(inferredPositive, leftKeys, rightKeys);
            double pslConflict = pairSignal(inferredNegative, leftKeys, rightKeys);

            double semantic = semanticSimilarity(left.getNodeId(), right.getNodeId());
            RelationEvidence relations = relationEvidence(left.getNodeId(), right.getNodeId());
            double pslContext = contextSimilarity(pslImportance, leftKeys, rightKeys);
            double mebnContext = contextSimilarity(mebnImportance, leftKeys, rightKeys);

            List<String> reasons = new ArrayList<>();
            addReason(reasons, "FOL_IDENTITY", folIdentity);
            addReason(reasons, "PSL_IDENTITY", pslIdentity);
            addReason(reasons, "KGE_SEMANTIC", semantic);
            addReason(reasons, "GRAPH_RELATION_OVERLAP", relations.neighborhoodSimilarity());
            addReason(reasons, "EXCLUSIVE_IDENTIFIER_NEIGHBOR", relations.exclusiveIdentity());
            addReason(reasons, "GRAPH_IDENTITY_RELATION", relations.directIdentity());
            addReason(reasons, "PSL_CONTEXT", pslContext);
            addReason(reasons, "MEBN_CONTEXT", mebnContext);
            addReason(reasons, "FOL_IDENTITY_CONFLICT", folConflict);
            addReason(reasons, "PSL_IDENTITY_CONFLICT", pslConflict);

            return new Evidence(
                    folIdentity,
                    pslIdentity,
                    relations.directIdentity(),
                    relations.exclusiveIdentity(),
                    semantic,
                    relations.neighborhoodSimilarity(),
                    pslContext,
                    mebnContext,
                    Math.max(folConflict, pslConflict),
                    List.copyOf(reasons));
        }

        private double semanticSimilarity(String leftId, String rightId) {
            if (graph == null || leftId == null || rightId == null) return 0.0;
            try {
                GraphEmbeddingResolver.Resolved left = GraphEmbeddingResolver.resolve(graph, leftId, 2);
                GraphEmbeddingResolver.Resolved right = GraphEmbeddingResolver.resolve(graph, rightId, 2);
                if (!left.present() || !right.present()) return 0.0;
                return clamp01(Math.max(0.0, Embeddings.cosine(left.vector(), right.vector())));
            } catch (Exception ignored) {
                return 0.0;
            }
        }

        private RelationEvidence relationEvidence(String leftId, String rightId) {
            if (graph == null || leftId == null || rightId == null
                    || !graph.containsEntity(leftId) || !graph.containsEntity(rightId)) {
                return RelationEvidence.empty();
            }

            Map<String, Double> leftSignatures = relationSignatures(leftId, rightId);
            Map<String, Double> rightSignatures = relationSignatures(rightId, leftId);
            Set<String> intersection = new HashSet<>(leftSignatures.keySet());
            intersection.retainAll(rightSignatures.keySet());
            Set<String> union = new HashSet<>(leftSignatures.keySet());
            union.addAll(rightSignatures.keySet());

            double weightedIntersection = 0.0;
            double weightedUnion = 0.0;
            double exclusiveIdentity = 0.0;
            for (String key : union) {
                double l = leftSignatures.getOrDefault(key, 0.0);
                double r = rightSignatures.getOrDefault(key, 0.0);
                weightedIntersection += Math.min(l, r);
                weightedUnion += Math.max(l, r);
                if (l > 0.0 && r > 0.0 && isExclusiveSignature(key)) {
                    exclusiveIdentity = Math.max(exclusiveIdentity, Math.min(l, r));
                }
            }
            double neighborhood = weightedUnion > 0.0
                    ? clamp01(weightedIntersection / weightedUnion) : 0.0;
            double directIdentity = Math.max(
                    directIdentityRelation(leftId, rightId),
                    directIdentityRelation(rightId, leftId));
            return new RelationEvidence(neighborhood, exclusiveIdentity, directIdentity);
        }

        private Map<String, Double> relationSignatures(String entityId, String excludedNeighbor) {
            Map<String, Double> signatures = new HashMap<>();
            for (GraphRelation relation : graph.relationsOf(entityId)) {
                boolean outgoing = entityId.equals(relation.sourceId());
                String neighbor = outgoing ? relation.targetId() : relation.sourceId();
                if (neighbor == null || neighbor.equals(excludedNeighbor)) continue;
                String key = (outgoing ? "out|" : "in|")
                        + normalizePredicate(relation.type()) + "|" + normalizeEntityKey(neighbor);
                double strength = clamp01(Math.max(relation.weight(), relation.confidence()));
                signatures.merge(key, strength, Math::max);
            }
            return signatures;
        }

        private double directIdentityRelation(String sourceId, String targetId) {
            double support = 0.0;
            for (GraphRelation relation : graph.outgoing(sourceId)) {
                if (targetId.equals(relation.targetId())
                        && POSITIVE_IDENTITY_PREDICATES.contains(normalizePredicate(relation.type()))) {
                    support = Math.max(support,
                            clamp01(Math.max(relation.weight(), relation.confidence())));
                }
            }
            return support;
        }

        private static boolean isExclusiveSignature(String signature) {
            String[] parts = signature == null ? new String[0] : signature.split("\\|", 3);
            String relationType = parts.length >= 2 ? parts[1] : signature;
            String normalized = normalizePredicate(relationType);
            for (String token : EXCLUSIVE_NEIGHBOR_TOKENS) {
                if (normalized.contains(token)) return true;
            }
            return false;
        }

        private static Set<String> entityKeys(GraphNode node) {
            Set<String> keys = new LinkedHashSet<>();
            if (node.getNodeId() != null) keys.add(normalizeEntityKey(node.getNodeId()));
            if (node.getExternalId() != null) keys.add(normalizeEntityKey(node.getExternalId()));
            return Set.copyOf(keys);
        }

        private static double pairSignal(
                Map<String, Double> signals,
                Collection<String> leftKeys,
                Collection<String> rightKeys) {
            double support = 0.0;
            for (String left : leftKeys) {
                for (String right : rightKeys) {
                    support = Math.max(support, signals.getOrDefault(pairKey(left, right), 0.0));
                }
            }
            return support;
        }

        private static double contextSimilarity(
                Map<String, Double> importance,
                Collection<String> leftKeys,
                Collection<String> rightKeys) {
            Double left = bestImportance(importance, leftKeys);
            Double right = bestImportance(importance, rightKeys);
            if (left == null || right == null) return 0.0;
            return clamp01(1.0 - Math.abs(left - right));
        }

        private static Double bestImportance(Map<String, Double> importance, Collection<String> keys) {
            Double best = null;
            for (String key : keys) {
                Double candidate = importance.get(normalizeEntityKey(key));
                if (candidate != null && (best == null || candidate > best)) best = candidate;
            }
            return best;
        }

        private static void addReason(List<String> reasons, String label, double value) {
            if (value > 0.0) {
                reasons.add(label + ":" + String.format(Locale.ROOT, "%.3f", value));
            }
        }
    }

    private record RelationEvidence(
            double neighborhoodSimilarity,
            double exclusiveIdentity,
            double directIdentity) {

        private static RelationEvidence empty() {
            return new RelationEvidence(0.0, 0.0, 0.0);
        }
    }

    /**
     * Per-pair signals and conservative merge policy.
     */
    public record Evidence(
            double folIdentity,
            double pslIdentity,
            double directGraphIdentity,
            double exclusiveNeighborIdentity,
            double semanticSimilarity,
            double neighborhoodSimilarity,
            double pslContextSimilarity,
            double mebnContextSimilarity,
            double identityConflict,
            List<String> reasons) {

        public static Evidence empty() {
            return new Evidence(0.0, 0.0, 0.0, 0.0, 0.0,
                    0.0, 0.0, 0.0, 0.0, List.of());
        }

        public boolean vetoMerge() {
            return identityConflict >= 0.75;
        }

        /**
         * Add learned graph support to an existing lexical/alias/attribute score.
         *
         * <p>Contextual similarity alone never creates an identity. A merge must be anchored
         * by an explicit identity assertion, a shared exclusive identifier (for example the
         * same email node), or both semantic and relational agreement.</p>
         */
        public double mergeScore(double baseScore) {
            if (vetoMerge()) return 0.0;

            double score = clamp01(baseScore);
            if (folIdentity > 0.0) {
                score = Math.max(score, 0.90 + 0.10 * clamp01(folIdentity));
            }
            if (pslIdentity > 0.0) {
                score = Math.max(score, 0.88 + 0.12 * clamp01(pslIdentity));
            }
            if (directGraphIdentity > 0.0) {
                score = Math.max(score, 0.90 + 0.10 * clamp01(directGraphIdentity));
            }
            if (exclusiveNeighborIdentity > 0.0) {
                score = Math.max(score, 0.94 + 0.06 * clamp01(exclusiveNeighborIdentity));
            }

            boolean semanticAnchor = semanticSimilarity >= 0.75;
            boolean relationalAnchor = neighborhoodSimilarity >= 0.35;
            if (baseScore >= 0.55 && (semanticAnchor || relationalAnchor)) {
                double support = weightedSupport();
                score = Math.max(score, Math.min(0.96, baseScore + 0.25 * support));
            }
            if (semanticSimilarity >= 0.88 && neighborhoodSimilarity >= 0.50) {
                double joint = 0.40 * semanticSimilarity
                        + 0.35 * neighborhoodSimilarity
                        + 0.15 * pslContextSimilarity
                        + 0.10 * mebnContextSimilarity;
                score = Math.max(score, clamp01(joint));
            }
            return clamp01(score);
        }

        private double weightedSupport() {
            double weighted = 0.0;
            double weights = 0.0;
            if (semanticSimilarity > 0.0) {
                weighted += 0.40 * semanticSimilarity;
                weights += 0.40;
            }
            if (neighborhoodSimilarity > 0.0) {
                weighted += 0.30 * neighborhoodSimilarity;
                weights += 0.30;
            }
            if (pslContextSimilarity > 0.0) {
                weighted += 0.20 * pslContextSimilarity;
                weights += 0.20;
            }
            if (mebnContextSimilarity > 0.0) {
                weighted += 0.10 * mebnContextSimilarity;
                weights += 0.10;
            }
            return weights > 0.0 ? clamp01(weighted / weights) : 0.0;
        }
    }
}
