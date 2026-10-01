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

package ai.kompile.staging.subprocess;

import ai.kompile.app.subprocess.SubprocessProtocolChannel;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Stands in for the training child in {@link TrainingSubprocessLauncherVerdictTest}. The launcher
 * starts it with its real command line; the {@code scenario} option of the args file picks what it
 * does. Like {@link TrainingSubprocessMain}, it reports through the real
 * {@link TrainingSubprocessProgressReporter} on the protocol channel and sends {@code System.out} to
 * stderr. A {@code legacy-} scenario writes its reports to fd 1, as a child from before the channel did.
 */
public final class FakeTrainingChild {

    /** The report a {@code held-} scenario writes first; the test holds the launcher's reader on it. */
    static final String FIRST_REPORT = "first report";
    /** The report a {@code held-} scenario writes once that reader is held; it is unread when the child dies. */
    static final String LAST_REPORT = "last report";

    private static final long HEARTBEAT_MS = 100;
    private static final long UNTIL_KILLED_MS = 60_000;

    private FakeTrainingChild() {
    }

    public static void main(String[] args) throws Exception {
        TrainingSubprocessArgs trainingArgs = TrainingSubprocessArgs.readFromFile(Path.of(args[0]));
        String taskId = trainingArgs.taskId();
        String scenario = trainingArgs.getOption("scenario", "complete");
        PrintStream protocol = scenario.startsWith("legacy-")
                ? System.out
                : SubprocessProtocolChannel.open(System.out);
        System.setOut(System.err);
        TrainingSubprocessProgressReporter reporter = new TrainingSubprocessProgressReporter(taskId, protocol);
        switch (scenario) {
            case "complete" -> {
                reporter.reportCompleted(0.5, 0.6, 10, 1, "/tmp/out", Map.of());
                // Gone the moment its report is written
                Runtime.getRuntime().halt(0);
            }
            case "native-then-complete", "legacy-native-then-complete" -> {
                // libnd4j prints to fd 1 beneath System.setOut, with no newline
                writeToFd1("[native] no newline ");
                reporter.reportCompleted(0.5, 0.6, 10, 1, "/tmp/out", Map.of());
                Runtime.getRuntime().halt(0);
            }
            case "glued-then-complete" -> {
                // What a child started without the channel has in its report pipe: native text with no
                // newline, then a report on the same line
                protocol.print("[native] no newline ");
                reporter.reportCompleted(0.5, 0.6, 10, 1, "/tmp/out", Map.of());
                Runtime.getRuntime().halt(0);
            }
            case "glued-oom-then-crash" -> {
                // A native allocation failure with no newline in the report pipe, the report written after
                // it, then the crash
                protocol.print("Cannot allocate 1073741824 bytes on device 0 ");
                reporter.reportProgressImmediate(1, 0, 1, 0.5, 1e-4, "TRAINING", 0.1, 0.1, "step");
                Runtime.getRuntime().halt(134);
            }
            case "legacy-oom-then-crash" -> {
                writeToFd1("Cannot allocate 1073741824 bytes on device 0 ");
                reporter.reportProgressImmediate(1, 0, 1, 0.5, 1e-4, "TRAINING", 0.1, 0.1, "step");
                Runtime.getRuntime().halt(134);
            }
            case "quoted-report-then-exit0" -> {
                // A log line quoting a report, as a debug log of the child's own messages can
                ByteArrayOutputStream quoted = new ByteArrayOutputStream();
                new TrainingSubprocessProgressReporter(taskId, new PrintStream(quoted, true, StandardCharsets.UTF_8))
                        .reportCompleted(0.5, 0.6, 10, 1, "/tmp/out", Map.of());
                System.err.print("sent " + quoted.toString(StandardCharsets.UTF_8));
                System.err.flush();
                System.exit(0);
            }
            case "fail" -> {
                reporter.reportFailed("TRAINING", "boom: real cause", "java.lang.IllegalStateException", null);
                Runtime.getRuntime().halt(1);
            }
            case "fail-then-progress" -> {
                // Progress still in the pipe behind the child's own failure report
                reporter.reportFailed("TRAINING", "boom: real cause", "java.lang.IllegalStateException", null);
                for (int i = 1; i <= 200; i++) {
                    reporter.reportProgressImmediate(i * 50L, 0, 1, 0.5, 1e-4, "TRAINING", 0.5, 0.5, "late");
                }
                reporter.close();
                System.exit(1);
            }
            case "exit0-silent" -> System.exit(0);
            case "exit7" -> System.exit(7);
            case "oom" -> {
                reporter.reportProgressImmediate(1, 0, 1, 0.5, 1e-4, "TRAINING", 0.1, 0.1, "before oom");
                // Far past the launcher's -Xmx: -XX:+ExitOnOutOfMemoryError ends the JVM with exit code 3
                long[] hog = new long[1 << 30];
                System.out.println(hog.length);
                System.exit(0);
            }
            case "hang" -> {
                reporter.startHeartbeat(HEARTBEAT_MS);
                Thread.sleep(UNTIL_KILLED_MS);
                System.exit(0);
            }
            case "no-heartbeat" -> {
                Thread.sleep(UNTIL_KILLED_MS);
                System.exit(0);
            }
            case "progress-flood" -> {
                reporter.startHeartbeat(HEARTBEAT_MS);
                for (long step = 1; ; step++) {
                    reporter.reportProgressImmediate(step, 0, 1, 0.5, 1e-4, "TRAINING", 0.1, 0.1, "step");
                }
            }
            case "nonfinite" -> {
                writeReport(protocol, new TrainingSubprocessMessage.Progress(
                        taskId, 1, 0, 1, Double.NaN, 1e-4, "TRAINING", 0.1, 0.1, "nan"));
                reporter.startHeartbeat(HEARTBEAT_MS);
                Thread.sleep(UNTIL_KILLED_MS);
                System.exit(0);
            }
            case "held-then-last-report", "held-nonfinite-then-last-report" -> {
                // The test holds the launcher's reader on the first report, then lets the last one be
                // written; no heartbeat, so the pipe holds nothing else while the reader is held
                Path handshake = Path.of(trainingArgs.getOption("handshakeDir", ""));
                double loss = scenario.startsWith("held-nonfinite-") ? Double.NaN : 0.5;
                writeReport(protocol, new TrainingSubprocessMessage.Progress(
                        taskId, 1, 0, 1, loss, 1e-4, "TRAINING", 0.1, 0.1, FIRST_REPORT));
                awaitFile(handshake.resolve("go"));
                reporter.reportProgressImmediate(2, 0, 1, 0.5, 1e-4, "TRAINING", 0.2, 0.2, LAST_REPORT);
                Files.createFile(handshake.resolve("written"));
                Thread.sleep(UNTIL_KILLED_MS);
                System.exit(0);
            }
            default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
        }
    }

    /** Writes a report by hand, as the reporter refuses one with a non-finite metric. */
    private static void writeReport(PrintStream protocol, TrainingSubprocessMessage message) throws IOException {
        String json = new ObjectMapper().writerFor(TrainingSubprocessMessage.class).writeValueAsString(message);
        protocol.println(TrainingSubprocessMessage.MESSAGE_PREFIX + json);
        protocol.flush();
    }

    /** Waits for the test to create a file. */
    private static void awaitFile(Path file) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(UNTIL_KILLED_MS);
        while (!Files.exists(file)) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException(file + " never appeared");
            }
            Thread.sleep(10);
        }
    }

    /** Writes to fd 1 the way native code does, beneath System.setOut. Leaves fd 1 open. */
    private static void writeToFd1(String text) throws IOException {
        FileOutputStream fd1 = new FileOutputStream(FileDescriptor.out);
        fd1.write(text.getBytes(StandardCharsets.UTF_8));
        fd1.flush();
    }
}
