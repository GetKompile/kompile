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

package ai.kompile.cli.main.chat.tools.custom;

import ai.kompile.cli.common.KompileHome;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Loads custom tool definitions from JSON files on disk.
 *
 * <h3>Search locations (in order, later overrides earlier):</h3>
 * <ol>
 *   <li>{@code ~/.kompile/tools/} — user-scoped tools</li>
 *   <li>{@code .kompile/tools/} — project-scoped tools (relative to working directory)</li>
 * </ol>
 *
 * Each {@code .json} file in these directories is parsed as a {@link CustomToolDefinition}.
 * Project-scoped tools override user-scoped tools with the same name.
 *
 * <p>This follows the same convention as {@code CustomSkillLoader} for skills.
 */
public class CustomToolLoader {

    private final Path workingDirectory;
    private final ObjectMapper objectMapper;

    public CustomToolLoader(Path workingDirectory, ObjectMapper objectMapper) {
        this.workingDirectory = workingDirectory;
        this.objectMapper = objectMapper;
    }

    /**
     * Load all custom tool definitions from user and project directories.
     * Project-scoped tools override user-scoped tools with the same name.
     *
     * @return map of tool name -> CustomToolDefinition
     */
    public Map<String, CustomToolDefinition> loadAll() {
        Map<String, CustomToolDefinition> tools = new LinkedHashMap<>();

        // 1. User-scoped: ~/.kompile/tools/
        Path userDir = KompileHome.homeDirectory().toPath().resolve("tools");
        loadFromDirectory(userDir, tools);

        // 2. Project-scoped: .kompile/tools/ (relative to working directory)
        Path projectDir = workingDirectory.resolve(".kompile").resolve("tools");
        loadFromDirectory(projectDir, tools);

        return tools;
    }

    private void loadFromDirectory(Path dir, Map<String, CustomToolDefinition> tools) {
        if (!Files.isDirectory(dir)) return;

        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .forEach(file -> {
                        try {
                            CustomToolDefinition def = objectMapper.readValue(
                                    file.toFile(), CustomToolDefinition.class);

                            // Fall back to filename if name not specified
                            if (def.getName() == null || def.getName().isBlank()) {
                                String filename = file.getFileName().toString()
                                        .replaceFirst("\\.json$", "");
                                def.setName(filename);
                            }

                            String error = def.validate();
                            if (error != null) {
                                System.err.println("Warning: Skipping invalid tool "
                                        + file + ": " + error);
                                return;
                            }

                            tools.put(def.getName(), def);
                        } catch (Exception e) {
                            System.err.println("Warning: Failed to load custom tool from "
                                    + file + ": " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            // Directory not accessible, skip
        }
    }
}
