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

package ai.kompile.tool.filesystem; // New package

import ai.kompile.tool.filesystem.config.FilesystemToolProperties; // Import from this module's config
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component // This bean will be discovered by the main application if this module is on classpath
public class FilesystemToolImpl { // Renamed class for convention, original name "FilesystemTool" is also fine

    private static final Logger logger = LoggerFactory.getLogger(FilesystemToolImpl.class);
    private final FilesystemToolProperties properties;
    private Map<String, Path> resolvedRoots;

    // Input DTOs are inner records, which is fine.
    public record ListFilesInput(String rootAlias, String subPath) {}
    public record ReadFileInput(String rootAlias, String filePath) {}

    public FilesystemToolImpl(FilesystemToolProperties properties) {
        this.properties = properties;
        logger.debug("FilesystemToolImpl constructed.");
    }

    @PostConstruct
    public void init() {
        logger.info("Initializing FilesystemToolImpl...");
        if (this.properties.getRoots() == null || this.properties.getRoots().isEmpty()) {
            logger.warn("No filesystem roots configured for FilesystemToolImpl. Tool will be non-functional.");
            this.resolvedRoots = Collections.emptyMap(); // Initialize to empty map
            return;
        }
        this.resolvedRoots = this.properties.getRoots().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey, // The key from application.properties (e.g., "default")
                        entry -> {
                            FilesystemToolProperties.RootConfig rootConfig = entry.getValue();
                            Path rootPath = Paths.get(rootConfig.getPath()).toAbsolutePath().normalize();
                            logger.info("Registering filesystem root: key='{}', alias='{}', path='{}'",
                                    entry.getKey(), rootConfig.getAlias(), rootPath);
                            // Create directories if they don't exist
                            if (!Files.exists(rootPath)) {
                                try {
                                    Files.createDirectories(rootPath);
                                    logger.info("Created shared directory for FilesystemToolImpl: {}", rootPath);
                                    // Add a sample file if the alias is 'default' or specific known alias
                                    // This sample file creation is more for demo purposes.
                                    if ("default".equals(rootConfig.getAlias()) || this.properties.getRoots().size() == 1) {
                                        Path sampleFile = rootPath.resolve("sample-shared.txt");
                                        if (!Files.exists(sampleFile)) {
                                            Files.writeString(sampleFile, "This is a sample file in the shared MCP root: " + rootConfig.getAlias());
                                            logger.info("Created sample file: {}", sampleFile);
                                        }
                                    }
                                } catch (IOException e) {
                                    logger.error("Could not create shared directory or sample file for root alias '{}' at {}: {}",
                                            rootConfig.getAlias(), rootPath, e.getMessage());
                                }
                            }
                            return rootPath;
                        }
                ));
        logger.info("FilesystemToolImpl initialized with resolved roots: {}", this.resolvedRoots.keySet());
    }

    private Path resolvePath(String rootAlias, String subPathStr) throws IOException, SecurityException {
        if (this.resolvedRoots == null || this.resolvedRoots.isEmpty()) {
            throw new IOException("Filesystem roots not configured or initialized for FilesystemToolImpl.");
        }
        // The rootAlias in the input (e.g., "default") should match the key used in application.properties
        // or the 'alias' field in RootConfig if you prefer to match against that.
        // Current logic uses the key of the map (e.g., "default" from mcp.filesystem.roots.default.path)
        Path actualRootPath = resolvedRoots.get(rootAlias);
        if (actualRootPath == null) {
            // For better usability, check if the provided alias matches any configured alias values
            String matchedKey = this.properties.getRoots().entrySet().stream()
                    .filter(entry -> rootAlias.equals(entry.getValue().getAlias()))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
            if (matchedKey != null) {
                actualRootPath = resolvedRoots.get(matchedKey);
            }

            if (actualRootPath == null) {
                throw new IllegalArgumentException("Invalid rootAlias: '" + rootAlias + "'. Available configured root aliases: " +
                        this.properties.getRoots().values().stream().map(FilesystemToolProperties.RootConfig::getAlias).collect(Collectors.toSet()));
            }
        }

        Path subPath = Paths.get(subPathStr == null ? "" : subPathStr).normalize();
        if (subPath.isAbsolute()) {
            throw new IllegalArgumentException("Sub-path must be relative for security: " + subPathStr);
        }

        Path resolvedPath = actualRootPath.resolve(subPath).normalize();

        if (!resolvedPath.startsWith(actualRootPath)) {
            logger.warn("Attempt to access path outside of the allowed root directory. Root Alias: '{}', Root Path: {}, Resolved Path: {}",
                    rootAlias, actualRootPath, resolvedPath);
            throw new SecurityException("Path traversal attempt detected. Access denied.");
        }
        return resolvedPath;
    }

    @Tool(name = "list_files",
            description = "Lists files and directories within a configured and aliased filesystem root. Provide rootAlias (e.g., 'default') and an optional relative subPath.")
    public Map<String, Object> listFiles(ListFilesInput input) {
        logger.info("FilesystemTool: Executing list_files with input: {}", input);
        if (input.rootAlias() == null || input.rootAlias().trim().isEmpty()) {
            return Map.of("error", "rootAlias cannot be empty.");
        }
        try {
            Path targetPath = resolvePath(input.rootAlias(), input.subPath());

            if (!Files.exists(targetPath)) {
                return Map.of("error", "Path does not exist: " + targetPath.toString());
            }
            if (!Files.isDirectory(targetPath)) {
                return Map.of("error", "Path is not a directory: " + targetPath.toString());
            }

            try (Stream<Path> stream = Files.list(targetPath)) {
                List<String> items = stream
                        .map(path -> (Files.isDirectory(path) ? "D: " : "F: ") + path.getFileName().toString())
                        .collect(Collectors.toList());
                return Map.of("path", targetPath.toString(), "items", items, "status", "Successfully listed files.");
            }
        } catch (SecurityException | IllegalArgumentException e) {
            logger.warn("Access or argument error in list_files for input {}: {}", input, e.getMessage());
            return Map.of("error", e.getMessage());
        } catch (IOException e) {
            logger.error("IO error in list_files for input {}: {}", input, e.getMessage(), e);
            return Map.of("error", "IO Error: " + e.getMessage());
        }
    }

    @Tool(name = "read_file",
            description = "Reads the content of a text file from a configured and aliased filesystem root. Provide rootAlias (e.g., 'default') and the relative filePath.")
    public Map<String, Object> readFile(ReadFileInput input) {
        logger.info("FilesystemTool: Executing read_file with input: {}", input);
        if (input.rootAlias() == null || input.rootAlias().trim().isEmpty() ||
                input.filePath() == null || input.filePath().trim().isEmpty()) {
            return Map.of("error", "rootAlias and filePath cannot be empty.");
        }

        try {
            Path targetFile = resolvePath(input.rootAlias(), input.filePath());

            if (!Files.exists(targetFile)) {
                return Map.of("error", "File does not exist: " + targetFile.toString());
            }
            if (!Files.isRegularFile(targetFile)) {
                return Map.of("error", "Path is not a regular file: " + targetFile.toString());
            }
            if (!Files.isReadable(targetFile)){
                return Map.of("error", "File is not readable: " + targetFile.toString());
            }

            long fileSize = Files.size(targetFile);
            if (fileSize > 10 * 1024 * 1024) { // Increased limit to 10MB, adjust as needed
                return Map.of("error", "File is too large to read (max 10MB): " + targetFile.toString() + ", size: " + fileSize + " bytes");
            }

            String content = Files.readString(targetFile);
            return Map.of("file_path", targetFile.toString(), "content_length", content.length(), "status", "Successfully read file. Preview: " + content.substring(0, Math.min(content.length(), 200)) + (content.length() > 200 ? "..." : ""));
            // For security and brevity, avoid returning full large file content directly in a general tool response
            // unless specifically designed for it. Consider returning a path or a handle.
            // For this example, returning a preview.

        } catch (SecurityException | IllegalArgumentException e) {
            logger.warn("Access or argument error in read_file for input {}: {}", input, e.getMessage());
            return Map.of("error", e.getMessage());
        } catch (IOException e) {
            logger.error("IO error in read_file for input {}: {}", input, e.getMessage(), e);
            return Map.of("error", "IO Error: " + e.getMessage());
        }
    }
}