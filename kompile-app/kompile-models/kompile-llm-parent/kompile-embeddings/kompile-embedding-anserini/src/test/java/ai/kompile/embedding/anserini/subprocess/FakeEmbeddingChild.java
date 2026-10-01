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

package ai.kompile.embedding.anserini.subprocess;

import ai.kompile.app.subprocess.SubprocessProtocolChannel;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Stand-in for {@link EmbeddingSubprocessMain} in {@link EmbeddingSubprocessCrashLifecycleTest}.
 * It answers LoadModel and Shutdown over the same protocol, sends no heartbeats, and loads no ND4J.
 * Like the real child, it writes its responses to the protocol channel when its launcher set one
 * up ({@link SubprocessProtocolChannel#open}), and to its original stdout otherwise.
 *
 * <p>It records what the test cannot ask the launcher, in the directory named by
 * {@code FAKE_EMBED_DIR}:
 * <ul>
 *   <li>{@code pids}: the pid of every child started, one per line;</li>
 *   <li>{@code events}: {@code start pid=<pid> prevAlive=<bool> key=<absent|match|mismatch>} when
 *       it starts, where {@code prevAlive} says whether the child started before it was still
 *       alive, and {@code load <modelId> <optimal> <max> <absoluteMax> <modelConfig>} for each
 *       load request.</li>
 * </ul>
 * The staging key is compared with {@code FAKE_EMBED_EXPECTED_KEY}, never written anywhere.
 *
 * <p>With {@code FAKE_EMBED_EXIT_ON_LOAD=<code>} it answers no load request. It records the load,
 * closes its protocol stream and exits with that code {@value #EXIT_DELAY_MS} ms later. A dying
 * child's pipes close before the kernel reports its exit, so the launcher sees the end of the output
 * while the child still looks alive; the delay makes that order certain. Closing the stream releases
 * the pipe only when the pipe is the child's fd 1 (a closed {@code System.out} points fd 1 at
 * {@code /dev/null}): the channel's fd is inherited, and Java can't close it before the process
 * exits. The test's launch script gives such a child the layout of an unwrapped one.
 *
 * <p>With {@code FAKE_EMBED_NATIVE_TEXT=<text>} it writes that text to fd 1 itself in front of each
 * response, as libnd4j's printf does beneath the real child's {@code System.setOut} redirect.
 *
 * <p>With {@code FAKE_EMBED_LEGACY} set it writes its responses to fd 1, as a child built before the
 * protocol channel does.
 *
 * <p>With {@code FAKE_EMBED_QUOTE_ON_STDERR} set it logs a failed response to each load request on
 * stderr, {@value #QUOTE_LEAD_MS} ms before it sends the real one: a log line that quotes a protocol
 * message.
 *
 * <p>With {@code FAKE_EMBED_HELD=stdout} or {@code FAKE_EMBED_HELD=stderr}, the first child to get a
 * load request writes {@link #HOLD_MARK}: as a protocol log message, or as a stderr line. It waits
 * for a {@code go} file, then writes its last output the same way (the response, or the error line
 * {@link #LAST_WORDS}), creates a {@code written} file and waits to be killed, reading nothing more.
 * It leaves a {@code held} file, so a child started after it behaves normally.
 */
public final class FakeEmbeddingChild {

    /** What a held child writes first; the test holds the launcher's reader on it. */
    static final String HOLD_MARK = "fake-embed-hold";

    /** The stderr line a child held there writes once the test lets it go on. */
    static final String LAST_WORDS = "ERROR fake - last words before death";

    private static final long EXIT_DELAY_MS = 300;

    private static final long QUOTE_LEAD_MS = 300;

    private FakeEmbeddingChild() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(System.getenv("FAKE_EMBED_DIR"));
        String exitOnLoad = System.getenv("FAKE_EMBED_EXIT_ON_LOAD");
        String nativeText = System.getenv("FAKE_EMBED_NATIVE_TEXT");
        boolean legacy = System.getenv("FAKE_EMBED_LEGACY") != null;
        boolean quoteOnStderr = System.getenv("FAKE_EMBED_QUOTE_ON_STDERR") != null;
        String held = System.getenv("FAKE_EMBED_HELD");

        // A child the test fails to stop must not outlive the test run.
        Thread reaper = new Thread(() -> {
            try {
                Thread.sleep(120_000);
            } catch (InterruptedException ignored) {
                // halt below either way
            }
            Runtime.getRuntime().halt(3);
        }, "fake-embedding-child-reaper");
        reaper.setDaemon(true);
        reaper.start();

        long pid = ProcessHandle.current().pid();
        Path pids = dir.resolve("pids");
        boolean prevAlive = false;
        if (Files.exists(pids)) {
            List<String> lines = Files.readAllLines(pids, StandardCharsets.UTF_8);
            if (!lines.isEmpty()) {
                long prev = Long.parseLong(lines.get(lines.size() - 1).trim());
                prevAlive = ProcessHandle.of(prev).map(ProcessHandle::isAlive).orElse(false);
            }
        }
        append(pids, Long.toString(pid));
        Path events = dir.resolve("events");
        append(events, "start pid=" + pid + " prevAlive=" + prevAlive + " key=" + keyState());

        ObjectMapper mapper = JsonUtils.standardMapper();
        PrintStream out = legacy ? System.out : SubprocessProtocolChannel.open(System.out);
        System.setOut(System.err);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            EmbeddingSubprocessMessage message = mapper.readValue(line, EmbeddingSubprocessMessage.class);
            if (message instanceof EmbeddingSubprocessMessage.LoadModelRequest request) {
                append(events, "load " + request.modelId() + " " + request.optimalBatchSize() + " "
                        + request.maxBatchSize() + " " + request.absoluteMaxBatchSize() + " "
                        + request.modelConfig());
                if (held != null && !Files.exists(dir.resolve("held"))) {
                    Files.createFile(dir.resolve("held"));
                    hold("stdout".equals(held), request, out, mapper, dir);
                }
                if (exitOnLoad != null) {
                    // PrintStream.close() on the original stdout points fd 1 at /dev/null, releasing
                    // the pipe when it is the protocol pipe.
                    out.close();
                    Thread.sleep(EXIT_DELAY_MS);
                    System.exit(Integer.parseInt(exitOnLoad));
                }
                if (quoteOnStderr) {
                    EmbeddingSubprocessMessage.LoadModelResponse quoted = new EmbeddingSubprocessMessage.LoadModelResponse(
                            request.requestId(), false, request.modelId(), 0, "fake", "FAKE", 0L, "quoted, not sent");
                    System.err.println("sending " + EmbeddingSubprocessMessage.MESSAGE_PREFIX
                            + mapper.writeValueAsString(quoted));
                    System.err.flush();
                    Thread.sleep(QUOTE_LEAD_MS);
                }
                if (Files.exists(dir.resolve("ignore-load"))) {
                    continue; // A live child that never answers the replacement's load request.
                }
                boolean rejected = Files.exists(dir.resolve("reject-load"));
                EmbeddingSubprocessMessage.LoadModelResponse response = new EmbeddingSubprocessMessage.LoadModelResponse(
                        request.requestId(), !rejected, request.modelId(), rejected ? 0 : 384,
                        "fake", "FAKE", 1L, rejected ? "reload rejected (test)" : null);
                if (nativeText != null) {
                    writeToFd1(nativeText);
                }
                out.println(EmbeddingSubprocessMessage.MESSAGE_PREFIX + mapper.writeValueAsString(response));
                out.flush();
            } else if (message instanceof EmbeddingSubprocessMessage.ShutdownRequest) {
                break;
            }
        }
        System.exit(0);
    }

    /** The held scenario: never returns, the test kills the child. */
    private static void hold(boolean onProtocol, EmbeddingSubprocessMessage.LoadModelRequest request,
                             PrintStream out, ObjectMapper mapper, Path dir) throws Exception {
        if (onProtocol) {
            EmbeddingSubprocessMessage.Log mark = new EmbeddingSubprocessMessage.Log(
                    "INFO", "fake", HOLD_MARK, System.currentTimeMillis());
            out.println(EmbeddingSubprocessMessage.MESSAGE_PREFIX + mapper.writeValueAsString(mark));
            out.flush();
        } else {
            System.err.println(HOLD_MARK);
            System.err.flush();
        }
        Path go = dir.resolve("go");
        while (!Files.exists(go)) {
            Thread.sleep(10);
        }
        if (onProtocol) {
            EmbeddingSubprocessMessage.LoadModelResponse response = new EmbeddingSubprocessMessage.LoadModelResponse(
                    request.requestId(), true, request.modelId(), 384, "fake", "FAKE", 1L, null);
            out.println(EmbeddingSubprocessMessage.MESSAGE_PREFIX + mapper.writeValueAsString(response));
            out.flush();
        } else {
            System.err.println(LAST_WORDS);
            System.err.flush();
        }
        Files.createFile(dir.resolve("written"));
        Thread.sleep(Long.MAX_VALUE);
    }

    private static String keyState() {
        String key = System.getenv("KOMPILE_STAGING_API_KEY");
        if (key == null) {
            return "absent";
        }
        return key.equals(System.getenv("FAKE_EMBED_EXPECTED_KEY")) ? "match" : "mismatch";
    }

    /** Writes as native code does: to fd 1 itself, beneath {@code System.setOut}. */
    private static void writeToFd1(String text) throws IOException {
        FileOutputStream fd1 = new FileOutputStream(FileDescriptor.out);
        fd1.write(text.getBytes(StandardCharsets.UTF_8));
        fd1.flush();
    }

    private static void append(Path file, String line) throws IOException {
        Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
