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

package ai.kompile.app.services.mcp;

import ai.kompile.tool.filesystem.FilesystemToolImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Map;

/**
 * Registers undo handlers for filesystem write operations.
 * This enables the action log to undo file writes, deletes, and directory creations.
 */
@Component
public class FilesystemUndoHandlerRegistrar {

    private static final Logger logger = LoggerFactory.getLogger(FilesystemUndoHandlerRegistrar.class);

    private final McpActionLogService actionLogService;
    private final FilesystemToolImpl filesystemToolImpl;

    @Autowired
    public FilesystemUndoHandlerRegistrar(McpActionLogService actionLogService,
                                           FilesystemToolImpl filesystemToolImpl) {
        this.actionLogService = actionLogService;
        this.filesystemToolImpl = filesystemToolImpl;
    }

    @PostConstruct
    public void registerHandlers() {
        logger.info("Registering filesystem undo handlers...");

        // Register undo handler for write_file
        actionLogService.registerUndoHandler("write_file", (action, undoData) -> {
            Map<String, Object> args = action.getArguments();
            Object result = action.getResult();

            String rootAlias = (String) args.get("rootAlias");
            String filePath = (String) args.get("filePath");

            if (rootAlias == null || filePath == null) {
                return Map.of("status", "error", "error", "Missing rootAlias or filePath in original action");
            }

            // Get undo information from the result
            if (result instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> resultMap = (Map<String, Object>) result;

                Boolean fileExistedBefore = (Boolean) resultMap.get("fileExistedBefore");
                String previousContent = (String) resultMap.get("previousContent");
                Boolean previousContentTooLarge = (Boolean) resultMap.get("previousContentTooLarge");

                if (previousContentTooLarge != null && previousContentTooLarge) {
                    return Map.of("status", "error",
                            "error", "Cannot undo: previous file content was too large to backup");
                }

                if (fileExistedBefore != null && fileExistedBefore) {
                    // File existed before - restore previous content
                    if (previousContent != null) {
                        FilesystemToolImpl.WriteFileInput undoInput =
                                new FilesystemToolImpl.WriteFileInput(rootAlias, filePath, previousContent, false);
                        Map<String, Object> undoResult = filesystemToolImpl.writeFile(undoInput);

                        if ("success".equals(undoResult.get("status"))) {
                            return Map.of("status", "success",
                                    "message", "File restored to previous content",
                                    "filePath", filePath);
                        } else {
                            return Map.of("status", "error",
                                    "error", "Failed to restore file: " + undoResult.get("error"));
                        }
                    } else {
                        return Map.of("status", "error",
                                "error", "Cannot undo: no previous content stored");
                    }
                } else {
                    // File was newly created - delete it
                    FilesystemToolImpl.DeleteFileInput undoInput =
                            new FilesystemToolImpl.DeleteFileInput(rootAlias, filePath);
                    Map<String, Object> undoResult = filesystemToolImpl.deleteFile(undoInput);

                    if ("success".equals(undoResult.get("status"))) {
                        return Map.of("status", "success",
                                "message", "Newly created file deleted",
                                "filePath", filePath);
                    } else {
                        return Map.of("status", "error",
                                "error", "Failed to delete file: " + undoResult.get("error"));
                    }
                }
            }

            return Map.of("status", "error", "error", "Cannot undo: missing undo information in result");
        });

        // Register undo handler for delete_file
        actionLogService.registerUndoHandler("delete_file", (action, undoData) -> {
            Map<String, Object> args = action.getArguments();
            Object result = action.getResult();

            String rootAlias = (String) args.get("rootAlias");
            String filePath = (String) args.get("filePath");

            if (rootAlias == null || filePath == null) {
                return Map.of("status", "error", "error", "Missing rootAlias or filePath in original action");
            }

            // Get undo information from the result
            if (result instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> resultMap = (Map<String, Object>) result;

                Boolean wasDirectory = (Boolean) resultMap.get("wasDirectory");
                String previousContent = (String) resultMap.get("previousContent");
                Boolean previousContentTooLarge = (Boolean) resultMap.get("previousContentTooLarge");

                if (previousContentTooLarge != null && previousContentTooLarge) {
                    return Map.of("status", "error",
                            "error", "Cannot undo: deleted file content was too large to backup");
                }

                if (wasDirectory != null && wasDirectory) {
                    // Was a directory - recreate it
                    FilesystemToolImpl.CreateDirectoryInput undoInput =
                            new FilesystemToolImpl.CreateDirectoryInput(rootAlias, filePath);
                    Map<String, Object> undoResult = filesystemToolImpl.createDirectory(undoInput);

                    if ("success".equals(undoResult.get("status"))) {
                        return Map.of("status", "success",
                                "message", "Directory restored",
                                "directoryPath", filePath);
                    } else {
                        return Map.of("status", "error",
                                "error", "Failed to recreate directory: " + undoResult.get("error"));
                    }
                } else {
                    // Was a file - restore it with previous content
                    if (previousContent != null) {
                        FilesystemToolImpl.WriteFileInput undoInput =
                                new FilesystemToolImpl.WriteFileInput(rootAlias, filePath, previousContent, false);
                        Map<String, Object> undoResult = filesystemToolImpl.writeFile(undoInput);

                        if ("success".equals(undoResult.get("status"))) {
                            return Map.of("status", "success",
                                    "message", "Deleted file restored",
                                    "filePath", filePath);
                        } else {
                            return Map.of("status", "error",
                                    "error", "Failed to restore file: " + undoResult.get("error"));
                        }
                    } else {
                        return Map.of("status", "error",
                                "error", "Cannot undo: no previous content stored for deleted file");
                    }
                }
            }

            return Map.of("status", "error", "error", "Cannot undo: missing undo information in result");
        });

        // Register undo handler for create_directory
        actionLogService.registerUndoHandler("create_directory", (action, undoData) -> {
            Map<String, Object> args = action.getArguments();
            Object result = action.getResult();

            String rootAlias = (String) args.get("rootAlias");
            String directoryPath = (String) args.get("directoryPath");

            if (rootAlias == null || directoryPath == null) {
                return Map.of("status", "error", "error", "Missing rootAlias or directoryPath in original action");
            }

            // Check if directory was newly created or already existed
            if (result instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> resultMap = (Map<String, Object>) result;

                Boolean alreadyExisted = (Boolean) resultMap.get("alreadyExisted");

                if (alreadyExisted != null && alreadyExisted) {
                    return Map.of("status", "success",
                            "message", "Directory already existed before creation, nothing to undo",
                            "directoryPath", directoryPath);
                }

                // Directory was newly created - delete it
                FilesystemToolImpl.DeleteFileInput undoInput =
                        new FilesystemToolImpl.DeleteFileInput(rootAlias, directoryPath);
                Map<String, Object> undoResult = filesystemToolImpl.deleteFile(undoInput);

                if ("success".equals(undoResult.get("status"))) {
                    return Map.of("status", "success",
                            "message", "Newly created directory deleted",
                            "directoryPath", directoryPath);
                } else {
                    // Directory might not be empty
                    String error = undoResult.get("error") != null ? undoResult.get("error").toString() : "Unknown error";
                    if (error.contains("non-empty")) {
                        return Map.of("status", "error",
                                "error", "Cannot undo: directory is no longer empty. Files may have been added to it.",
                                "directoryPath", directoryPath);
                    }
                    return Map.of("status", "error",
                            "error", "Failed to delete directory: " + error);
                }
            }

            return Map.of("status", "error", "error", "Cannot undo: missing undo information in result");
        });

        logger.info("Filesystem undo handlers registered: write_file, delete_file, create_directory");
    }
}
