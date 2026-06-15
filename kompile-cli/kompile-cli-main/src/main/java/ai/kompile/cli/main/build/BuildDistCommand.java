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

import ai.kompile.cli.main.util.EnvironmentUtils;
import ai.kompile.cli.main.util.OSResolver;
import org.apache.maven.shared.invoker.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Unified distribution build command.
 *
 * <p>Builds a complete kompile distribution containing all three native binaries
 * (CLI, model-staging, RAG server) plus OS-specific shared libraries, assembled
 * into a self-contained platform-specific tarball.</p>
 *
 * <p>The CUDA version is controlled by the {@code nd4j.cuda.version} POM property.
 * It defaults to 12.9 and can be overridden with {@code --cuda-version}.</p>
 *
 * <p>Usage:
 * <pre>
 *   # Build default distribution (auto-detect platform, CUDA 12.9)
 *   kompile build dist
 *
 *   # Override CUDA version
 *   kompile build dist --cuda-version=12.6
 *
 *   # CPU-only distribution (no CUDA)
 *   kompile build dist --backend=cpu
 *
 *   # Skip specific components
 *   kompile build dist --skip-cli --skip-staging
 * </pre>
 */
@Command(name = "dist",
        mixinStandardHelpOptions = true,
        description = "Build a complete kompile distribution.\n\n" +
                "Builds all native images (CLI, model-staging, RAG server) and assembles\n" +
                "them into a self-contained platform-specific tarball.\n\n" +
                "CUDA version is driven by the nd4j.cuda.version POM property.\n\n" +
                "Examples:\n" +
                "  kompile build dist\n" +
                "  kompile build dist --cuda-version=12.6\n" +
                "  kompile build dist --backend=cpu\n" +
                "  kompile build dist --skip-cli --skip-staging\n")
public class BuildDistCommand implements Callable<Integer> {

    @Option(names = {"--output-dir", "-o"},
            description = "Output directory for the distribution tarball. Default: ./kompile-dist-output",
            defaultValue = "./kompile-dist-output")
    private File outputDir;

    @Option(names = {"--cuda-version"},
            description = "CUDA version for nd4j backend (sets nd4j.cuda.version POM property). Default: 12.9",
            defaultValue = "12.9")
    private String cudaVersion;

    @Option(names = {"--platform"},
            description = "JavaCPP platform (e.g., linux-x86_64, macosx-arm64). Default: auto-detect")
    private String platform;

    @Option(names = {"--backend"},
            description = "Backend type: cuda (default) or cpu",
            defaultValue = "cuda")
    private String backend;

    @Option(names = {"--skip-cli"},
            description = "Skip building kompile-cli native image",
            defaultValue = "false")
    private boolean skipCli;

    @Option(names = {"--skip-staging"},
            description = "Skip building kompile-model-staging native image",
            defaultValue = "false")
    private boolean skipStaging;

    @Option(names = {"--skip-app"},
            description = "Skip building RAG app (kompile-server) native image",
            defaultValue = "false")
    private boolean skipApp;

    @Option(names = {"--graalvm-home"},
            description = "Path to GraalVM installation. Default: auto-detect (sdkman, ~/.kompile/graalvm, JAVA_HOME)")
    private File graalvmHome;

    @Option(names = {"--maven-home"},
            description = "Path to Maven installation. Default: auto-detect")
    private File mavenHome;

    @Option(names = {"--skip-tests"},
            description = "Skip tests during build. Default: true",
            defaultValue = "true",
            negatable = true)
    private boolean skipTests;

    @Option(names = {"--project-dir"},
            description = "Path to the RAG app project to build. Default: kompile-rag-builds/kompile-sample/project")
    private File projectDir;

    @Override
    public Integer call() throws Exception {
        // Resolve platform
        String effectivePlatform = platform != null ? platform : OSResolver.javacppPlatform();

        // Resolve backend artifact
        String backendArtifact;
        String distSuffix;
        if ("cpu".equalsIgnoreCase(backend)) {
            backendArtifact = "nd4j-native";
            distSuffix = effectivePlatform + "-cpu";
        } else {
            backendArtifact = "nd4j-cuda-${nd4j.cuda.version}";
            distSuffix = effectivePlatform + "-cuda" + cudaVersion;
        }

        System.out.println("========================================");
        System.out.println(" Kompile Distribution Build");
        System.out.println("========================================");
        System.out.println("  Platform:      " + effectivePlatform);
        System.out.println("  Backend:       " + ("cpu".equalsIgnoreCase(backend) ? "nd4j-native (CPU)" : "nd4j-cuda-" + cudaVersion + " (CUDA)"));
        System.out.println("  CUDA version:  " + ("cpu".equalsIgnoreCase(backend) ? "N/A" : cudaVersion + " (nd4j.cuda.version)"));
        System.out.println("  Build CLI:     " + (!skipCli));
        System.out.println("  Build staging: " + (!skipStaging));
        System.out.println("  Build app:     " + (!skipApp));
        System.out.println("========================================");
        System.out.println();

        File effectiveGraalVm = resolveGraalVmHome();
        if (effectiveGraalVm == null) {
            System.err.println("GraalVM not found. Install with: kompile install graalvm");
            System.err.println("Or use sdkman: sdk install java 21.0.10-graal");
            System.err.println("Or specify: --graalvm-home=/path/to/graalvm");
            return 1;
        }
        System.out.println("Using GraalVM: " + effectiveGraalVm.getAbsolutePath());

        File effectiveMaven = resolveMavenHome();
        if (effectiveMaven == null) {
            System.err.println("Maven not found. Use --maven-home to specify.");
            return 1;
        }
        System.out.println("Using Maven: " + effectiveMaven.getAbsolutePath());

        // Resolve the kompile repo root (parent of kompile-cli/)
        File repoRoot = resolveRepoRoot();
        if (repoRoot == null) {
            System.err.println("Could not determine kompile repository root.");
            System.err.println("Run this command from within the kompile source tree.");
            return 1;
        }
        System.out.println("Repo root: " + repoRoot.getAbsolutePath());
        System.out.println();

        // Resolve output directory
        File resolvedOutputDir = outputDir.getAbsoluteFile().getCanonicalFile();
        if (!resolvedOutputDir.exists() && !resolvedOutputDir.mkdirs()) {
            System.err.println("Could not create output directory: " + resolvedOutputDir);
            return 1;
        }

        int stepNum = 0;
        int totalSteps = (skipCli ? 0 : 1) + (skipStaging ? 0 : 1) + (skipApp ? 0 : 1) + 1; // +1 for assembly

        // Step: Build kompile-cli native image
        if (!skipCli) {
            stepNum++;
            System.out.println("[Step " + stepNum + "/" + totalSteps + "] Building kompile-cli native image...");
            int result = buildNativeImage(
                    new File(repoRoot, "kompile-cli"),
                    effectiveGraalVm, effectiveMaven,
                    effectivePlatform, null, // CLI doesn't need CUDA
                    "native");
            if (result != 0) {
                System.err.println("kompile-cli native build FAILED");
                return result;
            }
        }

        // Step: Build kompile-model-staging native image
        if (!skipStaging) {
            stepNum++;
            System.out.println("[Step " + stepNum + "/" + totalSteps + "] Building kompile-model-staging native image...");
            int result = buildNativeImage(
                    new File(repoRoot, "kompile-app/kompile-model-staging"),
                    effectiveGraalVm, effectiveMaven,
                    effectivePlatform, null, // staging uses nd4j-native (CPU only)
                    "native");
            if (result != 0) {
                System.err.println("kompile-model-staging native build FAILED");
                return result;
            }
        }

        // Step: Build RAG app (kompile-server) native image
        if (!skipApp) {
            stepNum++;
            File appProjectDir = resolveAppProjectDir(repoRoot);
            System.out.println("[Step " + stepNum + "/" + totalSteps + "] Building RAG app native image from: " + appProjectDir);
            Map<String, String> extraProps = new LinkedHashMap<>();
            if (!"cpu".equalsIgnoreCase(backend)) {
                extraProps.put("nd4j.cuda.version", cudaVersion);
            }
            int result = buildNativeImage(
                    appProjectDir,
                    effectiveGraalVm, effectiveMaven,
                    effectivePlatform, extraProps,
                    "native");
            if (result != 0) {
                System.err.println("RAG app native build FAILED");
                return result;
            }
        }

        // Step: Assemble distribution
        stepNum++;
        System.out.println("[Step " + stepNum + "/" + totalSteps + "] Assembling distribution...");
        int assemblyResult = assembleDistribution(repoRoot, resolvedOutputDir, effectivePlatform, distSuffix);
        if (assemblyResult != 0) {
            return assemblyResult;
        }

        printSummary(resolvedOutputDir, distSuffix);
        return 0;
    }

    private int buildNativeImage(File moduleDir, File graalVmHome, File mavenHome,
                                  String javacppPlatform, Map<String, String> extraProps,
                                  String profile) {
        try {
            InvocationRequest request = new DefaultInvocationRequest();
            request.setPomFile(new File(moduleDir, "pom.xml"));

            List<String> goals = new ArrayList<>();
            goals.add("clean");
            goals.add("package");
            request.setGoals(goals);

            Properties sysProps = new Properties();
            if (skipTests) sysProps.setProperty("skipTests", "true");
            sysProps.setProperty("skip.ui", "true");
            if (javacppPlatform != null) {
                sysProps.setProperty("javacpp.platform", javacppPlatform);
            }
            if (extraProps != null) {
                extraProps.forEach(sysProps::setProperty);
            }
            request.setProperties(sysProps);

            request.setProfiles(List.of(profile));
            request.setJavaHome(graalVmHome);
            request.setMavenOpts("-Dfile.encoding=UTF-8");

            Invoker invoker = new DefaultInvoker();
            invoker.setMavenHome(mavenHome);
            invoker.setWorkingDirectory(moduleDir);
            invoker.setOutputHandler(System.out::println);
            invoker.setErrorHandler(System.err::println);

            System.out.println("  Directory: " + moduleDir.getAbsolutePath());
            System.out.println("  Profile: " + profile);
            System.out.println("  Platform: " + javacppPlatform);

            InvocationResult result = invoker.execute(request);
            if (result.getExitCode() != 0) {
                System.err.println("Build FAILED (exit code " + result.getExitCode() + ")");
                if (result.getExecutionException() != null) {
                    result.getExecutionException().printStackTrace(System.err);
                }
                return 1;
            }
            System.out.println("  Build succeeded.");
            return 0;
        } catch (MavenInvocationException e) {
            System.err.println("Maven invocation failed: " + e.getMessage());
            return 1;
        }
    }

    private int assembleDistribution(File repoRoot, File outputDir, String platform, String distSuffix) {
        try {
            String version = "0.1.0-SNAPSHOT";
            String distName = "kompile-" + version + "-" + distSuffix;
            File distDir = new File(outputDir, distName);
            File binDir = new File(distDir, "bin");
            File libDir = new File(distDir, "lib");
            File confDir = new File(distDir, "conf");

            // Create directory structure
            for (File dir : new File[]{binDir, libDir, confDir,
                    new File(distDir, "data/input_documents"),
                    new File(distDir, "data/shared_files"),
                    new File(distDir, "logs"),
                    new File(distDir, "models")}) {
                if (!dir.exists() && !dir.mkdirs()) {
                    System.err.println("Could not create directory: " + dir);
                    return 1;
                }
            }

            // Copy CLI native binary
            if (!skipCli) {
                copyIfExists(new File(repoRoot, "kompile-cli/target/kompile-cli"),
                        new File(binDir, "kompile"), true);
            }

            // Copy model-staging native binary + JAR fallback
            if (!skipStaging) {
                copyIfExists(new File(repoRoot, "kompile-app/kompile-model-staging/target/kompile-model-staging"),
                        new File(binDir, "kompile-model-staging"), true);
                // Also copy the exec JAR for fallback
                File stagingJar = findFileByPattern(
                        new File(repoRoot, "kompile-app/kompile-model-staging/target"),
                        "kompile-model-staging-*-exec.jar");
                if (stagingJar != null) {
                    copyIfExists(stagingJar, new File(libDir, "kompile-model-staging.jar"), false);
                }
            }

            // Copy RAG app native binary
            if (!skipApp) {
                File appProjectDir = resolveAppProjectDir(repoRoot);
                File targetDir = new File(appProjectDir, "target");

                // Find the native binary (name varies by project)
                File nativeBinary = findNativeBinary(targetDir);
                if (nativeBinary != null) {
                    copyIfExists(nativeBinary, new File(binDir, "kompile-server"), true);
                }

                // Copy GraalVM shared libs
                copySharedLibs(targetDir, binDir);

                // Copy application.properties to conf/
                copyIfExists(new File(appProjectDir, "src/main/resources/application.properties"),
                        new File(confDir, "application.properties"), false);
            }

            // Create tarball
            System.out.println("  Creating tarball: " + distName + ".tar.gz");
            ProcessBuilder pb = new ProcessBuilder("tar", "-czf",
                    distName + ".tar.gz", distName);
            pb.directory(outputDir);
            pb.redirectErrorStream(true);
            pb.inheritIO();
            Process proc = pb.start();
            int exitCode = proc.waitFor();
            if (exitCode != 0) {
                System.err.println("tar failed with exit code " + exitCode);
                return 1;
            }

            return 0;
        } catch (Exception e) {
            System.err.println("Assembly failed: " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }
    }

    private void copyIfExists(File src, File dest, boolean executable) throws IOException {
        if (src.exists()) {
            Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (executable) {
                dest.setExecutable(true);
            }
            System.out.println("  Copied: " + src.getName() + " -> " + dest.getName());
        } else {
            System.out.println("  Skipped (not found): " + src.getAbsolutePath());
        }
    }

    private void copySharedLibs(File targetDir, File binDir) throws IOException {
        if (!targetDir.isDirectory()) return;
        File[] sharedLibs = targetDir.listFiles((d, name) ->
                (name.startsWith("lib") && (name.endsWith(".so") || name.endsWith(".dylib") || name.endsWith(".dll"))));
        if (sharedLibs != null) {
            for (File lib : sharedLibs) {
                Files.copy(lib.toPath(), new File(binDir, lib.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                new File(binDir, lib.getName()).setExecutable(true);
            }
            System.out.println("  Copied " + sharedLibs.length + " shared libraries to bin/");
        }
    }

    private File findNativeBinary(File targetDir) {
        if (!targetDir.isDirectory()) return null;
        File[] files = targetDir.listFiles((d, name) ->
                name.endsWith("-native") && !name.contains(".") && new File(d, name).canExecute());
        if (files != null && files.length > 0) {
            return files[0];
        }
        // Also check for kompile-app binary name
        File kompileApp = new File(targetDir, "kompile-app");
        if (kompileApp.exists() && kompileApp.canExecute()) return kompileApp;
        return null;
    }

    private File findFileByPattern(File dir, String globPattern) {
        if (!dir.isDirectory()) return null;
        String prefix = globPattern.split("\\*")[0];
        String suffix = globPattern.contains("*") ? globPattern.split("\\*")[1] : "";
        File[] matches = dir.listFiles((d, name) -> name.startsWith(prefix) && name.endsWith(suffix));
        return (matches != null && matches.length > 0) ? matches[0] : null;
    }

    private File resolveAppProjectDir(File repoRoot) {
        if (projectDir != null && projectDir.exists()) {
            return projectDir;
        }
        return new File(repoRoot, "kompile-rag-builds/kompile-sample/project");
    }

    private File resolveRepoRoot() {
        // Walk up from CWD looking for the kompile-cli/ directory
        File current = new File(System.getProperty("user.dir"));
        for (int i = 0; i < 10; i++) {
            if (new File(current, "kompile-cli").isDirectory()
                    && new File(current, "kompile-app").isDirectory()
                    && new File(current, "pom.xml").exists()) {
                return current;
            }
            current = current.getParentFile();
            if (current == null) break;
        }
        return null;
    }

    private File resolveGraalVmHome() {
        if (graalvmHome != null && graalvmHome.exists()) return graalvmHome;
        File kompileGraalVm = new File(System.getProperty("user.home"), ".kompile/graalvm");
        if (kompileGraalVm.exists()) return kompileGraalVm;
        File sdkmanCandidates = new File(System.getProperty("user.home"), ".sdkman/candidates/java");
        if (sdkmanCandidates.isDirectory()) {
            File[] entries = sdkmanCandidates.listFiles();
            if (entries != null) {
                Arrays.sort(entries, (a, b) -> b.getName().compareTo(a.getName()));
                for (File entry : entries) {
                    if (entry.isDirectory() && entry.getName().contains("graal")) {
                        if (new File(entry, "bin/native-image").exists()) return entry;
                    }
                }
            }
        }
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && new File(javaHome, "bin/native-image").exists()) {
            return new File(javaHome);
        }
        return null;
    }

    private File resolveMavenHome() {
        if (mavenHome != null && mavenHome.exists()) return mavenHome;
        return EnvironmentUtils.defaultMavenHome();
    }

    private void printSummary(File outputDir, String distSuffix) {
        String version = "0.1.0-SNAPSHOT";
        String distName = "kompile-" + version + "-" + distSuffix;
        File tarball = new File(outputDir, distName + ".tar.gz");

        System.out.println();
        System.out.println("========================================");
        System.out.println(" Distribution build complete!");
        System.out.println("========================================");
        if (tarball.exists()) {
            System.out.println("  Tarball: " + tarball.getAbsolutePath());
            long mb = tarball.length() / (1024 * 1024);
            System.out.println("  Size: " + mb + " MB");
        }
        System.out.println();
        System.out.println("To deploy:");
        System.out.println("  tar -xzf " + tarball.getName());
        System.out.println("  cd " + distName);
        System.out.println("  bin/kompile-server --server.port=9191");
        System.out.println();
        System.out.println("Individual components:");
        System.out.println("  bin/kompile               # CLI");
        System.out.println("  bin/kompile-server         # RAG server (native)");
        System.out.println("  bin/kompile-model-staging  # Model staging service (native)");
    }
}
