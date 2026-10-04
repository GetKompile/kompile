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

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Runs the sync endpoint on stdin/stdout for one scope. Invoked over SSH by a
 * remote {@code kompile sync} client (or as a local subprocess in tests):
 *
 * <pre>
 *   kompile sync serve [--home &lt;dir&gt;] [--components skills,memories,roles]
 * </pre>
 *
 * <p>The endpoint speaks JSON-lines, never opens a listening socket, and exits
 * when stdin closes. Writes are confined to selected component mounts,
 * including exact opt-in external harness files.</p>
 */
@Command(name = "serve",
        mixinStandardHelpOptions = true,
        description = "Serve the sync protocol on stdin/stdout for this kompile home (used over SSH).")
public class SyncServeCommand implements Callable<Integer> {

    @Option(names = "--home", description = "Scope root to serve (default: current kompile home).")
    private Path home;

    @Option(names = "--user-home", description = "External harness user home; explicit values ignore provider home environment overrides.")
    private Path userHome;

    @Option(names = "--components", split = ",", description = "Component families to expose (harness settings/credentials are opt-in).")
    private java.util.List<String> components;

    @Option(names = "--scope", defaultValue = "global",
            description = "Scope kind: global (home root) or project (a .kompile directory).")
    private String scope;

    @Override
    public Integer call() throws Exception {
        Path root = home != null ? SyncPaths.expandHome(home).toAbsolutePath().normalize()
                : ai.kompile.cli.common.KompileHome.homeDirectory().toPath();
        if ("project".equalsIgnoreCase(scope) && !root.endsWith(".kompile")) {
            // Allow passing the project root; serve its .kompile directory.
            root = root.resolve(".kompile");
        }
        List<String> allowed = SyncCatalog.validate(components);

        if (SyncCatalog.includesHarness(allowed)) System.err.println(SyncCatalog.HARNESS_WARNING);
        var handler = SyncServeHandler.create(SyncPaths.configured(root, scope, userHome), allowed, line ->
                System.err.println("[kompile-sync] " + line));

        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var writer = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            var request = SyncProtocol.mapper().readTree(line);
            var response = handler.handle(request);
            writer.write(SyncProtocol.toLine(response));
            writer.newLine();
            writer.flush();
        }
        handler.close();
        return 0;
    }
}
