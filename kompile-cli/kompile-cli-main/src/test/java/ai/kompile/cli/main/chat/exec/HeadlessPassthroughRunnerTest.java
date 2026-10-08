package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.main.chat.PassthroughStreamParser;
import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.LiveModelDiscovery;
import ai.kompile.cli.main.chat.config.ModelDiscovery;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class HeadlessPassthroughRunnerTest {
    @TempDir Path directory;
    ChatConfig config(String framework) {
        ChatConfig config = new ChatConfig(null, null, "zai/glm-5", null);
        config.setChatMode("passthrough"); config.setPassthroughAgent(framework); config.setPassthroughManaged(true);
        return config;
    }
    HeadlessAgentRunner.Options options(String session, boolean resume, ChatConfig config, List<HeadlessRunEvent> events) {
        return options("hello", session, resume, config, events);
    }
    HeadlessAgentRunner.Options options(String prompt, String session, boolean resume, ChatConfig config,
                                        List<HeadlessRunEvent> events) {
        return new HeadlessAgentRunner.Options(prompt, session, resume, null, null,
                HeadlessAgentRunner.OutputMode.QUIET, directory, 1000, null, null, null,
                events::add, config, null, false, false, null, false);
    }
    static class Stub extends SubprocessAgentRunner {
        final String framework;
        String restored;
        String prompt;
        Stub(String framework, Path cwd) {
            super(framework, cwd.toString(), false, false, null, 0, null, null, null);
            this.framework = framework;
        }
        @Override public void injectMcpTools() { }
        @Override public void cleanup() { }
        @Override public void cancelHeadless() { }
        @Override public void restoreNativeSession(String id) { restored = id; }
        @Override public NativeResult runHeadless(String prompt, long timeout, Consumer<PassthroughStreamParser.PassthroughEvent> events) {
            this.prompt = prompt;
            events.accept(new PassthroughStreamParser.SessionInit("native-id"));
            events.accept(new PassthroughStreamParser.ThinkingChunk("thinking"));
            events.accept(new PassthroughStreamParser.TextChunk("answer"));
            events.accept(new PassthroughStreamParser.ToolUse("read", "{\"file\":\"one\"}"));
            events.accept(new PassthroughStreamParser.ToolUse("bash", "{\"command\":\"test\"}"));
            events.accept(new PassthroughStreamParser.ToolComplete("read", "ok", 0, false));
            events.accept(new PassthroughStreamParser.ToolComplete("bash", "ok", 0, false));
            return new NativeResult(0, null, "native-id");
        }
    }
    @Test void openingAnOriginalTranscriptResumesExactNativeIdOnTheFirstManagedTurn() throws Exception {
        var workspace = new ai.kompile.cli.common.ChatWorkspaceStore(directory.resolve("workspace.json"));
        var chat = workspace.referenceNativeChat(directory, "codex", "native-id", "codex", "Original");
        var stubs = new ArrayList<Stub>();
        var seeded = new ArrayList<String>();
        var runner = new HeadlessPassthroughRunner(directory.resolve("native"), (framework, cwd, config, skip) -> {
            Stub stub = new Stub(framework, cwd); stubs.add(stub); return stub;
        }, workspace, routed -> null, (framework, turns, cwd) -> { seeded.add(framework); return "native-id"; });
        assertEquals(0, runner.run(options(chat.id(), false, config("codex"), new ArrayList<>())).exitCode());
        assertEquals("native-id", stubs.get(0).restored);
        assertEquals("native-id", runner.loadNativeId(chat.id(), directory, "codex"));
        assertEquals(0, runner.run(options(chat.id(), true, config("codex"), new ArrayList<>())).exitCode());
        assertEquals("native-id", stubs.get(1).restored);
        // Switched to another framework, the chat runs that framework's own session seeded with the
        // chat's transcript; the imported one is kept, and is never itself re-seeded.
        assertEquals(0, runner.run(options(chat.id(), false, config("claude"), new ArrayList<>())).exitCode());
        assertEquals(3, stubs.size());
        assertEquals(List.of("claude"), seeded);
        assertEquals("native-id", stubs.get(2).restored);
        assertEquals("native-id", runner.loadNativeId(chat.id(), directory, "claude"));
        assertEquals("native-id", runner.loadNativeId(chat.id(), directory, "codex"));
    }

    /**
     * An imported chat's source framework keeps history only its own session has, so going back to it
     * resumes that session and hands it every turn other vendors ran meanwhile — once.
     */
    @Test void importedSourceFrameworkIsHandedTheTurnsItMissedWhenTheChatComesBack() throws Exception {
        Path nativeSessions = directory.resolve("native");
        var workspace = new ai.kompile.cli.common.ChatWorkspaceStore(directory.resolve("workspace.json"));
        var chat = workspace.referenceNativeChat(directory, "codex", "native-id", "codex", "Original");
        var stubs = new ArrayList<Stub>();
        var runner = new HeadlessPassthroughRunner(nativeSessions, (framework, cwd, config, skip) -> {
            Stub stub = new Stub(framework, cwd); stubs.add(stub); return stub;
        }, workspace, routed -> null, (framework, turns, cwd) -> "native-id");
        assertEquals(0, runner.run(options("first on codex", chat.id(), false, config("codex"), new ArrayList<>())).exitCode());
        assertEquals("first on codex", stubs.get(0).prompt);

        HeadlessPassthroughRunner.startFreshNativeSession(nativeSessions, chat.id(), directory, "claude");
        assertEquals(0, runner.run(options("while on claude", chat.id(), true, config("claude"), new ArrayList<>())).exitCode());

        HeadlessPassthroughRunner.startFreshNativeSession(nativeSessions, chat.id(), directory, "codex");
        assertEquals(0, runner.run(options("back on codex", chat.id(), true, config("codex"), new ArrayList<>())).exitCode());
        Stub back = stubs.get(2);
        assertEquals("native-id", back.restored, "the source session is resumed, not replaced");
        assertTrue(back.prompt.contains("while on claude"), back.prompt);
        assertFalse(back.prompt.contains("first on codex"), "a turn the source session ran is not repeated");
        assertTrue(back.prompt.endsWith("back on codex"), back.prompt);

        assertEquals(0, runner.run(options("again on codex", chat.id(), true, config("codex"), new ArrayList<>())).exitCode());
        assertEquals("again on codex", stubs.get(3).prompt, "nothing new to catch up on");
    }

    /** A native framework is a vendor: "framework:model" switches it, and its first turn starts its own session. */
    @Test void modelCommandSwitchesTheFrameworkAndItsNativeSession() throws Exception {
        Path nativeSessions = directory.resolve("native");
        var stubs = new ArrayList<Stub>();
        var runner = new HeadlessPassthroughRunner(nativeSessions, (framework, cwd, config, skip) -> {
            Stub stub = new Stub(framework, cwd); stubs.add(stub); return stub;
        }, new ai.kompile.cli.common.ChatWorkspaceStore(directory.resolve("workspace.json")), routed -> null,
                (framework, turns, cwd) -> "native-id");
        String session = UUID.randomUUID().toString();
        assertEquals(0, runner.run(options(session, false, config("opencode"), new ArrayList<>())).exitCode());
        assertEquals("native-id", runner.loadNativeId(session, directory, "opencode"));

        var lists = new ArrayList<String>();
        java.util.function.Function<ChatConfig, ModelDiscovery.Result> live = routed -> {
            lists.add(routed.getPassthroughAgent());
            return ModelDiscovery.Result.success(List.of(new LiveModelDiscovery.Model(
                    "codex".equals(routed.getPassthroughAgent()) ? "gpt-5-codex" : "zai/glm-5", List.of())),
                    List.of("native:" + routed.getPassthroughAgent()));
        };
        // Browsing another framework lists its models without switching.
        ChatConfig chat = ChatConfig.loadSession(session);
        var browse = WebChatInput.parse("{\"version\":1,\"rawInput\":\"\",\"configQuery\":true,\"modelVendor\":\"codex\",\"sessionId\":\"" + session + "\"}");
        var browsed = HeadlessPassthroughRunner.resolveCommand(options(session, true, chat, new ArrayList<>())
                .withWebInput(browse), chat, directory, live, nativeSessions).data().path("model");
        assertEquals("codex", browsed.path("vendor").asText(), browsed.toString());
        assertEquals("gpt-5-codex", browsed.path("models").get(0).path("id").asText());
        assertFalse(browsed.path("models").get(0).path("current").asBoolean());
        assertEquals("opencode", chat.getPassthroughAgent());
        assertTrue(lists.contains("codex"));

        var switched = HeadlessPassthroughRunner.resolveCommand(options(session, true, chat, new ArrayList<>())
                .withWebInput(command(session, "/model codex:GPT-5-CODEX")), chat, directory, live, nativeSessions);
        assertEquals(WebCommandResolver.Status.COMPLETED, switched.status(), switched.text());
        assertEquals("codex", switched.data().path("state").path("framework").asText());
        ChatConfig pinned = ChatConfig.loadSession(session);
        assertEquals("codex", pinned.getPassthroughAgent());
        assertEquals("gpt-5-codex", pinned.getModel());
        assertNull(runner.loadNativeId(session, directory, "codex"), "codex has not started its session yet");

        // The next resumed turn starts codex's own session, seeded with the transcript; opencode's is untouched.
        assertEquals(0, runner.run(options(session, true, pinned, new ArrayList<>())).exitCode());
        assertEquals("codex", stubs.get(1).framework);
        assertEquals("native-id", stubs.get(1).restored);
        assertEquals("native-id", runner.loadNativeId(session, directory, "codex"));
        assertEquals("native-id", runner.loadNativeId(session, directory, "opencode"));

        // A framework the browser cannot run is refused and the chat is unchanged.
        var refused = HeadlessPassthroughRunner.resolveCommand(options(session, true, pinned, new ArrayList<>())
                .withWebInput(command(session, "/model dsh:anything")), pinned, directory, live, nativeSessions);
        assertEquals(WebCommandResolver.Status.NOT_YET_SUPPORTED, refused.status());
        assertEquals("codex", ChatConfig.loadSession(session).getPassthroughAgent());
    }

    @Test void vendorCannotReplaceAnOriginalNativeSessionReference() throws Exception {
        var workspace = new ai.kompile.cli.common.ChatWorkspaceStore(directory.resolve("workspace.json"));
        var chat = workspace.referenceNativeChat(directory, "codex", "original-id", "codex", "Original");
        var stub = new Stub("codex", directory);
        var runner = new HeadlessPassthroughRunner(directory.resolve("native"), (a, b, c, d) -> stub, workspace);
        assertNotEquals(0, runner.run(options(chat.id(), false, config("codex"), new ArrayList<>())).exitCode());
        assertEquals("original-id", stub.restored);
        assertEquals("original-id", runner.loadNativeId(chat.id(), directory, "codex"));
    }

    @Test void streamsTypedEventsPinsNativeIdAndRestoresExactly() throws Exception {
        String session = UUID.randomUUID().toString();
        var events = new ArrayList<HeadlessRunEvent>();
        var stubs = new ArrayList<Stub>();
        var runner = new HeadlessPassthroughRunner(directory.resolve("native"), (framework, cwd, config, skip) -> {
            assertFalse(skip); Stub stub = new Stub(framework, cwd); stubs.add(stub); return stub;
        });
        assertEquals(0, runner.run(options(session, false, config("opencode"), events)).exitCode());
        assertEquals("native-id", runner.loadNativeId(session, directory, "opencode"));
        assertEquals("opencode", ChatConfig.loadSession(session).getPassthroughAgent());
        assertTrue(events.stream().anyMatch(e -> e.type() == HeadlessRunEvent.Type.ASSISTANT_DELTA && e.text().equals("answer")));
        var completions = events.stream().filter(e -> e.type() == HeadlessRunEvent.Type.TOOL_COMPLETED).toList();
        assertEquals("native-1", completions.get(0).callId());
        assertEquals("read", completions.get(0).toolName());
        assertTrue(completions.get(0).rawInput().contains("one"));
        assertEquals("native-2", completions.get(1).callId());
        for (int i = 0; i < events.size(); i++) assertEquals(i + 1, events.get(i).sequence());
        assertEquals(0, runner.run(options(session, true, config("opencode"), new ArrayList<>())).exitCode());
        assertEquals("native-id", stubs.get(1).restored);
        assertThrows(java.io.IOException.class, () -> runner.loadNativeId(session, directory, "claude"));
        Path other = java.nio.file.Files.createDirectory(directory.resolve("other"));
        assertThrows(java.io.IOException.class, () -> runner.loadNativeId(session, other, "opencode"));
        assertThrows(java.io.IOException.class, () -> runner.saveNativeId(session, directory, "opencode", "foreign"));
    }
    @Test void commandsAndConfigReadsNeverLaunchNativeWorkOrRequireResumeMapping() {
        var launches = new AtomicInteger();
        var discovered = new ArrayList<String>();
        var runner = new HeadlessPassthroughRunner(directory.resolve("native"), (framework, cwd, config, skip) -> {
            launches.incrementAndGet(); return new Stub(framework, cwd);
        }, new ai.kompile.cli.common.ChatWorkspaceStore(directory.resolve("workspace.json")), routed -> {
            discovered.add(routed.getPassthroughAgent());
            return ModelDiscovery.Result.success(List.of(new LiveModelDiscovery.Model("zai/glm-5", List.of()),
                    new LiveModelDiscovery.Model("zai/glm-4", List.of())), List.of("native:" + routed.getPassthroughAgent()));
        });
        String session = UUID.randomUUID().toString();
        var input = WebChatInput.parse("{\"version\":1,\"rawInput\":\"/model zai/glm-4\",\"sessionId\":\"" + session + "\"}");
        assertEquals(0, runner.run(options(session, true, config("opencode"), new ArrayList<>()).withWebInput(input)).exitCode());
        assertEquals("zai/glm-4", ChatConfig.loadSession(session).getModel());
        assertEquals(List.of("opencode"), discovered);
        assertEquals(0, launches.get());
    }

    @Test void nativeModelMenuIsTheFrameworksLiveListAndSelectionsAreDecidedLikeTheTerminal() throws Exception {
        ModelDiscovery.Result live = ModelDiscovery.Result.success(List.of(
                new LiveModelDiscovery.Model("zai/glm-5", List.of()), new LiveModelDiscovery.Model("zai/glm-4", List.of())),
                List.of("native:opencode"));
        String session = UUID.randomUUID().toString();
        var config = WebChatInput.parse("{\"version\":1,\"rawInput\":\"\",\"configQuery\":true,\"sessionId\":\"" + session + "\"}");
        var menu = HeadlessPassthroughRunner.resolveCommand(options(session, false, config("opencode"), new ArrayList<>())
                .withWebInput(config), config("opencode"), directory, routed -> live);
        var models = menu.data().path("model").path("models");
        assertEquals(2, models.size());
        assertEquals("zai/glm-5", models.get(0).path("id").asText());
        assertTrue(models.get(0).path("current").asBoolean());
        assertEquals("zai/glm-4", models.get(1).path("id").asText());
        assertTrue(menu.data().path("model").path("liveListingAvailable").asBoolean());

        // Listed ids take the list's casing.
        ChatConfig listed = config("opencode");
        HeadlessPassthroughRunner.resolveCommand(options(session, false, listed, new ArrayList<>())
                .withWebInput(command(session, "/model ZAI/GLM-4")), listed, directory, routed -> live);
        assertEquals("zai/glm-4", listed.getModel());

        // A menu index is never persisted as a model id.
        ChatConfig numbered = config("opencode");
        var refused = HeadlessPassthroughRunner.resolveCommand(options(session, false, numbered, new ArrayList<>())
                .withWebInput(command(session, "/model 2")), numbered, directory, routed -> live);
        assertEquals(WebCommandResolver.Status.NOT_YET_SUPPORTED, refused.status());
        assertEquals("zai/glm-5", numbered.getModel());

        // With no live list only the recorded catalog vouches for an id.
        var none = ModelDiscovery.Result.failure(ModelDiscovery.Status.UNSUPPORTED, "no list here", List.of());
        ChatConfig recorded = config("opencode");
        assertEquals(WebCommandResolver.Status.COMPLETED, HeadlessPassthroughRunner.resolveCommand(
                options(session, false, recorded, new ArrayList<>()).withWebInput(command(session, "/model zai/glm-4")),
                recorded, directory, routed -> none).status());
        ChatConfig unlisted = config("opencode");
        var rejected = HeadlessPassthroughRunner.resolveCommand(options(session, false, unlisted, new ArrayList<>())
                .withWebInput(command(session, "/model zai/never-listed")), unlisted, directory, routed -> none);
        assertEquals(WebCommandResolver.Status.NOT_YET_SUPPORTED, rejected.status());
        assertEquals("zai/glm-5", unlisted.getModel());
        var empty = HeadlessPassthroughRunner.resolveCommand(options(session, false, config("opencode"), new ArrayList<>())
                .withWebInput(config), config("opencode"), directory, routed -> none);
        assertEquals(0, empty.data().path("model").path("models").size());
        assertEquals("no list here", empty.data().path("model").path("note").asText());
    }
    @Test void nativeEffortIsTheSelectedModelsLiveVariants() throws Exception {
        ModelDiscovery.Result live = ModelDiscovery.Result.success(List.of(
                new LiveModelDiscovery.Model("zai/glm-5", List.of("high", "max")), new LiveModelDiscovery.Model("zai/glm-4", List.of())),
                List.of("native:opencode"));
        String session = UUID.randomUUID().toString();
        var query = WebChatInput.parse("{\"version\":1,\"rawInput\":\"\",\"configQuery\":true,\"sessionId\":\"" + session + "\"}");
        var snapshot = HeadlessPassthroughRunner.resolveCommand(options(session, false, config("opencode"), new ArrayList<>())
                .withWebInput(query), config("opencode"), directory, routed -> live).data().path("thinking");
        assertTrue(snapshot.path("supported").asBoolean(), snapshot.toString());
        assertEquals(List.of("", "high", "max"), snapshot.path("thinkingOptions").findValuesAsText("value"));

        ChatConfig chosen = config("opencode");
        var applied = HeadlessPassthroughRunner.resolveCommand(options(session, false, chosen, new ArrayList<>())
                .withWebInput(command(session, "/thinking MAX")), chosen, directory, routed -> live);
        assertEquals("max", chosen.getThinking());
        assertEquals("max", applied.data().path("state").path("thinking").asText());
        assertEquals("max", ChatConfig.loadSession(session).getThinking());

        var refused = HeadlessPassthroughRunner.resolveCommand(options(session, false, chosen, new ArrayList<>())
                .withWebInput(command(session, "/thinking low")), chosen, directory, routed -> live);
        assertEquals(WebCommandResolver.Status.INVALID, refused.status());
        assertEquals("max", chosen.getThinking());

        // A model that offers no levels drops the chosen one rather than launching with a flag it rejects.
        HeadlessPassthroughRunner.resolveCommand(options(session, false, chosen, new ArrayList<>())
                .withWebInput(command(session, "/model zai/glm-4")), chosen, directory, routed -> live);
        assertNull(chosen.getThinking());
        var none = HeadlessPassthroughRunner.resolveCommand(options(session, false, chosen, new ArrayList<>())
                .withWebInput(command(session, "/thinking")), chosen, directory, routed -> live).data();
        assertFalse(none.path("supported").asBoolean());
        assertEquals("opencode lists no effort levels for zai/glm-4.", none.path("note").asText());

        ChatConfig cleared = config("opencode"); cleared.setThinking("high");
        HeadlessPassthroughRunner.resolveCommand(options(session, false, cleared, new ArrayList<>())
                .withWebInput(command(session, "/thinking default")), cleared, directory, routed -> live);
        assertNull(cleared.getThinking());
    }
    /** Every switch onto a framework hands it the whole transcript, including turns other vendors ran since. */
    @Test void switchingFrameworksHandsEachNewSessionTheWholeTranscript() throws Exception {
        Path nativeSessions = directory.resolve("native");
        record Seeded(String framework, List<String> contents) { }
        var seeded = new ArrayList<Seeded>();
        var stubs = new ArrayList<Stub>();
        var runner = new HeadlessPassthroughRunner(nativeSessions, (framework, cwd, config, skip) -> {
            Stub stub = new Stub(framework, cwd); stubs.add(stub); return stub;
        }, new ai.kompile.cli.common.ChatWorkspaceStore(directory.resolve("workspace.json")), routed -> null,
                (framework, turns, cwd) -> {
                    assertEquals(directory.toRealPath(), cwd);
                    seeded.add(new Seeded(framework, turns.stream().map(ai.kompile.cli.main.chat.ChatHistory.Turn::content).toList()));
                    return "native-id";
                });
        java.util.function.Function<ChatConfig, ModelDiscovery.Result> live = routed -> ModelDiscovery.Result.success(
                List.of(new LiveModelDiscovery.Model("codex".equals(routed.getPassthroughAgent()) ? "gpt-5-codex" : "zai/glm-5",
                        List.of())), List.of("native:" + routed.getPassthroughAgent()));
        String session = UUID.randomUUID().toString();
        assertEquals(0, runner.run(options(session, false, config("opencode"), new ArrayList<>())).exitCode());
        assertTrue(seeded.isEmpty(), "A chat's first turn has no transcript to hand off");

        ChatConfig chat = ChatConfig.loadSession(session);
        HeadlessPassthroughRunner.resolveCommand(options(session, true, chat, new ArrayList<>())
                .withWebInput(command(session, "/model codex:gpt-5-codex")), chat, directory, live, nativeSessions);
        assertEquals(0, runner.run(options(session, true, ChatConfig.loadSession(session), new ArrayList<>())).exitCode());
        assertEquals("codex", seeded.get(0).framework());
        assertEquals(List.of("hello", "answer"), seeded.get(0).contents());
        assertEquals("native-id", stubs.get(1).restored, "codex resumes the session seeded with the transcript");

        // Back on opencode, its old session would miss the codex turn: it starts over from the whole transcript.
        chat = ChatConfig.loadSession(session);
        HeadlessPassthroughRunner.resolveCommand(options(session, true, chat, new ArrayList<>())
                .withWebInput(command(session, "/model opencode:zai/glm-5")), chat, directory, live, nativeSessions);
        assertNull(runner.loadNativeId(session, directory, "opencode"));
        assertEquals(0, runner.run(options(session, true, ChatConfig.loadSession(session), new ArrayList<>())).exitCode());
        assertEquals("opencode", seeded.get(1).framework());
        assertEquals(List.of("hello", "answer", "hello", "answer"), seeded.get(1).contents());
    }

    @Test void aTranscriptThatCannotBeHandedOffFailsTheTurnInsteadOfStartingEmpty() throws Exception {
        Path nativeSessions = directory.resolve("native");
        var launches = new AtomicInteger();
        var runner = new HeadlessPassthroughRunner(nativeSessions, (framework, cwd, config, skip) -> {
            launches.incrementAndGet(); return new Stub(framework, cwd);
        }, new ai.kompile.cli.common.ChatWorkspaceStore(directory.resolve("workspace.json")), routed -> null,
                (framework, turns, cwd) -> { throw new java.io.IOException("store is read-only"); });
        String session = UUID.randomUUID().toString();
        assertEquals(0, runner.run(options(session, false, config("opencode"), new ArrayList<>())).exitCode());
        HeadlessPassthroughRunner.startFreshNativeSession(nativeSessions, session, directory, "codex");
        var events = new ArrayList<HeadlessRunEvent>();
        assertNotEquals(0, runner.run(options(session, true, config("codex"), events)).exitCode());
        assertEquals(1, launches.get(), "codex never launched without the transcript");
        assertTrue(events.stream().anyMatch(e -> e.type() == HeadlessRunEvent.Type.RUN_FAILED
                && e.message().contains("Could not hand this chat's transcript to codex")
                && e.message().contains("store is read-only")), events.toString());
        assertNull(runner.loadNativeId(session, directory, "codex"), "a failed hand-off leaves codex to retry");
    }

    /** The default standard route is not a dead end: a framework is one more vendor to switch to. */
    @Test void standardChatSwitchesToAFrameworkWhichGetsTheTranscript() throws Exception {
        WebModelCatalog.useDiscovery(routed -> ModelDiscovery.Result.success(List.of(new LiveModelDiscovery.Model(
                "passthrough".equals(routed.getChatMode()) ? "gpt-5-codex" : "base-model", List.of())), List.of("live")));
        try {
            String session = UUID.randomUUID().toString();
            ChatConfig standard = new ChatConfig("custom", null, "base-model", "https://example.test/v1");
            standard.setChatMode("standard");
            standard.bindSession(session);
            var history = new ai.kompile.cli.main.chat.ChatHistory(session);
            history.open(null, "custom", false, directory);
            history.logUserMessage("earlier question");
            history.logAgentResponse("custom", "standard answer", 0);
            history.close();

            var menu = WebCommandResolver.resolve(new WebChatInput(WebChatInput.VERSION, "/model", "", session),
                    directory, new ChatSessionStateStore(), standard, null).data();
            assertTrue(menu.path("vendors").findValuesAsText("vendor").contains("custom"), menu.toString());

            var switched = WebCommandResolver.resolve(new WebChatInput(WebChatInput.VERSION, "/model codex:gpt-5-codex", "", session),
                    directory, new ChatSessionStateStore(), standard, null);
            assertEquals(WebCommandResolver.Status.COMPLETED, switched.status(), switched.text());
            assertTrue(switched.data().path("nativeModelSelection").asBoolean());
            ChatConfig pinned = ChatConfig.loadSession(session);
            assertEquals("passthrough", pinned.getChatMode());
            assertTrue(pinned.isPassthroughManaged());
            assertEquals("codex", pinned.getPassthroughAgent());
            assertEquals("gpt-5-codex", pinned.getModel());
            assertEquals("codex / gpt-5-codex", WebCommandResolver.routeLabel(pinned));

            var seeded = new ArrayList<List<String>>();
            var stubs = new ArrayList<Stub>();
            var runner = new HeadlessPassthroughRunner(
                    ai.kompile.cli.common.KompileHome.homeDirectory().toPath().resolve("conversations/native-sessions"),
                    (framework, cwd, config, skip) -> { Stub stub = new Stub(framework, cwd); stubs.add(stub); return stub; },
                    new ai.kompile.cli.common.ChatWorkspaceStore(directory.resolve("workspace.json")), routed -> null,
                    (framework, turns, cwd) -> {
                        seeded.add(turns.stream().map(ai.kompile.cli.main.chat.ChatHistory.Turn::content).toList());
                        return "native-id";
                    });
            assertEquals(0, runner.run(options(session, true, pinned, new ArrayList<>())).exitCode());
            assertEquals(List.of(List.of("earlier question", "standard answer")), seeded);
            assertEquals("native-id", stubs.get(0).restored);
        } finally {
            WebModelCatalog.useDiscovery(null);
        }
    }

    /** A native chat switches to a standard vendor like any vendor switch; Kompile's harness then runs it. */
    @Test void nativeChatSwitchesToAStandardVendor() throws Exception {
        WebModelCatalog.useDiscovery(routed -> ModelDiscovery.Result.success(
                List.of(new LiveModelDiscovery.Model("claude-opus-5-5", List.of())), List.of("https://example.test/v1/models")));
        LiveModelDiscovery.useClaudeCodeLoginProbe(() -> new LiveModelDiscovery.ClaudeCodeLogin(true, "claude.ai", "max", false));
        try {
            String session = UUID.randomUUID().toString();
            ChatConfig chat = config("opencode");
            chat.bindSession(session);
            java.util.function.Function<ChatConfig, ModelDiscovery.Result> live = routed -> ModelDiscovery.Result.success(
                    List.of(new LiveModelDiscovery.Model("zai/glm-5", List.of())), List.of("native:opencode"));
            var browse = WebChatInput.parse("{\"version\":1,\"rawInput\":\"\",\"configQuery\":true,\"modelVendor\":\"anthropic\",\"sessionId\":\"" + session + "\"}");
            var browsed = HeadlessPassthroughRunner.resolveCommand(options(session, true, chat, new ArrayList<>())
                    .withWebInput(browse), chat, directory, live).data().path("model");
            assertEquals("anthropic", browsed.path("vendor").asText(), browsed.toString());
            assertEquals("claude-opus-5-5", browsed.path("models").get(0).path("id").asText());
            assertTrue(browsed.path("nativeModelSelection").asBoolean(), "browsing leaves the chat on its framework");
            var vendors = browsed.path("vendors").findValuesAsText("vendor");
            assertTrue(vendors.contains("anthropic") && vendors.contains("opencode"), vendors.toString());
            assertEquals("opencode", ChatConfig.loadSession(session).getPassthroughAgent());

            var switched = HeadlessPassthroughRunner.resolveCommand(options(session, true, chat, new ArrayList<>())
                    .withWebInput(command(session, "/model anthropic:claude-opus-5-5")), chat, directory, live);
            assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, switched.status(), switched.text());
            ChatConfig pinned = ChatConfig.loadSession(session);
            assertEquals("standard", pinned.getChatMode());
            assertEquals("anthropic", pinned.getProvider());
            assertEquals("claude-opus-5-5", pinned.getModel());
            assertEquals("anthropic / claude-opus-5-5", WebCommandResolver.routeLabel(pinned));
        } finally {
            WebModelCatalog.useDiscovery(null);
            LiveModelDiscovery.useClaudeCodeLoginProbe(null);
        }
    }

    private static WebChatInput command(String session, String raw) {
        return WebChatInput.parse("{\"version\":1,\"rawInput\":\"" + raw + "\",\"sessionId\":\"" + session + "\"}");
    }
    @Test void missingResumeMappingAndUnsupportedRoutesFailClosed() {
        var runner = new HeadlessPassthroughRunner(directory.resolve("native"), (a, b, c, d) -> { fail("Must not launch"); return null; });
        assertNotEquals(0, runner.run(options(UUID.randomUUID().toString(), true, config("claude"), new ArrayList<>())).exitCode());
        assertNotEquals(0, runner.run(options(UUID.randomUUID().toString(), false, config("unknown"), new ArrayList<>())).exitCode());
        var direct = config("claude"); direct.setPassthroughManaged(false);
        assertNotEquals(0, runner.run(options(UUID.randomUUID().toString(), false, direct, new ArrayList<>())).exitCode());
    }
}
