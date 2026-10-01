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

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Manages sync peer records stored under {@code ~/.kompile/sync/peers/}.
 *
 * <pre>
 *   kompile sync peer add workstation --ssh agibsonccc@192.168.1.50
 *   kompile sync peer list
 *   kompile sync peer remove workstation
 * </pre>
 */
@Command(name = "peer",
        mixinStandardHelpOptions = true,
        description = "Manage sync peer registrations.",
        subcommands = {SyncPeerCommand.Add.class, SyncPeerCommand.ListCmd.class, SyncPeerCommand.Remove.class})
public class SyncPeerCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.err.println("No peer action given. Showing help:\n");
        new picocli.CommandLine(this).usage(System.err);
        return 0;
    }

    @Command(name = "add", mixinStandardHelpOptions = true,
            description = "Register a sync peer reachable over SSH.")
    public static class Add implements Callable<Integer> {

        @Parameters(index = "0", description = "Peer name (letters, digits, dot, dash).")
        private String name;

        @Option(names = {"--ssh", "-s"}, required = true, description = "SSH target, e.g. user@host")
        private String sshTarget;

        @Option(names = "--port", defaultValue = "-1", description = "SSH port (default: ssh default).")
        private int port;

        @Option(names = "--home", description = "Remote kompile home if non-default.")
        private String remoteHome;

        @Override
        public Integer call() throws Exception {
            if (!sshTarget.matches("[A-Za-z0-9._-]+@[A-Za-z0-9._-]+")) {
                System.err.println("Invalid --ssh target (expected user@host): " + sshTarget);
                return 2;
            }
            SyncPeerStore store = new SyncPeerStore(
                    ai.kompile.cli.common.KompileHome.homeDirectory().toPath());
            ObjectNode peer = SyncProtocol.mapper().createObjectNode();
            peer.put("name", name);
            peer.put("ssh", sshTarget);
            if (port > 0) {
                peer.put("port", port);
            }
            if (remoteHome != null && !remoteHome.isBlank()) {
                peer.put("remoteHome", remoteHome);
            }
            peer.put("addedAt", java.time.Instant.now().toString());
            store.savePeer(name, peer);
            System.out.println("Peer '" + name + "' saved (" + sshTarget + ").");
            return 0;
        }
    }

    @Command(name = "list", mixinStandardHelpOptions = true,
            description = "List registered sync peers.")
    public static class ListCmd implements Callable<Integer> {

        @Override
        public Integer call() throws Exception {
            SyncPeerStore store = new SyncPeerStore(
                    ai.kompile.cli.common.KompileHome.homeDirectory().toPath());
            List<String> peers = store.listPeers();
            if (peers.isEmpty()) {
                System.out.println("No peers registered. Add one with: kompile sync peer add <name> --ssh user@host");
                return 0;
            }
            System.out.println("Peers:");
            for (String name : peers) {
                var peer = store.loadPeer(name);
                String detail = peer
                        .map(p -> p.path("ssh").asText("?") + (p.hasNonNull("port") ? ":" + p.get("port").asInt() : ""))
                        .orElse("(unreadable)");
                System.out.println("  " + name + " → " + detail);
            }
            return 0;
        }
    }

    @Command(name = "remove", mixinStandardHelpOptions = true, aliases = {"rm"},
            description = "Remove a peer and its baselines.")
    public static class Remove implements Callable<Integer> {

        @Parameters(index = "0", description = "Peer name to remove.")
        private String name;

        @Override
        public Integer call() throws Exception {
            SyncPeerStore store = new SyncPeerStore(
                    ai.kompile.cli.common.KompileHome.homeDirectory().toPath());
            boolean removed = store.deletePeer(name);
            System.out.println(removed ? "Peer '" + name + "' removed." : "No peer named '" + name + "'.");
            return removed ? 0 : 1;
        }
    }
}
