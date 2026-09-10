/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.graph.reasoning.lifecycle;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.unified.Dtype;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.VectorLayer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Portable TransE/RotatE learning over a {@link UnifiedGraph}.
 *
 * <p>The implementation deliberately depends only on the graph-reasoning model. It is used by the
 * bounded learning child process and by the explicit development fallback, so both execution modes
 * produce exactly the same vector layers and model artifact. Compatible entity and relation vectors
 * are warm-started; an algorithm or dimension change is a deliberate cold start.</p>
 */
public final class UnifiedGraphKgeLifecycle {
    public static final String ENTITY_LAYER = "kge";
    public static final String RELATION_LAYER = "kge-relations";
    public static final String MODEL_ARTIFACT = "models/kge.json";

    private static final int MAX_DIM = 256;
    private static final int MAX_EPOCHS = 500;
    private static final Pattern JSON_STRING = Pattern.compile(
            "\\\"%s\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
    private static final Pattern JSON_INTEGER = Pattern.compile(
            "\\\"%s\\\"\\s*:\\s*(-?[0-9]+)");

    private UnifiedGraphKgeLifecycle() {
    }

    public record Config(boolean enabled,
                         String algorithm,
                         int embeddingDim,
                         int epochs,
                         double learningRate,
                         int warmStartEpochs,
                         long seed) {
        public Config {
            algorithm = algorithm == null ? "TRANSE"
                    : algorithm.trim().toUpperCase(Locale.ROOT);
            if (!Set.of("TRANSE", "ROTATE").contains(algorithm)) {
                throw new IllegalArgumentException("embedding algorithm must be TRANSE or ROTATE");
            }
            embeddingDim = Math.max(2, Math.min(MAX_DIM, embeddingDim));
            epochs = Math.max(1, Math.min(MAX_EPOCHS, epochs));
            learningRate = Math.max(0.0001, Math.min(1.0, learningRate));
            warmStartEpochs = Math.max(0, Math.min(epochs, warmStartEpochs));
        }
    }

    public record Summary(boolean enabled,
                          String jobId,
                          String algorithm,
                          int embeddingDim,
                          int epochs,
                          int entities,
                          int relationTypes,
                          boolean warmStarted,
                          double finalLoss) {
        public static Summary disabled() {
            return new Summary(false, null, null, 0, 0, 0, 0, false, 0.0);
        }
    }

    /** Learn embeddings in-place and persist their portable layers and metadata in {@code graph}. */
    public static Summary learn(UnifiedGraph graph, Config config) {
        Objects.requireNonNull(graph, "graph");
        Config effective = Objects.requireNonNull(config, "config");
        if (!effective.enabled() || graph.relations().isEmpty()) {
            return Summary.disabled();
        }

        List<GraphEntity> graphEntities = new ArrayList<>(graph.entities());
        graphEntities.sort(Comparator.comparing(GraphEntity::id));
        TreeSet<String> relationTypes = new TreeSet<>();
        graph.relations().forEach(relation -> relationTypes.add(relation.type()));

        int dim = effective.embeddingDim();
        int storedEntityDim = "ROTATE".equals(effective.algorithm()) ? dim * 2 : dim;
        String priorModel = graph.artifactText(MODEL_ARTIFACT);
        boolean compatibleModel = effective.algorithm().equalsIgnoreCase(
                jsonString(priorModel, "algorithm"))
                && dim == jsonInt(priorModel, "embeddingDim", -1);
        VectorLayer priorEntities = compatibleModel ? graph.vectorLayer(ENTITY_LAYER) : null;
        VectorLayer priorRelations = compatibleModel ? graph.vectorLayer(RELATION_LAYER) : null;
        Random random = new Random(effective.seed());
        boolean warmStarted = false;

        Map<String, double[]> entityVectors = new LinkedHashMap<>();
        for (GraphEntity entity : graphEntities) {
            double[] prior = priorEntities != null ? priorEntities.get(entity.id()) : null;
            if (prior != null && prior.length == storedEntityDim) {
                entityVectors.put(entity.id(), prior.clone());
                warmStarted = true;
            } else {
                entityVectors.put(entity.id(), randomUnit(random, storedEntityDim));
            }
        }

        Map<String, double[]> relationVectors = new LinkedHashMap<>();
        for (String type : relationTypes) {
            double[] prior = priorRelations != null ? priorRelations.get(type) : null;
            if (prior != null && prior.length == dim) {
                relationVectors.put(type, prior.clone());
                warmStarted = true;
            } else {
                relationVectors.put(type, randomUnit(random, dim));
            }
        }

        int epochs = warmStarted ? effective.warmStartEpochs() : effective.epochs();
        if (epochs > 0) {
            if ("ROTATE".equals(effective.algorithm())) {
                trainRotate(graph, entityVectors, relationVectors, dim, epochs,
                        effective.learningRate());
            } else {
                trainTranse(graph, entityVectors, relationVectors, dim, epochs,
                        effective.learningRate());
            }
        }

        // UnifiedGraph.addEntity deliberately clears entity-scoped analysis state. Snapshot every
        // unrelated entity layer and opinion so updating the primary/KGE embedding cannot erase
        // sentence vectors, learned features, or epistemic evidence.
        Map<String, VectorLayer> preservedEntityLayers = new LinkedHashMap<>();
        for (VectorLayer layer : graph.vectorLayers().values()) {
            if (layer.target() != VectorLayer.Target.ENTITY || ENTITY_LAYER.equals(layer.name())) {
                continue;
            }
            VectorLayer copy = new VectorLayer(layer.name(), layer.target(), layer.dim(), layer.dtype());
            layer.rows().forEach((id, vector) -> copy.put(id, vector.clone()));
            preservedEntityLayers.put(copy.name(), copy);
        }
        Map<String, Opinion> preservedEntityOpinions =
                new LinkedHashMap<>(graph.entityOpinions());

        for (GraphEntity entity : graphEntities) {
            graph.addEntity(GraphEntity.builder(entity.id())
                    .type(entity.type())
                    .label(entity.label())
                    .weight(entity.weight())
                    .confidence(entity.confidence())
                    .tags(entity.tags())
                    .embedding(entityVectors.get(entity.id()))
                    .timestamp(entity.timestamp())
                    .attributes(entity.attributes())
                    .build());
        }
        preservedEntityLayers.values().forEach(graph::putVectorLayer);
        preservedEntityOpinions.forEach(graph::putEntityOpinion);
        // Replacing an entity intentionally clears entity-scoped analysis rows. Install the KGE
        // layers after updating primary embeddings so those freshly learned rows remain present.
        VectorLayer entityLayer = new VectorLayer(ENTITY_LAYER, VectorLayer.Target.ENTITY,
                storedEntityDim, Dtype.F32);
        entityVectors.forEach(entityLayer::put);
        VectorLayer relationLayer = new VectorLayer(RELATION_LAYER, VectorLayer.Target.GLOBAL,
                dim, Dtype.F32);
        relationVectors.forEach(relationLayer::put);
        graph.putVectorLayer(entityLayer).putVectorLayer(relationLayer);

        double finalLoss = averageLoss(graph, effective.algorithm(), dim,
                entityVectors, relationVectors);
        String jobId = "local-kge-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 16);
        String trainedAt = Instant.now().toString();
        graph.putArtifactText(MODEL_ARTIFACT, modelJson(jobId, effective, epochs,
                entityVectors.size(), relationVectors.size(), warmStarted, finalLoss, trainedAt));
        graph.meta("embeddingAlgorithm", effective.algorithm())
                .meta("embeddingDim", dim)
                .meta("embeddingEpochs", epochs)
                .meta("embeddingWarmStarted", warmStarted)
                .meta("embeddingUpdatedAt", trainedAt)
                .meta("phase.embeddingLearning", "COMPLETED");
        return new Summary(true, jobId, effective.algorithm(), dim, epochs,
                entityVectors.size(), relationVectors.size(), warmStarted, finalLoss);
    }

    private static void trainTranse(UnifiedGraph graph,
                                    Map<String, double[]> entities,
                                    Map<String, double[]> relations,
                                    int dim,
                                    int epochs,
                                    double learningRate) {
        for (int epoch = 0; epoch < epochs; epoch++) {
            Map<String, double[]> relationSums = zeroRows(relations.keySet(), dim);
            Map<String, Integer> relationCounts = new HashMap<>();
            for (GraphRelation relation : graph.relations()) {
                double[] head = entities.get(relation.sourceId());
                double[] tail = entities.get(relation.targetId());
                if (head == null || tail == null) continue;
                double[] sum = relationSums.get(relation.type());
                for (int i = 0; i < dim; i++) sum[i] += tail[i] - head[i];
                relationCounts.merge(relation.type(), 1, Integer::sum);
            }
            for (Map.Entry<String, double[]> entry : relations.entrySet()) {
                int count = relationCounts.getOrDefault(entry.getKey(), 0);
                if (count > 0) blend(entry.getValue(), relationSums.get(entry.getKey()), count,
                        learningRate);
            }
            for (GraphRelation relation : graph.relations()) {
                double[] head = entities.get(relation.sourceId());
                double[] tail = entities.get(relation.targetId());
                double[] rel = relations.get(relation.type());
                if (head == null || tail == null || rel == null) continue;
                for (int i = 0; i < dim; i++) {
                    double error = head[i] + rel[i] - tail[i];
                    tail[i] += learningRate * error * 0.5;
                    head[i] -= learningRate * error * 0.5;
                }
                normalize(head);
                normalize(tail);
            }
        }
    }

    private static void trainRotate(UnifiedGraph graph,
                                    Map<String, double[]> entities,
                                    Map<String, double[]> relations,
                                    int dim,
                                    int epochs,
                                    double learningRate) {
        Map<String, double[]> angles = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> entry : entities.entrySet()) {
            double[] angle = new double[dim];
            double[] vector = entry.getValue();
            for (int i = 0; i < dim; i++) angle[i] = Math.atan2(vector[i + dim], vector[i]);
            angles.put(entry.getKey(), angle);
        }
        for (int epoch = 0; epoch < epochs; epoch++) {
            Map<String, double[]> sine = zeroRows(relations.keySet(), dim);
            Map<String, double[]> cosine = zeroRows(relations.keySet(), dim);
            for (GraphRelation relation : graph.relations()) {
                double[] head = angles.get(relation.sourceId());
                double[] tail = angles.get(relation.targetId());
                if (head == null || tail == null) continue;
                for (int i = 0; i < dim; i++) {
                    double delta = tail[i] - head[i];
                    sine.get(relation.type())[i] += Math.sin(delta);
                    cosine.get(relation.type())[i] += Math.cos(delta);
                }
            }
            for (Map.Entry<String, double[]> entry : relations.entrySet()) {
                for (int i = 0; i < dim; i++) {
                    double target = Math.atan2(sine.get(entry.getKey())[i],
                            cosine.get(entry.getKey())[i]);
                    entry.getValue()[i] = circularBlend(entry.getValue()[i], target, learningRate);
                }
            }
            for (GraphRelation relation : graph.relations()) {
                double[] head = angles.get(relation.sourceId());
                double[] tail = angles.get(relation.targetId());
                double[] phase = relations.get(relation.type());
                if (head == null || tail == null || phase == null) continue;
                for (int i = 0; i < dim; i++) {
                    tail[i] = circularBlend(tail[i], head[i] + phase[i], learningRate * 0.5);
                    head[i] = circularBlend(head[i], tail[i] - phase[i], learningRate * 0.5);
                }
            }
        }
        for (Map.Entry<String, double[]> entry : entities.entrySet()) {
            double[] angle = angles.get(entry.getKey());
            double[] vector = entry.getValue();
            for (int i = 0; i < dim; i++) {
                vector[i] = Math.cos(angle[i]);
                vector[i + dim] = Math.sin(angle[i]);
            }
        }
    }

    private static double averageLoss(UnifiedGraph graph,
                                      String algorithm,
                                      int dim,
                                      Map<String, double[]> entities,
                                      Map<String, double[]> relations) {
        double total = 0.0;
        int count = 0;
        for (GraphRelation relation : graph.relations()) {
            double[] head = entities.get(relation.sourceId());
            double[] rel = relations.get(relation.type());
            double[] tail = entities.get(relation.targetId());
            if (head == null || rel == null || tail == null) continue;
            double squared = 0.0;
            if ("ROTATE".equals(algorithm)) {
                for (int i = 0; i < dim; i++) {
                    double cos = Math.cos(rel[i]);
                    double sin = Math.sin(rel[i]);
                    double real = head[i] * cos - head[i + dim] * sin - tail[i];
                    double imaginary = head[i] * sin + head[i + dim] * cos - tail[i + dim];
                    squared += real * real + imaginary * imaginary;
                }
            } else {
                for (int i = 0; i < dim; i++) {
                    double error = head[i] + rel[i] - tail[i];
                    squared += error * error;
                }
            }
            total += Math.sqrt(squared);
            count++;
        }
        return count == 0 ? 0.0 : total / count;
    }

    private static Map<String, double[]> zeroRows(Iterable<String> keys, int dim) {
        Map<String, double[]> rows = new LinkedHashMap<>();
        keys.forEach(key -> rows.put(key, new double[dim]));
        return rows;
    }

    private static void blend(double[] current, double[] sum, int count, double rate) {
        for (int i = 0; i < current.length; i++) {
            current[i] = current[i] * (1.0 - rate) + (sum[i] / count) * rate;
        }
        normalize(current);
    }

    private static double[] randomUnit(Random random, int dim) {
        double[] vector = new double[dim];
        for (int i = 0; i < dim; i++) vector[i] = random.nextDouble() * 2.0 - 1.0;
        normalize(vector);
        return vector;
    }

    private static void normalize(double[] vector) {
        double norm = 0.0;
        for (double value : vector) norm += value * value;
        norm = Math.sqrt(norm);
        if (norm == 0.0) return;
        for (int i = 0; i < vector.length; i++) vector[i] /= norm;
    }

    private static double circularBlend(double current, double target, double rate) {
        double delta = Math.atan2(Math.sin(target - current), Math.cos(target - current));
        return current + rate * delta;
    }

    private static String modelJson(String jobId,
                                    Config config,
                                    int effectiveEpochs,
                                    int entities,
                                    int relations,
                                    boolean warmStarted,
                                    double finalLoss,
                                    String trainedAt) {
        return String.format(Locale.ROOT, """
                {"jobId":"%s","status":"COMPLETED","algorithm":"%s",\
                "embeddingDim":%d,"epochs":%d,"configuredEpochs":%d,"warmStartEpochs":%d,\
                "learningRate":%.12f,"entities":%d,"relationTypes":%d,"warmStarted":%s,\
                "finalLoss":%.12f,"trainedAt":"%s","backend":"project-local"}
                """, escape(jobId), escape(config.algorithm()), config.embeddingDim(),
                effectiveEpochs, config.epochs(), config.warmStartEpochs(), config.learningRate(),
                entities, relations, warmStarted, finalLoss, escape(trainedAt)).replace("\n", "");
    }

    private static String jsonString(String json, String field) {
        if (json == null || json.isBlank()) return null;
        Matcher matcher = Pattern.compile(String.format(JSON_STRING.pattern(),
                Pattern.quote(field))).matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static int jsonInt(String json, String field, int fallback) {
        if (json == null || json.isBlank()) return fallback;
        Matcher matcher = Pattern.compile(String.format(JSON_INTEGER.pattern(),
                Pattern.quote(field))).matcher(json);
        if (!matcher.find()) return fallback;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
