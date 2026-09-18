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
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.cli.common.logs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogRetentionConfigTest {

    private String originalUserHome;

    @BeforeEach
    void redirectHome(@TempDir Path tempHome) {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", originalUserHome);
    }

    @Test
    void loadReturnsDefaultsWhenFileAbsent() {
        LogRetentionConfig config = LogRetentionConfig.load();
        assertEquals(30, config.maxAgeDays);
        assertEquals(2048, config.maxTotalMb);
        assertEquals(100, config.maxFilesPerAgent);
        assertTrue(config.archiveEnabled);
        assertEquals(90, config.coldRetentionDays);
    }

    @Test
    void saveThenLoadRoundTrips() {
        LogRetentionConfig config = new LogRetentionConfig();
        config.maxAgeDays = 14;
        config.maxTotalMb = 512;
        config.maxFilesPerAgent = 25;
        config.archiveEnabled = false;
        config.coldRetentionDays = 7;
        config.save();

        assertTrue(LogRetentionConfig.configFile().isFile(), "config file written");

        LogRetentionConfig loaded = LogRetentionConfig.load();
        assertEquals(14, loaded.maxAgeDays);
        assertEquals(512, loaded.maxTotalMb);
        assertEquals(25, loaded.maxFilesPerAgent);
        assertFalse(loaded.archiveEnabled);
        assertEquals(7, loaded.coldRetentionDays);
    }

    @Test
    void toPolicyMapsAllFields() {
        LogRetentionConfig config = new LogRetentionConfig();
        config.maxAgeDays = 5;
        config.maxTotalMb = 1;
        config.maxFilesPerAgent = 3;
        config.archiveEnabled = true;
        config.coldRetentionDays = 10;

        LogRetentionPolicy policy = config.toPolicy();
        assertEquals(5, policy.maxAge().toDays());
        assertEquals(1L * 1024 * 1024, policy.maxTotalBytes());
        assertEquals(3, policy.maxFilesPerAgent());
        assertTrue(policy.archiveEnabled());
        assertEquals(10, policy.coldRetention().toDays());
    }
}
