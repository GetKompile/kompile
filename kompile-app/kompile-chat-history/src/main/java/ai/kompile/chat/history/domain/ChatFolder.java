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

package ai.kompile.chat.history.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Entity representing a folder for organizing chat sessions with associated files.
 * Folders allow users to group related sessions and upload reference files that
 * can be injected into agent prompts.
 */
@Entity
@Table(name = "chat_folders", indexes = {
    @Index(name = "idx_folder_user_id", columnList = "userId"),
    @Index(name = "idx_folder_updated_at", columnList = "updatedAt")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatFolder {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    /**
     * Unique external identifier (UUID) for the folder.
     */
    @Column(nullable = false, unique = true)
    private String folderId;

    /**
     * Display name of the folder.
     */
    @Column(nullable = false)
    private String name;

    /**
     * Optional description of the folder's purpose or contents.
     */
    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Optional user ID for user-scoped folders.
     */
    @Column
    private String userId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Files uploaded to this folder.
     */
    @OneToMany(mappedBy = "folder", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("uploadedAt DESC")
    @Builder.Default
    private List<FolderFile> files = new ArrayList<>();

    /**
     * Chat sessions associated with this folder.
     */
    @OneToMany(mappedBy = "folder", fetch = FetchType.LAZY)
    @Builder.Default
    private List<ChatSession> sessions = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Add a file to this folder.
     */
    public void addFile(FolderFile file) {
        files.add(file);
        file.setFolder(this);
    }

    /**
     * Remove a file from this folder.
     */
    public void removeFile(FolderFile file) {
        files.remove(file);
        file.setFolder(null);
    }

    /**
     * Get the count of files in this folder.
     */
    public int getFileCount() {
        return files != null ? files.size() : 0;
    }
}
