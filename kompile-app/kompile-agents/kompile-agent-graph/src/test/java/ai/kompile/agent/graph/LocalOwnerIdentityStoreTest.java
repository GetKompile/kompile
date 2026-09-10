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
package ai.kompile.agent.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LocalOwnerIdentityStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void survivesRestartWithStrictCanonicalEncoding() throws Exception {
        Path control = tempDir.resolve("control");
        LocalOwnerIdentity first = new LocalOwnerIdentityStore(control).loadOrCreate();
        LocalOwnerIdentity restarted = new LocalOwnerIdentityStore(control).loadOrCreate();

        assertEquals(first, restarted);
        assertEquals(first.ownerId() + "\n",
                Files.readString(control.resolve(LocalOwnerIdentityStore.IDENTITY_FILE_NAME)));
    }

    @Test
    void concurrentFirstStartupAcrossProcessesConvergesOnOneIdentity() throws Exception {
        Path control = tempDir.resolve("control");
        List<Process> processes = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            processes.add(startOwnerProcess(control));
        }

        Set<UUID> owners = new HashSet<>();
        for (Process process : processes) {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "identity subprocess timed out");
            String output = new String(process.getInputStream().readAllBytes());
            assertEquals(0, process.exitValue(), output);
            owners.add(extractOwner(output));
        }

        assertEquals(1, owners.size());
        assertEquals(owners.iterator().next(),
                new LocalOwnerIdentityStore(control).loadOrCreate().ownerId());
    }

    @Test
    void enforcesOwnerOnlyPermissionsWhenPosixIsSupported() throws Exception {
        assumeTrue(Files.getFileAttributeView(
                tempDir, PosixFileAttributeView.class) != null, "POSIX permissions are unavailable");
        Path control = tempDir.resolve("control");
        new LocalOwnerIdentityStore(control).loadOrCreate();

        assertEquals(PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(control));
        assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(control.resolve(LocalOwnerIdentityStore.IDENTITY_FILE_NAME)));
        assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(control.resolve(LocalOwnerIdentityStore.LOCK_FILE_NAME)));
    }

    @Test
    void rejectsSymlinkedControlPathsAndIdentityFiles() throws Exception {
        Path realControl = tempDir.resolve("real-control");
        Files.createDirectory(realControl);
        Path linkedControl = tempDir.resolve("linked-control");
        createSymlinkOrAbort(linkedControl, realControl);

        IOException rootFailure = assertThrows(
                IOException.class, () -> new LocalOwnerIdentityStore(linkedControl));
        assertTrue(rootFailure.getMessage().contains("Symbolic links")
                        || rootFailure.getMessage().contains("safe directory"),
                rootFailure::getMessage);

        Path control = tempDir.resolve("control");
        LocalOwnerIdentityStore store = new LocalOwnerIdentityStore(control);
        Path external = tempDir.resolve("external-owner.id");
        Files.writeString(external, UUID.randomUUID() + "\n");
        createSymlinkOrAbort(
                control.resolve(LocalOwnerIdentityStore.IDENTITY_FILE_NAME), external);

        IOException identityFailure = assertThrows(IOException.class, store::loadOrCreate);
        assertTrue(identityFailure.getMessage().contains("Symbolic links"));
    }

    @Test
    void allowsResolvedSystemSymlinksOutsideTheConfiguredControlDirectory() throws Exception {
        Path realParent = tempDir.resolve("real-parent");
        Path existingChild = realParent.resolve("existing-child");
        Files.createDirectories(existingChild);
        Path linkedParent = tempDir.resolve("linked-parent");
        createSymlinkOrAbort(linkedParent, realParent);

        LocalOwnerIdentity owner = new LocalOwnerIdentityStore(
                linkedParent.resolve("existing-child/control")).loadOrCreate();

        assertEquals(owner.ownerId() + "\n",
                Files.readString(existingChild.resolve("control/local-owner.id")));
    }

    @Test
    void rejectsMalformedAndNonCanonicalIdentityFiles() throws Exception {
        Path control = tempDir.resolve("control");
        LocalOwnerIdentityStore store = new LocalOwnerIdentityStore(control);
        Path identity = control.resolve(LocalOwnerIdentityStore.IDENTITY_FILE_NAME);
        UUID owner = UUID.fromString("abcdefab-cdef-4abc-8def-abcdefabcdef");

        Files.writeString(identity, "not-a-uuid\n", StandardOpenOption.CREATE_NEW);
        assertThrows(IOException.class, store::loadOrCreate);

        Files.writeString(identity, owner.toString().toUpperCase() + "\n",
                StandardOpenOption.TRUNCATE_EXISTING);
        IOException nonCanonical = assertThrows(IOException.class, store::loadOrCreate);
        assertTrue(nonCanonical.getMessage().contains("not canonical"));

        Files.writeString(identity, owner + "\n\n", StandardOpenOption.TRUNCATE_EXISTING);
        assertThrows(IOException.class, store::loadOrCreate);
    }

    @Test
    void failedPreMoveFsyncLeavesNoIdentityOrStagingFile() throws Exception {
        Path control = tempDir.resolve("control");
        LocalOwnerIdentityStore store = new LocalOwnerIdentityStore(
                control, ignored -> { throw new IOException("synthetic pre-move fsync failure"); });

        assertThrows(IOException.class, store::loadOrCreate);

        assertFalse(Files.exists(control.resolve(LocalOwnerIdentityStore.IDENTITY_FILE_NAME)));
        assertNoStagingFiles(control);
    }

    @Test
    void refusesNonAtomicPublicationAndCleansTheStagedFile() throws Exception {
        Path control = tempDir.resolve("control");
        LocalOwnerIdentityStore store = new LocalOwnerIdentityStore(
                control,
                LocalOwnerIdentityStore::forceDirectory,
                (source, target) -> {
                    throw new AtomicMoveNotSupportedException(
                            source.toString(), target.toString(), "synthetic unsupported move");
                });

        IOException failure = assertThrows(IOException.class, store::loadOrCreate);

        assertTrue(failure.getMessage().contains("Atomic publication is required"));
        assertFalse(Files.exists(control.resolve(LocalOwnerIdentityStore.IDENTITY_FILE_NAME)));
        assertNoStagingFiles(control);
    }

    @Test
    void postMoveFsyncFailurePublishesOnlyACompleteRestartReadableIdentity() throws Exception {
        Path control = tempDir.resolve("control");
        AtomicInteger calls = new AtomicInteger();
        LocalOwnerIdentityStore store = new LocalOwnerIdentityStore(control, directory -> {
            LocalOwnerIdentityStore.forceDirectory(directory);
            if (calls.incrementAndGet() == 2) {
                throw new IOException("synthetic post-move fsync failure");
            }
        });

        IOException failure = assertThrows(IOException.class, store::loadOrCreate);

        assertTrue(failure.getMessage().contains("may have been published"));
        LocalOwnerIdentity recovered = new LocalOwnerIdentityStore(control).loadOrCreate();
        assertEquals(recovered.ownerId() + "\n",
                Files.readString(control.resolve(LocalOwnerIdentityStore.IDENTITY_FILE_NAME)));
        assertNoStagingFiles(control);
    }

    private Process startOwnerProcess(Path control) throws IOException {
        String executable = Path.of(
                System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java")
                .toString();
        return new ProcessBuilder(
                executable,
                "-cp",
                System.getProperty("java.class.path"),
                LocalOwnerIdentityProcessMain.class.getName(),
                control.toString())
                .redirectErrorStream(true)
                .start();
    }

    private static UUID extractOwner(String output) {
        return output.lines()
                .filter(line -> line.startsWith("OWNER="))
                .map(line -> UUID.fromString(line.substring("OWNER=".length())))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing owner identity in output: " + output));
    }

    private static void assertNoStagingFiles(Path control) throws IOException {
        try (var files = Files.list(control)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".staging")));
        }
    }

    private static void createSymlinkOrAbort(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException unsupported) {
            assumeTrue(false, "symbolic links are unavailable");
        }
    }
}
