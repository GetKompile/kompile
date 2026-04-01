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

package ai.kompile.cli.common.util;

import java.util.Locale;

/**
 * Operating system and architecture detection utilities.
 */
public class OSResolver {

    private static final String OS_NAME = System.getProperty("os.name", "generic").toLowerCase(Locale.ROOT);
    private static final String OS_ARCH = System.getProperty("os.arch", "generic").toLowerCase(Locale.ROOT);

    public static boolean isMac() {
        return OS_NAME.contains("mac") || OS_NAME.contains("darwin");
    }

    public static boolean isWindows() {
        return OS_NAME.contains("win");
    }

    public static boolean isLinux() {
        return OS_NAME.contains("nix") || OS_NAME.contains("nux") || OS_NAME.contains("aix");
    }

    public static boolean isUnix() {
        return isLinux() || isMac() || OS_NAME.contains("sunos") || OS_NAME.contains("freebsd") || OS_NAME.contains("openbsd");
    }

    /**
     * Returns a normalized OS name string (e.g., "windows", "mac", "linux").
     */
    public static String os() {
        if (isWindows()) {
            return "windows";
        } else if (isMac()) {
            return "mac";
        } else if (isLinux()) {
            return "linux";
        } else {
            return "unknown";
        }
    }

    private static boolean isRhelVariant(String platformName) {
        return platformName.contains("centos") ||
                platformName.contains("fedora") ||
                platformName.contains("rhel") ||
                platformName.contains("rocky") ||
                platformName.contains("almalinux") ||
                platformName.contains("oracle");
    }

    /**
     * Returns a normalized architecture string (e.g., "x86_64", "arm64", "ppc64le").
     */
    public static String arch() {
        String arch = OS_ARCH;
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            return "x86_64";
        } else if (arch.equals("aarch64") || arch.startsWith("armv8") || arch.equals("arm64")) {
            return "arm64";
        } else if (arch.startsWith("arm")) {
            return "armhf";
        } else if (arch.equals("ppc64le")) {
            return "ppc64le";
        }
        return arch;
    }

    /**
     * Returns the typical shared library extension for the current OS.
     */
    public static String sharedLibraryExtension() {
        if (isWindows()) {
            return ".dll";
        } else if (isMac()) {
            return ".dylib";
        } else {
            return ".so";
        }
    }
}
