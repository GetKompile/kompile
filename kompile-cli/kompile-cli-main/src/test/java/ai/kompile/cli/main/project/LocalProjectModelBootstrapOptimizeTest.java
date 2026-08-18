/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalProjectModelBootstrapOptimizeTest {

    @TempDir
    Path tempDir;

    @Test
    void optimizeInvokesTheStandaloneModelCliWithEveryConfiguredParameter() throws Exception {
        Path input = tempDir.resolve("converted.sdz");
        Path output = tempDir.resolve("optimized.sdz");
        Path capturedArguments = tempDir.resolve("arguments.txt");
        Path modelExecutable = tempDir.resolve("kompile-model");

        Files.writeString(input, "test model");
        Files.writeString(modelExecutable, "#!/bin/sh\nprintf '%s\\n' \"$@\" > '" + capturedArguments + "'\n");
        Files.setPosixFilePermissions(modelExecutable, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));

        Map<String, Object> result = LocalProjectModelBootstrap.optimize(
                tempDir,
                input,
                output,
                null,
                Map.ofEntries(
                        Map.entry("modelExecutable", modelExecutable.toString()),
                        Map.entry("selectedPasses", List.of("dead_code_elimination", "algebraic")),
                        Map.entry("profile", "TRANSFORMER"),
                        Map.entry("maxIterations", 7L),
                        Map.entry("quantizationType", "float16"),
                        Map.entry("force", true),
                        Map.entry("createBackup", false),
                        Map.entry("dryRun", true),
                        Map.entry("stagingExecutable", tempDir.resolve("staging-native").toString()),
                        Map.entry("stagingJar", tempDir.resolve("staging.jar").toString()),
                        Map.entry("javaExecutable", tempDir.resolve("java").toString()),
                        Map.entry("timeoutMinutes", 1L)));

        assertEquals("success", result.get("status"));
        assertEquals("kompile-model optimize", result.get("command"));
        List<String> arguments = Files.readAllLines(capturedArguments);
        assertEquals("optimize", arguments.get(0));
        assertTrue(arguments.contains("--input=" + input.toAbsolutePath().normalize()));
        assertTrue(arguments.contains("--output=" + output.toAbsolutePath().normalize()));
        assertTrue(arguments.contains("--passes=dead_code_elimination,algebraic"));
        assertTrue(arguments.contains("--profile=TRANSFORMER"));
        assertTrue(arguments.contains("--max-iterations=7"));
        assertTrue(arguments.contains("--quantization-type=float16"));
        assertTrue(arguments.contains("--force=true"));
        assertTrue(arguments.contains("--create-backup=false"));
        assertTrue(arguments.contains("--dry-run=true"));
        assertTrue(arguments.contains("--staging-executable=" + tempDir.resolve("staging-native").toAbsolutePath().normalize()));
        assertTrue(arguments.contains("--staging-jar=" + tempDir.resolve("staging.jar").toAbsolutePath().normalize()));
        assertTrue(arguments.contains("--java=" + tempDir.resolve("java").toAbsolutePath().normalize()));
    }
}
