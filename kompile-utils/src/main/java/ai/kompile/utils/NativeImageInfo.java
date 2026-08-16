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

package ai.kompile.utils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility class for detecting GraalVM native image execution context.
 *
 * <p>This class uses reflection to detect native image mode without requiring
 * a compile-time dependency on GraalVM SDK.</p>
 */
public final class NativeImageInfo {

    static final String IMAGE_CODE_PROPERTY = "org.graalvm.nativeimage.imagecode";

    // Lazily computed at first use — NEVER in a static initializer: when GraalVM
    // class-initializes this class at image BUILD time, values captured then
    // describe the builder's process (executable path = builder java / null),
    // which is how subprocess self-exec ended up probing "./kompile-app".
    // Same bug family as ND4JClassLoading's baked classloader. The detection is
    // idempotent, so racy double-computation under the volatile reads is fine.
    private static volatile Boolean isNativeImage;
    private static volatile String executablePath;
    private static volatile boolean executablePathResolved;

    private NativeImageInfo() {
    }

    public static boolean isRunningInNativeImage() {
        // Runtime image state must win over any value captured while Graal was
        // initializing classes in the hosted builder. A cached hosted-process
        // false would otherwise make the executable skip its side-loaded JNI
        // bootstrap entirely.
        if (isRuntimeImageCode()) {
            return true;
        }
        Boolean detected = isNativeImage;
        if (detected == null) {
            detected = detectNativeImage();
            isNativeImage = detected;
        }
        return Boolean.TRUE.equals(detected);
    }

    public static String getExecutablePath() {
        // The runtime executable can differ from the builder output path after
        // a distribution copies the image. Re-resolve it when runtime imagecode
        // is present instead of trusting a hosted-process cache.
        if (isRuntimeImageCode()) {
            executablePath = detectExecutablePath();
            executablePathResolved = true;
            return executablePath;
        }
        if (!executablePathResolved) {
            executablePath = detectExecutablePath();
            executablePathResolved = true;
        }
        return executablePath;
    }

    private static boolean isRuntimeImageCode() {
        return "runtime".equalsIgnoreCase(System.getProperty(IMAGE_CODE_PROPERTY));
    }

    public static Path getExecutablePathAsPath() {
        String path = getExecutablePath();
        return path != null ? Paths.get(path) : null;
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

    static Boolean detectNativeImage() {
        // Graal publishes this property directly in hosted and runtime image code.
        // Use it before reflection: small subprocess images do not all retain
        // reflective access to ImageInfo, which previously made otherwise-native
        // workers fall into the JVM/classpath native-library path.
        String imageCode = System.getProperty(IMAGE_CODE_PROPERTY);
        if ("runtime".equalsIgnoreCase(imageCode)
                || "buildtime".equalsIgnoreCase(imageCode)) {
            return true;
        }
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
        if (!isRunningInNativeImage()) {
            return null;
        }

        // On Linux this is the kernel-owned runtime truth and resolves launcher
        // symlinks to the copied distribution binary. Graal ProcessProperties can
        // retain the native-image output path from the build tree after that image
        // is copied, which makes component lookup silently fall back to ~/.kompile.
        Path procSelfExe = Paths.get("/proc/self/exe");
        if (Files.exists(procSelfExe)) {
            try {
                return Files.readSymbolicLink(procSelfExe).toString();
            } catch (Exception e) {
                // Fall through to portable alternatives.
            }
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
