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

package ai.kompile.chat.history.service;

import ai.kompile.chat.history.config.ChatHistoryProperties;
import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.chat.aggregate.ChatTranscriptRetention;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Scheduled retention cleanup for {@code ~/.kompile/conversations/*.txt}.
 *
 * <p>Runs on a fixed interval controlled by
 * {@code kompile.chat.history.cleanup-interval-ms} (default 1 hour). Caps are
 * read from the same properties class at each run so they can be changed via
 * {@code application.properties} without restarting.</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "kompile.chat.history", name = "cleanup-enabled", havingValue = "true", matchIfMissing = true)
public class CliTranscriptRetentionService {

    private final ChatHistoryProperties properties;

    public CliTranscriptRetentionService(ChatHistoryProperties properties) {
        this.properties = properties;
    }

    @Scheduled(fixedRateString = "${kompile.chat.history.cleanup-interval-ms:3600000}",
            initialDelayString = "${kompile.chat.history.cleanup-interval-ms:3600000}")
    public void scheduledCleanup() {
        ChatTranscriptRetention.Result result = runCleanup(false);
        if (result.totalDeleted() > 0) {
            log.info("Scheduled transcript cleanup: deleted {} (age={}, count={}, size={})",
                    result.totalDeleted(), result.deletedByAge(),
                    result.deletedByCount(), result.deletedBySize());
        } else {
            log.debug("Scheduled transcript cleanup: no deletions");
        }
    }

    public ChatTranscriptRetention.Result runCleanup(boolean dryRun) {
        return runCleanup(properties.getCleanupMaxAgeDays(),
                properties.getCleanupMaxTotalMb(),
                properties.getCleanupMaxPerSource(),
                dryRun);
    }

    public ChatTranscriptRetention.Result runCleanup(long maxAgeDays, long maxTotalMb,
                                                     int maxPerSource, boolean dryRun) {
        File dir = conversationsDir();
        ChatTranscriptRetention.Policy policy = ChatTranscriptRetention.Policy.of(
                maxAgeDays, maxTotalMb, maxPerSource);
        return new ChatTranscriptRetention(policy).apply(dir, dryRun);
    }

    private File conversationsDir() {
        String override = properties.getCliConversationsPath();
        Path dir;
        if (override != null && !override.isBlank()) {
            dir = Paths.get(override);
        } else {
            dir = KompileHome.homeDirectory().toPath().resolve("conversations");
        }
        return dir.toFile();
    }
}
