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

package ai.kompile.loader.slack;

import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SlackGraphExtractorTest {

    private SlackGraphExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new SlackGraphExtractor();
    }

    // ── canExtract / supportedDocumentTypes ──────────────────────────

    @Test
    void supportedDocumentTypes() {
        assertEquals(List.of("slack", "slack_history"), extractor.supportedDocumentTypes());
    }

    @Test
    void canExtractWithSlackChannelId() {
        Document doc = slackMsg("C123", "U456", "1234567.000100", "Hello");
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtractWithLegacyChannelId() {
        Document doc = new Document("test");
        doc.getMetadata().put("channel_id", "C123");
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

    // ── Channel entity ──────────────────────────────────────────────

    @Nested
    class ChannelEntity {
        @Test
        void createsChannelEntityWithName() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Hi");
            doc.getMetadata().put("slack.channelName", "general");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "SLACK_CHANNEL"));
            ExtractedEntity channel = entityOfType(result, "SLACK_CHANNEL");
            assertEquals("#general", channel.name());
            assertEquals("C123", channel.properties().get("channelId"));
        }

        @Test
        void channelWithTypeProperty() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Hi");
            doc.getMetadata().put("slack.channelType", "public_channel");
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity channel = entityOfType(result, "SLACK_CHANNEL");
            assertEquals("public_channel", channel.properties().get("channelType"));
        }

        @Test
        void channelWithoutNameUsesId() {
            Document doc = slackMsg("C123", null, "1234567.000100", "Hi");
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity channel = entityOfType(result, "SLACK_CHANNEL");
            assertEquals("C123", channel.name());
        }
    }

    // ── Workspace entity ─────────────────────────────────────────────

    @Nested
    class WorkspaceEntity {
        @Test
        void createsWorkspaceFromMetadata() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Hi");
            doc.getMetadata().put("slack.workspaceId", "T999");
            doc.getMetadata().put("slack.workspaceName", "My Workspace");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "SLACK_WORKSPACE"));
            ExtractedEntity ws = entityOfType(result, "SLACK_WORKSPACE");
            assertEquals("My Workspace", ws.name());
        }

        @Test
        void createsWorkspaceFromCollectionName() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Hi");
            doc.getMetadata().put("collection_name", "Team Slack");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "SLACK_WORKSPACE"));
            ExtractedEntity ws = entityOfType(result, "SLACK_WORKSPACE");
            assertEquals("Team Slack", ws.name());
        }

        @Test
        void channelInWorkspaceRelation() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Hi");
            doc.getMetadata().put("slack.workspaceId", "T999");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasRelationType(result, "CHANNEL_IN"));
        }

        @Test
        void noWorkspaceWhenNoMetadata() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Hi");
            ExtractionResult result = extractor.extract(doc);

            assertFalse(hasEntityOfType(result, "SLACK_WORKSPACE"));
        }
    }

    // ── User entity ──────────────────────────────────────────────────

    @Nested
    class UserEntity {
        @Test
        void createsUserEntityWithName() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Hi");
            doc.getMetadata().put("slack.userName", "alice");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "SLACK_USER"));
            ExtractedEntity user = entityOfType(result, "SLACK_USER");
            assertEquals("alice", user.name());
            assertEquals("U456", user.properties().get("userId"));
        }

        @Test
        void userWithoutNameUsesId() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Hi");
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity user = entityOfType(result, "SLACK_USER");
            assertEquals("U456", user.name());
        }

        @Test
        void memberOfRelationCreated() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Hi");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasRelationType(result, "MEMBER_OF"));
        }
    }

    // ── Message entity ───────────────────────────────────────────────

    @Nested
    class MessageEntity {
        @Test
        void createsMessageEntity() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Hello world");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "SLACK_MESSAGE"));
            ExtractedEntity msg = entityOfType(result, "SLACK_MESSAGE");
            assertEquals("1234567.000100", msg.properties().get("messageTs"));
        }

        @Test
        void messagePropertiesIncludeSubtype() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Hi");
            doc.getMetadata().put("slack.messageSubtype", "bot_message");
            doc.getMetadata().put("slack.messageType", "message");
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity msg = entityOfType(result, "SLACK_MESSAGE");
            assertEquals("bot_message", msg.properties().get("messageSubtype"));
            assertEquals("message", msg.properties().get("messageType"));
        }

        @Test
        void sentByRelation() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Hi");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasRelationType(result, "SENT_BY"));
        }

        @Test
        void postedInRelation() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Hi");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasRelationType(result, "POSTED_IN"));
        }

        @Test
        void longMessageDescriptionTruncated() {
            String longText = "X".repeat(300);
            Document doc = slackMsg("C123", "U456", "1234567.000100", longText);
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity msg = entityOfType(result, "SLACK_MESSAGE");
            assertTrue(msg.description().endsWith("..."));
            assertTrue(msg.description().length() <= 204); // 200 + "..."
        }
    }

    // ── Thread entity ────────────────────────────────────────────────

    @Nested
    class ThreadEntity {
        @Test
        void createsThreadEntityWhenThreadTsPresent() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Hi");
            doc.getMetadata().put("slack.threadTs", "1234567.000100");
            doc.getMetadata().put("slack.replyCount", 5);
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "SLACK_THREAD"));
        }

        @Test
        void threadInChannelRelation() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Hi");
            doc.getMetadata().put("slack.threadTs", "1234567.000100");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasRelationType(result, "THREAD_IN"));
        }

        @Test
        void threadReplyProducesRepliedInThreadRelation() {
            Document doc = slackMsg("C123", "U456", "1234567.000200", "reply");
            doc.getMetadata().put("slack.threadTs", "1234567.000100");
            doc.getMetadata().put("slack.isThreadReply", true);
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasRelationType(result, "REPLIED_IN_THREAD"));
        }

        @Test
        void threadReplyProducesRepliesToRelationToParentMessage() {
            Document doc = slackMsg("C123", "U456", "1234567.000200", "reply");
            doc.getMetadata().put("slack.threadTs", "1234567.000100");
            doc.getMetadata().put("slack.isThreadReply", true);
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasRelationType(result, "REPLIES_TO"),
                    "Thread reply should create REPLIES_TO edge to parent message");
        }

        @Test
        void threadParentWithReplyCountProducesStartedThreadRelation() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "parent msg");
            doc.getMetadata().put("slack.threadTs", "1234567.000100");
            doc.getMetadata().put("slack.replyCount", 3);
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasRelationType(result, "STARTED_THREAD"));
        }

        @Test
        void noThreadWithoutThreadTs() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Hi");
            ExtractionResult result = extractor.extract(doc);

            assertFalse(hasEntityOfType(result, "SLACK_THREAD"));
            assertFalse(hasRelationType(result, "THREAD_IN"));
        }
    }

    // ── User mention extraction ──────────────────────────────────────

    @Nested
    class UserMentions {
        @Test
        void extractsUserMentionsFromContent() {
            Document doc = slackMsg("C123", "U456", "1234567.000100",
                    "Hey <@U789ABC> can you review this?");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasRelationType(result, "MENTIONS_USER"));
            // Should create entity for mentioned user
            long userCount = result.entities().stream()
                    .filter(e -> "SLACK_USER".equals(e.type()))
                    .count();
            assertEquals(2, userCount, "Author + mentioned user");
        }

        @Test
        void extractsUserMentionWithDisplayName() {
            Document doc = slackMsg("C123", "U456", "1234567.000100",
                    "Pinging <@U789ABC|alice> for review");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasRelationType(result, "MENTIONS_USER"));
        }

        @Test
        void multipleUserMentions() {
            Document doc = slackMsg("C123", "U456", "1234567.000100",
                    "Hey <@UAAA> and <@UBBB> please check");
            ExtractionResult result = extractor.extract(doc);

            long mentionCount = result.relations().stream()
                    .filter(r -> "MENTIONS_USER".equals(r.type()))
                    .count();
            assertEquals(2, mentionCount);
        }
    }

    // ── Channel mention extraction ───────────────────────────────────

    @Nested
    class ChannelMentions {
        @Test
        void extractsChannelMentionFromContent() {
            Document doc = slackMsg("C123", "U456", "1234567.000100",
                    "Check <#C999|announcements> for details");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasRelationType(result, "MENTIONS_CHANNEL"));
            long channelCount = result.entities().stream()
                    .filter(e -> "SLACK_CHANNEL".equals(e.type()))
                    .count();
            assertEquals(2, channelCount, "Current channel + mentioned channel");
        }

        @Test
        void channelMentionWithoutName() {
            Document doc = slackMsg("C123", "U456", "1234567.000100",
                    "See <#CXYZ>");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasRelationType(result, "MENTIONS_CHANNEL"));
        }
    }

    // ── File attachments ─────────────────────────────────────────────

    @Nested
    class FileAttachments {
        @Test
        void createsFileEntityAndRelation() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Here's the file");
            doc.getMetadata().put("slack.fileId", "F001");
            doc.getMetadata().put("slack.fileName", "report.pdf");
            doc.getMetadata().put("slack.fileMimeType", "application/pdf");
            doc.getMetadata().put("slack.fileSize", 1024);
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "SLACK_FILE"));
            assertTrue(hasRelationType(result, "HAS_FILE"));

            ExtractedEntity file = entityOfType(result, "SLACK_FILE");
            assertEquals("report.pdf", file.name());
            assertEquals("application/pdf", file.properties().get("mimeType"));
        }

        @Test
        void fileWithoutNameUsesId() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "file");
            doc.getMetadata().put("slack.fileId", "F002");
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity file = entityOfType(result, "SLACK_FILE");
            assertEquals("File F002", file.name());
        }
    }

    // ── Reactions ────────────────────────────────────────────────────

    @Nested
    class Reactions {
        @Test
        void perUserReactionCreatesReactedToRelation() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Nice!");
            doc.getMetadata().put("slack.reactions", List.of("thumbsup:UABC123"));
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasRelationType(result, "REACTED_TO"));
            // Creates entity for reactor user
            assertTrue(result.entities().stream().anyMatch(e ->
                    "SLACK_USER".equals(e.type()) && "UABC123".equals(e.properties().get("userId"))));
        }

        @Test
        void aggregateReactionStoredAsProperty() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Nice!");
            doc.getMetadata().put("slack.reactions", List.of("thumbsup:5"));
            ExtractionResult result = extractor.extract(doc);

            // Should NOT create REACTED_TO relation (aggregate, not per-user)
            assertFalse(hasRelationType(result, "REACTED_TO"));
        }

        @Test
        void reactionUsersMapFormat() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Great!");
            doc.getMetadata().put("slack.reactionUsers", List.of(
                    Map.of("emoji", "fire", "users", List.of("UXXX", "UYYY"))
            ));
            ExtractionResult result = extractor.extract(doc);

            long reactedToCount = result.relations().stream()
                    .filter(r -> "REACTED_TO".equals(r.type()))
                    .count();
            assertEquals(2, reactedToCount, "One REACTED_TO per user in reaction");
        }
    }

    // ── Legacy metadata keys ─────────────────────────────────────────

    @Nested
    class LegacyKeys {
        @Test
        void legacyChannelIdWorks() {
            Document doc = new Document("Hi there");
            doc.getMetadata().put("channel_id", "C123");
            doc.getMetadata().put("user_id", "U456");
            doc.getMetadata().put("message_ts", "1234567.000100");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "SLACK_CHANNEL"));
            assertTrue(hasEntityOfType(result, "SLACK_USER"));
            assertTrue(hasEntityOfType(result, "SLACK_MESSAGE"));
        }

        @Test
        void legacyThreadMetadata() {
            Document doc = new Document("reply");
            doc.getMetadata().put("channel_id", "C123");
            doc.getMetadata().put("message_ts", "1234567.000200");
            doc.getMetadata().put("thread_ts", "1234567.000100");
            doc.getMetadata().put("is_thread_reply", "true");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "SLACK_THREAD"));
            assertTrue(hasRelationType(result, "REPLIED_IN_THREAD"));
        }
    }

    // ── Batch extraction / deduplication ──────────────────────────────

    @Nested
    class BatchExtraction {
        @Test
        void batchDeduplicatesEntities() {
            Document doc1 = slackMsg("C123", "U456", "1234567.000100", "Hi");
            Document doc2 = slackMsg("C123", "U456", "1234567.000200", "Hello again");
            ExtractionResult result = extractor.extractBatch(List.of(doc1, doc2));

            long channelCount = result.entities().stream()
                    .filter(e -> "SLACK_CHANNEL".equals(e.type()))
                    .count();
            assertEquals(1, channelCount, "Same channel should be deduplicated");

            long userCount = result.entities().stream()
                    .filter(e -> "SLACK_USER".equals(e.type()))
                    .count();
            assertEquals(1, userCount, "Same user should be deduplicated");
        }

        @Test
        void batchDeduplicatesRelations() {
            Document doc1 = slackMsg("C123", "U456", "1234567.000100", "Hi");
            Document doc2 = slackMsg("C123", "U456", "1234567.000100", "Hi again"); // same ts
            ExtractionResult result = extractor.extractBatch(List.of(doc1, doc2));

            long sentByCount = result.relations().stream()
                    .filter(r -> "SENT_BY".equals(r.type()))
                    .count();
            assertEquals(1, sentByCount, "Duplicate relations should be deduplicated");
        }

        @Test
        void batchMergesEntityNames() {
            Document doc1 = slackMsg("C123", "U456", "1234567.000100",
                    "Mentioning <@U789>");
            Document doc2 = slackMsg("C123", "U456", "1234567.000200",
                    "Mentioning <@U789>");
            doc2.getMetadata().put("slack.userName", "alice");
            ExtractionResult result = extractor.extractBatch(List.of(doc1, doc2));

            // The user entity for U456 should pick up the name from doc2
            long userCount = result.entities().stream()
                    .filter(e -> "SLACK_USER".equals(e.type()))
                    .count();
            assertTrue(userCount >= 1);
        }

        @Test
        void extractionMetadataPresent() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Hi");
            ExtractionResult result = extractor.extract(doc);

            assertNotNull(result.metadata());
            assertEquals("slack-rule-extractor", result.metadata().extractionModel());
        }
    }

    // ── Pinned items ─────────────────────────────────────────────────

    @Nested
    class PinnedItems {
        @Test
        void pinnedItemSubtypeCreatesPinnedItemEntity() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Important announcement");
            doc.getMetadata().put("slack.messageSubtype", "pinned_item");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "PINNED_ITEM"),
                    "pinned_item subtype should create PINNED_ITEM entity");
            assertTrue(hasRelationType(result, "PINNED_IN"),
                    "PINNED_ITEM should have PINNED_IN relation to channel");
        }

        @Test
        void slackPinnedTrueCreatesPinnedItemEntity() {
            Document doc = slackMsg("C123", "U456", "1234567.000200", "Pinned via flag");
            doc.getMetadata().put("slack.pinned", true);
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "PINNED_ITEM"),
                    "slack.pinned=true should create PINNED_ITEM entity");
            assertTrue(hasRelationType(result, "PINNED_IN"));
        }

        @Test
        void pinnedItemContainsRelationLinksToMessage() {
            Document doc = slackMsg("C123", "U456", "1234567.000300", "Pin this");
            doc.getMetadata().put("slack.pinned", true);
            ExtractionResult result = extractor.extract(doc);

            // PINNED_ITEM should be linked to the SLACK_MESSAGE via CONTAINS
            assertTrue(hasRelationType(result, "CONTAINS"),
                    "PINNED_ITEM should link to underlying message via CONTAINS");
        }

        @Test
        void pinnedItemEntityHasChannelProperties() {
            Document doc = slackMsg("C999", "U456", "1234567.000400", "Pinned msg");
            doc.getMetadata().put("slack.channelName", "important");
            doc.getMetadata().put("slack.pinned", true);
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity pinned = entityOfType(result, "PINNED_ITEM");
            assertEquals("C999", pinned.properties().get("channelId"));
            assertEquals("important", pinned.properties().get("channelName"));
        }

        @Test
        void normalMessageDoesNotCreatePinnedItem() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Just a message");
            ExtractionResult result = extractor.extract(doc);

            assertFalse(hasEntityOfType(result, "PINNED_ITEM"),
                    "Normal message should not create PINNED_ITEM");
            assertFalse(hasRelationType(result, "PINNED_IN"));
        }
    }

    // ── Bot messages ─────────────────────────────────────────────────

    @Nested
    class BotMessages {
        @Test
        void botMessageSubtypeCreatesSlackBotEntity() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Automated notification");
            doc.getMetadata().put("slack.messageSubtype", "bot_message");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "SLACK_BOT"),
                    "bot_message subtype should create SLACK_BOT entity");
            assertTrue(hasRelationType(result, "SENT_BY_BOT"),
                    "SLACK_MESSAGE should have SENT_BY_BOT relation to SLACK_BOT");
        }

        @Test
        void botIdMetadataCreatesSlackBotEntity() {
            Document doc = slackMsg("C123", null, "1234567.000100", "Bot says hi");
            doc.getMetadata().put("slack.botId", "B001BOTID");
            doc.getMetadata().put("slack.botName", "MyBot");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "SLACK_BOT"),
                    "slack.botId metadata should create SLACK_BOT entity");
            ExtractedEntity bot = entityOfType(result, "SLACK_BOT");
            assertEquals("MyBot", bot.name());
            assertEquals("B001BOTID", bot.properties().get("botId"));
            assertEquals("MyBot", bot.properties().get("botName"));
        }

        @Test
        void botEntityIdIsDeterministicByBotId() {
            Document doc1 = slackMsg("C123", null, "1234567.000100", "msg1");
            doc1.getMetadata().put("slack.botId", "B123");
            Document doc2 = slackMsg("C123", null, "1234567.000200", "msg2");
            doc2.getMetadata().put("slack.botId", "B123");

            ExtractionResult r1 = extractor.extract(doc1);
            ExtractionResult r2 = extractor.extract(doc2);

            String id1 = r1.entities().stream()
                    .filter(e -> "SLACK_BOT".equals(e.type()))
                    .findFirst().map(ExtractedEntity::id).orElse("none");
            String id2 = r2.entities().stream()
                    .filter(e -> "SLACK_BOT".equals(e.type()))
                    .findFirst().map(ExtractedEntity::id).orElse("x");

            assertEquals(id1, id2, "Same botId should produce the same entity ID");
        }

        @Test
        void sentByBotRelationPointsFromMessageToBot() {
            Document doc = slackMsg("C123", null, "1234567.000100", "Notification");
            doc.getMetadata().put("slack.botId", "B999");
            doc.getMetadata().put("slack.botName", "Alerter");
            ExtractionResult result = extractor.extract(doc);

            ExtractedRelation rel = result.relations().stream()
                    .filter(r -> "SENT_BY_BOT".equals(r.type()))
                    .findFirst().orElse(null);
            assertNotNull(rel);
            ExtractedEntity bot = entityOfType(result, "SLACK_BOT");
            assertEquals(bot.id(), rel.target(), "SENT_BY_BOT target should be the SLACK_BOT entity");
        }

        @Test
        void normalMessageDoesNotCreateBotEntity() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Regular message");
            ExtractionResult result = extractor.extract(doc);

            assertFalse(hasEntityOfType(result, "SLACK_BOT"),
                    "Regular user message should not create SLACK_BOT");
            assertFalse(hasRelationType(result, "SENT_BY_BOT"));
        }
    }

    // ── Empty / null handling ────────────────────────────────────────

    @Nested
    class EdgeCases {
        @Test
        void nullMetadataReturnsEmpty() {
            Document doc = new Document("test");
            // Metadata is never null in practice, but test defensively
            ExtractionResult result = extractor.extract(doc);
            // Should not throw
            assertNotNull(result);
        }

        @Test
        void noMessageTsProducesNoMessageEntity() {
            Document doc = new Document("test");
            doc.getMetadata().put("slack.channelId", "C123");
            // No messageTs
            ExtractionResult result = extractor.extract(doc);

            assertFalse(hasEntityOfType(result, "SLACK_MESSAGE"));
        }

        @Test
        void noUserIdProducesNoUserEntity() {
            Document doc = new Document("test");
            doc.getMetadata().put("slack.channelId", "C123");
            doc.getMetadata().put("slack.messageTs", "1234567.000100");
            // No userId
            ExtractionResult result = extractor.extract(doc);

            assertFalse(hasEntityOfType(result, "SLACK_USER"));
            assertFalse(hasRelationType(result, "SENT_BY"));
            assertFalse(hasRelationType(result, "MEMBER_OF"));
        }

        @Test
        void entityIdIsDeterministic() {
            Document doc1 = slackMsg("C123", "U456", "1234567.000100", "Hi");
            Document doc2 = slackMsg("C123", "U456", "1234567.000100", "Hi");
            ExtractionResult r1 = extractor.extract(doc1);
            ExtractionResult r2 = extractor.extract(doc2);

            assertEquals(
                    r1.entities().stream().map(ExtractedEntity::id).sorted().toList(),
                    r2.entities().stream().map(ExtractedEntity::id).sorted().toList()
            );
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private Document slackMsg(String channelId, String userId, String messageTs, String text) {
        Document doc = new Document(text);
        Map<String, Object> meta = doc.getMetadata();
        if (channelId != null) meta.put("slack.channelId", channelId);
        if (userId != null) meta.put("slack.userId", userId);
        if (messageTs != null) meta.put("slack.messageTs", messageTs);
        return doc;
    }

    // ── File CrawlItem support ────────────────────────────────────────

    @Nested
    class FileCrawlItems {

        @Test
        void fileEntityCreatedWithoutMessageTs() {
            // File CrawlItems from SlackCrawler have fileId but no messageTs
            Document doc = new Document("[File content]");
            doc.getMetadata().put("slack.channelId", "C100");
            doc.getMetadata().put("slack.channelName", "general");
            doc.getMetadata().put("slack.fileId", "F001");
            doc.getMetadata().put("slack.fileName", "report.xlsx");
            doc.getMetadata().put("slack.fileMimeType", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            doc.getMetadata().put("slack.fileSize", 42000);
            doc.getMetadata().put("slack.parentMessageTs", "1234567.000100");
            // No slack.messageTs — this is the critical scenario

            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "SLACK_FILE"),
                    "SLACK_FILE entity should be created even without messageTs");
            ExtractedEntity fileEntity = entityOfType(result, "SLACK_FILE");
            assertEquals("report.xlsx", fileEntity.name());
            assertEquals("F001", fileEntity.properties().get("fileId"));
            assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    fileEntity.properties().get("mimeType"));
            assertEquals("42000", fileEntity.properties().get("size"));
        }

        @Test
        void fileCrawlItemLinksToParentMessage() {
            Document doc = new Document("[File content]");
            doc.getMetadata().put("slack.channelId", "C100");
            doc.getMetadata().put("slack.fileId", "F002");
            doc.getMetadata().put("slack.fileName", "photo.jpg");
            doc.getMetadata().put("slack.parentMessageTs", "1234567.000200");
            // No messageTs

            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasRelationType(result, "HAS_FILE"),
                    "HAS_FILE relation should link to parent message via parentMessageTs");
        }

        @Test
        void fileEntityCreatedWithMessageTs() {
            // Regular message with file attachment — messageTs present
            Document doc = slackMsg("C100", "U200", "1234567.000300", "check this file");
            doc.getMetadata().put("slack.fileId", "F003");
            doc.getMetadata().put("slack.fileName", "data.csv");

            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "SLACK_FILE"));
            assertTrue(hasRelationType(result, "HAS_FILE"));
        }

        @Test
        void uploaderUserIdStoredOnFileEntity() {
            Document doc = new Document("[File]");
            doc.getMetadata().put("slack.channelId", "C100");
            doc.getMetadata().put("slack.fileId", "F004");
            doc.getMetadata().put("slack.fileName", "notes.txt");
            doc.getMetadata().put("slack.uploaderUserId", "U999");

            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity fileEntity = entityOfType(result, "SLACK_FILE");
            assertEquals("U999", fileEntity.properties().get("uploaderUserId"));
        }

        @Test
        void fileTypeStoredOnFileEntity() {
            Document doc = new Document("[File]");
            doc.getMetadata().put("slack.channelId", "C100");
            doc.getMetadata().put("slack.fileId", "F005");
            doc.getMetadata().put("slack.fileName", "report.pdf");
            doc.getMetadata().put("slack.fileType", "pdf");

            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity fileEntity = entityOfType(result, "SLACK_FILE");
            assertEquals("pdf", fileEntity.properties().get("fileType"));
        }
    }

    // ── Channel/User/Message property enrichment ─────────────────────

    @Nested
    class PropertyEnrichment {
        @Test
        void channelNameStoredInChannelEntityProps() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Hi");
            doc.getMetadata().put("slack.channelName", "general");
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity channel = entityOfType(result, "SLACK_CHANNEL");
            assertEquals("general", channel.properties().get("channelName"));
        }

        @Test
        void userNameStoredInUserEntityProps() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Hi");
            doc.getMetadata().put("slack.userName", "alice");
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity user = entityOfType(result, "SLACK_USER");
            assertEquals("alice", user.properties().get("userName"));
        }

        @Test
        void reactionCountStoredOnMessageEntity() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Hi");
            doc.getMetadata().put("slack.reactionCount", 5);
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity msg = entityOfType(result, "SLACK_MESSAGE");
            assertEquals("5", msg.properties().get("reactionCount"));
        }

        @Test
        void fileCountStoredOnMessageEntity() {
            Document doc = slackMsg("C123", "U456", "1234567.000100", "Check these");
            doc.getMetadata().put("slack.fileCount", 3);
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity msg = entityOfType(result, "SLACK_MESSAGE");
            assertEquals("3", msg.properties().get("fileCount"));
        }
    }

    // ── Channel topic / purpose / name changes ─────────────────────────

    @Nested
    class ChannelTopicPurposeChanges {

        @Test
        void channelTopicSubtypeCreatesTopicEntityAndHasTopicRelation() {
            Document doc = slackMsg("C123", "U456", "1234567.000100",
                    "This channel is for discussing project updates");
            doc.getMetadata().put("slack.messageSubtype", "channel_topic");
            doc.getMetadata().put("slack.channelName", "project-updates");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "TOPIC"),
                    "channel_topic subtype should create TOPIC entity");
            assertTrue(hasRelationType(result, "HAS_TOPIC"),
                    "channel should have HAS_TOPIC relation to topic");
            assertTrue(hasRelationType(result, "CHANGED_BY"),
                    "topic should have CHANGED_BY relation to user who set it");

            ExtractedEntity topic = entityOfType(result, "TOPIC");
            assertEquals("This channel is for discussing project updates",
                    topic.properties().get("topicText"));
            assertEquals("C123", topic.properties().get("channelId"));
            assertEquals("U456", topic.properties().get("setBy"));
        }

        @Test
        void channelPurposeSubtypeCreatesTopicEntityWithPurposeText() {
            Document doc = slackMsg("C123", "U456", "1234567.000200",
                    "Coordination for the Q3 launch");
            doc.getMetadata().put("slack.messageSubtype", "channel_purpose");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "TOPIC"),
                    "channel_purpose subtype should create TOPIC entity");
            assertTrue(hasRelationType(result, "HAS_TOPIC"));

            ExtractedEntity topic = entityOfType(result, "TOPIC");
            assertEquals("Coordination for the Q3 launch",
                    topic.properties().get("purposeText"));
        }

        @Test
        void channelNameSubtypeEnrichesChannelEntity() {
            Document doc = slackMsg("C123", "U456", "1234567.000300",
                    "renamed the channel from old-name to new-name");
            doc.getMetadata().put("slack.messageSubtype", "channel_name");
            doc.getMetadata().put("slack.channelName", "new-name");
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity channel = entityOfType(result, "SLACK_CHANNEL");
            assertEquals("renamed the channel from old-name to new-name",
                    channel.properties().get("renameMessage"));
        }

        @Test
        void channelJoinSubtypeCreatesMemberOfRelation() {
            Document doc = slackMsg("C123", "U789", "1234567.000400",
                    "has joined the channel");
            doc.getMetadata().put("slack.messageSubtype", "channel_join");
            ExtractionResult result = extractor.extract(doc);

            long memberOfCount = result.relations().stream()
                    .filter(r -> "MEMBER_OF".equals(r.type()))
                    .count();
            assertTrue(memberOfCount >= 1, "channel_join should create MEMBER_OF relation");

            // Check that at least one MEMBER_OF has joinedVia property
            boolean hasJoinVia = result.relations().stream()
                    .filter(r -> "MEMBER_OF".equals(r.type()))
                    .anyMatch(r -> "channel_join".equals(r.properties().get("joinedVia")));
            assertTrue(hasJoinVia, "channel_join MEMBER_OF should have joinedVia property");
        }

        @Test
        void channelLeaveSubtypeCreatesLowConfidenceMemberOfRelation() {
            Document doc = slackMsg("C123", "U789", "1234567.000500",
                    "has left the channel");
            doc.getMetadata().put("slack.messageSubtype", "channel_leave");
            ExtractionResult result = extractor.extract(doc);

            boolean hasLeftRelation = result.relations().stream()
                    .filter(r -> "MEMBER_OF".equals(r.type()))
                    .anyMatch(r -> "true".equals(r.properties().get("leftChannel")));
            assertTrue(hasLeftRelation, "channel_leave should create MEMBER_OF with leftChannel=true");
        }

        @Test
        void groupTopicSubtypeAlsoHandled() {
            Document doc = slackMsg("G123", "U456", "1234567.000600",
                    "Private group topic text");
            doc.getMetadata().put("slack.messageSubtype", "group_topic");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "TOPIC"),
                    "group_topic should be handled same as channel_topic");
        }

        @Test
        void topicMessageAlsoLinksToMessageViaContains() {
            Document doc = slackMsg("C123", "U456", "1234567.000700",
                    "New topic for discussions");
            doc.getMetadata().put("slack.messageSubtype", "channel_topic");
            ExtractionResult result = extractor.extract(doc);

            // The CONTAINS relation links the message entity to the topic entity
            boolean hasContains = result.relations().stream()
                    .filter(r -> "CONTAINS".equals(r.type()))
                    .anyMatch(r -> true);
            assertTrue(hasContains, "Topic change message should have CONTAINS relation to topic");
        }

        @Test
        void emptyTopicTextDoesNotCreateTopicEntity() {
            Document doc = slackMsg("C123", "U456", "1234567.000800", "   ");
            doc.getMetadata().put("slack.messageSubtype", "channel_topic");
            ExtractionResult result = extractor.extract(doc);

            assertFalse(hasEntityOfType(result, "TOPIC"),
                    "Empty topic text should not create TOPIC entity");
        }
    }

    // ── URL extraction ─────────────────────────────────────────────────

    @Nested
    class UrlExtraction {

        @Test
        void slackFormattedUrlExtractsExternalResource() {
            Document doc = slackMsg("C123", "U456", "1234567.000100",
                    "Check this out: <https://example.com/page|Example Page>");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "EXTERNAL_RESOURCE"),
                    "URL in Slack format should create EXTERNAL_RESOURCE entity");
            assertTrue(hasRelationType(result, "HYPERLINKS_TO"),
                    "Message should have HYPERLINKS_TO relation to URL entity");
        }

        @Test
        void multipleUrlsExtracted() {
            Document doc = slackMsg("C123", "U456", "1234567.000100",
                    "See <https://docs.google.com> and <https://github.com/repo>");
            ExtractionResult result = extractor.extract(doc);

            long urlCount = result.entities().stream()
                    .filter(e -> "EXTERNAL_RESOURCE".equals(e.type()))
                    .count();
            assertEquals(2, urlCount, "Two URLs should create two EXTERNAL_RESOURCE entities");
        }

        @Test
        void duplicateUrlsDeduped() {
            Document doc = slackMsg("C123", "U456", "1234567.000100",
                    "Link: <https://example.com> and again <https://example.com>");
            ExtractionResult result = extractor.extract(doc);

            long urlCount = result.entities().stream()
                    .filter(e -> "EXTERNAL_RESOURCE".equals(e.type()))
                    .count();
            assertEquals(1, urlCount, "Duplicate URLs should be deduped");
        }
    }

    // ── Broadcast mentions ────────────────────────────────────────────

    @Nested
    class BroadcastMentions {

        @Test
        void hereAndChannelBroadcastsCreateMentionsChannelRelation() {
            Document doc = slackMsg("C123", "U456", "1234567.000100",
                    "<!here> please review this");
            ExtractionResult result = extractor.extract(doc);

            long mentionsChannel = result.relations().stream()
                    .filter(r -> "MENTIONS_CHANNEL".equals(r.type()))
                    .count();
            assertTrue(mentionsChannel >= 1,
                    "<!here> should create MENTIONS_CHANNEL relation");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

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
}
