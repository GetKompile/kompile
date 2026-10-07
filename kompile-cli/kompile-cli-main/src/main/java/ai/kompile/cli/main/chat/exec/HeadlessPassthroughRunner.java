package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.main.chat.PassthroughStreamParser;
import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.SystemPromptManager;
import ai.kompile.cli.common.KompileHome;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** One bounded native turn, on the existing CLI JSONL transport. No terminal or latest-session fallback. */
public final class HeadlessPassthroughRunner {
    @FunctionalInterface
    interface RunnerFactory { SubprocessAgentRunner create(String framework, Path cwd, ChatConfig config, boolean skip); }

    private final Path nativeSessions;
    private final RunnerFactory factory;
    private final ai.kompile.cli.common.ChatWorkspaceStore workspace;

    public HeadlessPassthroughRunner() {
        this(KompileHome.homeDirectory().toPath().resolve("conversations/native-sessions"),
                (framework, cwd, config, skip) -> new SubprocessAgentRunner(framework, cwd.toString(),
                        skip, true, null, 0, SystemPromptManager.resolve(null, null, null), null, null));
    }

    HeadlessPassthroughRunner(Path nativeSessions, RunnerFactory factory) {
        this(nativeSessions, factory, new ai.kompile.cli.common.ChatWorkspaceStore());
    }

    HeadlessPassthroughRunner(Path nativeSessions, RunnerFactory factory,
                              ai.kompile.cli.common.ChatWorkspaceStore workspace) {
        this.nativeSessions = nativeSessions;
        this.factory = factory;
        this.workspace = workspace;
    }

    public HeadlessAgentRunner.Result run(HeadlessAgentRunner.Options opts) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        AtomicLong sequence = new AtomicLong();
        Consumer<HeadlessRunEvent> events = event -> {
            HeadlessRunEvent ordered = event.withSequence(sequence.incrementAndGet());
            if (opts.eventSink() != null) opts.eventSink().accept(ordered);
            if (opts.outputMode() == HeadlessAgentRunner.OutputMode.JSON)
                out.println(ExecJsonEvents.event(JsonUtils.standardMapper(), ordered));
        };
        SubprocessAgentRunner runner = null;
        Thread shutdown = null;
        ChatHistory history = null;
        StringBuilder answer = new StringBuilder();
        try {
            Path cwd = opts.workingDirectory().toRealPath();
            ChatConfig config = Objects.requireNonNull(opts.chatConfig(), "Missing passthrough config");
            if (!config.isPassthroughManaged()) throw new IllegalArgumentException("Direct passthrough styles are terminal-only");
            String framework = config.getPassthroughAgent();
            if (!SubprocessAgentRunner.supportsHeadless(framework))
                throw new IllegalArgumentException("No verified structured/exact-resume contract for native agent: " + framework);
            framework = framework.toLowerCase(Locale.ROOT);
            WebCommandResolver.Resolution command = resolveCommand(opts, config, cwd);
            events.accept(HeadlessRunEvent.started(opts.sessionId(), config.getModel(), cwd.toString(),
                    Map.of("mode", "passthrough", "framework", framework, "web_controls", "unsupported",
                            "cancellation", "process-tree-kill")));
            if (command != null && command.isCommandOutcome()) {
                events.accept(HeadlessRunEvent.commandOutcome(opts.sessionId(), command));
                events.accept(HeadlessRunEvent.completed(opts.sessionId(), command.text(), command.exitCode(), 0));
                writeAnswer(opts, out, command.text());
                return new HeadlessAgentRunner.Result(command.exitCode(), command.text(), opts.sessionId());
            }
            // Model turns pin the route; read-only menus never create a transcript or pin.
            config.bindSession(opts.sessionId());
            var reference = workspace.findChat(cwd, opts.sessionId());
            String nativeId;
            if (reference != null && reference.nativeSource() != null) {
                if (!framework.equals(reference.framework())) throw new IOException("Native chat belongs to another framework");
                nativeId = reference.nativeSessionId();
                // Pin before launch, so a provider returning a different ID fails closed even on first use.
                saveNativeId(opts.sessionId(), cwd, framework, nativeId);
            } else {
                nativeId = opts.resume() ? loadNativeId(opts.sessionId(), cwd, framework) : null;
            }
            if (!opts.attachments().isEmpty()) throw new IllegalArgumentException("Native web passthrough attachments are unsupported");
            if (opts.roleName() != null && !opts.roleName().isBlank())
                throw new IllegalArgumentException("Kompile harness roles are unsupported for native web passthrough");
            String prompt = command == null ? opts.prompt() : command.modelPrompt();
            runner = factory.create(framework, cwd, config, opts.autoApproveTools());
            runner.setOutputConsumer(err::println);
            runner.setExactResumeRequired(true);
            runner.setLaunchOverrides(config.getModel(), config.getThinking());
            runner.setExtraEnvironment(Map.of("KOMPILE_TOOL_SESSION_ID", opts.sessionId()));
            if (nativeId != null) runner.restoreNativeSession(nativeId);
            runner.injectMcpTools();
            SubprocessAgentRunner active = runner;
            shutdown = new Thread(active::cancelHeadless, "native-headless-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            history = new ChatHistory(opts.sessionId());
            history.open(null, framework, false, cwd);
            history.logUserMessage(opts.webInput() == null ? opts.prompt() : opts.webInput().rawInput());
            String pinnedFramework = framework;
            long[] toolCount = {0};
            record PendingTool(String id, String input) { }
            Map<String, ArrayDeque<PendingTool>> pendingTools = new HashMap<>();
            var result = runner.runHeadless(prompt, opts.timeoutMs(), event -> {
                if (event instanceof PassthroughStreamParser.SessionInit init) {
                    try { saveNativeId(opts.sessionId(), cwd, pinnedFramework, init.sessionId()); }
                    catch (IOException e) { throw new IllegalStateException("Cannot persist native session id", e); }
                } else if (event instanceof PassthroughStreamParser.TextChunk text) {
                    if (answer.length() + text.text().length() > 8_388_608)
                        throw new IllegalStateException("Native answer exceeds 8 MiB");
                    answer.append(text.text());
                    events.accept(HeadlessRunEvent.assistantDelta(opts.sessionId(), text.text()));
                } else if (event instanceof PassthroughStreamParser.ThinkingChunk thinking) {
                    events.accept(HeadlessRunEvent.thinkingDelta(opts.sessionId(), thinking.text()));
                } else if (event instanceof PassthroughStreamParser.ToolUse use) {
                    String id = "native-" + (++toolCount[0]);
                    pendingTools.computeIfAbsent(use.name(), name -> new ArrayDeque<>())
                            .addLast(new PendingTool(id, use.input()));
                    events.accept(HeadlessRunEvent.toolStarted(opts.sessionId(), id, use.name(), use.input()));
                } else if (event instanceof PassthroughStreamParser.ToolComplete done) {
                    ObjectNode detail = JsonUtils.standardMapper().createObjectNode();
                    detail.put("output", done.output());
                    detail.put("exitCode", done.exitCode());
                    var pending = pendingTools.get(done.name());
                    PendingTool call = pending == null ? null : pending.pollFirst();
                    events.accept(HeadlessRunEvent.toolCompleted(opts.sessionId(), call == null ? "" : call.id(), done.name(),
                            call == null ? "" : call.input(), !done.error(), 0, detail));
                } else if (event instanceof PassthroughStreamParser.TokenUsage usage) {
                    events.accept(HeadlessRunEvent.tokenUsage(opts.sessionId(), usage.inputTokens(), usage.outputTokens(),
                            usage.cacheReadTokens(), usage.cacheCreationTokens()));
                } else if (event instanceof PassthroughStreamParser.TurnComplete done) {
                    events.accept(HeadlessRunEvent.tokenUsage(opts.sessionId(), done.inputTokens(), done.outputTokens(),
                            done.cacheReadTokens(), done.cacheCreationTokens()));
                }
            });
            if (!answer.isEmpty()) history.logAgentResponse(framework, answer.toString(), 0);
            if (result.exitCode() != 0) throw new NativeFailure(result.exitCode(), result.error());
            if (result.nativeSessionId() == null || result.nativeSessionId().isBlank())
                throw new IllegalStateException("Native adapter returned no session id; this session cannot be resumed exactly");
            events.accept(HeadlessRunEvent.completed(opts.sessionId(), answer.toString(), 0, (int) toolCount[0]));
            writeAnswer(opts, out, answer.toString());
            return new HeadlessAgentRunner.Result(0, answer.toString(), opts.sessionId());
        } catch (Exception e) {
            int exit = e instanceof NativeFailure nativeFailure ? nativeFailure.exit : 1;
            events.accept(HeadlessRunEvent.failed(opts.sessionId(), e.getMessage(), exit));
            if (opts.outputMode() != HeadlessAgentRunner.OutputMode.JSON) err.println(e.getMessage());
            return new HeadlessAgentRunner.Result(exit, answer.toString(), opts.sessionId());
        } finally {
            if (shutdown != null) {
                try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) { }
            }
            if (runner != null) { runner.cancelHeadless(); runner.cleanup(); }
            if (history != null) history.close();
        }
    }

    private static void writeAnswer(HeadlessAgentRunner.Options opts, PrintStream out, String answer) throws IOException {
        if (opts.outputMode() != HeadlessAgentRunner.OutputMode.JSON
                && opts.outputMode() != HeadlessAgentRunner.OutputMode.QUIET) out.println(answer);
        if (opts.outputLastMessage() != null) Files.writeString(opts.outputLastMessage(), answer, StandardCharsets.UTF_8);
    }

    /** Native config/menu operations must not invoke the standard provider model resolver. */
    static WebCommandResolver.Resolution resolveCommand(HeadlessAgentRunner.Options opts, ChatConfig config, Path cwd)
            throws IOException {
        WebChatInput input = opts.webInput();
        if (input == null) return null;
        String raw = input.rawInput().strip();
        if (input.configQuery() || raw.equals("/model") || raw.startsWith("/model ")) {
            String selected = raw.startsWith("/model ") ? raw.substring(7).strip() : "";
            if (!selected.isEmpty()) {
                if (input.modelVendor() != null && !input.modelVendor().isBlank())
                    return unsupported("/model", "Native frameworks are pinned per session; create a new chat to switch framework");
                config.setModel(selected);
                config.bindSession(opts.sessionId());
            }
            ObjectNode data = JsonUtils.standardMapper().createObjectNode();
            data.put("mode", "passthrough");
            data.put("framework", config.getPassthroughAgent());
            data.put("menu", "model");
            data.put("currentModel", config.getModel());
            data.put("provider", config.getPassthroughAgent());
            data.put("nativeModelSelection", true);
            data.put("liveControls", false);
            data.put("interactiveInput", false);
            if (!selected.isEmpty()) data.putObject("state").put("sessionId", opts.sessionId()).put("model", config.getModel());
            ObjectNode payload = data;
            if (input.configQuery()) {
                payload = JsonUtils.standardMapper().createObjectNode();
                payload.put("menu", "config").put("available", true).put("sessionId", opts.sessionId());
                payload.set("model", data);
            }
            return new WebCommandResolver.Resolution(WebCommandResolver.Status.COMPLETED,
                    input.configQuery() ? "/config" : "/model", "Native model: " + config.getModel(), null, payload);
        }
        if (raw.startsWith("/") && !raw.equals("/help") && !raw.equals("/skills")
                && !raw.startsWith("/insights") && !raw.startsWith("/process"))
            return unsupported(raw.split("\\s+", 2)[0], "This command requires the standard harness or terminal, not native web passthrough");
        if (input.workflowApprove() != null)
            return unsupported("/workflow", "Workflow controls are unsupported for native web passthrough");
        return WebCommandResolver.resolve(input, cwd,
                new WebCommandResolver.RunPermissions(config.getPassthroughAgent(), null, opts.autoApproveTools()));
    }

    private static WebCommandResolver.Resolution unsupported(String command, String message) {
        return new WebCommandResolver.Resolution(WebCommandResolver.Status.NOT_YET_SUPPORTED, command, message, null);
    }

    private Path sessionPath(String session) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(session.getBytes(StandardCharsets.UTF_8));
            return nativeSessions.resolve(HexFormat.of().formatHex(hash) + ".json");
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    String loadNativeId(String session, Path cwd, String framework) throws IOException {
        Path path = sessionPath(session);
        if (!Files.isRegularFile(path)) throw new IOException("No native session id recorded for resume; refusing to start a fresh native session");
        var saved = JsonUtils.standardMapper().readTree(Files.readString(path));
        if (!session.equals(saved.path("session").asText())
                || !cwd.toRealPath().toString().equals(saved.path("folder").asText())
                || !framework.equals(saved.path("framework").asText()))
            throw new IOException("Native session belongs to a different folder or framework");
        String id = saved.path("nativeSessionId").asText();
        if (id.isBlank()) throw new IOException("Recorded native session id is empty; refusing a fresh session");
        return id;
    }

    void saveNativeId(String session, Path cwd, String framework, String id) throws IOException {
        if (id == null || id.isBlank()) throw new IOException("Native session id is empty");
        Files.createDirectories(nativeSessions);
        Path target = sessionPath(session);
        if (Files.exists(target) && !loadNativeId(session, cwd, framework).equals(id))
            throw new IOException("Refusing to replace the pinned native session id");
        ObjectNode saved = JsonUtils.standardMapper().createObjectNode();
        saved.put("version", 1).put("session", session).put("folder", cwd.toRealPath().toString())
                .put("framework", framework).put("nativeSessionId", id);
        Path temporary = Files.createTempFile(nativeSessions, "native-", ".tmp");
        try {
            Files.writeString(temporary, saved.toString(), StandardCharsets.UTF_8);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static final class NativeFailure extends RuntimeException {
        final int exit;
        NativeFailure(int exit, String message) { super(message); this.exit = exit; }
    }
}
