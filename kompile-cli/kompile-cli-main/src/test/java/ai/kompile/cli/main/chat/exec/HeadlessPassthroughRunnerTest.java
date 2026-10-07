package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.main.chat.PassthroughStreamParser;
import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.cli.main.chat.config.ChatConfig;
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
        return new HeadlessAgentRunner.Options("hello", session, resume, null, null,
                HeadlessAgentRunner.OutputMode.QUIET, directory, 1000, null, null, null,
                events::add, config, null, false, false, null, false);
    }
    static class Stub extends SubprocessAgentRunner {
        String restored;
        Stub(String framework, Path cwd) { super(framework, cwd.toString(), false, false, null, 0, null, null, null); }
        @Override public void injectMcpTools() { }
        @Override public void cleanup() { }
        @Override public void cancelHeadless() { }
        @Override public void restoreNativeSession(String id) { restored = id; }
        @Override public NativeResult runHeadless(String prompt, long timeout, Consumer<PassthroughStreamParser.PassthroughEvent> events) {
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
        var runner = new HeadlessPassthroughRunner(directory.resolve("native"), (framework, cwd, config, skip) -> {
            Stub stub = new Stub(framework, cwd); stubs.add(stub); return stub;
        }, workspace);
        assertEquals(0, runner.run(options(chat.id(), false, config("codex"), new ArrayList<>())).exitCode());
        assertEquals("native-id", stubs.get(0).restored);
        assertEquals("native-id", runner.loadNativeId(chat.id(), directory, "codex"));
        assertEquals(0, runner.run(options(chat.id(), true, config("codex"), new ArrayList<>())).exitCode());
        assertEquals("native-id", stubs.get(1).restored);
        assertNotEquals(0, runner.run(options(chat.id(), false, config("claude"), new ArrayList<>())).exitCode());
        assertEquals(2, stubs.size());
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
        var runner = new HeadlessPassthroughRunner(directory.resolve("native"), (framework, cwd, config, skip) -> {
            launches.incrementAndGet(); return new Stub(framework, cwd);
        });
        String session = UUID.randomUUID().toString();
        var input = WebChatInput.parse("{\"version\":1,\"rawInput\":\"/model zai/glm-4\",\"sessionId\":\"" + session + "\"}");
        assertEquals(0, runner.run(options(session, true, config("opencode"), new ArrayList<>()).withWebInput(input)).exitCode());
        assertEquals("zai/glm-4", ChatConfig.loadSession(session).getModel());
        assertEquals(0, launches.get());
    }
    @Test void missingResumeMappingAndUnsupportedRoutesFailClosed() {
        var runner = new HeadlessPassthroughRunner(directory.resolve("native"), (a, b, c, d) -> { fail("Must not launch"); return null; });
        assertNotEquals(0, runner.run(options(UUID.randomUUID().toString(), true, config("claude"), new ArrayList<>())).exitCode());
        assertNotEquals(0, runner.run(options(UUID.randomUUID().toString(), false, config("unknown"), new ArrayList<>())).exitCode());
        var direct = config("claude"); direct.setPassthroughManaged(false);
        assertNotEquals(0, runner.run(options(UUID.randomUUID().toString(), false, direct, new ArrayList<>())).exitCode());
    }
}
