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

package ai.kompile.cli.common;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Central location for Kompile home directory paths.
 * All Kompile tools share the {@code ~/.kompile} directory for configuration,
 * model caches, installed toolchains, and instance registry.
 */
public final class KompileHome {

    private KompileHome() {
        // Utility class
    }

    /**
     * Returns the root Kompile home directory ({@code ~/.kompile}).
     */
    public static File homeDirectory() {
        return new File(System.getProperty("user.home"), ".kompile");
    }

    /**
     * The effective Kompile data-dir root, honoring the {@code kompile.data.dir}
     * system property first and the project launcher's {@code KOMPILE_PROJECT_ROOT}
     * environment variable second, then falling back to {@code ~/.kompile}.
     *
     * <p>This is what lets static callers (which cannot see Spring's
     * {@code --kompile.data.dir} command-line argument) resolve per-project
     * configuration and data, consistent with {@code @Value("${kompile.data.dir}")}
     * services. Note this is the data-dir ROOT (whose {@code config/} subdir holds
     * configs), not the {@code ~/.kompile/data} subdirectory returned by
     * {@link #dataDir()}.</p>
     */
    public static File resolvedHomeDirectory() {
        return resolveHomeDirectory(
                System.getProperty("kompile.data.dir"),
                System.getenv("KOMPILE_PROJECT_ROOT"));
    }

    /** Package-private seam for deterministic launch-context tests. */
    static File resolveHomeDirectory(String dataDirProperty, String projectRootEnvironment) {
        if (dataDirProperty != null && !dataDirProperty.isBlank()) {
            return new File(dataDirProperty);
        }
        if (projectRootEnvironment != null && !projectRootEnvironment.isBlank()) {
            return new File(projectRootEnvironment);
        }
        return homeDirectory();
    }

    /**
     * The name of the Kompile project manifest file placed at a project root.
     * Mirrors {@code KompileProjectStore.MANIFEST_FILE} without requiring that
     * module as a dependency.
     */
    private static final String PROJECT_MANIFEST = "kompile.project.json";

    /**
     * Resolves the effective project data-directory root using the same priority
     * order that Spring-managed services use, but without requiring the Spring
     * {@code Environment} or {@code KompileProjectStore}:
     *
     * <ol>
     *   <li>The {@code kompile.data.dir} JVM system property, when set
     *       (e.g. via {@code -Dkompile.data.dir=&lt;projectDir&gt;}).</li>
     *   <li>A walk-up from the JVM's current working directory
     *       ({@code System.getProperty("user.dir")}) looking for a
     *       {@code kompile.project.json} manifest — the same logic as
     *       {@code KompileProjectStore.findProjectRoot()}.  This covers the
     *       common launch pattern where the script {@code cd}s to the project
     *       root and passes {@code --kompile.data.dir} as a Spring CLI arg
     *       (which is NOT bridged to a JVM system property).</li>
     *   <li>Falls back to {@code ~/.kompile} so existing behaviour is preserved
     *       for CLI invocations that have no project context.</li>
     * </ol>
     *
     * <p>Use this in preference to {@link #resolvedHomeDirectory()} for any
     * per-project artefact (graph hashes, snapshots, health time-series, …) that
     * must live alongside the graph files the app already writes correctly to
     * {@code <projectDir>/data/graph/}.</p>
     */
    public static File resolvedProjectDirectory() {
        // Priority 1: explicit -D system property
        String dataDirProp = System.getProperty("kompile.data.dir");
        if (dataDirProp != null && !dataDirProp.isBlank()) {
            return new File(dataDirProp);
        }

        String cwd = System.getProperty("user.dir");
        if (cwd != null && !cwd.isBlank()) {
            Path cwdPath = Path.of(cwd).toAbsolutePath().normalize();
            // Priority 2: manifest walk-up from CWD (mirrors KompileProjectStore.findProjectRoot)
            Path current = cwdPath;
            while (current != null) {
                if (Files.isRegularFile(current.resolve(PROJECT_MANIFEST))) {
                    return current.toFile();
                }
                current = current.getParent();
            }
            // Priority 3: the CWD itself. Launch scripts `cd` to the project root before starting the
            // app (see run-cpu.sh), so the CWD is the project dir even when the project has no
            // kompile.project.json manifest yet (a built project that was never `project init`'d, like
            // the generated fpna-v7). This keeps per-project artefacts (graph hashes, snapshots) WITH
            // the project — and clearable alongside data/graph — instead of leaking into ~/.kompile.
            return cwdPath.toFile();
        }

        // Priority 4: no working directory at all → ~/.kompile (legacy CLI behaviour)
        return homeDirectory();
    }

    /**
     * Returns the Maven installation directory ({@code ~/.kompile/mvn}).
     */
    public static File mavenDirectory() {
        return new File(homeDirectory(), "mvn");
    }

    /**
     * Returns the GraalVM installation directory ({@code ~/.kompile/graalvm}).
     */
    public static File graalvmDirectory() {
        return new File(homeDirectory(), "graalvm");
    }

    /**
     * Returns the Python installation directory ({@code ~/.kompile/python}).
     */
    public static File pythonDirectory() {
        return new File(homeDirectory(), "python");
    }

    /**
     * Returns the CMake installation directory ({@code ~/.kompile/cmake}).
     */
    public static File cmakeDirectory() {
        return new File(homeDirectory(), "cmake");
    }

    /**
     * Returns the managed tool binary directory ({@code ~/.kompile/bin}).
     * Contains tool binaries installed by kompile (e.g., git-xet).
     * This directory is added to PATH when launching subprocesses.
     */
    public static File binDirectory() {
        return new File(homeDirectory(), "bin");
    }

    /**
     * Returns the models cache directory ({@code ~/.kompile/models}).
     */
    public static File modelsDirectory() {
        return new File(homeDirectory(), "models");
    }

    /**
     * Returns the LLM serving cache directory.
     *
     * <p>An explicit {@code -Dkompile.llm.cache.dir} wins. Project launches use
     * {@code <kompile.data.dir>/data/llm-cache}; non-project launches retain the
     * legacy {@code ~/.kompile/llm-cache} location.</p>
     */
    public static File llmCacheDirectory() {
        String explicit = System.getProperty("kompile.llm.cache.dir");
        if (explicit != null && !explicit.isBlank()) {
            return new File(explicit);
        }
        String projectRoot = System.getProperty("kompile.data.dir");
        if (projectRoot != null && !projectRoot.isBlank()) {
            return new File(new File(projectRoot, "data"), "llm-cache");
        }
        return new File(homeDirectory(), "llm-cache");
    }

    /**
     * Returns the instances registry directory ({@code ~/.kompile/instances}).
     */
    public static File instancesDirectory() {
        return new File(homeDirectory(), "instances");
    }

    /**
     * Returns the configuration directory ({@code ~/.kompile/config}).
     */
    public static File configDirectory() {
        return new File(resolvedHomeDirectory(), "config");
    }

    /**
     * Returns the sessions directory ({@code ~/.kompile/sessions}).
     */
    public static File sessionsDirectory() {
        return new File(homeDirectory(), "sessions");
    }

    /**
     * Returns the data directory ({@code ~/.kompile/data}).
     */
    public static File dataDir() {
        return new File(homeDirectory(), "data");
    }

    /**
     * Returns the runtime directory ({@code ~/.kompile/run}).
     */
    public static File runtimeDirectory() {
        return new File(homeDirectory(), "run");
    }

    /**
     * Returns the daemon Unix socket file ({@code ~/.kompile/run/kompile.sock}).
     */
    public static File daemonSocketFile() {
        return new File(runtimeDirectory(), "kompile.sock");
    }

    /**
     * Returns the daemon lock file ({@code ~/.kompile/run/kompile.lock}).
     */
    public static File daemonLockFile() {
        return new File(runtimeDirectory(), "kompile.lock");
    }
}
