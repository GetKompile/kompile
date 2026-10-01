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
import java.nio.file.StandardCopyOption;
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
        description = "Synchronize skills, memories, roles, prompts, and provider skills between two kompile homes.",
        subcommands = {SyncPeerCommand.class, SyncServeCommand.class, SyncCommand.Status.class})
public class SyncCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Peer name (omit for the wizard).")
    private String peerName;

    @Option(names = {"--only", "-o"}, split = ",",
            description = "Component subset: skills,memories,roles,prompts,provider-skills,all.")
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

    @Option(names = "--binary", description = "With --local: kompile binary to launch (default: this executable).")
    private Path localBinary;

    @Option(names = "--yes", description = "Skip the final confirmation prompt.")
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
        try {
            components = SyncCatalog.validate(only);
            scope = SyncScope.parse(scopeToken);
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
        var localInventory = SyncInventoryScanner.scan(scopeRoot, components);
        Map<String, String> baseline = store.loadBaseline(peerName, scope);

        try (SyncSession session = new SyncSession(buildTransport(peer), localInventory, baseline,
                localSource(scopeRoot), System.out::println)) {
            String peerHost = session.handshake();
            System.out.println("Connected to " + peerHost + " (" + transportDescription(peer) + ")");

            var remoteInventory = session.remoteInventory(scope.kind(), components);
            SyncPlan plan = new SyncEngine(System.out::println)
                    .plan(localInventory, remoteInventory, baseline, allowDelete);

            printPlan(plan, peerHost);

            if (plan.hasConflicts()) {
                if (forceRemote || forceLocal) {
                    plan = resolveConflicts(plan, forceRemote);
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
                System.out.println("Already in sync.");
                return 0;
            }

            if (!assumeYes && System.console() != null) {
                if (!confirm("Apply this sync plan?")) {
                    System.out.println("Cancelled.");
                    return 0;
                }
            }

            SyncSession.Direction dir = parseDirection(direction);
            apply(session, scopeRoot, plan, dir, allowDelete, baseline, peerName, scope, store);
            return 0;
        }
    }

    private SyncTransport buildTransport(ObjectNode peer) {
        if (localMode) {
            Path home = localHome != null
                    ? localHome
                    : Path.of(System.getProperty("user.home"), ".kompile");
            Path binary = localBinary != null ? localBinary : defaultBinary();
            return SyncTransport.localSubprocess(binary, home, Map.of());
        }
        String ssh = peer.path("ssh").asText();
        int port = peer.path("port").asInt(-1);
        return SyncTransport.ssh(ssh, port, Path.of("ssh"), Map.of());
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

    private static SyncSession.LocalSource localSource(Path scopeRoot) {
        return entry -> Files.readAllBytes(
                entry.toPath(scopeRoot.resolve(SyncCatalog.componentDir(scopeKind(scopeRoot), entry.component()))));
    }

    private static String scopeKind(Path scopeRoot) {
        Path parent = scopeRoot.getParent();
        return parent != null && ".kompile".equals(parent.getFileName().toString())
                ? SyncScope.PROJECT : SyncScope.GLOBAL;
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

    /**
     * Removes conflict rows per the user's force choice. The losing side's
     * edits are intentionally left in place untouched — a forced sync advances
     * the winner's content and records it in the baseline; it never deletes
     * divergent history silently beyond that.
     */
    private static SyncPlan resolveConflicts(SyncPlan original, boolean takeRemote) {
        SyncPlan plan = new SyncPlan();
        for (SyncPlan.Item item : original.items()) {
            if (item.action() == SyncPlan.Action.CONFLICT) {
                if (takeRemote && item.remoteHash() != null) {
                    plan.add(new SyncPlan.Item(item.entry(), SyncPlan.Action.COPY_TO_LOCAL,
                            item.localHash(), item.remoteHash(), item.baselineHash()));
                } else if (!takeRemote && item.localHash() != null) {
                    plan.add(new SyncPlan.Item(item.entry(), SyncPlan.Action.COPY_TO_REMOTE,
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
                              Path scopeRoot,
                              SyncPlan plan,
                              SyncSession.Direction direction,
                              boolean allowDelete,
                              Map<String, String> baseline,
                              String peerName,
                              SyncScope scope,
                              SyncPeerStore store) throws IOException {

        SyncSession.LocalSink localSink = new SyncSession.LocalSink() {
            @Override
            public void write(SyncEntry entry, byte[] bytes) throws IOException {
                Path componentDir = scopeRoot.resolve(
                        SyncCatalog.componentDir(scopeKind(scopeRoot), entry.component()));
                Path target = entry.toPath(componentDir).toAbsolutePath().normalize();
                if (!target.startsWith(componentDir.toAbsolutePath().normalize())) {
                    throw new IOException("path escapes component root: " + entry.relativePath());
                }
                Files.createDirectories(target.getParent());
                Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                Files.write(tmp, bytes);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }

            @Override
            public void delete(SyncEntry entry) throws IOException {
                Path componentDir = scopeRoot.resolve(
                        SyncCatalog.componentDir(scopeKind(scopeRoot), entry.component()));
                Files.deleteIfExists(entry.toPath(componentDir));
            }
        };

        session.apply(localSink, direction, allowDelete);

        Map<String, String> nextBaseline = new HashMap<>(baseline);
        for (SyncPlan.Item item : plan.items()) {
            String key = item.entry().component() + "/" + item.entry().relativePath();
            boolean ran = switch (item.action()) {
                case COPY_TO_REMOTE -> direction != SyncSession.Direction.PULL;
                case DELETE_REMOTE -> direction != SyncSession.Direction.PULL && allowDelete;
                case COPY_TO_LOCAL -> direction != SyncSession.Direction.PUSH;
                case DELETE_LOCAL -> direction != SyncSession.Direction.PUSH && allowDelete;
                default -> false;
            };
            if (!ran) {
                continue;
            }
            switch (item.action()) {
                case COPY_TO_REMOTE -> nextBaseline.put(key, item.localHash());
                case COPY_TO_LOCAL -> nextBaseline.put(key, item.remoteHash());
                case DELETE_REMOTE, DELETE_LOCAL -> nextBaseline.remove(key);
                default -> { }
            }
        }
        Map<String, String> meta = Map.of(
                "syncedAt", java.time.Instant.now().toString(),
                "direction", direction.toString());
        store.saveBaseline(peerName, scope, nextBaseline, meta);
        System.out.println("Sync complete. Baseline updated (" + nextBaseline.size() + " entries).");
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
