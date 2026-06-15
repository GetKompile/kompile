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
package ai.kompile.tool.crawler;

import ai.kompile.knowledgegraph.resolution.GraphCompactionService;
import ai.kompile.knowledgegraph.resolution.GraphCompactionService.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP tool for entity resolution and graph compaction.
 * Exposes Senzing-style entity resolution capabilities to LLM agents.
 */
@Component
@ConditionalOnBean(GraphCompactionService.class)
public class EntityResolutionTool {

    private static final Logger log = LoggerFactory.getLogger(EntityResolutionTool.class);

    private final GraphCompactionService compactionService;

    public EntityResolutionTool(GraphCompactionService compactionService) {
        this.compactionService = compactionService;
    }

    // ---- Input records ----

    record CompactGraphInput(
            Double similarityThreshold,
            Boolean dryRun,
            Boolean useEmbeddings,
            Double embeddingThreshold,
            Long factSheetId
    ) {}

    record ExplainMatchInput(
            String nodeIdA,
            String nodeIdB
    ) {}

    record PreviewCandidatesInput(
            Double similarityThreshold,
            Integer maxResults,
            Boolean useEmbeddings,
            Long factSheetId
    ) {}

    // ---- Tools ----

    @Tool(name = "entity_resolution_compact", description = "Compact the knowledge graph by finding and merging similar entities. "
            + "Uses a multi-signal matching pipeline: normalized title matching, alias overlap, "
            + "Levenshtein distance, embedding-based cosine similarity (catches 'IBM' vs 'Big Blue'), "
            + "and Senzing-style attribute-behavior scoring (email/URL/phone as identity signals). "
            + "Entities are blocked by type, matched pairwise, "
            + "grouped into connected components, then merged. "
            + "Set dryRun=true to preview what would merge without executing. "
            + "Set useEmbeddings=false to disable semantic matching. "
            + "Returns merge decisions with Senzing-style why/how explainability and assembly traces.")
    public Map<String, Object> compactGraph(CompactGraphInput input) {
        try {
            double threshold = input.similarityThreshold() != null
                    ? input.similarityThreshold() : 0.85;
            boolean dryRun = input.dryRun() != null && input.dryRun();
            boolean useEmb = input.useEmbeddings() == null || input.useEmbeddings();
            double embThreshold = input.embeddingThreshold() != null
                    ? input.embeddingThreshold() : 0.88;

            if (dryRun) {
                CompactionConfig config = new CompactionConfig(threshold, false, useEmb, embThreshold);
                List<MatchCandidate> candidates = compactionService.previewCandidates(input.factSheetId(), config);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("mode", "preview");
                result.put("candidateCount", candidates.size());
                result.put("threshold", threshold);
                result.put("candidates", candidates.stream()
                        .limit(50)
                        .map(this::candidateToMap)
                        .collect(Collectors.toList()));
                if (candidates.size() > 50) {
                    result.put("note", "Showing first 50 of " + candidates.size() + " candidates");
                }
                return result;
            }

            CompactionConfig config = new CompactionConfig(threshold, true, useEmb, embThreshold);
            CompactionResult compactionResult = compactionService.compact(input.factSheetId(), config);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("mode", "executed");
            result.put("originalEntityCount", compactionResult.originalEntityCount());
            result.put("finalEntityCount", compactionResult.finalEntityCount());
            result.put("entitiesMerged", compactionResult.entitiesMerged());
            result.put("edgesRedirected", compactionResult.edgesRedirected());
            result.put("componentsFound", compactionResult.componentsFound());
            result.put("elapsedMs", compactionResult.elapsedMs());
            result.put("decisions", compactionResult.decisions().stream()
                    .limit(20)
                    .map(this::decisionToMap)
                    .collect(Collectors.toList()));
            return result;

        } catch (Exception e) {
            log.error("Entity resolution compact failed", e);
            return Map.of("error", "Compaction failed: " + e.getMessage());
        }
    }

    @Tool(name = "entity_resolution_explain", description = "Explain why two entities in the knowledge graph would or would not merge. "
            + "Returns Senzing-style explainability: match reasons (why they match), "
            + "blockers (why they don't), similarity score, and whether they would merge "
            + "at the default threshold. Provide two node IDs.")
    public Map<String, Object> explainMatch(ExplainMatchInput input) {
        try {
            if (input.nodeIdA() == null || input.nodeIdB() == null) {
                return Map.of("error", "Both nodeIdA and nodeIdB are required");
            }

            MatchExplanation explanation = compactionService.explain(
                    input.nodeIdA(), input.nodeIdB());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("nodeIdA", explanation.nodeIdA());
            result.put("nodeIdB", explanation.nodeIdB());
            result.put("wouldMerge", explanation.wouldMerge());
            result.put("similarityScore", explanation.score());
            result.put("matchReasons", explanation.matchReasons());
            result.put("blockers", explanation.blockers());
            return result;

        } catch (Exception e) {
            log.error("Entity resolution explain failed", e);
            return Map.of("error", "Explain failed: " + e.getMessage());
        }
    }

    @Tool(name = "entity_resolution_preview", description = "Preview entity resolution candidates in the knowledge graph. "
            + "Shows pairs of entities that would merge based on title similarity, "
            + "alias overlap, string distance, embedding cosine similarity, and "
            + "attribute-behavior scoring. Does NOT execute any merges. "
            + "Use this to review before running entity_resolution_compact.")
    public Map<String, Object> previewCandidates(PreviewCandidatesInput input) {
        try {
            double threshold = input.similarityThreshold() != null
                    ? input.similarityThreshold() : 0.85;
            int maxResults = input.maxResults() != null ? input.maxResults() : 50;

            boolean useEmb = input.useEmbeddings() == null || input.useEmbeddings();
            CompactionConfig config = new CompactionConfig(threshold, false, useEmb, 0.88);
            List<MatchCandidate> candidates = compactionService.previewCandidates(input.factSheetId(), config);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("totalCandidates", candidates.size());
            result.put("threshold", threshold);
            result.put("candidates", candidates.stream()
                    .limit(maxResults)
                    .map(this::candidateToMap)
                    .collect(Collectors.toList()));

            // Aggregate by entity type
            Map<String, Long> byType = candidates.stream()
                    .collect(Collectors.groupingBy(MatchCandidate::entityType, Collectors.counting()));
            result.put("candidatesByType", byType);

            return result;

        } catch (Exception e) {
            log.error("Entity resolution preview failed", e);
            return Map.of("error", "Preview failed: " + e.getMessage());
        }
    }

    // ---- Helpers ----

    private Map<String, Object> candidateToMap(MatchCandidate c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeIdA", c.nodeIdA());
        m.put("nodeIdB", c.nodeIdB());
        m.put("titleA", c.titleA());
        m.put("titleB", c.titleB());
        m.put("entityType", c.entityType());
        m.put("score", c.score());
        m.put("reasons", c.reasons());
        return m;
    }

    private Map<String, Object> decisionToMap(MergeDecision d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("canonicalNodeId", d.canonicalNodeId());
        m.put("canonicalTitle", d.canonicalTitle());
        m.put("mergedCount", d.mergedNodeIds().size());
        m.put("mergedNodeIds", d.mergedNodeIds());
        m.put("edgesRedirected", d.edgesRedirected());
        m.put("matchReasons", d.matchReasons());
        m.put("highestScore", d.highestScore());
        if (d.assemblySteps() != null && !d.assemblySteps().isEmpty()) {
            m.put("assemblySteps", d.assemblySteps());
        }
        return m;
    }
}
