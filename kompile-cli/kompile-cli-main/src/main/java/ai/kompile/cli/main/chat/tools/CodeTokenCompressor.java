/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Language-aware token compression for source code files.
 *
 * <p>Implements multiple compression strategies based on current research
 * (AST-aware stripping, import compression, boilerplate removal, structural
 * extraction) to reduce token consumption when LLMs read code files.
 *
 * <p>Typical savings: 30-70% for compressed mode, 70-95% for structure-only mode.
 */
public final class CodeTokenCompressor {

    private CodeTokenCompressor() {}

    // ── Language detection ────────────────────────────────────────────────

    enum Language {
        JAVA, KOTLIN, PYTHON, JAVASCRIPT, TYPESCRIPT, GO, RUST, C, CPP,
        CSHARP, RUBY, SCALA, XML, YAML, JSON, PROPERTIES, SHELL, SQL, UNKNOWN
    }

    static Language detectLanguage(String fileName) {
        if (fileName == null) return Language.UNKNOWN;
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".java")) return Language.JAVA;
        if (lower.endsWith(".kt") || lower.endsWith(".kts")) return Language.KOTLIN;
        if (lower.endsWith(".py") || lower.endsWith(".pyw")) return Language.PYTHON;
        if (lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".mjs")) return Language.JAVASCRIPT;
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return Language.TYPESCRIPT;
        if (lower.endsWith(".go")) return Language.GO;
        if (lower.endsWith(".rs")) return Language.RUST;
        if (lower.endsWith(".c") || lower.endsWith(".h")) return Language.C;
        if (lower.endsWith(".cpp") || lower.endsWith(".cc") || lower.endsWith(".cxx")
                || lower.endsWith(".hpp") || lower.endsWith(".hxx")) return Language.CPP;
        if (lower.endsWith(".cs")) return Language.CSHARP;
        if (lower.endsWith(".rb")) return Language.RUBY;
        if (lower.endsWith(".scala") || lower.endsWith(".sc")) return Language.SCALA;
        if (lower.endsWith(".xml") || lower.endsWith(".pom") || lower.endsWith(".html")
                || lower.endsWith(".xhtml") || lower.endsWith(".svg")) return Language.XML;
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) return Language.YAML;
        if (lower.endsWith(".json")) return Language.JSON;
        if (lower.endsWith(".properties") || lower.endsWith(".cfg") || lower.endsWith(".ini")
                || lower.endsWith(".conf") || lower.endsWith(".toml")) return Language.PROPERTIES;
        if (lower.endsWith(".sh") || lower.endsWith(".bash") || lower.endsWith(".zsh")) return Language.SHELL;
        if (lower.endsWith(".sql")) return Language.SQL;
        return Language.UNKNOWN;
    }

    // ── Patterns ─────────────────────────────────────────────────────────

    // License/copyright header patterns
    private static final Pattern LICENSE_BLOCK_COMMENT_START = Pattern.compile(
            "^\\s*/\\*.*(?:copyright|license|licensed|apache|mit license|bsd|gpl|mozilla|creative commons)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BLOCK_COMMENT_END = Pattern.compile("\\*/\\s*$");
    private static final Pattern LICENSE_LINE_COMMENT = Pattern.compile(
            "^\\s*(?://|#).*(?:copyright|license|licensed under|all rights reserved)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SHEBANG = Pattern.compile("^#!");

    // Import patterns by language family
    private static final Pattern JAVA_IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?[\\w.*]+;\\s*$");
    private static final Pattern JAVA_PACKAGE = Pattern.compile("^\\s*package\\s+[\\w.]+;\\s*$");
    private static final Pattern PYTHON_IMPORT = Pattern.compile("^\\s*(?:import|from)\\s+\\S+");
    private static final Pattern JS_IMPORT = Pattern.compile(
            "^\\s*(?:import\\s+|const\\s+.*=\\s*require\\(|let\\s+.*=\\s*require\\(|var\\s+.*=\\s*require\\()");
    private static final Pattern GO_IMPORT = Pattern.compile("^\\s*(?:import\\s+[\"(]|\\s*\"[\\w./]+\"\\s*$)");
    private static final Pattern RUST_USE = Pattern.compile("^\\s*use\\s+[\\w:]+");
    private static final Pattern C_INCLUDE = Pattern.compile("^\\s*#\\s*include\\s+[<\"]");
    private static final Pattern CSHARP_USING = Pattern.compile("^\\s*using\\s+[\\w.]+;");
    private static final Pattern RUBY_REQUIRE = Pattern.compile("^\\s*require\\s+");
    private static final Pattern SCALA_IMPORT = Pattern.compile("^\\s*import\\s+[\\w.{},\\s]+");

    // Structural patterns (signatures, definitions)
    private static final Pattern JAVA_CLASS_DEF = Pattern.compile(
            "^\\s*(?:public|private|protected|abstract|final|static|sealed|non-sealed)?\\s*" +
                    "(?:public|private|protected|abstract|final|static|sealed|non-sealed)?\\s*" +
                    "(?:class|interface|enum|record|@interface)\\s+\\w+");
    private static final Pattern JAVA_METHOD_DEF = Pattern.compile(
            "^\\s*(?:public|private|protected|abstract|final|static|synchronized|native|default)?\\s*" +
                    "(?:public|private|protected|abstract|final|static|synchronized|native|default)?\\s*" +
                    "(?:<[^>]+>\\s+)?\\S+\\s+\\w+\\s*\\(");
    private static final Pattern JAVA_FIELD_DEF = Pattern.compile(
            "^\\s*(?:public|private|protected|static|final|volatile|transient)?\\s*" +
                    "(?:public|private|protected|static|final|volatile|transient)?\\s*" +
                    "(?:public|private|protected|static|final|volatile|transient)?\\s*" +
                    "\\S+\\s+\\w+\\s*[=;]");
    private static final Pattern JAVA_ANNOTATION = Pattern.compile("^\\s*@\\w+");

    private static final Pattern PYTHON_DEF = Pattern.compile("^\\s*(?:def|class|async\\s+def)\\s+\\w+");
    private static final Pattern PYTHON_DECORATOR = Pattern.compile("^\\s*@\\w+");

    private static final Pattern JS_FUNC_DEF = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?(?:function\\*?\\s+\\w+|(?:const|let|var)\\s+\\w+\\s*=\\s*(?:async\\s+)?(?:\\([^)]*\\)|\\w+)\\s*=>)");
    private static final Pattern JS_CLASS_DEF = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:default\\s+)?(?:abstract\\s+)?class\\s+\\w+");
    private static final Pattern TS_INTERFACE_DEF = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:default\\s+)?interface\\s+\\w+");
    private static final Pattern TS_TYPE_DEF = Pattern.compile(
            "^\\s*(?:export\\s+)?type\\s+\\w+");

    private static final Pattern GO_FUNC_DEF = Pattern.compile("^\\s*func\\s+(?:\\([^)]+\\)\\s+)?\\w+");
    private static final Pattern GO_TYPE_DEF = Pattern.compile("^\\s*type\\s+\\w+\\s+(?:struct|interface)");

    private static final Pattern RUST_FN_DEF = Pattern.compile(
            "^\\s*(?:pub\\s+)?(?:async\\s+)?fn\\s+\\w+");
    private static final Pattern RUST_STRUCT_DEF = Pattern.compile(
            "^\\s*(?:pub\\s+)?(?:struct|enum|trait|impl|type)\\s+\\w+");

    private static final Pattern C_FUNC_DEF = Pattern.compile(
            "^\\s*(?:static\\s+)?(?:inline\\s+)?(?:const\\s+)?\\w[\\w*\\s]+\\w+\\s*\\([^;]*$");

    // Blank/whitespace
    private static final Pattern BLANK_LINE = Pattern.compile("^\\s*$");

    // Block comment content (inside /* ... */)
    private static final Pattern BLOCK_COMMENT_LINE = Pattern.compile("^\\s*\\*(?!/)");
    private static final Pattern BLOCK_COMMENT_OPEN = Pattern.compile("/\\*");
    private static final Pattern BLOCK_COMMENT_CLOSE = Pattern.compile("\\*/");

    // Javadoc / docstring markers
    private static final Pattern JAVADOC_START = Pattern.compile("^\\s*/\\*\\*");
    private static final Pattern PYTHON_DOCSTRING = Pattern.compile("^\\s*(?:\"\"\"|''')");

    // ── Compress mode ────────────────────────────────────────────────────

    /**
     * Compress source code by stripping boilerplate while preserving all
     * semantically meaningful code.
     *
     * <p>Techniques applied:
     * <ul>
     *   <li>Strip license/copyright headers</li>
     *   <li>Collapse import blocks to a single summary line</li>
     *   <li>Collapse consecutive blank lines to at most one</li>
     *   <li>Strip pure-whitespace trailing content</li>
     *   <li>Collapse long block comments to a summary</li>
     * </ul>
     *
     * @param lines    the source lines (without line numbers)
     * @param fileName file name for language detection
     * @return compressed result with line mapping
     */
    public static CompressResult compress(List<String> lines, String fileName) {
        Language lang = detectLanguage(fileName);
        List<NumberedLine> result = new ArrayList<>();

        int i = 0;
        int totalLines = lines.size();

        // Phase 1: Skip license header at top of file
        i = skipLicenseHeader(lines, i);

        // Phase 2: Process remaining lines
        boolean lastWasBlank = false;
        boolean inBlockComment = false;
        int importStart = -1;
        int importCount = 0;
        String importSummary = null;

        while (i < totalLines) {
            String line = lines.get(i);

            // Track block comments
            if (inBlockComment) {
                if (BLOCK_COMMENT_CLOSE.matcher(line).find()) {
                    inBlockComment = false;
                }
                i++;
                continue;
            }

            // Detect block comment start (non-javadoc, non-license — those are handled separately)
            if (BLOCK_COMMENT_OPEN.matcher(line).find() && !JAVADOC_START.matcher(line).matches()
                    && !BLOCK_COMMENT_CLOSE.matcher(line).find()) {
                // Multi-line block comment — count lines
                int commentStart = i;
                inBlockComment = true;
                i++;
                int commentLines = 1;
                while (i < totalLines) {
                    if (BLOCK_COMMENT_CLOSE.matcher(lines.get(i)).find()) {
                        inBlockComment = false;
                        commentLines++;
                        i++;
                        break;
                    }
                    commentLines++;
                    i++;
                }
                if (commentLines > 5) {
                    // Collapse large block comments
                    result.add(new NumberedLine(commentStart + 1,
                            "/* ... (" + commentLines + " line comment) ... */"));
                } else {
                    // Keep short block comments
                    for (int j = commentStart; j < Math.min(commentStart + commentLines, totalLines); j++) {
                        result.add(new NumberedLine(j + 1, lines.get(j)));
                    }
                }
                lastWasBlank = false;
                continue;
            }

            // Collapse blank lines
            if (BLANK_LINE.matcher(line).matches()) {
                if (!lastWasBlank && !result.isEmpty()) {
                    result.add(new NumberedLine(i + 1, ""));
                    lastWasBlank = true;
                }
                i++;
                continue;
            }
            lastWasBlank = false;

            // Collapse import blocks
            if (isImportLine(line, lang)) {
                if (importStart == -1) {
                    importStart = i;
                    importCount = 0;
                }
                importCount++;
                i++;
                continue;
            } else if (importCount > 0) {
                // Flush import block
                result.add(new NumberedLine(importStart + 1,
                        "[" + importCount + " import" + (importCount > 1 ? "s" : "") + "]"));
                importStart = -1;
                importCount = 0;
            }

            // Collapse package declarations for Java/Kotlin (single line, low value)
            if (lang == Language.JAVA || lang == Language.KOTLIN) {
                if (JAVA_PACKAGE.matcher(line).matches()) {
                    result.add(new NumberedLine(i + 1, line.trim()));
                    i++;
                    continue;
                }
            }

            // Collapse long Javadoc/docstrings (>8 lines)
            if (JAVADOC_START.matcher(line).matches()) {
                int docStart = i;
                int docLines = 1;
                i++;
                StringBuilder firstLine = new StringBuilder();
                while (i < totalLines) {
                    String docLine = lines.get(i).trim();
                    if (firstLine.isEmpty() && docLine.startsWith("*") && docLine.length() > 2) {
                        firstLine.append(docLine.substring(1).trim());
                    }
                    docLines++;
                    if (docLine.endsWith("*/")) {
                        i++;
                        break;
                    }
                    i++;
                }
                if (docLines > 8) {
                    String summary = firstLine.isEmpty() ? "..." : firstLine.toString();
                    if (summary.length() > 80) summary = summary.substring(0, 80) + "...";
                    result.add(new NumberedLine(docStart + 1,
                            "/** " + summary + " (" + docLines + " lines) */"));
                } else {
                    for (int j = docStart; j < Math.min(docStart + docLines, totalLines); j++) {
                        result.add(new NumberedLine(j + 1, lines.get(j)));
                    }
                }
                lastWasBlank = false;
                continue;
            }

            // Python docstrings
            if ((lang == Language.PYTHON) && PYTHON_DOCSTRING.matcher(line).matches()) {
                String marker = line.trim().startsWith("\"\"\"") ? "\"\"\"" : "'''";
                // Check if single-line docstring
                if (line.trim().length() > 3 && line.trim().endsWith(marker) && line.trim().indexOf(marker, 3) > 0) {
                    result.add(new NumberedLine(i + 1, line));
                    i++;
                    continue;
                }
                int docStart = i;
                int docLines = 1;
                i++;
                while (i < totalLines) {
                    docLines++;
                    if (lines.get(i).contains(marker)) {
                        i++;
                        break;
                    }
                    i++;
                }
                if (docLines > 6) {
                    result.add(new NumberedLine(docStart + 1,
                            "    " + marker + "...(" + docLines + " line docstring)..." + marker));
                } else {
                    for (int j = docStart; j < Math.min(docStart + docLines, totalLines); j++) {
                        result.add(new NumberedLine(j + 1, lines.get(j)));
                    }
                }
                lastWasBlank = false;
                continue;
            }

            // Keep everything else
            result.add(new NumberedLine(i + 1, line));
            i++;
        }

        // Flush trailing imports
        if (importCount > 0) {
            result.add(new NumberedLine(importStart + 1,
                    "[" + importCount + " import" + (importCount > 1 ? "s" : "") + "]"));
        }

        return new CompressResult(result, totalLines, result.size());
    }

    // ── Structure mode ───────────────────────────────────────────────────

    /**
     * Extract only structural definitions: class/interface/enum declarations,
     * method/function signatures, type definitions. Replaces bodies with {@code ...}.
     *
     * <p>This gives the LLM a file's API surface in ~10-20% of the original tokens.
     *
     * @param lines    the source lines
     * @param fileName file name for language detection
     * @return structure-only result
     */
    public static CompressResult extractStructure(List<String> lines, String fileName) {
        Language lang = detectLanguage(fileName);
        List<NumberedLine> result = new ArrayList<>();
        int totalLines = lines.size();

        // For non-code files, just return first few lines as preview
        if (lang == Language.XML || lang == Language.YAML || lang == Language.JSON
                || lang == Language.PROPERTIES || lang == Language.SQL || lang == Language.UNKNOWN) {
            int previewLines = Math.min(30, totalLines);
            for (int i = 0; i < previewLines; i++) {
                result.add(new NumberedLine(i + 1, lines.get(i)));
            }
            if (totalLines > previewLines) {
                result.add(new NumberedLine(previewLines + 1,
                        "... (" + (totalLines - previewLines) + " more lines)"));
            }
            return new CompressResult(result, totalLines, result.size());
        }

        // Skip license header
        int i = skipLicenseHeader(lines, 0);

        // Add package declaration if present
        if (i < totalLines && (lang == Language.JAVA || lang == Language.KOTLIN)) {
            if (JAVA_PACKAGE.matcher(lines.get(i)).matches()) {
                result.add(new NumberedLine(i + 1, lines.get(i).trim()));
                i++;
            }
        }

        // Count and summarize imports
        int importCount = 0;
        int importStart = i;
        while (i < totalLines && (isImportLine(lines.get(i), lang) || BLANK_LINE.matcher(lines.get(i)).matches())) {
            if (isImportLine(lines.get(i), lang)) importCount++;
            i++;
        }
        if (importCount > 0) {
            result.add(new NumberedLine(importStart + 1,
                    "[" + importCount + " import" + (importCount > 1 ? "s" : "") + "]"));
            result.add(new NumberedLine(-1, ""));
        }

        // Extract structural definitions
        boolean inBody = false;
        int braceDepth = 0;
        int pendingAnnotations = 0;
        List<NumberedLine> annotationBuffer = new ArrayList<>();

        while (i < totalLines) {
            String line = lines.get(i);
            String trimmed = line.trim();

            // Skip blank lines between definitions
            if (trimmed.isEmpty()) {
                if (!result.isEmpty() && !result.get(result.size() - 1).line().isEmpty()) {
                    result.add(new NumberedLine(i + 1, ""));
                }
                i++;
                continue;
            }

            boolean isDefinition = isStructuralDefinition(trimmed, lang);
            boolean isAnnotation = isAnnotationLine(trimmed, lang);

            if (isAnnotation) {
                annotationBuffer.add(new NumberedLine(i + 1, line));
                i++;
                continue;
            }

            if (isDefinition) {
                // Flush annotations
                result.addAll(annotationBuffer);
                annotationBuffer.clear();

                // Add the definition line
                result.add(new NumberedLine(i + 1, line));

                // For single-line definitions (ends with ; or has no body), just keep it
                if (trimmed.endsWith(";") || trimmed.endsWith(",")) {
                    i++;
                    continue;
                }

                // Look for opening brace
                int depth = countBraces(trimmed);
                i++;

                // If the definition line doesn't have the opening brace, look for it
                while (i < totalLines && depth <= 0) {
                    String nextTrimmed = lines.get(i).trim();
                    if (nextTrimmed.isEmpty()) { i++; continue; }
                    result.add(new NumberedLine(i + 1, lines.get(i)));
                    depth += countBraces(nextTrimmed);
                    i++;
                    if (nextTrimmed.contains("{")) break;
                    if (nextTrimmed.endsWith(";")) break;
                }

                // For class/interface/enum/struct — recurse into the body to find nested definitions
                if (isClassLevel(trimmed, lang)) {
                    // Keep going — inner definitions will be picked up
                    continue;
                }

                // For method/function bodies — skip the body
                if (depth > 0) {
                    int bodyStart = i;
                    while (i < totalLines && depth > 0) {
                        depth += countBraces(lines.get(i));
                        i++;
                    }
                    int bodyLines = i - bodyStart;
                    if (bodyLines > 0) {
                        result.add(new NumberedLine(bodyStart + 1,
                                "        // ... (" + bodyLines + " lines)"));
                        // Add closing brace
                        if (i > 0 && i <= totalLines) {
                            String closingLine = lines.get(i - 1).trim();
                            if (closingLine.equals("}") || closingLine.startsWith("}")) {
                                result.add(new NumberedLine(i, lines.get(i - 1)));
                            }
                        }
                    }
                }
                continue;
            }

            // For Python: indent-based structure extraction
            if (lang == Language.PYTHON && PYTHON_DEF.matcher(trimmed).matches()) {
                result.addAll(annotationBuffer);
                annotationBuffer.clear();
                result.add(new NumberedLine(i + 1, line));
                // Get the colon line if it's on the next line
                i++;
                if (i < totalLines && lines.get(i).trim().equals(":")) {
                    result.add(new NumberedLine(i + 1, lines.get(i)));
                    i++;
                }
                // Skip body (indented lines)
                int indent = getIndent(line);
                int bodyLines = 0;
                while (i < totalLines) {
                    String next = lines.get(i);
                    if (!next.trim().isEmpty() && getIndent(next) <= indent) break;
                    bodyLines++;
                    i++;
                }
                if (bodyLines > 0) {
                    result.add(new NumberedLine(-1,
                            " ".repeat(indent + 4) + "# ... (" + bodyLines + " lines)"));
                }
                continue;
            }

            // Discard non-structural lines (not annotations waiting to be flushed)
            annotationBuffer.clear();
            i++;
        }

        return new CompressResult(result, totalLines, result.size());
    }

    // ── Grep result compression ──────────────────────────────────────────

    /**
     * Compress grep/search results by deduplicating identical match content,
     * collapsing runs of similar lines, and grouping by file.
     *
     * @param rawOutput the raw grep output (file:line:content format)
     * @return compressed output
     */
    public static String compressGrepResults(String rawOutput) {
        if (rawOutput == null || rawOutput.isEmpty()) return rawOutput;

        String[] lines = rawOutput.split("\n");
        if (lines.length < 5) return rawOutput; // Not worth compressing

        // Group matches by file
        Map<String, List<String>> byFile = new LinkedHashMap<>();
        List<String> nonMatchLines = new ArrayList<>(); // Context lines, separators, etc.

        String currentFile = null;
        for (String line : lines) {
            // Parse ripgrep output: file:line:content or file-line-content (context)
            int firstColon = line.indexOf(':');
            if (firstColon > 0) {
                String possibleFile = line.substring(0, firstColon);
                // Check if this looks like a file path (not a drive letter or short prefix)
                if (possibleFile.contains("/") || possibleFile.contains("\\")
                        || possibleFile.endsWith(".java") || possibleFile.endsWith(".py")
                        || possibleFile.endsWith(".ts") || possibleFile.endsWith(".js")
                        || possibleFile.endsWith(".go") || possibleFile.endsWith(".rs")
                        || possibleFile.endsWith(".cpp") || possibleFile.endsWith(".c")
                        || possibleFile.endsWith(".rb") || possibleFile.endsWith(".scala")
                        || possibleFile.endsWith(".kt") || possibleFile.endsWith(".cs")
                        || possibleFile.endsWith(".xml") || possibleFile.endsWith(".yml")
                        || possibleFile.endsWith(".yaml") || possibleFile.endsWith(".json")
                        || possibleFile.endsWith(".md") || possibleFile.endsWith(".txt")
                        || possibleFile.endsWith(".sh") || possibleFile.endsWith(".sql")
                        || possibleFile.endsWith(".html") || possibleFile.endsWith(".css")
                        || possibleFile.endsWith(".properties") || possibleFile.endsWith(".toml")
                        || possibleFile.endsWith(".cfg") || possibleFile.endsWith(".h")
                        || possibleFile.endsWith(".hpp")) {
                    currentFile = possibleFile;
                    byFile.computeIfAbsent(currentFile, k -> new ArrayList<>()).add(line);
                    continue;
                }
            }
            // Separator or context line
            if (line.equals("--")) continue; // Skip ripgrep group separators
            if (currentFile != null) {
                byFile.get(currentFile).add(line);
            } else {
                nonMatchLines.add(line);
            }
        }

        // If no file grouping was detected, try dedup on raw lines
        if (byFile.isEmpty()) {
            return deduplicateLines(lines);
        }

        StringBuilder sb = new StringBuilder();
        int totalDeduplicated = 0;

        for (Map.Entry<String, List<String>> entry : byFile.entrySet()) {
            List<String> fileLines = entry.getValue();

            // Deduplicate identical content within same file
            List<String> deduplicated = new ArrayList<>();
            Map<String, Integer> seenContent = new LinkedHashMap<>();

            for (String fl : fileLines) {
                // Extract just the content portion (after file:line:)
                String content = extractMatchContent(fl);
                if (content != null && !content.trim().isEmpty()) {
                    Integer prevCount = seenContent.get(content.trim());
                    if (prevCount != null) {
                        seenContent.put(content.trim(), prevCount + 1);
                        totalDeduplicated++;
                        continue;
                    }
                    seenContent.put(content.trim(), 1);
                }
                deduplicated.add(fl);
            }

            for (String dl : deduplicated) {
                sb.append(dl).append("\n");
            }

            // Report duplicates
            for (Map.Entry<String, Integer> seen : seenContent.entrySet()) {
                if (seen.getValue() > 1) {
                    sb.append("  [+" + (seen.getValue() - 1) + " identical match"
                            + (seen.getValue() > 2 ? "es" : "") + "]\n");
                }
            }
        }

        if (totalDeduplicated > 0) {
            sb.append("[" + totalDeduplicated + " duplicate match"
                    + (totalDeduplicated > 1 ? "es" : "") + " removed]\n");
        }

        for (String nl : nonMatchLines) {
            sb.append(nl).append("\n");
        }

        return sb.toString().trim();
    }

    // ── Helper methods ───────────────────────────────────────────────────

    private static int skipLicenseHeader(List<String> lines, int start) {
        int i = start;
        int total = lines.size();

        // Skip leading blank lines
        while (i < total && BLANK_LINE.matcher(lines.get(i)).matches()) i++;

        if (i >= total) return i;

        // Check for shebang line
        if (SHEBANG.matcher(lines.get(i)).matches()) i++;

        // Skip blank lines after shebang
        while (i < total && BLANK_LINE.matcher(lines.get(i)).matches()) i++;

        if (i >= total) return i;

        // Check for block comment license header
        String firstLine = lines.get(i);
        if (LICENSE_BLOCK_COMMENT_START.matcher(firstLine).find()
                || (firstLine.trim().startsWith("/*") && i + 1 < total
                && LICENSE_LINE_COMMENT.matcher(lines.get(i + 1)).find())) {
            // Skip entire block comment
            while (i < total) {
                if (BLOCK_COMMENT_END.matcher(lines.get(i)).find()) {
                    i++;
                    break;
                }
                i++;
            }
            // Skip trailing blank line after license
            while (i < total && BLANK_LINE.matcher(lines.get(i)).matches()) i++;
            return i;
        }

        // Check for line-comment license header (# or // style)
        if (LICENSE_LINE_COMMENT.matcher(firstLine).find()) {
            while (i < total) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) break;
                if (!line.startsWith("#") && !line.startsWith("//")) break;
                i++;
            }
            while (i < total && BLANK_LINE.matcher(lines.get(i)).matches()) i++;
            return i;
        }

        return i;
    }

    private static boolean isImportLine(String line, Language lang) {
        return switch (lang) {
            case JAVA -> JAVA_IMPORT.matcher(line).matches();
            case KOTLIN -> JAVA_IMPORT.matcher(line).matches() || SCALA_IMPORT.matcher(line).matches();
            case PYTHON -> PYTHON_IMPORT.matcher(line).matches();
            case JAVASCRIPT, TYPESCRIPT -> JS_IMPORT.matcher(line).matches();
            case GO -> GO_IMPORT.matcher(line).matches();
            case RUST -> RUST_USE.matcher(line).matches();
            case C, CPP -> C_INCLUDE.matcher(line).matches();
            case CSHARP -> CSHARP_USING.matcher(line).matches();
            case RUBY -> RUBY_REQUIRE.matcher(line).matches();
            case SCALA -> SCALA_IMPORT.matcher(line).matches();
            default -> false;
        };
    }

    private static boolean isStructuralDefinition(String trimmed, Language lang) {
        return switch (lang) {
            case JAVA, KOTLIN -> JAVA_CLASS_DEF.matcher(trimmed).find()
                    || JAVA_METHOD_DEF.matcher(trimmed).find()
                    || JAVA_FIELD_DEF.matcher(trimmed).find();
            case PYTHON -> PYTHON_DEF.matcher(trimmed).find();
            case JAVASCRIPT -> JS_FUNC_DEF.matcher(trimmed).find()
                    || JS_CLASS_DEF.matcher(trimmed).find();
            case TYPESCRIPT -> JS_FUNC_DEF.matcher(trimmed).find()
                    || JS_CLASS_DEF.matcher(trimmed).find()
                    || TS_INTERFACE_DEF.matcher(trimmed).find()
                    || TS_TYPE_DEF.matcher(trimmed).find();
            case GO -> GO_FUNC_DEF.matcher(trimmed).find()
                    || GO_TYPE_DEF.matcher(trimmed).find();
            case RUST -> RUST_FN_DEF.matcher(trimmed).find()
                    || RUST_STRUCT_DEF.matcher(trimmed).find();
            case C, CPP -> C_FUNC_DEF.matcher(trimmed).find()
                    || JAVA_CLASS_DEF.matcher(trimmed).find();
            case CSHARP -> JAVA_CLASS_DEF.matcher(trimmed).find()
                    || JAVA_METHOD_DEF.matcher(trimmed).find();
            case RUBY -> trimmed.startsWith("def ") || trimmed.startsWith("class ")
                    || trimmed.startsWith("module ");
            case SCALA -> JAVA_CLASS_DEF.matcher(trimmed).find()
                    || trimmed.startsWith("def ") || trimmed.startsWith("val ")
                    || trimmed.startsWith("var ") || trimmed.startsWith("object ");
            default -> false;
        };
    }

    private static boolean isAnnotationLine(String trimmed, Language lang) {
        return switch (lang) {
            case JAVA, KOTLIN, SCALA -> JAVA_ANNOTATION.matcher(trimmed).matches();
            case PYTHON -> PYTHON_DECORATOR.matcher(trimmed).matches();
            default -> false;
        };
    }

    private static boolean isClassLevel(String trimmed, Language lang) {
        return switch (lang) {
            case JAVA, KOTLIN, CSHARP, SCALA ->
                    trimmed.contains("class ") || trimmed.contains("interface ")
                            || trimmed.contains("enum ") || trimmed.contains("record ")
                            || trimmed.contains("object ");
            case JAVASCRIPT, TYPESCRIPT -> trimmed.contains("class ");
            case GO -> trimmed.contains("struct") || trimmed.contains("interface");
            case RUST -> trimmed.contains("struct ") || trimmed.contains("enum ")
                    || trimmed.contains("trait ") || trimmed.contains("impl ");
            case PYTHON -> trimmed.startsWith("class ");
            case RUBY -> trimmed.startsWith("class ") || trimmed.startsWith("module ");
            default -> false;
        };
    }

    private static int countBraces(String line) {
        int count = 0;
        boolean inString = false;
        char stringChar = 0;
        for (int j = 0; j < line.length(); j++) {
            char c = line.charAt(j);
            if (inString) {
                if (c == stringChar && (j == 0 || line.charAt(j - 1) != '\\')) inString = false;
            } else {
                if (c == '"' || c == '\'') { inString = true; stringChar = c; }
                else if (c == '{') count++;
                else if (c == '}') count--;
            }
        }
        return count;
    }

    private static int getIndent(String line) {
        int indent = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') indent++;
            else if (c == '\t') indent += 4;
            else break;
        }
        return indent;
    }

    private static String extractMatchContent(String grepLine) {
        // Format: file:line:content
        int first = grepLine.indexOf(':');
        if (first < 0) return null;
        int second = grepLine.indexOf(':', first + 1);
        if (second < 0) return grepLine.substring(first + 1);
        return grepLine.substring(second + 1);
    }

    private static String deduplicateLines(String[] lines) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        List<String> unique = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.equals("--")) continue;
            Integer count = seen.get(trimmed);
            if (count != null) {
                seen.put(trimmed, count + 1);
            } else {
                seen.put(trimmed, 1);
                unique.add(line);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String u : unique) sb.append(u).append("\n");
        int dups = lines.length - unique.size();
        if (dups > 0) {
            sb.append("[" + dups + " duplicate line" + (dups > 1 ? "s" : "") + " removed]\n");
        }
        return sb.toString().trim();
    }

    // ── Result types ─────────────────────────────────────────────────────

    public record NumberedLine(int originalLineNumber, String line) {}

    public record CompressResult(
            List<NumberedLine> lines,
            int originalLineCount,
            int compressedLineCount) {

        public String format() {
            StringBuilder sb = new StringBuilder();
            for (NumberedLine nl : lines) {
                if (nl.originalLineNumber > 0) {
                    sb.append(String.format("%6d\t%s%n", nl.originalLineNumber, nl.line));
                } else {
                    sb.append(String.format("      \t%s%n", nl.line));
                }
            }
            return sb.toString();
        }

        public double compressionRatio() {
            if (originalLineCount == 0) return 1.0;
            return 1.0 - ((double) compressedLineCount / originalLineCount);
        }
    }
}
