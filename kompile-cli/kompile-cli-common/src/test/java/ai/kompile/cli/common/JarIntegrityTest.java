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
package ai.kompile.cli.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarIntegrityTest {

    @TempDir
    Path tempDir;

    @Test
    void validEmptyZipIsUsable() throws Exception {
        Path jar = tempDir.resolve("valid.jar");
        // Local file header immediately followed by a terminal EOCD record (valid empty zip).
        write(jar, new byte[]{0x50, 0x4B, 0x03, 0x04,
                0x50, 0x4B, 0x05, 0x06, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
        assertNull(JarIntegrity.describeProblem(jar.toFile()));
        assertTrue(JarIntegrity.isUsableJar(jar.toFile()));
    }

    @Test
    void truncatedZipWithNoCentralDirectoryIsReported() throws Exception {
        Path jar = tempDir.resolve("truncated.jar");
        // The production failure signature: PK header, data, then EOF (disk-full copy).
        write(jar, new byte[]{0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00, 1, 2, 3, 4});
        String problem = JarIntegrity.describeProblem(jar.toFile());
        assertTrue(problem != null && problem.contains("truncated zip"), problem);
        assertFalse(JarIntegrity.isUsableJar(jar.toFile()));
    }

    @Test
    void emptyJarIsReported() throws Exception {
        Path jar = tempDir.resolve("empty.jar");
        write(jar, new byte[0]);
        String problem = JarIntegrity.describeProblem(jar.toFile());
        assertTrue(problem != null && problem.contains("empty"), problem);
    }

    @Test
    void textPlaceholderJarIsUsable() throws Exception {
        // Test fixtures (and foreign payloads) are not the integrity probe's concern.
        Path jar = tempDir.resolve("placeholder.jar");
        write(jar, "test".getBytes());
        assertNull(JarIntegrity.describeProblem(jar.toFile()));
    }

    @Test
    void zipCommentIsTolerated() throws Exception {
        // EOCD search window must cover record + up-to-64KiB comment.
        Path jar = tempDir.resolve("commented.jar");
        byte[] payload = new byte[]{0x50, 0x4B, 0x03, 0x04, 0x00, 0x00};
        byte[] comment = new byte[900];
        comment[comment.length - 4] = 0x50; // decoy PK prefix inside the comment window
        comment[comment.length - 3] = 0x4B;
        byte[] eocd = new byte[]{0x50, 0x4B, 0x05, 0x06, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        byte[] all = new byte[payload.length + comment.length + eocd.length];
        System.arraycopy(payload, 0, all, 0, payload.length);
        System.arraycopy(comment, 0, all, payload.length, comment.length);
        System.arraycopy(eocd, 0, all, payload.length + comment.length, eocd.length);
        write(jar, all);
        assertNull(JarIntegrity.describeProblem(jar.toFile()));
    }

    @Test
    void missingOrNonJarFilesAreNotOurConcern() {
        assertNull(JarIntegrity.describeProblem(null));
        assertNull(JarIntegrity.describeProblem(tempDir.resolve("absent.jar").toFile()));
        assertNull(JarIntegrity.describeProblem(tempDir.resolve("kompile-chat").toFile()));
    }

    private static void write(Path target, byte[] bytes) throws Exception {
        try (FileOutputStream out = new FileOutputStream(target.toFile())) {
            out.write(bytes);
        }
    }

    @Test
    void probesAreCheapForMultiGigabyteJars() throws Exception {
        // Sparse large file: only head/tail are touched, so this stays fast.
        Path jar = tempDir.resolve("large.jar");
        try (var ch = java.nio.channels.FileChannel.open(jar,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
            ch.write(java.nio.ByteBuffer.wrap(new byte[]{0x50, 0x4B, 0x03, 0x04}));
            ch.position(5L * 1024 * 1024 * 1024);
            ch.write(java.nio.ByteBuffer.wrap(new byte[]{
                    0x50, 0x4B, 0x05, 0x06, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}));
        }
        long start = System.nanoTime();
        assertNull(JarIntegrity.describeProblem(jar.toFile()));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 2000, "probe took " + elapsedMs + "ms; head/tail read regressed");
        assertEquals(5L * 1024 * 1024 * 1024 + 22, Files.size(jar));
    }
}
