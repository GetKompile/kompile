package ai.kompile.app.web.controllers;

import ai.kompile.cli.common.ChatWorkspaceStore;
import ai.kompile.cli.common.WebChatContext;
import ai.kompile.cli.common.chat.sources.adapters.KompileAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ChatWorkspaceControllerTest {
    @TempDir Path temp;
    String mode, directory;
    ChatWorkspaceController controller;
    @BeforeEach void setup() {
        mode = System.getProperty(WebChatContext.MODE);
        directory = System.getProperty(WebChatContext.WORKING_DIRECTORY);
        System.setProperty(WebChatContext.MODE, "workspace");
        System.setProperty(WebChatContext.WORKING_DIRECTORY, temp.toString());
        controller = new ChatWorkspaceController(new ChatWorkspaceStore(temp.resolve(".kompile/chat-workspace.json")),
                new KompileAdapter(temp.resolve("conversations")));
    }
    @AfterEach void restore() {
        restore(WebChatContext.MODE, mode); restore(WebChatContext.WORKING_DIRECTORY, directory);
    }
    @Test void setupCatalogDelegatesToCliInTheRegisteredProjectWithoutCreatingChat() throws Exception {
        var harness = org.mockito.Mockito.mock(ai.kompile.app.services.agent.ChatHarnessClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "harness", harness);
        var project = controller.addProject(new ChatWorkspaceController.ProjectRequest(temp.toString()));
        var options = ai.kompile.cli.common.util.JsonUtils.standardMapper().createObjectNode().put("available", true);
        org.mockito.Mockito.when(harness.setupChat(org.mockito.ArgumentMatchers.eq(temp.toRealPath().toString()), org.mockito.ArgumentMatchers.any())).thenReturn(options);
        assertSame(options, controller.setupOptions(project.id(), new ChatWorkspaceController.SetupRequest(null,
                ai.kompile.cli.common.util.JsonUtils.standardMapper().createObjectNode().put("runtime", "direct"))));
        assertTrue(controller.workspace().projects().get(0).chats().isEmpty());
        org.mockito.Mockito.verify(harness).setupChat(org.mockito.ArgumentMatchers.eq(temp.toRealPath().toString()),
                org.mockito.ArgumentMatchers.argThat(p -> "catalog".equals(p.path("action").asText()) && "direct".equals(p.path("selection").path("runtime").asText())));
    }
    @Test void configuredChatUsesTheSameSessionIdForCliPinsAndWorkspaceMetadata() throws Exception {
        var harness = org.mockito.Mockito.mock(ai.kompile.app.services.agent.ChatHarnessClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "harness", harness);
        var project = controller.addProject(new ChatWorkspaceController.ProjectRequest(temp.toString()));
        var result = ai.kompile.cli.common.util.JsonUtils.standardMapper().createObjectNode().put("ok", true).put("framework", "standard").put("model", "fresh-model");
        org.mockito.Mockito.when(harness.setupChat(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(result);
        var chat = controller.setupCreate(project.id(), new ChatWorkspaceController.SetupRequest("New configured chat",
                ai.kompile.cli.common.util.JsonUtils.standardMapper().createObjectNode().put("model", "fresh-model")));
        assertEquals("fresh-model", chat.model());
        org.mockito.Mockito.verify(harness).setupChat(org.mockito.ArgumentMatchers.eq(temp.toRealPath().toString()),
                org.mockito.ArgumentMatchers.argThat(p -> "create".equals(p.path("action").asText()) && chat.id().equals(p.path("sessionId").asText())));
        assertEquals(chat, controller.workspace().projects().get(0).chats().get(0));
    }
    @Test void rejectedSetupDoesNotRegisterAnUnconfiguredConversation() throws Exception {
        var harness = org.mockito.Mockito.mock(ai.kompile.app.services.agent.ChatHarnessClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "harness", harness);
        var project = controller.addProject(new ChatWorkspaceController.ProjectRequest(temp.toString()));
        org.mockito.Mockito.when(harness.setupChat(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(ai.kompile.cli.common.util.JsonUtils.standardMapper().createObjectNode().put("ok", false).put("status", "Unsupported model option"));
        assertThrows(ResponseStatusException.class, () -> controller.setupCreate(project.id(), new ChatWorkspaceController.SetupRequest("Rejected",
                ai.kompile.cli.common.util.JsonUtils.standardMapper().createObjectNode())));
        assertTrue(controller.workspace().projects().get(0).chats().isEmpty());
        assertThrows(ResponseStatusException.class, () -> controller.setupOptions("unregistered", null));
        org.mockito.Mockito.verify(harness, org.mockito.Mockito.times(1)).setupChat(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }
    static void restore(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }
    private ai.kompile.cli.common.chat.sources.ChatSourceAdapter nativeReader(String source, Path transcript, Path cwd) {
        return new ai.kompile.cli.common.chat.sources.ChatSourceAdapter() {
            public String id() { return source; }
            public String displayName() { return source.equals("claude-code") ? "Claude Code" : source; }
            public ai.kompile.cli.common.chat.sources.SourceInfo discover() { throw new AssertionError("Do not enumerate twice"); }
            public java.util.List<ai.kompile.cli.common.chat.sources.ChatSessionSummary> list() throws java.io.IOException {
                if (!Files.exists(transcript)) throw new java.io.IOException("Vendor store unavailable");
                return java.util.List.of(new ai.kompile.cli.common.chat.sources.ChatSessionSummary(
                        "original-id", source, "Original title", source, 1, 1, cwd.toString()));
            }
            public java.util.Optional<Path> resolveWorkingDirectory(String id) {
                return "original-id".equals(id) ? java.util.Optional.of(cwd) : java.util.Optional.empty();
            }
            public java.util.List<ai.kompile.cli.common.chat.sources.ChatTurn> readTurns(String id) throws java.io.IOException {
                assertEquals("original-id", id);
                return java.util.List.of(new ai.kompile.cli.common.chat.sources.ChatTurn("assistant", Files.readString(transcript)));
            }
        };
    }

    @Test void projectVendorDiscoveryUsesScopedResumeReaderAndCanonicalDirectory() throws Exception {
        Path root = Files.createDirectory(temp.resolve("project"));
        Path nested = Files.createDirectory(root.resolve("nested"));
        Path other = Files.createDirectory(temp.resolve("other"));
        Path alias = Files.createSymbolicLink(temp.resolve("alias"), root);
        var scans = new java.util.concurrent.atomic.AtomicInteger();
        var reader = new ai.kompile.cli.common.chat.sources.ChatSourceAdapter() {
            public String id() { return "claude-code"; }
            public String displayName() { return "Claude Code"; }
            public ai.kompile.cli.common.chat.sources.SourceInfo discover() { throw new AssertionError("No discovery scan"); }
            public java.util.List<ai.kompile.cli.common.chat.sources.ChatSessionSummary> list() {
                throw new AssertionError("Use resume's project-scoped reader");
            }
            public java.util.List<ai.kompile.cli.common.chat.sources.ChatSessionSummary> list(Path directory) {
                assertEquals(root, directory);
                scans.incrementAndGet();
                return java.util.List.of(
                        summary("exact", root.toString()), summary("alias", alias.toString()),
                        summary("fallback", null), summary("unknown", null),
                        summary("nested", nested.toString()), summary("other", other.toString()),
                        summary("relative", "project"), summary("removed", temp.resolve("removed").toString()),
                        summary("subagent-child", root.toString()), summary("malformed", Character.toString(0)));
            }
            private ai.kompile.cli.common.chat.sources.ChatSessionSummary summary(String id, String cwd) {
                return new ai.kompile.cli.common.chat.sources.ChatSessionSummary(id, id(), id, id(), 1, 1, cwd);
            }
            public java.util.Optional<Path> resolveWorkingDirectory(String id) {
                return "fallback".equals(id) ? java.util.Optional.of(root) : java.util.Optional.empty();
            }
            public java.util.List<ai.kompile.cli.common.chat.sources.ChatTurn> readTurns(String id) {
                throw new AssertionError("Listing must not read or import turns");
            }
        };
        var store = new ChatWorkspaceStore(temp.resolve("workspace.json"));
        controller = new ChatWorkspaceController(store, new KompileAdapter(temp.resolve("conversations")),
                ai.kompile.cli.common.chat.sources.ChatSourceRegistry.of(java.util.List.of(reader,
                        nativeReader("codex", temp.resolve("missing"), other))));
        var project = controller.addProject(new ChatWorkspaceController.ProjectRequest(root.toString()));
        assertEquals(2, controller.nativeSources().size());
        assertEquals(0, scans.get(), "Folders must appear before transcript scans");
        var folder = controller.projectNativeFolder(project.id(), "claude-code");
        assertNull(folder.error());
        assertEquals(java.util.List.of("exact", "alias", "fallback"),
                folder.chats().stream().map(ai.kompile.cli.common.chat.sources.ChatSessionSummary::sessionId).toList());
        assertEquals(1, scans.get());
        assertTrue(store.read().projects().get(0).chats().isEmpty());
        assertFalse(Files.exists(temp.resolve("conversations")));
    }

    @Test void projectVendorFailuresAreIsolatedAndRequestsValidateScope() throws Exception {
        Path transcript = temp.resolve("vendor.jsonl"); Files.writeString(transcript, "unchanged");
        controller = new ChatWorkspaceController(new ChatWorkspaceStore(temp.resolve("workspace.json")),
                new KompileAdapter(temp.resolve("conversations")),
                ai.kompile.cli.common.chat.sources.ChatSourceRegistry.of(java.util.List.of(
                        nativeReader("claude-code", temp.resolve("missing"), temp),
                        nativeReader("codex", transcript, temp))));
        var project = controller.addProject(new ChatWorkspaceController.ProjectRequest(temp.toString()));
        var unavailable = controller.projectNativeFolder(project.id(), "claude-code");
        assertEquals("Vendor store unavailable", unavailable.error());
        assertTrue(unavailable.chats().isEmpty());
        assertEquals(1, controller.projectNativeFolder(project.id(), "codex").chats().size());
        assertEquals("unchanged", Files.readString(transcript));
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> controller.projectNativeFolder("unknown", "codex")).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> controller.projectNativeFolder(project.id(), "cursor")).getStatusCode().value());
        System.setProperty(WebChatContext.MODE, "single");
        assertEquals(409, assertThrows(ResponseStatusException.class, controller::nativeSources).getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> controller.projectNativeFolder(project.id(), "codex")).getStatusCode().value());
    }

    @Test void nativeFoldersReadOriginalStoresWithoutRegisteringOrImporting() throws Exception {
        Path transcript = temp.resolve("vendor.jsonl"); Files.writeString(transcript, "original");
        var store = new ChatWorkspaceStore(temp.resolve("workspace.json"));
        var registry = ai.kompile.cli.common.chat.sources.ChatSourceRegistry.of(java.util.List.of(
                nativeReader("claude-code", transcript, temp), nativeReader("codex", transcript, temp),
                nativeReader("cursor", transcript, temp)));
        controller = new ChatWorkspaceController(store, new KompileAdapter(temp.resolve("conversations")), registry);
        var folders = controller.nativeFolders();
        assertEquals(2, folders.size());
        assertEquals("Claude Code", folders.get(0).name());
        assertEquals("original-id", folders.get(0).chats().get(0).sessionId());
        assertTrue(store.read().projects().isEmpty());
        assertFalse(Files.exists(temp.resolve("conversations")));
        assertEquals("original", Files.readString(transcript));
    }

    @Test void openingNativeChatStoresOnlyReferenceAndReadsLiveOriginalTranscript() throws Exception {
        Path transcript = temp.resolve("vendor.jsonl"); Files.writeString(transcript, "original");
        var store = new ChatWorkspaceStore(temp.resolve("workspace.json"));
        controller = new ChatWorkspaceController(store, new KompileAdapter(temp.resolve("conversations")),
                ai.kompile.cli.common.chat.sources.ChatSourceRegistry.of(java.util.List.of(nativeReader("claude-code", transcript, temp))));
        var request = new ChatWorkspaceController.NativeRequest("claude-code", "original-id");
        var selected = controller.openNative(request);
        assertEquals(selected.chat(), controller.openNative(request).chat());
        // Opens in Kompile chat; the vendor's framework stays one model-menu choice away.
        assertEquals("standard", selected.chat().framework());
        assertEquals("claude-code", selected.chat().nativeSource());
        assertEquals("original-id", selected.chat().nativeSessionId());
        assertEquals(selected.chat(), controller.workspace().projects().get(0).chats().get(0));
        var original = controller.transcript(temp.toString(), selected.chat().id());
        assertEquals(selected.chat().id(), original.sessionId());
        assertEquals("original", original.turns().get(0).content());
        Files.writeString(transcript, "updated in vendor");
        assertEquals("updated in vendor", controller.transcript(temp.toString(), selected.chat().id()).turns().get(0).content());
        assertThrows(ResponseStatusException.class, () -> controller.renameChat(selected.project().id(), selected.chat().id(),
                new ChatWorkspaceController.ChatRequest("changed")));
        assertFalse(Files.exists(temp.resolve("conversations")));
        assertEquals("updated in vendor", Files.readString(transcript));

        // Once the first Kompile turn carried the vendor's turns over, the chat's own transcript is shown.
        Path conversations = Files.createDirectories(temp.resolve("conversations"));
        Files.writeString(conversations.resolve(selected.chat().id() + ".txt"), String.join("\n",
                "CWD:     " + temp.toRealPath(), "",
                "[system] " + ai.kompile.cli.common.chat.sources.KompileTranscriptFormat.carriedOverEvent("claude-code", "original-id"), "",
                "< updated in vendor", "", "> on kompile", "", "< kompile answer", ""));
        var carried = controller.transcript(temp.toString(), selected.chat().id());
        assertEquals(selected.chat().id(), carried.sessionId());
        assertEquals(java.util.List.of("updated in vendor", "on kompile", "kompile answer"),
                carried.turns().stream().map(ai.kompile.cli.common.chat.sources.ChatTurn::content).toList());
    }

    @Test void aTranscriptCarriedOverFromAnotherVendorSessionDoesNotReplaceTheVendorView() throws Exception {
        Path transcript = temp.resolve("vendor.jsonl"); Files.writeString(transcript, "original");
        var store = new ChatWorkspaceStore(temp.resolve("workspace.json"));
        controller = new ChatWorkspaceController(store, new KompileAdapter(temp.resolve("conversations")),
                ai.kompile.cli.common.chat.sources.ChatSourceRegistry.of(java.util.List.of(nativeReader("codex", transcript, temp))));
        var chat = controller.openNative(new ChatWorkspaceController.NativeRequest("codex", "original-id")).chat();
        Path conversations = Files.createDirectories(temp.resolve("conversations"));
        Files.writeString(conversations.resolve(chat.id() + ".txt"), String.join("\n",
                "CWD:     " + temp.toRealPath(), "",
                "[system] " + ai.kompile.cli.common.chat.sources.KompileTranscriptFormat.carriedOverEvent("codex", "other-id"), "",
                "< not this session", ""));
        assertEquals(java.util.List.of("original"), controller.transcript(temp.toString(), chat.id()).turns().stream()
                .map(ai.kompile.cli.common.chat.sources.ChatTurn::content).toList());
    }

    @Test void kompileTurnsOfAChatOpenedBeforeCarryOverAreShownAfterTheVendorTurns() throws Exception {
        Path transcript = temp.resolve("vendor.jsonl"); Files.writeString(transcript, "original");
        var store = new ChatWorkspaceStore(temp.resolve("workspace.json"));
        controller = new ChatWorkspaceController(store, new KompileAdapter(temp.resolve("conversations")),
                ai.kompile.cli.common.chat.sources.ChatSourceRegistry.of(java.util.List.of(nativeReader("codex", transcript, temp))));
        // Opened as the vendor framework, then answered in Kompile: no carry-over marker.
        var chat = store.referenceNativeChat(temp, "codex", "original-id", "codex", "Original");
        Path conversations = Files.createDirectories(temp.resolve("conversations"));
        Files.writeString(conversations.resolve(chat.id() + ".txt"), String.join("\n",
                "CWD:     " + temp.toRealPath(), "", "> asked in kompile", "", "< answered in kompile", ""));
        var shown = controller.transcript(temp.toString(), chat.id());
        assertEquals(java.util.List.of("original", "Turns taken in Kompile chat:", "asked in kompile", "answered in kompile"),
                shown.turns().stream().map(ai.kompile.cli.common.chat.sources.ChatTurn::content).toList());
        assertEquals("system", shown.turns().get(1).role());
    }

    @Test void aVendorChatRecordedBeforeKompileChatWasTheDefaultStartsThereUntilItTakesATurn() throws Exception {
        Path transcript = temp.resolve("vendor.jsonl"); Files.writeString(transcript, "original");
        var store = new ChatWorkspaceStore(temp.resolve("workspace.json"));
        controller = new ChatWorkspaceController(store, new KompileAdapter(temp.resolve("conversations")),
                ai.kompile.cli.common.chat.sources.ChatSourceRegistry.of(java.util.List.of(nativeReader("codex", transcript, temp))));
        var unstarted = store.referenceNativeChat(temp, "codex", "original-id", "codex", "Original");
        var started = store.referenceNativeChat(temp, "codex", "started-id", "codex", "Started");
        Path conversations = Files.createDirectories(temp.resolve("conversations"));
        Files.writeString(conversations.resolve(started.id() + ".txt"), String.join("\n",
                "CWD:     " + temp.toRealPath(), "", "> asked", "", "< answered", ""));
        var listed = controller.workspace().projects().get(0).chats();
        assertEquals("standard", listed.get(0).framework());
        assertEquals("codex", listed.get(0).nativeSource());
        assertEquals("standard", store.findChat(temp, unstarted.id()).framework());
        // One that already ran keeps the settings its own session recorded.
        assertEquals("codex", listed.get(1).framework());
        assertEquals("standard",
                controller.openNative(new ChatWorkspaceController.NativeRequest("codex", "original-id")).chat().framework());
    }

    @Test void unknownNativeIdsAndSourcesAreRejectedAndUnavailableVendorsAreIsolated() throws Exception {
        Path transcript = temp.resolve("vendor.jsonl"); Files.writeString(transcript, "original");
        var store = new ChatWorkspaceStore(temp.resolve("workspace.json"));
        controller = new ChatWorkspaceController(store, new KompileAdapter(temp.resolve("conversations")),
                ai.kompile.cli.common.chat.sources.ChatSourceRegistry.of(java.util.List.of(
                        nativeReader("claude-code", temp.resolve("missing"), temp), nativeReader("codex", transcript, temp))));
        var folders = controller.nativeFolders();
        assertNotNull(folders.get(0).error());
        assertEquals(1, folders.get(1).chats().size());
        assertThrows(ResponseStatusException.class, () -> controller.openNative(new ChatWorkspaceController.NativeRequest("codex", "../../file")));
        assertThrows(ResponseStatusException.class, () -> controller.openNative(new ChatWorkspaceController.NativeRequest("cursor", "original-id")));
        assertTrue(store.read().projects().isEmpty());
        System.setProperty(WebChatContext.MODE, "single");
        assertThrows(ResponseStatusException.class, controller::nativeFolders);
        assertThrows(ResponseStatusException.class, () -> controller.openNative(new ChatWorkspaceController.NativeRequest("codex", "original-id")));
    }

    @Test void nativeTranscriptCannotEscapeRegisteredWorkingDirectory() throws Exception {
        Path other = Files.createDirectory(temp.resolve("other"));
        Path transcript = temp.resolve("vendor.jsonl"); Files.writeString(transcript, "original");
        var store = new ChatWorkspaceStore(temp.resolve("workspace.json"));
        var chat = store.referenceNativeChat(temp, "codex", "original-id", "codex", "Original");
        controller = new ChatWorkspaceController(store, new KompileAdapter(temp.resolve("conversations")),
                ai.kompile.cli.common.chat.sources.ChatSourceRegistry.of(java.util.List.of(nativeReader("codex", transcript, other))));
        assertThrows(ResponseStatusException.class, () -> controller.transcript(temp.toString(), chat.id()));
    }

    @Test void frameworkRouteSurvivesListingTranscriptTitleMergeAndRename() throws Exception {
        var project = controller.addProject(new ChatWorkspaceController.ProjectRequest(temp.toString()));
        var chat = controller.createChat(project.id(), new ChatWorkspaceController.ChatRequest("Z.ai", "opencode", "zai/glm-5"));
        assertEquals("opencode", controller.workspace().projects().get(0).chats().get(0).framework());
        var renamed = controller.renameChat(project.id(), chat.id(), new ChatWorkspaceController.ChatRequest("GLM chat"));
        assertEquals("opencode", renamed.framework()); assertEquals("zai/glm-5", renamed.model());
        var listed = controller.workspace().projects().get(0).chats().get(0);
        assertEquals("GLM chat", listed.name()); assertEquals("opencode", listed.framework()); assertEquals("zai/glm-5", listed.model());
        // A vendor switch the CLI recorded is listed and survives renaming; the launch selection is kept.
        new ChatWorkspaceStore(temp.resolve(".kompile/chat-workspace.json")).recordRoute(temp, chat.id(), "anthropic / claude-opus-5-5");
        assertEquals("anthropic / claude-opus-5-5", controller.workspace().projects().get(0).chats().get(0).route());
        var switched = controller.renameChat(project.id(), chat.id(), new ChatWorkspaceController.ChatRequest("Switched"));
        assertEquals("anthropic / claude-opus-5-5", switched.route()); assertEquals("opencode", switched.framework());
        assertThrows(ResponseStatusException.class, () -> controller.createChat(project.id(),
                new ChatWorkspaceController.ChatRequest("invalid", "../../claude", "model")));
    }
    @Test void projectAndChatCreationArePersisted() throws Exception {
        var first = controller.addProject(new ChatWorkspaceController.ProjectRequest(temp.toString()));
        Path other = Files.createDirectory(temp.resolve("other"));
        var second = controller.addProject(new ChatWorkspaceController.ProjectRequest(other.toString()));
        var a = controller.createChat(first.id(), new ChatWorkspaceController.ChatRequest("A"));
        var b = controller.createChat(second.id(), new ChatWorkspaceController.ChatRequest("B"));
        var reopened = new ChatWorkspaceController(new ChatWorkspaceStore(temp.resolve(".kompile/chat-workspace.json")),
                new KompileAdapter(temp.resolve("conversations"))).workspace();
        assertTrue(reopened.enabled());
        assertEquals(2, reopened.projects().size());
        assertEquals(a, reopened.projects().get(0).chats().get(0));
        assertEquals(b, reopened.projects().get(1).chats().get(0));
    }
    @Test void renamePersistsBeforeFirstTurnAndRejectsBlankOrForeignChats() throws Exception {
        var project = controller.addProject(new ChatWorkspaceController.ProjectRequest(temp.toString()));
        var chat = controller.createChat(project.id(), new ChatWorkspaceController.ChatRequest("Original"));
        String title = "Full title ".repeat(30) + "ending";
        var renamed = controller.renameChat(project.id(), chat.id(), new ChatWorkspaceController.ChatRequest(title));
        assertEquals(title, renamed.name());
        var reopened = new ChatWorkspaceController(new ChatWorkspaceStore(temp.resolve(".kompile/chat-workspace.json")),
                new KompileAdapter(temp.resolve("conversations")));
        assertEquals(title, reopened.workspace().projects().get(0).chats().get(0).name());
        assertThrows(ResponseStatusException.class, () -> controller.renameChat(project.id(), chat.id(),
                new ChatWorkspaceController.ChatRequest("  ")));
        var other = controller.addProject(new ChatWorkspaceController.ProjectRequest(Files.createDirectory(temp.resolve("other")).toString()));
        assertThrows(ResponseStatusException.class, () -> controller.renameChat(other.id(), chat.id(),
                new ChatWorkspaceController.ChatRequest("Wrong project")));
        assertEquals(title, new KompileAdapter(temp.resolve("conversations")).readTitle(chat.id()));
    }

    @Test void newProjectIsARegisteredLocalFolderThatCanStartChats() throws Exception {
        var project = controller.newProject(new ChatWorkspaceController.NewProjectRequest(temp.toString(), "new-project"));
        assertEquals(temp.resolve("new-project").toRealPath().toString(), project.workingDirectory());
        assertTrue(project.chats().isEmpty());
        var chat = controller.createChat(project.id(), new ChatWorkspaceController.ChatRequest("First"));
        assertEquals(chat, controller.workspace().projects().get(0).chats().get(0));
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> controller.newProject(
                new ChatWorkspaceController.NewProjectRequest(temp.toString(), "new-project"))).getStatusCode().value());
        for (String name : new String[] {"../outside", "a/b", ""}) {
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> controller.newProject(
                    new ChatWorkspaceController.NewProjectRequest(temp.toString(), name))).getStatusCode().value());
        }
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> controller.newProject(null)).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> controller.newProject(
                new ChatWorkspaceController.NewProjectRequest("relative", "new"))).getStatusCode().value());
    }

    @Test void singleModeCannotExpandItsDirectoryBoundary() throws Exception {
        System.setProperty(WebChatContext.MODE, "single");
        assertFalse(controller.workspace().enabled());
        assertTrue(controller.workspace().projects().isEmpty());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> controller.addProject(new ChatWorkspaceController.ProjectRequest(temp.toString()))).getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> controller.createChat("unknown", null)).getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> controller.newProject(
                new ChatWorkspaceController.NewProjectRequest(temp.toString(), "new"))).getStatusCode().value());
        assertFalse(Files.exists(temp.resolve("new")));
    }
    @Test void existingNativeChatsAreListedAndReadWithoutImportPrefixes() throws Exception {
        controller.addProject(new ChatWorkspaceController.ProjectRequest(temp.toString()));
        Path conversations = Files.createDirectory(temp.resolve("conversations"));
        Files.writeString(conversations.resolve("existing-cli.txt"), "CWD: " + temp.toRealPath() + "\n> earlier question\n\n< earlier answer\n");
        Files.writeString(conversations.resolve("subagent-child.txt"), "CWD: " + temp.toRealPath() + "\n> private child\n");
        var registered = controller.addProject(new ChatWorkspaceController.ProjectRequest(temp.toString()));
        assertEquals("existing-cli", registered.chats().get(0).id());
        assertEquals("earlier question", registered.chats().get(0).name());
        assertEquals("existing-cli", controller.workspace().projects().get(0).chats().get(0).id());
        assertEquals(1, controller.workspace().projects().get(0).chats().size());
        var transcript = controller.transcript(temp.toString(), "existing-cli");
        assertEquals("existing-cli", transcript.sessionId());
        assertEquals(2, transcript.turns().size());
        assertEquals("earlier question", transcript.turns().get(0).content());
        assertEquals("earlier answer", transcript.turns().get(1).content());
        assertTrue(controller.transcript(temp.toString(), "new-uuid").turns().isEmpty());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> controller.transcript(temp.toString(), "../escape")).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> controller.transcript(temp.toString(), "subagent-child")).getStatusCode().value());
        Path other = Files.createDirectory(temp.resolve("different"));
        controller.addProject(new ChatWorkspaceController.ProjectRequest(other.toString()));
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> controller.transcript(other.toString(), "existing-cli")).getStatusCode().value());
    }

    @Test void symlinkTranscriptDirectoriesBelongToTheirCanonicalProject() throws Exception {
        Path alias = Files.createSymbolicLink(temp.resolveSibling(temp.getFileName() + "-alias"), temp);
        try {
            controller.addProject(new ChatWorkspaceController.ProjectRequest(temp.toString()));
            Path conversations = Files.createDirectory(temp.resolve("conversations"));
            Files.writeString(conversations.resolve("aliased-cli.txt"), "CWD: " + alias + "\n> aliased question\n");
            Files.writeString(conversations.resolve("unavailable-cli.txt"), "CWD: " + temp.resolve("removed") + "\n> old question\n");
            var chats = controller.workspace().projects().get(0).chats();
            assertEquals(1, chats.size());
            assertEquals("aliased-cli", chats.get(0).id());
            assertEquals("aliased question", controller.transcript(temp.toString(), "aliased-cli").turns().get(0).content());
        } finally { Files.delete(alias); }
    }

    @Test void unavailableProjectDoesNotBreakOtherChats() throws Exception {
        Path offline = Files.createDirectory(temp.resolve("offline"));
        var project = controller.addProject(new ChatWorkspaceController.ProjectRequest(offline.toString()));
        controller.createChat(project.id(), new ChatWorkspaceController.ChatRequest("Offline chat"));
        Files.delete(offline);
        controller.addProject(new ChatWorkspaceController.ProjectRequest(temp.toString()));
        assertEquals(2, controller.workspace().projects().size());
        assertEquals("Offline chat", controller.workspace().projects().get(0).chats().get(0).name());
    }

    @Test void legacyWorkspaceAliasAndActualTranscriptAreOneChat() throws Exception {
        var project = controller.addProject(new ChatWorkspaceController.ProjectRequest(temp.toString()));
        var chat = controller.createChat(project.id(), new ChatWorkspaceController.ChatRequest("Legacy chat"));
        String legacy = KompileAdapter.legacyBrowserSessionId(temp.toRealPath(), chat.id());
        Path conversations = Files.createDirectory(temp.resolve("conversations"));
        Files.writeString(conversations.resolve(legacy + ".txt"), "CWD: " + temp.toRealPath() + "\n> original\n\n< reply\n");
        var chats = controller.workspace().projects().get(0).chats();
        assertEquals(1, chats.size());
        assertEquals(legacy, chats.get(0).id());
        assertEquals("original", chats.get(0).name());
        Files.writeString(conversations.resolve(legacy + ".txt"), "[title] Renamed in resume\n", java.nio.file.StandardOpenOption.APPEND);
        assertEquals("Renamed in resume", controller.workspace().projects().get(0).chats().get(0).name());
        assertEquals(legacy, controller.transcript(temp.toString(), chat.id()).sessionId());
        assertEquals(legacy, controller.transcript(temp.toString(), legacy).sessionId());
    }

    @Test void invalidHostDirectoriesAndUnknownProjectsAreRejected() {
        for (String path : new String[] {"relative", temp.resolve("missing").toString(), ""}) {
            assertEquals(400, assertThrows(ResponseStatusException.class,
                    () -> controller.addProject(new ChatWorkspaceController.ProjectRequest(path))).getStatusCode().value());
        }
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> controller.createChat("unknown", null)).getStatusCode().value());
    }
}
