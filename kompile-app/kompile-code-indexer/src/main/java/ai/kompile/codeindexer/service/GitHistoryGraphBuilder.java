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

import ai.kompile.codeindexer.domain.CodeEntity;
import ai.kompile.codeindexer.domain.CodeEntityRepository;
import ai.kompile.codeindexer.domain.IndexedDirectory;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Extracts git commit history for indexed code projects and creates
 * temporal edges in the knowledge graph. Each commit becomes an ENTITY
 * node connected to its modified files via TEMPORAL edges, with
 * {@code occurredAt} set to the commit timestamp.
 */
@Service
public class GitHistoryGraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(GitHistoryGraphBuilder.class);

    private static final String COMMIT_ENTITY_TYPE = "GIT_COMMIT";
    private static final String AUTHOR_ENTITY_TYPE = "GIT_AUTHOR";
    private static final int DEFAULT_MAX_COMMITS = 200;

    private final CodeEntityRepository entityRepository;
    private final KnowledgeGraphService knowledgeGraphService;
    private final GraphEdgeRepository edgeRepository;
    private final CodebaseIndexer codebaseIndexer;

    @Autowired
    public GitHistoryGraphBuilder(CodeEntityRepository entityRepository,
                                  @Autowired(required = false) KnowledgeGraphService knowledgeGraphService,
                                  @Autowired(required = false) GraphEdgeRepository edgeRepository,
                                  CodebaseIndexer codebaseIndexer) {
        this.entityRepository = entityRepository;
        this.knowledgeGraphService = knowledgeGraphService;
        this.edgeRepository = edgeRepository;
        this.codebaseIndexer = codebaseIndexer;
    }

    /**
     * Extract git history for a project and create temporal graph edges.
     *
     * @param projectId  Logical project identifier
     * @param maxCommits Maximum number of recent commits to process
     * @return Result map with counts of nodes and edges created
     */
    public Map<String, Object> buildGitHistory(String projectId, int maxCommits) {
        if (knowledgeGraphService == null || edgeRepository == null) {
            log.debug("KnowledgeGraphService or EdgeRepository not available; skipping git history");
            return Map.of("skipped", true, "reason", "Knowledge graph not available");
        }

        // Find the indexed directory for this project to determine the git root
        List<IndexedDirectory> dirs = codebaseIndexer.listDirectories(projectId);
        if (dirs.isEmpty()) {
            return Map.of("skipped", true, "reason", "No indexed directories for project " + projectId);
        }

        // Use the first directory to find the git root
        String directoryPath = dirs.get(0).getAbsolutePath();
        File gitRoot = findGitRoot(new File(directoryPath));
        if (gitRoot == null) {
            return Map.of("skipped", true, "reason", "Not a git repository: " + directoryPath);
        }

        log.info("Building git history graph: projectId={}, gitRoot={}, maxCommits={}",
                projectId, gitRoot.getAbsolutePath(), maxCommits);

        // Build a lookup: relative file path → graph node ID
        List<CodeEntity> allEntities = entityRepository.findByProjectId(projectId);
        Map<String, String> filePathToNodeId = new HashMap<>();
        for (CodeEntity entity : allEntities) {
            if (entity.getGraphNodeId() != null && entity.getFilePath() != null) {
                filePathToNodeId.putIfAbsent(entity.getFilePath(), entity.getGraphNodeId().toString());
            }
        }

        // Parse git log
        List<GitCommit> commits = parseGitLog(gitRoot, maxCommits);
        if (commits.isEmpty()) {
            return Map.of("commitsFound", 0, "nodesCreated", 0, "edgesCreated", 0);
        }

        int nodesCreated = 0;
        int edgesCreated = 0;
        Map<String, String> authorNodeIds = new HashMap<>();

        for (GitCommit commit : commits) {
            // Create a node for the commit
            String commitNodeId = createCommitNode(commit);
            if (commitNodeId == null) continue;
            nodesCreated++;

            // Create or reuse author node
            String authorNodeId = authorNodeIds.computeIfAbsent(commit.authorEmail, email -> {
                String nodeId = createAuthorNode(commit.authorName, email);
                return nodeId;
            });
            if (authorNodeId != null && !nodesCreated(authorNodeId)) {
                nodesCreated++;
            }

            // Link author → commit
            if (authorNodeId != null) {
                edgesCreated += createTemporalEdge(authorNodeId, commitNodeId,
                        "authored commit " + commit.shortHash(),
                        commit.occurredAt());
            }

            // Link commit → modified files
            String gitRootPath = gitRoot.getAbsolutePath();
            for (String changedFile : commit.changedFiles) {
                // Try to match the changed file to an indexed entity's file path
                String matchedNodeId = resolveFileNodeId(changedFile, filePathToNodeId, gitRootPath,
                        dirs);
                if (matchedNodeId != null) {
                    edgesCreated += createTemporalEdge(commitNodeId, matchedNodeId,
                            commit.shortHash() + " modified " + changedFile,
                            commit.occurredAt());
                }
            }
        }

        log.info("Git history graph complete: projectId={}, commits={}, nodesCreated={}, edgesCreated={}",
                projectId, commits.size(), nodesCreated, edgesCreated);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("commitsProcessed", commits.size());
        result.put("nodesCreated", nodesCreated);
        result.put("edgesCreated", edgesCreated);
        result.put("gitRoot", gitRoot.getAbsolutePath());
        return result;
    }

    public Map<String, Object> buildGitHistory(String projectId) {
        return buildGitHistory(projectId, DEFAULT_MAX_COMMITS);
    }

    // ── Git command execution ────────────────────────────────────────────────

    private List<GitCommit> parseGitLog(File gitRoot, int maxCommits) {
        Process process = null;
        try {
            // Format: hash<SEP>author name<SEP>author email<SEP>ISO date<SEP>subject
            // followed by changed files (one per line), then blank line
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "log",
                    "--format=%H%x1f%an%x1f%ae%x1f%aI%x1f%s",
                    "--name-only",
                    "-n", String.valueOf(maxCommits)
            );
            pb.directory(gitRoot);
            pb.redirectErrorStream(true);
            process = pb.start();

            List<GitCommit> commits = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                GitCommit current = null;

                while ((line = reader.readLine()) != null) {
                    if (line.contains("\u001f")) {
                        // New commit header
                        if (current != null) {
                            commits.add(current);
                        }
                        String[] parts = line.split("\u001f", 5);
                        if (parts.length >= 5) {
                            current = new GitCommit();
                            current.hash = parts[0].trim();
                            current.authorName = parts[1].trim();
                            current.authorEmail = parts[2].trim();
                            current.dateIso = parts[3].trim();
                            current.subject = parts[4].trim();
                            current.changedFiles = new ArrayList<>();
                        }
                    } else if (!line.isBlank() && current != null) {
                        // Changed file path
                        current.changedFiles.add(line.trim());
                    }
                }
                // Don't forget the last commit
                if (current != null) {
                    commits.add(current);
                }
            }

            boolean finished = process.waitFor(300, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("git log timed out after 300s in {}", gitRoot);
                return commits; // return what we have so far
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("git log exited with code {} in {}", exitCode, gitRoot);
            }

            return commits;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("git log interrupted in {}", gitRoot);
            return List.of();
        } catch (Exception e) {
            log.error("Failed to parse git log in {}: {}", gitRoot, e.getMessage());
            return List.of();
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    private File findGitRoot(File dir) {
        File current = dir;
        while (current != null) {
            if (new File(current, ".git").exists()) {
                return current;
            }
            current = current.getParentFile();
        }
        return null;
    }

    // ── Graph node/edge creation ─────────────────────────────────────────────

    private String createCommitNode(GitCommit commit) {
        try {
            String externalId = "git_commit:" + commit.hash;
            Optional<GraphNode> existing = knowledgeGraphService.getNodeByExternalId(
                    externalId, NodeLevel.ENTITY);
            if (existing.isPresent()) {
                return existing.get().getNodeId();
            }

            String title = commit.shortHash() + ": " + commit.subject;
            String description = "Git commit by " + commit.authorName
                    + " on " + commit.dateIso
                    + "\n" + commit.subject;
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("commitHash", commit.hash);
            metadata.put("author", commit.authorName);
            metadata.put("authorEmail", commit.authorEmail);
            metadata.put("date", commit.dateIso);
            metadata.put("entityType", COMMIT_ENTITY_TYPE);
            metadata.put("filesChanged", commit.changedFiles.size());

            GraphNode node = knowledgeGraphService.createNode(
                    NodeLevel.ENTITY, externalId, title, description, metadata);

            // Set occurredAt on the node itself
            if (node != null && commit.occurredAt() != null) {
                node.setOccurredAt(commit.occurredAt());
            }

            return node != null ? node.getNodeId() : null;
        } catch (Exception e) {
            log.warn("Failed to create commit node for {}: {}", commit.hash, e.getMessage());
            return null;
        }
    }

    private String createAuthorNode(String name, String email) {
        try {
            String externalId = "git_author:" + email;
            Optional<GraphNode> existing = knowledgeGraphService.getNodeByExternalId(
                    externalId, NodeLevel.ENTITY);
            if (existing.isPresent()) {
                return existing.get().getNodeId();
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("email", email);
            metadata.put("entityType", AUTHOR_ENTITY_TYPE);

            GraphNode node = knowledgeGraphService.createNode(
                    NodeLevel.ENTITY, externalId, name + " <" + email + ">",
                    "Git author: " + name, metadata);
            return node != null ? node.getNodeId() : null;
        } catch (Exception e) {
            log.warn("Failed to create author node for {}: {}", email, e.getMessage());
            return null;
        }
    }

    private boolean nodesCreated(String nodeId) {
        // Helper to avoid double-counting author nodes
        try {
            return knowledgeGraphService.getNode(nodeId).isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    private int createTemporalEdge(String sourceNodeId, String targetNodeId,
                                    String description, LocalDateTime occurredAt) {
        try {
            if (knowledgeGraphService.edgeExists(sourceNodeId, targetNodeId)) {
                return 0;
            }

            GraphEdge edge = knowledgeGraphService.createEdge(
                    sourceNodeId, targetNodeId,
                    EdgeType.TEMPORAL, 1.0, description);

            // Set the temporal timestamp
            if (edge != null && occurredAt != null) {
                edge.setOccurredAt(occurredAt);
                edgeRepository.save(edge);
            }

            return 1;
        } catch (Exception e) {
            log.warn("Failed to create temporal edge {} -> {}: {}",
                    sourceNodeId, targetNodeId, e.getMessage());
            return 0;
        }
    }

    /**
     * Try to resolve a git-relative file path to a graph node ID by matching
     * against indexed entity file paths.
     */
    private String resolveFileNodeId(String gitRelativePath,
                                      Map<String, String> filePathToNodeId,
                                      String gitRootPath,
                                      List<IndexedDirectory> dirs) {
        // Direct match
        String nodeId = filePathToNodeId.get(gitRelativePath);
        if (nodeId != null) return nodeId;

        // Try with absolute path relative to each indexed directory
        for (IndexedDirectory dir : dirs) {
            String dirPath = dir.getAbsolutePath();
            // If the indexed dir is a subdirectory of the git root, compute
            // the relative path within the indexed directory
            if (dirPath.startsWith(gitRootPath)) {
                String dirRelative = dirPath.substring(gitRootPath.length());
                if (dirRelative.startsWith("/")) dirRelative = dirRelative.substring(1);
                if (gitRelativePath.startsWith(dirRelative + "/")) {
                    String withinDir = gitRelativePath.substring(dirRelative.length() + 1);
                    nodeId = filePathToNodeId.get(withinDir);
                    if (nodeId != null) return nodeId;
                } else if (dirRelative.isEmpty()) {
                    // Git root == indexed dir
                    nodeId = filePathToNodeId.get(gitRelativePath);
                    if (nodeId != null) return nodeId;
                }
            }

            // Try suffix match: file path may be stored differently
            for (Map.Entry<String, String> entry : filePathToNodeId.entrySet()) {
                if (entry.getKey().endsWith("/" + gitRelativePath)
                        || gitRelativePath.endsWith("/" + entry.getKey())
                        || entry.getKey().equals(gitRelativePath)) {
                    return entry.getValue();
                }
            }
        }

        return null;
    }

    // ── Git commit data holder ───────────────────────────────────────────────

    private static class GitCommit {
        String hash;
        String authorName;
        String authorEmail;
        String dateIso;
        String subject;
        List<String> changedFiles;

        String shortHash() {
            return hash != null && hash.length() >= 7 ? hash.substring(0, 7) : hash;
        }

        LocalDateTime occurredAt() {
            if (dateIso == null) return null;
            try {
                return Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(dateIso))
                        .atZone(ZoneOffset.UTC)
                        .toLocalDateTime();
            } catch (Exception e) {
                return null;
            }
        }
    }
}
