package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.*;

/** Correlates native provider command events by call id. MCP execution tools capture themselves. */
public final class ProviderTestCapture implements AutoCloseable {
    private final Path directory;
    private final String identity = UUID.randomUUID().toString();
    private final Map<String, TestMilestoneCapture> pending = new HashMap<>();
    private final Set<String> completed = new LinkedHashSet<>();

    public ProviderTestCapture(Path directory) { this.directory = directory; }

    public synchronized void start(String callId, String name, String input) {
        if (callId == null || callId.isBlank() || completed.contains(callId) || pending.containsKey(callId) || pending.size() >= 128) return;
        String tool = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (!Set.of("bash", "shell", "exec", "exec_command", "run_shell_command").contains(tool)) return;
        try {
            String command = input;
            Path cwd = directory;
            if (input.stripLeading().startsWith("{")) {
                JsonNode args = JsonUtils.standardMapper().readTree(input);
                command = args.path("command").asText(args.path("cmd").asText(""));
                String workdir = args.path("workdir").asText(args.path("cwd").asText(""));
                if (!workdir.isBlank()) cwd = directory.resolve(workdir).normalize();
            }
            TestMilestoneCapture capture = TestMilestoneCapture.start(command, cwd, null,
                    "provider:" + identity + ":" + callId, null);
            if (capture != null) pending.put(callId, capture);
        } catch (RuntimeException | java.io.IOException invalid) {
            System.err.println("Warning: cannot observe provider test command: " + invalid.getMessage());
        }
    }

    /** Full command output, not the provider's final prose and not repeated cumulative deltas. */
    public synchronized void complete(String callId, String output, Integer exitCode, boolean interrupted) {
        TestMilestoneCapture capture = pending.remove(callId);
        if (capture == null) return;
        completed.add(callId);
        if (completed.size() > 4096) completed.remove(completed.iterator().next());
        capture.accept(output);
        capture.finish(exitCode, false, interrupted, null);
    }

    @Override public synchronized void close() {
        pending.values().forEach(TestMilestoneCapture::close);
        pending.clear();
    }
}
