package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Project-local admission rules. No command substring matching or model-supplied cost labels. */
public final class ResourcePolicy {
    private ResourcePolicy() { }

    // The synchronous launch must publish the admission decision, even if config changes during a permission prompt.
    static final ThreadLocal<Decision> ACTIVE_LAUNCH = new ThreadLocal<>();

    public record Decision(String resourceClass, String reason) {
        public boolean high() { return "high".equals(resourceClass); }
    }

    private static Path file(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        Path project = new ai.kompile.project.KompileProjectStore().findProjectRoot(normalized).orElse(normalized);
        return project.resolve(".kompile/resource-policy.json");
    }

    public static ObjectNode defaults() {
        ObjectNode config = JsonUtils.standardMapper().createObjectNode();
        config.put("defaultClass", "high");
        config.put("unknownShellClass", "high");
        var rules = config.putArray("rules");
        // Editable defaults, not special cases in the evaluator.
        for (String tool : List.of("bash", "process")) {
            for (String executable : List.of("git", "ps", "pwd", "cd", "true", "false", "sleep",
                    "printf", "echo", "date", "uname", "hostname", "whoami", "id", "which",
                    "free", "df", "uptime", "nvidia-smi", "kill")) {
                ObjectNode rule = rules.addObject();
                rule.put("id", tool + "-" + executable).put("tool", tool).put("class", "low");
                rule.putArray("all").addObject().put("path", "/shell/executable")
                        .put("op", "eq").put("value", executable);
            }
        }
        return config;
    }

    private static Path userFile() {
        return Path.of(System.getProperty("user.home"), ".kompile", "resource-policy.json");
    }

    public static ObjectNode load(Path root) throws Exception {
        return loadSources(userFile(), file(root));
    }

    // Fields inherit independently. An explicit rules array replaces the inherited ordered list.
    static ObjectNode loadSources(Path... sources) throws Exception {
        ObjectNode config = defaults();
        for (Path source : sources) {
            config.setAll(readOverrides(source));
            validate(config);
        }
        return config;
    }

    private static ObjectNode readOverrides(Path source) throws Exception {
        if (!Files.exists(source)) return JsonUtils.standardMapper().createObjectNode();
        JsonNode node = JsonUtils.standardMapper().readTree(source.toFile());
        if (node == null || !node.isObject()) throw new IllegalArgumentException(source + ": policy must be an object");
        validateFields(node, source.toString());
        return (ObjectNode) node;
    }

    private static void validateFields(JsonNode node, String source) {
        var fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!Set.of("defaultClass", "unknownShellClass", "rules").contains(field))
                throw new IllegalArgumentException(source + ": unknown field " + field);
        }
    }

    public static String processClass(Path root, ai.kompile.cli.main.coordination.ProcessCoordEntry process) {
        if (process.getResourceClass() != null) return "low".equals(process.getResourceClass()) ? "low" : "high";
        return classify(root, "process", JsonUtils.standardMapper().createObjectNode()
                .put("action", "launch").put("command", process.getCommand())).resourceClass();
    }

    public static Decision classify(Path root, String tool, JsonNode arguments) {
        try {
            return classify(load(root), tool, arguments);
        } catch (Exception failure) {
            return new Decision("high", "Invalid resource policy: " + failure.getMessage());
        }
    }

    public static Decision classify(JsonNode config, String tool, JsonNode arguments) {
        validate(config);
        tool = tool.replaceFirst("^mcp_+kompile_+", "").toLowerCase(java.util.Locale.ROOT);
        if (HighMemoryToolCallGuard.classify(tool, arguments) == null)
            return new Decision("low", "non-launch/read-only action: no resource admission required");
        ObjectNode input = JsonUtils.standardMapper().createObjectNode();
        input.set("arguments", arguments);
        if (tool.equals("bash") || tool.equals("process") && arguments.path("action").asText().equals("launch")) {
            List<List<String>> commands = shell(arguments.path("command").asText());
            if (commands == null || commands.isEmpty()) {
                return new Decision(config.path("unknownShellClass").asText(), "unsupported or empty shell syntax");
            }
            List<String> reasons = new ArrayList<>();
            for (List<String> argv : commands) {
                ObjectNode parsed = shellInput(argv);
                input.set("shell", parsed);
                Decision decision = parsed == null
                        ? new Decision(config.path("unknownShellClass").asText(), "unsupported shell wrapper/control syntax")
                        : match(config, tool, input);
                if (decision.high()) return decision;
                reasons.add(decision.reason());
            }
            return new Decision("low", String.join("; ", reasons));
        }
        return match(config, tool, input);
    }

    private static Decision match(JsonNode config, String tool, JsonNode input) {
        for (JsonNode rule : config.path("rules")) {
            if (!rule.path("tool").asText().equals(tool)) continue;
            boolean matches = true;
            for (JsonNode condition : rule.path("all")) {
                JsonNode actual = input.at(condition.path("path").asText());
                JsonNode expected = condition.get("value");
                boolean ok = switch (condition.path("op").asText()) {
                    case "eq" -> actual.equals(expected);
                    case "exists" -> !actual.isMissingNode() == expected.asBoolean();
                    case "contains" -> actual.isArray() && contains(actual, expected);
                    case "sequence" -> actual.isArray() && sequence(actual, expected);
                    case "prefix" -> actual.isArray() && actual.size() >= expected.size()
                            && sequencePrefix(actual, expected);
                    case "gte" -> actual.isNumber() && actual.decimalValue().compareTo(expected.decimalValue()) >= 0;
                    case "lte" -> actual.isNumber() && actual.decimalValue().compareTo(expected.decimalValue()) <= 0;
                    default -> false;
                };
                if (!ok) { matches = false; break; }
            }
            if (matches) return new Decision(rule.path("class").asText(), "rule " + rule.path("id").asText());
        }
        return new Decision(config.path("defaultClass").asText(), "unmatched tool/arguments: configured default");
    }

    private static boolean contains(JsonNode array, JsonNode value) {
        for (JsonNode item : array) if (item.equals(value)) return true;
        return false;
    }

    private static boolean sequencePrefix(JsonNode array, JsonNode values) {
        for (int i = 0; i < values.size(); i++) if (!array.get(i).equals(values.get(i))) return false;
        return true;
    }

    private static boolean sequence(JsonNode array, JsonNode values) {
        for (int i = 0; i + values.size() <= array.size(); i++) {
            boolean matches = true;
            for (int j = 0; j < values.size(); j++) {
                if (!array.get(i + j).equals(values.get(j))) { matches = false; break; }
            }
            if (matches) return true;
        }
        return false;
    }

    public static void validate(JsonNode config) {
        if (config == null || !config.isObject()) throw new IllegalArgumentException("policy must be an object");
        validateFields(config, "policy");
        checkClass(config.path("defaultClass").asText());
        checkClass(config.path("unknownShellClass").asText());
        if (!config.path("rules").isArray()) throw new IllegalArgumentException("rules must be an ordered array");
        Set<String> ids = new java.util.HashSet<>();
        for (JsonNode rule : config.path("rules")) {
            if (rule.path("id").asText().isBlank() || !ids.add(rule.path("id").asText()))
                throw new IllegalArgumentException("rule ids must be nonempty and unique");
            checkClass(rule.path("class").asText());
            if (rule.path("tool").asText().isBlank() || !rule.path("all").isArray())
                throw new IllegalArgumentException("each rule needs tool and all[]");
            for (JsonNode condition : rule.path("all")) {
                String path = condition.path("path").asText();
                if (!path.startsWith("/arguments/") && !path.startsWith("/shell/"))
                    throw new IllegalArgumentException("condition path must start /arguments/ or /shell/");
                com.fasterxml.jackson.core.JsonPointer.compile(path);
                String op = condition.path("op").asText();
                if (!Set.of("eq", "exists", "contains", "sequence", "prefix", "gte", "lte").contains(op) || !condition.has("value"))
                    throw new IllegalArgumentException("condition needs eq/exists/contains/sequence/gte/lte and value");
                if (Set.of("sequence", "prefix").contains(op) && (!condition.get("value").isArray() || condition.get("value").isEmpty()))
                    throw new IllegalArgumentException("sequence value must be a nonempty array");
                if (op.equals("exists") && !condition.get("value").isBoolean()
                        || Set.of("gte", "lte").contains(op) && !condition.get("value").isNumber())
                    throw new IllegalArgumentException("invalid condition value type for " + op);
            }
        }
    }

    private static void checkClass(String value) {
        if (!Set.of("low", "high").contains(value)) throw new IllegalArgumentException("class must be low or high");
    }

    /** Literal shell subset only. Expansions, redirection and control syntax use the explicit fallback. */
    static List<List<String>> shell(String command) {
        List<List<String>> commands = new ArrayList<>();
        List<String> argv = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        char quote = 0;
        boolean started = false;
        boolean needsCommand = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != '\'') {
                if (c == '$' || c == '`') return null;
                if (c == '\\') {
                    if (++i >= command.length() || command.charAt(i) == '\n') return null;
                    if (quote == '"' && "\\\"\\\\$`".indexOf(command.charAt(i)) < 0) word.append('\\');
                    word.append(command.charAt(i)); started = true; continue;
                }
            }
            if (quote != 0) {
                if (c == quote) quote = 0; else word.append(c);
                continue;
            }
            if (c == '\'' || c == '"') { quote = c; started = true; continue; }
            if ("<>()[{}*?~#".indexOf(c) >= 0) return null;
            if (Character.isWhitespace(c) || c == '&' || c == '|' || c == ';') {
                if (started) { argv.add(word.toString()); word.setLength(0); started = false; }
                if (c == '&' || c == '|' || c == ';' || c == '\n') {
                    if (argv.isEmpty()) return null;
                    commands.add(List.copyOf(argv)); argv.clear();
                    boolean doubled = (c == '&' || c == '|') && i + 1 < command.length() && command.charAt(i + 1) == c;
                    needsCommand = c == '|' || doubled;
                    if (doubled) i++;
                }
            } else { word.append(c); started = true; }
        }
        if (quote != 0) return null;
        if (started) argv.add(word.toString());
        if (argv.isEmpty() && needsCommand) return null;
        if (!argv.isEmpty()) commands.add(List.copyOf(argv));
        return commands;
    }

    private static ObjectNode shellInput(List<String> words) {
        ObjectNode parsed = JsonUtils.standardMapper().createObjectNode();
        ObjectNode environment = parsed.putObject("environment");
        int start = 0;
        while (start < words.size() && assignment(words.get(start))) {
            String item = words.get(start++);
            environment.put(item.substring(0, item.indexOf('=')), item.substring(item.indexOf('=') + 1));
        }
        if (start < words.size() && basename(words.get(start)).equals("env")) {
            start++;
            if (start < words.size() && words.get(start).equals("--")) start++;
            while (start < words.size() && assignment(words.get(start))) {
                String item = words.get(start++);
                environment.put(item.substring(0, item.indexOf('=')), item.substring(item.indexOf('=') + 1));
            }
        }
        if (start == words.size()) return null;
        String executable = basename(words.get(start++));
        if (executable.isEmpty() || executable.startsWith("-") || Set.of("sh", "bash", "zsh", "eval", "source", ".", "env",
                "sudo", "time", "timeout", "nice", "nohup", "exec", "command", "if", "for", "while", "case", "!", "function").contains(executable)) return null;
        parsed.put("executable", executable);
        List<String> args = words.subList(start, words.size());
        parsed.set("argv", JsonUtils.standardMapper().valueToTree(args));
        ObjectNode options = parsed.putObject("options");
        for (String arg : args) {
            if (arg.equals("--")) break;
            int equals = arg.indexOf('=');
            if (arg.startsWith("-") && equals > 0) options.put(arg.substring(0, equals), arg.substring(equals + 1));
        }
        return parsed;
    }

    private static String basename(String value) { return value.substring(value.lastIndexOf('/') + 1); }

    private static boolean assignment(String word) {
        int equals = word.indexOf('=');
        if (equals < 1 || !(Character.isLetter(word.charAt(0)) || word.charAt(0) == '_')) return false;
        for (int i = 1; i < equals; i++) {
            if (!(Character.isLetterOrDigit(word.charAt(i)) || word.charAt(i) == '_')) return false;
        }
        return true;
    }

    private static String save(Path target, JsonNode config) throws Exception {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "resource-policy-", ".tmp");
        try {
            JsonUtils.standardMapper().writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), config);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
        return "Resource policy saved to " + target
                + ". New calls use it immediately; running processes retain their classification.";
    }

    private static String summary(Path root, Path target, Path user, ObjectNode config) throws Exception {
        StringBuilder result = new StringBuilder("Resource admission\nEditing: ").append(target)
                .append("\nSources (highest priority last):\n  built-in defaults\n  ")
                .append(user).append(Files.exists(user) ? " (present)" : " (not set)");
        boolean global = target.equals(user);
        if (!global) result.append("\n  ").append(file(root))
                .append(Files.exists(file(root)) ? " (present)" : " (not set)");
        for (String field : List.of("defaultClass", "unknownShellClass", "rules")) {
            String source = "built-in";
            if (readOverrides(user).has(field)) source = "user";
            if (!global && readOverrides(file(root)).has(field)) source = "project";
            result.append("\n").append(field).append(": ")
                    .append(field.equals("rules") ? config.path(field).size() + " ordered rules" : config.path(field).asText())
                    .append(" (from ").append(source).append(")");
        }
        result.append("\nRoutine diagnostics are low by default; unknown launches stay guarded.\n");
        return result.append("low = no exclusive lane; high = exclusive lane + capacity check. Permissions still apply.\n")
                .append("RAM/GPU thresholds and child RSS limits are separate: subprocess_watchdog config_get.\n")
                .append("Use /resources rules to list rules, /resources help to edit, /resources json to export.").toString();
    }

    private static String rulesSummary(ObjectNode config) {
        StringBuilder result = new StringBuilder("Rules (first match wins):\n");
        for (JsonNode rule : config.path("rules")) {
            result.append("  ").append(rule.path("id").asText()).append(": ")
                    .append(rule.path("class").asText()).append(" — ").append(rule.path("tool").asText());
            for (JsonNode condition : rule.path("all")) result.append("; ")
                    .append(condition.path("path").asText()).append(' ')
                    .append(condition.path("op").asText()).append(' ').append(condition.path("value"));
            result.append('\n');
        }
        return result.toString();
    }

    public static String command(Path root, String args) {
        return command(root, args, userFile());
    }

    static String command(Path root, String args, Path user) {
        try {
            String input = args.strip();
            boolean global = input.equals("global") || input.startsWith("global ");
            if (global) input = input.substring("global".length()).strip();
            Path target = global ? user : file(root);
            String[] parts = input.split("\\s+", 2);
            String action = parts[0];
            // Help and full replacement must remain usable even when a saved policy is invalid.
            if (action.equals("set") && parts.length == 2) {
                JsonNode replacement = JsonUtils.standardMapper().readTree(parts[1]);
                validate(replacement);
                return save(target, replacement);
            }
            ObjectNode effective = Set.of("help", "inherit").contains(action) ? defaults()
                    : global ? loadSources(user) : loadSources(user, file(root));
            if (action.isBlank() || action.equals("show") || action.equals("sources"))
                return summary(root, target, user, effective);
            if (action.equals("json")) return effective.toPrettyString();
            if (action.equals("rules")) return rulesSummary(effective);
            if (action.equals("inherit")) {
                if (parts.length != 2 || !Set.of("default", "unknown-shell", "rules").contains(parts[1]))
                    throw new IllegalArgumentException("inherit needs default, unknown-shell or rules");
                ObjectNode overrides = readOverrides(target);
                overrides.remove(switch (parts[1]) {
                    case "default" -> "defaultClass";
                    case "unknown-shell" -> "unknownShellClass";
                    default -> "rules";
                });
                return save(target, overrides);
            }
            if (action.equals("check") && parts.length == 2)
                return classify(effective, "bash", JsonUtils.standardMapper().createObjectNode()
                        .put("command", parts[1])).toString();
            if (parts.length == 2 && (action.equals("rule") || action.equals("add") && !parts[1].startsWith("{"))) {
                List<List<String>> parsed = shell(parts[1]);
                if (parsed == null || parsed.size() != 1 || parsed.get(0).size() < 3)
                    throw new IllegalArgumentException("rule needs <id> low|high <executable> [argument prefix...]; omitted prefix matches all arguments");
                List<String> words = parsed.get(0);
                checkClass(words.get(1));
                if (words.get(0).isBlank() || basename(words.get(2)).isBlank())
                    throw new IllegalArgumentException("rule id and executable must not be empty");
                ObjectNode config = effective.deepCopy();
                var rules = (com.fasterxml.jackson.databind.node.ArrayNode) config.path("rules");
                // Match both launch surfaces. Explicit prefixes avoid substring matches.
                for (String tool : List.of("process", "bash")) {
                    ObjectNode rule = JsonUtils.standardMapper().createObjectNode();
                    rule.put("id", words.get(0) + "-" + tool).put("tool", tool).put("class", words.get(1));
                    var conditions = rule.putArray("all");
                    conditions.addObject().put("path", "/shell/executable").put("op", "eq")
                            .put("value", basename(words.get(2)));
                    if (words.size() > 3) conditions.addObject().put("path", "/shell/argv").put("op", "prefix")
                            .set("value", JsonUtils.standardMapper().valueToTree(words.subList(3, words.size())));
                    rules.insert(0, rule);
                }
                validate(config);
                ObjectNode overrides = readOverrides(target);
                overrides.set("rules", rules);
                return save(target, overrides);
            }
            if (Set.of("add", "remove", "default", "unknown-shell").contains(action) && parts.length == 2) {
                ObjectNode config = effective.deepCopy();
                if (action.equals("default") || action.equals("unknown-shell")) {
                    checkClass(parts[1]);
                    config.put(action.equals("default") ? "defaultClass" : "unknownShellClass", parts[1]);
                } else if (action.equals("add")) {
                    // New rules take priority over existing defaults. Duplicate ids are rejected.
                    ((com.fasterxml.jackson.databind.node.ArrayNode) config.path("rules"))
                            .insert(0, JsonUtils.standardMapper().readTree(parts[1]));
                } else {
                    var rules = (com.fasterxml.jackson.databind.node.ArrayNode) config.path("rules");
                    boolean removed = false;
                    for (int i = rules.size() - 1; i >= 0; i--) {
                        if (Set.of(parts[1], parts[1] + "-bash", parts[1] + "-process")
                                .contains(rules.get(i).path("id").asText())) { rules.remove(i); removed = true; }
                    }
                    if (!removed) throw new IllegalArgumentException("No rule named " + parts[1]);
                }
                validate(config);
                ObjectNode overrides = readOverrides(target);
                String field = action.equals("default") ? "defaultClass"
                        : action.equals("unknown-shell") ? "unknownShellClass" : "rules";
                overrides.set(field, config.get(field));
                return save(target, overrides);
            }
            if (action.equals("preview") && parts.length == 2) {
                String[] call = parts[1].split("\\s+", 2);
                if (call.length != 2) throw new IllegalArgumentException("preview needs tool and JSON arguments");
                JsonNode arguments = JsonUtils.standardMapper().readTree(call[1]);
                if (!arguments.isObject()) throw new IllegalArgumentException("arguments must be an object");
                return classify(effective, call[0], arguments).toString();
            }
            return "Resources (changes apply immediately; no JSON needed):\n"
                    + "  /resources                         Readable policy and source summary\n"
                    + "  /resources setup                   Interactive configuration wizard\n"
                    + "  /resources add                     Interactive add-rule wizard\n"
                    + "  /resources rules                   List ordered rules\n"
                    + "  /resources check <shell command>   Preview only; never executes\n"
                    + "  /resources rule <id> low|high <executable> [argument prefix...]\n"
                    + "  /resources remove <id>             Remove a rule (both launch surfaces)\n"
                    + "  /resources default low|high        Unmatched launches (default: high)\n"
                    + "  /resources unknown-shell low|high  Unparsed shell syntax (default: high)\n"
                    + "  /resources inherit default|unknown-shell|rules   Clear that override\n"
                    + "  /resources global <command>        Edit user defaults instead of this project\n"
                    + "Example: /resources rule java-version low java -version\n"
                    + "Omitting the argument prefix matches ALL invocations of that executable.\n"
                    + "Builds, unknown scripts, model work and crawls remain high by default.\n"
                    + "Sources: built-in < ~/.kompile/resource-policy.json < project .kompile/resource-policy.json.\n"
                    + "Fields inherit independently; an explicit rules list replaces the inherited list.\n"
                    + "Advanced: json | add <rule JSON> | set <policy JSON> | preview <tool> <arguments JSON>\n"
                    + "Policy: defaultClass, unknownShellClass (low/high), ordered rules[]. First match wins per shell command; highest class wins across chains.\n"
                    + "Rule: {id, tool, class, all:[{path, op, value}]}. Paths: /arguments/<name>, /shell/executable (basename), /shell/argv (exact argument array), /shell/options/<flag> (flag=value), /shell/environment/<name> (literal assignments).\n"
                    + "Operators: eq (typed equality), contains (array member), sequence (consecutive tokens, e.g. [-pl, module]), prefix (initial tokens), exists (boolean), gte/lte (number).\n"
                    + "low: no exclusive resource lane or heavy-capacity check. high: existing exclusive lane and capacity checks. Other permissions and subprocess limits still apply.\n"
                    + "Example rule: {\"id\":\"scoped-test\",\"tool\":\"bash\",\"class\":\"low\",\"all\":[{\"path\":\"/shell/executable\",\"op\":\"eq\",\"value\":\"mvn\"},{\"path\":\"/shell/options/-Dtest\",\"op\":\"eq\",\"value\":\"MySmallTest\"}]}";
        } catch (Exception failure) { return "Resource policy error: " + failure.getMessage(); }
    }
}
