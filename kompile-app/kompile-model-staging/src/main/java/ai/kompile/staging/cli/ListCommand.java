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

import ai.kompile.staging.registry.*;
import ai.kompile.staging.staging.StagingModelInfo;
import ai.kompile.staging.staging.StagingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * CLI command for listing models.
 */
@Component
@Command(
    name = "list",
    description = "List models in the registry and staging",
    mixinStandardHelpOptions = true
)
public class ListCommand implements Callable<Integer> {

    @Autowired
    private RegistryService registryService;

    @Autowired
    private StagingService stagingService;

    @Option(names = {"-t", "--type"},
            description = "Filter by type: encoder, cross_encoder, reranker")
    private String type;

    @Option(names = {"-s", "--status"},
            description = "Filter by status: active, staged, pending, failed")
    private String status;

    @Option(names = {"--staging"},
            description = "Show staging models instead of registry")
    private boolean showStaging;

    @Override
    public Integer call() {
        if (showStaging) {
            return listStagingModels();
        } else {
            return listRegistryModels();
        }
    }

    private Integer listRegistryModels() {
        ModelRegistry registry = registryService.loadRegistry();

        System.out.println("=== Model Registry ===");
        System.out.println("Version: " + registry.getVersion());
        System.out.println("Last updated: " + registry.getUpdatedAt());
        System.out.println();

        List<ModelEntry> models;
        if (type != null) {
            models = registry.getModelsByType(ModelType.fromValue(type));
        } else {
            models = registry.getActiveModels();
        }

        if (models.isEmpty()) {
            System.out.println("No models found.");
            return 0;
        }

        // Print header
        System.out.printf("%-30s %-15s %-10s %-10s%n",
                "MODEL ID", "TYPE", "STATUS", "DIM/SIZE");
        System.out.println("-".repeat(70));

        for (ModelEntry entry : models) {
            if (status != null && !entry.getStatus().getValue().equalsIgnoreCase(status)) {
                continue;
            }

            String dimInfo = "";
            if (entry.getMetadata() != null) {
                if (entry.getMetadata().getEmbeddingDim() != null) {
                    dimInfo = entry.getMetadata().getEmbeddingDim().toString();
                } else if (entry.getMetadata().getHiddenSize() != null) {
                    dimInfo = entry.getMetadata().getHiddenSize().toString();
                }
            }

            System.out.printf("%-30s %-15s %-10s %-10s%n",
                    entry.getModelId(),
                    entry.getType().getValue(),
                    entry.getStatus().getValue(),
                    dimInfo);
        }

        System.out.println();
        System.out.println("Total: " + models.size() + " models");
        return 0;
    }

    private Integer listStagingModels() {
        List<StagingModelInfo> models = stagingService.getStagingModels();

        System.out.println("=== Staging Models ===");
        System.out.println();

        if (models.isEmpty()) {
            System.out.println("No models in staging.");
            return 0;
        }

        // Print header
        System.out.printf("%-30s %-15s %-8s %-20s%n",
                "MODEL ID", "STATUS", "PROGRESS", "MESSAGE");
        System.out.println("-".repeat(80));

        for (StagingModelInfo info : models) {
            System.out.printf("%-30s %-15s %6d%% %-20s%n",
                    info.getModelId(),
                    info.getStatus().getValue(),
                    info.getProgress(),
                    truncate(info.getMessage(), 20));
        }

        System.out.println();
        System.out.println("Total: " + models.size() + " models in staging");
        return 0;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }
}
