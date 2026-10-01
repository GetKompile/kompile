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

package ai.kompile.cli.main.project;

import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link ProjectAutoDetection#autoInitRoot(Path)}: where a manifest may be created
 * without an explicit {@code kompile project init}.
 */
@TemporaryUserHome
class ProjectAutoDetectionTest {

    @TempDir
    Path tempDir;

    private static Path home() throws Exception {
        return Path.of(System.getProperty("user.home")).toRealPath();
    }

    @Test
    void homeDirectoryAndItsParentsAreNeverAutoInitialised() throws Exception {
        Path home = home();
        assertEquals(Optional.empty(), ProjectAutoDetection.autoInitRoot(home));
        assertEquals(Optional.empty(), ProjectAutoDetection.autoInitRoot(home.getParent()));
    }

    @Test
    void subdirectoryOfACheckoutResolvesToTheCheckoutTop() throws Exception {
        Path checkout = tempDir.resolve("checkout");
        Path nested = Files.createDirectories(checkout.resolve("module/src"));
        Files.createDirectories(checkout.resolve(".git"));

        assertEquals(Optional.of(checkout.toRealPath()), ProjectAutoDetection.autoInitRoot(nested));
        assertEquals(Optional.of(checkout.toRealPath()), ProjectAutoDetection.autoInitRoot(checkout));
    }

    @Test
    void gitFileMarksTheTopOfALinkedWorktree() throws Exception {
        Path worktree = tempDir.resolve("worktree");
        Path nested = Files.createDirectories(worktree.resolve("src"));
        Files.writeString(worktree.resolve(".git"), "gitdir: /elsewhere/.git/worktrees/worktree\n");

        assertEquals(Optional.of(worktree.toRealPath()), ProjectAutoDetection.autoInitRoot(nested));
    }

    @Test
    void directoryOutsideAnyCheckoutIsItsOwnRoot() throws Exception {
        Path plain = Files.createDirectories(tempDir.resolve("plain/dir"));

        assertEquals(Optional.of(plain.toRealPath()), ProjectAutoDetection.autoInitRoot(plain));
    }

    @Test
    void checkoutThatContainsTheHomeDirectoryIsNeverAutoInitialised() throws Exception {
        Path home = home();
        Path nested = Files.createDirectories(home.resolve("projects/notes"));
        Files.createDirectories(home.resolve(".git"));
        try {
            assertEquals(Optional.empty(), ProjectAutoDetection.autoInitRoot(nested),
                    "a dotfiles checkout at the home directory is not a project root");
        } finally {
            Files.delete(home.resolve(".git"));
        }
    }
}
