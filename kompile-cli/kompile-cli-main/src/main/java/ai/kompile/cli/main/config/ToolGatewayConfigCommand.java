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

package ai.kompile.cli.main.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * CLI command for managing tool gateway configuration.
 * <p>
 * Usage:
 * <pre>
 *   kompile config tool-gateway --wizard          # Interactive setup
 *   kompile config tool-gateway --enable           # Enable the gateway
 *   kompile config tool-gateway --disable          # Disable the gateway
 *   kompile config tool-gateway --show             # Show current config
 *   kompile config tool-gateway --add-rule         # Add a rule non-interactively
 *   kompile config tool-gateway --list-rules       # List all rules
 * </pre>
 */
@Command(name = "tool-gateway",
        mixinStandardHelpOptions = true,
        description = "Configure the LLM-based tool gateway (rules, behavior, enable/disable)")
public class ToolGatewayConfigCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Option(names = {"--wizard", "-w"}, description = "Run interactive setup wizard")
    private boolean wizard;

    @Option(names = {"--show", "-s"}, description = "Show current configuration")
    private boolean show;

    @Option(names = {"--enable"}, description = "Print the property to enable the gateway")
    private boolean enable;

    @Option(names = {"--disable"}, description = "Print the property to disable the gateway")
    private boolean disable;

    @Option(names = {"--list-rules"}, description = "List all configured rules")
    private boolean listRules;

    @Option(names = {"--add-rule"}, description = "Add a rule: --add-rule --rule-id=X --condition=Y --action=BLOCK")
    private boolean addRule;

    @Option(names = {"--remove-rule"}, description = "Remove a rule by ID")
    private String removeRuleId;

    // Fields for --add-rule
    @Option(names = {"--rule-id"}, description = "Rule ID (used with --add-rule)")
    private String ruleId;

    @Option(names = {"--condition"}, description = "Rule condition (used with --add-rule)")
    private String condition;

    @Option(names = {"--action"}, description = "Rule action: ALLOW, REWRITE, BLOCK (used with --add-rule)")
    private String action = "BLOCK";

    @Option(names = {"--description"}, description = "Rule description (used with --add-rule)")
    private String description;

    @Option(names = {"--tool-patterns"}, description = "Comma-separated tool patterns (used with --add-rule)")
    private String toolPatterns;

    @Option(names = {"--priority"}, description = "Rule priority (used with --add-rule)")
    private int priority = 0;

    @Option(names = {"--block-message"}, description = "Block message (used with --add-rule --action=BLOCK)")
    private String blockMessage;

    @Option(names = {"--rewrite-instructions"}, description = "Rewrite instructions (used with --add-rule --action=REWRITE)")
    private String rewriteInstructions;

    @Option(names = {"--rules-file"}, description = "Path to rules file (default: ~/.kompile/config/tool-gateway-rules.json)")
    private String rulesFile;

    // Model configuration flags
    @Option(names = {"--model-base-url"}, description = "OpenAI-compatible API base URL (e.g., http://localhost:8090 for kompile-serve)")
    private String modelBaseUrl;

    @Option(names = {"--model-api-key"}, description = "API key for the model endpoint")
    private String modelApiKey;

    @Option(names = {"--model-name"}, description = "Model name to use (e.g., gpt-4o-mini, llama3)")
    private String modelName;

    @Override
    public Integer call() throws Exception {
        Path rulesPath = rulesFile != null
                ? Path.of(rulesFile)
                : Path.of(System.getProperty("user.home"), ".kompile", "config", "tool-gateway-rules.json");

        if (wizard) {
            return ToolGatewayWizard.run() ? 0 : 1;
        }

        if (enable) {
            System.out.println("Add to application.properties:");
            System.out.println("  kompile.tool-gateway.enabled=true");
            return 0;
        }

        if (disable) {
            System.out.println("Add to application.properties:");
            System.out.println("  kompile.tool-gateway.enabled=false");
            return 0;
        }

        if (show) {
            return showConfig(rulesPath);
        }

        if (listRules) {
            return listRules(rulesPath);
        }

        if (addRule) {
            return addRule(rulesPath);
        }

        if (removeRuleId != null) {
            return removeRule(rulesPath);
        }

        if (modelBaseUrl != null || modelApiKey != null || modelName != null) {
            return setModelConfig(rulesPath);
        }

        // No flags — show help
        System.out.println("Tool gateway configuration commands. Use --help for options, or --wizard for interactive setup.");
        return 0;
    }

    @SuppressWarnings("unchecked")
    private int showConfig(Path rulesPath) throws Exception {
        if (!Files.exists(rulesPath)) {
            System.out.println("No rules file at: " + rulesPath);
            System.out.println("Run 'kompile config tool-gateway --wizard' to create one.");
            return 0;
        }

        Map<String, Object> config = MAPPER.readValue(rulesPath.toFile(), Map.class);
        System.out.println("Rules file: " + rulesPath);
        System.out.println("Version:    " + config.getOrDefault("version", 1));
        System.out.println("Default:    " + config.getOrDefault("defaultAction", "ALLOW"));
        String sp = (String) config.get("systemPrompt");
        if (sp != null && !sp.isBlank()) {
            System.out.println("Sys prompt: " + sp);
        }

        // Model configuration
        Map<String, Object> modelConfig = (Map<String, Object>) config.get("model");
        if (modelConfig != null && !modelConfig.isEmpty()) {
            System.out.println("Model:");
            System.out.println("  Base URL:  " + modelConfig.getOrDefault("baseUrl", "(not set)"));
            System.out.println("  API Key:   " + (modelConfig.containsKey("apiKey") ? "(set)" : "(not set)"));
            System.out.println("  Model:     " + modelConfig.getOrDefault("modelName", "(not set)"));
        } else {
            System.out.println("Model:      (using global ChatModel)");
        }

        List<Object> rules = (List<Object>) config.getOrDefault("rules", Collections.emptyList());
        System.out.println("Rules:      " + rules.size());
        return 0;
    }

    @SuppressWarnings("unchecked")
    private int setModelConfig(Path rulesPath) throws Exception {
        Map<String, Object> config = loadOrCreateConfig(rulesPath);
        Map<String, Object> modelConfig = (Map<String, Object>) config.computeIfAbsent("model", k -> new LinkedHashMap<>());

        if (modelBaseUrl != null) modelConfig.put("baseUrl", modelBaseUrl);
        if (modelApiKey != null) modelConfig.put("apiKey", modelApiKey);
        if (modelName != null) modelConfig.put("modelName", modelName);

        saveConfig(rulesPath, config);
        System.out.println("Model configuration updated.");
        System.out.println("  Base URL: " + modelConfig.getOrDefault("baseUrl", "(not set)"));
        System.out.println("  API Key:  " + (modelConfig.containsKey("apiKey") ? "(set)" : "(not set)"));
        System.out.println("  Model:    " + modelConfig.getOrDefault("modelName", "(not set)"));
        System.out.println();
        System.out.println("Set in application.properties to use:");
        System.out.println("  kompile.tool-gateway.model.base-url=" + modelConfig.getOrDefault("baseUrl", ""));
        if (modelConfig.containsKey("modelName")) {
            System.out.println("  kompile.tool-gateway.model.model-name=" + modelConfig.get("modelName"));
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private int listRules(Path rulesPath) throws Exception {
        if (!Files.exists(rulesPath)) {
            System.out.println("No rules file at: " + rulesPath);
            return 0;
        }

        Map<String, Object> config = MAPPER.readValue(rulesPath.toFile(), Map.class);
        List<Map<String, Object>> rules = (List<Map<String, Object>>) config.getOrDefault("rules", Collections.emptyList());

        if (rules.isEmpty()) {
            System.out.println("No rules configured.");
            return 0;
        }

        System.out.printf("%-30s %-10s %-8s %-8s %s%n", "ID", "ACTION", "PRI", "ENABLED", "CONDITION");
        System.out.println("-".repeat(100));
        for (Map<String, Object> r : rules) {
            System.out.printf("%-30s %-10s %-8s %-8s %s%n",
                    r.getOrDefault("id", ""),
                    r.getOrDefault("action", "BLOCK"),
                    r.getOrDefault("priority", 0),
                    r.getOrDefault("enabled", true),
                    truncate((String) r.getOrDefault("condition", ""), 40));
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private int addRule(Path rulesPath) throws Exception {
        if (ruleId == null || ruleId.isBlank()) {
            System.err.println("--rule-id is required with --add-rule");
            return 1;
        }
        if (condition == null || condition.isBlank()) {
            System.err.println("--condition is required with --add-rule");
            return 1;
        }

        Map<String, Object> config = loadOrCreateConfig(rulesPath);
        List<Map<String, Object>> rules = (List<Map<String, Object>>) config.computeIfAbsent("rules", k -> new ArrayList<>());

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", ruleId);
        if (description != null) rule.put("description", description);
        if (toolPatterns != null && !toolPatterns.isBlank()) {
            rule.put("toolPatterns", Arrays.asList(toolPatterns.split(",")));
        }
        rule.put("condition", condition);
        rule.put("action", action.toUpperCase());
        if (blockMessage != null) rule.put("blockMessage", blockMessage);
        if (rewriteInstructions != null) rule.put("rewriteInstructions", rewriteInstructions);
        rule.put("priority", priority);
        rule.put("enabled", true);

        rules.add(rule);
        saveConfig(rulesPath, config);
        System.out.println("Rule '" + ruleId + "' added. Total rules: " + rules.size());
        return 0;
    }

    @SuppressWarnings("unchecked")
    private int removeRule(Path rulesPath) throws Exception {
        if (!Files.exists(rulesPath)) {
            System.err.println("No rules file at: " + rulesPath);
            return 1;
        }

        Map<String, Object> config = MAPPER.readValue(rulesPath.toFile(), Map.class);
        List<Map<String, Object>> rules = (List<Map<String, Object>>) config.getOrDefault("rules", new ArrayList<>());

        boolean removed = rules.removeIf(r -> removeRuleId.equals(r.get("id")));
        if (removed) {
            saveConfig(rulesPath, config);
            System.out.println("Rule '" + removeRuleId + "' removed.");
        } else {
            System.out.println("Rule '" + removeRuleId + "' not found.");
        }
        return removed ? 0 : 1;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadOrCreateConfig(Path path) throws Exception {
        if (Files.exists(path)) {
            return MAPPER.readValue(path.toFile(), Map.class);
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("version", 1);
        config.put("defaultAction", "ALLOW");
        config.put("rules", new ArrayList<>());
        return config;
    }

    private void saveConfig(Path path, Map<String, Object> config) throws Exception {
        Files.createDirectories(path.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), config);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
