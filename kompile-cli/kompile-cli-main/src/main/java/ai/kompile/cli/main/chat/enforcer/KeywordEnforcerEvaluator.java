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

import ai.kompile.cli.main.chat.enforcer.semantic.CompositeSemanticMatcher;
import ai.kompile.cli.main.chat.enforcer.semantic.SemanticMatch;
import ai.kompile.cli.main.chat.enforcer.semantic.SemanticMatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * A lightweight enforcer evaluator that uses keyword/pattern matching instead of an LLM.
 * When a violation is detected it pastes a reminder of all the rules and tells the LLM
 * to stop what it's doing and re-comply.
 *
 * <p>This evaluator is always available (no backend needed) and instant (no LLM latency).
 * It's ideal for hard rules like "never use X command" or "never output Y keyword"
 * where a simple contains/regex check is sufficient.</p>
 *
 * <h3>Rule format</h3>
 * Rules are loaded from a JSON file or constructed programmatically. Each rule has:
 * <ul>
 *   <li>{@code keyword} — literal text or regex pattern to search for</li>
 *   <li>{@code isRegex} — whether the keyword is a regex (default: false, literal contains check)</li>
 *   <li>{@code caseSensitive} — default false (case-insensitive matching)</li>
 *   <li>{@code description} — human-readable description of why this is banned</li>
 *   <li>{@code severity} — "error" (correctable) or "critical" (immediate stop)</li>
 *   <li>{@code scope} — where to check: "output" (text only), "tool" (tool name only),
 *       "tool_arg" (tool arguments only), "command" (tool name + args), "all" (everything)</li>
 * </ul>
 *
 * <h3>Line format prefixes for tool/command bans</h3>
 * <pre>
 *   BAN_TOOL: bash              → blocks the "bash" tool entirely
 *   BAN_TOOL: write             → blocks the "write" tool entirely
 *   BAN_CMD: rm -rf             → blocks any tool call whose args contain "rm -rf"
 *   BAN_CMD_REGEX: git\s+push   → blocks tool args matching regex
 *   STOP_TOOL: bash             → blocks bash tool, critical severity (immediate halt)
 * </pre>
 */
public class KeywordEnforcerEvaluator implements EnforcerEvaluator {

    private final List<KeywordRule> rules;
    private final String rawRulesText;
    private volatile SemanticMatcher semanticMatcher;
    // Pre-expanded variants for each banned keyword (keyed by keyword text)
    private final Map<String, List<String>> expandedVariants = new HashMap<>();

    public KeywordEnforcerEvaluator(List<KeywordRule> rules, String rawRulesText) {
        this.rules = rules != null ? List.copyOf(rules) : List.of();
        this.rawRulesText = rawRulesText != null ? rawRulesText : buildRulesText(this.rules);
    }

    /**
     * Attach a semantic matcher for synonym/embedding-based detection.
     * When set, all output-scoped keyword rules will also be checked semantically.
     * Pre-expands all keyword rules at attach time for fast runtime matching.
     */
    public void setSemanticMatcher(SemanticMatcher matcher) {
        this.semanticMatcher = matcher;
        expandedVariants.clear();
        if (matcher != null && matcher.isAvailable()) {
            for (KeywordRule rule : rules) {
                if (rule.appliesToOutput() && !rule.isRegex()) {
                    List<String> expanded = matcher.expand(rule.getKeyword());
                    expandedVariants.put(rule.getKeyword(), expanded);
                }
            }
        }
    }

    public SemanticMatcher getSemanticMatcher() {
        return semanticMatcher;
    }

    /**
     * Create an evaluator with semantic matching configured from EnforcerConfig.
     */
    public static KeywordEnforcerEvaluator withSemantics(List<KeywordRule> rules, String rawRulesText,
                                                          EnforcerConfig config) {
        KeywordEnforcerEvaluator evaluator = new KeywordEnforcerEvaluator(rules, rawRulesText);
        if (config != null && !"none".equalsIgnoreCase(config.getSemanticMode())) {
            CompositeSemanticMatcher matcher = CompositeSemanticMatcher.create(
                    config.getSemanticMode(),
                    config.getEmbeddingUrl(),
                    config.getSemanticThreshold()
            );
            if (matcher.isAvailable()) {
                evaluator.setSemanticMatcher(matcher);
            }
        }
        return evaluator;
    }

    /**
     * Parse keyword rules from the enforcer policy rules text.
     * Supports two formats:
     * <ol>
     *   <li>JSON array of rule objects</li>
     *   <li>Simple line-per-keyword format: each line is a banned keyword (literal, case-insensitive)</li>
     * </ol>
     */
    public static KeywordEnforcerEvaluator fromPolicy(EnforcerPolicy policy, ObjectMapper objectMapper) {
        return fromPolicy(policy, objectMapper, null);
    }

    /**
     * Parse keyword rules from the enforcer policy rules text, with optional semantic matching.
     */
    public static KeywordEnforcerEvaluator fromPolicy(EnforcerPolicy policy, ObjectMapper objectMapper,
                                                       EnforcerConfig config) {
        if (policy == null || !policy.hasRules()) {
            return new KeywordEnforcerEvaluator(List.of(), "");
        }

        String rulesText = policy.getRules();
        List<KeywordRule> parsed = tryParseJson(rulesText, objectMapper);
        if (parsed == null) {
            parsed = parseLineFormat(rulesText);
        }

        if (config != null && !"none".equalsIgnoreCase(config.getSemanticMode())) {
            return withSemantics(parsed, rulesText, config);
        }
        return new KeywordEnforcerEvaluator(parsed, rulesText);
    }

    /**
     * Load keyword rules from a JSON file.
     */
    public static KeywordEnforcerEvaluator fromFile(Path rulesFile, ObjectMapper objectMapper) throws IOException {
        String content = Files.readString(rulesFile, StandardCharsets.UTF_8);
        List<KeywordRule> rules = tryParseJson(content, objectMapper);
        if (rules == null) {
            rules = parseLineFormat(content);
        }
        return new KeywordEnforcerEvaluator(rules, content);
    }

    @Override
    public EnforcerDecision evaluate(String userPrompt, String agentOutput,
                                     EnforcerPolicy policy, int attempt) {
        return evaluate(userPrompt, agentOutput, policy, attempt, EnforcerConversationContext.empty());
    }

    @Override
    public EnforcerDecision evaluate(String userPrompt, String agentOutput,
                                     EnforcerPolicy policy, int attempt,
                                     EnforcerConversationContext context) {
        if (agentOutput == null || agentOutput.isBlank()) {
            return EnforcerDecision.pass("Empty output, no keywords to check");
        }

        List<String> violations = new ArrayList<>();
        boolean shouldStop = false;
        String textToCheck = agentOutput;

        for (KeywordRule rule : rules) {
            if (!rule.appliesToOutput()) {
                continue;
            }
            if (rule.matches(textToCheck)) {
                violations.add(rule.description != null ? rule.description
                        : "Banned keyword detected: " + rule.keyword);
                if ("critical".equalsIgnoreCase(rule.severity)) {
                    shouldStop = true;
                }
            }
        }

        // Semantic matching: check for reworded equivalents of banned keywords
        if (semanticMatcher != null && semanticMatcher.isAvailable()) {
            for (KeywordRule rule : rules) {
                if (!rule.appliesToOutput() || rule.isRegex()) continue;
                // Skip if already caught by literal matching
                String ruleDesc = rule.description != null ? rule.description
                        : "Banned keyword detected: " + rule.keyword;
                if (violations.contains(ruleDesc)) continue;

                List<String> expanded = expandedVariants.get(rule.getKeyword());
                SemanticMatch match;
                if (expanded != null && !expanded.isEmpty()) {
                    match = semanticMatcher.matchesWithExpansion(textToCheck, rule.getKeyword(), expanded);
                } else {
                    match = semanticMatcher.matches(textToCheck, rule.getKeyword());
                }
                if (match != null) {
                    violations.add("Semantic violation (" + match.describe() + "): " + rule.getKeyword());
                    if ("critical".equalsIgnoreCase(rule.getSeverity())) {
                        shouldStop = true;
                    }
                }
            }
        }

        if (violations.isEmpty()) {
            return EnforcerDecision.pass("No banned keywords detected");
        }

        if (shouldStop) {
            return EnforcerDecision.stop(violations,
                    "Critical keyword violation — immediate halt required");
        }

        // Build correction prompt: paste the full rules + how to re-comply
        String correctionPrompt = buildCorrectionMessage(violations, policy);
        return EnforcerDecision.fail(violations, correctionPrompt,
                "Keyword violations found: " + violations.size());
    }

    /**
     * Evaluate a proposed tool call against keyword rules.
     * Checks tool name against tool-scoped rules and tool arguments against
     * command/tool_arg-scoped rules.
     *
     * @param toolName the MCP tool being called (e.g. "bash", "write", "edit")
     * @param toolArgs serialized tool arguments (JSON string)
     * @param policy   the active enforcer policy (used in correction prompt)
     * @return ALLOW if no rules match, BLOCK with correction prompt if violated
     */
    public EnforcerToolCallDecision evaluateToolCall(String toolName, String toolArgs,
                                                     EnforcerPolicy policy) {
        List<String> violations = new ArrayList<>();
        boolean shouldStop = false;

        for (KeywordRule rule : rules) {
            boolean matched = false;
            String scope = rule.getScope();

            switch (scope) {
                case "tool":
                    // Check tool name only
                    matched = rule.matches(toolName != null ? toolName : "");
                    break;
                case "tool_arg":
                    // Check tool arguments only
                    matched = rule.matches(toolArgs != null ? toolArgs : "");
                    break;
                case "command":
                    // Check tool name + args concatenated
                    String combined = (toolName != null ? toolName : "") + " " + (toolArgs != null ? toolArgs : "");
                    matched = rule.matches(combined);
                    break;
                case "all":
                    // Check against both tool name and args separately
                    matched = rule.matches(toolName != null ? toolName : "")
                            || rule.matches(toolArgs != null ? toolArgs : "");
                    break;
                case "output":
                default:
                    // Output-only rules also check tool args since commands appear there
                    matched = rule.matches(toolArgs != null ? toolArgs : "");
                    break;
            }

            if (matched) {
                violations.add(rule.description != null ? rule.description
                        : "Banned in tool call: " + rule.keyword);
                if ("critical".equalsIgnoreCase(rule.severity)) {
                    shouldStop = true;
                }
            }
        }

        if (violations.isEmpty()) {
            return EnforcerToolCallDecision.allow("No keyword rules violated");
        }

        String correctionPrompt = buildToolCorrectionMessage(toolName, violations, policy);
        return new EnforcerToolCallDecision(
                EnforcerToolCallDecision.Action.BLOCK,
                String.join("; ", violations),
                violations,
                correctionPrompt,
                null);
    }

    @Override
    public boolean isAvailable() {
        return !rules.isEmpty();
    }

    @Override
    public String describe() {
        long toolRules = rules.stream().filter(r -> !r.getScope().equals("output")).count();
        String base = "keyword-evaluator (" + rules.size() + " rules, " + toolRules + " tool bans)";
        if (semanticMatcher != null && semanticMatcher.isAvailable()) {
            base += " + semantic:" + semanticMatcher.matcherType();
        }
        return base;
    }

    public List<KeywordRule> getRules() {
        return rules;
    }

    // ── Correction message builders ─────────────────────────────────────

    private String buildCorrectionMessage(List<String> violations, EnforcerPolicy policy) {
        StringBuilder sb = new StringBuilder();
        sb.append("STOP. You have violated the enforcer rules.\n\n");

        sb.append("## Violations Detected\n");
        for (String v : violations) {
            sb.append("- ").append(v).append("\n");
        }

        sb.append("\n## The Rules (you MUST follow these)\n");
        if (policy != null && policy.hasRules()) {
            sb.append(policy.getRules());
        } else {
            sb.append(rawRulesText);
        }

        sb.append("\n\n## How to Re-Comply\n");
        sb.append("1. STOP your current approach immediately.\n");
        sb.append("2. Remove or rewrite any output that contains the banned keywords/patterns.\n");
        sb.append("3. Re-read the rules above and produce a response that adheres to ALL of them.\n");
        sb.append("4. Do NOT mention the enforcer system or apologize — just produce the correct output.\n");
        sb.append("5. If the rules make your original task impossible, say so clearly and suggest an alternative.\n");

        return sb.toString();
    }

    private String buildToolCorrectionMessage(String toolName, List<String> violations,
                                              EnforcerPolicy policy) {
        StringBuilder sb = new StringBuilder();
        sb.append("STOP. Your tool call '").append(toolName != null ? toolName : "unknown")
                .append("' was BLOCKED by the enforcer.\n\n");

        sb.append("## Violations\n");
        for (String v : violations) {
            sb.append("- ").append(v).append("\n");
        }

        sb.append("\n## The Rules (you MUST follow these)\n");
        if (policy != null && policy.hasRules()) {
            sb.append(policy.getRules());
        } else {
            sb.append(rawRulesText);
        }

        sb.append("\n\n## How to Re-Comply\n");
        sb.append("1. Do NOT retry this tool call or any variant that matches the banned patterns.\n");
        sb.append("2. Re-read the rules above. They list banned tools and commands explicitly.\n");
        sb.append("3. Find an ALTERNATIVE approach that does not use the banned tool/command.\n");
        sb.append("4. If no alternative exists, state clearly that the task cannot be completed under the current rules.\n");
        sb.append("5. NEVER bypass, work around, or rename the banned operation to evade these rules.\n");

        return sb.toString();
    }

    // ── Parsing helpers ─────────────────────────────────────────────────

    private static List<KeywordRule> tryParseJson(String text, ObjectMapper objectMapper) {
        String trimmed = text.trim();
        // Must look like a JSON array or object with a "rules" key
        if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(trimmed);
            JsonNode rulesNode = root.isArray() ? root : root.path("rules");
            if (!rulesNode.isArray()) return null;

            List<KeywordRule> rules = new ArrayList<>();
            for (JsonNode node : rulesNode) {
                String keyword = node.path("keyword").asText(node.path("pattern").asText(""));
                if (keyword.isBlank()) continue;
                boolean isRegex = node.path("isRegex").asBoolean(node.path("regex").asBoolean(false));
                boolean caseSensitive = node.path("caseSensitive").asBoolean(false);
                String description = node.path("description").asText(null);
                String severity = node.path("severity").asText("error");
                String scope = node.path("scope").asText("output");
                rules.add(new KeywordRule(keyword, isRegex, caseSensitive, description, severity, scope));
            }
            return rules.isEmpty() ? null : rules;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parse simple line format. Lines starting with special prefixes become rules:
     * <pre>
     *   BAN: keyword            → error severity, literal match on output
     *   STOP: keyword           → critical severity (immediate halt), literal match on output
     *   REGEX: pattern          → error severity, regex match on output
     *   STOP_REGEX: pattern     → critical severity, regex match on output
     *   BAN_TOOL: toolname      → blocks a tool by name (e.g. "bash", "write")
     *   STOP_TOOL: toolname     → blocks a tool by name, critical severity
     *   BAN_CMD: command        → blocks tool args containing this text
     *   BAN_CMD_REGEX: pattern  → blocks tool args matching this regex
     *   STOP_CMD: command       → blocks tool args containing this text, critical severity
     *   BAN_DIFF: pattern       → blocks code pattern in added diff lines
     *   BAN_DIFF_REGEX: pattern → blocks code pattern (regex) in added diff lines
     *   STOP_DIFF: pattern      → critical code pattern ban in diffs
     *   STOP_DIFF_REGEX: pattern → critical code pattern ban (regex) in diffs
     * </pre>
     * Lines without prefixes are treated as general banned keywords (error severity, literal,
     * matched against both output text and tool arguments).
     */
    private static List<KeywordRule> parseLineFormat(String text) {
        List<KeywordRule> rules = new ArrayList<>();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                continue;
            }

            String upper = trimmed.toUpperCase(Locale.ROOT);

            // Diff pattern bans (check before BAN: to avoid prefix conflict)
            if (upper.startsWith("BAN_DIFF_REGEX:")) {
                String pattern = trimmed.substring(15).trim();
                if (!pattern.isEmpty()) {
                    rules.add(new KeywordRule(pattern, true, false,
                            "Banned code pattern: " + pattern, "error", "diff"));
                }
            } else if (upper.startsWith("STOP_DIFF_REGEX:")) {
                String pattern = trimmed.substring(16).trim();
                if (!pattern.isEmpty()) {
                    rules.add(new KeywordRule(pattern, true, false,
                            "Critical banned code pattern: " + pattern, "critical", "diff"));
                }
            } else if (upper.startsWith("BAN_DIFF:")) {
                String keyword = trimmed.substring(9).trim();
                if (!keyword.isEmpty()) {
                    rules.add(new KeywordRule(keyword, false, false,
                            "Banned in code: " + keyword, "error", "diff"));
                }
            } else if (upper.startsWith("STOP_DIFF:")) {
                String keyword = trimmed.substring(10).trim();
                if (!keyword.isEmpty()) {
                    rules.add(new KeywordRule(keyword, false, false,
                            "Critical ban in code: " + keyword, "critical", "diff"));
                }
            }
            // Tool bans (check before BAN: to avoid prefix conflict)
            else if (upper.startsWith("BAN_TOOL:")) {
                String tool = trimmed.substring(9).trim();
                if (!tool.isEmpty()) {
                    rules.add(new KeywordRule(tool, false, false,
                            "Banned tool: " + tool, "error", "tool"));
                }
            } else if (upper.startsWith("STOP_TOOL:")) {
                String tool = trimmed.substring(10).trim();
                if (!tool.isEmpty()) {
                    rules.add(new KeywordRule(tool, false, false,
                            "Banned tool (stop): " + tool, "critical", "tool"));
                }
            } else if (upper.startsWith("BAN_CMD_REGEX:")) {
                String pattern = trimmed.substring(14).trim();
                if (!pattern.isEmpty()) {
                    rules.add(new KeywordRule(pattern, true, false,
                            "Banned command pattern: " + pattern, "error", "command"));
                }
            } else if (upper.startsWith("BAN_CMD:")) {
                String cmd = trimmed.substring(8).trim();
                if (!cmd.isEmpty()) {
                    rules.add(new KeywordRule(cmd, false, false,
                            "Banned command: " + cmd, "error", "command"));
                }
            } else if (upper.startsWith("STOP_CMD:")) {
                String cmd = trimmed.substring(9).trim();
                if (!cmd.isEmpty()) {
                    rules.add(new KeywordRule(cmd, false, false,
                            "Banned command (stop): " + cmd, "critical", "command"));
                }
            }
            // Output text bans
            else if (upper.startsWith("BAN:")) {
                String keyword = trimmed.substring(4).trim();
                if (!keyword.isEmpty()) {
                    rules.add(new KeywordRule(keyword, false, false,
                            "Banned: " + keyword, "error", "output"));
                }
            } else if (upper.startsWith("STOP:")) {
                String keyword = trimmed.substring(5).trim();
                if (!keyword.isEmpty()) {
                    rules.add(new KeywordRule(keyword, false, false,
                            "Critical ban (stop): " + keyword, "critical", "output"));
                }
            } else if (upper.startsWith("REGEX:")) {
                String pattern = trimmed.substring(6).trim();
                if (!pattern.isEmpty()) {
                    rules.add(new KeywordRule(pattern, true, false,
                            "Banned pattern: " + pattern, "error", "output"));
                }
            } else if (upper.startsWith("STOP_REGEX:")) {
                String pattern = trimmed.substring(11).trim();
                if (!pattern.isEmpty()) {
                    rules.add(new KeywordRule(pattern, true, false,
                            "Critical ban (stop) pattern: " + pattern, "critical", "output"));
                }
            } else {
                // Default: treat as banned keyword, applies to everything (output + tool args)
                rules.add(new KeywordRule(trimmed, false, false,
                        "Banned: " + trimmed, "error", "all"));
                }
        }
        return rules;
    }

    private static String buildRulesText(List<KeywordRule> rules) {
        StringBuilder sb = new StringBuilder();
        for (KeywordRule rule : rules) {
            sb.append("- ").append(rule.description != null ? rule.description : rule.keyword).append("\n");
        }
        return sb.toString();
    }

    // ── Rule model ──────────────────────────────────────────────────────

    /**
     * Scopes determine where a rule is checked:
     * <ul>
     *   <li>{@code output} — only check agent text output</li>
     *   <li>{@code tool} — only check tool name (blocks a tool entirely)</li>
     *   <li>{@code tool_arg} — only check serialized tool arguments</li>
     *   <li>{@code command} — check tool name + arguments concatenated</li>
     *   <li>{@code all} — check everything (output text, tool names, tool args)</li>
     * </ul>
     */
    public static class KeywordRule {
        private final String keyword;
        private final boolean isRegex;
        private final boolean caseSensitive;
        private final String description;
        private final String severity;
        private final String scope;
        private final Pattern compiledPattern;

        public KeywordRule(String keyword, boolean isRegex, boolean caseSensitive,
                           String description, String severity) {
            this(keyword, isRegex, caseSensitive, description, severity, "output");
        }

        public KeywordRule(String keyword, boolean isRegex, boolean caseSensitive,
                           String description, String severity, String scope) {
            this.keyword = keyword;
            this.isRegex = isRegex;
            this.caseSensitive = caseSensitive;
            this.description = description;
            this.severity = severity != null ? severity : "error";
            this.scope = scope != null ? scope.toLowerCase(Locale.ROOT) : "output";

            if (isRegex) {
                int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
                this.compiledPattern = Pattern.compile(keyword, flags);
            } else {
                this.compiledPattern = null;
            }
        }

        public boolean matches(String text) {
            if (text == null || text.isEmpty()) return false;

            if (isRegex) {
                return compiledPattern.matcher(text).find();
            }

            // Simple contains check
            if (caseSensitive) {
                return text.contains(keyword);
            }
            return text.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
        }

        /**
         * Returns true if this rule should be checked against agent text output.
         * Tool-only rules (scope "tool", "tool_arg", "command") and diff rules skip output checking.
         */
        public boolean appliesToOutput() {
            return "output".equals(scope) || "all".equals(scope);
        }

        /**
         * Returns true if this rule should be checked against diff content (added lines).
         */
        public boolean appliesToDiff() {
            return "diff".equals(scope);
        }

        /**
         * Returns true if this rule should be checked during tool call evaluation.
         * Output-only rules still check tool args (commands appear there),
         * but tool-scoped rules are explicit about what they target.
         */
        public boolean appliesToToolCalls() {
            return true; // All rules participate in tool evaluation
        }

        public String getKeyword() { return keyword; }
        public boolean isRegex() { return isRegex; }
        public boolean isCaseSensitive() { return caseSensitive; }
        public String getDescription() { return description; }
        public String getSeverity() { return severity; }
        public String getScope() { return scope; }
    }
}
