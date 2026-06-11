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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;

/**
 * Extracts clean, minimal function/class/method signatures from indexed code entities.
 * Produces signature-only views of files, achieving 70-95% token reduction compared
 * to full source — similar to sigmap's signature extraction approach.
 *
 * <p>Works with the existing {@link IndexDatabase} to retrieve entity metadata and
 * format compact signature maps per file.</p>
 */
public class SignatureExtractor {

    private static final int MAX_SIGS_PER_FILE = 30;

    /**
     * Result of signature extraction for a single file.
     */
    public record FileSignatures(
            String filePath,
            String language,
            List<String> signatures,
            int originalLines,
            int signatureTokens,
            double reductionPercent
    ) {}

    /**
     * Result of signature extraction for an entire project.
     */
    public record ProjectSignatures(
            String projectId,
            List<FileSignatures> files,
            int totalFiles,
            int totalSignatures,
            int totalOriginalTokens,
            int totalSignatureTokens,
            double overallReductionPercent
    ) {}

    /**
     * Extract signatures for all files in a project using the index database.
     */
    public static ProjectSignatures extractProject(String projectId, Path indexDir,
                                                    Path rootDir) throws IOException {
        List<FileSignatures> fileSignatures = new ArrayList<>();
        int totalOriginalTokens = 0;
        int totalSignatureTokens = 0;
        int totalSignatures = 0;

        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            Set<String> relPaths = db.getAllRelPaths();
            for (String relPath : relPaths) {
                FileSignatures fs = extractFileFromDb(db, relPath, rootDir);
                if (fs != null && !fs.signatures.isEmpty()) {
                    fileSignatures.add(fs);
                    totalSignatures += fs.signatures.size();
                    totalOriginalTokens += fs.originalLines * 10; // rough: 10 tokens/line
                    totalSignatureTokens += fs.signatureTokens;
                }
            }
        } catch (SQLException e) {
            throw new IOException("Database error: " + e.getMessage(), e);
        }

        double reduction = totalOriginalTokens > 0
                ? (1.0 - (double) totalSignatureTokens / totalOriginalTokens) * 100.0
                : 0.0;

        return new ProjectSignatures(
                projectId, fileSignatures, fileSignatures.size(), totalSignatures,
                totalOriginalTokens, totalSignatureTokens, reduction
        );
    }

    /**
     * Extract signatures for a single file from the index database.
     */
    public static FileSignatures extractFile(String projectId, String relPath,
                                              Path indexDir, Path rootDir) throws IOException {
        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            return extractFileFromDb(db, relPath, rootDir);
        } catch (SQLException e) {
            throw new IOException("Database error: " + e.getMessage(), e);
        }
    }

    /**
     * Extract signatures for a file using an already-open database connection.
     */
    private static FileSignatures extractFileFromDb(IndexDatabase db, String relPath,
                                                     Path rootDir) throws SQLException {
        List<Map<String, Object>> entities = db.getEntitiesForFile(relPath);
        if (entities.isEmpty()) return null;

        String language = "";
        int fileEndLine = 0;
        List<String> signatures = new ArrayList<>();

        for (Map<String, Object> entity : entities) {
            String type = str(entity, "entityType");
            String name = str(entity, "name");
            String sig = str(entity, "signature");
            String visibility = str(entity, "visibility");
            String inheritedFrom = str(entity, "inheritedFrom");
            String implementsList = str(entity, "implementsList");

            if ("FILE".equals(type)) {
                language = str(entity, "language");
                Object endLine = entity.get("endLine");
                if (endLine instanceof Number) {
                    fileEndLine = ((Number) endLine).intValue();
                }
                continue;
            }

            if ("IMPORT".equals(type) || "PACKAGE".equals(type)) continue;

            String formatted = formatSignature(type, name, sig, visibility,
                    inheritedFrom, implementsList, language);
            if (formatted != null) {
                signatures.add(formatted);
            }

            if (signatures.size() >= MAX_SIGS_PER_FILE) break;
        }

        // Compute original file size for reduction estimate
        int originalLines = fileEndLine;
        if (originalLines == 0 && rootDir != null) {
            Path fullPath = rootDir.resolve(relPath);
            if (Files.exists(fullPath)) {
                try {
                    originalLines = (int) Files.lines(fullPath).count();
                } catch (IOException ignored) {}
            }
        }

        int sigTokens = CodeTokenizer.estimateTokens(signatures);
        int origTokens = originalLines * 10; // rough estimate
        double reduction = origTokens > 0
                ? (1.0 - (double) sigTokens / origTokens) * 100.0
                : 0.0;

        return new FileSignatures(relPath, language, signatures,
                originalLines, sigTokens, Math.max(0, reduction));
    }

    /**
     * Format an entity as a clean, minimal signature line.
     */
    private static String formatSignature(String entityType, String name, String sig,
                                           String visibility, String inheritedFrom,
                                           String implementsList, String language) {
        if (name == null || name.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();

        // Use the stored signature if available — it's already the best representation
        if (sig != null && !sig.isEmpty()) {
            // For classes/interfaces, add inheritance info
            if (("CLASS".equals(entityType) || "INTERFACE".equals(entityType)
                    || "ENUM".equals(entityType) || "RECORD".equals(entityType))) {
                sb.append(sig);
                if (inheritedFrom != null && !inheritedFrom.isEmpty()) {
                    if (!sig.contains("extends")) sb.append(" extends ").append(inheritedFrom);
                }
                if (implementsList != null && !implementsList.isEmpty()) {
                    if (!sig.contains("implements")) sb.append(" implements ").append(implementsList);
                }
                return sb.toString();
            }
            // For methods, add indentation to show hierarchy
            if ("METHOD".equals(entityType) || "FIELD".equals(entityType)) {
                return "  " + sig;
            }
            return sig;
        }

        // Reconstruct from parts if no stored signature
        String prefix = visibility != null && !visibility.isEmpty() ? visibility + " " : "";

        return switch (entityType) {
            case "CLASS" -> {
                sb.append(prefix).append("class ").append(name);
                if (inheritedFrom != null) sb.append(" extends ").append(inheritedFrom);
                if (implementsList != null) sb.append(" implements ").append(implementsList);
                yield sb.toString();
            }
            case "INTERFACE" -> prefix + "interface " + name;
            case "ENUM" -> prefix + "enum " + name;
            case "RECORD" -> prefix + "record " + name;
            case "ANNOTATION" -> "@interface " + name;
            case "METHOD" -> "  " + prefix + name + "(...)";
            case "FUNCTION" -> prefix + "function " + name + "(...)";
            case "FIELD" -> "  " + prefix + name;
            case "CONSTANT" -> "const " + name;
            case "MODULE" -> "module " + name;
            default -> null;
        };
    }

    /**
     * Format project signatures as a compact markdown string suitable for AI context.
     */
    public static String formatAsContext(ProjectSignatures project) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Code Signatures: ").append(project.projectId()).append("\n\n");
        sb.append("Files: ").append(project.totalFiles())
                .append(" | Signatures: ").append(project.totalSignatures())
                .append(" | Token reduction: ").append(String.format("%.1f%%", project.overallReductionPercent()))
                .append("\n\n");

        for (FileSignatures fs : project.files()) {
            if (fs.signatures().isEmpty()) continue;
            sb.append("## ").append(fs.filePath()).append("\n");
            for (String sig : fs.signatures()) {
                sb.append(sig).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Format a single file's signatures as compact context.
     */
    public static String formatFileContext(FileSignatures file) {
        if (file == null || file.signatures().isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(file.filePath());
        sb.append(" (").append(file.signatures().size()).append(" sigs, ")
                .append(String.format("%.0f%%", file.reductionPercent()))
                .append(" reduction)\n");
        for (String sig : file.signatures()) {
            sb.append(sig).append("\n");
        }
        return sb.toString();
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}
