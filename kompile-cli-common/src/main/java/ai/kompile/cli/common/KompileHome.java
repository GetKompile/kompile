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
     * Returns the instances registry directory ({@code ~/.kompile/instances}).
     */
    public static File instancesDirectory() {
        return new File(homeDirectory(), "instances");
    }

    /**
     * Returns the configuration directory ({@code ~/.kompile/config}).
     */
    public static File configDirectory() {
        return new File(homeDirectory(), "config");
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
