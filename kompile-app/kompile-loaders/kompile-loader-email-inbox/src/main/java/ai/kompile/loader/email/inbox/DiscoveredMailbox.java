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

package ai.kompile.loader.email.inbox;

import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;

import java.nio.file.Path;
import java.util.List;

/**
 * Represents a discovered local email mailbox on the system.
 *
 * @param clientName     the email client (e.g., "Thunderbird", "Outlook", "Apple Mail")
 * @param profileName    the profile or account name (may be null for single-profile clients)
 * @param path           the filesystem path to the mailbox root
 * @param sourceType     the {@link SourceType} that best represents this mailbox format
 * @param folders        discovered folder/mailbox names within this location (may be empty)
 * @param estimatedCount estimated number of messages (-1 if unknown)
 */
public record DiscoveredMailbox(
        String clientName,
        String profileName,
        Path path,
        SourceType sourceType,
        List<String> folders,
        long estimatedCount
) {
    @Override
    public String toString() {
        String profile = profileName != null ? " [" + profileName + "]" : "";
        String count = estimatedCount >= 0 ? " (~" + estimatedCount + " msgs)" : "";
        return clientName + profile + ": " + path + " (" + sourceType + ")" + count;
    }
}
