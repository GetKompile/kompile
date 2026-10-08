package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.main.chat.PassthroughStreamParser;
import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.LiveModelDiscovery;
import ai.kompile.cli.main.chat.config.ModelCatalogSelection;
import ai.kompile.cli.main.chat.config.ModelDiscovery;
import ai.kompile.cli.main.chat.config.SetupWizard;
import ai.kompile.cli.main.chat.config.SystemPromptManager;
import ai.kompile.cli.main.chat.tools.NativeResumeCoordinator;
import ai.kompile.cli.common.ChatWorkspaceStore;
import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.chat.sources.ChatSourceRegistry;
import ai.kompile.cli.common.chat.sources.ChatTurn;
import ai.kompile.cli.common.chat.sources.KompileTranscriptFormat;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/** One bounded native turn, on the existing CLI JSONL transport. No terminal or latest-session fallback. */
public final class HeadlessPassthroughRunner {
    @FunctionalInterface
    interface RunnerFactory { SubprocessAgentRunner create(String framework, Path cwd, ChatConfig config, boolean skip); }

    /** Starts a framework's own session already holding the chat's transcript; returns its native id. */
    @FunctionalInterface
    interface NativeSeeder { String seed(String framework, List<ChatHistory.Turn> turns, Path cwd) throws IOException; }

    private final Path nativeSessions;
    private final RunnerFactory factory;
    private final ai.kompile.cli.common.ChatWorkspaceStore workspace;
    private final Function<ChatConfig, ModelDiscovery.Result> modelDiscovery;
    private final NativeSeeder seeder;
    private final ChatSourceRegistry sources;

    public HeadlessPassthroughRunner() {
        this(defaultNativeSessions(),
                (framework, cwd, config, skip) -> new SubprocessAgentRunner(framework, cwd.toString(),
                        skip, true, null, 0, SystemPromptManager.resolve(null, null, null), null, null));
    }

    HeadlessPassthroughRunner(Path nativeSessions, RunnerFactory factory) {
        this(nativeSessions, factory, new ai.kompile.cli.common.ChatWorkspaceStore());
    }

    HeadlessPassthroughRunner(Path nativeSessions, RunnerFactory factory,
                              ai.kompile.cli.common.ChatWorkspaceStore workspace) {
        this(nativeSessions, factory, workspace, WebModelCatalog::discover);
    }

    HeadlessPassthroughRunner(Path nativeSessions, RunnerFactory factory,
                              ai.kompile.cli.common.ChatWorkspaceStore workspace,
                              Function<ChatConfig, ModelDiscovery.Result> modelDiscovery) {
        this(nativeSessions, factory, workspace, modelDiscovery, NativeResumeCoordinator::seed);
    }

    HeadlessPassthroughRunner(Path nativeSessions, RunnerFactory factory,
                              ai.kompile.cli.common.ChatWorkspaceStore workspace,
                              Function<ChatConfig, ModelDiscovery.Result> modelDiscovery, NativeSeeder seeder) {
        this(nativeSessions, factory, workspace, modelDiscovery, seeder, ChatSourceRegistry.getInstance());
    }

    HeadlessPassthroughRunner(Path nativeSessions, RunnerFactory factory,
                              ai.kompile.cli.common.ChatWorkspaceStore workspace,
                              Function<ChatConfig, ModelDiscovery.Result> modelDiscovery, NativeSeeder seeder,
                              ChatSourceRegistry sources) {
        this.nativeSessions = nativeSessions;
        this.factory = factory;
        this.workspace = workspace;
        this.modelDiscovery = modelDiscovery;
        this.seeder = seeder;
        this.sources = sources;
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
            WebCommandResolver.Resolution command = resolveCommand(opts, config, cwd, modelDiscovery, nativeSessions);
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
            // A vendor chat opened in Kompile chat and switched here before its first turn still gets
            // its vendor history into the chat's transcript, so a later Kompile turn has it too.
            carryOverNativeTranscript(nativeSessions, workspace, sources, opts.sessionId(), cwd);
            var reference = workspace.findChat(cwd, opts.sessionId());
            String nativeId;
            List<ChatHistory.Turn> missed = List.of();
            // An imported native chat resumes its source session when run on that source's framework,
            // whichever framework it was opened in; any other framework's own recorded session applies.
            if (reference != null && reference.nativeSource() != null
                    && framework.equals(ChatWorkspaceStore.nativeFramework(reference.nativeSource()))) {
                nativeId = reference.nativeSessionId();
                // The source session holds history only it has, so it is resumed rather than re-seeded;
                // the turns other vendors ran since it last answered are handed to it with this prompt.
                missed = missedTurns(opts.sessionId(), cwd, framework);
                // Pin before launch, so a provider returning a different ID fails closed even on first use.
                saveNativeId(opts.sessionId(), cwd, framework, nativeId);
            } else {
                nativeId = opts.resume() ? loadNativeId(opts.sessionId(), cwd, framework) : null;
                // A framework this chat switched to starts its own session holding the chat's transcript so far.
                if (nativeId == null) nativeId = handOff(opts.sessionId(), cwd, framework);
            }
            if (!opts.attachments().isEmpty()) throw new IllegalArgumentException("Native web passthrough attachments are unsupported");
            if (opts.roleName() != null && !opts.roleName().isBlank())
                throw new IllegalArgumentException("Kompile harness roles are unsupported for native web passthrough");
            String prompt = withMissedTurns(missed, command == null ? opts.prompt() : command.modelPrompt());
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
            markTurnsSeen(opts.sessionId(), cwd, framework, history.readTurns().size(), err);
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

    /**
     * The transcript hand-off of a vendor switch, as Kompile's own harness carries its history across
     * vendors: the framework's new session starts with every turn of this chat, whichever vendor ran it.
     * Null when the chat has no turns yet. Fails the turn rather than silently dropping the conversation.
     */
    private String handOff(String session, Path cwd, String framework) throws IOException {
        if (!ChatHistory.exists(session)) return null;
        List<ChatHistory.Turn> turns = new ChatHistory(session).readTurns();
        if (turns.isEmpty()) return null;
        String id;
        try {
            id = seeder.seed(framework, turns, cwd);
        } catch (IOException | RuntimeException e) {
            throw new IOException("Could not hand this chat's transcript to " + framework + ": " + e.getMessage()
                    + ". Switch back, or start a new chat for " + framework + ".", e);
        }
        saveNativeId(session, cwd, framework, id, turns.size());
        return id;
    }

    /**
     * A vendor chat opened in Kompile chat: before its first turn, the vendor session's turns are copied into
     * the chat's transcript under a marker, and that vendor framework's record is set to the original session
     * having seen them, so a switch back to the vendor resumes it and hands it only the turns since. False when
     * there is nothing to copy: not such a chat, copied already, or the chat has turns of its own. Fails
     * rather than letting the chat continue without its history.
     */
    public static boolean carryOverNativeTranscript(String session, Path cwd) throws IOException {
        return carryOverNativeTranscript(defaultNativeSessions(), new ChatWorkspaceStore(),
                ChatSourceRegistry.getInstance(), session, cwd);
    }

    static boolean carryOverNativeTranscript(Path nativeSessions, ChatWorkspaceStore workspace,
                                             ChatSourceRegistry sources, String session, Path cwd) throws IOException {
        // Workspace chats belong to registered, existing folders only.
        if (session == null || cwd == null || !Files.isDirectory(cwd)) return false;
        Path folder = cwd.toRealPath();
        var reference = workspace.findChat(folder, session);
        if (reference == null || reference.nativeSource() == null || !"standard".equals(reference.framework()))
            return false;
        String source = reference.nativeSource();
        String nativeId = reference.nativeSessionId();
        String framework = ChatWorkspaceStore.nativeFramework(source);
        if (framework == null) return false;
        ChatHistory history = new ChatHistory(session);
        Path transcript = history.getTranscriptFile();
        if (KompileTranscriptFormat.carriesOver(transcript, source, nativeId) || !history.readTurns().isEmpty())
            return false;
        String failure = "Could not carry over this chat's " + source + " history: ";
        var adapter = sources.find(source).orElseThrow(() -> new IOException(failure + "no reader for " + source + " chats"));
        Path nativeFolder = adapter.resolveWorkingDirectory(nativeId)
                .orElseThrow(() -> new IOException(failure + "the " + source + " session is no longer available"))
                .toRealPath();
        if (!nativeFolder.equals(folder)) throw new IOException(failure + "the " + source + " session belongs to another folder");
        List<ChatTurn> turns = adapter.readTurns(nativeId);
        try {
            history.open(null, null, false, folder);
            history.logSystem(KompileTranscriptFormat.carriedOverEvent(source, nativeId));
            for (ChatTurn turn : turns) {
                if ("user".equalsIgnoreCase(turn.role())) history.logUserMessage(turn.content());
                else if ("assistant".equalsIgnoreCase(turn.role())) history.logAssistantMessage(turn.content(), 0, 0);
            }
        } finally {
            history.close();
        }
        if (!KompileTranscriptFormat.carriesOver(transcript, source, nativeId))
            throw new IOException(failure + "the transcript " + transcript + " could not be written");
        pin(nativeSessions, session, folder, framework, nativeId, history.readTurns().size());
        return true;
    }

    /**
     * The chat's turns a resumed session has not seen: every turn after the last one it answered. A
     * record from before turns were counted catches up with the whole transcript once it was switched
     * back to, and with nothing otherwise.
     */
    private List<ChatHistory.Turn> missedTurns(String session, Path cwd, String framework) throws IOException {
        var saved = record(nativeSessions, session, cwd, framework);
        if (saved == null || !ChatHistory.exists(session)) return List.of();
        List<ChatHistory.Turn> turns = new ChatHistory(session).readTurns();
        int seen = saved.has("turnsSeen") ? saved.path("turnsSeen").asInt()
                : saved.path("fresh").asBoolean(false) ? 0 : turns.size();
        return seen >= turns.size() ? List.of() : List.copyOf(turns.subList(Math.max(0, seen), turns.size()));
    }

    static String withMissedTurns(List<ChatHistory.Turn> missed, String prompt) {
        if (missed.isEmpty()) return prompt;
        StringBuilder text = new StringBuilder("This chat continued with another vendor since your last reply. "
                + "These turns are part of our conversation:\n\n");
        for (ChatHistory.Turn turn : missed) {
            text.append("assistant".equalsIgnoreCase(turn.role()) ? "Assistant" : "User").append(":\n")
                    .append(turn.content()).append("\n\n");
        }
        return text.append("My message now:\n").append(prompt).toString();
    }

    /**
     * Records how many of the chat's turns the framework's session holds after a turn it answered. A
     * failure only means the next catch-up repeats turns, so it warns instead of failing the answered turn.
     */
    private void markTurnsSeen(String session, Path cwd, String framework, int turns, PrintStream err) {
        try {
            var saved = record(nativeSessions, session, cwd, framework);
            String id = saved == null ? "" : saved.path("nativeSessionId").asText();
            if (!id.isBlank()) saveNativeId(session, cwd, framework, id, turns);
        } catch (IOException e) {
            err.println("Warning: could not record which turns " + framework + " has seen: " + e.getMessage());
        }
    }

    private static void writeAnswer(HeadlessAgentRunner.Options opts, PrintStream out, String answer) throws IOException {
        if (opts.outputMode() != HeadlessAgentRunner.OutputMode.JSON
                && opts.outputMode() != HeadlessAgentRunner.OutputMode.QUIET) out.println(answer);
        if (opts.outputLastMessage() != null) Files.writeString(opts.outputLastMessage(), answer, StandardCharsets.UTF_8);
    }

    /** Native config/menu operations must not invoke the standard provider model resolver. */
    static WebCommandResolver.Resolution resolveCommand(HeadlessAgentRunner.Options opts, ChatConfig config, Path cwd,
                                                        Function<ChatConfig, ModelDiscovery.Result> modelDiscovery)
            throws IOException {
        return resolveCommand(opts, config, cwd, modelDiscovery, defaultNativeSessions());
    }

    static WebCommandResolver.Resolution resolveCommand(HeadlessAgentRunner.Options opts, ChatConfig config, Path cwd,
                                                        Function<ChatConfig, ModelDiscovery.Result> modelDiscovery,
                                                        Path nativeSessions)
            throws IOException {
        WebChatInput input = opts.webInput();
        if (input == null) return null;
        String raw = input.rawInput().strip();
        if (input.configQuery() || raw.equals("/model") || raw.startsWith("/model ")) {
            String requested = raw.startsWith("/model ") ? raw.substring(7).strip() : "";
            String command = input.configQuery() ? "/config" : "/model";
            // Frameworks and standard vendors are both vendors: "<vendor>:<model>" switches the chat onto
            // either, a bare vendor (or modelVendor) browses its live list, a bare model stays on this framework.
            String agent = config.getPassthroughAgent();
            ChatConfig standard = standardBase(cwd);
            int separator = requested.indexOf(':');
            String vendor;
            String selected;
            if (separator > 0) {
                vendor = requested.substring(0, separator).strip();
                selected = requested.substring(separator + 1).strip();
            } else if (requested.isEmpty()) {
                vendor = input.modelVendor() == null || input.modelVendor().isBlank() ? agent : input.modelVendor().strip();
                selected = "";
            } else if (WebModelCatalog.webFramework(requested) || WebModelCatalog.canonicalVendor(standard, requested) != null) {
                vendor = requested;
                selected = "";
            } else {
                vendor = agent;
                selected = requested;
            }
            WebCommandResolver.Resolution resolution;
            // One live listing per route per request: the config snapshot's thinking section reuses the model's.
            Map<String, ModelDiscovery.Result> listings = new HashMap<>();
            Function<ChatConfig, ModelDiscovery.Result> discover = route -> listings.computeIfAbsent(
                    route.getChatMode() + "\n" + route.getPassthroughAgent() + "\n" + route.getProvider(),
                    key -> modelDiscovery.apply(route));
            // A key shared by a framework and a standard vendor (opencode) means the kind this chat runs now.
            if (vendor.equals(agent) || ChatConfig.getPassthroughAgents().containsKey(vendor)) {
                resolution = frameworkModel(command, config, opts.sessionId(), cwd, vendor, selected, discover,
                        nativeSessions, new ChatSessionStateStore());
            } else if (WebModelCatalog.canonicalVendor(standard, vendor) == null) {
                return unsupported("/model", "'" + vendor + "' is not a vendor or native framework this chat can switch to");
            } else if (!selected.isEmpty()) {
                // Leaving the framework: Kompile's own harness carries the chat's transcript to the vendor.
                return WebCommandResolver.switchToStandardVendor("/model", standard, vendor, selected, opts.sessionId(), cwd);
            } else {
                ObjectNode data = WebCommandResolver.standardVendorModels(standard, vendor);
                if (data == null) return new WebCommandResolver.Resolution(WebCommandResolver.Status.INVALID, command,
                        "Vendor '" + vendor + "' has no configured provider route. Run /setup in the interactive CLI.", null);
                // The chat's own route stays current; the browsed vendor's models are offered for a switch.
                data.putNull("currentModel");
                nativeRoute(data, config);
                WebModelCatalog.putVendors(data, config);
                resolution = new WebCommandResolver.Resolution(WebCommandResolver.Status.COMPLETED, command,
                        "Models for vendor '" + vendor + "': select with /model " + vendor + ":<id>.", null, data);
            }
            if (!input.configQuery() || !resolution.isCommandOutcome() || resolution.exitCode() != 0) return resolution;
            ObjectNode payload = JsonUtils.standardMapper().createObjectNode();
            payload.put("menu", "config").put("available", true).put("sessionId", opts.sessionId());
            payload.set("model", resolution.data());
            payload.set("thinking", nativeThinking(config, discover.apply(config)));
            return new WebCommandResolver.Resolution(WebCommandResolver.Status.COMPLETED, "/config",
                    resolution.text(), null, payload);
        }
        if (raw.equals("/thinking") || raw.startsWith("/thinking ")) {
            String requested = raw.substring("/thinking".length()).strip();
            ModelDiscovery.Result discovery = modelDiscovery.apply(config);
            ObjectNode data = nativeThinking(config, discovery);
            if (!requested.isEmpty() && !"status".equalsIgnoreCase(requested)) {
                String value = "default".equalsIgnoreCase(requested) ? "" : requested;
                String canonical = value.isEmpty() ? "" : nativeThinkingVariants(config, discovery).stream()
                        .map(LiveModelDiscovery.Variant::value).filter(value::equalsIgnoreCase).findFirst().orElse(null);
                if (canonical == null)
                    return new WebCommandResolver.Resolution(WebCommandResolver.Status.INVALID, "/thinking", "'"
                            + requested + "' is not an effort level " + config.getPassthroughAgent() + " offers for "
                            + (config.getModel() == null ? "its default model" : config.getModel()) + "; no change was made", null);
                config.setThinking(canonical.isEmpty() ? null : canonical);
                config.bindSession(opts.sessionId());
                data = nativeThinking(config, discovery);
                data.putObject("state").put("sessionId", opts.sessionId()).put("thinking", canonical);
            }
            return new WebCommandResolver.Resolution(WebCommandResolver.Status.INTERACTION_REQUIRED, "/thinking",
                    "Thinking/effort: " + (config.getThinking() == null ? "framework default" : config.getThinking()), null, data);
        }
        if (raw.startsWith("/") && !raw.equals("/help") && !raw.equals("/skills")
                && !raw.startsWith("/insights") && !raw.startsWith("/process"))
            return unsupported(raw.split("\\s+", 2)[0], "This command requires the standard harness or terminal, not native web passthrough");
        if (input.workflowApprove() != null)
            return unsupported("/workflow", "Workflow controls are unsupported for native web passthrough");
        return WebCommandResolver.resolve(input, cwd,
                new WebCommandResolver.RunPermissions(config.getPassthroughAgent(), null, opts.autoApproveTools()));
    }

    /** The framework's effort levels for the selected model, from its live model metadata, like the new-chat wizard. */
    private static ObjectNode nativeThinking(ChatConfig config, ModelDiscovery.Result discovery) {
        String agent = config.getPassthroughAgent();
        List<LiveModelDiscovery.Variant> variants = nativeThinkingVariants(config, discovery);
        ObjectNode data = JsonUtils.standardMapper().createObjectNode();
        data.put("menu", "thinking");
        data.put("currentThinking", config.getThinking() == null ? "" : config.getThinking());
        data.put("provider", agent);
        data.put("model", config.getModel() == null ? "" : config.getModel());
        data.put("supported", !variants.isEmpty());
        ArrayNode options = data.putArray("thinkingOptions");
        if (!variants.isEmpty()) options.addObject().put("value", "").put("label", "framework default");
        for (LiveModelDiscovery.Variant variant : variants)
            options.addObject().put("value", variant.value()).put("label", variant.label());
        if (variants.isEmpty()) data.put("note", !SetupWizard.supportsPassthroughThinking(agent, config.isPassthroughManaged())
                ? agent + " does not take an effort level in this launch mode."
                : config.getModel() == null ? "Choose a model to see its effort levels."
                : discovery != null && discovery.status() != ModelDiscovery.Status.SUCCESS && !discovery.message().isBlank()
                ? "Effort levels are unavailable: " + discovery.message()
                : agent + " lists no effort levels for " + config.getModel() + ".");
        return data;
    }

    private static List<LiveModelDiscovery.Variant> nativeThinkingVariants(ChatConfig config, ModelDiscovery.Result discovery) {
        if (discovery == null || config.getModel() == null
                || !SetupWizard.supportsPassthroughThinking(config.getPassthroughAgent(), config.isPassthroughManaged()))
            return List.of();
        return discovery.models().stream().filter(model -> model.id().equalsIgnoreCase(config.getModel())).findFirst()
                .map(LiveModelDiscovery.Model::thinkingVariants).orElse(List.of());
    }

    private static WebCommandResolver.Resolution unsupported(String command, String message) {
        return new WebCommandResolver.Resolution(WebCommandResolver.Status.NOT_YET_SUPPORTED, command, message, null);
    }

    /**
     * A native framework's live models, browsed or selected from any chat. Selecting a model of another
     * framework moves the chat onto it — from a framework or from a standard vendor — and its next turn
     * starts that framework's own session holding the chat's transcript.
     */
    public static WebCommandResolver.Resolution frameworkModel(String command, ChatConfig config, String sessionId,
                                                               Path cwd, String target, String selected,
                                                               Function<ChatConfig, ModelDiscovery.Result> modelDiscovery) {
        return frameworkModel(command, config, sessionId, cwd, target, selected, modelDiscovery, new ChatSessionStateStore());
    }

    static WebCommandResolver.Resolution frameworkModel(String command, ChatConfig config, String sessionId, Path cwd,
                                                        String target, String selected,
                                                        Function<ChatConfig, ModelDiscovery.Result> modelDiscovery,
                                                        ChatSessionStateStore store) {
        return frameworkModel(command, config, sessionId, cwd, target, selected, modelDiscovery,
                defaultNativeSessions(), store);
    }

    static WebCommandResolver.Resolution frameworkModel(String command, ChatConfig config, String sessionId, Path cwd,
                                                        String target, String selected,
                                                        Function<ChatConfig, ModelDiscovery.Result> modelDiscovery,
                                                        Path nativeSessions, ChatSessionStateStore store) {
        boolean nativeRoute = "passthrough".equals(config.getChatMode());
        String agent = nativeRoute ? config.getPassthroughAgent() : null;
        boolean switching = !target.equals(agent);
        if (switching && !WebModelCatalog.webFramework(target))
            return unsupported("/model", "'" + target + "' is not a native framework that runs in the browser");
        ChatConfig listing = config;
        if (switching) {
            listing = config.copy();
            onFramework(listing, target);
            listing.setModel(null);
        }
        // The framework's live list, decided exactly like the terminal /model picker.
        ModelDiscovery.Result discovery = modelDiscovery.apply(listing);
        ModelCatalogSelection.CatalogList catalog = ModelCatalogSelection.listForPicker(discovery, target);
        List<String> listed = catalog.models();
        if (!selected.isEmpty()) {
            if (ModelCatalogSelection.decisionFor(discovery, target, selected)
                    == ModelCatalogSelection.SelectionDecision.UNKNOWN)
                return unsupported("/model", "'" + selected + "' is not in " + target
                        + "'s live model list or the last known good catalog; choose a listed model");
            if (sessionId == null || sessionId.isBlank())
                return new WebCommandResolver.Resolution(WebCommandResolver.Status.INVALID, command,
                        "Choose a chat to switch; no state was changed.", null);
            try {
                // Always a fresh session on switching in: the hand-off seeds it with the whole transcript,
                // including turns other vendors ran since this framework last had the chat.
                if (switching) startFreshNativeSession(nativeSessions, sessionId, cwd, target);
                ChatConfig chosen = switching ? listing : config;
                chosen.setModel(listed.stream().filter(id -> id.equalsIgnoreCase(selected)).findFirst().orElse(selected));
                // An effort level the newly selected model does not offer is dropped, as the terminal picker does.
                if (chosen.getThinking() != null && nativeThinkingVariants(chosen, discovery).stream()
                        .noneMatch(variant -> variant.value().equals(chosen.getThinking()))) chosen.setThinking(null);
                chosen.bindSession(sessionId);
                if (switching) onFramework(config, target);
                config.setModel(chosen.getModel());
                config.setThinking(chosen.getThinking());
            } catch (IOException | RuntimeException e) {
                return new WebCommandResolver.Resolution(WebCommandResolver.Status.INVALID, command,
                        "Could not switch this chat to " + target + ": " + e.getMessage(), null);
            }
            // A standard route's web model override belonged to the vendor this chat left.
            if (!nativeRoute && !store.clearRoute(sessionId, cwd).applied())
                return new WebCommandResolver.Resolution(WebCommandResolver.Status.INVALID, command, "Switched to "
                        + target + ", but an earlier web model override could not be cleared while the session "
                        + "state is locked; switch again.", null);
            listing = config;
        }
        ObjectNode data = JsonUtils.standardMapper().createObjectNode();
        data.put("menu", "model");
        data.put("currentModel", listing == config ? config.getModel() : null);
        data.put("provider", target);
        if (listing != config) data.put("vendor", target);
        // The chat's own route, not the browsed vendor's, decides what the rest of the dialog offers.
        if ("passthrough".equals(config.getChatMode())) nativeRoute(data, config);
        WebModelCatalog.putVendors(data, config);
        ArrayNode models = data.putArray("models");
        for (String id : listed) {
            ObjectNode entry = models.addObject().put("id", id);
            if (listing == config && id.equals(config.getModel())) entry.put("current", true);
        }
        data.put("liveListingAvailable", !catalog.fromFallback() && !listed.isEmpty());
        String note = !catalog.banner().isBlank() ? catalog.banner()
                : listed.isEmpty() && discovery != null ? discovery.message() : null;
        if (note != null && !note.isBlank()) data.put("note", note);
        if (!selected.isEmpty()) data.putObject("state").put("sessionId", sessionId)
                .put("framework", config.getPassthroughAgent()).put("model", config.getModel());
        return new WebCommandResolver.Resolution(WebCommandResolver.Status.COMPLETED, command,
                selected.isEmpty() ? "Models for " + target + ": select with /model " + target + ":<id>."
                        : "Native model: " + target + " / " + config.getModel(), null, data);
    }

    private static void nativeRoute(ObjectNode data, ChatConfig config) {
        data.put("mode", "passthrough");
        data.put("framework", config.getPassthroughAgent());
        data.put("nativeModelSelection", true);
        data.put("liveControls", false);
        data.put("interactiveInput", false);
    }

    /** Puts a chat config onto a framework's managed one-turn route; the effort level is the framework's own. */
    private static void onFramework(ChatConfig config, String framework) {
        config.setChatMode("passthrough");
        config.setPassthroughManaged(true);
        config.setPassthroughAgent(framework);
        config.setThinking(null);
    }

    /** The folder's standard settings a switch onto a standard vendor builds from, whatever route the chat runs. */
    private static ChatConfig standardBase(Path cwd) {
        ChatConfig folder = ChatConfig.loadOrFromEnv(cwd);
        ChatConfig base = folder == null ? new ChatConfig() : folder.copy();
        base.setChatMode("standard");
        return base;
    }

    private static Path defaultNativeSessions() {
        return KompileHome.homeDirectory().toPath().resolve("conversations/native-sessions");
    }

    private static Path hashed(Path directory, String key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return directory.resolve(HexFormat.of().formatHex(hash) + ".json");
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /**
     * This chat's record for one framework, or null when that framework has none yet. Records are kept per
     * framework, so switching a chat's framework never touches another framework's session; a record from
     * before that (one per chat) still answers for the framework it names.
     */
    private static com.fasterxml.jackson.databind.JsonNode record(Path directory, String session, Path cwd,
                                                                  String framework) throws IOException {
        Path path = hashed(directory, session + "\n" + framework);
        boolean perChat = !Files.isRegularFile(path);
        if (perChat) path = hashed(directory, session);
        if (!Files.isRegularFile(path)) return null;
        var saved = JsonUtils.standardMapper().readTree(Files.readString(path));
        if (perChat && !framework.equals(saved.path("framework").asText())) return null;
        if (!session.equals(saved.path("session").asText())
                || !cwd.toRealPath().toString().equals(saved.path("folder").asText())
                || !framework.equals(saved.path("framework").asText()))
            throw new IOException("Native session belongs to a different folder or framework");
        return saved;
    }

    /** {@code turnsSeen} is how many of the chat's turns that session holds; negative when unknown. */
    private static void write(Path directory, String session, Path cwd, String framework, String id,
                              int turnsSeen) throws IOException {
        Files.createDirectories(directory);
        ObjectNode saved = JsonUtils.standardMapper().createObjectNode();
        saved.put("version", 2).put("session", session).put("folder", cwd.toRealPath().toString())
                .put("framework", framework).put("nativeSessionId", id);
        // A switched-in framework has no session of its own yet; its first turn starts one.
        if (id.isEmpty()) saved.put("fresh", true);
        if (turnsSeen >= 0) saved.put("turnsSeen", turnsSeen);
        Path temporary = Files.createTempFile(directory, "native-", ".tmp");
        try {
            Files.writeString(temporary, saved.toString(), StandardCharsets.UTF_8);
            Files.move(temporary, hashed(directory, session + "\n" + framework),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    /**
     * A chat switched to {@code framework}: its next turn starts a new native session for that framework,
     * seeded with the chat's whole transcript. A session the framework had earlier in this chat is not
     * resumed — it would miss every turn other vendors ran since.
     */
    public static void startFreshNativeSession(String session, Path cwd, String framework) throws IOException {
        startFreshNativeSession(defaultNativeSessions(), session, cwd, framework);
    }

    static void startFreshNativeSession(Path directory, String session, Path cwd, String framework) throws IOException {
        if (session == null || session.isBlank()) throw new IOException("Choose a chat to switch");
        // Refuses a record that belongs to another folder. An imported chat's source session is resumed
        // rather than replaced, so how far it has seen the chat is kept for its catch-up.
        var existing = record(directory, session, cwd, framework);
        write(directory, session, cwd, framework, "", turnsSeen(existing));
    }

    private static int turnsSeen(com.fasterxml.jackson.databind.JsonNode saved) {
        return saved != null && saved.has("turnsSeen") ? saved.path("turnsSeen").asInt() : -1;
    }

    /** Null when the framework was switched in and has not started its session yet. */
    String loadNativeId(String session, Path cwd, String framework) throws IOException {
        var saved = record(nativeSessions, session, cwd, framework);
        if (saved == null) throw new IOException("No native session id recorded for resume; refusing to start a fresh native session");
        String id = saved.path("nativeSessionId").asText();
        if (id.isBlank() && saved.path("fresh").asBoolean(false)) return null;
        if (id.isBlank()) throw new IOException("Recorded native session id is empty; refusing a fresh session");
        return id;
    }

    void saveNativeId(String session, Path cwd, String framework, String id) throws IOException {
        saveNativeId(session, cwd, framework, id, -1);
    }

    /** {@code turnsSeen} negative keeps what the record already counted. */
    private void saveNativeId(String session, Path cwd, String framework, String id, int turnsSeen) throws IOException {
        pin(nativeSessions, session, cwd, framework, id, turnsSeen);
    }

    private static void pin(Path directory, String session, Path cwd, String framework, String id,
                            int turnsSeen) throws IOException {
        if (id == null || id.isBlank()) throw new IOException("Native session id is empty");
        var existing = record(directory, session, cwd, framework);
        String pinned = existing == null ? "" : existing.path("nativeSessionId").asText();
        if (!pinned.isBlank() && !pinned.equals(id))
            throw new IOException("Refusing to replace the pinned native session id");
        write(directory, session, cwd, framework, id, turnsSeen >= 0 ? turnsSeen : turnsSeen(existing));
    }

    private static final class NativeFailure extends RuntimeException {
        final int exit;
        NativeFailure(int exit, String message) { super(message); this.exit = exit; }
    }
}
