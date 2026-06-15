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
package ai.kompile.knowledgegraph.repository;

import ai.kompile.knowledgegraph.domain.NamedGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for NamedGraph entities.
 */
@Repository
public interface NamedGraphRepository extends JpaRepository<NamedGraph, Long> {

    /**
     * Find a named graph by its external UUID.
     */
    Optional<NamedGraph> findByGraphId(String graphId);

    /**
     * Find all root-level graphs (those with no parent).
     */
    List<NamedGraph> findByParentGraphIsNull();

    /**
     * Find all direct children of a given parent graph.
     */
    List<NamedGraph> findByParentGraph(NamedGraph parent);

    /**
     * Find all graphs scoped to a specific fact sheet.
     */
    List<NamedGraph> findByFactSheetId(Long factSheetId);

    /**
     * Case-insensitive name search.
     */
    List<NamedGraph> findByNameContainingIgnoreCase(String query);

    /**
     * Count direct children of a given parent graph.
     */
    long countByParentGraph(NamedGraph parent);

    /**
     * Find all ancestors of the graph with the given graphId, ordered root-first.
     * This iterative JPQL query walks the parent chain via a self-join chain.
     * For deep hierarchies, use {@code getAncestors} in the service which
     * traverses programmatically to avoid JPQL recursion limits.
     */
    @Query("SELECT g FROM NamedGraph g WHERE g.graphId = :graphId")
    Optional<NamedGraph> findByGraphIdWithParent(@Param("graphId") String graphId);

    /**
     * Find all graphs whose parent has the given graphId.
     */
    @Query("SELECT g FROM NamedGraph g WHERE g.parentGraph.graphId = :parentGraphId ORDER BY g.name")
    List<NamedGraph> findChildrenByParentGraphId(@Param("parentGraphId") String parentGraphId);

    /**
     * Count children by the parent's graphId.
     */
    @Query("SELECT COUNT(g) FROM NamedGraph g WHERE g.parentGraph.graphId = :parentGraphId")
    long countChildrenByParentGraphId(@Param("parentGraphId") String parentGraphId);

    /**
     * Find all graphs of a given ontology type.
     */
    List<NamedGraph> findByOntologyType(String ontologyType);

    /**
     * Find graphs scoped to a fact sheet with a specific ontology type.
     */
    List<NamedGraph> findByFactSheetIdAndOntologyType(Long factSheetId, String ontologyType);

    /**
     * Check whether a named graph with the given graphId exists.
     */
    boolean existsByGraphId(String graphId);
}
