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

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Interactive wizard for configuring the tool gateway rules file.
 * Walks the user through creating rules, setting the system prompt,
 * and configuring gateway behavior.
 */
public class ToolGatewayWizard {

    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String CYAN = "\033[36m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String RED = "\033[31m";

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    /**
     * Run the interactive setup wizard.
     * Returns true if config was saved, false if cancelled.
     */
    public static boolean run() {
        Terminal terminal = null;
        try {
            terminal = TerminalBuilder.builder().system(true).build();
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();

            System.out.println();
            System.out.println(BOLD + CYAN + "  ╭──────────────────────────────────────╮" + RESET);
            System.out.println(BOLD + CYAN + "  │     Tool Gateway Configuration       │" + RESET);
            System.out.println(BOLD + CYAN + "  ╰──────────────────────────────────────╯" + RESET);
            System.out.println();

            Path rulesPath = getDefaultRulesPath();
            Map<String, Object> config = loadExistingConfig(rulesPath);

            System.out.println("  Rules file: " + DIM + rulesPath + RESET);
            if (config != null) {
                @SuppressWarnings("unchecked")
                List<Object> rules = (List<Object>) config.getOrDefault("rules", Collections.emptyList());
                System.out.println("  Existing rules: " + BOLD + rules.size() + RESET);
            } else {
                System.out.println("  " + DIM + "No existing rules file found. We'll create one." + RESET);
                config = new LinkedHashMap<>();
                config.put("version", 1);
                config.put("defaultAction", "ALLOW");
                config.put("rules", new ArrayList<>());
            }
            System.out.println();

            // Step 1: Default action
            String defaultAction = selectDefaultAction(reader, (String) config.getOrDefault("defaultAction", "ALLOW"));
            if (defaultAction == null) return false;
            config.put("defaultAction", defaultAction);

            // Step 2: System prompt
            String systemPrompt = promptSystemPrompt(reader, (String) config.get("systemPrompt"));
            if (systemPrompt != null) {
                config.put("systemPrompt", systemPrompt);
            }

            // Step 3: Model configuration
            System.out.println();
            System.out.println(BOLD + "  Model Configuration:" + RESET);
            System.out.println("  " + DIM + "Configure a dedicated LLM endpoint for rule evaluation." + RESET);
            System.out.println("  " + DIM + "Leave blank to use the application's global ChatModel." + RESET);
            System.out.println();

            @SuppressWarnings("unchecked")
            Map<String, Object> modelConfig = (Map<String, Object>) config.getOrDefault("model", new LinkedHashMap<>());
            String currentBaseUrl = (String) modelConfig.getOrDefault("baseUrl", "");
            String currentModelName = (String) modelConfig.getOrDefault("modelName", "");

            String baseUrl = prompt(reader, "  Base URL (e.g., http://localhost:8090 for kompile-serve)" +
                    (currentBaseUrl.isEmpty() ? "" : " [" + currentBaseUrl + "]") + ": ");
            if (baseUrl != null && !baseUrl.trim().isEmpty()) {
                modelConfig.put("baseUrl", baseUrl.trim());
            }

            if (modelConfig.containsKey("baseUrl") && modelConfig.get("baseUrl") != null) {
                String apiKey = prompt(reader, "  API Key (for cloud APIs, or any value for local): ");
                if (apiKey != null && !apiKey.trim().isEmpty()) {
                    modelConfig.put("apiKey", apiKey.trim());
                }

                String modelName = prompt(reader, "  Model name (e.g., gpt-4o-mini, llama3)" +
                        (currentModelName.isEmpty() ? "" : " [" + currentModelName + "]") + ": ");
                if (modelName != null && !modelName.trim().isEmpty()) {
                    modelConfig.put("modelName", modelName.trim());
                }
            }

            if (!modelConfig.isEmpty()) {
                config.put("model", modelConfig);
            }

            // Step 4: Rules loop
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rules = (List<Map<String, Object>>) config.get("rules");
            if (rules == null) {
                rules = new ArrayList<>();
                config.put("rules", rules);
            }

            boolean addingRules = true;
            while (addingRules) {
                System.out.println();
                System.out.println("  Current rules: " + BOLD + rules.size() + RESET);
                if (!rules.isEmpty()) {
                    for (int i = 0; i < rules.size(); i++) {
                        Map<String, Object> r = rules.get(i);
                        System.out.printf("    %s%d%s. [%s] %s — %s%n",
                                CYAN, i + 1, RESET,
                                r.getOrDefault("action", "BLOCK"),
                                r.getOrDefault("id", "unnamed"),
                                r.getOrDefault("description", ""));
                    }
                }
                System.out.println();

                String choice = prompt(reader, "  Add a rule? (y/n, default: n): ");
                if (choice == null || !choice.toLowerCase().startsWith("y")) {
                    addingRules = false;
                } else {
                    Map<String, Object> rule = createRuleInteractively(reader);
                    if (rule != null) {
                        rules.add(rule);
                        System.out.println("  " + GREEN + "Rule added." + RESET);
                    }
                }
            }

            // Step 5: Save
            System.out.println();
            String confirm = prompt(reader, "  Save configuration? (y/n, default: y): ");
            if (confirm != null && confirm.toLowerCase().startsWith("n")) {
                System.out.println("  " + YELLOW + "Cancelled." + RESET);
                return false;
            }

            saveConfig(rulesPath, config);
            System.out.println();
            System.out.println(GREEN + "  Configuration saved to " + rulesPath + RESET);
            System.out.println();
            System.out.println("  To enable the gateway:");
            System.out.println("    " + BOLD + "kompile config app" + RESET + " → section 9 (Tool Gateway)");
            System.out.println("    or from the web UI at " + BOLD + "Settings > Tool Gateway" + RESET);
            System.out.println("    or from the web UI at " + BOLD + "Settings > Feature Flags" + RESET);
            System.out.println();

            return true;

        } catch (Exception e) {
            System.err.println("Wizard error: " + e.getMessage());
            return false;
        } finally {
            if (terminal != null) {
                try { terminal.close(); } catch (Exception ignored) {}
            }
        }
    }

    // ── Interactive helpers ─────────────────────────────────────────────────

    private static String selectDefaultAction(LineReader reader, String current) {
        List<String> actions = List.of(
                "ALLOW — unmatched tool calls pass through (recommended)",
                "BLOCK — unmatched tool calls are blocked"
        );

        System.out.println(BOLD + "  Default action for unmatched tool calls:" + RESET);
        System.out.println("  " + DIM + "(Currently: " + current + ")" + RESET);
        System.out.println();
        for (int i = 0; i < actions.size(); i++) {
            System.out.printf("  " + CYAN + "%2d" + RESET + "  %s%n", i + 1, actions.get(i));
        }
        System.out.println();

        String input = prompt(reader, "  Choice (1-2, default: 1): ");
        if (input == null) return null;
        if (input.trim().isEmpty() || "1".equals(input.trim())) return "ALLOW";
        if ("2".equals(input.trim())) return "BLOCK";
        return "ALLOW";
    }

    private static String promptSystemPrompt(LineReader reader, String current) {
        System.out.println();
        System.out.println(BOLD + "  System prompt for the gateway LLM:" + RESET);
        System.out.println("  " + DIM + "This is prepended to every evaluation. Leave blank to skip." + RESET);
        if (current != null && !current.isBlank()) {
            System.out.println("  " + DIM + "Current: " + current + RESET);
        }
        System.out.println();

        String input = prompt(reader, "  System prompt (or Enter to keep current): ");
        if (input == null || input.trim().isEmpty()) return current;
        return input.trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> createRuleInteractively(LineReader reader) {
        System.out.println();
        System.out.println(BOLD + "  ── New Rule ──" + RESET);

        String id = promptRequired(reader, "  Rule ID (e.g., block-system-writes): ");
        if (id == null) return null;

        String description = prompt(reader, "  Description: ");

        // Tool patterns
        List<String> patterns = new ArrayList<>();
        System.out.println("  " + DIM + "Tool patterns (glob, e.g., write_*, delete_file). Empty = all tools." + RESET);
        while (true) {
            String p = prompt(reader, "  Tool pattern (or Enter to finish): ");
            if (p == null || p.trim().isEmpty()) break;
            patterns.add(p.trim());
        }

        String condition = promptRequired(reader, "  Condition (natural language, evaluated by LLM): ");
        if (condition == null) return null;

        // Action
        System.out.println();
        System.out.printf("  " + CYAN + " 1" + RESET + "  BLOCK%n");
        System.out.printf("  " + CYAN + " 2" + RESET + "  REWRITE%n");
        System.out.printf("  " + CYAN + " 3" + RESET + "  ALLOW%n");
        String actionInput = prompt(reader, "  Action (1-3, default: 1): ");
        String action = "BLOCK";
        if ("2".equals(actionInput != null ? actionInput.trim() : "")) action = "REWRITE";
        else if ("3".equals(actionInput != null ? actionInput.trim() : "")) action = "ALLOW";

        String blockMessage = null;
        String rewriteInstructions = null;
        if ("BLOCK".equals(action)) {
            blockMessage = prompt(reader, "  Block message (shown to caller): ");
        } else if ("REWRITE".equals(action)) {
            rewriteInstructions = prompt(reader, "  Rewrite instructions (tells LLM how to rewrite args): ");
        }

        String priorityStr = prompt(reader, "  Priority (higher = evaluated first, default: 0): ");
        int priority = 0;
        if (priorityStr != null && !priorityStr.trim().isEmpty()) {
            try { priority = Integer.parseInt(priorityStr.trim()); } catch (NumberFormatException ignored) {}
        }

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", id);
        if (description != null && !description.isBlank()) rule.put("description", description);
        if (!patterns.isEmpty()) rule.put("toolPatterns", patterns);
        rule.put("condition", condition);
        rule.put("action", action);
        if (blockMessage != null && !blockMessage.isBlank()) rule.put("blockMessage", blockMessage);
        if (rewriteInstructions != null && !rewriteInstructions.isBlank()) rule.put("rewriteInstructions", rewriteInstructions);
        rule.put("priority", priority);
        rule.put("enabled", true);

        return rule;
    }

    // ── I/O helpers ─────────────────────────────────────────────────────────

    private static String prompt(LineReader reader, String text) {
        try {
            return reader.readLine(text);
        } catch (Exception e) {
            return null;
        }
    }

    private static String promptRequired(LineReader reader, String text) {
        while (true) {
            String input = prompt(reader, text);
            if (input == null) return null;
            if (!input.trim().isEmpty()) return input.trim();
            System.out.println("  " + YELLOW + "This field is required." + RESET);
        }
    }

    private static Path getDefaultRulesPath() {
        return Path.of(System.getProperty("user.home"), ".kompile", "config", "tool-gateway-rules.json");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadExistingConfig(Path path) {
        if (!Files.exists(path)) return null;
        try {
            return MAPPER.readValue(path.toFile(), Map.class);
        } catch (Exception e) {
            System.err.println("  " + RED + "Warning: could not parse existing rules file: " + e.getMessage() + RESET);
            return null;
        }
    }

    private static void saveConfig(Path path, Map<String, Object> config) throws IOException {
        Files.createDirectories(path.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), config);
    }
}
