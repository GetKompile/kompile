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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gives a subprocess's stdio protocol a pipe that nothing else writes to.
 *
 * <p>A Kompile child writes its protocol lines ({@code PREFIX{json}}) to its original stdout and
 * sends {@code System.out} to stderr. Native code writes straight to fd 1 beneath that redirect:
 * libnd4j's logger ({@code printf} + {@code fflush}), oneDNN and cuDNN logging, HotSpot's own
 * notices. Its text lands on the protocol pipe, in front of a protocol line or inside one that takes
 * more than one pipe write, and the parent loses the message.</p>
 *
 * <p>{@link #apply} (parent) starts the child as {@code /bin/sh -c 'exec "$@" 3>&1 1>&2'}: the child
 * keeps the PID the parent was given, has the stdout pipe on fd 3 and the stderr pipe on fds 1 and 2,
 * and finds the protocol fd in {@link #ENV_PROTOCOL_FD}. {@link #open} (child) returns a stream on
 * that fd. Anything else written to fd 1 reaches the parent's stderr reader as log text.</p>
 *
 * <p>A child built before this class, or one that could not open the channel, still writes its
 * protocol to fd 1, which now reaches the parent's stderr. {@link StderrProtocol} finds those lines
 * for the stderr reader until the child reports ({@link #CHANNEL_OPEN_NOTICE}) that it writes to the
 * channel.</p>
 */
public final class SubprocessProtocolChannel {

    /** Names the child's protocol fd. Set only for a child {@link #apply} wrapped. */
    public static final String ENV_PROTOCOL_FD = "KOMPILE_SUBPROCESS_PROTOCOL_FD";

    /** A child prints this on stderr once its protocol goes to the channel. */
    public static final String CHANNEL_OPEN_NOTICE = "[kompile-subprocess] protocol channel open on fd ";

    static final int PROTOCOL_FD = 3;

    static final String SHELL = "/bin/sh";

    /**
     * Copies the stdout pipe to fd 3 and the stderr pipe onto fd 1, then runs the command in the
     * shell's place, so the child keeps the shell's PID. The redirections belong to the exec'd
     * command: after a bare {@code exec 3>&1}, some shells leave fd 3 close-on-exec.
     */
    static final String WRAPPER_SCRIPT = "exec \"$@\" " + PROTOCOL_FD + ">&1 1>&2";

    /** {@code $0} of the wrapping shell, which names it in the shell's own error messages. */
    static final String WRAPPER_NAME = "kompile-subprocess";

    private SubprocessProtocolChannel() {
    }

    // ── parent side ───────────────────────────────────────────────────────────

    /**
     * Rewrites {@code pb} to run its command behind the protocol-channel wrapper. Call it right
     * before {@code pb.start()}, once the command and environment are final.
     *
     * <p>The command is left as it is when the wrapper can't be used: on Windows, without
     * {@code /bin/sh} or an fd directory, when stdout and stderr are not two separate pipes, or when
     * the command does not start with the absolute path of an executable file. The wrapping shell
     * would turn a missing executable into exit 127 instead of the {@code IOException} from
     * {@code start()}.</p>
     *
     * @return whether the child was wrapped, which is the only case in which its protocol can arrive
     *         on stderr
     */
    public static boolean apply(ProcessBuilder pb) {
        Map<String, String> env = pb.environment();
        if (!canWrap(pb)) {
            // An inherited value would name an fd this child doesn't have
            env.remove(ENV_PROTOCOL_FD);
            return false;
        }
        List<String> command = new ArrayList<>(pb.command().size() + 4);
        command.add(SHELL);
        command.add("-c");
        command.add(WRAPPER_SCRIPT);
        command.add(WRAPPER_NAME);
        command.addAll(pb.command());
        pb.command(command);
        env.put(ENV_PROTOCOL_FD, Integer.toString(PROTOCOL_FD));
        return true;
    }

    static boolean canWrap(ProcessBuilder pb) {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            return false;
        }
        if (pb.redirectErrorStream()
                || pb.redirectOutput().type() != ProcessBuilder.Redirect.Type.PIPE
                || pb.redirectError().type() != ProcessBuilder.Redirect.Type.PIPE) {
            return false;
        }
        List<String> command = pb.command();
        if (command.isEmpty() || command.get(0) == null) {
            return false;
        }
        try {
            Path executable = Path.of(command.get(0));
            return executable.isAbsolute()
                    && Files.isRegularFile(executable)
                    && Files.isExecutable(executable)
                    && Files.isExecutable(Path.of(SHELL))
                    && fdDirectory() != null;
        } catch (RuntimeException e) {
            // An unparseable path, or a security manager refusing the check
            return false;
        }
    }

    /**
     * Reads the protocol lines a wrapped child writes to fd 1, for that child's stderr reader.
     *
     * @param wrapped   what {@link #apply} returned for the child
     * @param prefix    the child's protocol prefix
     * @param childName names the child in the warning logged when its protocol arrives on stderr
     */
    public static StderrProtocol stderrProtocol(boolean wrapped, String prefix, String childName) {
        return new StderrProtocol(wrapped && prefix != null && !prefix.isEmpty() ? prefix : null, childName);
    }

    /**
     * Finds protocol lines on the stderr of a wrapped child that writes its protocol to fd 1: a build
     * from before the channel, or one that could not open it. Once the child prints
     * {@link #CHANNEL_OPEN_NOTICE}, its protocol is on the channel and everything on stderr is log
     * text. Because the notice and the child's later output share the stderr pipe, no line the child
     * writes after opening the channel can be taken for protocol.
     *
     * <p>One per child, used by that child's stderr reader thread only.</p>
     */
    public static final class StderrProtocol {

        private static final Logger log = LoggerFactory.getLogger(StderrProtocol.class);

        private final String prefix;
        private final String childName;
        private boolean channelOpen;
        private boolean warned;

        private StderrProtocol(String prefix, String childName) {
            this.prefix = prefix;
            this.childName = childName;
        }

        /**
         * Where the protocol prefix starts in a stderr line, or {@code -1} when the line is log text.
         * Text in front of the prefix is other output that reached fd 1 before the line ended.
         */
        public int prefixIndex(String line) {
            if (prefix == null || channelOpen || line == null) {
                return -1;
            }
            if (line.contains(CHANNEL_OPEN_NOTICE)) {
                channelOpen = true;
                return -1;
            }
            int prefixAt = line.indexOf(prefix);
            if (prefixAt >= 0 && !warned) {
                warned = true;
                log.warn("[{}] protocol messages are arriving on stderr: the child writes them to stdout, not"
                        + " to the protocol channel (it is an older build, or it could not open the channel;"
                        + " its stderr says which). Reading them from stderr, where other output can split"
                        + " them.", childName);
            }
            return prefixAt;
        }
    }

    // ── child side ────────────────────────────────────────────────────────────

    /**
     * The stream a child writes its protocol lines to: the channel its launcher set up, or
     * {@code fallback} (the child's original {@code System.out}) when there is none. Call it before
     * redirecting {@code System.out}.
     *
     * <p>The channel is used only when fds 1 and 2 are one pipe and the named fd is another pipe,
     * the layout {@link #apply} creates. A value some other process passed on names an fd that fails
     * this check. Whether or not the channel is used is reported on stderr.</p>
     *
     * <p>Initializes no logging: a console appender started this early would bind to the real
     * stdout.</p>
     */
    public static PrintStream open(PrintStream fallback) {
        String value = System.getenv(ENV_PROTOCOL_FD);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int fd = Integer.parseInt(value.trim());
            Path dir = fdDirectory();
            if (fd <= 2 || dir == null) {
                throw new IllegalStateException("fd " + fd + " can't be opened as the protocol channel");
            }
            Path channel = dir.resolve(Integer.toString(fd));
            BasicFileAttributes attributes = Files.readAttributes(channel, BasicFileAttributes.class);
            Object channelKey = attributes.fileKey();
            Object outKey = Files.readAttributes(dir.resolve("1"), BasicFileAttributes.class).fileKey();
            Object errKey = Files.readAttributes(dir.resolve("2"), BasicFileAttributes.class).fileKey();
            if (!attributes.isOther() || channelKey == null || outKey == null
                    || !outKey.equals(errKey) || channelKey.equals(outKey)) {
                throw new IllegalStateException("fds 1, 2 and " + fd + " are not the layout the launcher sets up");
            }
            PrintStream stream = new PrintStream(new BufferedOutputStream(
                    new FileOutputStream(channel.toFile(), true), 1 << 16), true, stdoutCharset());
            System.err.println(CHANNEL_OPEN_NOTICE + fd);
            System.err.flush();
            return stream;
        } catch (Exception e) {
            System.err.println("[kompile-subprocess] protocol channel unavailable, protocol goes to stdout: " + e);
            System.err.flush();
            return fallback;
        }
    }

    /** Where this process's open fds appear as files: {@code /proc/self/fd} on Linux, {@code /dev/fd} elsewhere. */
    static Path fdDirectory() {
        for (String candidate : new String[]{"/proc/self/fd", "/dev/fd"}) {
            Path dir = Path.of(candidate);
            if (Files.isDirectory(dir)) {
                return dir;
            }
        }
        return null;
    }

    /** The charset the JVM gave {@code System.out}, so protocol text is encoded as it was before. */
    static Charset stdoutCharset() {
        for (String property : new String[]{"stdout.encoding", "sun.stdout.encoding"}) {
            String name = System.getProperty(property);
            if (name != null && !name.isBlank()) {
                try {
                    return Charset.forName(name.trim());
                } catch (RuntimeException e) {
                    // System.out falls back to the default charset for a name it can't use
                }
            }
        }
        return Charset.defaultCharset();
    }
}
