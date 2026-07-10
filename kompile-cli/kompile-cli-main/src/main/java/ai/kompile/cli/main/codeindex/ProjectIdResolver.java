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
     * {@code explicit}, {@code registration}, {@code index-root},
     * {@code registration-unindexed}, {@code cwd-name}.
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
            return new Resolution(explicit.trim(), "explicit", false);
        }
        Path cwd = (workingDirectory != null ? workingDirectory : Path.of("."))
                .toAbsolutePath().normalize();

        String registrationId = registrationProjectId(cwd);
        if (registrationId != null && Files.isDirectory(baseIndexDir.resolve(registrationId))) {
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
        return new Resolution(name != null ? name.toString() : "default", "cwd-name", true);
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
                        && !registration.getProjectId().isBlank()) {
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
                if (!Files.isRegularFile(metaFile)) continue;
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
                        best = projectDir.getFileName().toString();
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
