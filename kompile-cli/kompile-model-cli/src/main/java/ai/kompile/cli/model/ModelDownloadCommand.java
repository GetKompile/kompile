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

import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.modelmanager.ManagedModelArtifactCatalog;
import ai.kompile.modelmanager.ManagedModelArtifactDownloader;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "download", description = "Download a pinned managed model directly; use --remote for scale-out staging.")
public class ModelDownloadCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--model-id", "-m"}, description = "Managed model identifier")
    private String modelId;

    @CommandLine.Option(names = {"--source", "-s"}, description = "Model source (e.g., huggingface, s3, http)")
    private String source;

    @CommandLine.Option(names = {"--repo", "-r"}, description = "Repository or model identifier")
    private String repo;

    @CommandLine.Option(names = "--revision", description = "Pinned source revision")
    private String revision;

    @CommandLine.Option(names = {"--output", "-o"}, description = "Direct-download destination directory")
    private Path output;

    @CommandLine.Option(names = "--force", description = "Replace existing direct-download components")
    private boolean force;

    @CommandLine.Option(names = "--dry-run", description = "Resolve and validate component destinations without writing")
    private boolean dryRun;

    @CommandLine.Option(names = "--remote", description = "Use the scale-out staging HTTP service instead of direct acquisition")
    private boolean remote;

    @CommandLine.Option(names = {"--endpoint"}, description = "Staging service URL")
    private String endpoint;

    @CommandLine.Option(names = {"--port"}, defaultValue = "8090", description = "Staging service port")
    private int port;

    @Override
    public Integer call() throws Exception {
        if (!remote) {
            return directDownload();
        }
        KompileHttpClient client = KompileHttpClient.create(endpoint, port);
        try {
            if (source == null || source.isBlank() || repo == null || repo.isBlank()) {
                throw new IllegalArgumentException("--remote requires --source and --repo");
            }
            Map<String, Object> body = Map.of("source", source, "repo", repo);
            String result = client.postString("/api/staging/download", body);
            System.out.println("Download initiated: " + result);
            return 0;
        } catch (Exception e) {
            System.err.println("Download failed: " + e.getMessage());
            return 1;
        }
    }

    private Integer directDownload() {
        try {
            if (modelId == null || modelId.isBlank()) {
                throw new IllegalArgumentException("Direct download requires --model-id");
            }
            ManagedModelArtifactCatalog.Definition definition = ManagedModelArtifactCatalog.find(modelId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No pinned managed component manifest is registered for model '" + modelId + "'"));
            if (repo != null && !repo.isBlank() && !definition.repository().equals(repo.trim())) {
                throw new IllegalArgumentException("Model '" + modelId + "' is pinned to repository "
                        + definition.repository());
            }
            if (revision != null && !revision.isBlank()
                    && !definition.revision().equals(revision.trim())) {
                throw new IllegalArgumentException("Model '" + modelId + "' is pinned to revision "
                        + definition.revision());
            }
            Path destination = output == null
                    ? Path.of("data/models").resolve(modelId)
                    : output;
            ManagedModelArtifactDownloader.Acquisition acquired =
                    new ManagedModelArtifactDownloader().acquire(
                            definition, destination.toAbsolutePath().normalize(), force, dryRun);
            System.out.println((dryRun ? "Direct download preview" : "Direct download complete")
                    + ": modelId=" + modelId
                    + ", repository=" + definition.repository()
                    + ", revision=" + definition.revision()
                    + ", modelPath=" + acquired.primaryModel()
                    + ", tokenizerPath=" + acquired.tokenizer()
                    + ", downloaded=" + acquired.downloaded());
            return 0;
        } catch (Exception e) {
            System.err.println("Direct download failed: " + e.getMessage());
            return 1;
        }
    }
}
