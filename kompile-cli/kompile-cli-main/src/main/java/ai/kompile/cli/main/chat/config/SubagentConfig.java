package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.main.chat.SessionModelLadder.ModelRung;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.regex.Pattern;

/** User-wide admission policy, reloaded for each new child run. Existing runs are not killed. */
public record SubagentConfig(int maxConcurrent, List<String> bannedModels,
                             boolean failoverEnabled, List<ModelRung> failoverRungs) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public SubagentConfig {
        if (maxConcurrent < -1) throw new IllegalArgumentException("maxConcurrent must be -1 (unlimited), 0 (disabled), or a positive integer");
        if (bannedModels == null) throw new IllegalArgumentException("bannedModels must be an array of glob patterns");
        bannedModels = bannedModels.stream().map(pattern -> {
            if (pattern == null || pattern.isBlank()) throw new IllegalArgumentException("Model ban patterns cannot be blank");
            return pattern.strip();
        }).distinct().toList();
        if (failoverRungs == null) throw new IllegalArgumentException("failoverRungs must be an array");
        if (failoverRungs.stream().anyMatch(rung -> rung == null || !rung.hasModel())) {
            throw new IllegalArgumentException("Fallback routes require a model");
        }
        failoverRungs = List.copyOf(failoverRungs);
    }

    public SubagentConfig(int maxConcurrent, List<String> bannedModels) {
        this(maxConcurrent, bannedModels, true, List.of());
    }

    public static SubagentConfig defaults() { return new SubagentConfig(-1, List.of()); }
    public static Path path() { return KompileHome.homeDirectory().toPath().resolve("config/subagent-config.json"); }
    public static SubagentConfig load() throws IOException { return load(path()); }

    public static SubagentConfig load(Path path) throws IOException {
        if (Files.notExists(path)) return defaults();
        try {
            var root = JSON.readTree(path.toFile());
            if (root == null || !root.isObject()) throw new IllegalArgumentException("Expected a JSON object");
            int max = -1;
            if (root.has("maxConcurrent")) {
                var value = root.get("maxConcurrent");
                if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException("maxConcurrent must be an integer");
                max = value.intValue();
            }
            var patterns = new java.util.ArrayList<String>();
            if (root.has("bannedModels")) {
                if (!root.get("bannedModels").isArray()) throw new IllegalArgumentException("bannedModels must be an array");
                for (var value : root.get("bannedModels")) {
                    if (!value.isTextual()) throw new IllegalArgumentException("Model ban patterns must be strings");
                    patterns.add(value.textValue());
                }
            }
            boolean enabled = true;
            if (root.has("failoverEnabled")) {
                if (!root.get("failoverEnabled").isBoolean()) throw new IllegalArgumentException("failoverEnabled must be a boolean");
                enabled = root.get("failoverEnabled").booleanValue();
            }
            var rungs = new java.util.ArrayList<ModelRung>();
            if (root.has("failoverRungs")) {
                if (!root.get("failoverRungs").isArray()) throw new IllegalArgumentException("failoverRungs must be an array");
                for (var rung : root.get("failoverRungs")) {
                    if (!rung.isObject()) throw new IllegalArgumentException("Fallback routes must be objects");
                    rungs.add(new ModelRung(text(rung, "provider"), text(rung, "model"),
                            text(rung, "credentialName"), text(rung, "label"), text(rung, "thinking"),
                            text(rung, "authenticationMethod")));
                }
            }
            return new SubagentConfig(max, patterns, enabled, rungs);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid subagent configuration at " + path + ": " + e.getMessage(), e);
        }
    }

    private static String text(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("Fallback " + field + " must be a string");
        return value.textValue();
    }

    public void save(Path path) throws IOException {
        Path target = path.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), "subagent-config-", ".tmp");
        try {
            // Tree serialization also works in the native CLI without registering this record for reflection.
            var root = JSON.createObjectNode().put("maxConcurrent", maxConcurrent);
            var patterns = root.putArray("bannedModels");
            bannedModels.forEach(patterns::add);
            root.put("failoverEnabled", failoverEnabled);
            var rungs = root.putArray("failoverRungs");
            for (ModelRung rung : failoverRungs) {
                // Explicit allowlist: references only, never credential values or reflective serialization.
                var entry = rungs.addObject().put("model", rung.model());
                if (rung.provider() != null) entry.put("provider", rung.provider());
                if (rung.credentialName() != null) entry.put("credentialName", rung.credentialName());
                if (rung.label() != null) entry.put("label", rung.label());
                if (rung.thinking() != null) entry.put("thinking", rung.thinking());
                if (rung.authenticationMethod() != null) entry.put("authenticationMethod", rung.authenticationMethod());
            }
            JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), root);
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    /** Case-insensitive full glob match on a model id or provider/model; only * and ? are special. */
    public String matchingBan(String provider, String model) {
        if (model == null || model.isBlank()) return null;
        String qualified = provider == null || provider.isBlank() ? model : provider + "/" + model;
        for (String glob : bannedModels) {
            StringBuilder regex = new StringBuilder();
            for (char c : glob.toCharArray()) {
                regex.append(c == '*' ? ".*" : c == '?' ? "." : Pattern.quote(String.valueOf(c)));
            }
            Pattern pattern = Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
            if (pattern.matcher(model).matches() || pattern.matcher(qualified).matches()) return glob;
        }
        return null;
    }
}
