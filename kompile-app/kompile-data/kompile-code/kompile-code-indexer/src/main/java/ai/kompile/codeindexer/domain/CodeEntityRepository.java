/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.codeindexer.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CodeEntityRepository extends JpaRepository<CodeEntity, UUID> {

    List<CodeEntity> findByProjectId(String projectId);

    List<CodeEntity> findByProjectIdAndEntityType(String projectId, CodeEntityType entityType);

    Optional<CodeEntity> findByProjectIdAndFullyQualifiedName(String projectId, String fqn);

    List<CodeEntity> findByProjectIdAndFilePath(String projectId, String filePath);

    @Query("SELECT e FROM CodeEntity e WHERE e.projectId = :projectId " +
           "AND (LOWER(e.name) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(e.fullyQualifiedName) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(e.docComment) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(e.signature) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(e.contentPreview) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<CodeEntity> search(@Param("projectId") String projectId, @Param("query") String query);

    @Query("SELECT e FROM CodeEntity e WHERE e.projectId = :projectId " +
           "AND e.entityType = :type " +
           "AND (LOWER(e.name) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(e.fullyQualifiedName) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<CodeEntity> searchByType(@Param("projectId") String projectId,
                                  @Param("query") String query,
                                  @Param("type") CodeEntityType type);

    List<CodeEntity> findByProjectIdAndParentFqn(String projectId, String parentFqn);

    List<CodeEntity> findByProjectIdAndEntityTypeIn(String projectId, List<CodeEntityType> entityTypes);

    List<CodeEntity> findByProjectIdAndLanguage(String projectId, String language);

    @Query("SELECT DISTINCT e.language FROM CodeEntity e WHERE e.projectId = :projectId AND e.language IS NOT NULL")
    List<String> findDistinctLanguagesByProjectId(@Param("projectId") String projectId);

    long countByProjectId(String projectId);

    long countByProjectIdAndEntityType(String projectId, CodeEntityType entityType);

    void deleteByProjectId(String projectId);

    void deleteByProjectIdAndFilePath(String projectId, String filePath);

    @Query("DELETE FROM CodeEntity e WHERE e.projectId = :projectId AND e.filePath IN :filePaths")
    @org.springframework.data.jpa.repository.Modifying
    void deleteByProjectIdAndFilePathIn(@Param("projectId") String projectId,
                                        @Param("filePaths") java.util.Set<String> filePaths);
}
