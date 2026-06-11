/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.mcp.stdio;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Enforces command guardrails for spawned subagent processes.
 *
 * <p>Guardrails are loaded from (in priority order):
 * <ol>
 *   <li>{@code .kompile/config/command-guardrails.json} in the project tree</li>
 *   <li>{@code ~/.kompile/config/command-guardrails.json} (global)</li>
 *   <li>Built-in defaults when an {@code AGENTS.md} file is present</li>
 * </ol>
 *
 * <p>Each guardrail entry consists of a pattern (regex or literal command fragment)
 * and an action (DENY or WARN). When a prompt is checked against the guardrails,
 * any matching DENY entry causes the check to fail with a descriptive message.</p>
 */
public class CommandGuardrail {

    /** Action taken when a guardrail matches. */
    public enum Action { DENY, WARN }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GuardrailRule {
        @JsonProperty("pattern")
        private String pattern;

        @JsonProperty("description")
        private String description;

        @JsonProperty("action")
        private Action action = Action.DENY;

        @JsonProperty("isRegex")
        private boolean isRegex;

        public GuardrailRule() {}

        public GuardrailRule(String pattern, String description, Action action, boolean isRegex) {
            this.pattern = pattern;
            this.description = description;
            this.action = action;
            this.isRegex = isRegex;
        }

        public String getPattern() { return pattern; }
        public String getDescription() { return description; }
        public Action getAction() { return action; }
        public boolean isRegex() { return isRegex; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GuardrailConfig {
        @JsonProperty("enabled")
        private boolean enabled = true;

        @JsonProperty("rules")
        private List<GuardrailRule> rules = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public List<GuardrailRule> getRules() { return rules; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public void setRules(List<GuardrailRule> rules) { this.rules = rules; }
    }

    /** Result of checking a prompt against guardrails. */
    public static class CheckResult {
        private final boolean allowed;
        private final List<String> violations;
        private final List<String> warnings;

        private CheckResult(boolean allowed, List<String> violations, List<String> warnings) {
            this.allowed = allowed;
            this.violations = violations;
            this.warnings = warnings;
        }

        public static CheckResult pass() {
            return new CheckResult(true, Collections.emptyList(), Collections.emptyList());
        }

        public static CheckResult fail(List<String> violations, List<String> warnings) {
            return new CheckResult(false, violations, warnings);
        }

        public static CheckResult warn(List<String> warnings) {
            return new CheckResult(true, Collections.emptyList(), warnings);
        }

        public boolean isAllowed() { return allowed; }
        public List<String> getViolations() { return violations; }
        public List<String> getWarnings() { return warnings; }
    }

    private final List<GuardrailRule> rules;
    private final boolean enabled;
    private final List<Pattern> compiledPatterns;

    private CommandGuardrail(List<GuardrailRule> rules, boolean enabled) {
        this.rules = rules;
        this.enabled = enabled;
        this.compiledPatterns = new ArrayList<>();
        for (GuardrailRule rule : rules) {
            if (rule.isRegex()) {
                compiledPatterns.add(Pattern.compile(rule.getPattern(), Pattern.CASE_INSENSITIVE));
            } else {
                // Treat as literal — escape for regex matching
                compiledPatterns.add(Pattern.compile(Pattern.quote(rule.getPattern()), Pattern.CASE_INSENSITIVE));
            }
        }
    }

    /**
     * Load guardrails from the project and user config, with built-in defaults
     * when an AGENTS.md is present.
     */
    public static CommandGuardrail load(Path workDir) {
        ObjectMapper om = new ObjectMapper();

        // Try project-level config first
        Path projectConfig = workDir.resolve(".kompile").resolve("config").resolve("command-guardrails.json");
        if (Files.exists(projectConfig)) {
            try {
                GuardrailConfig config = om.readValue(projectConfig.toFile(), GuardrailConfig.class);
                return new CommandGuardrail(config.getRules(), config.isEnabled());
            } catch (IOException e) {
                System.err.println("[Guardrail] Warning: Could not read " + projectConfig + ": " + e.getMessage());
            }
        }

        // Try global user config
        Path globalConfig = Path.of(System.getProperty("user.home"), ".kompile", "config", "command-guardrails.json");
        if (Files.exists(globalConfig)) {
            try {
                GuardrailConfig config = om.readValue(globalConfig.toFile(), GuardrailConfig.class);
                return new CommandGuardrail(config.getRules(), config.isEnabled());
            } catch (IOException e) {
                System.err.println("[Guardrail] Warning: Could not read " + globalConfig + ": " + e.getMessage());
            }
        }

        // Use built-in defaults
        return new CommandGuardrail(defaultRules(), true);
    }

    /** Create a guardrail with no rules (everything allowed). */
    public static CommandGuardrail disabled() {
        return new CommandGuardrail(Collections.emptyList(), false);
    }

    /** Create a guardrail with explicit rules (for testing). */
    public static CommandGuardrail withRules(List<GuardrailRule> rules) {
        return new CommandGuardrail(rules, true);
    }

    /**
     * Check a prompt against all guardrail rules.
     *
     * @param prompt the prompt text to check
     * @return CheckResult with violations and/or warnings
     */
    public CheckResult check(String prompt) {
        if (!enabled || rules.isEmpty() || prompt == null) return CheckResult.pass();

        List<String> violations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (int i = 0; i < rules.size(); i++) {
            GuardrailRule rule = rules.get(i);
            Pattern pattern = compiledPatterns.get(i);

            if (pattern.matcher(prompt).find()) {
                String msg = rule.getDescription() != null
                        ? rule.getDescription()
                        : "Matched guardrail pattern: " + rule.getPattern();

                if (rule.getAction() == Action.DENY) {
                    violations.add(msg);
                } else {
                    warnings.add(msg);
                }
            }
        }

        if (!violations.isEmpty()) {
            return CheckResult.fail(violations, warnings);
        } else if (!warnings.isEmpty()) {
            return CheckResult.warn(warnings);
        }
        return CheckResult.pass();
    }

    /**
     * Build a guardrail instruction block to inject into spawned agent prompts.
     * Returns empty string if no rules are configured.
     */
    public String buildPromptInjection() {
        if (!enabled || rules.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n# Command Guardrails\n\n");
        sb.append("The following commands are restricted in this project. ");
        sb.append("Do NOT use these commands or patterns:\n\n");

        for (GuardrailRule rule : rules) {
            if (rule.getAction() == Action.DENY) {
                sb.append("- **BANNED**: `").append(rule.getPattern()).append("`");
                if (rule.getDescription() != null) {
                    sb.append(" — ").append(rule.getDescription());
                }
                sb.append("\n");
            }
        }

        boolean hasWarnings = rules.stream().anyMatch(r -> r.getAction() == Action.WARN);
        if (hasWarnings) {
            sb.append("\nThe following commands should be used with caution:\n\n");
            for (GuardrailRule rule : rules) {
                if (rule.getAction() == Action.WARN) {
                    sb.append("- **CAUTION**: `").append(rule.getPattern()).append("`");
                    if (rule.getDescription() != null) {
                        sb.append(" — ").append(rule.getDescription());
                    }
                    sb.append("\n");
                }
            }
        }

        return sb.toString();
    }

    public boolean isEnabled() { return enabled; }
    public List<GuardrailRule> getRules() { return Collections.unmodifiableList(rules); }

    // ── Built-in defaults ────────────────────────────────────────────────

    static List<GuardrailRule> defaultRules() {
        List<GuardrailRule> rules = new ArrayList<>();

        // Destructive git operations
        rules.add(new GuardrailRule("git reset --hard", "Destructive: discards all uncommitted changes", Action.DENY, false));
        rules.add(new GuardrailRule("git checkout -- ", "Destructive: discards file changes without confirmation", Action.DENY, false));
        rules.add(new GuardrailRule("git stash", "Use explicit branches or commits instead of stash", Action.WARN, false));
        rules.add(new GuardrailRule("git clean -f", "Destructive: removes untracked files permanently", Action.DENY, false));
        rules.add(new GuardrailRule("git push --force", "Destructive: overwrites remote history", Action.DENY, false));

        // Build tool misuse
        rules.add(new GuardrailRule("ccache -C", "Destructive: clears entire ccache, slows subsequent builds", Action.DENY, false));
        rules.add(new GuardrailRule("ccache --clear", "Destructive: clears entire ccache, slows subsequent builds", Action.DENY, false));
        rules.add(new GuardrailRule("\\bmake\\b(?!file)", "Use Maven to build, not direct make invocations", Action.DENY, true));

        // Log consumption (use tee, not tail)
        rules.add(new GuardrailRule("\\btail\\b.*\\.(log|txt|out)", "Use tee to capture output, not tail on build/test logs", Action.DENY, true));

        // Unsafe environment manipulation
        rules.add(new GuardrailRule("LD_PRELOAD=", "Do not override system library loading", Action.DENY, false));
        rules.add(new GuardrailRule("export\\s+\\w+=.*&&\\s*mvn", "Do not set env vars inline before Maven; use pom.xml properties", Action.DENY, true));

        // Process killing
        rules.add(new GuardrailRule("killall java", "Never kill all Java processes; kill specific PIDs only", Action.DENY, false));
        rules.add(new GuardrailRule("pkill java", "Never kill all Java processes; kill specific PIDs only", Action.DENY, false));
        rules.add(new GuardrailRule("pkill -f java", "Never kill all Java processes; kill specific PIDs only", Action.DENY, false));

        return rules;
    }
}
