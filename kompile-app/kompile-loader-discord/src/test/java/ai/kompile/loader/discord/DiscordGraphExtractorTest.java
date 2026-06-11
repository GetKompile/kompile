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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.loader.discord;

import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DiscordGraphExtractorTest {

    private DiscordGraphExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new DiscordGraphExtractor();
    }

    // ── canExtract / supportedDocumentTypes ──────────────────────────

    @Test
    void supportedDocumentTypes() {
        assertEquals(List.of("discord", "discord_message", "discord_history", "discord_attachment"), extractor.supportedDocumentTypes());
    }

    @Test
    void canExtractWithGuildId() {
        Document doc = discordMsg("G100", "C200", "M300", "U400", "Hello");
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtractWithChannelIdOnly() {
        Document doc = new Document("test");
        doc.getMetadata().put("discord.channelId", "C200");
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void cannotExtractNull() {
        assertFalse(extractor.canExtract(null));
    }

    @Test
    void cannotExtractUnrelatedDoc() {
        Document doc = new Document("test");
        doc.getMetadata().put("source_type", "url");
        assertFalse(extractor.canExtract(doc));
    }

    // ── Server entity ────────────────────────────────────────────────

    @Nested
    class ServerEntity {
        @Test
        void createsServerEntityWithName() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "Hi");
            doc.getMetadata().put("discord.guildName", "My Server");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "DISCORD_SERVER"));
            ExtractedEntity server = entityOfType(result, "DISCORD_SERVER");
            assertEquals("My Server", server.name());
            assertEquals("G100", server.properties().get("guildId"));
        }

        @Test
        void serverWithoutNameUsesId() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "Hi");
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity server = entityOfType(result, "DISCORD_SERVER");
            assertEquals("G100", server.name());
        }

        @Test
        void noServerWithoutGuildId() {
            Document doc = new Document("test");
            doc.getMetadata().put("discord.channelId", "C200");
            doc.getMetadata().put("discord.messageId", "M300");
            ExtractionResult result = extractor.extract(doc);

            assertFalse(hasEntityOfType(result, "DISCORD_SERVER"));
        }

        @Test
        void serverEntityIncludesGuildDescription() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "Hi");
            doc.getMetadata().put("discord.guildName", "My Server");
            doc.getMetadata().put("discord.guildDescription", "A cool server for testing");
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity server = entityOfType(result, "DISCORD_SERVER");
            assertEquals("A cool server for testing", server.properties().get("description"));
        }

        @Test
        void serverEntityIncludesMemberCount() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "Hi");
            doc.getMetadata().put("discord.guildName", "My Server");
            doc.getMetadata().put("discord.guildMemberCount", 42);
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity server = entityOfType(result, "DISCORD_SERVER");
            assertEquals("42", server.properties().get("memberCount"));
        }
    }

    // ── Channel entity ───────────────────────────────────────────────

    @Nested
    class ChannelEntity {
        @Test
        void createsChannelEntityWithName() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "Hi");
            doc.getMetadata().put("discord.channelName", "general");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "DISCORD_CHANNEL"));
            ExtractedEntity channel = entityOfType(result, "DISCORD_CHANNEL");
            assertEquals("#general", channel.name());
        }

        @Test
        void channelTypeProperty() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "Hi");
            doc.getMetadata().put("discord.channelType", "GUILD_TEXT");
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity channel = entityOfType(result, "DISCORD_CHANNEL");
            assertEquals("GUILD_TEXT", channel.properties().get("channelType"));
        }

        @Test
        void channelInServerRelation() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "Hi");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasRelationType(result, "CHANNEL_IN"));
        }
    }

    // ── Thread entity ────────────────────────────────────────────────

    @Nested
    class ThreadEntity {
        @Test
        void threadCreatesDiscordThreadType() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "Hi");
            doc.getMetadata().put("discord.isThread", true);
            doc.getMetadata().put("discord.channelName", "help-thread");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "DISCORD_THREAD"));
            ExtractedEntity thread = entityOfType(result, "DISCORD_THREAD");
            assertEquals("#help-thread", thread.name());
        }

        @Test
        void threadInParentChannelRelation() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "Hi");
            doc.getMetadata().put("discord.isThread", true);
            doc.getMetadata().put("discord.parentChannelId", "C100");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasRelationType(result, "THREAD_IN"));
        }

        @Test
        void noThreadInWithoutParentChannelId() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "Hi");
            doc.getMetadata().put("discord.isThread", true);
            // No parentChannelId
            ExtractionResult result = extractor.extract(doc);

            assertFalse(hasRelationType(result, "THREAD_IN"));
        }

        @Test
        void threadMetadataCapturedOnThreadEntity() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "Hi");
            doc.getMetadata().put("discord.isThread", true);
            doc.getMetadata().put("discord.channelName", "help-thread");
            doc.getMetadata().put("discord.parentChannelId", "C100");
            doc.getMetadata().put("discord.threadArchived", false);
            doc.getMetadata().put("discord.threadAutoArchiveDuration", 1440);
            doc.getMetadata().put("discord.threadArchiveTimestamp", "2025-06-01T12:00:00Z");
            doc.getMetadata().put("discord.threadLocked", true);
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity thread = entityOfType(result, "DISCORD_THREAD");
            assertEquals("false", thread.properties().get("archived"),
                    "archived flag should be captured");
            assertEquals("1440", thread.properties().get("autoArchiveDuration"),
                    "autoArchiveDuration should be captured");
            assertEquals("2025-06-01T12:00:00Z", thread.properties().get("archiveTimestamp"),
                    "archiveTimestamp should be captured");
            assertEquals("true", thread.properties().get("locked"),
                    "locked flag should be captured");
        }

        @Test
        void threadMetadataNotPresentWhenAbsent() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "Hi");
            doc.getMetadata().put("discord.isThread", true);
            // No thread metadata keys
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity thread = entityOfType(result, "DISCORD_THREAD");
            assertNull(thread.properties().get("archived"),
                    "archived should not appear when metadata is absent");
            assertNull(thread.properties().get("autoArchiveDuration"),
                    "autoArchiveDuration should not appear when metadata is absent");
            assertNull(thread.properties().get("locked"),
                    "locked should not appear when metadata is absent");
        }

        @Test
        void threadMetadataNotStoredOnChannel() {
            // Channel docs should NOT get thread-specific metadata fields
            Document doc = discordMsg("G100", "C200", "M300", "U400", "Hi");
            doc.getMetadata().put("discord.channelTopic", "General discussion");
            // isThread is NOT set
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity channel = entityOfType(result, "DISCORD_CHANNEL");
            assertNull(channel.properties().get("archived"),
                    "Channel should not have thread-only 'archived' property");
            assertNull(channel.properties().get("locked"),
                    "Channel should not have thread-only 'locked' property");
        }
    }

    // ── Channel topic ─────────────────────────────────────────────────

    @Nested
    class ChannelTopic {
        @Test
        void channelTopicCapturedAsProperty() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "Hi");
            doc.getMetadata().put("discord.channelName", "general");
            doc.getMetadata().put("discord.channelTopic", "Welcome to #general! Read the rules.");
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity channel = entityOfType(result, "DISCORD_CHANNEL");
            assertEquals("Welcome to #general! Read the rules.", channel.properties().get("topic"),
                    "Channel topic should be stored as 'topic' property");
        }

        @Test
        void channelWithoutTopicHasNoTopicProperty() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "Hi");
            // No discord.channelTopic
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity channel = entityOfType(result, "DISCORD_CHANNEL");
            assertNull(channel.properties().get("topic"),
                    "topic property should be absent when channelTopic is not set");
        }

        @Test
        void channelMessageCountCapturedAsProperty() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "Hi");
            doc.getMetadata().put("discord.channelMessageCount", 42);
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity channel = entityOfType(result, "DISCORD_CHANNEL");
            assertEquals("42", channel.properties().get("messageCount"),
                    "messageCount should be stored as a string property");
        }
    }

    // ── User entity ──────────────────────────────────────────────────

    @Nested
    class UserEntity {
        @Test
        void createsUserEntityWithName() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "Hi");
            doc.getMetadata().put("discord.authorName", "Alice");
            doc.getMetadata().put("discord.authorUsername", "alice#1234");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "DISCORD_USER"));
            ExtractedEntity user = entityOfType(result, "DISCORD_USER");
            assertEquals("Alice", user.name());
            assertTrue(user.aliases().contains("alice#1234"));
        }

        @Test
        void botUserProperty() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "Hi");
            doc.getMetadata().put("discord.authorIsBot", true);
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity bot = entityOfType(result, "DISCORD_BOT");
            assertEquals("true", bot.properties().get("isBot"));
            // Bot messages should use SENT_BY_BOT relation
            assertTrue(hasRelationType(result, "SENT_BY_BOT"));
        }

        @Test
        void memberOfServerRelation() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "Hi");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasRelationType(result, "MEMBER_OF"));
        }
    }

    // ── Message entity ───────────────────────────────────────────────

    @Nested
    class MessageEntity {
        @Test
        void createsMessageEntity() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "Hello world");
            doc.getMetadata().put("discord.timestamp", "2025-01-15T12:00:00Z");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "DISCORD_MESSAGE"));
            ExtractedEntity msg = entityOfType(result, "DISCORD_MESSAGE");
            assertEquals("M300", msg.properties().get("messageId"));
            assertEquals("2025-01-15T12:00:00Z", msg.properties().get("timestamp"));
        }

        @Test
        void editedTimestampAndPinnedProperties() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "edited");
            doc.getMetadata().put("discord.editedTimestamp", "2025-01-15T13:00:00Z");
            doc.getMetadata().put("discord.pinned", true);
            doc.getMetadata().put("discord.messageType", "DEFAULT");
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity msg = entityOfType(result, "DISCORD_MESSAGE");
            assertEquals("2025-01-15T13:00:00Z", msg.properties().get("editedTimestamp"));
            assertEquals("true", msg.properties().get("pinned"));
            assertEquals("DEFAULT", msg.properties().get("messageType"));
        }

        @Test
        void sentByRelation() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "Hi");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasRelationType(result, "SENT_BY"));
        }

        @Test
        void postedInRelation() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "Hi");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasRelationType(result, "POSTED_IN"));
        }

        @Test
        void longDescriptionTruncated() {
            String longText = "Y".repeat(300);
            Document doc = discordMsg("G100", "C200", "M300", "U400", longText);
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity msg = entityOfType(result, "DISCORD_MESSAGE");
            assertTrue(msg.description().endsWith("..."));
            assertTrue(msg.description().length() <= 204);
        }
    }

    // ── Reply relations ──────────────────────────────────────────────

    @Nested
    class ReplyRelations {
        @Test
        void repliedToRelation() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "replying");
            doc.getMetadata().put("discord.replyToMessageId", "M200");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasRelationType(result, "REPLIED_TO"));
            // Stub entity for referenced message
            assertTrue(result.entities().stream().anyMatch(e ->
                    "DISCORD_MESSAGE".equals(e.type()) && "M200".equals(e.properties().get("messageId"))));
        }

        @Test
        void crossChannelReplyCreatesChannelStub() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "replying");
            doc.getMetadata().put("discord.replyToMessageId", "M200");
            doc.getMetadata().put("discord.replyToChannelId", "C999");
            ExtractionResult result = extractor.extract(doc);

            // Should create stub channel for cross-channel ref
            long channelCount = result.entities().stream()
                    .filter(e -> "DISCORD_CHANNEL".equals(e.type()))
                    .count();
            // Original channel C200 + stub for C999
            assertEquals(2, channelCount);
        }

        @Test
        void sameChannelReplyNoExtraChannelStub() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "replying");
            doc.getMetadata().put("discord.replyToMessageId", "M200");
            doc.getMetadata().put("discord.replyToChannelId", "C200"); // same channel
            ExtractionResult result = extractor.extract(doc);

            long channelCount = result.entities().stream()
                    .filter(e -> "DISCORD_CHANNEL".equals(e.type()))
                    .count();
            assertEquals(1, channelCount, "Same channel should not create an extra stub");
        }
    }

    // ── Attachments ──────────────────────────────────────────────────

    @Nested
    class Attachments {
        @Test
        void createsAttachmentEntityAndRelation() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "image");
            doc.getMetadata().put("discord.attachments", List.of(
                    Map.of("id", "A001", "filename", "photo.png", "contentType", "image/png", "size", 2048)
            ));
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "DISCORD_ATTACHMENT"));
            assertTrue(hasRelationType(result, "HAS_ATTACHMENT"));

            ExtractedEntity att = entityOfType(result, "DISCORD_ATTACHMENT");
            assertEquals("photo.png", att.name());
            assertEquals("image/png", att.properties().get("contentType"));
        }

        @Test
        void multipleAttachments() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "files");
            doc.getMetadata().put("discord.attachments", List.of(
                    Map.of("id", "A001", "filename", "a.txt"),
                    Map.of("id", "A002", "filename", "b.txt")
            ));
            ExtractionResult result = extractor.extract(doc);

            long attCount = result.entities().stream()
                    .filter(e -> "DISCORD_ATTACHMENT".equals(e.type()))
                    .count();
            assertEquals(2, attCount);
        }

        @Test
        void attachmentWithoutIdSkipped() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "file");
            doc.getMetadata().put("discord.attachments", List.of(
                    Map.of("filename", "orphan.txt") // no id
            ));
            ExtractionResult result = extractor.extract(doc);

            assertFalse(hasEntityOfType(result, "DISCORD_ATTACHMENT"));
        }

        @Test
        void attachmentWithoutNameUsesId() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "file");
            doc.getMetadata().put("discord.attachments", List.of(
                    Map.of("id", "A001")
            ));
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity att = entityOfType(result, "DISCORD_ATTACHMENT");
            assertEquals("Attachment A001", att.name());
        }

        @Test
        void attachmentCapturesUrlAndDimensions() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "image");
            doc.getMetadata().put("discord.attachments", List.of(
                    Map.of("id", "A001", "filename", "photo.png", "contentType", "image/png",
                            "size", 4096, "url", "https://cdn.discord.com/A001/photo.png",
                            "width", 1920, "height", 1080)
            ));
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity att = entityOfType(result, "DISCORD_ATTACHMENT");
            assertEquals("https://cdn.discord.com/A001/photo.png", att.properties().get("url"));
            assertEquals("1920", att.properties().get("width"));
            assertEquals("1080", att.properties().get("height"));
        }
    }

    // ── Mentioned users ──────────────────────────────────────────────

    @Nested
    class MentionedUsers {
        @Test
        void createsMentionedUserEntitiesAndRelations() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "tagging people");
            doc.getMetadata().put("discord.mentionUserIds", List.of("U500", "U600"));
            doc.getMetadata().put("discord.mentionUserNames", List.of("Bob", "Carol"));
            ExtractionResult result = extractor.extract(doc);

            long mentionCount = result.relations().stream()
                    .filter(r -> "MENTIONS_USER".equals(r.type()))
                    .count();
            assertEquals(2, mentionCount);

            // Check named user entity
            assertTrue(result.entities().stream().anyMatch(e ->
                    "DISCORD_USER".equals(e.type()) && "Bob".equals(e.name())));
        }

        @Test
        void mentionedUserWithoutNameUsesId() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "tagging");
            doc.getMetadata().put("discord.mentionUserIds", List.of("U500"));
            // No mentionUserNames
            ExtractionResult result = extractor.extract(doc);

            assertTrue(result.entities().stream().anyMatch(e ->
                    "DISCORD_USER".equals(e.type()) && "U500".equals(e.name())));
        }
    }

    // ── Mentioned roles ──────────────────────────────────────────────

    @Nested
    class MentionedRoles {
        @Test
        void createsRoleEntityAndRelation() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "@moderators");
            doc.getMetadata().put("discord.mentionRoleIds", List.of("R001", "R002"));
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "DISCORD_ROLE"));
            long roleCount = result.entities().stream()
                    .filter(e -> "DISCORD_ROLE".equals(e.type()))
                    .count();
            assertEquals(2, roleCount);

            long mentionRoleCount = result.relations().stream()
                    .filter(r -> "MENTIONS_ROLE".equals(r.type()))
                    .count();
            assertEquals(2, mentionRoleCount);
        }

        @Test
        void roleEntityUsesNameWhenProvided() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "@admin");
            doc.getMetadata().put("discord.mentionRoleIds", List.of("R001"));
            doc.getMetadata().put("discord.mentionRoleNames", List.of("Admin"));
            doc.getMetadata().put("discord.mentionRoleColors", List.of(0xFF0000));
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity role = entityOfType(result, "DISCORD_ROLE");
            assertEquals("Admin", role.name());
            assertEquals("Admin", role.properties().get("roleName"));
            assertEquals("#FF0000", role.properties().get("color"));
        }

        @Test
        void roleEntityFallsBackToIdWithoutName() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "@mods");
            doc.getMetadata().put("discord.mentionRoleIds", List.of("R001"));
            // No mentionRoleNames provided
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity role = entityOfType(result, "DISCORD_ROLE");
            assertEquals("Role R001", role.name());
        }
    }

    // ── Reactions ────────────────────────────────────────────────────

    @Nested
    class Reactions {
        @Test
        void unicodeReactionCreatesEntity() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "fun");
            doc.getMetadata().put("discord.reactions", List.of("thumbsup:5"));
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "DISCORD_REACTION"));
            assertTrue(hasRelationType(result, "HAS_REACTION"));

            ExtractedEntity reaction = entityOfType(result, "DISCORD_REACTION");
            assertEquals("thumbsup", reaction.name());
            assertEquals("thumbsup", reaction.properties().get("emojiName"));

            // Check count on the relation
            ExtractedRelation rel = result.relations().stream()
                    .filter(r -> "HAS_REACTION".equals(r.type()))
                    .findFirst().orElseThrow();
            assertEquals("5", rel.properties().get("count"));
        }

        @Test
        void customEmojiReaction() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "fun");
            doc.getMetadata().put("discord.reactions", List.of("pepe:123456:3"));
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity reaction = entityOfType(result, "DISCORD_REACTION");
            assertEquals("pepe:123456", reaction.name());
            assertEquals("pepe", reaction.properties().get("emojiName"));
            assertEquals("123456", reaction.properties().get("emojiId"));
        }

        @Test
        void malformedReactionSkipped() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "fun");
            doc.getMetadata().put("discord.reactions", List.of("badformat", "nocount:abc"));
            ExtractionResult result = extractor.extract(doc);

            assertFalse(hasEntityOfType(result, "DISCORD_REACTION"));
        }

        @Test
        void multipleReactions() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "fun");
            doc.getMetadata().put("discord.reactions", List.of("thumbsup:5", "heart:3"));
            ExtractionResult result = extractor.extract(doc);

            long reactionCount = result.entities().stream()
                    .filter(e -> "DISCORD_REACTION".equals(e.type()))
                    .count();
            assertEquals(2, reactionCount);
        }
    }

    // ── Batch extraction / deduplication ──────────────────────────────

    @Nested
    class BatchExtraction {
        @Test
        void batchDeduplicatesEntities() {
            Document doc1 = discordMsg("G100", "C200", "M300", "U400", "Hi");
            Document doc2 = discordMsg("G100", "C200", "M301", "U400", "Hello");
            ExtractionResult result = extractor.extractBatch(List.of(doc1, doc2));

            long serverCount = result.entities().stream()
                    .filter(e -> "DISCORD_SERVER".equals(e.type()))
                    .count();
            assertEquals(1, serverCount);

            long channelCount = result.entities().stream()
                    .filter(e -> "DISCORD_CHANNEL".equals(e.type()))
                    .count();
            assertEquals(1, channelCount);

            long userCount = result.entities().stream()
                    .filter(e -> "DISCORD_USER".equals(e.type()))
                    .count();
            assertEquals(1, userCount);
        }

        @Test
        void batchDeduplicatesRelations() {
            Document doc1 = discordMsg("G100", "C200", "M300", "U400", "Hi");
            Document doc2 = discordMsg("G100", "C200", "M300", "U400", "Hi"); // same msg
            ExtractionResult result = extractor.extractBatch(List.of(doc1, doc2));

            long sentByCount = result.relations().stream()
                    .filter(r -> "SENT_BY".equals(r.type()))
                    .count();
            assertEquals(1, sentByCount);
        }

        @Test
        void batchMergesEntityProperties() {
            // First doc has author without username, second adds username alias
            Document doc1 = discordMsg("G100", "C200", "M300", "U400", "Hi");
            doc1.getMetadata().put("discord.authorName", "Alice");
            Document doc2 = discordMsg("G100", "C200", "M301", "U400", "Hello");
            doc2.getMetadata().put("discord.authorName", "Alice");
            doc2.getMetadata().put("discord.authorUsername", "alice#1234");
            ExtractionResult result = extractor.extractBatch(List.of(doc1, doc2));

            ExtractedEntity user = result.entities().stream()
                    .filter(e -> "DISCORD_USER".equals(e.type()))
                    .findFirst().orElseThrow();
            assertEquals("Alice", user.name());
            // Aliases merged from both docs
            assertTrue(user.aliases().contains("alice#1234"));
        }

        @Test
        void extractionMetadataPresent() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "Hi");
            ExtractionResult result = extractor.extract(doc);

            assertNotNull(result.metadata());
            assertEquals("discord-rule-extractor", result.metadata().extractionModel());
        }
    }

    // ── Edge cases ───────────────────────────────────────────────────

    @Nested
    class EdgeCases {
        @Test
        void nullMetadataReturnsEmpty() {
            Document doc = new Document("test");
            ExtractionResult result = extractor.extract(doc);
            assertNotNull(result);
        }

        @Test
        void noMessageIdProducesNoMessageEntity() {
            Document doc = new Document("test");
            doc.getMetadata().put("discord.channelId", "C200");
            doc.getMetadata().put("discord.guildId", "G100");
            ExtractionResult result = extractor.extract(doc);

            assertFalse(hasEntityOfType(result, "DISCORD_MESSAGE"));
        }

        @Test
        void entityIdIsDeterministic() {
            Document doc1 = discordMsg("G100", "C200", "M300", "U400", "Hi");
            Document doc2 = discordMsg("G100", "C200", "M300", "U400", "Hi");
            ExtractionResult r1 = extractor.extract(doc1);
            ExtractionResult r2 = extractor.extract(doc2);

            assertEquals(
                    r1.entities().stream().map(ExtractedEntity::id).sorted().toList(),
                    r2.entities().stream().map(ExtractedEntity::id).sorted().toList()
            );
        }

        @Test
        void embedCountStoredAsProperty() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "embed test");
            doc.getMetadata().put("discord.embedCount", 2);
            doc.getMetadata().put("discord.attachmentCount", 1);
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity msg = entityOfType(result, "DISCORD_MESSAGE");
            assertEquals("2", msg.properties().get("embedCount"));
            assertEquals("1", msg.properties().get("attachmentCount"));
        }
    }

    // ── Embed Entities ────────────────────────────────────────────────

    @Nested
    class EmbedEntities {

        @Test
        void extractCreatesEmbedEntityFromStructuredData() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "check this out");
            Map<String, Object> embed = new LinkedHashMap<>();
            embed.put("index", 0);
            embed.put("type", "rich");
            embed.put("title", "GitHub PR #42");
            embed.put("description", "Fix critical bug in authentication flow");
            embed.put("url", "https://github.com/org/repo/pull/42");
            embed.put("authorName", "devbot");
            embed.put("footerText", "GitHub");
            embed.put("fieldCount", 3);
            doc.getMetadata().put("discord.embeds", List.of(embed));

            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "DISCORD_EMBED"),
                    "Should create DISCORD_EMBED entity");
            assertTrue(hasRelationType(result, "HAS_EMBED"),
                    "Should create HAS_EMBED relation");

            ExtractedEntity embedEntity = entityOfType(result, "DISCORD_EMBED");
            assertEquals("GitHub PR #42", embedEntity.name());
            assertEquals("rich", embedEntity.properties().get("embedType"));
            assertEquals("https://github.com/org/repo/pull/42", embedEntity.properties().get("url"));
            assertEquals("devbot", embedEntity.properties().get("authorName"));
            assertEquals("GitHub", embedEntity.properties().get("footerText"));
            assertEquals("3", embedEntity.properties().get("fieldCount"));
        }

        @Test
        void extractCreatesMultipleEmbedEntities() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "two embeds");
            List<Map<String, Object>> embeds = List.of(
                    Map.of("index", 0, "title", "Embed A", "type", "rich"),
                    Map.of("index", 1, "title", "Embed B", "type", "image", "imageUrl", "https://img.example.com/pic.png")
            );
            doc.getMetadata().put("discord.embeds", embeds);

            ExtractionResult result = extractor.extract(doc);

            long embedCount = result.entities().stream()
                    .filter(e -> "DISCORD_EMBED".equals(e.type())).count();
            assertEquals(2, embedCount, "Should create 2 DISCORD_EMBED entities");
            long hasEmbedCount = result.relations().stream()
                    .filter(r -> "HAS_EMBED".equals(r.type())).count();
            assertEquals(2, hasEmbedCount, "Should create 2 HAS_EMBED relations");
        }

        @Test
        void embedEntityUsesTypeAsFallbackName() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "no-title embed");
            Map<String, Object> embed = new LinkedHashMap<>();
            embed.put("index", 0);
            embed.put("type", "video");
            // No title set
            doc.getMetadata().put("discord.embeds", List.of(embed));

            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity embedEntity = entityOfType(result, "DISCORD_EMBED");
            assertEquals("Embed (video)", embedEntity.name());
        }

        @Test
        void embedEntityWithImageAndThumbnail() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "media embed");
            Map<String, Object> embed = new LinkedHashMap<>();
            embed.put("index", 0);
            embed.put("title", "Photo");
            embed.put("imageUrl", "https://cdn.example.com/photo.jpg");
            embed.put("thumbnailUrl", "https://cdn.example.com/thumb.jpg");
            doc.getMetadata().put("discord.embeds", List.of(embed));

            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity embedEntity = entityOfType(result, "DISCORD_EMBED");
            assertEquals("https://cdn.example.com/photo.jpg", embedEntity.properties().get("imageUrl"));
            assertEquals("https://cdn.example.com/thumb.jpg", embedEntity.properties().get("thumbnailUrl"));
        }

        @Test
        void hasEmbedRelationLinksMessageToEmbed() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "link embed");
            doc.getMetadata().put("discord.embeds", List.of(Map.of("index", 0, "title", "Link")));

            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity msgEntity = entityOfType(result, "DISCORD_MESSAGE");
            ExtractedEntity embedEntity = entityOfType(result, "DISCORD_EMBED");
            ExtractedRelation hasEmbed = result.relations().stream()
                    .filter(r -> "HAS_EMBED".equals(r.type()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(msgEntity.id(), hasEmbed.source());
            assertEquals(embedEntity.id(), hasEmbed.target());
        }

        @Test
        void embedDescriptionStoredAsProperty() {
            Document doc = discordMsg("G100", "C200", "M300", "U400", "embed with desc");
            Map<String, Object> embed = new LinkedHashMap<>();
            embed.put("index", 0);
            embed.put("title", "PR Review");
            embed.put("description", "Fixed authentication bug in login flow");
            doc.getMetadata().put("discord.embeds", List.of(embed));

            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity embedEntity = entityOfType(result, "DISCORD_EMBED");
            assertEquals("Fixed authentication bug in login flow",
                    embedEntity.properties().get("description"),
                    "Embed description should be stored as a property");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private Document discordMsg(String guildId, String channelId, String messageId,
                                 String authorId, String text) {
        Document doc = new Document(text);
        Map<String, Object> meta = doc.getMetadata();
        if (guildId != null) meta.put("discord.guildId", guildId);
        if (channelId != null) meta.put("discord.channelId", channelId);
        if (messageId != null) meta.put("discord.messageId", messageId);
        if (authorId != null) meta.put("discord.authorId", authorId);
        return doc;
    }

    private boolean hasEntityOfType(ExtractionResult result, String type) {
        return result.entities().stream().anyMatch(e -> type.equals(e.type()));
    }

    private boolean hasRelationType(ExtractionResult result, String type) {
        return result.relations().stream().anyMatch(r -> type.equals(r.type()));
    }

    private ExtractedEntity entityOfType(ExtractionResult result, String type) {
        return result.entities().stream()
                .filter(e -> type.equals(e.type()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No entity of type " + type));
    }

    // ── Standalone attachment CrawlItem tests ─────────────────────────

    @Nested
    class StandaloneAttachmentCrawlItem {
        @Test
        void standaloneAttachmentCreatesAttachmentEntity() {
            Document doc = new Document("Attachment content");
            Map<String, Object> meta = doc.getMetadata();
            meta.put("discord.guildId", "guild1");
            meta.put("discord.channelId", "chan1");
            // No messageId — this is a standalone attachment doc
            meta.put("discord.attachmentId", "att999");
            meta.put("discord.attachmentFilename", "report.pdf");
            meta.put("discord.attachmentContentType", "application/pdf");
            meta.put("discord.attachmentSize", 1024);
            meta.put("discord.parentMessageId", "msg100");
            meta.put("discord.parentAuthorName", "Alice");

            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "DISCORD_ATTACHMENT"),
                    "Standalone attachment CrawlItem should create DISCORD_ATTACHMENT entity");
            ExtractedEntity att = entityOfType(result, "DISCORD_ATTACHMENT");
            assertEquals("report.pdf", att.name());
            assertEquals("application/pdf", att.properties().get("contentType"));
        }

        @Test
        void standaloneAttachmentLinksToParentMessage() {
            Document doc = new Document("Attachment content");
            Map<String, Object> meta = doc.getMetadata();
            meta.put("discord.guildId", "guild1");
            meta.put("discord.channelId", "chan1");
            meta.put("discord.attachmentId", "att999");
            meta.put("discord.attachmentFilename", "image.png");
            meta.put("discord.parentMessageId", "msg100");

            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasRelationType(result, "HAS_ATTACHMENT"),
                    "Standalone attachment should link to parent message with HAS_ATTACHMENT");
            assertTrue(hasEntityOfType(result, "DISCORD_MESSAGE"),
                    "Parent message stub should be created as DISCORD_MESSAGE entity");
        }

        @Test
        void standaloneAttachmentWithoutParentStillCreatesEntity() {
            Document doc = new Document("Orphan attachment");
            Map<String, Object> meta = doc.getMetadata();
            meta.put("discord.guildId", "guild1");
            meta.put("discord.channelId", "chan1");
            meta.put("discord.attachmentId", "att888");
            meta.put("discord.attachmentFilename", "orphan.txt");
            // No parentMessageId

            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "DISCORD_ATTACHMENT"),
                    "Standalone attachment without parent should still create entity");
            assertFalse(hasRelationType(result, "HAS_ATTACHMENT"),
                    "Without parentMessageId, no HAS_ATTACHMENT relation should exist");
        }
    }
}
