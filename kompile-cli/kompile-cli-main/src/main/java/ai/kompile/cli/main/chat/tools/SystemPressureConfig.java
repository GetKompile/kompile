package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Independent, opt-in runtime pressure policy; admission/RSS policy is unchanged. */
public final class SystemPressureConfig {
    private SystemPressureConfig() { }
    static final Set<String> METRICS = Set.of("cpu", "ram", "swap", "disk", "gpuMemory", "gpuUtilization");
    private static final Set<String> FIELDS = Set.of("enabled", "intervalMs", "breachCount", "cooldownMs",
            "projectionSeconds", "momentumAlpha", "minMomentumPercentPerSecond", "thresholds");

    public record Settings(boolean enabled, long intervalMs, int breachCount, long cooldownMs,
                           double projectionSeconds, double momentumAlpha,
                           double minMomentumPercentPerSecond, Map<String, Double> thresholds) {
        public Settings { thresholds = Map.copyOf(thresholds); }

        /** Plain values avoid requiring record-reflection registration in the native CLI. */
        Map<String, Object> values() {
            return Map.of("enabled", enabled, "intervalMs", intervalMs, "breachCount", breachCount,
                    "cooldownMs", cooldownMs, "projectionSeconds", projectionSeconds,
                    "momentumAlpha", momentumAlpha, "minMomentumPercentPerSecond", minMomentumPercentPerSecond,
                    "thresholds", thresholds);
        }
    }

    public static ObjectNode defaults() {
        ObjectNode node = JsonUtils.standardMapper().createObjectNode();
        node.put("enabled", false).put("intervalMs", 2000).put("breachCount", 3)
                .put("cooldownMs", 30000).put("projectionSeconds", 10)
                .put("momentumAlpha", 0.3).put("minMomentumPercentPerSecond", 0.5);
        node.putObject("thresholds").put("cpu", 0).put("ram", 90).put("swap", 0)
                .put("disk", 0).put("gpuMemory", 95).put("gpuUtilization", 0);
        return node;
    }

    static Path projectFile(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        Path project = new ai.kompile.project.KompileProjectStore().findProjectRoot(normalized).orElse(normalized);
        return project.resolve(".kompile/resource-monitor.json");
    }

    static Path userFile() {
        return Path.of(System.getProperty("user.home"), ".kompile", "resource-monitor.json");
    }

    static ObjectNode overrides(Path file) throws Exception {
        if (!Files.exists(file)) return JsonUtils.standardMapper().createObjectNode();
        JsonNode node = JsonUtils.standardMapper().readTree(file.toFile());
        checkFields(node);
        return (ObjectNode) node;
    }

    static ObjectNode load(Path... sources) throws Exception {
        ObjectNode effective = defaults();
        for (Path file : sources) merge(effective, overrides(file));
        settings(effective);
        return effective;
    }

    static void merge(ObjectNode effective, JsonNode overrides) {
        checkFields(overrides);
        overrides.fields().forEachRemaining(field -> {
            if (field.getKey().equals("thresholds"))
                ((ObjectNode) effective.get("thresholds")).setAll((ObjectNode) field.getValue());
            else effective.set(field.getKey(), field.getValue());
        });
    }

    private static void checkFields(JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("monitor config must be an object");
        node.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) throw new IllegalArgumentException("Unknown monitor setting: " + field);
        });
        if (node.has("thresholds")) {
            if (!node.get("thresholds").isObject()) throw new IllegalArgumentException("thresholds must be an object");
            node.get("thresholds").fieldNames().forEachRemaining(metric -> {
                if (!METRICS.contains(metric)) throw new IllegalArgumentException("Unknown resource: " + metric);
            });
        }
    }

    static Settings settings(JsonNode node) {
        checkFields(node);
        if (!node.path("enabled").isBoolean()) throw new IllegalArgumentException("enabled must be boolean");
        long interval = integer(node, "intervalMs", 1000, 60000);
        int breaches = (int) integer(node, "breachCount", 1, 100);
        long cooldown = integer(node, "cooldownMs", 1000, 3600000);
        double horizon = number(node, "projectionSeconds", 0, 300);
        double alpha = number(node, "momentumAlpha", 0.01, 1);
        double minimum = number(node, "minMomentumPercentPerSecond", 0.01, 100);
        Map<String, Double> thresholds = new LinkedHashMap<>();
        for (String metric : METRICS) thresholds.put(metric, number(node.path("thresholds"), metric, 0, 100));
        if (node.path("enabled").asBoolean() && thresholds.values().stream().noneMatch(v -> v > 0))
            throw new IllegalArgumentException("Enable at least one resource threshold before enabling the monitor");
        return new Settings(node.path("enabled").asBoolean(), interval, breaches, cooldown,
                horizon, alpha, minimum, thresholds);
    }

    private static long integer(JsonNode node, String field, long min, long max) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < min || value.asLong() > max)
            throw new IllegalArgumentException(field + " must be an integer in [" + min + ", " + max + "]");
        return value.asLong();
    }

    private static double number(JsonNode node, String field, double min, double max) {
        JsonNode value = node.path(field);
        if (!value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() < min || value.asDouble() > max)
            throw new IllegalArgumentException(field + " must be a number in [" + min + ", " + max + "]");
        return value.asDouble();
    }

    public static String command(Path root, String args) {
        return command(root, args, userFile());
    }

    static String command(Path root, String args, Path user) {
        try {
            String input = args.strip();
            boolean global = input.startsWith("global ");
            if (global) input = input.substring(7).strip();
            Path target = global ? user : projectFile(root);
            String[] parts = input.split("\\s+", 3);
            if (input.isBlank() || input.equals("show"))
                return "System pressure monitor (owned chat commands only):\n" + load(user, projectFile(root)).toPrettyString()
                        + "\nSources: " + user + " < " + projectFile(root);
            ObjectNode patch = JsonUtils.standardMapper().createObjectNode();
            if (input.equals("enable") || input.equals("disable")) patch.put("enabled", input.equals("enable"));
            else if (parts[0].equals("set") && parts.length == 3) {
                if (METRICS.contains(parts[1])) patch.putObject("thresholds").set(parts[1], JsonUtils.standardMapper().readTree(parts[2]));
                else patch.set(parts[1], JsonUtils.standardMapper().readTree(parts[2]));
            } else return "Usage: /resources [global] monitor show|enable|disable|set <resource/setting> <value>\n"
                    + "Resources (percent, 0 disables): cpu ram swap disk gpuMemory gpuUtilization\n"
                    + "Settings: intervalMs breachCount cooldownMs projectionSeconds momentumAlpha minMomentumPercentPerSecond";
            ObjectNode saved = overrides(target);
            // Merge nested resource thresholds without materializing inherited defaults.
            ObjectNode effective = global ? load(user) : load(user, projectFile(root));
            merge(effective, patch);
            settings(effective);
            patch.fields().forEachRemaining(field -> {
                if (field.getKey().equals("thresholds")) {
                    if (!saved.has("thresholds")) saved.putObject("thresholds");
                    ((ObjectNode) saved.get("thresholds")).setAll((ObjectNode) field.getValue());
                } else saved.set(field.getKey(), field.getValue());
            });
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), "resource-monitor-", ".tmp");
            try {
                JsonUtils.standardMapper().writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), saved);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(tmp); }
            return "Saved " + target + "; applies on the next monitor poll. Only owned chat command trees are killable.";
        } catch (Exception failure) { return "Resource monitor error: " + failure.getMessage(); }
    }
}
