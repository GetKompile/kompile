/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package ai.kompile.cli.main.codeindex;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects unused exports (dead code) by analyzing the dependency graph.
 * Finds symbols that are exported/public but never referenced from other files,
 * identifying opportunities for code cleanup.
 *
 * <p>Detection categories:
 * <ul>
 *   <li><b>Dead files</b> — all exports unused, no other file depends on them</li>
 *   <li><b>Dead exports</b> — individual symbols never referenced externally</li>
 *   <li><b>Test-only exports</b> — only referenced from test files</li>
 *   <li><b>Unnecessary exports</b> — used internally but never imported externally (export keyword removable)</li>
 *   <li><b>Dead barrels</b> — index/init/__init__.py files nobody imports through</li>
 * </ul></p>
 *
 * <p>Inspired by soulforge's unused_exports analysis, adapted for Java and
 * the kompile relation graph model.</p>
 */
public class UnusedExportDetector {

    // Languages where import tracking is reliable enough for dead-code detection
    private static final Set<String> IMPORT_TRACKABLE_LANGUAGES = Set.of(
            "java", "kotlin", "typescript", "javascript", "python", "go",
            "rust", "csharp", "scala", "dart", "swift", "php", "ruby"
    );

    // Barrel file patterns (re-export aggregators)
    private static final Set<String> BARREL_FILENAMES = Set.of(
            "index.ts", "index.js", "index.tsx", "index.mts", "index.mjs",
            "__init__.py", "mod.rs", "package-info.java"
    );

    private UnusedExportDetector() {}

    /**
     * Detect all unused exports in the project.
     *
     * @param indexDir project index directory
     * @param rootDir  project root
     * @return detection results
     */
    public static UnusedExportReport detect(Path indexDir, Path rootDir) throws IOException {
        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            return detectInternal(db, rootDir);
        } catch (SQLException e) {
            throw new IOException("Unused export detection failed: " + e.getMessage(), e);
        }
    }

    // --- Data classes ---

    public record UnusedExport(
            String filePath,
            String name,
            String entityType,
            int startLine,
            int endLine,
            String language,
            ExportStatus status
    ) {}

    public enum ExportStatus {
        DEAD,           // Never referenced anywhere
        TEST_ONLY,      // Only referenced from test files
        INTERNAL_ONLY   // Used within the same file, but never imported externally
    }

    public record DeadFile(
            String filePath,
            String language,
            int lineCount,
            List<String> exportedSymbols
    ) {}

    public record DeadBarrel(
            String filePath,
            int lineCount
    ) {}

    public record UnusedExportReport(
            List<DeadFile> deadFiles,
            List<DeadBarrel> deadBarrels,
            List<UnusedExport> deadExports,
            List<UnusedExport> testOnlyExports,
            List<UnusedExport> internalOnlyExports,
            int totalExportsAnalyzed,
            long elapsedMs
    ) {}

    // --- Internal ---

    private static UnusedExportReport detectInternal(IndexDatabase db, Path rootDir)
            throws SQLException {
        long start = System.currentTimeMillis();
        Connection conn = db.getConnection();

        // Step 1: Get all exported/public entities (CLASS, INTERFACE, FUNCTION, METHOD with public visibility)
        List<ExportedEntity> allExports = getExportedEntities(conn);

        // Step 2: Build reverse-reference map: which FQNs are referenced from which files
        Map<String, Set<String>> referencedFrom = buildReferenceMap(conn);

        // Step 3: Classify each export
        List<UnusedExport> deadExports = new ArrayList<>();
        List<UnusedExport> testOnlyExports = new ArrayList<>();
        List<UnusedExport> internalOnlyExports = new ArrayList<>();

        for (ExportedEntity export : allExports) {
            Set<String> referencingFiles = referencedFrom.getOrDefault(export.fqn, Set.of());

            // Remove self-references (same file)
            Set<String> externalRefs = referencingFiles.stream()
                    .filter(f -> !f.equals(export.relPath))
                    .collect(Collectors.toSet());

            // Also check if referenced by name (not just FQN)
            Set<String> nameRefs = referencedFrom.getOrDefault(export.name, Set.of());
            Set<String> externalNameRefs = nameRefs.stream()
                    .filter(f -> !f.equals(export.relPath))
                    .collect(Collectors.toSet());

            externalRefs.addAll(externalNameRefs);

            if (externalRefs.isEmpty()) {
                // Check if used internally (within same file)
                boolean usedInternally = referencingFiles.contains(export.relPath) ||
                        nameRefs.contains(export.relPath);

                if (usedInternally) {
                    internalOnlyExports.add(new UnusedExport(
                            export.relPath, export.name, export.entityType,
                            export.startLine, export.endLine, export.language,
                            ExportStatus.INTERNAL_ONLY));
                } else {
                    deadExports.add(new UnusedExport(
                            export.relPath, export.name, export.entityType,
                            export.startLine, export.endLine, export.language,
                            ExportStatus.DEAD));
                }
            } else {
                // Check if all references are from test files
                boolean allFromTests = externalRefs.stream().allMatch(UnusedExportDetector::isTestFile);
                if (allFromTests) {
                    testOnlyExports.add(new UnusedExport(
                            export.relPath, export.name, export.entityType,
                            export.startLine, export.endLine, export.language,
                            ExportStatus.TEST_ONLY));
                }
            }
        }

        // Step 4: Identify dead files (all exports are dead, file has no dependents)
        List<DeadFile> deadFiles = identifyDeadFiles(conn, deadExports, allExports, rootDir);

        // Step 5: Identify dead barrels
        List<DeadBarrel> deadBarrels = identifyDeadBarrels(conn);

        long elapsed = System.currentTimeMillis() - start;
        return new UnusedExportReport(
                deadFiles, deadBarrels, deadExports, testOnlyExports, internalOnlyExports,
                allExports.size(), elapsed);
    }

    private static List<ExportedEntity> getExportedEntities(Connection conn) throws SQLException {
        List<ExportedEntity> exports = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     SELECT rel_path, name, fqn, entity_type, language, start_line, end_line, visibility
                     FROM entities_meta
                     WHERE entity_type IN ('CLASS', 'INTERFACE', 'FUNCTION', 'METHOD', 'ENUM', 'RECORD', 'CONSTANT')
                     AND (visibility IS NULL OR visibility IN ('public', 'export', 'pub', ''))
                     AND entity_type != 'IMPORT'
                     AND entity_type != 'PACKAGE'
                     AND entity_type != 'FILE'
                     """)) {
            while (rs.next()) {
                String lang = rs.getString("language");
                // Only analyze languages where we can reliably track imports
                if (!IMPORT_TRACKABLE_LANGUAGES.contains(lang)) continue;

                exports.add(new ExportedEntity(
                        rs.getString("rel_path"),
                        rs.getString("name"),
                        rs.getString("fqn"),
                        rs.getString("entity_type"),
                        lang,
                        rs.getInt("start_line"),
                        rs.getInt("end_line")));
            }
        }
        return exports;
    }

    /**
     * Build a map of FQN/name → set of files that reference it.
     * Uses the relations table (IMPORTS, CALLS, EXTENDS, IMPLEMENTS).
     */
    private static Map<String, Set<String>> buildReferenceMap(Connection conn) throws SQLException {
        Map<String, Set<String>> refs = new HashMap<>();

        // From relations: target_fqn referenced from file_path
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT target_fqn, target_name, file_path FROM relations " +
                             "WHERE relation_type IN ('IMPORTS', 'CALLS', 'EXTENDS', 'IMPLEMENTS')")) {
            while (rs.next()) {
                String targetFqn = rs.getString("target_fqn");
                String targetName = rs.getString("target_name");
                String filePath = rs.getString("file_path");

                if (targetFqn != null && !targetFqn.isEmpty()) {
                    refs.computeIfAbsent(targetFqn, k -> new HashSet<>()).add(filePath);
                }
                if (targetName != null && !targetName.isEmpty()) {
                    refs.computeIfAbsent(targetName, k -> new HashSet<>()).add(filePath);
                }
            }
        }

        return refs;
    }

    private static List<DeadFile> identifyDeadFiles(Connection conn, List<UnusedExport> deadExports,
                                                     List<ExportedEntity> allExports, Path rootDir) throws SQLException {
        // Group dead exports by file
        Map<String, List<UnusedExport>> deadByFile = deadExports.stream()
                .collect(Collectors.groupingBy(UnusedExport::filePath));

        // Group all exports by file
        Map<String, List<ExportedEntity>> allByFile = allExports.stream()
                .collect(Collectors.groupingBy(e -> e.relPath));

        List<DeadFile> deadFiles = new ArrayList<>();

        for (Map.Entry<String, List<UnusedExport>> entry : deadByFile.entrySet()) {
            String filePath = entry.getKey();
            List<UnusedExport> fileDeadExports = entry.getValue();
            List<ExportedEntity> fileAllExports = allByFile.getOrDefault(filePath, List.of());

            // A file is "dead" if ALL its exports are dead
            if (fileDeadExports.size() >= fileAllExports.size() && !fileAllExports.isEmpty()) {
                // Verify no other file has a relation pointing to this file
                boolean hasDependents = hasFileDependents(conn, filePath);
                if (!hasDependents) {
                    int lineCount = fileAllExports.stream()
                            .mapToInt(e -> e.endLine)
                            .max().orElse(0);
                    List<String> symbols = fileAllExports.stream()
                            .map(e -> e.name)
                            .collect(Collectors.toList());
                    deadFiles.add(new DeadFile(filePath, fileAllExports.get(0).language, lineCount, symbols));
                }
            }
        }

        return deadFiles;
    }

    private static boolean hasFileDependents(Connection conn, String filePath) throws SQLException {
        // Check if any relation targets entities in this file
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT COUNT(*) FROM relations r
                JOIN entities_meta e ON r.target_fqn = e.fqn
                WHERE e.rel_path = ? AND r.file_path != ?
                LIMIT 1""")) {
            ps.setString(1, filePath);
            ps.setString(2, filePath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private static List<DeadBarrel> identifyDeadBarrels(Connection conn) throws SQLException {
        List<DeadBarrel> barrels = new ArrayList<>();

        // Find barrel files (index.ts, __init__.py, etc.)
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT DISTINCT rel_path FROM entities_meta WHERE rel_path LIKE '%/index.ts' " +
                             "OR rel_path LIKE '%/index.js' OR rel_path LIKE '%/index.tsx' " +
                             "OR rel_path LIKE '%/__init__.py' OR rel_path LIKE '%/mod.rs'")) {
            while (rs.next()) {
                String barrelPath = rs.getString("rel_path");

                // Check if anything imports from this barrel
                boolean hasImporters = false;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM relations WHERE target_fqn LIKE ? AND file_path != ?")) {
                    ps.setString(1, barrelPath.replace(".ts", "").replace(".js", "")
                            .replace(".tsx", "").replace("/__init__.py", "")
                            .replace("/mod.rs", "") + "%");
                    ps.setString(2, barrelPath);
                    try (ResultSet innerRs = ps.executeQuery()) {
                        hasImporters = innerRs.next() && innerRs.getInt(1) > 0;
                    }
                }

                if (!hasImporters) {
                    // Estimate line count
                    int lineCount = 0;
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT MAX(end_line) FROM entities_meta WHERE rel_path = ?")) {
                        ps.setString(1, barrelPath);
                        try (ResultSet innerRs = ps.executeQuery()) {
                            if (innerRs.next()) lineCount = innerRs.getInt(1);
                        }
                    }
                    barrels.add(new DeadBarrel(barrelPath, lineCount));
                }
            }
        }

        return barrels;
    }

    private static boolean isTestFile(String path) {
        String lower = path.toLowerCase();
        return lower.contains("/test/") || lower.contains("/tests/") ||
                lower.contains("/__tests__/") || lower.contains("/spec/") ||
                lower.endsWith("test.java") || lower.endsWith("test.kt") ||
                lower.endsWith("test.py") || lower.endsWith("test.js") ||
                lower.endsWith("test.ts") || lower.endsWith("_test.go") ||
                lower.endsWith("spec.rb") || lower.endsWith("spec.js") ||
                lower.endsWith("spec.ts") || lower.endsWith(".test.ts") ||
                lower.endsWith(".test.js") || lower.endsWith(".spec.ts") ||
                lower.endsWith(".spec.js");
    }

    /**
     * Format detection results for display.
     */
    public static String formatReport(UnusedExportReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("Unused Export Detection Report\n\n");
        sb.append(String.format("- Exports analyzed: %d\n", report.totalExportsAnalyzed()));
        sb.append(String.format("- Dead files: %d\n", report.deadFiles().size()));
        sb.append(String.format("- Dead barrels: %d\n", report.deadBarrels().size()));
        sb.append(String.format("- Dead exports: %d\n", report.deadExports().size()));
        sb.append(String.format("- Test-only exports: %d\n", report.testOnlyExports().size()));
        sb.append(String.format("- Internal-only exports: %d\n", report.internalOnlyExports().size()));
        sb.append(String.format("- Elapsed: %dms\n\n", report.elapsedMs()));

        if (!report.deadFiles().isEmpty()) {
            sb.append("Dead files (all exports unused, no dependents):\n\n");
            for (DeadFile df : report.deadFiles()) {
                sb.append(String.format("  %s  (%dL, %d exports)\n", df.filePath(), df.lineCount(), df.exportedSymbols().size()));
                for (String sym : df.exportedSymbols()) {
                    sb.append(String.format("    - %s\n", sym));
                }
            }
            sb.append("\n");
        }

        if (!report.deadBarrels().isEmpty()) {
            sb.append("Dead barrels (nothing imports through them):\n\n");
            for (DeadBarrel db : report.deadBarrels()) {
                sb.append(String.format("  %s  (%dL)\n", db.filePath(), db.lineCount()));
            }
            sb.append("\n");
        }

        if (!report.deadExports().isEmpty()) {
            sb.append(String.format("Dead exports (%d — never referenced externally):\n\n", report.deadExports().size()));
            // Group by file
            Map<String, List<UnusedExport>> byFile = report.deadExports().stream()
                    .collect(Collectors.groupingBy(UnusedExport::filePath));
            for (Map.Entry<String, List<UnusedExport>> entry : byFile.entrySet()) {
                sb.append(String.format("  %s\n", entry.getKey()));
                for (UnusedExport ue : entry.getValue()) {
                    sb.append(String.format("    %s %s :%d-%d\n", ue.entityType(), ue.name(), ue.startLine(), ue.endLine()));
                }
            }
            sb.append("\n");
        }

        if (!report.testOnlyExports().isEmpty()) {
            sb.append(String.format("Test-only exports (%d — only imported by test files):\n\n", report.testOnlyExports().size()));
            Map<String, List<UnusedExport>> byFile = report.testOnlyExports().stream()
                    .collect(Collectors.groupingBy(UnusedExport::filePath));
            for (Map.Entry<String, List<UnusedExport>> entry : byFile.entrySet()) {
                sb.append(String.format("  %s\n", entry.getKey()));
                for (UnusedExport ue : entry.getValue()) {
                    sb.append(String.format("    %s %s :%d-%d\n", ue.entityType(), ue.name(), ue.startLine(), ue.endLine()));
                }
            }
            sb.append("\n");
        }

        sb.append("Note: dynamic imports, reflection, and framework annotations not tracked. Verify before removing.");
        return sb.toString();
    }

    // --- Internal records ---

    private record ExportedEntity(String relPath, String name, String fqn,
                                   String entityType, String language, int startLine, int endLine) {}
}
