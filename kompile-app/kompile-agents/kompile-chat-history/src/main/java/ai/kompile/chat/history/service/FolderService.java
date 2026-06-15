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

package ai.kompile.chat.history.service;

import ai.kompile.chat.history.domain.ChatFolder;
import ai.kompile.chat.history.domain.ChatSession;
import ai.kompile.chat.history.domain.FolderFile;
import ai.kompile.chat.history.repository.ChatFolderRepository;
import ai.kompile.chat.history.repository.ChatSessionRepository;
import ai.kompile.chat.history.repository.FolderFileRepository;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing folders and their files.
 * Provides CRUD operations for folders, file upload/download, and session association.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
@ConditionalOnClass(name = "ai.kompile.chat.history.service.ChatHistoryService")
@ConditionalOnProperty(name = "kompile.chat.history.enabled", havingValue = "true", matchIfMissing = true)
public class FolderService {


    @Autowired
    private final ChatFolderRepository folderRepository;
    @Autowired
    private final FolderFileRepository fileRepository;
    @Autowired
    private final ChatSessionRepository sessionRepository;

    @Value("${kompile.folders.base-path:./data/folders}")
    private String foldersBasePath;

    @Value("${kompile.folders.max-file-size:52428800}")  // 50MB default
    private long maxFileSize;

    private Path basePath;

    @PostConstruct
    public void init() throws IOException {
        basePath = Paths.get(foldersBasePath).toAbsolutePath().normalize();
        Files.createDirectories(basePath);
        log.info("Folder storage initialized at: {}", basePath);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // FOLDER CRUD OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Create a new folder.
     */
    @Transactional
    public ChatFolder createFolder(String name, String description, String userId) {
        String folderId = UUID.randomUUID().toString();

        ChatFolder folder = ChatFolder.builder()
            .folderId(folderId)
            .name(name != null ? name.trim() : "New Folder")
            .description(description)
            .userId(userId)
            .build();

        // Create directory for this folder
        try {
            Path folderDir = basePath.resolve(folderId);
            Files.createDirectories(folderDir);
        } catch (IOException e) {
            log.error("Failed to create folder directory for {}: {}", folderId, e.getMessage());
            throw new RuntimeException("Failed to create folder directory", e);
        }

        ChatFolder saved = folderRepository.save(folder);
        log.info("Created folder: {} ({})", name, folderId);
        return saved;
    }

    /**
     * Get a folder by its ID.
     */
    @Transactional(readOnly = true)
    public Optional<ChatFolder> getFolder(String folderId) {
        return folderRepository.findByFolderId(folderId);
    }

    /**
     * Get all folders for a specific user.
     */
    @Transactional(readOnly = true)
    public List<ChatFolder> getUserFolders(String userId) {
        if (userId == null || userId.isEmpty()) {
            return folderRepository.findAllByOrderByUpdatedAtDesc();
        }
        return folderRepository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    /**
     * Get all folders.
     */
    @Transactional(readOnly = true)
    public List<ChatFolder> getAllFolders() {
        return folderRepository.findAllByOrderByUpdatedAtDesc();
    }

    /**
     * Update a folder's name and description.
     */
    @Transactional
    public Optional<ChatFolder> updateFolder(String folderId, String name, String description) {
        Optional<ChatFolder> folderOpt = folderRepository.findByFolderId(folderId);
        folderOpt.ifPresent(folder -> {
            if (name != null && !name.trim().isEmpty()) {
                folder.setName(name.trim());
            }
            if (description != null) {
                folder.setDescription(description.trim());
            }
            folderRepository.save(folder);
            log.info("Updated folder: {}", folderId);
        });
        return folderOpt;
    }

    /**
     * Delete a folder and all its files.
     */
    @Transactional
    public boolean deleteFolder(String folderId) {
        Optional<ChatFolder> folderOpt = folderRepository.findByFolderId(folderId);
        if (folderOpt.isEmpty()) {
            return false;
        }

        ChatFolder folder = folderOpt.get();

        // Disassociate all sessions from this folder
        for (ChatSession session : new ArrayList<>(folder.getSessions())) {
            session.setFolder(null);
            sessionRepository.save(session);
        }

        // Delete folder directory and all files
        try {
            Path folderDir = basePath.resolve(folderId);
            if (Files.exists(folderDir)) {
                Files.walk(folderDir)
                    .sorted((a, b) -> -a.compareTo(b))  // Delete files before directories
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete: {}", path);
                        }
                    });
            }
        } catch (IOException e) {
            log.error("Failed to delete folder directory for {}: {}", folderId, e.getMessage());
        }

        // Delete from database (cascades to files)
        folderRepository.delete(folder);
        log.info("Deleted folder: {}", folderId);
        return true;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // FILE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Upload a file to a folder.
     */
    @Transactional
    public FolderFile addFileToFolder(String folderId, MultipartFile file) throws IOException {
        ChatFolder folder = folderRepository.findByFolderId(folderId)
            .orElseThrow(() -> new IllegalArgumentException("Folder not found: " + folderId));

        // Validate file size
        if (file.getSize() > maxFileSize) {
            throw new IllegalArgumentException("File exceeds maximum size of " + (maxFileSize / 1024 / 1024) + "MB");
        }

        // Sanitize filename
        String originalName = Objects.requireNonNullElse(file.getOriginalFilename(), "uploaded_file");
        String sanitizedName = sanitizeFileName(originalName);

        // Create folder directory if it doesn't exist
        Path folderDir = basePath.resolve(folderId);
        Files.createDirectories(folderDir);

        // Handle duplicate filenames
        Path targetPath = resolveUniquePath(folderDir, sanitizedName);

        // Copy file atomically
        Path tempFile = Files.createTempFile(folderDir, "upload-", ".tmp");
        try (InputStream is = file.getInputStream();
             OutputStream os = Files.newOutputStream(tempFile)) {
            is.transferTo(os);
        }
        Files.move(tempFile, targetPath, StandardCopyOption.ATOMIC_MOVE);

        // Create database record
        String storedPath = "folders/" + folderId + "/" + targetPath.getFileName().toString();
        FolderFile folderFile = FolderFile.builder()
            .fileId(UUID.randomUUID().toString())
            .folder(folder)
            .fileName(originalName)
            .storedPath(storedPath)
            .fileSize(file.getSize())
            .mimeType(file.getContentType())
            .build();

        FolderFile saved = fileRepository.save(folderFile);
        log.info("Added file {} to folder {}", originalName, folderId);
        return saved;
    }

    /**
     * Get all files in a folder.
     */
    @Transactional(readOnly = true)
    public List<FolderFile> getFolderFiles(String folderId) {
        return fileRepository.findByFolder_FolderIdOrderByUploadedAtDesc(folderId);
    }

    /**
     * Get a file by its ID.
     */
    @Transactional(readOnly = true)
    public Optional<FolderFile> getFile(String fileId) {
        return fileRepository.findByFileId(fileId);
    }

    /**
     * Get the absolute path to a file.
     */
    public Path getFilePath(FolderFile file) {
        return basePath.getParent().resolve(file.getStoredPath()).normalize();
    }

    /**
     * Remove a file from a folder.
     */
    @Transactional
    public boolean removeFileFromFolder(String folderId, String fileId) {
        Optional<FolderFile> fileOpt = fileRepository.findByFileId(fileId);
        if (fileOpt.isEmpty()) {
            return false;
        }

        FolderFile file = fileOpt.get();

        // Verify file belongs to the specified folder
        if (!file.getFolder().getFolderId().equals(folderId)) {
            throw new IllegalArgumentException("File does not belong to folder: " + folderId);
        }

        // Delete file from disk
        try {
            Path filePath = getFilePath(file);
            if (Files.exists(filePath)) {
                Files.delete(filePath);
            }
        } catch (IOException e) {
            log.error("Failed to delete file from disk: {}", fileId, e);
        }

        // Delete from database
        fileRepository.delete(file);
        log.info("Removed file {} from folder {}", fileId, folderId);
        return true;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SESSION ASSOCIATION
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Associate a session with a folder.
     */
    @Transactional
    public ChatSession associateSessionWithFolder(String sessionId, String folderId) {
        ChatSession session = sessionRepository.findBySessionId(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        ChatFolder folder = folderRepository.findByFolderId(folderId)
            .orElseThrow(() -> new IllegalArgumentException("Folder not found: " + folderId));

        session.setFolder(folder);
        ChatSession saved = sessionRepository.save(session);
        log.info("Associated session {} with folder {}", sessionId, folderId);
        return saved;
    }

    /**
     * Disassociate a session from its folder.
     */
    @Transactional
    public ChatSession disassociateSessionFromFolder(String sessionId) {
        ChatSession session = sessionRepository.findBySessionId(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        session.setFolder(null);
        ChatSession saved = sessionRepository.save(session);
        log.info("Disassociated session {} from folder", sessionId);
        return saved;
    }

    /**
     * Get all sessions associated with a folder.
     */
    @Transactional(readOnly = true)
    public List<ChatSession> getFolderSessions(String folderId) {
        ChatFolder folder = folderRepository.findByFolderId(folderId)
            .orElseThrow(() -> new IllegalArgumentException("Folder not found: " + folderId));
        return new ArrayList<>(folder.getSessions());
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // PROMPT CONTEXT
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Get absolute file paths for a folder (for prompt injection).
     */
    @Transactional(readOnly = true)
    public List<String> getFolderFilePaths(String folderId) {
        List<FolderFile> files = fileRepository.findByFolder_FolderIdOrderByUploadedAtDesc(folderId);
        List<String> paths = new ArrayList<>();
        for (FolderFile file : files) {
            Path absolutePath = getFilePath(file);
            paths.add(absolutePath.toString());
        }
        return paths;
    }

    /**
     * Build the file context prompt prefix for a folder.
     */
    @Transactional(readOnly = true)
    public String buildFileContextPrompt(String folderId) {
        List<String> filePaths = getFolderFilePaths(folderId);
        if (filePaths.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Available Files\n\n");
        sb.append("The following files are available in your working context. ");
        sb.append("You can read these files if needed using the filesystem tools, ");
        sb.append("but you don't have to unless the user's question requires it.\n\n");

        for (String path : filePaths) {
            sb.append("- `").append(path).append("`\n");
        }

        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Sanitize a filename to prevent directory traversal and other issues.
     */
    private String sanitizeFileName(String originalName) {
        // Remove path separators and keep only safe characters
        String sanitized = originalName.replaceAll("[^a-zA-Z0-9._-]", "_");

        // Ensure not empty
        if (sanitized.isEmpty()) {
            sanitized = "file_" + UUID.randomUUID().toString().substring(0, 8);
        }

        // Limit length
        if (sanitized.length() > 255) {
            String extension = "";
            int dotIndex = sanitized.lastIndexOf('.');
            if (dotIndex > 0) {
                extension = sanitized.substring(dotIndex);
                sanitized = sanitized.substring(0, dotIndex);
            }
            sanitized = sanitized.substring(0, 255 - extension.length()) + extension;
        }

        return sanitized;
    }

    /**
     * Resolve a unique path for a file, appending numbers if necessary.
     */
    private Path resolveUniquePath(Path directory, String fileName) {
        Path targetPath = directory.resolve(fileName);

        if (!Files.exists(targetPath)) {
            return targetPath;
        }

        // Find a unique name by appending numbers
        String baseName = fileName;
        String extension = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = fileName.substring(0, dotIndex);
            extension = fileName.substring(dotIndex);
        }

        int counter = 1;
        while (Files.exists(targetPath)) {
            String newName = baseName + "_" + counter + extension;
            targetPath = directory.resolve(newName);
            counter++;

            if (counter > 1000) {
                throw new RuntimeException("Too many files with the same name");
            }
        }

        return targetPath;
    }
}
