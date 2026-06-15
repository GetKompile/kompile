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

package ai.kompile.codeindexer.service;

import ai.kompile.codeindexer.domain.*;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Provides code search across indexed codebases. Combines database
 * text search with knowledge graph traversal for semantic results.
 */
@Service
public class CodeSearchService {

    private static final Logger log = LoggerFactory.getLogger(CodeSearchService.class);

    private final CodeEntityRepository entityRepository;
    private final CodeRelationRepository relationRepository;
    private final KnowledgeGraphService knowledgeGraphService;

    @Autowired
    public CodeSearchService(CodeEntityRepository entityRepository,
                             CodeRelationRepository relationRepository,
                             @Autowired(required = false) KnowledgeGraphService knowledgeGraphService) {
        this.entityRepository = entityRepository;
        this.relationRepository = relationRepository;
        this.knowledgeGraphService = knowledgeGraphService;
    }

    /**
     * Search for code entities matching a query.
     */
    public List<CodeEntity> search(String projectId, String query, int maxResults) {
        return entityRepository.search(projectId, query).stream()
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    /**
     * Search for code entities of a specific type.
     */
    public List<CodeEntity> searchByType(String projectId, String query,
                                         CodeEntityType type, int maxResults) {
        return entityRepository.searchByType(projectId, query, type).stream()
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    /**
     * Find all entities in a specific file.
     */
    public List<CodeEntity> getEntitiesInFile(String projectId, String filePath) {
        return entityRepository.findByProjectIdAndFilePath(projectId, filePath);
    }

    /**
     * Get children of a code entity (e.g. methods of a class).
     */
    public List<CodeEntity> getChildren(String projectId, String parentFqn) {
        return entityRepository.findByProjectIdAndParentFqn(projectId, parentFqn);
    }

    /**
     * Find a specific entity by fully qualified name.
     */
    public Optional<CodeEntity> findByFqn(String projectId, String fqn) {
        return entityRepository.findByProjectIdAndFullyQualifiedName(projectId, fqn);
    }

    /**
     * Find related entities via knowledge graph traversal.
     */
    public List<CodeEntity> findRelated(String projectId, String fqn, int maxDepth) {
        if (knowledgeGraphService == null) return List.of();

        Optional<CodeEntity> entity = findByFqn(projectId, fqn);
        if (entity.isEmpty() || entity.get().getGraphNodeId() == null) return List.of();

        UUID nodeId = entity.get().getGraphNodeId();
        var connected = knowledgeGraphService.getConnectedNodes(nodeId.toString(), maxDepth);

        // Map graph nodes back to code entities
        return connected.stream()
                .map(node -> entityRepository.findByProjectIdAndFullyQualifiedName(
                        projectId, node.getExternalId()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    /**
     * Find functions and methods that are never called within the indexed codebase.
     * Uses the CALLS relations extracted during indexing. A function is considered
     * "unused" if its simple name never appears as a call target.
     *
     * @param projectId    project scope
     * @param visibility   optional filter (e.g. "private", "public")
     * @param language     optional language filter (e.g. "java", "python")
     * @param includeTests whether to include test files in results
     * @param maxResults   cap on returned entities
     * @return list of potentially unused callable entities
     */
    public List<CodeEntity> findUnusedFunctions(String projectId, String visibility,
                                                String language, boolean includeTests,
                                                int maxResults) {
        // Collect all distinct function names that appear as CALLS targets
        Set<String> calledNames = relationRepository.findDistinctCalledNames(projectId);

        // Fetch all callable entities (methods + functions)
        List<CodeEntity> callables = entityRepository.findByProjectIdAndEntityTypeIn(
                projectId, List.of(CodeEntityType.METHOD, CodeEntityType.FUNCTION));

        return callables.stream()
                .filter(e -> !calledNames.contains(e.getName()))
                .filter(e -> !isLikelyEntryPoint(e))
                .filter(e -> visibility == null || visibility.isEmpty() ||
                        visibility.equalsIgnoreCase(e.getVisibility()))
                .filter(e -> language == null || language.isEmpty() ||
                        language.equalsIgnoreCase(e.getLanguage()))
                .filter(e -> includeTests || !isTestFile(e))
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    /**
     * Get callers of a specific function — which methods call the given name.
     */
    public List<CodeRelation> findCallersOf(String projectId, String functionName) {
        return relationRepository.findCallsToName(projectId, functionName);
    }

    /**
     * Returns true for names that are typically entry points or framework
     * callbacks — these should not be flagged as "unused" even when no
     * explicit call site exists in the indexed code.
     */
    private boolean isLikelyEntryPoint(CodeEntity entity) {
        String name = entity.getName();
        if (name == null) return false;

        // Common entry points and lifecycle hooks
        if ("main".equals(name) || "<init>".equals(name)) return true;

        // Test methods
        if (name.startsWith("test") || name.startsWith("Test")) return true;

        // JUnit / TestNG lifecycle
        if ("setUp".equals(name) || "tearDown".equals(name) ||
            "setup".equals(name) || "teardown".equals(name) ||
            "beforeEach".equals(name) || "afterEach".equals(name) ||
            "beforeAll".equals(name) || "afterAll".equals(name)) return true;

        // Spring / Jakarta lifecycle
        if ("init".equals(name) || "destroy".equals(name) ||
            "onApplicationEvent".equals(name) || "run".equals(name) ||
            "call".equals(name) || "accept".equals(name) ||
            "apply".equals(name) || "handle".equals(name) ||
            "get".equals(name) || "invoke".equals(name)) return true;

        // Python special methods
        if (name.startsWith("__") && name.endsWith("__")) return true;

        // Abstract / interface methods are called polymorphically
        if (Boolean.TRUE.equals(entity.getIsAbstract())) return true;

        // Check for common annotation markers in signature
        String sig = entity.getSignature();
        if (sig != null) {
            if (sig.contains("@Override") || sig.contains("@Bean") ||
                sig.contains("@EventListener") || sig.contains("@PostConstruct") ||
                sig.contains("@PreDestroy") || sig.contains("@Scheduled") ||
                sig.contains("@RequestMapping") || sig.contains("@GetMapping") ||
                sig.contains("@PostMapping") || sig.contains("@PutMapping") ||
                sig.contains("@DeleteMapping") || sig.contains("@Tool") ||
                sig.contains("@Test") || sig.contains("@Before") ||
                sig.contains("@After") || sig.contains("@staticmethod") ||
                sig.contains("@classmethod") || sig.contains("@property")) {
                return true;
            }
        }

        return false;
    }

    /**
     * Heuristic: returns true if the entity's file path looks like a test file.
     */
    private boolean isTestFile(CodeEntity entity) {
        String path = entity.getFilePath();
        if (path == null) return false;
        String lower = path.toLowerCase();
        return lower.contains("/test/") || lower.contains("/tests/") ||
               lower.contains("/spec/") || lower.contains("_test.") ||
               lower.contains(".test.") || lower.contains("test_") ||
               lower.contains("_spec.") || lower.contains(".spec.");
    }

    /**
     * Get statistics for an indexed project.
     */
    public Map<String, Object> getStatistics(String projectId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("projectId", projectId);
        stats.put("totalEntities", entityRepository.countByProjectId(projectId));

        Map<String, Long> byType = new LinkedHashMap<>();
        for (CodeEntityType type : CodeEntityType.values()) {
            long count = entityRepository.countByProjectIdAndEntityType(projectId, type);
            if (count > 0) byType.put(type.name().toLowerCase(), count);
        }
        stats.put("byType", byType);

        // Relation statistics
        long totalRelations = relationRepository.countByProjectId(projectId);
        stats.put("totalRelations", totalRelations);
        if (totalRelations > 0) {
            Map<String, Long> byRelType = new LinkedHashMap<>();
            for (CodeRelationType relType : CodeRelationType.values()) {
                long count = relationRepository.countByProjectIdAndRelationType(projectId, relType);
                if (count > 0) byRelType.put(relType.name().toLowerCase(), count);
            }
            stats.put("byRelationType", byRelType);
        }

        return stats;
    }

    /**
     * Format search results as a readable string for LLM consumption.
     */
    public String formatResults(List<CodeEntity> results) {
        if (results.isEmpty()) return "No results found.";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            CodeEntity e = results.get(i);
            sb.append(i + 1).append(". ");
            sb.append("[").append(e.getEntityType().name().toLowerCase()).append("] ");
            sb.append("**").append(e.getName()).append("**");
            if (e.getSignature() != null) {
                sb.append(" — `").append(e.getSignature()).append("`");
            }
            sb.append("\n");
            sb.append("   File: ").append(e.getFilePath());
            if (e.getStartLine() != null) {
                sb.append(":").append(e.getStartLine());
                if (e.getEndLine() != null && !e.getEndLine().equals(e.getStartLine())) {
                    sb.append("-").append(e.getEndLine());
                }
            }
            sb.append("\n");
            if (e.getDocComment() != null && !e.getDocComment().isEmpty()) {
                String doc = e.getDocComment();
                if (doc.length() > 200) doc = doc.substring(0, 200) + "...";
                sb.append("   Doc: ").append(doc.replaceAll("\\n", " ").trim()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
