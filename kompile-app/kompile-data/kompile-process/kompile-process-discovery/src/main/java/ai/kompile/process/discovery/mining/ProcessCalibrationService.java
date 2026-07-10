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

package ai.kompile.process.discovery.mining;

import ai.kompile.graph.reasoning.fol.grounding.PlattCalibrator;
import ai.kompile.graph.reasoning.fol.grounding.StrengthCalibrator;
import ai.kompile.graph.reasoning.synthesis.LogisticAnswerScorer;
import ai.kompile.graph.reasoning.synthesis.LogisticRegressionTrainer;
import ai.kompile.process.discovery.ProcessSuggestion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Closes the mined-suggestion learning loop: accept/dismiss decisions — which were already
 * captured in the suggestion store but fed nothing — become labeled outcomes that (re)fit the
 * Platt calibrator the miner grounds step confidences with. Before this, every discovery run used
 * an identity-sigmoid calibrator that never learned.
 *
 * <p>Durable state under {@code <dataDir>/rules/}: {@code process-calibration-outcomes.jsonl}
 * (append-only ledger: raw conformance score + 1.0/0.0 label per decision) and
 * {@code process-calibration.json} (the fitted {@code (w, b)} for
 * {@link StrengthCalibrator.SignalType#INDUCTIVE_MINER_FM}). Refit runs on every recorded outcome
 * once {@link #MIN_OUTCOMES} labels exist — Platt on two points is noise, not calibration.
 */
@Component
public class ProcessCalibrationService {

    private static final Logger log = LoggerFactory.getLogger(ProcessCalibrationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final StrengthCalibrator.SignalType SIGNAL = StrengthCalibrator.SignalType.INDUCTIVE_MINER_FM;
    /** Below this many labeled outcomes the identity calibrator is kept (no overfit on 2 points). */
    static final int MIN_OUTCOMES = 5;

    @Value("${kompile.data.dir:#{null}}")
    private String dataDir;

    /**
     * A Platt calibrator seeded from the persisted fitted parameters when available;
     * identity-sigmoid defaults otherwise (the prior behaviour, so nothing regresses).
     */
    public PlattCalibrator loadCalibrator() {
        PlattCalibrator calibrator = new PlattCalibrator();
        Path paramsFile = paramsFile();
        if (paramsFile == null || !Files.exists(paramsFile)) {
            return calibrator;
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(paramsFile, StandardCharsets.UTF_8));
            calibrator.setParams(SIGNAL, root.path("w").asDouble(1.0), root.path("b").asDouble(0.0));
        } catch (Exception e) {
            log.debug("Process calibration: could not load persisted params — using identity: {}",
                    e.getMessage());
        }
        return calibrator;
    }

    /**
     * Record an accept ({@code true}) or dismiss ({@code false}) decision for a MINED suggestion
     * and refit the calibrator from the full ledger. Non-mined suggestions and suggestions without
     * a raw conformance score are ignored — the label must pair with the signal that produced it.
     */
    public void recordOutcome(ProcessSuggestion suggestion, boolean accepted) {
        if (suggestion == null
                || suggestion.getRawConformanceScore() == null
                || !"PROCESS_MINING".equals(suggestion.getDiscoverySource())) {
            return;
        }
        Path ledger = ledgerFile();
        if (ledger == null) {
            return;
        }
        try {
            Files.createDirectories(ledger.getParent());
            com.fasterxml.jackson.databind.node.ObjectNode node = MAPPER.createObjectNode()
                    .put("rawScore", suggestion.getRawConformanceScore())
                    .put("label", accepted ? 1.0 : 0.0)
                    .put("suggestionId", suggestion.getId())
                    .put("at", Instant.now().toString());
            // The full signal-feature vector at decision time — the re-ranker's training row.
            com.fasterxml.jackson.databind.node.ArrayNode featureArray = node.putArray("features");
            for (double f : SuggestionFeatures.extract(suggestion)) {
                featureArray.add(f);
            }
            Files.writeString(ledger, node.toString() + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            refit(ledger);
            refitRanker(ledger);
        } catch (Exception e) {
            log.warn("Process calibration: could not record outcome for suggestion {} — {}",
                    suggestion.getId(), e.getMessage());
        }
    }

    private void refit(Path ledger) throws IOException {
        List<StrengthCalibrator.LabeledScore> batch = new ArrayList<>();
        for (String line : Files.readAllLines(ledger, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonNode node = MAPPER.readTree(line);
                batch.add(new StrengthCalibrator.LabeledScore(
                        node.path("rawScore").asDouble(), node.path("label").asDouble()));
            } catch (Exception ignored) {
                // malformed ledger line — skip, never fail the accept path
            }
        }
        if (batch.size() < MIN_OUTCOMES) {
            log.debug("Process calibration: {} outcome(s) recorded — refit starts at {}",
                    batch.size(), MIN_OUTCOMES);
            return;
        }
        PlattCalibrator fresh = new PlattCalibrator();
        fresh.updateFromLabeledBatch(SIGNAL, batch);
        double[] wb = fresh.getParams(SIGNAL);
        Files.writeString(paramsFile(),
                String.format(Locale.ROOT, "{\"w\":%.6f,\"b\":%.6f,\"outcomes\":%d}", wb[0], wb[1], batch.size()),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("Process calibration refit from {} outcomes: w={}, b={}", batch.size(), wb[0], wb[1]);
    }

    // ── learned re-ranker (synthesis/ logistic scorer over SuggestionFeatures) ──

    /** Both classes must be represented this many times before a ranker is fit (no one-class fits). */
    static final int MIN_PER_CLASS = 2;

    /**
     * Learned acceptance likelihood for a suggestion, or {@code null} when no ranker model exists
     * yet (not enough labeled outcomes, or the feature space changed since it was fitted).
     */
    public Double scoreSuggestion(ProcessSuggestion suggestion) {
        LogisticAnswerScorer ranker = loadRanker();
        if (ranker == null || suggestion == null) {
            return null;
        }
        return ranker.score(SuggestionFeatures.extract(suggestion));
    }

    /** The persisted ranker, or null when absent/stale (feature names no longer match). */
    LogisticAnswerScorer loadRanker() {
        Path rankerFile = rankerFile();
        if (rankerFile == null || !Files.exists(rankerFile)) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(rankerFile, StandardCharsets.UTF_8));
            JsonNode names = root.path("featureNames");
            if (names.size() != SuggestionFeatures.NAMES.size()) {
                log.info("Process ranker: feature space changed ({} vs {}) — stale model ignored",
                        names.size(), SuggestionFeatures.NAMES.size());
                return null;
            }
            for (int i = 0; i < names.size(); i++) {
                if (!SuggestionFeatures.NAMES.get(i).equals(names.get(i).asText())) {
                    log.info("Process ranker: feature '{}' != '{}' — stale model ignored",
                            names.get(i).asText(), SuggestionFeatures.NAMES.get(i));
                    return null;
                }
            }
            JsonNode weightsNode = root.path("weights");
            double[] weights = new double[weightsNode.size()];
            for (int i = 0; i < weights.length; i++) {
                weights[i] = weightsNode.get(i).asDouble();
            }
            return new LogisticAnswerScorer(
                    weights, root.path("bias").asDouble(0.0));
        } catch (Exception e) {
            log.debug("Process ranker: could not load persisted model — {}", e.getMessage());
            return null;
        }
    }

    /**
     * Fit the logistic re-ranker from every ledger line that carries a feature vector. Gated:
     * at least {@link #MIN_OUTCOMES} rows AND {@link #MIN_PER_CLASS} of EACH class — a one-class
     * fit ranks nothing. Persists weights + feature names + training log-loss for inspection.
     */
    private void refitRanker(Path ledger) throws IOException {
        List<double[]> rows = new ArrayList<>();
        List<Double> labels = new ArrayList<>();
        for (String line : Files.readAllLines(ledger, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonNode node = MAPPER.readTree(line);
                JsonNode features = node.path("features");
                if (features.size() != SuggestionFeatures.NAMES.size()) {
                    continue; // pre-feature ledger line (Platt-only) or stale feature space
                }
                double[] x = new double[features.size()];
                for (int i = 0; i < x.length; i++) {
                    x[i] = features.get(i).asDouble();
                }
                rows.add(x);
                labels.add(node.path("label").asDouble());
            } catch (Exception ignored) {
                // malformed ledger line — never fail the accept path
            }
        }
        long positives = labels.stream().filter(l -> l > 0.5).count();
        long negatives = labels.size() - positives;
        if (labels.size() < MIN_OUTCOMES || positives < MIN_PER_CLASS || negatives < MIN_PER_CLASS) {
            log.debug("Process ranker: {} outcomes ({}+/{}-) — fit needs {} total and {} per class",
                    labels.size(), positives, negatives, MIN_OUTCOMES, MIN_PER_CLASS);
            return;
        }
        double[][] x = rows.toArray(new double[0][]);
        double[] y = labels.stream().mapToDouble(Double::doubleValue).toArray();
        LogisticAnswerScorer fitted =
                LogisticRegressionTrainer.fit(x, y);
        double loss = LogisticRegressionTrainer.logLoss(fitted, x, y);

        com.fasterxml.jackson.databind.node.ObjectNode out = MAPPER.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode nameArray = out.putArray("featureNames");
        SuggestionFeatures.NAMES.forEach(nameArray::add);
        com.fasterxml.jackson.databind.node.ArrayNode weightArray = out.putArray("weights");
        for (double w : fitted.weights()) {
            weightArray.add(w);
        }
        out.put("bias", fitted.bias());
        out.put("outcomes", labels.size());
        out.put("logLoss", loss);
        Files.writeString(rankerFile(), out.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("Process ranker refit from {} outcomes ({}+/{}-): logLoss={}",
                labels.size(), positives, negatives, String.format(Locale.ROOT, "%.4f", loss));
    }

    private Path rankerFile() {
        return (dataDir == null || dataDir.isBlank())
                ? null : Path.of(dataDir, "rules", "process-ranker.json");
    }

    private Path ledgerFile() {
        return (dataDir == null || dataDir.isBlank())
                ? null : Path.of(dataDir, "rules", "process-calibration-outcomes.jsonl");
    }

    private Path paramsFile() {
        return (dataDir == null || dataDir.isBlank())
                ? null : Path.of(dataDir, "rules", "process-calibration.json");
    }
}
