package ai.kompile.cli.main.extensions.process;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ProcessExtensionStoreTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir Path temporary;

    private Path source(String directory, String id, String version) throws Exception {
        Path source = Files.createDirectories(temporary.resolve(directory));
        Files.writeString(source.resolve("extension.json"), """
                {"schemaVersion":1,"id":"%s","version":"%s","apiVersion":1,
                 "commands":{"%s":{"kind":"jar","path":"main.jar","args":["literal arg"]}}}
                """.formatted(id, version, id));
        Files.writeString(source.resolve("main.jar"), "trusted local bytes");
        return source;
    }

    private ProcessExtensionStore store() { return new ProcessExtensionStore(temporary.resolve("user-store"), mapper); }

    private void assertNoStaging(Path root) throws Exception {
        try (var children = Files.list(root)) {
            assertFalse(children.anyMatch(path -> path.getFileName().toString().startsWith(".stage-")
                    || path.getFileName().toString().startsWith(".installed-")));
        }
    }

    @Test
    void explicitRootStartsEmptyAndInstallCopiesWithoutExecuting() throws Exception {
        var store = store();
        assertEquals(temporary.resolve("user-store"), store.root());
        assertEquals(List.of(), store.installed());
        assertFalse(Files.exists(store.root()));
        Path source = source("source", "sample", "1");
        var installed = store.install(source, false);
        assertEquals(store.root().resolve("packages/sample/1"), installed.directory());
        assertEquals("sample", installed.manifest().id());
        assertEquals(1, store.installed().size());
        assertEquals("1", mapper.readTree(Files.readString(store.root().resolve("installed.json"))).get("sample").asText());
        Files.writeString(source.resolve("main.jar"), "source changes cannot change installed code");
        assertEquals("trusted local bytes", Files.readString(installed.directory().resolve("main.jar")));
        assertEquals(installed.directory(), new ProcessExtensionStore(store.root(), mapper).find("sample").orElseThrow().directory());
        assertTrue(store.find("absent").isEmpty());
        assertNoStaging(store.root());
    }

    @Test
    void deactivationRecoversFromCorruptSelectedMetadata() throws Exception {
        var store = store();
        var installed = store.install(source("source", "broken", "1"), false);
        Files.writeString(installed.directory().resolve("extension.json"), "invalid json");
        assertThrows(IOException.class, store::installed);
        assertTrue(store.remove("broken"));
        assertTrue(store.installed().isEmpty());
    }

    @Test
    void selectedCommandOwnershipIsCheckedInsideInstallTransaction() throws Exception {
        var store = store();
        store.install(source("first", "first", "1"), false);
        Path second = source("second", "second", "1");
        Path manifest = second.resolve("extension.json");
        Files.writeString(manifest, Files.readString(manifest).replace("\"commands\":{\"second\"", "\"commands\":{\"first\""));
        assertThrows(IOException.class, () -> store.install(second, false));
        assertTrue(store.find("second").isEmpty());
        assertEquals(1, store.installed().size());
    }

    @Test
    void replacementIsExplicitAndNeverOverwritesAnImmutableVersion() throws Exception {
        var store = store();
        Path first = source("first", "sample", "1");
        var installed = store.install(first, false);
        assertThrows(IOException.class, () -> store.install(first, false));
        Path second = source("second", "sample", "2");
        assertThrows(IOException.class, () -> store.install(second, false));
        assertFalse(Files.exists(store.root().resolve("packages/sample/2")));
        store.install(second, true);
        assertEquals("2", store.find("sample").orElseThrow().manifest().version());
        assertTrue(Files.isRegularFile(installed.directory().resolve("main.jar")));
        Path modified = source("modified", "sample", "1");
        Files.writeString(modified.resolve("main.jar"), "different payload");
        assertThrows(IOException.class, () -> store.install(modified, true));
        assertEquals("trusted local bytes", Files.readString(installed.directory().resolve("main.jar")));
        assertEquals("2", store.find("sample").orElseThrow().manifest().version());
        store.install(first, true); // Identical immutable payload may be reselected, not overwritten.
        assertEquals("1", store.find("sample").orElseThrow().manifest().version());
        assertNoStaging(store.root());
    }

    @Test
    void deactivationRetainsPayloadAndAllowsIdenticalReactivation() throws Exception {
        var store = store();
        Path source = source("source", "sample", "1");
        Path installed = store.install(source, false).directory();
        assertTrue(store.remove("sample"));
        assertFalse(store.remove("sample"));
        assertTrue(store.find("sample").isEmpty());
        assertTrue(store.installed().isEmpty());
        assertTrue(Files.exists(installed.resolve("main.jar")));
        assertEquals(installed, store.install(source, false).directory());
        assertThrows(IllegalArgumentException.class, () -> store.remove("../escape"));
        assertThrows(IllegalArgumentException.class, () -> store.find("../escape"));
    }

    @Test
    void rejectsSymlinksAnywhereIncludingUnusedFilesAndStoreParents() throws Exception {
        var store = store();
        Path source = source("source", "sample", "1");
        Files.createSymbolicLink(source.resolve("unused-link"), Path.of("main.jar"));
        assertThrows(IOException.class, () -> store.install(source, false));
        assertTrue(store.installed().isEmpty());
        assertNoStaging(store.root());
        Files.delete(source.resolve("unused-link"));
        Path alias = Files.createSymbolicLink(temporary.resolve("source-alias"), source);
        assertThrows(IOException.class, () -> store.install(alias, false));
        Files.createDirectory(source.resolve("real"));
        Files.createSymbolicLink(source.resolve("linked-dir"), Path.of("real"));
        assertThrows(IOException.class, () -> store.install(source, false));
        Files.delete(source.resolve("linked-dir"));
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        Path rootLink = Files.createSymbolicLink(temporary.resolve("linked-store"), outside);
        var linkedStore = new ProcessExtensionStore(rootLink.resolve("nested"), mapper);
        assertThrows(IOException.class, () -> linkedStore.install(source, false));
        assertThrows(IOException.class, linkedStore::installed);
        assertFalse(Files.exists(outside.resolve("nested")));
        var installed = store.install(source, false);
        Files.createSymbolicLink(installed.directory().resolve("injected-link"), Path.of("main.jar"));
        assertThrows(IOException.class, store::installed);
    }

    @Test
    void malformedRegistryAndSelectedMetadataFailClosedWithoutReplacingRegistry() throws Exception {
        var store = store();
        Path source = source("source", "sample", "1");
        var installed = store.install(source, false);
        Path index = store.root().resolve("installed.json");
        String good = Files.readString(index);
        for (String invalid : List.of("", "[]", "null", "{", "{\"../escape\":\"1\"}",
                "{\"sample\":1}", "{\"sample\":\"../1\"}", "{\"sample\":\"1\",\"sample\":\"2\"}", "{} {}")) {
            Files.writeString(index, invalid);
            assertThrows(IOException.class, store::installed, invalid);
            assertThrows(IOException.class, () -> store.install(source, true), invalid);
            assertThrows(IOException.class, () -> store.remove("sample"), invalid);
            assertEquals(invalid, Files.readString(index));
        }
        Files.writeString(index, good);
        Path manifest = installed.directory().resolve("extension.json");
        Files.writeString(manifest, Files.readString(manifest).replace("\"id\":\"sample\"", "\"id\":\"other\""));
        assertThrows(IOException.class, () -> store.find("sample"));
        assertThrows(IOException.class, store::installed);
        assertEquals(good, Files.readString(index));
        assertNoStaging(store.root());
    }

    @Test
    void selectedLaunchPathsAreRevalidatedAndMissingPackagesAreNotIgnored() throws Exception {
        var store = store();
        var installed = store.install(source("source", "sample", "1"), false);
        Path jar = installed.directory().resolve("main.jar");
        Files.delete(jar);
        assertThrows(IOException.class, () -> store.find("sample"));
        Files.createSymbolicLink(jar, Path.of("extension.json"));
        assertThrows(IOException.class, () -> store.find("sample"));
        Files.writeString(store.root().resolve("installed.json"), "{\"sample\":\"missing\"}");
        assertThrows(IOException.class, store::installed);
    }

    @Test
    void boundsMetadataAndRejectsDuplicateJsonKeysAndNonDirectorySources() throws Exception {
        var store = store();
        Path source = source("source", "sample", "1");
        Files.writeString(source.resolve("extension.json"), "x".repeat(1024 * 1024 + 1));
        assertThrows(IOException.class, () -> store.install(source, false));
        Files.writeString(source.resolve("extension.json"), "{\"id\":\"first\",\"id\":\"second\"}");
        assertThrows(IOException.class, () -> store.install(source, false));
        assertThrows(IOException.class, () -> store.install(source.resolve("main.jar"), false));
        assertThrows(IOException.class, () -> store.install(temporary, false));
        assertTrue(store.installed().isEmpty());
        assertNoStaging(store.root());
    }

    @Test
    void boundsPayloadSizeAndDepthBeforeCopyAndRejectsNonDirectoryStoreRoot() throws Exception {
        var store = store();
        Path source = source("source", "sample", "1");
        Path huge = source.resolve("huge.bin");
        try (var channel = java.nio.channels.FileChannel.open(huge, java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(512L * 1024 * 1024);
            channel.write(java.nio.ByteBuffer.wrap(new byte[]{1}));
        }
        assertThrows(IOException.class, () -> store.install(source, false));
        Files.delete(huge);
        Path deep = source;
        for (int i = 0; i < 33; i++) deep = Files.createDirectory(deep.resolve("d"));
        assertThrows(IOException.class, () -> store.install(source, false));
        assertNoStaging(store.root());
        Path notDirectory = Files.writeString(temporary.resolve("not-a-store"), "data");
        assertThrows(IOException.class, new ProcessExtensionStore(notDirectory, mapper)::installed);
    }

    @Test
    void preservesExecutableAttributesWithoutRunningSource() throws Exception {
        if (!Files.getFileStore(temporary).supportsFileAttributeView("posix")) return;
        var store = store();
        Path source = source("source", "sample", "1");
        Path launch = Files.writeString(source.resolve("run"), "THIS MUST NEVER BE EXECUTED");
        Files.setPosixFilePermissions(launch, PosixFilePermissions.fromString("rwxr-x---"));
        Path manifest = source.resolve("extension.json");
        Files.writeString(manifest, Files.readString(manifest).replace("\"kind\":\"jar\",\"path\":\"main.jar\"",
                "\"kind\":\"executable\",\"path\":\"run\""));
        var installed = store.install(source, false);
        assertEquals(Files.getPosixFilePermissions(launch), Files.getPosixFilePermissions(installed.directory().resolve("run")));
        assertEquals(List.of(installed.directory().resolve("run").toString(), "literal arg"),
                installed.manifest().commands().get("sample").command(installed.directory(), null));
    }

    @Test
    void concurrentCollidingInstallsPublishOneCompletePayloadAndValidAtomicIndex() throws Exception {
        var store = store();
        Path a = source("a", "sample", "1");
        Path b = source("b", "sample", "1");
        Files.writeString(b.resolve("main.jar"), "other complete payload");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> first = () -> installWhenReady(store, a, ready, start);
            Callable<Boolean> second = () -> installWhenReady(new ProcessExtensionStore(store.root(), mapper), b, ready, start);
            var fa = pool.submit(first);
            var fb = pool.submit(second);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertNotEquals(fa.get(10, TimeUnit.SECONDS), fb.get(10, TimeUnit.SECONDS));
            var selected = store.find("sample").orElseThrow();
            assertTrue(List.of("trusted local bytes", "other complete payload").contains(Files.readString(selected.directory().resolve("main.jar"))));
            assertEquals("1", mapper.readTree(Files.readString(store.root().resolve("installed.json"))).get("sample").asText());
            assertNoStaging(store.root());
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentCapabilityChangesCannotPublishAnInvalidCatalog() throws Exception {
        var store = store();
        Path provider1 = source("provider1", "provider", "1");
        var providerJson = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(Files.readString(provider1.resolve("extension.json")));
        providerJson.putArray("provides").add("shared-capability");
        Files.writeString(provider1.resolve("extension.json"), providerJson.toString());
        store.install(provider1, false);
        Path provider2 = source("provider2", "provider", "2");
        Path consumer = source("consumer", "consumer", "1");
        var consumerJson = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(Files.readString(consumer.resolve("extension.json")));
        consumerJson.putArray("requires").add("shared-capability");
        Files.writeString(consumer.resolve("extension.json"), consumerJson.toString());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> installWhenReady(store, provider2, ready, start));
            var b = pool.submit(() -> installWhenReady(new ProcessExtensionStore(store.root(), mapper), consumer, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertDoesNotThrow(() -> ai.kompile.cli.main.extensions.InstalledCliExtensions.discover(store, List.of()));
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void actualInstallRequiresHostCapabilitiesAndRejectsReservedCommandsUnderLock() throws Exception {
        var store = store();
        Path consumer = source("consumer", "consumer", "1");
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(Files.readString(consumer.resolve("extension.json")));
        json.putArray("requires").add("host-capability");
        Files.writeString(consumer.resolve("extension.json"), json.toString());
        assertThrows(IOException.class, () -> store.install(consumer, false));
        var host = new ai.kompile.cli.plugin.api.CliExtensionDescriptor("host", "1", 1,
                java.util.Set.of("host-capability"), java.util.Set.of(), List.of(), java.util.Set.of());
        assertThrows(IOException.class, () -> store.install(consumer, false, List.of(host), java.util.Set.of("consumer")));
        store.install(consumer, false, List.of(host), java.util.Set.of("host-command"));
        assertTrue(store.find("consumer").isPresent());
    }

    private boolean installWhenReady(ProcessExtensionStore store, Path source, CountDownLatch ready,
                                     CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Install did not start");
        try {
            store.install(source, true);
            return true;
        } catch (IOException expectedCollision) {
            return false;
        }
    }
}
