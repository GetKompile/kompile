package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.SessionModelLadder;
import ai.kompile.cli.main.chat.SessionModelLadder.ModelRung;
import ai.kompile.cli.main.chat.SubagentConfigCommands;
import ai.kompile.cli.main.chat.config.SubagentConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static ai.kompile.cli.main.chat.exec.WebCommandResolver.Status.*;

/** Browser adapter to the CLI's authoritative subagent policy and session ladder stores. */
final class WebHarnessSettings {
    private WebHarnessSettings() { }

    static ObjectNode subagents() throws IOException {
        SubagentConfig config = SubagentConfig.load();
        ObjectNode data = JsonUtils.standardMapper().createObjectNode();
        data.put("menu", "subagent-config");
        data.put("maxConcurrent", config.maxConcurrent());
        // A settings query runs in a separate JVM, so its counter is not the live run's.
        data.putNull("activeCount");
        data.put("activeCountScope", "See the owning run's Activity panel; admission is per JVM.");
        data.set("bannedModels", JsonUtils.standardMapper().valueToTree(config.bannedModels()));
        data.set("failover", ladderData(config.failoverEnabled(), config.failoverRungs()));
        return data;
    }

    static ObjectNode failover(String sessionId, Path directory) {
        SessionModelLadder ladder = SessionModelLadder.load(sessionId, directory);
        return ladderData(ladder.enabled(), ladder.rungs());
    }

    private static ObjectNode ladderData(boolean enabled, List<ModelRung> rungs) {
        ObjectNode data = JsonUtils.standardMapper().createObjectNode();
        data.put("menu", "failover");
        data.put("enabled", enabled);
        data.set("rungs", JsonUtils.standardMapper().valueToTree(rungs));
        return data;
    }

    static WebCommandResolver.Resolution subagents(String arguments) {
        String rest = arguments == null ? "" : arguments.strip();
        try {
            String text;
            boolean menu = rest.isEmpty() || rest.equals("status");
            if (rest.equals("failover") || rest.startsWith("failover ")) {
                return ladder(rest.substring("failover".length()).strip(), null, null, true);
            } else if (rest.startsWith("save ")) {
                JsonNode draft = object(rest.substring(5), Set.of("maxConcurrent", "bannedModels", "failoverEnabled", "failoverRungs"));
                JsonNode max = draft.path("maxConcurrent");
                if (!max.isIntegralNumber() || !max.canConvertToInt()) throw new IllegalArgumentException("maxConcurrent must be an integer");
                if (!draft.path("bannedModels").isArray()) throw new IllegalArgumentException("bannedModels must be an array");
                List<String> bans = new ArrayList<>();
                for (JsonNode ban : draft.path("bannedModels")) {
                    if (!ban.isTextual()) throw new IllegalArgumentException("Model bans must be strings");
                    bans.add(ban.textValue());
                }
                SubagentConfig config = new SubagentConfig(max.intValue(), bans,
                        bool(draft, "failoverEnabled"), rungs(draft.path("failoverRungs")));
                config.save(SubagentConfig.path());
                text = "Subagent configuration saved; applies to new children.";
            } else {
                text = menu ? "Subagent configuration loaded. Running children are shown in Activity; concurrency is per JVM."
                        : SubagentConfigCommands.command(SubagentConfig.path(), rest);
            }
            return new WebCommandResolver.Resolution(menu ? INTERACTION_REQUIRED : COMPLETED,
                    "/subagent-config", text, null, subagents());
        } catch (IOException | IllegalArgumentException error) {
            return invalid("/subagent-config");
        }
    }

    static WebCommandResolver.Resolution ladder(String arguments, String sessionId, Path directory, boolean child) {
        String command = child ? "/subagent-config" : "/model";
        String rest = arguments == null ? "" : arguments.strip();
        String[] parts = rest.split("\\s+", 2);
        String action = parts[0];
        String value = parts.length == 2 ? parts[1] : "";
        try {
            SubagentConfig policy = child ? SubagentConfig.load() : null;
            SessionModelLadder ladder = child ? null : SessionModelLadder.load(sessionId, directory);
            boolean enabled = child ? policy.failoverEnabled() : ladder.enabled();
            List<ModelRung> routes = new ArrayList<>(child ? policy.failoverRungs() : ladder.rungs());
            boolean menu = action.isEmpty() || action.equals("status");
            if (!menu) {
                if (!child && (sessionId == null || sessionId.isBlank())) throw new IllegalArgumentException("Session required");
                switch (action) {
                    case "save" -> {
                        JsonNode draft = object(value, Set.of("enabled", "rungs"));
                        enabled = bool(draft, "enabled");
                        routes = rungs(draft.path("rungs"));
                    }
                    case "on", "off" -> { requireEmpty(value); enabled = action.equals("on"); }
                    case "clear" -> { requireEmpty(value); routes.clear(); }
                    case "remove" -> routes.remove(index(value, routes.size()));
                    case "order" -> {
                        String[] positions = value.split("\\s+");
                        if (positions.length != 2) throw new IllegalArgumentException("Expected two positions");
                        int from = index(positions[0], routes.size());
                        int to = index(positions[1], routes.size());
                        routes.add(to, routes.remove(from));
                    }
                    default -> throw new IllegalArgumentException("Unknown failover action");
                }
                if (child) new SubagentConfig(policy.maxConcurrent(), policy.bannedModels(), enabled, routes).save(SubagentConfig.path());
                else {
                    if (!action.equals("on") && !action.equals("off")) ladder.replaceRungs(routes);
                    ladder.setEnabled(enabled);
                    ladder.save();
                }
            } else requireEmpty(value);
            return new WebCommandResolver.Resolution(menu ? INTERACTION_REQUIRED : COMPLETED, command,
                    menu ? "Model failover configuration loaded. Changes are staged until Save; only credential references are stored."
                            : "Model failover configuration saved.", null,
                    ladderData(enabled, routes).put("scope", child ? "subagent" : "session"));
        } catch (IOException | IllegalArgumentException error) {
            return invalid(command);
        }
    }

    private static WebCommandResolver.Resolution invalid(String command) {
        // Parser errors can quote input, including a mistakenly pasted token. Never echo them.
        return new WebCommandResolver.Resolution(INVALID, command,
                "Invalid harness settings; no changes saved. Use the settings editor or status, on, off, clear, remove N, order N N, save <JSON>. Only credential references are accepted.", null);
    }

    private static JsonNode object(String json, Set<String> allowed) throws IOException {
        var parser = JsonUtils.standardMapper().reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        JsonNode node = parser.readTree(json);
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Expected an object");
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw new IllegalArgumentException("Unexpected field");
        });
        return node;
    }

    private static boolean bool(JsonNode node, String field) {
        if (!node.path(field).isBoolean()) throw new IllegalArgumentException("Expected a boolean");
        return node.path(field).booleanValue();
    }

    private static List<ModelRung> rungs(JsonNode node) {
        if (!node.isArray()) throw new IllegalArgumentException("Expected routes array");
        List<ModelRung> routes = new ArrayList<>();
        Set<List<String>> identities = new HashSet<>();
        Set<String> fields = Set.of("provider", "model", "credentialName", "label", "thinking", "authenticationMethod");
        for (JsonNode route : node) {
            if (!route.isObject()) throw new IllegalArgumentException("Expected route object");
            route.fieldNames().forEachRemaining(field -> {
                if (!fields.contains(field)) throw new IllegalArgumentException("Unexpected route field");
            });
            ModelRung rung = new ModelRung(text(route, "provider"), text(route, "model"),
                    text(route, "credentialName"), text(route, "label"), text(route, "thinking"), text(route, "authenticationMethod"));
            if (!rung.hasModel()) throw new IllegalArgumentException("Model required");
            String auth = rung.authenticationMethod();
            if (auth != null && !Set.of("api-key", "oauth", "native").contains(auth)) throw new IllegalArgumentException("Unsupported authentication route");
            List<String> identity = java.util.Arrays.asList(rung.provider() == null ? null : rung.provider().toLowerCase(java.util.Locale.ROOT),
                    rung.model(), rung.credentialName(), auth);
            if (!identities.add(identity)) throw new IllegalArgumentException("Duplicate route");
            routes.add(rung);
        }
        return routes;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("Expected string");
        return value.textValue();
    }

    private static int index(String value, int size) {
        int index = Integer.parseInt(value) - 1;
        if (index < 0 || index >= size) throw new IllegalArgumentException("Position out of range");
        return index;
    }

    private static void requireEmpty(String value) {
        if (!value.isBlank()) throw new IllegalArgumentException("Unexpected arguments");
    }
}
