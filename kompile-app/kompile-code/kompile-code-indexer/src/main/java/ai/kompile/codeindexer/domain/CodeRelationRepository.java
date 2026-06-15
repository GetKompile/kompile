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
import java.util.Set;
import java.util.UUID;

@Repository
public interface CodeRelationRepository extends JpaRepository<CodeRelation, UUID> {

    List<CodeRelation> findByProjectIdAndRelationType(String projectId, CodeRelationType relationType);

    /** All distinct simple names that appear as CALLS targets in this project. */
    @Query("SELECT DISTINCT r.targetName FROM CodeRelation r " +
           "WHERE r.projectId = :projectId AND r.relationType = 'CALLS'")
    Set<String> findDistinctCalledNames(@Param("projectId") String projectId);

    /** Find all call relations whose target matches a given simple name. */
    @Query("SELECT r FROM CodeRelation r " +
           "WHERE r.projectId = :projectId AND r.relationType = 'CALLS' AND r.targetName = :name")
    List<CodeRelation> findCallsToName(@Param("projectId") String projectId, @Param("name") String name);

    /** Find all relations originating from a given source FQN. */
    @Query("SELECT r FROM CodeRelation r WHERE r.projectId = :projectId AND r.sourceFqn = :fqn")
    List<CodeRelation> findByProjectIdAndSourceFqn(@Param("projectId") String projectId, @Param("fqn") String fqn);

    /** Find all relations originating from a given source FQN with a specific type. */
    @Query("SELECT r FROM CodeRelation r WHERE r.projectId = :projectId AND r.sourceFqn = :fqn AND r.relationType = :type")
    List<CodeRelation> findByProjectIdAndSourceFqnAndRelationType(
            @Param("projectId") String projectId, @Param("fqn") String fqn, @Param("type") CodeRelationType type);

    /** Find all relations whose target FQN matches a given value. */
    @Query("SELECT r FROM CodeRelation r WHERE r.projectId = :projectId AND r.targetFqn = :fqn")
    List<CodeRelation> findByProjectIdAndTargetFqn(@Param("projectId") String projectId, @Param("fqn") String fqn);

    /** Find all relations whose target FQN matches with a specific relation type. */
    @Query("SELECT r FROM CodeRelation r WHERE r.projectId = :projectId AND r.targetFqn = :fqn AND r.relationType = :type")
    List<CodeRelation> findByProjectIdAndTargetFqnAndRelationType(
            @Param("projectId") String projectId, @Param("fqn") String fqn, @Param("type") CodeRelationType type);

    long countByProjectId(String projectId);

    long countByProjectIdAndRelationType(String projectId, CodeRelationType relationType);

    void deleteByProjectId(String projectId);

    void deleteByProjectIdAndFilePath(String projectId, String filePath);

    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM CodeRelation r WHERE r.projectId = :projectId AND r.filePath IN :filePaths")
    void deleteByProjectIdAndFilePathIn(@Param("projectId") String projectId,
                                        @Param("filePaths") java.util.Set<String> filePaths);
}
