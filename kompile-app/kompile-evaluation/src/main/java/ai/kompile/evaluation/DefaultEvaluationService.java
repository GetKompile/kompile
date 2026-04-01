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

package ai.kompile.evaluation;

import ai.kompile.core.evaluation.*;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Default implementation of EvaluationService.
 */
@Slf4j
public class DefaultEvaluationService implements EvaluationService {

    private final List<RagEvaluator> evaluators;
    private final EvaluationProperties properties;

    public DefaultEvaluationService(List<RagEvaluator> evaluators, EvaluationProperties properties) {
        this.evaluators = new CopyOnWriteArrayList<>(
                evaluators.stream()
                        .filter(RagEvaluator::isEnabled)
                        .collect(Collectors.toList())
        );
        this.properties = properties;

        log.info("Initialized evaluation service with {} evaluators", this.evaluators.size());
    }

    @Override
    public EvaluationReport evaluate(String query, String response,
                                     List<String> retrievedDocuments, EvaluationContext context) {
        return evaluate(query, response, retrievedDocuments, null, context);
    }

    @Override
    public EvaluationReport evaluate(String query, String response,
                                     List<String> retrievedDocuments,
                                     List<EvaluationType> types, EvaluationContext context) {
        if (!properties.isEnabled() || evaluators.isEmpty()) {
            return EvaluationReport.empty(query, response);
        }

        long startTime = System.currentTimeMillis();
        List<RagEvaluator> toRun = evaluators;

        // Filter by types if specified
        if (types != null && !types.isEmpty()) {
            Set<EvaluationType> typeSet = new HashSet<>(types);
            toRun = evaluators.stream()
                    .filter(e -> typeSet.contains(e.getType()))
                    .collect(Collectors.toList());
        }

        List<EvaluationResult> results;
        if (properties.isAsync() && toRun.size() > 1) {
            results = runAsync(toRun, query, response, retrievedDocuments, context);
        } else {
            results = runSequential(toRun, query, response, retrievedDocuments, context);
        }

        return buildReport(query, response, results, System.currentTimeMillis() - startTime);
    }

    /**
     * Evaluate a batch of inputs in parallel.
     */
    public List<EvaluationReport> evaluateBatch(List<BatchEvalInput> inputs, EvaluationContext context) {
        if (!properties.isEnabled() || evaluators.isEmpty() || inputs == null || inputs.isEmpty()) {
            return inputs == null ? Collections.emptyList() :
                    inputs.stream()
                            .map(i -> EvaluationReport.empty(i.query(), i.response()))
                            .collect(Collectors.toList());
        }

        List<CompletableFuture<EvaluationReport>> futures = inputs.stream()
                .map(input -> CompletableFuture.supplyAsync(() ->
                        evaluate(input.query(), input.response(), input.retrievedDocuments(), context)))
                .collect(Collectors.toList());

        return futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());
    }

    private List<EvaluationResult> runSequential(List<RagEvaluator> toRun, String query,
                                                  String response, List<String> retrievedDocuments,
                                                  EvaluationContext context) {
        List<EvaluationResult> results = new ArrayList<>();
        for (RagEvaluator evaluator : toRun) {
            EvaluationResult result = runEvaluator(evaluator, query, response, retrievedDocuments, context);
            if (result != null) {
                results.add(result);
            }
        }
        return results;
    }

    private List<EvaluationResult> runAsync(List<RagEvaluator> toRun, String query,
                                             String response, List<String> retrievedDocuments,
                                             EvaluationContext context) {
        List<CompletableFuture<EvaluationResult>> futures = toRun.stream()
                .map(evaluator -> CompletableFuture.supplyAsync(() ->
                        runEvaluator(evaluator, query, response, retrievedDocuments, context)))
                .collect(Collectors.toList());

        return futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private EvaluationResult runEvaluator(RagEvaluator evaluator, String query,
                                           String response, List<String> retrievedDocuments,
                                           EvaluationContext context) {
        try {
            long evalStart = System.currentTimeMillis();
            EvaluationResult result = evaluator.evaluate(query, response, retrievedDocuments, context);

            // Add timing info
            result = EvaluationResult.builder()
                    .evaluatorName(result.getEvaluatorName())
                    .evaluationType(result.getEvaluationType())
                    .passed(result.isPassed())
                    .score(result.getScore())
                    .confidence(result.getConfidence())
                    .explanation(result.getExplanation())
                    .findings(result.getFindings())
                    .threshold(result.getThreshold())
                    .metrics(result.getMetrics())
                    .metadata(result.getMetadata())
                    .evaluationTimeMs(System.currentTimeMillis() - evalStart)
                    .build();

            log.debug("Evaluator '{}' completed: score={}, passed={}",
                    evaluator.getName(), result.getScore(), result.isPassed());

            return result;

        } catch (Exception e) {
            log.error("Evaluator '{}' failed: {}", evaluator.getName(), e.getMessage(), e);
            return null;
        }
    }

    private EvaluationReport buildReport(String query, String response,
                                         List<EvaluationResult> results, long totalTime) {
        if (results.isEmpty()) {
            return EvaluationReport.empty(query, response);
        }

        // Calculate overall score (average)
        double overallScore = results.stream()
                .mapToDouble(EvaluationResult::getScore)
                .average()
                .orElse(0.0);

        // Determine overall pass/fail
        boolean overallPassed = results.stream().allMatch(EvaluationResult::isPassed);

        // Group scores by type
        Map<EvaluationType, Double> scoresByType = results.stream()
                .collect(Collectors.toMap(
                        EvaluationResult::getEvaluationType,
                        EvaluationResult::getScore,
                        (a, b) -> (a + b) / 2
                ));

        // Generate summary
        String summary = generateSummary(results, overallPassed, overallScore);

        // Generate recommendations
        List<String> recommendations = generateRecommendations(results);

        return EvaluationReport.builder()
                .query(query)
                .response(response)
                .overallPassed(overallPassed)
                .overallScore(overallScore)
                .results(results)
                .scoresByType(scoresByType)
                .totalEvaluationTimeMs(totalTime)
                .timestamp(Instant.now())
                .summary(summary)
                .recommendations(recommendations)
                .build();
    }

    private String generateSummary(List<EvaluationResult> results, boolean passed, double score) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Evaluation %s (score: %.2f). ", passed ? "PASSED" : "FAILED", score));

        long failedCount = results.stream().filter(r -> !r.isPassed()).count();
        if (failedCount > 0) {
            sb.append(String.format("%d/%d evaluators failed: ", failedCount, results.size()));
            sb.append(results.stream()
                    .filter(r -> !r.isPassed())
                    .map(r -> r.getEvaluatorName())
                    .collect(Collectors.joining(", ")));
        } else {
            sb.append("All evaluators passed.");
        }

        return sb.toString();
    }

    private List<String> generateRecommendations(List<EvaluationResult> results) {
        List<String> recommendations = new ArrayList<>();

        for (EvaluationResult result : results) {
            if (!result.isPassed()) {
                switch (result.getEvaluationType()) {
                    case RELEVANCY:
                        recommendations.add("Improve response relevancy by focusing more directly on the query");
                        break;
                    case FAITHFULNESS:
                        recommendations.add("Ensure response is grounded in the provided context");
                        break;
                    case HALLUCINATION_DETECTION:
                        recommendations.add("Remove unsupported claims not found in the context");
                        break;
                    case CONTEXT_RELEVANCY:
                        recommendations.add("Improve retrieval to get more relevant context documents");
                        break;
                    case ANSWER_CORRECTNESS:
                        recommendations.add("Verify factual accuracy of the response");
                        break;
                    default:
                        if (result.getExplanation() != null) {
                            recommendations.add(result.getExplanation());
                        }
                }
            }
        }

        return recommendations;
    }

    @Override
    public List<RagEvaluator> getEvaluators() {
        return new ArrayList<>(evaluators);
    }

    @Override
    public List<RagEvaluator> getEvaluatorsByType(EvaluationType type) {
        return evaluators.stream()
                .filter(e -> e.getType() == type)
                .collect(Collectors.toList());
    }

    @Override
    public void registerEvaluator(RagEvaluator evaluator) {
        evaluators.add(evaluator);
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * Input for batch evaluation.
     */
    public record BatchEvalInput(String query, String response, List<String> retrievedDocuments) {}
}
