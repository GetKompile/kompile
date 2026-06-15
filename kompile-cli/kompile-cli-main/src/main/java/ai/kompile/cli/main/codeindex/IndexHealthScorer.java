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

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Scores the health and quality of a code index on a 0–100 scale.
 * Composite scoring based on:
 * <ul>
 *   <li>Index freshness (staleness penalty: -4 pts/day past 7 days, max -30)</li>
 *   <li>Entity coverage (entities per file ratio)</li>
 *   <li>Relation density (relations per entity ratio)</li>
 *   <li>Signature completeness (% of entities with stored signatures)</li>
 *   <li>Language coverage (# of languages indexed)</li>
 * </ul>
 *
 * <p>Grade scale: A ≥ 90, B ≥ 75, C ≥ 60, D &lt; 60</p>
 *
 * <p>Inspired by sigmap's health scorer — tracks context freshness
 * and quality metrics to guide when to re-index.</p>
 */
public class IndexHealthScorer {

    /**
     * Health score result.
     */
    public record HealthScore(
            int score,
            String grade,
            String projectId,
            long daysSinceIndex,
            int totalFiles,
            int totalEntities,
            int totalRelations,
            double entitiesPerFile,
            double relationsPerEntity,
            double signatureCompleteness,
            int languageCount,
            Map<String, String> issues
    ) {}

    /**
     * Score the health of a project's code index.
     *
     * @param projectId the project identifier
     * @param indexDir  path to the index directory
     * @return health score with breakdown
     */
    public static HealthScore score(String projectId, Path indexDir) {
        int score = 100;
        Map<String, String> issues = new LinkedHashMap<>();

        // Load metadata
        Path metaFile = indexDir.resolve("metadata.json");
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (Files.exists(metaFile)) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = new ObjectMapper().readValue(metaFile.toFile(), Map.class);
                metadata = m;
            } catch (IOException ignored) {}
        }

        // 1. Staleness penalty (-4 pts/day past 7 days, max -30)
        String indexedAt = (String) metadata.get("indexedAt");
        long daysSinceIndex = -1;
        if (indexedAt != null && !indexedAt.isEmpty()) {
            try {
                Instant indexed = Instant.parse(indexedAt);
                daysSinceIndex = Duration.between(indexed, Instant.now()).toDays();
                if (daysSinceIndex > 7) {
                    int penalty = (int) Math.min((daysSinceIndex - 7) * 4, 30);
                    score -= penalty;
                    issues.put("staleness", "Index is " + daysSinceIndex + " days old (-" + penalty + " pts)");
                }
            } catch (Exception ignored) {}
        } else {
            score -= 30;
            issues.put("staleness", "No index timestamp found (-30 pts)");
        }

        // Get live stats from DB
        int totalFiles = 0;
        int totalEntities = 0;
        int totalRelations = 0;
        int entitiesWithSignature = 0;
        int languageCount = 0;

        if (Files.exists(indexDir.resolve("index.db"))) {
            try (IndexDatabase db = IndexDatabase.open(indexDir)) {
                totalFiles = db.getFileCount();
                totalEntities = db.getEntityCount();

                Map<String, Object> graphStats = db.getGraphStats();
                Object relCount = graphStats.get("totalRelations");
                if (relCount instanceof Number) totalRelations = ((Number) relCount).intValue();

                Map<String, Integer> langCounts = db.getLanguageCounts();
                languageCount = langCounts.size();

                // Count entities with signatures
                entitiesWithSignature = countEntitiesWithSignature(db);
            } catch (SQLException ignored) {}
        }

        // 2. Entity coverage penalty
        double entitiesPerFile = totalFiles > 0 ? (double) totalEntities / totalFiles : 0;
        if (totalFiles > 0 && entitiesPerFile < 2.0) {
            int penalty = (int) Math.min((2.0 - entitiesPerFile) * 10, 15);
            score -= penalty;
            issues.put("coverage", String.format("Low entity density: %.1f/file (-" + penalty + " pts)",
                    entitiesPerFile));
        }

        // 3. Relation density penalty
        double relationsPerEntity = totalEntities > 0 ? (double) totalRelations / totalEntities : 0;
        if (totalEntities > 10 && relationsPerEntity < 0.5) {
            int penalty = (int) Math.min((0.5 - relationsPerEntity) * 20, 10);
            score -= penalty;
            issues.put("relations", String.format("Low relation density: %.2f/entity (-" + penalty + " pts)",
                    relationsPerEntity));
        }

        // 4. Signature completeness penalty
        // Exclude FILE, PACKAGE, IMPORT entities from the calculation
        int significantEntities = Math.max(1, totalEntities - totalFiles * 3); // rough estimate
        double signatureCompleteness = significantEntities > 0
                ? (double) entitiesWithSignature / significantEntities : 0;
        signatureCompleteness = Math.min(1.0, signatureCompleteness); // clamp to 1.0

        if (significantEntities > 10 && signatureCompleteness < 0.5) {
            int penalty = (int) Math.min((0.5 - signatureCompleteness) * 20, 10);
            score -= penalty;
            issues.put("signatures", String.format("Low signature coverage: %.0f%% (-" + penalty + " pts)",
                    signatureCompleteness * 100));
        }

        // 5. Empty index penalty
        if (totalFiles == 0) {
            score = 0;
            issues.put("empty", "No files indexed");
        }

        // Clamp score
        score = Math.max(0, Math.min(100, score));

        String grade;
        if (score >= 90) grade = "A";
        else if (score >= 75) grade = "B";
        else if (score >= 60) grade = "C";
        else grade = "D";

        return new HealthScore(
                score, grade, projectId, daysSinceIndex,
                totalFiles, totalEntities, totalRelations,
                entitiesPerFile, relationsPerEntity, signatureCompleteness,
                languageCount, issues
        );
    }

    /**
     * Count entities that have a non-null, non-empty signature.
     */
    private static int countEntitiesWithSignature(IndexDatabase db) throws SQLException {
        // This is a DB query — we access it through a package-private method
        // Since IndexDatabase doesn't expose this directly, we use a search trick
        try {
            // Count entities where signature is not empty
            java.lang.reflect.Field connField = IndexDatabase.class.getDeclaredField("conn");
            connField.setAccessible(true);
            java.sql.Connection conn = (java.sql.Connection) connField.get(db);

            try (java.sql.Statement stmt = conn.createStatement();
                 java.sql.ResultSet rs = stmt.executeQuery(
                         "SELECT COUNT(*) FROM entities_meta WHERE signature IS NOT NULL AND signature != ''")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Format health score as markdown.
     */
    public static String formatHealth(HealthScore hs) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Index Health: ").append(hs.projectId()).append("\n\n");
        sb.append("**Score: ").append(hs.score()).append("/100 (Grade: ").append(hs.grade()).append(")**\n\n");

        sb.append("| Metric | Value |\n");
        sb.append("|--------|-------|\n");
        sb.append("| Files indexed | ").append(hs.totalFiles()).append(" |\n");
        sb.append("| Total entities | ").append(hs.totalEntities()).append(" |\n");
        sb.append("| Total relations | ").append(hs.totalRelations()).append(" |\n");
        sb.append(String.format("| Entities/file | %.1f |\n", hs.entitiesPerFile()));
        sb.append(String.format("| Relations/entity | %.2f |\n", hs.relationsPerEntity()));
        sb.append(String.format("| Signature coverage | %.0f%% |\n", hs.signatureCompleteness() * 100));
        sb.append("| Languages | ").append(hs.languageCount()).append(" |\n");
        if (hs.daysSinceIndex() >= 0) {
            sb.append("| Days since index | ").append(hs.daysSinceIndex()).append(" |\n");
        }

        if (!hs.issues().isEmpty()) {
            sb.append("\n### Issues\n");
            for (Map.Entry<String, String> issue : hs.issues().entrySet()) {
                sb.append("- **").append(issue.getKey()).append("**: ").append(issue.getValue()).append("\n");
            }
        }

        if (hs.score() < 75) {
            sb.append("\n### Recommendations\n");
            if (hs.daysSinceIndex() > 7) {
                sb.append("- Re-index the project: `local_code_index action='index'`\n");
            }
            if (hs.entitiesPerFile() < 2.0) {
                sb.append("- Some files may not have extractable entities. Check include patterns.\n");
            }
            if (hs.relationsPerEntity() < 0.5) {
                sb.append("- Low relation density. Re-index to refresh cross-file FQN resolution.\n");
            }
        }

        return sb.toString();
    }
}
