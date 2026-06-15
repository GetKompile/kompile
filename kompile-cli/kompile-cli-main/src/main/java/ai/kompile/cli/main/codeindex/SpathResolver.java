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

package ai.kompile.cli.main.codeindex;

import ai.kompile.cli.main.codeindex.SpathParser.PathSegment;
import ai.kompile.cli.main.codeindex.SpathParser.SegmentKind;
import ai.kompile.cli.main.codeindex.SpathParser.SpathQuery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/**
 * Resolves spath queries against the code index. Takes a parsed {@link SpathQuery}
 * and returns matching entities from the SQLite-backed {@link IndexDatabase}.
 *
 * <p>Resolution strategy:</p>
 * <ol>
 *   <li><b>Exact match</b>: {@code pkg.Class.method} → FQN equality</li>
 *   <li><b>Single wildcard</b>: {@code pkg.*} → direct children only (one level)</li>
 *   <li><b>Recursive wildcard</b>: {@code pkg.**} → all descendants (any depth)</li>
 *   <li><b>Pattern match</b>: {@code pkg.*Handler} → suffix/prefix match at one level</li>
 *   <li><b>Selector</b>: {@code pkg[file.java].Class} → scoped to specific file</li>
 *   <li><b>Property</b>: {@code pkg.Class/imports} → entity type filter (imports, annotations, etc.)</li>
 * </ol>
 *
 * <p>Supports both Java-style dot notation ({@code ai.kompile.cli.main}) and
 * Go-style slash notation ({@code internal/service}), as well as the Go
 * subpackage enumeration pattern ({@code internal/...}).</p>
 */
public class SpathResolver {

    /**
     * A single match from spath resolution.
     */
    public record SpathMatch(
            String entityType,
            String name,
            String fullyQualifiedName,
            String filePath,
            String language,
            int startLine,
            int endLine,
            String signature,
            String docComment,
            String visibility,
            String parentFqn,
            String inheritedFrom,
            String implementsList
    ) {}

    /**
     * Result of an spath resolution.
     */
    public record SpathResult(
            String query,
            int totalMatches,
            List<SpathMatch> matches,
            String resolvedPackage,     // the package portion of the query
            String resolvedSymbol       // the symbol portion, if any
    ) {}

    /**
     * Property name → entity type mapping.
     * Spath properties like /imports, /annotations map to entity type filters.
     */
    private static final Map<String, String> PROPERTY_TO_ENTITY_TYPE = Map.ofEntries(
            Map.entry("imports", "IMPORT"),
            Map.entry("annotations", "ANNOTATION"),
            Map.entry("methods", "METHOD"),
            Map.entry("fields", "FIELD"),
            Map.entry("classes", "CLASS"),
            Map.entry("interfaces", "INTERFACE"),
            Map.entry("enums", "ENUM"),
            Map.entry("functions", "FUNCTION"),
            Map.entry("constants", "CONSTANT"),
            Map.entry("packages", "PACKAGE"),
            Map.entry("modules", "MODULE"),
            Map.entry("records", "RECORD"),
            Map.entry("files", "FILE")
    );

    private final String projectId;
    private final Path indexDir;

    public SpathResolver(String projectId) {
        this.projectId = projectId;
        this.indexDir = LocalCodeIndexer.getIndexDir(projectId);
    }

    /**
     * Resolve an spath query string against the index.
     */
    public SpathResult resolve(String spathInput, int maxResults) throws IOException {
        SpathQuery query = SpathParser.parse(spathInput);
        return resolve(query, maxResults);
    }

    /**
     * Resolve a parsed spath query against the index.
     */
    public SpathResult resolve(SpathQuery query, int maxResults) throws IOException {
        if (!Files.exists(indexDir.resolve("index.db"))) {
            throw new IOException("No index found for project '" + projectId +
                    "'. Run 'kompile code-index' first.");
        }

        try (IndexLockManager.LockToken ignored = IndexLockManager.acquireReadLock(projectId);
             IndexDatabase db = IndexDatabase.open(indexDir)) {

            List<SpathMatch> matches = executeQuery(db, query, maxResults);

            return new SpathResult(
                    query.raw(),
                    matches.size(),
                    matches,
                    query.packagePath(),
                    query.symbolPath()
            );

        } catch (SQLException e) {
            throw new IOException("Database error resolving spath: " + e.getMessage(), e);
        }
    }

    /**
     * Execute the spath query against the database.
     */
    private List<SpathMatch> executeQuery(IndexDatabase db, SpathQuery query,
                                           int maxResults) throws SQLException {
        // Build SQL based on query characteristics
        StringBuilder sql = new StringBuilder(
                "SELECT entity_type, name, fqn, rel_path, language, start_line, end_line, " +
                "signature, doc_comment, visibility, inherited_from, implements_list FROM entities_meta WHERE 1=1");
        List<Object> params = new ArrayList<>();

        // Apply property filter (maps to entity type)
        if (query.property() != null) {
            String entityType = PROPERTY_TO_ENTITY_TYPE.get(query.property().toLowerCase());
            if (entityType != null) {
                sql.append(" AND entity_type = ?");
                params.add(entityType);
            }
        }

        // Apply selector (file scoping)
        if (query.selector() != null) {
            sql.append(" AND rel_path LIKE ?");
            params.add("%" + query.selector());
        }

        // Apply path matching
        if (!query.segments().isEmpty()) {
            appendPathCondition(sql, params, query);
        }

        sql.append(" ORDER BY entity_type, name LIMIT ?");
        params.add(maxResults);

        // Execute
        List<SpathMatch> matches = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    matches.add(new SpathMatch(
                            rs.getString("entity_type"),
                            rs.getString("name"),
                            rs.getString("fqn"),
                            rs.getString("rel_path"),
                            rs.getString("language"),
                            rs.getInt("start_line"),
                            rs.getInt("end_line"),
                            rs.getString("signature"),
                            rs.getString("doc_comment"),
                            rs.getString("visibility"),
                            null, // parentFqn not stored in entities_meta currently
                            rs.getString("inherited_from"),
                            rs.getString("implements_list")
                    ));
                }
            }
        }

        return matches;
    }

    /**
     * Append WHERE conditions for the path segments.
     *
     * Strategy:
     * 1. Build a full FQN pattern from segments, converting wildcards to SQL LIKE patterns
     * 2. For exact queries, try FQN exact match first, then fall back to name match
     * 3. For single *, ensure only one level of nesting is matched
     */
    private void appendPathCondition(StringBuilder sql, List<Object> params,
                                      SpathQuery query) {
        List<PathSegment> segments = query.segments();

        if (query.hasRecursiveWildcard()) {
            // ** — match any depth under prefix
            // Build prefix from segments before **
            StringBuilder prefix = new StringBuilder();
            for (PathSegment seg : segments) {
                if (seg.isRecursiveWildcard()) break;
                if (!prefix.isEmpty()) prefix.append(".");
                prefix.append(seg.name());
            }

            // Check for segments after ** (e.g., pkg.**.method)
            boolean hasTrailing = false;
            String trailingPattern = null;
            for (int i = 0; i < segments.size(); i++) {
                if (segments.get(i).isRecursiveWildcard() && i + 1 < segments.size()) {
                    hasTrailing = true;
                    StringBuilder trailing = new StringBuilder();
                    for (int j = i + 1; j < segments.size(); j++) {
                        if (!trailing.isEmpty()) trailing.append(".");
                        PathSegment ts = segments.get(j);
                        if (ts.name().contains("*")) {
                            trailing.append(ts.name().replace("*", "%"));
                        } else {
                            trailing.append(ts.name());
                        }
                    }
                    trailingPattern = trailing.toString();
                    break;
                }
            }

            if (prefix.isEmpty()) {
                // Just ** — match everything
                if (hasTrailing) {
                    sql.append(" AND fqn LIKE ?");
                    params.add("%" + trailingPattern);
                }
                // else: no filter, return all
            } else {
                if (hasTrailing) {
                    sql.append(" AND fqn LIKE ? AND fqn LIKE ?");
                    params.add(prefix + ".%");
                    params.add("%" + trailingPattern);
                } else {
                    sql.append(" AND fqn LIKE ?");
                    params.add(prefix + ".%");
                }
            }

        } else if (query.hasWildcard()) {
            // Single * or pattern like *Handler
            // Find the wildcard segment
            int wcIdx = -1;
            for (int i = 0; i < segments.size(); i++) {
                if (segments.get(i).isWildcard() || segments.get(i).kind() == SegmentKind.PATTERN) {
                    wcIdx = i;
                    break;
                }
            }

            // Build prefix (before wildcard)
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < wcIdx; i++) {
                if (!prefix.isEmpty()) prefix.append(".");
                prefix.append(segments.get(i).name());
            }

            PathSegment wcSeg = segments.get(wcIdx);

            // Build suffix (after wildcard)
            StringBuilder suffix = new StringBuilder();
            for (int i = wcIdx + 1; i < segments.size(); i++) {
                if (!suffix.isEmpty()) suffix.append(".");
                suffix.append(segments.get(i).name());
            }

            if (wcSeg.isWildcard()) {
                // Pure * — match one level deep under prefix
                if (prefix.isEmpty()) {
                    // * at root — match top-level entities
                    if (suffix.isEmpty()) {
                        sql.append(" AND fqn NOT LIKE '%.%.%'");
                    } else {
                        sql.append(" AND fqn LIKE ?");
                        params.add("%." + suffix);
                    }
                } else {
                    if (suffix.isEmpty()) {
                        // pkg.* — direct children of pkg
                        // Match: fqn LIKE 'prefix.%' AND fqn NOT LIKE 'prefix.%.%'
                        sql.append(" AND fqn LIKE ? AND fqn NOT LIKE ?");
                        params.add(prefix + ".%");
                        params.add(prefix + ".%.%");
                    } else {
                        // pkg.*.member — any child of pkg that has .member
                        sql.append(" AND fqn LIKE ?");
                        params.add(prefix + ".%." + suffix);
                    }
                }
            } else {
                // Pattern like *Handler or Get*
                String pattern = wcSeg.name().replace("*", "%");
                if (prefix.isEmpty()) {
                    sql.append(" AND name LIKE ?");
                    params.add(pattern);
                } else {
                    if (suffix.isEmpty()) {
                        // pkg.*Handler — name matching within package
                        sql.append(" AND fqn LIKE ? AND name LIKE ?");
                        params.add(prefix + ".%");
                        params.add(pattern);
                    } else {
                        sql.append(" AND fqn LIKE ? AND name LIKE ?");
                        params.add(prefix + ".%." + suffix);
                        params.add(pattern);
                    }
                }
            }

        } else {
            // Exact match — no wildcards
            String fqn = query.toFqnLikePattern();

            // Property queries with a path target specific entity's children
            if (query.property() != null) {
                // e.g., pkg.Class/imports → imports in the file containing pkg.Class
                // First find the file for the target entity, then get imports from that file
                sql.append(" AND rel_path IN (SELECT rel_path FROM entities_meta WHERE fqn = ?)");
                params.add(fqn);
            } else {
                // Try exact FQN match, or name match as fallback
                sql.append(" AND (fqn = ? OR name = ? OR fqn LIKE ?)");
                params.add(fqn);
                params.add(fqn); // name match for unqualified queries
                params.add("%." + fqn); // suffix match for partial FQNs
            }
        }
    }

    private Connection getConnection() throws SQLException {
        String url = "jdbc:sqlite:" + indexDir.resolve("index.db").toAbsolutePath();
        Connection conn = DriverManager.getConnection(url);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA query_only=true");
        }
        return conn;
    }
}
