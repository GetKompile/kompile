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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

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
@JsonIgnoreProperties(ignoreUnknown = true)
public class EnforcerConfig {

    private static final String CONFIG_FILENAME = "enforcer-config.json";
    private static final ObjectMapper MAPPER = new ObjectMapper()
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

    // ── Constructor ────────────────────────────────────────────────────────

    public EnforcerConfig() {}

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
            System.err.println("[enforcer] warning: could not read " + configPath + ": " + e.getMessage());
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
                System.err.println("[enforcer] warning: could not read " + codeProjectConfig + ": " + e.getMessage());
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

    // ── Getters & Setters ──────────────────────────────────────────────────

    public String getAgent() { return agent; }
    public void setAgent(String agent) { this.agent = agent; }

    public boolean isSkipPermissions() { return skipPermissions; }
    public void setSkipPermissions(boolean skipPermissions) { this.skipPermissions = skipPermissions; }

    public boolean isInjectTools() { return injectTools; }
    public void setInjectTools(boolean injectTools) { this.injectTools = injectTools; }

    public boolean isInjectSkills() { return injectSkills; }
    public void setInjectSkills(boolean injectSkills) { this.injectSkills = injectSkills; }

    public String getRuleFile() { return ruleFile; }
    public void setRuleFile(String ruleFile) { this.ruleFile = ruleFile; }

    public String getInlineRules() { return inlineRules; }
    public void setInlineRules(String inlineRules) { this.inlineRules = inlineRules; }

    public int getMaxCorrections() { return maxCorrections; }
    public void setMaxCorrections(int maxCorrections) { this.maxCorrections = maxCorrections; }

    public boolean isKeywordMode() { return keywordMode; }
    public void setKeywordMode(boolean keywordMode) { this.keywordMode = keywordMode; }

    public boolean isArchiveDiffs() { return archiveDiffs; }
    public void setArchiveDiffs(boolean archiveDiffs) { this.archiveDiffs = archiveDiffs; }

    public boolean isAutoRollbackOnViolation() { return autoRollbackOnViolation; }
    public void setAutoRollbackOnViolation(boolean autoRollbackOnViolation) { this.autoRollbackOnViolation = autoRollbackOnViolation; }

    public int getArchiveRetentionHours() { return archiveRetentionHours; }
    public void setArchiveRetentionHours(int archiveRetentionHours) { this.archiveRetentionHours = archiveRetentionHours; }

    public String getDiffPatternsFile() { return diffPatternsFile; }
    public void setDiffPatternsFile(String diffPatternsFile) { this.diffPatternsFile = diffPatternsFile; }

    public List<String> getDiffPatternRules() { return diffPatternRules; }
    public void setDiffPatternRules(List<String> diffPatternRules) { this.diffPatternRules = diffPatternRules; }

    public String getPrimaryLanguage() { return primaryLanguage; }
    public void setPrimaryLanguage(String primaryLanguage) { this.primaryLanguage = primaryLanguage; }

    public String getJudgeMode() { return judgeMode; }
    public void setJudgeMode(String judgeMode) { this.judgeMode = judgeMode; }

    public String getJudgeProvider() { return judgeProvider; }
    public void setJudgeProvider(String judgeProvider) { this.judgeProvider = judgeProvider; }

    public String getJudgeModel() { return judgeModel; }
    public void setJudgeModel(String judgeModel) { this.judgeModel = judgeModel; }

    public String getJudgeApiKey() { return judgeApiKey; }
    public void setJudgeApiKey(String judgeApiKey) { this.judgeApiKey = judgeApiKey; }

    public String getJudgeBaseUrl() { return judgeBaseUrl; }
    public void setJudgeBaseUrl(String judgeBaseUrl) { this.judgeBaseUrl = judgeBaseUrl; }

    public String getKompileUrl() { return kompileUrl; }
    public void setKompileUrl(String kompileUrl) { this.kompileUrl = kompileUrl; }

    public int getMcpPort() { return mcpPort; }
    public void setMcpPort(int mcpPort) { this.mcpPort = mcpPort; }

    public List<String> getBannedTools() { return bannedTools; }
    public void setBannedTools(List<String> bannedTools) { this.bannedTools = bannedTools; }

    public List<String> getBannedCommands() { return bannedCommands; }
    public void setBannedCommands(List<String> bannedCommands) { this.bannedCommands = bannedCommands; }

    public List<String> getBannedKeywords() { return bannedKeywords; }
    public void setBannedKeywords(List<String> bannedKeywords) { this.bannedKeywords = bannedKeywords; }

    public String getSemanticMode() { return semanticMode; }
    public void setSemanticMode(String semanticMode) { this.semanticMode = semanticMode; }

    public double getSemanticThreshold() { return semanticThreshold; }
    public void setSemanticThreshold(double semanticThreshold) { this.semanticThreshold = semanticThreshold; }

    public String getEmbeddingUrl() { return embeddingUrl; }
    public void setEmbeddingUrl(String embeddingUrl) { this.embeddingUrl = embeddingUrl; }

    public String getSynonymDictionaryPath() { return synonymDictionaryPath; }
    public void setSynonymDictionaryPath(String synonymDictionaryPath) { this.synonymDictionaryPath = synonymDictionaryPath; }
}
