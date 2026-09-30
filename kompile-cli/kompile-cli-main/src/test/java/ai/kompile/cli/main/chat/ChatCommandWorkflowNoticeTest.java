package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.exec.HeadlessAgentRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The team a chat starts with is shown inline in the terminal; a headless run keeps
 * stdout for its answer or JSONL stream, so the team goes to stderr, and nowhere
 * under --quiet.
 */
class ChatCommandWorkflowNoticeTest {

    private static final String NOTICE = "Workflow team restored for this session: review-team";

    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private PrintStream previousOut;
    private PrintStream previousErr;

    @BeforeEach
    void capture() {
        previousOut = System.out;
        previousErr = System.err;
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restore() {
        System.setOut(previousOut);
        System.setErr(previousErr);
    }

    @Test
    void theTerminalShowsTheTeamInline() {
        ChatCommand.printWorkflowNotice(false, null, NOTICE);

        assertEquals(NOTICE, stdout.toString(StandardCharsets.UTF_8).strip());
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aHeadlessRunKeepsStdoutForItsOutput() {
        ChatCommand.printWorkflowNotice(true, HeadlessAgentRunner.OutputMode.JSON, NOTICE);
        ChatCommand.printWorkflowNotice(true, HeadlessAgentRunner.OutputMode.TEXT, NOTICE);

        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertEquals(NOTICE + "\n" + NOTICE, stderr.toString(StandardCharsets.UTF_8).strip().replace("\r\n", "\n"));
    }

    @Test
    void quietPrintsNothing() {
        ChatCommand.printWorkflowNotice(true, HeadlessAgentRunner.OutputMode.QUIET, NOTICE);

        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }
}
