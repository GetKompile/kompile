/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.cloud;

import ai.kompile.cli.common.util.GitRunner;
import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Clone a hosted kompile project from a kompile-saas instance.
 *
 * <p>Fetches the project's git URL from the saas API, performs an authenticated {@code git clone}
 * (repo skeleton, source, {@code kompile.project.json} and git-xet pointer files), then materializes
 * the large git-xet-tracked data (models / indices / artifacts) from the instance's Xet CAS.
 *
 * <p>This is the turnkey counterpart to a raw {@code git clone}: it carries the user's saas
 * credentials through to both the git transport and the Xet content service, so private repos and
 * large data "just work".
 *
 * <pre>kompile cloud clone &lt;namespace&gt;/&lt;slug&gt; [targetDir]</pre>
 */
@Command(name = "clone", mixinStandardHelpOptions = true,
        description = "Clone a hosted kompile project (with its git-xet data) from a kompile-saas instance.")
public class CloneCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Project to clone, as <namespace>/<slug> (e.g. alice/my-rag).")
    String project;

    @Parameters(index = "1", arity = "0..1", description = "Target directory (defaults to the slug).")
    File targetDir;

    @Option(names = "--no-data", description = "Clone the repository only; do not materialize git-xet data.")
    boolean noData;

    @Override
    public Integer call() throws Exception {
        String[] parts = project.split("/");
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            System.err.println("Project must be in the form <namespace>/<slug>, e.g. alice/my-rag");
            return 2;
        }
        String namespace = parts[0];
        String slug = parts[1];

        SaasClient client = CloudCommand.requireAuth();

        JsonNode info;
        try {
            info = client.get("/api/projects/" + namespace + "/" + slug);
        } catch (Exception e) {
            System.err.println("Could not fetch project " + project + ": " + e.getMessage());
            return 1;
        }
        if (info == null || info.get("cloneUrl") == null) {
            System.err.println("Project " + project + " not found or not accessible.");
            return 1;
        }
        String cloneUrl = info.get("cloneUrl").asText();
        String defaultBranch = info.has("defaultBranch") ? info.get("defaultBranch").asText("main") : "main";
        String token = client.getToken();

        File dest = targetDir != null ? targetDir : new File(slug);
        System.out.println("Cloning " + project + " from " + cloneUrl + " -> " + dest.getPath());

        // Authenticated clone: pass the bearer token as an HTTP extra header so both public and
        // private repositories work without embedding credentials in the remote URL.
        Path cwd = Path.of(".");
        String authHeader = "http.extraHeader=Authorization: Bearer " + token;
        int rc = GitRunner.runInherited(cwd,
                "-c", authHeader, "clone", "--branch", defaultBranch, cloneUrl, dest.getPath());
        if (rc != 0) {
            // Retry without an explicit branch (e.g. freshly initialized / empty repository).
            rc = GitRunner.runInherited(cwd, "-c", authHeader, "clone", cloneUrl, dest.getPath());
        }
        if (rc != 0) {
            System.err.println("git clone failed (exit " + rc + ").");
            return rc;
        }

        Path repo = dest.toPath();
        if (!noData) {
            materializeData(repo, deriveSaasBase(cloneUrl), token);
        }

        System.out.println("Cloned " + project + " into " + dest.getPath() + ".");
        System.out.println("Next:  cd " + dest.getPath() + " && kompile project open .");
        return 0;
    }

    /** Derive the saas base URL (scheme://host[:port]) from the git clone URL (.../git/ns/slug.git). */
    private static String deriveSaasBase(String cloneUrl) {
        int idx = cloneUrl.indexOf("/git/");
        if (idx > 0) {
            return cloneUrl.substring(0, idx);
        }
        try {
            java.net.URI u = java.net.URI.create(cloneUrl);
            return u.getScheme() + "://" + u.getAuthority();
        } catch (Exception e) {
            return cloneUrl;
        }
    }

    /**
     * Materialize git-xet/LFS-tracked data from the instance's Xet CAS. The HF-generation git-xet is
     * a git-LFS custom transfer agent, so a {@code git lfs pull} with {@code HF_ENDPOINT}/{@code HF_TOKEN}
     * pointed at the saas instance triggers Xet downloads. Falls back to clear guidance if the client
     * tooling is not installed.
     */
    private void materializeData(Path repo, String saasBase, String token) {
        if (GitRunner.isGitXetAvailable()) {
            GitRunner.installXetInRepo(repo);
        }
        boolean lfs = GitRunner.runAllowFailure(repo, "lfs", "version").success();
        if (lfs) {
            Map<String, String> env = new HashMap<>();
            env.put("HF_ENDPOINT", saasBase);
            env.put("HF_TOKEN", token);
            try {
                int rc = GitRunner.runInheritedWithEnv(repo, env, "lfs", "pull");
                if (rc == 0) {
                    System.out.println("Materialized git-xet data from the Xet CAS.");
                    return;
                }
            } catch (Exception e) {
                // fall through to guidance
            }
        }
        System.out.println();
        System.out.println("NOTE: large git-xet data was not materialized automatically.");
        System.out.println("  Install the client, then pull data from inside the repo:");
        System.out.println("    kompile install git-xet");
        System.out.println("    HF_ENDPOINT=" + saasBase + " HF_TOKEN=<your-token> git lfs pull");
    }
}
