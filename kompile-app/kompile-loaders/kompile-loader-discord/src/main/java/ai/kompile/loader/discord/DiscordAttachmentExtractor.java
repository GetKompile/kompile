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

package ai.kompile.loader.discord;

import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.loader.discord.DiscordModels.Attachment;
import ai.kompile.loader.discord.DiscordModels.Message;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.*;

/**
 * Extracts Discord message attachments to temporary files and produces
 * {@link DocumentSourceDescriptor}s suitable for pipeline routing
 * (so the CrawlPipelineRouter can dispatch PDFs to the PDF loader, images to VLM, etc.).
 */
@Slf4j
public class DiscordAttachmentExtractor {

    /**
     * Result of extracting one attachment.
     */
    public record ExtractedAttachment(
            Path tempFile,
            String originalFilename,
            String mimeType,
            long sizeBytes,
            DocumentSourceDescriptor sourceDescriptor
    ) {}

    /**
     * Downloads all attachments from a message and returns descriptors for pipeline routing.
     *
     * @param api          Discord API client for downloading
     * @param message      the parent message
     * @param channelName  channel name for metadata
     * @param guildId      guild ID for metadata
     * @param guildName    guild name for metadata
     * @param collectionName target collection
     * @return list of extracted attachments
     */
    public List<ExtractedAttachment> extractAttachments(DiscordApiService api, Message message,
                                                         String channelName, String guildId,
                                                         String guildName, String collectionName) {
        if (message.attachments() == null || message.attachments().isEmpty()) {
            return List.of();
        }

        List<ExtractedAttachment> results = new ArrayList<>();

        for (Attachment att : message.attachments()) {
            try {
                Path tempFile = api.downloadAttachment(att);

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("discord.attachmentId", att.id());
                metadata.put("discord.attachmentFilename", att.filename());
                metadata.put("discord.attachmentContentType", att.contentType());
                metadata.put("discord.attachmentSize", att.size());
                metadata.put("discord.attachmentUrl", att.url());
                metadata.put("discord.parentMessageId", message.id());
                metadata.put("discord.parentChannelName", channelName);
                metadata.put("discord.guildId", guildId);
                metadata.put("discord.guildName", guildName);

                if (message.author() != null) {
                    metadata.put("discord.parentAuthorId", message.author().id());
                    metadata.put("discord.parentAuthorName", message.author().displayName());
                }
                if (message.timestamp() != null) {
                    metadata.put("discord.parentTimestamp", message.timestamp());
                }

                DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                        .type(DocumentSourceDescriptor.SourceType.FILE)
                        .pathOrUrl(tempFile.toString())
                        .originalFileName(att.filename())
                        .sourceId("discord-att:" + att.id())
                        .collectionName(collectionName)
                        .metadata(metadata)
                        .sizeBytes(att.size())
                        .build();

                results.add(new ExtractedAttachment(
                        tempFile,
                        att.filename(),
                        att.contentType() != null ? att.contentType() : "application/octet-stream",
                        att.size(),
                        descriptor
                ));

                log.debug("Extracted attachment: {} ({} bytes, {})",
                        att.filename(), att.size(), att.contentType());
            } catch (Exception e) {
                log.warn("Failed to download Discord attachment '{}' from message {}: {}",
                        att.filename(), message.id(), e.getMessage());
            }
        }

        return results;
    }
}
