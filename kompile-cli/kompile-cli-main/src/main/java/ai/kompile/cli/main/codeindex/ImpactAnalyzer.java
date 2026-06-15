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

/**
 * BFS blast-radius analysis over the reverse dependency graph.
 * Given a changed file, determines which other files are impacted:
 * direct importers, transitive importers, affected tests, and affected routes.
 *
 * <p>Uses the existing {@link IndexDatabase} relations table (IMPORTS, CALLS,
 * EXTENDS, IMPLEMENTS) to traverse the dependency graph in reverse.</p>
 *
 * <p>Inspired by sigmap's impact analysis — BFS over reverse graph with
 * configurable depth, cycle safety, and categorized output.</p>
 */
public class ImpactAnalyzer {

    /**
     * Impact analysis result for a single changed file.
     */
    public record FileImpact(
            String changedFile,
            List<String> directDependents,     // Files that directly import/use the changed file
            List<String> transitiveDependents, // Files reached at depth > 1
            List<String> affectedTests,        // Test files in the impact set
            List<String> affectedRoutes,       // Route/controller files in the impact set
            int totalImpact                    // Total unique files impacted
    ) {}

    /**
     * Aggregate impact analysis for multiple changed files.
     */
    public record ImpactReport(
            List<FileImpact> fileImpacts,
            Set<String> allImpacted,
            Set<String> allAffectedTests,
            Set<String> allAffectedRoutes,
            int totalUniqueImpact
    ) {}

    /**
     * Analyze the impact of changing a single file.
     *
     * @param relPath  the relative path of the changed file
     * @param indexDir path to the index directory
     * @param maxDepth maximum BFS depth (0 = unlimited)
     * @return impact analysis result
     */
    public static FileImpact analyzeFile(String relPath, Path indexDir,
                                          int maxDepth) throws IOException {
        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            return analyzeFileWithDb(db, relPath, maxDepth);
        } catch (SQLException e) {
            throw new IOException("Database error: " + e.getMessage(), e);
        }
    }

    /**
     * Analyze the impact of changing multiple files.
     */
    public static ImpactReport analyzeFiles(List<String> relPaths, Path indexDir,
                                             int maxDepth) throws IOException {
        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            List<FileImpact> impacts = new ArrayList<>();
            Set<String> allImpacted = new LinkedHashSet<>();
            Set<String> allTests = new LinkedHashSet<>();
            Set<String> allRoutes = new LinkedHashSet<>();

            for (String relPath : relPaths) {
                FileImpact impact = analyzeFileWithDb(db, relPath, maxDepth);
                impacts.add(impact);
                allImpacted.addAll(impact.directDependents());
                allImpacted.addAll(impact.transitiveDependents());
                allTests.addAll(impact.affectedTests());
                allRoutes.addAll(impact.affectedRoutes());
            }

            return new ImpactReport(impacts, allImpacted, allTests, allRoutes, allImpacted.size());
        } catch (SQLException e) {
            throw new IOException("Database error: " + e.getMessage(), e);
        }
    }

    /**
     * Core BFS implementation using an open database connection.
     */
    private static FileImpact analyzeFileWithDb(IndexDatabase db, String relPath,
                                                  int maxDepth) throws SQLException {
        // Find all FQNs defined in the changed file
        List<Map<String, Object>> entities = db.getEntitiesForFile(relPath);
        Set<String> fileFqns = new LinkedHashSet<>();
        for (Map<String, Object> entity : entities) {
            String fqn = (String) entity.get("fullyQualifiedName");
            if (fqn != null && !fqn.isEmpty()) fileFqns.add(fqn);
        }

        if (fileFqns.isEmpty()) {
            return new FileImpact(relPath, List.of(), List.of(), List.of(), List.of(), 0);
        }

        // BFS over reverse dependency graph
        Set<String> visited = new LinkedHashSet<>();  // all visited file paths
        visited.add(relPath);

        List<String> directDependents = new ArrayList<>();
        List<String> transitiveDependents = new ArrayList<>();

        // Seed: find all files that import/reference any FQN from the changed file
        Set<String> currentFrontier = new LinkedHashSet<>();
        for (String fqn : fileFqns) {
            List<Map<String, Object>> incoming = db.getIncomingRelations(fqn, 500);
            for (Map<String, Object> rel : incoming) {
                String sourceFile = (String) rel.get("filePath");
                if (sourceFile != null && !sourceFile.isEmpty() && visited.add(sourceFile)) {
                    directDependents.add(sourceFile);
                    currentFrontier.add(sourceFile);
                }
            }
        }

        // BFS at deeper levels
        int depth = 1;
        int effectiveMaxDepth = maxDepth > 0 ? maxDepth : 20; // safety limit

        while (!currentFrontier.isEmpty() && depth < effectiveMaxDepth) {
            Set<String> nextFrontier = new LinkedHashSet<>();
            for (String depFile : currentFrontier) {
                // Get FQNs defined in this dependent file
                List<Map<String, Object>> depEntities = db.getEntitiesForFile(depFile);
                for (Map<String, Object> de : depEntities) {
                    String depFqn = (String) de.get("fullyQualifiedName");
                    if (depFqn == null || depFqn.isEmpty()) continue;

                    // Find who imports this dependent
                    List<Map<String, Object>> incoming = db.getIncomingRelations(depFqn, 200);
                    for (Map<String, Object> rel : incoming) {
                        String sourceFile = (String) rel.get("filePath");
                        if (sourceFile != null && !sourceFile.isEmpty() && visited.add(sourceFile)) {
                            transitiveDependents.add(sourceFile);
                            nextFrontier.add(sourceFile);
                        }
                    }
                }
            }
            currentFrontier = nextFrontier;
            depth++;
        }

        // Categorize impacted files
        Set<String> allImpacted = new LinkedHashSet<>();
        allImpacted.addAll(directDependents);
        allImpacted.addAll(transitiveDependents);

        List<String> affectedTests = new ArrayList<>();
        List<String> affectedRoutes = new ArrayList<>();

        for (String file : allImpacted) {
            if (isTestFile(file)) affectedTests.add(file);
            if (isRouteFile(file)) affectedRoutes.add(file);
        }

        return new FileImpact(relPath, directDependents, transitiveDependents,
                affectedTests, affectedRoutes, allImpacted.size());
    }

    /**
     * Check if a file path looks like a test file.
     */
    private static boolean isTestFile(String path) {
        String lower = path.toLowerCase();
        return lower.contains("/test/") || lower.contains("/tests/")
                || lower.contains("/__tests__/") || lower.contains("/spec/")
                || lower.endsWith("test.java") || lower.endsWith("test.py")
                || lower.endsWith("test.js") || lower.endsWith("test.ts")
                || lower.endsWith("_test.go") || lower.endsWith("spec.rb")
                || lower.endsWith("test.kt") || lower.endsWith("spec.js")
                || lower.endsWith("spec.ts");
    }

    /**
     * Check if a file path looks like a route/controller/endpoint file.
     */
    private static boolean isRouteFile(String path) {
        String lower = path.toLowerCase();
        return lower.contains("/route") || lower.contains("/controller")
                || lower.contains("/endpoint") || lower.contains("/handler")
                || lower.contains("/api/") || lower.contains("/rest/");
    }

    /**
     * Format a single file impact as markdown.
     */
    public static String formatFileImpact(FileImpact impact) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Impact: ").append(impact.changedFile()).append("\n\n");
        sb.append("Total impact: **").append(impact.totalImpact()).append(" files**\n\n");

        if (!impact.directDependents().isEmpty()) {
            sb.append("### Direct dependents (").append(impact.directDependents().size()).append(")\n");
            for (String f : impact.directDependents()) {
                sb.append("- ").append(f).append("\n");
            }
            sb.append("\n");
        }

        if (!impact.transitiveDependents().isEmpty()) {
            sb.append("### Transitive dependents (").append(impact.transitiveDependents().size()).append(")\n");
            for (String f : impact.transitiveDependents()) {
                sb.append("- ").append(f).append("\n");
            }
            sb.append("\n");
        }

        if (!impact.affectedTests().isEmpty()) {
            sb.append("### Affected tests (").append(impact.affectedTests().size()).append(")\n");
            for (String f : impact.affectedTests()) {
                sb.append("- ").append(f).append("\n");
            }
            sb.append("\n");
        }

        if (!impact.affectedRoutes().isEmpty()) {
            sb.append("### Affected routes/controllers (").append(impact.affectedRoutes().size()).append(")\n");
            for (String f : impact.affectedRoutes()) {
                sb.append("- ").append(f).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Format a full impact report as markdown.
     */
    public static String formatReport(ImpactReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Impact Analysis Report\n\n");
        sb.append("Changed files: ").append(report.fileImpacts().size())
                .append(" | Total unique impact: **").append(report.totalUniqueImpact())
                .append(" files**\n\n");

        if (!report.allAffectedTests().isEmpty()) {
            sb.append("Tests to run: ").append(report.allAffectedTests().size()).append("\n");
        }
        if (!report.allAffectedRoutes().isEmpty()) {
            sb.append("Routes affected: ").append(report.allAffectedRoutes().size()).append("\n");
        }
        sb.append("\n");

        for (FileImpact fi : report.fileImpacts()) {
            sb.append(formatFileImpact(fi));
        }

        return sb.toString();
    }
}
