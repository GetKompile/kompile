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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Installs kompile skills into each agent's NATIVE skill/command infrastructure.
 * Each agent has its own mechanism for user-defined commands:
 *
 * <ul>
 *   <li><b>Claude Code</b>: {@code .claude/commands/<name>.md} — becomes {@code /name}.
 *       Placeholder: {@code $ARGUMENTS}</li>
 *   <li><b>Codex</b>: project {@code .agents/skills/<name>/SKILL.md}</li>
 *   <li><b>Qwen Code</b>: {@code .qwen/commands/<name>.md} — same format as Claude (fork)</li>
 *   <li><b>Gemini CLI</b>: managed project {@code GEMINI.md} block</li>
 *   <li><b>OpenCode / Crush</b>: appends to {@code AGENTS.md} (no native skill system)</li>
 * </ul>
 *
 * <p>All installed files are tracked and removed on {@link #cleanup()}.
 * Existing files are backed up before overwriting and restored on cleanup.</p>
 */
public class SkillsInjection {

    private final SkillRegistry skillRegistry;
    private final Path workingDirectory;

    // Tracks content owned by this injection so cleanup never overwrites later edits.
    private final List<OwnedFile> ownedFiles = new ArrayList<>();
    private final List<ManagedBlock> managedBlocks = new ArrayList<>();

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

    private int installForCodex(Collection<SkillConfig> skills) {
        Path root = workingDirectory.resolve(".agents").resolve("skills");
        int count = 0;
        try {
            requireSafePath(root);
            Files.createDirectories(root);
        } catch (IOException e) {
            return 0;
        }
        for (SkillConfig skill : skills) {
            try {
                Path skillDir = safeChild(root, skill.getName());
                boolean parentExisted = Files.isDirectory(skillDir, LinkOption.NOFOLLOW_LINKS);
                Files.createDirectories(skillDir);
                Path skillFile = safeChild(skillDir, "SKILL.md");
                if (installOwnedFile(skillFile, parentExisted ? null : skillDir,
                        convertToCodexFormat(skill))) count++;
            } catch (IOException ignored) {
                // Existing or unsafe provider skills are left untouched.
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
        return installIntoInstructionFile(skills, workingDirectory.resolve("GEMINI.md"));
    }

    // ── Shared: command file installation (Claude, Qwen) ───────────────────

    private int installCommandFiles(Path commandsDir, Collection<SkillConfig> skills, String argsPlaceholder) {
        int count = 0;
        try {
            requireSafePath(commandsDir);
            Files.createDirectories(commandsDir);
        } catch (IOException e) {
            System.err.println("[Skills] Warning: Could not create " + commandsDir + ": " + e.getMessage());
            return 0;
        }

        for (SkillConfig skill : skills) {
            try {
                Path commandFile = SkillPathPolicy.resolve(commandsDir, skill.getName());

                String content = skill.getPromptTemplate();
                if (content == null) continue;
                content = content.replace("{{args}}", argsPlaceholder);

                if (installOwnedFile(commandFile, null, content)) {
                    count++;
                }
            } catch (IOException e) {
                System.err.println("[Skills] Warning: Could not install command '" + skill.getName() + "': " + e.getMessage());
            }
        }
        return count;
    }

    // ── Shared managed instruction-file injection ──────────────────────────

    private int installIntoAgentsMd(Collection<SkillConfig> skills) {
        return installIntoInstructionFile(skills, workingDirectory.resolve("AGENTS.md"));
    }

    private int installIntoInstructionFile(Collection<SkillConfig> skills, Path instructionFile) {
        try {
            return ManagedFileLock.withLock(instructionFile, () -> {
            requireSafePath(workingDirectory);
            if (Files.isSymbolicLink(instructionFile)) {
                throw new IOException("Instruction file must not be a symbolic link: "
                        + instructionFile);
            }
            String blockId = UUID.randomUUID().toString();
            StringBuilder skillsContent = new StringBuilder();
            skillsContent.append("\n\n<!-- BEGIN KOMPILE MANAGED SKILLS ")
                    .append(blockId).append(" -->\n\n# Kompile Skills\n\n");
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
            skillsContent.append("<!-- END KOMPILE MANAGED SKILLS ")
                    .append(blockId).append(" -->\n");

            boolean existed = Files.isRegularFile(instructionFile, LinkOption.NOFOLLOW_LINKS);
            if (Files.exists(instructionFile, LinkOption.NOFOLLOW_LINKS) && !existed) {
                throw new IOException("Instruction path is not a regular file: "
                        + instructionFile);
            }
            String existing = existed ? readNoFollow(instructionFile) : "";
            boolean originalExisted = existed && !stripManagedSkillBlocks(existing).isBlank();
            String block = skillsContent.toString();
            String installedContent = existing + block;
            writeNoFollow(instructionFile, installedContent, existed);
            managedBlocks.add(new ManagedBlock(instructionFile, block, existed,
                    originalExisted, existing, installedContent));
            return skills.size();
            });
        } catch (IOException e) {
            System.err.println("[Skills] Warning: Could not inject skills into "
                    + instructionFile + ": " + e.getMessage());
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
     * Remove content owned by this injection without overwriting later edits.
     */
    public void cleanup() {
        for (ManagedBlock managed : managedBlocks) {
            try {
                cleanupManagedBlock(managed);
            } catch (IOException e) {
                System.err.println("[Skills] Warning: Could not clean " + managed.file + ": " + e.getMessage());
            }
        }
        managedBlocks.clear();

        for (OwnedFile owned : ownedFiles) {
            try {
                if (!Files.isRegularFile(owned.file, LinkOption.NOFOLLOW_LINKS)) continue;
                String current = readNoFollow(owned.file);
                if (!current.equals(owned.installedContent)) continue;
                Files.deleteIfExists(owned.file);
                if (owned.parentDir != null) {
                    try {
                        Files.deleteIfExists(owned.parentDir);
                    } catch (IOException ignored) {
                        // Directory is not empty; leave it.
                    }
                }
            } catch (IOException e) {
                System.err.println("[Skills] Warning: Could not restore " + owned.file + ": " + e.getMessage());
            }
        }
        ownedFiles.clear();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static void cleanupManagedBlock(ManagedBlock managed) throws IOException {
        ManagedFileLock.withLock(managed.file, () -> {
            if (!Files.isRegularFile(managed.file, LinkOption.NOFOLLOW_LINKS)) return null;
            String current = readNoFollow(managed.file);
            if (!current.contains(managed.block)) return null;
            if (current.equals(managed.installedContent)) {
                if (managed.fileExisted) {
                    writeNoFollow(managed.file, managed.originalContent, true);
                } else {
                    Files.deleteIfExists(managed.file);
                }
                return null;
            }
            String remaining = current.replace(managed.block, "");
            if (remaining.isBlank() && !managed.originalExisted) {
                Files.deleteIfExists(managed.file);
            } else {
                writeNoFollow(managed.file, remaining, true);
            }
            return null;
        });
    }

    private boolean installOwnedFile(Path file, Path parentDir, String content) throws IOException {
        requireSafePath(file.getParent());
        if (Files.isSymbolicLink(file)) {
            throw new IOException("Skill file must not be a symbolic link: " + file);
        }
        // Never overwrite a provider/user command. This avoids cross-session restore
        // stacks and guarantees cleanup cannot discard an existing definition.
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return false;
        writeNoFollow(file, content, false);
        ownedFiles.add(new OwnedFile(file, parentDir, content));
        return true;
    }

    private static String stripManagedSkillBlocks(String content) {
        String remaining = content == null ? "" : content;
        while (true) {
            int start = remaining.indexOf("<!-- BEGIN KOMPILE MANAGED SKILLS ");
            if (start < 0) return remaining.strip();
            int end = remaining.indexOf("<!-- END KOMPILE MANAGED SKILLS ", start);
            if (end < 0) return remaining.strip();
            int close = remaining.indexOf("-->", end);
            if (close < 0) return remaining.strip();
            remaining = remaining.substring(0, start) + remaining.substring(close + 3);
        }
    }

    private static String readNoFollow(Path file) throws IOException {
        try (var input = Files.newInputStream(file, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void writeNoFollow(Path file, String content, boolean existed) throws IOException {
        if (existed) {
            Files.writeString(file, content, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        } else {
            Files.writeString(file, content, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        }
    }

    private static Path safeChild(Path root, String child) throws IOException {
        requireSafePath(root);
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(child).normalize();
        if (!resolved.startsWith(normalizedRoot) || Files.isSymbolicLink(resolved)) {
            throw new IOException("Unsafe skill path: " + resolved);
        }
        return resolved;
    }

    private static void requireSafePath(Path path) throws IOException {
        if (SkillPathPolicy.hasSymlinkComponent(path)) {
            throw new IOException("Skill path contains a symbolic link: " + path);
        }
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

    private record OwnedFile(Path file, Path parentDir, String installedContent) {}
    private record ManagedBlock(Path file, String block, boolean fileExisted,
                                boolean originalExisted, String originalContent,
                                String installedContent) {}
}
