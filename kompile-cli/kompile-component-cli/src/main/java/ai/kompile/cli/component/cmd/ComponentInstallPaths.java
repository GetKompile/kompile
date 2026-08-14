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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.component.cmd;

import ai.kompile.cli.common.KompileHome;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Resolves installed component artifacts without conflating the distribution
 * install root with the persistent user-state root.
 */
final class ComponentInstallPaths {

    private static final Map<String, List<String>> ARTIFACT_ALIASES = Map.ofEntries(
            Map.entry("kompile-app-main", List.of("kompile-app-main", "kompile-server")),
            Map.entry("kompile-model-staging", List.of("kompile-model-staging")),
            Map.entry("kompile-model-serving", List.of("kompile-model-serving", "kompile-serving",
                    "kompile-app-subprocess-serving")),
            Map.entry("kompile-pipeline-serving", List.of("kompile-pipeline-serving")),
            Map.entry("kompile-app-chat", List.of("kompile-app-chat", "kompile-chat")),
            Map.entry("kompile-app-crawl-manager", List.of("kompile-app-crawl-manager",
                    "kompile-crawl-manager")),
            Map.entry("kompile-graph-service", List.of("kompile-graph-service")),
            Map.entry("kompile-cli", List.of("kompile-cli", "kompile")),
            Map.entry("kompile-app", List.of("kompile-app", "kompile-app-cli")),
            Map.entry("kompile-model", List.of("kompile-model")),
            Map.entry("kompile-agent", List.of("kompile-agent")),
            Map.entry("kompile-lite", List.of("kompile-lite"))
    );

    private ComponentInstallPaths() {
    }

    static InstallInfo findInstallInfo(String componentId) {
        for (File artifact : distributionArtifacts(componentId)) {
            if (artifact.isFile()) {
                return new InstallInfo(true, artifact, distributionVersion(artifact));
            }
        }

        for (File root : componentRoots()) {
            File componentDirectory = new File(root, componentId);
            if (!componentDirectory.isDirectory()) {
                continue;
            }

            File[] versionDirectories = componentDirectory.listFiles(File::isDirectory);
            if (versionDirectories != null && versionDirectories.length > 0) {
                Arrays.sort(versionDirectories, Comparator
                        .comparingLong(File::lastModified)
                        .thenComparing(File::getName)
                        .reversed());
                for (File versionDirectory : versionDirectories) {
                    File artifact = firstArtifact(versionDirectory, componentId);
                    if (artifact != null) {
                        return new InstallInfo(true, artifact, versionDirectory.getName());
                    }
                }
                return new InstallInfo(true, versionDirectories[0], versionDirectories[0].getName());
            }

            File nativeExecutable = new File(componentDirectory, componentId);
            if (nativeExecutable.isFile() && nativeExecutable.canExecute()) {
                return new InstallInfo(true, nativeExecutable, null);
            }
            return new InstallInfo(true, componentDirectory, null);
        }

        return new InstallInfo(false, null, null);
    }

    static List<File> componentRoots() {
        List<File> roots = new ArrayList<>();
        File installComponents = new File(KompileHome.installDirectory(), "components");
        roots.add(installComponents);
        File homeComponents = new File(KompileHome.homeDirectory(), "components");
        if (!homeComponents.equals(installComponents)) {
            roots.add(homeComponents);
        }
        return roots;
    }

    private static List<File> distributionArtifacts(String componentId) {
        List<File> artifacts = new ArrayList<>();
        File installRoot = KompileHome.installDirectory();
        File binDirectory = new File(installRoot, "bin");
        File libDirectory = new File(installRoot, "lib");
        for (String alias : aliasesFor(componentId)) {
            artifacts.add(new File(binDirectory, alias));
            artifacts.add(new File(binDirectory, alias + ".exe"));

            File exactJar = new File(libDirectory, alias + ".jar");
            artifacts.add(exactJar);
            File[] jars = libDirectory.listFiles((directory, name) ->
                    name.startsWith(alias) && name.endsWith(".jar")
                            && !name.equals(exactJar.getName()));
            if (jars != null) {
                Arrays.sort(jars, Comparator.comparing(File::getName).reversed());
                artifacts.addAll(Arrays.asList(jars));
            }
        }
        return artifacts;
    }

    private static File firstArtifact(File versionDirectory, String componentId) {
        File[] files = versionDirectory.listFiles(file -> file.isFile()
                && (file.getName().endsWith(".jar") || file.canExecute())
                && (file.getName().startsWith(componentId) || file.canExecute()));
        if (files == null || files.length == 0) {
            return null;
        }
        Arrays.sort(files, Comparator
                .comparing((File file) -> !file.canExecute())
                .thenComparing(File::getName));
        return files[0];
    }

    private static List<String> aliasesFor(String componentId) {
        return ARTIFACT_ALIASES.getOrDefault(componentId, List.of(componentId));
    }

    private static String distributionVersion(File artifact) {
        String name = artifact.getName();
        int index = name.indexOf("-0.");
        if (index < 0) {
            index = name.indexOf("-1.");
        }
        if (index < 0) {
            return null;
        }
        String version = name.substring(index + 1);
        return version.endsWith(".jar") ? version.substring(0, version.length() - 4) : version;
    }

    record InstallInfo(boolean installed, File path, String version) {
    }
}
