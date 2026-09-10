/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.chat.sources.ChatSourceRegistry;
import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.main.chat.ChatUiSession;
import ai.kompile.cli.main.chat.format.ConversationExporter;
import ai.kompile.cli.main.chat.format.ConversationReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResumeToolCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void isolatedConstructionNeverCreatesTerminalOrReader() throws Exception {
        try (ChatUiSession owner = new ChatUiSession(); ChatUiSession.Binding ignored = owner.bind()) {
            for (ResumeTool tool : List.of(new ResumeTool(), new ResumeTool(false), new ResumeTool(true))) {
                for (String name : List.of("terminal", "lineReader")) {
                    Field field = ResumeTool.class.getDeclaredField(name);
                    field.setAccessible(true);
                    assertNull(field.get(tool), name + " must remain unallocated under isolated ownership");
                }
                assertIsolatedBrowserRejection(tool.runInteractiveBrowser());
            }
        }
    }

    @Test
    void isolatedToolCallsRejectBrowserBeforeTerminalAccessIncludingCleanup() throws Exception {
        // Tools registered before the owner is bound must use the invocation's owner.
        ResumeTool modelTool = new ResumeTool(true);
        Terminal terminal = failOnUse(Terminal.class);
        LineReader lineReader = failOnUse(LineReader.class);
        ResumeTool existingTerminalTool = new ResumeTool(terminal, lineReader, null, null, null, null);
        ObjectMapper mapper = new ObjectMapper();
        ToolContext context = new ToolContext("isolated-resume", null, null, tempDir, null);
        try (ChatUiSession owner = new ChatUiSession()) {
            try (ChatUiSession.Binding ignored = owner.bind()) {
                // Both /tool resume and model dispatch enter execute; omitted action defaults to browse.
                for (ResumeTool tool : List.of(modelTool, existingTerminalTool)) {
                    for (String json : List.of("{}", "{\"action\":\"browse\"}", "{\"action\":\"BROWSE\"}")) {
                        assertIsolatedBrowserRejection(tool.execute(mapper.readTree(json), context));
                        assertIsolatedBrowserRejection(tool.execute(mapper.readTree(json), null));
                    }
                    assertIsolatedBrowserRejection(tool.runInteractiveBrowser());
                }
            }
            Runnable captured = owner.capture(() ->
                    assertIsolatedBrowserRejection(existingTerminalTool.runInteractiveBrowser()));
            owner.close();
            captured.run(); // A late callback must not regain the physical terminal after disposal.
        }
        // Unbound/legacy mode still enters the original browser rather than being rejected.
        assertFalse(ChatUiSession.current().isIsolated());
        assertThrows(AssertionError.class, existingTerminalTool::runInteractiveBrowser);
    }

    @Test
    void isolatedDataActionsStillListSearchViewResumeAndMigrate() throws Exception {
        String previousHome = System.getProperty("user.home");
        ChatSourceRegistry previousSources = ChatSourceRegistry.getInstance();
        System.setProperty("user.home", tempDir.toString());
        ChatSourceRegistry.setInstance(ChatSourceRegistry.of(List.of()));
        try {
            String sessionId = "isolated-data-session";
            ChatHistory history = new ChatHistory(sessionId);
            history.open("(local)", null, false, tempDir);
            history.logUserMessage("saved isolated conversation");
            history.close();
            ObjectMapper mapper = new ObjectMapper();
            ToolContext context = new ToolContext("data-resume", null, null, tempDir, null);
            ResumeTool tool = new ResumeTool(failOnUse(Terminal.class), failOnUse(LineReader.class),
                    null, null, null, new ConversationReader());
            try (ChatUiSession owner = new ChatUiSession()) {
                // Exercise identical execute paths in legacy and bound isolated ownership.
                for (boolean isolated : List.of(false, true)) {
                    try (ChatUiSession.Binding ignored = isolated ? owner.bind() : null) {
                        for (String action : List.of("search", "recent", "view", "resume",
                                "resume_all", "resume-all", "migrate")) {
                            ObjectNode params = mapper.createObjectNode();
                            params.put("action", action);
                            params.put("session_id", sessionId);
                            params.put("source", "kompile");
                            params.put("output_format", "openai");
                            params.put("compact", false);
                            ToolResult result = tool.execute(params, context);
                            assertFalse(result.isError(), action + ": " + result.getOutput());
                            JsonNode output = mapper.readTree(result.getOutput());
                            switch (action) {
                                case "search", "recent" -> assertEquals(sessionId,
                                        output.path("conversations").get(0).path("session_id").asText());
                                case "resume_all", "resume-all" -> {
                                    assertEquals(1, output.path("restored").asInt());
                                    assertEquals(0, output.path("failed").asInt());
                                }
                                case "view" -> assertTrue(output.path("transcript").asText()
                                        .contains("saved isolated conversation"));
                                case "resume" -> assertEquals(sessionId, output.path("session_id").asText());
                                case "migrate" -> assertTrue(Files.readString(Path.of(
                                        output.path("output_path").asText())).contains("saved isolated conversation"));
                            }
                        }
                        // Migration outputs must not become input sessions for the next listing.
                        Files.deleteIfExists(tempDir.resolve(".kompile/conversations/" + sessionId + "-migrated.openai"));
                    }
                }
            }
        } finally {
            ChatSourceRegistry.setInstance(previousSources);
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }

    private static void assertIsolatedBrowserRejection(ToolResult result) {
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("multi-session chat"), result.getOutput());
        assertTrue(result.getOutput().contains("host owns the physical terminal"), result.getOutput());
    }

    private static <T> T failOnUse(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    throw new AssertionError("Unexpected terminal interaction: " + method.getName());
                }));
    }

    @Test
    void codexResumePlacesBypassFlagBeforeResumeSubcommand() throws Exception {
        ConversationExporter.ExportResult exportResult = new ConversationExporter.ExportResult(
                "resume-session",
                "codex",
                tempDir.resolve("session.jsonl"),
                "codex resume resume-session",
                tempDir);

        List<String> args = buildAgentResumeCommand("codex", exportResult);

        assertTrue(args.contains("--dangerously-bypass-approvals-and-sandbox"));
        assertTrue(args.indexOf("--dangerously-bypass-approvals-and-sandbox") < args.indexOf("resume"));
        assertFalse(args.contains("--full-auto"));
        assertFalse(args.contains("--all"));
    }

    @Test
    void opencodeNativeResumeDoesNotAppendRunOnlyPermissionBypassFlag() throws Exception {
        ConversationExporter.ExportResult exportResult = new ConversationExporter.ExportResult(
                "resume-session",
                "opencode",
                tempDir.resolve("session.json"),
                "opencode --session resume-session",
                tempDir);

        List<String> args = buildAgentResumeCommand("opencode", exportResult);

        assertTrue(args.contains("opencode"));
        assertTrue(args.contains("--session"));
        assertTrue(args.contains("resume-session"));
        assertFalse(args.contains("--dangerously-skip-permissions"),
                "OpenCode TUI resume does not support --dangerously-skip-permissions");
    }

    @Test
    void piResumePlacesExtensionBeforeSessionSelector() throws Exception {
        ConversationExporter.ExportResult exportResult = new ConversationExporter.ExportResult(
                "resume-session",
                "pi",
                tempDir.resolve("session.jsonl"),
                "pi --session resume-session",
                tempDir);

        List<String> args = buildAgentResumeCommand("pi", exportResult);

        assertEquals("pi", args.get(0));
        assertTrue(args.contains("-e"));
        assertTrue(args.contains("--session"));
        assertTrue(args.indexOf("-e") < args.indexOf("--session"));
        assertTrue(args.contains("resume-session"));
        assertFalse(args.contains("--resume"));
    }

    @Test
    void codexResumeUsesStdioMcpEvenWhenSseUrlExists() throws Exception {
        assertNull(resolveResumeMcpSseUrl("codex"));
        assertEquals("stdio", mcpModeForResume("codex", "http://localhost:8080/mcp/sse"));
    }

    @Test
    void nativeCodexResumeUsesPerInvocationMcpOverridesWithoutMutatingGlobalConfig() throws Exception {
        String previousHome = System.getProperty("user.home");
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path home = tempDir.resolve("codex-home");
        Path workDir = tempDir.resolve("codex-work");
        Path configFile = home.resolve(".codex").resolve("config.toml");
        Path binary = Files.createFile(tempDir.resolve("kompile-cli")).toAbsolutePath();
        String originalConfig = "model = \"gpt-5\"\n";
        Files.createDirectories(configFile.getParent());
        Files.createDirectories(workDir);
        Files.writeString(configFile, originalConfig);
        assertTrue(binary.toFile().setExecutable(true));

        try {
            System.setProperty("user.home", home.toString());
            System.setProperty("kompile.cli.binary", binary.toString());
            List<String> command = new ArrayList<>(List.of(
                    "codex", "resume", "resume-session", "-C", workDir.toString()));

            Path injectedSettingsFile =
                    ResumeTool.configureNativeResumeMcp(command, workDir, "codex", null);

            assertNull(injectedSettingsFile);
            assertEquals("codex", command.get(0));
            assertEquals("-c", command.get(1));
            assertEquals("mcp_servers.kompile.command=\"" + binary + "\"", command.get(2));
            assertEquals("-c", command.get(3));
            assertTrue(command.get(4).contains("mcp_servers.kompile.args="));
            assertTrue(command.get(4).contains("\"mcp-stdio\""));
            assertEquals(5, command.indexOf("resume"));
            assertEquals(originalConfig, Files.readString(configFile));
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
            if (previousBinary == null) {
                System.clearProperty("kompile.cli.binary");
            } else {
                System.setProperty("kompile.cli.binary", previousBinary);
            }
        }
    }

    @Test
    void nonCodexResumeUsesSseMcpWhenUrlExists() throws Exception {
        assertEquals("sse", mcpModeForResume("claude", "http://localhost:8080/mcp/sse"));
        assertEquals("stdio", mcpModeForResume("claude", ""));
    }

    @Test
    void kompileChatWizardDisplaysFullTranscriptUuid() {
        String transcriptUuid = "123e4567-e89b-12d3-a456-426614174000";

        assertEquals(transcriptUuid,
                ResumeTool.wizardSessionIdentifier(transcriptUuid, null));
    }

    @Test
    void sessionIdentifierColumnPreservesFullUuidAndCompactsLongProviderIds() {
        String uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        assertEquals(uuid, ResumeTool.fitSessionIdentifier(uuid, 36));

        String longId = "ses_abcdefghijklmnopqrstuvwxyz0123456789";
        String fitted = ResumeTool.fitSessionIdentifier(longId, 20);
        assertEquals(20, fitted.length());
        assertTrue(fitted.startsWith("ses_"));
        assertTrue(fitted.contains("…"));
        assertTrue(fitted.endsWith("456789"));
    }

    @Test
    void kompileSubagentTranscriptsAreExcludedFromResumeListings() {
        assertFalse(ResumeTool.isResumableKompileSession(
                "subagent-codex-12345678-1234-1234-1234-123456789abc"));
        assertFalse(ResumeTool.isResumableKompileSession(
                "SUBAGENT-claude-12345678-1234-1234-1234-123456789abc"));
        assertFalse(ResumeTool.isResumableKompileSession(
                "test-logsubagent-12345678-1234-1234-1234-123456789abc"));
        assertTrue(ResumeTool.isResumableKompileSession("passthrough-codex-wrapper"));
        assertTrue(ResumeTool.isResumableKompileSession("ordinary-session"));
        assertFalse(ResumeTool.isResumableKompileSession(null));
    }

    @Test
    void localProviderTabsExcludeSyntheticKompileWrappers() {
        assertTrue(ResumeTool.isProviderBackedSyntheticWrapper("emulated-legacy", "claude"));
        assertTrue(ResumeTool.isProviderBackedSyntheticWrapper("passthrough-wrapper", "codex"));
        assertTrue(ResumeTool.isProviderBackedSyntheticWrapper("managed-wrapper", "opencode"));
        assertTrue(ResumeTool.isProviderBackedSyntheticWrapper("enforcer-wrapper", "opencode"));
        assertTrue(ResumeTool.isProviderBackedSyntheticWrapper("cli-wrapper", "opencode"));
        assertTrue(ResumeTool.isProviderBackedSyntheticWrapper("MANAGED-wrapper", "qwen"));
        assertFalse(ResumeTool.isProviderBackedSyntheticWrapper("ordinary-session", "claude"));
        assertTrue(ResumeTool.isProviderBackedSyntheticWrapper("managed-enforcer", "pi"));
    }

    @Test
    void resumeWizardAlwaysIncludesExplicitKompileChatTab() {
        List<ResumeTool.ConversationSummary> providerConversations = List.of(
                conversation("claude-session", "Claude", "claude", "claude-code", 1L),
                conversation("codex-session", "Codex", "codex", "codex", 2L),
                conversation("gemini-session", "Gemini", "gemini", "gemini", 3L),
                conversation("opencode-session", "OpenCode", "opencode", "opencode", 4L),
                conversation("pi-session", "Pi", "pi", "pi", 5L),
                conversation("qwen-session", "Qwen", "qwen", "qwen", 6L),
                conversation("agy-session", "Agy", "agy", "agy", 7L),
                conversation("cursor-session", "Cursor", "cursor", "cursor", 8L));

        List<String> tabs = ResumeTool.agentTabs(providerConversations);

        assertEquals(9, tabs.size());
        assertTrue(tabs.contains("kompile"));
        assertEquals("Kompile Chat", ResumeTool.agentTabLabel("kompile"));
        assertEquals(List.of("kompile"), ResumeTool.agentTabs(List.of()));
    }

    @Test
    void literalStandardChatsResumeInKompileInsteadOfPassthrough() {
        assertTrue(ResumeTool.isStandardKompileChatSession(
                "cli-standard123", "kompile", "coder", null));
        assertTrue(ResumeTool.isStandardKompileChatSession(
                "cli-standard456", "kompile", "unknown", ""));
        assertTrue(ResumeTool.isStandardKompileChatSession(
                "planning-20260809-0826", "kompile", "coder", null));

        assertFalse(ResumeTool.isStandardKompileChatSession(
                "cli-wrapper", "kompile", "opencode", null));
        assertFalse(ResumeTool.isStandardKompileChatSession(
                "cli-wrapper", "kompile", "coder", "native-session-id"));
        assertFalse(ResumeTool.isStandardKompileChatSession(
                "passthrough-wrapper", "kompile", "coder", null));
        assertFalse(ResumeTool.isStandardKompileChatSession(
                "cli-external", "codex", "coder", null));
    }

    @Test
    void standardChatsAreExposedAndDefaultToLiteralKompileResume() throws Exception {
        String previousHome = System.getProperty("user.home");
        Path home = tempDir.resolve("standard-chat-home");
        System.setProperty("user.home", home.toString());
        try {
            String transcriptUuid = "123e4567-e89b-12d3-a456-426614174000";
            ChatHistory history = new ChatHistory(transcriptUuid);
            history.open("(local)", null, false);
            history.logUserMessage("resume my literal standard chat");
            history.logAgentResponse("coder", "saved response", 10);
            history.close();

            ResumeTool tool = new ResumeTool(true);
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode search = mapper.createObjectNode();
            search.put("action", "search");
            search.put("source", "kompile");

            ToolResult searchResult = tool.execute(search, null);
            assertFalse(searchResult.isError());
            JsonNode searchJson = mapper.readTree(searchResult.getOutput());
            JsonNode standard = null;
            for (JsonNode conversation : searchJson.path("conversations")) {
                if (transcriptUuid.equals(conversation.path("session_id").asText())) {
                    standard = conversation;
                    break;
                }
            }

            assertTrue(standard != null, "standard chat must be present in resume search results");
            assertEquals("kompile", standard.path("agent").asText());
            assertEquals("standard_chat", standard.path("session_type").asText());
            assertEquals("kompile", standard.path("resume_target").asText());
            assertTrue(standard.path("resume_command").asText()
                    .contains("chat --resume " + transcriptUuid + " --mode standard"));

            ObjectNode resume = mapper.createObjectNode();
            resume.put("action", "resume");
            resume.put("session_id", transcriptUuid);
            ToolResult resumeResult = tool.execute(resume, null);
            assertFalse(resumeResult.isError());

            JsonNode resumeJson = mapper.readTree(resumeResult.getOutput());
            assertEquals("kompile", resumeJson.path("target_agent").asText());
            assertEquals("standard_chat", resumeJson.path("session_type").asText());
            assertEquals("standard", resumeJson.path("resume_mode").asText());
            assertEquals(transcriptUuid, resumeJson.path("session_id").asText());
            assertTrue(resumeJson.path("resume_command").asText()
                    .contains("chat --resume " + transcriptUuid + " --mode standard"));
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    @Test
    void searchScopesKompileSessionsToCurrentDirectory() throws Exception {
        String previousHome = System.getProperty("user.home");
        String previousUserDir = System.getProperty("user.dir");
        Path home = tempDir.resolve("scoped-search-home");
        Path currentProject = tempDir.resolve("search-project-a").toAbsolutePath().normalize();
        Path otherProject = tempDir.resolve("search-project-b").toAbsolutePath().normalize();
        Files.createDirectories(currentProject);
        Files.createDirectories(otherProject);
        System.setProperty("user.home", home.toString());
        System.setProperty("user.dir", otherProject.toString());
        try {
            ChatHistory local = new ChatHistory("local-search-session");
            local.open("(local)", null, false, currentProject);
            local.logUserMessage("local search conversation");
            local.close();

            ChatHistory remote = new ChatHistory("remote-search-session");
            remote.open("(local)", null, false, otherProject);
            remote.logUserMessage("remote search conversation");
            remote.close();

            ResumeTool tool = new ResumeTool(true);
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode search = mapper.createObjectNode();
            search.put("action", "search");
            search.put("source", "kompile");

            ToolContext currentProjectContext = new ToolContext(
                    "resume-scope-test", null, null, currentProject, null);
            ToolResult result = tool.execute(search, currentProjectContext);
            assertFalse(result.isError());
            JsonNode conversations = mapper.readTree(result.getOutput()).path("conversations");
            assertEquals(1, conversations.size());
            assertEquals("local-search-session",
                    conversations.get(0).path("session_id").asText());
            assertEquals(currentProject.toString(),
                    conversations.get(0).path("working_directory").asText());

            ObjectNode resumeRemote = mapper.createObjectNode();
            resumeRemote.put("action", "resume");
            resumeRemote.put("session_id", "remote-search-session");
            resumeRemote.put("target_agent", "auto");
            ToolResult resumeResult = tool.execute(resumeRemote, currentProjectContext);
            assertFalse(resumeResult.isError());
            JsonNode resumed = mapper.readTree(resumeResult.getOutput());
            assertEquals("kompile", resumed.path("target_agent").asText(),
                    "An explicit cross-project standard chat must retain global metadata");
            assertEquals("standard_chat", resumed.path("session_type").asText());
            assertEquals("standard", resumed.path("resume_mode").asText());
            assertEquals(otherProject.toString(), resumed.path("working_directory").asText());
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
            if (previousUserDir == null) {
                System.clearProperty("user.dir");
            } else {
                System.setProperty("user.dir", previousUserDir);
            }
        }
    }

    @Test
    void displayedNativeUuidMatchesHiddenKompileWrapperForCommands() {
        String wrapperId = "passthrough-codex-wrapper";
        String nativeUuid = "12345678-1234-1234-1234-123456789abc";

        assertTrue(ResumeTool.sessionIdentifierMatches(
                wrapperId, nativeUuid, nativeUuid, ResumeTool.IdentifierMatch.EXACT));
        assertTrue(ResumeTool.sessionIdentifierMatches(
                wrapperId, nativeUuid, "12345678", ResumeTool.IdentifierMatch.PREFIX));
        assertTrue(ResumeTool.sessionIdentifierMatches(
                wrapperId, nativeUuid, "1234-1234", ResumeTool.IdentifierMatch.SUBSTRING));
        assertFalse(ResumeTool.sessionIdentifierMatches(
                wrapperId, nativeUuid, "deadbeef", ResumeTool.IdentifierMatch.PREFIX));
    }

    @Test
    void expandedMetadataIncludesCanonicalIdSourceCountAndWorkingDirectory() {
        String uuid = "12345678-1234-1234-1234-123456789abc";

        List<String> lines = ResumeTool.expandedMetadataLines(
                uuid, uuid, "test-source", 7, "/work/project");

        assertEquals(List.of(
                "Session ID / UUID: " + uuid,
                "Source: test-source  ·  Messages: 7  ·  Working directory: /work/project"), lines);
    }

    @Test
    void expandedMetadataKeepsKompileTranscriptIdWhenNativeUuidDiffers() {
        List<String> lines = ResumeTool.expandedMetadataLines(
                "12345678-1234-1234-1234-123456789abc",
                "kompile-wrapper-id",
                "kompile",
                -1,
                "");

        assertEquals("Kompile transcript ID: kompile-wrapper-id", lines.get(1));
        assertTrue(lines.get(2).contains("Messages: unknown"));
        assertTrue(lines.get(2).contains("Working directory: unknown"));
    }

    @Test
    void providerNativeConversationReplacesWrapperWithRecordedNativeId() {
        String nativeId = "12345678-1234-1234-1234-123456789abc";
        ResumeTool.ConversationSummary wrapper = conversation(
                "emulated-wrapper", "Wrapper prompt", "claude", "kompile", 200_000L);
        ResumeTool.ConversationSummary nativeConversation = conversation(
                nativeId, "Native Claude title", "claude", "claude-code", 100_000L);

        List<ResumeTool.ConversationSummary> deduplicated = ResumeTool.deduplicateConversations(
                List.of(wrapper, nativeConversation), Map.of(wrapper.sessionId(), nativeId));

        assertEquals(List.of(nativeConversation), deduplicated);
    }

    @Test
    void legacySyntheticWrapperIsRemovedForUniqueNearbyNativeSession() {
        ResumeTool.ConversationSummary wrapper = conversation(
                "emulated-legacy", "/model", "claude", "kompile", 1_000_000L);
        ResumeTool.ConversationSummary nativeConversation = conversation(
                "native-near", "List MCP tools", "claude", "claude-code", 1_180_000L);
        ResumeTool.ConversationSummary unrelated = conversation(
                "native-far", "Independent session", "claude", "claude-code", 2_000_000L);

        List<ResumeTool.ConversationSummary> deduplicated = ResumeTool.deduplicateConversations(
                List.of(wrapper, nativeConversation, unrelated), Map.of());

        assertEquals(List.of(nativeConversation, unrelated), deduplicated);
    }

    @Test
    void legacySyntheticWrapperIsKeptWhenNearbyNativeMatchIsAmbiguous() {
        ResumeTool.ConversationSummary wrapper = conversation(
                "passthrough-legacy", "Prompt", "codex", "kompile", 1_000_000L);
        ResumeTool.ConversationSummary first = conversation(
                "native-before", "First", "codex", "codex", 999_000L);
        ResumeTool.ConversationSummary second = conversation(
                "native-after", "Second", "codex", "codex", 1_001_000L);

        List<ResumeTool.ConversationSummary> deduplicated = ResumeTool.deduplicateConversations(
                List.of(wrapper, first, second), Map.of());

        assertEquals(List.of(wrapper, first, second), deduplicated);
    }

    @Test
    void standardKompileConversationIsNeverCollapsedAsLegacyCliWrapper() {
        ResumeTool.ConversationSummary standardConversation = conversation(
                "cli-standard-session", "Prompt", "kompile", "kompile", 1_000_000L);
        ResumeTool.ConversationSummary nearbyConversation = conversation(
                "native-near", "Native", "kompile", "external", 1_001_000L);

        List<ResumeTool.ConversationSummary> deduplicated = ResumeTool.deduplicateConversations(
                List.of(standardConversation, nearbyConversation), Map.of());

        assertEquals(List.of(standardConversation, nearbyConversation), deduplicated);
    }

    @Test
    void ordinaryKompileConversationIsNotTimeMatchedToNativeSession() {
        ResumeTool.ConversationSummary kompileConversation = conversation(
                "ordinary-session", "Prompt", "claude", "kompile", 1_000_000L);
        ResumeTool.ConversationSummary nativeConversation = conversation(
                "native-near", "Native", "claude", "claude-code", 1_001_000L);

        List<ResumeTool.ConversationSummary> deduplicated = ResumeTool.deduplicateConversations(
                List.of(kompileConversation, nativeConversation), Map.of());

        assertEquals(List.of(kompileConversation, nativeConversation), deduplicated);
    }

    private static ResumeTool.ConversationSummary conversation(
            String sessionId,
            String title,
            String agent,
            String source,
            long timestamp) {
        return new ResumeTool.ConversationSummary(
                sessionId, title, String.valueOf(timestamp), agent, source,
                String.valueOf(timestamp), timestamp, -1, null);
    }

    private List<String> buildAgentResumeCommand(String agent,
                                                 ConversationExporter.ExportResult exportResult) throws Exception {
        ResumeTool tool = new ResumeTool(null, null, null, null, null, new ConversationReader());
        Method method = ResumeTool.class.getDeclaredMethod(
                "buildAgentResumeCommand",
                String.class,
                ConversationExporter.ExportResult.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> args = (List<String>) method.invoke(tool, agent, exportResult);
        return args;
    }

    private String resolveResumeMcpSseUrl(String agent) throws Exception {
        ResumeTool tool = new ResumeTool(null, null, null, null, null, new ConversationReader());
        Method method = ResumeTool.class.getDeclaredMethod("resolveResumeMcpSseUrl", String.class);
        method.setAccessible(true);
        return (String) method.invoke(tool, agent);
    }

    private String mcpModeForResume(String agent, String sseUrl) throws Exception {
        ResumeTool tool = new ResumeTool(null, null, null, null, null, new ConversationReader());
        Method method = ResumeTool.class.getDeclaredMethod("mcpModeForResume", String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(tool, agent, sseUrl);
    }
}
