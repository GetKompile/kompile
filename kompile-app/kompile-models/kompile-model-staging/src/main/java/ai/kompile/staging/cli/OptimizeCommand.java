/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.staging.cli;

import ai.kompile.staging.compiler.CompilerService;
import ai.kompile.staging.web.dto.CompilerOptimizeRequest;
import ai.kompile.staging.web.dto.CompilerOptimizeResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * One-shot, provider-neutral graph optimization command.
 *
 * <p>The command accepts either a catalog model id or a local SameDiff artifact.
 * Local input/output paths make conversion and optimization composable without
 * registering a model or starting a service.</p>
 */
@Component
@Command(
        name = "optimize",
        description = "Optimize a SameDiff graph using configurable GraphOptimizer passes",
        mixinStandardHelpOptions = true)
public class OptimizeCommand implements Callable<Integer> {

    @Autowired
    private CompilerService compilerService;

    @Option(names = {"-i", "--input"},
            description = "Local input SameDiff artifact (.sdz or .fb); omitted when --model-id is used")
    private Path input;

    @Option(names = "--model-id",
            description = "Catalog model id; local --input takes precedence when both are supplied")
    private String modelId;

    @Option(names = {"-o", "--output"},
            description = "Output artifact (.sdz or .fb); omitted updates the input artifact in place")
    private Path output;

    @Option(names = {"--passes", "--selected-pass"}, split = ",",
            description = "Comma-separated optimizer pass ids; overrides --profile when supplied")
    private List<String> selectedPasses;

    @Option(names = "--profile", defaultValue = "BASIC",
            description = "Sane default profile when passes are omitted (FULL, BASIC, TRANSFORMER, GPU, NONE)")
    private String profile;

    @Option(names = "--max-iterations", defaultValue = "3",
            description = "Maximum optimizer iterations")
    private int maxIterations;

    @Option(names = "--quantization-type",
            description = "Optional quantization type forwarded to the staging compiler")
    private String quantizationType;

    @Option(names = "--force", defaultValue = "false",
            description = "Allow re-optimizing an already cataloged artifact")
    private boolean force;

    @Option(names = "--create-backup", defaultValue = "true",
            description = "Create a .backup beside an in-place/catalog output")
    private boolean createBackup;

    @Option(names = "--dry-run", defaultValue = "false",
            description = "Load and resolve passes without writing an artifact")
    private boolean dryRun;

    @Override
    public Integer call() {
        try {
            if (input == null && (modelId == null || modelId.isBlank())) {
                throw new IllegalArgumentException("Either --input or --model-id is required");
            }
            Path inputPath = input == null ? null : input.toAbsolutePath().normalize();
            if (inputPath != null && (!Files.isRegularFile(inputPath) || Files.isSymbolicLink(inputPath))) {
                throw new IllegalArgumentException("Input artifact does not exist or is a symbolic link: " + inputPath);
            }
            Path outputPath = output == null ? null : output.toAbsolutePath().normalize();
            if (outputPath != null) {
                String name = outputPath.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".sdz") && !name.endsWith(".fb")) {
                    throw new IllegalArgumentException("Output must use .sdz or .fb: " + outputPath);
                }
            }

            CompilerOptimizeRequest request = CompilerOptimizeRequest.builder()
                    .modelId(modelId == null || modelId.isBlank() ? null : modelId.trim())
                    .inputPath(inputPath == null ? null : inputPath.toString())
                    .outputPath(outputPath == null ? null : outputPath.toString())
                    .selectedPasses(selectedPasses)
                    .profile(profile)
                    .maxIterations(Math.max(1, maxIterations))
                    .quantizationType(quantizationType)
                    .force(force)
                    .createBackup(createBackup)
                    .dryRun(dryRun)
                    .build();

            CompilerOptimizeResponse response = compilerService.optimizeGraph(request);
            if (response.isSuccess()) {
                System.out.println("Optimization completed successfully");
                System.out.println("Model: " + (inputPath != null ? inputPath : modelId));
                if (outputPath != null) {
                    System.out.println("Output: " + outputPath);
                }
                System.out.println("Passes: " + response.getPassesApplied());
                System.out.println("Operations: " + response.getBeforeOpsCount() + " -> " + response.getAfterOpsCount());
                return 0;
            }
            System.err.println("Optimization failed: " + response.getError());
            return 1;
        } catch (Exception e) {
            System.err.println("Optimization failed: " + message(e));
            return 1;
        }
    }

    private static String message(Exception e) {
        String value = e.getMessage();
        return value == null || value.isBlank() ? e.getClass().getSimpleName() : value;
    }
}
