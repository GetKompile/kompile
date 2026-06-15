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

import ai.kompile.chat.history.domain.ChatFolder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for ChatFolder entities.
 */
@Repository
public interface ChatFolderRepository extends JpaRepository<ChatFolder, Long> {

    /**
     * Find a folder by its unique folder ID.
     */
    Optional<ChatFolder> findByFolderId(String folderId);

    /**
     * Find all folders for a specific user, ordered by most recently updated.
     */
    List<ChatFolder> findByUserIdOrderByUpdatedAtDesc(String userId);

    /**
     * Find all folders ordered by most recently updated.
     */
    List<ChatFolder> findAllByOrderByUpdatedAtDesc();

    /**
     * Check if a folder exists by folder ID.
     */
    boolean existsByFolderId(String folderId);

    /**
     * Delete a folder by its folder ID.
     */
    void deleteByFolderId(String folderId);
}
