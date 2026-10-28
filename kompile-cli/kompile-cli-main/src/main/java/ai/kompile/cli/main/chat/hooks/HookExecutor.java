package ai.kompile.cli.main.chat.hooks;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Synchronous command hooks around the already-authorized host tool boundary. */
public final class HookExecutor {
    private static final int OUTPUT_LIMIT = 65_536;
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);
    private HookExecutor() { }
    @FunctionalInterface public interface Invocation {
        ToolResult run(JsonNode input) throws ToolExecutionException;
    }

    public static ToolResult execute(String tool, JsonNode input, ToolContext context, Invocation invocation)
            throws ToolExecutionException {
        if (context == null) return invocation.run(input);
        return execute(tool, input, context, invocation, new HookManager(context.getWorkingDirectory()));
    }

    static ToolResult execute(String tool, JsonNode input, ToolContext context, Invocation invocation, HookManager manager)
            throws ToolExecutionException {
        if (tool.equals("hooks") || ACTIVE.get() || context.isHookExecutionSuppressed()) return invocation.run(input);
        if (context.isAborted()) throw new ToolExecutionException("Tool call cancelled before hooks");
        List<HookManager.Hook> hooks;
        try { hooks = manager.load(); }
        catch (Exception invalid) { return ToolResult.error("Hook configuration error (tool not executed): " + invalid.getMessage()); }
        if (hooks.stream().noneMatch(h -> h.matches(tool))) return invocation.run(input);
        ACTIVE.set(true);
        List<String> notes = new ArrayList<>();
        try {
            String blocked = fire(hooks, "PreToolUse", tool, input, null, null, context, notes);
            if (blocked != null) return annotate(ToolResult.error("Blocked by PreToolUse hook: " + blocked), notes, context);
            ToolResult result;
            try { result = invocation.run(input); }
            catch (ToolExecutionException | RuntimeException failure) {
                fire(hooks, "PostToolUseFailure", tool, input, null, failure.getMessage(), context, notes);
                for (String note : notes) context.emitOutput(note);
                throw failure;
            }
            fire(hooks, result.isError() ? "PostToolUseFailure" : "PostToolUse", tool, input,
                    result, result.isError() ? result.getOutput() : null, context, notes);
            return annotate(result, notes, context);
        } finally { ACTIVE.remove(); }
    }

    private static String fire(List<HookManager.Hook> hooks, String event, String tool, JsonNode input,
                               ToolResult result, String error, ToolContext context, List<String> notes) {
        boolean pre = event.equals("PreToolUse");
        for (HookManager.Hook hook : hooks) {
            if (!hook.event().equals(event) || !hook.matches(tool)) continue;
            for (HookManager.Command command : hook.commands()) {
                String label = "[hook " + event + " " + hook.scope() + ":" + hook.id() + "] ";
                try {
                    context.checkPermission("hooks", "Execute " + label + command.command());
                    ObjectNode payload = JsonUtils.standardMapper().createObjectNode();
                    payload.put("hook_event_name", event).put("session_id", context.getSessionId())
                            .put("cwd", context.getWorkingDirectory().toString()).put("tool_name", tool);
                    payload.set("tool_input", input);
                    if (result != null && !result.isError()) {
                        ObjectNode response = payload.putObject("tool_response");
                        response.put("title", result.getTitle()).put("output", result.getOutput());
                        response.set("metadata", JsonUtils.standardMapper().valueToTree(result.getMetadata()));
                    }
                    if (event.equals("PostToolUseFailure")) {
                        payload.put("error", error == null ? "Tool execution failed" : error)
                                .put("is_interrupt", context.isAborted() || Thread.currentThread().isInterrupted());
                    }
                    Output output = run(command, payload, context);
                    if (output.exit() != 0) {
                        String message = label + "exit " + output.exit() + ": " + output.stderr();
                        if (pre) return message;
                        notes.add(message + " (tool has already completed)");
                        continue;
                    }
                    String stdout = output.stdout().strip();
                    if (!output.stderr().isBlank()) notes.add(label + output.stderr().strip());
                    if (stdout.startsWith("{") || stdout.startsWith("[")) {
                        JsonNode response = JsonUtils.standardMapper().reader()
                                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(stdout);
                        if (!response.isObject()) throw new IllegalArgumentException("Hook response must be a JSON object");
                        JsonNode specific = response.path("hookSpecificOutput");
                        if (!specific.isMissingNode() && !specific.isObject()) throw new IllegalArgumentException("hookSpecificOutput must be an object");
                        if (response.has("continue") && !response.get("continue").isBoolean()) throw new IllegalArgumentException("continue must be boolean");
                        for (String field : List.of("decision", "reason", "stopReason", "systemMessage")) {
                            if (response.has(field) && !response.get(field).isTextual()) throw new IllegalArgumentException(field + " must be a string");
                        }
                        for (String field : List.of("permissionDecision", "permissionDecisionReason", "additionalContext")) {
                            if (specific.has(field) && !specific.get(field).isTextual()) throw new IllegalArgumentException(field + " must be a string");
                        }
                        if (response.has("decision") && !List.of("approve", "block").contains(response.path("decision").asText())) {
                            throw new IllegalArgumentException("Unsupported decision; use hookSpecificOutput.permissionDecision");
                        }
                        if (specific.has("hookEventName") && !specific.path("hookEventName").asText().equals(event)) {
                            throw new IllegalArgumentException("Hook response event does not match " + event);
                        }
                        // Rewriting after the judge/coordinator reviewed the call would bypass those gates.
                        if (specific.has("updatedInput") && !specific.get("updatedInput").equals(input)) {
                            throw new IllegalArgumentException("updatedInput is unsupported; arguments cannot change after host supervision");
                        }
                        String decision = specific.path("permissionDecision").asText("");
                        if (!List.of("", "allow", "deny", "ask").contains(decision)) throw new IllegalArgumentException("Unknown permissionDecision: " + decision);
                        boolean stop = response.has("continue") && !response.path("continue").asBoolean(true);
                        if (pre && (decision.equals("deny") || decision.equals("ask") || stop
                                || response.path("decision").asText().equals("block"))) {
                            return label + specific.path("permissionDecisionReason").asText(
                                    response.path("stopReason").asText("Hook requested " + (decision.isEmpty() ? "stop" : decision)));
                        }
                        if (specific.has("additionalContext")) notes.add(label + specific.path("additionalContext").asText());
                        if (response.has("systemMessage")) notes.add(label + response.path("systemMessage").asText());
                        if (!pre && (decision.equals("deny") || stop || response.path("decision").asText().equals("block"))) {
                            notes.add(label + response.path("reason").asText("Post hook requested blocking; tool has already completed"));
                        }
                    } else if (!stdout.isEmpty()) notes.add(label + stdout);
                } catch (Exception failure) {
                    String message = label + "failed: " + failure.getMessage();
                    if (pre) return message;
                    notes.add(message + " (tool has already completed)");
                }
            }
        }
        return null;
    }

    private record Output(int exit, String stdout, String stderr) { }

    private static Output run(HookManager.Command hook, ObjectNode payload, ToolContext context) throws Exception {
        if (context.isAborted() || Thread.currentThread().isInterrupted()) throw new InterruptedException("Hook cancelled");
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        ProcessBuilder builder = windows ? new ProcessBuilder("cmd.exe", "/c", hook.command())
                : new ProcessBuilder("bash", "-c", hook.command());
        builder.directory(context.getWorkingDirectory().toFile());
        builder.environment().put("KOMPILE_PROJECT_DIR", context.getWorkingDirectory().toString());
        builder.environment().put("CLAUDE_PROJECT_DIR", context.getWorkingDirectory().toString());
        builder.environment().put("KOMPILE_SESSION_ID", context.getSessionId());
        // Inherited by a nested kompile/MCP host started from this hook.
        builder.environment().put("KOMPILE_HOOK_RUNNING", "1");
        Process process = builder.start();
        var io = Executors.newFixedThreadPool(3, runnable -> {
            Thread thread = new Thread(runnable, "kompile-hook-io");
            thread.setDaemon(true);
            return thread;
        });
        java.util.Set<ProcessHandle> descendants = new java.util.HashSet<>();
        try {
            Future<String> stdout = io.submit(() -> drain(process.getInputStream()));
            Future<String> stderr = io.submit(() -> drain(process.getErrorStream()));
            Future<?> stdin = io.submit(() -> {
                try (var stream = process.getOutputStream()) {
                    stream.write((payload.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                } catch (java.io.IOException closed) {
                    // A hook that exits without reading input is valid (e.g. `exit 2`).
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(hook.timeout());
            while (true) {
                process.descendants().forEach(descendants::add);
                if (context.isAborted() || Thread.currentThread().isInterrupted()) throw new InterruptedException("Hook cancelled");
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new java.util.concurrent.TimeoutException("Hook timed out after " + hook.timeout() + " seconds");
                if (process.waitFor(Math.min(TimeUnit.NANOSECONDS.toMillis(remaining) + 1, 100), TimeUnit.MILLISECONDS)) break;
            }
            long remaining = Math.max(1, deadline - System.nanoTime());
            stdin.get(remaining, TimeUnit.NANOSECONDS);
            String out = stdout.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            String err = stderr.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            return new Output(process.exitValue(), out, err);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            process.descendants().forEach(descendants::add);
            for (ProcessHandle descendant : descendants) if (descendant.isAlive()) descendant.destroyForcibly();
            if (process.isAlive()) process.destroyForcibly();
            process.getInputStream().close();
            process.getErrorStream().close();
            process.getOutputStream().close();
            io.shutdownNow();
        }
    }

    private static String drain(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream retained = new ByteArrayOutputStream();
        byte[] bytes = new byte[4096];
        int count;
        boolean truncated = false;
        while ((count = input.read(bytes)) >= 0) {
            int keep = Math.min(count, OUTPUT_LIMIT - retained.size());
            if (keep > 0) retained.write(bytes, 0, keep);
            if (keep < count) truncated = true;
        }
        if (truncated) throw new java.io.IOException("Hook output exceeds 64 KiB");
        return retained.toString(StandardCharsets.UTF_8);
    }

    private static ToolResult annotate(ToolResult result, List<String> notes, ToolContext context) {
        if (notes.isEmpty()) return result;
        // Hosts intentionally do not print the body again for streamed tools.
        if (result.isOutputStreamed()) notes.forEach(context::emitOutput);
        ToolResult annotated = new ToolResult(result.getTitle(), result.getOutput() + "\n" + String.join("\n", notes),
                result.getMetadata(), result.isError());
        ObjectNode presentation = ai.kompile.cli.main.chat.mcp.McpBundleToolLoader.presentation(result);
        return presentation == null ? annotated : ai.kompile.cli.main.chat.mcp.McpBundleToolLoader.reattach(annotated, presentation);
    }
}
