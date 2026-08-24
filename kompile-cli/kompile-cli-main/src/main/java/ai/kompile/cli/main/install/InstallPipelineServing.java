/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.install;

import ai.kompile.cli.main.install.registry.ComponentRegistry;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;

/** Install and stage the standalone unified-pipeline runtime. */
@CommandLine.Command(name = "kompile-pipeline-serving",
        description = "Install the request-scoped unified pipeline runtime",
        mixinStandardHelpOptions = true)
public class InstallPipelineServing implements Callable<Integer> {

    @CommandLine.Option(names = "--verbose", description = "Enable verbose output")
    private boolean verbose;

    @CommandLine.Option(names = "--local",
            description = "Stage a pre-built pipeline-serving executable JAR",
            required = true)
    private File localJar;

    @Override
    public Integer call() {
        ComponentRegistry registry = new ComponentRegistry();
        ComponentInstaller installer = new ComponentInstaller(registry);
        installer.setVerbose(verbose);
        try {
            File staged = installer.installDistributionRuntimeFromLocalJar(
                    ComponentRegistry.KOMPILE_PIPELINE_SERVING, localJar);
            System.out.println("\nkompile-pipeline-serving installed successfully!");
            System.out.println("  Runtime: " + staged.getAbsolutePath());
            System.out.println("  New pipeline requests will use this artifact; restart an existing pooled child first.");
            return 0;
        } catch (Exception failure) {
            System.err.println("\nFailed to install kompile-pipeline-serving: " + failure.getMessage());
            if (verbose) failure.printStackTrace();
            return 1;
        }
    }
}
