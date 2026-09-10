/*
 * Copyright 2025 Kompile Inc.
 *
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

class LocalProjectModelBootstrapConvertTest {

    @TempDir
    Path tempDir;

    @Test
    void convertInvokesTheStandaloneModelCliCommand() throws Exception {
        Path input = tempDir.resolve("model.safetensors");
        Path output = tempDir.resolve("converted.sdz");
        Path capturedArguments = tempDir.resolve("arguments.txt");
        Path modelExecutable = tempDir.resolve("kompile-model");

        Files.writeString(input, "test model");
        Files.writeString(modelExecutable, "#!/bin/sh\n"
                + "printf '%s\\n' \"$@\" > '" + capturedArguments + "'\n"
                + "for arg in \"$@\"; do case \"$arg\" in --output=*) out=${arg#--output=};; esac; done\n"
                + "dd if=/dev/zero of=\"$out\" bs=2048 count=1 2>/dev/null\n");
        Files.setPosixFilePermissions(modelExecutable, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));

        Map<String, Object> result = LocalProjectModelBootstrap.convert(
                tempDir,
                input,
                output,
                "safetensors",
                Map.of("modelExecutable", modelExecutable.toString(), "timeoutMinutes", 1L));

        assertEquals("success", result.get("status"));
        assertEquals("kompile-model convert", result.get("command"));
        List<String> arguments = Files.readAllLines(capturedArguments);
        assertEquals("convert", arguments.get(0));
        assertTrue(arguments.contains("--input=" + input.toAbsolutePath().normalize()));
        assertTrue(arguments.contains("--output=" + output.toAbsolutePath().normalize()));
        assertTrue(arguments.contains("--format=safetensors"));
    }
}
