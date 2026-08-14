/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.common.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Resolves which {@code java} executable to use when launching executable-JAR
 * components (model serving, pipeline execution, staging, and optional UI services)
 * from the CLI or generated scripts.
 *
 * <p>Kompile distributions bundle a jlink-built runtime at {@code <dist-root>/runtime}
 * so the jar tier works on boxes with no system JDK. Resolution order:</p>
 * <ol>
 *   <li>{@code $KOMPILE_JAVA} — explicit path to a java executable</li>
 *   <li>Bundled runtime in the install dir: {@code $KOMPILE_INSTALL_DIR/runtime/bin/java},
 *       falling back to {@code ~/.kompile/runtime/bin/java}</li>
 *   <li>Bundled runtime relative to the current executable (running from an
 *       extracted dist without installing): {@code <exe>/../../runtime/bin/java}</li>
 *   <li>{@code $JAVA_HOME/bin/java}</li>
 *   <li>The JVM running this process, when this process is itself a JVM
 *       (jar-launched CLI; excluded under native image where the current
 *       executable is the kompile binary)</li>
 *   <li>An SDKMAN Java 17 GraalVM candidate, discovered through {@code $SDKMAN_DIR}
 *       or {@code ~/.sdkman} (including the {@code current} candidate)</li>
 *   <li>{@code java} from {@code $PATH}</li>
 * </ol>
 */
public final class JavaRuntimeLocator {

    private JavaRuntimeLocator() { }

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    /** Resolve the java executable path (or bare {@code java} as a last resort). */
    public static String javaExecutable() {
        String override = System.getenv("KOMPILE_JAVA");
        if (override != null && !override.isBlank() && isExecutable(new File(override))) {
            return override;
        }

        File bundled = bundledRuntimeJava();
        if (bundled != null) {
            return bundled.getAbsolutePath();
        }

        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            File home = javaBin(Paths.get(javaHome));
            if (isExecutable(home)) {
                return home.getAbsolutePath();
            }
        }

        File current = currentProcessJava();
        if (current != null) {
            return current.getAbsolutePath();
        }

        File sdkman = sdkmanJava17Graal();
        if (sdkman != null) {
            return sdkman.getAbsolutePath();
        }

        return "java";
    }

    /** True when a usable java executable was found somewhere other than bare PATH fallback. */
    public static boolean hasExplicitRuntime() {
        return !"java".equals(javaExecutable());
    }

    /**
     * The bundled jlink runtime's java executable, or null when no bundled
     * runtime is present. Checks the install dir and the dist tree the current
     * executable runs from.
     */
    public static File bundledRuntimeJava() {
        String installDir = System.getenv("KOMPILE_INSTALL_DIR");
        if (installDir != null && !installDir.isBlank()) {
            File java = javaBin(Paths.get(installDir, "runtime"));
            if (isExecutable(java)) return java;
        }

        File homeRuntime = javaBin(Paths.get(System.getProperty("user.home"), ".kompile", "runtime"));
        if (isExecutable(homeRuntime)) return homeRuntime;

        // Running from an extracted (not installed) dist: <root>/bin/kompile → <root>/runtime
        Optional<String> exe = ProcessHandle.current().info().command();
        if (exe.isPresent()) {
            Path exePath = Paths.get(exe.get()).toAbsolutePath().normalize();
            Path parent = exePath.getParent();
            if (parent != null && parent.getParent() != null) {
                File distRuntime = javaBin(parent.getParent().resolve("runtime"));
                if (isExecutable(distRuntime)) return distRuntime;
            }
        }
        return null;
    }

    private static File currentProcessJava() {
        Optional<String> command = ProcessHandle.current().info().command();
        if (command.isEmpty()) return null;
        File exe = new File(command.get());
        String name = exe.getName();
        boolean isJava = name.equals("java") || name.equals("java.exe");
        return isJava && isExecutable(exe) ? exe : null;
    }

    /**
     * Discover the SDKMAN-managed Java 17 GraalVM used by local Kompile builds and
     * executable-JAR subprocesses. Candidate names are inspected rather than pinning
     * a particular GraalVM patch release, so SDKMAN upgrades remain transparent.
     */
    private static File sdkmanJava17Graal() {
        String configuredRoot = System.getenv("SDKMAN_DIR");
        Path sdkmanRoot = configuredRoot == null || configuredRoot.isBlank()
                ? Paths.get(System.getProperty("user.home"), ".sdkman")
                : Paths.get(configuredRoot);
        Path candidates = sdkmanRoot.resolve("candidates").resolve("java");

        Path current = candidates.resolve("current");
        File currentJava = javaBin(current);
        if (isExecutable(currentJava)
                && isJava17GraalCandidate(resolveCandidateName(current))) {
            return currentJava;
        }

        if (!Files.isDirectory(candidates)) {
            return null;
        }
        try (Stream<Path> entries = Files.list(candidates)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(path -> isJava17GraalCandidate(path.getFileName().toString()))
                    .sorted(Comparator.comparingLong(
                            (Path path) -> path.toFile().lastModified()).reversed())
                    .map(JavaRuntimeLocator::javaBin)
                    .filter(JavaRuntimeLocator::isExecutable)
                    .findFirst()
                    .orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String resolveCandidateName(Path candidate) {
        try {
            return candidate.toRealPath().getFileName().toString();
        } catch (IOException ignored) {
            Path name = candidate.getFileName();
            return name == null ? "" : name.toString();
        }
    }

    private static boolean isJava17GraalCandidate(String candidateName) {
        String name = candidateName.toLowerCase(Locale.ROOT);
        return (name.startsWith("17") || name.contains("-17") || name.contains("17-"))
                && name.contains("graal");
    }

    private static File javaBin(Path runtimeRoot) {
        return runtimeRoot.resolve("bin").resolve(WINDOWS ? "java.exe" : "java").toFile();
    }

    private static boolean isExecutable(File f) {
        return f != null && f.isFile() && f.canExecute();
    }
}
