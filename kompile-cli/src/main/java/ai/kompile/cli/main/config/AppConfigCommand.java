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

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.*;
import java.util.concurrent.Callable;

/**
 * CLI command for managing kompile-app-main UI configuration.
 * Provides both an interactive wizard and non-interactive commands for
 * editing the JSON config files that the Angular UI manages through REST APIs.
 *
 * <p>Usage examples:</p>
 * <pre>
 *   kompile config app --wizard                              # Interactive wizard
 *   kompile config app --show                                # Show all configuration
 *   kompile config app --show-section vectorstore            # Show specific section
 *   kompile config app --set vectorstore.vectorStoreType=PGVECTOR  # Set a value
 *   kompile config app --set embedding.embeddingTargetBatchSize=128
 *   kompile config app --reset                               # Reset all to defaults
 *   kompile config app --reset-section vectorstore           # Reset specific section
 * </pre>
 *
 * <p>Configuration sections:</p>
 * <ul>
 *   <li>{@code vectorstore} — Vector store type, paths, HNSW settings</li>
 *   <li>{@code embedding} — Embedding model and batch sizes</li>
 *   <li>{@code ingestion} — Document chunking and batch sizes</li>
 *   <li>{@code subprocess} — Subprocess mode and heap size</li>
 *   <li>{@code model-roles} — RAG pipeline model assignments</li>
 *   <li>{@code feature-flags} — Toggle optional features</li>
 *   <li>{@code llm-provider} — LLM API keys and model selection</li>
 *   <li>{@code backup} — Backup scheduling and retention</li>
 * </ul>
 *
 * <p>Config files are stored in {@code ~/.kompile/config/} and are read by the
 * Spring Boot application on startup.</p>
 */
@Command(name = "app",
        mixinStandardHelpOptions = true,
        description = "Configure kompile-app-main UI settings (vector store, embedding, LLM, features, etc.)")
public class AppConfigCommand implements Callable<Integer> {

    private static final String RESET  = "\033[0m";
    private static final String BOLD   = "\033[1m";
    private static final String DIM    = "\033[2m";
    private static final String CYAN   = "\033[36m";
    private static final String GREEN  = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String RED    = "\033[31m";

    @Option(names = {"--wizard", "-w"}, description = "Run interactive configuration wizard")
    private boolean wizard;

    @Option(names = {"--show", "-s"}, description = "Show all current configuration")
    private boolean show;

    @Option(names = {"--show-section"}, paramLabel = "SECTION",
            description = "Show configuration for a specific section (vectorstore, embedding, ingestion, subprocess, model-roles, feature-flags, llm-provider, backup)")
    private String showSection;

    @Option(names = {"--set"}, paramLabel = "KEY=VALUE", split = ",",
            description = "Set config values. Format: section.key=value (e.g., vectorstore.vectorStoreType=PGVECTOR)")
    private List<String> setValues;

    @Option(names = {"--reset"}, description = "Reset all configuration to defaults")
    private boolean reset;

    @Option(names = {"--reset-section"}, paramLabel = "SECTION",
            description = "Reset a specific section to defaults")
    private String resetSection;

    @Option(names = {"--list-sections"}, description = "List available configuration sections")
    private boolean listSections;

    @Override
    public Integer call() throws Exception {
        if (wizard) {
            return AppConfigWizard.run() ? 0 : 1;
        }

        if (listSections) {
            return listSections();
        }

        if (show) {
            AppConfigWizard.showAllConfig();
            return 0;
        }

        if (showSection != null) {
            return showSection(showSection);
        }

        if (setValues != null && !setValues.isEmpty()) {
            return setValues(setValues);
        }

        if (reset) {
            return resetAll();
        }

        if (resetSection != null) {
            return resetSection(resetSection);
        }

        // No flags — print usage hint
        System.out.println();
        System.out.println(BOLD + "  Kompile App Configuration" + RESET);
        System.out.println();
        System.out.println("  Manage kompile-app-main UI settings from the command line.");
        System.out.println("  Config files are stored in " + DIM + "~/.kompile/config/" + RESET);
        System.out.println();
        System.out.println("  Common commands:");
        System.out.println("    " + CYAN + "kompile config app --wizard" + RESET + "           Interactive setup wizard");
        System.out.println("    " + CYAN + "kompile config app --show" + RESET + "             Show all configuration");
        System.out.println("    " + CYAN + "kompile config app --show-section" + RESET + " X   Show a specific section");
        System.out.println("    " + CYAN + "kompile config app --set" + RESET + " K=V          Set a configuration value");
        System.out.println("    " + CYAN + "kompile config app --list-sections" + RESET + "    List available sections");
        System.out.println("    " + CYAN + "kompile config app --reset" + RESET + "            Reset all to defaults");
        System.out.println();
        System.out.println("  Use " + BOLD + "--help" + RESET + " for full option documentation.");
        System.out.println();
        return 0;
    }

    private int listSections() {
        System.out.println();
        System.out.println(BOLD + "  Available configuration sections:" + RESET);
        System.out.println();
        for (int i = 0; i < AppConfigWizard.SECTIONS.length; i++) {
            String file = AppConfigWizard.sectionToConfigFile(AppConfigWizard.SECTIONS[i]);
            System.out.printf("    " + CYAN + "%-16s" + RESET + " %s  " + DIM + "(%s)" + RESET + "%n",
                    AppConfigWizard.SECTIONS[i],
                    AppConfigWizard.SECTION_LABELS[i],
                    file);
        }
        System.out.println();
        System.out.println("  Use " + BOLD + "--show-section <name>" + RESET + " to view, " + BOLD + "--reset-section <name>" + RESET + " to reset.");
        System.out.println("  Use " + BOLD + "--set <section>.<key>=<value>" + RESET + " to set individual values.");
        System.out.println();
        return 0;
    }

    private int showSection(String section) {
        String normalized = section.toLowerCase().trim();
        String configFile = AppConfigWizard.sectionToConfigFile(normalized);

        if (configFile == null) {
            System.err.println(RED + "  Unknown section: " + section + RESET);
            System.err.println("  Available sections: " + String.join(", ", AppConfigWizard.SECTIONS));
            return 1;
        }

        // Find the label
        String label = normalized;
        for (int i = 0; i < AppConfigWizard.SECTIONS.length; i++) {
            if (AppConfigWizard.SECTIONS[i].equals(normalized)) {
                label = AppConfigWizard.SECTION_LABELS[i];
                break;
            }
        }

        System.out.println();
        AppConfigWizard.showSection(label, configFile);
        return 0;
    }

    private int setValues(List<String> assignments) {
        int errors = 0;
        for (String assignment : assignments) {
            int eq = assignment.indexOf('=');
            if (eq < 0) {
                System.err.println(RED + "  Invalid format: " + assignment + RESET);
                System.err.println("  Expected: section.key=value (e.g., vectorstore.vectorStoreType=PGVECTOR)");
                errors++;
                continue;
            }

            String dotPath = assignment.substring(0, eq).trim();
            String value = assignment.substring(eq + 1).trim();

            String[] resolved = AppConfigWizard.resolveSetKey(dotPath);
            if (resolved == null) {
                System.err.println(RED + "  Unknown key: " + dotPath + RESET);
                System.err.println("  Format: section.key (e.g., vectorstore.vectorStoreType)");
                System.err.println("  Sections: " + String.join(", ", AppConfigWizard.SECTIONS));
                errors++;
                continue;
            }

            String configFile = resolved[0];
            String key = resolved[1];

            Map<String, Object> config = AppConfigWizard.loadConfig(configFile);
            config.put(key, parseValue(value));
            AppConfigWizard.saveConfig(configFile, config);

            System.out.println(GREEN + "  Set " + RESET + dotPath + " = " + value + DIM + " (in " + configFile + ")" + RESET);
        }

        if (errors == 0) {
            System.out.println();
            System.out.println(GREEN + "  Configuration updated. Restart kompile-app for changes to take effect." + RESET);
            System.out.println();
        }

        return errors > 0 ? 1 : 0;
    }

    private int resetAll() {
        System.out.println();
        String[] allFiles = {
                AppConfigWizard.APP_INDEX_CONFIG,
                AppConfigWizard.MODEL_ROLES_CONFIG,
                AppConfigWizard.FEATURE_FLAGS_CONFIG,
                AppConfigWizard.LLM_PROVIDER_CONFIG,
                AppConfigWizard.BACKUP_CONFIG,
                "pipeline-config.json"
        };

        for (String file : allFiles) {
            AppConfigWizard.resetConfig(file);
            System.out.println("  " + DIM + "Removed: " + file + RESET);
        }

        System.out.println();
        System.out.println(GREEN + "  All configuration reset to defaults." + RESET);
        System.out.println("  Restart kompile-app to apply default settings.");
        System.out.println();
        return 0;
    }

    private int resetSection(String section) {
        String normalized = section.toLowerCase().trim();
        String configFile = AppConfigWizard.sectionToConfigFile(normalized);

        if (configFile == null) {
            System.err.println(RED + "  Unknown section: " + section + RESET);
            System.err.println("  Available sections: " + String.join(", ", AppConfigWizard.SECTIONS));
            return 1;
        }

        // For sections that share app-index-config.json, we can't just delete the whole file.
        // Instead, remove the relevant keys.
        if (AppConfigWizard.APP_INDEX_CONFIG.equals(configFile)) {
            Map<String, Object> config = AppConfigWizard.loadConfig(configFile);
            List<String> keysToRemove = getKeysForSection(normalized);
            for (String key : keysToRemove) {
                config.remove(key);
            }
            AppConfigWizard.saveConfig(configFile, config);
            System.out.println(GREEN + "  Section '" + section + "' reset. Relevant keys removed from " + configFile + RESET);
        } else {
            AppConfigWizard.resetConfig(configFile);
            System.out.println(GREEN + "  Section '" + section + "' reset. Removed " + configFile + RESET);
        }

        System.out.println("  Restart kompile-app to apply default settings.");
        System.out.println();
        return 0;
    }

    private List<String> getKeysForSection(String section) {
        return switch (section) {
            case "vectorstore" -> List.of(
                    "vectorStoreType", "vectorStorePath", "keywordIndexPath",
                    "hnsw",
                    "vespaEndpoint", "vespaNamespace", "vespaDocumentType", "vespaVectorField",
                    "vespaHybridSearchEnabled", "vespaHybridVectorWeight",
                    "pgvectorUrl", "pgvectorUsername", "pgvectorPassword", "pgvectorTableName",
                    "chromaHost", "chromaPort", "chromaCollectionName");
            case "embedding" -> List.of(
                    "embeddingModelId", "embeddingTargetBatchSize", "adaptiveBatchSize");
            case "ingestion" -> List.of("indexBatchSize");
            case "subprocess" -> List.of("subprocessEnabled", "subprocessHeapSize");
            default -> Collections.emptyList();
        };
    }

    /**
     * Parse a string value into an appropriate JSON type.
     * "true"/"false" → Boolean, numeric strings → Integer/Double, else String.
     */
    private Object parseValue(String value) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e1) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e2) {
                return value;
            }
        }
    }
}
