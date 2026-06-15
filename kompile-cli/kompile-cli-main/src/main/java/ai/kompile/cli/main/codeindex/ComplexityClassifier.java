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
import java.util.regex.Pattern;

/**
 * Classifies files into complexity tiers for model cost optimization.
 * Maps files to fast/balanced/powerful tiers based on path keywords
 * and entity counts (signature density).
 *
 * <p>Inspired by sigmap's routing classifier — simple config files
 * don't need a powerful model, while security-critical or complex
 * parser code benefits from one.</p>
 *
 * <ul>
 *   <li><b>fast</b>: config, markup, trivial files (≤2 entities) — use a fast/cheap model</li>
 *   <li><b>balanced</b>: standard application code (3–11 entities) — default tier</li>
 *   <li><b>powerful</b>: security, auth, core engine, compilers, parsers (≥12 entities or ≥8 methods) — use the best model</li>
 * </ul>
 */
public class ComplexityClassifier {

    /**
     * Complexity tier for a file.
     */
    public enum Tier {
        FAST,
        BALANCED,
        POWERFUL
    }

    /**
     * Classification result for a file.
     */
    public record FileClassification(
            String filePath,
            Tier tier,
            String reason,
            int entityCount,
            int methodCount
    ) {}

    /**
     * Classification result for a project.
     */
    public record ProjectClassification(
            String projectId,
            Map<Tier, List<String>> byTier,
            int fastCount,
            int balancedCount,
            int powerfulCount
    ) {}

    // File extensions that are inherently simple
    private static final Set<String> FAST_EXTENSIONS = Set.of(
            ".json", ".yml", ".yaml", ".toml", ".env", ".ini", ".cfg",
            ".html", ".htm", ".css", ".scss", ".sass", ".less",
            ".md", ".txt", ".rst", ".adoc",
            ".sh", ".bash", ".zsh",
            ".xml", ".properties", ".conf",
            ".sql", ".graphql", ".proto",
            ".gitignore", ".dockerignore", ".editorconfig"
    );

    // Path patterns indicating high complexity
    private static final List<Pattern> POWERFUL_PATH_PATTERNS = List.of(
            Pattern.compile("/security/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("/auth/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("/crypto/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("/core/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("/engine/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("/compiler/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("/parser/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("/scheduler/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("/orchestrat", Pattern.CASE_INSENSITIVE),
            Pattern.compile("/inference/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("/optimizer/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("/kernel/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("/runtime/", Pattern.CASE_INSENSITIVE)
    );

    // Path patterns indicating simple/config files
    private static final List<Pattern> FAST_PATH_PATTERNS = List.of(
            Pattern.compile("/config/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("/configs/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("/fixtures/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("/migrations/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("/seeds/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("/scripts/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("/resources/", Pattern.CASE_INSENSITIVE)
    );

    // Filenames that are inherently simple
    private static final Set<String> FAST_FILENAMES = Set.of(
            "Dockerfile", "Makefile", "Rakefile", "Gemfile",
            "package.json", "tsconfig.json", "pom.xml", "build.gradle",
            "setup.py", "setup.cfg", "pyproject.toml",
            "Cargo.toml", "go.mod", "go.sum"
    );

    /**
     * Classify a single file based on its path and entity counts.
     */
    public static FileClassification classify(String filePath, int entityCount, int methodCount) {
        if (filePath == null) {
            return new FileClassification(filePath, Tier.BALANCED, "unknown", entityCount, methodCount);
        }

        String lower = filePath.toLowerCase();
        String fileName = filePath.contains("/") ? filePath.substring(filePath.lastIndexOf('/') + 1) : filePath;

        // Check fast filenames
        if (FAST_FILENAMES.contains(fileName)) {
            return new FileClassification(filePath, Tier.FAST, "build/config file", entityCount, methodCount);
        }

        // Check fast extensions
        for (String ext : FAST_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                if (entityCount > 4) break; // surprisingly complex for its type
                return new FileClassification(filePath, Tier.FAST, "simple file type", entityCount, methodCount);
            }
        }

        // Check powerful path patterns
        for (Pattern p : POWERFUL_PATH_PATTERNS) {
            if (p.matcher(lower).find()) {
                return new FileClassification(filePath, Tier.POWERFUL, "critical path: " + p.pattern(),
                        entityCount, methodCount);
            }
        }

        // Check fast path patterns (only if low entity count)
        if (entityCount <= 4) {
            for (Pattern p : FAST_PATH_PATTERNS) {
                if (p.matcher(lower).find()) {
                    return new FileClassification(filePath, Tier.FAST, "config/resource path",
                            entityCount, methodCount);
                }
            }
        }

        // Classify by entity density
        if (entityCount >= 12 || methodCount >= 8) {
            return new FileClassification(filePath, Tier.POWERFUL, "high complexity (" +
                    entityCount + " entities, " + methodCount + " methods)", entityCount, methodCount);
        }

        if (entityCount <= 2) {
            return new FileClassification(filePath, Tier.FAST, "trivial (" +
                    entityCount + " entities)", entityCount, methodCount);
        }

        return new FileClassification(filePath, Tier.BALANCED, "standard complexity (" +
                entityCount + " entities)", entityCount, methodCount);
    }

    /**
     * Classify all files in a project using the index database.
     */
    public static ProjectClassification classifyProject(String projectId,
                                                         Path indexDir) throws IOException {
        Map<Tier, List<String>> byTier = new EnumMap<>(Tier.class);
        byTier.put(Tier.FAST, new ArrayList<>());
        byTier.put(Tier.BALANCED, new ArrayList<>());
        byTier.put(Tier.POWERFUL, new ArrayList<>());

        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            Set<String> relPaths = db.getAllRelPaths();

            for (String relPath : relPaths) {
                List<Map<String, Object>> entities = db.getEntitiesForFile(relPath);
                int entityCount = 0;
                int methodCount = 0;

                for (Map<String, Object> entity : entities) {
                    String type = (String) entity.getOrDefault("entityType", "");
                    if (!"FILE".equals(type) && !"PACKAGE".equals(type) && !"IMPORT".equals(type)) {
                        entityCount++;
                    }
                    if ("METHOD".equals(type) || "FUNCTION".equals(type)) {
                        methodCount++;
                    }
                }

                FileClassification fc = classify(relPath, entityCount, methodCount);
                byTier.get(fc.tier()).add(relPath);
            }
        } catch (SQLException e) {
            throw new IOException("Database error: " + e.getMessage(), e);
        }

        return new ProjectClassification(
                projectId, byTier,
                byTier.get(Tier.FAST).size(),
                byTier.get(Tier.BALANCED).size(),
                byTier.get(Tier.POWERFUL).size()
        );
    }

    /**
     * Format classification results as markdown.
     */
    public static String formatClassification(ProjectClassification pc) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Model Routing: ").append(pc.projectId()).append("\n\n");

        int total = pc.fastCount() + pc.balancedCount() + pc.powerfulCount();
        sb.append("| Tier | Files | % |\n");
        sb.append("|------|-------|---|\n");
        sb.append(String.format("| fast | %d | %.0f%% |\n", pc.fastCount(),
                total > 0 ? (double) pc.fastCount() / total * 100 : 0));
        sb.append(String.format("| balanced | %d | %.0f%% |\n", pc.balancedCount(),
                total > 0 ? (double) pc.balancedCount() / total * 100 : 0));
        sb.append(String.format("| powerful | %d | %.0f%% |\n", pc.powerfulCount(),
                total > 0 ? (double) pc.powerfulCount() / total * 100 : 0));

        if (!pc.byTier().get(Tier.POWERFUL).isEmpty()) {
            sb.append("\n### Powerful tier (use best model)\n");
            for (String f : pc.byTier().get(Tier.POWERFUL)) {
                sb.append("- ").append(f).append("\n");
            }
        }

        return sb.toString();
    }
}
