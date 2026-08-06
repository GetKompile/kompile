/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.matrix.gnn;

import ai.kompile.knowledgegraph.domain.GraphEdge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Small, bounded, CPU-only message-passing link predictor used by the crawl GNN stage.
 *
 * <p>The encoder is a trainable diagonal message-passing layer:
 * {@code h(v)=tanh(wSelf*x(v) + wNeighbor*mean(neighbors(v)) + bias)}. A trainable,
 * direction-sensitive decoder scores source, target, and pairwise interaction features. Existing
 * graph edges are positive examples; deterministic corruptions that are absent from the graph are
 * negatives. All parameters are ordinary on-heap arrays, so closing a model cannot retain an ND4J
 * workspace, native backend, model server, thread, or GPU allocation.</p>
 */
final class TrainableMessagePassingLinkModel implements AutoCloseable {

    private static final double EPSILON = 1.0e-12;
    private static final int RELATION_BUCKETS = 32;
    private static final double GRADIENT_CLIP = 5.0;
    private static final double PARAMETER_CLIP = 4.0;

    private final Map<String, double[]> baseFeatures;
    private final Map<String, double[]> neighborFeatures;
    private final int dimension;

    private final double[] selfScale;
    private final double[] neighborScale;
    private final double[] encoderBias;
    private final double[] sourceDecoder;
    private final double[] targetDecoder;
    private final double[] interactionDecoder;
    private final double[] relationDecoder;
    private double decoderBias;
    private boolean closed;

    private TrainableMessagePassingLinkModel(
            Map<String, double[]> baseFeatures,
            Map<String, double[]> neighborFeatures,
            GraphNeuralScoringService.TrainingConfig config) {
        this.baseFeatures = copyFeatureMap(baseFeatures);
        this.neighborFeatures = copyFeatureMap(neighborFeatures);
        this.dimension = baseFeatures.values().iterator().next().length;
        this.selfScale = new double[dimension];
        this.neighborScale = new double[dimension];
        this.encoderBias = new double[dimension];
        this.sourceDecoder = new double[dimension];
        this.targetDecoder = new double[dimension];
        this.interactionDecoder = new double[dimension];
        this.relationDecoder = new double[RELATION_BUCKETS];

        Random random = new Random(config.seed());
        for (int i = 0; i < dimension; i++) {
            selfScale[i] = config.selfWeight() + random.nextGaussian() * 0.01;
            neighborScale[i] = config.neighborWeight() + random.nextGaussian() * 0.01;
            sourceDecoder[i] = random.nextGaussian() * 0.05;
            targetDecoder[i] = random.nextGaussian() * 0.05;
            interactionDecoder[i] = random.nextGaussian() * 0.05;
        }
        for (int i = 0; i < relationDecoder.length; i++) {
            relationDecoder[i] = random.nextGaussian() * 0.01;
        }
    }

    static TrainingAttempt train(
            Map<String, double[]> baseFeatures,
            List<GraphEdge> graphEdges,
            GraphNeuralScoringService.TrainingConfig config) {
        if (baseFeatures == null || baseFeatures.size() < 2) {
            return TrainingAttempt.skipped("at least two feature-bearing nodes are required");
        }

        List<String> nodeIds = new ArrayList<>(baseFeatures.keySet());
        nodeIds.sort(String::compareTo);
        List<EdgeExample> positives = positiveExamples(graphEdges, baseFeatures.keySet());
        if (positives.isEmpty()) {
            return TrainingAttempt.skipped("no resolvable positive edges are available for training");
        }

        Collections.shuffle(positives, new Random(config.seed()));
        if (positives.size() > config.maxPositiveEdges()) {
            positives = new ArrayList<>(positives.subList(0, config.maxPositiveEdges()));
        }

        int validationPositiveCount = positives.size() > 1
                ? Math.max(1, Math.min(positives.size() / 5, positives.size() - 1))
                : 0;
        List<EdgeExample> validationPositives = validationPositiveCount == 0
                ? List.of()
                : List.copyOf(positives.subList(0, validationPositiveCount));
        List<EdgeExample> trainingPositives = validationPositiveCount == 0
                ? List.copyOf(positives)
                : List.copyOf(positives.subList(validationPositiveCount, positives.size()));

        Set<String> existingPairs = existingPairKeys(graphEdges);
        Random negativeRandom = new Random(config.seed() ^ 0x9E3779B97F4A7C15L);
        List<EdgeExample> trainingNegatives = negativeExamples(
                trainingPositives, nodeIds, existingPairs, config.negativeSamplesPerPositive(), negativeRandom);
        List<EdgeExample> validationNegatives = negativeExamples(
                validationPositives, nodeIds, existingPairs, config.negativeSamplesPerPositive(), negativeRandom);
        if (trainingNegatives.isEmpty()) {
            return TrainingAttempt.skipped("graph has no absent node pairs for negative supervision");
        }

        Map<String, double[]> neighborFeatures = aggregateNeighbors(baseFeatures, trainingPositives);
        TrainableMessagePassingLinkModel model = new TrainableMessagePassingLinkModel(
                baseFeatures, neighborFeatures, config);

        List<EdgeExample> trainingExamples = new ArrayList<>(
                trainingPositives.size() + trainingNegatives.size());
        trainingExamples.addAll(trainingPositives);
        trainingExamples.addAll(trainingNegatives);

        double initialLoss = model.meanLoss(trainingExamples);
        Parameters best = model.snapshot();
        double bestLoss = initialLoss;
        Random epochRandom = new Random(config.seed() ^ 0xD1B54A32D192ED03L);
        for (int epoch = 0; epoch < config.epochs(); epoch++) {
            Collections.shuffle(trainingExamples, epochRandom);
            double learningRate = config.learningRate() / Math.sqrt(1.0 + epoch * 0.05);
            for (EdgeExample example : trainingExamples) {
                model.trainExample(example, learningRate, config.l2());
            }
            double epochLoss = model.meanLoss(trainingExamples);
            if (Double.isFinite(epochLoss) && epochLoss < bestLoss) {
                bestLoss = epochLoss;
                best = model.snapshot();
            }
        }
        model.restore(best);

        double finalLoss = model.meanLoss(trainingExamples);
        double validationPositiveMean = model.meanScore(validationPositives);
        double validationNegativeMean = model.meanScore(validationNegatives);
        Diagnostics diagnostics = new Diagnostics(
                trainingExamples.size(),
                validationPositives.size() + validationNegatives.size(),
                initialLoss,
                finalLoss,
                validationPositiveMean,
                validationNegativeMean,
                model.fingerprint());
        return new TrainingAttempt(model, diagnostics, "ok");
    }

    double score(GraphEdge edge) {
        ensureOpen();
        if (edge == null) {
            return 0.5;
        }
        return score(new EdgeExample(
                edge.getSourceNodeId(), edge.getTargetNodeId(), relation(edge), 1.0));
    }

    private double score(EdgeExample example) {
        double[] source = encoded(example.sourceId());
        double[] target = encoded(example.targetId());
        if (source == null || target == null) {
            return 0.5;
        }
        return sigmoid(logit(source, target, example.relation()));
    }

    private void trainExample(EdgeExample example, double learningRate, double l2) {
        double[] sourceBase = baseFeatures.get(example.sourceId());
        double[] targetBase = baseFeatures.get(example.targetId());
        if (sourceBase == null || targetBase == null) {
            return;
        }
        double[] sourceNeighbor = neighborFeatures.get(example.sourceId());
        double[] targetNeighbor = neighborFeatures.get(example.targetId());
        double[] source = encoded(sourceBase, sourceNeighbor);
        double[] target = encoded(targetBase, targetNeighbor);
        double scale = 1.0 / Math.sqrt(dimension);
        double prediction = sigmoid(logit(source, target, example.relation()));
        double error = prediction - example.label();

        double[] sourcePreGradient = new double[dimension];
        double[] targetPreGradient = new double[dimension];
        for (int i = 0; i < dimension; i++) {
            double sourceGradient = error * scale
                    * (sourceDecoder[i] + interactionDecoder[i] * target[i]);
            double targetGradient = error * scale
                    * (targetDecoder[i] + interactionDecoder[i] * source[i]);
            sourcePreGradient[i] = sourceGradient * (1.0 - source[i] * source[i]);
            targetPreGradient[i] = targetGradient * (1.0 - target[i] * target[i]);
        }

        for (int i = 0; i < dimension; i++) {
            sourceDecoder[i] = update(sourceDecoder[i],
                    error * scale * source[i] + l2 * sourceDecoder[i], learningRate);
            targetDecoder[i] = update(targetDecoder[i],
                    error * scale * target[i] + l2 * targetDecoder[i], learningRate);
            interactionDecoder[i] = update(interactionDecoder[i],
                    error * scale * source[i] * target[i] + l2 * interactionDecoder[i], learningRate);

            double sourceNeighborValue = sourceNeighbor == null ? 0.0 : sourceNeighbor[i];
            double targetNeighborValue = targetNeighbor == null ? 0.0 : targetNeighbor[i];
            selfScale[i] = update(selfScale[i],
                    sourcePreGradient[i] * sourceBase[i]
                            + targetPreGradient[i] * targetBase[i]
                            + l2 * selfScale[i], learningRate);
            neighborScale[i] = update(neighborScale[i],
                    sourcePreGradient[i] * sourceNeighborValue
                            + targetPreGradient[i] * targetNeighborValue
                            + l2 * neighborScale[i], learningRate);
            encoderBias[i] = update(encoderBias[i],
                    sourcePreGradient[i] + targetPreGradient[i], learningRate);
        }

        RelationBucket relationBucket = relationBucket(example.relation());
        relationDecoder[relationBucket.index()] = update(
                relationDecoder[relationBucket.index()],
                error * relationBucket.sign() + l2 * relationDecoder[relationBucket.index()],
                learningRate);
        decoderBias = update(decoderBias, error, learningRate);
    }

    private double[] encoded(String nodeId) {
        double[] base = baseFeatures.get(nodeId);
        return base == null ? null : encoded(base, neighborFeatures.get(nodeId));
    }

    private double[] encoded(double[] base, double[] neighbor) {
        double[] encoded = new double[dimension];
        for (int i = 0; i < dimension; i++) {
            double neighborValue = neighbor == null ? 0.0 : neighbor[i];
            encoded[i] = Math.tanh(
                    selfScale[i] * base[i] + neighborScale[i] * neighborValue + encoderBias[i]);
        }
        return encoded;
    }

    private double logit(double[] source, double[] target, String relation) {
        double scale = 1.0 / Math.sqrt(dimension);
        double value = decoderBias;
        for (int i = 0; i < dimension; i++) {
            value += scale * (sourceDecoder[i] * source[i]
                    + targetDecoder[i] * target[i]
                    + interactionDecoder[i] * source[i] * target[i]);
        }
        RelationBucket relationBucket = relationBucket(relation);
        value += relationBucket.sign() * relationDecoder[relationBucket.index()];
        return value;
    }

    private double meanLoss(List<EdgeExample> examples) {
        if (examples == null || examples.isEmpty()) {
            return Double.NaN;
        }
        double loss = 0.0;
        int count = 0;
        for (EdgeExample example : examples) {
            double prediction = score(example);
            double bounded = Math.max(1.0e-9, Math.min(1.0 - 1.0e-9, prediction));
            loss += -example.label() * Math.log(bounded)
                    - (1.0 - example.label()) * Math.log(1.0 - bounded);
            count++;
        }
        return count == 0 ? Double.NaN : loss / count;
    }

    private double meanScore(List<EdgeExample> examples) {
        if (examples == null || examples.isEmpty()) {
            return Double.NaN;
        }
        return examples.stream().mapToDouble(this::score).average().orElse(Double.NaN);
    }

    private Parameters snapshot() {
        return new Parameters(
                selfScale.clone(), neighborScale.clone(), encoderBias.clone(),
                sourceDecoder.clone(), targetDecoder.clone(), interactionDecoder.clone(),
                relationDecoder.clone(), decoderBias);
    }

    private void restore(Parameters parameters) {
        System.arraycopy(parameters.selfScale(), 0, selfScale, 0, dimension);
        System.arraycopy(parameters.neighborScale(), 0, neighborScale, 0, dimension);
        System.arraycopy(parameters.encoderBias(), 0, encoderBias, 0, dimension);
        System.arraycopy(parameters.sourceDecoder(), 0, sourceDecoder, 0, dimension);
        System.arraycopy(parameters.targetDecoder(), 0, targetDecoder, 0, dimension);
        System.arraycopy(parameters.interactionDecoder(), 0, interactionDecoder, 0, dimension);
        System.arraycopy(parameters.relationDecoder(), 0, relationDecoder, 0, relationDecoder.length);
        decoderBias = parameters.decoderBias();
    }

    private String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, selfScale);
            updateDigest(digest, neighborScale);
            updateDigest(digest, encoderBias);
            updateDigest(digest, sourceDecoder);
            updateDigest(digest, targetDecoder);
            updateDigest(digest, interactionDecoder);
            updateDigest(digest, relationDecoder);
            digest.update(Double.toHexString(decoderBias).getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", e);
        }
    }

    private static void updateDigest(MessageDigest digest, double[] values) {
        for (double value : values) {
            digest.update(Double.toHexString(value).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
    }

    private static List<EdgeExample> positiveExamples(
            List<GraphEdge> edges, Set<String> availableNodeIds) {
        if (edges == null || edges.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, EdgeExample> deduplicated = new LinkedHashMap<>();
        edges.stream()
                .filter(edge -> edge != null
                        && availableNodeIds.contains(edge.getSourceNodeId())
                        && availableNodeIds.contains(edge.getTargetNodeId()))
                .sorted(Comparator.comparing(TrainableMessagePassingLinkModel::edgeSortKey))
                .forEach(edge -> {
                    EdgeExample example = new EdgeExample(
                            edge.getSourceNodeId(), edge.getTargetNodeId(), relation(edge), 1.0);
                    deduplicated.putIfAbsent(example.key(), example);
                });
        return new ArrayList<>(deduplicated.values());
    }

    private static List<EdgeExample> negativeExamples(
            List<EdgeExample> positives,
            List<String> nodeIds,
            Set<String> existingPairs,
            int samplesPerPositive,
            Random random) {
        if (positives == null || positives.isEmpty() || nodeIds.size() < 2) {
            return List.of();
        }
        List<EdgeExample> negatives = new ArrayList<>();
        Set<String> selected = new HashSet<>();
        for (EdgeExample positive : positives) {
            for (int sample = 0; sample < samplesPerPositive; sample++) {
                EdgeExample negative = randomNegative(
                        positive, nodeIds, existingPairs, selected, random, sample);
                if (negative != null) {
                    selected.add(negative.key());
                    negatives.add(negative);
                }
            }
        }
        return List.copyOf(negatives);
    }

    private static EdgeExample randomNegative(
            EdgeExample positive,
            List<String> nodeIds,
            Set<String> existingPairs,
            Set<String> selected,
            Random random,
            int sampleIndex) {
        int attempts = Math.max(32, nodeIds.size() * 2);
        for (int attempt = 0; attempt < attempts; attempt++) {
            boolean corruptSource = ((attempt + sampleIndex) & 1) == 0;
            String source = corruptSource
                    ? nodeIds.get(random.nextInt(nodeIds.size())) : positive.sourceId();
            String target = corruptSource
                    ? positive.targetId() : nodeIds.get(random.nextInt(nodeIds.size()));
            EdgeExample candidate = new EdgeExample(source, target, positive.relation(), 0.0);
            if (validNegative(candidate, existingPairs, selected)) {
                return candidate;
            }
        }
        for (String source : nodeIds) {
            for (String target : nodeIds) {
                EdgeExample candidate = new EdgeExample(source, target, positive.relation(), 0.0);
                if (validNegative(candidate, existingPairs, selected)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static boolean validNegative(
            EdgeExample candidate, Set<String> existingPairs, Set<String> selected) {
        return candidate.sourceId() != null
                && candidate.targetId() != null
                && !candidate.sourceId().equals(candidate.targetId())
                && !existingPairs.contains(pairKey(candidate.sourceId(), candidate.targetId()))
                && !selected.contains(candidate.key());
    }

    private static Set<String> existingPairKeys(List<GraphEdge> edges) {
        Set<String> result = new HashSet<>();
        if (edges != null) {
            for (GraphEdge edge : edges) {
                if (edge != null && edge.getSourceNodeId() != null && edge.getTargetNodeId() != null) {
                    result.add(pairKey(edge.getSourceNodeId(), edge.getTargetNodeId()));
                }
            }
        }
        return result;
    }

    private static Map<String, double[]> aggregateNeighbors(
            Map<String, double[]> baseFeatures,
            List<EdgeExample> trainingEdges) {
        int dimension = baseFeatures.values().iterator().next().length;
        Map<String, double[]> sums = new LinkedHashMap<>();
        Map<String, Double> masses = new LinkedHashMap<>();
        for (String nodeId : baseFeatures.keySet()) {
            sums.put(nodeId, new double[dimension]);
            masses.put(nodeId, 0.0);
        }
        for (EdgeExample edge : trainingEdges) {
            double[] source = baseFeatures.get(edge.sourceId());
            double[] target = baseFeatures.get(edge.targetId());
            if (source == null || target == null) {
                continue;
            }
            addNeighbor(sums.get(edge.sourceId()), target, edge.relation(), "out");
            addNeighbor(sums.get(edge.targetId()), source, edge.relation(), "in");
            masses.merge(edge.sourceId(), 1.0, Double::sum);
            masses.merge(edge.targetId(), 1.0, Double::sum);
        }
        Map<String, double[]> result = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> entry : sums.entrySet()) {
            double mass = masses.getOrDefault(entry.getKey(), 0.0);
            if (mass > EPSILON) {
                for (int i = 0; i < entry.getValue().length; i++) {
                    entry.getValue()[i] /= mass;
                }
            }
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static void addNeighbor(
            double[] sum, double[] neighbor, String relation, String direction) {
        for (int i = 0; i < sum.length; i++) {
            sum[i] += neighbor[i];
        }
        String token = direction + ':' + relation;
        int hash = token.hashCode();
        int index = Math.floorMod(hash, sum.length);
        double sign = ((Integer.rotateLeft(hash, 11) & 1) == 0) ? 1.0 : -1.0;
        sum[index] += sign * 0.25;
    }

    private static Map<String, double[]> copyFeatureMap(Map<String, double[]> source) {
        Map<String, double[]> copy = new LinkedHashMap<>();
        source.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> copy.put(entry.getKey(), entry.getValue().clone()));
        return copy;
    }

    private static String relation(GraphEdge edge) {
        if (edge != null && edge.getRelationType() != null && !edge.getRelationType().isBlank()) {
            return edge.getRelationType().trim().toLowerCase(Locale.ROOT);
        }
        return edge != null && edge.getEdgeType() != null
                ? edge.getEdgeType().name().toLowerCase(Locale.ROOT) : "unknown";
    }

    private static String edgeSortKey(GraphEdge edge) {
        return String.valueOf(edge.getSourceNodeId()) + '\u0000'
                + String.valueOf(edge.getTargetNodeId()) + '\u0000'
                + relation(edge) + '\u0000' + String.valueOf(edge.getEdgeId());
    }

    private static String pairKey(String source, String target) {
        return source + '\u0000' + target;
    }

    private static RelationBucket relationBucket(String relation) {
        int hash = String.valueOf(relation).hashCode();
        int index = Math.floorMod(hash, RELATION_BUCKETS);
        double sign = ((Integer.rotateLeft(hash, 7) & 1) == 0) ? 1.0 : -1.0;
        return new RelationBucket(index, sign);
    }

    private static double update(double parameter, double gradient, double learningRate) {
        double clippedGradient = Math.max(-GRADIENT_CLIP, Math.min(GRADIENT_CLIP, gradient));
        double value = parameter - learningRate * clippedGradient;
        return Math.max(-PARAMETER_CLIP, Math.min(PARAMETER_CLIP, value));
    }

    private static double sigmoid(double value) {
        if (value >= 0.0) {
            double exp = Math.exp(-Math.min(value, 40.0));
            return 1.0 / (1.0 + exp);
        }
        double exp = Math.exp(Math.max(value, -40.0));
        return exp / (1.0 + exp);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("message-passing model is closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        baseFeatures.values().forEach(values -> Arrays.fill(values, 0.0));
        neighborFeatures.values().forEach(values -> Arrays.fill(values, 0.0));
        baseFeatures.clear();
        neighborFeatures.clear();
        Arrays.fill(selfScale, 0.0);
        Arrays.fill(neighborScale, 0.0);
        Arrays.fill(encoderBias, 0.0);
        Arrays.fill(sourceDecoder, 0.0);
        Arrays.fill(targetDecoder, 0.0);
        Arrays.fill(interactionDecoder, 0.0);
        Arrays.fill(relationDecoder, 0.0);
        decoderBias = 0.0;
    }

    record Diagnostics(
            int trainingExamples,
            int validationExamples,
            double initialLoss,
            double finalLoss,
            double validationPositiveMean,
            double validationNegativeMean,
            String fingerprint) {
    }

    record TrainingAttempt(
            TrainableMessagePassingLinkModel model,
            Diagnostics diagnostics,
            String reason) implements AutoCloseable {

        static TrainingAttempt skipped(String reason) {
            return new TrainingAttempt(null, null, reason);
        }

        boolean trained() {
            return model != null && diagnostics != null;
        }

        @Override
        public void close() {
            if (model != null) {
                model.close();
            }
        }
    }

    private record EdgeExample(String sourceId, String targetId, String relation, double label) {
        String key() {
            return sourceId + '\u0000' + targetId + '\u0000' + relation;
        }
    }

    private record RelationBucket(int index, double sign) {
    }

    private record Parameters(
            double[] selfScale,
            double[] neighborScale,
            double[] encoderBias,
            double[] sourceDecoder,
            double[] targetDecoder,
            double[] interactionDecoder,
            double[] relationDecoder,
            double decoderBias) {
    }
}
