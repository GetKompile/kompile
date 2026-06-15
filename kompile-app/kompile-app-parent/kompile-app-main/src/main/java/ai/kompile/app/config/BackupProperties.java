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
 * limitations under the License.
 */

package ai.kompile.app.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the periodic backup service.
 * <p>
 * Supports backing up H2 databases and Lucene indexes with automatic cleanup.
 * </p>
 *
 * <h3>Example configuration:</h3>
 * <pre>
 * kompile.backup.enabled=true
 * kompile.backup.backup-path=${user.home}/.kompile/backups
 * kompile.backup.fixed-rate-ms=21600000
 * kompile.backup.retention-days=7
 * kompile.backup.format=COMPRESSED
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "kompile.backup")
public class BackupProperties {

    /**
     * Enable or disable the backup service.
     * When disabled, no scheduled or manual backups will be performed.
     */
    private boolean enabled = true;

    /**
     * Base path for backup storage.
     * Default: ~/.kompile/backups
     */
    private String backupPath = System.getProperty("user.home") + "/.kompile/backups";

    /**
     * Fixed rate interval for scheduled backups in milliseconds.
     * Default: 21600000 (6 hours)
     */
    private long fixedRateMs = 21600000;

    /**
     * Number of days to retain backups before automatic cleanup.
     * Default: 7 days
     */
    private int retentionDays = 7;

    /**
     * Backup format: COMPRESSED (tar.gz) or DIRECTORY (plain copy).
     * Default: COMPRESSED
     */
    private BackupFormat format = BackupFormat.COMPRESSED;

    /**
     * Include H2 databases in the backup.
     */
    private boolean includeDatabase = true;

    /**
     * Include Lucene indexes in the backup.
     */
    private boolean includeIndexes = true;

    /**
     * Path to the main orchestrator H2 database (without extension).
     * This is derived from spring.datasource.url.
     */
    private String orchestratorDbPath = "./data/orchestrator-db";

    /**
     * Path to the chat history H2 database (without extension).
     */
    private String chatHistoryDbPath = "./data/chat-history";

    /**
     * Path to the Anserini vector index directory.
     */
    private String vectorIndexPath = System.getProperty("user.home") + "/.kompile/anserini-vector-index";

    /**
     * Path to the text/keyword index directory.
     */
    private String textIndexPath = "./data/index";

    /**
     * Backup format options.
     */
    public enum BackupFormat {
        /**
         * Create a compressed tar.gz archive.
         * Smaller size, portable, but slower to create.
         */
        COMPRESSED,

        /**
         * Create a plain directory copy.
         * Faster to create, larger size, simpler recovery.
         */
        DIRECTORY
    }
}
