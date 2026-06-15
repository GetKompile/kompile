package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.mcp.McpSseClient;
import org.jline.reader.Candidate;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ChatCompleter}.
 * Uses a stub ParsedLine to exercise completion without a real terminal.
 */
class ChatCompleterTest {

    private List<McpSseClient.ToolInfo> toolList;
    private Set<String> skillNames;
    private ChatCompleter completer;

    @BeforeEach
    void setUp() {
        toolList = List.of(
                new McpSseClient.ToolInfo("read", "Read a file", null),
                new McpSseClient.ToolInfo("write", "Write a file", null),
                new McpSseClient.ToolInfo("grep", "Search file contents", null),
                new McpSseClient.ToolInfo("glob", "Find files by pattern", null),
                new McpSseClient.ToolInfo("bash", "Execute shell command", null)
        );
        skillNames = new LinkedHashSet<>(List.of("commit", "review", "fix", "test", "explain", "simplify", "pr"));

        completer = new ChatCompleter(
                () -> toolList,
                () -> skillNames,
                () -> new LinkedHashSet<>(List.of("coder", "planner")),
                () -> new LinkedHashSet<>(List.of("senior-dev", "devops", "architect"))
        );
    }

    // ========================================================================
    // Command name completion
    // ========================================================================

    @Test
    void slashAloneCompletesAllCommandsAndSkills() {
        List<Candidate> candidates = complete("/");
        // Must contain every built-in command
        Set<String> values = candidateValues(candidates);
        assertTrue(values.contains("/help"), "missing /help");
        assertTrue(values.contains("/tools"), "missing /tools");
        assertTrue(values.contains("/quit"), "missing /quit");
        assertTrue(values.contains("/enforce"), "missing /enforce");
        assertTrue(values.contains("/image"), "missing /image");
        assertTrue(values.contains("/queue-send-all"), "missing /queue-send-all");
        // Skills too
        assertTrue(values.contains("/commit"), "missing /commit skill");
        assertTrue(values.contains("/review"), "missing /review skill");
    }

    @Test
    void prefixNarrowsCommandCandidates() {
        List<Candidate> candidates = complete("/qu");
        Set<String> values = candidateValues(candidates);
        assertTrue(values.contains("/queue"), "/queue should match /qu");
        assertTrue(values.contains("/queues"), "/queues should match /qu");
        assertTrue(values.contains("/queue-send"), "/queue-send should match /qu");
        assertTrue(values.contains("/quit"), "/quit should match /qu");
        assertFalse(values.contains("/help"), "/help should not match /qu");
        assertFalse(values.contains("/tools"), "/tools should not match /qu");
    }

    @Test
    void exactCommandStillAppears() {
        List<Candidate> candidates = complete("/help");
        Set<String> values = candidateValues(candidates);
        assertTrue(values.contains("/help"));
    }

    @Test
    void commandsHaveDescriptions() {
        List<Candidate> candidates = complete("/he");
        Candidate help = candidates.stream()
                .filter(c -> "/help".equals(c.value()))
                .findFirst().orElse(null);
        assertNotNull(help, "/help candidate should exist");
        assertNotNull(help.descr(), "/help should have a description");
        assertFalse(help.descr().isEmpty());
    }

    @Test
    void skillCandidatesGroupedUnderSkillCategory() {
        List<Candidate> candidates = complete("/co");
        Candidate commit = candidates.stream()
                .filter(c -> "/commit".equals(c.value()))
                .findFirst().orElse(null);
        assertNotNull(commit, "/commit should match /co");
        assertEquals("skill", commit.group());
    }

    @Test
    void nonSlashInputProducesNoCandidates() {
        List<Candidate> candidates = complete("hello");
        assertTrue(candidates.isEmpty(), "non-slash input should produce no completions");
    }

    @Test
    void emptyInputProducesNoCandidates() {
        List<Candidate> candidates = complete("");
        assertTrue(candidates.isEmpty());
    }

    // ========================================================================
    // Every handleSlashCommand case is completable
    // ========================================================================

    @Test
    void allHandleSlashCommandCasesAreCompletable() {
        // Every case in ChatRepl.handleSlashCommand() must produce a candidate
        // when the user types that exact command
        List<String> allCases = List.of(
                "/quit", "/exit", "/help", "/setup", "/tools", "/subagents",
                "/local-tools", "/tool", "/local-tool", "/status", "/history",
                "/clear", "/compact", "/rag", "/agents", "/local-agents",
                "/agent", "/local-agent", "/config", "/sessions", "/ask",
                "/agent-chat", "/conversations", "/transcript", "/memory",
                "/recall", "/permissions", "/todos", "/plan", "/queue",
                "/queues", "/queue-send", "/queue-send-all", "/queue-remove",
                "/queue-clear", "/queue-status", "/jobs", "/jobs-remove",
                "/jobs-clear", "/processes", "/process-kill", "/process-output",
                "/statusbar", "/auto-dequeue", "/enforce", "/stats",
                "/passthrough", "/resume", "/mode", "/menu", "/skills",
                "/roles", "/role", "/model", "/forward", "/image", "/file",
                "/attach", "/attachments"
        );

        for (String cmd : allCases) {
            List<Candidate> candidates = complete(cmd);
            Set<String> values = candidateValues(candidates);
            assertTrue(values.contains(cmd),
                    "Command " + cmd + " should be completable but was not found in candidates: " + values);
        }
    }

    // ========================================================================
    // Tool name completion
    // ========================================================================

    @Test
    void toolCompletionAfterToolSpace() {
        List<Candidate> candidates = complete("/tool ");
        Set<String> values = candidateValues(candidates);
        assertEquals(Set.of("read", "write", "grep", "glob", "bash"), values);
    }

    @Test
    void toolCompletionWithPrefix() {
        List<Candidate> candidates = complete("/tool gr");
        Set<String> values = candidateValues(candidates);
        assertTrue(values.contains("grep"), "grep starts with 'gr'");
        assertFalse(values.contains("glob"), "glob does not start with 'gr'");
        assertFalse(values.contains("read"), "read does not start with 'gr'");
    }

    @Test
    void toolCompletionHasDescription() {
        List<Candidate> candidates = complete("/tool re");
        Candidate read = candidates.stream()
                .filter(c -> "read".equals(c.value()))
                .findFirst().orElse(null);
        assertNotNull(read);
        assertEquals("Read a file", read.descr());
    }

    @Test
    void localToolCompletesToolNames() {
        List<Candidate> candidates = complete("/local-tool ");
        Set<String> values = candidateValues(candidates);
        assertTrue(values.contains("read"));
        assertTrue(values.contains("bash"));
    }

    @Test
    void toolsSlashDoesNotTriggerToolNameCompletion() {
        // "/tools " should NOT complete tool names — it's a different command
        List<Candidate> candidates = complete("/tools ");
        // "/tools" has no sub-arg completions defined
        assertTrue(candidates.isEmpty(),
                "/tools (with trailing space) should not produce sub-completions");
    }

    @Test
    void nullToolsSupplierProducesNoCandidates() {
        ChatCompleter nullTools = new ChatCompleter(() -> null);
        List<Candidate> candidates = new ArrayList<>();
        nullTools.complete(null, stubParsedLine("/tool "), candidates);
        assertTrue(candidates.isEmpty());
    }

    // ========================================================================
    // Agent name completion
    // ========================================================================

    @Test
    void agentCompletionAfterAgentSpace() {
        List<Candidate> candidates = complete("/agent ");
        Set<String> values = candidateValues(candidates);
        assertEquals(Set.of("coder", "planner"), values);
    }

    @Test
    void agentCompletionWithPrefix() {
        List<Candidate> candidates = complete("/agent co");
        Set<String> values = candidateValues(candidates);
        assertTrue(values.contains("coder"));
        assertFalse(values.contains("planner"));
    }

    @Test
    void localAgentCompletesAgentNames() {
        List<Candidate> candidates = complete("/local-agent p");
        Set<String> values = candidateValues(candidates);
        assertTrue(values.contains("planner"));
        assertFalse(values.contains("coder"));
    }

    @Test
    void agentCandidatesGroupedAsAgent() {
        List<Candidate> candidates = complete("/agent ");
        for (Candidate c : candidates) {
            assertEquals("agent", c.group());
        }
    }

    // ========================================================================
    // Role name completion
    // ========================================================================

    @Test
    void roleCompletionAfterRoleSpace() {
        List<Candidate> candidates = complete("/role ");
        Set<String> values = candidateValues(candidates);
        assertEquals(Set.of("senior-dev", "devops", "architect"), values);
    }

    @Test
    void roleCompletionWithPrefix() {
        List<Candidate> candidates = complete("/role dev");
        Set<String> values = candidateValues(candidates);
        assertTrue(values.contains("devops"));
        assertFalse(values.contains("architect"));
    }

    @Test
    void roleCandidatesGroupedAsRole() {
        List<Candidate> candidates = complete("/role ");
        for (Candidate c : candidates) {
            assertEquals("role", c.group());
        }
    }

    // ========================================================================
    // Sub-argument completion (enforce, rag, plan, mode)
    // ========================================================================

    @Test
    void enforceSubArgs() {
        List<Candidate> candidates = complete("/enforce ");
        Set<String> values = candidateValues(candidates);
        assertEquals(Set.of("on", "off", "rules", "score"), values);
    }

    @Test
    void enforceSubArgWithPrefix() {
        List<Candidate> candidates = complete("/enforce o");
        Set<String> values = candidateValues(candidates);
        assertTrue(values.contains("on"));
        assertTrue(values.contains("off"));
        assertFalse(values.contains("rules"));
    }

    @Test
    void enforceSubArgHasDescription() {
        List<Candidate> candidates = complete("/enforce ru");
        Candidate rules = candidates.stream()
                .filter(c -> "rules".equals(c.value()))
                .findFirst().orElse(null);
        assertNotNull(rules);
        assertEquals("Show or set enforcer rules", rules.descr());
    }

    @Test
    void ragSubArgs() {
        List<Candidate> candidates = complete("/rag ");
        Set<String> values = candidateValues(candidates);
        assertEquals(Set.of("on", "off"), values);
    }

    @Test
    void planSubArgs() {
        List<Candidate> candidates = complete("/plan ");
        Set<String> values = candidateValues(candidates);
        assertEquals(Set.of("on", "off"), values);
    }

    @Test
    void modeSubArgs() {
        List<Candidate> candidates = complete("/mode ");
        Set<String> values = candidateValues(candidates);
        assertEquals(Set.of("standard", "passthrough", "plan"), values);
    }

    @Test
    void modeSubArgWithPrefix() {
        List<Candidate> candidates = complete("/mode pa");
        Set<String> values = candidateValues(candidates);
        assertTrue(values.contains("passthrough"));
        assertFalse(values.contains("standard"));
    }

    // ========================================================================
    // File path completion
    // ========================================================================

    @Test
    void fileCompletionListsCurrentDirectory(@TempDir Path tmpDir) throws IOException {
        Files.createFile(tmpDir.resolve("photo.png"));
        Files.createFile(tmpDir.resolve("doc.txt"));
        Files.createDirectory(tmpDir.resolve("subdir"));

        List<Candidate> candidates = complete("/image " + tmpDir + File.separator);
        Set<String> displays = candidates.stream()
                .map(Candidate::displ)
                .collect(Collectors.toSet());
        assertTrue(displays.contains("photo.png"), "should list photo.png");
        assertTrue(displays.contains("doc.txt"), "should list doc.txt");
        assertTrue(displays.contains("subdir/"), "should list subdir with trailing /");
    }

    @Test
    void fileCompletionFiltersPrefix(@TempDir Path tmpDir) throws IOException {
        Files.createFile(tmpDir.resolve("alpha.txt"));
        Files.createFile(tmpDir.resolve("beta.txt"));
        Files.createFile(tmpDir.resolve("alphabeta.txt"));

        List<Candidate> candidates = complete("/file " + tmpDir + File.separator + "al");
        Set<String> displays = candidates.stream()
                .map(Candidate::displ)
                .collect(Collectors.toSet());
        assertTrue(displays.contains("alpha.txt"));
        assertTrue(displays.contains("alphabeta.txt"));
        assertFalse(displays.contains("beta.txt"));
    }

    @Test
    void fileCompletionSkipsHiddenFiles(@TempDir Path tmpDir) throws IOException {
        Files.createFile(tmpDir.resolve(".hidden"));
        Files.createFile(tmpDir.resolve("visible.txt"));

        List<Candidate> candidates = complete("/attach " + tmpDir + File.separator);
        Set<String> displays = candidates.stream()
                .map(Candidate::displ)
                .collect(Collectors.toSet());
        assertTrue(displays.contains("visible.txt"));
        assertFalse(displays.contains(".hidden"));
    }

    @Test
    void fileCompletionDirectoriesNotComplete(@TempDir Path tmpDir) throws IOException {
        Files.createDirectory(tmpDir.resolve("mydir"));
        Files.createFile(tmpDir.resolve("myfile.txt"));

        List<Candidate> candidates = complete("/image " + tmpDir + File.separator);
        // Directories have complete=false so tab doesn't close them
        Candidate dir = candidates.stream()
                .filter(c -> c.displ().equals("mydir/"))
                .findFirst().orElse(null);
        assertNotNull(dir, "directory candidate should exist");
        assertFalse(dir.complete(), "directory should not be marked complete (allows further tab)");

        // Files have complete=true
        Candidate file = candidates.stream()
                .filter(c -> c.displ().equals("myfile.txt"))
                .findFirst().orElse(null);
        assertNotNull(file);
        assertTrue(file.complete(), "file should be marked complete");
    }

    @Test
    void fileCompletionShowsFileSize(@TempDir Path tmpDir) throws IOException {
        Path f = Files.createFile(tmpDir.resolve("test.txt"));
        Files.writeString(f, "hello world");

        List<Candidate> candidates = complete("/file " + tmpDir + File.separator + "test");
        Candidate c = candidates.stream()
                .filter(x -> x.displ().equals("test.txt"))
                .findFirst().orElse(null);
        assertNotNull(c);
        assertNotNull(c.descr(), "file candidates should show size");
        assertTrue(c.descr().contains("B"), "size should contain B for bytes: " + c.descr());
    }

    @Test
    void fileCompletionInvalidPathProducesNothing() {
        List<Candidate> candidates = complete("/image /nonexistent/path/xyz/");
        assertTrue(candidates.isEmpty());
    }

    @Test
    void nonFileCommandDoesNotTriggerFilePaths() {
        // "/help " should not try to complete file paths
        List<Candidate> candidates = complete("/help ");
        assertTrue(candidates.isEmpty());
    }

    // ========================================================================
    // Backward compatibility
    // ========================================================================

    @Test
    void singleArgConstructorWorks() {
        ChatCompleter simple = new ChatCompleter(() -> toolList);
        List<Candidate> candidates = new ArrayList<>();
        simple.complete(null, stubParsedLine("/tool "), candidates);
        assertFalse(candidates.isEmpty(), "single-arg constructor should still complete tools");
    }

    @Test
    void twoArgConstructorWorks() {
        ChatCompleter twoArg = new ChatCompleter(() -> toolList, () -> skillNames);
        List<Candidate> candidates = new ArrayList<>();
        twoArg.complete(null, stubParsedLine("/co"), candidates);
        Set<String> values = candidateValues(candidates);
        assertTrue(values.contains("/commit"), "two-arg constructor should complete skills");
        assertTrue(values.contains("/compact"), "two-arg constructor should complete commands");
    }

    // ========================================================================
    // Edge cases
    // ========================================================================

    @Test
    void caseInsensitiveCommandMatching() {
        // Prefix matching is case-insensitive
        List<Candidate> candidates = complete("/HE");
        Set<String> values = candidateValues(candidates);
        assertTrue(values.contains("/help"), "/help should match /HE (case-insensitive)");
    }

    @Test
    void commandWithTrailingSpaceAndNoSubArgs() {
        // "/stats " has no sub-args — should produce empty list
        List<Candidate> candidates = complete("/stats ");
        assertTrue(candidates.isEmpty());
    }

    @Test
    void nullSkillsSupplierReturnDoesNotCrash() {
        ChatCompleter nullSkills = new ChatCompleter(() -> toolList, () -> null);
        List<Candidate> candidates = new ArrayList<>();
        nullSkills.complete(null, stubParsedLine("/"), candidates);
        // Should still have built-in commands
        assertFalse(candidates.isEmpty());
    }

    @Test
    void nullSkillsAndToolsDoesNotCrash() {
        ChatCompleter minimal = new ChatCompleter(() -> toolList);
        List<Candidate> candidates = new ArrayList<>();
        minimal.complete(null, stubParsedLine("/"), candidates);
        // Should still have built-in commands
        assertFalse(candidates.isEmpty());
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private List<Candidate> complete(String input) {
        List<Candidate> candidates = new ArrayList<>();
        completer.complete(null, stubParsedLine(input), candidates);
        return candidates;
    }

    private static Set<String> candidateValues(List<Candidate> candidates) {
        return candidates.stream().map(Candidate::value).collect(Collectors.toSet());
    }

    /**
     * Minimal ParsedLine stub — only line() and cursor() are used by ChatCompleter.
     */
    private static ParsedLine stubParsedLine(String line) {
        return new ParsedLine() {
            @Override public String word() { return ""; }
            @Override public int wordCursor() { return 0; }
            @Override public int wordIndex() { return 0; }
            @Override public List<String> words() { return List.of(); }
            @Override public String line() { return line; }
            @Override public int cursor() { return line.length(); }
        };
    }
}
