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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentInstallPathsTest {

    @AfterEach
    void clearInstallOverride() {
        System.clearProperty("kompile.install.dir");
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void resolvesDistributionNativeAliasesAndExactJars(@TempDir Path distribution) throws Exception {
        Path bin = Files.createDirectories(distribution.resolve("bin"));
        Path lib = Files.createDirectories(distribution.resolve("lib"));
        Path server = Files.createFile(bin.resolve("kompile-server"));
        assertTrue(server.toFile().setExecutable(true));
        Path staging = Files.createFile(lib.resolve("kompile-model-staging.jar"));
        System.setProperty("kompile.install.dir", distribution.toString());

        ComponentInstallPaths.InstallInfo app =
                ComponentInstallPaths.findInstallInfo("kompile-app-main");
        ComponentInstallPaths.InstallInfo modelStaging =
                ComponentInstallPaths.findInstallInfo("kompile-model-staging");

        assertTrue(app.installed());
        assertEquals(server.toFile(), app.path());
        assertTrue(modelStaging.installed());
        assertEquals(staging.toFile(), modelStaging.path());
    }

    @Test
    void configAndStatusAcceptAdvertisedLowercaseFormatsAndDefaults() {
        CommandLine config = new CommandLine(new ComponentConfigCommand());
        CommandLine status = new CommandLine(new ComponentStatusCommand());

        assertDoesNotThrow(() -> config.parseArgs());
        assertDoesNotThrow(() -> config.parseArgs("--format", "json"));
        assertDoesNotThrow(() -> status.parseArgs());
        assertDoesNotThrow(() -> status.parseArgs("--format", "yaml"));
    }
}
