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
}
