/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main;

import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "init",
        description = "Initialize an isolated kompile install directory (the value passed as -Dkompile.data.dir at runtime).")
public class Init implements Callable<Integer> {

    @CommandLine.Option(
            names = {"--installDir", "-d"},
            description = "Path to the install directory. Defaults to ~/.kompile.",
            required = false)
    private String installDir;

    @CommandLine.Option(
            names = {"--force"},
            description = "Delete the directory if it already exists and recreate it.")
    private boolean force = false;

    @Override
    public Integer call() throws Exception {
        Path target = resolveInstallDir();

        if (Files.exists(target)) {
            if (force) {
                deleteRecursively(target);
                System.err.println("Removed existing install directory: " + target);
            } else {
                System.out.println("Install directory already exists: " + target);
                System.out.println("Use --force to recreate.");
            }
        }

        // Create the canonical layout that the running kompile app expects to
        // find under kompile.data.dir. AppIndexConfigService writes its config
        // file to <installDir>/config/app-index-config.json on first run; the
        // other directories are created by the app as needed but pre-creating
        // them lets users drop fact sheets, archives, etc. in place.
        createDir(target);
        createDir(target.resolve("config"));
        createDir(target.resolve("fact-sheets"));
        createDir(target.resolve("archives"));
        createDir(target.resolve("anserini/indexes"));

        System.out.println("Initialized kompile install directory at: " + target);
        System.out.println();
        System.out.println("Start a kompile app against this directory with:");
        System.out.println("  java -Dkompile.data.dir=" + target + " -jar <your-app>.jar");
        return 0;
    }

    private Path resolveInstallDir() {
        if (installDir != null && !installDir.isBlank()) {
            return Paths.get(installDir).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.home"), ".kompile").toAbsolutePath().normalize();
    }

    private void createDir(Path dir) throws java.io.IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            System.out.println("Created: " + dir);
        }
    }

    private void deleteRecursively(Path root) throws java.io.IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (java.io.IOException e) {
                    throw new RuntimeException("Failed to delete " + p, e);
                }
            });
        }
    }
}
