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

import picocli.CommandLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Subcommand: {@code kompile enforcer init}
 * Runs the interactive enforcer setup wizard to create per-project configuration,
 * or accepts CLI flags for non-interactive configuration.
 *
 * <p>Non-interactive example:
 * <pre>
 *   kompile enforcer init --keyword-mode \
 *     --ban-keyword "pre-existing" --ban-keyword "preexisting" \
 *     --inline-rules "STOP: pre-existing\nSTOP: preexisting" \
 *     --max-corrections 3
 * </pre>
 */
@CommandLine.Command(
        name = "init",
        description = "Initialize enforcer configuration for this project (interactive wizard or CLI flags)",
        mixinStandardHelpOptions = true
)
public class EnforcerInitCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--working-dir", "-d"},
            description = "Project directory to configure", defaultValue = ".")
    String workingDir;

    @CommandLine.Option(names = {"--force", "-f"},
            description = "Overwrite existing config", defaultValue = "false")
    boolean force;

    @CommandLine.Option(names = {"--show"},
            description = "Show current enforcer config and exit", defaultValue = "false")
    boolean show;

    @CommandLine.Option(names = {"--delete"},
            description = "Delete enforcer config for this project", defaultValue = "false")
    boolean delete;

    // ── Non-interactive config flags ────────────────────────────────────────

    @CommandLine.Option(names = {"--keyword-mode"},
            description = "Use keyword-based evaluator (no LLM judge needed)")
    Boolean keywordMode;

    @CommandLine.Option(names = {"--agent"},
            description = "Subordinate agent to enforce (default: claude)")
    String agent;

    @CommandLine.Option(names = {"--max-corrections"},
            description = "Maximum correction attempts after a violation")
    Integer maxCorrections;

    @CommandLine.Option(names = {"--ban-keyword"},
            description = "Add a banned keyword (repeatable)")
    List<String> banKeywords;

    @CommandLine.Option(names = {"--ban-tool"},
            description = "Add a banned tool (repeatable)")
    List<String> banTools;

    @CommandLine.Option(names = {"--ban-command"},
            description = "Add a banned command (repeatable)")
    List<String> banCommands;

    @CommandLine.Option(names = {"--inline-rules"},
            description = "Inline enforcer rules text (BAN:/STOP: format, use \\n for newlines)")
    String inlineRules;

    @CommandLine.Option(names = {"--rule-file"},
            description = "Path to a rule file (relative to working dir)")
    String ruleFile;

    @CommandLine.Option(names = {"--diff-pattern"},
            description = "Add a diff pattern rule (repeatable)")
    List<String> diffPatterns;

    @CommandLine.Option(names = {"--language"},
            description = "Primary language for the project (default: java)")
    String primaryLanguage;

    @CommandLine.Option(names = {"--archive-diffs"},
            description = "Enable diff archiving for rollback")
    Boolean archiveDiffs;

    @CommandLine.Option(names = {"--auto-rollback"},
            description = "Auto-rollback on violation")
    Boolean autoRollback;

    @CommandLine.Option(names = {"--judge-provider"},
            description = "Judge LLM provider (anthropic, openai, etc.)")
    String judgeProvider;

    @CommandLine.Option(names = {"--judge-model"},
            description = "Judge LLM model")
    String judgeModel;

    // ── Semantic matching flags ────────────────────────────────────────────

    @CommandLine.Option(names = {"--semantic-mode"},
            description = "Semantic matching mode: none, wordnet, embedding, both")
    String semanticMode;

    @CommandLine.Option(names = {"--semantic-threshold"},
            description = "Similarity threshold for embedding matching (0.0-1.0, default: 0.78)")
    Double semanticThreshold;

    @CommandLine.Option(names = {"--embedding-url"},
            description = "URL for embedding endpoint (e.g., http://localhost:8080/api/embed)")
    String embeddingUrl;

    @CommandLine.Option(names = {"--synonym-dictionary"},
            description = "Path to custom WordNet synonym dictionary JSON file")
    String synonymDictionaryPath;

    @Override
    public Integer call() {
        Path wd = Path.of(workingDir).toAbsolutePath().normalize();

        // Show existing config
        if (show) {
            return showConfig(wd);
        }

        // Delete config
        if (delete) {
            return deleteConfig(wd);
        }

        // Check for existing config
        if (!force && EnforcerConfig.exists(wd)) {
            // If CLI flags are provided, merge into existing config
            if (hasNonInteractiveFlags()) {
                return mergeConfig(wd);
            }
            System.out.println();
            System.out.println("  Enforcer config already exists: " + EnforcerConfig.resolveConfigPath(wd));
            System.out.println("  Use --force to overwrite, or --show to view current config.");
            System.out.println();
            return 0;
        }

        // If CLI flags are provided, create config non-interactively
        if (hasNonInteractiveFlags()) {
            return createFromFlags(wd);
        }

        // Run interactive wizard
        EnforcerConfig config = EnforcerSetupWizard.run(wd);
        return config != null ? 0 : 1;
    }

    private boolean hasNonInteractiveFlags() {
        return keywordMode != null || agent != null || maxCorrections != null
                || (banKeywords != null && !banKeywords.isEmpty())
                || (banTools != null && !banTools.isEmpty())
                || (banCommands != null && !banCommands.isEmpty())
                || inlineRules != null || ruleFile != null
                || (diffPatterns != null && !diffPatterns.isEmpty())
                || primaryLanguage != null || archiveDiffs != null
                || autoRollback != null || judgeProvider != null || judgeModel != null
                || semanticMode != null || semanticThreshold != null
                || embeddingUrl != null || synonymDictionaryPath != null;
    }

    private int createFromFlags(Path wd) {
        EnforcerConfig config = new EnforcerConfig();
        applyFlags(config);

        try {
            config.save(wd);
            System.out.println();
            System.out.println("  Enforcer config created: " + EnforcerConfig.resolveConfigPath(wd));
            printSummary(config);
            return 0;
        } catch (Exception e) {
            System.err.println("  Failed to save config: " + e.getMessage());
            return 1;
        }
    }

    private int mergeConfig(Path wd) {
        EnforcerConfig config = EnforcerConfig.load(wd);
        if (config == null) {
            config = new EnforcerConfig();
        }
        applyFlags(config);

        try {
            config.save(wd);
            System.out.println();
            System.out.println("  Enforcer config updated: " + EnforcerConfig.resolveConfigPath(wd));
            printSummary(config);
            return 0;
        } catch (Exception e) {
            System.err.println("  Failed to save config: " + e.getMessage());
            return 1;
        }
    }

    private void applyFlags(EnforcerConfig config) {
        if (keywordMode != null) config.setKeywordMode(keywordMode);
        if (agent != null) config.setAgent(agent);
        if (maxCorrections != null) config.setMaxCorrections(maxCorrections);
        if (primaryLanguage != null) config.setPrimaryLanguage(primaryLanguage);
        if (archiveDiffs != null) config.setArchiveDiffs(archiveDiffs);
        if (autoRollback != null) config.setAutoRollbackOnViolation(autoRollback);
        if (judgeProvider != null) config.setJudgeProvider(judgeProvider);
        if (judgeModel != null) config.setJudgeModel(judgeModel);
        if (ruleFile != null) config.setRuleFile(ruleFile);

        if (inlineRules != null) {
            // Support \n in inline rules for multi-line
            String expanded = inlineRules.replace("\\n", "\n");
            config.setInlineRules(expanded);
        }

        // Merge lists (add to existing, don't replace)
        if (banKeywords != null && !banKeywords.isEmpty()) {
            List<String> existing = new ArrayList<>(config.getBannedKeywords());
            for (String kw : banKeywords) {
                if (!existing.contains(kw)) existing.add(kw);
            }
            config.setBannedKeywords(existing);
        }
        if (banTools != null && !banTools.isEmpty()) {
            List<String> existing = new ArrayList<>(config.getBannedTools());
            for (String t : banTools) {
                if (!existing.contains(t)) existing.add(t);
            }
            config.setBannedTools(existing);
        }
        if (banCommands != null && !banCommands.isEmpty()) {
            List<String> existing = new ArrayList<>(config.getBannedCommands());
            for (String c : banCommands) {
                if (!existing.contains(c)) existing.add(c);
            }
            config.setBannedCommands(existing);
        }
        if (diffPatterns != null && !diffPatterns.isEmpty()) {
            List<String> existing = new ArrayList<>(config.getDiffPatternRules());
            for (String p : diffPatterns) {
                if (!existing.contains(p)) existing.add(p);
            }
            config.setDiffPatternRules(existing);
        }

        // Semantic matching
        if (semanticMode != null) config.setSemanticMode(semanticMode);
        if (semanticThreshold != null) config.setSemanticThreshold(semanticThreshold);
        if (embeddingUrl != null) config.setEmbeddingUrl(embeddingUrl);
        if (synonymDictionaryPath != null) config.setSynonymDictionaryPath(synonymDictionaryPath);
    }

    private void printSummary(EnforcerConfig config) {
        System.out.println("  ──────────────────────────────────────────");
        System.out.println("  Agent:           " + config.getAgent());
        System.out.println("  Mode:            " + (config.isKeywordMode() ? "keyword" : "LLM judge"));
        System.out.println("  Max corrections: " + config.getMaxCorrections());
        if (!config.getBannedKeywords().isEmpty()) {
            System.out.println("  Banned keywords: " + String.join(", ", config.getBannedKeywords()));
        }
        if (!config.getBannedTools().isEmpty()) {
            System.out.println("  Banned tools:    " + String.join(", ", config.getBannedTools()));
        }
        if (config.getInlineRules() != null && !config.getInlineRules().isBlank()) {
            System.out.println("  Inline rules:    " + countLines(config.getInlineRules()) + " lines");
        }
        if (!config.getDiffPatternRules().isEmpty()) {
            System.out.println("  Diff patterns:   " + config.getDiffPatternRules().size() + " rules");
        }
        System.out.println("  Archive:         " + (config.isArchiveDiffs() ? "enabled" : "disabled"));
        System.out.println("  Auto-rollback:   " + (config.isAutoRollbackOnViolation() ? "yes" : "no"));
        if (!"none".equals(config.getSemanticMode())) {
            System.out.println("  Semantic mode:   " + config.getSemanticMode());
            System.out.println("  Threshold:       " + config.getSemanticThreshold());
            if (config.getEmbeddingUrl() != null && !config.getEmbeddingUrl().isBlank()) {
                System.out.println("  Embedding URL:   " + config.getEmbeddingUrl());
            }
            if (config.getSynonymDictionaryPath() != null) {
                System.out.println("  Synonym dict:    " + config.getSynonymDictionaryPath());
            }
        }
        System.out.println();
    }

    private int showConfig(Path wd) {
        EnforcerConfig config = EnforcerConfig.load(wd);
        if (config == null) {
            System.out.println("  No enforcer config found for this project.");
            System.out.println("  Run: kompile enforcer init");
            return 1;
        }

        System.out.println();
        System.out.println("  \033[1mEnforcer Config: " + EnforcerConfig.resolveConfigPath(wd) + "\033[0m");
        System.out.println("  ──────────────────────────────────────────");
        System.out.println("  Agent:           " + config.getAgent());
        System.out.println("  Mode:            " + (config.isKeywordMode() ? "keyword" : "LLM judge"));
        System.out.println("  Max corrections: " + config.getMaxCorrections());
        System.out.println("  Diff archive:    " + (config.isArchiveDiffs() ? "enabled" : "disabled"));
        System.out.println("  Auto-rollback:   " + (config.isAutoRollbackOnViolation() ? "yes" : "no"));

        if (config.getRuleFile() != null) {
            System.out.println("  Rule file:       " + config.getRuleFile());
        }
        if (config.getInlineRules() != null && !config.getInlineRules().isBlank()) {
            System.out.println("  Inline rules:    " + countLines(config.getInlineRules()) + " lines");
            for (String line : config.getInlineRules().split("\n")) {
                if (!line.isBlank()) {
                    System.out.println("                   " + line.trim());
                }
            }
        }
        if (!config.getBannedTools().isEmpty()) {
            System.out.println("  Banned tools:    " + String.join(", ", config.getBannedTools()));
        }
        if (!config.getBannedCommands().isEmpty()) {
            System.out.println("  Banned commands: " + String.join(", ", config.getBannedCommands()));
        }
        if (!config.getBannedKeywords().isEmpty()) {
            System.out.println("  Banned keywords: " + String.join(", ", config.getBannedKeywords()));
        }
        if (!config.getDiffPatternRules().isEmpty()) {
            System.out.println("  Diff patterns:   " + config.getDiffPatternRules().size() + " rules");
            for (String p : config.getDiffPatternRules()) {
                System.out.println("                   " + p);
            }
        }
        if (config.getDiffPatternsFile() != null) {
            System.out.println("  Patterns file:   " + config.getDiffPatternsFile());
        }
        if (config.getJudgeProvider() != null) {
            System.out.println("  Judge provider:  " + config.getJudgeProvider());
        }
        if (config.getJudgeModel() != null) {
            System.out.println("  Judge model:     " + config.getJudgeModel());
        }
        if (!"none".equals(config.getSemanticMode())) {
            System.out.println("  Semantic mode:   " + config.getSemanticMode());
            System.out.println("  Sim. threshold:  " + config.getSemanticThreshold());
            if (config.getEmbeddingUrl() != null && !config.getEmbeddingUrl().isBlank()) {
                System.out.println("  Embedding URL:   " + config.getEmbeddingUrl());
            }
            if (config.getSynonymDictionaryPath() != null) {
                System.out.println("  Synonym dict:    " + config.getSynonymDictionaryPath());
            }
        }
        System.out.println();
        return 0;
    }

    private int deleteConfig(Path wd) {
        try {
            if (EnforcerConfig.delete(wd)) {
                System.out.println("  Enforcer config deleted.");
            } else {
                System.out.println("  No enforcer config found.");
            }
            return 0;
        } catch (Exception e) {
            System.err.println("  Failed to delete config: " + e.getMessage());
            return 1;
        }
    }

    private static int countLines(String text) {
        if (text == null || text.isEmpty()) return 0;
        return text.split("\n").length;
    }
}
