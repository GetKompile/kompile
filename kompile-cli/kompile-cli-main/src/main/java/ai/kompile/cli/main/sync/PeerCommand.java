package ai.kompile.cli.main.sync;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.main.coordination.CoordinationStateManager;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/** The same operations as the peer MCP/chat tool. Bare invocation is an interactive wizard. */
@Command(name = "peer", mixinStandardHelpOptions = true,
        description = "Pair encrypted CLI peers, sync selected state, exchange agent messages and request advisory judgements.")
public final class PeerCommand implements Callable<Integer> {
    @Parameters(index = "0", arity = "0..1", description = "init|listen|stop|add|list|remove|status|local_status|agents|sync|send|inbox|ack|judge") String action;
    @Parameters(index = "1", arity = "0..1", description = "Registered peer name") String peer;
    @Option(names = "--address", description = "tls://host:port") String address;
    @Option(names = "--fingerprint", description = "Trusted peer SHA-256 certificate fingerprint") String fingerprint;
    @Option(names = "--token-file", description = "Private local copy of the peer's pairing token; chmod 600") Path tokenFile;
    @Option(names = "--identity", description = "Listener identity directory (default: Kompile home/peer/identity)") Path identity;
    @Option(names = "--bind", defaultValue = "127.0.0.1", description = "Listener bind address; use a reachable interface for other machines") String bind;
    @Option(names = "--port", defaultValue = "7337") int port;
    @Option(names = "--only", split = ",", description = "Explicit sync/listener families; all includes secrets") List<String> components;
    @Option(names = "--user-home", description = "Explicit external harness profile home") Path userHome;
    @Option(names = "--home", description = "Kompile profile root") Path home;
    @Option(names = "--project", description = "Project for local/received agent messages (default: cwd)") Path project;
    @Option(names = "--session", description = "Local coordination session id; required for CLI inbox/ack") String session;
    @Option(names = "--target-session", description = "Remote coordination session id, from agents") String target;
    @Option(names = "--message") String message;
    @Option(names = "--message-id") String messageId;
    @Option(names = "--reply-to") String replyTo;
    @Option(names = "--prompt", description = "Judge task prompt (advisory only)") String prompt;
    @Option(names = "--output-file", description = "UTF-8 agent output to judge; maximum 64KiB") Path outputFile;
    @Option(names = "--direction", defaultValue = "both") String direction;
    @Option(names = "--conflict", defaultValue = "abort", description = "abort|local|remote") String conflict;
    @Option(names = "--apply", description = "Apply the sync (default is preview)") boolean apply;
    @Option(names = "--allow-delete", description = "Explicitly enable sync deletions on this side") boolean allowDelete;
    @Option(names = "--allow-judge", description = "Listener may run local configured judge at peer request (costs provider usage)") boolean allowJudge;
    @Option(names = "--yes", description = "Explicit operator authorization for pairing/listening/applying/secrets/judge usage") boolean yes;

    @Override public Integer call() throws Exception {
        if (action == null) {
            var console = System.console();
            if (console == null) { System.err.println("Use kompile peer --help or specify an action."); return 2; }
            action = console.readLine("Peer action [init/listen/add/list/sync/agents/send/inbox/judge]: ").trim();
            if (List.of("add", "sync", "agents", "send", "judge", "status", "remove").contains(action)) peer = console.readLine("Peer name: ").trim();
            if ("add".equals(action)) {
                address = console.readLine("Peer address (tls://host:port): ").trim();
                fingerprint = console.readLine("Trusted SHA-256 fingerprint: ").trim();
                tokenFile = Path.of(console.readLine("Private token file path: ").trim());
            }
            if ("send".equals(action)) { target = console.readLine("Remote session id: ").trim(); message = console.readLine("Message: "); }
            if ("inbox".equals(action)) session = console.readLine("Local coordination session id: ").trim();
        }
        if (List.of("init", "listen", "stop", "add", "remove", "judge").contains(action) || ("sync".equals(action) && apply)) {
            if (!yes && System.console() != null) yes = "yes".equalsIgnoreCase(System.console().readLine(
                    "Authorize %s? Selected snapshots may contain secrets; only pair trusted devices. Type yes: ", action));
        }
        if (List.of("inbox", "ack").contains(action) && (session == null || session.isBlank())) {
            System.err.println("CLI inbox/ack require --session; the MCP tool is automatically bound to its caller."); return 2;
        }
        var args = SyncProtocol.mapper().createObjectNode().put("action", action).put("confirmed", yes)
                .put("bind", bind).put("port", port).put("dryRun", !apply).put("direction", direction).put("conflict", conflict)
                .put("allowDelete", allowDelete).put("allowJudge", allowJudge);
        if (peer != null) args.put("peer", peer);
        if (address != null) args.put("address", address);
        if (fingerprint != null) args.put("fingerprint", fingerprint);
        if (tokenFile != null) args.put("tokenFile", tokenFile.toString());
        if (identity != null) args.put("identity", identity.toString());
        if (userHome != null) args.put("userHome", userHome.toString());
        if (target != null) args.put("targetSessionId", target);
        if (message != null) args.put("message", message);
        if (messageId != null) args.put("messageId", messageId);
        if (replyTo != null) args.put("replyTo", replyTo);
        if (prompt != null) args.put("prompt", prompt);
        if (outputFile != null) {
            try (var in = Files.newInputStream(outputFile)) {
                byte[] bytes = in.readNBytes(65_537);
                if (bytes.length > 65_536) throw new IllegalArgumentException("Judge output file exceeds 64KiB.");
                args.put("output", new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        if (components != null) { var array = args.putArray("components"); components.forEach(array::add); }
        Path profile = home == null ? KompileHome.homeDirectory().toPath() : home;
        Path wd = project == null ? Path.of(System.getProperty("user.dir")) : project;
        var coordinator = new CoordinationStateManager(wd, session == null ? "peer-cli" : session, SyncProtocol.mapper());
        var result = new PeerService(profile, wd, coordinator).execute(args);
        System.out.println(result.toPrettyString());
        if ("listen".equals(action)) {
            System.out.println("Peer listener is running; Ctrl+C stops it. Export only selected families; close active state writers before sync.");
            try { new java.util.concurrent.CountDownLatch(1).await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return result.path("ok").asBoolean(false) ? 0 : 3;
    }
}
