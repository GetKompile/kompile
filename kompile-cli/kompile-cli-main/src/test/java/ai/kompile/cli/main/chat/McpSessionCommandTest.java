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

package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.config.ClaudeMcpServer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /mcp} against a stand-in for the session's Claude Code process: the
 * server table, and when {@code /mcp reconnect} restarts servers, refuses, or
 * takes {@code --force}.
 */
class McpSessionCommandTest {

    private static final ClaudeMcpServer KOMPILE = new ClaudeMcpServer("kompile", "connected", "kompile 1.0.0",
            "stdio", List.of("java", "-jar", "/opt/kompile/lib/kompile-cli.jar", "mcp-stdio", "--work-dir", "/repo"),
            42, "");
    private static final ClaudeMcpServer DOCS = new ClaudeMcpServer("docs", "failed", "", "sse",
            List.of("http://127.0.0.1:8085/sse"), 0, "SSE error:\nECONNREFUSED");
    private static final ClaudeMcpServer OFF = new ClaudeMcpServer("off", "disabled", "", "stdio",
            List.of("node", "server.js"), 0, "");

    private final FakeServers servers = new FakeServers();
    private final List<SharedProcessMirror.SessionProcess> jobs = new ArrayList<>();
    private final List<Long> exitedPids = new ArrayList<>();
    private final List<String> out = new ArrayList<>();
    private IOException jobsUnreadable;
    private boolean turnActive;

    @Test
    void statusShowsEachServersStateToolsAndTransport() {
        run("");

        assertEquals(List.of(
                "  MCP servers of this session's Claude Code process",
                "  kompile  connected   kompile 1.0.0 · 42 tools · stdio · runs this session's background jobs",
                "  docs     failed      sse  SSE error: ECONNREFUSED",
                "  off      disabled    stdio",
                "  /mcp reconnect [name] restarts servers in place, e.g. onto a redeployed Kompile."), out);
    }

    @Test
    void statusAndReconnectSayWhyThereIsNothingToShow() {
        servers.running = false;
        run("status");
        run("reconnect");
        servers.running = true;
        servers.current = List.of();
        run("status");
        run("reconnect");
        servers.listFailure = new IOException("Claude Code did not answer mcp_status within 10 s");
        run("");

        String noProcess = "  No Claude Code process is running for this session; the next message starts one, "
                + "with its MCP servers.";
        assertEquals(List.of(
                noProcess,
                noProcess,
                "  Claude Code runs no MCP servers for this session.",
                "  Claude Code runs no MCP servers to reconnect.",
                "  Could not read the MCP servers from Claude Code: Claude Code did not answer mcp_status within 10 s"),
                out);
        assertEquals(List.of(), servers.reconnected);
    }

    @Test
    void reconnectRestartsTheNamedServerInPlaceAndShowsTheServersAfterward() {
        run("reconnect kompile");

        assertEquals(List.of("kompile"), servers.reconnected);
        assertEquals(List.of(McpSessionCommand.RECONNECT_TIMEOUT), servers.timeouts);
        assertEquals("  Reconnecting kompile…", out.get(0));
        assertEquals("  MCP servers of this session's Claude Code process", out.get(1));
    }

    @Test
    void reconnectWithoutANameRestartsEveryServerThatIsNotDisabled() {
        run("reconnect");

        assertEquals(List.of("kompile", "docs"), servers.reconnected);
    }

    @Test
    void noServerRestartsWhileATurnRuns() {
        turnActive = true;

        run("reconnect docs --force");

        assertEquals(List.of(), servers.reconnected);
        assertEquals(List.of("  A turn is running. Reconnect after it ends: "
                + "the turn's calls to a restarting server would fail."), out);
    }

    @Test
    void kompileDoesNotRestartWhileItRunsJobsForThisSession() {
        jobs.add(new SharedProcessMirror.SessionProcess("proc-027", 4242L, "Redeploy\nKompile"));
        jobs.add(new SharedProcessMirror.SessionProcess("proc-030", 0L, "mvn -o test"));

        run("reconnect kompile");

        assertEquals(List.of(), servers.reconnected);
        assertEquals(List.of(
                "  Not reconnecting: restarting Kompile's server stops the background jobs it runs for this session:",
                "    proc-027 (pid 4242)  Redeploy Kompile",
                "    proc-030  mvn -o test",
                "  Wait for them to end, or run /mcp reconnect kompile --force to stop them."), out);
    }

    @Test
    void reconnectingEveryServerWaitsForKompilesJobsToo() {
        jobs.add(new SharedProcessMirror.SessionProcess("proc-027", 4242L, "Redeploy"));

        run("reconnect");

        assertEquals(List.of(), servers.reconnected, "no server restarts when the batch is refused");
        assertEquals(List.of(
                "  Not reconnecting: restarting Kompile's server stops the background job it runs for this session:",
                "    proc-027 (pid 4242)  Redeploy",
                "  Wait for it to end, or run /mcp reconnect --force to stop it."), out);
    }

    @Test
    void aJobWhoseProcessExitedDoesNotHoldTheRestartUp() {
        jobs.add(new SharedProcessMirror.SessionProcess("proc-027", 4242L, "Redeploy"));
        exitedPids.add(4242L);

        run("reconnect kompile");

        assertEquals(List.of("kompile"), servers.reconnected);
    }

    @Test
    void forceRestartsKompileWhileItRunsJobs() {
        jobs.add(new SharedProcessMirror.SessionProcess("proc-027", 4242L, "Redeploy"));

        run("reconnect --force kompile");

        assertEquals(List.of("kompile"), servers.reconnected);
    }

    @Test
    void otherServersRestartWhileKompileRunsJobs() {
        jobs.add(new SharedProcessMirror.SessionProcess("proc-027", 4242L, "Redeploy"));

        run("reconnect docs");

        assertEquals(List.of("docs"), servers.reconnected);
    }

    @Test
    void jobsThatCannotBeReadRefuseTheRestartUnlessForced() {
        jobsUnreadable = new IOException("could not read coordination state: coordination snapshot unavailable");

        run("reconnect kompile");

        assertEquals(List.of(), servers.reconnected);
        assertEquals(List.of(
                "  Not reconnecting: could not check for background jobs that restarting Kompile's server would "
                        + "stop (could not read coordination state: coordination snapshot unavailable).",
                "  /mcp reconnect kompile --force reconnects anyway."), out);

        run("reconnect kompile --force");

        assertEquals(List.of("kompile"), servers.reconnected);
    }

    @Test
    void anUnknownNameListsTheServers() {
        run("reconnect nope");

        assertEquals(List.of(), servers.reconnected);
        assertEquals(List.of("  No MCP server named nope. Servers: kompile, docs, off"), out);
    }

    @Test
    void aServerThatFailsToReconnectIsReportedAndTheRestStillRestart() {
        servers.refused = "kompile";

        run("reconnect");

        assertEquals(List.of("docs"), servers.reconnected);
        assertTrue(out.contains("  Could not reconnect kompile: Server not found: kompile"), out.toString());
    }

    @Test
    void aProcessThatEndsDuringTheRestartStopsIt() {
        servers.endsOnReconnect = true;

        run("reconnect");

        assertEquals(List.of(), servers.reconnected);
        assertEquals(List.of("  Reconnecting kompile…",
                "  The Claude Code process ended; the next message starts a new one, with its MCP servers."), out);
    }

    @Test
    void anythingElsePrintsTheUsage() {
        for (String arguments : List.of("restart", "status extra", "status --force", "reconnect a b")) {
            out.clear();
            run(arguments);
            assertEquals(2, out.size(), arguments + ": " + out);
            assertTrue(out.get(0).startsWith("  Usage: /mcp [status]"), arguments + ": " + out);
        }
        assertEquals(List.of(), servers.reconnected);
    }

    @Test
    void theDefaultLivenessCheckCountsAnUnrecordedPidAsRunningAndAnExitedOneAsGone() throws Exception {
        Process exited = new ProcessBuilder("true").start();
        assertEquals(0, exited.waitFor());
        jobs.add(new SharedProcessMirror.SessionProcess("proc-gone", exited.pid(), "Ended"));
        McpSessionCommand command = new McpSessionCommand(servers, () -> jobs, () -> false,
                new TerminalRenderer(false), out::add);

        command.run("reconnect kompile");
        assertEquals(List.of("kompile"), servers.reconnected);

        long self = ProcessHandle.current().pid();
        jobs.add(new SharedProcessMirror.SessionProcess("proc-live", self, "This JVM"));
        jobs.add(new SharedProcessMirror.SessionProcess("proc-unrecorded", 0L, "No pid recorded"));
        out.clear();
        command.run("reconnect kompile");

        assertEquals(List.of("kompile"), servers.reconnected, "a running job must hold the restart up");
        assertTrue(out.contains("    proc-live (pid " + self + ")  This JVM"), out.toString());
        assertTrue(out.contains("    proc-unrecorded  No pid recorded"), out.toString());
        assertTrue(out.stream().noneMatch(line -> line.contains("proc-gone")), out.toString());
    }

    private void run(String arguments) {
        new McpSessionCommand(servers, () -> {
            if (jobsUnreadable != null) throw jobsUnreadable;
            return jobs;
        }, () -> turnActive, pid -> !exitedPids.contains(pid), new TerminalRenderer(false), out::add)
                .run(arguments);
    }

    /** The session's Claude Code process: its servers, and the reconnects it was asked for. */
    private static final class FakeServers implements McpSessionCommand.Servers {
        List<ClaudeMcpServer> current = List.of(KOMPILE, DOCS, OFF);
        boolean running = true;
        boolean endsOnReconnect;
        String refused;
        IOException listFailure;
        final List<String> reconnected = new ArrayList<>();
        final List<Duration> timeouts = new ArrayList<>();

        @Override
        public List<ClaudeMcpServer> list(Duration timeout) throws IOException {
            if (listFailure != null) throw listFailure;
            return running ? current : null;
        }

        @Override
        public boolean reconnect(String name, Duration timeout) throws IOException {
            if (!running || endsOnReconnect) return false;
            timeouts.add(timeout);
            if (name.equals(refused)) throw new IOException("Server not found: " + name);
            reconnected.add(name);
            return true;
        }
    }
}
