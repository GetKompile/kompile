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
import ai.kompile.modelmanager.registry.ModelMetadata;
import ai.kompile.modelmanager.registry.ModelType;
import ai.kompile.staging.staging.StagingModelInfo;
import ai.kompile.staging.staging.StagingService;
import ai.kompile.staging.staging.StagingStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * CLI command for full pipeline: download, convert, validate, and promote.
 */
@Component
@Command(
    name = "pipeline",
    description = "Full pipeline: download, convert, validate, and optionally promote",
    mixinStandardHelpOptions = true
)
public class PipelineCommand implements Callable<Integer> {

    @Autowired
    private StagingService stagingService;

    @Option(names = {"-s", "--source"}, required = true,
            description = "Source type: huggingface, github, http")
    private String source;

    @Option(names = {"-r", "--repo"}, required = true,
            description = "Repository or URL")
    private String repository;

    @Option(names = {"-m", "--model-id"},
            description = "Model ID (defaults to repo name)")
    private String modelId;

    @Option(names = {"-t", "--type"}, defaultValue = "encoder",
            description = "Model type: encoder, cross_encoder, reranker")
    private String modelType;

    @Option(names = {"-f", "--format"}, defaultValue = "onnx",
            description = "Model format: onnx, tensorflow")
    private String format;

    @Option(names = {"--promote"},
            description = "Automatically promote to production after staging")
    private boolean autoPromote;

    @Option(names = {"--embedding-dim"},
            description = "Embedding dimension (for encoder models)")
    private Integer embeddingDim;

    @Option(names = {"--hidden-size"},
            description = "Hidden size (for cross-encoder models)")
    private Integer hiddenSize;

    @Option(names = {"--max-seq-length"}, defaultValue = "512",
            description = "Maximum sequence length")
    private int maxSequenceLength;

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

        System.out.println("=== Kompile Model Pipeline ===");
        System.out.println();
        System.out.println("Model ID: " + id);
        System.out.println("Source: " + source + " (" + repository + ")");
        System.out.println("Type: " + modelType);
        System.out.println("Format: " + format);
        System.out.println("Auto-promote: " + autoPromote);
        System.out.println();

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

        // Run staging pipeline
        System.out.println("Starting pipeline...");
        final String finalId = id;
        StagingModelInfo result = stagingService.stageModel(request, info -> {
            System.out.printf("\r[%3d%%] %-15s: %s    ",
                    info.getProgress(),
                    info.getStatus().getValue().toUpperCase(),
                    info.getMessage() != null ? info.getMessage() : "");
        });

        System.out.println();
        System.out.println();

        if (result.getStatus() == StagingStatus.FAILED) {
            System.err.println("Pipeline failed: " + result.getError());
            return 1;
        }

        if (result.getStatus() == StagingStatus.COMPLETED ||
            result.getStatus() == StagingStatus.READY) {
            System.out.println("Staging completed successfully!");

            if (autoPromote) {
                System.out.println();
                System.out.println("Promoting to production...");

                ModelMetadata metadata = ModelMetadata.builder()
                        .embeddingDim(embeddingDim)
                        .hiddenSize(hiddenSize)
                        .maxSequenceLength(maxSequenceLength)
                        .sourceOrigin(source)
                        .sourceRepository(repository)
                        .originalFormat(format)
                        .framework("samediff")
                        .build();

                boolean promoted = stagingService.promoteModel(finalId, metadata);

                if (promoted) {
                    System.out.println("Model promoted to production successfully!");
                    System.out.println();
                    System.out.println("The model is now available in the production registry.");
                    return 0;
                } else {
                    System.err.println("Failed to promote model to production.");
                    return 1;
                }
            } else {
                System.out.println();
                System.out.println("Model is ready for promotion.");
                System.out.println("Run: kompile-staging promote --model=" + finalId);
                return 0;
            }
        }

        System.err.println("Unexpected status: " + result.getStatus());
        return 1;
    }
}
