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

package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.KompileHome;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads AGENTS.md files from the project hierarchy and user home,
 * similar to how other CLIs load project instruction files.
 * <p>
 * Search order (all found files are concatenated):
 * <ol>
 *   <li>Walk from cwd up to filesystem root, collecting AGENTS.md files</li>
 *   <li>~/.kompile/AGENTS.md (global user instructions)</li>
 * </ol>
 * Files higher in the tree are included first (most general → most specific).
 */
public class AgentsMdLoader {

    private static final String AGENTS_MD = "AGENTS.md";

    private final Path workingDirectory;

    public AgentsMdLoader(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    /**
     * Load and concatenate all AGENTS.md content found in the hierarchy.
     * Returns empty string if no files found.
     */
    public String load() {
        List<Path> files = findAgentsMdFiles();
        if (files.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (Path file : files) {
            try {
                String content = Files.readString(file);
                if (!content.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append("\n\n");
                    }
                    sb.append("# ").append(AGENTS_MD).append(" (").append(file.getParent()).append(")\n\n");
                    sb.append(content.strip());
                }
            } catch (IOException e) {
                // Skip unreadable files
            }
        }
        return sb.toString();
    }

    /**
     * Find all AGENTS.md files in hierarchy order (root → cwd, then ~/.kompile/).
     */
    private List<Path> findAgentsMdFiles() {
        // Use LinkedHashSet to deduplicate while preserving order
        Set<Path> found = new LinkedHashSet<>();

        // Walk up from cwd, collect in reverse (we'll reverse at the end)
        List<Path> cwdFiles = new ArrayList<>();
        Path dir = workingDirectory.toAbsolutePath().normalize();
        while (dir != null) {
            Path candidate = dir.resolve(AGENTS_MD);
            if (Files.isRegularFile(candidate)) {
                cwdFiles.add(candidate.toAbsolutePath().normalize());
            }
            dir = dir.getParent();
        }

        // Reverse so root-level files come first
        for (int i = cwdFiles.size() - 1; i >= 0; i--) {
            found.add(cwdFiles.get(i));
        }

        // Global user config
        Path globalFile = KompileHome.homeDirectory().toPath().resolve(AGENTS_MD);
        if (Files.isRegularFile(globalFile)) {
            found.add(globalFile.toAbsolutePath().normalize());
        }

        return new ArrayList<>(found);
    }

    /**
     * List the paths of all AGENTS.md files that would be loaded (for /help display).
     */
    public List<Path> listFiles() {
        return findAgentsMdFiles();
    }
}
