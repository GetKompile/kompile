/* Copyright 2025 Kompile Inc. Licensed under Apache-2.0. */
package ai.kompile.cli.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ModelDownloadCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void directDryRunUsesPinnedManifestWithoutStaging() {
        Path output = tempDir.resolve("multilingual-e5-small");

        int exit = new CommandLine(new ModelDownloadCommand()).execute(
                "--model-id=multilingual-e5-small",
                "--repo=intfloat/multilingual-e5-small",
                "--revision=614241f622f53c4eeff9890bdc4f31cfecc418b3",
                "--output=" + output,
                "--dry-run");

        assertEquals(0, exit);
        assertFalse(Files.exists(output));
    }

    @Test
    void directDownloadRejectsUnpinnedRevisionBeforeNetworkAccess() {
        int exit = new CommandLine(new ModelDownloadCommand()).execute(
                "--model-id=multilingual-e5-small",
                "--revision=main",
                "--output=" + tempDir.resolve("model"),
                "--dry-run");

        assertEquals(1, exit);
    }
}
