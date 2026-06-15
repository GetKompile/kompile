/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.orchestrator.integration.cli;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Configuration for CLI-based LLM agents (Claude, Codex, Gemini, etc.)
 * Defines command-line flags, environment variables, and capabilities.
 */
@Data
@Builder
@Slf4j
public class CliAgentConfig {

    private String name;                    // Agent ID: "claude-cli", "codex-cli", "gemini-cli"
    private String displayName;             // Human-readable name
    private String command;                 // CLI command: "claude", "codex", "gemini"
    private String skipPermissionsFlag;     // e.g., "--dangerously-skip-permissions" for Claude
    private String outputFormatFlag;        // e.g., "--output-format" for Claude
    private String outputFormat;            // e.g., "stream-json" for Claude
    private String verboseFlag;             // e.g., "--verbose" for Claude
    private String promptFlag;              // e.g., "-p" for Claude
    private List<String> defaultArgs;        // Default CLI arguments (order-preserving)
    private Map<String, String> environment; // Environment variables
    private boolean available;              // Whether CLI is installed
    private boolean defaultAgent;           // Default agent selection

    // MCP Server configuration
    private boolean mcpSupported;
    private String mcpServerFlag;           // e.g., "--mcp-server" for Claude
    private String mcpConfigFlag;           // Config file path flag
    private String mcpAllowToolsFlag;       // Tools whitelist flag

    /**
     * Pre-configured CLI agents.
     */
    public static final CliAgentConfig CLAUDE_CLI = CliAgentConfig.builder()
            .name("claude-cli")
            .displayName("Claude CLI")
            .command("claude")
            .skipPermissionsFlag("--dangerously-skip-permissions")
            .outputFormatFlag("--output-format")
            .outputFormat("stream-json")
            .verboseFlag("--verbose")
            .promptFlag("-p")
            .defaultArgs(List.of("--output-format", "stream-json", "--verbose"))
            .environment(Map.of())
            .mcpSupported(true)
            .mcpServerFlag("--mcp-server")
            .mcpConfigFlag("--mcp-config")
            .mcpAllowToolsFlag("--allowedTools")
            .defaultAgent(true)
            .build();

    public static final CliAgentConfig CODEX_CLI = CliAgentConfig.builder()
            .name("codex-cli")
            .displayName("OpenAI Codex CLI")
            .command("codex")
            .skipPermissionsFlag("--full-auto")
            .promptFlag("-p")
            .defaultArgs(List.of())
            .environment(Map.of())
            .mcpSupported(false)
            .defaultAgent(false)
            .build();

    public static final CliAgentConfig GEMINI_CLI = CliAgentConfig.builder()
            .name("gemini-cli")
            .displayName("Google Gemini CLI")
            .command("gemini")
            .skipPermissionsFlag("--yolo")
            .promptFlag("-p")
            .defaultArgs(List.of())
            .environment(Map.of())
            .mcpSupported(false)
            .defaultAgent(false)
            .build();

    /**
     * Get all pre-configured agents.
     */
    public static List<CliAgentConfig> getAllAgents() {
        return List.of(CLAUDE_CLI, CODEX_CLI, GEMINI_CLI);
    }

    /**
     * Get an agent by name.
     */
    public static Optional<CliAgentConfig> getByName(String name) {
        return getAllAgents().stream()
                .filter(a -> a.getName().equals(name))
                .findFirst();
    }

    /**
     * Get the default agent.
     */
    public static CliAgentConfig getDefault() {
        return getAllAgents().stream()
                .filter(CliAgentConfig::isDefaultAgent)
                .findFirst()
                .orElse(CLAUDE_CLI);
    }

    /**
     * Check if this agent CLI is available on the system.
     */
    public boolean checkAvailability() {
        try {
            ProcessBuilder pb = new ProcessBuilder(command, "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean completed = process.waitFor(5, TimeUnit.SECONDS);

            if (completed && process.exitValue() == 0) {
                this.available = true;
                log.debug("Agent {} is available", name);
                return true;
            }
        } catch (Exception e) {
            log.debug("Agent {} is not available: {}", name, e.getMessage());
        }
        this.available = false;
        return false;
    }

    /**
     * Parse help output to detect MCP capabilities.
     */
    public void detectMcpCapabilities() {
        if (!available) return;

        try {
            ProcessBuilder pb = new ProcessBuilder(command, "--help");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder helpOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    helpOutput.append(line).append("\n");
                }
            }

            boolean completed = process.waitFor(5, TimeUnit.SECONDS);
            if (completed) {
                String help = helpOutput.toString().toLowerCase();
                this.mcpSupported = help.contains("mcp") ||
                                    help.contains("--mcp-server") ||
                                    help.contains("--mcp-config");
                log.debug("Agent {} MCP support: {}", name, mcpSupported);
            }
        } catch (Exception e) {
            log.debug("Failed to detect MCP capabilities for {}: {}", name, e.getMessage());
        }
    }

    /**
     * Build the command line for executing this agent.
     */
    public List<String> buildCommand(String prompt, boolean skipPermissions,
                                       String workingDirectory) {
        List<String> cmd = new ArrayList<>();
        cmd.add(command);

        // Add skip permissions flag if requested
        if (skipPermissions && skipPermissionsFlag != null) {
            cmd.add(skipPermissionsFlag);
        }

        // Add output format for Claude
        if (outputFormatFlag != null && outputFormat != null) {
            cmd.add(outputFormatFlag);
            cmd.add(outputFormat);
        }

        // Add verbose flag for Claude
        if (verboseFlag != null && "claude-cli".equals(name)) {
            cmd.add(verboseFlag);
        }

        // Add any additional default args (that aren't already included)
        if (defaultArgs != null) {
            for (String arg : defaultArgs) {
                if (!cmd.contains(arg)) {
                    // Skip format args if already added
                    if (arg.equals("--output-format") || arg.equals("stream-json") || arg.equals("--verbose")) {
                        continue;
                    }
                    cmd.add(arg);
                }
            }
        }

        // Add prompt
        if (promptFlag != null) {
            cmd.add(promptFlag);
        }
        cmd.add(prompt);

        return cmd;
    }

    /**
     * Get safe environment variables (without sensitive values logged).
     */
    public Map<String, String> safeEnvironment() {
        return environment != null ? new HashMap<>(environment) : new HashMap<>();
    }
}
