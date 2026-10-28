package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.insights.TestMilestoneReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Project overrides for deterministic test capture. Never changes the command being observed. */
final class TestCaptureConfig {
    record Rule(Pattern command, Pattern summary) {}
    final boolean enabled;
    final boolean fallback;
    final List<String> disabledFrameworks;
    final List<Pattern> includes;
    final List<Pattern> excludes;
    final List<String> reportPaths;
    final List<Rule> rules;
    final JsonNode settings;

    private TestCaptureConfig(JsonNode node) {
        if (!node.isObject()) throw new IllegalArgumentException("autoCapture must be an object");
        for (String key : List.of("enabled", "fallbackEnabled")) {
            if (node.has(key) && !node.get(key).isBoolean()) throw new IllegalArgumentException(key + " must be boolean");
        }
        for (String key : List.of("disabledFrameworks", "includeCommands", "excludeCommands", "reportPaths", "customRules")) {
            if (node.has(key) && !node.get(key).isArray()) throw new IllegalArgumentException(key + " must be an array");
        }
        settings = node.deepCopy();
        enabled = node.path("enabled").asBoolean(true);
        fallback = node.path("fallbackEnabled").asBoolean(true);
        disabledFrameworks = strings(node.path("disabledFrameworks"));
        includes = patterns(node.path("includeCommands"));
        excludes = patterns(node.path("excludeCommands"));
        reportPaths = strings(node.path("reportPaths"));
        rules = new ArrayList<>();
        for (JsonNode rule : node.path("customRules")) {
            Pattern summary = regex(rule.path("summaryRegex").asText());
            for (String group : List.of("total", "passed", "failed")) {
                if (!summary.pattern().contains("(?<" + group + ">")) throw new IllegalArgumentException("Custom summaryRegex requires named group " + group);
            }
            rules.add(new Rule(regex(rule.path("commandRegex").asText()), summary));
        }
    }

    static Path root(Path directory) {
        Path cwd = directory.toAbsolutePath().normalize();
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        for (Path p = cwd; p != null; p = p.getParent()) {
            boolean projectConfig = (!p.equals(home) || p.equals(cwd))
                    && Files.isRegularFile(p.resolve(TestMilestoneReader.STORE_DIR).resolve("config.json"));
            if (Files.exists(p.resolve("kompile.project.json")) || Files.exists(p.resolve(".git")) || projectConfig) return p;
        }
        return cwd;
    }

    static TestCaptureConfig defaults() {
        return new TestCaptureConfig(JsonUtils.standardMapper().createObjectNode());
    }

    static TestCaptureConfig load(Path root, Consumer<String> warning) {
        try {
            return new TestCaptureConfig(effective(root));
        } catch (Exception invalid) {
            warning.accept("Automatic test capture disabled: " + invalid.getMessage());
            ObjectNode off = JsonUtils.standardMapper().createObjectNode().put("enabled", false);
            return new TestCaptureConfig(off);
        }
    }

    static ObjectNode effective(Path root) throws IOException {
        ObjectNode result = JsonUtils.standardMapper().createObjectNode();
        Path global = Path.of(System.getProperty("user.home"), TestMilestoneReader.STORE_DIR, "config.json");
        merge(result, global);
        Path local = root.resolve(TestMilestoneReader.STORE_DIR).resolve("config.json");
        if (!local.equals(global)) merge(result, local);
        return result;
    }

    private static void merge(ObjectNode target, Path file) throws IOException {
        if (!Files.isRegularFile(file)) return;
        JsonNode config = JsonUtils.standardMapper().readTree(file.toFile());
        JsonNode capture = config.path("autoCapture");
        if (!capture.isMissingNode() && !capture.isObject()) throw new IOException("autoCapture must be an object: " + file);
        if (capture.isObject()) target.setAll((ObjectNode) capture);
    }

    static void validate(JsonNode node) {
        if (!node.isObject()) throw new IllegalArgumentException("auto_capture must be an object");
        for (String key : List.of("enabled", "fallbackEnabled")) {
            if (node.has(key) && !node.get(key).isBoolean()) throw new IllegalArgumentException(key + " must be boolean");
        }
        for (String key : List.of("disabledFrameworks", "includeCommands", "excludeCommands", "reportPaths", "customRules")) {
            if (node.has(key) && !node.get(key).isArray()) throw new IllegalArgumentException(key + " must be an array");
        }
        new TestCaptureConfig(node);
    }

    boolean allows(String framework) {
        return !disabledFrameworks.contains(framework) && !disabledFrameworks.contains("*");
    }

    private static List<String> strings(JsonNode node) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual()) throw new IllegalArgumentException("Expected a string in capture configuration");
            result.add(value.asText());
        }
        return List.copyOf(result);
    }

    private static List<Pattern> patterns(JsonNode node) {
        return strings(node).stream().map(TestCaptureConfig::regex).toList();
    }

    private static Pattern regex(String text) {
        if (text.isBlank() || text.length() > 1024) throw new IllegalArgumentException("Capture regex must contain 1..1024 characters");
        // Configuration is trusted, but reject the most common accidental exponential patterns.
        if (text.matches(".*\\([^)]*[+*|][^)]*\\)[+*{].*") || text.matches(".*\\\\[1-9].*")) {
            throw new IllegalArgumentException("Capture regex cannot repeat a quantified/alternating group or use backreferences");
        }
        return Pattern.compile(text);
    }
}
