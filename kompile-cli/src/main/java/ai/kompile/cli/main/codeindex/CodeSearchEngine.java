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
import java.io.PrintStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fine-grained code search engine operating on an indexed project.
 * Provides IntelliJ-style search capabilities:
 *
 * <ul>
 *   <li><b>Find in files</b> — text or regex search across all indexed source files</li>
 *   <li><b>Find and replace</b> — search and replace with preview, triggers incremental re-index</li>
 *   <li><b>Find usages</b> — find all references to a symbol (class, method, function)</li>
 * </ul>
 *
 * All operations work on files known to the index (via fingerprints),
 * using the project root path from metadata to resolve files on disk.
 */
public class CodeSearchEngine {

    private final LocalCodeIndexer indexer;

    public CodeSearchEngine(LocalCodeIndexer indexer) {
        this.indexer = indexer;
    }

    // -----------------------------------------------------------------------
    // Find in Files
    // -----------------------------------------------------------------------

    /**
     * Search options for find-in-files.
     */
    public record FindOptions(
            boolean regex,
            boolean caseSensitive,
            boolean wholeWord,
            String filePattern,       // glob filter, e.g. "*.java"
            int contextLines,         // lines of context around match (default 0)
            int maxResults            // max matches to return (default 500)
    ) {
        public FindOptions() {
            this(false, true, false, null, 0, 500);
        }

        public static FindOptions defaults() { return new FindOptions(); }

        public FindOptions withRegex(boolean r) { return new FindOptions(r, caseSensitive, wholeWord, filePattern, contextLines, maxResults); }
        public FindOptions withCaseSensitive(boolean c) { return new FindOptions(regex, c, wholeWord, filePattern, contextLines, maxResults); }
        public FindOptions withWholeWord(boolean w) { return new FindOptions(regex, caseSensitive, w, filePattern, contextLines, maxResults); }
        public FindOptions withFilePattern(String p) { return new FindOptions(regex, caseSensitive, wholeWord, p, contextLines, maxResults); }
        public FindOptions withContextLines(int c) { return new FindOptions(regex, caseSensitive, wholeWord, filePattern, c, maxResults); }
        public FindOptions withMaxResults(int m) { return new FindOptions(regex, caseSensitive, wholeWord, filePattern, contextLines, m); }
    }

    /**
     * A single match in a file.
     */
    public record FileMatch(
            String filePath,          // relative path
            int lineNumber,
            String lineContent,
            int matchStart,           // column offset within line
            int matchEnd,
            List<String> contextBefore,
            List<String> contextAfter
    ) {}

    /**
     * Result of a find-in-files operation.
     */
    public record FindResult(
            String query,
            int totalMatches,
            int filesWithMatches,
            List<FileMatch> matches,
            boolean truncated         // true if maxResults was hit
    ) {}

    /**
     * Search for text or regex pattern across all indexed source files.
     */
    public FindResult findInFiles(String projectId, String query, FindOptions options) throws IOException {
        Path rootDir = getProjectRoot(projectId);
        Map<String, IndexFileStore.FileFingerprint> fingerprints = loadFingerprints(projectId);

        Pattern pattern = buildPattern(query, options);
        List<FileMatch> matches = new ArrayList<>();
        int filesWithMatches = 0;
        boolean truncated = false;

        for (String relPath : fingerprints.keySet()) {
            if (options.filePattern() != null && !matchesGlob(relPath, options.filePattern())) {
                continue;
            }

            Path file = rootDir.resolve(relPath);
            if (!Files.exists(file)) continue;

            List<FileMatch> fileMatches = searchFile(file, relPath, pattern, options);
            if (!fileMatches.isEmpty()) {
                filesWithMatches++;
                for (FileMatch m : fileMatches) {
                    if (matches.size() >= options.maxResults()) {
                        truncated = true;
                        break;
                    }
                    matches.add(m);
                }
                if (truncated) break;
            }
        }

        return new FindResult(query, matches.size(), filesWithMatches, matches, truncated);
    }

    // -----------------------------------------------------------------------
    // Find and Replace
    // -----------------------------------------------------------------------

    /**
     * A single replacement in a file.
     */
    public record Replacement(
            String filePath,
            int lineNumber,
            String originalLine,
            String replacedLine,
            int replacementCount       // number of replacements on this line
    ) {}

    /**
     * Result of a find-and-replace operation.
     */
    public record ReplaceResult(
            String query,
            String replacement,
            int totalReplacements,
            int filesModified,
            List<Replacement> replacements,
            boolean applied,           // true if changes were written to disk
            IndexResult indexResult     // non-null if incremental re-index was triggered
    ) {
        /** Alias for the IndexResult from LocalCodeIndexer. */
        public record IndexResult(String projectId, int filesProcessed, int entitiesFound) {}
    }

    /**
     * Find and replace text across all indexed source files.
     * When {@code dryRun=true}, returns a preview without modifying files.
     * When {@code dryRun=false}, writes changes and triggers incremental re-index.
     */
    public ReplaceResult findAndReplace(String projectId, String query, String replacement,
                                         FindOptions options, boolean dryRun,
                                         PrintStream out) throws IOException {
        Path rootDir = getProjectRoot(projectId);
        Map<String, IndexFileStore.FileFingerprint> fingerprints = loadFingerprints(projectId);

        Pattern pattern = buildPattern(query, options);
        List<Replacement> replacements = new ArrayList<>();
        Set<Path> modifiedFiles = new LinkedHashSet<>();

        for (String relPath : fingerprints.keySet()) {
            if (options.filePattern() != null && !matchesGlob(relPath, options.filePattern())) {
                continue;
            }

            Path file = rootDir.resolve(relPath);
            if (!Files.exists(file)) continue;

            String content = Files.readString(file);
            String[] lines = content.split("\n", -1);
            boolean fileModified = false;
            StringBuilder newContent = new StringBuilder();

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                Matcher m = pattern.matcher(line);
                if (m.find()) {
                    String replaced = m.replaceAll(replacement);
                    int count = 0;
                    Matcher counter = pattern.matcher(line);
                    while (counter.find()) count++;

                    replacements.add(new Replacement(relPath, i + 1, line, replaced, count));
                    newContent.append(replaced);
                    fileModified = true;
                } else {
                    newContent.append(line);
                }
                if (i < lines.length - 1) newContent.append("\n");
            }

            if (fileModified && !dryRun) {
                Files.writeString(file, newContent.toString());
                modifiedFiles.add(file);
            }
        }

        // Trigger incremental re-index after modifications
        ReplaceResult.IndexResult indexResult = null;
        if (!dryRun && !modifiedFiles.isEmpty()) {
            if (out != null) out.println("Re-indexing " + modifiedFiles.size() + " modified file(s)...");
            LocalCodeIndexer.IndexResult ir = indexer.index(rootDir, projectId, null, null, out != null ? out : nullOut());
            indexResult = new ReplaceResult.IndexResult(ir.projectId(), ir.filesProcessed(), ir.entitiesFound());
        }

        return new ReplaceResult(query, replacement, replacements.size(),
                modifiedFiles.size(), replacements, !dryRun, indexResult);
    }

    // -----------------------------------------------------------------------
    // Find Usages
    // -----------------------------------------------------------------------

    /**
     * Usage category for a symbol reference.
     */
    public enum UsageKind {
        DEFINITION,       // where the symbol is defined
        IMPORT,           // import statement
        TYPE_REFERENCE,   // type annotation, variable declaration
        METHOD_CALL,      // method/function invocation
        INHERITANCE,      // extends, implements, trait impl
        FIELD_ACCESS,     // field/property access
        STRING_LITERAL,   // reference in string/comment
        OTHER             // uncategorized reference
    }

    /**
     * A single usage of a symbol.
     */
    public record Usage(
            String filePath,
            int lineNumber,
            String lineContent,
            UsageKind kind,
            String context             // enclosing class/function name if available
    ) {}

    /**
     * Result of a find-usages operation.
     */
    public record UsagesResult(
            String symbolName,
            String symbolType,         // CLASS, METHOD, FUNCTION, etc.
            String definitionFile,     // where it's defined (null if not found in index)
            int definitionLine,
            int totalUsages,
            Map<UsageKind, Integer> usagesByKind,
            List<Usage> usages
    ) {}

    /**
     * Find all usages of a symbol across the indexed codebase.
     * First looks up the symbol definition in the index, then searches
     * all files for references, categorizing each by usage kind.
     */
    public UsagesResult findUsages(String projectId, String symbolName,
                                    String entityType, int maxResults) throws IOException {
        Path rootDir = getProjectRoot(projectId);
        Map<String, IndexFileStore.FileFingerprint> fingerprints = loadFingerprints(projectId);

        // Look up the symbol definition in the index
        List<Map<String, Object>> definitions = indexer.search(projectId, symbolName, entityType, 5);
        Map<String, Object> definition = null;
        for (Map<String, Object> d : definitions) {
            String name = (String) d.getOrDefault("name", "");
            if (name.equals(symbolName)) {
                definition = d;
                break;
            }
        }

        String defFile = definition != null ? (String) definition.get("filePath") : null;
        int defLine = definition != null && definition.get("startLine") != null ?
                ((Number) definition.get("startLine")).intValue() : 0;
        String symbolType = definition != null ?
                (String) definition.getOrDefault("entityType", "UNKNOWN") : "UNKNOWN";
        String fqn = definition != null ?
                (String) definition.getOrDefault("fullyQualifiedName", symbolName) : symbolName;

        // Build search patterns based on symbol type
        List<UsagePattern> patterns = buildUsagePatterns(symbolName, fqn, symbolType);

        // Search all indexed files for usages
        List<Usage> usages = new ArrayList<>();
        Map<UsageKind, Integer> usagesByKind = new EnumMap<>(UsageKind.class);

        for (String relPath : fingerprints.keySet()) {
            Path file = rootDir.resolve(relPath);
            if (!Files.exists(file)) continue;

            try {
                String content = Files.readString(file);
                String[] lines = content.split("\n", -1);
                String lang = detectLanguageFromPath(relPath);

                for (int i = 0; i < lines.length; i++) {
                    if (usages.size() >= maxResults) break;
                    String line = lines[i];

                    for (UsagePattern up : patterns) {
                        if (up.pattern.matcher(line).find()) {
                            // Skip if this is the definition itself
                            if (relPath.equals(defFile) && (i + 1) == defLine) {
                                // Mark definition
                                usages.add(new Usage(relPath, i + 1, line.trim(),
                                        UsageKind.DEFINITION, findEnclosingContext(lines, i, lang)));
                                usagesByKind.merge(UsageKind.DEFINITION, 1, Integer::sum);
                            } else {
                                UsageKind kind = classifyUsage(line, symbolName, up.hint, lang);
                                usages.add(new Usage(relPath, i + 1, line.trim(),
                                        kind, findEnclosingContext(lines, i, lang)));
                                usagesByKind.merge(kind, 1, Integer::sum);
                            }
                            break; // one match per line per pattern set
                        }
                    }
                }
            } catch (IOException ignored) {
                // Skip unreadable files
            }
        }

        return new UsagesResult(symbolName, symbolType, defFile, defLine,
                usages.size(), usagesByKind, usages);
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private record UsagePattern(Pattern pattern, UsageKind hint) {}

    private List<UsagePattern> buildUsagePatterns(String name, String fqn, String symbolType) {
        List<UsagePattern> patterns = new ArrayList<>();
        String escaped = Pattern.quote(name);

        // Word-boundary match on the symbol name
        patterns.add(new UsagePattern(
                Pattern.compile("\\b" + escaped + "\\b"),
                UsageKind.OTHER));

        // FQN match (e.g., com.example.MyClass)
        if (fqn != null && !fqn.equals(name) && fqn.contains(".")) {
            String escapedFqn = Pattern.quote(fqn);
            patterns.add(new UsagePattern(
                    Pattern.compile(escapedFqn),
                    UsageKind.TYPE_REFERENCE));
        }

        return patterns;
    }

    /**
     * Classify a usage line into a UsageKind based on language-aware heuristics.
     */
    private UsageKind classifyUsage(String line, String symbolName, UsageKind hint, String lang) {
        String trimmed = line.trim();

        // Splan-specific classification
        if ("splan".equals(lang)) {
            return classifySplanUsage(trimmed, symbolName);
        }

        // Import detection (all languages)
        if (trimmed.startsWith("import ") || trimmed.startsWith("from ") ||
                trimmed.startsWith("use ") || trimmed.startsWith("require(") ||
                trimmed.startsWith("require '") || trimmed.startsWith("require \"") ||
                trimmed.contains("import {")) {
            return UsageKind.IMPORT;
        }

        // Inheritance detection
        if (trimmed.contains("extends " + symbolName) ||
                trimmed.contains("implements " + symbolName) ||
                trimmed.contains("impl " + symbolName) ||
                (trimmed.contains("class ") && trimmed.contains("(" + symbolName + ")")) || // Python
                trimmed.contains(": " + symbolName)) { // Go embed / TS extends
            return UsageKind.INHERITANCE;
        }

        // String literal / comment
        if (trimmed.startsWith("//") || trimmed.startsWith("#") ||
                trimmed.startsWith("/*") || trimmed.startsWith("*") ||
                trimmed.startsWith("\"\"\"") || trimmed.startsWith("'''")) {
            return UsageKind.STRING_LITERAL;
        }

        // Method call detection: symbolName( or .symbolName(
        if (line.contains(symbolName + "(") || line.contains("." + symbolName + "(")) {
            return UsageKind.METHOD_CALL;
        }

        // Field access: .symbolName (not followed by '(')
        if (line.contains("." + symbolName) && !line.contains("." + symbolName + "(")) {
            return UsageKind.FIELD_ACCESS;
        }

        // Type reference: new SymbolName, SymbolName variable, SymbolName.something
        if (line.contains("new " + symbolName) ||
                line.contains(symbolName + " ") ||
                line.contains(symbolName + "<") ||
                line.contains(symbolName + "[") ||
                line.contains(symbolName + ".")) {
            return UsageKind.TYPE_REFERENCE;
        }

        return hint != null ? hint : UsageKind.OTHER;
    }

    /**
     * Classify splan-specific usage kinds.
     * In splan: commands are operations (FUNCTION), :name references are declarations (CONSTANT),
     * and content inside delimiters is a content block (FIELD).
     */
    private UsageKind classifySplanUsage(String line, String symbolName) {
        // Comment
        if (line.startsWith("#")) {
            return UsageKind.STRING_LITERAL;
        }

        // Declaration definition: :symbolName:::
        if (line.startsWith(":" + symbolName) && (line.contains(":::") || line.contains("###") ||
                line.contains("$$$") || line.contains("@@@") || line.contains("%%%"))) {
            return UsageKind.DEFINITION;
        }

        // Declaration reference: :symbolName used as argument
        if (line.contains(":" + symbolName) && !line.startsWith(":")) {
            return UsageKind.FIELD_ACCESS;
        }

        // Operation (command) at start of line
        if (Character.isLetter(line.charAt(0)) && line.startsWith(symbolName)) {
            return UsageKind.METHOD_CALL;
        }

        // Symbol appears as a token argument in an operation
        if (Character.isLetter(line.charAt(0)) && line.contains(symbolName)) {
            return UsageKind.TYPE_REFERENCE;
        }

        // Inside content block delimiters
        if (line.contains(":::") || line.contains("###") || line.contains("$$$") ||
                line.contains("@@@") || line.contains("%%%")) {
            return UsageKind.STRING_LITERAL;
        }

        return UsageKind.OTHER;
    }

    /**
     * Find the enclosing class or function name for context.
     */
    private String findEnclosingContext(String[] lines, int lineIdx, String lang) {
        // Splan: search backwards for section separator or file start
        if ("splan".equals(lang)) {
            return findSplanEnclosingContext(lines, lineIdx);
        }

        // Search backwards for the nearest class/function definition
        for (int i = lineIdx - 1; i >= 0 && i >= lineIdx - 50; i--) {
            String line = lines[i];
            Matcher m;

            // Java/Kotlin/TS class
            m = Pattern.compile("(?:class|interface|enum|object)\\s+(\\w+)").matcher(line);
            if (m.find()) return m.group(1);

            // Java/Kotlin method
            m = Pattern.compile("(?:public|private|protected|internal)?\\s*(?:static\\s+)?\\w+\\s+(\\w+)\\s*\\(").matcher(line);
            if (m.find() && !Set.of("if", "for", "while", "switch", "catch", "return").contains(m.group(1))) {
                return m.group(1) + "()";
            }

            // Python class/def
            m = Pattern.compile("^\\s*(?:class|def|async\\s+def)\\s+(\\w+)").matcher(line);
            if (m.find()) return m.group(1);

            // Go func
            m = Pattern.compile("^func\\s+(?:\\(\\w+\\s+\\*?\\w+\\)\\s+)?(\\w+)").matcher(line);
            if (m.find()) return m.group(1);

            // Rust fn/struct/impl
            m = Pattern.compile("(?:fn|struct|impl|trait)\\s+(\\w+)").matcher(line);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /**
     * Find enclosing splan section context. Counts section separators (---) above
     * the current line to determine section-N.
     */
    private String findSplanEnclosingContext(String[] lines, int lineIdx) {
        int sectionIndex = 0;
        for (int i = 0; i < lineIdx && i < lines.length; i++) {
            if ("---".equals(lines[i].trim())) {
                sectionIndex++;
            }
        }
        return "section-" + sectionIndex;
    }

    /**
     * Search a single file for pattern matches.
     */
    private List<FileMatch> searchFile(Path file, String relPath,
                                        Pattern pattern, FindOptions options) throws IOException {
        List<FileMatch> matches = new ArrayList<>();
        String content = Files.readString(file);
        String[] lines = content.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            Matcher m = pattern.matcher(lines[i]);
            while (m.find()) {
                List<String> before = new ArrayList<>();
                List<String> after = new ArrayList<>();

                for (int b = Math.max(0, i - options.contextLines()); b < i; b++) {
                    before.add(lines[b]);
                }
                for (int a = i + 1; a <= Math.min(lines.length - 1, i + options.contextLines()); a++) {
                    after.add(lines[a]);
                }

                matches.add(new FileMatch(relPath, i + 1, lines[i],
                        m.start(), m.end(), before, after));
                break; // one match per line
            }
        }
        return matches;
    }

    /**
     * Build a compiled Pattern from the query and options.
     */
    private Pattern buildPattern(String query, FindOptions options) {
        String patternStr;
        if (options.regex()) {
            patternStr = query;
        } else {
            patternStr = Pattern.quote(query);
        }

        if (options.wholeWord()) {
            patternStr = "\\b" + patternStr + "\\b";
        }

        int flags = 0;
        if (!options.caseSensitive()) {
            flags |= Pattern.CASE_INSENSITIVE;
        }

        return Pattern.compile(patternStr, flags);
    }

    /**
     * Get the root directory path for a project from its metadata.
     */
    private Path getProjectRoot(String projectId) throws IOException {
        Map<String, Object> stats = indexer.getStats(projectId);
        String rootPath = (String) stats.get("rootPath");
        if (rootPath == null) {
            throw new IOException("No root path found for project '" + projectId + "'");
        }
        return Path.of(rootPath);
    }

    private Map<String, IndexFileStore.FileFingerprint> loadFingerprints(String projectId) throws IOException {
        Path indexDir = LocalCodeIndexer.getIndexDir(projectId);
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        om.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        IndexFileStore store = new IndexFileStore(indexDir, om);
        return store.loadFingerprints();
    }

    private boolean matchesGlob(String path, String globPattern) {
        // Simple glob matching
        if (globPattern.startsWith("*.")) {
            return path.endsWith(globPattern.substring(1));
        }
        if (globPattern.endsWith("*")) {
            return path.startsWith(globPattern.substring(0, globPattern.length() - 1));
        }
        return path.contains(globPattern);
    }

    private String detectLanguageFromPath(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) return "unknown";
        String ext = path.substring(dot);
        return switch (ext) {
            case ".java", ".kt", ".scala", ".groovy" -> "jvm";
            case ".py", ".pyi" -> "python";
            case ".go" -> "go";
            case ".rs" -> "rust";
            case ".ts", ".tsx", ".js", ".jsx" -> "typescript";
            case ".c", ".cpp", ".cc", ".h", ".hpp" -> "c";
            case ".splan" -> "splan";
            default -> "unknown";
        };
    }

    private static PrintStream nullOut() {
        return new PrintStream(java.io.OutputStream.nullOutputStream());
    }
}
