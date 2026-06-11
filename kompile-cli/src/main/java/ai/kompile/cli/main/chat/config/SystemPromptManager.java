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

import ai.kompile.cli.common.KompileHome;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Manages system prompt injection into external CLI agents.
 * <p>
 * Resolution order for the central system prompt:
 * <ol>
 *   <li>{@code --system-prompt} CLI flag (inline text)</li>
 *   <li>{@code --system-prompt-file} CLI flag (file path)</li>
 *   <li>{@code systemPrompt} field in {@code ~/.kompile/chat-config.json}</li>
 *   <li>{@code ~/.kompile/system-prompt.md} file</li>
 * </ol>
 * <p>
 * Per-agent overrides are loaded from {@code ~/.kompile/system-prompts/<agent>.md}
 * and appended to the central prompt.
 * <p>
 * Injection strategy per agent:
 * <ul>
 *   <li><b>Claude</b>: {@code --append-system-prompt-file <path>}</li>
 *   <li><b>Qwen</b>: {@code --append-system-prompt "text"}</li>
 *   <li><b>Gemini</b>: {@code GEMINI_SYSTEM_MD=<path>} env var (writes temp file)</li>
 *   <li><b>Codex</b>: Prepends to project {@code AGENTS.md} (backup/restore)</li>
 *   <li><b>OpenCode</b>: Prepends to project {@code AGENTS.md} (backup/restore)</li>
 * </ul>
 */
public class SystemPromptManager {

    private static final Path KOMPILE_HOME = KompileHome.homeDirectory().toPath();
    private static final Path DEFAULT_PROMPT_FILE = KOMPILE_HOME.resolve("system-prompt.md");
    private static final Path PER_AGENT_DIR = KOMPILE_HOME.resolve("system-prompts");

    private final String centralPrompt;
    private final Map<String, String> perAgentPrompts;

    // Cleanup state: backed-up files to restore on cleanup
    private final List<BackupEntry> backups = new ArrayList<>();
    private Path tempPromptFile;

    private SystemPromptManager(String centralPrompt, Map<String, String> perAgentPrompts) {
        this.centralPrompt = centralPrompt;
        this.perAgentPrompts = perAgentPrompts;
    }

    /**
     * Resolve the system prompt manager from all available sources.
     *
     * @param cliPrompt     inline text from {@code --system-prompt} (may be null)
     * @param cliPromptFile file path from {@code --system-prompt-file} (may be null)
     * @param configPrompt  text from {@code ChatConfig.systemPrompt} (may be null)
     * @return a SystemPromptManager, or null if no system prompt is configured
     */
    public static SystemPromptManager resolve(String cliPrompt, String cliPromptFile, String configPrompt) {
        String central = null;

        // 1. CLI inline text takes highest priority
        if (cliPrompt != null && !cliPrompt.isBlank()) {
            central = cliPrompt.strip();
        }

        // 2. CLI file path
        if (central == null && cliPromptFile != null && !cliPromptFile.isBlank()) {
            Path file = Path.of(cliPromptFile);
            if (Files.isReadable(file)) {
                try {
                    central = Files.readString(file).strip();
                } catch (IOException e) {
                    System.err.println("Warning: Could not read system prompt file: " + e.getMessage());
                }
            } else {
                System.err.println("Warning: System prompt file not found: " + cliPromptFile);
            }
        }

        // 3. Config file value
        if (central == null && configPrompt != null && !configPrompt.isBlank()) {
            central = configPrompt.strip();
        }

        // 4. Default file at ~/.kompile/system-prompt.md
        if (central == null && Files.isReadable(DEFAULT_PROMPT_FILE)) {
            try {
                String content = Files.readString(DEFAULT_PROMPT_FILE).strip();
                if (!content.isEmpty()) {
                    central = content;
                }
            } catch (IOException e) {
                // Ignore — no default prompt
            }
        }

        // Load per-agent overrides
        Map<String, String> perAgent = loadPerAgentPrompts();

        if (central == null && perAgent.isEmpty()) {
            return null; // No system prompt configured
        }

        return new SystemPromptManager(central, perAgent);
    }

    /**
     * Get the effective system prompt for a given agent.
     * Combines the central prompt with any per-agent override.
     */
    public String resolveForAgent(String agentName) {
        StringBuilder sb = new StringBuilder();
        if (centralPrompt != null && !centralPrompt.isEmpty()) {
            sb.append(centralPrompt);
        }
        String agentOverride = perAgentPrompts.get(normalizeAgentName(agentName));
        if (agentOverride != null && !agentOverride.isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(agentOverride);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * Get the raw central prompt (without per-agent overrides).
     */
    public String getCentralPrompt() {
        return centralPrompt;
    }

    /**
     * Get extra command-line arguments to append for the given agent.
     * For Claude and Qwen, this returns the append-system-prompt flags.
     * For other agents, returns an empty list (they use file/env injection).
     */
    public List<String> getExtraArgs(String agentName) {
        String prompt = resolveForAgent(agentName);
        if (prompt == null || prompt.isEmpty()) return List.of();

        String name = normalizeAgentName(agentName);
        return switch (name) {
            case "claude" -> {
                // Write to temp file and use --append-system-prompt-file
                Path file = ensureTempPromptFile(prompt);
                if (file != null) {
                    yield List.of("--append-system-prompt-file", file.toAbsolutePath().toString());
                }
                yield List.of();
            }
            case "qwen" -> List.of("--append-system-prompt", prompt);
            default -> List.of(); // Codex, Gemini, OpenCode use other mechanisms
        };
    }

    /**
     * Get extra environment variables to set for the given agent.
     * For Gemini, this sets GEMINI_SYSTEM_MD pointing to a temp file.
     */
    public Map<String, String> getExtraEnv(String agentName) {
        String prompt = resolveForAgent(agentName);
        if (prompt == null || prompt.isEmpty()) return Map.of();

        String name = normalizeAgentName(agentName);
        if ("gemini".equals(name)) {
            Path file = ensureTempPromptFile(prompt);
            if (file != null) {
                return Map.of("GEMINI_SYSTEM_MD", file.toAbsolutePath().toString());
            }
        }
        return Map.of();
    }

    /**
     * Inject the system prompt into a file-based instruction mechanism for agents
     * that don't support CLI flags (Codex, OpenCode).
     * Prepends the system prompt to the project's AGENTS.md file, backing up the original.
     *
     * @param agentName  the agent name
     * @param workingDir the project working directory
     * @return the path to the injected file, or null if no injection was needed
     */
    public Path injectInstructionFile(String agentName, Path workingDir) {
        String prompt = resolveForAgent(agentName);
        if (prompt == null || prompt.isEmpty()) return null;

        String name = normalizeAgentName(agentName);
        if (!"codex".equals(name) && !"opencode".equals(name)) {
            return null; // Other agents use CLI args or env vars
        }

        Path agentsMd = workingDir.resolve("AGENTS.md");
        try {
            // Backup existing AGENTS.md if present
            if (Files.exists(agentsMd)) {
                Path backup = workingDir.resolve("AGENTS.md.kompile-backup");
                Files.copy(agentsMd, backup, StandardCopyOption.REPLACE_EXISTING);
                backups.add(new BackupEntry(agentsMd, backup));

                // Prepend our prompt to existing content
                String existing = Files.readString(agentsMd);
                String combined = "# Kompile System Instructions\n\n" + prompt
                        + "\n\n---\n\n" + existing;
                Files.writeString(agentsMd, combined);
            } else {
                // Create new AGENTS.md with our prompt
                Files.writeString(agentsMd, "# Kompile System Instructions\n\n" + prompt + "\n");
                backups.add(new BackupEntry(agentsMd, null)); // null backup = delete on cleanup
            }
            return agentsMd;
        } catch (IOException e) {
            System.err.println("Warning: Could not inject system prompt into AGENTS.md: " + e.getMessage());
            return null;
        }
    }

    /**
     * Clean up all injected files: restore backups and remove temp files.
     */
    public void cleanup() {
        // Restore backed-up files
        for (BackupEntry entry : backups) {
            try {
                if (entry.backup != null) {
                    Files.move(entry.backup, entry.original, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(entry.original);
                }
            } catch (IOException e) {
                System.err.println("Warning: Could not restore " + entry.original + ": " + e.getMessage());
            }
        }
        backups.clear();

        // Remove temp prompt file
        if (tempPromptFile != null) {
            try {
                Files.deleteIfExists(tempPromptFile);
            } catch (IOException e) {
                // Ignore
            }
            tempPromptFile = null;
        }
    }

    /**
     * Write the prompt to a temporary file (reused across calls for the same session).
     */
    private Path ensureTempPromptFile(String prompt) {
        if (tempPromptFile != null && Files.exists(tempPromptFile)) {
            // Check if content matches; if not, rewrite
            try {
                String existing = Files.readString(tempPromptFile);
                if (existing.equals(prompt)) return tempPromptFile;
            } catch (IOException e) {
                // Rewrite
            }
        }

        try {
            Path tmpDir = KOMPILE_HOME.resolve("tmp");
            Files.createDirectories(tmpDir);
            tempPromptFile = tmpDir.resolve("system-prompt-" + ProcessHandle.current().pid() + ".md");
            Files.writeString(tempPromptFile, prompt);
            return tempPromptFile;
        } catch (IOException e) {
            System.err.println("Warning: Could not write temp system prompt file: " + e.getMessage());
            return null;
        }
    }

    /**
     * Load per-agent prompt overrides from ~/.kompile/system-prompts/<agent>.md
     */
    private static Map<String, String> loadPerAgentPrompts() {
        Map<String, String> result = new HashMap<>();
        if (!Files.isDirectory(PER_AGENT_DIR)) return result;

        try (var stream = Files.list(PER_AGENT_DIR)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p).strip();
                            if (!content.isEmpty()) {
                                String agentName = p.getFileName().toString()
                                        .replaceFirst("\\.md$", "").toLowerCase();
                                result.put(agentName, content);
                            }
                        } catch (IOException e) {
                            // Skip unreadable files
                        }
                    });
        } catch (IOException e) {
            // Ignore — no per-agent prompts
        }
        return result;
    }

    private static String normalizeAgentName(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase();
        if (lower.contains("claude")) return "claude";
        if (lower.contains("codex")) return "codex";
        if (lower.contains("gemini")) return "gemini";
        if (lower.contains("qwen")) return "qwen";
        if (lower.contains("opencode")) return "opencode";
        if (lower.contains("pi")) return "pi";
        return lower;
    }

    private record BackupEntry(Path original, Path backup) {}
}
