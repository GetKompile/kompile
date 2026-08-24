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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.skill.CustomSkillLoader;
import ai.kompile.cli.main.chat.skill.SkillConfig;
import ai.kompile.cli.main.chat.skill.SkillRegistry;
import ai.kompile.cli.main.chat.skill.SkillsMarkdownGenerator;

import java.nio.file.Path;
import java.util.List;

/**
 * Immutable project instructions and skills loaded for one chat working directory.
 * All standard-chat transports use this object so displaying project context and
 * actually sending it to a model cannot drift into separate code paths.
 */
public final class ProjectChatContext {

    private static final int DEFAULT_MAX_CONTEXT_CHARS = 1_000_000;
    private final Path workingDirectory;
    private final String agentsMdContent;
    private final List<Path> agentsMdFiles;
    private final SkillRegistry skillRegistry;
    private final String skillsContent;

    private ProjectChatContext(Path workingDirectory, SkillRegistry skillRegistry) {
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
        AgentsMdLoader agentsLoader = new AgentsMdLoader(this.workingDirectory);
        this.agentsMdContent = agentsLoader.load();
        this.agentsMdFiles = List.copyOf(agentsLoader.listFiles());
        this.skillRegistry = skillRegistry;
        this.skillsContent = SkillsMarkdownGenerator.generateCompact(skillRegistry.all());
        int maxChars = Integer.getInteger(
                "kompile.chat.maxProjectContextChars", DEFAULT_MAX_CONTEXT_CHARS);
        int totalChars = agentsMdContent.length()
                + (skillsContent == null ? 0 : skillsContent.length());
        if (totalChars > maxChars) {
            throw new IllegalStateException("Project instructions and skill catalog require "
                    + totalChars + " characters, exceeding the configured limit " + maxChars);
        }
    }

    /** Load built-in and custom skills together with the effective AGENTS.md hierarchy. */
    public static ProjectChatContext load(Path workingDirectory) {
        SkillRegistry skills = new SkillRegistry();
        for (SkillConfig custom : new CustomSkillLoader(workingDirectory).loadAll().values()) {
            skills.register(custom);
        }
        return new ProjectChatContext(workingDirectory, skills);
    }

    /** Reuse a registry already owned by a REPL while loading the same instructions. */
    public static ProjectChatContext load(Path workingDirectory, SkillRegistry skillRegistry) {
        return new ProjectChatContext(workingDirectory,
                skillRegistry != null ? skillRegistry : load(workingDirectory).skillRegistry());
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    public String agentsMdContent() {
        return agentsMdContent;
    }

    public List<Path> agentsMdFiles() {
        return agentsMdFiles;
    }

    public SkillRegistry skillRegistry() {
        return skillRegistry;
    }

    /** Render the supplemental system-prompt fragment shared by local and server chat. */
    public String renderSystemPrompt() {
        StringBuilder prompt = new StringBuilder();
        if (agentsMdContent != null && !agentsMdContent.isBlank()) {
            prompt.append("# Project Instructions (from AGENTS.md)\n\n")
                    .append(agentsMdContent.strip());
        }
        String skillsPrompt = renderSkillsPrompt();
        if (!skillsPrompt.isBlank()) {
            if (prompt.length() > 0) prompt.append("\n\n");
            prompt.append(skillsPrompt);
        }
        return prompt.toString();
    }

    public String renderSkillsPrompt() {
        if (skillsContent == null || skillsContent.isBlank()) return "";
        return "# Kompile Skills\n\n"
                + "The normal Kompile chat has loaded these skills. When a user invokes one, "
                + "follow the expanded <skill> instructions in that user turn.\n\n"
                + skillsContent.strip();
    }
}
