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
package ai.kompile.knowledgegraph.resolution;

import ai.kompile.knowledgegraph.resolution.GraphCompactionService.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST API for entity resolution and graph compaction.
 *
 * <p>Endpoints provide Senzing-style capabilities:
 * <ul>
 *   <li>Compact — merge similar entities in the knowledge graph</li>
 *   <li>Preview — see what would merge without executing</li>
 *   <li>Explain — why/why-not two entities match (explainability)</li>
 *   <li>Resolve — cross-chunk entity deduplication on extraction results</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/entity-resolution")
public class EntityResolutionController {

    private final GraphCompactionService compactionService;
    private final EntityResolutionService resolutionService;

    public EntityResolutionController(GraphCompactionService compactionService,
                                       EntityResolutionService resolutionService) {
        this.compactionService = compactionService;
        this.resolutionService = resolutionService;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REQUEST DTO
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Request body for compact/preview with advanced resolution features.
     */
    public record CompactionRequest(
            Double threshold,
            Long factSheetId,
            Boolean crossTypeMerging,
            Boolean entityTypeCorrection,
            Boolean crossLanguageResolution,
            Set<String> customCompatibleTypePairs,
            Map<String, String> typeHierarchy
    ) {
        CompactionConfig toConfig(boolean deleteAfterMerge) {
            double t = threshold != null ? threshold : 0.85;
            boolean ctm = crossTypeMerging != null && crossTypeMerging;
            boolean etc = entityTypeCorrection != null && entityTypeCorrection;
            boolean clr = crossLanguageResolution != null && crossLanguageResolution;
            return new CompactionConfig(t, deleteAfterMerge, true, 0.88,
                    ctm, etc, clr, customCompatibleTypePairs, typeHierarchy, null);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRAPH COMPACTION (persisted knowledge graph)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Compact the knowledge graph by merging similar entities.
     *
     * @param threshold   Levenshtein similarity threshold (0.0-1.0, default 0.85)
     * @param factSheetId optional fact-sheet scope (null = all entities)
     * @return compaction summary with merge decisions
     */
    @PostMapping("/compact")
    public ResponseEntity<Map<String, Object>> compact(
            @RequestParam(defaultValue = "0.85") double threshold,
            @RequestParam(required = false) Long factSheetId) {
        CompactionConfig config = CompactionConfig.withThreshold(threshold);
        CompactionResult result = compactionService.compact(factSheetId, config);
        return ResponseEntity.ok(toCompactionResponse(result));
    }

    /**
     * Compact the knowledge graph with advanced resolution features.
     */
    @PostMapping("/compact/advanced")
    public ResponseEntity<Map<String, Object>> compactAdvanced(
            @RequestBody CompactionRequest request) {
        CompactionConfig config = request.toConfig(true);
        CompactionResult result = compactionService.compact(request.factSheetId(), config);
        return ResponseEntity.ok(toCompactionResponse(result));
    }

    /**
     * Preview merge candidates without executing merges.
     *
     * @param threshold   Levenshtein similarity threshold
     * @param factSheetId optional fact-sheet scope
     * @return list of candidate pairs with scores and reasons
     */
    @GetMapping("/compact/preview")
    public ResponseEntity<Map<String, Object>> previewCompaction(
            @RequestParam(defaultValue = "0.85") double threshold,
            @RequestParam(required = false) Long factSheetId) {
        CompactionConfig config = CompactionConfig.previewOnly(threshold);
        List<MatchCandidate> candidates = compactionService.previewCandidates(factSheetId, config);

        List<Map<String, Object>> candidateList = candidates.stream()
                .map(this::toCandidateMap)
                .collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("candidateCount", candidateList.size());
        response.put("threshold", threshold);
        response.put("candidates", candidateList);
        return ResponseEntity.ok(response);
    }

    /**
     * Preview merge candidates with advanced resolution features.
     */
    @PostMapping("/compact/preview/advanced")
    public ResponseEntity<Map<String, Object>> previewCompactionAdvanced(
            @RequestBody CompactionRequest request) {
        CompactionConfig config = request.toConfig(false);
        List<MatchCandidate> candidates = compactionService.previewCandidates(request.factSheetId(), config);

        List<Map<String, Object>> candidateList = candidates.stream()
                .map(this::toCandidateMap)
                .collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("candidateCount", candidateList.size());
        response.put("threshold", request.threshold() != null ? request.threshold() : 0.85);
        response.put("candidates", candidateList);
        return ResponseEntity.ok(response);
    }

    /**
     * Merge two specific entities. The entity with the higher confidence
     * (or longer description) becomes the canonical; the other is merged
     * into it and deleted.
     *
     * @param nodeIdA first entity node ID
     * @param nodeIdB second entity node ID
     * @return merge decision with assembly trace
     */
    @PostMapping("/merge")
    public ResponseEntity<Map<String, Object>> mergePair(
            @RequestParam String nodeIdA,
            @RequestParam String nodeIdB) {
        CompactionConfig config = CompactionConfig.withThreshold(0.0);
        CompactionResult result = compactionService.mergePair(nodeIdA, nodeIdB, config);
        return ResponseEntity.ok(toCompactionResponse(result));
    }

    /**
     * Find and list all duplicate entity groups without merging.
     * Returns groups of entities that appear to be duplicates.
     *
     * @param threshold   similarity threshold
     * @param factSheetId optional fact-sheet scope
     * @return duplicate groups with scores
     */
    @GetMapping("/duplicates")
    public ResponseEntity<Map<String, Object>> findDuplicates(
            @RequestParam(defaultValue = "0.85") double threshold,
            @RequestParam(required = false) Long factSheetId) {
        CompactionConfig config = CompactionConfig.previewOnly(threshold);
        List<MatchCandidate> candidates = compactionService.previewCandidates(factSheetId, config);

        // Group candidates into connected clusters
        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        Map<String, String> titles = new LinkedHashMap<>();
        Map<String, String> types = new LinkedHashMap<>();
        for (MatchCandidate c : candidates) {
            adjacency.computeIfAbsent(c.nodeIdA(), k -> new LinkedHashSet<>()).add(c.nodeIdB());
            adjacency.computeIfAbsent(c.nodeIdB(), k -> new LinkedHashSet<>()).add(c.nodeIdA());
            titles.put(c.nodeIdA(), c.titleA());
            titles.put(c.nodeIdB(), c.titleB());
            types.put(c.nodeIdA(), c.entityType());
            types.put(c.nodeIdB(), c.entityType());
        }

        Set<String> visited = new LinkedHashSet<>();
        List<Map<String, Object>> groups = new ArrayList<>();
        for (String nodeId : adjacency.keySet()) {
            if (visited.contains(nodeId)) continue;
            // BFS to find cluster
            Set<String> cluster = new LinkedHashSet<>();
            java.util.Queue<String> queue = new java.util.LinkedList<>();
            queue.add(nodeId);
            visited.add(nodeId);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                cluster.add(current);
                for (String neighbor : adjacency.getOrDefault(current, Set.of())) {
                    if (visited.add(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }

            if (cluster.size() >= 2) {
                List<Map<String, Object>> members = cluster.stream()
                        .map(id -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("nodeId", id);
                            m.put("title", titles.getOrDefault(id, ""));
                            m.put("entityType", types.getOrDefault(id, "ENTITY"));
                            return m;
                        })
                        .collect(Collectors.toList());

                // Find max score within this cluster
                double maxScore = candidates.stream()
                        .filter(c -> cluster.contains(c.nodeIdA()) && cluster.contains(c.nodeIdB()))
                        .mapToDouble(MatchCandidate::score)
                        .max()
                        .orElse(0.0);

                Map<String, Object> group = new LinkedHashMap<>();
                group.put("size", cluster.size());
                group.put("maxScore", maxScore);
                group.put("entityType", types.getOrDefault(cluster.iterator().next(), "ENTITY"));
                group.put("members", members);
                groups.add(group);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("duplicateGroupCount", groups.size());
        response.put("totalDuplicateEntities", groups.stream().mapToInt(g -> (int) g.get("size")).sum());
        response.put("threshold", threshold);
        response.put("groups", groups);
        return ResponseEntity.ok(response);
    }

    /**
     * Explain why two entities would or would not merge.
     * Senzing-style "why" / "why not" / "how" explainability.
     *
     * @param nodeIdA first entity node ID
     * @param nodeIdB second entity node ID
     */
    @GetMapping("/explain")
    public ResponseEntity<Map<String, Object>> explain(
            @RequestParam String nodeIdA,
            @RequestParam String nodeIdB) {
        MatchExplanation explanation = compactionService.explain(nodeIdA, nodeIdB);
        return ResponseEntity.ok(toExplanationResponse(explanation));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EXTRACTION-LEVEL RESOLUTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get resolution configuration including type hierarchy and feature flags.
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("defaultSimilarityThreshold", 0.85);
        config.put("defaultEmbeddingThreshold", 0.88);
        config.put("description", "Entity resolution uses a multi-signal matching pipeline: " +
                "normalized title matching, alias overlap, Levenshtein distance, " +
                "embedding-based cosine similarity (semantic blocking), and " +
                "attribute-behavior scoring (Senzing-style frequency/exclusivity weighting). " +
                "Entities are blocked by type to reduce O(n²) comparisons, then " +
                "connected components are found and merged. Each merge includes a " +
                "step-by-step assembly trace (Senzing-style 'How' explainability).");
        config.put("approach", "principles-based");
        config.put("signals", List.of(
                "EXACT_TITLE_MATCH — normalized title comparison (lowercase, strip suffixes, collapse whitespace)",
                "TITLE_IN_ALIAS — entity name found in the other entity's alias list",
                "SHARED_ALIASES — both entities share one or more aliases",
                "LEVENSHTEIN — string edit distance similarity above threshold",
                "EMBEDDING_COSINE — semantic similarity via text embedding model (catches 'IBM' vs 'Big Blue')",
                "ATTR_MATCH — Senzing-style attribute-behavior scoring (email=EXCLUSIVE, country=FREQUENT, etc.)",
                "ABBREVIATION_MATCH — first-initial abbreviation expansion (R. Nakamura ↔ Reiko Nakamura)",
                "PARTIAL_NAME_MATCH — first-name disambiguation (Priya ↔ Priya Sharma)",
                "CROSS_LANGUAGE_MATCH — cross-language alias overlap (München ↔ Munich)"
        ));
        config.put("attributeBehaviors", Map.of(
                "EXCLUSIVE", "Strong identity signal (email, URL, ticker, DOI, ISBN) — weight 0.90-0.95",
                "CLOSE_EXCLUSIVE", "Near-unique identifier (address, phone) — weight 0.80-0.85",
                "STABLE", "Rarely changes (founded year) — weight 0.60",
                "FREQUENT", "Common values, weak signal (industry, location) — weight 0.30-0.50",
                "VERY_FREQUENT", "Very common, minimal signal (country) — weight 0.20"
        ));
        config.put("features", Map.of(
                "crossTypeMerging", "Merge entities across compatible types (e.g. EMPLOYEE ↔ PERSON)",
                "entityTypeCorrection", "Auto-correct mistyped entity types (e.g. email tagged as PERSON)",
                "crossLanguageResolution", "Match entities across languages and scripts"
        ));

        // Type hierarchy
        config.put("defaultTypeHierarchy", CompactionConfig.DEFAULT_TYPE_HIERARCHY);
        config.put("builtInCompatibleTypePairs", GraphCompactionService.COMPATIBLE_TYPE_PAIRS);
        return ResponseEntity.ok(config);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TYPE HIERARCHY
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get the default type hierarchy.
     */
    @GetMapping("/type-hierarchy")
    public ResponseEntity<Map<String, Object>> getTypeHierarchy() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("defaultHierarchy", CompactionConfig.DEFAULT_TYPE_HIERARCHY);
        response.put("builtInCompatiblePairs", GraphCompactionService.COMPATIBLE_TYPE_PAIRS);

        // Compute the effective pairs and eligible types from defaults
        CompactionConfig defaultConfig = CompactionConfig.withCrossTypeMerging(0.85);
        response.put("effectiveCompatiblePairs", defaultConfig.effectiveCompatibleTypePairs());
        response.put("effectiveEligibleTypes", defaultConfig.effectiveCrossTypeEligibleTypes());
        return ResponseEntity.ok(response);
    }

    /**
     * Preview what a custom type hierarchy + custom pairs would produce.
     * Does not persist — just shows the computed effective pairs and eligible types.
     */
    @PostMapping("/type-hierarchy/preview")
    public ResponseEntity<Map<String, Object>> previewTypeHierarchy(
            @RequestBody CompactionRequest request) {
        CompactionConfig config = request.toConfig(false);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("inputHierarchy", request.typeHierarchy());
        response.put("inputCustomPairs", request.customCompatibleTypePairs());
        response.put("effectiveHierarchy", config.effectiveTypeHierarchy());
        response.put("effectiveCompatiblePairs", config.effectiveCompatibleTypePairs());
        response.put("effectiveEligibleTypes", config.effectiveCrossTypeEligibleTypes());
        return ResponseEntity.ok(response);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RESPONSE BUILDERS
    // ═══════════════════════════════════════════════════════════════════════════

    private Map<String, Object> toCompactionResponse(CompactionResult result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("originalEntityCount", result.originalEntityCount());
        response.put("finalEntityCount", result.finalEntityCount());
        response.put("entitiesMerged", result.entitiesMerged());
        response.put("edgesRedirected", result.edgesRedirected());
        response.put("componentsFound", result.componentsFound());
        response.put("elapsedMs", result.elapsedMs());

        List<Map<String, Object>> decisions = result.decisions().stream()
                .map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("canonicalNodeId", d.canonicalNodeId());
                    m.put("canonicalTitle", d.canonicalTitle());
                    m.put("mergedNodeIds", d.mergedNodeIds());
                    m.put("edgesRedirected", d.edgesRedirected());
                    m.put("matchReasons", d.matchReasons());
                    m.put("highestScore", d.highestScore());
                    if (d.assemblySteps() != null && !d.assemblySteps().isEmpty()) {
                        m.put("assemblySteps", d.assemblySteps());
                    }
                    return m;
                })
                .collect(Collectors.toList());
        response.put("decisions", decisions);
        return response;
    }

    private Map<String, Object> toCandidateMap(MatchCandidate candidate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeIdA", candidate.nodeIdA());
        m.put("nodeIdB", candidate.nodeIdB());
        m.put("titleA", candidate.titleA());
        m.put("titleB", candidate.titleB());
        m.put("entityType", candidate.entityType());
        m.put("score", candidate.score());
        m.put("reasons", candidate.reasons());
        return m;
    }

    private Map<String, Object> toExplanationResponse(MatchExplanation explanation) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("nodeIdA", explanation.nodeIdA());
        response.put("nodeIdB", explanation.nodeIdB());
        response.put("wouldMerge", explanation.wouldMerge());
        response.put("score", explanation.score());
        response.put("matchReasons", explanation.matchReasons());
        response.put("blockers", explanation.blockers());
        return response;
    }
}
