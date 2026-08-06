/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KompileHomeTest {

    @AfterEach
    void clearDataDirProperty() {
        System.clearProperty("kompile.data.dir");
        System.clearProperty("kompile.llm.cache.dir");
    }

    @Test
    void configDirectoryDefaultsToHomeWhenPropertyUnset() {
        System.clearProperty("kompile.data.dir");
        File expected = new File(new File(System.getProperty("user.home"), ".kompile"), "config");
        assertEquals(expected, KompileHome.configDirectory());
        assertEquals(KompileHome.homeDirectory(), KompileHome.resolvedHomeDirectory());
    }

    @Test
    void resolvedHomeAndConfigHonorKompileDataDirSystemProperty() {
        System.setProperty("kompile.data.dir", "/tmp/kompile-proj");
        assertEquals(new File("/tmp/kompile-proj"), KompileHome.resolvedHomeDirectory());
        assertEquals(new File("/tmp/kompile-proj", "config"), KompileHome.configDirectory());
    }

    @Test
    void blankLaunchContextFallsBackToHome() {
        assertEquals(KompileHome.homeDirectory(),
                KompileHome.resolveHomeDirectory("   ", "   "));
    }

    @Test
    void projectLauncherEnvironmentSelectsTheManagedProjectRoot() {
        assertEquals(new File("/tmp/kompile-env-project"),
                KompileHome.resolveHomeDirectory(null, "/tmp/kompile-env-project"));
    }

    @Test
    void explicitDataDirectoryWinsOverProjectLauncherEnvironment() {
        assertEquals(new File("/tmp/kompile-explicit-project"),
                KompileHome.resolveHomeDirectory(
                        "/tmp/kompile-explicit-project", "/tmp/kompile-env-project"));
    }

    @Test
    void llmCacheUsesProjectDataDirectory() {
        System.setProperty("kompile.data.dir", "/tmp/kompile-proj");
        assertEquals(new File("/tmp/kompile-proj/data/llm-cache"), KompileHome.llmCacheDirectory());
    }

    @Test
    void explicitLlmCacheOverrideWins() {
        System.setProperty("kompile.data.dir", "/tmp/kompile-proj");
        System.setProperty("kompile.llm.cache.dir", "/tmp/custom-llm-cache");
        assertEquals(new File("/tmp/custom-llm-cache"), KompileHome.llmCacheDirectory());
    }
}
