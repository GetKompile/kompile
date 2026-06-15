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

package ai.kompile.tool.filesystem; // New package

import ai.kompile.core.mcp.optimization.McpOptimizationConfig;
import ai.kompile.core.mcp.optimization.McpOptimizationConfigProvider;
import ai.kompile.core.mcp.optimization.ResultReferenceCache;
import ai.kompile.tool.filesystem.config.FilesystemToolConfigService;
import ai.kompile.tool.filesystem.config.FilesystemToolProperties;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component // This bean will be discovered by the main application if this module is on classpath
public class FilesystemToolImpl { // Renamed class for convention, original name "FilesystemTool" is also fine

    private static final Logger logger = LoggerFactory.getLogger(FilesystemToolImpl.class);
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    private final FilesystemToolConfigService configService;
    private final McpOptimizationConfigProvider optimizationProvider;
    private final ResultReferenceCache resultCache;
    private volatile Map<String, Path> resolvedRoots;

    // Input DTOs are inner records, which is fine.
    public record ListFilesInput(String rootAlias, String subPath) {}
    public record ReadFileInput(String rootAlias, String filePath) {}
    public record WriteFileInput(String rootAlias, String filePath, String content, Boolean append) {}
    public record DeleteFileInput(String rootAlias, String filePath) {}
    public record CreateDirectoryInput(String rootAlias, String directoryPath) {}
    public record UndoTokenInput(String undoToken) {}

    public FilesystemToolImpl(FilesystemToolConfigService configService,
                              @Autowired(required = false) McpOptimizationConfigProvider optimizationProvider,
                              @Autowired(required = false) ResultReferenceCache resultCache) {
        this.configService = configService;
        this.optimizationProvider = optimizationProvider != null
                ? optimizationProvider
                : McpOptimizationConfigProvider.ofDefaults();
        this.resultCache = resultCache;
        logger.debug("FilesystemToolImpl constructed.");
    }

    @PostConstruct
    public void init() {
        logger.info("Initializing FilesystemToolImpl...");
        Map<String, FilesystemToolProperties.RootConfig> roots = configService.getRoots();
        if (roots == null || roots.isEmpty()) {
            logger.warn("No filesystem roots configured for FilesystemToolImpl. Tool will be non-functional.");
            this.resolvedRoots = Collections.emptyMap(); // Initialize to empty map
            return;
        }
        this.resolvedRoots = roots.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey, // The key from config (e.g., "default")
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
                                    if ("default".equals(rootConfig.getAlias()) || roots.size() == 1) {
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
        // The rootAlias in the input (e.g., "default") should match the key in the config roots map,
        // or the 'alias' field in RootConfig.
        Path actualRootPath = resolvedRoots.get(rootAlias);
        if (actualRootPath == null) {
            // For better usability, check if the provided alias matches any configured alias values
            Map<String, FilesystemToolProperties.RootConfig> roots = configService.getRoots();
            String matchedKey = roots.entrySet().stream()
                    .filter(entry -> rootAlias.equals(entry.getValue().getAlias()))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
            if (matchedKey != null) {
                actualRootPath = resolvedRoots.get(matchedKey);
            }

            if (actualRootPath == null) {
                throw new IllegalArgumentException("Invalid rootAlias: '" + rootAlias + "'. Available configured root aliases: " +
                        roots.values().stream().map(FilesystemToolProperties.RootConfig::getAlias).collect(Collectors.toSet()));
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

    @Tool(name = "write_file",
            description = "Writes content to a file in a configured filesystem root. Provide rootAlias (e.g., 'default'), relative filePath, and content. " +
                    "Set append=true to append to existing file, otherwise it overwrites. Returns an undo_token — pass it to fs_undo to revert.")
    public Map<String, Object> writeFile(WriteFileInput input) {
        logger.info("FilesystemTool: Executing write_file with input: rootAlias={}, filePath={}, contentLength={}, append={}",
                input.rootAlias(), input.filePath(),
                input.content() != null ? input.content().length() : 0,
                input.append());

        if (input.rootAlias() == null || input.rootAlias().trim().isEmpty() ||
                input.filePath() == null || input.filePath().trim().isEmpty()) {
            return Map.of("error", "rootAlias and filePath cannot be empty.");
        }

        if (input.content() == null) {
            return Map.of("error", "content cannot be null. Use empty string to create empty file.");
        }

        try {
            Path targetFile = resolvePath(input.rootAlias(), input.filePath());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("file_path", targetFile.toString());

            // Capture previous content for undo. Route through the result-reference
            // cache when optimization is enabled so the response payload doesn't
            // balloon to 10MB on large overwrites.
            String previousContent = null;
            boolean fileExisted = Files.exists(targetFile);
            if (fileExisted && Files.isRegularFile(targetFile)) {
                try {
                    long fileSize = Files.size(targetFile);
                    if (fileSize <= MAX_FILE_BYTES) {
                        previousContent = Files.readString(targetFile);
                        result.put("previousContentLength", previousContent.length());
                    } else {
                        result.put("previousContentLength", fileSize);
                        result.put("previousContentTooLarge", true);
                    }
                } catch (IOException e) {
                    logger.warn("Could not read previous content for undo: {}", e.getMessage());
                }
            }
            result.put("fileExistedBefore", fileExisted);

            stashUndoOrInlineContent(previousContent, targetFile.toString(), result);

            // Create parent directories if needed
            Path parent = targetFile.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
                result.put("createdParentDirectories", true);
            }

            // Write the file
            boolean append = input.append() != null && input.append();
            if (append && fileExisted) {
                Files.writeString(targetFile, input.content(), StandardOpenOption.APPEND);
                result.put("operation", "appended");
            } else {
                Files.writeString(targetFile, input.content());
                result.put("operation", fileExisted ? "overwritten" : "created");
            }

            result.put("status", "success");
            result.put("bytesWritten", input.content().length());

            return result;

        } catch (SecurityException | IllegalArgumentException e) {
            logger.warn("Access or argument error in write_file for input {}: {}", input, e.getMessage());
            return Map.of("error", e.getMessage());
        } catch (IOException e) {
            logger.error("IO error in write_file for input {}: {}", input, e.getMessage(), e);
            return Map.of("error", "IO Error: " + e.getMessage());
        }
    }

    @Tool(name = "delete_file",
            description = "Deletes a file or empty directory from a configured filesystem root. Provide rootAlias (e.g., 'default') and relative filePath. " +
                    "Returns an undo_token for files — pass it to fs_undo to restore.")
    public Map<String, Object> deleteFile(DeleteFileInput input) {
        logger.info("FilesystemTool: Executing delete_file with input: {}", input);

        if (input.rootAlias() == null || input.rootAlias().trim().isEmpty() ||
                input.filePath() == null || input.filePath().trim().isEmpty()) {
            return Map.of("error", "rootAlias and filePath cannot be empty.");
        }

        try {
            Path targetPath = resolvePath(input.rootAlias(), input.filePath());

            if (!Files.exists(targetPath)) {
                return Map.of("error", "Path does not exist: " + targetPath.toString());
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("deleted_path", targetPath.toString());

            boolean wasDirectory = Files.isDirectory(targetPath);
            result.put("wasDirectory", wasDirectory);

            // Capture previous content for undo (only for files). Route through the
            // result-reference cache when optimization is enabled so the response
            // payload doesn't inline the entire file.
            String previousContent = null;
            if (!wasDirectory) {
                try {
                    long fileSize = Files.size(targetPath);
                    if (fileSize <= MAX_FILE_BYTES) {
                        previousContent = Files.readString(targetPath);
                        result.put("previousContentLength", previousContent.length());
                    } else {
                        result.put("previousContentLength", fileSize);
                        result.put("previousContentTooLarge", true);
                        result.put("warning", "File content was too large to backup for undo");
                    }
                } catch (IOException e) {
                    logger.warn("Could not read previous content for undo: {}", e.getMessage());
                    result.put("warning", "Could not backup content for undo: " + e.getMessage());
                }
                stashUndoOrInlineContent(previousContent, targetPath.toString(), result);
            }

            // Delete the file/directory
            if (wasDirectory) {
                try (Stream<Path> entries = Files.list(targetPath)) {
                    if (entries.findAny().isPresent()) {
                        return Map.of("error", "Cannot delete non-empty directory: " + targetPath.toString());
                    }
                }
            }

            Files.delete(targetPath);
            result.put("status", "success");

            return result;

        } catch (SecurityException | IllegalArgumentException e) {
            logger.warn("Access or argument error in delete_file for input {}: {}", input, e.getMessage());
            return Map.of("error", e.getMessage());
        } catch (IOException e) {
            logger.error("IO error in delete_file for input {}: {}", input, e.getMessage(), e);
            return Map.of("error", "IO Error: " + e.getMessage());
        }
    }

    /**
     * Routes previous content to either the result-reference cache (when
     * optimization is enabled) or inlines it (when disabled / cache is absent).
     */
    private void stashUndoOrInlineContent(String previousContent, String filePath, Map<String, Object> result) {
        if (previousContent == null) {
            return;
        }
        McpOptimizationConfig cfg = optimizationProvider.getConfiguration();
        boolean useCache = resultCache != null
                && Boolean.TRUE.equals(cfg.getEnabled())
                && Boolean.TRUE.equals(cfg.getFilesystemStorePreviousContentInCache());
        if (useCache) {
            String token = resultCache.storeFilesystemUndo(filePath, previousContent);
            result.put("undo_token", token);
            result.put("undoHint", "Call fs_undo with this token to restore, or fs_get_previous_content to fetch the raw content.");
        } else {
            result.put("previousContent", previousContent);
        }
    }

    @Tool(name = "fs_undo",
            description = "Restores a file to its pre-write-or-delete state using an undo_token returned by write_file or delete_file.")
    public Map<String, Object> fsUndo(UndoTokenInput input) {
        if (input == null || input.undoToken() == null || input.undoToken().isBlank()) {
            return Map.of("error", "undoToken cannot be empty.");
        }
        if (resultCache == null) {
            return Map.of("error", "Undo cache is not available; MCP optimization is disabled.");
        }
        Optional<Map<String, Object>> entryOpt = resultCache.getFilesystemUndo(input.undoToken());
        if (entryOpt.isEmpty()) {
            return Map.of("error", "Unknown or expired undo_token: " + input.undoToken());
        }
        Map<String, Object> entry = entryOpt.get();
        String filePath = (String) entry.get("filePath");
        String previousContent = (String) entry.get("previousContent");
        if (filePath == null) {
            return Map.of("error", "Undo entry is missing a filePath.");
        }
        try {
            Path target = Paths.get(filePath);
            Path parent = target.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            if (previousContent == null) {
                // Original state was "file did not exist" — the undo is a delete.
                if (Files.exists(target)) {
                    Files.delete(target);
                }
            } else {
                Files.writeString(target, previousContent);
            }
            resultCache.invalidateFilesystemUndo(input.undoToken());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("restored_path", filePath);
            result.put("bytesRestored", previousContent == null ? 0 : previousContent.length());
            return result;
        } catch (IOException e) {
            logger.error("fs_undo IO error for token {}: {}", input.undoToken(), e.getMessage(), e);
            return Map.of("error", "IO Error: " + e.getMessage());
        }
    }

    @Tool(name = "fs_get_previous_content",
            description = "Returns the full previous content associated with an undo_token without restoring the file.")
    public Map<String, Object> fsGetPreviousContent(UndoTokenInput input) {
        if (input == null || input.undoToken() == null || input.undoToken().isBlank()) {
            return Map.of("error", "undoToken cannot be empty.");
        }
        if (resultCache == null) {
            return Map.of("error", "Undo cache is not available; MCP optimization is disabled.");
        }
        Optional<Map<String, Object>> entryOpt = resultCache.getFilesystemUndo(input.undoToken());
        if (entryOpt.isEmpty()) {
            return Map.of("error", "Unknown or expired undo_token: " + input.undoToken());
        }
        Map<String, Object> entry = entryOpt.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file_path", entry.get("filePath"));
        result.put("previousContent", entry.get("previousContent"));
        result.put("status", "success");
        return result;
    }

    @Tool(name = "create_directory",
            description = "Creates a directory in a configured filesystem root. Provide rootAlias (e.g., 'default') and relative directoryPath. " +
                    "Creates parent directories as needed.")
    public Map<String, Object> createDirectory(CreateDirectoryInput input) {
        logger.info("FilesystemTool: Executing create_directory with input: {}", input);

        if (input.rootAlias() == null || input.rootAlias().trim().isEmpty() ||
                input.directoryPath() == null || input.directoryPath().trim().isEmpty()) {
            return Map.of("error", "rootAlias and directoryPath cannot be empty.");
        }

        try {
            Path targetDir = resolvePath(input.rootAlias(), input.directoryPath());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("directory_path", targetDir.toString());

            if (Files.exists(targetDir)) {
                if (Files.isDirectory(targetDir)) {
                    result.put("status", "success");
                    result.put("message", "Directory already exists");
                    result.put("alreadyExisted", true);
                    return result;
                } else {
                    return Map.of("error", "Path exists but is not a directory: " + targetDir.toString());
                }
            }

            // Create the directory and any necessary parent directories
            Files.createDirectories(targetDir);

            result.put("status", "success");
            result.put("message", "Directory created successfully");
            result.put("alreadyExisted", false);

            return result;

        } catch (SecurityException | IllegalArgumentException e) {
            logger.warn("Access or argument error in create_directory for input {}: {}", input, e.getMessage());
            return Map.of("error", e.getMessage());
        } catch (IOException e) {
            logger.error("IO error in create_directory for input {}: {}", input, e.getMessage(), e);
            return Map.of("error", "IO Error: " + e.getMessage());
        }
    }
}