package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.FakeClaudeCode;
import ai.kompile.cli.main.chat.harness.HarnessConfig;
import ai.kompile.cli.main.chat.harness.JudgeBackend;
import ai.kompile.cli.main.chat.harness.JudgeBackendFactory;
import ai.kompile.cli.main.chat.harness.JudgeDimensions;
import ai.kompile.cli.main.chat.harness.JudgeLlmEvaluator;
import ai.kompile.cli.main.chat.harness.TurnMetrics;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Claude Code judge, end to end against a stand-in for Claude Code. Each verdict starts
 * a Claude Code process with no tools, no MCP servers and no skills that is not saved and
 * asks for the judge's low effort once. The process start counts against the process
 * deadline, not the shorter one for requests that start no process.
 */
@TemporaryUserHome
class AuxiliaryChatReplClaudeJudgeTest {

    private static final String REASONING = "The answer matches the request.";

    /**
     * Each process records its arguments one per bracket pair (an empty one shows as
     * {@code []}) and its pid, holds 1.5 s before it reads its input, answers initialize
     * with its model's efforts and answers a message with the verdict in
     * {@code verdict.jsonl}.
     */
    private static final String SLOW_START = """
            MODEL=
            for ((i = 0; i < ${#args[@]}; i++)); do
              if [ "${args[i]}" = --model ]; then MODEL="${args[i+1]}"; fi
            done
            startup() {
              { printf '[%s]' "${args[@]}"; printf '\\n'; } >> "$DIR/args"
              printf '%s\\n' "$$" >> "$DIR/pids"
              [ -p "$DIR/hold" ] || mkfifo "$DIR/hold"
              read -t 1.5 -r _ <> "$DIR/hold"
            }
            control() {
              if [ "$2" = initialize ]; then
                emit '{"type":"control_response","response":{"subtype":"success","request_id":"'"$1"'","response":{"models":[{"value":"'"${MODEL:-default}"'","supportedEffortLevels":["low","medium","high"]}]}}}'
              else
                emit '{"type":"control_response","response":{"subtype":"success","request_id":"'"$1"'","response":{}}}'
              fi
            }
            turn() {
              say_init
              cat "$DIR/verdict.jsonl"
              say_result
            }
            """;

    /** The deadline of a judge request that starts no process. */
    private static final int REQUEST_DEADLINE_MS = 1_000;

    @TempDir
    Path project;

    @Test
    void aVerdictRunsWithoutToolsOrMcpAtLowEffortWithinTheProcessDeadline() throws Exception {
        FakeClaudeCode fake = fake();
        try (Judge judge = judge(fake, 10_000)) {
            assertTrue(judge.verdicts().startsProviderProcess(), "a Claude Code verdict starts a Claude Code process");

            // The process holds 1.5 s before it reads the request: past the request deadline,
            // within the process deadline.
            JudgeDimensions verdict = judge.evaluate();

            String transcript = judge.repl().transcript();
            assertFalse(verdict.isError(), verdict.getErrorDetail() + "\n" + transcript);
            assertEquals(5.0f, verdict.getCorrectness(), 0.001f, verdict.toString());
            assertFalse(transcript.contains("[error]"), transcript);
            assertTrue(transcript.contains(REASONING), transcript);

            List<String> launches = Files.readAllLines(fake.path("args"), StandardCharsets.UTF_8);
            assertEquals(1, launches.size(), "one process for one verdict: " + launches);
            String launch = launches.get(0);
            assertTrue(launch.contains("[--tools][][--strict-mcp-config]"), launch);
            assertTrue(launch.contains("[--disable-slash-commands]"), launch);
            assertTrue(launch.contains("[--no-session-persistence]"), launch);
            assertFalse(launch.contains("[--mcp-config"), launch);
            // Low effort rides the command line when the judge selection names it, else a
            // settings change once Claude Code lists low for the model: one or the other.
            boolean effortOnCommandLine = launch.contains("[--effort][low]");
            List<String> effortChanges = fake.controls("apply_flag_settings").stream()
                    .map(request -> request.path("settings").path("effortLevel").asText())
                    .toList();
            assertTrue(effortOnCommandLine != effortChanges.equals(List.of("low")),
                    "low effort exactly once; command line: " + launch + ", settings changes: " + effortChanges);
            assertFalse(Files.exists(project.resolve(".mcp.json")), "a judge writes no MCP server config");
            assertFalse(Files.exists(project.resolve(".claude").resolve("settings.local.json")),
                    "a judge writes no Claude Code settings");
        }
    }

    @Test
    void theRequestDeadlineAloneCancelsTheSameVerdict() throws Exception {
        // Without a process deadline the process start counts against the request deadline,
        // and the verdict is cancelled before Claude Code reads it.
        FakeClaudeCode fake = fake();
        try (Judge judge = judge(fake, 0)) {
            JudgeDimensions verdict = judge.evaluate();

            assertTrue(verdict.isError(), verdict.toString());
            assertTrue(verdict.getErrorDetail().contains("exceeded " + REQUEST_DEADLINE_MS + "ms"),
                    verdict.getErrorDetail());
        }
        // The cancelled verdict's process is stopped, not left running: it ends before this
        // test does, so it writes nothing into the project once the test is over.
        List<String> pids = Files.readAllLines(fake.path("pids"), StandardCharsets.UTF_8);
        assertEquals(1, pids.size(), "one process for one verdict: " + pids);
        Optional<ProcessHandle> process = ProcessHandle.of(Long.parseLong(pids.get(0).strip()));
        FakeClaudeCode.await("the cancelled verdict's Claude Code process to stop",
                () -> !process.map(ProcessHandle::isAlive).orElse(false));
    }

    private FakeClaudeCode fake() throws Exception {
        FakeClaudeCode fake = new FakeClaudeCode(project.resolve("claude"), SLOW_START);
        writeVerdict(JsonUtils.standardMapper(), fake.path("verdict.jsonl"));
        return fake;
    }

    /** A Claude Code judge lane as a chat builds it, with the given process deadline. */
    private Judge judge(FakeClaudeCode fake, int processDeadlineMs) {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ChatConfig chat = new ChatConfig("anthropic", null, "claude-opus-5-5", null);
        chat.setAuthenticationMethod("oauth");
        DirectLlmClient client = JudgeBackendFactory.createDirectJudgeClient(
                chat, null, null, null, null, "low", mapper, project);
        assertNotNull(client);
        fake.installBinary(client);
        AuxiliaryChatRepl repl = AuxiliaryChatRepl.modelBacked(AuxiliaryChatRepl.Kind.JUDGE, client, null);
        JudgeBackend verdicts = repl.verdictBackend();
        HarnessConfig config = new HarnessConfig();
        config.setJudgeDeadlineMs(REQUEST_DEADLINE_MS);
        config.setJudgeProcessDeadlineMs(processDeadlineMs);
        config.setJudgeSwapCandidates(List.of());
        return new Judge(repl, verdicts, JudgeBackendFactory.withResilience(verdicts, config, mapper));
    }

    private record Judge(AuxiliaryChatRepl repl, JudgeBackend verdicts, JudgeBackend backend)
            implements AutoCloseable {

        JudgeDimensions evaluate() {
            return new JudgeLlmEvaluator(backend, JsonUtils.standardMapper()).evaluate(TurnMetrics.builder()
                    .taskPrompt("Say whether two plus two is four")
                    .agentOutput("Yes, two plus two is four.")
                    .agenticSteps(1)
                    .build(), "general", "coder");
        }

        @Override
        public void close() {
            backend.close();
            repl.close();
        }
    }

    /** One stream-json text delta whose text is the verdict. */
    private static void writeVerdict(ObjectMapper mapper, Path file) throws Exception {
        ObjectNode verdict = mapper.createObjectNode();
        verdict.put("correctness", 5);
        verdict.put("completeness", 5);
        verdict.putNull("design_quality");
        verdict.putNull("thinking_coherence");
        verdict.put("reasoning", REASONING);
        ObjectNode line = mapper.createObjectNode();
        line.put("type", "stream_event");
        ObjectNode event = line.putObject("event");
        event.put("type", "content_block_delta");
        event.put("index", 0);
        ObjectNode delta = event.putObject("delta");
        delta.put("type", "text_delta");
        delta.put("text", mapper.writeValueAsString(verdict));
        Files.writeString(file, mapper.writeValueAsString(line) + "\n", StandardCharsets.UTF_8);
    }
}
