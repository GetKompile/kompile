package ai.kompile.cli.main.chat.enforcer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EnforcerConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void saveAndLoadRoundTrip() throws IOException {
        EnforcerConfig config = new EnforcerConfig();
        config.setAgent("codex");
        config.setKeywordMode(true);
        config.setMaxCorrections(3);
        config.setBannedTools(List.of("bash", "write"));
        config.setBannedCommands(List.of("rm -rf", "git push --force"));
        config.setDiffPatternRules(List.of("System.exit(", "BAN_DIFF_REGEX: eval\\("));
        config.setPrimaryLanguage("typescript");

        config.save(tempDir);
        assertTrue(EnforcerConfig.exists(tempDir));

        EnforcerConfig loaded = EnforcerConfig.load(tempDir);
        assertNotNull(loaded);
        assertEquals("codex", loaded.getAgent());
        assertTrue(loaded.isKeywordMode());
        assertEquals(3, loaded.getMaxCorrections());
        assertEquals(List.of("bash", "write"), loaded.getBannedTools());
        assertEquals(List.of("rm -rf", "git push --force"), loaded.getBannedCommands());
        assertEquals(2, loaded.getDiffPatternRules().size());
        assertEquals("typescript", loaded.getPrimaryLanguage());
    }

    @Test
    void loadReturnsNullWhenNoConfig() {
        assertNull(EnforcerConfig.load(tempDir));
        assertFalse(EnforcerConfig.exists(tempDir));
    }

    @Test
    void deleteRemovesConfig() throws IOException {
        EnforcerConfig config = new EnforcerConfig();
        config.save(tempDir);
        assertTrue(EnforcerConfig.exists(tempDir));

        assertTrue(EnforcerConfig.delete(tempDir));
        assertFalse(EnforcerConfig.exists(tempDir));
    }

    @Test
    void buildRulesTextCombinesAllSources() throws IOException {
        EnforcerConfig config = new EnforcerConfig();
        config.setInlineRules("BAN: badword\nSTOP: danger");
        config.setBannedTools(List.of("bash"));
        config.setBannedCommands(List.of("rm -rf"));
        config.setBannedKeywords(List.of("TODO"));
        config.setDiffPatternRules(List.of("System.exit("));

        String rules = config.buildRulesText(tempDir);

        assertTrue(rules.contains("BAN: badword"));
        assertTrue(rules.contains("STOP: danger"));
        assertTrue(rules.contains("BAN_TOOL: bash"));
        assertTrue(rules.contains("BAN_CMD: rm -rf"));
        assertTrue(rules.contains("BAN: TODO"));
        assertTrue(rules.contains("BAN_DIFF: System.exit("));
    }

    @Test
    void buildRulesTextLoadsFromRuleFile() throws IOException {
        Path ruleFile = tempDir.resolve("rules.txt");
        Files.writeString(ruleFile, "BAN: fromfile\nSTOP_TOOL: bash");

        EnforcerConfig config = new EnforcerConfig();
        config.setRuleFile("rules.txt");

        String rules = config.buildRulesText(tempDir);
        assertTrue(rules.contains("BAN: fromfile"));
        assertTrue(rules.contains("STOP_TOOL: bash"));
    }

    @Test
    void buildRulesTextHandlesMissingRuleFile() throws IOException {
        EnforcerConfig config = new EnforcerConfig();
        config.setRuleFile("nonexistent.txt");
        config.setInlineRules("BAN: fallback");

        String rules = config.buildRulesText(tempDir);
        // Should not throw, just skip the missing file
        assertTrue(rules.contains("BAN: fallback"));
    }

    @Test
    void defaultValuesAreReasonable() {
        EnforcerConfig config = new EnforcerConfig();
        assertEquals("claude", config.getAgent());
        assertTrue(config.isSkipPermissions());
        assertTrue(config.isInjectTools());
        assertTrue(config.isInjectSkills());
        assertEquals(2, config.getMaxCorrections());
        assertFalse(config.isKeywordMode());
        assertTrue(config.isArchiveDiffs());
        assertTrue(config.isAutoRollbackOnViolation());
        assertEquals(168, config.getArchiveRetentionHours());
        assertEquals("java", config.getPrimaryLanguage());
    }

    @Test
    void resolveConfigPathIsUnderKompileDir() {
        Path configPath = EnforcerConfig.resolveConfigPath(tempDir);
        assertTrue(configPath.toString().contains(".kompile"));
        assertTrue(configPath.toString().endsWith("enforcer-config.json"));
    }

    @Test
    void diffPatternRulesAddPrefix() throws IOException {
        EnforcerConfig config = new EnforcerConfig();
        // Pattern without BAN_DIFF prefix should get it added
        config.setDiffPatternRules(List.of("System.exit(", "BAN_DIFF_REGEX: eval\\("));

        String rules = config.buildRulesText(tempDir);
        assertTrue(rules.contains("BAN_DIFF: System.exit("));
        // Already prefixed one should not double-prefix
        assertTrue(rules.contains("BAN_DIFF_REGEX: eval\\("));
        assertFalse(rules.contains("BAN_DIFF: BAN_DIFF_REGEX:"));
    }

    @Test
    void judgeSettingsRoundTrip() throws IOException {
        EnforcerConfig config = new EnforcerConfig();
        config.setJudgeProvider("anthropic");
        config.setJudgeModel("claude-sonnet-4-20250514");
        config.setJudgeMode("remote");

        config.save(tempDir);
        EnforcerConfig loaded = EnforcerConfig.load(tempDir);

        assertEquals("anthropic", loaded.getJudgeProvider());
        assertEquals("claude-sonnet-4-20250514", loaded.getJudgeModel());
        assertEquals("remote", loaded.getJudgeMode());
    }
}
