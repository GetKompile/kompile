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

import ai.kompile.cli.main.Info;
import ai.kompile.cli.main.util.EnvironmentUtils;
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
 * Developer-workflow native image build.
 *
 * <p>Builds a GraalVM native image for a generated kompile project using the
 * {@code native} Maven profile ({@code mvn package -Pnative -DskipTests}).
 * The resulting binary is placed at {@code <project-dir>/target/<name>-native}.
 *
 * <p>The {@code NativeLibraryResolver} in the application finds the required
 * {@code .so} files from {@code ~/.javacpp/cache/} (populated automatically by
 * prior JVM-mode runs) when the native binary is executed.  No tarball is
 * produced; this is a fast in-place build suitable for local development.
 *
 * <p>Usage:
 * <pre>
 *   # Build using project directory
 *   kompile build native-dev --project-dir=/path/to/my-rag-app
 *
 *   # Build and immediately start the binary
 *   kompile build native-dev --project-dir=/path/to/my-rag-app --run
 *
 *   # Use a specific GraalVM installation
 *   kompile build native-dev --project-dir=/path/to/my-rag-app \
 *       --graalvm-home=/home/user/.sdkman/candidates/java/21.0.10-graal
 * </pre>
 *
 * <p>For production distribution (tarball with bundled {@code lib/} directory),
 * use {@code kompile build native-dist} instead.
 */
@Command(name = "native-dev",
        mixinStandardHelpOptions = true,
        description = "Build a GraalVM native image for developer use (in-place build).\n\n" +
                "Runs 'mvn package -Pnative -DskipTests' in the project directory.\n" +
                "The native binary is placed at <project-dir>/target/<name>-native.\n\n" +
                "Native libraries (.so files) are resolved from ~/.javacpp/cache/ at runtime,\n" +
                "which is populated automatically during prior JVM-mode runs.\n\n" +
                "For production distribution with bundled .so files, use:\n" +
                "  kompile build native-dist --project-dir=<dir>\n\n" +
                "Examples:\n" +
                "  kompile build native-dev --project-dir=./my-rag-app\n" +
                "  kompile build native-dev --project-dir=./my-rag-app --run\n" +
                "  kompile build native-dev --project-dir=./my-rag-app --graalvm-home=~/.sdkman/candidates/java/21.0.10-graal\n")
public class BuildNativeDevCommand implements Callable<Integer> {

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

    @Option(names = {"--run"},
            description = "Start the native binary immediately after a successful build.",
            defaultValue = "false")
    private boolean runAfterBuild;

    @Option(names = {"--run-args"},
            description = "Extra arguments to pass to the native binary when --run is set (comma-separated).",
            split = ",",
            arity = "0..*")
    private List<String> runArgs = new ArrayList<>();

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

        // Warn if JavaCPP cache is empty — developer may need a JVM run first
        warnIfJavaCppCacheEmpty();

        int buildResult = invokeMavenNativeBuild(resolvedProjectDir, pomFile, effectiveGraalVm, effectiveMaven, "native");
        if (buildResult != 0) {
            return buildResult;
        }

        File nativeBinary = findNativeBinary(resolvedProjectDir);
        printPostBuildInstructions(resolvedProjectDir, nativeBinary, false);

        if (runAfterBuild && nativeBinary != null) {
            return runBinary(nativeBinary);
        }
        return 0;
    }

    // ========== Internal helpers ==========

    protected int invokeMavenNativeBuild(File projectDir, File pomFile,
                                          File graalVmHome, File mavenHome,
                                          String mavenProfile) throws MavenInvocationException {
        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(pomFile);

        List<String> goals = new ArrayList<>();
        if (javacppPlatform != null && !javacppPlatform.isEmpty()) {
            goals.add("-Djavacpp.platform=" + javacppPlatform);
            if (javacppExtension != null && !javacppExtension.isEmpty()) {
                goals.add("-Djavacpp.platform.extension=" + javacppExtension);
            }
        }
        goals.add("clean");
        goals.add("package");
        request.setGoals(goals);

        Properties sysProps = new Properties();
        if (skipTests) sysProps.setProperty("skipTests", "true");
        request.setProperties(sysProps);

        request.setProfiles(List.of(mavenProfile));
        request.setJavaHome(graalVmHome);
        request.setMavenOpts("-Dfile.encoding=UTF-8");

        Invoker invoker = new DefaultInvoker();
        invoker.setMavenHome(mavenHome);
        invoker.setWorkingDirectory(projectDir);
        invoker.setOutputHandler(System.out::println);
        invoker.setErrorHandler(System.err::println);

        System.out.println("\nStarting native image build (profile: " + mavenProfile + ")...");
        System.out.println("  Directory: " + projectDir.getAbsolutePath());
        System.out.println("  Profile: " + mavenProfile);
        if (skipTests) System.out.println("  Tests: SKIPPED");
        System.out.println("  Note: native image builds take 10-30 minutes and require 18GB+ heap.");

        InvocationResult result = invoker.execute(request);

        if (result.getExitCode() != 0) {
            System.err.println("\nNative image build FAILED (exit code " + result.getExitCode() + ")");
            if (result.getExecutionException() != null) {
                result.getExecutionException().printStackTrace(System.err);
            }
            System.err.println("\nTips:");
            System.err.println("  - Ensure GraalVM is installed: kompile install graalvm");
            System.err.println("  - Ensure sufficient heap: export MAVEN_OPTS='-Xmx20g'");
            System.err.println("  - Review native image config in src/main/resources/META-INF/native-image/");
            return 1;
        }

        System.out.println("\nNative image build successful!");
        return 0;
    }

    protected void printPostBuildInstructions(File projectDir, File nativeBinary, boolean isDistBuild) {
        if (nativeBinary != null && nativeBinary.exists()) {
            System.out.println("  Native binary: " + nativeBinary.getAbsolutePath());
        }
        System.out.println();
        System.out.println("To run the native binary:");

        if (nativeBinary != null) {
            System.out.println("  " + nativeBinary.getAbsolutePath());
        } else {
            System.out.println("  <project-dir>/target/<name>-native");
        }
        System.out.println();

        if (!isDistBuild) {
            System.out.println("Native library resolution:");
            System.out.println("  The binary finds .so files from ~/.javacpp/cache/ (populated by prior JVM-mode runs).");
            System.out.println("  If .so files are missing, run once in JVM mode first:");
            System.out.println("    cd " + projectDir.getAbsolutePath());
            System.out.println("    mvn package -DskipTests && java -jar target/*.jar --server.port=8080");
            System.out.println("  Or set KOMPILE_NATIVE_LIB_DIR=/path/to/lib/");
            System.out.println();
            System.out.println("For production distribution (self-contained tarball with bundled .so files):");
            System.out.println("  kompile build native-dist --project-dir=" + projectDir.getAbsolutePath());
            System.out.println("  Or: cd " + projectDir.getAbsolutePath() + " && mvn package -Pnative-dist -DskipTests");
        }
    }

    protected int runBinary(File binary) throws IOException, InterruptedException {
        System.out.println("\nStarting: " + binary.getAbsolutePath());
        List<String> command = new ArrayList<>();
        command.add(binary.getAbsolutePath());
        if (runArgs != null) {
            command.addAll(runArgs);
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(binary.getParentFile().getParentFile()); // project root
        pb.inheritIO();
        Process process = pb.start();
        if (!process.waitFor(3600, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return 1;
        }
        return process.exitValue();
    }

    protected File findNativeBinary(File projectDir) {
        File targetDir = new File(projectDir, "target");
        if (!targetDir.exists()) return null;

        try (Stream<Path> files = Files.list(targetDir.toPath())) {
            return files
                    .filter(p -> !Files.isDirectory(p))
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        // Match <name>-native but not .jar, .xml, etc.
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

    private void warnIfJavaCppCacheEmpty() {
        String userHome = System.getProperty("user.home");
        if (userHome == null) return;
        Path cache = Path.of(userHome, ".javacpp", "cache");
        if (!Files.isDirectory(cache)) {
            System.out.println("\nWARNING: ~/.javacpp/cache/ does not exist.");
            System.out.println("  Native .so libraries will not be found at runtime unless you:");
            System.out.println("  1. Run the project in JVM mode first to populate the cache:");
            System.out.println("     cd " + projectDir.getAbsolutePath());
            System.out.println("     mvn package -DskipTests && java -jar target/*.jar");
            System.out.println("  2. Or set KOMPILE_NATIVE_LIB_DIR=/path/to/lib/ before running the native binary.");
            System.out.println("  3. Or use 'kompile build native-dist' to produce a self-contained tarball.");
            System.out.println();
        }
    }

    protected File resolveGraalVmHome() {
        // 1. Explicit flag
        if (graalvmHome != null && graalvmHome.exists()) return graalvmHome;

        // 2. Kompile-managed install (~/.kompile/graalvm)
        File kompileGraalVm = Info.graalvmDirectory();
        if (kompileGraalVm != null && kompileGraalVm.exists()) return kompileGraalVm;

        // 3. sdkman candidates — look for any GraalVM 21+ entry
        File sdkmanCandidates = new File(System.getProperty("user.home"), ".sdkman/candidates/java");
        if (sdkmanCandidates.isDirectory()) {
            File[] entries = sdkmanCandidates.listFiles();
            if (entries != null) {
                // Prefer graal entries, sorted descending (newest first)
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

    protected File resolveMavenHome() {
        if (mavenHome != null && mavenHome.exists()) return mavenHome;
        return EnvironmentUtils.defaultMavenHome();
    }
}
