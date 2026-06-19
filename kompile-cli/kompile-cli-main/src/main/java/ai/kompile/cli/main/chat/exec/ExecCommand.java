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

package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.main.chat.ChatHistory;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * {@code kompile exec} — run kompile's own agent non-interactively and print its
 * response to stdout, the way {@code codex exec} and {@code opencode run} do.
 *
 * <p>The prompt is taken from the positional arguments, or from stdin when no
 * arguments are given (or the single argument is {@code "-"}):
 * <pre>
 *   kompile exec "summarize README.md"
 *   echo "list the open TODOs" | kompile exec
 *   kompile exec -c "now fix the first one"        # continue the last session
 *   kompile exec --json "count the java files" | jq .
 * </pre>
 *
 * <p>By default stdout carries only the agent's text and stderr carries
 * tool/progress activity (pipe-friendly). {@code --quiet} prints just the final
 * text; {@code --json} emits a JSONL event stream. Tools run auto-approved since
 * there is no interactive prompt.
 */
@CommandLine.Command(
        name = "exec",
        description = "Run kompile's agent non-interactively and print its response "
                + "(like `codex exec` / `opencode run`). Prompt comes from arguments or stdin.",
        mixinStandardHelpOptions = true)
public class ExecCommand implements Callable<Integer> {

    @CommandLine.Parameters(arity = "0..*", paramLabel = "PROMPT",
            description = "Prompt text. If omitted (or '-'), the prompt is read from stdin.")
    private List<String> promptParts = new ArrayList<>();

    @CommandLine.Option(names = {"--session-id"},
            description = "Session ID for transcript persistence (generated if not provided).")
    private String sessionId;

    @CommandLine.Option(names = {"-c", "--continue"}, defaultValue = "false",
            description = "Continue the most recent session (restores its history into context).")
    private boolean continueLast;

    @CommandLine.Option(names = {"-r", "--resume"}, paramLabel = "SESSION_ID",
            description = "Resume a specific session by ID.")
    private String resumeSessionId;

    @CommandLine.Option(names = {"--agent"}, defaultValue = "coder",
            description = "Local agent to run (default: ${DEFAULT-VALUE}).")
    private String agentName;

    @CommandLine.Option(names = {"--model"},
            description = "Override the configured model for this run.")
    private String model;

    @CommandLine.Option(names = {"-q", "--quiet"}, defaultValue = "false",
            description = "Print only the final response text; suppress tool/progress output.")
    private boolean quiet;

    @CommandLine.Option(names = {"--json"}, defaultValue = "false",
            description = "Emit a JSONL event stream (session, text, tool, result) to stdout.")
    private boolean json;

    @CommandLine.Option(names = {"--output-last-message"}, paramLabel = "FILE",
            description = "Also write the final response text to FILE.")
    private String outputLastMessage;

    @CommandLine.Option(names = {"-C", "--cwd"}, paramLabel = "DIR",
            description = "Working directory for the agent (default: current directory).")
    private String cwd;

    @CommandLine.Option(names = {"--timeout"}, defaultValue = "0", paramLabel = "SECONDS",
            description = "Max seconds to run before cancelling (0 = no limit).")
    private long timeoutSeconds;

    @Override
    public Integer call() {
        if (quiet && json) {
            System.err.println("--quiet and --json cannot be combined.");
            return 2;
        }

        String prompt;
        try {
            prompt = PromptResolver.resolve(promptParts, System.in);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        } catch (IOException e) {
            System.err.println("Failed to read prompt from stdin: " + e.getMessage());
            return 2;
        }

        // Resolve session id + whether to restore prior history.
        boolean resume = false;
        String resolvedSession = sessionId;
        if (resumeSessionId != null && !resumeSessionId.isBlank()) {
            resolvedSession = resumeSessionId;
            resume = true;
        } else if (continueLast) {
            List<ChatHistory.ConversationSummary> convos = ChatHistory.listConversations();
            if (convos.isEmpty()) {
                System.err.println("No previous session to continue.");
                return 1;
            }
            resolvedSession = convos.get(0).sessionId();
            resume = true;
        }
        if (resolvedSession == null || resolvedSession.isBlank()) {
            resolvedSession = "exec-" + UUID.randomUUID().toString().substring(0, 8);
        }

        Path workDir = (cwd != null && !cwd.isBlank())
                ? Path.of(cwd).toAbsolutePath().normalize()
                : Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

        HeadlessAgentRunner.OutputMode mode = json ? HeadlessAgentRunner.OutputMode.JSON
                : quiet ? HeadlessAgentRunner.OutputMode.QUIET
                : HeadlessAgentRunner.OutputMode.TEXT;

        HeadlessAgentRunner.Options opts = new HeadlessAgentRunner.Options(
                prompt,
                resolvedSession,
                resume,
                agentName,
                blankToNull(model),
                mode,
                workDir,
                Math.max(0, timeoutSeconds) * 1000L,
                blankToNull(outputLastMessage) != null ? Path.of(outputLastMessage) : null);

        return new HeadlessAgentRunner().run(opts).exitCode();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
