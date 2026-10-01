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

package ai.kompile.app.subprocess;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The protocol channel keeps native output off a child's protocol pipe. {@link ChannelChild} writes
 * the way a Kompile child does, with native text going straight to fd 1.
 */
@Timeout(120)
class SubprocessProtocolChannelTest {

    private static final String PREFIX = "TEST_MSG:";
    private static final int BIG = 200_000;
    private static final String ENV = SubprocessProtocolChannel.ENV_PROTOCOL_FD;

    @TempDir
    Path tempDir;

    @Test
    void aWrappedChildKeepsItsPidAndHasTheStdoutPipeOnFd3AndTheStderrPipeOnFd1() throws Exception {
        ProcessBuilder pb = new ProcessBuilder(SubprocessProtocolChannel.SHELL, "-c",
                "echo \"pid=$$ fd=$" + ENV + "\" >&3; echo on-fd1; echo on-fd2 >&2");
        assumeTrue(SubprocessProtocolChannel.canWrap(pb), "this platform has no /bin/sh or fd directory");

        assertTrue(SubprocessProtocolChannel.apply(pb));
        Process process = pb.start();
        Output output = Output.of(process);

        assertEquals(0, process.waitFor());
        assertEquals(List.of("pid=" + process.pid() + " fd=3"), output.stdout);
        assertEquals(List.of("on-fd1", "on-fd2"), output.stderr);
    }

    @Test
    void aCommandTheWrapperCantRunIsLeftAsItIsAndLosesAnInheritedFdName() throws IOException {
        assumeTrue(SubprocessProtocolChannel.canWrap(new ProcessBuilder(SubprocessProtocolChannel.SHELL)));
        Path notExecutable = Files.writeString(tempDir.resolve("not-executable"), "#!/bin/sh\n");
        List<ProcessBuilder> builders = List.of(
                new ProcessBuilder("sh", "-c", "true"),
                new ProcessBuilder(SubprocessProtocolChannel.SHELL, "-c", "true").redirectErrorStream(true),
                new ProcessBuilder(SubprocessProtocolChannel.SHELL, "-c", "true")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD),
                new ProcessBuilder(SubprocessProtocolChannel.SHELL, "-c", "true")
                        .redirectError(ProcessBuilder.Redirect.INHERIT),
                new ProcessBuilder(tempDir.resolve("no-such-java").toString()),
                new ProcessBuilder(notExecutable.toString()),
                new ProcessBuilder(new ArrayList<>()));
        for (ProcessBuilder pb : builders) {
            pb.environment().put(ENV, "3");
            List<String> command = List.copyOf(pb.command());

            assertFalse(SubprocessProtocolChannel.apply(pb), "wrapped " + command);
            assertEquals(command, pb.command());
            assertFalse(pb.environment().containsKey(ENV), "kept the inherited fd name for " + command);
        }
    }

    @Test
    void aMissingExecutableStillFailsToStart() {
        ProcessBuilder pb = new ProcessBuilder(tempDir.resolve("no-such-java").toString());

        SubprocessProtocolChannel.apply(pb);

        assertThrows(IOException.class, pb::start);
    }

    @Test
    void protocolOnTheChannelArrivesWholeWhileNativeOutputFloodsFd1() throws Exception {
        ProcessBuilder pb = new ProcessBuilder(javaChild("channel"));
        assumeTrue(SubprocessProtocolChannel.canWrap(pb), "this platform has no /bin/sh or fd directory");
        assertTrue(SubprocessProtocolChannel.apply(pb));

        Process process = pb.start();
        Output output = Output.of(process);

        assertEquals(0, process.waitFor(), String.join("\n", output.stderr));
        assertEquals(expectedProtocol(), output.stdout);
        String stderr = String.join("\n", output.stderr);
        assertTrue(stderr.contains(SubprocessProtocolChannel.CHANNEL_OPEN_NOTICE + "3"), stderr);
        assertTrue(stderr.contains("[native] no newline"), "the fd 1 output never reached stderr");
        // The prefix the child printed on stderr after opening the channel is only text
        assertEquals(List.of(), protocolOnStderr(output.stderr, true));
    }

    @Test
    void aChildThatWritesItsProtocolToStdoutIsReadFromStderr() throws Exception {
        ProcessBuilder pb = new ProcessBuilder(javaChild("legacy"));
        assumeTrue(SubprocessProtocolChannel.canWrap(pb), "this platform has no /bin/sh or fd directory");
        assertTrue(SubprocessProtocolChannel.apply(pb));

        Process process = pb.start();
        Output output = Output.of(process);

        assertEquals(0, process.waitFor(), String.join("\n", output.stderr));
        assertEquals(List.of(), output.stdout);
        assertEquals(payloads(expectedProtocol()), protocolOnStderr(output.stderr, true));
        // An unwrapped child's stderr carries no protocol
        assertEquals(List.of(), protocolOnStderr(output.stderr, false));
    }

    @Test
    void theChannelIsUsedOnlyInTheLayoutTheLauncherSetsUp() throws Exception {
        assumeTrue(SubprocessProtocolChannel.canWrap(new ProcessBuilder(SubprocessProtocolChannel.SHELL)));
        // not wrapped: fd 3 is whatever the child JVM opened there, if anything
        assertFallsBackToFd1(javaChild("channel-quiet"), true);
        // fd 3 is the stdout pipe, but so is fd 1
        assertFallsBackToFd1(shell("exec \"$@\" 3>&1", javaChild("channel-quiet")), true);
        // fd 3 and fd 2 are the stderr pipe, and fd 1 is the stdout pipe
        assertFallsBackToFd1(shell("exec \"$@\" 3>&2", javaChild("channel-quiet")), true);
        // fds 1, 2 and 3 are all the stderr pipe
        assertFallsBackToFd1(shell("exec \"$@\" 3>&2 1>&2", javaChild("channel-quiet")), false);
        // fd 3 is a file
        Path file = tempDir.resolve("fd3");
        assertFallsBackToFd1(shell("exec \"$@\" 1>&2 3>'" + file + "'", javaChild("channel-quiet")), false);
        assertEquals(0L, Files.size(file), "wrote protocol to a file");
    }

    /** Runs a child given a protocol fd name, and checks that it writes its protocol to fd 1 and says why. */
    private static void assertFallsBackToFd1(List<String> command, boolean fd1IsTheStdoutPipe) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put(ENV, "3");

        Process process = pb.start();
        Output output = Output.of(process);

        String stderr = String.join("\n", output.stderr);
        assertEquals(0, process.waitFor(), stderr);
        if (fd1IsTheStdoutPipe) {
            // where an unwrapped parent reads protocol
            assertEquals(expectedProtocol(), output.stdout, stderr);
        } else {
            assertEquals(List.of(), output.stdout, stderr);
            assertTrue(output.stderr.containsAll(expectedProtocol()), stderr);
        }
        assertTrue(stderr.contains("protocol channel unavailable"), stderr);
        assertFalse(stderr.contains(SubprocessProtocolChannel.CHANNEL_OPEN_NOTICE), stderr);
    }

    @Test
    void withoutAnFdNameTheChildKeepsItsStdout() {
        assumeTrue(System.getenv(ENV) == null, "the test JVM was itself given a protocol fd");

        assertSame(System.out, SubprocessProtocolChannel.open(System.out));
    }

    @Test
    void stderrCarriesProtocolOnlyForAWrappedChildThatHasNotOpenedTheChannel() {
        SubprocessProtocolChannel.StderrProtocol unwrapped = SubprocessProtocolChannel.stderrProtocol(false, PREFIX, "t");
        SubprocessProtocolChannel.StderrProtocol noPrefix = SubprocessProtocolChannel.stderrProtocol(true, null, "t");
        SubprocessProtocolChannel.StderrProtocol wrapped = SubprocessProtocolChannel.stderrProtocol(true, PREFIX, "t");

        assertEquals(-1, unwrapped.prefixIndex(PREFIX + "{}"));
        assertEquals(-1, noPrefix.prefixIndex(PREFIX + "{}"));
        assertEquals(-1, wrapped.prefixIndex("plain text"));
        assertEquals(-1, wrapped.prefixIndex(null));
        assertEquals(0, wrapped.prefixIndex(PREFIX + "{}"));
        assertEquals(8, wrapped.prefixIndex("[native]" + PREFIX + "{}"));
        // native text can reach fd 1 ahead of the notice
        assertEquals(-1, wrapped.prefixIndex("[native]" + SubprocessProtocolChannel.CHANNEL_OPEN_NOTICE + "3"));
        assertEquals(-1, wrapped.prefixIndex(PREFIX + "{}"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static List<String> expectedProtocol() {
        return List.of(PREFIX + "{\"at\":\"start\"}",
                PREFIX + "{\"big\":\"" + "x".repeat(BIG) + "\"}",
                PREFIX + "{\"at\":\"end\"}");
    }

    private static List<String> payloads(List<String> lines) {
        return lines.stream().map(line -> line.substring(PREFIX.length())).collect(Collectors.toList());
    }

    /** What a launcher's stderr reader hands its protocol handler, reading {@code stderr} line by line. */
    private static List<String> protocolOnStderr(List<String> stderr, boolean wrapped) {
        SubprocessProtocolChannel.StderrProtocol protocol = SubprocessProtocolChannel.stderrProtocol(wrapped, PREFIX, "test");
        List<String> payloads = new ArrayList<>();
        for (String line : stderr) {
            int prefixAt = protocol.prefixIndex(line);
            if (prefixAt >= 0) {
                payloads.add(line.substring(prefixAt + PREFIX.length()));
            }
        }
        return payloads;
    }

    private static List<String> javaChild(String mode) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx64m",
                "-cp", System.getProperty("java.class.path"), ChannelChild.class.getName(), mode);
    }

    private static List<String> shell(String script, List<String> command) {
        List<String> wrapped = new ArrayList<>(List.of(SubprocessProtocolChannel.SHELL, "-c", script, "test"));
        wrapped.addAll(command);
        return wrapped;
    }

    /** Both of a child's streams, read to their ends at the same time so neither pipe fills. */
    private static final class Output {
        final List<String> stdout;
        final List<String> stderr;

        private Output(List<String> stdout, List<String> stderr) {
            this.stdout = stdout;
            this.stderr = stderr;
        }

        static Output of(Process process) throws Exception {
            CompletableFuture<List<String>> stderr = CompletableFuture.supplyAsync(() -> lines(process.getErrorStream()));
            List<String> stdout = lines(process.getInputStream());
            return new Output(stdout, stderr.get(60, TimeUnit.SECONDS));
        }

        private static List<String> lines(InputStream stream) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.toList());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * Writes protocol the way a Kompile child does. {@code channel}: through {@link SubprocessProtocolChannel#open},
     * while another thread writes native-style text without newlines straight to fd 1. {@code channel-quiet}: the
     * same without the native text. {@code legacy}: to its original stdout, as a child built before the channel.
     */
    public static final class ChannelChild {

        private ChannelChild() {
        }

        public static void main(String[] args) throws Exception {
            String mode = args[0];
            PrintStream protocol = mode.startsWith("channel") ? SubprocessProtocolChannel.open(System.out) : System.out;
            System.setOut(System.err);

            AtomicBoolean done = new AtomicBoolean();
            Thread nativeWriter = new Thread(() -> {
                FileOutputStream fd1 = new FileOutputStream(FileDescriptor.out);
                byte[] text = "[native] no newline ".getBytes(StandardCharsets.UTF_8);
                try {
                    do {
                        fd1.write(text);
                    } while (!done.get());
                    fd1.write('\n');
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            if (mode.equals("channel")) {
                nativeWriter.start();
            }
            for (String line : expectedProtocol()) {
                protocol.println(line);
            }
            protocol.flush();
            done.set(true);
            if (mode.equals("channel")) {
                nativeWriter.join();
            }
            if (mode.startsWith("channel")) {
                // a log line that quotes the protocol; once the channel is open it is only text
                System.err.println(PREFIX + "{\"on\":\"stderr\"}");
                System.err.flush();
            }
        }
    }
}
