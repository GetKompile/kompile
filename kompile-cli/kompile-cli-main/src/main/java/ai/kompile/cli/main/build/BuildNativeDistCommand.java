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

package ai.kompile.cli.main.build;

import ai.kompile.cli.common.util.ByteFormatUtils;
import ai.kompile.cli.common.util.EnvironmentUtils;
import org.apache.maven.shared.invoker.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Production distribution native image build.
 *
 * <p>Builds a self-contained tarball ({@code <name>-<version>-native-dist.tar.gz}) using
 * the {@code native-dist} Maven profile.  The tarball contains:
 * <ul>
 *   <li>The GraalVM native binary</li>
 *   <li>A {@code lib/} directory with all required {@code .so} files</li>
 * </ul>
 *
 * <p>This means the binary can run on any compatible Linux machine without needing
 * {@code ~/.javacpp/cache/} or any Java installation — all native libraries are bundled.
 *
 * <p>The {@code native-dist} profile assumes the native image was already built (by
 * running {@code mvn package -Pnative -DskipTests} first), so this command runs both
 * profiles in sequence unless {@code --skip-native-build} is set.
 *
 * <p>Usage:
 * <pre>
 *   # Build native image + distribution tarball in one step
 *   kompile build native-dist --project-dir=/path/to/my-rag-app
 *
 *   # Only assemble the distribution tarball (native image already built)
 *   kompile build native-dist --project-dir=/path/to/my-rag-app --skip-native-build
 *
 *   # Use a specific GraalVM installation
 *   kompile build native-dist --project-dir=/path/to/my-rag-app \
 *       --graalvm-home=/home/user/.sdkman/candidates/java/21.0.10-graal
 * </pre>
 *
 * <p>For the developer in-place workflow (no tarball), use {@code kompile build native-dev}.
 */
@Command(name = "native-dist",
        mixinStandardHelpOptions = true,
        description = "Build a GraalVM native image + self-contained distribution tarball.\n\n" +
                "Runs 'mvn package -Pnative -DskipTests' then 'mvn package -Pnative-dist -DskipTests'.\n" +
                "Produces: <project-dir>/target/<name>-<version>-native-dist.tar.gz\n\n" +
                "The tarball contains the binary and a lib/ directory with all .so files.\n" +
                "Extract and run anywhere without needing Java or ~/.javacpp/cache/:\n" +
                "  tar -xzf <name>-native-dist.tar.gz\n" +
                "  ./<name>-native/<name>-native\n\n" +
                "For in-place developer builds (no tarball), use:\n" +
                "  kompile build native-dev --project-dir=<dir>\n\n" +
                "Examples:\n" +
                "  kompile build native-dist --project-dir=./my-rag-app\n" +
                "  kompile build native-dist --project-dir=./my-rag-app --skip-native-build\n" +
                "  kompile build native-dist --project-dir=./my-rag-app --graalvm-home=~/.sdkman/candidates/java/21.0.10-graal\n")
public class BuildNativeDistCommand implements Callable<Integer> {

    @Option(names = {"--project-dir", "-d"},
            description = "Path to the generated project directory to build. Default: current directory",
            defaultValue = ".")
    private File projectDir;

    @Option(names = {"--graalvm-home"},
            description = "Path to GraalVM installation. Default: ~/.kompile/graalvm, then sdkman, then JAVA_HOME.")
    private File graalvmHome;

    @Option(names = {"--maven-home"},
            description = "Path to Maven installation. Default: ~/.kompile/mvn, then $M2_HOME, then PATH.")
    private File mavenHome;

    @Option(names = {"--skip-tests"},
            description = "Skip Maven tests. Default: true",
            defaultValue = "true",
            negatable = true)
    private boolean skipTests;

    @Option(names = {"--skip-native-build"},
            description = "Skip the native image build step (assume it was already run). " +
                    "Only runs the native-dist assembly step.",
            defaultValue = "false")
    private boolean skipNativeBuild;

    @Option(names = {"--javacpp-platform"},
            description = "JavaCPP platform string (e.g., linux-x86_64). Default: linux-x86_64",
            defaultValue = "linux-x86_64")
    private String javacppPlatform;

    @Option(names = {"--javacpp-extension"},
            description = "JavaCPP extension (e.g., avx2, cuda).")
    private String javacppExtension;

    @Override
    public Integer call() throws Exception {
        File resolvedProjectDir = projectDir.getAbsoluteFile().getCanonicalFile();
        if (!resolvedProjectDir.exists() || !resolvedProjectDir.isDirectory()) {
            System.err.println("Project directory not found: " + resolvedProjectDir);
            System.err.println("Generate a project first: kompile init-project --name=myapp");
            return 1;
        }

        File pomFile = new File(resolvedProjectDir, "pom.xml");
        if (!pomFile.exists()) {
            System.err.println("No pom.xml found in: " + resolvedProjectDir);
            System.err.println("This command expects a generated kompile project directory.");
            System.err.println("Generate one with: kompile init-project --name=myapp");
            return 1;
        }

        File effectiveGraalVm = resolveGraalVmHome();
        if (effectiveGraalVm == null || !effectiveGraalVm.exists()) {
            System.err.println("GraalVM not found. Tried:");
            System.err.println("  1. ~/.kompile/graalvm");
            System.err.println("  2. ~/.sdkman/candidates/java/ (first GraalVM entry)");
            System.err.println("  3. $JAVA_HOME (if it is a GraalVM)");
            System.err.println("  4. --graalvm-home option");
            System.err.println("Install GraalVM: kompile install graalvm");
            System.err.println("Or use sdkman: sdk install java 21.0.10-graal");
            return 1;
        }
        System.out.println("Using GraalVM: " + effectiveGraalVm.getAbsolutePath());

        File effectiveMaven = resolveMavenHome();
        if (effectiveMaven == null || !effectiveMaven.exists()) {
            System.err.println("Maven not found. Searched: ~/.kompile/mvn, $M2_HOME, PATH.");
            System.err.println("Use --maven-home to specify Maven installation.");
            return 1;
        }
        System.out.println("Using Maven: " + effectiveMaven.getAbsolutePath());

        // Step 1: Build native image (unless --skip-native-build)
        if (!skipNativeBuild) {
            System.out.println("\n[Step 1/2] Building native image (profile: native)...");
            int buildResult = invokeMavenBuild(resolvedProjectDir, pomFile, effectiveGraalVm, effectiveMaven, "native");
            if (buildResult != 0) {
                return buildResult;
            }
        } else {
            System.out.println("\n[Step 1/2] Skipping native image build (--skip-native-build set).");
            // Verify the native binary exists
            File nativeBinary = findNativeBinary(resolvedProjectDir);
            if (nativeBinary == null) {
                System.err.println("No native binary found in " + resolvedProjectDir + "/target/");
                System.err.println("Run without --skip-native-build to build the native image first.");
                return 1;
            }
            System.out.println("  Found native binary: " + nativeBinary.getAbsolutePath());
        }

        // Step 2: Assemble distribution tarball
        System.out.println("\n[Step 2/2] Assembling distribution tarball (profile: native-dist)...");
        int distResult = invokeMavenBuild(resolvedProjectDir, pomFile, effectiveGraalVm, effectiveMaven, "native-dist");
        if (distResult != 0) {
            return distResult;
        }

        printPostBuildInstructions(resolvedProjectDir);
        return 0;
    }

    // ========== Internal helpers ==========

    private int invokeMavenBuild(File projectDir, File pomFile,
                                  File graalVmHome, File mavenHome,
                                  String profile) throws MavenInvocationException {
        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(pomFile);

        List<String> goals = new ArrayList<>();
        if (javacppPlatform != null && !javacppPlatform.isEmpty()) {
            goals.add("-Djavacpp.platform=" + javacppPlatform);
            if (javacppExtension != null && !javacppExtension.isEmpty()) {
                goals.add("-Djavacpp.platform.extension=" + javacppExtension);
            }
        }
        // native-dist doesn't need clean (it reuses the previously built binary),
        // but native profile does want a clean build to avoid stale artifacts.
        if ("native".equals(profile)) {
            goals.add("clean");
        }
        goals.add("package");
        request.setGoals(goals);

        Properties sysProps = new Properties();
        if (skipTests) sysProps.setProperty("skipTests", "true");
        request.setProperties(sysProps);

        request.setProfiles(List.of(profile));
        request.setJavaHome(graalVmHome);
        request.setMavenOpts("-Dfile.encoding=UTF-8");

        Invoker invoker = new DefaultInvoker();
        invoker.setMavenHome(mavenHome);
        invoker.setWorkingDirectory(projectDir);
        invoker.setOutputHandler(System.out::println);
        invoker.setErrorHandler(System.err::println);

        System.out.println("  Directory: " + projectDir.getAbsolutePath());
        System.out.println("  Profile: " + profile);
        if (skipTests) System.out.println("  Tests: SKIPPED");
        if ("native".equals(profile)) {
            System.out.println("  Note: native image builds take 10-30 minutes and require 18GB+ heap.");
        }

        InvocationResult result = invoker.execute(request);

        if (result.getExitCode() != 0) {
            System.err.println("\nBuild FAILED for profile '" + profile + "' (exit code " + result.getExitCode() + ")");
            if (result.getExecutionException() != null) {
                result.getExecutionException().printStackTrace(System.err);
            }
            if ("native".equals(profile)) {
                System.err.println("\nTips:");
                System.err.println("  - Ensure GraalVM is installed: kompile install graalvm");
                System.err.println("  - Ensure sufficient heap: export MAVEN_OPTS='-Xmx20g'");
                System.err.println("  - Review native image config in src/main/resources/META-INF/native-image/");
            } else {
                System.err.println("\nTips:");
                System.err.println("  - Ensure the native binary was built: run without --skip-native-build");
                System.err.println("  - Check src/assembly/native-dist.xml exists in the project");
            }
            return 1;
        }

        System.out.println("  Profile '" + profile + "' succeeded.");
        return 0;
    }

    private void printPostBuildInstructions(File projectDir) {
        // Find the tarball
        File targetDir = new File(projectDir, "target");
        File tarball = null;
        if (targetDir.exists()) {
            try (Stream<Path> files = Files.list(targetDir.toPath())) {
                tarball = files
                        .filter(p -> !Files.isDirectory(p))
                        .filter(p -> {
                            String name = p.getFileName().toString();
                            return name.endsWith("-native-dist.tar.gz");
                        })
                        .map(Path::toFile)
                        .findFirst()
                        .orElse(null);
            } catch (IOException e) {
                // ignore — just skip tarball path in output
            }
        }

        System.out.println("\nDistribution tarball build successful!");
        if (tarball != null && tarball.exists()) {
            System.out.println("  Tarball: " + tarball.getAbsolutePath());
            System.out.println("  Size: " + formatBytes(tarball.length()));
        } else {
            System.out.println("  Tarball: " + projectDir.getAbsolutePath() + "/target/*-native-dist.tar.gz");
        }

        System.out.println();
        System.out.println("To deploy and run on any compatible Linux machine:");
        if (tarball != null) {
            System.out.println("  tar -xzf " + tarball.getAbsolutePath());
            String dirName = tarball.getName().replace("-native-dist.tar.gz", "");
            System.out.println("  ./" + dirName + "/" + dirName.replaceAll("-0\\..*", "-native"));
        } else {
            System.out.println("  tar -xzf target/*-native-dist.tar.gz");
            System.out.println("  ./<name>-native/<name>-native");
        }
        System.out.println();
        System.out.println("The tarball is fully self-contained (includes all .so files).");
        System.out.println("No Java, ~/.javacpp/cache/, or Maven installation needed at runtime.");
        System.out.println();
        System.out.println("For developer in-place builds (no tarball):");
        System.out.println("  kompile build native-dev --project-dir=" + projectDir.getAbsolutePath());
    }

    private File findNativeBinary(File projectDir) {
        File targetDir = new File(projectDir, "target");
        if (!targetDir.exists()) return null;

        try (Stream<Path> files = Files.list(targetDir.toPath())) {
            return files
                    .filter(p -> !Files.isDirectory(p))
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith("-native") && !name.contains(".");
                    })
                    .map(Path::toFile)
                    .filter(File::canExecute)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private File resolveGraalVmHome() {
        // 1. Explicit flag
        if (graalvmHome != null && graalvmHome.exists()) return graalvmHome;

        // 2. Kompile-managed install (~/.kompile/graalvm)
        File kompileGraalVm = new File(System.getProperty("user.home"), ".kompile/graalvm");
        if (kompileGraalVm.exists()) return kompileGraalVm;

        // 3. sdkman candidates — look for any GraalVM entry
        File sdkmanCandidates = new File(System.getProperty("user.home"), ".sdkman/candidates/java");
        if (sdkmanCandidates.isDirectory()) {
            File[] entries = sdkmanCandidates.listFiles();
            if (entries != null) {
                Arrays.sort(entries, (a, b) -> b.getName().compareTo(a.getName()));
                for (File entry : entries) {
                    if (entry.isDirectory() && entry.getName().contains("graal")) {
                        File nativeImage = new File(entry, "bin/native-image");
                        if (nativeImage.exists()) {
                            return entry;
                        }
                    }
                }
            }
        }

        // 4. $JAVA_HOME if it contains native-image
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null) {
            File javaHomeDir = new File(javaHome);
            if (new File(javaHomeDir, "bin/native-image").exists()) {
                return javaHomeDir;
            }
        }

        return null;
    }

    private File resolveMavenHome() {
        if (mavenHome != null && mavenHome.exists()) return mavenHome;
        return EnvironmentUtils.defaultMavenHome();
    }

    private static String formatBytes(long bytes) {
        return ByteFormatUtils.formatBytes(bytes);
    }
}
