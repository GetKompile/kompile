/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.orchestrator.integration.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CliAgentExtractionLlmServiceConfigPathTest {

    private String savedDataDir;
    private String savedUserHome;

    @BeforeEach
    void setUp() {
        savedDataDir = System.getProperty("kompile.data.dir");
        savedUserHome = System.getProperty("user.home");
    }

    @AfterEach
    void tearDown() {
        if (savedDataDir == null) System.clearProperty("kompile.data.dir");
        else System.setProperty("kompile.data.dir", savedDataDir);
        System.setProperty("user.home", savedUserHome);
    }

    @Test
    void resolvesFromDataDirWhenSet(@TempDir Path tempDir) throws Exception {
        System.setProperty("kompile.data.dir", tempDir.toString());
        java.lang.reflect.Method m = CliAgentExtractionLlmService.class.getDeclaredMethod("resolveCliLlmConfigPath");
        m.setAccessible(true);
        Path result = (Path) m.invoke(null);
        assertTrue(result.startsWith(tempDir), "Must start with kompile.data.dir. Got: " + result);
        assertTrue(result.endsWith("cli-llm-config.json"), "Must end with cli-llm-config.json. Got: " + result);
    }

    @Test
    void fallsBackToUserHomeWhenDataDirNotSet(@TempDir Path fakeHome) {
        System.clearProperty("kompile.data.dir");
        System.setProperty("user.home", fakeHome.toString());
        try {
            java.lang.reflect.Method m = CliAgentExtractionLlmService.class.getDeclaredMethod("resolveCliLlmConfigPath");
            m.setAccessible(true);
            Path result = (Path) m.invoke(null);
            assertTrue(result.startsWith(fakeHome), "Must fall back to user.home. Got: " + result);
            assertTrue(result.endsWith("cli-llm-config.json"), "Must end with cli-llm-config.json. Got: " + result);
        } catch (Exception e) {
            fail("resolveCliLlmConfigPath should be accessible: " + e.getMessage());
        }
    }
}
