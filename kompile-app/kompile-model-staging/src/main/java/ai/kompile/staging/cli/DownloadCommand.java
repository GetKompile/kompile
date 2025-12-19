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

package ai.kompile.staging.cli;

import ai.kompile.staging.download.DownloadRequest;
import ai.kompile.staging.download.DownloadResult;
import ai.kompile.staging.download.DownloadService;
import ai.kompile.staging.registry.ModelType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * CLI command for downloading models.
 */
@Component
@Command(
    name = "download",
    description = "Download a model from an external source",
    mixinStandardHelpOptions = true
)
public class DownloadCommand implements Callable<Integer> {

    @Autowired
    private List<DownloadService> downloadServices;

    @Option(names = {"-s", "--source"}, required = true,
            description = "Source type: huggingface, github, http")
    private String source;

    @Option(names = {"-r", "--repo"}, required = true,
            description = "Repository or URL (e.g., BAAI/bge-base-en-v1.5)")
    private String repository;

    @Option(names = {"-m", "--model-id"},
            description = "Model ID to use in registry (defaults to repo name)")
    private String modelId;

    @Option(names = {"-t", "--type"}, defaultValue = "encoder",
            description = "Model type: encoder, cross_encoder, reranker")
    private String modelType;

    @Option(names = {"-f", "--format"}, defaultValue = "onnx",
            description = "Model format: onnx, tensorflow, keras")
    private String format;

    @Option(names = {"-o", "--output"},
            description = "Output directory (defaults to staging)")
    private String outputDir;

    @Option(names = {"--revision"},
            description = "Git revision/tag/branch")
    private String revision;

    @Option(names = {"--token"},
            description = "Authentication token")
    private String token;

    @Override
    public Integer call() {
        // Determine model ID
        String id = modelId;
        if (id == null) {
            id = repository.contains("/")
                    ? repository.substring(repository.lastIndexOf('/') + 1)
                    : repository;
        }

        System.out.println("Downloading model: " + id);
        System.out.println("Source: " + source + " (" + repository + ")");

        // Find appropriate downloader
        DownloadService downloader = null;
        for (DownloadService ds : downloadServices) {
            if (ds.canHandle(source)) {
                downloader = ds;
                break;
            }
        }

        if (downloader == null) {
            System.err.println("No downloader available for source: " + source);
            return 1;
        }

        // Create download request
        DownloadRequest request = DownloadRequest.builder()
                .source(source)
                .repository(repository)
                .modelId(id)
                .modelType(ModelType.fromValue(modelType))
                .format(format)
                .revision(revision)
                .authToken(token)
                .build();

        // Determine output path
        Path destination;
        if (outputDir != null) {
            destination = Paths.get(outputDir);
        } else {
            destination = Paths.get(System.getProperty("user.home"), ".kompile", "staging", "pending", id);
        }

        // Download with progress
        DownloadResult result = downloader.download(request, destination, progress -> {
            System.out.print("\r" + progress.getMessage() + "   ");
        });

        System.out.println();

        if (result.isSuccess()) {
            System.out.println("Download completed successfully!");
            System.out.println("Model path: " + result.getModelPath());
            if (result.getVocabPath() != null) {
                System.out.println("Vocab path: " + result.getVocabPath());
            }
            System.out.println("Checksum: " + result.getChecksum());
            System.out.println("Total bytes: " + result.getTotalBytes());
            System.out.println("Duration: " + result.getDurationMs() + "ms");
            return 0;
        } else {
            System.err.println("Download failed: " + result.getErrorMessage());
            return 1;
        }
    }
}
