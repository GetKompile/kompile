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

import ai.kompile.cli.common.config.ComponentFilter;
import ai.kompile.cli.common.config.ConfigArchiveManifest;
import ai.kompile.cli.common.config.ImportMode;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.util.*;

/**
 * Interactive wizard for selecting components during config export/import.
 * Uses numbered toggle menus matching the SetupWizard pattern.
 *
 * <p>All components start enabled. The user toggles individual items on/off,
 * then confirms to proceed.</p>
 */
public class ConfigArchiveWizard {

    private static final String RESET  = "\033[0m";
    private static final String BOLD   = "\033[1m";
    private static final String DIM    = "\033[2m";
    private static final String CYAN   = "\033[36m";
    private static final String GREEN  = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String RED    = "\033[31m";

    private static final String CHECK   = GREEN + "[x]" + RESET;
    private static final String UNCHECK = DIM   + "[ ]" + RESET;

    /**
     * Run the export wizard. Returns a ComponentFilter, or null if cancelled.
     */
    public static ComponentFilter runExportWizard() {
        return runWizard("Export", null);
    }

    /**
     * Run the import wizard. Shows manifest contents and lets user pick components
     * and import mode. Returns null if cancelled.
     */
    public static ImportWizardResult runImportWizard(ConfigArchiveManifest manifest) {
        Terminal terminal = null;
        try {
            terminal = TerminalBuilder.builder().system(true).build();
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();

            printHeader("Import Configuration Archive");

            // Show archive info
            System.out.println("  " + DIM + "Created: " + manifest.getCreatedAt() + RESET);
            System.out.println("  " + DIM + "Host:    " + manifest.getHostname() + RESET);
            if (manifest.getDescription() != null) {
                System.out.println("  " + DIM + "Note:    " + manifest.getDescription() + RESET);
            }
            System.out.println();

            // Build filter from what's actually in the archive
            ComponentFilter filter = buildFilterFromManifest(manifest);
            if (filter.getEnabled().isEmpty()) {
                System.out.println(YELLOW + "  Archive is empty — nothing to import." + RESET);
                return null;
            }

            // Component selection loop
            filter = runComponentToggleLoop(reader, filter, "import");
            if (filter == null) return null;

            // Select import mode
            ImportMode mode = selectImportMode(reader);
            if (mode == null) return null;

            return new ImportWizardResult(filter, mode);

        } catch (Exception e) {
            System.err.println("Wizard error: " + e.getMessage());
            return null;
        } finally {
            closeTerminal(terminal);
        }
    }

    /**
     * Run a generic component selection wizard (used for export).
     * @param action "Export" or "Import"
     * @param manifest if non-null, restrict toggle items to what's in the manifest
     */
    private static ComponentFilter runWizard(String action, ConfigArchiveManifest manifest) {
        Terminal terminal = null;
        try {
            terminal = TerminalBuilder.builder().system(true).build();
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();

            printHeader(action + " Configuration Archive");

            ComponentFilter filter = (manifest != null)
                    ? buildFilterFromManifest(manifest)
                    : ComponentFilter.all();

            filter = runComponentToggleLoop(reader, filter, action.toLowerCase());
            return filter;

        } catch (Exception e) {
            System.err.println("Wizard error: " + e.getMessage());
            return null;
        } finally {
            closeTerminal(terminal);
        }
    }

    // ─── Component toggle loop ──────────────────────────────────────────

    private static ComponentFilter runComponentToggleLoop(LineReader reader,
                                                          ComponentFilter filter,
                                                          String action) {
        String[] components = ComponentFilter.ALL_COMPONENTS;

        while (true) {
            System.out.println(BOLD + "  Select components to " + action + ":" + RESET);
            System.out.println("  " + DIM + "(Enter a number to toggle, 'a' to select all, 'n' to select none)" + RESET);
            System.out.println();

            int enabledCount = 0;
            for (int i = 0; i < components.length; i++) {
                String key = components[i];
                String label = ComponentFilter.COMPONENT_LABELS.get(key);
                boolean on = filter.isEnabled(key);
                if (on) enabledCount++;
                String checkbox = on ? CHECK : UNCHECK;
                System.out.printf("  " + CYAN + "%2d" + RESET + "  %s  %s%n", i + 1, checkbox, label);
            }
            System.out.println();
            System.out.println("  " + DIM + "Selected: " + enabledCount + "/" + components.length + RESET);
            System.out.println();

            String input;
            try {
                input = reader.readLine("  Toggle (1-" + components.length
                        + "), [a]ll, [n]one, [c]onfirm, [q]uit: ");
            } catch (Exception e) {
                return null;
            }
            if (input == null) return null;
            String trimmed = input.trim().toLowerCase();

            if (trimmed.equals("q") || trimmed.equals("quit")) {
                return null;
            }
            if (trimmed.equals("c") || trimmed.equals("confirm") || trimmed.equals("done")
                    || trimmed.equals("y") || trimmed.equals("yes")) {
                if (!filter.hasAnyEnabled()) {
                    System.out.println("  " + YELLOW + "Select at least one component." + RESET);
                    continue;
                }
                // Print confirmed selection
                System.out.println();
                System.out.println(GREEN + "  Selected components:" + RESET);
                for (String key : components) {
                    if (filter.isEnabled(key)) {
                        System.out.println("    " + GREEN + "+" + RESET + " " + key);
                    }
                }
                System.out.println();
                return filter;
            }
            if (trimmed.equals("a") || trimmed.equals("all")) {
                for (String key : components) filter.enable(key);
                continue;
            }
            if (trimmed.equals("n") || trimmed.equals("none")) {
                for (String key : components) filter.disable(key);
                continue;
            }

            // Handle comma-separated numbers or ranges: "1,3,5" or "1-5"
            for (String part : trimmed.split("[,\\s]+")) {
                part = part.trim();
                if (part.isEmpty()) continue;
                if (part.contains("-")) {
                    // Range: "2-5"
                    String[] range = part.split("-", 2);
                    try {
                        int lo = Integer.parseInt(range[0].trim());
                        int hi = Integer.parseInt(range[1].trim());
                        for (int n = lo; n <= hi; n++) {
                            if (n >= 1 && n <= components.length) {
                                filter.toggle(components[n - 1]);
                            }
                        }
                    } catch (NumberFormatException ignored) {}
                } else {
                    try {
                        int n = Integer.parseInt(part);
                        if (n >= 1 && n <= components.length) {
                            filter.toggle(components[n - 1]);
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
    }

    // ─── Import mode selection ──────────────────────────────────────────

    private static ImportMode selectImportMode(LineReader reader) {
        System.out.println(BOLD + "  Select import mode:" + RESET);
        System.out.println();
        System.out.printf("  " + CYAN + " 1" + RESET + "  Append   — merge with existing configs (safe)%n");
        System.out.printf("  " + CYAN + " 2" + RESET + "  Override — replace existing configs%n");
        System.out.println();

        while (true) {
            String input;
            try {
                input = reader.readLine("  Mode (1-2, or Ctrl+C to cancel): ");
            } catch (Exception e) {
                return null;
            }
            if (input == null) return null;
            String trimmed = input.trim().toLowerCase();
            if (trimmed.equals("q") || trimmed.equals("quit")) return null;
            if (trimmed.equals("1") || trimmed.startsWith("a")) {
                System.out.println("  → " + GREEN + "Append" + RESET);
                System.out.println();
                return ImportMode.APPEND;
            }
            if (trimmed.equals("2") || trimmed.startsWith("o")) {
                System.out.println("  → " + YELLOW + "Override" + RESET);
                System.out.println();
                return ImportMode.OVERRIDE;
            }
            System.out.println("  " + YELLOW + "Enter 1 (append) or 2 (override)" + RESET);
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    /**
     * Build a ComponentFilter that only enables components present in the manifest.
     */
    private static ComponentFilter buildFilterFromManifest(ConfigArchiveManifest manifest) {
        ComponentFilter f = ComponentFilter.none();

        List<String> kompileConfigs = manifest.getKompileConfigs();
        if (kompileConfigs != null) {
            // Check if any config dir files are present
            boolean hasAppConfigs = kompileConfigs.stream().anyMatch(c ->
                    !c.equals("chat-config.json") && !c.equals("harness-config.json")
                            && !c.equals("staging-settings.json") && !c.equals("perf-data.json"));
            if (hasAppConfigs) f.enable("kompile-app-configs");
            if (kompileConfigs.contains("chat-config.json")) f.enable("kompile-chat-config");
            if (kompileConfigs.contains("harness-config.json")) f.enable("kompile-harness-config");
            if (kompileConfigs.contains("staging-settings.json") || kompileConfigs.contains("perf-data.json"))
                f.enable("kompile-other-configs");
        }

        if (manifest.getSystemPrompts() != null && !manifest.getSystemPrompts().isEmpty())
            f.enable("system-prompts");

        Map<String, List<String>> providers = manifest.getChatProviderConfigs();
        if (providers != null) {
            for (String provider : providers.keySet()) {
                if (providers.get(provider) != null && !providers.get(provider).isEmpty()) {
                    f.enable(provider);
                }
            }
        }

        return f;
    }

    private static void printHeader(String title) {
        System.out.println();
        System.out.println(BOLD + CYAN + "  ╭──────────────────────────────────────╮" + RESET);
        System.out.printf( BOLD + CYAN + "  │  %-36s│%n" + RESET, title);
        System.out.println(BOLD + CYAN + "  ╰──────────────────────────────────────╯" + RESET);
        System.out.println();
    }

    private static void closeTerminal(Terminal terminal) {
        if (terminal != null) {
            try { terminal.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Result of the import wizard — contains both the filter and the selected mode.
     */
    public static class ImportWizardResult {
        private final ComponentFilter filter;
        private final ImportMode mode;

        public ImportWizardResult(ComponentFilter filter, ImportMode mode) {
            this.filter = filter;
            this.mode = mode;
        }

        public ComponentFilter getFilter() { return filter; }
        public ImportMode getMode() { return mode; }
    }
}
