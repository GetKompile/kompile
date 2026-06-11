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
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;

import java.util.*;
import java.util.stream.Collectors;

/**
 * LLM-based evaluator that assesses whether a graph extraction is complete
 * relative to the source text.
 * <p>
 * Given the original source text and the extracted entities/relationships,
 * asks the LLM to identify any missing entities or relationships and score
 * the overall completeness.
 */
@Slf4j
@RequiredArgsConstructor
public class GraphCompletenessEvaluator implements GraphEvaluator {

    private static final String EVALUATION_PROMPT = """
            You are evaluating the completeness of an entity/relationship extraction from a source text.

            ## Source Text
            "%s"

            ## Extracted Entities
            %s

            ## Extracted Relationships
            %s

            ## Task
            Evaluate how completely the extraction captured all significant entities and relationships
            from the source text. Consider:
            1. Are there important named entities (people, organizations, locations, dates, products, events, concepts) that were missed?
            2. Are there significant relationships between entities that were not captured?
            3. Are the extracted entity types appropriate?

            Rate the completeness on a scale of 0.0 to 1.0, where:
            - 1.0 = All significant entities and relationships were captured
            - 0.7-0.9 = Most were captured, with only minor omissions
            - 0.4-0.6 = Partial capture, some important items missing
            - 0.1-0.3 = Many important entities or relationships were missed
            - 0.0 = Almost nothing was captured

            Respond with ONLY a JSON object:
            {
              "score": 0.0-1.0,
              "missingEntities": ["entity1", "entity2"],
              "missingRelationships": ["source1 -> target1 (TYPE)", "source2 -> target2 (TYPE)"],
              "entityCompleteness": 0.0-1.0,
              "relationshipCompleteness": 0.0-1.0,
              "explanation": "brief explanation"
            }
            """;

    private final ChatClient chatClient;
    private final EvaluationProperties properties;

    @Override
    public GraphEvaluationResult evaluate(Graph extracted, GraphEvaluationContext context) {
        long startTime = System.currentTimeMillis();

        if (context.getSourceText() == null || context.getSourceText().isBlank()) {
            return GraphEvaluationResult.builder()
                    .evaluatorName(getName())
                    .evaluationType(getType())
                    .passed(false)
                    .score(0.0)
                    .explanation("No source text provided for completeness evaluation")
                    .evaluationTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }

        try {
            String entitiesStr = formatEntities(extracted.getEntities());
            String relationshipsStr = formatRelationships(extracted);
            String prompt = String.format(EVALUATION_PROMPT,
                    context.getSourceText(), entitiesStr, relationshipsStr);

            String result = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            JsonNode json = EvaluationResponseParser.parse(result);
            if (json == null) {
                log.warn("Could not parse graph completeness evaluation response");
                return GraphEvaluationResult.builder()
                        .evaluatorName(getName())
                        .evaluationType(getType())
                        .passed(false)
                        .score(0.0)
                        .explanation("Failed to parse LLM evaluation response")
                        .evaluationTimeMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            double score = EvaluationResponseParser.getDouble(json, "score", -1.0);
            if (score < 0.0) {
                return GraphEvaluationResult.builder()
                        .evaluatorName(getName())
                        .evaluationType(getType())
                        .passed(false)
                        .score(0.0)
                        .explanation("LLM response did not contain a valid score")
                        .evaluationTimeMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            double entityCompleteness = EvaluationResponseParser.getDouble(
                    json, "entityCompleteness", score);
            double relationshipCompleteness = EvaluationResponseParser.getDouble(
                    json, "relationshipCompleteness", score);
            String explanation = EvaluationResponseParser.getString(json, "explanation", null);
            List<String> missingEntities = EvaluationResponseParser.getStringArray(
                    json, "missingEntities");
            List<String> missingRelationships = EvaluationResponseParser.getStringArray(
                    json, "missingRelationships");

            double threshold = context.getThreshold() != null
                    ? context.getThreshold()
                    : properties.getGraphCompleteness().getThreshold();

            Map<String, Double> metrics = new LinkedHashMap<>();
            metrics.put("entityCompleteness", entityCompleteness);
            metrics.put("relationshipCompleteness", relationshipCompleteness);
            metrics.put("missingEntityCount", (double) missingEntities.size());
            metrics.put("missingRelationshipCount", (double) missingRelationships.size());

            // Build entity match entries for missing entities
            List<GraphEvaluationResult.EntityMatch> entityMatches = new ArrayList<>();
            for (String missing : missingEntities) {
                entityMatches.add(GraphEvaluationResult.EntityMatch.builder()
                        .expectedTitle(missing)
                        .matchType(GraphEvaluationResult.MatchType.FALSE_NEGATIVE)
                        .build());
            }

            // Build relationship match entries for missing relationships
            List<GraphEvaluationResult.RelationshipMatch> relMatches = new ArrayList<>();
            for (String missing : missingRelationships) {
                relMatches.add(GraphEvaluationResult.RelationshipMatch.builder()
                        .expectedSource(missing)
                        .matchType(GraphEvaluationResult.MatchType.FALSE_NEGATIVE)
                        .build());
            }

            return GraphEvaluationResult.builder()
                    .evaluatorName(getName())
                    .evaluationType(getType())
                    .passed(score >= threshold)
                    .score(score)
                    .precision(1.0) // completeness doesn't measure false positives
                    .recall(score)
                    .f1(score)
                    .falseNegatives(missingEntities.size() + missingRelationships.size())
                    .threshold(threshold)
                    .entityMatches(entityMatches)
                    .relationshipMatches(relMatches)
                    .metrics(metrics)
                    .explanation(explanation)
                    .evaluationTimeMs(System.currentTimeMillis() - startTime)
                    .build();

        } catch (Exception e) {
            log.error("Graph completeness evaluation failed: {}", e.getMessage());
            return GraphEvaluationResult.builder()
                    .evaluatorName(getName())
                    .evaluationType(getType())
                    .passed(false)
                    .score(0.0)
                    .explanation(e.getMessage())
                    .evaluationTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    @Override
    public String getName() {
        return "graph-completeness";
    }

    @Override
    public EvaluationType getType() {
        return EvaluationType.GRAPH_COMPLETENESS;
    }

    @Override
    public boolean requiresLlm() {
        return true;
    }

    @Override
    public boolean requiresGroundTruth() {
        return false;
    }

    private String formatEntities(List<Entity> entities) {
        if (entities == null || entities.isEmpty()) return "(none)";
        return entities.stream()
                .map(e -> String.format("- %s [%s]%s",
                        e.getTitle(),
                        e.getType() != null ? e.getType() : "UNKNOWN",
                        e.getDescription() != null ? ": " + e.getDescription() : ""))
                .collect(Collectors.joining("\n"));
    }

    private String formatRelationships(Graph graph) {
        List<Relationship> relationships = graph.getRelationships();
        if (relationships == null || relationships.isEmpty()) return "(none)";

        Map<String, String> idToTitle = new HashMap<>();
        if (graph.getEntities() != null) {
            for (Entity e : graph.getEntities()) {
                if (e.getId() != null && e.getTitle() != null) {
                    idToTitle.put(e.getId(), e.getTitle());
                }
            }
        }

        return relationships.stream()
                .map(r -> {
                    String src = idToTitle.getOrDefault(r.getSource(), r.getSource());
                    String tgt = idToTitle.getOrDefault(r.getTarget(), r.getTarget());
                    return String.format("- %s -> %s [%s]%s",
                            src, tgt,
                            r.getType() != null ? r.getType() : "RELATED_TO",
                            r.getDescription() != null ? ": " + r.getDescription() : "");
                })
                .collect(Collectors.joining("\n"));
    }
}
