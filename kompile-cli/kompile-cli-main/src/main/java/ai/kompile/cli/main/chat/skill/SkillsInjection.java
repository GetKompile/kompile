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

package ai.kompile.cli.main.chat.skill;

import ai.kompile.cli.common.KompileHome;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Installs kompile skills into each agent's NATIVE skill/command infrastructure.
 * Each agent has its own mechanism for user-defined commands:
 *
 * <ul>
 *   <li><b>Claude Code</b>: {@code .claude/commands/<name>.md} — becomes {@code /name}.
 *       Placeholder: {@code $ARGUMENTS}</li>
 *   <li><b>Codex</b>: {@code ~/.agents/skills/<name>/SKILL.md} — becomes {@code $name}</li>
 *   <li><b>Qwen Code</b>: {@code .qwen/commands/<name>.md} — same format as Claude (fork)</li>
 *   <li><b>Gemini CLI</b>: appends to {@code GEMINI_SYSTEM_MD} temp file (no native skill system)</li>
 *   <li><b>OpenCode / Crush</b>: appends to {@code AGENTS.md} (no native skill system)</li>
 * </ul>
 *
 * <p>All installed files are tracked and removed on {@link #cleanup()}.
 * Existing files are backed up before overwriting and restored on cleanup.</p>
 */
public class SkillsInjection {

    private static final Path KOMPILE_HOME = KompileHome.homeDirectory().toPath();

    private final SkillRegistry skillRegistry;
    private final Path workingDirectory;

    // Tracks files we created or backed up for cleanup
    private final List<InstalledFile> installedFiles = new ArrayList<>();
    private final List<BackupEntry> backups = new ArrayList<>();

    public SkillsInjection(SkillRegistry skillRegistry, Path workingDirectory) {
        this.skillRegistry = skillRegistry;
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
    }

    /**
     * Install all skills into the given agent's native skill/command system.
     * This writes actual files where the agent natively discovers them.
     *
     * @param agentName the agent name
     * @return number of skills installed
     */
    public int installSkills(String agentName) {
        Collection<SkillConfig> skills = skillRegistry.all();
        if (skills.isEmpty()) return 0;

        String name = normalizeAgentName(agentName);
        return switch (name) {
            case "claude" -> installForClaude(skills);
            case "qwen" -> installForQwen(skills);
            case "codex" -> installForCodex(skills);
            case "opencode" -> installForOpenCode(skills);
            case "gemini" -> installForGemini(skills);
            default -> 0;
        };
    }

    // ── Claude Code ────────────────────────────────────────────────────────
    // .claude/commands/<name>.md → /name
    // Placeholder: $ARGUMENTS

    private int installForClaude(Collection<SkillConfig> skills) {
        Path commandsDir = workingDirectory.resolve(".claude").resolve("commands");
        return installCommandFiles(commandsDir, skills, "$ARGUMENTS");
    }

    // ── Qwen Code ──────────────────────────────────────────────────────────
    // .qwen/commands/<name>.md → /name (Claude fork, same mechanism)
    // Placeholder: $ARGUMENTS

    private int installForQwen(Collection<SkillConfig> skills) {
        Path commandsDir = workingDirectory.resolve(".qwen").resolve("commands");
        return installCommandFiles(commandsDir, skills, "$ARGUMENTS");
    }

    // ── Codex ──────────────────────────────────────────────────────────────
    // ~/.agents/skills/<name>/SKILL.md → $name

    private int installForCodex(Collection<SkillConfig> skills) {
        Path skillsDir = Path.of(System.getProperty("user.home"), ".agents", "skills");
        int count = 0;
        try {
            Files.createDirectories(skillsDir);
        } catch (IOException e) {
            System.err.println("[Skills] Warning: Could not create " + skillsDir + ": " + e.getMessage());
            return 0;
        }

        for (SkillConfig skill : skills) {
            try {
                Path skillDir = skillsDir.resolve(skill.getName());
                Files.createDirectories(skillDir);
                Path skillFile = skillDir.resolve("SKILL.md");

                // Backup existing SKILL.md if present
                backupIfExists(skillFile);

                // Write SKILL.md in Codex format
                String content = convertToCodexFormat(skill);
                Files.writeString(skillFile, content);
                installedFiles.add(new InstalledFile(skillFile, skillDir));
                count++;
            } catch (IOException e) {
                System.err.println("[Skills] Warning: Could not install Codex skill '" + skill.getName() + "': " + e.getMessage());
            }
        }
        return count;
    }

    // ── OpenCode / Crush ───────────────────────────────────────────────────
    // No native skill system — append full content to AGENTS.md

    private int installForOpenCode(Collection<SkillConfig> skills) {
        return installIntoAgentsMd(skills);
    }

    // ── Gemini CLI ─────────────────────────────────────────────────────────
    // No native skill system — write to temp file for GEMINI_SYSTEM_MD

    private int installForGemini(Collection<SkillConfig> skills) {
        // Gemini has no native command system; append full skill content
        // to a temp file that can be set via GEMINI_SYSTEM_MD env var
        return installIntoAgentsMd(skills);
    }

    // ── Shared: command file installation (Claude, Qwen) ───────────────────

    private int installCommandFiles(Path commandsDir, Collection<SkillConfig> skills, String argsPlaceholder) {
        int count = 0;
        try {
            Files.createDirectories(commandsDir);
        } catch (IOException e) {
            System.err.println("[Skills] Warning: Could not create " + commandsDir + ": " + e.getMessage());
            return 0;
        }

        for (SkillConfig skill : skills) {
            try {
                Path commandFile = commandsDir.resolve(skill.getName() + ".md");

                // Backup existing file if present
                backupIfExists(commandFile);

                // Convert {{args}} to the agent's native placeholder
                String content = skill.getPromptTemplate();
                if (content == null) continue;
                content = content.replace("{{args}}", argsPlaceholder);

                Files.writeString(commandFile, content);
                installedFiles.add(new InstalledFile(commandFile, null));
                count++;
            } catch (IOException e) {
                System.err.println("[Skills] Warning: Could not install command '" + skill.getName() + "': " + e.getMessage());
            }
        }
        return count;
    }

    // ── Shared: AGENTS.md injection (OpenCode, Gemini) ─────────────────────

    private int installIntoAgentsMd(Collection<SkillConfig> skills) {
        Path agentsMd = workingDirectory.resolve("AGENTS.md");
        try {
            StringBuilder skillsContent = new StringBuilder();
            skillsContent.append("\n\n---\n\n# Kompile Skills\n\n");
            skillsContent.append("The following skills are available. Follow the instructions for the relevant skill when asked.\n\n");

            for (SkillConfig skill : skills) {
                String template = skill.getPromptTemplate();
                if (template == null) continue;
                skillsContent.append("## Skill: ").append(skill.getName());
                if (skill.getDisplayName() != null && !skill.getDisplayName().equals(skill.getName())) {
                    skillsContent.append(" (").append(skill.getDisplayName()).append(")");
                }
                skillsContent.append("\n\n");
                skillsContent.append(template).append("\n\n");
            }

            if (Files.exists(agentsMd)) {
                String existing = Files.readString(agentsMd);
                if (existing.contains("# Kompile Skills")) {
                    return 0; // Already injected
                }
                Path backup = workingDirectory.resolve("AGENTS.md.kompile-skills-backup");
                Files.copy(agentsMd, backup, StandardCopyOption.REPLACE_EXISTING);
                backups.add(new BackupEntry(agentsMd, backup));
                Files.writeString(agentsMd, existing + skillsContent);
            } else {
                Files.writeString(agentsMd, skillsContent.toString());
                backups.add(new BackupEntry(agentsMd, null));
            }
            return skills.size();
        } catch (IOException e) {
            System.err.println("[Skills] Warning: Could not inject skills into AGENTS.md: " + e.getMessage());
            return 0;
        }
    }

    // ── Codex format conversion ────────────────────────────────────────────

    private String convertToCodexFormat(SkillConfig skill) {
        StringBuilder sb = new StringBuilder();
        // Codex requires YAML frontmatter delimited by ---
        sb.append("---\n");
        sb.append("name: ").append(yamlQuote(skill.getName())).append("\n");
        if (skill.getDisplayName() != null && !skill.getDisplayName().equals(skill.getName())) {
            sb.append("display_name: ").append(yamlQuote(skill.getDisplayName())).append("\n");
        }
        if (skill.getDescription() != null) {
            sb.append("description: ").append(yamlQuote(skill.getDescription())).append("\n");
        }
        sb.append("---\n\n");
        sb.append("# ").append(skill.getDisplayName() != null ? skill.getDisplayName() : skill.getName()).append("\n\n");
        if (skill.getDescription() != null) {
            sb.append(skill.getDescription()).append("\n\n");
        }
        String template = skill.getPromptTemplate();
        if (template != null) {
            // Codex uses $ARGUMENTS for skill args
            template = template.replace("{{args}}", "$ARGUMENTS");
            sb.append(template);
        }
        return sb.toString();
    }

    // ── Legacy methods (kept for backward compatibility) ────────────────────

    /**
     * Get extra command-line arguments for the given agent.
     * Now returns empty — skills are installed as native commands, not CLI flags.
     */
    public List<String> getExtraArgs(String agentName) {
        return List.of();
    }

    /**
     * Get extra environment variables for the given agent.
     */
    public Map<String, String> getExtraEnv(String agentName) {
        return Map.of();
    }

    /**
     * Legacy method — now delegates to {@link #installSkills(String)}.
     * Kept for callers that still use this API.
     */
    public Path injectInstructionFile(String agentName) {
        int count = installSkills(agentName);
        return count > 0 ? workingDirectory : null;
    }

    /**
     * Get the generated skills markdown content (for reference/debugging).
     */
    public String getSkillsMarkdown() {
        return SkillsMarkdownGenerator.generate(skillRegistry.all());
    }

    // ── Cleanup ────────────────────────────────────────────────────────────

    /**
     * Remove all installed skill files and restore backups.
     */
    public void cleanup() {
        // Remove files we created
        for (InstalledFile installed : installedFiles) {
            try {
                Files.deleteIfExists(installed.file);
                // Remove empty parent directory (for Codex skill dirs)
                if (installed.parentDir != null) {
                    try {
                        Files.deleteIfExists(installed.parentDir);
                    } catch (IOException e) {
                        // Directory not empty — that's fine, leave it
                    }
                }
            } catch (IOException e) {
                System.err.println("[Skills] Warning: Could not remove " + installed.file + ": " + e.getMessage());
            }
        }
        installedFiles.clear();

        // Restore backed-up files
        for (BackupEntry entry : backups) {
            try {
                if (entry.backup != null) {
                    Files.move(entry.backup, entry.original, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(entry.original);
                }
            } catch (IOException e) {
                System.err.println("[Skills] Warning: Could not restore " + entry.original + ": " + e.getMessage());
            }
        }
        backups.clear();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void backupIfExists(Path file) throws IOException {
        if (!Files.exists(file)) return;
        Path backup = file.resolveSibling(file.getFileName() + ".kompile-backup");
        Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        backups.add(new BackupEntry(file, backup));
    }

    /** Quote a YAML value if it contains special characters (colons, quotes, etc.) */
    private static String yamlQuote(String value) {
        if (value == null) return "\"\"";
        if (value.contains(":") || value.contains("#") || value.contains("\"")
                || value.contains("'") || value.contains("{") || value.contains("}")
                || value.contains("[") || value.contains("]") || value.contains(",")
                || value.contains("&") || value.contains("*") || value.contains("!")
                || value.contains("|") || value.contains(">") || value.contains("%")
                || value.contains("@") || value.contains("`")) {
            // Use double quotes, escape internal double quotes
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return value;
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

    private record InstalledFile(Path file, Path parentDir) {}
    private record BackupEntry(Path original, Path backup) {}
}
