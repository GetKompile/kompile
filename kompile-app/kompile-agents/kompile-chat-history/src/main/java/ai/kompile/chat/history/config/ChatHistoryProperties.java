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

package ai.kompile.chat.history.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for chat history.
 */
@Data
@Component
@ConfigurationProperties(prefix = "kompile.chat.history")
public class ChatHistoryProperties {

    /**
     * Enable or disable chat history feature.
     */
    private boolean enabled = true;

    /**
     * Database type: h2, postgres, mysql, etc.
     */
    private String databaseType = "h2";

    /**
     * Path for H2 database file (when using embedded H2).
     */
    private String h2DatabasePath = "./data/chat-history";

    /**
     * JDBC URL (overrides H2 settings when specified).
     */
    private String jdbcUrl;

    /**
     * JDBC username.
     */
    private String jdbcUsername;

    /**
     * JDBC password.
     */
    private String jdbcPassword;

    /**
     * JDBC driver class name.
     */
    private String jdbcDriverClassName;

    /**
     * Maximum number of sessions to retain per user (0 = unlimited).
     */
    private int maxSessionsPerUser = 0;

    /**
     * Maximum age of sessions in days before cleanup (0 = no cleanup).
     */
    private int maxSessionAgeDays = 0;

    /**
     * Enable H2 console for debugging.
     */
    private boolean h2ConsoleEnabled = false;

    /**
     * Path to CLI conversations directory. Defaults to ~/.kompile/conversations.
     */
    private String cliConversationsPath;

    /**
     * Enable CLI transcript sync features.
     */
    private boolean cliSyncEnabled = true;

    /**
     * Interval in milliseconds between CLI transcript sync runs. Default 5 minutes.
     */
    private long cliSyncIntervalMs = 300000;

    /**
     * Maximum number of sessions to import per source per sync cycle.
     */
    private int cliSyncBatchSize = 50;

    /**
     * Enable scheduled retention cleanup of Kompile transcript .txt files.
     */
    private boolean cleanupEnabled = true;

    /**
     * Interval in milliseconds between retention cleanup runs. Default 1 hour.
     */
    private long cleanupIntervalMs = 3600000;

    /**
     * Delete transcripts older than this (days). 0 disables age-based cleanup.
     */
    private long cleanupMaxAgeDays = 90;

    /**
     * Cap total transcript directory size (MB). 0 disables size-based cleanup.
     */
    private long cleanupMaxTotalMb = 2048;

    /**
     * Maximum number of transcripts to keep (newest first). 0 disables count-based cleanup.
     */
    private int cleanupMaxPerSource = 1000;
}
