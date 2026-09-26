package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatCompleter;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.context.ConversationLedger;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Restarting Kompile Chat on the Claude subscription route continues the Claude
 * Code session that already holds the conversation: the first turn after the
 * restart uses {@code --resume} and restores no history. Drives the real ledger,
 * DirectLlmClient and AgenticChatLoop in two successive "processes" against a
 * fake {@code claude} that logs its argv and copies each turn file.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class AgenticChatLoopClaudeNativeResumeTest {
    private static final String SESSION = "claude-native-resume-test";

    @TempDir Path directory;

    @Test
    void aRestartedChatResumesTheClaudeCodeSessionInsteadOfRestoringHistory() throws Exception {
        String home = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectories(directory.resolve("home")).toString());
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        ChatCompleter.setContentOutput(lines::add);
        try {
            assertEquals("done", chatInNewProcess("first question").strip(), String.valueOf(lines));
            assertEquals("done", chatInNewProcess("second question").strip(), String.valueOf(lines));

            List<String> argv = Files.readAllLines(log().resolve("argv.log"), StandardCharsets.UTF_8);
            assertEquals(2, argv.size(), String.valueOf(argv));
            Matcher created = Pattern.compile("--session-id (\\S+)").matcher(argv.get(0));
            assertTrue(created.find(), argv.get(0));
            String nativeSession = created.group(1);
            assertTrue(argv.get(1).contains("--resume " + nativeSession),
                    "the restarted chat must continue the saved session\n" + argv);

            String resumed = prompt(2);
            assertTrue(prompt(1).contains("first question"), prompt(1));
            assertTrue(resumed.contains("second question"), resumed);
            assertFalse(resumed.contains("[Earlier conversation restored by Kompile"),
                    "the resumed session already holds the conversation\n" + resumed);
            assertFalse(resumed.contains("first question"), resumed);
            assertFalse(resumed.contains("[Updated Kompile Chat system instructions"),
                    "unchanged instructions are not delivered again\n" + resumed);

            ConversationLedger ledger = new ConversationLedger(JsonUtils.standardMapper());
            ledger.configureSession(SESSION);
            ConversationLedger.NativeSession saved = ledger.resumableNativeSession("claude-cli");
            assertNotNull(saved, "the next restart can resume the same session again");
            assertEquals(nativeSession, saved.sessionId());
        } finally {
            ChatCompleter.setContentOutput(null);
            ChatCompleter.setTranscriptBlockOutput(null);
            ChatCompleter.setActivity(null);
            if (home == null) System.clearProperty("user.home");
            else System.setProperty("user.home", home);
        }
    }

    /** One Kompile process: a new client and loop bound to the same chat session. */
    private String chatInNewProcess(String message) throws Exception {
        var mapper = JsonUtils.standardMapper();
        ChatConfig config = new ChatConfig("anthropic", null, "claude-opus-5-5", null);
        config.setAuthenticationMethod("oauth");
        config.setDefaultMemory(false);
        config.setContextWindowTokens(200_000);
        config.setMaxOutputTokens(4_096);
        Path project = Files.createDirectories(directory.resolve("project"));
        try (DirectLlmClient client = new DirectLlmClient(config, mapper, project)) {
            AgenticChatLoop loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                    new PermissionService(), new AgentRegistry(), project, client, null);
            loop.configureConversationSession(SESSION);
            // Restoring a durable conversation resets the transport, so install after it.
            installFakeClaude(client, project);
            return loop.chat(message, SESSION, "coder", "default", false);
        }
    }

    private Path log() {
        return directory.resolve("fake-claude-log");
    }

    private String prompt(int turn) throws Exception {
        return Files.readString(log().resolve("prompt-" + turn + ".txt"), StandardCharsets.UTF_8);
    }

    private void installFakeClaude(DirectLlmClient client, Path project) throws Exception {
        Path log = Files.createDirectories(log());
        Path fake = directory.resolve("fake-claude");
        Files.writeString(fake, "#!/usr/bin/env bash\n"
                + "cat > /dev/null 2>&1 || true\n"
                + "printf '%s\\n' \"$*\" >> '" + log + "/argv.log'\n"
                + "PROMPT_PATH=$(printf '%s\\n' \"$*\" | grep -oE '/[^ ]+\\.txt' | head -1)\n"
                + "N=$(( $(ls '" + log + "' | grep -c '^prompt-') + 1 ))\n"
                + "cat \"$PROMPT_PATH\" > '" + log + "/prompt-'$N'.txt'\n"
                + "SID=; PREV=\n"
                + "for ARG in \"$@\"; do\n"
                + "  case \"$PREV\" in --session-id|--resume) SID=$ARG;; esac\n"
                + "  PREV=$ARG\n"
                + "done\n"
                + "printf '{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"%s\"}\\n' \"$SID\"\n"
                + "printf '%s\\n' '{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"done\"}}}'\n"
                + "printf '%s\\n' '{\"type\":\"result\",\"subtype\":\"success\",\"num_turns\":1,"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}'\n"
                + "exit 0\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(fake, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        Class<?> transport = Class.forName("ai.kompile.cli.main.chat.config.ClaudeCliClient");
        Constructor<?> constructor = transport.getDeclaredConstructor(
                Path.class, String.class, String.class);
        constructor.setAccessible(true);
        Object claude = constructor.newInstance(project, null, fake.toString());
        Field field = DirectLlmClient.class.getDeclaredField("claudeServeClient");
        field.setAccessible(true);
        field.set(client, claude);
    }
}
