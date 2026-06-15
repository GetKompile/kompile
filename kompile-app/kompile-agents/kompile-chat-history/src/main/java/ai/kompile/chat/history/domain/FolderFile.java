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

/**
 * Entity representing a file uploaded to a folder.
 * These files can be referenced by agents during chat sessions.
 */
@Entity
@Table(name = "folder_files", indexes = {
    @Index(name = "idx_file_folder_id", columnList = "folder_id"),
    @Index(name = "idx_file_uploaded_at", columnList = "uploadedAt")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FolderFile {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    /**
     * Unique external identifier (UUID) for the file.
     */
    @Column(nullable = false, unique = true)
    private String fileId;

    /**
     * The folder this file belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_id", nullable = false)
    private ChatFolder folder;

    /**
     * Original filename as uploaded by the user.
     */
    @Column(nullable = false)
    private String fileName;

    /**
     * Relative path where the file is stored (e.g., "folders/{folderId}/{sanitizedName}").
     */
    @Column(nullable = false)
    private String storedPath;

    /**
     * File size in bytes.
     */
    @Column(nullable = false)
    private Long fileSize;

    /**
     * MIME type of the file.
     */
    @Column
    private String mimeType;

    /**
     * When the file was uploaded.
     */
    @Column(nullable = false)
    private LocalDateTime uploadedAt;

    /**
     * Optional JSON metadata for the file.
     */
    @Column(columnDefinition = "TEXT")
    private String metadata;

    @PrePersist
    protected void onCreate() {
        uploadedAt = LocalDateTime.now();
    }
}
