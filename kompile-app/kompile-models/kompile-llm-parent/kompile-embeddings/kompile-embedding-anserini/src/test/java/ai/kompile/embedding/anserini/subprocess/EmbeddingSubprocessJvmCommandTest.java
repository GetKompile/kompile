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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The parent system properties a JVM embedding child inherits. They follow the launcher's own
 * JavaCPP caps and ND4J environment values on the command line, and the child keeps the last
 * duplicate {@code -D}, so a forwarded parent value used to replace the launcher's: a parent
 * {@code maxbytes} lifted the child's off-heap cap.
 *
 * <p>No ND4J: the JVM branch of {@code buildCommand()} reads the parent's ND4J environment, so these
 * tests call the forwarding step directly.
 */
@DisplayName("EmbeddingSubprocessLauncher JVM command")
class EmbeddingSubprocessJvmCommandTest {

    @Test
    @DisplayName("a parent property the launcher already set is not forwarded, so the launcher's value stands")
    void launcherValuesAreNotReplacedByParentProperties() {
        Properties parent = new Properties();
        parent.setProperty("org.bytedeco.javacpp.maxbytes", "64g");
        parent.setProperty("org.bytedeco.javacpp.maxphysicalbytes", "128g");
        parent.setProperty("nd4j.environment.verbose", "true");
        parent.setProperty("org.bytedeco.javacpp.logger.debug", "true");
        parent.setProperty("org.bytedeco.javacpp.pathsFirst", "true");
        parent.setProperty("org.nd4j.linalg.defaultbackend", "cpu");
        List<String> command = List.of("java", "-Xmx4096m",
                "-Dorg.bytedeco.javacpp.maxbytes=16384m",
                "-Dorg.bytedeco.javacpp.maxphysicalbytes=60000m",
                "-XX:+UseG1GC",
                "-Dnd4j.environment.verbose=false",
                "-Dorg.bytedeco.javacpp.logger.debug=false");

        List<String> forwarded = EmbeddingSubprocessLauncher.forwardedParentProperties(parent, command);

        assertEquals(Set.of("-Dorg.bytedeco.javacpp.pathsFirst=true", "-Dorg.nd4j.linalg.defaultbackend=cpu"),
                new HashSet<>(forwarded));
        assertEquals(2, forwarded.size(), forwarded::toString);
    }

    @Test
    @DisplayName("only the exact key counts as set: a longer key with the same start is still forwarded")
    void onlyTheExactKeyIsLeftOut() {
        Properties parent = new Properties();
        parent.setProperty("org.bytedeco.javacpp.maxbytes.extra", "1");
        parent.setProperty("nd4j.flag", "on");

        List<String> forwarded = EmbeddingSubprocessLauncher.forwardedParentProperties(parent,
                List.of("-Dorg.bytedeco.javacpp.maxbytes=16384m", "-Dnd4j.flag"));

        assertEquals(List.of("-Dorg.bytedeco.javacpp.maxbytes.extra=1"), forwarded);
    }

    @Test
    @DisplayName("only ND4J, JavaCPP and native-library properties with a value are forwarded")
    void onlyNonBlankPropertiesUnderAForwardedPrefixAreForwarded() {
        Properties parent = new Properties();
        parent.setProperty("cuda.cache", "x");
        parent.setProperty("java.io.tmpdir", "/parent/tmp");
        parent.setProperty("user.home", "/home/parent");
        parent.setProperty("org.nd4j.blank", " ");

        assertEquals(List.of("-Dcuda.cache=x"),
                EmbeddingSubprocessLauncher.forwardedParentProperties(parent, List.of("java")));
    }
}
