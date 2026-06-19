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

package ai.kompile.cli.main.configure;

import ai.kompile.cli.main.GlobalBootstrap;
import ai.kompile.cli.main.Info;
import ai.kompile.cli.main.chat.McpUrlResolver;
import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.SetupWizard;
import ai.kompile.cli.main.chat.enforcer.EnforcerConfig;
import ai.kompile.cli.main.chat.enforcer.EnforcerSetupWizard;
import ai.kompile.cli.main.chat.harness.HarnessConfig;
import ai.kompile.cli.main.codeindex.CodeIndexCommand;
import ai.kompile.cli.main.codeindex.LocalCodeIndexer;
import ai.kompile.cli.main.config.AppConfigWizard;
import ai.kompile.cli.main.config.ToolGatewayWizard;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

@Command(name = "configure",
        mixinStandardHelpOptions = true,
        description = "Guided setup for Kompile features and agent workflows.",
        subcommands = {
                CommandLine.HelpCommand.class,
                ConfigureCommand.InitConfigureCommand.class,
                ConfigureCommand.AppConfigureCommand.class,
                ConfigureCommand.ChatConfigureCommand.class,
                ConfigureCommand.PassthroughConfigureCommand.class,
                ConfigureCommand.EnforcerConfigureCommand.class,
                ConfigureCommand.JudgeConfigureCommand.class,
                ConfigureCommand.CodeIndexConfigureCommand.class,
                ConfigureCommand.McpConfigureCommand.class,
                ConfigureCommand.GatewayConfigureCommand.class
        })
public class ConfigureCommand implements Callable<Integer> {

    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String CYAN = "\033[36m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";

    @Option(names = "--status", description = "Show configuration status and exit")
    boolean statusOnly;

    @Override
    public Integer call() {
        if (statusOnly) {
            printStatus(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize());
            return 0;
        }
        return runMainWizard();
    }

    private int runMainWizard() {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            Path workingDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

            while (true) {
                printHeader("Kompile Configure");
                printStatus(workingDir);
                System.out.println(BOLD + "  What do you want to configure?" + RESET);
                System.out.println();
                List<String> items = List.of(
                        "Initialize global ~/.kompile home and default configs",
                        "Application config: vector store, embeddings, ingestion, feature flags",
                        "Chat session intent: choose mode, provider, and agent for this run",
                        "Passthrough prep: inspect agents and MCP/tool injection guidance",
                        "Enforcer: project rules, banned tools, judge mode, semantic matching",
                        "Judge/performance harness: judge backend and quality scoring",
                        "Code indexing: index this or another source tree",
                        "MCP: choose profile/schema and generate launch settings",
                        "Tool gateway: LLM-based tool evaluation rules",
                        "Show status only"
                );
                int choice = selectNumbered(reader, items, true);
                if (choice < 0 || choice == 0) return 0;
                int result = switch (choice) {
                    case 1 -> new InitConfigureCommand().call();
                    case 2 -> new AppConfigureCommand().call();
                    case 3 -> new ChatConfigureCommand().call();
                    case 4 -> new PassthroughConfigureCommand().call();
                    case 5 -> new EnforcerConfigureCommand().call();
                    case 6 -> new JudgeConfigureCommand().call();
                    case 7 -> new CodeIndexConfigureCommand().call();
                    case 8 -> new McpConfigureCommand().call();
                    case 9 -> new GatewayConfigureCommand().call();
                    case 10 -> { printStatus(workingDir); yield 0; }
                    default -> 0;
                };
                if (result != 0) return result;
                prompt(reader, "  Press Enter to continue...");
            }
        } catch (Exception e) {
            System.err.println("Configure failed: " + e.getMessage());
            return 1;
        }
    }

    private static void printStatus(Path workingDir) {
        File home = Info.homeDirectory();
        System.out.println(BOLD + "  Current status" + RESET);
        System.out.println("    Home:       " + status(Files.isDirectory(home.toPath())) + " " + home.getAbsolutePath());
        System.out.println("    App config: " + status(Files.isRegularFile(home.toPath().resolve("config").resolve(AppConfigWizard.APP_INDEX_CONFIG))));
        System.out.println("    Chat:       " + status(ChatConfig.exists()) + " " + home.toPath().resolve("chat-config.json"));
        System.out.println("    Enforcer:   " + status(EnforcerConfig.exists(workingDir)) + " " + EnforcerConfig.resolveConfigPath(workingDir));
        System.out.println("    Judge:      " + status(Files.isRegularFile(HarnessConfig.getConfigFilePath())) + " " + HarnessConfig.getConfigFilePath());
        System.out.println("    Code index: " + status(Files.isDirectory(LocalCodeIndexer.getBaseIndexDir())) + " " + LocalCodeIndexer.getBaseIndexDir());
        System.out.println("    Agents:     " + detectedAgentsSummary());
        System.out.println();
    }

    private static String status(boolean ok) {
        return ok ? GREEN + "configured" + RESET : YELLOW + "not configured" + RESET;
    }

    private static String detectedAgentsSummary() {
        List<String> found = new ArrayList<>();
        for (String agent : ChatConfig.getPassthroughAgentOrder()) {
            if (SubprocessAgentRunner.resolveAgentBinary(agent) != null) found.add(agent);
        }
        return found.isEmpty() ? YELLOW + "none detected" + RESET : GREEN + String.join(", ", found) + RESET;
    }

    private static void printHeader(String title) {
        System.out.println();
        System.out.println(BOLD + CYAN + "  " + title + RESET);
        System.out.println(BOLD + CYAN + "  " + "=".repeat(title.length()) + RESET);
        System.out.println();
    }

    private static int selectNumbered(LineReader reader, List<String> items, boolean includeExit) {
        for (int i = 0; i < items.size(); i++) {
            System.out.printf("  " + CYAN + "%2d" + RESET + "  %s%n", i + 1, items.get(i));
        }
        if (includeExit) {
            System.out.println("  " + CYAN + " 0" + RESET + "  Exit");
        }
        System.out.println();
        while (true) {
            String input = prompt(reader, includeExit ? "  Choice (0-" + items.size() + "): " : "  Choice (1-" + items.size() + "): ");
            if (input == null) return -1;
            String trimmed = input.trim();
            if (trimmed.equalsIgnoreCase("q") || trimmed.equalsIgnoreCase("quit")) return -1;
            try {
                int n = Integer.parseInt(trimmed);
                if (includeExit && n == 0) return 0;
                if (n >= 1 && n <= items.size()) return n;
            } catch (NumberFormatException ignored) {
                String lower = trimmed.toLowerCase(Locale.ROOT);
                for (int i = 0; i < items.size(); i++) {
                    if (items.get(i).toLowerCase(Locale.ROOT).contains(lower)) return i + 1;
                }
            }
            System.out.println("  " + YELLOW + "Enter a valid number, or q to quit." + RESET);
        }
    }

    private static String prompt(LineReader reader, String message) {
        try {
            return reader.readLine(message);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean confirm(LineReader reader, String message, boolean defaultValue) {
        String suffix = defaultValue ? " [Y/n]: " : " [y/N]: ";
        String input = prompt(reader, message + suffix);
        if (input == null || input.isBlank()) return defaultValue;
        return input.trim().toLowerCase(Locale.ROOT).startsWith("y");
    }

    @Command(name = "init", mixinStandardHelpOptions = true,
            description = "Initialize ~/.kompile and write missing default config files.")
    public static class InitConfigureCommand implements Callable<Integer> {
        @Option(names = "--app-wizard", description = "Run app configuration wizard after bootstrapping")
        boolean appWizard;

        @Override
        public Integer call() {
            System.out.println("Initializing Kompile home...");
            GlobalBootstrap.ensureHomeDirectory();
            boolean wrote = GlobalBootstrap.ensureConfigs();
            System.out.println(wrote ? "Default configs written." : "Default configs already exist.");
            if (appWizard) AppConfigWizard.run();
            return 0;
        }
    }

    @Command(name = "app", mixinStandardHelpOptions = true,
            description = "Run the application configuration wizard.")
    public static class AppConfigureCommand implements Callable<Integer> {
        @Override
        public Integer call() {
            AppConfigWizard.run();
            return 0;
        }
    }

    @Command(name = "chat", mixinStandardHelpOptions = true,
            description = "Choose chat intent, provider, and agent. No implicit chat defaults are used.")
    public static class ChatConfigureCommand implements Callable<Integer> {
        @Override
        public Integer call() {
            System.out.println();
            System.out.println(DIM + "Chat is task-specific. This wizard asks for the session mode and agent/provider each time." + RESET);
            ChatConfig config = SetupWizard.run();
            return config != null ? 0 : 1;
        }
    }

    @Command(name = "passthrough", mixinStandardHelpOptions = true,
            description = "Inspect CLI agents and prepare passthrough/MCP usage guidance.")
    public static class PassthroughConfigureCommand implements Callable<Integer> {
        @Override
        public Integer call() {
            try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
                LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
                printHeader("Passthrough Setup");
                List<String> agents = availableAgents();
                if (agents.isEmpty()) {
                    System.out.println(YELLOW + "  No supported CLI agents were detected on PATH." + RESET);
                    System.out.println("  Supported agents: " + String.join(", ", ChatConfig.getPassthroughAgentOrder()));
                    return 1;
                }
                int idx = selectNumbered(reader, agents, false);
                if (idx < 0) return 1;
                String agent = agents.get(idx - 1);
                System.out.println();
                System.out.println("Use passthrough directly with:");
                System.out.println("  kompile passthrough --agent " + agent + " --working-dir <project>");
                System.out.println();
                System.out.println("Use chat managed passthrough with:");
                System.out.println("  kompile chat --mode passthrough --agent " + agent);
                System.out.println();
                if (confirm(reader, "  Configure MCP launch settings for this agent now?", true)) {
                    McpConfigureCommand cmd = new McpConfigureCommand();
                    cmd.agent = agent;
                    return cmd.call();
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Passthrough setup failed: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "enforcer", mixinStandardHelpOptions = true,
            description = "Run the project enforcer setup wizard.")
    public static class EnforcerConfigureCommand implements Callable<Integer> {
        @Option(names = {"--working-dir", "-d"}, description = "Project directory to configure", defaultValue = ".")
        String workingDir;

        @Option(names = {"--force", "-f"}, description = "Reconfigure even if an enforcer config already exists")
        boolean force;

        @Override
        public Integer call() {
            Path wd = Path.of(workingDir).toAbsolutePath().normalize();
            if (EnforcerConfig.exists(wd) && !force) {
                try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
                    LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
                    System.out.println("Existing enforcer config: " + EnforcerConfig.resolveConfigPath(wd));
                    if (!confirm(reader, "  Reconfigure and overwrite it?", false)) return 0;
                } catch (Exception e) {
                    System.err.println("Could not prompt for reconfiguration: " + e.getMessage());
                    return 1;
                }
            }
            return EnforcerSetupWizard.run(wd) != null ? 0 : 1;
        }
    }

    @Command(name = "judge", mixinStandardHelpOptions = true,
            description = "Configure judge backend and performance harness settings.")
    public static class JudgeConfigureCommand implements Callable<Integer> {
        @Override
        public Integer call() {
            try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
                LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
                printHeader("Judge Configuration");
                ObjectMapper mapper = JsonUtils.standardMapper();
                HarnessConfig config = HarnessConfig.load(mapper);

                List<String> modes = List.of(
                        "auto - prefer authenticated CLI agents, then servers/local/remote",
                        "cli - force an authenticated CLI agent judge",
                        "remote - use a cloud or OpenAI-compatible judge API",
                        "local - use in-process local model classes when available",
                        "auto-server - use a running kompile or Ollama server"
                );
                int selected = selectNumbered(reader, modes, false);
                if (selected < 0) return 1;
                String mode = switch (selected) {
                    case 1 -> "auto";
                    case 2 -> "cli";
                    case 3 -> "remote";
                    case 4 -> "local";
                    case 5 -> "auto-server";
                    default -> "auto";
                };
                config.setJudgeMode("auto".equals(mode) ? null : mode);

                String model = prompt(reader, "  Judge model (blank for backend default): ");
                config.setJudgeModel(model == null || model.isBlank() ? null : model.trim());

                if ("remote".equals(mode)) {
                    String provider = prompt(reader, "  Judge provider (anthropic/openai/gemini/ollama/kompile): ");
                    config.setJudgeProvider(provider == null || provider.isBlank() ? null : provider.trim());
                    String baseUrl = prompt(reader, "  Judge base URL (blank for provider default): ");
                    config.setJudgeBaseUrl(baseUrl == null || baseUrl.isBlank() ? null : baseUrl.trim());
                    String apiKey = prompt(reader, "  Judge API key (blank to use env/chat config): ");
                    config.setJudgeApiKey(apiKey == null || apiKey.isBlank() ? null : apiKey.trim());
                } else if ("local".equals(mode)) {
                    String localModel = prompt(reader, "  Local judge model ID/path: ");
                    config.setJudgeLocalModel(localModel == null || localModel.isBlank() ? null : localModel.trim());
                    String quant = prompt(reader, "  Local quantization (blank for default): ");
                    config.setJudgeLocalQuant(quant == null || quant.isBlank() ? null : quant.trim());
                } else if ("auto-server".equals(mode)) {
                    String serverType = prompt(reader, "  Server type (kompile/ollama, blank for kompile): ");
                    config.setJudgeServerType(serverType == null || serverType.isBlank() ? null : serverType.trim());
                    String port = prompt(reader, "  Server port (blank for default): ");
                    if (port != null && !port.isBlank()) config.setJudgeServerPort(Integer.parseInt(port.trim()));
                }

                config.setJudgeEnabled(confirm(reader, "  Enable judge scoring?", config.isJudgeEnabled()));
                config.setAutoSwapEnabled(confirm(reader, "  Enable model auto-swap on poor scores?", config.isAutoSwapEnabled()));
                config.save(mapper);
                System.out.println(GREEN + "  Judge config saved: " + HarnessConfig.getConfigFilePath() + RESET);
                return 0;
            } catch (Exception e) {
                System.err.println("Judge configuration failed: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "code-index", mixinStandardHelpOptions = true,
            description = "Guided local code indexing for a source tree.")
    public static class CodeIndexConfigureCommand implements Callable<Integer> {
        @Option(names = {"--directory", "-d"}, description = "Directory to index")
        String directory;

        @Option(names = {"--project", "-p"}, description = "Project identifier")
        String project;

        @Option(names = "--include", description = "Include patterns, comma-separated")
        String include;

        @Option(names = "--exclude", description = "Exclude patterns, comma-separated")
        String exclude;

        @Option(names = {"--force", "-f"}, description = "Force a full re-index")
        boolean force;

        @Override
        public Integer call() {
            try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
                LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
                printHeader("Code Index Setup");
                String dir = valueOrPrompt(reader, directory, "  Directory to index (blank for current directory): ");
                if (dir == null || dir.isBlank()) dir = ".";
                Path root = Path.of(dir).toAbsolutePath().normalize();
                String projectId = valueOrPrompt(reader, project, "  Project id (blank for directory name): ");
                if (projectId == null || projectId.isBlank()) projectId = root.getFileName().toString();
                String includePatterns = valueOrPrompt(reader, include, "  Include patterns (blank for language defaults): ");
                String excludePatterns = valueOrPrompt(reader, exclude, "  Exclude patterns (blank for defaults): ");
                boolean doForce = force || confirm(reader, "  Force full re-index?", false);

                List<String> args = new ArrayList<>();
                args.add(root.toString());
                args.add("--project");
                args.add(projectId);
                if (includePatterns != null && !includePatterns.isBlank()) {
                    args.add("--include");
                    args.add(includePatterns.trim());
                }
                if (excludePatterns != null && !excludePatterns.isBlank()) {
                    args.add("--exclude");
                    args.add(excludePatterns.trim());
                }
                if (doForce) args.add("--force");
                return new CommandLine(new CodeIndexCommand()).execute(args.toArray(String[]::new));
            } catch (Exception e) {
                System.err.println("Code index configuration failed: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "mcp", mixinStandardHelpOptions = true,
            description = "Guide MCP profile/schema selection and print launch settings.")
    public static class McpConfigureCommand implements Callable<Integer> {
        @Option(names = "--agent", description = "Target agent: claude, codex, gemini, qwen, opencode")
        String agent;

        @Option(names = "--work-dir", description = "Working directory for MCP tools", defaultValue = ".")
        String workDir;

        @Option(names = "--url", description = "Kompile app base URL or /mcp/sse URL")
        String url;

        @Option(names = "--profile", description = "MCP tool profile: full, core, explore, minimal")
        String profile;

        @Option(names = "--schema-level", description = "Schema level: none, moderate, aggressive, compact")
        String schemaLevel;

        @Override
        public Integer call() {
            try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
                LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
                printHeader("MCP Setup");
                String selectedAgent = agent;
                if (selectedAgent == null || selectedAgent.isBlank()) {
                    List<String> agents = availableAgents();
                    if (agents.isEmpty()) agents = ai.kompile.core.agent.CliAgentRegistry.commandNames();
                    int idx = selectNumbered(reader, agents, false);
                    if (idx < 0) return 1;
                    selectedAgent = agents.get(idx - 1);
                }
                String selectedProfile = chooseFrom(reader, profile, "Tool profile", List.of("full", "core", "explore", "minimal"), "core");
                String selectedSchema = chooseFrom(reader, schemaLevel, "Schema level", List.of("compact", "aggressive", "moderate", "none"), "compact");
                String wd = valueOrPrompt(reader, workDir, "  Working directory (blank for current directory): ");
                if (wd == null || wd.isBlank()) wd = ".";

                String sse = McpUrlResolver.resolveOnce(url, 0);
                System.out.println();
                System.out.println("MCP mode: " + (sse != null ? "SSE via " + sse : "stdio via kompile mcp-stdio"));
                System.out.println();
                System.out.println("Use this standalone server command when an agent asks for stdio MCP:");
                System.out.println("  kompile mcp-stdio --work-dir " + Path.of(wd).toAbsolutePath().normalize()
                        + " --profile " + selectedProfile + " --schema-level " + selectedSchema);
                if (sse != null) {
                    System.out.println();
                    System.out.println("Use this SSE endpoint for agents that support remote MCP:");
                    System.out.println("  " + sse);
                }
                System.out.println();
                System.out.println("For managed passthrough, Kompile injects these settings automatically:");
                System.out.println("  kompile chat --mode passthrough --agent " + selectedAgent);
                return 0;
            } catch (Exception e) {
                System.err.println("MCP setup failed: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "gateway", aliases = "tool-gateway", mixinStandardHelpOptions = true,
            description = "Run the tool gateway rule wizard.")
    public static class GatewayConfigureCommand implements Callable<Integer> {
        @Override
        public Integer call() {
            ToolGatewayWizard.run();
            return 0;
        }
    }

    private static String valueOrPrompt(LineReader reader, String value, String message) {
        if (value != null && !value.isBlank()) return value;
        return prompt(reader, message);
    }

    private static String chooseFrom(LineReader reader, String current, String title, List<String> values, String fallback) {
        if (current != null && !current.isBlank()) return current;
        List<String> labels = new ArrayList<>();
        for (String value : values) {
            labels.add(value + (value.equals(fallback) ? " (recommended)" : ""));
        }
        System.out.println(BOLD + "  " + title + RESET);
        int selected = selectNumbered(reader, labels, false);
        if (selected < 0) return fallback;
        return values.get(selected - 1);
    }

    private static List<String> availableAgents() {
        List<String> agents = new ArrayList<>();
        for (String agent : ChatConfig.getPassthroughAgentOrder()) {
            if (SubprocessAgentRunner.resolveAgentBinary(agent) != null) agents.add(agent);
        }
        return agents;
    }
}
