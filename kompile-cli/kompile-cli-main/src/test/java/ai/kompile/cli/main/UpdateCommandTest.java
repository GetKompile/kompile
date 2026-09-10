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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main;

import ai.kompile.cli.common.util.JsonUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCommandTest {

    @Test
    void rootRegistersUpdateCommand() {
        assertTrue(new CommandLine(new MainCommand()).getSubcommands().containsKey("update"));
    }

    @Test
    void updateHelpDoesNotResolveOrMutateAnything(@TempDir Path tempDir) {
        AtomicBoolean resolved = new AtomicBoolean();
        UpdateCommand command = command(tempDir, "linux-x86_64", Map.of(),
                (installed, version, baseUrl, includePrerelease) -> {
                    resolved.set(true);
                    return artifact("2.0.0", "full-linux-x86_64", "zip");
                },
                () -> tempDir.resolve("installer.sh"),
                (arguments, environment) -> 0,
                new StringWriter(), new StringWriter());
        StringWriter usage = new StringWriter();
        CommandLine commandLine = new CommandLine(command);
        commandLine.setOut(new PrintWriter(usage, true));

        assertEquals(0, commandLine.execute("--help"));
        assertFalse(resolved.get());
        assertTrue(usage.toString().contains("-h, --help"));
        assertTrue(usage.toString().contains("--version=VERSION"));
    }

    @Test
    void readsAuthoritativeDistributionMetadata(@TempDir Path tempDir) throws Exception {
        writeMetadata(tempDir, "1.2.3", "cuda", "linux-x86_64",
                "cuda-12.9", "cuda-linux-x86_64-cuda-12.9");

        UpdateCommand.InstalledDistribution installed = UpdateCommand.readInstalledDistribution(
                tempDir, "linux-x86_64", JsonUtils.standardMapper());

        assertEquals("1.2.3", installed.version());
        assertEquals("cuda", installed.variant());
        assertEquals("cuda-12.9", installed.backendProfile());
        assertEquals("cuda-linux-x86_64-cuda-12.9", installed.distributionClassifier());
    }

    @Test
    void refusesMissingOrCrossPlatformMetadata(@TempDir Path tempDir) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> UpdateCommand.readInstalledDistribution(
                tempDir, "linux-x86_64", JsonUtils.standardMapper()));

        writeMetadata(tempDir, "1.2.3", "full", "windows-x86_64",
                "none", "full-windows-x86_64");
        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
                () -> UpdateCommand.readInstalledDistribution(
                        tempDir, "linux-x86_64", JsonUtils.standardMapper()));
        assertTrue(mismatch.getMessage().contains("does not match this machine"));
    }

    @Test
    void refusesUnmanagedRuntimeEvenWhenAnotherInstallExists(@TempDir Path tempDir) throws Exception {
        writeMetadata(tempDir, "1.0.0", "full", "linux-x86_64",
                "none", "full-linux-x86_64");
        AtomicBoolean resolved = new AtomicBoolean();
        StringWriter stderr = new StringWriter();
        UpdateCommand command = new UpdateCommand(
                () -> tempDir,
                () -> "linux-x86_64",
                () -> false,
                Map.of(),
                (installed, version, baseUrl, includePrerelease) -> {
                    resolved.set(true);
                    return artifact("2.0.0", installed.distributionClassifier(), "zip");
                },
                () -> tempDir.resolve("installer.sh"),
                (arguments, environment) -> 0,
                JsonUtils.standardMapper(),
                new PrintWriter(new StringWriter(), true),
                new PrintWriter(stderr, true));

        assertEquals(3, new CommandLine(command).execute("--check"));
        assertFalse(resolved.get());
        assertTrue(stderr.toString().contains("does not belong to a managed distribution"));
    }

    @Test
    void runtimeDistributionRootWinsOnlyWithoutExplicitOverride(@TempDir Path tempDir) {
        Path configured = tempDir.resolve("default-home");
        Path runtime = tempDir.resolve("manual distribution");

        assertEquals(runtime, UpdateCommand.selectInstallRoot(configured, false, runtime));
        assertEquals(configured, UpdateCommand.selectInstallRoot(configured, true, runtime));
        assertEquals(configured, UpdateCommand.selectInstallRoot(configured, false, null));
    }

    @Test
    void checkNeverMaterializesOrLaunchesInstaller(@TempDir Path tempDir) throws Exception {
        writeMetadata(tempDir, "1.0.0", "full", "linux-x86_64",
                "none", "full-linux-x86_64");
        AtomicBoolean materialized = new AtomicBoolean();
        AtomicBoolean launched = new AtomicBoolean();
        StringWriter stdout = new StringWriter();
        UpdateCommand command = command(tempDir, "linux-x86_64", Map.of(),
                (installed, version, baseUrl, includePrerelease) ->
                        artifact("2.0.0", "full-linux-x86_64", "zip"),
                () -> {
                    materialized.set(true);
                    return tempDir.resolve("installer.sh");
                },
                (arguments, environment) -> {
                    launched.set(true);
                    return 0;
                },
                stdout, new StringWriter());

        int exitCode = new CommandLine(command).execute("--check");

        assertEquals(0, exitCode);
        assertFalse(materialized.get());
        assertFalse(launched.get());
        assertTrue(stdout.toString().contains("Update available: 1.0.0 -> 2.0.0"));
    }

    @Test
    void updatePreservesLaneAndSanitizesInstallerEnvironment(@TempDir Path tempDir) throws Exception {
        writeMetadata(tempDir, "1.0.0", "cuda", "linux-x86_64",
                "cuda-12.9", "cuda-linux-x86_64-cuda-12.9");
        Path installer = Files.writeString(tempDir.resolve("installer.sh"), "#!/bin/sh\n");
        AtomicReference<List<String>> launchedCommand = new AtomicReference<>();
        AtomicReference<Map<String, String>> launchedEnvironment = new AtomicReference<>();
        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", "/usr/bin");
        environment.put("KOMPILE_VARIANT", "full");
        environment.put("KOMPILE_BACKEND_PROFILE", "cpu");
        environment.put("KOMPILE_DEV", "1");
        environment.put("KOMPILE_MODIFY_PATH", "1");
        environment.put("KOMPILE_UPDATE_TEST_FAIL_AFTER", "1");
        environment.put("KOMPILE_BASH", "/bin/bash");

        UpdateCommand command = command(tempDir, "linux-x86_64", environment,
                (installed, version, baseUrl, includePrerelease) -> artifact("2.0.0",
                        installed.distributionClassifier(), "zip"),
                () -> installer,
                (arguments, childEnvironment) -> {
                    launchedCommand.set(arguments);
                    launchedEnvironment.set(childEnvironment);
                    return 0;
                },
                new StringWriter(), new StringWriter());

        int exitCode = new CommandLine(command).execute("--version", "2.0.0", "--verbose");

        assertEquals(0, exitCode);
        List<String> arguments = launchedCommand.get();
        assertEquals("/bin/bash", arguments.get(0));
        assertContainsPair(arguments, "--variant", "cuda");
        assertContainsPair(arguments, "--backend-profile", "cuda-12.9");
        assertContainsPair(arguments, "--distribution-classifier", "cuda-linux-x86_64-cuda-12.9");
        assertContainsPair(arguments, "--dir", tempDir.toAbsolutePath().normalize().toString());
        assertContainsPair(arguments, "--version", "2.0.0");
        assertTrue(arguments.contains("--update"));
        assertTrue(arguments.contains("--verbose"));

        Map<String, String> childEnvironment = launchedEnvironment.get();
        assertEquals("/usr/bin", childEnvironment.get("PATH"));
        assertEquals("0", childEnvironment.get("KOMPILE_DEV"));
        assertEquals("0", childEnvironment.get("KOMPILE_MODIFY_PATH"));
        assertEquals(tempDir.toAbsolutePath().normalize().toString(),
                childEnvironment.get("KOMPILE_INSTALL_DIR"));
        assertFalse(childEnvironment.containsKey("KOMPILE_VARIANT"));
        assertFalse(childEnvironment.containsKey("KOMPILE_BACKEND_PROFILE"));
        assertFalse(childEnvironment.containsKey("KOMPILE_UPDATE_TEST_FAIL_AFTER"));
    }

    @Test
    void windowsApplyFailsClosedWithoutLaunchingInstaller(@TempDir Path tempDir) throws Exception {
        writeMetadata(tempDir, "1.0.0", "cli-only", "windows-x86_64",
                "none", "cli-only-windows-x86_64");
        AtomicBoolean launched = new AtomicBoolean();
        StringWriter stderr = new StringWriter();
        UpdateCommand command = new UpdateCommand(
                () -> tempDir,
                () -> "windows-x86_64",
                () -> true,
                Map.of(),
                (installed, version, baseUrl, includePrerelease) -> artifact("2.0.0",
                        installed.distributionClassifier(), "zip"),
                () -> tempDir.resolve("installer.sh"),
                (arguments, environment) -> {
                    launched.set(true);
                    return 0;
                },
                JsonUtils.standardMapper(),
                new PrintWriter(new StringWriter(), true),
                new PrintWriter(stderr, true));

        assertEquals(3, new CommandLine(command).execute("--version", "2.0.0"));
        assertFalse(launched.get());
        assertTrue(stderr.toString().contains("Windows can check for updates"));
    }

    @Test
    void customBaseRequiresAnExplicitVersion(@TempDir Path tempDir) throws Exception {
        writeMetadata(tempDir, "1.0.0", "full", "linux-x86_64",
                "none", "full-linux-x86_64");
        AtomicBoolean resolved = new AtomicBoolean();
        StringWriter stderr = new StringWriter();
        UpdateCommand command = command(tempDir, "linux-x86_64", Map.of(),
                (installed, version, baseUrl, includePrerelease) -> {
                    resolved.set(true);
                    return artifact("2.0.0", installed.distributionClassifier(), "zip");
                },
                () -> tempDir.resolve("installer.sh"),
                (arguments, environment) -> 0,
                new StringWriter(), stderr);

        assertEquals(2, new CommandLine(command).execute("--url", tempDir.toUri().toString()));
        assertFalse(resolved.get());
        assertTrue(stderr.toString().contains("requires --version"));
    }

    @Test
    void githubResolverSkipsNonProductReleaseAndUsesChecksummedZipFallback(@TempDir Path tempDir)
            throws Exception {
        UpdateCommand.InstalledDistribution installed = new UpdateCommand.InstalledDistribution(
                tempDir, "1.0.0", "full", "linux-x86_64", "none", "full-linux-x86_64");
        String response = """
                [
                  {"tag_name":"opennlp","draft":false,"assets":[
                    {"name":"model.sdz","browser_download_url":"https://example.test/model.sdz"}
                  ]},
                  {"tag_name":"v2.0.0","draft":false,"prerelease":false,"assets":[
                    {"name":"kompile-dist-2.0.0-full-linux-x86_64.zip",
                     "browser_download_url":"https://example.test/kompile.zip"},
                    {"name":"kompile-dist-2.0.0-full-linux-x86_64.zip.sha256",
                     "browser_download_url":"https://example.test/kompile.zip.sha256"}
                  ]}
                ]
                """;
        UpdateCommand.DefaultReleaseResolver resolver = new UpdateCommand.DefaultReleaseResolver(
                uri -> response, JsonUtils.standardMapper());

        UpdateCommand.ReleaseArtifact artifact = resolver.resolve(installed, null, null, true);

        assertEquals("2.0.0", artifact.version());
        assertTrue(artifact.archiveName().endsWith(".zip"));
        assertEquals("https://example.test/kompile.zip", artifact.archiveUri().toString());
    }

    @Test
    void githubResolverRequiresPrereleaseOptIn(@TempDir Path tempDir) throws Exception {
        UpdateCommand.InstalledDistribution installed = new UpdateCommand.InstalledDistribution(
                tempDir, "1.0.0", "full", "linux-x86_64", "none", "full-linux-x86_64");
        String response = """
                [{"tag_name":"v2.0.0","draft":false,"prerelease":true,"assets":[
                  {"name":"kompile-dist-2.0.0-full-linux-x86_64.zip",
                   "browser_download_url":"https://example.test/kompile.zip"},
                  {"name":"kompile-dist-2.0.0-full-linux-x86_64.zip.sha256",
                   "browser_download_url":"https://example.test/kompile.zip.sha256"}
                ]}]
                """;
        UpdateCommand.DefaultReleaseResolver resolver = new UpdateCommand.DefaultReleaseResolver(
                uri -> response, JsonUtils.standardMapper());

        assertThrows(IOException.class, () -> resolver.resolve(installed, null, null, false));
        assertEquals("2.0.0", resolver.resolve(installed, null, null, true).version());
    }

    @Test
    void implicitUpdateNeverDowngrades(@TempDir Path tempDir) throws Exception {
        writeMetadata(tempDir, "3.0.0", "full", "linux-x86_64",
                "none", "full-linux-x86_64");
        AtomicBoolean materialized = new AtomicBoolean();
        StringWriter stdout = new StringWriter();
        UpdateCommand command = command(tempDir, "linux-x86_64", Map.of(),
                (installed, version, baseUrl, includePrerelease) ->
                        artifact("2.0.0", installed.distributionClassifier(), "zip"),
                () -> {
                    materialized.set(true);
                    return tempDir.resolve("installer.sh");
                },
                (arguments, environment) -> 0,
                stdout, new StringWriter());

        assertEquals(0, new CommandLine(command).execute());
        assertFalse(materialized.get());
        assertTrue(stdout.toString().contains("No newer compatible release"));
        assertTrue(UpdateCommand.compareVersions("2.0.0", "1.9.9") > 0);
        assertEquals(0, UpdateCommand.compareVersions("2.0", "2.0.0"));
        assertTrue(UpdateCommand.compareVersions("2.0.0-rc.10", "2.0.0-rc.2") > 0);
        assertTrue(UpdateCommand.compareVersions("2.0.0", "2.0.0-rc.10") > 0);
    }

    @Test
    void forceReinstallsImplicitCurrentVersion(@TempDir Path tempDir) throws Exception {
        writeMetadata(tempDir, "2.0.0", "full", "linux-x86_64",
                "none", "full-linux-x86_64");
        Path installer = Files.writeString(tempDir.resolve("installer.sh"), "#!/bin/sh\n");
        AtomicBoolean launched = new AtomicBoolean();
        UpdateCommand command = command(tempDir, "linux-x86_64", Map.of(),
                (installed, version, baseUrl, includePrerelease) ->
                        artifact("2.0.0", installed.distributionClassifier(), "zip"),
                () -> installer,
                (arguments, environment) -> {
                    launched.set(true);
                    return 0;
                },
                new StringWriter(), new StringWriter());

        assertEquals(0, new CommandLine(command).execute("--force"));
        assertTrue(launched.get());
    }

    @Test
    void githubResolverChoosesHighestCompatibleVersionNotApiOrder(@TempDir Path tempDir)
            throws Exception {
        UpdateCommand.InstalledDistribution installed = new UpdateCommand.InstalledDistribution(
                tempDir, "1.0.0", "full", "linux-x86_64", "none", "full-linux-x86_64");
        String response = """
                [
                  {"tag_name":"v1.5.0","draft":false,"prerelease":false,"assets":[
                    {"name":"kompile-dist-1.5.0-full-linux-x86_64.zip","browser_download_url":"https://example.test/1.5.zip"},
                    {"name":"kompile-dist-1.5.0-full-linux-x86_64.zip.sha256","browser_download_url":"https://example.test/1.5.zip.sha256"}
                  ]},
                  {"tag_name":"v2.0.0","draft":false,"prerelease":false,"assets":[
                    {"name":"kompile-dist-2.0.0-full-linux-x86_64.zip","browser_download_url":"https://example.test/2.0.zip"},
                    {"name":"kompile-dist-2.0.0-full-linux-x86_64.zip.sha256","browser_download_url":"https://example.test/2.0.zip.sha256"}
                  ]}
                ]
                """;
        UpdateCommand.DefaultReleaseResolver resolver = new UpdateCommand.DefaultReleaseResolver(
                uri -> response, JsonUtils.standardMapper());

        assertEquals("2.0.0", resolver.resolve(installed, null, null, false).version());
    }

    @Test
    void customFileBaseRequiresArchiveAndChecksum(@TempDir Path tempDir) throws Exception {
        UpdateCommand.InstalledDistribution installed = new UpdateCommand.InstalledDistribution(
                tempDir, "1.0.0", "full", "linux-x86_64", "none", "full-linux-x86_64");
        String name = "kompile-dist-2.0.0-full-linux-x86_64.zip";
        Files.writeString(tempDir.resolve(name), "archive");
        Files.writeString(tempDir.resolve(name + ".sha256"), "checksum");
        UpdateCommand.DefaultReleaseResolver resolver = new UpdateCommand.DefaultReleaseResolver(
                uri -> {
                    throw new AssertionError("custom file resolution must not call GitHub");
                }, JsonUtils.standardMapper());

        UpdateCommand.ReleaseArtifact artifact = resolver.resolve(
                installed, "2.0.0", tempDir.toUri().toString(), false);

        assertEquals(name, artifact.archiveName());
        assertEquals(tempDir.resolve(name).toUri(), artifact.archiveUri());
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
                installed, "2.0.0", "http://example.test/releases", false));
    }

    @Test
    void bundledInstallerAndNativeResourceMetadataArePresent() throws Exception {
        ClassLoader loader = UpdateCommand.class.getClassLoader();
        try (InputStream installer = loader.getResourceAsStream(UpdateCommand.INSTALLER_RESOURCE)) {
            assertNotNull(installer, "canonical install.sh must be bundled in the CLI");
            assertTrue(new String(installer.readAllBytes(), StandardCharsets.UTF_8)
                    .contains("Kompile Installer"));
        }
        try (InputStream config = loader.getResourceAsStream(
                "META-INF/native-image/ai.kompile/kompile-cli/resource-config.json")) {
            assertNotNull(config);
            assertTrue(new String(config.readAllBytes(), StandardCharsets.UTF_8)
                    .contains("installer/install\\\\.sh"));
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void offlineInstallerUpdatePromotesPayloadAndPreservesState(@TempDir Path tempDir) throws Exception {
        Path install = tempDir.resolve("installed root");
        createOldInstall(install);
        Path archive = createDistributionArchive(tempDir.resolve("release mirror"),
                "2.0.0", "new-cli\n");
        writeChecksum(archive, sha256(archive));

        ProcessResult result = runInstaller(install, archive, archive.resolveSibling(
                archive.getFileName() + ".sha256"));

        assertEquals(0, result.exitCode(), result.output());
        assertEquals("new-cli\n", Files.readString(install.resolve("bin/kompile")));
        assertFalse(Files.exists(install.resolve("lib/stale.jar")));
        assertFalse(Files.exists(install.resolve("lib/.boot-inf-extracted")));
        assertEquals("unmanaged\n", Files.readString(install.resolve("bin/git-xet")));
        assertEquals("legacy-user-file\n",
                Files.readString(install.resolve("bin/kompile-app-main.jar")));
        assertEquals("{\"mcpServers\":{\"user\":{}}}\n",
                Files.readString(install.resolve("data/mcp-config.json")));
        assertEquals("2.0.0\n", Files.readString(install.resolve(".version")));
        assertFalse(Files.exists(install.resolve(".update.lock")));
        assertFalse(Files.readString(install.resolve("manifest.sha256"))
                .contains("data/mcp-config.json"));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void checksumFailureLeavesExistingInstallUntouched(@TempDir Path tempDir) throws Exception {
        Path install = tempDir.resolve("installed");
        createOldInstall(install);
        Path archive = createDistributionArchive(tempDir, "2.0.0", "new-cli\n");
        Path checksum = archive.resolveSibling(archive.getFileName() + ".sha256");
        Files.writeString(checksum, "0".repeat(64) + "  " + archive.getFileName() + "\n");

        ProcessResult result = runInstaller(install, archive, checksum);

        assertTrue(result.exitCode() != 0, result.output());
        assertEquals("old-cli\n", Files.readString(install.resolve("bin/kompile")));
        assertTrue(Files.exists(install.resolve("lib/stale.jar")));
        assertEquals("1.0.0\n", Files.readString(install.resolve(".version")));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void promotionFailureRollsBackAndRemovesUpdateLock(@TempDir Path tempDir) throws Exception {
        Path install = tempDir.resolve("installed");
        createOldInstall(install);
        Path archive = createDistributionArchive(tempDir, "2.0.0", "new-cli\n");
        Path checksum = archive.resolveSibling(archive.getFileName() + ".sha256");
        writeChecksum(archive, sha256(archive));

        ProcessResult result = runInstaller(install, archive, checksum,
                Map.of("KOMPILE_UPDATE_TEST_FAIL_AFTER", "1"));

        assertTrue(result.exitCode() != 0, result.output());
        assertTrue(result.output().contains("Previous managed payload restored"), result.output());
        assertEquals("old-cli\n", Files.readString(install.resolve("bin/kompile")));
        assertTrue(Files.exists(install.resolve("lib/stale.jar")));
        assertEquals("1.0.0\n", Files.readString(install.resolve(".version")));
        assertFalse(Files.exists(install.resolve(".update.lock")));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void activeUpdateLockPreventsConcurrentMutation(@TempDir Path tempDir) throws Exception {
        Path install = tempDir.resolve("installed");
        createOldInstall(install);
        Files.createDirectories(install.resolve(".update.lock"));
        Path archive = createDistributionArchive(tempDir, "2.0.0", "new-cli\n");
        Path checksum = archive.resolveSibling(archive.getFileName() + ".sha256");
        writeChecksum(archive, sha256(archive));

        ProcessResult result = runInstaller(install, archive, checksum);

        assertTrue(result.exitCode() != 0, result.output());
        assertTrue(result.output().contains("Another update is active"), result.output());
        assertEquals("old-cli\n", Files.readString(install.resolve("bin/kompile")));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void traversalArchiveIsRejectedBeforeMutation(@TempDir Path tempDir) throws Exception {
        Path install = tempDir.resolve("installed");
        createOldInstall(install);
        Path archive = tempDir.resolve("kompile-dist-2.0.0-full-linux-x86_64.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("kompile/../escaped"));
            zip.write("escape".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path checksum = archive.resolveSibling(archive.getFileName() + ".sha256");
        writeChecksum(archive, sha256(archive));

        ProcessResult result = runInstaller(install, archive, checksum);

        assertTrue(result.exitCode() != 0, result.output());
        assertTrue(result.output().contains("Unsafe archive entry"), result.output());
        assertEquals("old-cli\n", Files.readString(install.resolve("bin/kompile")));
        assertFalse(Files.exists(tempDir.resolve("escaped")));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void symbolicLinkArchiveIsRejectedBeforeExtraction(@TempDir Path tempDir) throws Exception {
        Path install = tempDir.resolve("installed");
        createOldInstall(install);
        Path archiveRoot = Files.createDirectories(tempDir.resolve("kompile"));
        Files.createSymbolicLink(archiveRoot.resolve("link"), Path.of("../../escaped"));
        Path archive = tempDir.resolve("kompile-dist-2.0.0-full-linux-x86_64.zip");
        Process zip;
        try {
            zip = new ProcessBuilder("zip", "-y", "-q", archive.toString(), "kompile/link")
                    .directory(tempDir.toFile())
                    .start();
        } catch (IOException unavailable) {
            Assumptions.assumeTrue(false, "zip command is unavailable");
            return;
        }
        Assumptions.assumeTrue(zip.waitFor(10, TimeUnit.SECONDS) && zip.exitValue() == 0,
                "zip command cannot create symbolic-link fixtures");
        Path checksum = archive.resolveSibling(archive.getFileName() + ".sha256");
        writeChecksum(archive, sha256(archive));

        ProcessResult result = runInstaller(install, archive, checksum);

        assertTrue(result.exitCode() != 0, result.output());
        assertTrue(result.output().contains("symbolic link"), result.output());
        assertEquals("old-cli\n", Files.readString(install.resolve("bin/kompile")));
        assertFalse(Files.exists(tempDir.resolve("escaped")));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void symbolicLinkParentInInstalledRootIsRejectedBeforeMutation(@TempDir Path tempDir)
            throws Exception {
        Path install = tempDir.resolve("installed");
        createOldInstall(install);
        Path externalBin = tempDir.resolve("external-bin");
        Files.move(install.resolve("bin"), externalBin);
        Files.createSymbolicLink(install.resolve("bin"), externalBin);
        Path archive = createDistributionArchive(tempDir.resolve("mirror"),
                "2.0.0", "new-cli\n");
        Path checksum = archive.resolveSibling(archive.getFileName() + ".sha256");
        writeChecksum(archive, sha256(archive));

        ProcessResult result = runInstaller(install, archive, checksum);

        assertTrue(result.exitCode() != 0, result.output());
        assertTrue(result.output().contains("symbolic-link directory"), result.output());
        assertEquals("old-cli\n", Files.readString(externalBin.resolve("kompile")));
        assertEquals("1.0.0\n", Files.readString(install.resolve(".version")));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void installerLatestDiscoveryIgnoresModelReleaseAndUsesZipFallback(@TempDir Path tempDir)
            throws Exception {
        Path archive = createDistributionArchive(tempDir, "2.0.0", "new-cli\n");
        writeChecksum(archive, sha256(archive));
        Path fakeBin = Files.createDirectories(tempDir.resolve("fake-bin"));
        Path fakeCurl = fakeBin.resolve("curl");
        Files.writeString(fakeCurl, """
                #!/usr/bin/env bash
                set -eu
                output=""
                head_request=false
                url=""
                while [ "$#" -gt 0 ]; do
                  case "$1" in
                    -o) output="$2"; shift 2 ;;
                    --head) head_request=true; shift ;;
                    -*) shift ;;
                    *) url="$1"; shift ;;
                  esac
                done
                case "$url" in
                  *"/releases?per_page=100") printf '%s\\n' "$FAKE_RELEASES_JSON"; exit 0 ;;
                esac
                source_path="${url#file://}"
                if [ "$head_request" = true ]; then
                  [ -f "$source_path" ]
                  exit
                fi
                cp "$source_path" "$output"
                """);
        assertTrue(fakeCurl.toFile().setExecutable(true));

        Path install = tempDir.resolve("fresh-install");
        ProcessBuilder builder = new ProcessBuilder(
                "bash", findRepositoryFile("install.sh").toString(),
                "--variant", "full",
                "--dir", install.toString(),
                "--url", tempDir.toUri().toString());
        builder.redirectErrorStream(true);
        builder.environment().put("PATH", fakeBin + ":" + System.getenv("PATH"));
        builder.environment().put("FAKE_RELEASES_JSON", """
                [
                  {"tag_name": "opennlp"},
                  {"tag_name": "v2.0.0"}
                ]
                """);
        Process process = builder.start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "installer latest check timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("Version:   2.0.0"), output);
        assertTrue(output.contains("kompile-dist-2.0.0-full-linux-x86_64.zip"), output);
        assertEquals("2.0.0\n", Files.readString(install.resolve(".version")));
    }

    private static UpdateCommand command(
            Path installRoot,
            String platform,
            Map<String, String> environment,
            UpdateCommand.ReleaseResolver resolver,
            UpdateCommand.InstallerMaterializer materializer,
            UpdateCommand.InstallerLauncher launcher,
            StringWriter stdout,
            StringWriter stderr) {
        return new UpdateCommand(
                () -> installRoot,
                () -> platform,
                () -> true,
                environment,
                resolver,
                materializer,
                launcher,
                JsonUtils.standardMapper(),
                new PrintWriter(stdout, true),
                new PrintWriter(stderr, true));
    }

    private static UpdateCommand.ReleaseArtifact artifact(
            String version, String classifier, String extension) {
        String name = "kompile-dist-" + version + "-" + classifier + "." + extension;
        URI archive = URI.create("https://example.test/" + name);
        return new UpdateCommand.ReleaseArtifact(version, name, archive,
                URI.create(archive + ".sha256"));
    }

    private static void assertContainsPair(List<String> values, String option, String expected) {
        int index = values.indexOf(option);
        assertTrue(index >= 0, "missing option " + option + " in " + values);
        assertTrue(index + 1 < values.size(), "missing value for " + option);
        assertEquals(expected, values.get(index + 1));
    }

    private static void writeMetadata(
            Path root,
            String version,
            String variant,
            String platform,
            String backend,
            String classifier) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve(".dist-info.json"), """
                {
                  "version": "%s",
                  "variant": "%s",
                  "platform": "%s",
                  "backendProfile": "%s",
                  "distributionClassifier": "%s"
                }
                """.formatted(version, variant, platform, backend, classifier));
    }

    private static void createOldInstall(Path install) throws Exception {
        Files.createDirectories(install.resolve("bin"));
        Files.createDirectories(install.resolve("lib/.boot-inf-extracted"));
        Files.createDirectories(install.resolve("data"));
        Files.writeString(install.resolve("bin/kompile"), "old-cli\n");
        Files.writeString(install.resolve("bin/git-xet"), "unmanaged\n");
        Files.writeString(install.resolve("bin/kompile-app-main.jar"), "legacy-user-file\n");
        Files.writeString(install.resolve("lib/stale.jar"), "stale\n");
        Files.writeString(install.resolve("lib/.boot-inf-extracted/stale.class"), "stale-cache\n");
        Files.writeString(install.resolve("data/mcp-config.json"),
                "{\"mcpServers\":{\"user\":{}}}\n");
        Files.writeString(install.resolve(".version"), "1.0.0\n");
        Files.writeString(install.resolve(".variant"), "full\n");
        writeMetadata(install, "1.0.0", "full", "linux-x86_64",
                "none", "full-linux-x86_64");
        writeManifest(install, install.resolve("manifest.sha256"), List.of(
                "bin/kompile", "lib/stale.jar", "data/mcp-config.json",
                ".version", ".variant", ".dist-info.json"));
    }

    private static Path createDistributionArchive(Path directory, String version, String cliContent)
            throws Exception {
        Path root = directory.resolve("archive-root");
        Files.createDirectories(root.resolve("bin"));
        Files.createDirectories(root.resolve("data"));
        Files.writeString(root.resolve("bin/kompile"), cliContent);
        Files.writeString(root.resolve("data/mcp-config.json"), "{\"mcpServers\":{}}\n");
        Files.writeString(root.resolve(".version"), version + "\n");
        Files.writeString(root.resolve(".variant"), "full\n");
        writeMetadata(root, version, "full", "linux-x86_64",
                "none", "full-linux-x86_64");
        writeManifest(root, root.resolve("manifest.sha256"), List.of(
                "bin/kompile", "data/mcp-config.json", ".version", ".variant", ".dist-info.json"));

        Path archive = directory.resolve(
                "kompile-dist-" + version + "-full-linux-x86_64.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("kompile/"));
            zip.closeEntry();
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted().toList()) {
                    if (path.equals(root) || Files.isDirectory(path)) continue;
                    String relative = root.relativize(path).toString().replace('\\', '/');
                    zip.putNextEntry(new ZipEntry("kompile/" + relative));
                    Files.copy(path, zip);
                    zip.closeEntry();
                }
            }
        }
        return archive;
    }

    private static void writeManifest(Path root, Path manifest, List<String> paths) throws Exception {
        List<String> lines = new ArrayList<>();
        for (String path : paths) {
            lines.add(sha256(root.resolve(path)) + "  " + path);
        }
        Files.writeString(manifest, String.join("\n", lines) + "\n");
    }

    private static void writeChecksum(Path archive, String checksum) throws IOException {
        Files.writeString(archive.resolveSibling(archive.getFileName() + ".sha256"),
                checksum + "  " + archive.getFileName() + "\n");
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static ProcessResult runInstaller(Path install, Path archive, Path checksum) throws Exception {
        return runInstaller(install, archive, checksum, Map.of());
    }

    private static ProcessResult runInstaller(
            Path install, Path archive, Path checksum, Map<String, String> environment) throws Exception {
        Path installer = findRepositoryFile("install.sh");
        ProcessBuilder builder = new ProcessBuilder(
                "bash", installer.toString(),
                "--update",
                "--version", "2.0.0",
                "--variant", "full",
                "--dir", install.toString(),
                "--archive-url", archive.toUri().toString(),
                "--checksum-url", checksum.toUri().toString());
        builder.redirectErrorStream(true);
        builder.environment().put("KOMPILE_MODIFY_PATH", "0");
        builder.environment().putAll(environment);
        Process process = builder.start();
        boolean finished = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("installer did not finish within 30 seconds");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.exitValue(), output);
    }

    private static Path findRepositoryFile(String name) {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(name);
            if (Files.isRegularFile(candidate)) return candidate;
            current = current.getParent();
        }
        throw new IllegalStateException("Could not find repository file: " + name);
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
