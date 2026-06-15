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

package ai.kompile.cli.model;

import picocli.CommandLine;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Clone a model repository via git (with optional git-xet support).
 * Supports HuggingFace repos, GitHub repos, and any git URL.
 * <p>
 * Examples:
 * <pre>
 *   kompile-model clone meta-llama/Llama-3-8B
 *   kompile-model clone --xet meta-llama/Llama-3-8B --target ./models/llama3
 *   kompile-model clone https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2
 *   kompile-model clone --branch main --token hf_... meta-llama/Llama-3-8B
 * </pre>
 */
@CommandLine.Command(name = "clone", description = "Clone a model repository via git/git-xet.")
public class ModelCloneCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "Model repo (e.g., 'org/model' for HuggingFace, or a full git URL).")
    private String repo;

    @CommandLine.Option(names = {"--target", "-t"}, description = "Target directory. Defaults to ~/.kompile/models/<model-name>.")
    private File target;

    @CommandLine.Option(names = {"--branch", "-b"}, description = "Branch or revision to clone.")
    private String branch;

    @CommandLine.Option(names = {"--xet"}, description = "Use git-xet for efficient large file transfer (recommended for large models).")
    private boolean useXet;

    @CommandLine.Option(names = {"--token"}, description = "Authentication token (e.g., HuggingFace token).")
    private String token;

    @CommandLine.Option(names = {"--depth"}, description = "Shallow clone depth. Use 1 for fastest clone.", defaultValue = "1")
    private int depth;

    @Override
    public Integer call() throws Exception {
        String cloneUrl = resolveCloneUrl(repo);
        Path targetDir = resolveTargetDir(repo, target);

        if (Files.isDirectory(targetDir) && Files.isDirectory(targetDir.resolve(".git"))) {
            System.out.println("Repository already cloned at: " + targetDir);
            System.out.println("  To update: cd " + targetDir + " && git pull");
            return 0;
        }

        // Install git-xet if requested and available
        if (useXet) {
            if (!ai.kompile.cli.common.util.GitRunner.isGitXetAvailable()) {
                System.err.println("Warning: git-xet is not installed. Falling back to standard git clone.");
                System.err.println("  Install with: kompile install git-xet");
                useXet = false;
            }
        }

        // Build clone args
        List<String> args = new ArrayList<>();
        args.add("clone");
        if (depth > 0) { args.add("--depth"); args.add(String.valueOf(depth)); }
        if (branch != null && !branch.isBlank()) { args.add("--branch"); args.add(branch); }

        // For HuggingFace with token, embed in URL
        String effectiveUrl = cloneUrl;
        if (token != null && !token.isBlank() && cloneUrl.contains("huggingface.co")) {
            effectiveUrl = cloneUrl.replace("https://", "https://hf_user:" + token + "@");
        }
        args.add(effectiveUrl);
        args.add(targetDir.toString());

        System.out.println("Cloning " + repo + " → " + targetDir);
        if (useXet) System.out.println("  Using git-xet for large file transfer");

        Files.createDirectories(targetDir.getParent());

        int exitCode = ai.kompile.cli.common.util.GitRunner.runInherited(
                targetDir.getParent(), args.toArray(new String[0]));

        if (exitCode != 0) {
            System.err.println("Clone failed with exit code " + exitCode);
            return exitCode;
        }

        // Install git-xet in the cloned repo
        if (useXet) {
            System.out.println("Installing git-xet in cloned repository...");
            if (!ai.kompile.cli.common.util.GitRunner.installXetInRepo(targetDir)) {
                System.err.println("Warning: git xet install failed (non-fatal)");
            }
        }

        System.out.println("Model cloned successfully: " + targetDir);
        return 0;
    }

    /**
     * Resolve a repo identifier to a full clone URL.
     * - "org/model" → "https://huggingface.co/org/model"
     * - Already a URL → use as-is
     */
    private static String resolveCloneUrl(String repo) {
        if (repo.startsWith("http://") || repo.startsWith("https://") || repo.startsWith("git@")) {
            return repo;
        }
        // Treat as HuggingFace repo ID (org/model)
        return "https://huggingface.co/" + repo;
    }

    /**
     * Resolve the target directory for the clone.
     */
    private static Path resolveTargetDir(String repo, File explicitTarget) {
        if (explicitTarget != null) {
            return explicitTarget.toPath().toAbsolutePath().normalize();
        }
        // Extract model name from repo
        String name = repo;
        if (name.contains("/")) {
            name = name.substring(name.lastIndexOf('/') + 1);
        }
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        return Path.of(System.getProperty("user.home"), ".kompile", "models", name);
    }

}
