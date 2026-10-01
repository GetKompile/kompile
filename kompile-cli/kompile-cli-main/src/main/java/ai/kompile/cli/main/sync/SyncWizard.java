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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Interactive flow for bare {@code kompile sync}: pick or add a peer, choose
 * components/scope/direction, preview the plan, resolve conflicts, apply.
 * Kept deliberately thin; the engine does the real work.
 */
public final class SyncWizard {

    private SyncWizard() {
    }

    /** Runs the wizard; returns a process exit code. */
    public static int run() {
        try {
            var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            var store = new SyncPeerStore(ai.kompile.cli.common.KompileHome.homeDirectory().toPath());

            System.out.println();
            System.out.println("Kompile Sync — skills, memories, roles, and prompts between homes.");
            System.out.println("(Binaries and models are handled by 'kompile update', never by sync.)");
            System.out.println();

            List<String> peers = store.listPeers();
            String peer;
            if (peers.isEmpty()) {
                System.out.println("No peers registered yet.");
                System.out.print("Add one now? name (blank to cancel): ");
                String name = reader.readLine();
                if (name == null || name.isBlank()) {
                    System.out.println("Cancelled.");
                    return 0;
                }
                System.out.print("SSH target (user@host): ");
                String ssh = reader.readLine();
                if (ssh == null || ssh.isBlank()) {
                    System.out.println("Cancelled.");
                    return 0;
                }
                peer = configurePeerViaCommand(name.trim(), ssh.trim());
            } else {
                System.out.println("Peers:");
                for (int i = 0; i < peers.size(); i++) {
                    System.out.println("  " + (i + 1) + ". " + peers.get(i));
                }
                System.out.println("  n. Add a new peer");
                System.out.print("Select peer [1-" + peers.size() + ", n]: ");
                String choice = reader.readLine();
                if (choice == null) {
                    return 0;
                }
                choice = choice.trim();
                if (choice.equalsIgnoreCase("n")) {
                    System.out.print("Peer name: ");
                    String name = reader.readLine();
                    System.out.print("SSH target (user@host): ");
                    String ssh = reader.readLine();
                    if (name == null || ssh == null || name.isBlank() || ssh.isBlank()) {
                        System.out.println("Cancelled.");
                        return 0;
                    }
                    peer = configurePeerViaCommand(name.trim(), ssh.trim());
                } else {
                    int index = Integer.parseInt(choice) - 1;
                    if (index < 0 || index >= peers.size()) {
                        System.out.println("Cancelled.");
                        return 0;
                    }
                    peer = peers.get(index);
                }
            }

            System.out.println();
            System.out.println("Components: " + String.join(", ", SyncCatalog.COMPONENTS));
            System.out.print("Components to sync [default: " + String.join(",", SyncCatalog.DEFAULT_COMPONENTS) + "]: ");
            String componentsLine = reader.readLine();
            List<String> components = SyncCatalog.validate(
                    componentsLine == null || componentsLine.isBlank()
                            ? null : List.of(componentsLine));

            System.out.print("Scope [global / project:<abs-path>] (default: global): ");
            String scopeLine = reader.readLine();
            SyncScope scope = SyncScope.parse(scopeLine == null || scopeLine.isBlank() ? "global" : scopeLine);

            System.out.print("Direction [push/pull/both] (default: both): ");
            String dirLine = reader.readLine();
            String direction = dirLine == null || dirLine.isBlank() ? "both" : dirLine.trim();

            System.out.println();
            System.out.println("Launching sync with peer '" + peer + "'…");
            return new picocli.CommandLine(new SyncCommand()).execute(
                    toArgs(peer, components, scope, direction));
        } catch (IOException e) {
            System.err.println("Wizard error: " + e.getMessage());
            return 1;
        } catch (NumberFormatException e) {
            System.err.println("Invalid selection.");
            return 2;
        }
    }

    /**
     * Saves a peer record directly (wizard-friendly, no reparse of args).
     */
    private static String configurePeerViaCommand(String name, String ssh) throws IOException {
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IOException("Invalid peer name: " + name);
        }
        if (!ssh.matches("[A-Za-z0-9._-]+@[A-Za-z0-9._-]+")) {
            throw new IOException("Invalid SSH target (expected user@host): " + ssh);
        }
        SyncPeerStore store = new SyncPeerStore(ai.kompile.cli.common.KompileHome.homeDirectory().toPath());
        var peer = SyncProtocol.mapper().createObjectNode();
        peer.put("name", name);
        peer.put("ssh", ssh);
        peer.put("addedAt", java.time.Instant.now().toString());
        store.savePeer(name, peer);
        System.out.println("Peer '" + name + "' saved (" + ssh + ").");
        return name;
    }

    private static String[] toArgs(String peer, List<String> components, SyncScope scope, String direction) {
        java.util.List<String> args = new java.util.ArrayList<>();
        args.add(peer);
        args.add("--only");
        args.add(String.join(",", components));
        args.add("--scope");
        args.add(scope.isProject() ? "project:" + scope.projectId() : "global");
        args.add("--direction");
        args.add(direction);
        args.add("--yes");
        return args.toArray(new String[0]);
    }
}
