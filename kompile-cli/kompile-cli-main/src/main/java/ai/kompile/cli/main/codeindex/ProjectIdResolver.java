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
 *       that id;</li>
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
            Path manifest = dir.resolve("kompile.project.json");
            if (!Files.isRegularFile(manifest)) continue;
            try {
                JsonNode projects = MAPPER.readTree(manifest.toFile()).path("codingProjects");
                if (!projects.isArray()) continue;
                String bestId = null;
                Path bestRoot = null;
                for (JsonNode project : projects) {
                    String lifecycle = project.path("lifecycle").asText("").trim();
                    if (!lifecycle.isEmpty() && !"ACTIVE".equalsIgnoreCase(lifecycle)) continue;
                    String id = project.path("codeProjectId").asText("").trim();
                    if (id.isEmpty()) id = project.path("id").asText("").trim();
                    if (!LocalCodeIndexer.isSafeProjectId(id)) continue;
                    String configuredRoot = project.path("rootPath").asText("").trim();
                    Path root = configuredRoot.isEmpty()
                            ? dir
                            : Path.of(configuredRoot);
                    if (!root.isAbsolute()) root = dir.resolve(root);
                    root = root.toAbsolutePath().normalize();
                    if (!start.startsWith(root)) continue;
                    if (bestRoot == null || root.getNameCount() > bestRoot.getNameCount()) {
                        bestId = id;
                        bestRoot = root;
                    }
                }
                if (bestId != null) return bestId;
            } catch (Exception ignored) {
                // Unreadable/corrupt manifest — keep walking for a parent manifest.
            }
        }
        return null;
    }

    private static boolean hasSearchableIndex(Path baseIndexDir, String projectId) {
        if (baseIndexDir == null || !LocalCodeIndexer.isSafeProjectId(projectId)) return false;
        Path projectDir = baseIndexDir.resolve(projectId);
        return Files.isRegularFile(projectDir.resolve("metadata.json"))
                && Files.isRegularFile(projectDir.resolve("index.db"));
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
