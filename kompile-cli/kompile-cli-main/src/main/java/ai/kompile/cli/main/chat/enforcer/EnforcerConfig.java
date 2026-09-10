/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.enforcer;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.Data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-project enforcer configuration persisted at {@code .kompile/enforcer-config.json}.
 * Created via the interactive setup wizard ({@code kompile enforcer init}) and loaded
 * automatically when running enforcer mode in the same project directory.
 *
 * <p>This config stores all the options that would otherwise be passed via CLI flags,
 * so teams can check in a shared enforcer configuration.</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EnforcerConfig {

    private static final String CONFIG_FILENAME = "enforcer-config.json";
    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    // ── Agent settings ─────────────────────────────────────────────────────

    @JsonProperty
    private String agent = "claude";

    @JsonProperty
    private boolean skipPermissions = true;

    @JsonProperty
    private boolean injectTools = true;

    @JsonProperty
    private boolean injectSkills = true;

    // ── Rule settings ──────────────────────────────────────────────────────

    @JsonProperty
    private String ruleFile;

    @JsonProperty
    private String inlineRules;

    @JsonProperty
    private int maxCorrections = 2;

    @JsonProperty
    private boolean keywordMode = false;

    // ── Diff archiving ─────────────────────────────────────────────────────

    @JsonProperty
    private boolean archiveDiffs = true;

    @JsonProperty
    private boolean autoRollbackOnViolation = true;

    @JsonProperty
    private int archiveRetentionHours = 168; // 7 days

    // ── Diff pattern checking ──────────────────────────────────────────────

    @JsonProperty
    private String diffPatternsFile;

    @JsonProperty
    private List<String> diffPatternRules = new ArrayList<>();

    @JsonProperty
    private String primaryLanguage = "java";

    // ── Judge settings ─────────────────────────────────────────────────────

    @JsonProperty
    private String judgeMode; // auto, remote, local

    @JsonProperty
    private String judgeProvider;

    @JsonProperty
    private String judgeModel;

    @JsonProperty
    private String judgeApiKey;

    @JsonProperty
    private String judgeBaseUrl;

    /** What to do when the judge is unavailable or fails mid-turn: fail_open | fail_closed | degrade_to_keyword. */
    @JsonProperty
    private String judgeFallbackPolicy = "fail_open";

    // ── Host-enforced workflow profile ─────────────────────────────────────

    /** off | advisory | enforced. Kept orthogonal to standard/passthrough transport mode. */
    @JsonProperty
    private String workflowMode = "off";

    /** Exact skill names expanded by the host before the first model request. */
    @JsonProperty
    private List<String> workflowRequiredSkills = new ArrayList<>();

    /** Hold mutating tools until this turn successfully creates a todo plan. */
    @JsonProperty
    private boolean workflowRequirePlanBeforeMutation = true;

    /** Bounded final-response corrections after a mutation was held for missing prerequisites. */
    @JsonProperty
    private int workflowMaxCorrections = 2;

    // ── Direction monitoring (goal-drift judge) ────────────────────────────
    // Strictly opt-in: directionMonitoring defaults to false and is never enabled
    // implicitly. When enabled, a direction judge watches whether the conversation
    // is still moving toward the user's goal and can interrupt + redirect the agent.
    // Unlike the compliance judges, an active direction monitor is deliberately NOT
    // subject to the one-shot /judge override.

    @JsonProperty
    private boolean directionMonitoring = false;

    /** Explicit session goal; when absent the judge uses each turn's user message. */
    @JsonProperty
    private String directionGoal;

    /** Run every N model iterations; the final response is always checked (minimum 1). */
    @JsonProperty
    private int directionCheckEvery = 3;

    /** Max in-place redirects per turn before the judge halts the turn (0..4). */
    @JsonProperty
    private int directionMaxRedirects = 2;

    /** Minimum confidence required before a direction verdict may redirect or halt. */
    @JsonProperty
    private double directionConfidenceThreshold = 0.6;

    /** Consecutive drift-affected turns before cross-turn escalation; 0 disables it. */
    @JsonProperty
    private int directionCrossTurnDriftLimit = 3;

    /** Observe-only mode: report drift but never redirect or halt. */
    @JsonProperty
    private boolean directionReportOnly = false;

    // ── Semantic matching ─────────────────────────────────────────────────

    @JsonProperty
    private String semanticMode = "none"; // none, wordnet, embedding, both

    @JsonProperty
    private double semanticThreshold = 0.78;

    @JsonProperty
    private String embeddingUrl = "";

    @JsonProperty
    private String synonymDictionaryPath;

    // ── MCP / connectivity ─────────────────────────────────────────────────

    @JsonProperty
    private String kompileUrl = "";

    @JsonProperty
    private int mcpPort = 0;

    // ── Tool & command restrictions ────────────────────────────────────────

    @JsonProperty
    private List<String> bannedTools = new ArrayList<>();

    @JsonProperty
    private List<String> bannedCommands = new ArrayList<>();

    @JsonProperty
    private List<String> bannedKeywords = new ArrayList<>();

    // ── Persistence ────────────────────────────────────────────────────────

    /**
     * Load the enforcer config from the project's .kompile directory.
     * Returns null if no config file exists.
     */
    public static EnforcerConfig load(Path workingDir) {
        Path configPath = resolveConfigPath(workingDir);
        if (!Files.exists(configPath)) {
            return null;
        }
        try {
            return MAPPER.readValue(configPath.toFile(), EnforcerConfig.class);
        } catch (IOException e) {
            EnforcerDiagnostics.alert("[enforcer] warning: could not read " + configPath + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Save this config to the project's .kompile directory.
     */
    public void save(Path workingDir) throws IOException {
        Path configPath = resolveConfigPath(workingDir);
        Files.createDirectories(configPath.getParent());
        MAPPER.writeValue(configPath.toFile(), this);
    }

    /**
     * Check if a config file exists for this project.
     */
    public static boolean exists(Path workingDir) {
        return Files.exists(resolveConfigPath(workingDir));
    }

    /**
     * Delete the enforcer config for this project.
     */
    public static boolean delete(Path workingDir) throws IOException {
        Path configPath = resolveConfigPath(workingDir);
        return Files.deleteIfExists(configPath);
    }

    public static Path resolveConfigPath(Path workingDir) {
        return workingDir.toAbsolutePath().normalize()
                .resolve(".kompile").resolve(CONFIG_FILENAME);
    }

    // ── Per-coding-project config (stored under project root) ─────────────

    /**
     * Resolve the config path for a specific coding project within a kompile project.
     * Stored at {@code <projectRoot>/.kompile/code-projects/<codingProjectId>/enforcer-config.json}.
     */
    public static Path resolveCodeProjectConfigPath(Path projectRoot, String codingProjectId) {
        return projectRoot.toAbsolutePath().normalize()
                .resolve(".kompile").resolve("code-projects")
                .resolve(codingProjectId).resolve(CONFIG_FILENAME);
    }

    /**
     * Load the enforcer config for a specific coding project within a kompile project.
     * Falls back to the project-level config if no per-coding-project config exists.
     * Returns null if neither exists.
     */
    public static EnforcerConfig loadForCodeProject(Path projectRoot, String codingProjectId) {
        Path codeProjectConfig = resolveCodeProjectConfigPath(projectRoot, codingProjectId);
        if (Files.exists(codeProjectConfig)) {
            try {
                return MAPPER.readValue(codeProjectConfig.toFile(), EnforcerConfig.class);
            } catch (IOException e) {
                EnforcerDiagnostics.alert("[enforcer] warning: could not read " + codeProjectConfig + ": " + e.getMessage());
            }
        }
        // Fall back to project-level config
        return load(projectRoot);
    }

    /**
     * Save this config for a specific coding project within a kompile project.
     */
    public void saveForCodeProject(Path projectRoot, String codingProjectId) throws IOException {
        Path configPath = resolveCodeProjectConfigPath(projectRoot, codingProjectId);
        Files.createDirectories(configPath.getParent());
        MAPPER.writeValue(configPath.toFile(), this);
    }

    /**
     * Check if a per-coding-project config exists.
     */
    public static boolean existsForCodeProject(Path projectRoot, String codingProjectId) {
        return Files.exists(resolveCodeProjectConfigPath(projectRoot, codingProjectId));
    }

    /**
     * Delete the per-coding-project enforcer config.
     */
    public static boolean deleteForCodeProject(Path projectRoot, String codingProjectId) throws IOException {
        return Files.deleteIfExists(resolveCodeProjectConfigPath(projectRoot, codingProjectId));
    }

    // ── Rule resolution ────────────────────────────────────────────────────

    /**
     * Build the complete rules text from this config.
     * Combines inline rules, banned tools/commands/keywords, and diff patterns.
     */
    public String buildRulesText(Path workingDir) throws IOException {
        StringBuilder sb = new StringBuilder();

        // Load from rule file if specified
        if (ruleFile != null && !ruleFile.isBlank()) {
            Path rulePath = workingDir.resolve(ruleFile);
            if (Files.exists(rulePath)) {
                sb.append(Files.readString(rulePath));
                sb.append("\n");
            }
        }

        // Inline rules
        if (inlineRules != null && !inlineRules.isBlank()) {
            sb.append(inlineRules).append("\n");
        }

        // Banned tools
        for (String tool : bannedTools) {
            sb.append("BAN_TOOL: ").append(tool).append("\n");
        }

        // Banned commands
        for (String cmd : bannedCommands) {
            sb.append("BAN_CMD: ").append(cmd).append("\n");
        }

        // Banned keywords
        for (String kw : bannedKeywords) {
            sb.append("BAN: ").append(kw).append("\n");
        }

        // Diff patterns (inline)
        for (String pattern : diffPatternRules) {
            if (!pattern.toUpperCase().startsWith("BAN_DIFF")) {
                sb.append("BAN_DIFF: ");
            }
            sb.append(pattern).append("\n");
        }

        return sb.toString().trim();
    }

    /**
     * Whether this config should activate enforcement for a session.
     * True for keyword mode, or whenever there is anything to enforce
     * (inline rules, a rule file, banned tools/commands/keywords, or diff patterns).
     *
     * <p>Used by the chat router to escalate passthrough into an enforced session
     * for BOTH keyword and LLM-judge modes. Previously the router checked only
     * {@link #isKeywordMode()}, so an LLM-judge config silently never enforced.</p>
     */
    public boolean isEnforcementEnabled() {
        return keywordMode
                || (inlineRules != null && !inlineRules.isBlank())
                || (ruleFile != null && !ruleFile.isBlank())
                || (bannedTools != null && !bannedTools.isEmpty())
                || (bannedCommands != null && !bannedCommands.isEmpty())
                || (bannedKeywords != null && !bannedKeywords.isEmpty())
                || (diffPatternRules != null && !diffPatternRules.isEmpty());
    }

    /** Whether a host workflow profile is configured, independent of enforcer text rules. */
    public boolean isWorkflowEnabled() {
        if (workflowMode == null || workflowMode.isBlank()) return false;
        String mode = workflowMode.trim().toLowerCase(java.util.Locale.ROOT);
        return !"off".equals(mode) && !"disabled".equals(mode) && !"none".equals(mode);
    }

    /**
     * Decide whether a passthrough session should use its configured judge policy.
     * A project config now activates without a startup prompt; users control the live
     * session with {@code /judge on|off} and every session with {@code /judge global on|off}.
     *
     * <ul>
     *   <li>Explicit CLI rule flags ({@code --rules}/{@code --rule-file}) always activate.</li>
     *   <li>{@code sessionChoice == null} uses the project configuration.</li>
     *   <li>{@code sessionChoice == TRUE} activates only when the project config actually
     *       has something to enforce ({@link #isEnforcementEnabled()}).</li>
     *   <li>{@code sessionChoice == FALSE} never activates, regardless of what is on disk.</li>
     * </ul>
     *
     * @param sessionChoice        the user's Y/N answer for this run, or {@code null} if not asked
     * @param hasExplicitRuleFlags whether {@code --rules}/{@code --rule-file} were passed on the CLI
     * @param projectConfig        the loaded {@code .kompile/enforcer-config.json}, or {@code null}
     */
    public static boolean shouldActivate(Boolean sessionChoice, boolean hasExplicitRuleFlags,
                                         EnforcerConfig projectConfig) {
        if (hasExplicitRuleFlags) {
            return true;
        }
        if (Boolean.FALSE.equals(sessionChoice)) {
            return false;
        }
        return projectConfig != null && projectConfig.isEnforcementEnabled();
    }

}
