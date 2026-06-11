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

import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Interactive setup wizard for enforcer configuration per project.
 * Walks the user through configuring:
 * <ul>
 *   <li>Agent selection</li>
 *   <li>Evaluation mode (keyword vs LLM judge)</li>
 *   <li>Rules (inline, file, banned tools/commands/patterns)</li>
 *   <li>Diff archiving and pattern checking</li>
 *   <li>Judge backend settings (when LLM mode selected)</li>
 * </ul>
 *
 * <p>Config is saved to {@code .kompile/enforcer-config.json} in the project directory.</p>
 */
public class EnforcerSetupWizard {

    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String CYAN = "\033[36m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";

    /**
     * Run the interactive enforcer setup wizard.
     * Returns the created config or null if cancelled.
     */
    public static EnforcerConfig run(Path workingDir) {
        Terminal terminal = null;
        try {
            terminal = TerminalBuilder.builder().system(true).build();
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            return runWithReader(reader, workingDir);
        } catch (Exception e) {
            System.err.println("Enforcer setup error: " + e.getMessage());
            return null;
        } finally {
            if (terminal != null) {
                try { terminal.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Run the wizard with a provided reader (for testing or embedding in another terminal session).
     */
    public static EnforcerConfig runWithReader(LineReader reader, Path workingDir) {
        EnforcerConfig config = new EnforcerConfig();

        System.out.println();
        System.out.println(BOLD + CYAN + "  ╭──────────────────────────────────────╮" + RESET);
        System.out.println(BOLD + CYAN + "  │     Kompile Enforcer Setup           │" + RESET);
        System.out.println(BOLD + CYAN + "  ╰──────────────────────────────────────╯" + RESET);
        System.out.println();
        System.out.println("  " + DIM + "Project: " + workingDir + RESET);
        System.out.println("  " + DIM + "Config:  .kompile/enforcer-config.json" + RESET);
        System.out.println();

        // ── Step 1: Agent selection ────────────────────────────────────
        String agent = selectAgent(reader);
        if (agent == null) return null;
        config.setAgent(agent);

        // ── Step 2: Evaluation mode ────────────────────────────────────
        boolean keyword = selectEvaluationMode(reader);
        config.setKeywordMode(keyword);

        // ── Step 3: Rules ──────────────────────────────────────────────
        if (!configureRules(reader, config, workingDir)) return null;

        // ── Step 4: Banned tools & commands ────────────────────────────
        configureBans(reader, config);

        // ── Step 5: Diff patterns ──────────────────────────────────────
        configureDiffPatterns(reader, config, workingDir);

        // ── Step 6: Diff archiving ─────────────────────────────────────
        configureArchiving(reader, config);

        // ── Step 7: Judge settings (if LLM mode) ───────────────────────
        if (!keyword) {
            configureJudge(reader, config);
        }

        // ── Step 8: Semantic matching ──────────────────────────────────
        configureSemanticMatching(reader, config);

        // ── Step 9: Max corrections ────────────────────────────────────
        configureCorrections(reader, config);

        // ── Save ───────────────────────────────────────────────────────
        try {
            config.save(workingDir);
            System.out.println();
            System.out.println(GREEN + "  Enforcer config saved!" + RESET);
            System.out.println();
            printSummary(config, workingDir);
            System.out.println();
            System.out.println("  " + DIM + "Run with: kompile enforcer" + RESET);
            System.out.println("  " + DIM + "Reconfigure: kompile enforcer init" + RESET);
            System.out.println();
            return config;
        } catch (IOException e) {
            System.err.println("  Failed to save config: " + e.getMessage());
            return null;
        }
    }

    // ── Step implementations ───────────────────────────────────────────────

    private static String selectAgent(LineReader reader) {
        List<String> agents = new ArrayList<>();
        List<String> keys = new ArrayList<>();

        // Check which agents exist on PATH
        String[][] candidates = {
                {"claude", "Claude Code (Anthropic)"},
                {"codex", "Codex CLI (OpenAI)"},
                {"gemini", "Gemini CLI (Google)"},
                {"qwen", "Qwen CLI"},
                {"opencode", "OpenCode"},
                {"aider", "Aider"}
        };

        for (String[] entry : candidates) {
            if (agentExists(entry[0])) {
                agents.add(entry[1] + DIM + " (" + entry[0] + ")" + RESET);
                keys.add(entry[0]);
            }
        }

        if (agents.isEmpty()) {
            System.out.println(YELLOW + "  No CLI agents detected on PATH." + RESET);
            String custom = promptText(reader, "  Agent binary name: ");
            return custom != null && !custom.isBlank() ? custom.trim() : null;
        }

        int selected = selectNumbered(reader, "Select subordinate agent:", agents);
        if (selected < 0) return null;

        System.out.println("  " + DIM + "→ " + keys.get(selected) + RESET);
        System.out.println();
        return keys.get(selected);
    }

    private static boolean selectEvaluationMode(LineReader reader) {
        List<String> modes = List.of(
                "Keyword mode — instant pattern matching, no LLM needed (recommended for most cases)",
                "LLM judge — full LLM evaluation of agent output (requires API key)"
        );

        int selected = selectNumbered(reader, "Select evaluation mode:", modes);
        if (selected < 0) return true; // default to keyword

        System.out.println();
        return selected == 0;
    }

    private static boolean configureRules(LineReader reader, EnforcerConfig config, Path workingDir) {
        List<String> ruleOptions = List.of(
                "Enter rules interactively now",
                "Point to an existing rule file",
                "Create a new rule file (opens template)",
                "Skip — will configure rules later"
        );

        int selected = selectNumbered(reader, "How do you want to define enforcer rules?", ruleOptions);
        if (selected < 0) return false;

        switch (selected) {
            case 0 -> {
                // Interactive inline rules
                System.out.println();
                System.out.println("  " + DIM + "Enter rules (one per line). Use BAN:, STOP:, BAN_TOOL:, BAN_CMD:, BAN_DIFF: prefixes." + RESET);
                System.out.println("  " + DIM + "Finish with a single '.' line." + RESET);
                System.out.println();
                StringBuilder sb = new StringBuilder();
                while (true) {
                    String line = promptText(reader, "  rule> ");
                    if (line == null || ".".equals(line.trim())) break;
                    sb.append(line).append("\n");
                }
                if (!sb.isEmpty()) {
                    config.setInlineRules(sb.toString().trim());
                }
            }
            case 1 -> {
                // Existing rule file
                String path = promptText(reader, "  Rule file path (relative to project): ");
                if (path != null && !path.isBlank()) {
                    Path resolved = workingDir.resolve(path.trim());
                    if (!Files.exists(resolved)) {
                        System.out.println(YELLOW + "  Warning: file does not exist yet: " + path + RESET);
                    }
                    config.setRuleFile(path.trim());
                }
            }
            case 2 -> {
                // Create template
                String filename = promptText(reader, "  Rule file name [.kompile/enforcer-rules.txt]: ");
                if (filename == null || filename.isBlank()) {
                    filename = ".kompile/enforcer-rules.txt";
                }
                Path rulePath = workingDir.resolve(filename.trim());
                try {
                    Files.createDirectories(rulePath.getParent());
                    if (!Files.exists(rulePath)) {
                        Files.writeString(rulePath, RULE_TEMPLATE, StandardCharsets.UTF_8);
                        System.out.println(GREEN + "  Created: " + filename + RESET);
                    } else {
                        System.out.println(DIM + "  File already exists: " + filename + RESET);
                    }
                    config.setRuleFile(filename.trim());
                } catch (IOException e) {
                    System.out.println(YELLOW + "  Could not create file: " + e.getMessage() + RESET);
                }
            }
            case 3 -> {
                // Skip
                System.out.println(DIM + "  Rules can be added later to .kompile/enforcer-config.json" + RESET);
            }
        }
        System.out.println();
        return true;
    }

    private static void configureBans(LineReader reader, EnforcerConfig config) {
        System.out.println(BOLD + "  Tool & command restrictions" + RESET);
        System.out.println("  " + DIM + "(Enter comma-separated values, or leave empty to skip)" + RESET);
        System.out.println();

        // Banned tools
        String tools = promptText(reader, "  Banned tools (e.g. bash,write): ");
        if (tools != null && !tools.isBlank()) {
            List<String> banned = new ArrayList<>();
            for (String t : tools.split(",")) {
                if (!t.trim().isEmpty()) banned.add(t.trim());
            }
            config.setBannedTools(banned);
        }

        // Banned commands
        String cmds = promptText(reader, "  Banned commands (e.g. rm -rf,git push --force): ");
        if (cmds != null && !cmds.isBlank()) {
            List<String> banned = new ArrayList<>();
            for (String c : cmds.split(",")) {
                if (!c.trim().isEmpty()) banned.add(c.trim());
            }
            config.setBannedCommands(banned);
        }

        // Banned keywords in output
        String keywords = promptText(reader, "  Banned output keywords (e.g. TODO,FIXME): ");
        if (keywords != null && !keywords.isBlank()) {
            List<String> banned = new ArrayList<>();
            for (String k : keywords.split(",")) {
                if (!k.trim().isEmpty()) banned.add(k.trim());
            }
            config.setBannedKeywords(banned);
        }

        System.out.println();
    }

    private static void configureDiffPatterns(LineReader reader, EnforcerConfig config, Path workingDir) {
        String useDiff = promptText(reader, "  Enable diff pattern checking (banned code patterns)? [Y/n]: ");
        if (useDiff != null && useDiff.trim().toLowerCase().startsWith("n")) {
            System.out.println();
            return;
        }

        // Language
        String lang = promptText(reader, "  Primary language [" + config.getPrimaryLanguage() + "]: ");
        if (lang != null && !lang.isBlank()) {
            config.setPrimaryLanguage(lang.trim());
        }

        // Source of patterns
        List<String> options = List.of(
                "Enter patterns now (BAN_DIFF: format)",
                "Point to existing patterns file (JSON or line format)",
                "Bootstrap from natural language (uses LLM once to generate patterns)",
                "Skip — will add patterns later"
        );

        int selected = selectNumbered(reader, "Diff pattern source:", options);

        switch (selected) {
            case 0 -> {
                System.out.println();
                System.out.println("  " + DIM + "Enter BAN_DIFF: patterns (one per line). Finish with '.'." + RESET);
                List<String> patterns = new ArrayList<>();
                while (true) {
                    String line = promptText(reader, "  pattern> ");
                    if (line == null || ".".equals(line.trim())) break;
                    if (!line.isBlank()) patterns.add(line.trim());
                }
                config.setDiffPatternRules(patterns);
            }
            case 1 -> {
                String path = promptText(reader, "  Patterns file path: ");
                if (path != null && !path.isBlank()) {
                    config.setDiffPatternsFile(path.trim());
                }
            }
            case 2 -> {
                System.out.println();
                System.out.println("  " + DIM + "Describe what code patterns to ban (natural language)." + RESET);
                System.out.println("  " + DIM + "An LLM will generate regex/keyword patterns from your description." + RESET);
                System.out.println("  " + DIM + "Run: kompile enforcer --bootstrap-patterns=\"<your description>\"" + RESET);
                System.out.println();
                String desc = promptText(reader, "  Save bootstrap description for later? [y/N]: ");
                if (desc != null && desc.trim().toLowerCase().startsWith("y")) {
                    String description = promptText(reader, "  Description: ");
                    if (description != null && !description.isBlank()) {
                        // Save as a note in the config for the user to run bootstrap
                        System.out.println(DIM + "  Run: kompile enforcer --bootstrap-patterns=\""
                                + description.trim() + "\" --bootstrap-language="
                                + config.getPrimaryLanguage() + RESET);
                    }
                }
            }
            case 3 -> {
                // Skip
            }
        }
        System.out.println();
    }

    private static void configureArchiving(LineReader reader, EnforcerConfig config) {
        String archive = promptText(reader, "  Enable diff archiving (rollback on violation)? [Y/n]: ");
        if (archive != null && archive.trim().toLowerCase().startsWith("n")) {
            config.setArchiveDiffs(false);
        } else {
            config.setArchiveDiffs(true);

            String autoRollback = promptText(reader, "  Auto-rollback on violation? [Y/n]: ");
            if (autoRollback != null && autoRollback.trim().toLowerCase().startsWith("n")) {
                config.setAutoRollbackOnViolation(false);
            }

            String retention = promptText(reader, "  Archive retention hours [168 = 7 days]: ");
            if (retention != null && !retention.isBlank()) {
                try {
                    config.setArchiveRetentionHours(Integer.parseInt(retention.trim()));
                } catch (NumberFormatException ignored) {}
            }
        }
        System.out.println();
    }

    private static void configureJudge(LineReader reader, EnforcerConfig config) {
        System.out.println(BOLD + "  LLM Judge Configuration" + RESET);
        System.out.println();

        List<String> providers = List.of(
                "Anthropic (Claude)",
                "OpenAI",
                "Google Gemini",
                "Ollama (local)",
                "Use harness-config.json defaults"
        );
        String[] providerKeys = {"anthropic", "openai", "gemini", "ollama", null};

        int selected = selectNumbered(reader, "Judge provider:", providers);
        if (selected < 0 || selected == 4) {
            // Use defaults
            System.out.println();
            return;
        }

        config.setJudgeProvider(providerKeys[selected]);

        if (!"ollama".equals(providerKeys[selected])) {
            String apiKey = promptText(reader, "  Judge API key (or Enter to use env variable): ");
            if (apiKey != null && !apiKey.isBlank()) {
                config.setJudgeApiKey(apiKey.trim());
            }
        }

        String model = promptText(reader, "  Judge model (or Enter for default): ");
        if (model != null && !model.isBlank()) {
            config.setJudgeModel(model.trim());
        }

        System.out.println();
    }

    private static void configureCorrections(LineReader reader, EnforcerConfig config) {
        String maxStr = promptText(reader, "  Max correction attempts [2]: ");
        if (maxStr != null && !maxStr.isBlank()) {
            try {
                int val = Integer.parseInt(maxStr.trim());
                if (val >= 0 && val <= 8) {
                    config.setMaxCorrections(val);
                }
            } catch (NumberFormatException ignored) {}
        }
        System.out.println();
    }

    private static void configureSemanticMatching(LineReader reader, EnforcerConfig config) {
        System.out.println(BOLD + "  Semantic Matching" + RESET);
        System.out.println("  " + DIM + "Catches reworded equivalents of banned phrases (e.g., \"already existing\" ≈ \"pre-existing\")" + RESET);
        System.out.println();

        List<String> modes = List.of(
                "None — literal keyword matching only (fastest)",
                "WordNet — synonym expansion, catches common rephrasings (recommended)",
                "Embedding — cosine similarity via kompile embedding endpoint (most thorough)",
                "Both — WordNet + Embedding combined (catches the most, requires embedding endpoint)"
        );

        int selected = selectNumbered(reader, "Semantic matching mode:", modes);
        if (selected < 0) {
            System.out.println();
            return;
        }

        String[] modeKeys = {"none", "wordnet", "embedding", "both"};
        config.setSemanticMode(modeKeys[selected]);

        if (selected == 2 || selected == 3) {
            // Embedding mode — need endpoint URL
            String url = promptText(reader, "  Embedding endpoint URL [http://localhost:8080/api/embed]: ");
            if (url == null || url.isBlank()) {
                url = "http://localhost:8080/api/embed";
            }
            config.setEmbeddingUrl(url.trim());

            String threshold = promptText(reader, "  Similarity threshold (0.0-1.0) [0.78]: ");
            if (threshold != null && !threshold.isBlank()) {
                try {
                    double val = Double.parseDouble(threshold.trim());
                    if (val > 0 && val <= 1.0) {
                        config.setSemanticThreshold(val);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        if (selected == 1 || selected == 3) {
            // WordNet — optionally point to custom dictionary
            String dict = promptText(reader, "  Custom synonym dictionary path (or Enter for built-in): ");
            if (dict != null && !dict.isBlank()) {
                config.setSynonymDictionaryPath(dict.trim());
            }
        }

        System.out.println();
    }

    // ── Utility methods ────────────────────────────────────────────────────

    private static int selectNumbered(LineReader reader, String title, List<String> items) {
        System.out.println(BOLD + "  " + title + RESET);
        System.out.println();
        for (int i = 0; i < items.size(); i++) {
            System.out.printf("  " + CYAN + "%2d" + RESET + "  %s%n", i + 1, items.get(i));
        }
        System.out.println();

        while (true) {
            String input;
            try {
                input = reader.readLine("  Choice (1-" + items.size() + "): ");
            } catch (Exception e) {
                return -1;
            }
            if (input == null) return -1;
            String trimmed = input.trim();
            if (trimmed.equalsIgnoreCase("q") || trimmed.equalsIgnoreCase("quit")) return -1;

            try {
                int n = Integer.parseInt(trimmed);
                if (n >= 1 && n <= items.size()) return n - 1;
            } catch (NumberFormatException ignored) {}

            // Partial name match
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).toLowerCase().contains(trimmed.toLowerCase())) return i;
            }

            System.out.println("  " + YELLOW + "Enter 1-" + items.size() + RESET);
        }
    }

    private static String promptText(LineReader reader, String prompt) {
        try {
            String input = reader.readLine(prompt);
            return input;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean agentExists(String name) {
        return SubprocessAgentRunner.resolveAgentBinary(name) != null;
    }

    private static void printSummary(EnforcerConfig config, Path workingDir) {
        System.out.println("  " + BOLD + "Configuration Summary" + RESET);
        System.out.println("  ─────────────────────────────");
        System.out.println("  Agent:         " + config.getAgent());
        System.out.println("  Mode:          " + (config.isKeywordMode() ? "keyword (no LLM)" : "LLM judge"));
        System.out.println("  Max retries:   " + config.getMaxCorrections());
        System.out.println("  Diff archive:  " + (config.isArchiveDiffs() ? "enabled" : "disabled"));
        if (config.getRuleFile() != null) {
            System.out.println("  Rule file:     " + config.getRuleFile());
        }
        if (!config.getBannedTools().isEmpty()) {
            System.out.println("  Banned tools:  " + String.join(", ", config.getBannedTools()));
        }
        if (!config.getBannedCommands().isEmpty()) {
            System.out.println("  Banned cmds:   " + String.join(", ", config.getBannedCommands()));
        }
        if (!config.getDiffPatternRules().isEmpty()) {
            System.out.println("  Diff patterns: " + config.getDiffPatternRules().size() + " rules");
        }
        if (config.getDiffPatternsFile() != null) {
            System.out.println("  Patterns file: " + config.getDiffPatternsFile());
        }
        if (!"none".equals(config.getSemanticMode())) {
            System.out.println("  Semantic:      " + config.getSemanticMode()
                    + " (threshold=" + config.getSemanticThreshold() + ")");
        }
        System.out.println("  Config:        " + EnforcerConfig.resolveConfigPath(workingDir));
    }

    // ── Rule file template ─────────────────────────────────────────────────

    private static final String RULE_TEMPLATE = """
            # Kompile Enforcer Rules
            # ─────────────────────
            # Each line defines a rule. Use these prefixes:
            #
            # Output text bans:
            #   BAN: keyword         - blocks output containing this text
            #   STOP: keyword        - critical ban (halts immediately)
            #   REGEX: pattern       - blocks output matching regex
            #   STOP_REGEX: pattern  - critical regex ban
            #
            # Tool bans:
            #   BAN_TOOL: toolname   - blocks a tool entirely (e.g. bash, write)
            #   STOP_TOOL: toolname  - critical tool ban
            #   BAN_CMD: command     - blocks commands containing this text
            #   BAN_CMD_REGEX: pat   - blocks commands matching regex
            #   STOP_CMD: command    - critical command ban
            #
            # Code pattern bans (checked against diffs):
            #   BAN_DIFF: pattern          - blocks added lines containing this text
            #   BAN_DIFF_REGEX: pattern    - blocks added lines matching regex
            #   STOP_DIFF: pattern         - critical diff pattern ban
            #   STOP_DIFF_REGEX: pattern   - critical diff regex ban
            #
            # Lines starting with # or // are comments.
            # Lines without a prefix are treated as BAN: (matches output + tool args).

            # ── Example rules (uncomment or replace) ──

            # BAN_TOOL: bash
            # BAN_CMD: rm -rf
            # BAN_CMD: git push --force
            # BAN_DIFF: System.exit(
            # BAN_DIFF: TODO
            # BAN_DIFF_REGEX: password\\s*=\\s*"[^"]+"
            # STOP: DROP TABLE
            """;
}
