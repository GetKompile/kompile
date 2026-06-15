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

package ai.kompile.cli.main.install;

import ai.kompile.cli.main.Info;
import ai.kompile.cli.main.util.OSResolver;
import picocli.CommandLine;

import java.io.File;
import java.util.*;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "native-tools", mixinStandardHelpOptions = false,
        description = "Installs native tools like cmake, make, gcc for use with compiling C++ tools.")
public class NativeToolsCompilation implements Callable<Integer> {

    private static final String CMAKE_VERSION = "3.25.0";
    private static final String CMAKE_URL_BASE = "https://github.com/Kitware/CMake/releases/download/v" + CMAKE_VERSION;

    @CommandLine.Option(names = {"--platform"}, description = "Platform to install tools for (e.g., linux-x86_64, linux-arm64, macosx-arm64).", required = true)
    private String[] platforms;

    @CommandLine.Option(names = {"--cmake"}, defaultValue = "true", negatable = true,
            description = "Install cmake (default: true)")
    private boolean installCmake;

    @CommandLine.Option(names = {"--force"}, defaultValue = "false",
            description = "Force re-download even if tools already exist")
    private boolean force;

    @Override
    public Integer call() throws Exception {
        File nativeToolsDir = new File(Info.homeDirectory(), "native-tools");
        if (!nativeToolsDir.exists() && !nativeToolsDir.mkdirs()) {
            System.err.println("Failed to create native tools directory: " + nativeToolsDir.getAbsolutePath());
            return 1;
        }

        System.out.println("Installing native tools for platforms: " + Arrays.toString(platforms));
        System.out.println("Install directory: " + nativeToolsDir.getAbsolutePath());
        System.out.println();

        int failures = 0;
        for (String platform : platforms) {
            System.out.println("=== Platform: " + platform + " ===");

            try {
                if (installCmake) {
                    installCmakeForPlatform(platform, nativeToolsDir);
                }
            } catch (Exception e) {
                System.err.println("Failed to install tools for platform " + platform + ": " + e.getMessage());
                failures++;
            }

            System.out.println();
        }

        if (failures > 0) {
            System.err.println(failures + " platform(s) failed to install.");
            return 1;
        }

        System.out.println("Native tools installation complete.");
        System.out.println("Tools are available at: " + nativeToolsDir.getAbsolutePath());
        System.out.println();
        printPathInstructions(nativeToolsDir);
        return 0;
    }

    private void installCmakeForPlatform(String platform, File nativeToolsDir) throws Exception {
        File platformDir = new File(nativeToolsDir, platform);
        File cmakeDir = new File(platformDir, "cmake");

        if (cmakeDir.exists() && !force) {
            System.out.println("  cmake: already installed (use --force to re-download)");
            return;
        }

        String downloadUrl = getCmakeDownloadUrl(platform);
        if (downloadUrl == null) {
            System.err.println("  cmake: no prebuilt binary available for " + platform);
            System.err.println("         Build from source: download cmake-" + CMAKE_VERSION + ".tar.gz and run ./configure && make");
            return;
        }

        System.out.println("  cmake: downloading from " + downloadUrl);
        String fileName = "cmake-" + CMAKE_VERSION + "-" + platform + getArchiveExtension(platform);

        File archive = InstallMain.downloadAndLoadFrom(downloadUrl, fileName, force);
        if (archive == null) {
            throw new RuntimeException("Failed to download cmake archive");
        }

        if (!platformDir.exists() && !platformDir.mkdirs()) {
            throw new RuntimeException("Failed to create platform directory: " + platformDir);
        }

        System.out.println("  cmake: extracting...");
        if (fileName.endsWith(".zip")) {
            ArchiveUtils.unzipFileTo(archive.getAbsolutePath(), platformDir.getAbsolutePath(), true);
        } else {
            // For tar.gz, use system tar
            ProcessBuilder pb = new ProcessBuilder("tar", "xzf", archive.getAbsolutePath(), "-C", platformDir.getAbsolutePath());
            pb.inheritIO();
            Process p = pb.start();
            if (!p.waitFor(300, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new RuntimeException("tar extraction timed out after 300 seconds");
            }
            if (p.exitValue() != 0) {
                throw new RuntimeException("tar extraction failed with exit code: " + p.exitValue());
            }
        }

        // Rename extracted directory to "cmake" for consistent path
        File[] extracted = platformDir.listFiles((dir, name) -> name.startsWith("cmake-"));
        if (extracted != null && extracted.length > 0 && !cmakeDir.exists()) {
            if (!extracted[0].renameTo(cmakeDir)) {
                System.err.println("  Warning: could not rename " + extracted[0].getName() + " to cmake");
            }
        }

        System.out.println("  cmake: installed to " + cmakeDir.getAbsolutePath());
    }

    private String getCmakeDownloadUrl(String platform) {
        // Map platform string to cmake download URL
        switch (platform.toLowerCase()) {
            case "linux-x86_64":
                return CMAKE_URL_BASE + "/cmake-" + CMAKE_VERSION + "-linux-x86_64.tar.gz";
            case "linux-arm64":
            case "linux-aarch64":
                return CMAKE_URL_BASE + "/cmake-" + CMAKE_VERSION + "-linux-aarch64.tar.gz";
            case "macosx-x86_64":
            case "macos-x86_64":
                return CMAKE_URL_BASE + "/cmake-" + CMAKE_VERSION + "-macos-universal.tar.gz";
            case "macosx-arm64":
            case "macos-arm64":
                return CMAKE_URL_BASE + "/cmake-" + CMAKE_VERSION + "-macos-universal.tar.gz";
            case "windows-x86_64":
                return CMAKE_URL_BASE + "/cmake-" + CMAKE_VERSION + "-windows-x86_64.zip";
            default:
                return null;
        }
    }

    private String getArchiveExtension(String platform) {
        if (platform.toLowerCase().startsWith("windows")) {
            return ".zip";
        }
        return ".tar.gz";
    }

    private void printPathInstructions(File nativeToolsDir) {
        System.out.println("To use installed tools, add them to your PATH:");
        for (String platform : platforms) {
            File cmakeBin = new File(nativeToolsDir, platform + "/cmake/bin");
            if (cmakeBin.exists()) {
                System.out.println("  export PATH=" + cmakeBin.getAbsolutePath() + ":$PATH");
            }
        }
    }
}
