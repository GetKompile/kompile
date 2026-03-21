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

import ai.kompile.modelmanager.registry.ModelMetadata;
import ai.kompile.staging.staging.StagingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * CLI command for promoting staged models to production.
 */
@Component
@Command(
    name = "promote",
    description = "Promote a staged model to production",
    mixinStandardHelpOptions = true
)
public class PromoteCommand implements Callable<Integer> {

    @Autowired
    private StagingService stagingService;

    @Option(names = {"-m", "--model"}, required = true,
            description = "Model ID to promote")
    private String modelId;

    @Option(names = {"--embedding-dim"},
            description = "Embedding dimension (for encoder models)")
    private Integer embeddingDim;

    @Option(names = {"--hidden-size"},
            description = "Hidden size (for cross-encoder models)")
    private Integer hiddenSize;

    @Option(names = {"--num-layers"},
            description = "Number of layers")
    private Integer numLayers;

    @Option(names = {"--max-seq-length"}, defaultValue = "512",
            description = "Maximum sequence length")
    private int maxSequenceLength;

    @Option(names = {"--description"},
            description = "Model description")
    private String description;

    @Override
    public Integer call() {
        System.out.println("Promoting model: " + modelId);

        ModelMetadata metadata = ModelMetadata.builder()
                .embeddingDim(embeddingDim)
                .hiddenSize(hiddenSize)
                .numLayers(numLayers)
                .maxSequenceLength(maxSequenceLength)
                .description(description)
                .framework("samediff")
                .build();

        boolean success = stagingService.promoteModel(modelId, metadata);

        if (success) {
            System.out.println("Model promoted successfully!");
            System.out.println("Model is now available in the production registry.");
            return 0;
        } else {
            System.err.println("Failed to promote model. Check if it's in staged/ready state.");
            return 1;
        }
    }
}
