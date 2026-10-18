package ai.kompile.cli.main.sync;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.coordination.CoordinationStateManager;
import ai.kompile.cli.main.chat.tools.PeerTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback TLS peers, entirely synthetic isolated profiles; never reads a user's credentials or calls a provider. */
class PeerIntegrationTest {
    @TempDir static Path shared;
    @TempDir Path temp;
    static Path identity;
    static String pin, token;
    final List<CoordinationStateManager> managers = new ArrayList<>();
    @BeforeAll static void identity() throws Exception {
        identity = shared.resolve("identity"); PeerTls.init(identity);
        pin = PeerTls.fingerprint(identity.resolve("identity.p12"), identity.resolve("password"));
        token = PeerTls.readSecret(identity.resolve("token"));
    }
    @AfterEach void shutdown() { managers.forEach(CoordinationStateManager::shutdown); }
    private CoordinationStateManager manager(Path project, String session) throws Exception {
        Files.createDirectories(project);
        var result = new CoordinationStateManager(project, session, JsonUtils.standardMapper(), temp.resolve("system"), Duration.ofMinutes(10), Clock.systemUTC());
        managers.add(result); return result;
    }
    private static void file(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent()); Files.writeString(path, text);
    }
    private ObjectNode args(String action) { return SyncProtocol.mapper().createObjectNode().put("action", action); }
    private void pair(PeerService service, String name, PeerServer server) throws Exception {
        service.execute(args("add").put("peer", name).put("address", "tls://127.0.0.1:" + server.port())
                .put("fingerprint", pin).put("tokenFile", identity.resolve("token").toString()).put("confirmed", true));
    }
    private PeerServer serve(Path home, CoordinationStateManager coordinator, List<String> components) throws Exception {
        return new PeerServer("127.0.0.1", 0, PeerTls.serverContext(identity.resolve("identity.p12"), identity.resolve("password")),
                token, () -> PeerService.handler(new SyncPaths(home, "global", temp.resolve("external")), components, coordinator, false, false));
    }
    @Test void stateFamiliesAreExplicitAllowlistedAndPrivate() throws Exception {
        Path home = temp.resolve("home");
        file(home.resolve("auth.json"), "synthetic-secret");
        file(home.resolve("chat-config.json"), "{}" );
        file(home.resolve("judge-defaults.json"), "{}" );
        file(home.resolve("config/machine.json"), "machine-private");
        file(home.resolve("conversations/session1.txt"), "hello");
        file(home.resolve("conversations/session1/tool-results/private.txt"), "secret");
        file(home.resolve("conversations/sessions.json"), "ownership");
        file(home.resolve("sessions/session1/judgements.jsonl"), "{\"verdict\":\"pass\"}\n");
        file(home.resolve("sessions/session1/state.json"), "runtime");
        var mounts = new SyncPaths(home, "global", temp);
        var families = List.of(SyncCatalog.CREDENTIALS, SyncCatalog.CLI_SETTINGS, SyncCatalog.TRANSCRIPTS, SyncCatalog.JUDGE_RECORDS);
        var inventory = SyncInventoryScanner.scan(mounts, families);
        assertEquals(List.of("auth.json"), inventory.get(SyncCatalog.CREDENTIALS).stream().map(SyncEntry::relativePath).toList());
        assertEquals(List.of("session1.txt"), inventory.get(SyncCatalog.TRANSCRIPTS).stream().map(SyncEntry::relativePath).toList());
        assertEquals(List.of("session1/judgements.jsonl"), inventory.get(SyncCatalog.JUDGE_RECORDS).stream().map(SyncEntry::relativePath).toList());
        assertEquals(List.of("skills", "memories", "roles"), SyncCatalog.validate(null));
        assertTrue(SyncCatalog.validate(List.of("all")).containsAll(families));
        assertThrows(IOException.class, () -> mounts.resolve(SyncEntry.file(SyncCatalog.CREDENTIALS, "password", "", 0, null)));
        assertThrows(IOException.class, () -> mounts.resolve(SyncEntry.file(SyncCatalog.TRANSCRIPTS, "session1/tool-results/private.txt", "", 0, null)));
        assertThrows(IOException.class, () -> mounts.resolve(SyncEntry.file(SyncCatalog.JUDGE_RECORDS, "session1/state.json", "", 0, null)));
        assertThrows(IllegalArgumentException.class, () -> new SyncPaths(home, "project", temp).validateComponents(families));
        var auth = SyncEntry.file(SyncCatalog.CREDENTIALS, "auth.json", "", 0, null);
        mounts.write(auth, "new-synthetic-secret".getBytes(StandardCharsets.UTF_8));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(home.resolve("auth.json")));
        assertFalse(SyncInventoryScanner.scan(home, SyncCatalog.DEFAULT_COMPONENTS).values().stream().flatMap(List::stream).anyMatch(e -> e.relativePath().equals("auth.json")));
    }
    @Test void bidirectionalStateSyncPreviewConsentConflictsAndRechecks() throws Exception {
        Path a = temp.resolve("a"), b = temp.resolve("b");
        var ca = manager(temp.resolve("project-a"), "agent-a");
        var cb = manager(temp.resolve("project-b"), "agent-b");
        file(a.resolve("skills/a.md"), "skill-a"); file(b.resolve("skills/b.md"), "skill-b");
        file(a.resolve("auth.json"), "synthetic-credential-never-return");
        file(a.resolve("chat-config.json"), "{}" );
        file(a.resolve("conversations/from-a.txt"), "transcript-a");
        file(b.resolve("sessions/from-b/judgements.jsonl"), "{\"action\":\"PASS\"}\n");
        var families = List.of("skills", "credentials", "cli-settings", "transcripts", "judge-records");
        try (var server = serve(b, cb, families)) {
            var service = new PeerService(a, temp.resolve("project-a"), ca); pair(service, "b", server);
            var request = args("sync").put("peer", "b"); var components = request.putArray("components"); families.forEach(components::add);
            var preview = service.execute(request);
            assertTrue(preview.path("dryRun").asBoolean()); assertFalse(preview.toString().contains("synthetic-credential"));
            assertFalse(Files.exists(b.resolve("auth.json"))); assertFalse(Files.exists(a.resolve("skills/b.md")));
            request.put("dryRun", false);
            assertThrows(IllegalArgumentException.class, () -> service.execute(request));
            request.put("confirmed", true); assertTrue(service.execute(request).path("ok").asBoolean());
            assertEquals("synthetic-credential-never-return", Files.readString(b.resolve("auth.json")));
            assertEquals("transcript-a", Files.readString(b.resolve("conversations/from-a.txt")));
            assertTrue(Files.exists(a.resolve("sessions/from-b/judgements.jsonl")));
            assertEquals("skill-b", Files.readString(a.resolve("skills/b.md")));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(b.resolve("auth.json")));
            file(a.resolve("skills/a.md"), "local-change"); file(b.resolve("skills/a.md"), "remote-change");
            var conflict = service.execute(request); assertFalse(conflict.path("ok").asBoolean());
            assertEquals("remote-change", Files.readString(b.resolve("skills/a.md")));
            request.put("conflict", "local"); assertTrue(service.execute(request).path("ok").asBoolean());
            assertEquals("local-change", Files.readString(b.resolve("skills/a.md")));
            var apply = SyncProtocol.request(4, "apply").put("action", "write").put("dataBase64", Base64.getEncoder().encodeToString("stale".getBytes(StandardCharsets.UTF_8)));
            apply.putObject("entry").put("component", "skills").put("path", "a.md")
                    .put("sha256", SyncSession.sha256("stale".getBytes(StandardCharsets.UTF_8))).put("size", 5);
            try (var connection = new PeerConnection("tls://127.0.0.1:" + server.port(), pin, token)) {
                connection.request(SyncProtocol.request(1, "hello")); file(b.resolve("skills/a.md"), "after-inventory");
                assertThrows(IOException.class, () -> connection.request(apply));
                assertEquals("after-inventory", Files.readString(b.resolve("skills/a.md")));
            }
        }
    }
    @Test void agentsExchangeDurableMessagesBothWaysAndAcknowledge() throws Exception {
        Path pa = temp.resolve("pa"), pb = temp.resolve("pb"), a = temp.resolve("a"), b = temp.resolve("b");
        var ca = manager(pa, "agent-a"); var cb = manager(pb, "agent-b");
        ca.registerAgent("a", null, "cli-a", 0, ProcessHandle.current().pid());
        cb.registerAgent("b", null, "cli-b", 0, ProcessHandle.current().pid());
        var listenerA = manager(pa, "listener-a"); var listenerB = manager(pb, "listener-b");
        var sa = new PeerService(a, pa, ca); var sb = new PeerService(b, pb, cb);
        try (var serverA = serve(a, listenerA, List.of("skills")); var serverB = serve(b, listenerB, List.of("skills"))) {
            pair(sa, "b", serverB); pair(sb, "a", serverA);
            assertEquals("agent-b", sa.execute(args("agents").put("peer", "b")).path("agents").get(0).path("sessionId").asText());
            var sent = sa.execute(args("send").put("peer", "b").put("targetSessionId", "agent-b").put("message", "request"));
            var received = sb.execute(args("inbox")); assertEquals(1, received.path("messages").size());
            assertTrue(received.toString().contains("untrustedPeerMessage"));
            sb.execute(args("send").put("peer", "a").put("targetSessionId", "agent-a").put("message", "reply").put("replyTo", sent.path("messageId").asText()));
            assertEquals(1, sa.execute(args("inbox")).path("messages").size());
            assertTrue(sb.execute(args("ack").put("messageId", sent.path("messageId").asText())).path("acknowledged").asBoolean());
            assertEquals(0, sb.execute(args("inbox")).path("messages").size());
            assertThrows(IOException.class, () -> sa.execute(args("send").put("peer", "b").put("targetSessionId", "not-active").put("message", "reject")));
        }
    }
    @Test void wrongPinsTokensAndUnexposedFamiliesFailClosed() throws Exception {
        Path home = temp.resolve("home"); file(home.resolve("auth.json"), "private");
        var coordinator = manager(temp.resolve("project"), "listener");
        try (var server = serve(home, coordinator, List.of("skills"))) {
            String address = "tls://127.0.0.1:" + server.port();
            try (var wrongPin = new PeerConnection(address, "0".repeat(64), token)) {
                assertThrows(IOException.class, () -> wrongPin.request(SyncProtocol.request(1, "status")));
            }
            try (var wrongToken = new PeerConnection(address, pin, "0".repeat(64))) {
                assertThrows(IOException.class, () -> wrongToken.request(SyncProtocol.request(1, "status")));
            }
            try (var valid = new PeerConnection(address, pin, token)) {
                assertTrue(valid.request(SyncProtocol.request(1, "status")).path("ok").asBoolean());
                var fetch = SyncProtocol.request(2, "fetch"); fetch.putObject("entry").put("component", "credentials").put("path", "auth.json");
                assertThrows(IOException.class, () -> valid.request(fetch));
                assertThrows(IOException.class, () -> valid.request(SyncProtocol.request(3, "judge").put("prompt", "x").put("output", "y")));
                assertThrows(IOException.class, () -> valid.request(SyncProtocol.request(4, "approve")));
            }
        }
    }
    @Test void advisoryJudgeRoundTripRequiresConsentAndDoesNotExecuteControls() throws Exception {
        var coordinator = manager(temp.resolve("project"), "listener"); var service = new PeerService(temp.resolve("a"), temp, coordinator);
        var calls = new AtomicInteger();
        try (var server = new PeerServer("127.0.0.1", 0, PeerTls.serverContext(identity.resolve("identity.p12"), identity.resolve("password")), token,
                () -> PeerService.handler(new SyncPaths(temp.resolve("b"), "global", temp), List.of("skills"), coordinator, true, false, request -> {
                    calls.incrementAndGet(); return SyncProtocol.ok(request.path("id").asInt()).put("advisory", true).put("score", 5);
                }))) {
            pair(service, "b", server);
            var request = args("judge").put("peer", "b").put("prompt", "task").put("output", "answer");
            assertThrows(IllegalArgumentException.class, () -> service.execute(request)); assertEquals(0, calls.get());
            request.put("confirmed", true); assertTrue(service.execute(request).path("advisory").asBoolean()); assertEquals(1, calls.get());
        }
    }
    @Test void pathsSecretPermissionsAndIdentityReplacementAreRejected() throws Exception {
        for (String address : List.of("http://localhost:3", "tls://user@localhost:3", "tls://localhost:3/", "tls://localhost:3?q=x", "tls://localhost", "tls://localhost:70000"))
            assertThrows(IllegalArgumentException.class, () -> PeerConnection.validateAddress(address));
        assertThrows(IOException.class, () -> PeerTls.init(identity));
        Path publicToken = temp.resolve("token"); file(publicToken, token);
        Files.setPosixFilePermissions(publicToken, PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(IOException.class, () -> PeerTls.readSecret(publicToken));
        Path link = temp.resolve("link"); Files.createSymbolicLink(link, identity.resolve("token"));
        assertThrows(IOException.class, () -> PeerTls.readSecret(link));
        Path home = temp.resolve("home"); Files.createDirectories(home); Files.createSymbolicLink(home.resolve("auth.json"), identity.resolve("token"));
        assertThrows(IOException.class, () -> new SyncPaths(home, "global", temp).read(SyncEntry.file("credentials", "auth.json", "", 0, null)));
    }
    @Test void boundsAndResponseIdsAreEnforced() throws Exception {
        assertThrows(EOFException.class, () -> PeerConnection.readLine(new ByteArrayInputStream("truncated".getBytes(StandardCharsets.UTF_8))));
        var mismatched = new BufferedReader(new StringReader("{\"id\":2,\"ok\":true}\n"));
        assertThrows(IOException.class, () -> SyncProtocol.exchange(mismatched, line -> {}, SyncProtocol.request(1, "hello")));
        assertThrows(IOException.class, () -> SyncProtocol.exchange(new BufferedReader(new StringReader("{\"id\":1}\n")), line -> {}, SyncProtocol.request(1, "hello")));
        var endless = new InputStream() { int left = PeerConnection.MAX_LINE + 1; @Override public int read() { return left-- > 0 ? 'x' : -1; } };
        assertThrows(IOException.class, () -> PeerConnection.limitedInput(endless).transferTo(OutputStream.nullOutputStream()));
    }
    @Test void mcpSchemaAndChildBoundariesAreEnforced() throws Exception {
        var tool = new PeerTool(manager(temp.resolve("project"), "agent"));
        assertTrue(tool.parameterSchema().path("properties").has("components"));
        var context = new ToolContext("session", null, null, temp, null); context.markSupervisedChild();
        assertTrue(tool.execute(args("listen").put("confirmed", true), context).isError());
        assertTrue(tool.execute(args("sync").put("dryRun", false).put("confirmed", true), context).isError());
        assertTrue(tool.execute(args("inbox").put("sessionId", "another-session"), context).isError());
        assertTrue(tool.execute(args("inbox").put("limit", 101), context).isError());
        assertTrue(tool.execute(args("sync").put("dryRun", "false"), context).isError());
        assertTrue(tool.execute(args("sync").put("conflict", "anything"), context).isError());
    }
    @Test void listenerLifecycleAndPairingAreExplicitAndHostOwned() throws Exception {
        Path home = temp.resolve("home"), project = temp.resolve("project");
        var service = new PeerService(home, project, manager(project, "host"));
        var init = args("init"); assertThrows(IllegalArgumentException.class, () -> service.execute(init));
        init.put("confirmed", true); var initialized = service.execute(init);
        assertFalse(initialized.toString().contains(PeerTls.readSecret(home.resolve("peer/identity/token"))));
        var listen = args("listen").put("port", 0).put("confirmed", true);
        listen.putArray("components").add("skills");
        var listening = service.execute(listen);
        try {
            assertTrue(service.execute(args("local_status")).path("listening").asBoolean());
            assertThrows(IOException.class, () -> service.execute(listen));
            var add = args("add").put("peer", "self").put("address", "tls://127.0.0.1:" + listening.path("port").asInt())
                    .put("fingerprint", listening.path("fingerprint").asText()).put("tokenFile", home.resolve("peer/identity/token").toString()).put("confirmed", true);
            service.execute(add); assertThrows(IOException.class, () -> service.execute(add));
            assertTrue(service.execute(args("status").put("peer", "self")).path("ok").asBoolean());
            var override = args("sync").put("peer", "self").put("address", "tls://127.0.0.1:123");
            assertThrows(IllegalArgumentException.class, () -> service.execute(override));
        } finally { assertTrue(service.execute(args("stop").put("confirmed", true)).path("stopped").asBoolean()); }
        assertFalse(service.execute(args("local_status")).path("listening").asBoolean());
    }
    @Test void unauthenticatedRequestsNeverConstructHandlers() throws Exception {
        var constructed = new AtomicInteger();
        try (var server = new PeerServer("127.0.0.1", 0, PeerTls.serverContext(identity.resolve("identity.p12"), identity.resolve("password")), token,
                () -> { constructed.incrementAndGet(); return request -> SyncProtocol.ok(request.path("id").asInt()); })) {
            try (var connection = new PeerConnection("tls://127.0.0.1:" + server.port(), pin, "0".repeat(64))) {
                assertThrows(IOException.class, () -> connection.request(SyncProtocol.request(1, "hello")));
            }
            assertEquals(0, constructed.get());
        }
        assertThrows(IOException.class, () -> PeerConnection.readLine(new ByteArrayInputStream("12345\n".getBytes(StandardCharsets.UTF_8)), 4));
    }
    @Test void deletingAPeerDoesNotRemoveAnotherPeersBaseline() throws Exception {
        var store = new SyncPeerStore(temp);
        store.savePeer("a", args("x")); store.savePeer("a-other", args("y"));
        store.saveBaseline("a", SyncScope.global(), Map.of("skills/a", "one"), Map.of());
        store.saveBaseline("a-other", SyncScope.global(), Map.of("skills/b", "two"), Map.of());
        assertTrue(store.deletePeer("a"));
        assertEquals(Map.of("skills/b", "two"), store.loadBaseline("a-other", SyncScope.global()));
    }
    @Test void peerToolIsDiscoverableInIntegrationsGroup() {
        var tool = new PeerTool(null);
        var dynamic = new ai.kompile.cli.main.chat.tools.DynamicToolManager();
        dynamic.register(tool.id(), tool.description(), tool.parameterSchema());
        assertFalse(dynamic.getActiveToolIds().contains("peer"));
        dynamic.activateGroup("integrations"); assertTrue(dynamic.getActiveToolIds().contains("peer"));
    }
    @Test void cliParsingExposesTheSameActions() {
        var cli = new picocli.CommandLine(new PeerCommand());
        cli.parseArgs("send", "other", "--target-session", "remote-agent", "--message", "hello peer");
        PeerCommand command = (PeerCommand) cli.getCommand();
        assertEquals("send", command.action); assertEquals("other", command.peer); assertEquals("hello peer", command.message);
        assertTrue(new picocli.CommandLine(new ai.kompile.cli.main.MainCommand()).getSubcommands().containsKey("peer"));
    }
}
