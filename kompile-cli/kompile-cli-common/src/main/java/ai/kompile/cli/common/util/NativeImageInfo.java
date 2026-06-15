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

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility class for detecting GraalVM native image execution context.
 *
 * <p>This class uses reflection to detect native image mode without requiring
 * a compile-time dependency on GraalVM SDK.</p>
 */
public final class NativeImageInfo {

    private static final Boolean IS_NATIVE_IMAGE;
    private static final String EXECUTABLE_PATH;

    static {
        IS_NATIVE_IMAGE = detectNativeImage();
        EXECUTABLE_PATH = detectExecutablePath();
    }

    private NativeImageInfo() {
    }

    public static boolean isRunningInNativeImage() {
        return IS_NATIVE_IMAGE != null && IS_NATIVE_IMAGE;
    }

    public static String getExecutablePath() {
        return EXECUTABLE_PATH;
    }

    public static Path getExecutablePathAsPath() {
        return EXECUTABLE_PATH != null ? Paths.get(EXECUTABLE_PATH) : null;
    }

    public static boolean hasClasspath() {
        String classpath = System.getProperty("java.class.path");
        if (classpath == null || classpath.isBlank()) {
            return false;
        }
        String[] entries = classpath.split(System.getProperty("path.separator"));
        for (String entry : entries) {
            if (entry.endsWith(".jar") || entry.endsWith("/classes") || entry.endsWith("\\classes")) {
                return true;
            }
        }
        return false;
    }

    public static SubprocessLaunchMode getRecommendedLaunchMode() {
        if (isRunningInNativeImage()) {
            return SubprocessLaunchMode.NATIVE_EXECUTABLE;
        }
        if (hasClasspath()) {
            return SubprocessLaunchMode.JVM_CLASSPATH;
        }
        return SubprocessLaunchMode.NATIVE_EXECUTABLE;
    }

    private static Boolean detectNativeImage() {
        try {
            Class<?> imageInfoClass = Class.forName("org.graalvm.nativeimage.ImageInfo");
            Method inImageCodeMethod = imageInfoClass.getMethod("inImageCode");
            Object result = inImageCodeMethod.invoke(null);
            return (Boolean) result;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (NoSuchMethodException | IllegalAccessException | java.lang.reflect.InvocationTargetException e) {
            return false;
        }
    }

    private static String detectExecutablePath() {
        if (!Boolean.TRUE.equals(IS_NATIVE_IMAGE)) {
            return null;
        }

        try {
            Class<?> processPropertiesClass = Class.forName("org.graalvm.nativeimage.ProcessProperties");
            Method getExecutableNameMethod = processPropertiesClass.getMethod("getExecutableName");
            Object result = getExecutableNameMethod.invoke(null);
            if (result != null) {
                return result.toString();
            }
        } catch (Exception e) {
            // Fall through to alternatives
        }

        String command = System.getProperty("sun.java.command");
        if (command != null && !command.isBlank()) {
            String[] parts = command.split("\\s+");
            if (parts.length > 0 && !parts[0].contains(".class")) {
                return parts[0];
            }
        }

        Path procSelfExe = Paths.get("/proc/self/exe");
        if (java.nio.file.Files.exists(procSelfExe)) {
            try {
                return java.nio.file.Files.readSymbolicLink(procSelfExe).toString();
            } catch (Exception e) {
                // Ignore
            }
        }

        return null;
    }

    /**
     * Enumeration of subprocess launch modes.
     */
    public enum SubprocessLaunchMode {
        JVM_CLASSPATH,
        NATIVE_EXECUTABLE
    }
}
