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

import ai.kompile.cli.main.chat.enforcer.EnforcerConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP tool for viewing and modifying the enforcer configuration via the agent.
 * Allows the LLM agent to inspect and update enforcer settings including
 * banned keywords, semantic matching mode, thresholds, and other options.
 *
 * <p>Actions:
 * <ul>
 *   <li>{@code status} — show current enforcer config summary</li>
 *   <li>{@code get} — get the full config as JSON</li>
 *   <li>{@code set} — update specific config fields</li>
 *   <li>{@code add_keyword} — add a banned keyword</li>
 *   <li>{@code remove_keyword} — remove a banned keyword</li>
 *   <li>{@code add_tool_ban} — add a banned tool</li>
 *   <li>{@code remove_tool_ban} — remove a banned tool</li>
 *   <li>{@code set_semantic} — configure semantic matching mode/threshold/URL</li>
 *   <li>{@code test} — test whether a phrase would be caught by semantic expansion</li>
 *   <li>{@code init} — create a new enforcer config with defaults</li>
 *   <li>{@code delete} — remove the enforcer config</li>
 * </ul></p>
 */
public class EnforcerConfigTool implements CliTool {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public String id() {
        return "enforcer_config";
    }

    @Override
    public String description() {
        return "View and modify the enforcer configuration for this project. "
                + "The enforcer monitors agent output for banned keywords, patterns, and semantic equivalents. "
                + "Actions: 'status' (summary), 'get' (full JSON config), "
                + "'set' (update fields: keyword_mode, max_corrections, semantic_mode, semantic_threshold, "
                + "embedding_url, inline_rules, archive_diffs, auto_rollback), "
                + "'add_keyword'/'remove_keyword' (manage banned keywords), "
                + "'add_tool_ban'/'remove_tool_ban' (manage banned tools), "
                + "'set_semantic' (configure semantic matching: mode, threshold, embedding_url, synonym_dictionary), "
                + "'test' (test if a phrase would be caught by semantic expansion), "
                + "'init' (create default config), 'delete' (remove config).";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("action").put("type", "string")
                .put("description", "Action to perform: status, get, set, add_keyword, remove_keyword, "
                        + "add_tool_ban, remove_tool_ban, set_semantic, test, init, delete");

        // For 'set' action — key-value pairs
        props.putObject("field").put("type", "string")
                .put("description", "Config field to set (for 'set' action): keyword_mode, max_corrections, "
                        + "semantic_mode, semantic_threshold, embedding_url, inline_rules, "
                        + "archive_diffs, auto_rollback, agent, rule_file, primary_language");
        props.putObject("value").put("type", "string")
                .put("description", "Value to set (for 'set' action). Use 'true'/'false' for booleans, numbers as strings.");

        // For add/remove actions
        props.putObject("keyword").put("type", "string")
                .put("description", "Keyword/tool to add or remove (for add_keyword/remove_keyword/add_tool_ban/remove_tool_ban)");

        // For set_semantic action
        props.putObject("mode").put("type", "string")
                .put("description", "Semantic mode (for 'set_semantic'): none, wordnet, embedding, both");
        props.putObject("threshold").put("type", "number")
                .put("description", "Similarity threshold 0.0-1.0 (for 'set_semantic', default 0.78)");
        props.putObject("embedding_url").put("type", "string")
                .put("description", "Embedding endpoint URL (for 'set_semantic')");
        props.putObject("synonym_dictionary").put("type", "string")
                .put("description", "Path to custom synonym dictionary JSON (for 'set_semantic')");

        // For 'test' action
        props.putObject("phrase").put("type", "string")
                .put("description", "Phrase to test semantic expansion against (for 'test' action)");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() {
        return "enforcer_config";
    }

    @Override
    public McpToolAnnotations mcpAnnotations() {
        return McpToolAnnotations.WRITE;
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        String action = params.path("action").asText("status");
        Path wd = context.getWorkingDirectory();

        try {
            return switch (action) {
                case "status" -> executeStatus(wd);
                case "get" -> executeGet(wd);
                case "set" -> executeSet(params, wd);
                case "add_keyword" -> executeAddKeyword(params, wd);
                case "remove_keyword" -> executeRemoveKeyword(params, wd);
                case "add_tool_ban" -> executeAddToolBan(params, wd);
                case "remove_tool_ban" -> executeRemoveToolBan(params, wd);
                case "set_semantic" -> executeSetSemantic(params, wd);
                case "test" -> executeTest(params, wd);
                case "init" -> executeInit(wd);
                case "delete" -> executeDelete(wd);
                default -> ToolResult.error("Unknown action: " + action
                        + ". Use: status, get, set, add_keyword, remove_keyword, add_tool_ban, "
                        + "remove_tool_ban, set_semantic, test, init, delete");
            };
        } catch (Exception e) {
            return ToolResult.error("Enforcer config error: " + e.getMessage());
        }
    }

    // ── Action implementations ──────────────────────────────────────────────

    private ToolResult executeStatus(Path wd) {
        EnforcerConfig config = EnforcerConfig.load(wd);
        if (config == null) {
            return ToolResult.success("No enforcer config found for this project.\n"
                    + "Use action='init' to create one, or run `kompile enforcer init` interactively.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Enforcer Config: ").append(EnforcerConfig.resolveConfigPath(wd)).append("\n");
        sb.append("──────────────────────────────────────\n");
        sb.append("Agent:           ").append(config.getAgent()).append("\n");
        sb.append("Mode:            ").append(config.isKeywordMode() ? "keyword" : "LLM judge").append("\n");
        sb.append("Max corrections: ").append(config.getMaxCorrections()).append("\n");
        sb.append("Semantic mode:   ").append(config.getSemanticMode()).append("\n");
        if (!"none".equals(config.getSemanticMode())) {
            sb.append("Sim. threshold:  ").append(config.getSemanticThreshold()).append("\n");
            if (config.getEmbeddingUrl() != null && !config.getEmbeddingUrl().isBlank()) {
                sb.append("Embedding URL:   ").append(config.getEmbeddingUrl()).append("\n");
            }
            if (config.getSynonymDictionaryPath() != null) {
                sb.append("Synonym dict:    ").append(config.getSynonymDictionaryPath()).append("\n");
            }
        }
        sb.append("Archive diffs:   ").append(config.isArchiveDiffs() ? "enabled" : "disabled").append("\n");
        sb.append("Auto-rollback:   ").append(config.isAutoRollbackOnViolation() ? "yes" : "no").append("\n");
        if (!config.getBannedKeywords().isEmpty()) {
            sb.append("Banned keywords: ").append(String.join(", ", config.getBannedKeywords())).append("\n");
        }
        if (!config.getBannedTools().isEmpty()) {
            sb.append("Banned tools:    ").append(String.join(", ", config.getBannedTools())).append("\n");
        }
        if (!config.getBannedCommands().isEmpty()) {
            sb.append("Banned commands: ").append(String.join(", ", config.getBannedCommands())).append("\n");
        }
        if (config.getInlineRules() != null && !config.getInlineRules().isBlank()) {
            sb.append("Inline rules:    ").append(config.getInlineRules().split("\n").length).append(" lines\n");
        }
        if (!config.getDiffPatternRules().isEmpty()) {
            sb.append("Diff patterns:   ").append(config.getDiffPatternRules().size()).append(" rules\n");
        }

        return ToolResult.success(sb.toString());
    }

    private ToolResult executeGet(Path wd) throws Exception {
        EnforcerConfig config = EnforcerConfig.load(wd);
        if (config == null) {
            return ToolResult.error("No enforcer config found. Use action='init' to create one.");
        }
        return ToolResult.success(MAPPER.writeValueAsString(config));
    }

    private ToolResult executeSet(JsonNode params, Path wd) throws Exception {
        EnforcerConfig config = EnforcerConfig.load(wd);
        if (config == null) {
            config = new EnforcerConfig();
        }

        String field = params.path("field").asText("");
        String value = params.path("value").asText("");

        if (field.isBlank()) {
            return ToolResult.error("'field' parameter required for 'set' action.");
        }

        switch (field) {
            case "keyword_mode" -> config.setKeywordMode(Boolean.parseBoolean(value));
            case "max_corrections" -> config.setMaxCorrections(Integer.parseInt(value));
            case "semantic_mode" -> config.setSemanticMode(value);
            case "semantic_threshold" -> config.setSemanticThreshold(Double.parseDouble(value));
            case "embedding_url" -> config.setEmbeddingUrl(value);
            case "inline_rules" -> config.setInlineRules(value.replace("\\n", "\n"));
            case "archive_diffs" -> config.setArchiveDiffs(Boolean.parseBoolean(value));
            case "auto_rollback" -> config.setAutoRollbackOnViolation(Boolean.parseBoolean(value));
            case "agent" -> config.setAgent(value);
            case "rule_file" -> config.setRuleFile(value);
            case "primary_language" -> config.setPrimaryLanguage(value);
            case "judge_provider" -> config.setJudgeProvider(value);
            case "judge_model" -> config.setJudgeModel(value);
            case "synonym_dictionary" -> config.setSynonymDictionaryPath(value);
            default -> {
                return ToolResult.error("Unknown field: " + field + ". Valid fields: keyword_mode, "
                        + "max_corrections, semantic_mode, semantic_threshold, embedding_url, "
                        + "inline_rules, archive_diffs, auto_rollback, agent, rule_file, "
                        + "primary_language, judge_provider, judge_model, synonym_dictionary");
            }
        }

        config.save(wd);
        return ToolResult.success("Set " + field + " = " + value + "\nConfig saved to: "
                + EnforcerConfig.resolveConfigPath(wd));
    }

    private ToolResult executeAddKeyword(JsonNode params, Path wd) throws Exception {
        String keyword = params.path("keyword").asText("");
        if (keyword.isBlank()) {
            return ToolResult.error("'keyword' parameter required.");
        }

        EnforcerConfig config = EnforcerConfig.load(wd);
        if (config == null) config = new EnforcerConfig();

        List<String> keywords = new ArrayList<>(config.getBannedKeywords());
        if (!keywords.contains(keyword)) {
            keywords.add(keyword);
            config.setBannedKeywords(keywords);
            config.save(wd);
            return ToolResult.success("Added banned keyword: \"" + keyword + "\"\n"
                    + "Total banned keywords: " + keywords.size());
        }
        return ToolResult.success("Keyword already banned: \"" + keyword + "\"");
    }

    private ToolResult executeRemoveKeyword(JsonNode params, Path wd) throws Exception {
        String keyword = params.path("keyword").asText("");
        if (keyword.isBlank()) {
            return ToolResult.error("'keyword' parameter required.");
        }

        EnforcerConfig config = EnforcerConfig.load(wd);
        if (config == null) return ToolResult.error("No enforcer config found.");

        List<String> keywords = new ArrayList<>(config.getBannedKeywords());
        if (keywords.remove(keyword)) {
            config.setBannedKeywords(keywords);
            config.save(wd);
            return ToolResult.success("Removed banned keyword: \"" + keyword + "\"\n"
                    + "Remaining: " + keywords.size());
        }
        return ToolResult.error("Keyword not found in banned list: \"" + keyword + "\"");
    }

    private ToolResult executeAddToolBan(JsonNode params, Path wd) throws Exception {
        String tool = params.path("keyword").asText("");
        if (tool.isBlank()) {
            return ToolResult.error("'keyword' parameter required (tool name to ban).");
        }

        EnforcerConfig config = EnforcerConfig.load(wd);
        if (config == null) config = new EnforcerConfig();

        List<String> tools = new ArrayList<>(config.getBannedTools());
        if (!tools.contains(tool)) {
            tools.add(tool);
            config.setBannedTools(tools);
            config.save(wd);
            return ToolResult.success("Added banned tool: \"" + tool + "\"\n"
                    + "Total banned tools: " + tools.size());
        }
        return ToolResult.success("Tool already banned: \"" + tool + "\"");
    }

    private ToolResult executeRemoveToolBan(JsonNode params, Path wd) throws Exception {
        String tool = params.path("keyword").asText("");
        if (tool.isBlank()) {
            return ToolResult.error("'keyword' parameter required (tool name to unban).");
        }

        EnforcerConfig config = EnforcerConfig.load(wd);
        if (config == null) return ToolResult.error("No enforcer config found.");

        List<String> tools = new ArrayList<>(config.getBannedTools());
        if (tools.remove(tool)) {
            config.setBannedTools(tools);
            config.save(wd);
            return ToolResult.success("Removed banned tool: \"" + tool + "\"\n"
                    + "Remaining: " + tools.size());
        }
        return ToolResult.error("Tool not found in banned list: \"" + tool + "\"");
    }

    private ToolResult executeSetSemantic(JsonNode params, Path wd) throws Exception {
        EnforcerConfig config = EnforcerConfig.load(wd);
        if (config == null) config = new EnforcerConfig();

        String mode = params.path("mode").asText(null);
        if (mode != null && !mode.isBlank()) {
            if (!List.of("none", "wordnet", "embedding", "both").contains(mode.toLowerCase())) {
                return ToolResult.error("Invalid mode: " + mode + ". Use: none, wordnet, embedding, both");
            }
            config.setSemanticMode(mode.toLowerCase());
        }

        if (params.has("threshold")) {
            double threshold = params.path("threshold").asDouble(0.78);
            if (threshold <= 0 || threshold > 1.0) {
                return ToolResult.error("Threshold must be between 0.0 and 1.0");
            }
            config.setSemanticThreshold(threshold);
        }

        String embUrl = params.path("embedding_url").asText(null);
        if (embUrl != null && !embUrl.isBlank()) {
            config.setEmbeddingUrl(embUrl);
        }

        String synDict = params.path("synonym_dictionary").asText(null);
        if (synDict != null && !synDict.isBlank()) {
            config.setSynonymDictionaryPath(synDict);
        }

        config.save(wd);

        StringBuilder sb = new StringBuilder("Semantic matching updated:\n");
        sb.append("  Mode:       ").append(config.getSemanticMode()).append("\n");
        sb.append("  Threshold:  ").append(config.getSemanticThreshold()).append("\n");
        if (config.getEmbeddingUrl() != null && !config.getEmbeddingUrl().isBlank()) {
            sb.append("  Embed URL:  ").append(config.getEmbeddingUrl()).append("\n");
        }
        if (config.getSynonymDictionaryPath() != null) {
            sb.append("  Dict:       ").append(config.getSynonymDictionaryPath()).append("\n");
        }
        return ToolResult.success(sb.toString());
    }

    private ToolResult executeTest(JsonNode params, Path wd) {
        String phrase = params.path("phrase").asText("");
        if (phrase.isBlank()) {
            return ToolResult.error("'phrase' parameter required for 'test' action.");
        }

        EnforcerConfig config = EnforcerConfig.load(wd);
        String mode = config != null ? config.getSemanticMode() : "wordnet";
        double threshold = config != null ? config.getSemanticThreshold() : 0.78;
        String embUrl = config != null ? config.getEmbeddingUrl() : null;

        var matcher = ai.kompile.cli.main.chat.enforcer.semantic.CompositeSemanticMatcher.create(
                mode.equals("none") ? "wordnet" : mode, embUrl, threshold);

        if (!matcher.isAvailable()) {
            // Fall back to wordnet-only for testing
            matcher = ai.kompile.cli.main.chat.enforcer.semantic.CompositeSemanticMatcher.create(
                    "wordnet", null, threshold);
        }

        List<String> expanded = matcher.expand(phrase);

        StringBuilder sb = new StringBuilder();
        sb.append("Semantic expansion for: \"").append(phrase).append("\"\n");
        sb.append("Matcher type: ").append(matcher.matcherType()).append("\n");
        sb.append("Expanded variants (").append(expanded.size()).append("):\n");
        for (int i = 0; i < Math.min(expanded.size(), 30); i++) {
            sb.append("  - ").append(expanded.get(i)).append("\n");
        }
        if (expanded.size() > 30) {
            sb.append("  ... and ").append(expanded.size() - 30).append(" more\n");
        }

        // Also test against current banned keywords if config exists
        if (config != null && !config.getBannedKeywords().isEmpty()) {
            sb.append("\nMatching against banned keywords:\n");
            for (String banned : config.getBannedKeywords()) {
                var match = matcher.matches(phrase, banned);
                if (match != null) {
                    sb.append("  MATCH: \"").append(phrase).append("\" ~ \"")
                            .append(banned).append("\" → ").append(match.describe()).append("\n");
                }
            }
        }

        return ToolResult.success(sb.toString());
    }

    private ToolResult executeInit(Path wd) throws Exception {
        if (EnforcerConfig.exists(wd)) {
            return ToolResult.error("Enforcer config already exists at: "
                    + EnforcerConfig.resolveConfigPath(wd)
                    + "\nUse action='get' to view it or action='set' to modify.");
        }

        EnforcerConfig config = new EnforcerConfig();
        config.setKeywordMode(true);
        config.setSemanticMode("wordnet");
        config.save(wd);

        return ToolResult.success("Created enforcer config with defaults at: "
                + EnforcerConfig.resolveConfigPath(wd)
                + "\nDefaults: keyword_mode=true, semantic_mode=wordnet, max_corrections=2"
                + "\nUse action='add_keyword' to add banned keywords."
                + "\nUse action='set_semantic' to configure semantic matching.");
    }

    private ToolResult executeDelete(Path wd) throws Exception {
        if (EnforcerConfig.delete(wd)) {
            return ToolResult.success("Enforcer config deleted.");
        }
        return ToolResult.error("No enforcer config found to delete.");
    }
}
