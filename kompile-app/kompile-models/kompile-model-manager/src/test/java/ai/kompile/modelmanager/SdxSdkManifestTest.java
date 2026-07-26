/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.modelmanager;

import ai.kompile.cli.common.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SdxSdkManifestTest {
    @TempDir Path tempDir;

    @Test
    void canonicalManifestUrlUsesExactSdkTag() {
        assertEquals("https://example/releases/sdk-v1.2.3/sdx-sdk-manifest.json",
                SdkConstants.getSdxManifestUrl("1.2.3", "https://example/releases"));
    }

    @Test
    void selectsExactVariant() throws Exception {
        String json = manifest("1.2.3",
                artifact("runtime", "platform-sdk", "linux-x86_64", "cpu", "a.zip", sha("a"), 1) + "," +
                artifact("runtime", "platform-sdk", "linux-x86_64", "cuda", "b.zip", sha("b"), 1));
        SdxSdkManifest parsed = parse(json, "1.2.3");
        assertEquals("b.zip", parsed.select("runtime", "platform-sdk", "linux-x86_64", "cuda").fileName());
        assertThrows(IOException.class,
                () -> SdxSdkManifest.defaultVariant(parsed, "runtime", "platform-sdk", "linux-x86_64"));
    }

    @Test
    void downloadsExactManifestFilenameVerifiesChecksumAndQualifiesCache() throws Exception {
        byte[] content = "canonical".getBytes(StandardCharsets.UTF_8);
        Path release = release("1.2.3");
        Files.write(release.resolve("upstream-name.zip"), content);
        writeManifest(release, manifest("1.2.3",
                artifact("runtime", "platform-sdk", "linux-x86_64", "cpu", "upstream-name.zip",
                        sha(content), content.length)));

        KompileModelManager manager = new KompileModelManager(tempDir.resolve("cache"));
        Path downloaded = manager.downloadSdxSdk("1.2.3", "runtime", "platform-sdk",
                "linux-x86_64", null, tempDir.toUri().toString());

        assertEquals("upstream-name.zip", downloaded.getFileName().toString());
        assertTrue(downloaded.toString().contains("sdx-sdk/sdx-runtime/1.2.3/origins/"));
        assertTrue(downloaded.toString().contains("linux-x86_64/cpu"));
        assertArrayEquals(content, Files.readAllBytes(downloaded));
    }

    @Test
    void compatibilityFacadeStillResolvesManifestClassifier() throws Exception {
        byte[] content = "legacy-api".getBytes(StandardCharsets.UTF_8);
        Path release = release("1.2.3");
        Files.write(release.resolve("chosen.zip"), content);
        writeManifest(release, manifest("1.2.3",
                artifact("runtime", "platform-sdk", "linux-x86_64", "cpu", "chosen.zip",
                        sha(content), content.length) + "," +
                artifact("runtime", "runtime-bindings", "linux-x86_64", "cpu", "bindings.zip",
                        sha("bindings"), "bindings".length())));
        SdkDescriptor descriptor = SdkConstants.createSdxRuntimeDescriptor("1.2.3", tempDir.toUri().toString());
        Path downloaded = new KompileModelManager(tempDir.resolve("compat-cache"))
                .downloadSdk(descriptor, "linux-x86_64-cpu");
        assertEquals("chosen.zip", downloaded.getFileName().toString());
    }

    @Test
    void checksumFailureDeletesDownload() throws Exception {
        byte[] content = "corrupt".getBytes(StandardCharsets.UTF_8);
        Path release = release("1.2.3");
        Files.write(release.resolve("bad.zip"), content);
        writeManifest(release, manifest("1.2.3",
                artifact("runtime", "platform-sdk", "linux-x86_64", "cpu", "bad.zip", sha("other"), content.length)));
        KompileModelManager manager = new KompileModelManager(tempDir.resolve("bad-cache"));
        IOException error = assertThrows(IOException.class, () -> manager.downloadSdxSdk(
                "1.2.3", "runtime", "platform-sdk", "linux-x86_64", "cpu", tempDir.toUri().toString()));
        assertTrue(error.getMessage().contains("Checksum mismatch"));
    }

    @Test
    void rejectsInvalidSchemaUnsafePathAndDuplicates() {
        String validArtifact = artifact("runtime", "platform-sdk", "linux-x86_64", "cpu", "a.zip", sha("a"), 1);
        assertThrows(IOException.class, () -> parse(manifestWithSchema(2, "1.2.3", validArtifact), "1.2.3"));
        assertThrows(IOException.class, () -> parse(manifest("1.2.3", validArtifact.replace("a.zip", "../a.zip")), "1.2.3"));
        String duplicate = validArtifact + "," + validArtifact.replace("a.zip", "b.zip");
        assertThrows(IOException.class, () -> parse(manifest("1.2.3", duplicate), "1.2.3"));
    }

    @Test
    void invalidCachedManifestNeverFallsBackToRelease() throws Exception {
        KompileModelManager manager = new KompileModelManager(tempDir.resolve("cache"));
        Path cacheManifest = manager.getSdxManifestCachePath("1.2.3", tempDir.toUri().toString());
        Files.createDirectories(cacheManifest.getParent());
        String invalid = "{\"schemaVersion\":2}";
        Files.writeString(cacheManifest, invalid);
        Files.writeString(cacheManifest.resolveSibling(SdxSdkManifest.FILE_NAME + ".sha256"),
                sha(invalid) + "  " + SdxSdkManifest.FILE_NAME + "\n");
        Path release = release("1.2.3");
        writeManifest(release, manifest("1.2.3",
                artifact("runtime", "platform-sdk", "linux-x86_64", "cpu", "a.zip", sha("a"), 1)));
        assertThrows(IOException.class, () -> manager.loadSdxManifest("1.2.3", tempDir.toUri().toString()));
    }

    @Test
    void isolatesCachesByReleaseOrigin() throws Exception {
        Path sourceA = tempDir.resolve("source-a");
        Path sourceB = tempDir.resolve("source-b");
        byte[] a = "source-a".getBytes(StandardCharsets.UTF_8);
        byte[] b = "source-b".getBytes(StandardCharsets.UTF_8);
        Path releaseA = releaseAt(sourceA, "1.2.3");
        Path releaseB = releaseAt(sourceB, "1.2.3");
        Files.write(releaseA.resolve("sdk.zip"), a);
        Files.write(releaseB.resolve("sdk.zip"), b);
        writeManifest(releaseA, manifest("1.2.3", artifact("runtime", "platform-sdk",
                "linux-x86_64", "cpu", "sdk.zip", sha(a), a.length)));
        writeManifest(releaseB, manifest("1.2.3", artifact("runtime", "platform-sdk",
                "linux-x86_64", "cpu", "sdk.zip", sha(b), b.length)));

        KompileModelManager manager = new KompileModelManager(tempDir.resolve("origin-cache"));
        Path downloadedA = manager.downloadSdxSdk("1.2.3", "runtime", "platform-sdk",
                "linux-x86_64", "cpu", sourceA.toUri().toString());
        Path downloadedB = manager.downloadSdxSdk("1.2.3", "runtime", "platform-sdk",
                "linux-x86_64", "cpu", sourceB.toUri().toString());

        assertNotEquals(downloadedA, downloadedB);
        assertArrayEquals(a, Files.readAllBytes(downloadedA));
        assertArrayEquals(b, Files.readAllBytes(downloadedB));
    }

    @Test
    void rejectsManifestChecksumSidecarMismatch() throws Exception {
        Path release = release("1.2.3");
        String json = manifest("1.2.3", artifact("runtime", "platform-sdk",
                "linux-x86_64", "cpu", "a.zip", sha("a"), 1));
        Files.writeString(release.resolve(SdxSdkManifest.FILE_NAME), json);
        Files.writeString(release.resolve(SdxSdkManifest.FILE_NAME + ".sha256"),
                sha("different") + "  " + SdxSdkManifest.FILE_NAME + "\n");
        KompileModelManager manager = new KompileModelManager(tempDir.resolve("sidecar-cache"));
        IOException error = assertThrows(IOException.class,
                () -> manager.loadSdxManifest("1.2.3", tempDir.toUri().toString()));
        assertTrue(error.getMessage().contains("Checksum mismatch"));
    }

    @Test
    void acceptsMacosxConsumerAlias() throws Exception {
        SdxSdkManifest parsed = parse(manifest("1.2.3", artifact("runtime", "platform-sdk",
                "macos-arm64", "cpu", "mac.zip", sha("m"), 1)), "1.2.3");
        assertEquals("mac.zip", parsed.select("runtime", "platform-sdk", "macosx-arm64", "cpu").fileName());
        assertEquals("cpu", SdxSdkManifest.defaultVariant(
                parsed, "runtime", "platform-sdk", "macosx-arm64"));
    }

    @Test
    void installsManifestSelectedMobileArtifacts() throws Exception {
        Path aar = tempDir.resolve("runtime.aar");
        Files.writeString(aar, "aar");
        SdxSdkManifest.Artifact aarArtifact = new SdxSdkManifest.Artifact(
                "runtime", "android-aar", "android-arm64", "cpu", "android-arm64-cpu",
                "runtime.aar", "aar", sha("aar"), 3);
        Path installedAar = SdxSdkArtifactInstaller.install(
                new KompileModelManager.ResolvedSdxSdkArtifact(aar, aarArtifact), tempDir.resolve("android/libs"));
        assertEquals("aar", Files.readString(installedAar));

        Path archive = tempDir.resolve("Runtime.xcframework.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("Runtime.xcframework/Info.plist"));
            zip.write("plist".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        SdxSdkManifest.Artifact appleArtifact = new SdxSdkManifest.Artifact(
                "runtime", "apple-xcframework", "ios-arm64", "cpu", "ios-arm64-cpu",
                "Runtime.xcframework.zip", "xcframework.zip", sha(Files.readAllBytes(archive)), Files.size(archive));
        Path installedFramework = SdxSdkArtifactInstaller.install(
                new KompileModelManager.ResolvedSdxSdkArtifact(archive, appleArtifact),
                tempDir.resolve("ios/Frameworks"));
        assertEquals("plist", Files.readString(installedFramework.resolve("Info.plist")));
    }

    @Test
    void rejectsUnsafeXcframeworkWithoutLeavingPartialInstall() throws Exception {
        Path archive = tempDir.resolve("Runtime.xcframework.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("Runtime.xcframework/Info.plist"));
            zip.write("plist".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("Runtime.xcframework/../escape.txt"));
            zip.write("escape".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        SdxSdkManifest.Artifact artifact = new SdxSdkManifest.Artifact(
                "runtime", "apple-xcframework", "ios-arm64", "cpu", "ios-arm64-cpu",
                "Runtime.xcframework.zip", "xcframework.zip", sha(Files.readAllBytes(archive)), Files.size(archive));
        Path destination = tempDir.resolve("unsafe/Frameworks");

        assertThrows(IOException.class, () -> SdxSdkArtifactInstaller.install(
                new KompileModelManager.ResolvedSdxSdkArtifact(archive, artifact), destination));
        assertFalse(Files.exists(destination.resolve("Runtime.xcframework")));
        assertFalse(Files.exists(tempDir.resolve("unsafe/escape.txt")));
    }

    @Test
    void failedXcframeworkPromotionRestoresPreviousInstall() throws Exception {
        Path destination = tempDir.resolve("promotion/Frameworks");
        Path installed = destination.resolve("Runtime.xcframework");
        Path staged = destination.resolve("staged/Runtime.xcframework");
        Files.createDirectories(installed);
        Files.createDirectories(staged);
        Files.writeString(installed.resolve("Info.plist"), "previous");
        Files.writeString(staged.resolve("Info.plist"), "replacement");
        AtomicInteger moves = new AtomicInteger();

        IOException failure = assertThrows(IOException.class,
                () -> SdxSdkArtifactInstaller.promoteDirectory(staged, installed, (source, target) -> {
                    if (moves.incrementAndGet() == 2) {
                        throw new IOException("synthetic promotion failure");
                    }
                    Files.move(source, target);
                }));

        assertTrue(failure.getMessage().contains("synthetic promotion failure"));
        assertEquals("previous", Files.readString(installed.resolve("Info.plist")));
        assertEquals("replacement", Files.readString(staged.resolve("Info.plist")));
        assertFalse(Files.exists(destination.resolve(".Runtime.xcframework.sdx-backup")));
    }

    @Test
    void recoversStaleXcframeworkBackupBeforePromotion() throws Exception {
        Path destination = tempDir.resolve("stale-backup/Frameworks");
        Path installed = destination.resolve("Runtime.xcframework");
        Path backup = destination.resolve(".Runtime.xcframework.sdx-backup");
        Path staged = destination.resolve("staged/Runtime.xcframework");
        Files.createDirectories(backup);
        Files.createDirectories(staged);
        Files.writeString(backup.resolve("Info.plist"), "previous");
        Files.writeString(staged.resolve("Info.plist"), "replacement");
        AtomicInteger moves = new AtomicInteger();

        SdxSdkArtifactInstaller.promoteDirectory(staged, installed, (source, target) -> {
            if (moves.incrementAndGet() == 1) {
                assertEquals(backup, source);
                assertEquals(installed, target);
            }
            Files.move(source, target);
        });

        assertEquals(3, moves.get());
        assertEquals("replacement", Files.readString(installed.resolve("Info.plist")));
        assertFalse(Files.exists(backup));
    }

    @Test
    void discardsStaleXcframeworkBackupWhenInstallExists() throws Exception {
        Path destination = tempDir.resolve("stale-backup-with-install/Frameworks");
        Path installed = destination.resolve("Runtime.xcframework");
        Path backup = destination.resolve(".Runtime.xcframework.sdx-backup");
        Path staged = destination.resolve("staged/Runtime.xcframework");
        Files.createDirectories(installed);
        Files.createDirectories(backup);
        Files.createDirectories(staged);
        Files.writeString(installed.resolve("Info.plist"), "current");
        Files.writeString(backup.resolve("Info.plist"), "stale");
        Files.writeString(staged.resolve("Info.plist"), "replacement");

        SdxSdkArtifactInstaller.promoteDirectory(staged, installed,
                (source, target) -> Files.move(source, target));

        assertEquals("replacement", Files.readString(installed.resolve("Info.plist")));
        assertFalse(Files.exists(backup));
    }

    @Test
    void failedStaleXcframeworkBackupRecoveryPreservesBackup() throws Exception {
        Path destination = tempDir.resolve("failed-stale-backup-recovery/Frameworks");
        Path installed = destination.resolve("Runtime.xcframework");
        Path backup = destination.resolve(".Runtime.xcframework.sdx-backup");
        Path staged = destination.resolve("staged/Runtime.xcframework");
        Files.createDirectories(backup);
        Files.createDirectories(staged);
        Files.writeString(backup.resolve("Info.plist"), "previous");
        Files.writeString(staged.resolve("Info.plist"), "replacement");

        IOException failure = assertThrows(IOException.class,
                () -> SdxSdkArtifactInstaller.promoteDirectory(staged, installed, (source, target) -> {
                    assertEquals(backup, source);
                    assertEquals(installed, target);
                    throw new IOException("synthetic stale-backup recovery failure");
                }));

        assertTrue(failure.getMessage().contains("synthetic stale-backup recovery failure"));
        assertEquals("previous", Files.readString(backup.resolve("Info.plist")));
        assertFalse(Files.exists(installed));
        assertEquals("replacement", Files.readString(staged.resolve("Info.plist")));
    }

    @Test
    void rejectsNonDirectoryStaleXcframeworkBackupWithoutMovingIt() throws Exception {
        Path destination = tempDir.resolve("invalid-stale-backup/Frameworks");
        Path installed = destination.resolve("Runtime.xcframework");
        Path backup = destination.resolve(".Runtime.xcframework.sdx-backup");
        Path staged = destination.resolve("staged/Runtime.xcframework");
        Files.createDirectories(destination);
        Files.createDirectories(staged);
        Files.writeString(backup, "not a framework directory");
        Files.writeString(staged.resolve("Info.plist"), "replacement");
        AtomicInteger moves = new AtomicInteger();

        IOException failure = assertThrows(IOException.class,
                () -> SdxSdkArtifactInstaller.promoteDirectory(staged, installed, (source, target) -> {
                    moves.incrementAndGet();
                    Files.move(source, target);
                }));

        assertTrue(failure.getMessage().contains("backup path is not a directory"));
        assertEquals(0, moves.get());
        assertEquals("not a framework directory", Files.readString(backup));
        assertFalse(Files.exists(installed));
        assertEquals("replacement", Files.readString(staged.resolve("Info.plist")));
    }

    @Test
    void refusesPreexistingSymlinkMobileDestinations() throws Exception {
        Path aar = tempDir.resolve("runtime.aar");
        Files.writeString(aar, "aar");
        SdxSdkManifest.Artifact artifact = new SdxSdkManifest.Artifact(
                "runtime", "android-aar", "android-arm64", "cpu", "android-arm64-cpu",
                "runtime.aar", "aar", sha("aar"), 3);
        Path destination = tempDir.resolve("symlink/libs");
        Path outside = tempDir.resolve("outside.aar");
        Files.createDirectories(destination);
        Files.writeString(outside, "outside");
        try {
            Files.createSymbolicLink(destination.resolve("runtime.aar"), outside);
        } catch (IOException | UnsupportedOperationException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "symbolic links are unavailable on this test platform: " + unsupported.getMessage());
            return;
        }

        assertThrows(IOException.class, () -> SdxSdkArtifactInstaller.install(
                new KompileModelManager.ResolvedSdxSdkArtifact(aar, artifact), destination));
        assertEquals("outside", Files.readString(outside));
    }

    @Test
    void acceptsProducerJavaArtifactsWithEmptySelectors() throws Exception {
        String json = manifest("1.2.3",
                javaArtifact("alpha.jar", sha("alpha"), 5) + "," +
                javaArtifact("beta.jar", sha("beta"), 4));
        SdxSdkManifest parsed = parse(json, "1.2.3");
        assertEquals(2, parsed.artifacts().size());
        assertEquals("", parsed.artifacts().get(0).classifier());
    }

    private Path release(String version) throws IOException {
        return releaseAt(tempDir, version);
    }

    private static Path releaseAt(Path parent, String version) throws IOException {
        Path release = parent.resolve("sdk-v" + version);
        Files.createDirectories(release);
        return release;
    }

    private static void writeManifest(Path release, String json) throws IOException {
        Files.writeString(release.resolve(SdxSdkManifest.FILE_NAME), json);
        Files.writeString(release.resolve(SdxSdkManifest.FILE_NAME + ".sha256"),
                sha(json) + "  " + SdxSdkManifest.FILE_NAME + "\n");
    }

    private static SdxSdkManifest parse(String json, String version) throws IOException {
        return SdxSdkManifest.parse(JsonUtils.standardMapper(),
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), version);
    }

    private static String manifest(String version, String artifacts) {
        return manifestWithSchema(1, version, artifacts);
    }

    private static String manifestWithSchema(int schema, String version, String artifacts) {
        return "{\"schemaVersion\":" + schema + ",\"releaseVersion\":\"" + version +
                "\",\"releaseTag\":\"sdk-v" + version + "\",\"artifacts\":[" + artifacts + "]}";
    }

    private static String artifact(String component, String role, String platform, String variant,
                                   String fileName, String sha256, long size) {
        return "{\"component\":\"" + component + "\",\"packageRole\":\"" + role +
                "\",\"platform\":\"" + platform + "\",\"variant\":\"" + variant +
                "\",\"classifier\":\"" + platform + "-" + variant + "\",\"fileName\":\"" + fileName +
                "\",\"packaging\":\"zip\",\"sha256\":\"" + sha256 + "\",\"size\":" + size + "}";
    }

    private static String javaArtifact(String fileName, String sha256, long size) {
        return "{\"component\":\"java\",\"packageRole\":\"java\",\"platform\":\"\"," +
                "\"variant\":\"\",\"classifier\":\"\",\"fileName\":\"" + fileName +
                "\",\"packaging\":\"jar\",\"sha256\":\"" + sha256 + "\",\"size\":" + size + "}";
    }

    private static String sha(String value) { return sha(value.getBytes(StandardCharsets.UTF_8)); }

    private static String sha(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
