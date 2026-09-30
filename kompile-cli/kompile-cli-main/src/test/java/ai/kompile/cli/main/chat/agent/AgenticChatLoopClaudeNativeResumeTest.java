package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatCompleter;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.FakeClaudeCode;
import ai.kompile.cli.main.chat.context.ConversationLedger;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Restarting Kompile Chat on the Claude subscription route continues the Claude
 * Code session that already holds the conversation: the first turn after the
 * restart uses {@code --resume} and restores no history. Drives the real ledger,
 * DirectLlmClient and AgenticChatLoop in two successive "processes" against a
 * fake {@code claude} that logs its argv and each message it takes in.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class AgenticChatLoopClaudeNativeResumeTest {
    private static final String SESSION = "claude-native-resume-test";

    @TempDir Path directory;
    private FakeClaudeCode fake;

    @Test
    void aRestartedChatResumesTheClaudeCodeSessionInsteadOfRestoringHistory() throws Exception {
        String home = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectories(directory.resolve("home")).toString());
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        ChatCompleter.setContentOutput(lines::add);
        try {
            assertEquals("done", chatInNewProcess("first question").strip(), String.valueOf(lines));
            assertEquals("done", chatInNewProcess("second question").strip(), String.valueOf(lines));

            List<String> argv = fake.argv();
            assertEquals(2, argv.size(), String.valueOf(argv));
            assertEquals(2, fake.messages().size(), String.valueOf(fake.messages()));
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

    /** The text of the message the given turn sent to Claude Code, counting from 1. */
    private String prompt(int turn) throws Exception {
        return fake.messages().get(turn - 1);
    }

    /** Both processes start the same fake, so its logs cover the whole chat. */
    private void installFakeClaude(DirectLlmClient client, Path project) throws Exception {
        if (fake == null) fake = new FakeClaudeCode(directory.resolve("claude"), "ANSWER=done");
        fake.install(client, project, null);
    }
}
