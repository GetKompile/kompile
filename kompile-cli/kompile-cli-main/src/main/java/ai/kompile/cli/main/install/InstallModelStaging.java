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

import ai.kompile.cli.main.install.registry.ComponentRegistry;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;

/**
 * Install command for kompile-model-staging component.
 * Downloads and installs the model lifecycle management service.
 */
@CommandLine.Command(name = "kompile-model-staging", 
        description = "Install kompile-model-staging (model lifecycle management service)",
        mixinStandardHelpOptions = true)
public class InstallModelStaging implements Callable<Integer> {

    @CommandLine.Option(names = {"--source"}, 
            description = "Release source: github, maven, custom",
            defaultValue = "github")
    private String source = "github";

    @CommandLine.Option(names = {"--version"}, 
            description = "Component version to install (default: current CLI version)")
    private String version;

    @CommandLine.Option(names = {"--url"}, 
            description = "Custom download URL (overrides --source)")
    private String customUrl;

    @CommandLine.Option(names = {"--force"}, 
            description = "Force re-download even if already installed")
    private boolean force = false;

    @CommandLine.Option(names = {"--verbose"}, 
            description = "Enable verbose output")
    private boolean verbose = false;

    @CommandLine.Option(names = {"--port"}, 
            description = "Default port for the staging service",
            defaultValue = "8081")
    private int port = 8081;

    @CommandLine.Option(names = {"--build-from-source"},
            description = "Build from local source instead of downloading")
    private String sourceDir;

    @CommandLine.Option(names = {"--local"},
            description = "Install from a local JAR file (e.g., a pre-built exec JAR)")
    private File localJar;

    @Override
    public Integer call() throws Exception {
        ComponentRegistry registry = new ComponentRegistry();

        // Override version if specified
        if (version != null) {
            registry.setVersion(version);
        }

        // Set custom URL if provided
        if (customUrl != null) {
            registry.setCustomUrl(ComponentRegistry.KOMPILE_MODEL_STAGING, customUrl);
        }

        ComponentInstaller installer = new ComponentInstaller(registry);
        installer.setForceDownload(force);
        installer.setVerbose(verbose);

        try {
            File installedJar;

            if (localJar != null) {
                // Install from local JAR file directly
                if (!localJar.isFile()) {
                    System.err.println("Local JAR not found: " + localJar.getAbsolutePath());
                    return 1;
                }
                installedJar = installer.installFromLocalJar(ComponentRegistry.KOMPILE_MODEL_STAGING, localJar);
            } else if (sourceDir != null) {
                // Build from source
                installedJar = installer.buildFromSource(ComponentRegistry.KOMPILE_MODEL_STAGING, sourceDir);
            } else {
                // Download from release source
                ComponentRegistry.ReleaseSource releaseSource = parseSource(source);
                installedJar = installer.installComponent(ComponentRegistry.KOMPILE_MODEL_STAGING, releaseSource);
            }

            System.out.println("\nkompile-model-staging installed successfully!");
            System.out.println("  JAR: " + installedJar.getAbsolutePath());
            System.out.println("  Default port: " + port);
            System.out.println("\nTo start the service:");
            System.out.println("  kompile manage start kompile-model-staging --port " + port);
            System.out.println("\nOr use within a project:");
            System.out.println("  kompile project start");

            return 0;

        } catch (Exception e) {
            System.err.println("\nFailed to install kompile-model-staging: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
            return 1;
        }
    }

    private ComponentRegistry.ReleaseSource parseSource(String source) {
        switch (source.toLowerCase()) {
            case "github":
                return ComponentRegistry.ReleaseSource.GITHUB_RELEASES;
            case "maven":
                return ComponentRegistry.ReleaseSource.MAVEN;
            case "custom":
                if (customUrl == null) {
                    throw new IllegalArgumentException("--url is required when using --source=custom");
                }
                return ComponentRegistry.ReleaseSource.CUSTOM;
            default:
                throw new IllegalArgumentException("Unknown source: " + source + ". Use: github, maven, or custom");
        }
    }
}
