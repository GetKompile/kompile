/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.project.LocalSubprocessWatchdog;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H1/H2 (audit items #9 and C4): connector-sourced messages (Slack/Discord/IMAP) materialized to
 * markdown by the local crawl pipeline are re-extracted into local knowledge-graph structure --
 * deterministically, with no LLM/network calls -- and a materialized message's attachments are
 * linked to their own DOCUMENT nodes with HAS_ATTACHMENT edges. {@code request == null} exercises
 * the real {@link LocalProjectGraphBackend#updateCrawlGraph} path (not a hand-built graph) while
 * keeping every LLM/native-chat/semantic-extraction branch short-circuited off, per {@link
 * LocalProjectGraphBackend#preflightNativeChat} and the extraction-request factories.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ConnectorGraphExtractionTest {
    @TempDir
    Path projectRoot;

    private ObjectMapper mapper;
    private String previousAdmissionMode;

    @BeforeEach
    void setUp() {
        previousAdmissionMode = System.getProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY);
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY, "off");
        mapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        if (previousAdmissionMode == null) {
            System.clearProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY);
        } else {
            System.setProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY, previousAdmissionMode);
        }
    }

    @Test
    void materializedConnectorMessagesProduceExpectedGraphStructureAndAttachmentEdge() throws Exception {
        String knowledgeBaseId = "connector-kb";
        Path directory = Files.createDirectories(projectRoot.resolve("data/crawls/" + knowledgeBaseId));
        Path markdownDir = Files.createDirectories(projectRoot.resolve("data/markdown/" + knowledgeBaseId));

        Files.writeString(markdownDir.resolve("msg-slack-1.md"), """
                <!-- kompile-source-type: slack -->
                <!-- kompile-source-metadata: {} -->
                Deploying the new build now, see the attached report.
                """, StandardCharsets.UTF_8);
        Files.writeString(markdownDir.resolve("msg-discord-1.md"), """
                <!-- kompile-source-type: discord -->
                <!-- kompile-source-metadata: {} -->
                Build is green on engineering.
                """, StandardCharsets.UTF_8);
        Files.writeString(markdownDir.resolve("msg-email-1.md"), """
                <!-- kompile-source-type: email -->
                <!-- kompile-source-metadata: {} -->
                Deploy status: green.
                """, StandardCharsets.UTF_8);

        Files.writeString(directory.resolve("documents.jsonl"), """
                {"documentId":"msg-slack-1","source":"slack://channel/C100/message/1700000000.000100","title":"Slack message","loader":"external-materialized","markdownPath":"data/markdown/connector-kb/msg-slack-1.md","attachments":[{"fileName":"report.pdf","path":"attachments/msg-slack-1/report.pdf","contentType":"application/pdf","size":1024}],"loaderOutputs":[{"index":0,"title":"Slack message","metadata":{"slack.channelId":"C100","slack.channelName":"general","slack.userId":"U200","slack.userName":"alice","slack.messageTs":"1700000000.000100"}}]}
                {"documentId":"att-1","relativePath":"data/markdown/connector-kb/attachments/msg-slack-1/report.pdf","title":"report.pdf","loader":"pdf"}
                {"documentId":"msg-discord-1","source":"discord://G300/C400/M500","title":"Discord message","loader":"external-materialized","markdownPath":"data/markdown/connector-kb/msg-discord-1.md","loaderOutputs":[{"index":0,"title":"Discord message","metadata":{"discord.guildId":"G300","discord.guildName":"Kompile Community","discord.channelId":"C400","discord.channelName":"engineering","discord.authorId":"U600","discord.authorName":"bob","discord.messageId":"M500","discord.timestamp":"2026-09-01T12:00:00Z"}}]}
                {"documentId":"msg-email-1","source":"imap://inbox/msg-1","title":"Email message","loader":"external-materialized","markdownPath":"data/markdown/connector-kb/msg-email-1.md","loaderOutputs":[{"index":0,"title":"Email message","metadata":{"email.from":"Alice <alice@example.com>","email.to":"Bob <bob@example.com>","email.subject":"Deploy status","email.date":"2026-09-01T12:05:00Z","email.messageId":"<msg-1@example.com>"}}]}
                """, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("chunks.jsonl"), """
                {"documentId":"msg-slack-1","chunkId":"msg-slack-1-0","index":0,"text":"Deploying the new build now, see the attached report.","sourceMetadata":{"slack.channelName":"general","slack.userName":"alice"}}
                {"documentId":"msg-discord-1","chunkId":"msg-discord-1-0","index":0,"text":"Build is green on engineering."}
                {"documentId":"msg-email-1","chunkId":"msg-email-1-0","index":0,"text":"Deploy status: green."}
                """, StandardCharsets.UTF_8);

        LocalProjectGraphBackend backend = new LocalProjectGraphBackend(mapper);
        LocalProjectGraphBackend.GraphUpdate update = backend.updateCrawlGraph(projectRoot,
                knowledgeBaseId, "Connector KB", null, "proj-1", List.of(), "crawl-1", null);
        assertTrue(Files.isRegularFile(update.graphPath()), "graph.kgraph must be written");

        UnifiedGraph graph = UnifiedGraph.load(update.graphPath());
        assertNull(graph.meta().get("connectorGraphExtractionError"),
                "connector extraction must not fail the crawl: " + graph.meta().get("connectorGraphExtractionError"));

        // -- Slack: channel/user/message entities and their relations --
        GraphEntity slackChannel = findEntity(graph, "SLACK_CHANNEL", "channelId", "C100");
        GraphEntity slackUser = findEntity(graph, "SLACK_USER", "userId", "U200");
        GraphEntity slackMessage = findEntity(graph, "SLACK_MESSAGE", "messageTs", "1700000000.000100");
        assertTrue(hasRelation(graph, slackMessage.id(), slackUser.id(), "SENT_BY"));
        assertTrue(hasRelation(graph, slackMessage.id(), slackChannel.id(), "POSTED_IN"));
        assertTrue(hasRelation(graph, slackUser.id(), slackChannel.id(), "MEMBER_OF"));

        // -- Discord: server/channel/user/message entities and their relations --
        GraphEntity discordServer = findEntity(graph, "DISCORD_SERVER", "guildId", "G300");
        GraphEntity discordChannel = findEntity(graph, "DISCORD_CHANNEL", "channelId", "C400");
        GraphEntity discordUser = findEntity(graph, "DISCORD_USER", "userId", "U600");
        GraphEntity discordMessage = findEntity(graph, "DISCORD_MESSAGE", "messageId", "M500");
        assertTrue(hasRelation(graph, discordMessage.id(), discordUser.id(), "SENT_BY"));
        assertTrue(hasRelation(graph, discordMessage.id(), discordChannel.id(), "POSTED_IN"));
        assertTrue(hasRelation(graph, discordUser.id(), discordServer.id(), "MEMBER_OF"));
        assertTrue(hasRelation(graph, discordChannel.id(), discordServer.id(), "CHANNEL_IN"));

        // -- Email: message + person entities and SENT_BY/SENT_TO --
        GraphEntity emailMessage = findEntity(graph, "EMAIL_MESSAGE", "messageId", "<msg-1@example.com>");
        GraphEntity fromPerson = graph.entities().stream()
                .filter(e -> "PERSON".equals(e.type()))
                .filter(e -> hasRelation(graph, emailMessage.id(), e.id(), "SENT_BY"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No SENT_BY PERSON found for the email message"));
        assertTrue(fromPerson.label() != null && fromPerson.label().contains("Alice"), fromPerson.label());
        GraphEntity toPerson = graph.entities().stream()
                .filter(e -> "PERSON".equals(e.type()))
                .filter(e -> hasRelation(graph, emailMessage.id(), e.id(), "SENT_TO"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No SENT_TO PERSON found for the email message"));
        assertTrue(toPerson.label() != null && toPerson.label().contains("Bob"), toPerson.label());

        // -- H2/C4: HAS_ATTACHMENT from the Slack message's DOCUMENT node to the attachment's --
        GraphEntity messageDocument = findDocument(graph, "msg-slack-1");
        GraphEntity attachmentDocument = findDocument(graph, "att-1");
        assertTrue(hasRelation(graph, messageDocument.id(), attachmentDocument.id(), "HAS_ATTACHMENT"),
                "expected HAS_ATTACHMENT from the Slack message document to the attachment document");

        // -- H1: every connector entity is linked back to the DOCUMENT node it came from --
        assertTrue(hasRelation(graph, messageDocument.id(), slackMessage.id(), "CONTAINS_ENTITY"));

        // -- H3 (second half): a nested sourceMetadata object on a chunks.jsonl row must not break
        // jsonAttributes for the CHUNK node it becomes.
        GraphEntity slackChunk = graph.entities().stream()
                .filter(e -> "CHUNK".equals(e.type()))
                .filter(e -> "msg-slack-1-0".equals(e.attributes().get("chunkId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Slack chunk entity missing"));
        Object sourceMetadata = slackChunk.attributes().get("sourceMetadata");
        assertTrue(sourceMetadata instanceof Map<?, ?>, "sourceMetadata must survive as a nested map: " + sourceMetadata);
        assertEquals("general", ((Map<?, ?>) sourceMetadata).get("slack.channelName"));
    }

    @Test
    void plainFileOnlyCrawlIsUnaffectedByConnectorExtraction() throws Exception {
        String knowledgeBaseId = "plain-kb";
        Path directory = Files.createDirectories(projectRoot.resolve("data/crawls/" + knowledgeBaseId));
        Files.writeString(directory.resolve("documents.jsonl"), """
                {"documentId":"readme","relativePath":"README.md","title":"README","loader":"markdown","source":"README.md"}
                """, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("chunks.jsonl"), """
                {"documentId":"readme","chunkId":"readme-0","index":0,"text":"Plain local file, no connector involved."}
                """, StandardCharsets.UTF_8);

        LocalProjectGraphBackend backend = new LocalProjectGraphBackend(mapper);
        LocalProjectGraphBackend.GraphUpdate update = backend.updateCrawlGraph(projectRoot,
                knowledgeBaseId, "Plain KB", null, "proj-1", List.of(), "crawl-2", null);

        UnifiedGraph graph = UnifiedGraph.load(update.graphPath());
        assertNull(graph.meta().get("connectorGraphExtractionError"));
        assertNull(graph.meta().get("connectorGraphExtractionWarnings"));
        assertFalse(graph.entities().stream()
                .anyMatch(e -> "local-crawl:connector-extraction".equals(e.attributes().get("provenance"))),
                "a crawl with no external-materialized rows must add zero connector entities");
        assertFalse(graph.relations().stream()
                .anyMatch(r -> "local-crawl:connector-extraction".equals(r.attributes().get("provenance"))
                        || "local-crawl:server-mode-attachment".equals(r.attributes().get("provenance"))),
                "a crawl with no external-materialized rows must add zero connector relations");
    }

    @Test
    void serverModeAttachmentLinksByOpaqueParentSourcePath() throws Exception {
        String knowledgeBaseId = "server-mode-kb";
        Path directory = Files.createDirectories(projectRoot.resolve("data/crawls/" + knowledgeBaseId));
        Files.writeString(directory.resolve("documents.jsonl"), """
                {"documentId":"msg-parent-1","source_path":"imap://inbox/999","title":"Parent email","loader":"mail"}
                {"documentId":"att-server-1","parent_source_path":"imap://inbox/999","source_path":"imap://inbox/999#attachment/1","title":"invoice.pdf","loader":"pdf"}
                """, StandardCharsets.UTF_8);

        LocalProjectGraphBackend backend = new LocalProjectGraphBackend(mapper);
        LocalProjectGraphBackend.GraphUpdate update = backend.updateCrawlGraph(projectRoot,
                knowledgeBaseId, "Server Mode KB", null, "proj-1", List.of(), "crawl-3", null);

        UnifiedGraph graph = UnifiedGraph.load(update.graphPath());
        GraphEntity parentDocument = findDocument(graph, "msg-parent-1");
        GraphEntity attachmentDocument = findDocument(graph, "att-server-1");
        assertTrue(hasRelation(graph, parentDocument.id(), attachmentDocument.id(), "HAS_ATTACHMENT"),
                "server-mode attachment must be linked via the opaque parent_source_path back-reference");
    }

    private static GraphEntity findEntity(UnifiedGraph graph, String type, String attributeKey, String attributeValue) {
        Optional<GraphEntity> match = graph.entities().stream()
                .filter(e -> type.equals(e.type()))
                .filter(e -> attributeValue.equals(String.valueOf(e.attributes().get(attributeKey))))
                .findFirst();
        return match.orElseThrow(() -> new AssertionError("No " + type + " entity with " + attributeKey
                + "=" + attributeValue + " found among: " + graph.entities().stream()
                .map(e -> e.type() + ":" + e.attributes()).toList()));
    }

    private static GraphEntity findDocument(UnifiedGraph graph, String documentId) {
        return graph.entities().stream()
                .filter(e -> "DOCUMENT".equals(e.type()))
                .filter(e -> documentId.equals(e.attributes().get("documentId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No DOCUMENT entity for documentId=" + documentId));
    }

    private static boolean hasRelation(UnifiedGraph graph, String sourceId, String targetId, String type) {
        return graph.relations().stream().anyMatch(r ->
                type.equals(r.type()) && sourceId.equals(r.sourceId()) && targetId.equals(r.targetId()));
    }
}
