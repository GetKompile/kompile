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

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Advanced platform detection with normalization for OS, architecture, and kernel.
 */
public class PlatformDetector {

    public static class PlatformInfo {
        private final String osName;
        private final String osKernel;
        private final String osArch;
        private final String osFamily;

        public PlatformInfo(String osName, String osKernel, String osArch, String osFamily) {
            this.osName = osName;
            this.osKernel = osKernel;
            this.osArch = osArch;
            this.osFamily = osFamily;
        }

        public String getOsName() { return osName; }
        public String getOsKernel() { return osKernel; }
        public String getOsArch() { return osArch; }
        public String getOsFamily() { return osFamily; }

        @Override
        public String toString() {
            return String.format("PlatformInfo{osName='%s', osKernel='%s', osArch='%s', osFamily='%s'}",
                    osName, osKernel, osArch, osFamily);
        }

        public String getIdentifier() {
            return osName + "-" + osArch;
        }

        public String getFileExtension() {
            switch (osName.toLowerCase()) {
                case "linux": return ".so";
                case "windows": return ".dll";
                case "mac": return ".dylib";
                default: return ".so";
            }
        }
    }

    public static PlatformInfo detectPlatform() {
        String rawOsName = System.getProperty("os.name").toLowerCase(Locale.ENGLISH);
        String rawOsArch = System.getProperty("os.arch").toLowerCase(Locale.ENGLISH);

        String normalizedOsName;
        String osKernel;
        String osFamily = null;

        if (rawOsName.contains("linux")) {
            if (rawOsName.contains("android")) {
                normalizedOsName = "android";
                osKernel = "linux";
            } else {
                normalizedOsName = "linux";
                osKernel = "linux";
            }
        } else if (rawOsName.contains("mac os x") || rawOsName.contains("macos") || rawOsName.contains("darwin")) {
            normalizedOsName = "macosx";
            osKernel = "darwin";
        } else if (rawOsName.contains("windows")) {
            normalizedOsName = "windows";
            osKernel = "windows";
            osFamily = "windows";
        } else {
            normalizedOsName = rawOsName.replaceAll("\\s+", "");
            osKernel = normalizedOsName;
        }

        String normalizedArch = normalizeArchitecture(rawOsArch);

        if ("android".equals(normalizedOsName) && normalizedArch.startsWith("arm")) {
            normalizedArch = "arm";
        }

        return new PlatformInfo(normalizedOsName, osKernel, normalizedArch, osFamily);
    }

    private static String normalizeArchitecture(String rawArch) {
        if (rawArch.equals("arm") || rawArch.equals("armhf")) {
            return "armhf";
        }
        if (rawArch.equals("aarch64") || rawArch.equals("arm64") || rawArch.equals("armv8")) {
            return "arm64";
        }
        if (rawArch.equals("i386") || rawArch.equals("i486") || rawArch.equals("i586") ||
                rawArch.equals("i686") || rawArch.equals("x86") || rawArch.equals("amd64") ||
                rawArch.equals("x86-64") || rawArch.equals("x86_64")) {
            return "x86_64";
        }
        return rawArch;
    }

    public static boolean matchesPlatform(String expectedOsName, String expectedArch, String expectedFamily) {
        PlatformInfo info = detectPlatform();
        if (expectedOsName != null && !expectedOsName.equals(info.getOsName())) {
            return false;
        }
        if (expectedArch != null && !expectedArch.equals(info.getOsArch())) {
            return false;
        }
        if (expectedFamily != null && !expectedFamily.equals(info.getOsFamily())) {
            return false;
        }
        return true;
    }

    public static Map<String, Boolean> getActiveProfiles() {
        PlatformInfo info = detectPlatform();
        Map<String, Boolean> profiles = new HashMap<>();
        profiles.put("linux", "linux".equals(info.getOsName()));
        profiles.put("macosx", "macosx".equals(info.getOsName()));
        profiles.put("windows", "windows".equals(info.getOsName()));
        profiles.put("android", "android".equals(info.getOsName()));
        profiles.put("arm", "armhf".equals(info.getOsArch()));
        profiles.put("aarch64", "arm64".equals(info.getOsArch()));
        profiles.put("armv8", "arm64".equals(info.getOsArch()));
        profiles.put("i386", "x86_64".equals(info.getOsArch()));
        profiles.put("i486", "x86_64".equals(info.getOsArch()));
        profiles.put("i586", "x86_64".equals(info.getOsArch()));
        profiles.put("i686", "x86_64".equals(info.getOsArch()));
        profiles.put("x86", "x86_64".equals(info.getOsArch()));
        profiles.put("amd64", "x86_64".equals(info.getOsArch()));
        profiles.put("x86-64", "x86_64".equals(info.getOsArch()));
        return profiles;
    }
}
