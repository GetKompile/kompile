package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.config.SubagentConfig;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SubagentConfigCommandsTest {
    @TempDir Path dir;

    @Test void defaultsRemainUnlimitedAndReadOnlyCommandsDoNotCreateFiles() throws Exception {
        Path path = dir.resolve("config.json");
        assertEquals(SubagentConfig.defaults(), SubagentConfig.load(path));
        assertTrue(SubagentConfigCommands.command(path, "status").contains("unlimited"));
        assertTrue(SubagentConfigCommands.command(path, "help").contains("ban <glob>"));
        assertFalse(Files.exists(path));
    }

    @Test void commandsRoundTripAndInvalidInputDoesNotChangeSavedPolicy() throws Exception {
        Path path = dir.resolve("nested/config.json");
        SubagentConfigCommands.command(path, "max 3");
        SubagentConfigCommands.command(path, "ban *haiku*");
        SubagentConfigCommands.command(path, "ban anthropic/*");
        SubagentConfigCommands.command(path, "ban *haiku*");
        var saved = new SubagentConfig(3, List.of("*haiku*", "anthropic/*"));
        assertEquals(saved, SubagentConfig.load(path));
        for (String input : List.of("max -2", "max -1", "max 1.5", "max", "ban", "unban missing", "unknown", "clear-bans extra")) {
            assertThrows(IllegalArgumentException.class, () -> SubagentConfigCommands.command(path, input), input);
            assertEquals(saved, SubagentConfig.load(path));
        }
        SubagentConfigCommands.command(path, "unban *haiku*");
        assertEquals(List.of("anthropic/*"), SubagentConfig.load(path).bannedModels());
        SubagentConfigCommands.command(path, "clear-bans");
        SubagentConfigCommands.command(path, "max 0");
        assertEquals(new SubagentConfig(0, List.of()), SubagentConfig.load(path));
        SubagentConfigCommands.command(path, "max unlimited");
        assertEquals(SubagentConfig.defaults(), SubagentConfig.load(path));
    }

    @Test void matchingUsesCaseInsensitiveFullGlobsWithLiteralRegexCharacters() {
        var config = new SubagentConfig(-1, List.of("*haiku*", "openai/gpt-?", "literal[1].+"));
        assertEquals("*haiku*", config.matchingBan("anthropic", "CLAUDE-HAIKU-4.5"));
        assertEquals("openai/gpt-?", config.matchingBan("OPENAI", "gpt-5"));
        assertNull(config.matchingBan("other", "gpt-5"));
        assertNull(config.matchingBan("openai", "gpt-50"));
        assertEquals("literal[1].+", config.matchingBan("custom", "literal[1].+"));
        assertNull(config.matchingBan("custom", "literal1x"));
        assertNull(new SubagentConfig(-1, List.of("haiku")).matchingBan("anthropic", "claude-haiku"));
    }

    @Test void malformedPolicyFailsClosedRatherThanFallingBackToUnlimited() throws Exception {
        Path path = dir.resolve("policy.json");
        for (String json : List.of("{", "null", "[]", "{\"maxConcurrent\":-2}", "{\"maxConcurrent\":2.5}",
                "{\"maxConcurrent\":2147483648}", "{\"bannedModels\":null}", "{\"bannedModels\":[1]}", "{\"bannedModels\":[\" \"]}")) {
            Files.writeString(path, json);
            assertThrows(IOException.class, () -> SubagentConfig.load(path), json);
        }
    }

    @Test void wizardSavesOnlyOnExplicitSaveAndCancelAndInterruptLeaveFileUnchanged() throws Exception {
        Path path = dir.resolve("policy.json");
        var closed = new AtomicBoolean();
        SubagentConfigCommands.wizard(reader("1", "2", "2", "*haiku*", "5"), path,
                (title, lines) -> assertEquals("Subagent settings", title), () -> closed.set(true));
        assertTrue(closed.get());
        var saved = new SubagentConfig(2, List.of("*haiku*"));
        assertEquals(saved, SubagentConfig.load(path));
        SubagentConfigCommands.wizard(reader("1", "0", "0"), path, (t, l) -> {}, () -> {});
        assertEquals(saved, SubagentConfig.load(path));
        SubagentConfigCommands.wizard(reader("1", "3"), path, (t, l) -> {}, () -> {});
        assertEquals(saved, SubagentConfig.load(path));
    }

    @Test void failoverRoundTripsByAllowlistAndOtherCommandsRetainIt() throws Exception {
        Path path = dir.resolve("failover.json");
        var rung = new SessionModelLadder.ModelRung("openai", "fixture", "named-account", "Backup", "high", "api-key");
        var config = new SubagentConfig(3, List.of("*blocked*"), false, List.of(rung));
        config.save(path);
        assertEquals(config, SubagentConfig.load(path));
        String json = Files.readString(path);
        assertTrue(json.contains("\"credentialName\""));
        assertTrue(json.contains("\"thinking\""));
        assertTrue(json.contains("\"authenticationMethod\""));
        assertFalse(json.contains("apiKey"));
        assertFalse(json.contains("accessToken"));
        for (String command : List.of("max 2", "ban another", "unban another", "clear-bans")) {
            SubagentConfigCommands.command(path, command);
            assertFalse(SubagentConfig.load(path).failoverEnabled());
            assertEquals(List.of(rung), SubagentConfig.load(path).failoverRungs());
        }
        String status = SubagentConfigCommands.command(path, "status");
        assertTrue(status.contains("credential: named-account"));
        assertTrue(status.contains("thinking: high"));
        assertTrue(status.contains("auth: api-key"));
        // Unknown secret-like fields must never be retained or serialized back out.
        Files.writeString(path, "{\"failoverRungs\":[{\"provider\":\"openai\",\"model\":\"fixture\",\"apiKey\":\"not-a-reference\"}]}");
        SubagentConfig.load(path).save(path);
        assertFalse(Files.readString(path).contains("not-a-reference"));
    }

    @Test void malformedFailoverConfigFailsClosedAndLegacyConfigKeepsDefaults() throws Exception {
        Path path = dir.resolve("failover.json");
        Files.writeString(path, "{\"maxConcurrent\":2,\"bannedModels\":[]}");
        assertTrue(SubagentConfig.load(path).failoverEnabled());
        assertTrue(SubagentConfig.load(path).failoverRungs().isEmpty());
        for (String json : List.of("{\"failoverEnabled\":null}", "{\"failoverEnabled\":\"yes\"}",
                "{\"failoverRungs\":null}", "{\"failoverRungs\":[null]}", "{\"failoverRungs\":[{}]}",
                "{\"failoverRungs\":[{\"model\":\"fixture\",\"thinking\":7}]}")) {
            Files.writeString(path, json);
            assertThrows(IOException.class, () -> SubagentConfig.load(path), json);
        }
    }

    @Test void nestedFailoverEditorRequiresOuterSaveAndStandaloneCancelDoesNotWrite() throws Exception {
        Path path = dir.resolve("policy.json");
        SubagentConfig original = new SubagentConfig(2, List.of("*haiku*"));
        original.save(path);
        byte[] before = Files.readAllBytes(path);
        // Accept the nested editor, then cancel the enclosing settings wizard.
        SubagentConfigCommands.wizard(reader("6", "4", "5", "0"), path, (t, l) -> {}, () -> {});
        assertArrayEquals(before, Files.readAllBytes(path));
        SubagentConfigCommands.wizard(reader("6", "4", "5", "5"), path, (t, l) -> {}, () -> {});
        assertFalse(SubagentConfig.load(path).failoverEnabled());
        assertEquals(original.bannedModels(), SubagentConfig.load(path).bannedModels());
        before = Files.readAllBytes(path);
        SubagentConfigCommands.failoverWizard(reader("4", "0"), path, null, (t, l) -> {}, () -> {});
        assertArrayEquals(before, Files.readAllBytes(path));
        SubagentConfigCommands.failoverWizard(reader("4", "5"), path, null, (t, l) -> {}, () -> {});
        assertTrue(SubagentConfig.load(path).failoverEnabled());
    }

    @Test void modelFailoverAliasesOnlyMatchWholeSubcommands() {
        assertEquals("", ChatCommandRouter.modelLadderArguments("ladder"));
        assertEquals("", ChatCommandRouter.modelLadderArguments("failover"));
        assertEquals("status", ChatCommandRouter.modelLadderArguments("failover status"));
        assertEquals("status", ChatCommandRouter.modelLadderArguments("ladder\tstatus"));
        assertEquals("add openai/fixture --thinking high", ChatCommandRouter.modelLadderArguments("ladder add openai/fixture --thinking high"));
        assertNull(ChatCommandRouter.modelLadderArguments("ladder-model-id"));
    }

    @Test void catalogAndCompletionExposeNewCommandWithoutChangingSubagentListCommand() throws Exception {
        assertTrue(ChatCommandCatalog.isBuiltin("subagent-config"));
        assertTrue(ChatCommandCatalog.isBuiltin("subagents"));
        assertEquals(ChatCommandCatalog.WebSupport.TERMINAL_REQUIRED, ChatCommandCatalog.webSupport("subagent-config"));
        var completer = new ChatCompleter(List::of);
        var parser = new org.jline.reader.impl.DefaultParser();
        var candidates = new java.util.ArrayList<org.jline.reader.Candidate>();
        completer.complete(null, parser.parse("/subagent-c", 11), candidates);
        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("/subagent-config")));
        candidates.clear();
        String input = "/subagent-config ";
        completer.complete(null, parser.parse(input, input.length()), candidates);
        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("max")));
        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("failover")));
        candidates.clear();
        input = "/model ";
        completer.complete(null, parser.parse(input, input.length()), candidates);
        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("ladder")));
        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("failover")));
    }

    private static LineReader reader(String... lines) {
        var input = new ArrayDeque<>(List.of(lines));
        return (LineReader) Proxy.newProxyInstance(LineReader.class.getClassLoader(), new Class<?>[]{LineReader.class}, (p, method, args) -> {
            if (method.getName().equals("readLine")) {
                if (input.isEmpty()) throw new UserInterruptException("");
                return input.remove();
            }
            throw new UnsupportedOperationException(method.getName());
        });
    }
}
