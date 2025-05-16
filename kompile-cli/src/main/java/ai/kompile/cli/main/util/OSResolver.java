/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

package ai.kompile.cli.main.util;

import org.jetbrains.annotations.NotNull;
import java.util.Locale;

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
     * Returns a normalized OS name string (e.g., "windows", "mac", "linux", "rhel", "debian", "ubuntu", "arch").
     * For Linux, it attempts to identify common distributions.
     * @return Normalized OS name.
     */
    public static String os() {
        if (isWindows()) {
            return "windows";
        } else if (isMac()) {
            return "mac"; // Consistent with "macosx" often used by JavaCPP
        } else if (isLinux()) {
            // Use the more specific getDistro() for Linux variants
            // The original ai.kompile.cli.main.util.OS.OS.getPlatformName() is not available here.
            // We'd need a way to get detailed platform name if that OS class is not used.
            // For now, let's assume a simpler Linux detection or that the old OS.OS class is available.
            // If OS.OS is not available, this part needs a new way to get distro info (e.g. reading /etc/os-release)
            // For simplicity here, we'll just return "linux".
            // To replicate original behavior, the calling context would need to provide the platformName.
            // String platformName = System.getProperty("os.name").toLowerCase(Locale.ROOT); // Fallback
            // return getDistro(platformName);
            return "linux"; // Simplified for now, as getDistro() relied on an external OS.OS
        } else {
            return "unknown"; // Or a more generic identifier
        }
    }

    // This method would ideally use a robust way to get Linux distribution info
    // if the original OS.OS.getPlatformName() is not used/available.
    // For now, it's a placeholder if we can't access the original OS class.
    @NotNull
    private static String getDistro(String platformNameInput) {
        String platformName = platformNameInput.toLowerCase(Locale.ROOT);
        if (isRhelVariant(platformName)) {
            return "rhel";
        } else if (platformName.contains("debian")) {
            return "debian";
        } else if (platformName.contains("ubuntu")) {
            return "ubuntu";
        } else if (platformName.contains("arch")) {
            return "arch";
        } else if (platformName.contains("linux")) { // Generic Linux if no specific distro matched
            return "linux";
        } else {
            return "generic-os"; // Fallback for non-Linux or very unknown Linux
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
