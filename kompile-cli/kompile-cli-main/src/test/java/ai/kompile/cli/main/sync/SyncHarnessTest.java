package ai.kompile.cli.main.sync;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import picocli.CommandLine;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Only synthetic credentials; all provider homes are isolated under TempDir. */
@ResourceLock("java.lang.System.properties")
@ResourceLock("SYSTEM_OUT")
@ResourceLock("SYSTEM_ERR")
class SyncHarnessTest {
    @TempDir Path temp;
    private static final String SETTINGS = SyncCatalog.HARNESS_SETTINGS;
    private static final String CREDS = SyncCatalog.HARNESS_CREDENTIALS;
    private static final List<String> BOTH = List.of(SETTINGS, CREDS);
    private static final String TOKEN = "synthetic-token-never-log-this";

    private SyncPaths paths(String name) {
        Path profile = temp.resolve(name);
        return new SyncPaths(profile.resolve(".kompile"), "global", profile);
    }
    private static void file(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent()); Files.writeString(path, text);
    }
    private static SyncEntry entry(String component, String path, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return SyncEntry.file(component, path, SyncSession.sha256(bytes), bytes.length, null);
    }
    private static ObjectNode request(String op, String action, String component, String path, String text) {
        var req = SyncProtocol.request(7, op);
        if (action != null) req.put("action", action);
        var e = req.putObject("entry"); e.put("component", component); e.put("path", path);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        e.put("sha256", SyncSession.sha256(bytes)); e.put("size", bytes.length);
        req.put("dataBase64", Base64.getEncoder().encodeToString(bytes)); return req;
    }

    @Test void defaultsNeverSelectHarnesses() {
        assertFalse(SyncCatalog.includesHarness(SyncCatalog.validate(null)));
        assertEquals(BOTH, SyncCatalog.validate(List.of("harness-settings,harness-credentials")));
        assertTrue(SyncCatalog.includesHarness(SyncCatalog.validate(List.of("all"))));
    }
    @Test void scansOnlyExactAllowlistedFilesFromExternalHomes() throws Exception {
        Path home = temp.resolve("profile");
        file(home.resolve(".codex/config.toml"), "model='test'");
        file(home.resolve(".codex/auth.json"), TOKEN);
        file(home.resolve(".codex/sessions/history.jsonl"), "history");
        file(home.resolve(".codex/cache.sqlite"), "cache");
        file(home.resolve(".claude/.credentials.json"), TOKEN);
        file(home.resolve(".claude.json"), "global MCP settings");
        file(home.resolve(".claude/settings.json"), "settings");
        file(home.resolve(".claude/projects/history.jsonl"), "history");
        file(home.resolve(".claude/.credentials.json.backup"), "backup");
        var p = new SyncPaths(home.resolve(".kompile"), "global", home);
        var inventory = SyncInventoryScanner.scan(p, BOTH);
        assertEquals(List.of("claude/global-state.json", "claude/settings.json", "codex/config.toml"),
                inventory.get(SETTINGS).stream().map(SyncEntry::relativePath).toList());
        assertEquals(List.of("claude/.credentials.json", "codex/auth.json"),
                inventory.get(CREDS).stream().map(SyncEntry::relativePath).toList());
        assertFalse(SyncInventoryScanner.scan(p, List.of(SETTINGS)).containsKey(CREDS));
    }
    @Test void supportsCustomProviderRoots() throws Exception {
        Path profile = temp.resolve("profile"), codex = temp.resolve("custom-codex"), claude = temp.resolve("custom-claude");
        file(codex.resolve("auth.json"), TOKEN); file(claude.resolve(".credentials.json"), TOKEN);
        file(claude.resolve(".claude.json"), "global");
        var p = new SyncPaths(profile.resolve(".kompile"), "global", profile, codex, claude);
        assertEquals(2, SyncInventoryScanner.scan(p, List.of(CREDS)).get(CREDS).size());
        assertEquals(claude.resolve(".claude.json"), p.resolve(entry(SETTINGS, "claude/global-state.json", "global")));
    }
    @Test void projectScopeNeverMountsGlobalCredentialsOrDoublePrefixesKompile() throws Exception {
        Path root = temp.resolve("project/.kompile"); file(root.resolve("skills/example.md"), "skill");
        var p = new SyncPaths(root, "project", temp.resolve("profile"));
        assertEquals(1, SyncInventoryScanner.scan(p, List.of("skills")).get("skills").size());
        assertThrows(IllegalArgumentException.class, () -> SyncInventoryScanner.scan(p, BOTH));
    }
    @Test void defaultEndpointCannotFetchOrApplyCredentials() throws Exception {
        var handler = SyncServeHandler.create(paths("remote"), null, null);
        for (String op : List.of(SyncProtocol.OP_FETCH, SyncProtocol.OP_APPLY))
            assertFalse(handler.handle(request(op, "write", CREDS, "codex/auth.json", TOKEN)).path("ok").asBoolean());
    }
    @Test void inventoryHonorsRequestedSubsetAndScope() throws Exception {
        var handler = SyncServeHandler.create(paths("remote"), BOTH, null);
        var req = SyncProtocol.request(2, SyncProtocol.OP_INVENTORY);
        req.put("scope", "global"); req.putArray("components").add(SETTINGS);
        var result = handler.handle(req);
        assertTrue(result.path("ok").asBoolean()); assertTrue(result.path("inventory").has(SETTINGS));
        assertFalse(result.path("inventory").has(CREDS));
        req.put("scope", "project"); assertFalse(handler.handle(req).path("ok").asBoolean());
        req.put("scope", "global"); req.putArray("components").add("models");
        assertFalse(handler.handle(req).path("ok").asBoolean());
    }
    @Test void allowlistAppliesToFetchWriteAndDeleteNotJustScanning() throws Exception {
        var handler = SyncServeHandler.create(paths("remote"), BOTH, null);
        for (String bad : List.of("codex/sessions/private.json", "codex/config.toml", "claude/settings.json", "claude/../auth.json")) {
            for (String action : List.of("write", "delete"))
                assertFalse(handler.handle(request(SyncProtocol.OP_APPLY, action, CREDS, bad, TOKEN)).path("ok").asBoolean());
            assertFalse(handler.handle(request(SyncProtocol.OP_FETCH, null, CREDS, bad, TOKEN)).path("ok").asBoolean());
        }
        assertFalse(handler.handle(request(SyncProtocol.OP_FETCH, null, SETTINGS, "codex/auth.json", TOKEN)).path("ok").asBoolean());
    }
    @Test void writesPrivateFilesAndLeavesNoTemporaryPayloads() throws Exception {
        var p = paths("remote"); var e = entry(CREDS, "codex/auth.json", TOKEN);
        p.write(e, TOKEN.getBytes(StandardCharsets.UTF_8)); assertEquals(TOKEN, Files.readString(p.resolve(e)));
        if (Files.getFileStore(p.resolve(e)).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(p.resolve(e)));
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(p.resolve(e).getParent()));
        }
        try (var entries = Files.list(p.resolve(e).getParent())) { assertEquals(1, entries.count()); }
    }
    @Test void refusesSymlinksIncludingLinksIntroducedAfterInventory() throws Exception {
        var p = paths("remote"); var handler = SyncServeHandler.create(p, BOTH, null);
        Path outside = temp.resolve("outside"); file(outside.resolve("auth.json"), "do not touch");
        Files.createDirectories(temp.resolve("remote")); Files.createSymbolicLink(temp.resolve("remote/.codex"), outside);
        assertThrows(IOException.class, () -> SyncInventoryScanner.scan(p, BOTH));
        for (String action : List.of("write", "delete"))
            assertFalse(handler.handle(request(SyncProtocol.OP_APPLY, action, CREDS, "codex/auth.json", TOKEN)).path("ok").asBoolean());
        assertFalse(handler.handle(request(SyncProtocol.OP_FETCH, null, CREDS, "codex/auth.json", TOKEN)).path("ok").asBoolean());
        assertEquals("do not touch", Files.readString(outside.resolve("auth.json")));
        Files.delete(temp.resolve("remote/.codex")); Files.createDirectories(temp.resolve("remote/.codex"));
        Files.createSymbolicLink(temp.resolve("remote/.codex/auth.json"), outside.resolve("auth.json"));
        assertThrows(IOException.class, () -> p.read(entry(CREDS, "codex/auth.json", TOKEN)));
    }
    @Test void refusesLiveCredentialChangeSinceInventory() throws Exception {
        var p = paths("remote"); Path auth = temp.resolve("remote/.codex/auth.json"); file(auth, "old token");
        var handler = SyncServeHandler.create(p, BOTH, null); file(auth, "fresh login");
        var result = handler.handle(request(SyncProtocol.OP_APPLY, "write", CREDS, "codex/auth.json", TOKEN));
        assertFalse(result.path("ok").asBoolean()); assertEquals("fresh login", Files.readString(auth));
        assertFalse(result.toString().contains(TOKEN));
    }
    @Test void cliRoundTripTransfersBothHomesUpdatesBaselineAndDoesNotLogTokens() throws Exception {
        file(temp.resolve("local/.codex/config.toml"), "model='codex-test'");
        file(temp.resolve("local/.codex/auth.json"), TOKEN); file(temp.resolve("local/.claude.json"), "MCP config");
        file(temp.resolve("remote/.claude/settings.json"), "model='claude-test'");
        file(temp.resolve("remote/.claude/.credentials.json"), TOKEN);
        List<String> audit = new ArrayList<>();
        var result = runCli(commandToRemote(audit), "workstation", "--only", String.join(",", BOTH), "--local",
                "--remote-user-home", temp.resolve("remote").toString(), "--yes");
        assertEquals(0, result.exit(), result.output()); assertTrue(result.output().contains("WARNING:"));
        assertFalse(result.output().contains(TOKEN)); assertFalse(audit.toString().contains(TOKEN));
        assertEquals(TOKEN, Files.readString(temp.resolve("remote/.codex/auth.json")));
        assertEquals(TOKEN, Files.readString(temp.resolve("local/.claude/.credentials.json")));
        assertEquals("MCP config", Files.readString(temp.resolve("remote/.claude.json")));
        var baseline = new SyncPeerStore(temp.resolve("local/.kompile")).loadBaseline("workstation", SyncScope.parse("global"));
        assertEquals(5, baseline.size());
        try (var entries = Files.list(temp.resolve("local/.kompile/sync/state"))) {
            assertFalse(Files.readString(entries.findFirst().orElseThrow()).contains(TOKEN));
        }
    }
    @Test void cliConflictsAbortAndForceRemoteActuallyAppliesChosenPlan() throws Exception {
        file(temp.resolve("local/.codex/auth.json"), "local token"); file(temp.resolve("remote/.codex/auth.json"), "remote token");
        var first = runCli(commandToRemote(new ArrayList<>()), "workstation", "--only", CREDS, "--yes");
        assertEquals(3, first.exit(), first.output()); assertEquals("local token", Files.readString(temp.resolve("local/.codex/auth.json")));
        var forced = runCli(commandToRemote(new ArrayList<>()), "workstation", "--only", CREDS, "--yes", "--force-remote");
        assertEquals(0, forced.exit(), forced.output()); assertEquals("remote token", Files.readString(temp.resolve("local/.codex/auth.json")));
    }
    @Test void dryRunWarnsButNeverWrites() throws Exception {
        file(temp.resolve("local/.codex/auth.json"), TOKEN);
        var result = runCli(commandToRemote(new ArrayList<>()), "workstation", "--only", CREDS, "--dry-run");
        assertEquals(0, result.exit(), result.output()); assertTrue(result.output().contains("WARNING:"));
        assertFalse(result.output().contains(TOKEN)); assertFalse(Files.exists(temp.resolve("remote/.codex/auth.json")));
    }
    @Test void cliRequiresConsentAndExplicitOtherProfileForLocalMode() throws Exception {
        SyncCommand noTransport = new SyncCommand() {
            @Override SyncTransport buildTransport(ObjectNode p, List<String> c, SyncScope s) { fail("Must not start transport"); return null; }
        };
        assertEquals(2, runCli(noTransport, "workstation", "--only", CREDS).exit());
        assertEquals(2, runCli(noTransport, "workstation", "--only", SETTINGS, "--local", "--yes").exit());
        assertEquals(2, runCli(noTransport, "workstation", "--only", CREDS, "--scope", "project:" + temp, "--yes").exit());
    }
    @Test void sshArgumentsAreQuotedNotShellInterpolated() {
        assertEquals("'/home/a b'", SyncTransport.shellQuote("/home/a b"));
        assertEquals("'a'\"'\"'b;$(x)'", SyncTransport.shellQuote("a'b;$(x)"));
        assertThrows(IllegalArgumentException.class, () -> SyncTransport.shellQuote("a\nb"));
    }

    @Test void quotedTildePathsAreExpandedByTheEndpointNotTheShell() {
        Path expected = Path.of(System.getProperty("user.home"));
        assertEquals(expected, SyncPaths.expandHome(Path.of("~")));
        assertEquals(expected.resolve(".kompile"), SyncPaths.expandHome(Path.of("~/.kompile")));
        assertEquals(Path.of("~other/profile"), SyncPaths.expandHome(Path.of("~other/profile")));
    }
    @Test void refusesDirectoryProvidersWithoutSecureHandlesAndClosesThem() {
        boolean[] closed = {false};
        var ordinary = new java.nio.file.DirectoryStream<Path>() {
            @Override public Iterator<Path> iterator() { fail("Must not traverse an insecure stream"); return null; }
            @Override public void close() { closed[0] = true; }
        };
        assertThrows(IOException.class, () -> SyncPaths.requireSecure(ordinary));
        assertTrue(closed[0]);
    }
    @Test void anchoredHandlesDoNotFollowAnAncestorSwappedAfterOpen() throws Exception {
        var p = paths("remote");
        Path provider = temp.resolve("remote/.codex"), outside = temp.resolve("outside");
        file(provider.resolve("auth.json"), TOKEN); file(outside.resolve("auth.json"), "outside");
        try (var parent = SyncPaths.secureParent(provider.resolve("auth.json"))) {
            Files.move(provider, provider.resolveSibling("original-codex"));
            Files.createSymbolicLink(provider, outside);
            try (var channel = parent.newByteChannel(Path.of("auth.json"),
                    Set.of(java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS))) {
                assertEquals(TOKEN, new String(java.nio.channels.Channels.newInputStream(channel).readAllBytes(), StandardCharsets.UTF_8));
            }
            parent.deleteFile(Path.of("auth.json"));
            assertEquals("outside", Files.readString(outside.resolve("auth.json")));
        }
        assertThrows(IOException.class, () -> p.read(entry(CREDS, "codex/auth.json", TOKEN)));
    }
    @Test void identicalFirstSyncEstablishesBaselineForLaterEdits() throws Exception {
        file(temp.resolve("local/.codex/auth.json"), TOKEN); file(temp.resolve("remote/.codex/auth.json"), TOKEN);
        assertEquals(0, runCli(commandToRemote(new ArrayList<>()), "workstation", "--only", CREDS, "--yes").exit());
        file(temp.resolve("local/.codex/auth.json"), "new token");
        var second = runCli(commandToRemote(new ArrayList<>()), "workstation", "--only", CREDS, "--yes");
        assertEquals(0, second.exit(), second.output());
        assertEquals("new token", Files.readString(temp.resolve("remote/.codex/auth.json")));
    }
    @Test void changedRemoteProfileCannotReuseBaselineOrDeleteOtherProfilesLogin() throws Exception {
        file(temp.resolve("local/.codex/auth.json"), TOKEN);
        assertEquals(0, runCli(commandToRemote(new ArrayList<>()), "workstation", "--only", CREDS, "--yes").exit());
        Files.delete(temp.resolve("local/.codex/auth.json"));
        SyncCommand changed = new SyncCommand() {
            @Override SyncTransport buildTransport(ObjectNode p, List<String> c, SyncScope s) {
                try { return new HandlerTransport(SyncServeHandler.create(paths("other-remote"), c, null)); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            }
        };
        var result = runCli(changed, "workstation", "--only", CREDS, "--yes", "--allow-delete");
        assertNotEquals(0, result.exit()); assertTrue(result.output().contains("mounts differ"), result.output());
        assertEquals(TOKEN, Files.readString(temp.resolve("remote/.codex/auth.json")));
        assertFalse(Files.exists(temp.resolve("other-remote/.codex/auth.json")));
    }
    @Test void baselineRejectsChangedLocalAndProviderMounts() throws Exception {
        var store = new SyncPeerStore(temp.resolve("store")); var scope = SyncScope.parse("global");
        var a = paths("profile-a");
        store.saveBaseline("workstation", scope, Map.of(CREDS + "/codex/auth.json", "hash"), Map.of("mountIdentity", a.mountIdentity()));
        assertThrows(IOException.class, () -> store.validateBaselineMounts("workstation", scope, paths("profile-b").mountIdentity()));
        var custom = new SyncPaths(temp.resolve("profile-a/.kompile"), "global", temp.resolve("profile-a"),
                temp.resolve("custom-codex"), temp.resolve("profile-a/.claude"));
        assertThrows(IOException.class, () -> store.validateBaselineMounts("workstation", scope, custom.mountIdentity()));
        store.validateBaselineMounts("workstation", scope, a.mountIdentity());
        store.saveBaseline("workstation", scope, Map.of(CREDS + "/codex/auth.json", "hash"), Map.of());
        assertThrows(IOException.class, () -> store.validateBaselineMounts("workstation", scope, a.mountIdentity()));
    }
    @Test void forcedLocalDeletionRequiresAuthorizationBeforeAnyOtherTransfers() throws Exception {
        file(temp.resolve("local/.codex/auth.json"), TOKEN);
        assertEquals(0, runCli(commandToRemote(new ArrayList<>()), "workstation", "--only", String.join(",", BOTH), "--yes").exit());
        Files.delete(temp.resolve("local/.codex/auth.json")); file(temp.resolve("remote/.codex/auth.json"), "edited remote");
        file(temp.resolve("local/.claude/settings.json"), "independent change");
        var blocked = runCli(commandToRemote(new ArrayList<>()), "workstation", "--only", String.join(",", BOTH), "--yes", "--force-local");
        assertEquals(3, blocked.exit(), blocked.output()); assertTrue(blocked.output().contains("--allow-delete"));
        assertFalse(Files.exists(temp.resolve("remote/.claude/settings.json")));
        assertEquals("edited remote", Files.readString(temp.resolve("remote/.codex/auth.json")));
        var allowed = runCli(commandToRemote(new ArrayList<>()), "workstation", "--only", String.join(",", BOTH), "--yes", "--force-local", "--allow-delete");
        assertEquals(0, allowed.exit(), allowed.output()); assertFalse(Files.exists(temp.resolve("remote/.codex/auth.json")));
        assertEquals("independent change", Files.readString(temp.resolve("remote/.claude/settings.json")));
    }
    @Test void forcedRemoteDeletionIsAppliedWhenAuthorized() throws Exception {
        file(temp.resolve("local/.codex/auth.json"), TOKEN);
        assertEquals(0, runCli(commandToRemote(new ArrayList<>()), "workstation", "--only", CREDS, "--yes").exit());
        Files.delete(temp.resolve("remote/.codex/auth.json")); file(temp.resolve("local/.codex/auth.json"), "edited local");
        assertEquals(3, runCli(commandToRemote(new ArrayList<>()), "workstation", "--only", CREDS, "--yes", "--force-remote").exit());
        var result = runCli(commandToRemote(new ArrayList<>()), "workstation", "--only", CREDS, "--yes", "--force-remote", "--allow-delete");
        assertEquals(0, result.exit(), result.output()); assertFalse(Files.exists(temp.resolve("local/.codex/auth.json")));
    }
    @Test void directionFiltersDoNotAdvanceSkippedCopiesOrDeletions() throws Exception {
        file(temp.resolve("local/.codex/auth.json"), TOKEN); file(temp.resolve("remote/.claude/.credentials.json"), "remote token");
        assertEquals(0, runCli(commandToRemote(new ArrayList<>()), "workstation", "--only", CREDS, "--yes", "--direction", "push").exit());
        assertFalse(Files.exists(temp.resolve("local/.claude/.credentials.json")));
        var store = new SyncPeerStore(temp.resolve("local/.kompile")); var scope = SyncScope.parse("global");
        assertEquals(1, store.loadBaseline("workstation", scope).size());
        assertEquals(0, runCli(commandToRemote(new ArrayList<>()), "workstation", "--only", CREDS, "--yes", "--direction", "pull").exit());
        assertEquals("remote token", Files.readString(temp.resolve("local/.claude/.credentials.json")));
        Files.delete(temp.resolve("local/.codex/auth.json"));
        assertEquals(0, runCli(commandToRemote(new ArrayList<>()), "workstation", "--only", CREDS, "--yes").exit());
        assertEquals(2, store.loadBaseline("workstation", scope).size());
        assertTrue(Files.exists(temp.resolve("remote/.codex/auth.json")));
        assertEquals(0, runCli(commandToRemote(new ArrayList<>()), "workstation", "--only", CREDS, "--yes", "--allow-delete").exit());
        assertFalse(Files.exists(temp.resolve("remote/.codex/auth.json")));
        assertEquals(1, store.loadBaseline("workstation", scope).size());
    }
    @Test void missingFileLoginsAreReportedPerProviderOnBothSides() throws Exception {
        var result = runCli(commandToRemote(new ArrayList<>()), "workstation", "--only", CREDS, "--dry-run");
        assertEquals(0, result.exit(), result.output());
        for (String side : List.of("Local", "Peer")) for (String provider : List.of("codex", "claude"))
            assertTrue(result.output().contains(side + ": no file-based " + provider + " credentials found"), result.output());
    }

    private SyncCommand commandToRemote(List<String> audit) {
        return new SyncCommand() {
            @Override SyncTransport buildTransport(ObjectNode p, List<String> c, SyncScope s) {
                try { return new HandlerTransport(SyncServeHandler.create(paths("remote"), c, audit::add)); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            }
        };
    }
    private record Result(int exit, String output) { }
    private Result runCli(SyncCommand command, String... args) throws Exception {
        String oldHome = System.getProperty("user.home"); PrintStream oldOut = System.out, oldErr = System.err;
        var captured = new ByteArrayOutputStream();
        try (var out = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setProperty("user.home", temp.resolve("local").toString()); System.setOut(out); System.setErr(out);
            var store = new SyncPeerStore(temp.resolve("local/.kompile"));
            store.savePeer("workstation", SyncProtocol.mapper().createObjectNode().put("ssh", "test@localhost"));
            // Explicit --user-home guarantees tests never follow live provider environment overrides.
            List<String> actual = new ArrayList<>(List.of(args)); actual.add("--user-home"); actual.add(temp.resolve("local").toString());
            int exit = new CommandLine(command).execute(actual.toArray(String[]::new));
            return new Result(exit, captured.toString(StandardCharsets.UTF_8));
        } finally { System.setProperty("user.home", oldHome); System.setOut(oldOut); System.setErr(oldErr); }
    }
    /** Real JSON-lines session/endpoint, no helper thread or timing dependency. */
    private static final class HandlerTransport implements SyncTransport {
        private final ArrayDeque<Integer> response = new ArrayDeque<>();
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private final SyncServeHandler handler;
        HandlerTransport(SyncServeHandler handler) { this.handler = handler; }
        @Override public InputStream stdout() {
            return new InputStream() {
                @Override public int read() { return response.isEmpty() ? -1 : response.remove(); }
                @Override public int available() { return response.size(); }
            };
        }
        @Override public OutputStream stdin() {
            return new OutputStream() {
                @Override public void write(int b) { pending.write(b); }
                @Override public void flush() throws IOException {
                    var req = SyncProtocol.mapper().readTree(pending.toByteArray()); pending.reset();
                    byte[] bytes = (SyncProtocol.toLine(handler.handle(req)) + "\n").getBytes(StandardCharsets.UTF_8);
                    for (byte b : bytes) response.add(Byte.toUnsignedInt(b));
                }
            };
        }
        @Override public String describe() { return "test:isolated"; }
        @Override public void close() { handler.close(); }
    }
}
