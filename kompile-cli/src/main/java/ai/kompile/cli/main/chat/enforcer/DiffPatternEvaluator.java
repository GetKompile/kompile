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

package ai.kompile.cli.main.chat.enforcer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates coding patterns in unified diffs without involving an LLM at runtime.
 * Patterns are checked only against added lines (lines starting with '+') in a diff,
 * excluding the diff header itself (lines starting with '+++').
 *
 * <p>The pattern list can be bootstrapped by an LLM once and then applied purely
 * via regex/keyword matching at runtime — zero inference cost per evaluation.</p>
 *
 * <h3>Rule line format</h3>
 * <pre>
 *   BAN_DIFF: System.exit(              → literal contains on added lines
 *   BAN_DIFF_REGEX: catch\s*\(\s*Exception\s+\w+\s*\)\s*\{\s*\}  → regex on added lines
 *   STOP_DIFF: eval(                    → critical (immediate halt)
 *   STOP_DIFF_REGEX: password\s*=\s*"[^"]+"  → critical regex
 * </pre>
 *
 * <h3>JSON format</h3>
 * <pre>
 * [
 *   {
 *     "pattern": "System\\.exit\\(",
 *     "regex": true,
 *     "description": "Do not use System.exit() — throw an exception instead",
 *     "severity": "error",
 *     "scope": "diff",
 *     "fileGlob": "*.java"
 *   }
 * ]
 * </pre>
 *
 * <h3>Bootstrap flow</h3>
 * <ol>
 *   <li>User describes project rules in natural language</li>
 *   <li>An LLM generates a JSON/line-format pattern list (one-time cost)</li>
 *   <li>Patterns saved to a file (e.g., {@code .kompile/enforcer-patterns.txt})</li>
 *   <li>At runtime, this evaluator loads the file and checks diffs — no LLM</li>
 * </ol>
 */
public class DiffPatternEvaluator {

    private final List<DiffRule> rules;
    private final String rawRulesText;

    public DiffPatternEvaluator(List<DiffRule> rules, String rawRulesText) {
        this.rules = rules != null ? List.copyOf(rules) : List.of();
        this.rawRulesText = rawRulesText != null ? rawRulesText : "";
    }

    /**
     * Extract diff-scoped rules from a keyword evaluator's rules list.
     */
    public static DiffPatternEvaluator fromKeywordRules(List<KeywordEnforcerEvaluator.KeywordRule> allRules,
                                                         String rawRulesText) {
        List<DiffRule> diffRules = new ArrayList<>();
        for (KeywordEnforcerEvaluator.KeywordRule kr : allRules) {
            if ("diff".equals(kr.getScope())) {
                diffRules.add(new DiffRule(
                        kr.getKeyword(), kr.isRegex(), kr.isCaseSensitive(),
                        kr.getDescription(), kr.getSeverity(), null));
            }
        }
        return new DiffPatternEvaluator(diffRules, rawRulesText);
    }

    /**
     * Parse diff pattern rules from a standalone file.
     */
    public static DiffPatternEvaluator fromFile(Path file, ObjectMapper objectMapper) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        return fromText(content, objectMapper);
    }

    /**
     * Parse diff pattern rules from text (JSON or line format).
     */
    public static DiffPatternEvaluator fromText(String text, ObjectMapper objectMapper) {
        if (text == null || text.isBlank()) {
            return new DiffPatternEvaluator(List.of(), "");
        }

        // Try JSON first
        List<DiffRule> jsonRules = tryParseJson(text, objectMapper);
        if (jsonRules != null) {
            return new DiffPatternEvaluator(jsonRules, text);
        }

        // Line format
        List<DiffRule> lineRules = parseLineFormat(text);
        return new DiffPatternEvaluator(lineRules, text);
    }

    /**
     * Evaluate a unified diff against all diff pattern rules.
     *
     * @param unifiedDiff the full unified diff text (as from {@code git diff})
     * @return evaluation result with violations (if any) and correction prompt
     */
    public DiffEvaluation evaluate(String unifiedDiff) {
        if (rules.isEmpty() || unifiedDiff == null || unifiedDiff.isBlank()) {
            return DiffEvaluation.pass();
        }

        // Parse the diff into per-file added lines
        List<DiffHunk> hunks = parseDiff(unifiedDiff);
        if (hunks.isEmpty()) {
            return DiffEvaluation.pass();
        }

        List<DiffViolation> violations = new ArrayList<>();
        boolean shouldStop = false;

        for (DiffHunk hunk : hunks) {
            for (DiffRule rule : rules) {
                // File glob filter
                if (rule.fileGlob != null && !rule.fileGlob.isEmpty()) {
                    if (!matchesGlob(hunk.filePath, rule.fileGlob)) {
                        continue;
                    }
                }

                // Check each added line
                for (int i = 0; i < hunk.addedLines.size(); i++) {
                    String line = hunk.addedLines.get(i);
                    if (rule.matches(line)) {
                        violations.add(new DiffViolation(
                                rule, hunk.filePath, hunk.lineNumbers.get(i), line.trim()));
                        if ("critical".equalsIgnoreCase(rule.severity)) {
                            shouldStop = true;
                        }
                        break; // One match per rule per hunk is enough
                    }
                }
            }
        }

        if (violations.isEmpty()) {
            return DiffEvaluation.pass();
        }

        String correctionPrompt = buildCorrectionPrompt(violations);
        return new DiffEvaluation(false, shouldStop, violations, correctionPrompt);
    }

    public boolean isAvailable() {
        return !rules.isEmpty();
    }

    public int ruleCount() {
        return rules.size();
    }

    public List<DiffRule> getRules() {
        return rules;
    }

    public String getRawRulesText() {
        return rawRulesText;
    }

    // ── Diff parsing ───────────────────────────────────────────────────────

    private static final Pattern DIFF_FILE_HEADER = Pattern.compile("^\\+\\+\\+\\s+[ab]/(.+)$");
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@\\s+-\\d+(?:,\\d+)?\\s+\\+(\\d+)(?:,\\d+)?\\s+@@");

    /**
     * Parse unified diff into file-keyed hunks with only the added lines.
     */
    static List<DiffHunk> parseDiff(String diff) {
        List<DiffHunk> hunks = new ArrayList<>();
        String currentFile = null;
        int currentLineNo = 0;
        List<String> addedLines = new ArrayList<>();
        List<Integer> lineNumbers = new ArrayList<>();

        for (String line : diff.split("\n")) {
            Matcher fileMatcher = DIFF_FILE_HEADER.matcher(line);
            if (fileMatcher.matches()) {
                // Flush previous hunk
                if (currentFile != null && !addedLines.isEmpty()) {
                    hunks.add(new DiffHunk(currentFile, List.copyOf(addedLines), List.copyOf(lineNumbers)));
                }
                currentFile = fileMatcher.group(1);
                addedLines = new ArrayList<>();
                lineNumbers = new ArrayList<>();
                currentLineNo = 0;
                continue;
            }

            Matcher hunkMatcher = HUNK_HEADER.matcher(line);
            if (hunkMatcher.find()) {
                // Flush accumulated lines for previous hunk section
                if (currentFile != null && !addedLines.isEmpty()) {
                    hunks.add(new DiffHunk(currentFile, List.copyOf(addedLines), List.copyOf(lineNumbers)));
                    addedLines = new ArrayList<>();
                    lineNumbers = new ArrayList<>();
                }
                currentLineNo = Integer.parseInt(hunkMatcher.group(1));
                continue;
            }

            if (currentFile == null || currentLineNo == 0) {
                continue;
            }

            if (line.startsWith("+")) {
                // Added line (not the file header which we already matched above)
                addedLines.add(line.substring(1));
                lineNumbers.add(currentLineNo);
                currentLineNo++;
            } else if (line.startsWith("-")) {
                // Removed line — don't advance line counter
            } else if (line.startsWith(" ") || line.isEmpty()) {
                // Context line
                currentLineNo++;
            }
        }

        // Flush last hunk
        if (currentFile != null && !addedLines.isEmpty()) {
            hunks.add(new DiffHunk(currentFile, List.copyOf(addedLines), List.copyOf(lineNumbers)));
        }

        return hunks;
    }

    // ── Pattern matching helpers ────────────────────────────────────────────

    private static boolean matchesGlob(String filePath, String glob) {
        // Simple glob: *.java matches foo/Bar.java, src/**/*.ts matches src/a/b.ts
        String regex = glob
                .replace(".", "\\.")
                .replace("**/", "(.+/)?")
                .replace("*", "[^/]*")
                .replace("?", "[^/]");
        return Pattern.compile(regex).matcher(filePath).matches()
                || filePath.endsWith(glob.replace("*", ""));
    }

    // ── Correction prompt ────────────────────────────────────────────────────

    private String buildCorrectionPrompt(List<DiffViolation> violations) {
        StringBuilder sb = new StringBuilder();
        sb.append("STOP. Your code changes violate enforcer diff patterns.\n\n");

        sb.append("## Violations Found in Diff\n");
        for (DiffViolation v : violations) {
            sb.append("- **").append(v.filePath).append(":").append(v.lineNumber).append("** — ");
            sb.append(v.rule.description != null ? v.rule.description : "Banned pattern: " + v.rule.pattern);
            sb.append("\n  Offending line: `").append(v.matchedLine).append("`\n");
        }

        sb.append("\n## Banned Code Patterns (you MUST NOT introduce these)\n");
        for (DiffRule rule : rules) {
            sb.append("- ");
            if (rule.isRegex) {
                sb.append("[regex] ");
            }
            sb.append("`").append(rule.pattern).append("`");
            if (rule.description != null) {
                sb.append(" — ").append(rule.description);
            }
            if (rule.fileGlob != null) {
                sb.append(" (files: ").append(rule.fileGlob).append(")");
            }
            sb.append("\n");
        }

        sb.append("\n## How to Re-Comply\n");
        sb.append("1. UNDO the changes that introduced the banned patterns.\n");
        sb.append("2. Rewrite the code using the ALLOWED alternatives described above.\n");
        sb.append("3. If no alternative exists, explain why the task cannot be done under these rules.\n");
        sb.append("4. Do NOT introduce ANY of the listed patterns in your next attempt.\n");
        sb.append("5. Re-read ALL patterns before producing code — even ones you didn't violate this time.\n");

        return sb.toString();
    }

    // ── Parsing helpers ─────────────────────────────────────────────────────

    private static List<DiffRule> tryParseJson(String text, ObjectMapper objectMapper) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(trimmed);
            JsonNode rulesNode = root.isArray() ? root : root.path("rules");
            if (rulesNode.isMissingNode()) {
                rulesNode = root.path("patterns");
            }
            if (!rulesNode.isArray()) return null;

            List<DiffRule> rules = new ArrayList<>();
            for (JsonNode node : rulesNode) {
                String pattern = node.path("pattern").asText(node.path("keyword").asText(""));
                if (pattern.isBlank()) continue;
                boolean isRegex = node.path("regex").asBoolean(node.path("isRegex").asBoolean(false));
                boolean caseSensitive = node.path("caseSensitive").asBoolean(false);
                String description = node.path("description").asText(null);
                String severity = node.path("severity").asText("error");
                String fileGlob = node.path("fileGlob").asText(node.path("files").asText(null));
                rules.add(new DiffRule(pattern, isRegex, caseSensitive, description, severity, fileGlob));
            }
            return rules.isEmpty() ? null : rules;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<DiffRule> parseLineFormat(String text) {
        List<DiffRule> rules = new ArrayList<>();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                continue;
            }

            String upper = trimmed.toUpperCase(Locale.ROOT);

            if (upper.startsWith("BAN_DIFF_REGEX:")) {
                String pattern = trimmed.substring(15).trim();
                if (!pattern.isEmpty()) {
                    rules.add(new DiffRule(pattern, true, false,
                            "Banned code pattern: " + pattern, "error", null));
                }
            } else if (upper.startsWith("STOP_DIFF_REGEX:")) {
                String pattern = trimmed.substring(16).trim();
                if (!pattern.isEmpty()) {
                    rules.add(new DiffRule(pattern, true, false,
                            "Critical banned code pattern: " + pattern, "critical", null));
                }
            } else if (upper.startsWith("BAN_DIFF:")) {
                String keyword = trimmed.substring(9).trim();
                if (!keyword.isEmpty()) {
                    rules.add(new DiffRule(keyword, false, false,
                            "Banned in code: " + keyword, "error", null));
                }
            } else if (upper.startsWith("STOP_DIFF:")) {
                String keyword = trimmed.substring(10).trim();
                if (!keyword.isEmpty()) {
                    rules.add(new DiffRule(keyword, false, false,
                            "Critical ban in code: " + keyword, "critical", null));
                }
            }
            // Lines without BAN_DIFF prefix are ignored (they're for other scopes)
        }
        return rules;
    }

    // ── Data types ──────────────────────────────────────────────────────────

    public static class DiffRule {
        private final String pattern;
        private final boolean isRegex;
        private final boolean caseSensitive;
        private final String description;
        private final String severity;
        private final String fileGlob;
        private final Pattern compiledPattern;

        public DiffRule(String pattern, boolean isRegex, boolean caseSensitive,
                        String description, String severity, String fileGlob) {
            this.pattern = pattern;
            this.isRegex = isRegex;
            this.caseSensitive = caseSensitive;
            this.description = description;
            this.severity = severity != null ? severity : "error";
            this.fileGlob = fileGlob;

            if (isRegex) {
                int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
                this.compiledPattern = Pattern.compile(pattern, flags);
            } else {
                this.compiledPattern = null;
            }
        }

        public boolean matches(String line) {
            if (line == null || line.isEmpty()) return false;
            if (isRegex) {
                return compiledPattern.matcher(line).find();
            }
            if (caseSensitive) {
                return line.contains(pattern);
            }
            return line.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
        }

        public String getPattern() { return pattern; }
        public boolean isRegex() { return isRegex; }
        public boolean isCaseSensitive() { return caseSensitive; }
        public String getDescription() { return description; }
        public String getSeverity() { return severity; }
        public String getFileGlob() { return fileGlob; }
    }

    static class DiffHunk {
        final String filePath;
        final List<String> addedLines;
        final List<Integer> lineNumbers;

        DiffHunk(String filePath, List<String> addedLines, List<Integer> lineNumbers) {
            this.filePath = filePath;
            this.addedLines = addedLines;
            this.lineNumbers = lineNumbers;
        }
    }

    public record DiffViolation(DiffRule rule, String filePath, int lineNumber, String matchedLine) {}

    public record DiffEvaluation(boolean passed, boolean shouldStop,
                                  List<DiffViolation> violations, String correctionPrompt) {
        public static DiffEvaluation pass() {
            return new DiffEvaluation(true, false, List.of(), null);
        }
    }

    // ── Bootstrap prompt generation ─────────────────────────────────────────

    /**
     * Generate a prompt that can be sent to an LLM to bootstrap a pattern list
     * from a natural language description of project coding rules.
     *
     * @param projectDescription natural language description of what patterns to ban
     * @param language           primary programming language (e.g., "java", "typescript")
     * @return prompt to send to an LLM for one-shot pattern generation
     */
    public static String buildBootstrapPrompt(String projectDescription, String language) {
        return """
                You are generating a list of banned code patterns for a static diff checker.
                The checker scans ONLY added lines in git diffs (unified diff format).

                ## Project Context
                Language: %s
                Rules description:
                %s

                ## Output Format
                Produce a JSON array of pattern objects. Each object has:
                - "pattern": the literal string or regex to search for in added lines
                - "regex": boolean, true if pattern is a regex (otherwise literal contains check)
                - "caseSensitive": boolean (default false)
                - "description": WHY this is banned and what to use instead (1-2 sentences)
                - "severity": "error" (correctable) or "critical" (immediate halt)
                - "fileGlob": optional file filter (e.g., "*.java", "**/*.ts"), null if applies to all files

                ## Guidelines
                - Prefer literal patterns over regex when possible (faster, fewer false positives)
                - Use regex only for structural patterns (e.g., empty catch blocks, hardcoded secrets)
                - Keep patterns specific enough to avoid false positives on normal code
                - Include the description explaining the ALTERNATIVE the developer should use
                - Group related patterns (e.g., all "banned import" patterns together)
                - Be conservative: it's better to miss a violation than to block correct code

                ## Output ONLY the JSON array, no markdown fencing, no explanation.
                """.formatted(language, projectDescription);
    }
}
