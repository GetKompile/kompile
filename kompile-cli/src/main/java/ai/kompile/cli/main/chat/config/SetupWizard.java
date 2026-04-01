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

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;

/**
 * Interactive first-run setup wizard for configuring the chat LLM provider.
 * Walks the user through selecting a provider, entering API key, and choosing a model.
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
        try {
            Terminal terminal = TerminalBuilder.builder().system(true).build();
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();

            System.out.println();
            System.out.println(BOLD + CYAN + "  ╭──────────────────────────────────────╮" + RESET);
            System.out.println(BOLD + CYAN + "  │       Kompile Chat Setup             │" + RESET);
            System.out.println(BOLD + CYAN + "  ╰──────────────────────────────────────╯" + RESET);
            System.out.println();
            System.out.println("  No LLM configuration found. Let's set one up.");
            System.out.println("  " + DIM + "(Config will be saved to ~/.kompile/chat-config.json)" + RESET);
            System.out.println();

            // Step 1: Select provider
            String provider = selectProvider(reader);
            if (provider == null) return null;

            // Step 2: API key (skip for ollama and kompile)
            String apiKey = null;
            if (!"ollama".equals(provider) && !"kompile".equals(provider)) {
                apiKey = promptApiKey(reader, provider);
                if (apiKey == null) return null;
            }

            // Step 3: Select model (skip for kompile — uses server-side agents)
            String model = null;
            if (!"kompile".equals(provider)) {
                model = selectModel(reader, provider);
                if (model == null) return null;
            }

            // Step 4: Custom base URL (optional, but prompted for kompile)
            String baseUrl = promptBaseUrl(reader, provider);

            // Build and save config
            ChatConfig config = new ChatConfig(provider, apiKey, model, baseUrl);

            try {
                config.save();
                System.out.println();
                System.out.println(GREEN + "  ✓ Configuration saved!" + RESET);
                System.out.println();
                System.out.println("  Provider: " + BOLD + provider + RESET);
                System.out.println("  Model:    " + BOLD + model + RESET);
                if (baseUrl != null) {
                    System.out.println("  Base URL: " + BOLD + baseUrl + RESET);
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
        }
    }

    private static String selectProvider(LineReader reader) {
        System.out.println(BOLD + "  Select LLM Provider:" + RESET);
        System.out.println();

        String[] providers = ChatConfig.PROVIDER_ORDER;
        for (int i = 0; i < providers.length; i++) {
            String key = providers[i];
            String desc = ChatConfig.PROVIDERS.get(key);
            System.out.printf("  " + CYAN + "%d" + RESET + "  %s%n", i + 1, desc);
        }
        System.out.println();

        while (true) {
            String input = prompt(reader, "  Choice [1-" + providers.length + "]: ");
            if (input == null) return null;

            try {
                int choice = Integer.parseInt(input.trim());
                if (choice >= 1 && choice <= providers.length) {
                    String selected = providers[choice - 1];
                    System.out.println("  → " + GREEN + ChatConfig.PROVIDERS.get(selected) + RESET);
                    System.out.println();
                    return selected;
                }
            } catch (NumberFormatException ignored) {
                // Also accept provider name directly
                String lower = input.trim().toLowerCase();
                for (String p : providers) {
                    if (p.equals(lower)) {
                        System.out.println("  → " + GREEN + ChatConfig.PROVIDERS.get(p) + RESET);
                        System.out.println();
                        return p;
                    }
                }
            }
            System.out.println("  " + YELLOW + "Please enter a number 1-" + providers.length + RESET);
        }
    }

    private static String promptApiKey(LineReader reader, String provider) {
        String envVar = getEnvVarName(provider);
        String envValue = envVar != null ? System.getenv(envVar) : null;

        if (envValue != null && !envValue.isBlank()) {
            String masked = maskKey(envValue);
            System.out.println("  Found " + envVar + " in environment: " + DIM + masked + RESET);
            String use = prompt(reader, "  Use this key? [Y/n]: ");
            if (use == null) return null;
            if (use.isBlank() || use.toLowerCase().startsWith("y")) {
                return envValue;
            }
        }

        System.out.println(BOLD + "  Enter API Key:" + RESET);
        if (envVar != null) {
            System.out.println("  " + DIM + "(or set " + envVar + " environment variable)" + RESET);
        }

        String key = prompt(reader, "  API key: ");
        if (key == null || key.isBlank()) {
            System.out.println("  " + YELLOW + "API key is required for " + provider + RESET);
            return null;
        }
        return key.trim();
    }

    private static String selectModel(LineReader reader, String provider) {
        String[] defaults = ChatConfig.getDefaultModels(provider);

        System.out.println(BOLD + "  Select Model:" + RESET);
        System.out.println();

        if (defaults.length > 0) {
            for (int i = 0; i < defaults.length; i++) {
                String recommended = (i == 0) ? " " + DIM + "(recommended)" + RESET : "";
                System.out.printf("  " + CYAN + "%d" + RESET + "  %s%s%n", i + 1, defaults[i], recommended);
            }
            System.out.printf("  " + CYAN + "%d" + RESET + "  %s%n", defaults.length + 1, "Custom...");
            System.out.println();

            while (true) {
                String input = prompt(reader, "  Choice [1-" + (defaults.length + 1) + "] or model name: ");
                if (input == null) return null;

                String trimmed = input.trim();
                if (trimmed.isEmpty()) {
                    // Default to first option
                    System.out.println("  → " + GREEN + defaults[0] + RESET);
                    System.out.println();
                    return defaults[0];
                }

                try {
                    int choice = Integer.parseInt(trimmed);
                    if (choice >= 1 && choice <= defaults.length) {
                        System.out.println("  → " + GREEN + defaults[choice - 1] + RESET);
                        System.out.println();
                        return defaults[choice - 1];
                    }
                    if (choice == defaults.length + 1) {
                        // Custom model
                        String custom = prompt(reader, "  Model name: ");
                        if (custom == null || custom.isBlank()) return null;
                        return custom.trim();
                    }
                } catch (NumberFormatException ignored) {
                    // Treat as model name directly
                    return trimmed;
                }

                System.out.println("  " + YELLOW + "Please enter a valid choice" + RESET);
            }
        } else {
            // No defaults, ask for custom name
            String custom = prompt(reader, "  Model name: ");
            if (custom == null || custom.isBlank()) return null;
            return custom.trim();
        }
    }

    private static String promptBaseUrl(LineReader reader, String provider) {
        String defaultUrl = ChatConfig.getDefaultBaseUrl(provider);

        if ("kompile".equals(provider)) {
            System.out.println();
            System.out.println(BOLD + "  Kompile App URL:" + RESET);
            System.out.println("  Default: " + DIM + defaultUrl + RESET);
            System.out.println("  " + DIM + "(The CLI will connect via MCP SSE to this instance)" + RESET);
            String custom = prompt(reader, "  URL (Enter to use default): ");
            if (custom == null || custom.isBlank()) return null;
            return custom.trim();
        }

        if ("ollama".equals(provider)) {
            System.out.println("  Default Ollama URL: " + DIM + defaultUrl + RESET);
            String custom = prompt(reader, "  Custom URL (Enter to use default): ");
            if (custom == null || custom.isBlank()) return null;
            return custom.trim();
        }

        if ("custom".equals(provider)) {
            System.out.println(BOLD + "  Enter Base URL:" + RESET);
            System.out.println("  " + DIM + "(OpenAI-compatible Chat Completions endpoint)" + RESET);
            String url = prompt(reader, "  Base URL: ");
            if (url == null || url.isBlank()) return null;
            return url.trim();
        }

        // Standard providers - don't ask for custom URL by default
        return null;
    }

    private static String prompt(LineReader reader, String prompt) {
        try {
            return reader.readLine(prompt);
        } catch (Exception e) {
            return null;
        }
    }

    private static String getEnvVarName(String provider) {
        switch (provider) {
            case "openai":      return "OPENAI_API_KEY";
            case "anthropic":   return "ANTHROPIC_API_KEY";
            case "gemini":      return "GOOGLE_API_KEY";
            case "openrouter":  return "OPENROUTER_API_KEY";
            case "deepseek":    return "DEEPSEEK_API_KEY";
            case "groq":        return "GROQ_API_KEY";
            default:            return null;
        }
    }

    private static String maskKey(String key) {
        if (key.length() <= 8) return "****";
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }
}
