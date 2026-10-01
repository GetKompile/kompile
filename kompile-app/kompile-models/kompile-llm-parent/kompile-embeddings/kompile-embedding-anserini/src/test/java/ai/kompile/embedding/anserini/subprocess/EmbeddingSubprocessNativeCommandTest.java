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

import ai.kompile.app.subprocess.SubprocessBackendFlags;
import ai.kompile.app.subprocess.SubprocessPlacement;
import ai.kompile.embedding.anserini.AnseriniEncoderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The command for a native (GraalVM) embedding child. A native binary accepts {@code -Xmx} and
 * {@code -D} but no HotSpot {@code -XX:} flags and no classpath. It used to get none of the runtime
 * flags, so it ran on its build-time defaults: no heap or JavaCPP caps, and no scheduler placement,
 * which on a multi-GPU box meant it ignored its assigned device and memory bound.
 *
 * <p>No ND4J and no process: the native branch of {@code buildCommand()} only assembles strings, and
 * the executable is a stub script that is never run.
 */
@DisplayName("EmbeddingSubprocessLauncher native command")
class EmbeddingSubprocessNativeCommandTest {

    private static final String DEVICE_PROP = "nd4j.placement.defaultDevice";
    private static final String MAXBYTES_PROP = "org.bytedeco.javacpp.maxbytes";
    private static final String DUMMY_KEY = "dummy-key-for-test";

    @TempDir
    Path tempDir;

    private Path executable;
    private String savedDevice;
    private String savedMaxBytes;
    private String savedStagingUrl;
    private String savedStagingKey;
    private int savedRetryPollSeconds;

    @BeforeEach
    void setUp() throws IOException {
        executable = tempDir.resolve("kompile-native");
        Files.writeString(executable, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwxr-xr-x"));
        savedDevice = System.getProperty(DEVICE_PROP);
        savedMaxBytes = System.getProperty(MAXBYTES_PROP);
        savedStagingUrl = AnseriniEncoderFactory.getStagingUrl();
        savedStagingKey = AnseriniEncoderFactory.getStagingApiKey();
        savedRetryPollSeconds = AnseriniEncoderFactory.getStagingRetryPollIntervalSeconds();
    }

    @AfterEach
    void tearDown() {
        restore(DEVICE_PROP, savedDevice);
        restore(MAXBYTES_PROP, savedMaxBytes);
        AnseriniEncoderFactory.configureStagingService(savedStagingUrl, savedStagingKey, savedRetryPollSeconds);
    }

    private EmbeddingSubprocessLauncher nativeLauncher(boolean localModelOnly) {
        EmbeddingSubprocessLauncher launcher = EmbeddingSubprocessLauncher.builder()
                .launchMode(EmbeddingSubprocessLauncher.LaunchMode.NATIVE_EXECUTABLE)
                .nativeExecutablePath(executable.toString())
                .classpath(List.of("unused-in-native-mode"))
                .maxHeapMb(4096)
                .localModelOnly(localModelOnly)
                .build();
        launcher.applyPlacement(SubprocessPlacement.gpu(1, 6L << 30));
        return launcher;
    }

    @Test
    @DisplayName("carries the heap, the JavaCPP caps and the placement, and no -XX: flag or classpath")
    void nativeCommandCarriesRuntimeFlags() {
        List<String> command = nativeLauncher(true).buildCommand();

        assertEquals(executable.toAbsolutePath().toString(), command.get(0));
        assertTrue(command.contains("-Xmx4096m"), command::toString);
        assertEquals("16384m", lastValue(command, MAXBYTES_PROP), "maxbytes defaults to 4 x heap");
        assertNotNull(lastValue(command, "org.bytedeco.javacpp.maxphysicalbytes"), command::toString);
        assertTrue(command.contains("-Dorg.nd4j.gpu.priority=1000"), command::toString);
        assertEquals("1", lastValue(command, DEVICE_PROP));
        assertEquals(Long.toString(6L << 30),
                lastValue(command, SubprocessBackendFlags.MAX_DEVICE_MEMORY_PROPERTY));
        assertTrue(command.stream().noneMatch(arg -> arg.startsWith("-XX:")), command::toString);
        assertFalse(command.contains("-cp"), command::toString);
        assertEquals("--subprocess=embedding", command.get(command.size() - 1));
    }

    @Test
    @DisplayName("the placement and caps come after the forwarded parent properties, so they win")
    void launcherValuesWinOverForwardedParentProperties() {
        System.setProperty(DEVICE_PROP, "0");
        System.setProperty(MAXBYTES_PROP, "1g");

        List<String> command = nativeLauncher(true).buildCommand();

        // The parent's values are still forwarded; the child keeps the last duplicate -D.
        assertTrue(command.contains("-D" + DEVICE_PROP + "=0"), command::toString);
        assertTrue(command.contains("-D" + MAXBYTES_PROP + "=1g"), command::toString);
        assertEquals("1", lastValue(command, DEVICE_PROP));
        assertEquals("16384m", lastValue(command, MAXBYTES_PROP));
    }

    @Test
    @DisplayName("a managed child gets the staging URL on its command line, never the API key")
    void stagingKeyIsNeverOnTheCommandLine() {
        AnseriniEncoderFactory.configureStagingService("http://staging.test", DUMMY_KEY);
        EmbeddingSubprocessLauncher launcher = nativeLauncher(false);

        List<String> command = launcher.buildCommand();
        List<String> sourceFlags = launcher.modelSourceFlags();

        assertTrue(command.contains("-Dkompile.staging.url=http://staging.test"), command::toString);
        assertTrue(sourceFlags.contains("-Dkompile.staging.url=http://staging.test"), sourceFlags::toString);
        assertTrue(command.stream().noneMatch(arg -> arg.contains(DUMMY_KEY)),
                "the staging key must not be in the child's argv");
        assertTrue(sourceFlags.stream().noneMatch(arg -> arg.contains(DUMMY_KEY)),
                "the staging key must not be a model-source flag");
    }

    @Test
    @DisplayName("a local-only child gets no model-source flags")
    void localOnlyChildGetsNoModelSourceFlags() {
        AnseriniEncoderFactory.configureStagingService("http://staging.test", DUMMY_KEY);
        EmbeddingSubprocessLauncher launcher = nativeLauncher(true);

        assertEquals(List.of(), launcher.modelSourceFlags());
        assertTrue(launcher.buildCommand().stream().noneMatch(arg -> arg.startsWith("-Dkompile.staging.")));
    }

    /** The value of the last {@code -D<key>=} in {@code command}: the child keeps the last duplicate. */
    private static String lastValue(List<String> command, String key) {
        String prefix = "-D" + key + "=";
        String value = null;
        for (String arg : command) {
            if (arg.startsWith(prefix)) {
                value = arg.substring(prefix.length());
            }
        }
        return value;
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
