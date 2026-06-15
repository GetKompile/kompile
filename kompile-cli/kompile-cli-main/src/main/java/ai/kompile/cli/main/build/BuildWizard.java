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

package ai.kompile.cli.main.build;

import ai.kompile.cli.main.build.config.BuildPreset;
import ai.kompile.cli.main.build.config.ModuleCatalog;
import ai.kompile.cli.main.build.config.ModuleSelection;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Interactive build wizard that walks the user through building a kompile app.
 * Guides through preset selection, module customization, and build options.
 */
public class BuildWizard {

    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String CYAN = "\033[36m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String WHITE = "\033[37m";

    /** Categories shown during wizard customization (CORE is always included). */
    private static final ModuleCatalog.Category[] CUSTOMIZABLE_CATEGORIES = {
            ModuleCatalog.Category.LLM,
            ModuleCatalog.Category.EMBEDDING,
            ModuleCatalog.Category.VECTORSTORE,
            ModuleCatalog.Category.LOADER,
            ModuleCatalog.Category.CHUNKER,
            ModuleCatalog.Category.TOOL,
            ModuleCatalog.Category.ENTERPRISE,
            ModuleCatalog.Category.ADVANCED,
            ModuleCatalog.Category.PIPELINE
    };

    /**
     * Result of the wizard containing all user selections.
     */
    public static class WizardResult {
        public final String configName;
        public final BuildPreset preset;
        public final Set<String> moduleIds;
        public final boolean buildNative;
        public final String javacppPlatform;

        WizardResult(String configName, BuildPreset preset, Set<String> moduleIds,
                     boolean buildNative, String javacppPlatform) {
            this.configName = configName;
            this.preset = preset;
            this.moduleIds = Collections.unmodifiableSet(moduleIds);
            this.buildNative = buildNative;
            this.javacppPlatform = javacppPlatform;
        }
    }

    /**
     * Run the interactive build wizard.
     * Returns a WizardResult or null if the user cancels.
     */
    public static WizardResult run() {
        try {
            Terminal terminal = TerminalBuilder.builder().system(true).build();
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();

            // Welcome banner
            System.out.println();
            System.out.println(BOLD + CYAN + "  ╭──────────────────────────────────────╮" + RESET);
            System.out.println(BOLD + CYAN + "  │       Kompile Build Wizard           │" + RESET);
            System.out.println(BOLD + CYAN + "  ╰──────────────────────────────────────╯" + RESET);
            System.out.println();
            System.out.println("  Build a custom kompile application step by step.");
            System.out.println("  " + DIM + "(Press Ctrl+C or enter empty input to cancel)" + RESET);
            System.out.println();

            // Step 1: App name
            String configName = promptRequired(reader, "  App name (used as artifactId): ");
            if (configName == null) return null;

            // Step 2: Preset selection
            System.out.println();
            System.out.println(BOLD + "  Select a starting preset:" + RESET);
            System.out.println();

            BuildPreset[] presets = BuildPreset.values();
            for (int i = 0; i < presets.length; i++) {
                String label = presets[i].name().toLowerCase().replace("_", "-");
                System.out.printf("  " + CYAN + "%d" + RESET + "  %-20s %s%s%n",
                        i + 1, label, DIM + presets[i].getDescription() + RESET,
                        i == 0 ? " " + DIM + "(recommended)" + RESET : "");
            }
            System.out.printf("  " + CYAN + "%d" + RESET + "  %-20s %s%n",
                    presets.length + 1, "custom", DIM + "Start from scratch and pick each module" + RESET);
            System.out.println();

            Integer presetChoice = promptNumber(reader, "  Choice [1-" + (presets.length + 1) + "]: ", 1, presets.length + 1);
            if (presetChoice == null) return null;

            BuildPreset selectedPreset = null;
            Set<String> selectedModules;

            if (presetChoice <= presets.length) {
                selectedPreset = presets[presetChoice - 1];
                selectedModules = new LinkedHashSet<>(selectedPreset.getDefaultModules());
                System.out.println("  → " + GREEN + selectedPreset.name().toLowerCase().replace("_", "-") + RESET);

                // Ask if they want to customize
                System.out.println();
                boolean customize = promptYesNo(reader, "  Customize module selection? [y/N]: ", false);
                if (customize) {
                    selectedModules = customizeModules(reader, selectedModules);
                    if (selectedModules == null) return null;
                }
            } else {
                // Custom: start with just core modules
                selectedModules = new LinkedHashSet<>();
                for (ModuleCatalog.ModuleEntry entry : ModuleCatalog.getByCategory(ModuleCatalog.Category.CORE)) {
                    selectedModules.add(entry.getId());
                }
                System.out.println("  → " + GREEN + "custom" + RESET);
                selectedModules = customizeModules(reader, selectedModules);
                if (selectedModules == null) return null;
            }

            // Step 3: Build options
            System.out.println();
            System.out.println(BOLD + "  Build Options:" + RESET);
            System.out.println();

            boolean buildNative = promptYesNo(reader, "  Build GraalVM native image? [Y/n]: ", true);

            String platform = promptPlatform(reader);
            if (platform == null) return null;

            // Step 4: Summary
            System.out.println();
            System.out.println(BOLD + CYAN + "  ─── Build Summary ───" + RESET);
            System.out.println();
            System.out.println("  App name:    " + BOLD + configName + RESET);
            if (selectedPreset != null) {
                System.out.println("  Preset:      " + BOLD + selectedPreset.name().toLowerCase().replace("_", "-") + RESET);
            } else {
                System.out.println("  Preset:      " + BOLD + "custom" + RESET);
            }
            System.out.println("  Native:      " + BOLD + (buildNative ? "yes" : "no") + RESET);
            System.out.println("  Platform:    " + BOLD + platform + RESET);
            System.out.println();

            printModuleSummary(selectedModules);

            System.out.println();
            boolean proceed = promptYesNo(reader, "  Proceed with build? [Y/n]: ", true);
            if (!proceed) {
                System.out.println("  " + YELLOW + "Build cancelled." + RESET);
                return null;
            }

            System.out.println();
            return new WizardResult(configName, selectedPreset, selectedModules, buildNative, platform);

        } catch (Exception e) {
            System.err.println("Build wizard error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Walk through each customizable category and let user toggle modules.
     */
    private static Set<String> customizeModules(LineReader reader, Set<String> current) {
        Set<String> modules = new LinkedHashSet<>(current);

        for (ModuleCatalog.Category category : CUSTOMIZABLE_CATEGORIES) {
            List<ModuleCatalog.ModuleEntry> available = ModuleCatalog.getByCategory(category);
            if (available.isEmpty()) continue;

            System.out.println();
            System.out.println(BOLD + "  " + formatCategoryName(category) + ":" + RESET);
            System.out.println();

            // Display modules with selection state
            for (int i = 0; i < available.size(); i++) {
                ModuleCatalog.ModuleEntry entry = available.get(i);
                boolean selected = modules.contains(entry.getId());
                String marker = selected ? GREEN + "[*]" + RESET : "[ ]";
                System.out.printf("  %s " + CYAN + "%d" + RESET + "  %-35s %s%n",
                        marker, i + 1, entry.getId(), DIM + entry.getDescription() + RESET);
            }
            System.out.println();
            System.out.println("  " + DIM + "Toggle modules by number (e.g., 1 3), Enter to continue" + RESET);

            while (true) {
                String input = prompt(reader, "  Toggle: ");
                if (input == null) return null;
                if (input.trim().isEmpty()) break;

                // Parse space/comma separated numbers
                String[] parts = input.trim().split("[\\s,]+");
                boolean anyInvalid = false;
                for (String part : parts) {
                    try {
                        int num = Integer.parseInt(part);
                        if (num >= 1 && num <= available.size()) {
                            String id = available.get(num - 1).getId();
                            if (modules.contains(id)) {
                                modules.remove(id);
                            } else {
                                modules.add(id);
                            }
                        } else {
                            anyInvalid = true;
                        }
                    } catch (NumberFormatException e) {
                        anyInvalid = true;
                    }
                }

                if (anyInvalid) {
                    System.out.println("  " + YELLOW + "Enter numbers 1-" + available.size() + RESET);
                }

                // Redisplay after toggling
                for (int i = 0; i < available.size(); i++) {
                    ModuleCatalog.ModuleEntry entry = available.get(i);
                    boolean selected = modules.contains(entry.getId());
                    String marker = selected ? GREEN + "[*]" + RESET : "[ ]";
                    System.out.printf("  %s " + CYAN + "%d" + RESET + "  %-35s %s%n",
                            marker, i + 1, entry.getId(), DIM + entry.getDescription() + RESET);
                }
                System.out.println();
            }
        }

        return modules;
    }

    private static String promptPlatform(LineReader reader) {
        String[] platforms = {"linux-x86_64", "macosx-arm64", "macosx-x86_64", "windows-x86_64"};

        System.out.println();
        System.out.println(BOLD + "  Target platform:" + RESET);
        System.out.println();
        for (int i = 0; i < platforms.length; i++) {
            String rec = i == 0 ? " " + DIM + "(default)" + RESET : "";
            System.out.printf("  " + CYAN + "%d" + RESET + "  %s%s%n", i + 1, platforms[i], rec);
        }
        System.out.println();

        String input = prompt(reader, "  Choice [1-" + platforms.length + "] or platform name (Enter for default): ");
        if (input == null) return null;
        if (input.trim().isEmpty()) return platforms[0];

        try {
            int choice = Integer.parseInt(input.trim());
            if (choice >= 1 && choice <= platforms.length) {
                return platforms[choice - 1];
            }
        } catch (NumberFormatException ignored) {
        }

        // Accept raw platform string
        return input.trim();
    }

    private static void printModuleSummary(Set<String> moduleIds) {
        System.out.println("  " + BOLD + "Modules (" + moduleIds.size() + "):" + RESET);
        for (ModuleCatalog.Category cat : ModuleCatalog.Category.values()) {
            List<String> catModules = moduleIds.stream()
                    .filter(id -> {
                        ModuleCatalog.ModuleEntry e = ModuleCatalog.get(id);
                        return e != null && e.getCategory() == cat;
                    })
                    .collect(Collectors.toList());
            if (!catModules.isEmpty()) {
                System.out.println("    " + WHITE + cat.name() + RESET + ": " + String.join(", ", catModules));
            }
        }
    }

    private static String formatCategoryName(ModuleCatalog.Category category) {
        switch (category) {
            case LLM:         return "LLM Providers";
            case EMBEDDING:   return "Embedding Providers";
            case VECTORSTORE: return "Vector Stores";
            case LOADER:      return "Document Loaders";
            case CHUNKER:     return "Text Chunkers";
            case TOOL:        return "Tools";
            case ENTERPRISE:  return "Enterprise";
            case ADVANCED:    return "Advanced";
            case PIPELINE:    return "Pipeline";
            default:          return category.name();
        }
    }

    private static String prompt(LineReader reader, String prompt) {
        try {
            return reader.readLine(prompt);
        } catch (Exception e) {
            return null;
        }
    }

    private static String promptRequired(LineReader reader, String prompt) {
        while (true) {
            String input = prompt(reader, prompt);
            if (input == null) return null;
            String trimmed = input.trim();
            if (!trimmed.isEmpty()) return trimmed;
            System.out.println("  " + YELLOW + "This field is required." + RESET);
        }
    }

    private static Integer promptNumber(LineReader reader, String prompt, int min, int max) {
        while (true) {
            String input = prompt(reader, prompt);
            if (input == null) return null;
            try {
                int val = Integer.parseInt(input.trim());
                if (val >= min && val <= max) return val;
            } catch (NumberFormatException ignored) {
            }
            System.out.println("  " + YELLOW + "Please enter a number " + min + "-" + max + RESET);
        }
    }

    private static boolean promptYesNo(LineReader reader, String prompt, boolean defaultValue) {
        String input = prompt(reader, prompt);
        if (input == null || input.trim().isEmpty()) return defaultValue;
        String lower = input.trim().toLowerCase();
        return lower.startsWith("y");
    }
}
