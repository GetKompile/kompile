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

package ai.kompile.cli.main.build;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * CLI command that delegates to the bundled build-scripts/ for
 * end-to-end platform builds: DL4J backend + kompile native images.
 *
 * <p>Assumes the kompile repo is the working directory and
 * {@code ../deeplearning4j} is the DL4J checkout.
 *
 * <p>Scripts are bundled as classpath resources and extracted to
 * {@code build-scripts/} in the working directory if not already present.
 */
@Command(name = "platform",
        mixinStandardHelpOptions = true,
        description = "Build kompile for a DL4J platform target.\n\n" +
                "Orchestrates: DL4J backend -> kompile Java -> native images -> distribution.\n\n" +
                "Assumes ../deeplearning4j is the DL4J checkout.\n\n" +
                "Examples:\n" +
                "  kompile build platform linux-x86_64-cuda-12.9\n" +
                "  kompile build platform linux-x86_64 --native-targets cli,app,staging\n" +
                "  kompile build platform --list\n")
public class BuildPlatformCommand implements Callable<Integer> {

    private static final String[] BUNDLED_SCRIPTS = {
            "build-common.sh",
            "build-kompile-platform.sh",
            "build-kompile-all.sh",
            "build-kompile-cpu.sh",
            "build-kompile-cpu-onednn.sh",
            "build-kompile-cpu-arm.sh",
            "build-kompile-cpu-full.sh",
            "build-kompile-cuda.sh",
            "build-kompile-cuda-12.6.sh",
            "build-kompile-cuda-13.1.sh",
            "build-kompile-cuda-full.sh",
            "build-kompile-rocm.sh",
            "build-kompile-macos.sh",
            "build-kompile-windows.sh",
            "build-kompile-windows-cuda.sh",
            "build-kompile-native-only.sh",
            "build-kompile-docker.sh"
    };

    @Parameters(index = "0", arity = "0..1",
            description = "DL4J platform target (e.g., linux-x86_64, linux-x86_64-cuda-12.9)")
    private String platform;

    @Option(names = {"--native-targets"},
            description = "Comma-separated: cli, app, staging. Default: cli")
    private String nativeTargets;

    @Option(names = {"--variant"},
            description = "Distribution variant: cli-only, hosted, cpu-intel, cpu-arm, cuda, amd-zluda")
    private String variant;

    @Option(names = {"--dl4j-root"},
            description = "Path to deeplearning4j checkout. Default: ../deeplearning4j (cloned if missing)")
    private File dl4jRoot;

    @Option(names = {"--dl4j-branch"},
            description = "DL4J branch to clone/checkout. Default: master")
    private String dl4jBranch;

    @Option(names = {"--kompile-branch"},
            description = "Kompile branch to checkout. Default: main")
    private String kompileBranch;

    @Option(names = {"--graalvm-home"},
            description = "Path to GraalVM installation. Default: auto-detect")
    private File graalvmHome;

    @Option(names = {"--output-dir", "-o"},
            description = "Output directory. Default: ./dist")
    private File outputDir;

    @Option(names = {"--skip-dl4j"}, description = "Skip DL4J backend build")
    private boolean skipDl4j;

    @Option(names = {"--skip-java"}, description = "Skip kompile Java build")
    private boolean skipJava;

    @Option(names = {"--skip-native"}, description = "Skip native image builds")
    private boolean skipNative;

    @Option(names = {"--skip-dist"}, description = "Skip distribution assembly")
    private boolean skipDist;

    @Option(names = {"--setup"}, description = "Auto-install build dependencies")
    private boolean setup;

    @Option(names = {"--list", "-l"}, description = "List supported platforms")
    private boolean listPlatforms;

    @Option(names = {"--all-platforms"}, description = "Build all supported platforms")
    private boolean allPlatforms;

    @Option(names = {"--platforms"},
            description = "Platform group: cpu, cuda, rocm, linux, macos, windows, all")
    private String platforms;

    @Override
    public Integer call() throws Exception {
        File scriptsDir = ensureBuildScripts();

        // Pick script
        String scriptName;
        List<String> args = new ArrayList<>();

        if (allPlatforms || platforms != null) {
            scriptName = "build-kompile-all.sh";
            if (platforms != null) {
                args.add("--platforms");
                args.add(platforms);
            }
        } else {
            scriptName = "build-kompile-platform.sh";
            if (listPlatforms) {
                args.add("--list");
            } else if (platform == null) {
                System.err.println("Usage: kompile build platform <platform-target>");
                System.err.println("Run with --list to see supported platforms.");
                return 1;
            } else {
                args.add(platform);
            }
        }

        // Forward flags
        if (nativeTargets != null)  { args.add("--native-targets"); args.add(nativeTargets); }
        if (variant != null)        { args.add("--variant"); args.add(variant); }
        if (dl4jBranch != null)     { args.add("--dl4j-branch"); args.add(dl4jBranch); }
        if (kompileBranch != null)  { args.add("--kompile-branch"); args.add(kompileBranch); }
        if (skipDl4j)  args.add("--skip-dl4j");
        if (skipJava)  args.add("--skip-java");
        if (skipNative) args.add("--skip-native");
        if (skipDist)  args.add("--skip-dist");
        if (setup)     args.add("--setup");

        List<String> command = new ArrayList<>();
        command.add("bash");
        command.add(new File(scriptsDir, scriptName).getAbsolutePath());
        command.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(System.getProperty("user.dir")));
        pb.redirectErrorStream(true);
        if (dl4jRoot != null)    pb.environment().put("DL4J_PROJECT_ROOT", dl4jRoot.getAbsolutePath());
        if (graalvmHome != null) pb.environment().put("GRAALVM_HOME", graalvmHome.getAbsolutePath());
        if (outputDir != null)   pb.environment().put("KOMPILE_OUTPUT_DIR", outputDir.getAbsolutePath());

        Process proc = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        }

        return proc.waitFor();
    }

    /**
     * If build-scripts/ exists in CWD, use it.
     * Otherwise extract the bundled scripts from the classpath into build-scripts/.
     */
    private File ensureBuildScripts() throws IOException {
        File dir = new File("build-scripts");
        if (dir.isDirectory() && new File(dir, "build-common.sh").exists()) {
            return dir;
        }

        // Extract from classpath
        dir.mkdirs();
        for (String name : BUNDLED_SCRIPTS) {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream("build-scripts/" + name)) {
                if (is == null) continue;
                Path target = dir.toPath().resolve(name);
                Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                target.toFile().setExecutable(true);
            }
        }
        System.out.println("Extracted build scripts to: " + dir.getAbsolutePath());
        return dir;
    }
}
