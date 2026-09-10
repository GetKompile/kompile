/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.coordination.CoordinationActivity;
import ai.kompile.cli.main.coordination.ActivityWaitRegistry;
import ai.kompile.cli.main.coordination.CoordinationStateManager;
import ai.kompile.cli.main.coordination.ProcessCoordEntry;
import ai.kompile.cli.main.coordination.SystemActivityCoordinator;
import ai.kompile.cli.main.project.LocalSubprocessWatchdog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic harness gate for tool calls that can materially increase RAM or GPU use.
 *
 * <p>The gate runs below the model/workflow layer. It atomically reserves a user-wide activity
 * lane, checks registered peer work and configured host/GPU capacity admission, and only then
 * invokes the tool. Rejected launches install a host-polled one-shot resource watch. This prevents advisory messages from being bypassed by an agent
 * that never reads its mailbox.</p>
 */
public final class HighMemoryToolCallGuard {

    private static final Set<String> GUARDED_TOOL_IDS = Set.of(
            "bash", "process", "crawl_source", "crawl_documents", "crawl_control",
            "crawl_result", "model_runtime", "pipeline", "graph_embeddings",
            "code_search", "code_graph", "local_code_index", "eval");
    private static final Set<String> TERMINAL_STATES = Set.of(
            "COMPLETED", "FAILED", "CANCELLED", "CANCELED", "KILLED", "LOST", "NOT_FOUND");

    enum ActivityKind {
        BUILD, TEST, CRAWL, MODEL, BENCHMARK, INDEX
    }

    record HighMemoryCall(ActivityKind kind, String description, boolean asynchronous) { }

    record CapacityCheck(boolean admitted, String outcome, String reason,
                         boolean waited, long waitedMs, int samples,
                         Map<String, Object> details) {
        CapacityCheck {
            details = details == null ? Map.of() : Map.copyOf(details);
        }
    }

    @FunctionalInterface
    interface CapacityProbe {
        CapacityCheck check(boolean wait,
                            java.util.function.Consumer<String> waitObserver)
                throws InterruptedException;
    }

    private final CoordinationStateManager coordinator;
    private final CapacityProbe capacityProbe;
    private final ObjectMapper mapper;

    public HighMemoryToolCallGuard(CoordinationStateManager coordinator) {
        this(coordinator, HighMemoryToolCallGuard::checkConfiguredCapacity,
                JsonUtils.standardMapper());
    }

    HighMemoryToolCallGuard(CoordinationStateManager coordinator,
                            CapacityProbe capacityProbe, ObjectMapper mapper) {
        this.coordinator = coordinator;
        this.capacityProbe = capacityProbe;
        this.mapper = mapper == null ? JsonUtils.standardMapper() : mapper;
    }

    /** Return the original tool unless its action space can start or finish high-memory work. */
    public CliTool wrap(CliTool tool) {
        if (tool == null || coordinator == null
                || !GUARDED_TOOL_IDS.contains(canonicalToolName(tool.id()))) {
            return tool;
        }
        return new GuardedTool(tool);
    }

    public ToolResult inspectToolCall(String toolName, JsonNode params, ToolContext context) {
        ResourcePolicy.Decision decision = ResourcePolicy.classify(coordinator.getProjectRoot(), toolName, params);
        HighMemoryCall call = classify(toolName, params);
        ToolResult result = call == null || !decision.high()
                ? ToolResult.success("resource preflight admitted", decision.reason(), Map.of("admitted", true))
                : inspect(call.kind().name(), call.description(), context);
        Map<String, Object> metadata = new LinkedHashMap<>(result.getMetadata());
        metadata.put("resourceClassification", decision);
        return new ToolResult(result.getTitle(), result.getOutput(), metadata, result.isError());
    }

    public ActivityWaitRegistry.Snapshot watchToolCall(String toolName, JsonNode params, ToolContext context) {
        JsonNode snapshot = params.deepCopy();
        HighMemoryCall call = classify(toolName, snapshot);
        String key = toolWaitKey(toolName, snapshot);
        return coordinator.activityWaits().watch(waitSession(context), key,
                call == null ? "TOOL" : call.kind().name(), toolName + " " + snapshot,
                () -> call == null || !ResourcePolicy.classify(coordinator.getProjectRoot(), toolName, snapshot).high()
                        || resourcesAvailable(call));
    }

    /** Point-in-time explicit preflight used by the coordination tool; no lane is retained. */
    public ToolResult inspect(String kind, String description, ToolContext context) {
        HighMemoryCall call = new HighMemoryCall(parseKind(kind),
                description == null || description.isBlank() ? "manual high-memory preflight" : description,
                false);
        List<ProcessCoordEntry> processes = conflictingProcesses();
        if (!processes.isEmpty()) {
            return blockedByProcesses(call, processes);
        }
        SystemActivityCoordinator.ReservationResult reservation = reserve(call, context, "manual");
        if (!reservation.admitted()) {
            return blockedByActivities(call, reservation);
        }
        String activityId = reservation.activity().getActivityId();
        try {
            CapacityCheck capacity = capacityProbe.check(false, reason -> emitWait(context, reason));
            return capacity.admitted()
                    ? ToolResult.success("resource preflight admitted",
                    preflightSummary(call, capacity, reservation.activity(), false),
                    Map.of("admitted", true, "activityId", activityId,
                            "capacity", capacity.details()))
                    : capacityBlocked(call, capacity, activityId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("High-memory preflight was interrupted before work started");
        } finally {
            coordinator.releaseHighMemoryActivity(activityId);
        }
    }

    static HighMemoryCall classify(String toolName, JsonNode params) {
        String tool = canonicalToolName(toolName);
        String action = action(params);
        return switch (tool) {
            case "bash" -> commandCall(text(params, "command"));
            case "process" -> {
                HighMemoryCall command = "launch".equals(action)
                        ? commandCall(text(params, "command")) : null;
                yield command == null ? null
                        : new HighMemoryCall(command.kind(), command.description(), true);
            }
            case "crawl_source", "crawl_documents" -> dryRun(params) ? null
                    : new HighMemoryCall(ActivityKind.CRAWL, tool + " production pipeline",
                    !params.path("waitForCompletion").asBoolean(false)
                            && params.path("async").asBoolean(true));
            case "crawl_control" -> Set.of("start", "retry", "run_step").contains(action)
                    ? new HighMemoryCall(ActivityKind.CRAWL,
                    "crawl_control " + action, true) : null;
            case "model_runtime" -> Set.of("bootstrap", "convert", "import", "optimize")
                    .contains(action)
                    ? new HighMemoryCall(ActivityKind.MODEL,
                    "model_runtime " + action, false) : null;
            case "pipeline" -> Set.of("run", "test").contains(action)
                    ? new HighMemoryCall(ActivityKind.MODEL,
                    "pipeline " + action, "run".equals(action)
                    && !params.path("wait").asBoolean(false)) : null;
            case "graph_embeddings" -> "train".equals(action)
                    ? new HighMemoryCall(ActivityKind.MODEL,
                    "graph embedding training", true) : null;
            case "code_search", "local_code_index" -> "index".equals(action)
                    ? new HighMemoryCall(ActivityKind.INDEX,
                    tool + " index", params.path("background").asBoolean(true)) : null;
            case "code_graph" -> "build".equals(action)
                    ? new HighMemoryCall(ActivityKind.INDEX, "code graph build", false) : null;
            case "eval" -> "run".equals(action)
                    ? new HighMemoryCall(ActivityKind.TEST,
                    "evaluation " + action, false) : null;
            default -> null;
        };
    }

    private static HighMemoryCall commandCall(String command) {
        HighMemoryCall kind = classifyCommand(command);
        return kind != null ? kind : new HighMemoryCall(ActivityKind.BUILD, command, false);
    }

    // Kind is display metadata only; resource cost is decided exclusively by ResourcePolicy.
    static HighMemoryCall classifyCommand(String command) {
        if (command == null) return null;
        List<List<String>> commands = ResourcePolicy.shell(command);
        if (commands == null) return null;
        for (List<String> argv : commands) {
            String executable = argv.get(0);
            executable = executable.substring(executable.lastIndexOf('/') + 1);
            if (Set.of("pytest", "ctest").contains(executable) || argv.contains("test"))
                return new HighMemoryCall(ActivityKind.TEST, command, false);
            if (Set.of("mvn", "mvnw", "gradle", "gradlew", "cmake", "ninja", "make", "native-image").contains(executable))
                return new HighMemoryCall(ActivityKind.BUILD, command, false);
        }
        return null;
    }

    private ToolResult execute(CliTool delegate, JsonNode params, ToolContext context)
            throws ToolExecutionException {
        HighMemoryCall call = classify(delegate.id(), params);
        ResourcePolicy.Decision decision = call == null ? null
                : ResourcePolicy.classify(coordinator.getProjectRoot(), delegate.id(), params);
        ResourcePolicy.Decision previous = ResourcePolicy.ACTIVE_LAUNCH.get();
        ToolResult result;
        try {
            ResourcePolicy.ACTIVE_LAUNCH.set(decision);
            result = executeAdmission(delegate, params, context, call, decision);
        } finally {
            if (previous == null) ResourcePolicy.ACTIVE_LAUNCH.remove();
            else ResourcePolicy.ACTIVE_LAUNCH.set(previous);
        }
        if (decision == null) return result;
        Map<String, Object> metadata = new LinkedHashMap<>(result.getMetadata());
        metadata.put("resourceClassification", decision);
        String output = result.getOutput();
        if (result.isError() && Boolean.FALSE.equals(metadata.get("admitted"))) {
            output += "\nClassification: " + decision.resourceClass() + " — " + decision.reason()
                    + "\nConfigure or preview rules with /resources help.";
        }
        return new ToolResult(result.getTitle(), output, metadata, result.isError());
    }

    private ToolResult executeAdmission(CliTool delegate, JsonNode params, ToolContext context,
                                        HighMemoryCall call, ResourcePolicy.Decision decision)
            throws ToolExecutionException {
        if (context != null && context.isAborted()) return ToolResult.error("Request cancelled before work started");
        if (call == null || !decision.high()) {
            if (call != null) coordinator.activityWaits().cancelMatching(waitSession(context), toolWaitKey(delegate.id(), params));
            ToolResult result = delegate.execute(params, context);
            reconcileTerminalLifecycle(delegate.id(), params, result);
            return result;
        }

        if (context != null && context.isAborted()) {
            return ToolResult.error("High-memory request cancelled before work started");
        }
        List<ProcessCoordEntry> processes = conflictingProcesses();
        if (!processes.isEmpty()) {
            return withResourceWatch(blockedByProcesses(call, processes), call, context, delegate.id(), params);
        }

        SystemActivityCoordinator.ReservationResult reservation = reserve(
                call, context, delegate.id());
        if (!reservation.admitted()) {
            return withResourceWatch(blockedByActivities(call, reservation), call, context, delegate.id(), params);
        }

        CoordinationActivity activity = reservation.activity();
        String activityId = activity.getActivityId();
        int peersNotified = broadcast("activity.intent", activityMessage("starting", activity));
        boolean retained = false;
        CapacityCheck capacity;
        try {
            boolean requireCapacity = call.kind() != ActivityKind.CRAWL;
            // Do not occupy a tool worker (or the lane) waiting for RAM. The host watch
            // will notify the agent when both capacity and peer work are clear.
            capacity = capacityProbe.check(false, reason -> emitWait(context, reason));
            if (!capacity.admitted() && requireCapacity) {
                return withResourceWatch(capacityBlocked(call, capacity, activityId), call, context, delegate.id(), params);
            }
            if (!capacity.admitted()) {
                emitWait(context, firstNonBlank(capacity.reason(),
                        "crawl capacity is constrained; the async crawl will wait before expensive work"));
            }

            if (context != null && context.isAborted()) {
                return ToolResult.error("High-memory request cancelled before work started");
            }
            coordinator.activityWaits().cancelMatching(waitSession(context), toolWaitKey(delegate.id(), params));
            ToolResult result = delegate.execute(params, context);
            reconcileTerminalLifecycle(delegate.id(), params, result);
            if (!result.isError() && call.asynchronous() && !terminal(result)) {
                String processId = value(result, "processId", "process_id");
                long processPid = longValue(result, "pid");
                String externalId = firstNonBlank(
                        value(result, "jobId", "job_id", "runId", "run_id"),
                        value(params, "jobId", "job_id", "runId", "run_id"));
                boolean attached = coordinator.attachHighMemoryActivity(
                        activityId, processId, processPid, externalId);
                // Even if the attachment update loses a transient lock race, retain the
                // original reservation rather than launching uncoordinated work.
                retained = true;
                result = withPreflightMetadata(result, call, capacity, activity,
                        peersNotified, true, attached ? null
                                : "activity remained reserved but async identifiers could not be attached");
            } else {
                result = withPreflightMetadata(result, call, capacity, activity,
                        peersNotified, false, null);
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolExecutionException(
                    "High-memory preflight interrupted before tool execution", e);
        } finally {
            if (!retained) {
                coordinator.releaseHighMemoryActivity(activityId);
                broadcast("activity.complete", activityMessage("finished", activity));
            }
        }
    }

    public ActivityWaitRegistry.Snapshot watch(String kind, String description, ToolContext context) {
        return watch(new HighMemoryCall(parseKind(kind),
                description == null || description.isBlank() ? "blocked high-memory work" : description,
                false), context);
    }

    private ActivityWaitRegistry.Snapshot watch(HighMemoryCall call, ToolContext context) {
        return coordinator.activityWaits().watch(waitSession(context), waitKey(call),
                call.kind().name(), call.description(), () -> resourcesAvailable(call));
    }

    private boolean resourcesAvailable(HighMemoryCall call) {
        if (!conflictingProcesses().isEmpty()) return false;
        // Reserve briefly rather than interpreting an empty, failed query as availability.
        SystemActivityCoordinator.ReservationResult reservation = reserve(call, null, "resource-watch");
        if (!reservation.admitted()) return false;
        try {
            return capacityProbe.check(false, ignored -> { }).admitted();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            coordinator.releaseHighMemoryActivity(reservation.activity().getActivityId());
        }
    }

    private ToolResult withResourceWatch(ToolResult blocked, HighMemoryCall call, ToolContext context,
                                         String toolName, JsonNode params) {
        if (context != null && context.isAborted()) return blocked;
        try {
            ActivityWaitRegistry.Snapshot wait = watchToolCall(toolName, params, context);
            Map<String, Object> metadata = new LinkedHashMap<>(blocked.getMetadata());
            metadata.put("resourceWaitId", wait.waitId());
            metadata.put("wakeSupported", wait.wakeSupported());
            String guidance = wait.wakeSupported()
                    ? "You may pause this turn; the host will wake this session when resources clear."
                    : "Keep a tool response pending with edit_coordinator action='wait_for_activity', wait_id='"
                      + wait.waitId() + "'. On timeout, repeat that wait (do not relaunch the build). "
                      + "This external client cannot be woken after it ends its turn.";
            return new ToolResult(blocked.getTitle(), blocked.getOutput()
                    + "\nResource watch: " + wait.waitId() + "\n" + guidance
                    + "\nUse cancel_activity_wait if abandoning this work. No work is auto-launched.",
                    metadata, true);
        } catch (RuntimeException failure) {
            return new ToolResult(blocked.getTitle(), blocked.getOutput()
                    + "\nNo resource watch was installed: " + failure.getMessage(), blocked.getMetadata(), true);
        }
    }

    private String waitSession(ToolContext context) {
        return context == null || context.getSessionId() == null
                ? coordinator.getSessionId() : context.getSessionId();
    }

    private static String toolWaitKey(String tool, JsonNode params) {
        return canonicalToolName(tool) + ":" + params;
    }

    private static String waitKey(HighMemoryCall call) {
        return call.kind().name() + ":" + call.description();
    }

    private SystemActivityCoordinator.ReservationResult reserve(
            HighMemoryCall call, ToolContext context, String toolName) {
        String agentName = context != null && context.getAgent() != null
                ? firstNonBlank(context.getAgent().getRoleName(), context.getAgent().getName())
                : null;
        return coordinator.reserveHighMemoryActivity(
                agentName, call.kind().name(), canonicalToolName(toolName), call.description());
    }

    private List<ProcessCoordEntry> conflictingProcesses() {
        List<ProcessCoordEntry> blockers = new ArrayList<>();
        for (ProcessCoordEntry process : coordinator.queryProcesses()) {
            if (process == null || !process.isRunningState()) continue;
            // Old records have no retained decision: use the configured policy, never description keywords.
            if ("high".equals(ResourcePolicy.processClass(coordinator.getProjectRoot(), process))) blockers.add(process);
        }
        return List.copyOf(blockers);
    }

    private ToolResult blockedByProcesses(HighMemoryCall call, List<ProcessCoordEntry> blockers) {
        Set<String> notified = new LinkedHashSet<>();
        StringBuilder output = new StringBuilder();
        output.append("High-memory preflight blocked before any work started.\n")
                .append("Requested: ").append(call.kind()).append(" — ")
                .append(call.description()).append("\n")
                .append("Registered high-memory work is already running:\n");
        for (ProcessCoordEntry blocker : blockers) {
            output.append("  - ").append(firstNonBlank(blocker.getProcessId(), "unknown"))
                    .append(" by ").append(firstNonBlank(blocker.getAgentName(), blocker.getSessionId(), "unknown"))
                    .append(": ").append(firstNonBlank(blocker.getDescription(), blocker.getCommand(), "work"))
                    .append('\n');
            if (blocker.getSessionId() != null && notified.add(blocker.getSessionId())) {
                send(blocker.getSessionId(), "activity.request",
                        "High-memory " + call.kind() + " work is waiting for your running process "
                                + blocker.getProcessId() + " to finish.");
            }
        }
        output.append("Wait for the shared lane to clear, then retry. No process was launched.");
        return new ToolResult("high-memory preflight blocked", output.toString(),
                Map.of("admitted", false, "kind", call.kind().name(),
                        "processBlockers", blockers.size()), true);
    }

    private ToolResult blockedByActivities(HighMemoryCall call,
                                           SystemActivityCoordinator.ReservationResult reservation) {
        StringBuilder output = new StringBuilder();
        output.append("High-memory preflight blocked before any work started.\n")
                .append("Requested: ").append(call.kind()).append(" — ")
                .append(call.description()).append("\n")
                .append("Reason: ").append(reservation.reason()).append('\n');
        for (CoordinationActivity blocker : reservation.blockers()) {
            output.append("  - ").append(blocker.getKind()).append(" by ")
                    .append(firstNonBlank(blocker.getAgentName(), blocker.getSessionId(), "unknown"))
                    .append(" in ").append(firstNonBlank(blocker.getProjectRoot(), "unknown project"))
                    .append(": ").append(firstNonBlank(blocker.getDescription(), blocker.getToolName(), "work"))
                    .append('\n');
            if (coordinator.getProjectRoot().toString().equals(blocker.getProjectRoot())) {
                send(blocker.getSessionId(), "activity.request",
                        "High-memory " + call.kind() + " work is waiting for activity "
                                + blocker.getActivityId() + " to finish.");
            }
        }
        output.append("Wait for the shared lane to clear, then retry. No process was launched.");
        return new ToolResult("high-memory preflight blocked", output.toString(),
                Map.of("admitted", false, "kind", call.kind().name(),
                        "activityBlockers", reservation.blockers().size()), true);
    }

    private ToolResult capacityBlocked(HighMemoryCall call, CapacityCheck capacity,
                                       String activityId) {
        broadcast("activity.blocked", "High-memory " + call.kind()
                + " preflight did not start: " + firstNonBlank(capacity.reason(), capacity.outcome()));
        String output = "High-memory preflight blocked before any work started.\n"
                + "Requested: " + call.kind() + " — " + call.description() + "\n"
                + "Capacity: " + firstNonBlank(capacity.reason(), capacity.outcome(), "not admitted") + "\n"
                + "Waited: " + capacity.waitedMs() + " ms across " + capacity.samples() + " sample(s).\n"
                + "Close or finish other memory/GPU-heavy work, inspect edit_coordinator awareness, "
                + "then retry. No process was launched.";
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("admitted", false);
        metadata.put("activityId", activityId);
        metadata.put("kind", call.kind().name());
        metadata.put("outcome", capacity.outcome());
        metadata.put("waitedMs", capacity.waitedMs());
        metadata.put("samples", capacity.samples());
        metadata.put("capacity", capacity.details());
        return new ToolResult("high-memory capacity blocked", output, metadata, true);
    }

    private ToolResult withPreflightMetadata(ToolResult result, HighMemoryCall call,
                                             CapacityCheck capacity,
                                             CoordinationActivity activity,
                                             int peersNotified, boolean retained,
                                             String warning) {
        Map<String, Object> metadata = new LinkedHashMap<>(result.getMetadata());
        Map<String, Object> preflight = new LinkedHashMap<>();
        preflight.put("admitted", true);
        preflight.put("capacityAdmitted", capacity.admitted());
        preflight.put("activityId", activity.getActivityId());
        preflight.put("kind", call.kind().name());
        preflight.put("outcome", capacity.outcome());
        preflight.put("waitedMs", capacity.waitedMs());
        preflight.put("samples", capacity.samples());
        preflight.put("peersNotified", peersNotified);
        preflight.put("retainedForAsyncWork", retained);
        preflight.put("capacity", capacity.details());
        if (warning != null) preflight.put("warning", warning);
        metadata.put("resourcePreflight", preflight);
        return new ToolResult(result.getTitle(), result.getOutput(), metadata, result.isError());
    }

    private void reconcileTerminalLifecycle(String toolName, JsonNode params, ToolResult result) {
        if (result == null) return;
        String tool = canonicalToolName(toolName);
        if (!Set.of("crawl_control", "crawl_result", "pipeline").contains(tool)) return;
        // A cancel request is only an intent. Keep the lane until the backend
        // confirms a terminal state; failed and CANCELLING responses must not
        // reopen the race while a child is still consuming memory.
        if (!terminal(result)) return;
        String externalId = firstNonBlank(
                value(result, "jobId", "job_id", "runId", "run_id"),
                value(params, "jobId", "job_id", "runId", "run_id"));
        if (externalId != null) {
            coordinator.releaseHighMemoryActivityByExternalId(externalId);
        }
    }

    private boolean terminal(ToolResult result) {
        Object terminal = result.getMetadata().get("terminal");
        if (Boolean.TRUE.equals(terminal)
                || "true".equalsIgnoreCase(String.valueOf(terminal))) return true;
        String state = firstNonBlank(value(result, "status", "state"));
        return state != null && TERMINAL_STATES.contains(state.toUpperCase(Locale.ROOT));
    }

    private int broadcast(String kind, String message) {
        try {
            return coordinator.broadcastMessage(kind, message, null).size();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private void send(String sessionId, String kind, String message) {
        if (sessionId == null || sessionId.isBlank()
                || sessionId.equals(coordinator.getSessionId())) return;
        try {
            coordinator.sendMessage(sessionId, kind, message, null);
        } catch (Exception ignored) {
            // The atomic activity lane remains authoritative when a peer mailbox vanished.
        }
    }

    private static void emitWait(ToolContext context, String reason) {
        if (context != null && reason != null && !reason.isBlank()) {
            context.emitOutput("[resource preflight] waiting: " + reason);
        }
    }

    private static String preflightSummary(HighMemoryCall call, CapacityCheck capacity,
                                           CoordinationActivity activity, boolean retained) {
        return "High-memory preflight admitted.\n"
                + "  Activity: " + activity.getActivityId() + "\n"
                + "  Kind: " + call.kind() + "\n"
                + "  Waited: " + capacity.waitedMs() + " ms\n"
                + "  Reservation retained: " + retained;
    }

    private static CapacityCheck checkConfiguredCapacity(
            boolean wait, java.util.function.Consumer<String> waitObserver)
            throws InterruptedException {
        if (!wait) {
            Map<String, Object> sample = LocalSubprocessWatchdog.get().sampleCapacityStatus();
            boolean admitted = Boolean.TRUE.equals(sample.get("wouldAdmit"));
            Map<String, Object> details = mapValue(sample.get("capacity"));
            return new CapacityCheck(admitted,
                    admitted ? "WOULD_ADMIT" : "CONSTRAINED",
                    String.valueOf(sample.getOrDefault("reason",
                            admitted ? "capacity available" : "capacity constrained")),
                    false, 0L, 1, details);
        }
        LocalSubprocessWatchdog.CapacityAdmission admission =
                LocalSubprocessWatchdog.get().awaitCrawlCapacity(waitObserver);
        Map<String, Object> details = new LinkedHashMap<>();
        LocalSubprocessWatchdog.HardwareCapacitySnapshot capacity = admission.capacity();
        if (capacity != null) {
            details.put("sampledAt", capacity.sampledAt().toString());
            details.put("totalRamMb", capacity.totalRamMb());
            details.put("availableRamMb", capacity.availableRamMb());
            details.put("ramUsedFraction", capacity.ramUsedFraction());
            details.put("gpuProbe", capacity.gpuProbe());
            details.put("gpuCount", capacity.gpus().size());
            details.put("bestGpuAvailableMb", capacity.bestGpuAvailableMb());
            details.put("bestGpuUsedFraction", capacity.bestGpuUsedFraction());
        }
        return new CapacityCheck(admission.admitted(), admission.outcome(), admission.reason(),
                admission.waited(), admission.waitedMs(), admission.samples(), details);
    }

    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private String value(ToolResult result, String... names) {
        if (result == null) return null;
        for (String name : names) {
            Object value = result.getMetadata().get(name);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        try {
            JsonNode payload = mapper.readTree(result.getOutput());
            return value(payload, names);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long longValue(ToolResult result, String name) {
        Object value = result == null ? null : result.getMetadata().get(name);
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static String value(JsonNode node, String... names) {
        if (node == null) return null;
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private static String action(JsonNode params) {
        String value = firstNonBlank(value(params, "action", "operation"));
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String text(JsonNode params, String field) {
        JsonNode value = params == null ? null : params.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static boolean dryRun(JsonNode params) {
        return params != null && (params.path("dryRun").asBoolean(false)
                || params.path("dry_run").asBoolean(false));
    }

    private static ActivityKind parseKind(String kind) {
        if (kind == null || kind.isBlank()) return ActivityKind.BUILD;
        try {
            return ActivityKind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return ActivityKind.BUILD;
        }
    }

    private static String canonicalToolName(String toolName) {
        if (toolName == null) return "";
        return toolName.replaceFirst("^mcp_+kompile_+", "").toLowerCase(Locale.ROOT);
    }

    private static String activityMessage(String verb, CoordinationActivity activity) {
        return "High-memory activity " + verb + ": " + activity.getKind()
                + " via " + activity.getToolName() + " (" + activity.getActivityId() + ") — "
                + activity.getDescription();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private final class GuardedTool implements CliTool {
        private final CliTool delegate;

        private GuardedTool(CliTool delegate) {
            this.delegate = delegate;
        }

        @Override public String id() { return delegate.id(); }
        @Override public String description() { return delegate.description(); }
        @Override public String compactHint() { return delegate.compactHint(); }
        @Override public JsonNode parameterSchema() { return delegate.parameterSchema(); }
        @Override public String permissionKey() { return delegate.permissionKey(); }
        @Override public McpToolAnnotations mcpAnnotations() { return delegate.mcpAnnotations(); }

        @Override
        public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
            return HighMemoryToolCallGuard.this.execute(delegate, params, context);
        }
    }
}
