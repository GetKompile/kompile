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
package ai.kompile.kclaw.tool;

import ai.kompile.kclaw.service.PermissionService;
import ai.kompile.react.model.ToolDefinition;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class MemoryTool {

    private Path memoryDir;
    private final PermissionService permissions;

    public MemoryTool(String workspace, PermissionService permissions) {
        this.memoryDir = Path.of(workspace, "memory");
        this.permissions = permissions;
    }

    public void init() throws IOException {
        Files.createDirectories(memoryDir);
        log.info("Memory storage initialized at: {}", memoryDir);
    }

    public void setWorkspace(String workspace) throws IOException {
        this.memoryDir = Path.of(workspace, "memory");
        Files.createDirectories(memoryDir);
        log.info("Memory storage relocated to: {}", memoryDir);
    }

    public ToolDefinition getSaveToolDefinition() {
        return ToolDefinition.builder()
                .name("save_memory")
                .description("Save important information to long-term memory. Use this to remember facts, preferences, and context for future conversations.")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "key", Map.of(
                                        "type", "string",
                                        "description", "A short label for this memory (e.g., 'user_preferences', 'project_context')"
                                ),
                                "content", Map.of(
                                        "type", "string",
                                        "description", "The information to remember"
                                )
                        ),
                        "required", List.of("key", "content")
                ))
                .executor(this::saveMemory)
                .requiresApproval(false)
                .parallelizable(true)
                .build();
    }

    public ToolDefinition getSearchToolDefinition() {
        return ToolDefinition.builder()
                .name("search_memory")
                .description("Search long-term memory for relevant information. Returns matching memories.")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of(
                                        "type", "string",
                                        "description", "What to search for in memory"
                                )
                        ),
                        "required", List.of("query")
                ))
                .executor(this::searchMemory)
                .requiresApproval(false)
                .parallelizable(true)
                .build();
    }

    private String saveMemory(Map<String, Object> arguments) {
        String key = (String) arguments.get("key");
        String content = (String) arguments.get("content");

        if (key == null || key.isBlank()) {
            return "Error: Memory key is required";
        }
        if (content == null || content.isBlank()) {
            return "Error: Memory content is required";
        }

        String safeKey = sanitizeKey(key);
        Path file = memoryDir.resolve(safeKey + ".md");

        try {
            Files.writeString(file, content);
            log.info("Saved memory: {}", key);
            return "Saved to memory: " + key;
        } catch (IOException e) {
            log.error("Failed to save memory: {}", key, e);
            return "Error saving memory: " + e.getMessage();
        }
    }

    private String searchMemory(Map<String, Object> arguments) {
        String query = (String) arguments.get("query");
        if (query == null || query.isBlank()) {
            return "Error: Search query is required";
        }

        String[] terms = query.toLowerCase().split("\\s+");

        try (Stream<Path> files = Files.list(memoryDir)) {
            List<String> results = files
                    .filter(p -> p.toString().endsWith(".md"))
                    .map(this::readFile)
                    .filter(content -> content != null && !content.isEmpty())
                    .filter(content -> Arrays.stream(terms)
                            .anyMatch(term -> content.toLowerCase().contains(term)))
                    .collect(Collectors.toList());

            if (results.isEmpty()) {
                return "No matching memories found for: " + query;
            }

            return "Found " + results.size() + " matching memories:\n\n" +
                    String.join("\n\n---\n\n", results);

        } catch (IOException e) {
            log.error("Failed to search memory", e);
            return "Error searching memory: " + e.getMessage();
        }
    }

    private String readFile(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return null;
        }
    }

    private String sanitizeKey(String key) {
        return key.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
