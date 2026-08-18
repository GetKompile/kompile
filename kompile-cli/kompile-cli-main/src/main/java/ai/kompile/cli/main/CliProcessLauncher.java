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
package ai.kompile.cli.main;

import ai.kompile.cli.common.util.JavaRuntimeLocator;
import ai.kompile.utils.NativeImageInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Resolves a distributable CLI process ABI for request-scoped children and the
 * optional local daemon. Native CLI processes re-exec the native binary;
 * executable-JAR processes use {@code java -jar}. Development classpaths are
 * deliberately not propagated into subprocesses.
 */
public final class CliProcessLauncher {

    private static final List<String> SHELL_NAMES =
            List.of("sh", "bash", "zsh", "dash", "fish");

    private CliProcessLauncher() { }

    /**
     * Native Kompile processes must remain native across request-scoped child
     * boundaries. Mixing a native parent with an executable Spring Boot JAR is
     * both operationally surprising and unsafe for the multi-gigabyte backend
     * artifacts shipped by local-model distributions.
     */
    public static boolean requiresNativeChildren() {
        return NativeImageInfo.isRunningInNativeImage();
    }

    public static boolean isChildArtifactCompatible(boolean nativeExecutable) {
        return childArtifactCompatible(requiresNativeChildren(), nativeExecutable);
    }

    static boolean childArtifactCompatible(boolean nativeParent, boolean nativeExecutable) {
        return !nativeParent || nativeExecutable;
    }

    public static void requireCompatibleChild(
            String component, boolean nativeExecutable, Path artifact) throws IOException {
        requireCompatibleChild(component, nativeExecutable, artifact, requiresNativeChildren());
    }

    static void requireCompatibleChild(
            String component, boolean nativeExecutable, Path artifact, boolean nativeParent)
            throws IOException {
        if (!childArtifactCompatible(nativeParent, nativeExecutable)) {
            throw new IOException("Native Kompile execution requires a native " + component
                    + " child executable, but resolved an executable JAR: " + artifact
                    + ". Install the matching bin/" + component
                    + " artifact or configure its native executable path. JAR fallbacks are "
                    + "available only when the parent Kompile process is running on the JVM.");
        }
    }

    /** Resolve the current native CLI or an explicitly runnable executable JAR. */
    public static Launcher find() {
        String binaryOverride = firstNonBlank(
                System.getProperty("kompile.cli.binary"),
                System.getenv("KOMPILE_CLI_BINARY"));
        if (isRunnableCommand(binaryOverride)) {
            return new Launcher(binaryOverride, List.of());
        }

        String currentCommand = normalizeCurrentCommand(
                ProcessHandle.current().info().command().orElse(null));
        if (currentCommand != null && !isJavaCommand(currentCommand)) {
            if (isShellCommand(currentCommand)) {
                String scriptLauncher = resolveShellWrappedLauncher();
                if (scriptLauncher != null) {
                    return new Launcher(scriptLauncher, List.of());
                }
            } else {
                return new Launcher(currentCommand, List.of());
            }
        }

        // A native process never drops into the executable-JAR tier. In particular,
        // KOMPILE_CLI_JAR must not override native self re-execution.
        if (requiresNativeChildren()) {
            return null;
        }

        String jarOverride = firstNonBlank(
                System.getProperty("kompile.cli.jar"),
                System.getenv("KOMPILE_CLI_JAR"));
        if (isRegularJar(jarOverride)) {
            return new Launcher(JavaRuntimeLocator.javaExecutable(),
                    List.of("-jar", Path.of(jarOverride).toAbsolutePath().normalize().toString()));
        }

        Path codeSource = resolveCodeSource();
        if (codeSource != null && Files.isRegularFile(codeSource)
                && codeSource.getFileName().toString().endsWith(".jar")) {
            return new Launcher(JavaRuntimeLocator.javaExecutable(),
                    List.of("-jar", codeSource.toString()));
        }
        return null;
    }

    /** Build a complete child command or fail with an actionable artifact requirement. */
    public static List<String> commandFor(List<String> applicationArgs) throws IOException {
        Launcher launcher = find();
        if (launcher == null) {
            throw new IOException("No native Kompile CLI or executable CLI JAR is available. "
                    + "Run the installed CLI, or set KOMPILE_CLI_BINARY / KOMPILE_CLI_JAR.");
        }
        return launcher.withArgs(applicationArgs);
    }

    /**
     * Remove Linux's diagnostic {@code " (deleted)"} suffix only when the
     * replacement path is a real executable.
     */
    public static String normalizeCurrentCommand(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String candidate = command.strip();
        String deletedSuffix = " (deleted)";
        if (candidate.endsWith(deletedSuffix)) {
            candidate = candidate.substring(0, candidate.length() - deletedSuffix.length()).strip();
        }
        return isRunnableCommand(candidate) ? candidate : null;
    }

    private static Path resolveCodeSource() {
        try {
            return Path.of(MainCommand.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath()
                    .normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String resolveShellWrappedLauncher() {
        String[] arguments = ProcessHandle.current().info().arguments().orElse(null);
        if (arguments == null || arguments.length == 0) {
            return null;
        }
        try {
            Path scriptPath = Path.of(arguments[0]).toAbsolutePath().normalize();
            return Files.isExecutable(scriptPath) ? scriptPath.toString() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isJavaCommand(String command) {
        String name = Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT);
        return name.equals("java") || name.equals("java.exe");
    }

    private static boolean isShellCommand(String command) {
        String name = Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT);
        return SHELL_NAMES.contains(name);
    }

    private static boolean isRegularJar(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            Path path = Path.of(value);
            return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar");
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isRunnableCommand(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        try {
            Path candidate = Path.of(command);
            boolean explicitPath = candidate.isAbsolute()
                    || command.contains("/")
                    || command.contains("\\");
            return !explicitPath || Files.isExecutable(candidate);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** A command plus fixed launcher arguments such as {@code -jar <artifact>}. */
    public record Launcher(String command, List<String> prefixArgs) {
        public Launcher {
            prefixArgs = List.copyOf(prefixArgs);
        }

        public List<String> withArgs(List<String> applicationArgs) {
            List<String> commandLine = new ArrayList<>(1 + prefixArgs.size()
                    + applicationArgs.size());
            commandLine.add(command);
            commandLine.addAll(prefixArgs);
            commandLine.addAll(applicationArgs);
            return commandLine;
        }
    }
}
