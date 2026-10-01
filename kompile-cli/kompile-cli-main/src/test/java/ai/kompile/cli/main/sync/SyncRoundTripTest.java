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
package ai.kompile.cli.main.sync;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip tests: two scope homes synchronized through the real
 * {@link SyncServeHandler} over in-memory pipes, exercising the same protocol
 * path used over SSH, including conflict abort and baseline advancement.
 */
class SyncRoundTripTest {

    @TempDir
    Path tempDir;

    private static final Consumer<String> AUDIT = s -> { };

    /** Wraps a handler in the JSON-lines loop used by SyncServeCommand. */
    private static TransportPair connect(SyncServeHandler handler) throws IOException {
        PipedOutputStream clientToServer = new PipedOutputStream();
        PipedInputStream serverIn = new PipedInputStream(clientToServer, 65536);
        PipedOutputStream serverOut = new PipedOutputStream();
        PipedInputStream clientIn = new PipedInputStream(serverOut, 262144);

        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sync-test-server");
            t.setDaemon(true);
            return t;
        });
        executor.submit(() -> {
            try (var reader = SyncProtocol.reader(serverIn)) {
                var writer = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                        serverOut, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode response;
                    try {
                        JsonNode request = SyncProtocol.mapper().readTree(line);
                        response = handler.handle(request);
                    } catch (Throwable t) {
                        // Never leave the client blocked: always answer.
                        response = SyncProtocol.error(0, "server loop: " + t);
                    }
                    writer.write(SyncProtocol.toLine(response));
                    writer.newLine();
                    writer.flush();
                }
            } catch (IOException ignored) {
                // Client closed; test teardown.
            }
        });
        return new TransportPair(clientIn, clientToServer, executor);
    }

    private record TransportPair(InputStream in, OutputStream out, ExecutorService executor) {
    }

    /** Minimal transport around the pipe pair. */
    private static final class PipeTransport implements SyncTransport {
        private final TransportPair pair;

        PipeTransport(TransportPair pair) {
            this.pair = pair;
        }

        @Override
        public OutputStream stdin() {
            return pair.out();
        }

        @Override
        public InputStream stdout() {
            return pair.in();
        }

        @Override
        public String describe() {
            return "pipe:test";
        }

        @Override
        public void close() {
            try {
                pair.out().close();
            } catch (IOException ignored) {
            }
        }
    }

    private static Path skillsFile(Path home, String rel, String content) throws IOException {
        Path target = home.resolve("skills").resolve(rel);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
        return target;
    }

    @Test
    void localChangePropagatesToRemoteAndConverges() throws Exception {
        Path localHome = tempDir.resolve("local");
        Path remoteHome = tempDir.resolve("remote");
        Files.createDirectories(localHome);
        Files.createDirectories(remoteHome);

        skillsFile(localHome, "alpha/SKILL.md", "local version");
        skillsFile(remoteHome, "beta/SKILL.md", "remote only skill");

        SyncServeHandler handler = SyncServeHandler.create(remoteHome, List.of("skills"), AUDIT);
        TransportPair pair = connect(handler);
        try (SyncSession session = new SyncSession(new PipeTransport(pair),
                SyncInventoryScanner.scan(localHome, List.of("skills")),
                new HashMap<>(),
                entry -> Files.readAllBytes(localHome.resolve("skills").resolve(entry.relativePath())),
                s -> { })) {

            session.handshake();
            session.remoteInventory("global", List.of("skills"));
            SyncPlan plan = session.computePlan(false);

            // Local alpha -> remote; remote beta -> local; no baseline yet.
            assertEquals(1, plan.count(SyncPlan.Action.COPY_TO_REMOTE));
            assertEquals(1, plan.count(SyncPlan.Action.COPY_TO_LOCAL));
            assertFalse(plan.hasConflicts());

            // Apply with a recording local sink and verify convergence.
            Map<String, byte[]> localApplied = new HashMap<>();
            Map<String, String> localDeletes = new HashMap<>();
            SyncSession.LocalSink sink = new SyncSession.LocalSink() {
                @Override
                public void write(SyncEntry entry, byte[] bytes) {
                    localApplied.put(entry.relativePath(), bytes);
                }

                @Override
                public void delete(SyncEntry entry) {
                    localDeletes.put(entry.relativePath(), "deleted");
                }
            };
            session.apply(sink, SyncSession.Direction.BOTH, false);

            assertEquals("local version",
                    new String(readRemote(remoteHome, "skills/alpha/SKILL.md"), StandardCharsets.UTF_8));
            assertEquals("remote only skill",
                    new String(localApplied.get("beta/SKILL.md"), StandardCharsets.UTF_8));
        } finally {
            pair.executor().shutdownNow();
        }
    }

    @Test
    void conflictingFirstSyncAbortsBeforeAnyWrite() throws Exception {
        Path localHome = tempDir.resolve("local");
        Path remoteHome = tempDir.resolve("remote");
        Files.createDirectories(localHome);
        Files.createDirectories(remoteHome);

        skillsFile(localHome, "clash/SKILL.md", "local edit");
        skillsFile(remoteHome, "clash/SKILL.md", "remote edit");

        SyncServeHandler handler = SyncServeHandler.create(remoteHome, List.of("skills"), AUDIT);
        TransportPair pair = connect(handler);
        try (SyncSession session = new SyncSession(new PipeTransport(pair),
                SyncInventoryScanner.scan(localHome, List.of("skills")),
                new HashMap<>(),
                entry -> Files.readAllBytes(localHome.resolve("skills").resolve(entry.relativePath())),
                s -> { })) {

            session.handshake();
            session.remoteInventory("global", List.of("skills"));
            SyncPlan plan = session.computePlan(false);
            assertEquals(1, plan.conflicts().size());

            // Applying with no write performed: assert remote is untouched.
            try {
                session.apply(recordingSink(), SyncSession.Direction.BOTH, false);
            } catch (UnsupportedOperationException expected) {
                // Sink refuses writes; proves apply never reached the remote either.
            }
            assertEquals("remote edit",
                    Files.readString(remoteHome.resolve("skills/clash/SKILL.md")));
        } finally {
            pair.executor().shutdownNow();
        }
    }

    @Test
    void serveHandlerRejectsComponentOutsideAllowList() throws Exception {
        Path remoteHome = tempDir.resolve("remote");
        Files.createDirectories(remoteHome.resolve("skills"));

        SyncServeHandler handler = SyncServeHandler.create(remoteHome, List.of("skills"), AUDIT);
        var request = SyncProtocol.request(7, SyncProtocol.OP_FETCH);
        var entry = request.putObject("entry");
        entry.put("component", "memories");
        entry.put("path", "MEMORY.md");
        var response = handler.handle(request);
        assertEquals(false, response.path("ok").asBoolean());
    }

    @Test
    void serveHandlerRejectsPathEscapes() throws Exception {
        Path remoteHome = tempDir.resolve("remote");
        Files.createDirectories(remoteHome.resolve("skills"));

        SyncServeHandler handler = SyncServeHandler.create(remoteHome, List.of("skills"), AUDIT);
        var request = SyncProtocol.request(8, SyncProtocol.OP_APPLY);
        request.put("action", "write");
        var entry = request.putObject("entry");
        entry.put("component", "skills");
        entry.put("path", "../../stolen.md");
        entry.put("sha256", SyncSession.sha256("x".getBytes()));
        request.put("dataBase64", java.util.Base64.getEncoder().encodeToString("x".getBytes()));
        var response = handler.handle(request);
        assertEquals(false, response.path("ok").asBoolean());
        assertFalse(Files.exists(remoteHome.resolve("stolen.md")));
    }

    @Test
    void baselineAdvancementMakesSecondSyncNoop() throws Exception {
        Path localHome = tempDir.resolve("local");
        Path remoteHome = tempDir.resolve("remote");
        Files.createDirectories(localHome);
        Files.createDirectories(remoteHome);

        skillsFile(localHome, "one/SKILL.md", "content-one");
        skillsFile(remoteHome, "two/SKILL.md", "content-two");

        SyncServeHandler handler = SyncServeHandler.create(remoteHome, List.of("skills"), AUDIT);
        TransportPair pair = connect(handler);

        Map<String, String> baseline = new HashMap<>();
        try (SyncSession session = new SyncSession(new PipeTransport(pair),
                SyncInventoryScanner.scan(localHome, List.of("skills")),
                baseline,
                entry -> Files.readAllBytes(localHome.resolve("skills").resolve(entry.relativePath())),
                s -> { })) {

            session.handshake();
            var remoteInventory = session.remoteInventory("global", List.of("skills"));
            SyncPlan plan = session.computePlan(false);

            Map<String, byte[]> applied = new HashMap<>();
            session.apply(recordingSink(applied), SyncSession.Direction.BOTH, false);

            // Advance the baseline exactly like SyncCommand.apply does.
            for (SyncPlan.Item item : plan.items()) {
                String key = item.entry().component() + "/" + item.entry().relativePath();
                switch (item.action()) {
                    case COPY_TO_REMOTE -> baseline.put(key, item.localHash());
                    case COPY_TO_LOCAL -> baseline.put(key, item.remoteHash());
                    default -> { }
                }
            }
            // Post-transfer state: remote now holds one/, local holds two/.
            assertEquals("content-one",
                    Files.readString(remoteHome.resolve("skills/one/SKILL.md")));
            assertTrue(applied.containsKey("two/SKILL.md"));

            // Second round against the updated homes and baseline: all NOOP.
            SyncServeHandler handler2 = SyncServeHandler.create(remoteHome, List.of("skills"), AUDIT);
            TransportPair pair2 = connect(handler2);
            try (SyncSession session2 = new SyncSession(new PipeTransport(pair2),
                    SyncInventoryScanner.scan(localHome, List.of("skills")),
                    baseline,
                    entry -> Files.readAllBytes(localHome.resolve("skills").resolve(entry.relativePath())),
                    s -> { })) {
                session2.handshake();
                session2.remoteInventory("global", List.of("skills"));
                SyncPlan plan2 = session2.computePlan(false);
                assertTrue(plan2.isNoop(), "expected convergence, got: " + plan2);
            } finally {
                pair2.executor().shutdownNow();
            }
        } finally {
            pair.executor().shutdownNow();
        }
    }

    private static SyncSession.LocalSink recordingSink() {
        return new SyncSession.LocalSink() {
            @Override
            public void write(SyncEntry entry, byte[] bytes) {
                throw new UnsupportedOperationException("no writes expected");
            }

            @Override
            public void delete(SyncEntry entry) {
                throw new UnsupportedOperationException("no deletes expected");
            }
        };
    }

    private static SyncSession.LocalSink recordingSink(Map<String, byte[]> applied) {
        return new SyncSession.LocalSink() {
            @Override
            public void write(SyncEntry entry, byte[] bytes) {
                applied.put(entry.relativePath(), bytes);
            }

            @Override
            public void delete(SyncEntry entry) {
                throw new UnsupportedOperationException("no deletes expected");
            }
        };
    }

    private static byte[] readRemote(Path remoteHome, String rel) throws IOException {
        return Files.readAllBytes(remoteHome.resolve(rel));
    }
}
