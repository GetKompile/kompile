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

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongPredicate;

/**
 * {@code /mcp}: the MCP servers of the Claude Code process serving this chat
 * session, and restarting them in place.
 *
 * <p>Claude Code starts a session's MCP servers with its process, so each keeps
 * running the build it started from. {@code /mcp reconnect} has Claude Code
 * restart a server ({@code mcp_reconnect}): it stops the server's process with
 * SIGINT and starts it again from the same configuration, which moves
 * Kompile's own server onto a redeployed build without restarting the session.
 * When Kompile's stdio server stops, it stops the background jobs its
 * {@code process} tool runs for the session, so restarting it while one runs
 * takes {@code --force}. No server is restarted while a turn runs: the turn's
 * calls to it would fail.</p>
 */
final class McpSessionCommand {

    static final Duration STATUS_TIMEOUT = Duration.ofSeconds(10);
    static final Duration RECONNECT_TIMEOUT = Duration.ofSeconds(60);

    /** The Claude Code process serving the session; null or false when none runs. */
    interface Servers {
        List<ClaudeMcpServer> list(Duration timeout) throws IOException;

        boolean reconnect(String name, Duration timeout) throws IOException;
    }

    /** Processes other sessions, Kompile's MCP server among them, run on this session's behalf. */
    interface SessionJobs {
        List<SharedProcessMirror.SessionProcess> running() throws IOException;
    }

    private final Servers servers;
    private final SessionJobs jobs;
    private final BooleanSupplier turnActive;
    private final LongPredicate processAlive;
    private final TerminalRenderer renderer;
    private final Consumer<String> out;

    McpSessionCommand(Servers servers, SessionJobs jobs, BooleanSupplier turnActive,
                      TerminalRenderer renderer, Consumer<String> out) {
        this(servers, jobs, turnActive, McpSessionCommand::alive, renderer, out);
    }

    McpSessionCommand(Servers servers, SessionJobs jobs, BooleanSupplier turnActive,
                      LongPredicate processAlive, TerminalRenderer renderer, Consumer<String> out) {
        this.servers = servers;
        this.jobs = jobs;
        this.turnActive = turnActive;
        this.processAlive = processAlive;
        this.renderer = renderer;
        this.out = out;
    }

    void run(String arguments) {
        List<String> words = new ArrayList<>(Arrays.asList(
                (arguments == null ? "" : arguments.strip()).split("\\s+")));
        words.removeIf(String::isEmpty);
        boolean force = words.remove("--force");
        String action = words.isEmpty() ? "status" : words.get(0).toLowerCase(Locale.ROOT);
        if ("status".equals(action) && words.size() <= 1 && !force) {
            List<ClaudeMcpServer> current = list();
            if (current != null) print(current);
        } else if ("reconnect".equals(action) && words.size() <= 2) {
            reconnect(words.size() == 2 ? words.get(1) : null, force);
        } else {
            out.accept("  Usage: /mcp [status]                    Show this session's MCP servers");
            out.accept("         /mcp reconnect [name] [--force]  Restart one server, or all of them, in place");
        }
    }

    private void reconnect(String name, boolean force) {
        if (turnActive.getAsBoolean()) {
            out.accept(renderer.yellow("  A turn is running. Reconnect after it ends: "
                    + "the turn's calls to a restarting server would fail."));
            return;
        }
        List<ClaudeMcpServer> current = list();
        if (current == null) return;
        List<ClaudeMcpServer> targets = new ArrayList<>();
        for (ClaudeMcpServer server : current) {
            if (name == null ? !"disabled".equals(server.status()) : server.name().equals(name)) {
                targets.add(server);
            }
        }
        if (targets.isEmpty()) {
            out.accept(renderer.yellow(name == null
                    ? "  Claude Code runs no MCP servers to reconnect."
                    : "  No MCP server named " + name + ". Servers: "
                            + String.join(", ", current.stream().map(ClaudeMcpServer::name).toList())));
            return;
        }
        if (!force && targets.stream().anyMatch(ClaudeMcpServer::isKompileStdio) && !jobsAllowRestart(name)) {
            return;
        }
        for (ClaudeMcpServer server : targets) {
            out.accept(renderer.dim("  Reconnecting " + server.name() + "…"));
            try {
                if (!servers.reconnect(server.name(), RECONNECT_TIMEOUT)) {
                    out.accept(renderer.yellow("  The Claude Code process ended; "
                            + "the next message starts a new one, with its MCP servers."));
                    return;
                }
            } catch (IOException e) {
                out.accept(renderer.red("  Could not reconnect " + server.name() + ": " + e.getMessage()));
            }
        }
        List<ClaudeMcpServer> after = list();
        if (after != null) print(after);
    }

    /**
     * True when no background job would stop with Kompile's server. Otherwise
     * says which jobs would, or why they could not be checked, and is false.
     */
    private boolean jobsAllowRestart(String name) {
        String forced = "/mcp reconnect " + (name == null ? "" : name + " ") + "--force";
        List<SharedProcessMirror.SessionProcess> running;
        try {
            running = jobs.running().stream().filter(job -> processAlive.test(job.pid())).toList();
        } catch (IOException e) {
            out.accept(renderer.yellow("  Not reconnecting: could not check for background jobs that "
                    + "restarting Kompile's server would stop (" + e.getMessage() + ")."));
            out.accept(renderer.dim("  " + forced + " reconnects anyway."));
            return false;
        }
        if (running.isEmpty()) return true;
        out.accept(renderer.yellow("  Not reconnecting: restarting Kompile's server stops the background "
                + (running.size() == 1 ? "job" : "jobs") + " it runs for this session:"));
        for (SharedProcessMirror.SessionProcess job : running) {
            out.accept("    " + job.processId() + (job.pid() > 0 ? " (pid " + job.pid() + ")" : "")
                    + "  " + oneLine(job.label(), 100));
        }
        out.accept(renderer.dim("  Wait for " + (running.size() == 1 ? "it" : "them") + " to end, or run "
                + forced + " to stop " + (running.size() == 1 ? "it." : "them.")));
        return false;
    }

    /** The servers, or null after saying why there are none to show. */
    private List<ClaudeMcpServer> list() {
        List<ClaudeMcpServer> current;
        try {
            current = servers.list(STATUS_TIMEOUT);
        } catch (IOException e) {
            out.accept(renderer.red("  Could not read the MCP servers from Claude Code: " + e.getMessage()));
            return null;
        }
        if (current == null) {
            out.accept(renderer.dim("  No Claude Code process is running for this session; "
                    + "the next message starts one, with its MCP servers."));
        }
        return current;
    }

    private void print(List<ClaudeMcpServer> current) {
        if (current.isEmpty()) {
            out.accept(renderer.dim("  Claude Code runs no MCP servers for this session."));
            return;
        }
        int width = current.stream().mapToInt(server -> server.name().length()).max().orElse(0);
        out.accept(renderer.cyan("  MCP servers of this session's Claude Code process"));
        for (ClaudeMcpServer server : current) {
            List<String> details = new ArrayList<>();
            if (!server.serverInfo().isEmpty()) details.add(server.serverInfo());
            if ("connected".equals(server.status())) {
                details.add(server.toolCount() + (server.toolCount() == 1 ? " tool" : " tools"));
            }
            if (!server.transport().isEmpty()) details.add(server.transport());
            if (server.isKompileStdio()) details.add("runs this session's background jobs");
            StringBuilder line = new StringBuilder("  ").append(pad(server.name(), width)).append("  ")
                    .append(status(server.status())).append("  ")
                    .append(renderer.dim(String.join(" · ", details)));
            if (!server.error().isEmpty()) line.append("  ").append(renderer.red(oneLine(server.error(), 160)));
            out.accept(line.toString());
        }
        out.accept(renderer.dim("  /mcp reconnect [name] restarts servers in place, e.g. onto a redeployed Kompile."));
    }

    private String status(String status) {
        String shown = pad(status.isEmpty() ? "unknown" : status, 10);
        return switch (status) {
            case "connected" -> renderer.green(shown);
            case "failed" -> renderer.red(shown);
            case "disabled" -> renderer.dim(shown);
            default -> renderer.yellow(shown);
        };
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }

    private static String oneLine(String value, int max) {
        String line = value.strip().replaceAll("\\s+", " ");
        return line.length() <= max ? line : line.substring(0, max - 1) + "…";
    }

    /** A pid the coordination entry could not record counts as running. */
    private static boolean alive(long pid) {
        return pid <= 0 || ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
