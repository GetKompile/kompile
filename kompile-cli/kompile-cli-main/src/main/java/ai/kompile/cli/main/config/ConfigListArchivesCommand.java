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

import ai.kompile.cli.common.config.ArchiveInfo;
import ai.kompile.cli.common.config.ConfigArchiveManifest;
import ai.kompile.cli.common.config.ConfigArchiveService;
import picocli.CommandLine.Command;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Lists saved configuration archives.
 *
 * <p>Usage:
 * <pre>
 *   kompile config archives    # List all saved archives
 * </pre>
 */
@Command(name = "archives",
        mixinStandardHelpOptions = true,
        description = "List saved configuration archives")
public class ConfigListArchivesCommand implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        List<ArchiveInfo> archives = ConfigArchiveService.listArchives();

        if (archives.isEmpty()) {
            System.out.println("No configuration archives found.");
            System.out.println("Use 'kompile config export' to create one.");
            return 0;
        }

        System.out.println("Saved configuration archives:");
        System.out.println();

        for (ArchiveInfo info : archives) {
            System.out.printf("  %-50s  %8s  %s%n",
                    info.getFileName(),
                    formatSize(info.getSizeBytes()),
                    info.getLastModified().substring(0, 19).replace('T', ' '));

            ConfigArchiveManifest m = info.getManifest();
            if (m != null) {
                int total = m.getKompileConfigs().size() + m.getSystemPrompts().size();
                for (List<String> files : m.getChatProviderConfigs().values()) {
                    total += files.size();
                }
                System.out.printf("  %-50s  %d configs", "", total);
                if (!m.getChatProviderConfigs().isEmpty()) {
                    System.out.print(", providers: " +
                            String.join(", ", m.getChatProviderConfigs().keySet()));
                }
                if (m.getDescription() != null) {
                    System.out.print("  — " + m.getDescription());
                }
                System.out.println();
            }
            System.out.println();
        }

        System.out.println("Total: " + archives.size() + " archive(s)");
        return 0;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
