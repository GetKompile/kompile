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

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * User-editable retention policy, persisted at {@code <kompile-home>/config/log-retention.json}.
 *
 * <p>When the file is absent or unreadable, {@link #load()} returns a config with the same
 * defaults as {@link LogRetentionPolicy#DEFAULT}. All fields are optional on read: missing
 * values keep their constructor default, so a partial file is tolerated.
 */
public final class LogRetentionConfig {

    private static final Logger log = LoggerFactory.getLogger(LogRetentionConfig.class);
    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    public long maxAgeDays = 30;
    public long maxTotalMb = 2048;
    public int maxFilesPerAgent = 100;
    public boolean archiveEnabled = true;
    public long coldRetentionDays = 90;

    public LogRetentionPolicy toPolicy() {
        return LogRetentionPolicy.of(maxAgeDays, maxTotalMb, maxFilesPerAgent,
                archiveEnabled, coldRetentionDays);
    }

    /** {@code <kompile-home>/config/log-retention.json} */
    public static File configFile() {
        return new File(KompileHome.configDirectory(), "log-retention.json");
    }

    public static LogRetentionConfig load() {
        File file = configFile();
        if (!file.isFile()) {
            return new LogRetentionConfig();
        }
        try {
            LogRetentionConfig config = MAPPER.readValue(file, LogRetentionConfig.class);
            return config != null ? config : new LogRetentionConfig();
        } catch (IOException e) {
            log.warn("Failed to read {}: {}", file, e.getMessage());
            return new LogRetentionConfig();
        }
    }

    public void save() {
        File file = configFile();
        try {
            File parent = file.getParentFile();
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }
            Files.writeString(file.toPath(),
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to write {}: {}", file, e.getMessage());
        }
    }
}
