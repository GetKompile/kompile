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
package ai.kompile.cli.main;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainCommandTest {

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void delegatedDistributionBinaryReceivesResolvedInstallRoot(@TempDir Path tempDir) throws Exception {
        Path binDir = Files.createDirectories(tempDir.resolve("bin"));
        Files.createDirectories(tempDir.resolve("lib"));
        Path observedRoot = tempDir.resolve("observed-root");
        Path componentBinary = binDir.resolve("kompile-component");
        String outputPath = observedRoot.toString().replace("'", "'\"'\"'");
        Files.writeString(componentBinary,
                "#!/bin/sh\nprintf '%s' \"$KOMPILE_INSTALL_DIR\" > '" + outputPath + "'\n");
        assertTrue(componentBinary.toFile().setExecutable(true));

        String previousInstallDir = System.getProperty("kompile.install.dir");
        try {
            System.setProperty("kompile.install.dir", tempDir.toString());
            int exitCode = new MainCommand.DelegatingCommand("kompile-component", "test").call();

            assertEquals(0, exitCode);
            assertEquals(tempDir.toFile().getAbsolutePath(), Files.readString(observedRoot));
        } finally {
            if (previousInstallDir == null) {
                System.clearProperty("kompile.install.dir");
            } else {
                System.setProperty("kompile.install.dir", previousInstallDir);
            }
        }
    }
}
