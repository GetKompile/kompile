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

package ai.kompile.staging.cli.archive;

import ai.kompile.staging.registry.ModelRegistry;
import ai.kompile.staging.registry.RegistryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * CLI command for listing installed Kompile archives.
 */
@Component
@Command(
    name = "list",
    description = "List installed Kompile archives",
    mixinStandardHelpOptions = true
)
public class ArchiveListCommand implements Callable<Integer> {

    @Autowired
    private RegistryService registryService;

    @Option(names = {"--verbose", "-v"},
            description = "Show detailed information including models")
    private boolean verbose;

    @Option(names = {"--json"},
            description = "Output in JSON format")
    private boolean json;

    @Override
    public Integer call() {
        ModelRegistry registry = registryService.loadRegistry();
        List<ModelRegistry.ArchiveInstallInfo> archives = registry.getInstalledArchives();

        if (archives.isEmpty()) {
            if (json) {
                System.out.println("[]");
            } else {
                System.out.println("No archives installed.");
                System.out.println();
                System.out.println("Use 'archive download' to install archives from a URL");
                System.out.println("Use 'archive import' to import archives from a local file");
            }
            return 0;
        }

        if (json) {
            printJson(archives);
        } else {
            printTable(archives);
        }

        return 0;
    }

    private void printTable(List<ModelRegistry.ArchiveInstallInfo> archives) {
        System.out.println("Installed Archives:");
        System.out.println();

        // Print header
        String format = "%-30s %-12s %-20s %-8s%n";
        System.out.printf(format, "ARCHIVE ID", "VERSION", "INSTALLED", "MODELS");
        System.out.println("-".repeat(75));

        for (ModelRegistry.ArchiveInstallInfo archive : archives) {
            String installedAt = archive.getInstalledAt();
            if (installedAt != null && installedAt.length() > 20) {
                installedAt = installedAt.substring(0, 19);
            }

            int modelCount = archive.getModelIds() != null ? archive.getModelIds().size() : 0;

            System.out.printf(format,
                    truncate(archive.getArchiveId(), 30),
                    truncate(archive.getVersion(), 12),
                    installedAt != null ? installedAt : "unknown",
                    String.valueOf(modelCount));

            if (verbose && archive.getModelIds() != null && !archive.getModelIds().isEmpty()) {
                System.out.println("  Models:");
                for (String modelId : archive.getModelIds()) {
                    System.out.println("    - " + modelId);
                }
                System.out.println();
            }
        }

        System.out.println();
        System.out.println("Total: " + archives.size() + " archive(s)");
    }

    private void printJson(List<ModelRegistry.ArchiveInstallInfo> archives) {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        for (int i = 0; i < archives.size(); i++) {
            ModelRegistry.ArchiveInstallInfo archive = archives.get(i);
            sb.append("  {\n");
            sb.append("    \"archiveId\": \"").append(escapeJson(archive.getArchiveId())).append("\",\n");
            sb.append("    \"version\": \"").append(escapeJson(archive.getVersion())).append("\",\n");
            sb.append("    \"installedAt\": ").append(archive.getInstalledAt() != null ?
                    "\"" + escapeJson(archive.getInstalledAt()) + "\"" : "null").append(",\n");
            sb.append("    \"sourceUrl\": ").append(archive.getSourceUrl() != null ?
                    "\"" + escapeJson(archive.getSourceUrl()) + "\"" : "null").append(",\n");
            sb.append("    \"modelIds\": [");
            if (archive.getModelIds() != null && !archive.getModelIds().isEmpty()) {
                sb.append("\n");
                for (int j = 0; j < archive.getModelIds().size(); j++) {
                    sb.append("      \"").append(escapeJson(archive.getModelIds().get(j))).append("\"");
                    if (j < archive.getModelIds().size() - 1) {
                        sb.append(",");
                    }
                    sb.append("\n");
                }
                sb.append("    ");
            }
            sb.append("]\n");
            sb.append("  }");
            if (i < archives.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("]");
        System.out.println(sb.toString());
    }

    private String truncate(String s, int maxLength) {
        if (s == null) return "";
        if (s.length() <= maxLength) return s;
        return s.substring(0, maxLength - 3) + "...";
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
