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
package ai.kompile.app.projectstore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Kompile-managed project store config persists to the config directory resolved via
 * {@code -Dkompile.data.dir} (i.e. {@code <data-dir>/config/project-store.json}), not a Spring property.
 */
class ProjectStoreConfigServiceTest {

    @TempDir
    Path home;
    private String previousDataDir;

    @BeforeEach
    void setUp() {
        previousDataDir = System.getProperty("kompile.data.dir");
        System.setProperty("kompile.data.dir", home.toString());
    }

    @AfterEach
    void tearDown() {
        if (previousDataDir == null) {
            System.clearProperty("kompile.data.dir");
        } else {
            System.setProperty("kompile.data.dir", previousDataDir);
        }
    }

    @Test
    void defaultsToUnconfigured() {
        ProjectStoreConfigService service = new ProjectStoreConfigService();
        service.load();
        assertFalse(service.isConfigured());
        assertTrue(service.storeUrl().isEmpty());
    }

    @Test
    void persistsTrimsAndReloadsStoreUrl() throws Exception {
        ProjectStoreConfigService service = new ProjectStoreConfigService();
        service.load();

        ProjectStoreConfig update = new ProjectStoreConfig();
        update.setUrl("http://localhost:8088/");
        update.setGitXet(false);
        service.update(update);

        // Persisted under the Kompile config directory (honoring -Dkompile.data.dir).
        Path file = home.resolve("config").resolve("project-store.json");
        assertTrue(Files.exists(file), "config file should be written");
        assertTrue(Files.readString(file).contains("localhost:8088"));

        // Trailing slash trimmed; flags retained.
        assertTrue(service.isConfigured());
        assertEquals("http://localhost:8088", service.storeUrl().orElseThrow());
        assertFalse(service.getConfig().isGitXet());

        // A fresh instance reads the persisted value back (round-trips the read-only 'configured' field).
        ProjectStoreConfigService reloaded = new ProjectStoreConfigService();
        reloaded.load();
        assertEquals("http://localhost:8088", reloaded.storeUrl().orElseThrow());
        assertFalse(reloaded.getConfig().isGitXet());
    }
}
