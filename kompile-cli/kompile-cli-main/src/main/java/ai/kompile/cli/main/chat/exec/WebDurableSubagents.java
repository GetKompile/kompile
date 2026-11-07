package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.chat.sources.KompileTranscriptFormat;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.SubagentResumeService;
import ai.kompile.cli.main.chat.config.SubagentChatConfig;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.workflow.WorkflowSessionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Set;

import static ai.kompile.cli.main.chat.exec.WebCommandResolver.Status.*;

/** Web projection of the existing durable-child store; never a second runner or ledger. */
public final class WebDurableSubagents {
    private static final String COMMAND = "/subagents";
    private static final String INTENT = "durable-subagent-resume";
    private static final int MAX_HISTORY = 30;
    private static final int MAX_CHARS = 65_536;
    // Route/role editing belongs to the existing native tool, not this browser entry point.
    private static final Set<String> RESUME_FIELDS = Set.of("session_id", "prompt");
    private final ObjectMapper mapper;
    private final Path conversations;
    private final SubagentResumeService service;

    public WebDurableSubagents() {
        this(KompileHome.homeDirectory().toPath().resolve("conversations"), JsonUtils.standardMapper());
    }

    WebDurableSubagents(Path conversations, ObjectMapper mapper) {
        this.conversations = conversations;
        this.mapper = mapper;
        this.service = new SubagentResumeService(conversations, mapper);
    }

    public WebCommandResolver.Resolution resolve(String arguments, Path directory, ToolContext principal) {
        try {
            requireRead(principal);
            String rest = arguments == null ? "" : arguments.strip();
            if (rest.isEmpty() || rest.equals("saved")) {
                return outcome(COMPLETED, "Saved durable children.", list(directory, principal));
            }
            if (rest.startsWith("show ")) return show(rest.substring(5).strip(), directory);
            if (rest.startsWith("resume ")) {
                ObjectNode request = parseResume(rest.substring(7));
                String id = request.path("session_id").asText();
                requireScoped(id, directory);
                if (service.isLocked(id)) return invalid("This durable child is busy; no continuation was started.");
                ObjectNode data = menu("resume");
                data.put("sessionId", id);
                data.put("hostAction", INTENT);
                data.set("request", request);
                // This is an execution intent, not a successful command outcome. Only the
                // headless host may turn it into COMPLETED after the real tool returns.
                return outcome(INTERACTION_REQUIRED, "Durable child continuation requires host execution.", data);
            }
            return invalid("Usage: /subagents saved | show <session-id> | resume <JSON>");
        } catch (Exception refused) {
            return invalid("Durable child request was refused; check workspace, child id and permissions.");
        }
    }

    ObjectNode list(Path directory, ToolContext principal) throws ToolExecutionException {
        requireRead(principal);
        ObjectNode data = menu("list");
        var children = data.putArray("children");
        for (var row : service.list()) {
            if (!scoped(row.sessionId(), directory)) continue;
            ObjectNode child = children.addObject();
            child.put("sessionId", row.sessionId());
            child.put("name", row.name());
            child.put("agentType", row.agentType());
            child.put("parentSession", row.parentSession());
            child.put("model", row.model());
            child.put("lastActivity", row.lastActivity());
            child.put("lastModified", row.lastModified());
            child.put("locked", service.isLocked(row.sessionId()));
        }
        return data;
    }

    private WebCommandResolver.Resolution show(String id, Path directory) throws IOException {
        requireScoped(id, directory);
        // Use the shared plain-transcript parser, not route sidecars or serialized
        // tool/system blocks from the model ledger.
        var turns = KompileTranscriptFormat.readTurns(conversations.resolve(id + ".txt"));
        var recent = new ArrayDeque<ObjectNode>();
        int remaining = MAX_CHARS;
        for (int i = turns.size() - 1; i >= 0 && recent.size() < MAX_HISTORY && remaining > 0; i--) {
            var turn = turns.get(i);
            if (!Set.of("user", "assistant").contains(turn.role())) continue;
            String text = turn.content() == null ? "" : turn.content();
            if (text.length() > remaining) text = text.substring(text.length() - remaining);
            remaining -= text.length();
            recent.addFirst(mapper.createObjectNode().put("role", turn.role()).put("content", text));
        }
        ObjectNode data = menu("show").put("sessionId", id);
        var history = data.putArray("history");
        recent.forEach(history::add);
        data.put("locked", service.isLocked(id));
        return outcome(COMPLETED, "Saved durable child history.", data);
    }

    static boolean isResume(WebCommandResolver.Resolution resolution) {
        return resolution != null && COMMAND.equals(resolution.command())
                && resolution.status() == INTERACTION_REQUIRED && resolution.data() != null
                && INTENT.equals(resolution.data().path("hostAction").asText());
    }

    /** Recheck scope and authority immediately before the real host tool boundary. */
    WebCommandResolver.Resolution execute(WebCommandResolver.Resolution intent, ToolContext context,
                                          ChatConfig config, AgentRegistry agents) {
        try {
            if (!isResume(intent)) return invalid("Invalid durable child execution intent.");
            requireRead(context);
            ObjectNode request = parseResume(intent.data().path("request").toString());
            String id = request.path("session_id").asText();
            requireScoped(id, context.getWorkingDirectory());
            if (context.isAborted() || Thread.currentThread().isInterrupted())
                return invalid("Durable child continuation was cancelled.");
            if (!nativeRoute(config) || WorkflowSessionContext.enforcementFor(context) != null
                    || WorkflowSessionContext.recorded(context.getSessionId(), context.getWorkingDirectory()) != null)
                return invalid("Durable child continuation requires Standard Chat without a workflow team.");
            var metadata = service.metadataFor(id);
            if (metadata.workflowParticipant() != null && !metadata.workflowParticipant().isBlank())
                return invalid("Durable workflow children cannot be continued through this entry point.");
            var target = agents.get(metadata.agentType());
            if (target == null || !target.isSubagent()) target = agents.get("general");
            if (target == null) return invalid("The native continuation agent is unavailable.");
            // Preview with the same shared route builder used by DirectSubagentRunner.
            // A saved opaque route or custom agent override may not escape via native resume.
            boolean targetRoute = target.getProviderOverride() != null || target.getModelOverride() != null
                    || target.getAuthenticationMethodOverride() != null;
            ChatConfig route = SubagentChatConfig.configure(config,
                    targetRoute ? target.getProviderOverride() : metadata.provider(),
                    targetRoute ? target.getModelOverride() : metadata.model(),
                    target.getThinkingOverride() != null ? target.getThinkingOverride() : metadata.thinking(),
                    targetRoute ? target.getAuthenticationMethodOverride() : metadata.authenticationMethod());
            if (!nativeRoute(route)) return invalid("Durable child continuation requires a native Standard Chat route.");
            if (service.isLocked(id)) return invalid("This durable child is busy; no continuation was started.");
            var registry = context.getToolRegistry();
            if (registry == null || registry.isProductMode() || context.getAgent() == null
                    || !context.getAgent().canSpawnSubagents() || context.isSupervisedChild()
                    || ai.kompile.cli.main.chat.agent.SubagentDelegationGuard.blocks("subagent_resume")
                    || registry.getToolsForAgent(context.getAgent()).stream().noneMatch(t -> t.id().equals("subagent_resume")))
                return invalid("Durable child continuation is not available to the current principal.");
            context.checkPermission("task", "Resume durable child");
            request.put("agent_type", target.getName());
            request.put("action", "resume");
            request.put("background", false);
            ToolResult result = registry.execute("subagent_resume", request, context);
            if (context.isAborted() || Thread.currentThread().isInterrupted())
                return invalid("Durable child continuation was cancelled.");
            if (result == null || result.isError()) return invalid("Durable child continuation was refused or failed.");
            return outcome(COMPLETED, result.getOutput() == null ? "" : result.getOutput(),
                    menu("resume").put("sessionId", id));
        } catch (Exception refused) {
            // Provider/auth/hook exceptions may contain secrets. Never reflect them.
            if (refused instanceof InterruptedException) Thread.currentThread().interrupt();
            return invalid("Durable child continuation was refused or failed.");
        }
    }

    static boolean nativeRoute(ChatConfig config) {
        return config != null && config.getProvider() != null && !config.getProvider().isBlank()
                && "standard".equalsIgnoreCase(config.getChatMode())
                && !config.isKompileServer() && !config.isKompileLocalServing() && !config.isOpenCodeNative();
    }

    private ObjectNode parseResume(String json) throws IOException {
        if (json == null || json.length() > MAX_CHARS) throw new IOException("Invalid request");
        JsonNode parsed = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(json);
        if (!(parsed instanceof ObjectNode request)) throw new IOException("Invalid request");
        var fields = request.fieldNames();
        while (fields.hasNext()) {
            String key = fields.next();
            if (!RESUME_FIELDS.contains(key) || !request.get(key).isTextual()
                    || request.get(key).asText().isBlank()) throw new IOException("Invalid request");
        }
        if (!validId(request.path("session_id").asText()) || request.path("prompt").asText().isBlank())
            throw new IOException("Invalid request");
        return request;
    }

    private static boolean validId(String id) {
        return id != null && id.length() <= 200 && id.matches("subagent-[A-Za-z0-9_-]+");
    }

    private void requireScoped(String id, Path directory) throws IOException {
        if (!scoped(id, directory)) throw new IOException("Child unavailable");
    }

    private boolean scoped(String id, Path directory) {
        if (!validId(id) || directory == null) return false;
        try {
            Path root = conversations.toRealPath();
            // Artifact aliases (even within the store) could substitute a main ledger
            // or another child's transcript. Workspace aliases may target the same real dir.
            for (String suffix : new String[]{".txt", ".subagent.json", ".ledger.jsonl", ".lock"}) {
                Path file = conversations.resolve(id + suffix);
                if (Files.isSymbolicLink(file)
                        || (Files.exists(file) && !file.toRealPath().getParent().equals(root))) return false;
            }
            var metadata = service.metadataFor(id);
            if (metadata == null || !id.equals(metadata.sessionId())) return false;
            String recorded = KompileTranscriptFormat.readHeader(conversations.resolve(id + ".txt")).workingDirectory();
            return recorded != null && !recorded.isBlank()
                    && Path.of(recorded).toRealPath().equals(directory.toRealPath());
        } catch (IOException | RuntimeException unavailable) {
            return false;
        }
    }

    private static void requireRead(ToolContext principal) throws ToolExecutionException {
        if (principal == null || principal.getAgent() == null) throw new ToolExecutionException("Principal unavailable", true);
        principal.checkPermission("read", "Read durable child conversations");
    }

    private ObjectNode menu(String action) {
        return mapper.createObjectNode().put("menu", "durable-subagents").put("action", action);
    }

    private static WebCommandResolver.Resolution invalid(String text) { return outcome(INVALID, text, null); }

    private static WebCommandResolver.Resolution outcome(WebCommandResolver.Status status, String text, JsonNode data) {
        return new WebCommandResolver.Resolution(status, COMMAND, text, null, data);
    }
}
