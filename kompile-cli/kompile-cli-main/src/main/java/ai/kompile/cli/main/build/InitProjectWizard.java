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
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import ai.kompile.cli.common.util.EnvironmentUtils;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Interactive wizard that walks the user through initializing a new kompile project.
 * Covers all configuration: project identity, preset, module customization, compute backend,
 * platform, database, LLM/embedding providers, server port, build options, and more.
 */
public class InitProjectWizard {

    private static final String RESET  = "\033[0m";
    private static final String BOLD   = "\033[1m";
    private static final String DIM    = "\033[2m";
    private static final String CYAN   = "\033[36m";
    private static final String GREEN  = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String WHITE  = "\033[37m";
    private static final String RED    = "\033[31m";

    /**
     * Categories shown during general module customization.
     * LLM and EMBEDDING are handled by dedicated provider-selection steps.
     */
    private static final ModuleCatalog.Category[] CUSTOMIZABLE_CATEGORIES = {
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
        public final String projectName;
        public final File outputDir;
        public final BuildPreset preset;
        public final Set<String> moduleIds;
        public final int serverPort;
        public final String javacppPlatform;
        public final boolean startAfterBuild;
        public final boolean noBuild;

        // Compute backends (one or more, first is primary)
        public final List<String> backends;  // e.g. ["nd4j-native"], ["nd4j-native", "nd4j-cuda-12.9"]
        public final String javacppExtension; // null, "avx2", or "cuda"

        // App metadata
        public final String appTitle;

        // Database (pgvector)
        public final String databaseUrl;
        public final String databaseUsername;
        public final String databasePassword;
        public final boolean enableSchemaInit;

        // Provider API keys — null if not set or not needed
        public final String openaiApiKey;
        public final String anthropicApiKey;
        public final String geminiProjectId;

        // Language / model config
        public final List<String> supportedLanguages;

        // Maven home — null means use auto-discovery
        public final File mavenHome;

        // Data sources to crawl after the app starts — empty list means no initial crawl
        public final List<String> dataSources;
        public final int crawlDepth;
        public final boolean crawlMultimodal;
        public final boolean crawlGraph;

        WizardResult(String projectName, File outputDir, BuildPreset preset,
                     Set<String> moduleIds, int serverPort, String javacppPlatform,
                     boolean startAfterBuild, boolean noBuild,
                     List<String> backends, String javacppExtension,
                     String appTitle,
                     String databaseUrl, String databaseUsername, String databasePassword,
                     boolean enableSchemaInit,
                     String openaiApiKey, String anthropicApiKey, String geminiProjectId,
                     List<String> supportedLanguages,
                     File mavenHome,
                     List<String> dataSources, int crawlDepth,
                     boolean crawlMultimodal, boolean crawlGraph) {
            this.projectName = projectName;
            this.outputDir = outputDir;
            this.preset = preset;
            this.moduleIds = Collections.unmodifiableSet(moduleIds);
            this.serverPort = serverPort;
            this.javacppPlatform = javacppPlatform;
            this.startAfterBuild = startAfterBuild;
            this.noBuild = noBuild;
            this.backends = backends != null ? Collections.unmodifiableList(backends) : List.of("nd4j-native");
            this.javacppExtension = javacppExtension;
            this.appTitle = appTitle;
            this.databaseUrl = databaseUrl;
            this.databaseUsername = databaseUsername;
            this.databasePassword = databasePassword;
            this.enableSchemaInit = enableSchemaInit;
            this.openaiApiKey = openaiApiKey;
            this.anthropicApiKey = anthropicApiKey;
            this.geminiProjectId = geminiProjectId;
            this.supportedLanguages = supportedLanguages != null
                    ? Collections.unmodifiableList(supportedLanguages)
                    : List.of("en");
            this.mavenHome = mavenHome;
            this.dataSources = dataSources != null
                    ? Collections.unmodifiableList(dataSources)
                    : List.of();
            this.crawlDepth = crawlDepth;
            this.crawlMultimodal = crawlMultimodal;
            this.crawlGraph = crawlGraph;
        }
    }

    /**
     * Run the interactive project initialization wizard.
     * Returns a WizardResult or null if the user cancels.
     */
    public static WizardResult run() {
        try {
            Terminal terminal = TerminalBuilder.builder().system(true).build();
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();

            printBanner();

            // ── Step 1: Project name ────────────────────────────────────────
            String projectName = promptRequired(reader, "  Project name: ");
            if (projectName == null) return null;
            projectName = projectName.toLowerCase().replaceAll("\\s+", "-").replaceAll("[^a-z0-9\\-]", "");
            if (projectName.isEmpty()) {
                System.out.println("  " + YELLOW + "Invalid project name." + RESET);
                return null;
            }

            // ── Step 2: Output directory ────────────────────────────────────
            System.out.println();
            String outputDirStr = prompt(reader,
                    "  Output directory " + DIM + "[Enter = current directory]" + RESET + ": ");
            if (outputDirStr == null) return null;
            File outputDir = outputDirStr.trim().isEmpty()
                    ? new File(".")
                    : new File(outputDirStr.trim());

            File projectDir = new File(outputDir, projectName);
            if (projectDir.exists()) {
                System.out.println();
                System.out.println("  " + YELLOW + "Directory already exists: " + projectDir.getAbsolutePath() + RESET);
                boolean overwrite = promptYesNo(reader, "  Remove and recreate? [y/N]: ", false);
                if (!overwrite) {
                    System.out.println("  " + YELLOW + "Cancelled." + RESET);
                    return null;
                }
            }

            // ── Step 3: Application title ───────────────────────────────────
            System.out.println();
            String appTitle = prompt(reader,
                    "  Application title " + DIM + "[Enter = Kompile RAG Console]" + RESET + ": ");
            if (appTitle == null) return null;
            if (appTitle.trim().isEmpty()) appTitle = "Kompile RAG Console";
            else appTitle = appTitle.trim();

            // ── Step 4: Preset selection ────────────────────────────────────
            System.out.println();
            System.out.println(BOLD + "  Choose an application template:" + RESET);
            System.out.println();

            BuildPreset[] presets = BuildPreset.values();
            for (int i = 0; i < presets.length; i++) {
                String label = presets[i].name().toLowerCase().replace("_", "-");
                String rec = (i == 0) ? " " + GREEN + "(recommended)" + RESET : "";
                String apiNote = getPresetApiNote(presets[i]);
                System.out.printf("  " + CYAN + "%d" + RESET + "  %-22s %s%s%s%n",
                        i + 1, label, DIM + presets[i].getDescription() + RESET, rec, apiNote);
            }
            System.out.printf("  " + CYAN + "%d" + RESET + "  %-22s %s%n",
                    presets.length + 1, "custom", DIM + "Start from scratch and pick each module" + RESET);
            System.out.println();

            Integer presetChoice = promptNumber(reader, "  Choice [1-" + (presets.length + 1) + "]: ",
                    1, presets.length + 1);
            if (presetChoice == null) return null;

            BuildPreset selectedPreset = null;
            Set<String> selectedModules;

            if (presetChoice <= presets.length) {
                selectedPreset = presets[presetChoice - 1];
                selectedModules = new LinkedHashSet<>(selectedPreset.getDefaultModules());
                System.out.println("  → " + GREEN + selectedPreset.name().toLowerCase().replace("_", "-") + RESET);
                printPresetExplanation(selectedPreset);
            } else {
                selectedModules = new LinkedHashSet<>();
                System.out.println("  → " + GREEN + "custom" + RESET);

                // app-main and app-lite are mutually exclusive — prompt for which base
                System.out.println();
                System.out.println(BOLD + "  Application base:" + RESET);
                System.out.println();
                System.out.println("  " + CYAN + "1" + RESET + "  Full Application         " + DIM + "Web UI, MCP tools, pipelines, all loaders" + RESET + "  " + GREEN + "(recommended)" + RESET);
                System.out.println("  " + CYAN + "2" + RESET + "  Lite Application          " + DIM + "Self-contained chat + RAG + Graph RAG, minimal footprint" + RESET);
                System.out.println();
                Integer baseChoice = promptNumber(reader, "  Choice [1-2] " + DIM + "(default: 1)" + RESET + ": ", 1, 2, 1);
                if (baseChoice == null) return null;

                if (baseChoice == 2) {
                    // Lite base: app-lite + relevant core (no loaders-orchestrator, no pipelines-llm)
                    selectedModules.add("app-lite");
                    selectedModules.add("app-core");
                    selectedModules.add("app-anserini");
                    selectedModules.add("chat-history");
                    System.out.println("  → " + GREEN + "Lite Application" + RESET);
                } else {
                    // Full base: app-main + all bundled core modules
                    selectedModules.add("app-main");
                    selectedModules.add("app-core");
                    selectedModules.add("loaders-orchestrator");
                    selectedModules.add("app-anserini");
                    selectedModules.add("chat-history");
                    selectedModules.add("pipelines-llm");
                    System.out.println("  → " + GREEN + "Full Application" + RESET);
                }
            }

            // ── Step 5: LLM provider selection ─────────────────────────────
            // Remove any preset LLM modules — user picks explicitly
            Set<String> presetLlms = new LinkedHashSet<>();
            for (String id : selectedModules) {
                ModuleCatalog.ModuleEntry e = ModuleCatalog.get(id);
                if (e != null && e.getCategory() == ModuleCatalog.Category.LLM) {
                    presetLlms.add(id);
                }
            }

            System.out.println();
            System.out.println(BOLD + "  LLM Provider:" + RESET);
            System.out.println();
            System.out.println("  " + CYAN + "1" + RESET + "  Local (built-in)         " + GREEN + "No API key needed" + RESET + " — uses Claude Code / Codex / Gemini CLI");
            System.out.println("                                " + DIM + "Routes prompts through a locally installed coding CLI." + RESET);
            System.out.println("                                " + DIM + "Just install one (e.g. `npm i -g @anthropic-ai/claude-code`) and log in." + RESET);
            System.out.println("  " + CYAN + "2" + RESET + "  OpenAI                   " + DIM + "Hosted — requires API key" + RESET);
            System.out.println("  " + CYAN + "3" + RESET + "  Anthropic                " + DIM + "Hosted — requires API key" + RESET);
            System.out.println("  " + CYAN + "4" + RESET + "  Google Gemini             " + DIM + "Hosted — requires API key & GCP project" + RESET);
            System.out.println("  " + CYAN + "5" + RESET + "  None                     " + DIM + "Embedding + retrieval only, no LLM generation" + RESET);
            System.out.println("  " + CYAN + "6" + RESET + "  Multiple                 " + DIM + "Pick several providers" + RESET);
            System.out.println();

            // Show default from preset — Local (CLI Agent) is option 1 and the default
            String llmDefault = "1";
            String llmDefaultLabel = "Local (built-in)";
            if (presetLlms.contains("llm-openai") && presetLlms.size() == 1) {
                llmDefault = "2"; llmDefaultLabel = "OpenAI";
            } else if (presetLlms.contains("llm-anthropic") && presetLlms.size() == 1) {
                llmDefault = "3"; llmDefaultLabel = "Anthropic";
            } else if (presetLlms.contains("llm-gemini") && presetLlms.size() == 1) {
                llmDefault = "4"; llmDefaultLabel = "Gemini";
            } else if (presetLlms.isEmpty()) {
                llmDefault = "5"; llmDefaultLabel = "None";
            } else if (presetLlms.size() > 1) {
                llmDefault = "6"; llmDefaultLabel = "Multiple";
            }

            Integer llmChoice = promptNumber(reader,
                    "  Choice [1-6] " + DIM + "(default: " + llmDefault + " = " + llmDefaultLabel + ")" + RESET + ": ",
                    1, 6, Integer.parseInt(llmDefault));
            if (llmChoice == null) return null;

            // Clear all LLM modules, then add the user's choice
            for (ModuleCatalog.ModuleEntry e : ModuleCatalog.getByCategory(ModuleCatalog.Category.LLM)) {
                selectedModules.remove(e.getId());
            }
            switch (llmChoice) {
                case 1:
                    selectedModules.add("llm-cli-agent");
                    System.out.println("  → " + GREEN + "Local (built-in CLI agent)" + RESET);
                    break;
                case 2:
                    selectedModules.add("llm-openai");
                    System.out.println("  → " + GREEN + "OpenAI" + RESET);
                    break;
                case 3:
                    selectedModules.add("llm-anthropic");
                    System.out.println("  → " + GREEN + "Anthropic" + RESET);
                    break;
                case 4:
                    selectedModules.add("llm-gemini");
                    System.out.println("  → " + GREEN + "Google Gemini" + RESET);
                    break;
                case 5:
                    System.out.println("  → " + GREEN + "None (retrieval only)" + RESET);
                    break;
                case 6:
                    // Multi-select with toggles
                    Set<String> llmPicks = promptLlmMultiSelect(reader);
                    if (llmPicks == null) return null;
                    selectedModules.addAll(llmPicks);
                    System.out.println("  → " + GREEN + String.join(", ", llmPicks) + RESET);
                    break;
            }

            // ── Step 6: Embedding provider selection ────────────────────────
            Set<String> presetEmbeddings = new LinkedHashSet<>();
            for (String id : selectedModules) {
                ModuleCatalog.ModuleEntry e = ModuleCatalog.get(id);
                if (e != null && e.getCategory() == ModuleCatalog.Category.EMBEDDING) {
                    presetEmbeddings.add(id);
                }
            }

            System.out.println();
            System.out.println(BOLD + "  Embedding Provider:" + RESET);
            System.out.println();
            System.out.println("  " + CYAN + "1" + RESET + "  Anserini (SameDiff)      " + DIM + "Local — BGE / Arctic Embed models, no API key" + RESET);
            System.out.println("  " + CYAN + "2" + RESET + "  OpenAI                   " + DIM + "Hosted — requires API key" + RESET);
            System.out.println("  " + CYAN + "3" + RESET + "  Sentence Transformer     " + DIM + "Local — Python subprocess" + RESET);
            System.out.println("  " + CYAN + "4" + RESET + "  PostgresML               " + DIM + "In-database — requires PostgresML" + RESET);
            System.out.println("  " + CYAN + "5" + RESET + "  Multiple                 " + DIM + "Pick several providers" + RESET);
            System.out.println();

            String embDefault = "1";
            String embDefaultLabel = "Anserini (local)";
            if (presetEmbeddings.contains("embedding-openai") && !presetEmbeddings.contains("embedding-anserini")) {
                embDefault = "2"; embDefaultLabel = "OpenAI";
            } else if (presetEmbeddings.size() > 1) {
                embDefault = "5"; embDefaultLabel = "Multiple";
            }

            Integer embChoice = promptNumber(reader,
                    "  Choice [1-5] " + DIM + "(default: " + embDefault + " = " + embDefaultLabel + ")" + RESET + ": ",
                    1, 5, Integer.parseInt(embDefault));
            if (embChoice == null) return null;

            // Clear all embedding modules, then add the user's choice
            for (ModuleCatalog.ModuleEntry e : ModuleCatalog.getByCategory(ModuleCatalog.Category.EMBEDDING)) {
                selectedModules.remove(e.getId());
            }
            switch (embChoice) {
                case 1:
                    selectedModules.add("embedding-anserini");
                    System.out.println("  → " + GREEN + "Anserini (local SameDiff)" + RESET);
                    break;
                case 2:
                    selectedModules.add("embedding-openai");
                    System.out.println("  → " + GREEN + "OpenAI" + RESET);
                    break;
                case 3:
                    selectedModules.add("embedding-sentence-transformer");
                    System.out.println("  → " + GREEN + "Sentence Transformer" + RESET);
                    break;
                case 4:
                    selectedModules.add("embedding-postgresml");
                    System.out.println("  → " + GREEN + "PostgresML" + RESET);
                    break;
                case 5:
                    // Multi-select with toggles
                    Set<String> embPicks = promptEmbeddingMultiSelect(reader);
                    if (embPicks == null) return null;
                    selectedModules.addAll(embPicks);
                    System.out.println("  → " + GREEN + String.join(", ", embPicks) + RESET);
                    break;
            }

            // ── Step 7: Remaining module customization ──────────────────────
            System.out.println();
            boolean customize = promptYesNo(reader, "  Customize remaining modules (vector stores, loaders, tools, etc.)? [y/N]: ", false);
            if (customize) {
                selectedModules = customizeModules(reader, selectedModules);
                if (selectedModules == null) return null;
            }

            // ── Step 8: Compute backend (multi-select) ─────────────────────
            List<String> backends = promptBackends(reader);
            if (backends == null) return null;

            boolean cudaSelected = backends.stream().anyMatch(b -> b.startsWith("nd4j-cuda"));
            String javacppExtension = null;

            // AVX2 only when CPU is selected and no CUDA
            if (backends.contains("nd4j-native") && !cudaSelected) {
                System.out.println();
                boolean useAvx2 = promptYesNo(reader,
                        "  Enable AVX2 optimizations? " + DIM + "(recommended for modern x86 CPUs)" + RESET + " [Y/n]: ", true);
                if (useAvx2) {
                    javacppExtension = "avx2";
                }
            }

            // ── Step 9: Platform ────────────────────────────────────────────
            String platform = promptPlatform(reader, cudaSelected);
            if (platform == null) return null;

            // ── Step 10: Server port ────────────────────────────────────────
            System.out.println();
            int serverPort = promptPort(reader);

            // ── Step 11: Database config (conditional on pgvector) ───────────
            String databaseUrl = null;
            String databaseUsername = null;
            String databasePassword = null;
            boolean enableSchemaInit = true;

            if (selectedModules.contains("vectorstore-pgvector")) {
                System.out.println();
                System.out.println(BOLD + "  PostgreSQL Database Configuration " + DIM + "(required for pgvector)" + RESET);
                System.out.println();

                String dbUrlInput = prompt(reader,
                        "  Database URL " + DIM + "[Enter = jdbc:postgresql://localhost:5432/kompile_db]" + RESET + ": ");
                if (dbUrlInput == null) return null;
                databaseUrl = dbUrlInput.trim().isEmpty() ? "jdbc:postgresql://localhost:5432/kompile_db" : dbUrlInput.trim();

                String dbUser = prompt(reader,
                        "  Database username " + DIM + "[Enter = postgres]" + RESET + ": ");
                if (dbUser == null) return null;
                databaseUsername = dbUser.trim().isEmpty() ? "postgres" : dbUser.trim();

                String dbPass = prompt(reader,
                        "  Database password " + DIM + "[Enter = postgres]" + RESET + ": ");
                if (dbPass == null) return null;
                databasePassword = dbPass.trim().isEmpty() ? "postgres" : dbPass.trim();

                enableSchemaInit = promptYesNo(reader,
                        "  Auto-initialize database schema? [Y/n]: ", true);

                System.out.println("  → " + GREEN + "PostgreSQL configured" + RESET);
            }

            // ── Step 12: Language configuration ─────────────────────────────
            List<String> supportedLanguages = List.of("en");
            if (selectedModules.contains("chunker-sentence")) {
                System.out.println();
                System.out.println(BOLD + "  Language Configuration " + DIM + "(for OpenNLP sentence chunker)" + RESET);
                System.out.println();
                System.out.println("  Available: " + DIM + "en, de, fr, it, nl, pt, es, da, fi, no, sv, th, ca, cs, el, eu, gl, hu, pl, ro, sk, sl, sr, tl, uk" + RESET);
                System.out.println();

                String langInput = prompt(reader,
                        "  Languages " + DIM + "(comma-separated) [Enter = en]" + RESET + ": ");
                if (langInput == null) return null;
                if (!langInput.trim().isEmpty()) {
                    supportedLanguages = Arrays.stream(langInput.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toList());
                    if (supportedLanguages.isEmpty()) supportedLanguages = List.of("en");
                }
                System.out.println("  → " + GREEN + String.join(", ", supportedLanguages) + RESET);
            }

            // ── Step 13: Provider API keys ──────────────────────────────────
            String openaiApiKey = null;
            String anthropicApiKey = null;
            String geminiProjectId = null;

            boolean needsHostedLlm = selectedModules.contains("llm-openai")
                    || selectedModules.contains("llm-anthropic")
                    || selectedModules.contains("llm-gemini")
                    || selectedModules.contains("embedding-openai");

            boolean isLocalOnly = !needsHostedLlm;

            if (isLocalOnly) {
                // Tell the user they don't need API keys
                System.out.println();
                System.out.println("  " + GREEN + "No API keys needed!" + RESET);
                if (selectedModules.contains("llm-cli-agent")) {
                    System.out.println("  This project uses a CLI agent (Claude Code, Codex, or Gemini CLI) for LLM inference.");
                    System.out.println("  Make sure your preferred CLI tool is installed and authenticated.");
                } else if (selectedModules.contains("embedding-anserini")
                        && !selectedModules.contains("llm-openai")
                        && !selectedModules.contains("llm-anthropic")
                        && !selectedModules.contains("llm-gemini")) {
                    System.out.println("  This project uses local SameDiff embeddings — runs entirely offline.");
                }
            } else {
                System.out.println();
                System.out.println(BOLD + "  Provider Configuration " + DIM + "(optional — can be set later in application.properties)" + RESET);
                System.out.println();

                if (selectedModules.contains("llm-openai") || selectedModules.contains("embedding-openai")) {
                    openaiApiKey = prompt(reader, "  OpenAI API key " + DIM + "[Enter to skip]" + RESET + ": ");
                    if (openaiApiKey != null) openaiApiKey = openaiApiKey.trim();
                    if (openaiApiKey != null && openaiApiKey.isEmpty()) openaiApiKey = null;
                }

                if (selectedModules.contains("llm-anthropic")) {
                    anthropicApiKey = prompt(reader, "  Anthropic API key " + DIM + "[Enter to skip]" + RESET + ": ");
                    if (anthropicApiKey != null) anthropicApiKey = anthropicApiKey.trim();
                    if (anthropicApiKey != null && anthropicApiKey.isEmpty()) anthropicApiKey = null;
                }

                if (selectedModules.contains("llm-gemini")) {
                    geminiProjectId = prompt(reader, "  Google Cloud project ID " + DIM + "[Enter to skip]" + RESET + ": ");
                    if (geminiProjectId != null) geminiProjectId = geminiProjectId.trim();
                    if (geminiProjectId != null && geminiProjectId.isEmpty()) geminiProjectId = null;
                }
            }

            // ── Step 14: Build / start options ─────────────────────────────
            System.out.println();
            System.out.println(BOLD + "  Build Options:" + RESET);
            System.out.println();

            boolean noBuild = false;
            boolean startAfterBuild = false;

            System.out.println("  " + CYAN + "1" + RESET + "  Generate, build, and start the application");
            System.out.println("  " + CYAN + "2" + RESET + "  Generate and build only");
            System.out.println("  " + CYAN + "3" + RESET + "  Generate project files only (no build)");
            System.out.println();

            Integer buildChoice = promptNumber(reader, "  Choice [1-3] " + DIM + "(default: 2)" + RESET + ": ",
                    1, 3, 2);
            if (buildChoice == null) return null;

            switch (buildChoice) {
                case 1:
                    startAfterBuild = true;
                    break;
                case 2:
                    break;
                case 3:
                    noBuild = true;
                    break;
            }

            // ── Step 15: Data sources to crawl on first run ──────────────────
            List<String> dataSources = new ArrayList<>();
            int crawlDepth = 3;
            boolean crawlMultimodal = false;
            boolean crawlGraph = false;

            if (startAfterBuild) {
                System.out.println();
                System.out.println(BOLD + "  Initial Data Sources:" + RESET);
                System.out.println(DIM + "  Seed the index with content from URLs or directories on first startup." + RESET);
                System.out.println(DIM + "  You can always add more data later via the UI or `kompile ingest`." + RESET);
                System.out.println();

                boolean addSources = promptYesNo(reader,
                        "  Add data sources to index on startup? [y/N]: ", false);

                if (addSources) {
                    System.out.println();
                    System.out.println(DIM + "  Enter URLs or directory paths, one per line." + RESET);
                    System.out.println(DIM + "  Examples:  https://docs.example.com" + RESET);
                    System.out.println(DIM + "             /home/user/documents" + RESET);
                    System.out.println(DIM + "  Press Enter on a blank line when done." + RESET);
                    System.out.println();

                    while (true) {
                        String source = prompt(reader, "  Source " + (dataSources.size() + 1) + ": ");
                        if (source == null) return null;
                        source = source.trim();
                        if (source.isEmpty()) break;
                        dataSources.add(source);
                        System.out.println("  " + GREEN + "  Added: " + source + RESET);
                    }

                    if (!dataSources.isEmpty()) {
                        // Crawl depth
                        Integer depth = promptNumber(reader,
                                "  Crawl depth " + DIM + "(default: 3)" + RESET + ": ",
                                1, 20, 3);
                        if (depth != null) crawlDepth = depth;

                        // Multimodal
                        boolean hasDocumentContent = dataSources.stream().anyMatch(s ->
                                !s.startsWith("http://") && !s.startsWith("https://"));
                        String mmDefault = hasDocumentContent ? "Y/n" : "y/N";
                        crawlMultimodal = promptYesNo(reader,
                                "  Enable multimodal processing (VLM for PDFs/images)? [" + mmDefault + "]: ",
                                hasDocumentContent);

                        // Graph extraction — only if an LLM is configured
                        boolean hasLlm = selectedModules.contains("llm-openai")
                                || selectedModules.contains("llm-anthropic")
                                || selectedModules.contains("llm-gemini");
                        if (hasLlm) {
                            crawlGraph = promptYesNo(reader,
                                    "  Build knowledge graph from crawled content? [y/N]: ", false);
                        }
                    }
                }
            }

            // ── Step 16: Maven discovery ───────────────────────────────────
            File mavenHome = null;
            if (!noBuild) {
                File autoMaven = EnvironmentUtils.defaultMavenHome();
                if (autoMaven != null) {
                    System.out.println();
                    System.out.println("  " + GREEN + "Maven found: " + RESET + autoMaven.getAbsolutePath());
                    mavenHome = autoMaven;
                } else {
                    System.out.println();
                    System.out.println("  " + YELLOW + "Maven not found automatically." + RESET);
                    System.out.println("  " + DIM + "Searched: ~/.kompile/mvn, $M2_HOME, PATH" + RESET);
                    System.out.println();

                    while (true) {
                        String mvnPath = prompt(reader,
                                "  Path to Maven installation " + DIM + "(e.g. /usr/share/maven)" + RESET + ": ");
                        if (mvnPath == null) return null;
                        mvnPath = mvnPath.trim();
                        if (mvnPath.isEmpty()) {
                            System.out.println("  " + YELLOW + "Maven is required for building. Switching to 'generate only' mode." + RESET);
                            noBuild = true;
                            startAfterBuild = false;
                            break;
                        }
                        File mvnDir = new File(mvnPath);
                        // Accept both the maven home dir and the bin/mvn path
                        if (mvnDir.getName().equals("mvn") && mvnDir.isFile()) {
                            mvnDir = mvnDir.getParentFile().getParentFile(); // bin/mvn -> maven home
                        }
                        if (mvnDir.isDirectory() && (new File(mvnDir, "bin/mvn").exists()
                                || new File(mvnDir, "bin/mvn.cmd").exists())) {
                            mavenHome = mvnDir;
                            System.out.println("  → " + GREEN + "Maven: " + mavenHome.getAbsolutePath() + RESET);
                            break;
                        }
                        System.out.println("  " + YELLOW + "Not a valid Maven directory (expected bin/mvn inside). Try again or press Enter to skip." + RESET);
                    }
                }
            }

            // ── Summary ─────────────────────────────────────────────────────
            System.out.println();
            System.out.println(BOLD + CYAN + "  ╭──────────────────────────────────────╮" + RESET);
            System.out.println(BOLD + CYAN + "  │         Project Summary              │" + RESET);
            System.out.println(BOLD + CYAN + "  ╰──────────────────────────────────────╯" + RESET);
            System.out.println();
            System.out.println("  Project:     " + BOLD + projectName + RESET);
            System.out.println("  Title:       " + BOLD + appTitle + RESET);
            System.out.println("  Location:    " + BOLD + projectDir.getAbsolutePath() + RESET);
            if (selectedPreset != null) {
                System.out.println("  Preset:      " + BOLD + selectedPreset.name().toLowerCase().replace("_", "-") + RESET);
            } else {
                System.out.println("  Preset:      " + BOLD + "custom" + RESET);
            }
            System.out.println("  Backend:     " + BOLD + String.join(", ", backends) + RESET
                    + (javacppExtension != null ? " (" + javacppExtension + ")" : ""));
            System.out.println("  Platform:    " + BOLD + platform + RESET);
            System.out.println("  Port:        " + BOLD + serverPort + RESET);

            if (mavenHome != null) {
                System.out.println("  Maven:       " + BOLD + mavenHome.getAbsolutePath() + RESET);
            }

            if (supportedLanguages.size() > 1 || !supportedLanguages.get(0).equals("en")) {
                System.out.println("  Languages:   " + BOLD + String.join(", ", supportedLanguages) + RESET);
            }

            if (databaseUrl != null) {
                System.out.println("  Database:    " + BOLD + databaseUrl + RESET);
            }

            String buildAction = noBuild ? "generate only" : (startAfterBuild ? "build + start" : "build");
            System.out.println("  Action:      " + BOLD + buildAction + RESET);

            // Provider status
            if (isLocalOnly) {
                System.out.println("  LLM:         " + GREEN + "local (no API keys)" + RESET);
            } else {
                if (openaiApiKey != null) {
                    System.out.println("  OpenAI:      " + GREEN + "configured" + RESET);
                }
                if (anthropicApiKey != null) {
                    System.out.println("  Anthropic:   " + GREEN + "configured" + RESET);
                }
                if (geminiProjectId != null) {
                    System.out.println("  Gemini:      " + GREEN + "configured" + RESET);
                }
                // Show which providers still need keys
                List<String> unconfigured = new ArrayList<>();
                if (selectedModules.contains("llm-openai") && openaiApiKey == null) unconfigured.add("OpenAI");
                if (selectedModules.contains("embedding-openai") && openaiApiKey == null && !unconfigured.contains("OpenAI")) unconfigured.add("OpenAI");
                if (selectedModules.contains("llm-anthropic") && anthropicApiKey == null) unconfigured.add("Anthropic");
                if (selectedModules.contains("llm-gemini") && geminiProjectId == null) unconfigured.add("Gemini");
                if (!unconfigured.isEmpty()) {
                    System.out.println("  " + YELLOW + "Note: " + String.join(", ", unconfigured)
                            + " API key(s) needed — set in application.properties after generation" + RESET);
                }
            }

            // Data sources
            if (!dataSources.isEmpty()) {
                System.out.println("  Data sources (" + dataSources.size() + "):");
                for (String src : dataSources) {
                    System.out.println("    " + BOLD + src + RESET);
                }
                System.out.println("  Crawl depth: " + BOLD + crawlDepth + RESET
                        + (crawlMultimodal ? "  " + GREEN + "[multimodal]" + RESET : "")
                        + (crawlGraph ? "  " + GREEN + "[graph]" + RESET : ""));
            }

            System.out.println();
            printModuleSummary(selectedModules);

            System.out.println();
            boolean proceed = promptYesNo(reader, "  Create this project? [Y/n]: ", true);
            if (!proceed) {
                System.out.println("  " + YELLOW + "Cancelled." + RESET);
                return null;
            }

            System.out.println();
            return new WizardResult(projectName, outputDir, selectedPreset, selectedModules,
                    serverPort, platform, startAfterBuild, noBuild,
                    backends, javacppExtension,
                    appTitle,
                    databaseUrl, databaseUsername, databasePassword, enableSchemaInit,
                    openaiApiKey, anthropicApiKey, geminiProjectId,
                    supportedLanguages,
                    mavenHome,
                    dataSources, crawlDepth, crawlMultimodal, crawlGraph);

        } catch (Exception e) {
            System.err.println("Wizard error: " + e.getMessage());
            return null;
        }
    }

    // ── Preset explanations ────────────────────────────────────────────────

    private static String getPresetApiNote(BuildPreset preset) {
        switch (preset) {
            case HOSTED_LLM_RAG:
            case MINIMAL:
                return "  " + YELLOW + "[needs API key]" + RESET;
            case SAMEDIFF_RAG:
            case LITE:
                return "  " + GREEN + "[local, no API key]" + RESET;
            case CLI_AGENT_RAG:
                return "  " + GREEN + "[uses local CLI tool]" + RESET;
            case FULL:
                return "  " + YELLOW + "[API keys optional]" + RESET;
            case PIPELINE:
                return "  " + DIM + "[no web app]" + RESET;
            default:
                return "";
        }
    }

    private static void printPresetExplanation(BuildPreset preset) {
        System.out.println();
        switch (preset) {
            case HOSTED_LLM_RAG:
                System.out.println("  " + DIM + "Uses OpenAI as the LLM provider with local Anserini embeddings and vector search." + RESET);
                System.out.println("  " + DIM + "You will need an OpenAI API key to use LLM features." + RESET);
                break;
            case SAMEDIFF_RAG:
                System.out.println("  " + DIM + "Runs entirely locally using SameDiff for embeddings. No hosted LLM included." + RESET);
                System.out.println("  " + DIM + "Use this for embedding + retrieval without LLM generation, or add an LLM module later." + RESET);
                System.out.println("  " + GREEN + "  No API keys required." + RESET);
                break;
            case CLI_AGENT_RAG:
                System.out.println("  " + DIM + "Uses a locally installed CLI tool (Claude Code, Codex CLI, or Gemini CLI) as the LLM." + RESET);
                System.out.println("  " + DIM + "Prompts are routed through the CLI agent subprocess." + RESET);
                System.out.println("  " + GREEN + "  No API keys required — just ensure your CLI tool is installed and logged in." + RESET);
                break;
            case FULL:
                System.out.println("  " + DIM + "Includes all available LLM providers, embeddings, vector stores, loaders, and tools." + RESET);
                System.out.println("  " + DIM + "Configure whichever providers you want in application.properties." + RESET);
                break;
            case MINIMAL:
                System.out.println("  " + DIM + "Minimal setup with OpenAI for both LLM and embeddings." + RESET);
                System.out.println("  " + DIM + "You will need an OpenAI API key." + RESET);
                break;
            case LITE:
                System.out.println("  " + DIM + "Self-contained chat + RAG + Graph RAG with local embeddings." + RESET);
                System.out.println("  " + DIM + "No MCP, no pipelines. Lightweight and self-contained." + RESET);
                System.out.println("  " + GREEN + "  No API keys required." + RESET);
                break;
            case PIPELINE:
                System.out.println("  " + DIM + "Pipeline executor only — no web application or RAG modules." + RESET);
                System.out.println("  " + DIM + "Use for batch ML pipeline execution." + RESET);
                break;
        }
    }

    // ── Module customization ────────────────────────────────────────────────

    private static Set<String> customizeModules(LineReader reader, Set<String> current) {
        Set<String> modules = new LinkedHashSet<>(current);
        boolean hasAppMain = modules.contains("app-main");

        for (ModuleCatalog.Category category : CUSTOMIZABLE_CATEGORIES) {
            List<ModuleCatalog.ModuleEntry> allInCategory = ModuleCatalog.getByCategory(category);
            if (allInCategory.isEmpty()) continue;

            // Split into bundled (always included with app-main) and add-ons (toggleable)
            List<ModuleCatalog.ModuleEntry> bundled = new ArrayList<>();
            List<ModuleCatalog.ModuleEntry> addOns = new ArrayList<>();
            for (ModuleCatalog.ModuleEntry entry : allInCategory) {
                if (entry.isBundled() && hasAppMain) {
                    bundled.add(entry);
                } else {
                    addOns.add(entry);
                }
            }

            System.out.println();
            System.out.println(BOLD + "  " + formatCategoryName(category) + ":" + RESET);

            // Show bundled modules as always-included (informational, not toggleable)
            if (!bundled.isEmpty()) {
                System.out.println();
                System.out.println("  " + DIM + "Included with Full Application:" + RESET);
                for (ModuleCatalog.ModuleEntry entry : bundled) {
                    System.out.printf("  " + GREEN + " ✓ " + RESET + " %-30s %s%n",
                            entry.getDisplayName(), DIM + entry.getDescription() + RESET);
                }
            }

            // Show add-on modules as toggleable
            if (!addOns.isEmpty()) {
                System.out.println();
                if (!bundled.isEmpty()) {
                    System.out.println("  " + DIM + "Optional add-ons:" + RESET);
                }

                printCategoryModules(addOns, modules);

                System.out.println();
                System.out.println("  " + DIM + "Toggle by number (e.g., 1 3), 'a' = all, 'n' = none, Enter = continue" + RESET);

                while (true) {
                    String input = prompt(reader, "  Toggle: ");
                    if (input == null) return null;
                    String trimmed = input.trim();
                    if (trimmed.isEmpty()) break;

                    if (trimmed.equalsIgnoreCase("a")) {
                        for (ModuleCatalog.ModuleEntry entry : addOns) {
                            modules.add(entry.getId());
                        }
                    } else if (trimmed.equalsIgnoreCase("n")) {
                        for (ModuleCatalog.ModuleEntry entry : addOns) {
                            modules.remove(entry.getId());
                        }
                    } else {
                        String[] parts = trimmed.split("[\\s,]+");
                        boolean anyInvalid = false;
                        for (String part : parts) {
                            try {
                                int num = Integer.parseInt(part);
                                if (num >= 1 && num <= addOns.size()) {
                                    String id = addOns.get(num - 1).getId();
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
                            System.out.println("  " + YELLOW + "Enter numbers 1-" + addOns.size()
                                    + ", 'a' for all, 'n' for none" + RESET);
                        }
                    }

                    // Redisplay after toggling
                    printCategoryModules(addOns, modules);
                    System.out.println();
                }
            } else if (bundled.isEmpty()) {
                System.out.println("  " + DIM + "(no modules in this category)" + RESET);
            }
        }

        return modules;
    }

    private static void printCategoryModules(List<ModuleCatalog.ModuleEntry> available, Set<String> selected) {
        for (int i = 0; i < available.size(); i++) {
            ModuleCatalog.ModuleEntry entry = available.get(i);
            boolean isSelected = selected.contains(entry.getId());
            String marker = isSelected ? GREEN + "[*]" + RESET : "[ ]";
            System.out.printf("  %s " + CYAN + "%d" + RESET + "  %-30s %s%n",
                    marker, i + 1, entry.getDisplayName(), DIM + entry.getDescription() + RESET);
        }
    }

    // ── Multi-select helpers for LLM / Embedding ─────────────────────────

    private static Set<String> promptLlmMultiSelect(LineReader reader) {
        String[][] options = {
                {"llm-cli-agent",  "Local (built-in CLI agent)"},
                {"llm-openai",     "OpenAI"},
                {"llm-anthropic",  "Anthropic"},
                {"llm-gemini",     "Google Gemini"},
        };
        return promptMultiSelect(reader, options, "LLM");
    }

    private static Set<String> promptEmbeddingMultiSelect(LineReader reader) {
        String[][] options = {
                {"embedding-anserini",              "Anserini (local SameDiff)"},
                {"embedding-openai",                "OpenAI"},
                {"embedding-sentence-transformer",  "Sentence Transformer (local Python)"},
                {"embedding-postgresml",            "PostgresML"},
        };
        return promptMultiSelect(reader, options, "Embedding");
    }

    private static Set<String> promptMultiSelect(LineReader reader, String[][] options, String label) {
        Set<String> selected = new LinkedHashSet<>();
        System.out.println();
        System.out.println("  " + DIM + "Toggle by number, Enter when done:" + RESET);
        printMultiSelectState(options, selected);
        System.out.println();

        while (true) {
            String input = prompt(reader, "  Toggle: ");
            if (input == null) return null;
            String trimmed = input.trim();
            if (trimmed.isEmpty()) break;

            try {
                int num = Integer.parseInt(trimmed);
                if (num >= 1 && num <= options.length) {
                    String id = options[num - 1][0];
                    if (selected.contains(id)) selected.remove(id);
                    else selected.add(id);
                }
            } catch (NumberFormatException ignored) {
            }
            printMultiSelectState(options, selected);
            System.out.println();
        }

        if (selected.isEmpty()) {
            System.out.println("  " + YELLOW + "No " + label + " providers selected." + RESET);
        }
        return selected;
    }

    private static void printMultiSelectState(String[][] options, Set<String> selected) {
        for (int i = 0; i < options.length; i++) {
            boolean on = selected.contains(options[i][0]);
            String marker = on ? GREEN + "[*]" + RESET : "[ ]";
            System.out.printf("  %s " + CYAN + "%d" + RESET + "  %s%n", marker, i + 1, options[i][1]);
        }
    }

    // ── Compute backends ─────────────────────────────────────────────────────

    /**
     * All known ND4J backends. Order matters: display order in wizard.
     * [0]=artifactId, [1]=display name, [2]=description, [3]="true" if experimental
     */
    private static final String[][] ALL_BACKENDS = {
            {"nd4j-native",     "CPU",                "Works everywhere, no GPU required",                              "false"},
            {"nd4j-cuda-13.1",  "NVIDIA CUDA 13.1",   "NVIDIA GPU acceleration (CUDA 13.1 + cuDNN)",                   "false"},
            {"nd4j-cuda-12.9",  "NVIDIA CUDA 12.9",   "NVIDIA GPU acceleration (CUDA 12.9 + cuDNN 9.10)",              "false"},
            {"nd4j-minimizer",  "Minimizer",           "Minimal CPU backend with reduced op set",                       "true"},
            {"nd4j-zluda",      "ZLUDA",               "AMD/Intel GPU via CUDA transpiler",                             "true"},
            {"nd4j-hexagon",    "Hexagon NPU",         "Qualcomm Hexagon NPU via hexagon-mlir",                         "true"},
            {"nd4j-tpu",        "Google TPU",          "Google TPU via PJRT (Portable Runtime)",                         "true"},
    };

    private static List<String> promptBackends(LineReader reader) {
        Set<String> selected = new LinkedHashSet<>();
        selected.add("nd4j-native"); // CPU pre-selected by default

        System.out.println();
        System.out.println(BOLD + "  Compute Backend(s):" + RESET);
        System.out.println("  " + DIM + "Select one or more. CPU is pre-selected. Toggle by number, Enter when done." + RESET);
        System.out.println();

        printBackendState(selected);
        System.out.println();

        while (true) {
            String input = prompt(reader, "  Toggle: ");
            if (input == null) return null;
            String trimmed = input.trim();
            if (trimmed.isEmpty()) break;

            String[] parts = trimmed.split("[\\s,]+");
            for (String part : parts) {
                try {
                    int num = Integer.parseInt(part);
                    if (num >= 1 && num <= ALL_BACKENDS.length) {
                        String id = ALL_BACKENDS[num - 1][0];
                        if (selected.contains(id)) selected.remove(id);
                        else selected.add(id);
                    }
                } catch (NumberFormatException ignored) {
                }
            }

            printBackendState(selected);
            System.out.println();
        }

        if (selected.isEmpty()) {
            System.out.println("  " + YELLOW + "No backends selected — defaulting to CPU." + RESET);
            selected.add("nd4j-native");
        }

        List<String> result = new ArrayList<>(selected);
        System.out.println("  → " + GREEN + result.stream()
                .map(InitProjectWizard::backendDisplayName)
                .collect(Collectors.joining(", ")) + RESET);
        return result;
    }

    private static void printBackendState(Set<String> selected) {
        for (int i = 0; i < ALL_BACKENDS.length; i++) {
            boolean on = selected.contains(ALL_BACKENDS[i][0]);
            String marker = on ? GREEN + "[*]" + RESET : "[ ]";
            boolean experimental = "true".equals(ALL_BACKENDS[i][3]);
            String expTag = experimental ? " " + YELLOW + "[experimental]" + RESET : "";
            System.out.printf("  %s " + CYAN + "%d" + RESET + "  %-25s %s%s%n",
                    marker, i + 1, ALL_BACKENDS[i][1], DIM + ALL_BACKENDS[i][2] + RESET, expTag);
        }
    }

    private static String backendDisplayName(String artifactId) {
        for (String[] entry : ALL_BACKENDS) {
            if (entry[0].equals(artifactId)) return entry[1];
        }
        return artifactId;
    }

    // ── Platform ────────────────────────────────────────────────────────────

    /** Platforms that support CUDA GPU acceleration. */
    private static final Set<String> CUDA_PLATFORMS = Set.of(
            "linux-x86_64", "linux-arm64", "windows-x86_64"
    );

    private static String promptPlatform(LineReader reader, boolean cudaSelected) {
        String[] allPlatforms = {
                "linux-x86_64", "linux-arm64", "linux-ppc64le",
                "macosx-arm64", "macosx-x86_64",
                "windows-x86_64", "windows-arm64"
        };

        // If CUDA is selected, filter to CUDA-compatible platforms only
        String[] platforms;
        if (cudaSelected) {
            platforms = Arrays.stream(allPlatforms)
                    .filter(CUDA_PLATFORMS::contains)
                    .toArray(String[]::new);
        } else {
            platforms = allPlatforms;
        }

        // Auto-detect current platform
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        String detectedPlatform;
        if (os.contains("mac")) {
            detectedPlatform = (arch.contains("aarch64") || arch.contains("arm")) ? "macosx-arm64" : "macosx-x86_64";
        } else if (os.contains("win")) {
            detectedPlatform = arch.contains("aarch64") ? "windows-arm64" : "windows-x86_64";
        } else {
            // Linux or other
            if (arch.contains("aarch64") || arch.contains("arm64")) {
                detectedPlatform = "linux-arm64";
            } else if (arch.contains("ppc64")) {
                detectedPlatform = "linux-ppc64le";
            } else {
                detectedPlatform = "linux-x86_64";
            }
        }

        // Find detected platform index in the filtered list
        int detected = 0;
        for (int i = 0; i < platforms.length; i++) {
            if (platforms[i].equals(detectedPlatform)) {
                detected = i;
                break;
            }
        }

        System.out.println();
        System.out.println(BOLD + "  Target platform:" + RESET);
        if (cudaSelected) {
            System.out.println("  " + DIM + "(CUDA selected — showing only GPU-compatible platforms)" + RESET);
        }
        System.out.println();
        for (int i = 0; i < platforms.length; i++) {
            String tag = (i == detected) ? " " + GREEN + "(detected)" + RESET : "";
            System.out.printf("  " + CYAN + "%d" + RESET + "  %s%s%n", i + 1, platforms[i], tag);
        }
        System.out.println();

        String input = prompt(reader, "  Choice [1-" + platforms.length + "] "
                + DIM + "(Enter for " + platforms[detected] + ")" + RESET + ": ");
        if (input == null) return null;
        if (input.trim().isEmpty()) return platforms[detected];

        try {
            int choice = Integer.parseInt(input.trim());
            if (choice >= 1 && choice <= platforms.length) {
                return platforms[choice - 1];
            }
        } catch (NumberFormatException ignored) {
        }

        // Accept raw platform string but validate against CUDA if needed
        String raw = input.trim();
        if (cudaSelected && !CUDA_PLATFORMS.contains(raw)) {
            System.out.println("  " + RED + "CUDA is not supported on " + raw + ". "
                    + "Supported CUDA platforms: " + String.join(", ", CUDA_PLATFORMS) + RESET);
            System.out.println("  " + YELLOW + "Falling back to " + platforms[detected] + RESET);
            return platforms[detected];
        }
        return raw;
    }

    // ── Port ────────────────────────────────────────────────────────────────

    private static int promptPort(LineReader reader) {
        while (true) {
            String input = prompt(reader,
                    "  Server port " + DIM + "[Enter = 8080]" + RESET + ": ");
            if (input == null || input.trim().isEmpty()) return 8080;
            try {
                int port = Integer.parseInt(input.trim());
                if (port >= 1 && port <= 65535) return port;
                System.out.println("  " + YELLOW + "Port must be 1-65535" + RESET);
            } catch (NumberFormatException e) {
                System.out.println("  " + YELLOW + "Please enter a valid port number" + RESET);
            }
        }
    }

    // ── Summary ─────────────────────────────────────────────────────────────

    private static void printModuleSummary(Set<String> moduleIds) {
        // Count only add-on modules for the summary — bundled modules come with app-main
        List<String> addOnNames = new ArrayList<>();
        for (String id : moduleIds) {
            ModuleCatalog.ModuleEntry e = ModuleCatalog.get(id);
            if (e != null && !e.isBundled()) {
                addOnNames.add(e.getDisplayName());
            }
        }

        if (addOnNames.isEmpty()) {
            System.out.println("  " + BOLD + "Add-on modules: " + RESET + DIM + "none (using bundled defaults)" + RESET);
        } else {
            System.out.println("  " + BOLD + "Add-on modules (" + addOnNames.size() + "):" + RESET);
            for (ModuleCatalog.Category cat : ModuleCatalog.Category.values()) {
                List<String> catNames = moduleIds.stream()
                        .map(ModuleCatalog::get)
                        .filter(e -> e != null && e.getCategory() == cat && !e.isBundled())
                        .map(ModuleCatalog.ModuleEntry::getDisplayName)
                        .collect(Collectors.toList());
                if (!catNames.isEmpty()) {
                    System.out.println("    " + WHITE + formatCategoryName(cat) + RESET + ": " + String.join(", ", catNames));
                }
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

    // ── Input helpers ───────────────────────────────────────────────────────

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
        return promptNumber(reader, prompt, min, max, null);
    }

    private static Integer promptNumber(LineReader reader, String prompt, int min, int max, Integer defaultValue) {
        while (true) {
            String input = prompt(reader, prompt);
            if (input == null) return null;
            if (input.trim().isEmpty() && defaultValue != null) return defaultValue;
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

    // ── Banner ──────────────────────────────────────────────────────────────

    private static void printBanner() {
        System.out.println();
        System.out.println(BOLD + CYAN + "  ╭──────────────────────────────────────╮" + RESET);
        System.out.println(BOLD + CYAN + "  │     Kompile Project Wizard           │" + RESET);
        System.out.println(BOLD + CYAN + "  ╰──────────────────────────────────────╯" + RESET);
        System.out.println();
        System.out.println("  Create a new self-contained kompile project step by step.");
        System.out.println("  " + DIM + "(Press Ctrl+C or enter empty input to cancel)" + RESET);
        System.out.println();
    }
}
