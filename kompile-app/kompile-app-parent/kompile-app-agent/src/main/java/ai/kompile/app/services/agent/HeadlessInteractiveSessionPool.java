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

package ai.kompile.app.services.agent;

import ai.kompile.core.agent.AgentProvider;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Manages a pool of PERSISTENT interactive CLI agent sessions for headless (non-display)
 * programmatic use — i.e. extraction LLM calls from {@link CliAgentLLMChat}.
 *
 * <h3>Architecture</h3>
 * Each session is a long-lived subprocess (the agent in interactive mode, same command
 * that {@link AgentSubprocessExecutor#buildInteractiveCommand} produces — no {@code -p},
 * no one-shot invocation). Sessions are kept alive across extraction calls and returned
 * to the pool after each turn.
 *
 * <h3>PTY spawn</h3>
 * Sessions are spawned via {@link PtyProcessBuilder} (pty4j), which allocates a real
 * POSIX PTY master+slave pair. The agent process is attached to the slave end and sees
 * {@code isatty()==true}, causing Claude's REPL to enter interactive mode instead of
 * hanging on the plain-pipe path. {@link PtyProcess} extends {@link Process} so the pool's
 * lifecycle logic (isAlive, destroyForcibly, waitFor) is unchanged.
 *
 * <h3>TTY echo suppression</h3>
 * A PTY master echoes every byte written to it back through the read (output) stream.
 * The prompt text we write to stdin therefore appears immediately in the output stream
 * before the agent's reply begins. We suppress this safely without any OS-level
 * {@code tcsetattr(ECHO=off)} call (pty4j 0.12.x exposes no echo-control API from Java):
 * <ol>
 *   <li><strong>Stream-json sessions (claude / codex / opencode):</strong> every line
 *       emitted by the agent in {@code --output-format stream-json} mode is a JSON object
 *       starting with {@code {}. The echoed prompt text is plain prose — it never starts
 *       with {@code {}. The reader pre-filters: any line whose first non-whitespace
 *       character is not {@code {} is silently discarded <em>before</em> being passed to
 *       {@link ClaudeStreamParser#parseLine}. This is 100% reliable because the echo
 *       always precedes the first JSON event and no legitimate stream-json line is
 *       non-JSON.</li>
 *   <li><strong>Plain-text sessions (gemini, etc.):</strong> echo cannot be distinguished
 *       from agent output by content alone. We anchor turn completion on the agent's
 *       interactive prompt pattern (e.g. {@code "> "}) rather than on content lines, so
 *       echoed lines accumulate harmlessly in the buffer and are present in the returned
 *       text. Callers that use plain-text sessions should apply their own post-processing
 *       to strip the echoed prompt prefix if needed.</li>
 * </ol>
 *
 * <h3>Turn protocol</h3>
 * <ol>
 *   <li>Write prompt + newline to the session's stdin (PTY master write end).</li>
 *   <li>The session's dedicated reader thread accumulates output lines via
 *       {@link ClaudeStreamParser#parseLine} (for stream-json agents) or plain-text
 *       prompt-pattern matching (for others). When the parser signals
 *       {@code isResult()==true} — or the prompt pattern fires — the turn is complete.</li>
 *   <li>The accumulated text is delivered via a per-turn {@link CompletableFuture}.</li>
 * </ol>
 *
 * <h3>Robustness</h3>
 * <ul>
 *   <li>Per-call timeout: if the future does not complete within the configured deadline,
 *       the session is killed and replaced.</li>
 *   <li>Dead-session detection: before checking out a session the pool verifies
 *       {@code process.isAlive()}; dead entries are discarded and replaced.</li>
 *   <li>Startup settle: after spawning, each new session sleeps for
 *       {@link #STARTUP_SETTLE_MS} before being marked pool-ready, giving the agent
 *       TUI time to render its chrome before the first prompt arrives.</li>
 * </ul>
 *
 * <h3>Model pinning</h3>
 * Each {@link HeadlessSession} remembers the model it was spawned with so callers can
 * attribute outcomes back to the right model for health-based de-escalation.
 */
@Service
public class HeadlessInteractiveSessionPool {

    private static final Logger log = LoggerFactory.getLogger(HeadlessInteractiveSessionPool.class);

    /** Milliseconds a newly-spawned session waits before being declared pool-ready. */
    private static final long STARTUP_SETTLE_MS = 3_000L;

    /**
     * PTY column width. Large value prevents stream-json lines from being wrapped
     * mid-token by the terminal line discipline (which would corrupt JSON framing).
     * 10000 columns: no realistic JSON event line reaches this; it is effectively
     * "no wrapping". Can be widened further with no functional cost.
     */
    private static final int PTY_COLUMNS = 10_000;

    /**
     * PTY row height. Irrelevant for non-visual headless use; 50 rows matches
     * a typical terminal and avoids any agent-side "terminal too small" guards.
     */
    private static final int PTY_ROWS = 50;

    /**
     * TERM value injected into the agent environment. {@code dumb} disables colour
     * and most cursor-positioning escapes, reducing ANSI noise in the output stream.
     * {@link ClaudeStreamParser#stripAnsi} handles whatever escapes still leak through.
     */
    private static final String PTY_TERM = "dumb";

    /** Session ID prefix for log correlation. */
    private static final String SESSION_ID_PREFIX = "headless-";

    private final ClaudeStreamParser streamParser;

    public HeadlessInteractiveSessionPool(ClaudeStreamParser streamParser) {
        this.streamParser = streamParser;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Session
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * A single persistent interactive agent session. Held in the pool while idle;
     * exclusively checked-out while a turn is in-flight.
     */
    public static final class HeadlessSession {

        final String sessionId;
        final String agentName;
        /** The model this session was pinned to at spawn time, or null. */
        final String model;
        /** The PTY-backed process. PtyProcess extends Process so lifecycle calls are identical. */
        final Process process;
        final OutputStream stdin;
        /** Reader thread — lives for the lifetime of the session. Set once after construction. */
        Thread readerThread;
        /** Whether stream-json mode is active (claude/codex/opencode) or plain-text (gemini). */
        final boolean streamJson;
        /** Regex that marks turn completion for non-stream-json agents (may be null). */
        final Pattern promptPattern;
        final AtomicBoolean alive = new AtomicBoolean(true);

        // Per-turn state — reset by startTurn(), resolved by reader thread.
        volatile CompletableFuture<String> turnFuture;
        volatile StringBuilder turnBuffer;

        HeadlessSession(String sessionId, String agentName, String model,
                        Process process,
                        boolean streamJson, Pattern promptPattern) {
            this.sessionId = sessionId;
            this.agentName = agentName;
            this.model = model;
            this.process = process;
            this.stdin = process.getOutputStream();
            this.streamJson = streamJson;
            this.promptPattern = promptPattern;
        }

        boolean isAlive() {
            return alive.get() && process.isAlive();
        }

        /**
         * Prepare for a new turn: atomically installs a fresh {@link CompletableFuture}
         * and clears the text buffer. Must be called while the session is exclusively
         * held by the caller (not in the pool).
         */
        CompletableFuture<String> startTurn() {
            turnBuffer = new StringBuilder();
            CompletableFuture<String> f = new CompletableFuture<>();
            turnFuture = f;
            return f;
        }

        /**
         * Write prompt text to the agent's stdin (PTY master write end) followed by a
         * newline to submit. Synchronized so concurrent callers (impossible while the
         * session is exclusively held, but defensive) do not interleave bytes.
         *
         * <p><strong>Echo note:</strong> the PTY line discipline echoes these bytes back
         * through getInputStream() immediately. The reader thread suppresses them — see
         * class-level Javadoc for the two suppression strategies.
         */
        void sendPrompt(String prompt) throws IOException {
            synchronized (stdin) {
                stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
                stdin.write('\n');
                stdin.flush();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Pool management
    // ═══════════════════════════════════════════════════════════════════════════

    /** The idle pool — sessions waiting to serve a call. */
    private final LinkedBlockingQueue<HeadlessSession> idlePool = new LinkedBlockingQueue<>();

    /** Background replenisher thread pool. */
    private final ExecutorService replenisher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "headless-pool-replenisher");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean shutdown = false;

    /**
     * Borrow a live session from the pool, discarding dead ones.
     * Returns null if the pool is empty.
     */
    private HeadlessSession borrowFromPool() {
        HeadlessSession session;
        while ((session = idlePool.poll()) != null) {
            if (session.isAlive()) {
                log.debug("Headless pool hit — session {} model={}", session.sessionId, session.model);
                return session;
            }
            log.debug("Discarding dead headless session {}", session.sessionId);
            terminateQuietly(session);
        }
        return null;
    }

    /**
     * Return a session to the idle pool after a successful turn.
     * Discards it if the underlying process has died.
     */
    void returnToPool(HeadlessSession session) {
        if (session == null) return;
        if (session.isAlive()) {
            // Clear per-turn state so a subsequent caller doesn't see stale data.
            session.turnFuture = null;
            session.turnBuffer = null;
            idlePool.offer(session);
        } else {
            log.debug("Not returning dead session {} to pool", session.sessionId);
            terminateQuietly(session);
        }
    }

    /**
     * Spawn N sessions and add them to the idle pool (called by the pool replenisher).
     * Each session sleeps for {@link #STARTUP_SETTLE_MS} before being offered to the pool
     * so the agent TUI finishes rendering its chrome.
     */
    public void prefill(AgentProvider agent, String modelOverride,
                        AgentSubprocessExecutor executor, int count) {
        if (shutdown) return;
        for (int i = 0; i < count; i++) {
            if (shutdown) break;
            HeadlessSession s = spawnSession(agent, modelOverride, executor);
            if (s != null) {
                try {
                    Thread.sleep(STARTUP_SETTLE_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    terminateQuietly(s);
                    return;
                }
                if (s.isAlive()) {
                    idlePool.offer(s);
                    log.info("Headless pool: added session {} model={} (pool size now {})",
                            s.sessionId, s.model, idlePool.size());
                } else {
                    log.warn("Headless pool: session {} died before it could be pooled", s.sessionId);
                }
            }
        }
    }

    /**
     * Schedule an asynchronous replenishment of the idle pool up to {@code targetSize}.
     */
    public void scheduleReplenish(AgentProvider agent, String modelOverride,
                                  AgentSubprocessExecutor executor, int targetSize) {
        int deficit = targetSize - idlePool.size();
        if (deficit <= 0 || shutdown) return;
        final int toSpawn = deficit;
        replenisher.submit(() -> prefill(agent, modelOverride, executor, toSpawn));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Session spawning — PTY
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Spawn a new persistent interactive session for the given agent via a real PTY.
     *
     * <p>{@link PtyProcessBuilder} allocates a POSIX master+slave PTY pair. The agent
     * subprocess is attached to the slave end (so {@code isatty(0)==true} in the child)
     * and the JVM holds the master end via {@link PtyProcess#getInputStream()} /
     * {@link PtyProcess#getOutputStream()}.
     *
     * <p>Environment: {@code agent.safeEnvironment()} is merged into a fresh map so the
     * caller cannot see the JVM's raw env. {@code TERM=dumb} is added unconditionally;
     * it suppresses colour rendering and most cursor-positioning escapes, reducing the
     * ANSI noise the reader thread must strip. If the caller already set TERM it is
     * overwritten — dumb is the correct choice for a headless JSON-parsing consumer.
     *
     * <p>The returned session has a live reader thread and is NOT yet in the pool.
     * Returns null on failure.
     */
    public HeadlessSession spawnSession(AgentProvider agent, String modelOverride,
                                        AgentSubprocessExecutor executor) {
        if (agent == null || executor == null) return null;
        try {
            List<String> command = executor.buildInteractiveCommand(
                    agent, true, false, null, modelOverride);

            // Build the child environment: start from the agent's safe env, then add TERM.
            // PtyProcessBuilder.setEnvironment() replaces the process environment entirely,
            // so we must include process-plumbing basics (PATH, HOME, SHELL) when safeEnvironment()
            // does not already provide them; otherwise pty4j cannot resolve commands like opencode.
            Map<String, String> env = new HashMap<>(agent.safeEnvironment());
            inheritIfBlank(env, "PATH");
            inheritIfBlank(env, "HOME");
            inheritIfBlank(env, "SHELL");
            env.put("TERM", PTY_TERM);
            command = resolveExecutable(command, env);

            PtyProcess proc = new PtyProcessBuilder(command.toArray(new String[0]))
                    .setEnvironment(env)
                    .setRedirectErrorStream(true)    // merge stderr into the PTY master read stream
                    .setInitialColumns(PTY_COLUMNS)  // wide enough to prevent mid-JSON line wrap
                    .setInitialRows(PTY_ROWS)
                    .setConsole(false)               // false = allocate a proper interactive PTY
                    .start();

            String sid = SESSION_ID_PREFIX + UUID.randomUUID().toString().substring(0, 8);
            String agentName = agent.getName() == null ? "" : agent.getName();
            boolean useStreamJson = streamParser.supportsStreamJson(agentName);

            Pattern promptPattern = null;
            if (agent.getInteractivePromptPattern() != null
                    && !agent.getInteractivePromptPattern().isBlank()) {
                promptPattern = Pattern.compile(agent.getInteractivePromptPattern());
            }

            HeadlessSession session = new HeadlessSession(
                    sid, agentName, modelOverride, proc,
                    useStreamJson, promptPattern);

            // Build and start the reader thread, then wire the reference back into
            // the session (readerThread is non-final, set once here before any caller
            // can see the session).
            Thread reader = buildReaderThread(session);
            session.readerThread = reader;
            reader.setDaemon(true);
            reader.start();

            log.info("Spawned PTY headless session {} for agent '{}' model={} PID={}",
                    sid, agentName, modelOverride, proc.pid());
            return session;

        } catch (Exception e) {
            log.warn("Failed to spawn PTY headless session for agent '{}': {}",
                    agent.getName(), e.getMessage());
            return null;
        }
    }

    private void inheritIfBlank(Map<String, String> env, String key) {
        String current = env.get(key);
        if (current != null && !current.isBlank()) {
            return;
        }
        String inherited = System.getenv(key);
        if (inherited != null && !inherited.isBlank()) {
            env.put(key, inherited);
        }
    }

    private List<String> resolveExecutable(List<String> command, Map<String, String> env) {
        if (command == null || command.isEmpty()) {
            return command;
        }
        String executable = command.get(0);
        String resolved = resolveExecutable(executable, env.get("PATH"));
        if (resolved.equals(executable)) {
            return command;
        }
        List<String> copy = new ArrayList<>(command);
        copy.set(0, resolved);
        return copy;
    }

    private String resolveExecutable(String executable, String pathEnv) {
        if (executable == null || executable.isBlank()) {
            return executable;
        }
        Path direct = Path.of(executable);
        if (direct.isAbsolute() || executable.contains(File.separator)) {
            return executable;
        }
        if (pathEnv == null || pathEnv.isBlank()) {
            return executable;
        }
        for (String part : pathEnv.split(File.pathSeparator)) {
            if (part == null || part.isBlank()) {
                continue;
            }
            Path candidate = Path.of(part, executable);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return executable;
    }

    /**
     * Build and return (but do not start) the reader thread for {@code session}.
     * The reader runs for the lifetime of the session; it accumulates per-turn text
     * and completes {@link HeadlessSession#turnFuture} when a turn boundary is detected.
     *
     * <h4>Echo suppression in the reader</h4>
     * <p>The PTY master echoes written bytes back through its read end. For stream-json
     * sessions this reader pre-screens each line: only lines whose first non-whitespace
     * character is {@code '{'} are forwarded to {@link ClaudeStreamParser#parseLine}.
     * Claude's {@code --output-format stream-json} emits exclusively JSON objects, so
     * every legitimate agent output line starts with {@code {}. The echoed prompt is
     * prose — it never starts with {@code {}. This gate is applied unconditionally in
     * the stream-json branch so no echoed input can ever reach the turn buffer,
     * regardless of prompt content.
     *
     * <p>For plain-text sessions there is no content-based gate — echoed lines accumulate
     * in the buffer alongside real output. Turn completion is detected via the
     * {@code promptPattern} (the agent's interactive prompt line), not by content.
     */
    private Thread buildReaderThread(HeadlessSession session) {
        return new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(session.process.getInputStream(), StandardCharsets.UTF_8))) {
                String rawLine;
                while ((rawLine = reader.readLine()) != null) {
                    if (!session.alive.get()) break;

                    // Strip ANSI escapes — a PTY emits more control sequences than plain pipes
                    // (cursor movement, colour, bracketed-paste mode toggles, etc.).
                    String line = ClaudeStreamParser.stripAnsi(rawLine);
                    if (line.isBlank()) continue;

                    CompletableFuture<String> future = session.turnFuture;
                    StringBuilder buf = session.turnBuffer;
                    if (future == null || future.isDone()) {
                        // No active turn — discard (startup banner, idle agent output, echoed
                        // prompt before the turn future was installed, etc.).
                        continue;
                    }

                    if (session.streamJson) {
                        // ── stream-json path (claude / codex / opencode) ─────────────────
                        //
                        // ECHO SUPPRESSION: only process lines that begin with '{'.
                        // All agent stream-json output is a JSON object on a single line.
                        // The echoed prompt (what we wrote to stdin) is plain prose and
                        // never starts with '{'. Skipping non-'{' lines is therefore
                        // both necessary (suppress echo) and safe (no valid output lost).
                        if (!line.trim().startsWith("{")) {
                            log.trace("Headless session {} skipping non-JSON line (echo or banner): {}",
                                    session.sessionId,
                                    line.length() > 80 ? line.substring(0, 80) + "…" : line);
                            continue;
                        }

                        ClaudeStreamParser.ParseResult result =
                                streamParser.parseLine(session.sessionId, line);
                        if (result != null) {
                            if (result.textContent() != null && !result.textContent().isEmpty()) {
                                buf.append(result.textContent());
                            }
                            if (result.isResult()) {
                                // Turn is done — deliver the accumulated text.
                                String text = buf.toString().trim();
                                streamParser.clearSession(session.sessionId);
                                log.debug("Headless session {} turn complete via stream-json ({} chars)",
                                        session.sessionId, text.length());
                                future.complete(text);
                            }
                        }
                        // If parseLine returned null the line was an irrelevant JSON event
                        // (e.g. content_block_start with no text). Do not fallback to
                        // appending the raw line — that would re-introduce echo corruption.

                    } else {
                        // ── plain-text path (gemini, etc.) ──────────────────────────────
                        buf.append(line).append('\n');
                        if (session.promptPattern != null && session.promptPattern.matcher(rawLine).matches()) {
                            String text = buf.toString().trim();
                            log.debug("Headless session {} turn complete via prompt pattern ({} chars)",
                                    session.sessionId, text.length());
                            future.complete(text);
                        }
                    }
                }
            } catch (IOException e) {
                if (session.alive.get()) {
                    log.debug("Headless session {} reader IO error: {}", session.sessionId, e.getMessage());
                }
            } finally {
                session.alive.set(false);
                // If a turn was in-flight when the process died, complete it exceptionally.
                CompletableFuture<String> pending = session.turnFuture;
                if (pending != null && !pending.isDone()) {
                    pending.completeExceptionally(
                            new IOException("Headless session " + session.sessionId + " process died"));
                }
                log.debug("Headless session {} reader thread exiting", session.sessionId);
            }
        }, "headless-reader-" + session.sessionId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Blocking prompt call — the main public API
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Execute a single extraction prompt against a persistent interactive agent session.
     *
     * <p>The call is blocking (up to {@code timeoutSeconds}) but the underlying session
     * is persistent — it stays alive to serve the next call from the pool.
     *
     * <p>Lifecycle:
     * <ol>
     *   <li>Attempt to borrow a live session from the idle pool.</li>
     *   <li>On pool miss, spawn a new session (which is used immediately, NOT added
     *       to the pool first — it goes back to the pool after the turn completes).</li>
     *   <li>Install a turn {@link CompletableFuture} and write the prompt to stdin.</li>
     *   <li>Wait up to {@code timeoutSeconds} for the future to be resolved by the reader.</li>
     *   <li>On success, return the session to the idle pool.
     *       On timeout/failure, kill + discard the session (the pool replenisher will
     *       fill the gap asynchronously).</li>
     * </ol>
     *
     * @param agent          the agent to use
     * @param modelOverride  model to pin to; null means use the agent's default
     * @param executor       command builder (for pool-miss spawning)
     * @param prompt         the extraction prompt text
     * @param timeoutSeconds per-call deadline (same value as the old one-shot path)
     * @param targetPoolSize desired idle pool size after this call (for replenishment)
     * @return extracted text from the agent
     */
    public String prompt(AgentProvider agent, String modelOverride,
                         AgentSubprocessExecutor executor,
                         String prompt, int timeoutSeconds, int targetPoolSize) {
        if (shutdown) {
            throw new IllegalStateException("Headless session pool is shut down");
        }

        HeadlessSession session = null;
        boolean ownedSession = false;
        try {
            // 1. Borrow or spawn.
            session = borrowFromPool();
            if (session == null) {
                log.info("Headless pool miss — spawning fresh PTY session for agent '{}' model={}",
                        agent.getName(), modelOverride);
                session = spawnSession(agent, modelOverride, executor);
                if (session == null) {
                    throw new IllegalStateException("Could not spawn headless interactive session for agent '"
                            + agent.getName() + "'");
                }
                // New session: wait for startup settle before sending.
                try {
                    Thread.sleep(STARTUP_SETTLE_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    terminateQuietly(session);
                    throw new IllegalStateException("Interrupted during headless session startup", ie);
                }
                if (!session.isAlive()) {
                    throw new IllegalStateException("Headless session for agent '" + agent.getName()
                            + "' died during startup");
                }
            }
            ownedSession = true;

            // 2. Start turn and send prompt.
            CompletableFuture<String> turnFuture = session.startTurn();
            session.sendPrompt(prompt);
            log.info("Headless session {} sent {} chars to agent '{}'",
                    session.sessionId, prompt.length(), session.agentName);

            // 3. Await turn completion.
            String result = turnFuture.get(timeoutSeconds, TimeUnit.SECONDS);
            log.info("Headless session {} turn complete: {} chars", session.sessionId,
                    result == null ? 0 : result.length());

            if (result == null || result.isBlank()) {
                terminateQuietly(session);
                scheduleReplenish(agent, modelOverride, executor, targetPoolSize);
                throw new IllegalStateException("Headless session returned empty output");
            }

            // 4. Return session to pool.
            returnToPool(session);
            ownedSession = false; // pool now owns it

            // 5. Async replenishment.
            scheduleReplenish(agent, modelOverride, executor, targetPoolSize);

            return result;

        } catch (TimeoutException te) {
            log.error("Headless session {} timed out after {}s — killing and discarding",
                    session != null ? session.sessionId : "?", timeoutSeconds);
            if (session != null) terminateQuietly(session);
            // Replenish the slot that was lost.
            scheduleReplenish(agent, modelOverride, executor, targetPoolSize);
            throw new IllegalStateException("Timed out after " + timeoutSeconds
                    + "s waiting for interactive response", te);

        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            log.error("Headless session {} execution error: {}",
                    session != null ? session.sessionId : "?",
                    cause != null ? cause.getMessage() : ee.getMessage());
            if (session != null) terminateQuietly(session);
            scheduleReplenish(agent, modelOverride, executor, targetPoolSize);
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Headless session execution failed", cause != null ? cause : ee);

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            if (session != null) terminateQuietly(session);
            throw new IllegalStateException("Headless session interrupted", ie);

        } catch (IOException ioe) {
            log.error("Headless session {} IO error sending prompt: {}",
                    session != null ? session.sessionId : "?", ioe.getMessage());
            if (session != null) terminateQuietly(session);
            scheduleReplenish(agent, modelOverride, executor, targetPoolSize);
            throw new IllegalStateException("Headless session IO error", ioe);

        } finally {
            // If we still own the session (exception path where we didn't return it),
            // make sure it gets cleaned up.
            if (ownedSession && session != null) {
                // Already handled in catch blocks, but guard against any gap.
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Shutdown
    // ═══════════════════════════════════════════════════════════════════════════

    @PreDestroy
    public void shutdownPool() {
        shutdown = true;
        replenisher.shutdownNow();
        HeadlessSession s;
        int count = 0;
        while ((s = idlePool.poll()) != null) {
            terminateQuietly(s);
            count++;
        }
        log.info("HeadlessInteractiveSessionPool shut down ({} idle sessions terminated)", count);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Internal helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private static void terminateQuietly(HeadlessSession session) {
        if (session == null) return;
        session.alive.set(false);
        try {
            if (session.process.isAlive()) {
                session.process.destroyForcibly();
            }
        } catch (Exception ignored) {
        }
    }

    /** Snapshot of the idle pool size (for status reporting). */
    public int idleSize() {
        return idlePool.size();
    }
}
