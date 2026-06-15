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

package ai.kompile.cli.main.util;

import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public class OSResolver {

    private static final String OS_NAME = System.getProperty("os.name", "generic").toLowerCase(Locale.ROOT);
    private static final String OS_ARCH = System.getProperty("os.arch", "generic").toLowerCase(Locale.ROOT);
    private static volatile String cachedDistro = null;

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
     * Returns a normalized OS name string (e.g., "windows", "mac", "linux", "rhel", "debian", "ubuntu", "arch").
     * For Linux, it attempts to identify common distributions.
     * @return Normalized OS name.
     */
    public static String os() {
        if (isWindows()) {
            return "windows";
        } else if (isMac()) {
            return "mac";
        } else if (isLinux()) {
            return getLinuxDistro();
        } else {
            return "unknown";
        }
    }

    /**
     * Detects the Linux distribution by reading /etc/os-release.
     * Falls back to "linux" if the file is not available.
     */
    @NotNull
    private static String getLinuxDistro() {
        if (cachedDistro != null) {
            return cachedDistro;
        }

        String distro = detectLinuxDistro();
        cachedDistro = distro;
        return distro;
    }

    @NotNull
    private static String detectLinuxDistro() {
        Path osRelease = Path.of("/etc/os-release");
        if (Files.exists(osRelease)) {
            try (BufferedReader reader = Files.newBufferedReader(osRelease)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("ID=")) {
                        String id = line.substring(3).replace("\"", "").trim().toLowerCase(Locale.ROOT);
                        return mapDistroId(id);
                    }
                }
            } catch (IOException e) {
                // Fall through to fallback
            }
        }

        // Fallback: check for distro-specific files
        if (Files.exists(Path.of("/etc/redhat-release"))) return "rhel";
        if (Files.exists(Path.of("/etc/debian_version"))) return "debian";

        return "linux";
    }

    @NotNull
    private static String mapDistroId(String id) {
        if (isRhelVariant(id)) return "rhel";
        switch (id) {
            case "ubuntu":   return "ubuntu";
            case "debian":   return "debian";
            case "arch":
            case "manjaro":  return "arch";
            case "opensuse":
            case "sles":     return "suse";
            case "alpine":   return "alpine";
            default:         return id.isEmpty() ? "linux" : id;
        }
    }

    private static boolean isRhelVariant(String platformName) {
        return platformName.contains("centos") ||
                platformName.contains("fedora") ||
                platformName.contains("rhel") || // Added rhel itself
                platformName.contains("rocky") ||
                platformName.contains("almalinux") || // Added AlmaLinux
                platformName.contains("oracle");
    }

    /**
     * Returns a normalized architecture string (e.g., "x86_64", "arm64", "ppc64le").
     * @return Normalized architecture string.
     */
    public static String arch() {
        String arch = OS_ARCH;
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            return "x86_64";
        } else if (arch.equals("aarch64") || arch.startsWith("armv8") || arch.equals("arm64")) {
            return "arm64";
        } else if (arch.startsWith("arm")) {
            return "armhf"; // Or determine 32-bit arm variant more precisely if needed
        } else if (arch.equals("ppc64le")) {
            return "ppc64le";
        }
        // Add more mappings as needed
        return arch; // Return original if no specific mapping
    }

    /**
     * Returns the JavaCPP platform string for the current OS and architecture.
     * Examples: "linux-x86_64", "linux-arm64", "macosx-arm64", "windows-x86_64".
     * This is the canonical format used by JavaCPP/nd4j for platform-specific artifacts.
     * @return JavaCPP platform string.
     */
    public static String javacppPlatform() {
        String os;
        if (isWindows()) {
            os = "windows";
        } else if (isMac()) {
            os = "macosx";
        } else {
            os = "linux";
        }
        return os + "-" + arch();
    }

    /**
     * Returns the typical shared library extension for the current OS.
     * (e.g., ".so" for Linux, ".dylib" for macOS, ".dll" for Windows).
     * @return The shared library extension string.
     */
    public static String sharedLibraryExtension() {
        if (isWindows()) {
            return ".dll";
        } else if (isMac()) {
            return ".dylib";
        } else { // Linux and other Unix-like
            return ".so";
        }
    }
}
