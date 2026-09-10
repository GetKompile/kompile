/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.lifecycle;

import ai.kompile.graph.reasoning.learning.PslWeightLearningService;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.psl.GraphPslProgramBuilder;
import ai.kompile.graph.reasoning.psl.GroundRule;
import ai.kompile.graph.reasoning.psl.HlMrfMapInference;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * Incremental MAP inference for the default graph-PSL program.
 *
 * <p>The default {@link GraphPslProgramBuilder} program is component-separable: every ground rule
 * contains one or more entity states, their priors, and (at most) one observed link/conflict edge. This
 * class solves those independent components separately and stores the solved component snapshots in
 * the {@link UnifiedGraph} model-artifact bundle. A later pass reuses only a component whose actual
 * entity membership, semantic inputs, learned rule weights, and solver contract are byte-for-byte
 * unchanged. The cache is deliberately graph-owned; there is no process-wide state or warm start.</p>
 *
 * <p>Only this default graph-PSL path is incremental. Other {@link ai.kompile.graph.reasoning.model.ReasoningGraph}
 * implementations continue to use the ordinary full-program path in {@code HybridReasoner}.</p>
 */
public final class IncrementalGraphPslInference {

    /** Persisted model-artifact name; it travels with {@link UnifiedGraph#save(java.nio.file.Path)}. */
    public static final String CACHE_ARTIFACT = "reasoning/incremental-graph-psl-cache.v1.bin";

    /** Version of the persisted component-cache contract. */
    public static final int CACHE_VERSION = 2;

    /** The default threshold in {@link GraphPslProgramBuilder}; kept here to partition identically. */
    private static final double DEFAULT_MIN_EDGE_WEIGHT = 0.05;
    private static final double DEFAULT_PROPAGATION_WEIGHT = 2.0;
    private static final double DEFAULT_ABDUCTION_WEIGHT = 1.0;
    private static final double DEFAULT_PRIOR_WEIGHT = 1.0;
    private static final double DEFAULT_CONFLICT_WEIGHT = 2.0;
    private static final String FINGERPRINT_ALGORITHM = "SHA-256";
    // ADMM residual/stopping semantics are part of the persisted solver contract. Bump this
    // when the numerical contract changes so old component snapshots are solved again.
    private static final String CONTRACT_VERSION = "graph-psl-default-v3";
    private static final List<String> DEFAULT_RULE_TEMPLATES = List.of(
            "propagation(State(X),Link(X,Y),State(Y),squared)",
            "abduction(State(Y),Link(X,Y),State(X),squared)",
            "conflict(State(Y),Conflict(X,Y),empty-head,squared)",
            "prior-forward(Prior(N),State(N),squared)",
            "prior-reverse(State(N),Prior(N),squared)");
    private static final String META_PREFIX = "reasoning.incrementalPsl.";

    public static final String META_COMPONENT_COUNT = META_PREFIX + "componentCount";
    public static final String META_REUSED_COMPONENT_COUNT = META_PREFIX + "reusedComponentCount";
    public static final String META_SOLVED_COMPONENT_COUNT = META_PREFIX + "solvedComponentCount";
    public static final String META_REUSE_COUNT = META_PREFIX + "reuseCount";
    public static final String META_SOLVE_COUNT = META_PREFIX + "solveCount";
    public static final String META_TOTAL_REUSED_COMPONENT_COUNT = META_PREFIX + "totalReusedComponentCount";
    public static final String META_TOTAL_SOLVED_COMPONENT_COUNT = META_PREFIX + "totalSolvedComponentCount";
    public static final String META_CACHE_ENTRIES = META_PREFIX + "cacheEntries";
    public static final String META_CACHE_LOADED = META_PREFIX + "cacheLoaded";
    public static final String META_NON_CONVERGED_COMPONENT_COUNT = META_PREFIX + "nonConvergedComponentCount";
    public static final String META_STATUS = META_PREFIX + "status";

    private IncrementalGraphPslInference() {
    }

    /**
     * Result of one incremental pass. Scores are keyed by actual graph entity id, never by the
     * builder's synthetic {@code n0}, {@code n1}, ... constants.
     */
    public record Result(Map<String, Double> scores,
                         int componentCount,
                         int reusedComponentCount,
                         int solvedComponentCount,
                         int cacheEntryCount,
                         boolean cacheLoaded,
                         int nonConvergedComponentCount) {
        public Result {
            scores = scores == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(scores));
            componentCount = Math.max(0, componentCount);
            reusedComponentCount = Math.max(0, reusedComponentCount);
            solvedComponentCount = Math.max(0, solvedComponentCount);
            cacheEntryCount = Math.max(0, cacheEntryCount);
            nonConvergedComponentCount = Math.max(0, nonConvergedComponentCount);
        }

        /** Backward-compatible result constructor for callers that do not inspect convergence. */
        public Result(Map<String, Double> scores, int componentCount, int reusedComponentCount,
                      int solvedComponentCount, int cacheEntryCount, boolean cacheLoaded) {
            this(scores, componentCount, reusedComponentCount, solvedComponentCount, cacheEntryCount,
                    cacheLoaded, 0);
        }
    }

    /** Run incremental default graph-PSL inference and persist the current component cache. */
    public static Result infer(UnifiedGraph graph) {
        return infer(graph, ignored -> { });
    }

    /**
     * Package-private test seam: the observer is called once for each component grounding pass.
     * It is deliberately not part of the cache or solver state.
     */
    static Result infer(UnifiedGraph graph, IntConsumer groundingObserver) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(groundingObserver, "groundingObserver");

        CacheSnapshot previous = readCache(graph);
        String ruleContract = ruleContract(graph);

        List<Component> components = components(graph);
        Map<String, ComponentSnapshot> currentEntries = new LinkedHashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();
        int reused = 0;
        int solved = 0;
        int nonConverged = 0;

        for (Component component : components) {
            ComponentSnapshot cached = previous.components().get(component.key());
            if (cached != null) {
                String solverContract = solverContract(cached.groundRuleCount(), cached.atomCount());
                String fingerprint = fingerprint(component, ruleContract, solverContract);
                if (reusable(cached, component, fingerprint, solverContract)) {
                    scores.putAll(cached.scores());
                    currentEntries.put(component.key(), cached);
                    reused++;
                    continue;
                }
            }

            MutableReasoningGraph localGraph = new MutableReasoningGraph();
            for (GraphEntity entity : component.entities()) {
                localGraph.addEntity(entity);
            }
            for (GraphRelation relation : component.relations()) {
                // MutableReasoningGraph intentionally accepts dangling endpoints. They matter for
                // the source/undirected degree prior even though no Link atom is emitted.
                localGraph.addRelation(relation);
            }

            GraphPslProgramBuilder localBuilder = new GraphPslProgramBuilder();
            PslProgram localProgram = UnifiedGraphReasoningLifecycle.applyLearnedPslWeights(
                    graph, localBuilder.build(localGraph));
            List<GroundRule> groundRules = localProgram.ground();
            groundingObserver.accept(groundRules.size());
            String solverContract = solverContract(groundRules.size(), localProgram.atomCount());
            String fingerprint = fingerprint(component, ruleContract, solverContract);
            HlMrfMapInference.Result inference = HlMrfMapInference.chooseSolver(
                    groundRules.size(), localProgram.atomCount()).solve(localProgram, groundRules);
            Map<String, Double> componentScores = actualScores(component, localBuilder, inference);
            scores.putAll(componentScores);
            solved++;
            if (!inference.converged()) {
                nonConverged++;
            }

            // Truncated grounding, non-convergence, malformed values, and incomplete state output
            // are valid for this invocation but are never persisted for a future reuse.
            if (reusableResult(component, localBuilder, localProgram.isGroundingTruncated(),
                    inference, componentScores)) {
                currentEntries.put(component.key(), new ComponentSnapshot(
                        CACHE_VERSION, component.key(), fingerprint, solverContract,
                        localProgram.atomCount(), groundRules.size(), true, componentScores));
            }
        }

        // Replacing the snapshot (rather than merging) drops removed/split/merged components.
        graph.putModel(CACHE_ARTIFACT, new CacheSnapshot(CACHE_VERSION, currentEntries));
        updateMetadata(graph, components.size(), reused, solved, nonConverged,
                currentEntries.size(), previous.loaded());
        return new Result(scores, components.size(), reused, solved, currentEntries.size(),
                previous.loaded(), nonConverged);
    }

    private static Map<String, Double> actualScores(Component component,
                                                     GraphPslProgramBuilder builder,
                                                     HlMrfMapInference.Result inference) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (GraphEntity entity : component.entities()) {
            String constant = builder.entityIdToConstant().get(entity.id());
            Double value = constant == null ? null
                    : inference.values().get(GraphPslProgramBuilder.STATE + "(" + constant + ")");
            scores.put(entity.id(), value == null ? 0.0 : value);
        }
        return scores;
    }

    private static boolean reusable(ComponentSnapshot snapshot, Component component,
                                    String fingerprint, String solverContract) {
        if (snapshot == null || snapshot.formatVersion() != CACHE_VERSION
                || !component.key().equals(snapshot.key())
                || !fingerprint.equals(snapshot.fingerprint())
                || !solverContract.equals(snapshot.solverContract())
                || !snapshot.converged()) {
            return false;
        }
        return validScores(component, snapshot.scores());
    }

    private static boolean reusableResult(Component component, GraphPslProgramBuilder builder,
                                          boolean groundingTruncated,
                                          HlMrfMapInference.Result inference,
                                          Map<String, Double> scores) {
        if (inference == null || !inference.converged() || inference.groundRules().isEmpty()
                || containsNonFinite(inference.values()) || groundingTruncated) {
            return false;
        }
        for (GraphEntity entity : component.entities()) {
            String constant = builder.entityIdToConstant().get(entity.id());
            Double value = constant == null ? null
                    : inference.values().get(GraphPslProgramBuilder.STATE + "(" + constant + ")");
            if (value == null || !Double.isFinite(value) || value < 0.0 || value > 1.0) {
                return false;
            }
        }
        return validScores(component, scores);
    }

    private static boolean validScores(Component component, Map<String, Double> scores) {
        if (scores == null || scores.size() != component.entities().size()) {
            return false;
        }
        Set<String> expected = new LinkedHashSet<>();
        for (GraphEntity entity : component.entities()) {
            expected.add(entity.id());
            Double value = scores.get(entity.id());
            if (value == null || !Double.isFinite(value) || value < 0.0 || value > 1.0) {
                return false;
            }
        }
        return scores.keySet().equals(expected);
    }

    private static boolean containsNonFinite(Map<String, Double> values) {
        if (values == null) return true;
        for (Double value : values.values()) {
            if (value == null || !Double.isFinite(value)) return true;
        }
        return false;
    }

    private static List<Component> components(UnifiedGraph graph) {
        List<GraphEntity> entities = new ArrayList<>(graph.entities());
        entities.sort(Comparator.comparing(GraphEntity::id));
        Map<String, Integer> indexById = new LinkedHashMap<>();
        Map<String, Integer> degrees = new LinkedHashMap<>();
        for (int i = 0; i < entities.size(); i++) {
            indexById.put(entities.get(i).id(), i);
            degrees.put(entities.get(i).id(), 0);
        }
        DisjointSet union = new DisjointSet(entities.size());
        List<GraphRelation> effectiveRelations = new ArrayList<>();

        // One graph-wide edge pass establishes both connectivity and the exact degree contribution
        // used by GraphPslProgramBuilder. Component construction below only groups these rows; it
        // never rescans the whole graph for each component.
        for (GraphRelation relation : graph.relations()) {
            if (!eligible(relation)) continue;
            Integer source = indexById.get(relation.sourceId());
            Integer target = indexById.get(relation.targetId());
            boolean sourcePresent = source != null;
            boolean targetPresent = target != null;
            boolean reverse = !relation.directed() || isSymmetricType(relation.type());

            if (sourcePresent) {
                degrees.merge(relation.sourceId(), 1, Integer::sum);
            }
            if (reverse && targetPresent && !Objects.equals(relation.sourceId(), relation.targetId())) {
                degrees.merge(relation.targetId(), 1, Integer::sum);
            }
            if (sourcePresent && targetPresent) {
                union.union(source, target);
                effectiveRelations.add(relation);
            } else if (sourcePresent || (reverse && targetPresent)) {
                // Preserve only dangling relations that can affect a builder prior. A directed edge
                // with a missing source has no observable effect and need not enter any component.
                effectiveRelations.add(relation);
            }
        }

        Map<Integer, List<GraphEntity>> entitiesByRoot = new LinkedHashMap<>();
        for (GraphEntity entity : entities) {
            int root = union.find(indexById.get(entity.id()));
            entitiesByRoot.computeIfAbsent(root, ignored -> new ArrayList<>()).add(entity);
        }
        Map<Integer, List<GraphRelation>> relationsByRoot = new LinkedHashMap<>();
        for (GraphRelation relation : effectiveRelations) {
            Integer endpoint = indexById.get(relation.sourceId());
            boolean reverse = !relation.directed() || isSymmetricType(relation.type());
            if (endpoint == null && reverse) endpoint = indexById.get(relation.targetId());
            if (endpoint != null) {
                int root = union.find(endpoint);
                relationsByRoot.computeIfAbsent(root, ignored -> new ArrayList<>()).add(relation);
            }
        }

        List<Component> result = new ArrayList<>(entitiesByRoot.size());
        for (Map.Entry<Integer, List<GraphEntity>> entry : entitiesByRoot.entrySet()) {
            List<GraphEntity> componentEntities = entry.getValue();
            componentEntities.sort(Comparator.comparing(GraphEntity::id));
            List<GraphRelation> componentRelations = new ArrayList<>(
                    relationsByRoot.getOrDefault(entry.getKey(), List.of()));
            componentRelations.sort(RELATION_ORDER);
            String key = componentKey(componentEntities);
            Map<String, Integer> componentDegrees = new LinkedHashMap<>();
            for (GraphEntity entity : componentEntities) {
                componentDegrees.put(entity.id(), degrees.getOrDefault(entity.id(), 0));
            }
            result.add(new Component(key, componentEntities, componentRelations, componentDegrees));
        }
        result.sort(Comparator.comparing(Component::key));
        return result;
    }

    private static boolean eligible(GraphRelation relation) {
        if (GraphPslProgramBuilder.isIdentitySeparationType(relation.type())) return false;
        return clamp01(relation.weight() * relation.confidence()) >= DEFAULT_MIN_EDGE_WEIGHT;
    }

    private static String componentKey(List<GraphEntity> entities) {
        StringBuilder key = new StringBuilder("component:");
        for (GraphEntity entity : entities) {
            appendLengthPrefixed(key, entity.id());
        }
        return key.toString();
    }

    private static String ruleContract(UnifiedGraph graph) {
        StringBuilder contract = new StringBuilder(CONTRACT_VERSION);
        appendLengthPrefixed(contract, String.valueOf(HlMrfMapInference.DEFAULT_MAX_ITERATIONS));
        appendLengthPrefixed(contract, Double.toString(HlMrfMapInference.DEFAULT_TOLERANCE));
        appendLengthPrefixed(contract, Double.toString(HlMrfMapInference.DEFAULT_HARD_WEIGHT));
        appendLengthPrefixed(contract, Double.toString(DEFAULT_MIN_EDGE_WEIGHT));
        appendLengthPrefixed(contract, Double.toString(DEFAULT_PROPAGATION_WEIGHT));
        appendLengthPrefixed(contract, Double.toString(DEFAULT_ABDUCTION_WEIGHT));
        appendLengthPrefixed(contract, Double.toString(DEFAULT_PRIOR_WEIGHT));
        appendLengthPrefixed(contract, Double.toString(DEFAULT_CONFLICT_WEIGHT));
        DEFAULT_RULE_TEMPLATES.forEach(rule -> appendLengthPrefixed(contract, rule));
        Map<String, Double> effectiveWeights = PslWeightLearningService.parseWeights(
                graph.artifactText(UnifiedGraphReasoningLifecycle.PSL_WEIGHTS_ARTIFACT));
        effectiveWeights.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(weight -> {
                    appendLengthPrefixed(contract, weight.getKey());
                    appendLengthPrefixed(contract, Long.toUnsignedString(
                            Double.doubleToLongBits(weight.getValue())));
                });
        return contract.toString();
    }

    private static String solverContract(int groundRuleCount, int atomCount) {
        String solver = HlMrfMapInference.chooseSolver(groundRuleCount, atomCount)
                .getClass().getName();
        return CONTRACT_VERSION + ":" + solver + ":ground=" + groundRuleCount
                + ":atoms=" + atomCount;
    }

    private static String fingerprint(Component component,
                                      String ruleContract, String solverContract) {
        try {
            MessageDigest digest = MessageDigest.getInstance(FINGERPRINT_ALGORITHM);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(CACHE_VERSION);
                writeString(out, ruleContract);
                writeString(out, solverContract);
                writeString(out, component.key());
                out.writeInt(component.entities().size());
                for (GraphEntity entity : component.entities()) {
                    writeString(out, entity.id());
                    writeString(out, entity.type());
                    writeDouble(out, entity.weight());
                    writeDouble(out, entity.confidence());
                    out.writeInt(component.degrees().getOrDefault(entity.id(), 0));
                    List<String> memberships = new ArrayList<>(entity.typeMemberships());
                    Collections.sort(memberships);
                    out.writeInt(memberships.size());
                    for (String membership : memberships) writeString(out, membership);
                }
                List<GraphRelation> relations = new ArrayList<>(component.relations());
                relations.sort(RELATION_ORDER);
                out.writeInt(relations.size());
                for (GraphRelation relation : relations) {
                    writeString(out, relation.id());
                    writeString(out, relation.sourceId());
                    writeString(out, relation.targetId());
                    writeString(out, relation.type());
                    writeDouble(out, relation.weight());
                    writeDouble(out, relation.confidence());
                    out.writeBoolean(relation.directed());
                }
                out.flush();
            }
            return hex(digest.digest(bytes.toByteArray()));
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("Unable to fingerprint incremental graph-PSL component", e);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(data.length);
        out.write(data);
    }

    private static void writeDouble(DataOutputStream out, double value) throws IOException {
        out.writeLong(Double.doubleToLongBits(value));
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    private static void appendLengthPrefixed(StringBuilder out, String value) {
        if (value == null) {
            out.append("-1:");
            return;
        }
        out.append(value.length()).append(':').append(value);
    }

    private static CacheSnapshot readCache(UnifiedGraph graph) {
        if (graph.artifact(CACHE_ARTIFACT) == null) {
            return CacheSnapshot.empty(false);
        }
        try {
            Object value = graph.model(CACHE_ARTIFACT);
            if (!(value instanceof CacheSnapshot snapshot) || snapshot.formatVersion() != CACHE_VERSION
                    || snapshot.components() == null) {
                return CacheSnapshot.empty(false);
            }
            return new CacheSnapshot(CACHE_VERSION, snapshot.components(), true);
        } catch (RuntimeException malformed) {
            // A corrupt, old, or manually supplied artifact is simply a cold cache. The next pass
            // replaces it with a current snapshot after solving the present components.
            return CacheSnapshot.empty(false);
        }
    }

    private static void updateMetadata(UnifiedGraph graph, int components, int reused, int solved,
                                       int nonConverged, int entries, boolean loaded) {
        long priorReuse = number(graph.meta().get(META_TOTAL_REUSED_COMPONENT_COUNT));
        long priorSolve = number(graph.meta().get(META_TOTAL_SOLVED_COMPONENT_COUNT));
        graph.meta(META_COMPONENT_COUNT, components)
                .meta(META_REUSED_COMPONENT_COUNT, reused)
                .meta(META_SOLVED_COMPONENT_COUNT, solved)
                .meta(META_NON_CONVERGED_COMPONENT_COUNT, nonConverged)
                .meta(META_REUSE_COUNT, reused)
                .meta(META_SOLVE_COUNT, solved)
                .meta(META_TOTAL_REUSED_COMPONENT_COUNT, priorReuse + reused)
                .meta(META_TOTAL_SOLVED_COMPONENT_COUNT, priorSolve + solved)
                .meta(META_CACHE_ENTRIES, entries)
                .meta(META_CACHE_LOADED, loaded)
                .meta(META_STATUS, nonConverged == 0 ? "COMPLETED" : "NON_CONVERGED");
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static boolean isSymmetricType(String type) {
        String normalized = type == null ? "" : type.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.equals("SAME_AS")
                || normalized.equals("EQUIVALENT_TO")
                || normalized.equals("SIMILAR_TO")
                || normalized.equals("COREFERS_TO")
                || normalized.equals("SAME_ENTITY");
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static final Comparator<GraphRelation> RELATION_ORDER = Comparator
            .comparing(GraphRelation::id)
            .thenComparing(GraphRelation::sourceId)
            .thenComparing(GraphRelation::targetId)
            .thenComparing(GraphRelation::type)
            .thenComparingDouble(GraphRelation::weight)
            .thenComparingDouble(GraphRelation::confidence)
            .thenComparing(GraphRelation::directed);

    private record Component(String key, List<GraphEntity> entities, List<GraphRelation> relations,
                             Map<String, Integer> degrees) {
        private Component {
            entities = List.copyOf(entities);
            relations = List.copyOf(relations);
            degrees = Map.copyOf(degrees);
        }
    }

    private record ComponentSnapshot(int formatVersion, String key, String fingerprint,
                                     String solverContract, int atomCount, int groundRuleCount,
                                     boolean converged, Map<String, Double> scores)
            implements Serializable {
        private static final long serialVersionUID = 1L;

        private ComponentSnapshot {
            atomCount = Math.max(0, atomCount);
            groundRuleCount = Math.max(0, groundRuleCount);
            scores = scores == null ? Map.of() : new LinkedHashMap<>(scores);
        }
    }

    private record CacheSnapshot(int formatVersion, Map<String, ComponentSnapshot> components,
                                 boolean loaded) implements Serializable {
        private static final long serialVersionUID = 1L;

        private CacheSnapshot {
            components = components == null ? Map.of() : new LinkedHashMap<>(components);
        }

        private CacheSnapshot(int formatVersion, Map<String, ComponentSnapshot> components) {
            this(formatVersion, components, true);
        }

        private static CacheSnapshot empty(boolean loaded) {
            return new CacheSnapshot(CACHE_VERSION, Map.of(), loaded);
        }
    }

    private static final class DisjointSet {
        private final int[] parent;
        private final byte[] rank;

        private DisjointSet(int size) {
            parent = new int[size];
            rank = new byte[size];
            for (int i = 0; i < size; i++) parent[i] = i;
        }

        private int find(int value) {
            int root = value;
            while (parent[root] != root) root = parent[root];
            while (parent[value] != value) {
                int next = parent[value];
                parent[value] = root;
                value = next;
            }
            return root;
        }

        private void union(int left, int right) {
            int a = find(left);
            int b = find(right);
            if (a == b) return;
            if (rank[a] < rank[b]) {
                parent[a] = b;
            } else if (rank[a] > rank[b]) {
                parent[b] = a;
            } else {
                parent[b] = a;
                rank[a]++;
            }
        }
    }
}
