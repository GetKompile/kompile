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

package ai.kompile.loader.slack;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for Slack loaders.
 */
@Data
@Component
@ConfigurationProperties(prefix = "kompile.slack")
public class SlackLoaderProperties {

    /**
     * Slack Bot OAuth token (xoxb-...).
     * Required for API access.
     */
    private String token;

    /**
     * Default message limit for single channel loads.
     */
    private int defaultLimit = 100;

    /**
     * History configuration.
     */
    private History history = new History();

    @Data
    public static class History {
        /**
         * Whether to include thread replies in history loads.
         */
        private boolean includeThreads = true;

        /**
         * Default number of days to look back when loading history.
         */
        private int defaultDays = 30;

        /**
         * Maximum messages to load per channel (0 = unlimited).
         */
        private int maxMessages = 0;
    }
}
