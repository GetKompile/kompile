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

package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.cli.main.chat.tools.ResumeTool;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Interactive setup wizard for kompile chat.
 * Presents session mode selection (Standard / Passthrough / Resume) FIRST,
 * then only asks for provider/API key details if Standard mode is chosen.
 * Passthrough mode delegates to CLI agents (claude, codex, gemini, etc.)
 * which handle their own authentication — no API key collection needed.
 * Uses numbered input for selection - bulletproof across all terminal types.
 */
public class SetupWizard {

    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String CYAN = "\033[36m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";

    /**
     * Run the interactive setup wizard.
     * Returns a valid ChatConfig or null if the user cancels.
     */
    public static ChatConfig run() {
        Terminal terminal = null;
        try {
            terminal = TerminalBuilder.builder().system(true).build();
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();

            System.out.println();
            System.out.println(BOLD + CYAN + "  ╭──────────────────────────────────────╮" + RESET);
            System.out.println(BOLD + CYAN + "  │       Kompile Chat Setup             │" + RESET);
            System.out.println(BOLD + CYAN + "  ╰──────────────────────────────────────╯" + RESET);
            System.out.println();
            System.out.println("  " + DIM + "(Config will be saved to ~/.kompile/chat-config.json)" + RESET);
            System.out.println();

            // Step 1: Select chat mode — ALWAYS first
            String chatMode = selectChatMode(reader);
            if (chatMode == null) return null;

            // Handle resume mode - close our terminal first so ResumeTool can own it
            if ("resume".equals(chatMode)) {
                try {
                    terminal.close();
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                }
                terminal = null;

                System.out.println();
                System.out.println(GREEN + "  Launching Resume Tool..." + RESET);
                System.out.println();
                try {
                    ResumeTool resumeTool = new ResumeTool();
                    resumeTool.runInteractiveBrowser();
                    ChatConfig resumeConfig = new ChatConfig(null, null, null, null);
                    resumeConfig.setChatMode("resume");
                    return resumeConfig;
                } catch (IOException e) {
                    System.err.println("Error launching resume tool: " + e.getMessage());
                    return null;
                }
            }

            // Step 2: If passthrough mode, select style then agent
            String passthroughAgent = null;
            boolean passthroughManaged = true;
            if ("passthrough".equals(chatMode)) {
                // Ask managed vs direct first
                List<String> styles = List.of(
                    "Kompile managed — kompile REPL with tools, memory, skills (recommended)",
                    "Direct — agent owns the terminal (raw native experience, no kompile features)"
                );
                int styleIdx = selectNumbered(reader, "Select Passthrough Style:", styles);
                if (styleIdx < 0) return null;
                passthroughManaged = (styleIdx == 0);

                System.out.println();
                passthroughAgent = selectPassthroughAgent(reader);
                if (passthroughAgent == null) return null;
            }

            // Step 3: For standard mode, select LLM provider
            String provider = null;
            String apiKey = null;
            String model = null;
            String baseUrl = null;

            if ("standard".equals(chatMode)) {
                provider = selectProvider(reader);
                if (provider == null) return null;

                if (!"ollama".equals(provider) && !"kompile".equals(provider)) {
                    apiKey = promptApiKey(reader, provider);
                    if (apiKey == null) return null;
                }

                if (!"kompile".equals(provider)) {
                    model = selectModel(reader, provider);
                    if (model == null) return null;
                }

                baseUrl = promptBaseUrl(reader, provider);
            }

            // Build and save config
            ChatConfig config = new ChatConfig(provider, apiKey, model, baseUrl);
            config.setChatMode(chatMode);
            if (passthroughAgent != null) {
                config.setPassthroughAgent(passthroughAgent);
            }
            config.setPassthroughManaged(passthroughManaged);

            try {
                config.save();
                System.out.println();
                System.out.println(GREEN + "  ✓ Configuration saved!" + RESET);
                System.out.println();
                System.out.println("  Chat Mode: " + BOLD + chatMode + RESET);
                if ("passthrough".equals(chatMode)) {
                    System.out.println("  Agent:     " + BOLD + passthroughAgent + RESET);
                } else {
                    System.out.println("  Provider: " + BOLD + provider + RESET);
                    System.out.println("  Model:    " + BOLD + model + RESET);
                    if (baseUrl != null) {
                        System.out.println("  Base URL: " + BOLD + baseUrl + RESET);
                    }
                }
                System.out.println();
                System.out.println(DIM + "  You can reconfigure anytime with: /setup" + RESET);
                System.out.println();
            } catch (IOException e) {
                System.err.println("Warning: Could not save config: " + e.getMessage());
                System.err.println("Proceeding with in-memory configuration.");
            }

            return config;

        } catch (Exception e) {
            System.err.println("Setup wizard error: " + e.getMessage());
            return null;
        } finally {
            if (terminal != null) {
                try {
                    terminal.close();
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                }
            }
        }
    }

    // ── Selection helpers ───────────────────────────────────────────────────

    /**
     * Show a numbered menu and read the user's choice.
     * Accepts either a number (1-N) or a partial name match.
     * Returns the selected index (0-based), or -1 on cancel.
     */
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
                input = reader.readLine("  Choice (1-" + items.size() + ", or Ctrl+C to cancel): ");
            } catch (Exception e) {
                return -1;
            }
            if (input == null) return -1;
            String trimmed = input.trim();
            if (trimmed.equalsIgnoreCase("q") || trimmed.equalsIgnoreCase("quit") || trimmed.equalsIgnoreCase("cancel")) {
                return -1;
            }

            // Try number
            try {
                int n = Integer.parseInt(trimmed);
                if (n >= 1 && n <= items.size()) {
                    return n - 1;
                }
            } catch (NumberFormatException ignored) {}

            // Try partial name match
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).toLowerCase().contains(trimmed.toLowerCase())) {
                    return i;
                }
            }

            System.out.println("  " + YELLOW + "Please enter a number 1-" + items.size() + " or type part of the name" + RESET);
        }
    }

    // ── Chat mode selection ─────────────────────────────────────────────────

    private static String selectChatMode(LineReader reader) {
        List<String> modes = List.of(
            "Standard Chat — REPL with RAG, memory, and tools",
            "Passthrough — delegate to Claude Code, Codex, Gemini, etc.",
            "Resume Previous Conversation"
        );

        System.out.println();
        int selected = selectNumbered(reader, "Select Chat Mode:", modes);
        if (selected < 0) return null;

        String[] values = {"standard", "passthrough", "resume"};
        System.out.println("  → " + GREEN + Character.toUpperCase(values[selected].charAt(0)) + values[selected].substring(1) + RESET);
        System.out.println();
        return values[selected];
    }

    // ── Passthrough agent selection ─────────────────────────────────────────

    /**
     * Check if an agent binary exists on PATH — delegates to SubprocessAgentRunner.
     */
    private static boolean agentExists(String name) {
        return SubprocessAgentRunner.resolveAgentBinary(name) != null;
    }

    private static String selectPassthroughAgent(LineReader reader) {
        // Only show agents that actually exist on the system
        List<String> availableAgents = new ArrayList<>();
        List<String> agentKeys = new ArrayList<>();

        for (String key : ChatConfig.getPassthroughAgentOrder()) {
            if (agentExists(key)) {
                String desc = ChatConfig.getPassthroughAgents().get(key);
                availableAgents.add(desc);
                agentKeys.add(key);
            }
        }

        if (availableAgents.isEmpty()) {
            System.out.println(YELLOW + "  Warning: No CLI agents found on PATH." + RESET);
            System.out.println("  Install Claude Code, Codex, Gemini, Qwen, or OpenCode first.");
            System.out.println("  Docs: https://docs.anthropic.com/en/docs/claude-code/overview");
            return null;
        }

        int selected = selectNumbered(reader, "Select CLI Agent:", availableAgents);
        if (selected < 0) return null;

        String selectedKey = agentKeys.get(selected);
        System.out.println("  → " + GREEN + availableAgents.get(selected) + RESET);
        System.out.println();
        return selectedKey;
    }

    // ── Provider selection ──────────────────────────────────────────────────

    private static String selectProvider(LineReader reader) {
        List<String> providers = new ArrayList<>();
        for (String key : ChatConfig.PROVIDER_ORDER) {
            providers.add(ChatConfig.PROVIDERS.get(key));
        }

        int selected = selectNumbered(reader, "Select LLM Provider:", providers);
        if (selected < 0) return null;

        String selectedKey = ChatConfig.PROVIDER_ORDER[selected];
        System.out.println("  → " + GREEN + ChatConfig.PROVIDERS.get(selectedKey) + RESET);
        System.out.println();
        return selectedKey;
    }

    // ── Model selection ─────────────────────────────────────────────────────

    private static String selectModel(LineReader reader, String provider) {
        String[] defaults = ChatConfig.getDefaultModels(provider);

        if (defaults.length == 0) {
            return promptManual(reader, "  Model name: ");
        }

        List<String> models = new ArrayList<>();
        for (int i = 0; i < defaults.length; i++) {
            String suffix = (i == 0) ? " (recommended)" : "";
            models.add(defaults[i] + suffix);
        }
        models.add("Custom...");

        int selected = selectNumbered(reader, "Select Model:", models);
        if (selected < 0) return null;

        if (selected < defaults.length) {
            String model = defaults[selected];
            System.out.println("  → " + GREEN + model + RESET);
            System.out.println();
            return model;
        } else {
            return promptManual(reader, "  Custom model name: ");
        }
    }

    // ── Manual text prompt ──────────────────────────────────────────────────

    private static String promptManual(LineReader reader, String promptText) {
        try {
            String input = reader.readLine(promptText);
            if (input == null || input.trim().isEmpty()) return null;
            return input.trim();
        } catch (Exception e) {
            return null;
        }
    }

    // ── API key prompt ──────────────────────────────────────────────────────

    private static String promptApiKey(LineReader reader, String provider) {
        String envVar = getEnvVarName(provider);
        String envValue = envVar != null ? System.getenv(envVar) : null;

        if (envValue != null && !envValue.isBlank()) {
            String masked = maskKey(envValue);
            System.out.println("  Found " + envVar + " in environment: " + DIM + masked + RESET);
            String use = promptManual(reader, "  Use this key? [Y/n]: ");
            if (use == null) return null;
            if (use.isBlank() || use.toLowerCase().startsWith("y")) {
                return envValue;
            }
        }

        System.out.println(BOLD + "  Enter API Key:" + RESET);
        if (envVar != null) {
            System.out.println("  " + DIM + "(or set " + envVar + " environment variable)" + RESET);
        }

        return promptManual(reader, "  API key: ");
    }

    // ── Base URL prompt ─────────────────────────────────────────────────────

    private static String promptBaseUrl(LineReader reader, String provider) {
        String defaultUrl = ChatConfig.getDefaultBaseUrl(provider);

        if ("kompile".equals(provider)) {
            System.out.println();
            System.out.println(BOLD + "  Kompile App URL:" + RESET);
            System.out.println("  Default: " + DIM + defaultUrl + RESET);
            System.out.println("  " + DIM + "(The CLI will connect via MCP SSE to this instance)" + RESET);
            String custom = promptManual(reader, "  URL (Enter to use default): ");
            if (custom == null || custom.isBlank()) return null;
            return custom.trim();
        }

        if ("ollama".equals(provider)) {
            System.out.println("  Default Ollama URL: " + DIM + defaultUrl + RESET);
            String custom = promptManual(reader, "  Custom URL (Enter to use default): ");
            if (custom == null || custom.isBlank()) return null;
            return custom.trim();
        }

        if ("custom".equals(provider)) {
            System.out.println(BOLD + "  Enter Base URL:" + RESET);
            System.out.println("  " + DIM + "(OpenAI-compatible Chat Completions endpoint)" + RESET);
            String url = promptManual(reader, "  Base URL: ");
            if (url == null || url.isBlank()) return null;
            return url.trim();
        }

        return null;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static String getEnvVarName(String provider) {
        return switch (provider) {
            case "openai" -> "OPENAI_API_KEY";
            case "anthropic" -> "ANTHROPIC_API_KEY";
            case "gemini" -> "GOOGLE_API_KEY";
            case "openrouter" -> "OPENROUTER_API_KEY";
            case "deepseek" -> "DEEPSEEK_API_KEY";
            case "groq" -> "GROQ_API_KEY";
            default -> null;
        };
    }

    private static String maskKey(String key) {
        if (key == null || key.length() <= 8) return "****";
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }
}
