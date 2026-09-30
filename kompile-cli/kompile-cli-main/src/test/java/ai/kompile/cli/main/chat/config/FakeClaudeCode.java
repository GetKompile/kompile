/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * A stand-in for the persistent Claude Code process {@link ClaudeCliClient}
 * runs: a bash script that reads stream-json input until the input closes. It
 * answers each control request, echoes each user message it takes in the way
 * {@code --replay-user-messages} does, and answers the message with a turn.
 *
 * <p>A test changes its behaviour by redefining the script's functions; the
 * overrides follow the defaults, so they win:</p>
 * <ul>
 *   <li>{@code startup} runs before any input is read. It copies the
 *       instructions file to {@code instructions.md}.</li>
 *   <li>{@code turn N UUID} answers the N-th message this process took in:
 *       init, the text in {@code $ANSWER}, then a result.</li>
 *   <li>{@code control RID SUBTYPE LINE} answers every control request but the
 *       two below with success.</li>
 *   <li>{@code on_interrupt RID LINE} stops the tool recorded in
 *       {@code tool.pid}, answers, and ends the turn.</li>
 *   <li>{@code on_stop_task RID LINE} answers and reports the task killed.</li>
 * </ul>
 *
 * <p>Bodies can use {@code emit JSON}, {@code say_init}, {@code say_text TEXT},
 * {@code say_result}, {@code start_tool} (a 30 s tool whose pid goes to
 * {@code tool.pid}), {@code $DIR} (the fake's directory), {@code $SESSION} (the
 * session id from argv) and {@code $RESUMED} (1 under {@code --resume}). Every
 * process started from one directory appends its arguments to {@code argv.log}
 * and each input line to {@code stdin.log}.</p>
 *
 * <p>{@link #install} routes a {@link DirectLlmClient}'s Claude Code turns to
 * the fake.</p>
 */
public final class FakeClaudeCode {

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();
    private static final long WAIT_MILLIS = 10_000;

    private static final String DEFAULTS = """
            #!/usr/bin/env bash
            DIR='@DIR@'
            printf '%s\\n' "$*" >> "$DIR/argv.log"
            SESSION=
            RESUMED=
            INSTRUCTIONS=
            ANSWER=ok
            args=("$@")
            for ((i = 0; i < ${#args[@]}; i++)); do
              case "${args[i]}" in
                --session-id) SESSION="${args[i+1]}" ;;
                --resume) SESSION="${args[i+1]}"; RESUMED=1 ;;
                --append-system-prompt-file) INSTRUCTIONS="${args[i+1]}" ;;
              esac
            done
            emit() { printf '%s\\n' "$1"; }
            say_init() { emit '{"type":"system","subtype":"init","session_id":"'"$SESSION"'"}'; }
            say_text() {
              emit '{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"'"$1"'"}}}'
            }
            say_result() {
              emit '{"type":"result","subtype":"success","is_error":false,"num_turns":1,"result":"","usage":{"input_tokens":1,"output_tokens":1}}'
            }
            start_tool() {
              sleep 30 > /dev/null 2>&1 &
              echo $! > "$DIR/tool.pid.tmp" && mv "$DIR/tool.pid.tmp" "$DIR/tool.pid"
            }
            startup() {
              if [ -n "$INSTRUCTIONS" ]; then cp "$INSTRUCTIONS" "$DIR/instructions.md"; fi
            }
            turn() {
              say_init
              say_text "$ANSWER"
              say_result
            }
            control() {
              emit '{"type":"control_response","response":{"subtype":"success","request_id":"'"$1"'","response":{}}}'
            }
            on_interrupt() {
              if [ -f "$DIR/tool.pid" ]; then kill "$(cat "$DIR/tool.pid")" 2>/dev/null; fi
              emit '{"type":"control_response","response":{"subtype":"success","request_id":"'"$1"'","response":{"cancelled":[],"still_queued":[]}}}'
              emit '{"type":"result","subtype":"error_during_execution","is_error":true,"num_turns":1}'
            }
            on_stop_task() {
              local re='"task_id":"([^"]+)"' task=
              [[ $2 =~ $re ]] && task="${BASH_REMATCH[1]}"
              emit '{"type":"control_response","response":{"subtype":"success","request_id":"'"$1"'","response":{}}}'
              emit '{"type":"system","subtype":"task_notification","task_id":"'"$task"'","status":"stopped","summary":"stopped"}'
            }
            """;

    private static final String MAIN_LOOP = """
            startup
            N=0
            re_rid='"request_id":"([^"]+)"'
            re_sub='"subtype":"([^"]+)"'
            re_uuid='"uuid":"([^"]+)"'
            while IFS= read -r line; do
              printf '%s\\n' "$line" >> "$DIR/stdin.log"
              case "$line" in
                '{"type":"control_request"'*)
                  rid=
                  sub=
                  [[ $line =~ $re_rid ]] && rid="${BASH_REMATCH[1]}"
                  [[ $line =~ $re_sub ]] && sub="${BASH_REMATCH[1]}"
                  case "$sub" in
                    interrupt) on_interrupt "$rid" "$line" ;;
                    stop_task) on_stop_task "$rid" "$line" ;;
                    *) control "$rid" "$sub" "$line" ;;
                  esac
                  ;;
                '{"type":"user"'*)
                  uuid=
                  [[ $line =~ $re_uuid ]] && uuid="${BASH_REMATCH[1]}"
                  N=$((N + 1))
                  emit '{"type":"user","isReplay":true,"uuid":"'"$uuid"'","message":{"role":"user","content":"(replay)"}}'
                  turn "$N" "$uuid"
                  ;;
              esac
            done
            exit 0
            """;

    /** A condition a test waits for. */
    public interface Condition {
        boolean holds() throws Exception;
    }

    private final Path dir;
    private final Path binary;

    public FakeClaudeCode(Path dir) throws IOException {
        this(dir, "");
    }

    /**
     * @param overrides bash that redefines the script's functions or variables
     */
    public FakeClaudeCode(Path dir, String overrides) throws IOException {
        this.dir = dir.toAbsolutePath().normalize();
        Files.createDirectories(this.dir);
        this.binary = this.dir.resolve("fake-claude");
        // A new file, moved into place: a process still running the old script
        // keeps reading the old one.
        Path script = this.dir.resolve("fake-claude.tmp");
        Files.writeString(script, DEFAULTS.replace("@DIR@", this.dir.toString())
                + (overrides == null ? "" : overrides) + "\n" + MAIN_LOOP, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        Files.move(script, binary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public String binary() {
        return binary.toString();
    }

    public Path path(String name) {
        return dir.resolve(name);
    }

    /**
     * Route the client's Claude Code turns to this fake, started in
     * {@code workingDirectory}. A null {@code sessionId} starts a new session.
     */
    public void install(DirectLlmClient client, Path workingDirectory, String sessionId) throws Exception {
        Field field = DirectLlmClient.class.getDeclaredField("claudeServeClient");
        field.setAccessible(true);
        field.set(client, new ClaudeCliClient(workingDirectory, sessionId, binary()));
    }

    /**
     * Start every Claude Code process the client starts from this fake, in the client's
     * own mode: its session and each of its one-shot requests.
     */
    public void installBinary(DirectLlmClient client) {
        client.setClaudeBinaryOverride(binary());
    }

    /** One line per process started: its arguments. */
    public List<String> argv() throws IOException {
        return lines("argv.log");
    }

    /** Every line written to the processes' input, parsed. */
    public List<JsonNode> inputs() throws IOException {
        List<JsonNode> inputs = new ArrayList<>();
        for (String line : lines("stdin.log")) {
            try {
                inputs.add(MAPPER.readTree(line));
            } catch (IOException e) {
                // The last line may still be being written.
            }
        }
        return inputs;
    }

    /** The text of each user message the processes took in, in order. */
    public List<String> messages() throws IOException {
        List<String> messages = new ArrayList<>();
        for (JsonNode input : inputs()) {
            if ("user".equals(input.path("type").asText())) {
                messages.add(input.path("message").path("content").asText());
            }
        }
        return messages;
    }

    /** The requests of the control requests the processes received, in order. */
    public List<JsonNode> controls() throws IOException {
        List<JsonNode> controls = new ArrayList<>();
        for (JsonNode input : inputs()) {
            if ("control_request".equals(input.path("type").asText())) {
                controls.add(input.path("request"));
            }
        }
        return controls;
    }

    public List<JsonNode> controls(String subtype) throws IOException {
        return controls().stream()
                .filter(request -> subtype.equals(request.path("subtype").asText()))
                .toList();
    }

    /** Wait for a control request of this subtype; returns the first one. */
    public JsonNode awaitControl(String subtype) throws Exception {
        await("a " + subtype + " control request", () -> !controls(subtype).isEmpty());
        return controls(subtype).get(0);
    }

    /** Fail unless the tool {@code start_tool} ran has stopped. */
    public void assertToolStopped() throws Exception {
        long pid = Long.parseLong(Files.readString(path("tool.pid"), StandardCharsets.UTF_8).strip());
        Optional<ProcessHandle> tool = ProcessHandle.of(pid);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (tool.map(ProcessHandle::isAlive).orElse(false)) {
            if (System.nanoTime() > deadline) {
                tool.get().destroyForcibly();
                fail("the tool the CLI started (pid " + pid + ") outlived the stopped turn");
            }
            Thread.sleep(20);
        }
    }

    public static void awaitFile(Path file) throws Exception {
        await(file.toString(), () -> Files.exists(file));
    }

    public static void await(String what, Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
        while (!condition.holds()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }

    private List<String> lines(String name) throws IOException {
        Path file = dir.resolve(name);
        return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
    }
}
