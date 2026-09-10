/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalLauncherSecurityTest {

    @TempDir
    Path tempDir;

    @Test
    void failureDiagnosticDoesNotReevaluateCommandArguments() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "")
                .toLowerCase().startsWith("win"));
        Path marker = tempDir.resolve("diagnostic-command-substitution-ran");
        String substitution = "$(touch " + marker + ")";
        TerminalLauncher launcher = new TerminalLauncher(new TerminalConfig());
        String wrapper = launcher.buildBashWrapperCommand(
                List.of("/bin/false", substitution));

        ProcessBuilder processBuilder = new ProcessBuilder("bash", "-c", wrapper);
        processBuilder.environment().put("SHELL", "/bin/true");
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), "the fallback shell should exit cleanly");
        assertFalse(Files.exists(marker),
                "a diagnostic must not evaluate a command argument a second time");
        assertFalse(wrapper.contains("Command failed: " + substitution));
    }
}
