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

package ai.kompile.cli.main.codeindex;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Multi-signal relevance ranker for code search results.
 * Scores files and entities using: exact token match, symbol match,
 * prefix match, path match, recency boost, and graph-based neighbor boost.
 *
 * <p>Intent-aware: adjusts weights based on query intent
 * (debug/explain/refactor/review/test/integrate/navigate).
 * Applies penalty signals for test files, generated code, docs, and vendor files.
 * Reports confidence tiers (high/medium/low) based on normalized position.</p>
 *
 * <p>Inspired by sigmap's multi-signal scoring approach with 2-hop BFS
 * graph boost and intent-specific weight tuning.</p>
 */
public class CodeRelevanceRanker {

    // Graph boost amounts for BFS neighbor scoring
    private static final double GRAPH_HOP1_BOOST = 0.40;
    private static final double GRAPH_HOP2_BOOST = 0.15;

    // Penalty multipliers for file categories
    private static final Map<String, Double> PENALTY_SIGNALS = Map.of(
            "test", 0.4,
            "generated", 0.3,
            "docs", 0.2,
            "vendor", 0.0,
            "node_modules", 0.0
    );

    /**
     * A scored result with relevance metadata.
     */
    public record ScoredResult(
            String filePath,
            String entityType,
            String name,
            String signature,
            String fqn,
            Object startLine,
            double score,
            String confidence,  // "high", "medium", "low"
            Map<String, Double> scoreBreakdown
    ) implements Comparable<ScoredResult> {
        @Override
        public int compareTo(ScoredResult other) {
            return Double.compare(other.score, this.score); // descending
        }
    }

    /**
     * Ranked search result with metadata.
     */
    public record RankedResults(
            String query,
            IntentClassifier.Intent intent,
            List<ScoredResult> results,
            int totalCandidates,
            long elapsedMs
    ) {}

    /**
     * Perform a ranked search across the index.
     *
     * @param projectId the project to search
     * @param query     the search query
     * @param indexDir  path to the index directory
     * @param rootDir   path to the project root (for recency checking)
     * @param topK      maximum results to return
     * @return ranked results with scores and confidence tiers
     */
    public static RankedResults rankedSearch(String projectId, String query,
                                              Path indexDir, Path rootDir,
                                              int topK) throws IOException {
        long t0 = System.currentTimeMillis();

        // 1. Classify intent and get weight profile
        IntentClassifier.Intent intent = IntentClassifier.classify(query);
        IntentClassifier.WeightProfile weights = IntentClassifier.getWeights(intent);

        // 2. Tokenize the query
        List<String> queryTokens = CodeTokenizer.tokenize(query);
        if (queryTokens.isEmpty()) {
            return new RankedResults(query, intent, List.of(), 0,
                    System.currentTimeMillis() - t0);
        }

        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            // 3. Get candidate entities from FTS search (broader than final topK)
            int candidateLimit = Math.max(topK * 5, 100);
            List<Map<String, Object>> candidates = db.search(query, null, candidateLimit);

            if (candidates.isEmpty()) {
                return new RankedResults(query, intent, List.of(), 0,
                        System.currentTimeMillis() - t0);
            }

            // 4. Build graph neighbor set for graph-based boosting
            Set<String> hop1Neighbors = new HashSet<>();
            Set<String> hop2Neighbors = new HashSet<>();
            buildGraphNeighborSets(db, candidates, hop1Neighbors, hop2Neighbors);

            // 5. Score each candidate
            List<ScoredResult> scored = new ArrayList<>();
            for (Map<String, Object> entity : candidates) {
                double score = scoreEntity(entity, queryTokens, weights,
                        hop1Neighbors, hop2Neighbors, rootDir);
                if (score > 0) {
                    scored.add(toScoredResult(entity, score, queryTokens, weights,
                            hop1Neighbors, hop2Neighbors, rootDir));
                }
            }

            // 6. Co-change mutual boost: if a result file frequently co-changes
            //    with other files in the result set, boost it
            Path projectIndexDir = LocalCodeIndexer.getIndexDir(projectId);
            Set<String> resultFiles = scored.stream()
                    .map(ScoredResult::filePath)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            if (resultFiles.size() > 1) {
                List<ScoredResult> boosted = new ArrayList<>();
                for (ScoredResult sr : scored) {
                    double coBoost = GitSignals.coChangeBoost(sr.filePath(), resultFiles, projectIndexDir);
                    if (coBoost > 1.0) {
                        Map<String, Double> bd = new LinkedHashMap<>(sr.scoreBreakdown());
                        bd.put("coChangeBoost", coBoost);
                        boosted.add(new ScoredResult(sr.filePath(), sr.entityType(), sr.name(),
                                sr.signature(), sr.fqn(), sr.startLine(),
                                sr.score() * coBoost, sr.confidence(), bd));
                    } else {
                        boosted.add(sr);
                    }
                }
                scored = boosted;
            }

            // 7. Sort by score descending
            Collections.sort(scored);

            // 8. Deduplicate by file — keep highest-scoring entity per file
            List<ScoredResult> deduped = deduplicateByFile(scored);

            // 9. Assign confidence tiers
            int total = deduped.size();
            List<ScoredResult> withConfidence = new ArrayList<>();
            for (int i = 0; i < Math.min(total, topK); i++) {
                ScoredResult sr = deduped.get(i);
                String conf;
                if (total <= 3) {
                    conf = i == 0 ? "high" : "medium";
                } else {
                    double pos = (double) i / total;
                    conf = pos < 0.33 ? "high" : pos < 0.66 ? "medium" : "low";
                }
                withConfidence.add(new ScoredResult(
                        sr.filePath(), sr.entityType(), sr.name(), sr.signature(),
                        sr.fqn(), sr.startLine(), sr.score(), conf, sr.scoreBreakdown()
                ));
            }

            return new RankedResults(query, intent, withConfidence, candidates.size(),
                    System.currentTimeMillis() - t0);

        } catch (SQLException e) {
            throw new IOException("Database error: " + e.getMessage(), e);
        }
    }

    /**
     * Score a single entity against the query.
     */
    private static double scoreEntity(Map<String, Object> entity,
                                       List<String> queryTokens,
                                       IntentClassifier.WeightProfile weights,
                                       Set<String> hop1Neighbors,
                                       Set<String> hop2Neighbors,
                                       Path rootDir) {
        String name = str(entity, "name");
        String fqn = str(entity, "fullyQualifiedName");
        String sig = str(entity, "signature");
        String filePath = str(entity, "filePath");

        // Tokenize entity fields
        List<String> nameTokens = CodeTokenizer.tokenizeSymbol(name);
        List<String> fqnTokens = CodeTokenizer.tokenize(fqn);
        List<String> pathTokens = CodeTokenizer.tokenizePath(filePath);
        List<String> sigTokens = sig != null ? CodeTokenizer.tokenize(sig) : List.of();

        Set<String> allEntityTokens = new HashSet<>();
        allEntityTokens.addAll(nameTokens);
        allEntityTokens.addAll(fqnTokens);
        allEntityTokens.addAll(sigTokens);

        double exactScore = 0;
        double symbolScore = 0;
        double prefixScore = 0;
        double pathScore = 0;

        for (String qt : queryTokens) {
            // Exact token match in any entity field
            if (allEntityTokens.contains(qt)) {
                exactScore += weights.exactToken();
            }

            // Symbol name match (bonus for matching the entity name itself)
            if (nameTokens.contains(qt)) {
                symbolScore += weights.symbolMatch();
            }

            // Prefix match (query token is a prefix of an entity token, min 4 chars)
            if (qt.length() >= 4) {
                for (String et : allEntityTokens) {
                    if (et.startsWith(qt) && !et.equals(qt)) {
                        prefixScore += weights.prefixMatch();
                        break;
                    }
                }
            }

            // Path match
            if (pathTokens.contains(qt)) {
                pathScore += weights.pathMatch();
            }
        }

        double baseScore = exactScore + symbolScore + prefixScore + pathScore;

        // Apply git-based recency boost (real commit history, not indexer timestamp)
        baseScore *= GitSignals.recencyMultiplier(filePath, rootDir, weights.recencyBoost());

        // Apply git churn boost — frequently modified files are more likely relevant
        baseScore *= GitSignals.churnMultiplier(filePath, rootDir);

        // Apply author diversity boost — files with many contributors are more central
        baseScore *= GitSignals.authorMultiplier(filePath, rootDir);

        // Apply graph boost
        if (fqn != null) {
            if (hop1Neighbors.contains(fqn)) {
                baseScore += weights.graphBoost() * GRAPH_HOP1_BOOST * queryTokens.size();
            } else if (hop2Neighbors.contains(fqn)) {
                baseScore += weights.graphBoost() * GRAPH_HOP2_BOOST * queryTokens.size();
            }
        }

        // Apply PageRank importance boost (if available)
        // High-PageRank files are central to the codebase — slight boost
        baseScore *= computePageRankMultiplier(filePath, rootDir);

        // Apply penalties
        double penaltyMultiplier = computePenalty(filePath);
        baseScore *= penaltyMultiplier;

        return baseScore;
    }

    /**
     * Build a full ScoredResult with breakdown.
     */
    private static ScoredResult toScoredResult(Map<String, Object> entity, double score,
                                                List<String> queryTokens,
                                                IntentClassifier.WeightProfile weights,
                                                Set<String> hop1Neighbors,
                                                Set<String> hop2Neighbors,
                                                Path rootDir) {
        String filePath = str(entity, "filePath");
        String name = str(entity, "name");
        String fqn = str(entity, "fullyQualifiedName");

        Map<String, Double> breakdown = new LinkedHashMap<>();
        breakdown.put("total", score);
        breakdown.put("penalty", computePenalty(filePath));

        return new ScoredResult(
                filePath,
                str(entity, "entityType"),
                name,
                str(entity, "signature"),
                fqn,
                entity.get("startLine"),
                score,
                null, // confidence assigned later
                breakdown
        );
    }

    // Static cache for PageRank scores per search session
    private static volatile Map<String, Double> pageRankCache = null;
    private static volatile Path pageRankCacheDir = null;

    /**
     * Compute PageRank importance multiplier for a file.
     * Files with higher PageRank (more central in the dependency graph) get a boost.
     * Range: 1.0 (no data / below median) to 1.3 (top-ranked files).
     */
    private static double computePageRankMultiplier(String filePath, Path rootDir) {
        if (filePath == null || rootDir == null) return 1.0;

        try {
            // Derive index dir from root dir (same convention as LocalCodeIndexer)
            String projectId = rootDir.getFileName().toString();
            Path indexDir = LocalCodeIndexer.getIndexDir(projectId);

            // Lazy-load PageRank cache
            if (pageRankCache == null || !indexDir.equals(pageRankCacheDir)) {
                synchronized (CodeRelevanceRanker.class) {
                    if (pageRankCache == null || !indexDir.equals(pageRankCacheDir)) {
                        try {
                            List<Map<String, Object>> topFiles = PageRankComputer.getTopFiles(indexDir, 500);
                            Map<String, Double> cache = new HashMap<>();
                            for (Map<String, Object> f : topFiles) {
                                cache.put((String) f.get("relPath"), (Double) f.get("score"));
                            }
                            pageRankCache = cache;
                            pageRankCacheDir = indexDir;
                        } catch (Exception e) {
                            pageRankCache = Map.of();
                            pageRankCacheDir = indexDir;
                        }
                    }
                }
            }

            Double score = pageRankCache.get(filePath);
            if (score == null || score <= 0) return 1.0;

            // Normalize: top PageRank files get up to 1.3x boost
            // Median PR score is roughly 1/N, so files significantly above that are important
            double maxScore = pageRankCache.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
            double normalized = score / maxScore; // 0.0 to 1.0
            return 1.0 + normalized * 0.3; // 1.0 to 1.3

        } catch (Exception e) {
            return 1.0;
        }
    }

    /**
     * Compute penalty multiplier based on file path patterns.
     * Tiers (inspired by soulforge's granular file-type scoring):
     *   0.00 — vendor/node_modules (complete suppression)
     *   0.05 — generated code, lock files, build artifacts
     *   0.10 — junk files (.DS_Store, IDE config)
     *   0.15 — documentation
     *   0.30 — test files
     *   0.40 — config/data files (json, yaml, toml, xml)
     *   1.00 — source code (no penalty)
     */
    private static double computePenalty(String filePath) {
        if (filePath == null) return 1.0;
        String lower = filePath.toLowerCase();

        // Tier: vendor (0.0)
        if (lower.contains("node_modules/") || lower.contains("node_modules\\")) return 0.0;
        if (lower.contains("vendor/") || lower.contains("vendor\\")) return 0.0;
        if (lower.contains("bower_components/")) return 0.0;

        // Tier: generated / build artifacts / lock files (0.05)
        if (lower.contains("/dist/") || lower.contains("/build/") || lower.contains("/out/")
                || lower.contains("/target/") || lower.contains("/_build/")
                || lower.contains("/.next/") || lower.contains("/.nuxt/")
                || lower.contains("/__pycache__/") || lower.contains("/.gradle/")) return 0.05;
        if (lower.endsWith("-lock.json") || lower.endsWith(".lock") || lower.endsWith(".lockb")
                || lower.endsWith("lock.yaml") || lower.endsWith("lock.yml")
                || lower.endsWith("-shrinkwrap.json")) return 0.05;
        if (lower.contains("/generated/") || lower.contains("\\generated\\")
                || lower.endsWith(".generated.java") || lower.endsWith(".g.dart")
                || lower.endsWith("_generated.go") || lower.endsWith(".min.js")
                || lower.endsWith(".min.css") || lower.endsWith(".js.map")
                || lower.endsWith(".css.map")) return 0.05;

        // Tier: junk / IDE config (0.10)
        if (lower.endsWith(".ds_store") || lower.endsWith("thumbs.db")
                || lower.endsWith("desktop.ini")) return 0.10;
        if (lower.contains("/.idea/") || lower.contains("/.vscode/")
                || lower.contains("/.settings/") || lower.contains("/.vs/")) return 0.10;

        // Tier: documentation (0.15)
        if (lower.contains("/docs/") || lower.contains("/documentation/")
                || lower.endsWith(".md") || lower.endsWith(".mdx")
                || lower.endsWith(".adoc") || lower.endsWith(".rst")
                || lower.endsWith(".txt")) return 0.15;

        // Tier: test files (0.30)
        if (lower.contains("/test/") || lower.contains("/tests/")
                || lower.contains("/__tests__/") || lower.contains("/spec/")
                || lower.contains("/e2e/") || lower.contains("/cypress/")
                || lower.contains("/playwright/")
                || lower.endsWith("test.java") || lower.endsWith("test.kt")
                || lower.endsWith("test.py") || lower.endsWith("test.js")
                || lower.endsWith("test.ts") || lower.endsWith("test.tsx")
                || lower.endsWith(".test.js") || lower.endsWith(".test.ts")
                || lower.endsWith(".spec.js") || lower.endsWith(".spec.ts")
                || lower.endsWith("_test.go") || lower.endsWith("spec.rb")) {
            return 0.30;
        }

        // Tier: config / data files (0.40)
        if (lower.endsWith(".json") || lower.endsWith(".yaml") || lower.endsWith(".yml")
                || lower.endsWith(".toml") || lower.endsWith(".ini")
                || lower.endsWith(".cfg") || lower.endsWith(".conf")
                || lower.endsWith(".properties") || lower.endsWith(".xml")) return 0.40;

        return 1.0;
    }

    /**
     * Build 2-hop BFS neighbor sets from the graph relations.
     * hop1: entities directly related to any candidate.
     * hop2: entities two hops away.
     */
    private static void buildGraphNeighborSets(IndexDatabase db,
                                                List<Map<String, Object>> candidates,
                                                Set<String> hop1Neighbors,
                                                Set<String> hop2Neighbors) {
        // Collect FQNs of top candidates to seed the BFS
        Set<String> seedFqns = new LinkedHashSet<>();
        int seedLimit = Math.min(candidates.size(), 10);
        for (int i = 0; i < seedLimit; i++) {
            String fqn = str(candidates.get(i), "fullyQualifiedName");
            if (fqn != null) seedFqns.add(fqn);
        }

        try {
            // Hop 1: direct relations
            for (String fqn : seedFqns) {
                List<Map<String, Object>> outgoing = db.getOutgoingRelations(fqn, 50);
                for (Map<String, Object> rel : outgoing) {
                    String targetFqn = str(rel, "targetFqn");
                    if (targetFqn != null && !targetFqn.isEmpty() && !seedFqns.contains(targetFqn)) {
                        hop1Neighbors.add(targetFqn);
                    }
                }
                List<Map<String, Object>> incoming = db.getIncomingRelations(fqn, 50);
                for (Map<String, Object> rel : incoming) {
                    String sourceFqn = str(rel, "sourceFqn");
                    if (sourceFqn != null && !sourceFqn.isEmpty() && !seedFqns.contains(sourceFqn)) {
                        hop1Neighbors.add(sourceFqn);
                    }
                }
            }

            // Hop 2: neighbors of hop1 (with limit to avoid explosion)
            int hop2Budget = 200;
            for (String fqn : hop1Neighbors) {
                if (hop2Budget <= 0) break;
                List<Map<String, Object>> outgoing = db.getOutgoingRelations(fqn, 20);
                for (Map<String, Object> rel : outgoing) {
                    String targetFqn = str(rel, "targetFqn");
                    if (targetFqn != null && !targetFqn.isEmpty()
                            && !seedFqns.contains(targetFqn)
                            && !hop1Neighbors.contains(targetFqn)) {
                        hop2Neighbors.add(targetFqn);
                        hop2Budget--;
                        if (hop2Budget <= 0) break;
                    }
                }
            }
        } catch (SQLException ignored) {
            // Graph boost is optional — proceed without it
        }
    }

    /**
     * Deduplicate scored results by file path, keeping the highest-scoring entity per file.
     */
    private static List<ScoredResult> deduplicateByFile(List<ScoredResult> scored) {
        Map<String, ScoredResult> byFile = new LinkedHashMap<>();
        for (ScoredResult sr : scored) {
            String key = sr.filePath();
            if (key == null) continue;
            ScoredResult existing = byFile.get(key);
            if (existing == null || sr.score() > existing.score()) {
                byFile.put(key, sr);
            }
        }
        List<ScoredResult> result = new ArrayList<>(byFile.values());
        Collections.sort(result);
        return result;
    }

    /**
     * Format ranked results as a markdown string.
     */
    public static String formatResults(RankedResults results) {
        StringBuilder sb = new StringBuilder();
        sb.append("Ranked search: \"").append(results.query()).append("\"\n");
        sb.append("Intent: ").append(IntentClassifier.describeIntent(results.intent()))
                .append(" | Candidates: ").append(results.totalCandidates())
                .append(" | Time: ").append(results.elapsedMs()).append("ms\n\n");

        int idx = 0;
        for (ScoredResult sr : results.results()) {
            idx++;
            sb.append(idx).append(". ");
            sb.append("[").append(sr.confidence()).append("] ");
            if (sr.entityType() != null) {
                sb.append("[").append(sr.entityType().toLowerCase()).append("] ");
            }
            sb.append("**").append(sr.name()).append("**");
            sb.append(" (score: ").append(String.format("%.2f", sr.score())).append(")\n");
            sb.append("   ").append(sr.filePath());
            if (sr.startLine() != null) sb.append(":").append(sr.startLine());
            sb.append("\n");
            if (sr.signature() != null) {
                sb.append("   `").append(sr.signature()).append("`\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}
