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

import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Main entry point: {@code kompile sync [peer] [options]}.
 *
 * <p>Bare invocation launches the wizard (per project convention). With a peer
 * name it runs a noninteractive sync: handshake, inventory exchange, plan,
 * then apply — aborting before any write when conflicts exist unless
 * {@code --force-remote} / {@code --force-local} choose a winner.</p>
 *
 * <pre>
 *   kompile sync                                       # wizard
 *   kompile sync workstation                           # bidirectional sync
 *   kompile sync workstation --dry-run                 # plan only
 *   kompile sync workstation --only skills,memories    # component subset
 *   kompile sync workstation --direction push|pull     # one-way
 *   kompile sync workstation --scope project:/abs/path # project scope
 *   kompile sync workstation --local --home /other/home  # same-machine homes
 *   kompile sync status [peer]                         # saved baseline summary
 * </pre>
 */
@Command(name = "sync",
        mixinStandardHelpOptions = true,
        description = "Synchronize portable content and opt-in Codex/Claude settings and logins between two installs.",
        subcommands = {SyncPeerCommand.class, SyncServeCommand.class, SyncCommand.Status.class})
public class SyncCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Peer name (omit for the wizard).")
    private String peerName;

    @Option(names = {"--only", "-o"}, split = ",",
            description = "Component subset: skills,memories,roles,prompts,provider-skills,harness-settings,harness-credentials,all (all includes secrets).")
    private List<String> only;

    @Option(names = "--direction", defaultValue = "both", description = "push | pull | both (default: both).")
    private String direction;

    @Option(names = "--scope", defaultValue = "global", description = "global or project:<absolute-project-path>.")
    private String scopeToken;

    @Option(names = "--dry-run", description = "Show the transfer plan without applying it.")
    private boolean dryRun;

    @Option(names = "--allow-delete", description = "Propagate deletions (default: withhold).")
    private boolean allowDelete;

    @Option(names = "--force-remote", description = "Resolve conflicts by taking the remote version.")
    private boolean forceRemote;

    @Option(names = "--force-local", description = "Resolve conflicts by pushing the local version.")
    private boolean forceLocal;

    @Option(names = "--local", description = "Sync with another home on this machine (subprocess transport).")
    private boolean localMode;

    @Option(names = "--home", description = "With --local: the other kompile home (or project root).")
    private Path localHome;

    @Option(names = "--user-home", description = "Local external harness user home (default: current profile; explicit values ignore provider home overrides).")
    private Path userHome;

    @Option(names = "--remote-user-home", description = "External harness user home on the peer (required with --local for harness sync).")
    private Path remoteUserHome;

    @Option(names = "--binary", description = "With --local: kompile binary to launch (default: this executable).")
    private Path localBinary;

    @Option(names = "--yes", description = "Authorize the transfer and skip confirmation (including secrets when harness families are selected).")
    private boolean assumeYes;

    @Override
    public Integer call() throws Exception {
        if (SyncCommand.Status.STATUS.equals(peerName)) {
            return new Status().call();
        }

        if (peerName == null || peerName.isBlank()) {
            if (System.console() == null) {
                System.err.println("kompile sync requires a peer name in noninteractive contexts.");
                return 2;
            }
            return SyncWizard.run();
        }

        List<String> components;
        SyncScope scope;
        SyncSession.Direction dir;
        try {
            components = SyncCatalog.validate(only);
            scope = SyncScope.parse(scopeToken);
            dir = parseDirection(direction);
            if (forceLocal && forceRemote) throw new IllegalArgumentException("Choose only one conflict winner.");
            if (SyncCatalog.includesHarness(components)) {
                System.err.println(SyncCatalog.HARNESS_WARNING);
                if (scope.isProject()) throw new IllegalArgumentException("Harness sync requires --scope global.");
                if (localMode && remoteUserHome == null) {
                    throw new IllegalArgumentException("Harness sync with --local requires --remote-user-home to identify the other profile.");
                }
                if (!dryRun && !assumeYes) {
                    if (System.console() == null) {
                        throw new IllegalArgumentException("Harness sync requires explicit authorization: use --yes or preview with --dry-run.");
                    }
                    if (!confirm("Authorize transfer of potentially secret harness files?")) return 0;
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        }

        SyncPeerStore store = new SyncPeerStore(ai.kompile.cli.common.KompileHome.homeDirectory().toPath());
        var peerOpt = store.loadPeer(peerName);
        if (peerOpt.isEmpty()) {
            System.err.println("Unknown peer '" + peerName + "'. Register it first:");
            System.err.println("  kompile sync peer add " + peerName + " --ssh user@host");
            return 2;
        }
        ObjectNode peer = peerOpt.get();

        Path scopeRoot = scopeRoot(scope);
        SyncPaths paths = SyncPaths.configured(scopeRoot, scope.kind(), userHome);
        var localInventory = SyncInventoryScanner.scan(paths, components);
        reportMissingCredentials("Local", localInventory, components);
        Map<String, String> baseline = store.loadBaseline(peerName, scope);

        try (SyncSession session = new SyncSession(buildTransport(peer, components, scope), localInventory, baseline,
                paths::read, System.out::println)) {
            String peerHost = session.handshake();
            System.out.println("Connected to " + peerHost + " (" + transportDescription(peer) + ")");
            if (SyncCatalog.includesHarness(components) && session.remoteMountIdentity().isBlank()) {
                throw new IOException("Peer does not identify its harness mounts; update both Kompile installs.");
            }
            String mountIdentity = SyncSession.sha256((paths.mountIdentity() + "\n" + session.remoteMountIdentity())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            store.validateBaselineMounts(peerName, scope, mountIdentity);

            var remoteInventory = session.remoteInventory(scope.kind(), components);
            reportMissingCredentials("Peer", remoteInventory, components);
            // Validate peer-provided paths before any payload can be read or written.
            for (var entries : remoteInventory.values()) for (SyncEntry entry : entries) paths.resolve(entry);
            SyncPlan plan = session.computePlan(allowDelete);

            printPlan(plan, peerHost);

            if (plan.hasConflicts()) {
                if (forceRemote || forceLocal) {
                    plan = resolveConflicts(plan, forceRemote, allowDelete);
                    if (plan.hasConflicts()) {
                        System.err.println("The selected conflict winner deleted a file; --allow-delete is required. Nothing was transferred.");
                        return 3;
                    }
                    System.out.println(forceRemote
                            ? "Conflicts resolved in favor of the remote side."
                            : "Conflicts resolved in favor of the local side.");
                } else {
                    System.err.println("Conflicts detected; nothing was transferred.");
                    System.err.println("Resolve with --force-local or --force-remote, or run the wizard.");
                    return 3;
                }
            }

            if (dryRun) {
                System.out.println("Dry run: no changes were applied.");
                return 0;
            }
            if (plan.isNoop()) {
                saveBaseline(plan, dir, allowDelete, baseline, peerName, scope, store, mountIdentity);
                System.out.println("Already in sync.");
                return 0;
            }

            if (!assumeYes && System.console() != null) {
                if (!confirm("Apply this sync plan?")) {
                    System.out.println("Cancelled.");
                    return 0;
                }
            }

            apply(session, paths, plan, dir, allowDelete, baseline, peerName, scope, store, mountIdentity);
            return 0;
        }
    }

    SyncTransport buildTransport(ObjectNode peer, List<String> components, SyncScope scope) {
        List<String> options = new java.util.ArrayList<>(List.of("--components", String.join(",", components),
                "--scope", scope.kind()));
        if (remoteUserHome != null) {
            options.add("--user-home");
            options.add(remoteUserHome.toString());
        }
        if (localMode) {
            Path home = localHome != null
                    ? localHome
                    : Path.of(System.getProperty("user.home"), ".kompile");
            Path binary = localBinary != null ? localBinary : defaultBinary();
            if (scope.isProject() && localHome == null) home = Path.of(scope.projectId());
            return SyncTransport.localSubprocess(binary, home, Map.of(), options);
        }
        String ssh = peer.path("ssh").asText();
        int port = peer.path("port").asInt(-1);
        String remoteHome = peer.path("remoteHome").asText("");
        if (!remoteHome.isBlank()) {
            options.add("--home");
            options.add(remoteHome);
        } else if (scope.isProject()) {
            options.add("--home");
            options.add(scope.projectId());
        }
        return SyncTransport.ssh(ssh, port, Path.of("ssh"), Map.of(), options);
    }

    private String transportDescription(ObjectNode peer) {
        return localMode
                ? "local:" + (localHome != null ? localHome : "~/.kompile")
                : "ssh:" + peer.path("ssh").asText("?");
    }

    private static Path defaultBinary() {
        Path self = ai.kompile.utils.NativeImageInfo.getExecutablePathAsPath();
        if (self != null && Files.isRegularFile(self)) {
            return self;
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (dir.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(dir, "kompile");
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        throw new IllegalStateException("Cannot locate a kompile binary for --local sync; pass --binary.");
    }

    static Path scopeRoot(SyncScope scope) {
        if (scope.isProject()) {
            Path projectRoot = Path.of(scope.projectId());
            Path kompileDir = projectRoot.resolve(".kompile");
            if (!Files.isDirectory(kompileDir)) {
                throw new IllegalArgumentException("not a kompile project (missing .kompile): " + projectRoot);
            }
            return kompileDir;
        }
        return ai.kompile.cli.common.KompileHome.homeDirectory().toPath();
    }

    private static SyncSession.Direction parseDirection(String token) {
        switch (token.toLowerCase(java.util.Locale.ROOT)) {
            case "push": return SyncSession.Direction.PUSH;
            case "pull": return SyncSession.Direction.PULL;
            case "both": return SyncSession.Direction.BOTH;
            default:
                throw new IllegalArgumentException("Unknown direction: " + token + " (push|pull|both)");
        }
    }

    /** Select the winning content, or its deletion only with explicit authorization. */
    private static SyncPlan resolveConflicts(SyncPlan original, boolean takeRemote, boolean allowDelete) {
        SyncPlan plan = new SyncPlan();
        for (SyncPlan.Item item : original.items()) {
            if (item.action() == SyncPlan.Action.CONFLICT) {
                if (takeRemote && item.remoteHash() != null) {
                    plan.add(new SyncPlan.Item(item.entry(), SyncPlan.Action.COPY_TO_LOCAL,
                            item.localHash(), item.remoteHash(), item.baselineHash()));
                } else if (!takeRemote && item.localHash() != null) {
                    plan.add(new SyncPlan.Item(item.entry(), SyncPlan.Action.COPY_TO_REMOTE,
                            item.localHash(), item.remoteHash(), item.baselineHash()));
                } else if (allowDelete) {
                    plan.add(new SyncPlan.Item(item.entry(), takeRemote ? SyncPlan.Action.DELETE_LOCAL : SyncPlan.Action.DELETE_REMOTE,
                            item.localHash(), item.remoteHash(), item.baselineHash()));
                } else {
                    plan.add(item);
                }
            } else {
                plan.add(item);
            }
        }
        return plan;
    }

    private static void printPlan(SyncPlan plan, String peerHost) {
        System.out.println();
        System.out.println("Sync plan vs " + peerHost + ":");
        for (SyncPlan.Item item : plan.items()) {
            if (item.action() != SyncPlan.Action.NOOP) {
                System.out.println("  " + item);
            }
        }
        System.out.println("  (" + plan.count(SyncPlan.Action.NOOP) + " unchanged)");
        plan.notes().forEach((component, notes) -> {
            System.out.println("  notes [" + component + "]:");
            notes.forEach(n -> System.out.println("    - " + n));
        });
    }

    /**
     * Applies the plan through the session (protocol side for the remote,
     * direct file IO for the local side) and advances the baseline only for
     * actions that actually ran given the direction and deletion policy.
     */
    private static void apply(SyncSession session,
                              SyncPaths paths,
                              SyncPlan plan,
                              SyncSession.Direction direction,
                              boolean allowDelete,
                              Map<String, String> baseline,
                              String peerName,
                              SyncScope scope,
                              SyncPeerStore store,
                              String mountIdentity) throws IOException {

        SyncSession.LocalSink localSink = new SyncSession.LocalSink() {
            @Override
            public void write(SyncEntry entry, byte[] bytes) throws IOException {
                paths.checkUnchanged(entry, plannedLocalHash(plan, entry));
                paths.write(entry, bytes);
            }

            @Override
            public void delete(SyncEntry entry) throws IOException {
                paths.checkUnchanged(entry, plannedLocalHash(plan, entry));
                paths.delete(entry);
            }
        };

        session.apply(plan, localSink, direction, allowDelete);
        saveBaseline(plan, direction, allowDelete, baseline, peerName, scope, store, mountIdentity);
        System.out.println("Sync complete. Baseline updated.");
    }

    private static void saveBaseline(SyncPlan plan, SyncSession.Direction direction, boolean allowDelete,
                                     Map<String, String> baseline, String peerName, SyncScope scope,
                                     SyncPeerStore store, String mountIdentity) throws IOException {
        Map<String, String> nextBaseline = new HashMap<>(baseline);
        for (SyncPlan.Item item : plan.items()) {
            String key = item.entry().component() + "/" + item.entry().relativePath();
            boolean ran = switch (item.action()) {
                case COPY_TO_REMOTE -> direction != SyncSession.Direction.PULL;
                case DELETE_REMOTE -> direction != SyncSession.Direction.PULL && allowDelete;
                case COPY_TO_LOCAL -> direction != SyncSession.Direction.PUSH;
                case DELETE_LOCAL -> direction != SyncSession.Direction.PUSH && allowDelete;
                case NOOP -> java.util.Objects.equals(item.localHash(), item.remoteHash());
                default -> false;
            };
            if (!ran) {
                continue;
            }
            switch (item.action()) {
                case COPY_TO_REMOTE -> nextBaseline.put(key, item.localHash());
                case COPY_TO_LOCAL -> nextBaseline.put(key, item.remoteHash());
                case DELETE_REMOTE, DELETE_LOCAL -> nextBaseline.remove(key);
                case NOOP -> {
                    if (item.localHash() == null) nextBaseline.remove(key);
                    else nextBaseline.put(key, item.localHash());
                }
                default -> { }
            }
        }
        Map<String, String> meta = Map.of(
                "syncedAt", java.time.Instant.now().toString(),
                "direction", direction.toString(),
                "mountIdentity", mountIdentity);
        store.saveBaseline(peerName, scope, nextBaseline, meta);
    }

    private static void reportMissingCredentials(String side, Map<String, List<SyncEntry>> inventory, List<String> components) {
        if (!components.contains(SyncCatalog.HARNESS_CREDENTIALS)) return;
        var entries = inventory.getOrDefault(SyncCatalog.HARNESS_CREDENTIALS, List.of());
        for (String provider : List.of("codex", "claude")) {
            if (entries.stream().noneMatch(entry -> entry.relativePath().startsWith(provider + "/"))) {
                System.err.println(side + ": no file-based " + provider + " credentials found; keychain/environment logins are not exported.");
            }
        }
    }

    private static String plannedLocalHash(SyncPlan plan, SyncEntry entry) {
        return plan.items().stream().filter(item -> item.entry().packagePath().equals(entry.packagePath()))
                .findFirst().orElseThrow().localHash();
    }

    private static boolean confirm(String prompt) throws IOException {
        System.out.print(prompt + " [y/N] ");
        String line = new java.io.BufferedReader(
                new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        return line != null && line.trim().equalsIgnoreCase("y");
    }

    /** Baseline status subcommand. */
    @Command(name = "status", mixinStandardHelpOptions = true,
            description = "Show the saved sync baseline for peers.")
    public static class Status implements Callable<Integer> {

        static final String STATUS = "status";

        @Parameters(index = "0", arity = "0..1", description = "Peer name (omit for all).")
        private String peerName;

        @Option(names = "--scope", defaultValue = "global", description = "global or project:<path>.")
        private String scopeToken;

        @Override
        public Integer call() throws Exception {
            SyncPeerStore store = new SyncPeerStore(
                    ai.kompile.cli.common.KompileHome.homeDirectory().toPath());
            SyncScope scope = SyncScope.parse(scopeToken);
            List<String> peers = peerName != null ? List.of(peerName) : store.listPeers();
            if (peers.isEmpty()) {
                System.out.println("No peers registered.");
                return 0;
            }
            for (String peer : peers) {
                Map<String, String> baseline = store.loadBaseline(peer, scope);
                System.out.println(peer + " [" + scope + "] baseline: " + baseline.size() + " entries");
            }
            return 0;
        }
    }
}
