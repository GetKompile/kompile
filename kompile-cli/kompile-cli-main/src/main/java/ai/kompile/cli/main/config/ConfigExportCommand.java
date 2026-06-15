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

package ai.kompile.cli.main.config;

import ai.kompile.cli.common.config.ComponentFilter;
import ai.kompile.cli.common.config.ConfigArchiveManifest;
import ai.kompile.cli.common.config.ConfigArchiveService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Exports Kompile and chat provider configurations into a portable zip archive.
 *
 * <p>By default, runs an interactive wizard to select components.
 * Use {@code --all} to skip the wizard and include everything.
 *
 * <p>Usage:
 * <pre>
 *   kompile config export                              # Interactive wizard
 *   kompile config export --all                        # Export everything
 *   kompile config export --all -o /tmp/my-config.zip  # Custom output, no wizard
 *   kompile config export -d "prod setup"              # With description
 * </pre>
 */
@Command(name = "export",
        mixinStandardHelpOptions = true,
        description = "Export Kompile and chat provider configs to a zip archive")
public class ConfigExportCommand implements Callable<Integer> {

    @Option(names = {"-o", "--output"},
            description = "Output file path (default: ~/.kompile/archives/kompile-config-<timestamp>.zip)")
    private Path output;

    @Option(names = {"-d", "--description"},
            description = "Description to embed in the archive manifest")
    private String description;

    @Option(names = {"--all", "-a"},
            description = "Export all components without interactive selection")
    private boolean all;

    @Override
    public Integer call() throws Exception {
        ComponentFilter filter;
        if (all) {
            filter = ComponentFilter.all();
        } else {
            filter = ConfigArchiveWizard.runExportWizard();
            if (filter == null) {
                System.out.println("Export cancelled.");
                return 0;
            }
        }

        System.out.println("Exporting configuration archive...");

        Path archivePath = ConfigArchiveService.exportArchive(output, description, filter);
        ConfigArchiveManifest manifest = ConfigArchiveService.readManifest(archivePath);

        System.out.println();
        System.out.println("Archive created: " + archivePath);
        System.out.println();

        printManifestSummary(manifest);
        return 0;
    }

    static void printManifestSummary(ConfigArchiveManifest manifest) {
        List<String> kompileConfigs = manifest.getKompileConfigs();
        if (!kompileConfigs.isEmpty()) {
            System.out.println("Kompile configs (" + kompileConfigs.size() + "):");
            for (String c : kompileConfigs) {
                System.out.println("  - " + c);
            }
        }

        Map<String, List<String>> providers = manifest.getChatProviderConfigs();
        if (!providers.isEmpty()) {
            System.out.println();
            System.out.println("Chat provider configs:");
            for (Map.Entry<String, List<String>> e : providers.entrySet()) {
                System.out.println("  " + e.getKey() + ":");
                for (String f : e.getValue()) {
                    System.out.println("    - " + f);
                }
            }
        }

        List<String> prompts = manifest.getSystemPrompts();
        if (!prompts.isEmpty()) {
            System.out.println();
            System.out.println("System prompts (" + prompts.size() + "):");
            for (String p : prompts) {
                System.out.println("  - " + p);
            }
        }
    }
}
