/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The terminal config is read from and written to the config directory current when it is
 * loaded or saved, not the one current when the class first loaded.
 */
@TemporaryUserHome
class TerminalConfigTest {

    private static final String DATA_DIR = "kompile.data.dir";

    @TempDir
    Path dataDirs;

    @Test
    void configFollowsTheCurrentDataDirectory() {
        Path first = dataDirs.resolve("first");
        Path second = dataDirs.resolve("second");
        String original = System.getProperty(DATA_DIR);
        try {
            // The class can first load while an earlier data directory is current.
            System.setProperty(DATA_DIR, first.toString());
            TerminalConfig.builder().terminalCommand("first-terminal").build().save();

            System.setProperty(DATA_DIR, second.toString());
            assertFalse(TerminalConfig.load().isConfigured(), "the second data directory has no terminal yet");
            TerminalConfig.builder().terminalCommand("second-terminal").terminalArgs("-e").build().save();

            assertTrue(Files.exists(first.resolve("config").resolve("terminal.json")));
            assertTrue(Files.exists(second.resolve("config").resolve("terminal.json")));
            TerminalConfig current = TerminalConfig.load();
            assertEquals("second-terminal", current.getTerminalCommand());
            assertEquals("-e", current.getTerminalArgs());

            System.setProperty(DATA_DIR, first.toString());
            TerminalConfig earlier = TerminalConfig.load();
            assertEquals("first-terminal", earlier.getTerminalCommand());
            assertNull(earlier.getTerminalArgs());
        } finally {
            if (original == null) System.clearProperty(DATA_DIR);
            else System.setProperty(DATA_DIR, original);
        }
    }
}
