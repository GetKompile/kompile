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
package ai.kompile.knowledgegraph.reasoning;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.synthesis.AnswerFeatures;
import ai.kompile.graph.reasoning.synthesis.AnswerScorerTrainingHarness;
import ai.kompile.graph.reasoning.synthesis.AnswerSynthesizer;
import ai.kompile.graph.reasoning.synthesis.AnswerSynthesizer.Candidate;
import ai.kompile.graph.reasoning.synthesis.AnswerSynthesizer.CandidateSignal;
import ai.kompile.graph.reasoning.synthesis.AnswerSynthesizer.SignalGroup;
import ai.kompile.graph.reasoning.synthesis.AnswerSynthesizer.SynthesizedAnswer;
import ai.kompile.graph.reasoning.synthesis.TrainingExample;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Orchestrates answer synthesis over the live knowledge graph: retrieves candidate entities for
 * a natural-language query, gathers calibrated signals about each (retrieval relevance, expected
 * type conformance), and folds them through the pure
 * {@link AnswerSynthesizer} into ranked, opinion-carrying answers with operator-tree traces.
 *
 * <p>Training: {@link #trainAnswerScorer(List, double, String)} replays a golden QA set through
 * the same candidate assembly, featurizes each candidate with {@link AnswerFeatures}, and fits
 * the logistic re-ranker via {@link AnswerScorerTrainingHarness} — reporting held-out MRR against
 * the retrieval-only baseline and the algebraic fold so the ship gates stay honest.</p>
 */
@Service
public class AnswerSynthesisService {

    private static final Logger log = LoggerFactory.getLogger(AnswerSynthesisService.class);
    private static final int DEFAULT_MAX_CANDIDATES = 8;
    private static final int RETRIEVAL_OVERSAMPLE = 3;

    private final KnowledgeGraphService knowledgeGraphService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public AnswerSynthesisService(KnowledgeGraphService knowledgeGraphService) {
        this.knowledgeGraphService = knowledgeGraphService;
    }

    /** One golden question-answer pair for scorer training. */
    public record GoldenQA(String query, String correctAnswer, long factSheetId,
                           String expectedType) {
    }

    /**
     * Retrieve candidates for the query and synthesize ranked answers.
     *
     * @param factSheetId   scope; {@code 0} = all graphs
     * @param query         the natural-language question
     * @param expectedType  optional expected entity type (adds a TYPE signal)
     * @param maxCandidates result cap; {@code <= 0} uses the default
     */
    public List<SynthesizedAnswer> synthesize(
            long factSheetId, String query, String expectedType, int maxCandidates) {
        int limit = maxCandidates > 0 ? maxCandidates : DEFAULT_MAX_CANDIDATES;
        List<Candidate> candidates = assembleCandidates(factSheetId, query, expectedType, limit);
        List<SynthesizedAnswer> answers = AnswerSynthesizer.synthesize(candidates);
        return answers.size() <= limit ? answers : List.copyOf(answers.subList(0, limit));
    }

    /**
     * Train the logistic answer re-ranker from a golden QA set. When {@code savePath} is given,
     * the trained model (feature names, weights, bias) is written there as JSON.
     */
    public AnswerScorerTrainingHarness.TrainingReport trainAnswerScorer(
            List<GoldenQA> golden, double heldOutFraction, String savePath) {
        Objects.requireNonNull(golden, "golden");
        List<TrainingExample> examples = new ArrayList<>();
        for (GoldenQA qa : golden) {
            if (qa == null || qa.query() == null || qa.correctAnswer() == null) {
                continue;
            }
            List<Candidate> candidates = assembleCandidates(
                    qa.factSheetId(), qa.query(), qa.expectedType(), DEFAULT_MAX_CANDIDATES);
            for (Candidate candidate : candidates) {
                int label = matchesAnswer(candidate.entityId(), qa.correctAnswer()) ? 1 : 0;
                examples.add(new TrainingExample(
                        qa.query(), candidate.entityId(),
                        AnswerFeatures.extract(candidate.signals()), label));
            }
        }
        AnswerScorerTrainingHarness.TrainingReport report =
                AnswerScorerTrainingHarness.train(examples, heldOutFraction);
        if (savePath != null && !savePath.isBlank()) {
            try {
                Map<String, Object> model = new LinkedHashMap<>();
                model.put("featureNames", report.featureNames());
                model.put("weights", report.weights());
                model.put("bias", report.bias());
                Path path = Path.of(savePath);
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                Files.writeString(path, objectMapper
                        .writerWithDefaultPrettyPrinter().writeValueAsString(model));
                log.info("Answer-scorer model written to {}", path.toAbsolutePath());
            } catch (Exception e) {
                log.warn("Failed to write answer-scorer model to {}: {}", savePath, e.getMessage());
            }
        }
        return report;
    }

    // ─── Candidate assembly ───────────────────────────────────────────────────────

    private final Map<String, String> titlesById = new LinkedHashMap<>();

    private List<Candidate> assembleCandidates(
            long factSheetId, String query, String expectedType, int limit) {
        if (knowledgeGraphService == null || query == null || query.isBlank()) {
            return List.of();
        }
        List<GraphNode> hits;
        try {
            hits = knowledgeGraphService.searchNodes(
                    query, NodeLevel.ENTITY, Math.max(limit * RETRIEVAL_OVERSAMPLE, 24));
        } catch (Exception e) {
            log.warn("Candidate retrieval failed for query '{}': {}", query, e.getMessage());
            return List.of();
        }

        Set<String> queryTokens = tokens(query);
        List<Candidate> candidates = new ArrayList<>();
        for (GraphNode node : hits) {
            if (node == null || node.getNodeId() == null) {
                continue;
            }
            if (factSheetId != 0L && node.getFactSheetId() != null
                    && node.getFactSheetId() != factSheetId) {
                continue;
            }
            String title = node.getTitle() != null ? node.getTitle() : node.getNodeId();
            synchronized (titlesById) {
                titlesById.put(node.getNodeId(), title);
            }

            List<CandidateSignal> signals = new ArrayList<>();
            signals.add(CandidateSignal.of(SignalGroup.RETRIEVAL, "text-retrieval",
                    Opinion.fromSoftTruth(lexicalRelevance(queryTokens, title), 3)));
            if (expectedType != null && !expectedType.isBlank()) {
                boolean typed = typeMemberships(node)
                        .contains(expectedType.trim().toLowerCase(Locale.ROOT));
                signals.add(CandidateSignal.of(SignalGroup.TYPE, "expected-type",
                        Opinion.fromSoftTruth(typed ? 0.9 : 0.15, 5)));
            }
            candidates.add(new Candidate(node.getNodeId(), signals));
        }
        return candidates;
    }

    /** True when the correct answer names the candidate by id or by resolved title. */
    private boolean matchesAnswer(String entityId, String correctAnswer) {
        String normalizedAnswer = normalize(correctAnswer);
        if (normalize(entityId).equals(normalizedAnswer)) {
            return true;
        }
        String title;
        synchronized (titlesById) {
            title = titlesById.get(entityId);
        }
        return title != null && normalize(title).equals(normalizedAnswer);
    }

    private static double lexicalRelevance(Set<String> queryTokens, String title) {
        if (queryTokens.isEmpty()) {
            return 0.5;
        }
        Set<String> titleTokens = tokens(title);
        long overlap = queryTokens.stream().filter(titleTokens::contains).count();
        double coverage = (double) overlap / queryTokens.size();
        return Math.min(0.95, Math.max(0.05, 0.1 + 0.85 * coverage));
    }

    private static Set<String> typeMemberships(GraphNode node) {
        Set<String> memberships = new LinkedHashSet<>();
        Map<String, Object> metadata = node.getMetadata() == null ? Map.of() : node.getMetadata();
        for (String key : List.of("entity_type", "entityType", "type", "memberType")) {
            Object value = metadata.get(key);
            if (value instanceof String text && !text.isBlank()) {
                memberships.add(text.trim().toLowerCase(Locale.ROOT));
            }
        }
        for (String key : List.of("owlInferredTypes", "entity_types", "additionalTypes")) {
            Object value = metadata.get(key);
            if (value instanceof Collection<?> collection) {
                for (Object item : collection) {
                    if (item != null && !String.valueOf(item).isBlank()) {
                        memberships.add(String.valueOf(item).trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        if (node.getNodeType() != null) {
            memberships.add(node.getNodeType().name().toLowerCase(Locale.ROOT));
        }
        return memberships;
    }

    private static Set<String> tokens(String text) {
        Set<String> result = new LinkedHashSet<>();
        for (String token : normalize(text).split(" ")) {
            if (token.length() >= 2) {
                result.add(token);
            }
        }
        return result;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").trim();
    }
}
