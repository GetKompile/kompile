/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.services.subprocess;

import ai.kompile.app.learning.subprocess.LearningSubprocessProgressReporter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Stands in for the learning child in {@link LearningSubprocessLauncherVerdictTest}. The launcher
 * starts it with its real command line and args file; the {@code fake.learning.scenario} system
 * property picks what it does. It reports through the real {@link LearningSubprocessProgressReporter}.
 */
public final class FakeLearningChild {

    static final String SCENARIO_PROPERTY = "fake.learning.scenario";
    static final String CHILD_FAILURE = "boom: real cause";
    /** What a completed KGE run writes to its output file. */
    static final String TRAINED_EMBEDDINGS =
            "{\"entities\":{\"a\":[0.5,0.5],\"b\":[1.0,0.0]},\"relations\":{\"R\":[0.0,1.0]}}";

    private static final long HEARTBEAT_MS = 100;
    private static final long UNTIL_KILLED_MS = 60_000;

    private FakeLearningChild() {
    }

    public static void main(String[] args) throws Exception {
        Map<?, ?> learningArgs = new ObjectMapper().readValue(Path.of(args[0]).toFile(), Map.class);
        boolean kge = String.valueOf(learningArgs.get("algorithm")).startsWith("KGE_");
        // A PSL or MEBN run is told where its caller keeps the weights; this child never writes there
        String output = (String) learningArgs.get(kge ? "outputEmbeddingsPath" : "outputWeightsFilePath");
        LearningSubprocessProgressReporter reporter = new LearningSubprocessProgressReporter(System.out);
        String scenario = System.getProperty(SCENARIO_PROPERTY, "complete");
        switch (scenario) {
            case "complete" -> {
                if (kge) {
                    Files.writeString(Path.of(output), TRAINED_EMBEDDINGS, StandardCharsets.UTF_8);
                    reporter.reportCompleted(0.25, output, 2, 1);
                } else {
                    reporter.reportCompleted(0.125, output, 3, 0);
                }
                // Gone the moment its report is written
                Runtime.getRuntime().halt(0);
            }
            case "fail" -> {
                reporter.reportFailed(CHILD_FAILURE);
                Runtime.getRuntime().halt(1);
            }
            case "fail-then-progress" -> {
                // Progress still in the pipe behind the child's own failure report
                reporter.reportFailed(CHILD_FAILURE);
                for (int epoch = 1; epoch <= 200; epoch++) {
                    reporter.reportProgress(epoch, 200, 0.5);
                }
                System.exit(1);
            }
            case "exit0-silent" -> System.exit(0);
            case "exit7" -> System.exit(7);
            case "oom" -> {
                reporter.reportProgress(1, 10, 0.5);
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
            case "complete-on-stop" -> {
                // Completes only while it is being stopped, once the launcher has given up on it. The
                // launcher closed the pipes when it stopped it, so the report goes nowhere; exiting with
                // 0 is what says it completed, where a stopped child that did not exits with 143
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        Files.writeString(Path.of(output), TRAINED_EMBEDDINGS, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    reporter.reportCompleted(0.25, output, 2, 1);
                    Runtime.getRuntime().halt(0);
                }));
                reporter.startHeartbeat(HEARTBEAT_MS);
                Thread.sleep(UNTIL_KILLED_MS);
                System.exit(0);
            }
            case "echo-warm-start" -> {
                // Trains nothing: its output is the warm start it was given, so the caller can read it back
                Object warmStart = learningArgs.get("warmStartEmbeddingsPath");
                if (warmStart == null) {
                    reporter.reportFailed("no warm start");
                    Runtime.getRuntime().halt(1);
                }
                Files.write(Path.of(output), Files.readAllBytes(Path.of((String) warmStart)));
                reporter.reportCompleted(0.25, output, 2, 1);
                Runtime.getRuntime().halt(0);
            }
            default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
        }
    }
}
