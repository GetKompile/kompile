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

package ai.kompile.cli.main.codeindex;

import ai.kompile.cli.common.registry.ProjectRegistration;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the effective {@code project_id} for code-index tool calls.
 *
 * <p>Historically every tool fell back to either the literal {@code "default"}
 * or the working directory's name when {@code project_id} was omitted, which
 * silently queried (or created) the wrong index whenever the caller sat in a
 * subdirectory or the project was indexed under a custom id. Resolution order:
 *
 * <ol>
 *   <li><b>explicit</b> — a non-blank {@code project_id} parameter wins as-is;</li>
 *   <li><b>project-manifest</b> — the nearest {@code kompile.project.json}
 *       coding project whose root contains the working directory;</li>
 *   <li><b>registration</b> — the nearest {@code .kompile/registration.json}
 *       walking up from the working directory, if a local index exists for
 *       that id (an index whose metadata a crash left unreadable does not
 *       count);</li>
 *   <li><b>index-root</b> — the indexed project whose recorded root is the
 *       deepest ancestor of the working directory;</li>
 *   <li><b>registration-unindexed</b> — a registration id with no local index
 *       yet (surfaces an actionable "index first" error downstream instead of
 *       inventing a different id);</li>
 *   <li><b>cwd-name</b> — the legacy fallback: the working directory's name.</li>
 * </ol>
 */
public final class ProjectIdResolver {

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    /** How many parent directories to walk when looking for a registration. */
    private static final int MAX_REGISTRATION_WALK = 20;

    /**
     * A resolved project id plus how it was determined. {@code source} is one of
     * {@code explicit}, {@code project-manifest},
     * {@code project-manifest-unindexed}, {@code registration},
     * {@code index-root}, {@code registration-unindexed}, {@code cwd-name}.
     */
    public record Resolution(String projectId, String source, boolean autoResolved) {}

    /** A coding project an ACTIVE manifest entry declares: its id and normalized root. */
    private record DeclaredProject(String id, Path root) {}

    private ProjectIdResolver() {}

    /**
     * Resolve against the standard local index location
     * ({@code ~/.kompile/code-index}).
     */
    public static Resolution resolve(String explicit, Path workingDirectory) {
        return resolve(explicit, workingDirectory, LocalCodeIndexer.getBaseIndexDir());
    }

    /**
     * Resolve with an explicit index base directory (test seam).
     */
    static Resolution resolve(String explicit, Path workingDirectory, Path baseIndexDir) {
        if (explicit != null && !explicit.isBlank()) {
            String projectId = explicit.trim();
            if (!LocalCodeIndexer.isSafeProjectId(projectId)) {
                throw new IllegalArgumentException("Invalid code-index project id: " + projectId);
            }
            return new Resolution(projectId, "explicit", false);
        }
        Path cwd = (workingDirectory != null ? workingDirectory : Path.of("."))
                .toAbsolutePath().normalize();

        String manifestId = manifestCodeProjectId(cwd);
        if (manifestId != null) {
            String source = hasSearchableIndex(baseIndexDir, manifestId)
                    ? "project-manifest" : "project-manifest-unindexed";
            return new Resolution(manifestId, source, true);
        }

        String registrationId = registrationProjectId(cwd);
        if (registrationId != null && hasSearchableIndex(baseIndexDir, registrationId)) {
            return new Resolution(registrationId, "registration", true);
        }

        String rootMatch = deepestIndexedRootProject(cwd, baseIndexDir);
        if (rootMatch != null) {
            return new Resolution(rootMatch, "index-root", true);
        }

        if (registrationId != null) {
            return new Resolution(registrationId, "registration-unindexed", true);
        }

        Path name = cwd.getFileName();
        String fallback = name != null ? name.toString() : "default";
        if (!LocalCodeIndexer.isSafeProjectId(fallback)) {
            fallback = fallback.replace('/', '-').replace('\\', '-');
            if (!LocalCodeIndexer.isSafeProjectId(fallback)) fallback = "default";
        }
        return new Resolution(fallback, "cwd-name", true);
    }

    /**
     * Resolve the canonical code-project id declared by the nearest project manifest.
     * A manifest is authoritative even before its structural index has been built: in
     * that case callers receive an actionable "index first" result for the right id
     * instead of silently selecting an unrelated duplicate index for the same root.
     */
    private static String manifestCodeProjectId(Path start) {
        Path dir = start;
        for (int i = 0; i < MAX_REGISTRATION_WALK && dir != null; i++, dir = dir.getParent()) {
            DeclaredProject best = null;
            for (DeclaredProject project : activeManifestProjects(dir)) {
                if (!start.startsWith(project.root())) continue;
                if (best == null || project.root().getNameCount() > best.root().getNameCount()) {
                    best = project;
                }
            }
            if (best != null) return best.id();
        }
        return null;
    }

    /**
     * The directory to index when asked to index {@code requested} as {@code projectId}: the
     * project's declared root when that strictly contains {@code requested}, else
     * {@code requested}. One project keeps one index; indexing a sub-directory under the
     * project's id would re-root the index there and drop every file outside it.
     */
    public static Path indexRoot(String projectId, Path requested) {
        return indexRoot(projectId, requested, LocalCodeIndexer.getBaseIndexDir());
    }

    /** {@link #indexRoot(String, Path)} with an explicit index base directory (test seam). */
    static Path indexRoot(String projectId, Path requested, Path baseIndexDir) {
        Path declared = declaredRoot(projectId, requested, baseIndexDir);
        return declared != null && strictlyEncloses(declared, requested) ? declared : requested;
    }

    /** True when {@code root} is an existing directory strictly above {@code dir}. */
    static boolean strictlyEncloses(Path root, Path dir) {
        Path canonicalRoot = canonical(root);
        Path canonicalDir = canonical(dir);
        return !canonicalDir.equals(canonicalRoot) && canonicalDir.startsWith(canonicalRoot)
                && Files.isDirectory(canonicalRoot);
    }

    private static Path canonical(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            return absolute.toRealPath();
        } catch (IOException unavailable) {
            return absolute;
        }
    }

    /**
     * The directory {@code projectId} is declared at: the root of the ACTIVE manifest coding
     * project with that id in the nearest manifest walking up from {@code start}, else the root
     * its local index records. Null when neither names one.
     */
    public static Path declaredRoot(String projectId, Path start) {
        return declaredRoot(projectId, start, LocalCodeIndexer.getBaseIndexDir());
    }

    /** {@link #declaredRoot(String, Path)} with an explicit index base directory (test seam). */
    static Path declaredRoot(String projectId, Path start, Path baseIndexDir) {
        if (projectId == null || !LocalCodeIndexer.isSafeProjectId(projectId)) return null;
        Path dir = (start != null ? start : Path.of(".")).toAbsolutePath().normalize();
        for (int i = 0; i < MAX_REGISTRATION_WALK && dir != null; i++, dir = dir.getParent()) {
            for (DeclaredProject project : activeManifestProjects(dir)) {
                if (project.id().equals(projectId)) return project.root();
            }
        }
        if (baseIndexDir == null) return null;
        try {
            Object rootPath = new IndexFileStore(baseIndexDir.resolve(projectId), MAPPER)
                    .loadMetadata().get("rootPath");
            return rootPath instanceof String root && !root.isBlank()
                    ? Path.of(root).toAbsolutePath().normalize() : null;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** The ACTIVE coding projects {@code dir}'s manifest declares; empty when absent or unreadable. */
    private static List<DeclaredProject> activeManifestProjects(Path dir) {
        Path manifest = dir.resolve("kompile.project.json");
        if (!Files.isRegularFile(manifest)) return List.of();
        try {
            JsonNode projects = MAPPER.readTree(manifest.toFile()).path("codingProjects");
            if (!projects.isArray()) return List.of();
            List<DeclaredProject> declared = new ArrayList<>();
            for (JsonNode project : projects) {
                String lifecycle = project.path("lifecycle").asText("").trim();
                if (!lifecycle.isEmpty() && !"ACTIVE".equalsIgnoreCase(lifecycle)) continue;
                String id = project.path("codeProjectId").asText("").trim();
                if (id.isEmpty()) id = project.path("id").asText("").trim();
                if (!LocalCodeIndexer.isSafeProjectId(id)) continue;
                String configuredRoot = project.path("rootPath").asText("").trim();
                Path root = configuredRoot.isEmpty() ? dir : Path.of(configuredRoot);
                if (!root.isAbsolute()) root = dir.resolve(root);
                declared.add(new DeclaredProject(id, root.toAbsolutePath().normalize()));
            }
            return declared;
        } catch (Exception ignored) {
            // Unreadable/corrupt manifest — callers keep walking for a parent manifest.
            return List.of();
        }
    }

    private static boolean hasSearchableIndex(Path baseIndexDir, String projectId) {
        if (baseIndexDir == null || !LocalCodeIndexer.isSafeProjectId(projectId)) return false;
        Path projectDir = baseIndexDir.resolve(projectId);
        if (!Files.isRegularFile(projectDir.resolve(IndexFileStore.METADATA_FILE))
                || !Files.isRegularFile(projectDir.resolve("index.db"))) {
            return false;
        }
        try {
            new IndexFileStore(projectDir, MAPPER).loadMetadata();
            return true;
        } catch (IOException unreadable) {
            // Torn by an interrupted write: report the id as unindexed so callers ask for an index.
            return false;
        }
    }

    /**
     * Walk up from {@code start} looking for the nearest
     * {@code .kompile/registration.json} with a usable project id.
     */
    private static String registrationProjectId(Path start) {
        Path dir = start;
        for (int i = 0; i < MAX_REGISTRATION_WALK && dir != null; i++, dir = dir.getParent()) {
            try {
                ProjectRegistration registration = ProjectRegistration.loadFromProject(dir);
                if (registration != null && registration.getProjectId() != null
                        && !registration.getProjectId().isBlank()
                        && LocalCodeIndexer.isSafeProjectId(registration.getProjectId().trim())) {
                    return registration.getProjectId().trim();
                }
            } catch (Exception ignored) {
                // Unreadable/corrupt registration — keep walking.
            }
        }
        return null;
    }

    /**
     * Scan the local index base dir for the project whose recorded
     * {@code rootPath} is the deepest ancestor of (or equal to) {@code cwd}.
     * Ties on depth break toward the most recently indexed project
     * (ISO-8601 {@code indexedAt} compares lexicographically).
     */
    private static String deepestIndexedRootProject(Path cwd, Path baseIndexDir) {
        if (baseIndexDir == null || !Files.isDirectory(baseIndexDir)) return null;
        String best = null;
        Path bestRoot = null;
        String bestIndexedAt = "";
        try (DirectoryStream<Path> projects = Files.newDirectoryStream(baseIndexDir)) {
            for (Path projectDir : projects) {
                Path metaFile = projectDir.resolve("metadata.json");
                if (!Files.isRegularFile(metaFile)
                        || !Files.isRegularFile(projectDir.resolve("index.db"))) continue;
                try {
                    JsonNode meta = MAPPER.readTree(metaFile.toFile());
                    String rootPath = meta.path("rootPath").asText("");
                    if (rootPath.isEmpty()) continue;
                    Path root = Path.of(rootPath).toAbsolutePath().normalize();
                    if (!cwd.startsWith(root)) continue;
                    String indexedAt = meta.path("indexedAt").asText("");
                    boolean deeper = bestRoot == null
                            || root.getNameCount() > bestRoot.getNameCount()
                            || (root.getNameCount() == bestRoot.getNameCount()
                                && indexedAt.compareTo(bestIndexedAt) > 0);
                    if (deeper) {
                        String candidate = projectDir.getFileName().toString();
                        if (!LocalCodeIndexer.isSafeProjectId(candidate)) continue;
                        best = candidate;
                        bestRoot = root;
                        bestIndexedAt = indexedAt;
                    }
                } catch (Exception ignored) {
                    // Corrupt metadata for one project must not break resolution.
                }
            }
        } catch (IOException e) {
            return null;
        }
        return best;
    }
}
