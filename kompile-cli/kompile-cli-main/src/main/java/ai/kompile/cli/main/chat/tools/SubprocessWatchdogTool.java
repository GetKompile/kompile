/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.project.LocalSubprocessWatchdog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Manage and observe the local crawl runner's parent-side subprocess memory watchdog
 * through MCP. Every pooled local-crawl child (model-serving, pipeline runtime, learning)
 * is tracked here; RSS breaches are force-killed by the watchdog itself, and this tool
 * exposes live status, mutable configuration, and manual kill.
 *
 * <p>Actions:</p>
 * <ul>
 *   <li><b>status</b> — tracked processes with RSS, breach state, and effective limits.</li>
 *   <li><b>config_get</b> — current configuration plus platform support.</li>
 *   <li><b>config_update</b> — update child RSS enforcement and pre-crawl hardware-capacity
 *       admission at runtime (applies immediately).</li>
 *   <li><b>kill</b> — force-destroy one tracked process by id (destructive).</li>
 *   <li><b>check</b> — run one enforcement pass now and report the outcome.</li>
 * </ul>
 */
public class SubprocessWatchdogTool implements CliTool {

    private final ObjectMapper mapper;

    public SubprocessWatchdogTool() {
        this(JsonUtils.standardMapper());
    }

    public SubprocessWatchdogTool(ObjectMapper mapper) {
        this.mapper = mapper == null ? JsonUtils.standardMapper() : mapper;
    }

    @Override
    public String id() {
        return "subprocess_watchdog";
    }

    @Override
    public String description() {
        return "Subprocess memory watchdog for the LOCAL crawl runner (MCP host): tracks every "
                + "pooled project-local subprocess (model-serving, pipeline runtime, learning child), "
                + "gates new crawls on host RAM/GPU capacity, samples child RSS, and force-kills "
                + "children that exceed the configured limit. "
                + "Actions: status (tracked processes + limits), config_get, config_update "
                + "(RSS limits plus admissionMode=wait|fail|off, timeout/poll interval, RAM/GPU "
                + "thresholds), "
                + "kill (force-destroy one tracked process by id), check (run one enforcement pass now). "
                + "maxRssMb=0 and maxRssFraction=0 disable RSS enforcement; zero admission "
                + "thresholds disable the corresponding capacity check.";
    }

    @Override
    public String compactHint() {
        return "Local crawl subprocess watchdog: action=status|config_get|config_update|kill|check. "
                + "config_update keys: enabled, intervalMs, maxRssMb, maxRssFraction, breachCount, "
                + "graceSeconds, admissionMode, admissionTimeoutMs, admissionPollIntervalMs, "
                + "admissionMinAvailableRamMb, admissionMaxRamUsedFraction, "
                + "admissionMinAvailableGpuMb, admissionMaxGpuUsedFraction.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description",
                "Action: status, config_get, config_update, kill, check");
        action.putArray("enum").add("status").add("config_get").add("config_update")
                .add("kill").add("check");

        ObjectNode id = props.putObject("id");
        id.put("type", "string");
        id.put("description",
                "Tracked process id for kill (from status.trackedProcesses[].id, e.g. serving-<runId>).");

        ObjectNode reason = props.putObject("reason");
        reason.put("type", "string");
        reason.put("description", "Human-readable reason for a kill action.");

        ObjectNode config = props.putObject("config");
        config.put("type", "object");
        config.put("description",
                "config_update values: enabled(bool), intervalMs(int), maxRssMb(long), "
                        + "maxRssFraction(0-1 double), breachCount(int), graceSeconds(int). "
                        + "Pre-crawl admission: admissionMode(wait|fail|off), admissionTimeoutMs, "
                        + "admissionPollIntervalMs, admissionMinAvailableRamMb, "
                        + "admissionMaxRamUsedFraction(0-1), admissionMinAvailableGpuMb, "
                        + "admissionMaxGpuUsedFraction(0-1). Zero thresholds disable that check. "
                        + "GPU admission uses the best available device, never a hardcoded card. "
                        + "maxRssMb=0 and maxRssFraction=0 disable RSS enforcement.");

        return schema;
    }

    @Override
    public String permissionKey() {
        return "subprocess_watchdog";
    }

    @Override
    public McpToolAnnotations mcpAnnotations() {
        // status/config_get/check are reads; kill is destructive, so the tool as a whole
        // is annotated destructive to make clients prompt for confirmation.
        return McpToolAnnotations.DESTRUCTIVE;
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Manage the local subprocess memory watchdog");
        String action = params.path("action").asText("status").toLowerCase(Locale.ROOT);
        LocalSubprocessWatchdog watchdog = LocalSubprocessWatchdog.get();
        try {
            return switch (action) {
                case "status" -> status(watchdog);
                case "config_get" -> config(watchdog);
                case "config_update" -> updateConfig(watchdog, params.path("config"));
                case "kill" -> kill(watchdog, params);
                case "check" -> check(watchdog);
                default -> ToolResult.error("Unknown action: " + action
                        + " (expected status|config_get|config_update|kill|check)");
            };
        } catch (IllegalArgumentException e) {
            return ToolResult.error("subprocess_watchdog " + action + ": " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("subprocess_watchdog " + action + " failed: " + e.getMessage());
        }
    }

    private ToolResult status(LocalSubprocessWatchdog watchdog) {
        return ToolResult.success(
                "subprocess_watchdog_status", json(watchdog.statusMap()));
    }

    private ToolResult config(LocalSubprocessWatchdog watchdog) {
        return ToolResult.success("subprocess_watchdog_config", json(watchdog.configMap()));
    }

    private ToolResult updateConfig(LocalSubprocessWatchdog watchdog, JsonNode configNode) {
        if (configNode == null || !configNode.isObject() || configNode.isEmpty()) {
            return ToolResult.error("config_update requires a config object, e.g. "
                    + "{\"config\":{\"maxRssFraction\":0.5}}");
        }
        Map<String, Object> updates = new LinkedHashMap<>();
        var fields = configNode.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            JsonNode value = configNode.get(field);
            if (value.isBoolean()) {
                updates.put(field, value.asBoolean());
            } else if (value.isNumber()) {
                updates.put(field, value.numberValue());
            } else if (value.isTextual() || value.isNull()) {
                updates.put(field, value.isNull() ? null : value.asText());
            } else {
                return ToolResult.error("Unsupported config value type for '" + field
                        + "': " + value.getNodeType());
            }
        }
        Map<String, Object> applied = watchdog.updateConfig(updates);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("applied", updates);
        payload.put("config", applied);
        payload.put("note", "The next scheduled RSS check and crawl admission use the new values immediately.");
        return ToolResult.success("subprocess_watchdog_config_updated", json(payload));
    }

    private ToolResult kill(LocalSubprocessWatchdog watchdog, JsonNode params) {
        String id = params.path("id").asText("").trim();
        if (id.isEmpty()) {
            StringBuilder ids = new StringBuilder();
            for (LocalSubprocessWatchdog.TrackedSubprocess tracked : watchdog.list()) {
                if (ids.length() > 0) ids.append(", ");
                ids.append(tracked.id());
            }
            return ToolResult.error("kill requires id. Tracked ids: "
                    + (ids.length() == 0 ? "(none)" : ids));
        }
        boolean destroyed = watchdog.kill(id, params.path("reason").asText("manual kill via MCP tool"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("destroyed", destroyed);
        payload.put("trackedCount", watchdog.trackedCount());
        return ToolResult.success("subprocess_watchdog_kill", json(payload));
    }

    private ToolResult check(LocalSubprocessWatchdog watchdog) {
        int killed = watchdog.checkAll();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("killedThisPass", killed);
        payload.put("trackedCount", watchdog.trackedCount());
        try {
            payload.put("capacityAdmission", watchdog.sampleCapacityStatus());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("subprocess_watchdog check interrupted while sampling hardware capacity");
        }
        return ToolResult.success("subprocess_watchdog_check", json(payload));
    }

    private String json(Object value) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
