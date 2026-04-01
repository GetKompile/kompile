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

package ai.kompile.chat.history.controller;

import ai.kompile.chat.history.domain.ChatFolder;
import ai.kompile.chat.history.domain.ChatSession;
import ai.kompile.chat.history.domain.FolderFile;
import ai.kompile.chat.history.dto.*;
import ai.kompile.chat.history.service.FolderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * REST controller for folder operations.
 * Manages folders for organizing chat sessions with associated files.
 */
@Slf4j
@RestController
@RequestMapping("/api/folders")
@RequiredArgsConstructor
@ConditionalOnClass(name = "ai.kompile.chat.history.service.ChatHistoryService")
@ConditionalOnProperty(name = "kompile.chat.history.enabled", havingValue = "true", matchIfMissing = true)
public class FolderController {

    private final FolderService folderService;

    // ═══════════════════════════════════════════════════════════════════════════════
    // FOLDER CRUD
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Create a new folder.
     */
    @PostMapping
    public ResponseEntity<FolderDto> createFolder(@RequestBody CreateFolderRequest request) {
        log.info("Creating new folder: {}", request.getName());
        ChatFolder folder = folderService.createFolder(
            request.getName(),
            request.getDescription(),
            request.getUserId()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(FolderDto.fromEntity(folder, false));
    }

    /**
     * Get all folders.
     */
    @GetMapping
    public ResponseEntity<List<FolderDto>> getFolders(
        @RequestParam(name = "userId", required = false) String userId
    ) {
        log.debug("Getting folders for user: {}", userId);
        List<ChatFolder> folders = folderService.getUserFolders(userId);
        List<FolderDto> dtos = folders.stream()
            .map(f -> FolderDto.fromEntity(f, false))
            .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    /**
     * Get a specific folder with files.
     */
    @GetMapping("/{folderId}")
    public ResponseEntity<FolderDto> getFolder(@PathVariable String folderId) {
        log.debug("Getting folder: {}", folderId);
        return folderService.getFolder(folderId)
            .map(folder -> ResponseEntity.ok(FolderDto.fromEntity(folder, true)))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Update a folder.
     */
    @PutMapping("/{folderId}")
    public ResponseEntity<FolderDto> updateFolder(
        @PathVariable String folderId,
        @RequestBody UpdateFolderRequest request
    ) {
        log.info("Updating folder: {}", folderId);
        return folderService.updateFolder(folderId, request.getName(), request.getDescription())
            .map(folder -> ResponseEntity.ok(FolderDto.fromEntity(folder, false)))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Delete a folder and all its files.
     */
    @DeleteMapping("/{folderId}")
    public ResponseEntity<Void> deleteFolder(@PathVariable String folderId) {
        log.info("Deleting folder: {}", folderId);
        boolean deleted = folderService.deleteFolder(folderId);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // FILE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Upload a file to a folder.
     */
    @PostMapping("/{folderId}/files")
    public ResponseEntity<?> uploadFile(
        @PathVariable String folderId,
        @RequestParam("file") MultipartFile file
    ) {
        log.info("Uploading file {} to folder {}", file.getOriginalFilename(), folderId);
        try {
            FolderFile folderFile = folderService.addFileToFolder(folderId, file);
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(FolderFileDto.fromEntity(folderFile));
        } catch (IllegalArgumentException e) {
            log.error("Invalid upload request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IOException e) {
            log.error("Failed to upload file: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Failed to upload file: " + e.getMessage());
        }
    }

    /**
     * Upload multiple files to a folder.
     */
    @PostMapping("/{folderId}/files/batch")
    public ResponseEntity<BatchFileUploadResponse> uploadFiles(
        @PathVariable String folderId,
        @RequestParam("files") List<MultipartFile> files
    ) {
        log.info("Uploading {} files to folder {}", files.size(), folderId);
        List<FolderFileDto> uploaded = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (MultipartFile file : files) {
            try {
                FolderFile folderFile = folderService.addFileToFolder(folderId, file);
                uploaded.add(FolderFileDto.fromEntity(folderFile));
            } catch (Exception e) {
                errors.add(file.getOriginalFilename() + ": " + e.getMessage());
            }
        }

        BatchFileUploadResponse response = BatchFileUploadResponse.builder()
            .successCount(uploaded.size())
            .failedCount(errors.size())
            .files(uploaded)
            .errors(errors)
            .build();

        return ResponseEntity.ok(response);
    }

    /**
     * Get all files in a folder.
     */
    @GetMapping("/{folderId}/files")
    public ResponseEntity<List<FolderFileDto>> getFolderFiles(@PathVariable String folderId) {
        log.debug("Getting files for folder: {}", folderId);
        List<FolderFile> files = folderService.getFolderFiles(folderId);
        List<FolderFileDto> dtos = files.stream()
            .map(FolderFileDto::fromEntity)
            .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    /**
     * Delete a file from a folder.
     */
    @DeleteMapping("/{folderId}/files/{fileId}")
    public ResponseEntity<Void> deleteFile(
        @PathVariable String folderId,
        @PathVariable String fileId
    ) {
        log.info("Deleting file {} from folder {}", fileId, folderId);
        try {
            boolean deleted = folderService.removeFileFromFolder(folderId, fileId);
            return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Download a file from a folder.
     */
    @GetMapping("/{folderId}/files/{fileId}/download")
    public ResponseEntity<Resource> downloadFile(
        @PathVariable String folderId,
        @PathVariable String fileId
    ) {
        log.debug("Downloading file {} from folder {}", fileId, folderId);

        return folderService.getFile(fileId)
            .map(file -> {
                // Verify file belongs to folder
                if (!file.getFolder().getFolderId().equals(folderId)) {
                    return ResponseEntity.notFound().<Resource>build();
                }

                try {
                    Path filePath = folderService.getFilePath(file);
                    Resource resource = new UrlResource(filePath.toUri());

                    if (resource.exists() && resource.isReadable()) {
                        return ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType(
                                file.getMimeType() != null ? file.getMimeType() : "application/octet-stream"))
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + file.getFileName() + "\"")
                            .body(resource);
                    } else {
                        log.error("File not found on disk: {}", filePath);
                        return ResponseEntity.notFound().<Resource>build();
                    }
                } catch (MalformedURLException e) {
                    log.error("Invalid file path: {}", e.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).<Resource>build();
                }
            })
            .orElse(ResponseEntity.notFound().build());
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SESSION ASSOCIATION
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Associate a session with a folder.
     */
    @PostMapping("/{folderId}/sessions/{sessionId}")
    public ResponseEntity<ChatSessionDto> associateSession(
        @PathVariable String folderId,
        @PathVariable String sessionId
    ) {
        log.info("Associating session {} with folder {}", sessionId, folderId);
        try {
            ChatSession session = folderService.associateSessionWithFolder(sessionId, folderId);
            return ResponseEntity.ok(ChatSessionDto.fromEntity(session, false));
        } catch (IllegalArgumentException e) {
            log.error("Failed to associate session: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Disassociate a session from a folder.
     */
    @DeleteMapping("/{folderId}/sessions/{sessionId}")
    public ResponseEntity<Void> disassociateSession(
        @PathVariable String folderId,
        @PathVariable String sessionId
    ) {
        log.info("Disassociating session {} from folder {}", sessionId, folderId);
        try {
            folderService.disassociateSessionFromFolder(sessionId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Get all sessions associated with a folder.
     */
    @GetMapping("/{folderId}/sessions")
    public ResponseEntity<List<ChatSessionDto>> getFolderSessions(@PathVariable String folderId) {
        log.debug("Getting sessions for folder: {}", folderId);
        try {
            List<ChatSession> sessions = folderService.getFolderSessions(folderId);
            List<ChatSessionDto> dtos = sessions.stream()
                .map(s -> ChatSessionDto.fromEntity(s, false))
                .collect(Collectors.toList());
            return ResponseEntity.ok(dtos);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // FILE CONTEXT
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Get folder context for prompt injection.
     */
    @GetMapping("/{folderId}/context")
    public ResponseEntity<FolderContextDto> getFolderContext(@PathVariable String folderId) {
        log.debug("Getting context for folder: {}", folderId);

        return folderService.getFolder(folderId)
            .map(folder -> {
                List<String> filePaths = folderService.getFolderFilePaths(folderId);
                String promptPrefix = folderService.buildFileContextPrompt(folderId);

                FolderContextDto context = FolderContextDto.builder()
                    .folderId(folderId)
                    .filePaths(filePaths)
                    .promptPrefix(promptPrefix)
                    .build();

                return ResponseEntity.ok(context);
            })
            .orElse(ResponseEntity.notFound().build());
    }
}
