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

package ai.kompile.chat.history.repository;

import ai.kompile.chat.history.domain.FolderFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for FolderFile entities.
 */
@Repository
public interface FolderFileRepository extends JpaRepository<FolderFile, Long> {

    /**
     * Find a file by its unique file ID.
     */
    Optional<FolderFile> findByFileId(String fileId);

    /**
     * Find all files in a folder, ordered by upload date (newest first).
     */
    List<FolderFile> findByFolder_FolderIdOrderByUploadedAtDesc(String folderId);

    /**
     * Check if a file exists by file ID.
     */
    boolean existsByFileId(String fileId);

    /**
     * Delete a file by its file ID.
     */
    void deleteByFileId(String fileId);

    /**
     * Count files in a folder.
     */
    long countByFolder_FolderId(String folderId);
}
