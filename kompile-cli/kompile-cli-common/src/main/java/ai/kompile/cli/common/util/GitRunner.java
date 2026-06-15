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

import ai.kompile.cli.common.KompileHome;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/**
 * Shared git subprocess runner used across all kompile modules.
 * Augments PATH with {@code ~/.kompile/bin/} so that managed tool binaries
 * (like git-xet) are discoverable by git even when not on the system PATH.
 */
public final class GitRunner {

    private GitRunner() {}

    /**
     * Result of a git subprocess invocation.
     */
    public record Result(int exitCode, String output) {
        public boolean success() { return exitCode == 0; }
    }

    /**
     * Run a git command in the given working directory.
     * PATH is augmented with {@code ~/.kompile/bin/} so managed binaries are found.
     *
     * @param workingDir directory to run the command in
     * @param args       git arguments (e.g., "status", "--porcelain")
     * @return result with exit code and captured stdout+stderr
     */
    public static Result run(Path workingDir, String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        return exec(workingDir, cmd);
    }

    /**
     * Run a git command, allowing failure (returns exit code 127 on IOException).
     */
    public static Result runAllowFailure(Path workingDir, String... args) {
        try {
            return run(workingDir, args);
        } catch (RuntimeException e) {
            return new Result(127, e.getMessage());
        }
    }

    /**
     * Run an arbitrary command with kompile-managed PATH augmentation.
     */
    public static Result exec(Path workingDir, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(workingDir != null ? workingDir.toFile() : null)
                    .redirectErrorStream(true);
            augmentPath(pb);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            return new Result(exitCode, output.trim());
        } catch (IOException e) {
            return new Result(127, "Failed to execute: " + String.join(" ", cmd) + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(130, "Interrupted");
        }
    }

    /**
     * Run a git command with inherited IO (output goes directly to console).
     */
    public static int runInherited(Path workingDir, String... args) throws IOException, InterruptedException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(workingDir != null ? workingDir.toFile() : null)
                .inheritIO();
        augmentPath(pb);
        return pb.start().waitFor();
    }

    /**
     * Check whether git-xet is installed and available.
     * Probes both the system PATH and ~/.kompile/bin/.
     */
    public static boolean isGitXetAvailable() {
        Result result = runAllowFailure(Path.of("."), "xet", "--version");
        return result.success();
    }

    /**
     * Install git-xet filters in a repository by running {@code git xet install}.
     *
     * @param repoDir the git repository directory
     * @return true if install succeeded
     */
    public static boolean installXetInRepo(Path repoDir) {
        Result result = runAllowFailure(repoDir, "xet", "install");
        return result.success();
    }

    /**
     * Returns the path to the git-xet binary in the managed bin directory,
     * or null if it doesn't exist there.
     */
    public static File managedXetBinary() {
        File bin = KompileHome.binDirectory();
        File xet = new File(bin, "git-xet");
        if (xet.isFile() && xet.canExecute()) return xet;
        // Windows variant
        File xetExe = new File(bin, "git-xet.exe");
        if (xetExe.isFile() && xetExe.canExecute()) return xetExe;
        return null;
    }

    /**
     * Augment the PATH environment variable in a ProcessBuilder to include
     * {@code ~/.kompile/bin/}. This ensures that managed tool binaries
     * (like git-xet) are discoverable even when not on the system PATH.
     */
    public static void augmentPath(ProcessBuilder pb) {
        Map<String, String> env = pb.environment();
        String binDir = KompileHome.binDirectory().getAbsolutePath();
        String pathKey = System.getProperty("os.name", "").toLowerCase().contains("windows") ? "Path" : "PATH";
        String currentPath = env.getOrDefault(pathKey, System.getenv(pathKey));
        if (currentPath == null || currentPath.isBlank()) {
            env.put(pathKey, binDir);
        } else if (!currentPath.contains(binDir)) {
            env.put(pathKey, binDir + File.pathSeparator + currentPath);
        }
    }
}
