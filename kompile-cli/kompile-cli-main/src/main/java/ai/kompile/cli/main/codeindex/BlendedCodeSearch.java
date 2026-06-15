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
 * Blended search orchestrator that auto-detects query intent and selects the
 * optimal search strategy — or combines several — to produce the best results.
 *
 * <h3>Strategy selection</h3>
 * <table>
 *   <tr><th>Query type</th><th>Primary strategy</th><th>Fallback</th><th>Output mode</th></tr>
 *   <tr><td>Symbol path ({@code com.example.UserService})</td>
 *       <td>spath resolution</td><td>ranked search on leaf name</td><td>detail</td></tr>
 *   <tr><td>Wildcard ({@code *.Handler}, {@code **})</td>
 *       <td>spath pattern</td><td>ranked search</td><td>compressed signatures</td></tr>
 *   <tr><td>Natural language ({@code user service login})</td>
 *       <td>ranked search</td><td>spath on camelCase reconstruction</td><td>detail</td></tr>
 *   <tr><td>Broad / explore ({@code overview}, {@code what does this do})</td>
 *       <td>signatures (project-wide)</td><td>ranked search</td><td>compressed</td></tr>
 * </table>
 *
 * <p>After blending, results from multiple strategies are merged, deduplicated by
 * file+entity, and optionally compressed via {@link SignatureExtractor} when the
 * result set spans many files.</p>
 */
public class BlendedCodeSearch {

    /** Signature compression threshold: when results span this many files, compress. */
    private static final int COMPRESS_FILE_THRESHOLD = 5;

    // -----------------------------------------------------------------------
    // Query type detection
    // -----------------------------------------------------------------------

    /**
     * Detected query type that drives strategy selection.
     */
    public enum QueryType {
        /** Looks like a fully-qualified or dotted symbol path: {@code com.example.UserService} */
        SYMBOL_PATH,
        /** Contains spath wildcards: {@code *}, {@code **}, {@code *Handler} */
        WILDCARD,
        /** Natural language tokens: {@code user service handler} */
        NATURAL,
        /** Broad exploration query: {@code overview}, {@code what does this project do} */
        BROAD
    }

    /**
     * A single entry in the blended result set.
     */
    public record ResultEntry(
            String filePath,
            String name,
            String entityType,
            String signature,
            String fqn,
            Object startLine,
            double score,
            String source   // "spath", "ranked", "merged"
    ) {}

    /**
     * Full blended search result.
     */
    public record BlendedResult(
            String query,
            QueryType queryType,
            IntentClassifier.Intent intent,
            List<ResultEntry> results,
            String compressedContext,   // null when result set is small
            int totalCandidates,
            long elapsedMs
    ) {}

    /**
     * Classify a query string into a {@link QueryType}.
     */
    public static QueryType detectQueryType(String query) {
        if (query == null || query.isBlank()) return QueryType.BROAD;

        String trimmed = query.trim();

        // Wildcard patterns
        if (trimmed.contains("*")) return QueryType.WILDCARD;

        // Symbol path: contains dots and segments look like identifiers
        if (looksLikeSymbolPath(trimmed)) return QueryType.SYMBOL_PATH;

        // Broad exploration: very short or exploration-intent keywords
        IntentClassifier.Intent intent = IntentClassifier.classify(trimmed);
        if (intent == IntentClassifier.Intent.EXPLAIN) {
            // Check if the query is more about exploration than a specific entity
            List<String> tokens = CodeTokenizer.tokenize(trimmed);
            if (tokens.size() <= 2) return QueryType.BROAD;
        }

        // Check for overview/broad keywords
        String lower = trimmed.toLowerCase();
        if (lower.matches(".*(overview|summary|structure|architecture|what does this|show me everything|project layout).*")) {
            return QueryType.BROAD;
        }

        return QueryType.NATURAL;
    }

    /**
     * Check if a query looks like a symbol path (dot-separated identifiers).
     */
    static boolean looksLikeSymbolPath(String query) {
        if (!query.contains(".")) return false;
        // Must not contain spaces (natural language has spaces)
        if (query.contains(" ")) return false;
        // Each segment must be a valid identifier
        String[] parts = query.split("\\.");
        if (parts.length < 2) return false;
        for (String part : parts) {
            if (part.isEmpty()) return false;
            if (!Character.isLetter(part.charAt(0)) && part.charAt(0) != '_') return false;
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // Main search entry point
    // -----------------------------------------------------------------------

    /**
     * Perform a blended search across the index.
     *
     * @param projectId the indexed project
     * @param query     the search query (symbol path, natural language, or wildcard)
     * @param indexDir  path to the index directory
     * @param rootDir   path to the project root
     * @param topK      max results to return
     * @return blended results with optional compressed context
     */
    public static BlendedResult search(String projectId, String query,
                                        Path indexDir, Path rootDir,
                                        int topK) throws IOException {
        long t0 = System.currentTimeMillis();

        QueryType qType = detectQueryType(query);
        IntentClassifier.Intent intent = IntentClassifier.classify(query);

        List<ResultEntry> results;
        int totalCandidates;

        switch (qType) {
            case SYMBOL_PATH -> {
                SymbolSearchResult ssr = doSymbolSearch(projectId, query, indexDir, rootDir, topK);
                results = ssr.results;
                totalCandidates = ssr.totalCandidates;
            }
            case WILDCARD -> {
                WildcardSearchResult wsr = doWildcardSearch(projectId, query, indexDir, rootDir, topK);
                results = wsr.results;
                totalCandidates = wsr.totalCandidates;
            }
            case BROAD -> {
                BroadSearchResult bsr = doBroadSearch(projectId, query, indexDir, rootDir, topK);
                results = bsr.results;
                totalCandidates = bsr.totalCandidates;
            }
            default -> {
                // NATURAL — ranked search, optionally augmented by spath
                NaturalSearchResult nsr = doNaturalSearch(projectId, query, indexDir, rootDir, topK);
                results = nsr.results;
                totalCandidates = nsr.totalCandidates;
            }
        }

        // Compress output when result set spans many files
        String compressed = null;
        Set<String> uniqueFiles = results.stream()
                .map(ResultEntry::filePath)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (uniqueFiles.size() >= COMPRESS_FILE_THRESHOLD) {
            compressed = buildCompressedContext(projectId, uniqueFiles, indexDir, rootDir);
        }

        return new BlendedResult(query, qType, intent, results, compressed,
                totalCandidates, System.currentTimeMillis() - t0);
    }

    // -----------------------------------------------------------------------
    // Strategy: Symbol path → spath primary, ranked fallback
    // -----------------------------------------------------------------------

    private record SymbolSearchResult(List<ResultEntry> results, int totalCandidates) {}

    private static SymbolSearchResult doSymbolSearch(String projectId, String query,
                                                      Path indexDir, Path rootDir,
                                                      int topK) throws IOException {
        List<ResultEntry> results = new ArrayList<>();
        int totalCandidates = 0;

        // Primary: spath resolution
        try {
            SpathResolver resolver = new SpathResolver(projectId);
            SpathResolver.SpathResult spathResult = resolver.resolve(query, topK);
            totalCandidates += spathResult.totalMatches();

            for (SpathResolver.SpathMatch m : spathResult.matches()) {
                results.add(new ResultEntry(
                        m.filePath(), m.name(), m.entityType(), m.signature(),
                        m.fullyQualifiedName(), m.startLine() > 0 ? m.startLine() : null,
                        1.0, "spath"
                ));
            }
        } catch (Exception ignored) {
            // spath parse may fail for edge-case inputs
        }

        // Fallback: ranked search on the leaf segment name
        if (results.isEmpty()) {
            String leafName = extractLeafName(query);
            if (leafName != null && !leafName.isEmpty()) {
                CodeRelevanceRanker.RankedResults ranked =
                        CodeRelevanceRanker.rankedSearch(projectId, leafName,
                                indexDir, rootDir, topK);
                totalCandidates += ranked.totalCandidates();
                for (CodeRelevanceRanker.ScoredResult sr : ranked.results()) {
                    results.add(fromRanked(sr, "ranked"));
                }
            }
        } else {
            // Augment: also do a ranked search to find related entities
            String leafName = extractLeafName(query);
            if (leafName != null && !leafName.isEmpty()) {
                try {
                    CodeRelevanceRanker.RankedResults ranked =
                            CodeRelevanceRanker.rankedSearch(projectId, leafName,
                                    indexDir, rootDir, topK);
                    totalCandidates += ranked.totalCandidates();

                    Set<String> existingKeys = results.stream()
                            .map(r -> r.filePath() + ":" + r.name())
                            .collect(Collectors.toSet());

                    for (CodeRelevanceRanker.ScoredResult sr : ranked.results()) {
                        String key = sr.filePath() + ":" + sr.name();
                        if (!existingKeys.contains(key)) {
                            results.add(fromRanked(sr, "ranked"));
                            existingKeys.add(key);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        // Limit to topK
        if (results.size() > topK) results = new ArrayList<>(results.subList(0, topK));

        return new SymbolSearchResult(results, totalCandidates);
    }

    // -----------------------------------------------------------------------
    // Strategy: Wildcard → spath pattern primary, compressed output
    // -----------------------------------------------------------------------

    private record WildcardSearchResult(List<ResultEntry> results, int totalCandidates) {}

    private static WildcardSearchResult doWildcardSearch(String projectId, String query,
                                                          Path indexDir, Path rootDir,
                                                          int topK) throws IOException {
        List<ResultEntry> results = new ArrayList<>();
        int totalCandidates = 0;

        // Primary: spath pattern resolution
        try {
            SpathResolver resolver = new SpathResolver(projectId);
            SpathResolver.SpathResult spathResult = resolver.resolve(query, topK * 2);
            totalCandidates += spathResult.totalMatches();

            for (SpathResolver.SpathMatch m : spathResult.matches()) {
                results.add(new ResultEntry(
                        m.filePath(), m.name(), m.entityType(), m.signature(),
                        m.fullyQualifiedName(), m.startLine() > 0 ? m.startLine() : null,
                        1.0, "spath"
                ));
            }
        } catch (Exception ignored) {}

        // Fallback: extract non-wildcard tokens and do ranked search
        if (results.isEmpty()) {
            String cleanQuery = query.replace("*", " ").replace(".", " ").trim();
            List<String> tokens = CodeTokenizer.tokenize(cleanQuery);
            if (!tokens.isEmpty()) {
                String rankedQuery = String.join(" ", tokens);
                CodeRelevanceRanker.RankedResults ranked =
                        CodeRelevanceRanker.rankedSearch(projectId, rankedQuery,
                                indexDir, rootDir, topK);
                totalCandidates += ranked.totalCandidates();
                for (CodeRelevanceRanker.ScoredResult sr : ranked.results()) {
                    results.add(fromRanked(sr, "ranked"));
                }
            }
        }

        if (results.size() > topK) results = new ArrayList<>(results.subList(0, topK));

        return new WildcardSearchResult(results, totalCandidates);
    }

    // -----------------------------------------------------------------------
    // Strategy: Natural language → ranked search primary, spath augment
    // -----------------------------------------------------------------------

    private record NaturalSearchResult(List<ResultEntry> results, int totalCandidates) {}

    private static NaturalSearchResult doNaturalSearch(String projectId, String query,
                                                        Path indexDir, Path rootDir,
                                                        int topK) throws IOException {
        List<ResultEntry> results = new ArrayList<>();
        int totalCandidates = 0;

        // Primary: ranked search
        CodeRelevanceRanker.RankedResults ranked =
                CodeRelevanceRanker.rankedSearch(projectId, query, indexDir, rootDir, topK);
        totalCandidates += ranked.totalCandidates();

        for (CodeRelevanceRanker.ScoredResult sr : ranked.results()) {
            results.add(fromRanked(sr, "ranked"));
        }

        // Augment: try to reconstruct a symbol path from camelCase tokens
        List<String> tokens = CodeTokenizer.tokenize(query);
        if (tokens.size() >= 2) {
            // Try capitalizing first letters to form PascalCase: "user service" → "UserService"
            String pascalCase = tokens.stream()
                    .map(t -> Character.toUpperCase(t.charAt(0)) + t.substring(1))
                    .collect(Collectors.joining());

            try {
                SpathResolver resolver = new SpathResolver(projectId);
                SpathResolver.SpathResult spathResult = resolver.resolve(pascalCase, 5);
                totalCandidates += spathResult.totalMatches();

                Set<String> existingKeys = results.stream()
                        .map(r -> r.filePath() + ":" + r.name())
                        .collect(Collectors.toSet());

                for (SpathResolver.SpathMatch m : spathResult.matches()) {
                    String key = m.filePath() + ":" + m.name();
                    if (!existingKeys.contains(key)) {
                        results.add(new ResultEntry(
                                m.filePath(), m.name(), m.entityType(), m.signature(),
                                m.fullyQualifiedName(),
                                m.startLine() > 0 ? m.startLine() : null,
                                0.5, "spath"
                        ));
                        existingKeys.add(key);
                    }
                }
            } catch (Exception ignored) {}
        }

        if (results.size() > topK) results = new ArrayList<>(results.subList(0, topK));

        return new NaturalSearchResult(results, totalCandidates);
    }

    // -----------------------------------------------------------------------
    // Strategy: Broad / explore → signatures primary, ranked augment
    // -----------------------------------------------------------------------

    private record BroadSearchResult(List<ResultEntry> results, int totalCandidates) {}

    private static BroadSearchResult doBroadSearch(String projectId, String query,
                                                    Path indexDir, Path rootDir,
                                                    int topK) throws IOException {
        List<ResultEntry> results = new ArrayList<>();
        int totalCandidates = 0;

        // Do a ranked search if there are actual search terms
        List<String> tokens = CodeTokenizer.tokenize(query);
        if (!tokens.isEmpty()) {
            CodeRelevanceRanker.RankedResults ranked =
                    CodeRelevanceRanker.rankedSearch(projectId, query,
                            indexDir, rootDir, topK);
            totalCandidates += ranked.totalCandidates();

            for (CodeRelevanceRanker.ScoredResult sr : ranked.results()) {
                results.add(fromRanked(sr, "ranked"));
            }
        }

        // For broad queries, we rely more on the compressed context (built after)
        // so even an empty result set is OK — the caller gets the compressed view

        // If no ranked results, pull top entities from the index directly
        if (results.isEmpty()) {
            try (IndexDatabase db = IndexDatabase.open(indexDir)) {
                List<Map<String, Object>> topEntities =
                        db.search("*", null, topK);
                totalCandidates += topEntities.size();

                for (Map<String, Object> entity : topEntities) {
                    String type = str(entity, "entityType");
                    if ("FILE".equals(type) || "IMPORT".equals(type) || "PACKAGE".equals(type))
                        continue;
                    results.add(new ResultEntry(
                            str(entity, "filePath"), str(entity, "name"),
                            type, str(entity, "signature"),
                            str(entity, "fullyQualifiedName"),
                            entity.get("startLine"), 0.1, "index"
                    ));
                }
            } catch (SQLException e) {
                throw new IOException("Database error: " + e.getMessage(), e);
            }
        }

        if (results.size() > topK) results = new ArrayList<>(results.subList(0, topK));

        return new BroadSearchResult(results, totalCandidates);
    }

    // -----------------------------------------------------------------------
    // Compressed context builder
    // -----------------------------------------------------------------------

    /**
     * Build compressed signature context for a set of files.
     * Used when the result set spans many files and full details would be too verbose.
     */
    static String buildCompressedContext(String projectId, Set<String> filePaths,
                                          Path indexDir, Path rootDir) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Compressed context (").append(filePaths.size()).append(" files)\n\n");

        int totalSigs = 0;
        for (String filePath : filePaths) {
            SignatureExtractor.FileSignatures fs =
                    SignatureExtractor.extractFile(projectId, filePath, indexDir, rootDir);
            if (fs != null && !fs.signatures().isEmpty()) {
                sb.append("## ").append(filePath).append("\n");
                for (String sig : fs.signatures()) {
                    sb.append(sig).append("\n");
                }
                sb.append("\n");
                totalSigs += fs.signatures().size();
            }
        }

        if (totalSigs == 0) return null;

        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Formatting
    // -----------------------------------------------------------------------

    /**
     * Format blended results as markdown.
     */
    public static String formatResults(BlendedResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Blended search: \"").append(result.query()).append("\"\n");
        sb.append("Type: ").append(result.queryType())
                .append(" | Intent: ").append(IntentClassifier.describeIntent(result.intent()))
                .append(" | Candidates: ").append(result.totalCandidates())
                .append(" | Time: ").append(result.elapsedMs()).append("ms\n\n");

        if (!result.results().isEmpty()) {
            sb.append("### Results (").append(result.results().size()).append(")\n\n");
            int idx = 0;
            for (ResultEntry r : result.results()) {
                idx++;
                sb.append(idx).append(". ");
                if (r.entityType() != null) {
                    sb.append("[").append(r.entityType().toLowerCase()).append("] ");
                }
                sb.append("**").append(r.name()).append("**");
                if (r.score() > 0) sb.append(" (").append(String.format("%.2f", r.score())).append(")");
                sb.append(" via ").append(r.source());
                sb.append("\n   ").append(r.filePath());
                if (r.startLine() != null) sb.append(":").append(r.startLine());
                sb.append("\n");
                if (r.signature() != null) {
                    sb.append("   `").append(r.signature()).append("`\n");
                }
                sb.append("\n");
            }
        }

        if (result.compressedContext() != null) {
            sb.append("---\n\n").append(result.compressedContext());
        }

        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String extractLeafName(String symbolPath) {
        int lastDot = symbolPath.lastIndexOf('.');
        return lastDot >= 0 ? symbolPath.substring(lastDot + 1) : symbolPath;
    }

    private static ResultEntry fromRanked(CodeRelevanceRanker.ScoredResult sr, String source) {
        return new ResultEntry(
                sr.filePath(), sr.name(), sr.entityType(), sr.signature(),
                sr.fqn(), sr.startLine(), sr.score(), source
        );
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}
